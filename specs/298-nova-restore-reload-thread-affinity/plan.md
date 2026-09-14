# Implementation Plan: Nova restore後のreloadがthread-affinity契約を守って完了する

> Issue: #298
> Spec: [spec.md](./spec.md)
> Status: draft
> Delivery: Phase 1 = investigation（本phaseの成果物はdocs/test計画のみ。production
> code変更なし）→ decision gate（3分岐: fix / no-code resolution / 観測継続）→
> Phase 2 = fix（分岐 (A) の場合のみ実施。gate後確定）

## Current evidence

対象baseline: `origin/main` = `9821dec073`（2026-09-14再確認。前回snapshotの
baseline `f9afd8bfde121932c0c8ed965225d52a84d86ab4` から84 commit進行）。

観測build `d0f40446c7` はこのmainの祖先であるが、**`d0f40446c7..origin/main` の
差分はNova restore/model reload pathの本体を変更している**（下記の#299 fix一式）。
前回planが「差分は当該pathに触れないため観測は現行構造に妥当する」と記述した根拠は
失効している。Phase 1は観測の現行main妥当性から再検証する。

### 記録済みのruntime証拠（事実）

#287 T4コメント（2026-09-12T04:52:56Z、Pixel 9a / Android 17、build `15.Dev.(d0f4044)`）
より:

```text
IllegalStateException: Cache accessed on wrong thread
  full stack: BaseIconCache.assertWorkerThread → LoaderTask.loadWorkspaceImpl、
  coroutine継続frame NovaBackupConverter.kt:167
Can't create handler inside Thread[NovaBackupRestore]
Desktop items loading interrupted
```

- 復元はpref commitとgrid切替（`launcher_5_4_4.db` → `launcher_5_5_5.db`）まで到達。
- 同日同buildの他セッションは成功しており、発生はセッション依存（レース性）。
- 同一セッションで #299 の `CAPTURE_INVALID`（capture側 `IllegalArgumentException`）が
  恒常化。因果関係は未確立。

### baseline間の主要変更: #299 fix（PR #314、`9821dec073` merge）

`f9afd8bf..9821dec` でrestore/reload pathに触れたcommit群
（`0eb3355e0e`..`70284c36b9`、fix/test(299) 12 commit）:

1. restore完了部のreload発行を `reloadAfterRestore` → `forceReload` から
   **generation identity付きcompletion barrier** へ置換（下記code path事実3-4）。
2. loader起動をrestore threadから **`MAIN_EXECUTOR` へdispatch** する構造へ変更。
3. `startLoaderWithoutCallbacks` に **UI thread事前条件assertion** を追加。
4. restore reloadのterminal completionをtokenで観測し、deadline超過時にrestoreを
   失敗させるbarrierを導入。
5. `tests/organizer-instrumentation/app/lawnchair/backup/NovaRestoreCapture*Test`
   harness（`NovaRestoreCaptureTestBase` 等）と
   `NovaRestoreCaptureTestBase` 経由のrestore threading契約testを追加。

#299のassessment（`docs/assessment/issue-299-nova-restore-capture-invalid.md`）は
次を明示的に記録している:

- actual wrong-thread path（`BaseIconCache.assertWorkerThread` 経由の
  `Cache accessed on wrong thread`、`Can't create handler inside
  Thread[NovaBackupRestore]`）は **未再現・未特定**。#298 scopeとして分離維持。
- #298と同一seam（restore/reload窓）に触れる変更は#299 fixに含めない方針。
- 修復点crash（#298相当の「中断」の代替）を注入した実験では、中断の繰り返しで
  invalid行と `CAPTURE_INVALID` が持続し、settle到達で修復されることを直接観測。
  `Desktop items loading interrupted` 行が同一に記録された（原因同一性までは未証明）。
- `performRestore` commit直後から `reloadAfterRestore` 直前まで正規化は行われない
  （定点1/2観測。旧pathでの計測である点に注意）。

### 現在のmainで確認したcode path（2026-09-14に実読、事実。行番号は `9821dec073`）

