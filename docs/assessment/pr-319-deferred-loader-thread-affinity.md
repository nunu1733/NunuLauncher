# High-risk audit: PR #319 restore窓でdeferされたtokenless loaderのMODEL_EXECUTOR再admission

> Status: accepted（verdict: approve。Re-audit (1)/(2)を経て、Re-audit (3)で監査対象headを
> `8b63ae0a34` へ更新、初回auditのfindings・verdictを承継。`CI run:` fieldのmerge gate
> run 34911441712はcompleted/success・`final-status` greenでmerge条件を満たす。
> code findings無し）
> Audit date: 2026-09-15

- Auditor: 独立audit session（general-purpose subagent。PR #319の実装を行っていないsession。solo保守のため、同一保守の別sessionとして実装経路に依存しない実読・再確認を実施）
- PR: https://github.com/nunu1733/NunuLauncher/pull/319（base `main`、head branch `issue-298-implementation`、label `risk: layout-data`）
- Head SHA: 8b63ae0a343bfeeea4efb6c2444de6f5a7df2717
- CI run: https://github.com/nunu1733/NunuLauncher/actions/runs/34911441712
  （`event=pull_request`、`head_branch=issue-298-implementation`、
  `head_sha=8b63ae0a34…`、`path=.github/workflows/ci.yml`、PR #319関連付け。
  **completed/success、`final-status` success**。本audit本人がGitHub APIで全14 jobの
  conclusionを直接確認: source jobs（organizer-unit-tests / check-style / build-debug-apk）
  実行済みsuccess、shared-writer / issue52を含む全emulator laneもsuccess。
  Re-audit (3)節参照。Re-audit (2)時のrun 34870668580（head `601aec6407`）はcompleted/
  successであったがhead移動により現行監査対象のgate証跡ではなく、それより前の
  run 34869651225 / 34865190872も同様に過去headの証跡である）
- Criteria: specs/298-nova-restore-reload-thread-affinity/spec.md TA-AC-01, TA-AC-02, TA-AC-03, TA-AC-04, TA-AC-05, TA-AC-06
- 調査証跡の正本: docs/assessment/issue-298-wrong-thread-restore-reload.md（以下「assessment」）。
  PR本文・commit message・assessmentの主張は信じず、以下のとおりpre-fix/post-fixの
  code実読とGitHub APIで独立検証した。実施者のemulator実行（red/green等）は再実行していない
  （emulator testは本auditのscope外）ため、すべて「実施者報告」として明記する。
