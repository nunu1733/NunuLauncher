# Implementation Plan: Nova restore後のreloadがthread-affinity契約を守って完了する

> Issue: #298
> Spec: [spec.md](./spec.md)
> Status: draft

## Current evidence

対象baseline: `origin/main` = `f9afd8bfde121932c0c8ed965225d52a84d86ab4`。
観測build `d0f40446c7` はこのmainの祖先であり、`d0f40446c7..origin/main` の差分
（#235/#292 organizer系）はNova restore / model reload pathに触れない。

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

### 現在のmainで確認したcode path（2026-09-12に実読、事実）

1. **Restore threadはLooperを持たない。**
   [NovaBackupConverter.kt](../../lawnchair/src/app/lawnchair/backup/NovaBackupConverter.kt)
   の `restoreDispatcher`（:82-84）は `Executors.newSingleThreadExecutor` の専用thread
   `NovaBackupRestore`（#168導入）。`convertAndRestore`（:158-229）がこのthreadで
   実行される。`Thread::asCoroutineDispatcher` でありLooperは作られない。

2. **restore完了部の構造（#58 / #168確立済み）。** `convertAndRestore` は1個の
   `BACKUP_RESTORE` lease内で: `RestoreDbTask.prepareForRawFileRestore`
   （[RestoreDbTask.java](../../src/com/android/launcher3/provider/RestoreDbTask.java)
   :249-265 — `model.quiesceForRestore()` + `closeActiveHelperForRestore()`）→
   staging DB作成 → grid prefs書き込み + `applyConvertedGrid`（Main thread hop、
   NovaBackupConverter.kt:266-270、#168）→ `restored.db` copy →
   `RestoreDbTask.performRestore`（RestoreDbTask.java:205-230、RESTORE leaseに再入）→
   `RestoreDbTask.reloadAfterRestore`（:273-283）→ `pinImportedDeepShortcuts`。

3. **reload要求はrestore threadから発行される。**
   `reloadAfterRestore(context)`（:273-276）→ `LauncherAppState.INSTANCE.getNoCreate()`
   がnon-nullなら `app.getModel().forceReload()`（:283-287）。
   `LauncherModel.forceReload()`（[LauncherModel.java](../../src/com/android/launcher3/LauncherModel.java)
   :314-325）は `mLock` 下で `stopLoader()` + `mModelLoaded = false` とし、
   callbacksが存在すれば `startLoader()` を呼ぶ。この一連の呼び出し自体は
   restore thread上で実行される。

4. **LoaderTaskは常にMODEL_EXECUTORで走る。** `startLoader` は `bindDirectly`
   （既にmodel loaded）の場合を除き `MODEL_EXECUTOR.post(mLoaderTask)` する
   （LauncherModel.java:465-467）。`MODEL_EXECUTOR` は `LooperExecutor` で
   looper thread `launcher-loader`（[Executors.java](../../src/com/android/launcher3/util/Executors.java)
   :96-97）。

5. **IconCacheのworker threadはMODEL_EXECUTORと同一looper。**
   [IconCache.java](../../src/com/android/launcher3/icons/IconCache.java):122 が
   `super(context, dbFileName, MODEL_EXECUTOR.getLooper(), ...)`。
   iconloaderlib `BaseIconCache.assertWorkerThread`（submodule
   `platform_frameworks_libs_systemui` @ `6a11ef767998885838a599331b5485f768b3d725`、
   `iconloaderlib/.../cache/BaseIconCache.java:814-818`）は
   `Looper.myLooper() != mBgLooper` であれば **無条件に**
   `IllegalStateException("Cache accessed on wrong thread " + Looper.myLooper())` を
   throwする。assertion呼び出し箇所は同file :432/:521/:570 のcache entry point。

6. **LoaderTask内の1 item例外はcatchされてlogだけ残る。**
   [WorkspaceItemProcessor.kt](../../src/com/android/launcher3/model/WorkspaceItemProcessor.kt)
   :109-114 が `catch (e: Exception)` で `Log.e(TAG, "Desktop items loading interrupted", e)`。
   T4の `Desktop items loading interrupted` はこの箇所の出力と一致する。

7. **LoaderTaskはrestore leaseの後ろでdeferする（blockしない）。**
   [LoaderTask.java](../../src/com/android/launcher3/model/LoaderTask.java):248-259 —
   `coordinator.runOrDefer(OwnerKind.MODEL_WRITER, ...)`（#14）。tokenless loaderは
   MODEL_EXECUTORを塞がない。

8. **launcher側のassertはstudio build限定、iconloaderlib側は無条件。**
   [Preconditions.java](../../src/com/android/launcher3/util/Preconditions.java):36-40 の
   `assertWorkerThread` は `FeatureFlags.IS_STUDIO_BUILD` でのみ検査し、対象looperは
   `MODEL_EXECUTOR.getLooper()`。一方iconloaderlibのassertion（上記5）はbuild typeに
   関係なくthrowする。debug build（T4観測）でも検出されたのは後者。