1. **Restore threadはLooperを持たない（不変）。**
   [NovaBackupConverter.kt](../../lawnchair/src/app/lawnchair/backup/NovaBackupConverter.kt)
   の `restoreDispatcher`（:91-92）は `Executors.newSingleThreadExecutor` の専用thread
   `NovaBackupRestore`（#168導入）。`convertAndRestore`（:167）がこのthreadで
   実行される。`Thread::asCoroutineDispatcher` でありLooperは作られない。

2. **restore完了部の構造（#58 / #168部分は不変、reload発行は#299で置換）。**
   `convertAndRestore` は1個の `BACKUP_RESTORE` lease内で:
   `RestoreDbTask.prepareForRawFileRestore`（`model.quiesceForRestore()` +
   `closeActiveHelperForRestore()`）→ staging DB作成 → grid prefs書き込み +
   `applyConvertedGrid`（Main thread hop、#168）→ `restored.db` copy →
   `RestoreDbTask.performRestore`（RESTORE leaseに再入）→ **reload完了部（下記3）** →
   `pinImportedDeepShortcuts`。

3. **reload発行はbarrier経路が本線（NovaBackupConverter.kt:243-253）。**
   `LauncherAppState.INSTANCE.getNoCreate()` がnon-nullなら
   `RestoreReloadBarrier(app, 15s).dispatch()`。dispatchはrestore thread上で
   `model.beginRestoreReload()` + `model.dispatchRestoreReload(requestId, completed,
   cancelled)` を呼ぶ。nullの場合のみ旧来のbaseline fallback
   `RestoreDbTask.reloadAfterRestore(context)`（`RestoreDbTask.java:273-291`、
   no-op → `forceReload`）が走る。`awaitCompletion()` はlease `use` blockの
   **後** で呼ばれ、deadlineはlease解放後に起算される（`70284c36b9`）。

4. **`dispatchRestoreReload`（LauncherModel.java:610前後）のthread構造。**
   restore thread上で `mLock` 下の `stopLoader()` + token swap + `mModelLoaded =
   false` を実行し、supersedeされたtokenの `cancelled` を同期的にrunした後、
   loader起動本体を `MAIN_EXECUTOR.execute` で投稿する。Main thread上で
   `hasCallbacks()` 分岐により `startLoader()` または `startLoaderWithoutCallbacks()`
   （UI thread事前条件assertion付き、`LauncherModel.java:405-415`）を呼ぶ。
   callbacks消失によるnever-started pathはtokenをterminalizeし `cancelled` をrun。

5. **LoaderTaskは常にMODEL_EXECUTORで走る（不変）。** `startLoader` は
   `bindDirectly` を除き `MODEL_EXECUTOR.post(mLoaderTask)`。`MODEL_EXECUTOR` は
   LooperExecutorでlooper thread `launcher-loader`
   （[Executors.java](../../src/com/android/launcher3/util/Executors.java):96-97）。
   #299でLoaderTaskは `notifyRestoreReloadComplete` flagを追加され、transaction
   commit後の `MODEL_EXECUTOR.post(mLauncherBinder::notifyOrganizerReloadComplete)`
   がrestore tokenの `completeRestoreReload` も解決する
   （[LoaderTask.java](../../src/com/android/launcher3/model/LoaderTask.java):440-455）。

6. **IconCacheのworker threadはMODEL_EXECUTORと同一looper（不変）。**
   [IconCache.java](../../src/com/android/launcher3/icons/IconCache.java):122 が
   `super(context, dbFileName, MODEL_EXECUTOR.getLooper(), ...)`。iconloaderlib
   `BaseIconCache.assertWorkerThread`（submodule
   `platform_frameworks_libs_systemui`、`BaseIconCache.java:814-818`）は
   `Looper.myLooper() != mBgLooper` であれば **無条件に** throwする
   （:432/:521/:570のentry point）。

7. **LoaderTask内の1 item例外はcatchされてlogだけ残る（不変）。**
   [WorkspaceItemProcessor.kt](../../src/com/android/launcher3/model/WorkspaceItemProcessor.kt)
   :109-114 の `catch (e: Exception)` → `Log.e(TAG, "Desktop items loading
   interrupted", e)`。T4の3つ目のsignatureと一致。