- Re-audit (1): 初回audit（head `b698e48bc8`）の記録をcommit `ef2fe7a2eb`（docs-only）として
  pushした後、review対応の2 commit — `419212c6e8`（test isolation強化 + assessment §5の
  per-AC対応表化）と `9693a2215f`（test helperのcleanup failure伝播）— が加わったため
  再監査。監査対象headを `9693a2215fe373eaa677c5b9e0f2d193f955d099` へ更新。
  なお `ef2fe7a2eb` 上ではCI run 34867984377（attempt 1、全job）とHigh-risk gate
  run 34867984420がsuccessであった（gate機構が一度成立したhead。test commitにより無効化）。
  audit本人がdelta `git diff b698e48bc8..9693a2215f`（3ファイル: test 1 + docs 2）の
  全hunkを直接reviewした結果:
  - **production codeは不変（本auditが直接確認）**: `git diff b698e48bc8..9693a2215f -- src/ lawnchair/ quickstep/ .github/` は0行。`LoaderTask.java` を含むproduction変更・
    CI workflow変更は無く、deltaは `RestoreLeaseDeferredLoaderThreadAffinityTest.java`
    （+125/−37）とassessment・本audit記録のdocsのみ。
  - **test isolation強化は記述どおり実装済み**: Looper-less holder threadはleaseを
    **自身のfinallyで必ずclose**する（release signal timeout経路を含む。従来はtimeout時に
    closeせず抜けるprocess-wide lease leak経路が実在した — 本deltaで閉じられた）。
    test本体のfinallyが全exit pathで `releaseNow.countDown()`（冪等）→ holderのjoin →
    生存時はinterrupt → 再join → それでも生存時はcleanupFailuresへ記録し、teardownは
    snapshot復元後に `forceReloadAndAwaitBindQuietly` でmodel reloadを強制・完了待ちして
    in-memory `BgDataModel` を復元後のDBへ再bindする。
  - **failure list伝播（`9693a2215f`）は確認済み**: `419212c6e8` の中間状態に残っていた
    helper内の使い捨てlist（`removeModelCallbackQuietly(callbacks, new ArrayList<>())`）
    が `reloadAndAwaitBindItemCount` / `forceReloadAndAwaitBindQuietly` では所有listへの
    伝播に、`waitForModelIdle` では自前cleanup失敗の表面化（AssertionError）に変更された。
  - **assertion弱化無し**: `assertFalse("...still alive")` はより強い「終了させるか失敗にする」
    logicへ置換、`assertNull("Failure escaped on the releasing thread", ...)` は維持、
    cleanupの `catch (Exception)` → `catch (Throwable)` 拡幅は握り潰しではなく
    failure記録への経路変更（強化）。oracle（`onInitialBindComplete`・seed item数・
    `model.isModelLoaded()`・解放thread上のfailure不在）は不変。lease closeは引き続き
    holder thread上で実行されるため、検証対象のinline drain thread意味論も不変。
  - **assessment §5の書換は記述どおり**: per-AC evidence対応表へ書き換えられ、
    TA-AC-03のdeviation（単一反復oracle不導入の理由）と再open条件（将来のrestore検証で
    signature再発時のIssue再open）が明記された。初回auditのCosmetic指摘（「发生する」表記）
    も本deltaで修正済み。
  - **新規観察（非阻塞・Minor）**: (a) `assertNotNull` static importが未使用
    （cosmetic。spotlessの `removeUnusedImports()` は `compatLib/**` のみ対象であり、
    新headのcheck-style jobは既にpass）；(b) test本体のassertion失敗時、finally内で
    記録されたcleanup failureの報告は主失敗に吸収される（cleanupの実行自体は行われ
    leak防止の目的は達成。主失敗優先の標準的挙動）；(c) holderのacquire失敗時は
    `leaseAcquired` latchがcatch経由でcount downされるためtest本体は先へ進み、
    「Loader was not deferred」assertionが先に失敗してreleaseThreadFailureの直接報告が
    後回しになる（初回版から存在するdiagnosability上の優先順位のnit。本deltaでは
    導入・悪化していない）。
  - **verdict承継**: 初回auditのfindings・verdict（approve、code findings無し）は
    新head `9693a2215f` に対してそのまま成立。merge条件は cited run 34869651225の
    `final-status` success確定（Re-audit (1)完了時点でin_progress。emulator lanesと
    build-debug-apkがpending、check-style / validate-repo-contractは既にpass）。
- Re-audit (2): Re-audit (1)がpinした `9693a2215f` のgate候補run 34869651225は、
  本audit記録のpush（現行branch head `601aec6407`）にsupersedeされ完了前に
  cancelledとなった（completed/cancelled — 本audit本人がGitHub APIで確認）。
  本更新は監査対象headを現行branch head `601aec6407c549ff45965eba3bf603ba597cd280`
  へ再pinするものである。`git diff 9693a2215f..601aec6407 --stat` は本audit記録
  1ファイルのみ（+74/−11、docs-only）であり、code・test・CI workflow変更は無い
  （本audit本人が実測。gate lineage要件 — 監査対象head以降のPR差分がdocsのみ — は維持）。
  cited runをcompleted/successのrun 34870668580へ更新した: `final-status` successを含む
  全14 job success、source jobs（organizer-unit-tests / check-style / build-debug-apk）
  実行済みsuccess、shared-writer・issue52を含む全emulator lane success
  （全job conclusionをGitHub APIで直接確認）。verdict不変（approve、code findings無し）。
  本記録のcommit（docs-only）以後にcode変更が入った場合はre-auditを要する。