9. **既存のreload generation機構。** `startLoader` は#152のorganizer reload tokenを
   captureし、`completeOrganizerReload` がtoken一致でのみsnapshotを配る。
   `quiesceForRestore`（LauncherModel.java:331-337、#58）は進行中loaderを止め
   model loaded状態を無効化する。

### 静的読解で確定しないこと（未確定 — 本タスクでfix architectureを決めない根拠）

- restore threadから `BaseIconCache` のassert付きentry pointへの直接到達点は、
  `convertAndRestore` の呼び出しgraph（performRestore/sanitizeDB/reloadAfterRestore/
  pinImportedDeepShortcuts）には見つからなかった（上記1-3、5の実読）。
- `Can't create handler inside Thread[NovaBackupRestore]` を生む無Looper Handler生成箇所も
  特定できていない（NovaBackupConverter / RestoreDbTask / ModelDbController /
  LayoutWriteCoordinator には該当なし。restore窓内でlazy構築されるsingletonの可能性を
  含め未特定）。
- T4のfull stackはcoroutine継続frame（NovaBackupConverter.kt:167 = 当該revisionの
  lease `.use` block継続点）を含むmerged stackであり、例外の発生threadと
  coroutine frameの関係はstack単独では確定しない。
- よって「どの呼び出しがどのthreadからどの契約付きAPIへ届いたか」の正確なchain、
  および3 signatureの相互関係は、runtime reproductionによる特定（下記Investigation）が
  必要。これはIssue自身の受入条件TA-AC-01でもある。

## Design

### Investigation plan（root cause確定が最初の成果物）

fix architectureはI-2完了のdecision gateを通過するまで決定しない。

- **I-1: emulator再現の確立。** API 36.1 emulator + debug build（building guide準拠）
  でNova restoreを繰り返し実行する。T4はセッション依存のため、大きなbackup・連続
  cycle・loader競合を意図した手順で窓を広げる。復元実行中のfull logcatを収集し
  （`NovaBackupConverter`、`RestoreDbTask`、`LauncherModel`、`LoaderTask`、
  `BaseIconCache` のsignature）、3 signatureの出現条件を絞る。
- **I-2: call chainの特定（TA-AC-01）。** 再現時のfull stack（assertion throw点から
  根まで）を取得し、各signatureについて「発生thread」「契約付きAPI」「到達経路」を
  コードに対応させて記録する。`Cache accessed on wrong thread` については
  `BaseIconCache.assertWorkerThread` throw点の `mBgLooper` と実際の `Looper.myLooper()`
  の値から、呼び出しthreadを確定する。調査記録は `docs/assessment/issue-298-<slug>.md`
  に証跡（対象build SHA、取得log、確認日）とともに残す。
- **I-3: 中断時の残存状態の観測。** reloadが中断された直後のmodel/icon cache状態
  （何が初期化済みで何が無効化されたか、`quiesceForRestore` / 次回loadが状態を
  収束させるか）を観測し、TA-AC-04と#299切り分けの入力とする。
- **Decision gate:** I-2でchainが確定した時点で、修正のseam選択（下記候補から）と
  テスト戦略を確定する。修正が変更困難なthreading所有権の判断を含む場合はADRの
  3条件を再確認し、必要ならADRを作成する。

### Modules and interfaces（候補 — decision gate後確定）

- 修正が守るべき既存contract:
  - #58: 1個の `BACKUP_RESTORE` leaseがquiesce→commit→correlated reloadを囲む。
    reloadのthread handoffはこのlease内で行い、leaseのthread束縛（re-entryは
    thread identityベース、#168コメント記載）を壊さない。
  - #168: grid適用のauthoritative性（`applyConvertedGrid` のMain hop、durability
    barrier）を変更しない。
  - #14: loaderはleaseの後ろでdeferし、MODEL_EXECUTORをblockさせない。
  - #152: reload generation tokenによるsupersession。restore由来reloadと
    organizer由来reloadが競合しないこと（spec scopeのstale state/concurrency）。
- 候補seam（I-2の結果により選択・絞り込み）:
  - correlated reloadの発行と完了待ちを、契約上要求されるthreadへの明示的dispatchと
    する（現状 `reloadAfterRestore` はrestore threadから同期的に `forceReload` を
    呼ぶだけ。要求threadでの処理実行/完了同期をどこが所有するか）。
  - restore窓内でrestore thread上に構築されるHandler依存componentがある場合、その
    構築をMain/model thread側へ移すか、restore threadでのlazy構築を禁止するか。
  - LoaderTask側との競合が本質の場合、restore窓のloader遮断/quiesceの範囲見直し
    （#58の `quiesceForRestore` の適用タイミングと `runOrDefer` の相互作用）。