8. **LoaderTaskはrestore leaseの後ろでdeferする（不変）。**
   [LoaderTask.java](../../src/com/android/launcher3/model/LoaderTask.java):248-259
   の `coordinator.runOrDefer(OwnerKind.MODEL_WRITER, ...)`（#14）。

9. **launcher側assertはstudio build限定、iconloaderlib側は無条件（不変）。**
   [Preconditions.java](../../src/com/android/launcher3/util/Preconditions.java):36-40
   vs 上記6。

10. **既存のgeneration機構（不変+#299追加）。** #152 organizer reload token、
    #58 `quiesceForRestore`（LauncherModel.java:331-337）、#299
    `RestoreReloadRequest` token（generation identity + terminal outcome、
    LauncherModel.java:584-700付近）。

### 静的読解で確定しないこと（未確定 — Phase 2でfix architectureを決めない根拠）

- restore threadから `BaseIconCache` のassert付きentry pointへの直接到達点は、
  現行 `convertAndRestore` の呼び出しgraph（performRestore/barrier/
  pinImportedDeepShortcuts）にも見つからなかった（上記1-5の実読）。
  `forceReload` がrestore pathから外れたため、旧観測stackの
  `NovaBackupConverter.kt:167` 継続frameが現行構造のどれに対応するかは確定していない。
- `Can't create handler inside Thread[NovaBackupRestore]` を生む無Looper Handler生成
  箇所は引き続き未特定（NovaBackupConverter / RestoreDbTask / ModelDbController /
  LayoutWriteCoordinatorには該当なし。restore窓内でlazy構築されるsingleton候補は
  残る。barrierの `completed`/`cancelled` callback本体は現行コードでは
  `AtomicReference` 更新 + `CountDownLatch.countDown()` のみであり、Handler生成の
  源泉からは静的に除外できる。ただしcallbackの実行自体がrestore thread上の
  同期処理であること、およびその前後のrestore thread同期処理は引き続き調査対象）。
- T4のfull stackはcoroutine継続frameを含むmerged stackであり、例外の発生threadと
  coroutine frameの関係はstack単独では確定しない。
- #299再構成により3 signatureの発現条件自体が変化した可能性がある（barrierが
  詰まったreloadをrestore失敗へ変えるため、`Desktop items loading interrupted` の
  みが観測されるcaseや、barrier timeout throwが顕在化するcaseが考えられる）。
  よって「どの呼び出しがどのthreadからどの契約付きAPIへ届いたか」の正確なchain、
  3 signatureの相互関係、および現行mainでの実在性は、runtime reproductionによる
  特定（Phase 1 I-1/I-2）が必要。これはTA-AC-01そのものである。

## Design

### Phase 1: Investigation（root cause確定が最初の成果物）

fix architectureはPhase 1のdecision gateを通過するまで決定しない。Phase 1の
成果物は `docs/assessment/issue-298-<slug>.md` と本planの更新のみであり、
production code・既存testの変更は行わない（docs-only PRとして出す）。

- **I-1: 現行mainでのemulator再現の確立。** API 36.1 emulator + debug build
  （building guide準拠、baseline `9821dec073` 以降のhead）でNova restoreを
  繰り返し実行する。T4はセッション依存のため、大きなbackup・連続cycle・loader
  競合（restore中のlauncher起動等）を意図した手順で窓を広げる。復元実行中の
  full logcatを収集し（`NovaBackupConverter`、`RestoreDbTask`、`LauncherModel`、
  `LoaderTask`、`BaseIconCache`、`RestoreReloadBarrier` のsignature）、3 signature
  に加えてbarrier由来の新signature（`Restore reload did not complete within...`、
  `Restore reload attempt N was cancelled`、restore失敗surface）の出現条件を絞る。
  再現不能の場合も、試行手順・回数・収集logを証跡として記録する
  （「再現なし」もPhase 1の正当な成果であり、その場合は観測の妥当性限界と
  監視継続の要否をdecision gateで判定する）。