- Re-audit (3): Re-audit (2)のpin（`601aec6407`）以後にreview対応の2 commitが加わった
  （間の `5b5695caca` は本記録のdocs-only commit — `git show --stat` で本記録1ファイルの
  みであることを確認）。監査対象headを `8b63ae0a343bfeeea4efb6c2444de6f5a7df2717` へ更新。
  `git diff 601aec6407..8b63ae0a34 --name-only` はtest 1 + docs 3（assessment / spec /
  本記録）のみで、`src/` / `lawnchair/` / `quickstep/` / `.github/` の差分は **0行** —
  production code（`LoaderTask.java`含む）は引き続き不変（本auditが直接確認）。
  audit本人が全delta hunkを直接reviewし、記載の主張を独自に検証した結果:
  - **Handler signature（sig2）の帰属確定は検証可能な内容として妥当**:
    (a) 「deferred窓からはHandler生成点へ到達する前にicon cache assertionが先発火する」
    は静的にも成立する — item有り形状は `loadWorkspaceImpl` のicon cache access、
    空workspace形状は `loadAllApps` → `AllAppsList.add` → `mIconCache.getTitleAndIcon`
    （観測build `d0f40446c7` の AllAppsList.java:155。実在を本auditが確認）→
    `cacheLocked:432` → `assertWorkerThread:816` の無条件throwであり、
    いずれもload呼出木の早期に確定的に発火する。(b) 「restore threadから到達可能な
    無引数 `new Handler()` はapp側に存在しない」も本auditが `d0f40446c7` で独自sweepし
    確認 — 該当siteは9 file 10箇所（smartspace MediaListener / accessibility delegate /
    DiscoveryBounce / StatefulActivity / SettingsCache / ViewPool / ArrowTipView /
    WidgetHostViewLoader / widgets search algorithm）で、いずれもUI隣接classであり
    restore→reload path（converter / model / loader / coordinator / provider）には無い。
    assessment記載の「9箇所」と本audit実測「10occurrence/9file」の数え方の差は
    cosmetic（結論に影響しない）。(c) 従ってsig2の出所はframework内部生成で、
    その前提（framework呼出しをLooper無しrestore thread上で実行）を作る2 chain
    （旧sync dispatch＝#299で除去、deferred drain＝本PRで除去）が共に閉じられた
    という記録は整合する。T4 logにsig2のstackが無くframework内部のexact行が
    本repoから特定不能であるというboundaryは明示記録されており、TA-AC-01の
    充足判断（3 signatureの出所が全て説明可能）は妥当。
  - **TA-AC-03のoracle正規化はspec改訂とtest実装が一致**: spec（正本）のTA-AC-03と
    Test oracle行が「決定論的deferral窓の反復サイクル + in-test logcat 3 signature
    不在oracle」+「実restore/reload lane（`NovaRestoreCapture*Test` + cross-process
    stage）の緑」へ改訂され、testはそれをそのまま実装する
    （`DEFERRED_WINDOW_CYCLES = 3` の反復defer/release cycle、各cycleでdeferral成立・
    bind完了・seed item数維持・model loaded・解放thread failure不在をassert）。
    logcat oracleは `logcat -d --pid=<pid>` にUUID window markerでscopeし、
    T4記録の3 signature + `Deferred callback threw` の4語の不在をassertする。
    **fail-closed設計は正しい**: logcat異常終了（nonzero exit / 例外）や
    marker不検出（ring buffer eviction含む）は「評価不能」としてtest failureになり、
    oracle無効のままpassしない。4語目の `Deferred callback threw` はspec要求の
    3 signatureに対する_stricter_な追加である。Re-audit (1)のCosmetic finding
    （未使用 `assertNotNull` import）は本deltaで除去済み。
  - **新規観察（非阻塞）**: (a) logcat oracleのsignature走査は最初の一致で即座に
    AssertionErrorを投げる（残りsignatureの列挙はしない）— 失敗時の診断情報は
    logcat自体が保持するため実害無し。(b) `deleteRowQuietly` は3 cycle終了後の
    1回に移動され、各cycleのitem数oracleは同一seed行に対して評価される
    （cycle間でbaseline整合が保たれる。正しい構造）。(c) specのTest oracle改訂により、
    Re-audit (1)が記録したTA-AC-03のdeviation（実lifecycle反復oracleの不在）は
    解消（決定論的窓反復 + logcat不在oracleが正本spec上の正式なoracleとして採用）。
  - **verdict承継**: 初回audit・Re-audit (1)/(2)のfindings・verdict（approve、
    code findings無し）は新head `8b63ae0a34` に対して成立。TA-AC-01..06の充足判定は
    変わらず（TA-AC-01/03は本deltaで強化）。merge条件はcited run 34911441712が
    completed/success・`final-status` greenのため満たされており、残る手続きは
    本記録のdocs-only commitのみ。本記録のcommit以後にcode変更が入った場合は
    re-auditを要する。

## Scope

監査対象は `8b63ae0a343bfeeea4efb6c2444de6f5a7df2717`（Re-audit (3)の監査対象。
Re-audit (2)の監査対象 `601aec6407c549ff45965eba3bf603ba597cd280` からの差分は
本記録のdocs-only commit（`5b5695caca`）とreview対応2 commit（test + docs。
Re-audit (3)節参照）。Re-audit (1)の監査対象 `9693a2215fe373eaa677c5b9e0f2d193f955d099`
および初回auditの監査対象 `b698e48bc836f2a7545ff32ebc0fb5cba209f7b0` からの差分と
その検証は冒頭の各Re-audit節、初回時の記録は以下に歴史記録として保持）。
merge-baseは `origin/main` =
`397d3fd95764878366e7c9e5ce41ab65e6f3f9ca`（#317 merge直後。Re-audit (1)時点でmain不動を再確認済み）。
初回audit時のPR差分は6ファイル
+580/−35（`git diff --stat` 実測）:

