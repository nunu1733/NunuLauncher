# Issue #298 Assessment: Nova restore reload wrong-thread chain

- Issue: [#298](https://github.com/nunu1733/NunuLauncher/issues/298)
- Status: investigation complete (decision gate branch A — chain identified)
- Baseline investigated: `origin/main` = `397d3fd957`（2026-09-15時点。spec/plan改訂時点
  `9821dec073` から #317 mergeのみ）
- 関連: #287（観測元）、#299（同一セッションで観測されたcapture恒常化、fix merge済み）、
  #168（問題の初出記録）
- 確認日: 2026-09-14〜2026-09-15

## 1. 確定したwrong-thread call chain（TA-AC-01）

### 1.1 構造（`397d3fd957` 実読）

1. **Restore threadはLooperを持たない。** `NovaBackupConverter.restoreDispatcher`
   （lawnchair/src/app/lawnchair/backup/NovaBackupConverter.kt:91-92）は
   `Executors.newSingleThreadExecutor` を `asCoroutineDispatcher` したもの。thread名
   `NovaBackupRestore`。`convertAndRestore`（:167）がこのthreadで実行される。
2. **`LoaderTask.run()` はtokenlessの場合 `this::runInternal` をそのまま
   `runOrDefer` に渡していた**（src/com/android/launcher3/model/LoaderTask.java:262-272、
   修正前）。
3. **`LayoutWriteCoordinator.runOrDefer` は、holderがORGANIZERまたはrestore-family
   （RESTORE / BACKUP_RESTORE）の場合にtokenless仕事をFIFOへdeferする**
   （LayoutWriteCoordinator.java:460-497、`defersTokenlessWork`: :525-527 は
   `h.kind == ORGANIZER || isRestoreFamily(h.kind)`）。
4. **`release()` はdeferred FIFOを「lease解放thread上でinline」実行する**
   （LayoutWriteCoordinator.java:572-620）。`current = null` 後、`toRun` に取り出した
   各entryを、synchronized blockの外・`release()` を呼んだthread上で
   `r.runWithOperationFuture()` する。Nova restoreではleaseの解放者はrestore thread
   （`convertAndRestore` のlease `use` block終了時）である。
5. **よって、restore窓内でdeferされたLoaderTaskは `runInternal` をrestore thread上で
   実行する。** `runInternal` → `beginLoader` → `loadWorkspace` →
   `loadWorkspaceImpl` の呼び出し木の中でiconloaderlib `BaseIconCache` のassert付き
   entry point（同file :432/:521/:570）に到達すると、`assertWorkerThread`
   （platform_frameworks_libs_systemui submodule、BaseIconCache.java:814-818）が
   `Looper.myLooper()`（= null。NovaBackupRestoreにLooperは無い）≠ `mBgLooper`
   （= MODEL_EXECUTORの `launcher-loader` looper）で
   **無条件に** `IllegalStateException("Cache accessed on wrong thread ...")` をthrowする。

### 1.2 T4観測3 signatureへの対応

| 観測signature | 説明 |
|---|---|
| `IllegalStateException: Cache accessed on wrong thread`（full stackに `BaseIconCache.assertWorkerThread` → `LoaderTask.loadWorkspaceImpl` とcoroutine継続frame `NovaBackupConverter.kt:167`） | 上記1-5。stackにNovaBackupConverterのcoroutine継続frameが含まれるのは、throwがrestore thread上で発生し、そのthreadが `convertAndRestore` のsuspend継続（lease `use` blockの脱出点 = 観測revisionの `NovaBackupConverter.kt:167`）を積んでいたため。「merged stack」は2 threadの合成ではなく、**1つのrestore thread stackにloader frameとcoroutine継続が同居したもの**として説明できる。 |
| `Can't create handler inside Thread[NovaBackupRestore]` | 同じ「runInternal がrestore thread上で実行される」窓の別表現。load呼び出し木の途中でLooper無しthread上の無引数Handler生成が実行された場合に発生する（`new Handler()` は `Looper.myLooper() == null` でthrow）。旧構造（`forceReload` がrestore threadから同期的に `startLoader` を呼び、`ItemInstallQueue` / `MainThreadInitializedObject.get` のmain thread submit待ち等が同threadで走った）では発生箇所の候補が複数あり、**exactなHandler生成siteのruntime特定は本assessmentのred再現log（§2）で行う**。修正によりload本体がrestore thread上で実行されなくなるため、生成siteがどこであれこの窓から発生しなくなる。 |
| `Desktop items loading interrupted` | `WorkspaceItemProcessor.kt:109-114` のper-item `catch (Exception)` による `Log.e`。restore thread上で動いたloadがitem処理中に上記 `IllegalStateException` を受けた場合にこの行が出る。red再現でも同行が出ることを§2で確認する。 |

### 1.3 なぜレース（セッション依存）だったか

deferは `LoaderTask.run()` がMODEL_EXECUTORで到達した時点でrestore-family leaseが
持有中のときだけ起こる。LoaderTaskの起動（旧: `forceReload` → `startLoader` →
`MODEL_EXECUTOR.post`、現行: barrier → `MAIN_EXECUTOR` 経由 `startLoader` →
`MODEL_EXECUTOR.post`）と、restore threadがleaseを解放する時点の相対順序で、
deferされるか否かが決まる。 **deferされなければ違反は発生しない** （通常はMODEL_EXECUTOR
で実行される）。したがって同一手順でも成功セッションと失敗セッションが混在する。
T4（build `d0f40446c7`）は旧構造でdeferが起きたセッションである。

### 1.4 #299 fix（`9821dec073` merge）との関係

#299はloader起動をrestore threadから `MAIN_EXECUTOR` へ移し（`dispatchRestoreReload`）、
`startLoaderWithoutCallbacks` にUI thread事前条件を課し、completion barrierを導入した。
ただし **defer後のdrain threadは変更していない** ため、`release()` のinline drain +
`LoaderTask` のbare `this::runInternal` という違反経路は現行mainに残存する
（#299 assessmentも「actual wrong-thread pathは未再現・#298 scope」と記録し、
同一seamへの変更を意図的にexcludeしていた）。#299の再構成は障害窓を「狭めるが
消してはいない」（起動が非同期になった分、`LoaderTask.run` 到達時点でleaseが
解放済みになる確率は上がり、defer成立率は下がる。成立時の違反は変わらずdeterministic）。

## 2. runtime再現証跡（I-1、emulator `nunu_qpr2_api36_1` 系 API 36 emulator）

### 2.1 再現手法

ランダムなレースの待ち受けではなく、T4窓を **決定論的に再構成する** instrumentation
test（`tests/organizer-instrumentation/com/android/launcher3/organizer/
RestoreLeaseDeferredLoaderThreadAffinityTest.java`）を追加した:

1. Looper無しthread `NovaBackupRestoreTestThread` が `BACKUP_RESTORE` leaseを
   acquire（restore threadと同じ「Looper無し解放者」を再現）。
2. main threadから `forceReload()`（#299後の本経路と同じ: main → `startLoader` →
   `MODEL_EXECUTOR.post`）し、loaderが `pendingDeferredCount()` で観測可能な形で
   FIFOへdeferされたことを確認してから解放する。
3. restore-like thread上で `lease.close()`。

oracle: workspace loadが完了し（`onInitialBindComplete`）、seedしたitem数が保たれ、
modelがloadedになること。修正前はloader本体がrestore-like thread上で実行され、
icon cacheのworker-thread assertionによりloadが成立しない（red）。修正後は
MODEL_EXECUTORで完了する（green）。

### 2.2 red実行（修正前コード、`397d3fd957` + test）

- 実行日: 2026-09-15
- 環境: emulator `nunu_qpr2_api36_1`（API 36, AVD）、build
  `assembleLawnWithQuickstepGithubDebug`（未修正 `LoaderTask`）
- 結果: **FAILED（期待どおり）**
  `java.lang.AssertionError: Workspace load did not complete after the lease release`
- runtime証跡（emulator logcat、同一スレッド上での連鎖を完全に捕捉。保存:
  調査セッション `/tmp/issue298-evidence/red-run-wrong-thread-stack.log`、
  PRには要約を記載）:

```text
E WorkspaceItemProcessor: Desktop items loading interrupted
E WorkspaceItemProcessor: java.lang.IllegalStateException: Cache accessed on wrong thread null
  at com.android.launcher3.icons.cache.BaseIconCache.assertWorkerThread(BaseIconCache.java:816)
  at com.android.launcher3.icons.cache.BaseIconCache.getEntryForPackageLocked(BaseIconCache.java:570)
  at com.android.launcher3.icons.IconCache.getTitleAndIconForApp(IconCache.java:577)
  at com.android.launcher3.model.WorkspaceItemProcessor.processWidget(WorkspaceItemProcessor.kt:521)
  at com.android.launcher3.model.WorkspaceItemProcessor.processItem(WorkspaceItemProcessor.kt:110)
  at com.android.launcher3.model.LoaderTask.loadWorkspaceImpl(LoaderTask.java:537)
  at com.android.launcher3.model.LoaderTask.loadWorkspace(LoaderTask.java:469)
  at com.android.launcher3.model.LoaderTask.runInternal(LoaderTask.java:294)
  at com.android.launcher3.model.LayoutWriteCoordinator$2.runWithOperationFuture(LayoutWriteCoordinator.java:482)
  at com.android.launcher3.model.LayoutWriteCoordinator.release(LayoutWriteCoordinator.java:608)
  at com.android.launcher3.model.LayoutWriteCoordinator$LeaseImpl.close(LayoutWriteCoordinator.java:639)
  at RestoreLeaseDeferredLoaderThreadAffinityTest.lambda$...$0(testのlease.close()呼び出し)
  at java.lang.Thread.run(Thread.java:1563)

E LayoutWriteCoordinator: Deferred callback threw
E LayoutWriteCoordinator: java.lang.IllegalStateException: Cache accessed on wrong thread null
  at BaseIconCache.assertWorkerThread(BaseIconCache.java:816)
  at BaseIconCache.cacheLocked(BaseIconCache.java:432)
  at IconCache.getTitlesAndIconsInBulk(IconCache.java:456)
  at LoaderTask.tryLoadWorkspaceIconsInBulk(LoaderTask.java:676)
  at LoaderTask.loadWorkspaceImpl(LoaderTask.java:539) ... release(:608) 経由
```

- これは#287 T4セッション（build `d0f4044`）のstackと同一構造である:
  `LeaseImpl.close`（restore thread上）→ `release` のinline drain →
  `runWithOperationFuture` → `runInternal` → `loadWorkspaceImpl` →
  `BaseIconCache.assertWorkerThread`。`Cache accessed on wrong thread null` の
  `null` は解放thread（Looper無し）の `Looper.myLooper()` である。
  T4で観測された「merged stack」（coroutine継続frameとの同居）は、例外が
  restore thread上で発生したため1 stack内にloader frameと
  `convertAndRestore` 継続が共存したものとして説明が確定した。
- 3 signatureのうち1（wrong-thread cache access）と3（`Desktop items loading
  interrupted`）を同一実行で再現。2（`Can't create handler inside
  Thread[NovaBackupRestore]`）は本実行ではicon cacheのISEが先行して発生したため
  出現しなかった。T4では例外発生順の差で3つとも記録されたと解釈できる。
  Handler生成siteのexact特定は、修正によりload本体がrestore thread上で
  実行されなくなるため事後観測は不可能になる（障害窓の消滅）。本体は
  「runInternal呼び出し木内の任意の無引数Handler生成」であり、chainとしては
  §1で確定済み。

### 2.3 green実行（修正後コード）

- 実行日: 2026-09-15
- 修正: `LoaderTask.run()` のtokenless経路を `runInternalOnModelExecutor` へ
  （MODEL_EXECUTOR上ならinline、それ以外は `MODEL_EXECUTOR.execute(this)` で
  再admission）。
- 結果: **PASSED**（`Starting 1 tests ... Finished 1 tests ... BUILD SUCCESSFUL`）。
  deferred loadがMODEL_EXECUTOR上で完了し、seed itemを保持、model loadedに到達。
- 同一修正でshared-writer coordinator lane全体（既存8 class + 本test）を実行し、
  既存契約の非回帰を確認（§5）。

## 3. 中断時の残存状態（I-3）

修正前の違反が成立した場合、loadは `beginLoader` transactionの途中で
`IllegalStateException` で中断される。このとき:

- `mModelLoaded` は `beginLoader` のtransaction close条件を満たさないため
  loadedにならない。後続のload要求（`forceReload` / 次回activation）が再loadを
  行えば状態は収束する。T4セッションで「workspace loading interrupted」後の
  不確実性はこの中途状態に相当する。
- DBは `LoaderTransaction` 内の書き込みがtransactionとしてguardされるため、
  loader自体は読み取り主体であり、#299 assessmentが記録したような「修復sanitizeの
  部分適用」は本違反経路ではsanitizeが走る前に死ぬか、sanitize内のper-item
  catchで記録が残る形で发生する。恒常化の本体は#299が別に特定したcapture側の
  経路であり、本Issueのchainとは分離して扱う（#299/#298の因果は依然未確立）。

## 4. Decision gate記録

- **分岐: (A) chain確定 → Phase 2 fix。**
- 3 signatureの発生thread・契約付きAPI・到達経路を§1で確定し、§2の決定論的red/green
  実行でruntime裏付けを取る（§2.2/2.3）。
- **Phase 2 fix（採用）**: `LoaderTask.run()` のtokenless経路を、ModelWriterと同じ
  「deferred jobはMODEL_EXECUTORへ手渡してadmissionをやり直す」規律に揃える
  （`runInternalOnModelExecutor`: 現在threadがMODEL_EXECUTORならinline実行、
  それ以外は `MODEL_EXECUTOR.execute(this)` で再admission）。exact-token
  organizer経路はcapability ThreadLocalを保つためinline実行のまま。
  これは `LayoutWriteCoordinator.runModelWriterOrDefer` のJavadocが定める
  「continuation must not run DB work inline on the lease-releasing thread」および
  class Javadocの「reposted on lease release」という既存契約の適用漏れ箇所を
  塞ぐものであり、新しいthreading所有権の判断を導入しないためADRは不要
  （3条件を満たさない）。
- coordinator側は変更しない（現行のdeferred entryは全てModelWriter / LauncherProvider /
  reservationのいずれかで、それらは自前でMODEL_EXECUTORへhopする実装済み。
  bare runnableはLoaderTaskのみ）。
- 正規化/拒絶（#299 assessmentが却下済み）には触れない。#299 barrier契約
  （deadline / restore失敗surface）は不変。

## 5. 検証

- 本test（red→green）をshared-writer coordinator instrumentation laneへ追加
  （`.github/workflows/ci.yml`）。TA-AC-03の「繰り返し」は、決定論的窓再構成により
  単発で違反窓を確実に踏む方式で置き換えた（レース待ちの反復実行は違反の不在を
  証明できないため）。
- 併せて既存coordinator/restore系instrumentation（同一lane）とJVM unit test、
  `spotlessCheck`、`assembleLawnWithQuickstepGithubDebug` を実行する。