- **I-2: call chainの特定（TA-AC-01）。** 再現時のfull stack（assertion throw点
  から根まで）を取得し、各signatureについて「発生thread」「契約付きAPI」
  「到達経路」をコードに対応させて記録する。`Cache accessed on wrong thread` は
  `BaseIconCache.assertWorkerThread` throw点の `mBgLooper` と実際の
  `Looper.myLooper()` の値から、呼び出しthreadを確定する。
  `Can't create handler inside Thread[NovaBackupRestore]` はHandler生成箇所の
  特定（restore窓内lazy singleton候補の列挙と潰し込み）を含む。
- **I-3: 中断時の残存状態の観測。** #299 assessmentの修復点中断観測（invalid行
  持続、settle後修復、定点1/2）を出発点に、actual wrong-thread path（または
  再現不能な場合はbarrier timeout/cancelled path）での残存状態を観測し、
  TA-AC-04と#299切り分けの入力とする。process再起動・再restore跨ぎの追跡は
  #299では未実施であり、本Issueで実施する。
- **Decision gate（Phase 1 → 分岐確定）:** I-2の結果をinputに、次の3分岐のいずれかを
  `docs/assessment/issue-298-<slug>.md` へ判定記録として残す。調査記録には証跡
  （対象build SHA、取得log、確認日）を伴わせる。
  - **(A) chain確定 → Phase 2 fix。** 3 signatureそれぞれの発生thread・契約付きAPI・
    到達経路が証跡付きで確定した場合。Phase 2のseam選択（下記候補）とtest戦略を
    確定する。
  - **(B) 障害窓の消滅確定 → no-code resolution / close判定。** 現行構造で障害窓が
    取り除かれたことを十分な証拠で確定した場合。spec TA-AC-01の「障害窓が取り除か
    れた/変化した」記録pathに対応する。判定要件: T4相当の窓拡大手順を十分な回数
    実行して3 signatureとbarrier由来signatureが不在であることに加え、**#299再構成の
    どの変更が観測された契約違反経路を構造的に除去したかを静的・動的証拠で説明
    できること**。単なる数回の再現なしは判定材料にならない（#299が#298を事実上
    消したのか、残存レースがあるのかを区別できないため）。Issue closeの可否を
    この記録で判定する。
  - **(C) 再現不能・消滅も証明不能 → production fixは行わない。** Issueを開いた
    まま維持し、追加観測の計画（実機セッションのlogcat収集手順、監視継続の要否、
    再開条件）を調査記録へ残す。spec受入条件（TA-AC-01〜06）は未達のまま引き継ぐ。
  修正が変更困難なthreading所有権の判断を含む場合はADRの3条件を再確認し、
  必要ならADRを作成する。

### Phase 2: Fix（decision gate分岐 (A) の場合のみ実施。以下は候補でありgate後確定）

#### Modules and interfaces（候補 — decision gate後確定）

- 修正が守るべき既存contract:
  - #58: 1個の `BACKUP_RESTORE` leaseがquiesce→commit→correlated reloadを囲む。
    reloadのthread handoffはこのleaseとbarrierの内側で行い、leaseのthread束縛
    （re-entryはthread identityベース、#168コメント記載）を壊さない。
  - #168: grid適用のauthoritative性（`applyConvertedGrid` のMain hop、durability
    barrier）を変更しない。
  - #14: loaderはleaseの後ろでdeferし、MODEL_EXECUTORをblockさせない。
  - #152 / #299: reload generation tokenによるsupersession。organizer由来reloadと
    restore由来reload、およびbarrier再dispatchが競合しないこと。barrierの
    成功条件（successful completionのみ）を弱めない。
  - #299 assessmentの決定: 正規化/拒絶は採用しない。restore時bind/dropの
    「restore threadからのwidget bind」は#298 hazardとして記録済みであり、
    Phase 2でbindをrestore thread上へ持ち込む設計は採らない。