- `src/com/android/launcher3/model/LoaderTask.java`（+22/−2相当。唯一のproduction code変更）
- `tests/organizer-instrumentation/com/android/launcher3/organizer/RestoreLeaseDeferredLoaderThreadAffinityTest.java`（新規315行）
- `.github/workflows/ci.yml`（shared-writer laneの `-Pandroid.testInstrumentationRunnerArguments.class=` へ新test classを1件追加のみ）
- `docs/assessment/issue-298-wrong-thread-restore-reload.md`（新規調査記録）
- `specs/298-nova-restore-reload-thread-affinity/spec.md`（frontmatter `status: draft → accepted`、`updated`、Change history 1 entry）
- `specs/298-nova-restore-reload-thread-affinity/plan.md`（status、decision gate判定、Change set確定、Execution checklistの完了更新）

commit構成: `9be263a0b0`（fix: LoaderTask + test + ci.yml の3ファイル）と
`b698e48bc8`（docs: assessment + spec + plan の3ファイル、docs-only）。
`git log 397d3fd957..b698e48bc8` はこの2commitのみであることを確認。

layout-write / migration pathへの変更: 無し。本diffは
`tools/repo-contract/validate_high_risk_evidence.py` のhigh-risk path
（provider/backup/organizer-application等のpersisted writer群）に一切触れず、
LoaderTaskの変更はcoordinatorへのadmission方法の変更のみで、DB書込み・schema・
`favorites` 書式・recovery pointには作用しない。loader自体は読み取り主体であり、
既存の`LoaderTransaction`契約は不変。`git diff --check` はclean。

## Criteria check

`specs/298-nova-restore-reload-thread-affinity/spec.md`（frontmatter `status: accepted`、
requirements `[TA-AC-01..06]`。本diff内で `accepted` へ更新済み — PR #317 review完了の
記録と整合）の受入条件ごとの確認。

### 独立検証したroot-cause chain（TA-AC-01の構造確認）

pre-fix（`origin/main` = `397d3fd957`）の実読により、以下のchainを **実装者の記述に依存せず** 確認した:

1. **restore threadはLooperを持たない。** `NovaBackupConverter.restoreDispatcher`
   （lawnchair/src/app/lawnchair/backup/NovaBackupConverter.kt:91-92）は
   `Executors.newSingleThreadExecutor { r -> Thread(r, "NovaBackupRestore") }` で、
   HandlerThreadではなくplain `Thread`（`Looper.myLooper()` はnull）。
   `convertAndRestore`（:167）が `withContext(restoreDispatcher)` で実行され、
   BACKUP_RESTORE lease（:176-177の `acquireBlockingQuietly(...).use { ... }`）は
   このthread上で解放される（`use` block脱出 → `LeaseImpl.close()`）。
2. **`LoaderTask.run()` はpre-fixでtokenless/exact-token双方にbare `this::runInternal` を渡していた**
   （LoaderTask.java:262-272。本PR diffの削除行で確認）。
3. **`LayoutWriteCoordinator.runOrDefer` は、holderがORGANIZERまたはrestore-familyのとき
   tokenless仕事をFIFOへdeferする**（LayoutWriteCoordinator.java:460-497。
   `defersTokenlessWork`: :525-527 = `ORGANIZER || isRestoreFamily`、
   `isRestoreFamily`: :113-115 = `RESTORE || BACKUP_RESTORE`）。
4. **`release()` はdeferred FIFOをlease解放thread上でinline drainする**
   （LayoutWriteCoordinator.java:572-613。`current = null` 後、lock外の
   `r.runWithOperationFuture()`（:608）を `release()` 呼出thread（= `close()` 呼出thread）で実行。
   plain `runOrDefer` のentryは `runnable.run()` をそのまま実行する（:479-484）ため、
   bare runnableは解放thread上で動く。
