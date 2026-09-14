# High-risk audit: PR #319 restore窓でdeferされたtokenless loaderのMODEL_EXECUTOR再admission

> Status: accepted（verdict: approve。mergeはExecuted test surface節に記録した
> CI merge gateの確定と、本audit記録のdocs-only commitを条件とする。code findings無し）
> Audit date: 2026-09-15

- Auditor: 独立audit session（general-purpose subagent。PR #319の実装を行っていないsession。solo保守のため、同一保守の別sessionとして実装経路に依存しない実読・再確認を実施）
- PR: https://github.com/nunu1733/NunuLauncher/pull/319（base `main`、head branch `issue-298-implementation`、label `risk: layout-data`）
- Head SHA: b698e48bc836f2a7545ff32ebc0fb5cba209f7b0
- CI run: https://github.com/nunu1733/NunuLauncher/actions/runs/34865190872
  （`event=pull_request`、`head_branch=issue-298-implementation`、`head_sha=b698e48bc8…`、
  PR #319関連付け。audit完了時点ではattempt 2が `in_progress`。mergeには本runの
  `final-status` successが確定することを要求する — Findings参照）
- Criteria: specs/298-nova-restore-reload-thread-affinity/spec.md TA-AC-01, TA-AC-02, TA-AC-03, TA-AC-04, TA-AC-05, TA-AC-06
- 調査証跡の正本: docs/assessment/issue-298-wrong-thread-restore-reload.md（以下「assessment」）。
  PR本文・commit message・assessmentの主張は信じず、以下のとおりpre-fix/post-fixの
  code実読とGitHub APIで独立検証した。実施者のemulator実行（red/green等）は再実行していない
  （emulator testは本auditのscope外）ため、すべて「実施者報告」として明記する。

## Scope

監査対象は `b698e48bc836f2a7545ff32ebc0fb5cba209f7b0`。merge-baseは `origin/main` =
`397d3fd95764878366e7c9e5ce41ab65e6f3f9ca`（#317 merge直後）。PR差分は6ファイル
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

CI merge gate（audited head `b698e48bc8` 上。GitHub APIで直接確認。audit完了時点）:

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
- **【非阻塞・構造的】high-risk-evidence run 34865248058のfailure**:
  「audit記録が存在しない」ことによる失敗であり、本audit記録のcommit（docs-only）で
  解消する。audit記録pin以降にcode変更が入った場合は再auditを要求する
  （本記録のHead SHAは `b698e48bc8` を指す）。
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