- 候補seam（I-2の結果により選択・絞り込み）:
  - `dispatchRestoreReload` のrestore thread側区間（`mLock` 下の `stopLoader` +
    token swap）と、その前後のrestore thread上の同期処理。barrierが渡す
    `completed`/`cancelled` callback本体は現行コードでは `AtomicReference` 更新 +
    `CountDownLatch.countDown()` のみでHandler/looper依存APIへ到達しないため
    （静的に除外済み）、callback本体ではなくそれを取り巻くrestore thread同期処理
    （`stopLoader` を含むmLock区間、supersede時の同時実行を含む）をHandler
    signatureの調査対象とする。
  - restore窓内でrestore thread上に構築されるHandler依存component（lazy
    singleton候補）の構築Main/model thread側への移動、またはrestore threadでの
    lazy構築の禁止。
  - LoaderTask側との競合が本質の場合、restore窓のloader遮断/quiesceの範囲見直し
    （#58の `quiesceForRestore` の適用タイミングと `runOrDefer` の相互作用）。
    #299 barrierとの相互作用（barrier待ちとquiesce解放の順序）を含む。
- 変えないinterface: `LayoutWriteCoordinator` のlease契約、`ModelDbController` の
  helper lifecycle契約、diagnostics契約、#299 barrierの外向き契約
  （成功条件・deadline・restore失敗surface）。

#### Data flow（現行構造、2026-09-14実読に基づく）

restore thread（NO Looper）: convertAndRestore → [lease] → staging/commit
→ performRestore（DB transaction）→ `RestoreReloadBarrier.dispatch()`
→ `beginRestoreReload` + `dispatchRestoreReload`（mLock下 stopLoader + token swap、
superseded `cancelled` を同時実行）→ [MAIN_EXECUTOR] `startLoader()` /
`startLoaderWithoutCallbacks()`（UI thread assert）→ [lease解放] →
restore thread: `awaitCompletion()`（deadlineはlease解放後に起算）
→ [MODEL_EXECUTOR] LoaderTask（lease defer後 runInternal）→ DB読み出し +
icon cache（mBgLooper == MODEL_EXECUTOR looper）→ commit後
`MODEL_EXECUTOR.post` → `completeRestoreReload(token)` → latch解放
→ restore thread: barrier成立 → restore成功return。
違反はこの図のどの境界で、どの実行主体が契約外に達したかの確定がI-2の成果。
baseline fallback（model非活性）: `reloadAfterRestore` no-op → barrier無しで
restore完了。

## Change set

| Area | Intended change | Why here |
|---|---|---|
| `docs/assessment/issue-298-<slug>.md`（新規、Phase 1） | I-1/I-2/I-3の調査記録（証跡、確定chain、残存状態観測、再現なしの場合はその証跡） | 調査証跡の正本置き場（#299 assessmentと同一慣行） |
| `lawnchair/src/app/lawnchair/backup/NovaBackupConverter.kt`（Phase 2、gate後確定） | decision gate後確定。barrier dispatch区間・callback実行threadが候補 | restore completion pathの所有者 |
| `src/com/android/launcher3/LauncherModel.java`（Phase 2、gate後確定） | decision gate後確定。`dispatchRestoreReload` のthread構造が候補 | Launcher3/AOSP由来のためbridge最小変更 + 近傍文書にIssue番号と理由を記録 |
| `src/com/android/launcher3/provider/RestoreDbTask.java`（Phase 2、gate後確定、不要の可能性あり） | decision gate後確定（baseline fallback経路のみ現存） | 同上 |
| `tests/organizer-instrumentation/app/lawnchair/backup/NovaRestoreCaptureTestBase.kt` 系（Phase 2） | restore→reload完了のthreading観点拡張（TA-AC-06）。#299 harnessの拡張か、#298専用scenarioの追加かはgate後確定 | #299が確立したNova restore instrumentation seamが実在 |
| `tests/organizer-instrumentation/app/lawnchair/backup/NovaRestoreGridApplicationTest.kt`（Phase 2） | restore→workspace使用可能の観点拡張（TA-AC-04） | #168のNova restore instrumentation seam |
| `tests/src/com/android/launcher3/provider/RestoreDbTaskTest.java` または新規JVM test（Phase 2） | seam単位の回帰（decision gate後確定） | 既存restore系JVMテスト表面 |