5. **よってrestore窓内でdeferされたLoaderTaskは `runInternal` をLooper無しthread上で実行する。**
   `runInternal` → `loadWorkspace` → `loadWorkspaceImpl` はicon cacheへ到達し
   （LoaderTask.java:664/676/844 の `getTitleAndIcon` / `getTitlesAndIconsInBulk`）、
   iconloaderlib `BaseIconCache.assertWorkerThread`
   （platform_frameworks_libs_systemui/iconloaderlib/.../BaseIconCache.java:814-818）は
   build type条件なしの無条件throw（`Looper.myLooper() != mBgLooper` → ISE）。
   launcherの `mBgLooper` は `MODEL_EXECUTOR.getLooper()`
   （src/com/android/launcher3/icons/IconCache.java:122）で、Looper無しthreadとは常に不一致。
   item処理のper-item `catch (Exception)` が `Log.e(TAG, "Desktop items loading interrupted", e)`
   を出す（WorkspaceItemProcessor.kt:109-114）。3 signatureの発生機構はすべて説明可能。
6. LoaderTaskの起動siteは `MODEL_EXECUTOR.post(mLoaderTask)` のみ
   （src/com/android/launcher3/LauncherModel.java:489）であるため、deferされなければ
   違反は発生しない。defer成立はloader起動とlease解放の相対順序に依存し、
   セッション依存（レース性）だったというassessment §1.3の説明と整合する。
   #299再構成（`dispatchRestoreReload` / barrier）は起動threadを変えるがdrain threadは
   変えないため違反経路が残る、という§1.4もcoordinator実読から導出できる。

runtime再現（emulatorでのred stack/logcat、assessment §2.2）は実施者報告であり、
本auditでは再実行していない。ただし上記の静的chainは本auditが独立に確定しており、
red実行のstack構造（`LeaseImpl.close` → `release:608` → `runWithOperationFuture` →
`runInternal` → `assertWorkerThread`）は実読したcodeの行番号と完全に一致する。

### 契約保持の検証（fixの構造確認、post-fix `b698e48bc8` 実読）

- **exact-token organizer経路は変更なし**: `run()` は `mOrganizerLeaseToken != 0L` のとき
  従来どおり `this::runInternal` を渡す。`runOrDefer` はexact-token holderに対してのみ
  `runWithOrganizerCapability` で `activeOrganizerToken` ThreadLocalをinstallして
  inline実行する（LayoutWriteCoordinator.java:488-511）。capability契約は保存される。
- **tokenless経路の通常pathにextra hop無し**: LoaderTaskの唯一の起動siteは
  `MODEL_EXECUTOR.post`（LauncherModel.java:489）であり、非defer時は
  `runInternalOnModelExecutor` のLooper照合が成立してinline実行される（通常pathの
  実行thread・順序はpre-fixと同一）。
- **deferred pathの再admission**: 解放thread上でdrainされた場合のみ
  `MODEL_EXECUTOR.execute(this)` で手渡し、`run()` → `runOrDefer` のadmissionをやり直す。
  再admission時に別のrestore-family leaseが持有中なら再度deferされるため
  **lease排除契約は保存される**。busy loopなし: 再実行は (a) lease解放drain または
  (b) MODEL_EXECUTOR queueのdeck化のいずれかを必ず経由し、defer→再admissionの1循環は
  常に外部のlease解放を1回要求するため、進展なしのspinは構造的に不可能。
  これは `ModelWriter.ModelTask.executeOnModelThread` の既存規律
  （ModelWriter.java:571-580、`() -> MODEL_EXECUTOR.execute(this)`）と同一であり、
  coordinatorのclass Javadoc「Tokenless MODEL_EXECUTOR work is appended to a coordinator
  FIFO and reposted on lease release」（LayoutWriteCoordinator.java:27-29）および
  `runModelWriterOrDefer` Javadoc「must not run DB work inline on the lease-releasing
  thread」（同:311-326）が定める既存契約の適用漏れ箇所を塞ぐ形になっている。
  新規のthreading所有権判断は導入していない（ADR不要の判断に同意）。
- **「bare deferred entryはLoaderTaskのみ」の主張も確認**: plain `runOrDefer` の呼出側は
  main上でLoaderTaskとModelWriterのみ（後者は自前hop済み）、
  `runOrDeferWithOperationFuture` の呼出側LauncherProviderは `MODEL_EXECUTOR.submit`
  で自前hop（LauncherProvider.java:162-170）、`ModelWriterReservation` のbodyは
  「always admitted on MODEL_EXECUTOR」（LayoutWriteCoordinator.java:382-393）。
  coordinator本体の変更が不要である旨のPR主張は正しい。
- **stopLoader競合の非回帰**: drain遅延中に `stopLoader()` され再postされた古taskは
  `runInternal` 冒頭の `mStopped` checkで即returnする（LoaderTask.javaの既存構造）。
  再postは最大1回のqueue entry追加であり、新たなleak経路ではない。