- 変えないinterface: `LayoutWriteCoordinator` のlease契約、`ModelDbController` の
  helper lifecycle契約、diagnostics契約。

### Data flow

restore thread（NO Looper）: convertAndRestore → [lease] → staging/commit
→ performRestore（DB transaction）→ reloadAfterRestore → `forceReload`
→ [MODEL_EXECUTOR] LoaderTask（lease defer後 runInternal）→ DB読み出し +
icon cache（mBgLooper == MODEL_EXECUTOR looper）→ [MAIN] Binder → workspace bind。
違反はこの図のどの境界で、どの実行主体が契約外に達したかの確定がI-2の成果。

## Change set

| Area | Intended change | Why here |
|---|---|---|
| `docs/assessment/issue-298-<slug>.md`（新規） | I-2/I-3の調査記録（証跡、確定chain、残存状態観測） | 調査証跡の正本置き場（既存慣行） |
| `lawnchair/src/app/lawnchair/backup/NovaBackupConverter.kt` | decision gate後確定。reload完了部のthread handoffが候補 | restore completion pathの所有者 |
| `src/com/android/launcher3/provider/RestoreDbTask.java` | decision gate後確定。`reloadAfterRestore` seamの変更候補 | Launcher3/AOSP由来のためbridge最小変更 + 近傍文書にIssue番号と理由を記録 |
| `src/com/android/launcher3/LauncherModel.java` | decision gate後確定（不要の可能性あり） | 同上。変更時はbridge最小箇所に限定 |
| `tests/organizer-instrumentation/app/lawnchair/backup/NovaRestoreGridApplicationTest.kt` | restore→reload完了のthreading観点拡張（TA-AC-06） | #168のNova restore instrumentation seamが実在 |
| `tests/src/com/android/launcher3/provider/RestoreDbTaskTest.java` または新規JVM test | seam単位の回帰（decision gate後確定） | 既存restore系JVMテスト表面 |

実際の変更file集合はI-2の結果で確定し、本表を更新する。

## Migration and recovery

- schema/migration変更なし。rollbackは単純revertで復帰する。
- 修正がrestore pathの振る舞いを変える場合でも、restore失敗時の既存fail-closed
  （#167）とlease解放（#58の `use` block）は保持する。reloadが完了しない場合も
  restore threadがleaseを握り続けないこと（既存 `use` の成功条件を弱めない）を
  検証で確認する。

## Verification

| Acceptance criterion | Automated/manual evidence | Command or environment |
|---|---|---|
| TA-AC-01 | 調査記録（docs/assessment）+ stack証跡 | I-1/I-2で収集 |
| TA-AC-02 | 修正diffの構造的確認 + runtime evidence | I-2後のPR review |
| TA-AC-03 | 繰り返しrestore cycleのlogcat検証（3 signature不在） | emulator `nunu_qpr2_api36_1` 相当 + 実機（可能なら） |
| TA-AC-04 | restore→workspace表示の確認、`NovaRestoreGridApplicationTest` 系拡張の実行 | organizer-instrumentation lane |
| TA-AC-05 | diff review（assertion非弱化）+ JVM/instrumentation test | 通常test surface |
| TA-AC-06 | 追加regression + device evidence記録 | emulator/実機検証記録 |

含めるべき観点: threadingはfailure injection（競合窓を意図した繰り返し実行）でのみ
有意な検証になる。単発の成功はTA-AC-03の証拠にならない。issue本文がdevice/emulator
検証を必須としている点、およびrestore系PRの高リスク要件（`risk: layout-data` 等の
label付与時は `final-status` + 独立audit記録）をPR時に確認する。

## Documentation updates

- [ ] spec status/history（decision gate通過後、受入条件の確定を反映）
- [ ] CONTEXT.md（Domain language の用語を承認時に反映するか検討）
- [ ] DESIGN.md（threading所有権のsystem不変条件に追加が必要になった場合）
- [ ] ADR（3条件を満たすthreading所有権の判断になった場合）
- [ ] Launcher3由来fileへの変更時、近傍文書にIssue番号と理由を記録

## Execution checklist

- [ ] I-1: emulatorで障害を再現し、3 signatureのlog/stackを取得する。
- [ ] I-2: call chainを確定し、docs/assessmentへ記録する（TA-AC-01）。
- [ ] I-3: 中断時の残存状態を観測・記録する。
- [ ] Decision gate: spec statusと本planのDesign/Change setをchainの確定結果で更新する。
- [ ] 失敗を再現するテストを先に追加する（修正前に失敗することを確認）。
- [ ] Minimal implementation、rollback/recovery確認、full verification、PR evidence記録。