実際の変更file集合はI-2の結果で確定し、本表を更新する。Phase 1のPRは
docs-only（本表の1行目のみ）である。

## Migration and recovery

- schema/migration変更なし。rollbackは単純revertで復帰する。
- 修正がrestore pathの振る舞いを変える場合でも、restore失敗時の既存fail-closed
  （#167）とlease解放（#58の `use` block）、#299 barrierのrestore失敗surfaceは
  保持する。reloadが完了しない場合もrestore threadがleaseを握り続けないこと
  （既存 `use` の成功条件を弱めない）を検証で確認する。

## Verification

| Acceptance criterion | Automated/manual evidence | Command or environment |
|---|---|---|
| TA-AC-01 | 調査記録（docs/assessment）+ stack証跡 | Phase 1 I-1/I-2で収集 |
| TA-AC-02 | 修正diffの構造的確認 + runtime evidence | Phase 2、I-2後のPR review |
| TA-AC-03 | 繰り返しrestore cycleのlogcat検証（3 signature不在 + barrier由来signature不在） | emulator `nunu_qpr2_api36_1` 相当 + 実機（可能なら） |
| TA-AC-04 | barrier完了後のrestore→workspace表示の確認、`NovaRestoreGridApplicationTest` / `NovaRestoreCapture*Test` 系拡張の実行 | organizer-instrumentation lane |
| TA-AC-05 | diff review（assertion非弱化）+ JVM/instrumentation test | 通常test surface |
| TA-AC-06 | 追加regression + device evidence記録 | emulator/実機検証記録 |

含めるべき観点: threadingはfailure injection（競合窓を意図した繰り返し実行）でのみ
有意な検証になる。単発の成功はTA-AC-03の証拠にならない。TA-AC-04の観測は
#299と同一のbarrier定義（completion barrier到達後の判定）を用いる。issue本文が
device/emulator検証を必須としている点、およびrestore系PRの高リスク要件
（`risk: layout-data` 等のlabel付与時は `final-status` + 独立audit記録）を
PR時に確認する。Phase 1のPRはdocs-onlyのため低リスクだが、調査証跡の完全性を
review対象とする。

## Documentation updates

- [ ] spec status/history（Phase 1 decision gate通過後、受入条件の確定を反映）
- [ ] CONTEXT.md（Domain language の用語を承認時に反映するか検討）
- [ ] DESIGN.md（threading所有権のsystem不変条件に追加が必要になった場合）
- [ ] ADR（3条件を満たすthreading所有権の判断になった場合）
- [ ] Launcher3由来fileへの変更時、近傍文書にIssue番号と理由を記録

## Execution checklist

### Phase 1

- [ ] I-1: 現行main（`9821dec073` 以降）のemulatorで障害を再現し、3 signature +
      barrier由来signatureのlog/stackを取得する。再現不能な場合は試行手順・回数・
      logを証跡として記録する。
- [ ] I-2: call chainを確定し、`docs/assessment/issue-298-<slug>.md` へ記録する
      （TA-AC-01）。観測build構造と現行main構造の対応も記録する。
- [ ] I-3: 中断時の残存状態を観測・記録する（#299 assessmentの観測を入力に、
      actual path / barrier pathで追試、process再起動跨ぎを含む）。
- [ ] Decision gate: I-2の結果を (A) chain確定 → Phase 2 fix / (B) 障害窓消滅確定 →
      no-code resolution・close判定 / (C) 再現不能・消滅も証明不能 → 観測継続の
      3分岐で判定し、`docs/assessment/issue-298-<slug>.md` へ記録する。
      (A) の場合はspec statusと本planのPhase 2 Design/Change setを確定結果で更新する。
- [ ] Phase 1 PR（docs-only）を出し、調査記録をreviewに付す。

### Phase 2（decision gate通過後）

- [ ] 失敗を再現するテストを先に追加する（修正前に失敗することを確認）。
- [ ] Minimal implementation、rollback/recovery確認、full verification、PR evidence記録。