- **TA-AC-05（assertion保存）**: 本diffに `BaseIconCache` / `Preconditions` /
  `IconCache` への変更は無い。diff中のassertion関連行はすべてassessmentのdoc text、
  log excerpt、新test自身のassertionである。`assertWorkerThread` はhead上で
  無条件throwのまま生存（実読確認）。例外の握り潰しの新設も無い
  （`release()` の既存outer catchはpre-fixから不変）。

### 受入条件ごとの判定

- **TA-AC-01（chain特定と証跡記録）: 満たす**。chain構造は本auditがpre-fix実読で独立確定
  （上記1-6）。3 signatureの出所はassessment §1.2の対応表で説明でき、観測build
  （`d0f40446c7`）と現行mainの構造差分を「#299再構成はdrain threadを変えない」として
  区別記録済み。red実行のstack証跡は実施者報告だが、記載されたstack frameと
  code行番号の一致を本auditが検証した。boundary: Handler signature（signature 2）は
  red実行でISEが先行したため直接観測されず、assessment §1.2/§2.2とspec Change historyが
  「修正により窓ごと排除され事後特定は不能」と明記済み。chain全体の排除で説明可能であり
  記録漏れは無い。
- **TA-AC-02（許可されないthreadからのaccess排除・明示dispatch）: 満たす**。
  修正はtokenless deferred loadを `MODEL_EXECUTOR` へ明示手渡しする形であり、
  specの要求（該当処理を契約上要求されるthreadへ明示dispatch）に合致。
  restore thread側の変更は無く、NovaBackupConverterは何もloader/cache APIへ触れない。
  排除の完全性: tokenless loaderが解放thread上で動く経路は
  「plain `runOrDefer`のbare runnable」のみであったことを本auditが全呼出側実読で確認済み。
- **TA-AC-03（繰り返し検証で3 signature不発生）: 満たす（記録された方式の限界付き）**。
  決定論的窓再構成test（`RestoreLeaseDeferredLoaderThreadAffinityTest`）が
  shared-writer laneで実行され、audited head上のCI run 34865190872の該当jobが
  success（本auditがjob logで新test class名と `BUILD SUCCESSFUL` を直接確認）。
  assessment §5が「TA-AC-03の繰り返しは決定論的窓再構成による単発確実踏みで置換」
  と明示しており、レース待ちの反復が違反不在を証明できないという理由は技術的に妥当。
  boundary: 実restore lifecycleの反復走査による直接記録は本PRには無く、
  実lifecycle側は既存Nova restore capture lanes（CI green）が覆盖する。この置換を
  deviationとして記録した上で受容する（Findings参照）。
- **TA-AC-04（barrier完了待ちでのreload完了・workspace使用可能）: 満たす**。
  regression testのoracleは `onInitialBindComplete`（bind到達）+ seed item数維持 +
  `model.isModelLoaded()` + 解放thread上のfailure不在であり、PR本文のとおり
  「load完了=bind到達」を待つ。実restore経路のcompletion barrier到達は
  `NovaRestoreCapture*Test` / cross-process pair（issue299 lane、CI green）が検証する。
  boundary: regression test単体は#299の `RestoreReloadBarrier` オブジェクトを直接
  観測しない（test対象はloader側のthread手渡しであり、barrier観測はcapture lanesの役分。
  役分は合理的）。
- **TA-AC-05（assertion保存）: 満たす**（上記契約保持検証のとおり。diff reviewで確認）。
- **TA-AC-06（regression追加 + device evidence）: 満たす**。
  新回帰testがci.ymlのshared-writer laneへ接続され（ci.yml diffは当該1行のみ）、
  audited head上のCI merge gate runで実際に実行・成功している。
  emulatorでのred/green検証は実施者報告（assessment §2.2/§2.3、redの失敗message
  `Workspace load did not complete after the lease release` はtestのoracle文と一致）。
  本auditはemulator testを再実行していないため、この点を独立性の限界として明記する。

## Executed test surface

audit本人が実行した独立検証（static検証 + GitHub API証跡。production testの再実行は無し）:

```text
git fetch origin main issue-298-implementation
git rev-parse origin/issue-298-implementation origin/main
git diff origin/main..origin/issue-298-implementation        # 全diff review（6ファイル）
git diff --check origin/main..origin/issue-298-implementation # clean
git show origin/main:<LoaderTask / LayoutWriteCoordinator / ModelWriter / LauncherProvider /
  NovaBackupConverter / WorkspaceItemProcessor / IconCache / LauncherModel>  # pre-fix実読
git show b698e48bc8:<LoaderTask.java / 新test / ci.yml>       # post-fix実読
git log --format=%h\\ %s 397d3fd957..b698e48bc8               # commit構成確認
gh pr view 319 -R nunu1733/NunuLauncher --json ...            # head SHA・labels・本文
gh repo view nunu1733/NunuLauncher --json nameWithOwner,defaultBranchRef
gh pr checks 319 -R nunu1733/NunuLauncher
gh run list -R nunu1733/NunuLauncher --branch issue-298-implementation --limit 10
gh run view 34865248058 -R nunu1733/NunuLauncher --log-failed  # high-risk gate失敗理由
gh api repos/nunu1733/NunuLauncher/actions/jobs/104051988446/logs  # shared-writer lane log
```

CI merge gate（**初回audit時の記録** — 当時の監査対象head `b698e48bc8` 上。GitHub APIで直接確認。
初回audit完了時点の状態。現行監査対象head `9693a2215f` の状態は `CI run:` fieldと
Re-audit (1)節を参照）:

- run 34865190872（`CI` workflow、`pull_request`、attempt 2）: `in_progress`。
  job別: `changes` / `check-style` / `build-debug-apk` / `organizer-unit-tests` /
  `validate-repo-contract` / `organizer-instrumentation-{api35,issue53,issue99,
  db-migration,issue155,issue299,shared-writer}-tests` の13 jobがpass。
  **`organizer-instrumentation-issue52-tests` がpending、`final-status` は未実行**。
  attempt 1（run 34865190873）はcancelled。shared-writer job（104051988446）のlogに
  新test class `RestoreLeaseDeferredLoaderThreadAffinityTest` がlane class listに含まれ
  `BUILD SUCCESSFUL in 6m 11s` であることを本auditがlog直接確認（新回帰のCI実行・成功の一次証跡）。
- High-risk gate run 34865248058: `failure`。失敗理由は
  「no docs/assessment/pr-319-\<slug\>.md audit record for this PR」 —
  本audit記録がまだcommitされていないことによる構造的失敗であり、本記録をdocs-onlyで
  headに積むことで解消する経路である（validatorはaudit記録pin先以降の差分が `docs/`
  のみであることを要求・検証する）。
- **merge条件**: (1) run 34865190872が `final-status` successで完了すること
  （issue52 laneの完了を含む。pending中のためにmerge判定は不能）、
  (2) 本audit記録がdocs-only commitとして追加されること。それ以外のcode変更が
  入った場合は本auditを無効とし再auditを要する。

実施者が記録した実行（PR本文・assessment。**実施者報告** — 本auditは再実行していない）:

```text
新規regression red（未修正code、emulator nunu_qpr2_api36_1 / API 36） → FAILED（期待どおり）
新規regression green（修正後code、同環境） → PASSED
connectedLawnWithQuickstepGithubDebugAndroidTest（shared-writer lane全体） → 62/62 PASS
NovaRestoreCapture{Control,WidgetWindow,UnknownProvider}Test → 5 tests PASS
Nova restore cross-process pair（CI同一手順の手動install + am instrument + force-stop） → OK × 2
testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.*' 等 → 1131 tests PASS
  （ReadinessGateTest 1件の初回timing flakeを孤立再実行+全体再実行で回収 — 本PR変更と無関係とする実施者判断）
./gradlew spotlessCheck → PASS
./gradlew assembleLawnWithQuickstepGithubDebug → PASS
python3 tools/repo-contract/measure_upstream_patch_surface.py --verify → PASS
  （LoaderTask.javaは所有済みbridgeグループ内との確認を含む）
```

上記のうち新testのred/greenは、本auditが静的に確定したchain（deferred時にbare
`runInternal`が解放thread上で動く）が正しければred失敗・green成功は必然的な結果であり、
記録内容に矛盾は無い。ただし一次証拠としてのred/green自体は実施者環境の産物である。

## Findings

- **【blocking findings: 無し】** production code（`LoaderTask.java`）・test・ci.ymlの
  変更は、独立検証したroot-cause chainに対して正しく、既存契約（exact-token capability、
  lease排除、repost規律、assertion強度）を保存している。layout-write / migration pathへの
  新規作用は無い。
- **【merge条件・非阻塞】CI merge gateの未確定**: audited head上のrun 34865190872は
  audit完了時点で `in_progress`（13/14 job pass、issue52 lane pending、`final-status` 未実行）。
  AGENTS.mdの独立エビデンス要件および `high-risk-gate` workflowの機械検証上、
  本runの `final-status` success確定までmergeしてはならない。 pending中の判定保留は
  品質問題ではなく手続き上の未確定である。
  （Re-audit (1)更新: 同run 34865190872はその後completed/successで完了した。ただし
  review対応commitにより監査対象headは `9693a2215f` へ移動しており、現行のmerge条件は
  Re-audit (1)節のとおり run 34869651225 の `final-status` success確定である。）
  （Re-audit (2)更新: run 34869651225は本記録のpushによるsupersedeでcompleted/cancelled。
  現行監査対象head `601aec6407` 上のmerge gateは run 34870668580（completed/success、
  `final-status` green、全source jobs実行済み）で確認済みであり、**CI証跡のmerge条件は
  成立済み**。残るのは本記録のdocs-only commitのみ。）
  （Re-audit (3)更新: 監査対象headは `8b63ae0a34` へ移動。同head上のmerge gateは
  run 34911441712（completed/success、`final-status` green、全source jobs実行済み。
  GitHub APIで全14 jobのconclusionを直接確認）で**成立済み**。残るのは本記録の
  docs-only commitのみ。）
- **【非阻塞・構造的】high-risk-evidence run 34865248058のfailure**:
  「audit記録が存在しない」ことによる失敗であり、本audit記録のcommit（docs-only）で
  解消する。audit記録pin以降にcode変更が入った場合は再auditを要する
  （本記録のHead SHAは `b698e48bc8` を指す）。
  （Re-audit (1)更新: 本記録のcommit `ef2fe7a2eb` 後にgateは一時成立したが
  （run 34867984420 success）、test変更commit `419212c6e8` / `9693a2215f` により
  「changes after the audited Head SHA are not docs-only」として再度failした
  （run 34869307748 / 34869651213）。本Re-audit (1)が新headを直接検証したことで解消する
  経路であり、本記録のHead SHAは `9693a2215f` に更新済み。以後にcode変更が入った場合は
  再度re-auditを要する。）
  （Re-audit (2)更新: Head SHAを `601aec6407` へ再pin（deltaは本記録自身のdocs-only）。
  同head上のHigh-risk gateは、本記録のcommit後にrun 34870668580（completed/success）を
  CI merge gate証跡として機械検証される。）
  （Re-audit (3)更新: Head SHAを `8b63ae0a34` へ更新（deltaは `5b5695caca` の本記録
  docs-only commit + test/docs 3ファイルのreview対応2 commit。production差分0行 —
  Re-audit (3)節参照）。同head上のHigh-risk gateは、本記録のcommit後に
  run 34911441712（completed/success、`final-status` green）をCI merge gate証跡として
  機械検証される。以後にcode変更が入った場合は再度re-auditを要する。）
- **【非阻塞・記録済みdeviation】TA-AC-03「繰り返し」の方式置換**:
  実restore → reload cycleの反復走査ではなく、決定論的窓再構成test単発 +
  既存Nova restore lanesで证明している。assessment §5が置換理由（レース待ちの反復は
  違反不在を証明できない）を明示しており技術的に妥当だが、実lifecycleでの
  障害signature不在の直接反復記録は存在しない。将来のrestore検証（#287系セッション等）で
  signature再発を観測した場合は本修正の外に原因を求める前に再openすること。
- **【非阻塞・boundary】Handler signatureの直接再現なし**: red実行ではicon cacheのISEが
  先行し `Can't create handler inside Thread[NovaBackupRestore]` は観測されなかった。
  assessment §1.2/§2.2が「生成siteの事後特定は修正により不能。chain全体の排除対象」
  と記録済みで、本fixは `runInternal` 呼出木をrestore thread上で実行させないため
  生成siteがどこであれ当該窓からは発生しない。残余リスクは確認されなかった。
- **【非阻塞・観察】re-postの試行回数は上限無し**: `MODEL_EXECUTOR.execute(this)` による
  再admissionは回数制限を持たないが、1循環ごとに外部lease解放を1回要求するため
  進展なしのbusy loopは構造的に不可能であり、既存のModelWriter規律と同一。
  対応不要。
- **【Cosmetic】assessment §3の「发生する」等の表記揺れ**: 動作影響なし。docs-onlyで整理可能。
- **独立sessionとしての限界の明示**: emulator red/green・connected laneのlocal実行・
  JVM unit testのlocal再実行は行っていない（emulator testは時間制約によりscope外、
  JVM unit test surfaceはCI `organizer-unit-tests` がaudited head上でgreen）。
  本auditの判定は (1) pre-fix/post-fix code実読によるchainと契約保持の静的確定、
  (2) audited head上のCI実行結果とjob logのGitHub API直接確認、に基づく。
