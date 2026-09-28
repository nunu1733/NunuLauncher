# Independent audit: PR #391 Usage Access要求をjust-in-time化する（#371）

> Status: accepted（audit完了。2026-09-21 再監査 — 新head `1923d51928bc` 分を追記、下記「Re-audit」節）
> Audit date: 2026-09-21（初回監査 @ `05ca58944047`、同日 再監査 @ `1923d51928bc`）
> Verdict: **条件付きGO**（新head `1923d51928bc` で再確認。実装review loop＋CI修正loopの最終確認が
> いずれもNo findingsのheadと監査対象headが一致し、受入条件JIT-AC-01〜08は監査者の独立読み取りと
> 再実行で確認。mergeは本headでのCI `final-status` green確認を条件とする。詳細は Re-audit節の
> CI status / 最終判定）

- Auditor: 実装を行っていない独立session（実装agent/実装sessionとは別の監査として実施。production/test codeの修正は行っていない）
- PR: https://github.com/nunu1733/NunuLauncher/pull/391
- Head SHA: `1923d51928bcd174328e399c34f1ab1ee4fc2c61`
  （初回監査対象は `05ca58944047a10fd29ac19e86d81718645267ab`。PR headの更新に伴い同日再監査。
  いずれも `gh pr view 391 -R nunu1733/NunuLauncher` の `headRefOid` とworktree HEADの一致を確認。`git status` クリーン）
- Base: `main`（merge-base `b21b186495`。`git diff origin/main...HEAD` は17 file、+3144/−45。
  `git diff --check` でwhitespace error 0件）
- CI run: https://github.com/nunu1733/NunuLauncher/actions/runs/35545992004 ／ high-risk-evidence:
  https://github.com/nunu1733/NunuLauncher/actions/runs/35545992002（監査時点で大半のjobが
  pending。job単位の結果は「CI status」節）
- Criteria: [specs/371-usage-access-jit-request/spec.md](../../specs/371-usage-access-jit-request/spec.md)
  （accepted、JIT-AC-01〜09、Test oracle表）、[plan.md](../../specs/371-usage-access-jit-request/plan.md)
  （accepted、Verification表）、[specs/203-usage-implicit-preference-signals/spec.md](../../specs/203-usage-implicit-preference-signals/spec.md)
  （本PRによる2026-09-21 amendment後）、Issue #371本文・全コメント（Phase1 review loopに加え、
  実装review loop 3ラウンド: 6指摘→対応、3指摘（process-wide token/dispose無効化等）→対応、
  1指摘（`resetForTests()` のrunner構築前移動）→対応、最終確認 **No findings** @ `05ca58944047` の記録を照合）、
  accepted disposition `docs/product/organizer-disposition-migration.md` §3.10/§4.1/§5/§7.3（spec/planの参照）
- 監査方法: PR本文・`git diff origin/main...HEAD` 全差分（17 file）review、変更後production/test/spec
  sourceの実読み取り、下記「独立再実行」のコマンド実行、`gh pr checks` によるCI照合。受入条件の判定は
  実装者の主張（PR本文・Issue snapshot）を引用するだけでなく、監査者自身のcode読み取りで再確認した。

## Scope

diff全体（17 file）:

- Source（7 file）: `UsageAccessJitRequest.kt`（新規413行。gate＋dialog＋bounded re-read helper）、
  `ManualOrganizationRun.kt`（+174。pause state 2種・入口判定・CAPTURE commit集約・cancel/dismiss連携）、
  `exchange/ExchangeFlowUi.kt`（+240。attempt token・`AwaitingUsageAccessJit` variant・dispose連携）、
  `ManualOrganizationFace.kt`（+5。face mapping 2行のみ）、`ManualOrganizationPreferences.kt`
  （dialog host＋`exchangeHolder.dispose()` のDisposableEffect）、`OrganizerUsageMaterialRows.kt`
  （**javadocのみ。実装・状態管理は無変更**）、`lawnchair/res/values/strings.xml`＋`values-ja/strings.xml`
  （JIT dialog 5 key新規＋T-06 row文言2 key改訂。双locale）
- Test: `UsageAccessJitInstrumentationTest.kt`（新規433行・8 test）、`UsageAccessJitGateTest.kt`
  （新規・16 test）、`ExchangeFlowJitGateTest.kt`（新規・6 test）、`ManualOrganizationRunTest.kt`
  （+9 test）、`ManualOrganizationFaceTest.kt`（+12行）
- Docs/spec: specs/371（spec/plan新規・accepted）、specs/203 amendment（11行）
- CI: `.github/workflows/ci.yml`（issue52 instrumentation laneへ `UsageAccessJitInstrumentationTest` 追加の1行）

### 非変更範囲の検証 — PASS

`git diff --name-only origin/main...HEAD` の全pathを確認し、次を検証した:

- composer（`OrganizationInputComposer`）/ `PersonalizationSignalSnapshotSource` / `UsageAccess.kt`
  predicate / exchange flow controller / manifest / preference key・route schema / persistent store /
  run-journal / diagnostics契約 / Launcher layout DB・`favorites`: **変更なし**
  （ホームレイアウト安全規約は適用対象外と判定）
- 新しいpermission・外部送信・dependency追加: **なし**（`AndroidManifest.xml` 等へのdiff 0件）
- spec 203のsnapshot・provenance契約節（AC-11〜AC-16 / U-4）: amendment diff上**無変更**
  （diffはfrontmatter updated・amendment注記・permission表2行・AC-17〜19追加・U-2のみ）

## 独立再実行（worktree `/Users/nunu/Documents/work2/NunuLauncher/worktree-371`、head `05ca58944047`、2026-09-21）

| command | 結果 |
|---|---|
| `git rev-parse HEAD` | `05ca58944047a10fd29ac19e86d81718645267ab` = PR headRefOidと一致 |
| `./gradlew spotlessCheck` | **PASS**（exit 0） |
| `./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.*' --tests 'app.lawnchair.ui.preferences.navigation.*' --tests 'app.lawnchair.bugreport.*' --tests 'app.lawnchair.backup.*'` | **PASS**（exit 0。`build/test-results` のXML **148 suite / 1607 tests、failures 0、errors 0、skipped 0**。結果XMLのmtimeは監査実行時刻と一致することでキャッシュ流入でなく実行の証跡を確認） |
| `./gradlew compileLawnWithQuickstepGithubDebugAndroidTestSources` | **PASS**（exit 0） |

JIT要求のinstrumentation（`UsageAccessJitInstrumentationTest` 8 test。実app-op付与復帰・deny fallback・
Back非漏出・cross-origin等）は本監査ではemulator実行していない（compileまで）。実行PASSの根拠は
実装review loopの記録とCI `organizer-instrumentation-issue52-tests` lane（監査時点pending、下記）に依存する。

## 契約spot check（監査者の実読み取り）

| 項目 | 判定 | 確認内容 |
|---|---|---|
| gate state machine | **PASS** | `UsageAccessJitGate`（`UsageAccessJitRequest.kt:71-184`）: `Phase` は `Available/Reserved/Presented/Resolved` の4値。`evaluate`（101-119行）は `Reserved/Presented→Wait`、`Resolved→Proceed`、`Available`＋付与済み→**その場で `Resolved` へ遷移してProceed（機会消費。spec scenario「初回trigger時に付与済み」どおり）**、`Available`＋未付与→原子的 `Reserved` 遷移に成功したcallerのみ `Present`。`markPresented`（121-128行）は `Reserved→Presented` のみで待機者は解放しない、`resolve`（130-137行）は `Presented→Resolved` で初めて待機者を解放、`release`（139-146行）は `Presented` 以降には効かない（1回限り契約）。全methodが同一monitor下でowner一致check付き |
| abandon原子性 | **PASS** | `abandon(owner)`（159-168行）は「`currentOwner != owner` ならreturn」の後、state別規則（`Reserved→release相当`、`Presented→resolve相当＝放棄解決`、それ以外無作用）を**同一 `synchronized(lock)` 内でread-and-act完結**。racingな `markPresented` がownerを `Presented` 孤児化させる窓は構造的にない。呼び出し側はrun `cancel()`（`ManualOrganizationRun.kt:1108`）/ `dismiss()`（1332行）とexchange側の全退場経路（`openFlow`/`openImport`/`close`/`requestGeneration`/`confirmReplacementAndGenerate`/`dispose` の各先頭。`ExchangeFlowUi.kt:274-346`） |
| 観測seam | **PASS** | gateはrevision counter付き不変 `Snapshot` の `StateFlow` を公開（93-99行、`transitionLocked` で毎遷移発行）。run側host（`RunUsageAccessJitDialogHost` 359-372行）とexchange側host（`ExchangeUsageAccessJitDialogHost` 1139-1149行）はcollectによる決定的観測で解決/解放を扱い、pollingや偶然の再compositionに依存しない。`LaunchedEffect(gateSnapshot, ...)` による再評価で、`Resolved` なら継続、`Available` 復帰なら再獲得競争（原子的遷移に成功した1つのみ提示） |
| bounded re-read定数 | **PASS** | `USAGE_ACCESS_JIT_GRANT_WAIT_LIMIT_MS = 1500L`（236行。specの0.5〜2秒レンジ内）、poll 250ms（238行）。`awaitUsageAccessGrant`（240-254行）はGRANTED観測で即return（上限待たず）、上限到達で `false`。sleepは注入可能（既定 `delay`）で固定sleepは使わない。unit testがproduction定数を同一source-of-truthから取得しレンジ内assert（`UsageAccessJitGateTest.kt:174-176`）と `limit` 到達fallback・`limit` 未満の継続待機・早期GRANTED終了（186-230行）を検証 |
| run側pause位置 | **PASS** | `runComposedPhase`（`ManualOrganizationRun.kt:787`）の**入口**で `usageAccessGate.evaluate(runId)`（800行）。RD-6 cancel gate／journal open（827行以降）より**前**であり、`Present/Wait` 時は `State.AwaitingUsageAccessJit` をpublishしてreturn（journal・`RUN_STARTED`・composition不発生）。検出 `Unavailable` 継続・D-06 0件内部継続・選択確認の3経路がすべてこの単一入口を通るchoke point構造。pause中はRUN lease保持（2回目startは既存gateでBusy）。cancel勝利時のrace（evaluate後〜lock前）では `release(runId)` で予約を巻き戻し（803-811行） |
| CAPTURE publish位置 | **PASS** | `State.Capturing` ＋ `preparationPhase=CAPTURE` ＋ `journalStarted=true` ＋ `RUN_STARTED` emitはRD-6 lock区間内の**同一critical section**（827-850行。phase BEFORE state順）。`confirmSelection`/`continueWithEmptySelection` はCAPTUREをpublishせず `runComposedPhase` を呼ぶのみ（642行comment・652行確認）。JIT resume経路（`continueAfterUsageAccessGate` 669-687行）はlock内で `State.ResumingUsageAccessJit` へのclaimのみ行い（可視 `Capturing` はpublishしない）、lockを抜けてから `runComposedPhase` を正確に1回呼ぶ — 二重callback（ON_RESUME＋継続・二重tap）でも2回目はclaim失敗でno-op。admission時の `Capturing`（1412行）は `preparationPhase=DETECTION` 併用の既存挙動でありcapture開始commitではない |
| cancel/dismiss連携 | **PASS** | `cancel()`（1083-1123行）/`dismiss()`（1296-1347行）ともcancel可能状態へ `AwaitingUsageAccessJit`/`ResumingUsageAccessJit` を含め、`destroyUsageAccessGateOwnership(runId)` → `abandon(runId)` を呼ぶ。journal guard（`operation.journalStarted`）によりpre-RUN_STARTEDのcancel/dismissはjournal eventを発生させない（1102-1122・1333-1344行）。lease closeは既存経路でexactly once |
| exchange attempt token | **PASS** | `generate`/`generateScoped` の先頭で `UsageAccessJitGateProvider.nextAttemptToken()`（process-wide `AtomicLong`。`UsageAccessJitRequest.kt:191-223`）によりtoken発行（`ExchangeFlowUi.kt:355-367・429-446・680行`）。holder固定counterではなく、holder再生成跨ぎのABAを排除。`ExchangeScreen.AwaitingUsageAccessJit(attemptToken, tier, scoped, isPresenter)` はstate machine本体のvariant（machine外stateではない）。`continueUsageAccessJit`（377-386行）は現行screenのtoken一致でのみ単回適用 — 遅延callback・close後再生成のstale resumeはtoken不一致でno-op |
| dispose時の無効化 | **PASS** | `dispose()`（314-319行）は `abandonAwaitingUsageAccessJit()`（`abandon` によるstate別規則）の後 `screen = Closed` で保留attempt自体を無効化。host側は `ManualOrganizationPreferences.kt:298-300` の `DisposableEffect(exchangeHolder) { onDispose { exchangeHolder.dispose() } }` で経路leak —route change/activity再生成でも `Reserved` 永久保持・`Presented` 孤児化は構造的に発生しない |
| spec 203 amendment | **PASS** | U-2を「常設管理row（T-06）＋初回signal読み取り直前のJIT要求1回（同一run内再促しなし規則維持・process-local永続化なし）」へ改訂。Permission and fallback behavior表の「opt-in (初回)」行rationaleをprivacy修飾形（raw履歴非送出/bucket化/送信前確認経由）へ更新し「JIT要求 (#371)」行を追加。AC-17〜19追加。snapshot・provenance契約節のdiff無変更。amendment注記に#371と処置文書§3.10の二段階所有を記録 |
| strings（EN/ja） | **PASS** | JIT dialog 5 key（`organizer_usage_access_jit_title/_body/_open_settings/_continue/_settings_unavailable`）とT-06 row 2 key（`organizer_personalization_usage_access_granted/_not_granted`）がEN（`values/strings.xml`）・ja（`values-ja/strings.xml`）双方に存在。監査者が意味要素checklistを自ら検証: JIT body（EN）は「Asking now because organizing is about to read app usage（なぜ今）」「coarse usage hints improve placement quality（何のため）」「skipping is fine and organizing works fully without it（断った影響＋任意性）」「raw usage history never leaves this device / AI consult confirms before sending（privacy修飾）」の全要素を1文で満たす。ja文言も同等の要素を1文で持つ（直訳でなく自然なja。bucket化は「粗い使用状況のヒント」で表現）。T-06文言も管理rowの「なぜ今」＋修飾形privacyを満たす。format resource不使用（placeholderなし）のためplaceholder一致要件は空集合として充足 |

## Criteria check（JIT-AC-01〜09）

| AC | 判定 | 根拠（監査者の独立読み取りと再実行） |
|---|---|---|
| JIT-AC-01 | **PASS**（静的） | 契約spot checkのとおりpauseはcomposition直前の単一入口。unit 9 test（3経路pause・選択中断非消費 `dSixEmptyCutContinuationPausesAtTheSameChokePoint`/`selectionConfirmationPausesWithTheSelectionAndResumesScopeComposed`/`grantedFirstTriggerNeverPausesAndConsumesTheOpportunityForTheProcess` 等）が監査者のunit再実行でgreen。instrumentation（run開始→表示→解決→composition、候補あり経路の否定oracle）はCI lane依存 |
| JIT-AC-02 | **PASS** | 文言の意味要素は監査者がEN/ja実fileで全要素確認（前表）。placeholderは無し。ただしoracleの「resourceの存在の機械確認」を担うunit testは新設されておらず（下記Findings 1）、存在保証はproduction `R.string` 参照のcompile保証＋監査者のdiff reviewによる |
| JIT-AC-03 | **PASS**（静的） | 1500ms（レンジ内）・GRANTED即時終了・注入可能clock。unitの決定的oracle（`limit` 境界・早期exit・レンジassert）は監査者の再実行でgreen。contract commitでの具体値確定（1500ms）と記録はPR本文にあり。実app-op付与復帰のinstrumentationはCI lane依存 |
| JIT-AC-04 | **PASS**（静的） | `NotReady` 不発生の構造（gateはcomposerに触れない・snapshot読み取りは従来seamのまま）＋composer回帰test無編集green（監査者のunit再実行に含まれる）。遷移失敗（`ActivityNotFoundException` catch→dialog維持＋text表示＋続行、`openUsageAccessSettings` 322-327行・dialog 286-294行）の単一normative経路を実装確認。instrumentation exact oracleはCI lane依存 |
| JIT-AC-05 | **PASS**（静的） | gate unit 16 test（競合で `Present` 1つ・提示後非復活・abandon×markPresented両順序＋concurrent 64回・観測seam・stale owner非作用・attempt identity bind）＋run unit（waiter進行・二重継続で `RUN_STARTED` 1回）＋exchange unit 6 test（token一致単回resume・stale resume破棄）が監査者の再実行でgreen。`resetForTests()` は両settings-return testでrunner構築前に呼ばれることを実file確認（196行・234行。最終review指摘の解消） |
| JIT-AC-06 | **PASS** | `OrganizerUsageMaterialRows.kt` はjavadocのみで状態管理契約（表示・遷移・`ON_RESUME`再読取）はdiff 0件。文言改訂の意味要素は監査者が確認。T-06回帰のinstrumentation（`OrganizerDiagnosticsRouteInstrumentationTest` がgranted/not_granted文言を参照）はCI lane依存。既存unitは無編集でgreen |
| JIT-AC-07 | **PASS** | spec 203 amendment diffを監査者が全行review（前表）。snapshot/provenance契約節の無変更をdiff grepで確認 |
| JIT-AC-08 | **PASS** | manifest/persistent store/diagnostics/journal eventへのdiff 0件（非変更範囲検証）。failure path（遷移失敗・pause中cancelのjournal無event・lease exactly once release・二重継続でRUN_STARTED 1回・選択中断非消費）はunit test群でgreen。CAPTURE commitが入口gate区間より前にpublishされない構造は実装とunit oracleで確認 |
| JIT-AC-09 | **部分**（evidence未確定） | instrumentation 8 testの1つが200% font scale到達testでcompile PASSを監査者確認。ただしTalkBack/focus復帰/emulator screenshot（light/dark × ja/default）のevidenceはCI laneのartifactで補完する前提であり、監査時点では未確定（CI pending。spec Test oracleのとおりevidence要求） |

## CI status（監査時点、2026-09-21、run 35545992004 @ `05ca58944047`）

| 結果 | job |
|---|---|
| pass（3） | changes（14s）/ validate-repo-contract（53s）/ high-risk-evidence（32s、run 35545992002） |
| pending（11） | build-debug-apk / check-style / organizer-unit-tests / organizer-instrumentation-api35-tests / organizer-instrumentation-db-migration-tests / organizer-instrumentation-issue155-tests / organizer-instrumentation-issue299-tests / organizer-instrumentation-issue332-tests / organizer-instrumentation-issue52-tests（**本PRのoracle class `UsageAccessJitInstrumentationTest` を含むlane**）/ organizer-instrumentation-issue53-tests / organizer-instrumentation-issue99-tests / organizer-instrumentation-shared-writer-tests |
| 最終 | `final-status` = **監査時点未確定** |

本PRには `risk: layout-data` / `risk: migration` labelは付与されておらず（`gh pr view` でlabels空を確認）、
変更pathもLauncher DB・migration対象外のため `high-risk-gate` の独立エビデンス要件自体は適用外
（high-risk-evidence jobはpass）。ただし `final-status` は通常のmerge gateであり、確定を待たずに
mergeへ進めない。本PRの新規instrumentation classを含むissue52 laneの結果が特に要確認である。

## Findings

1. **JIT-AC-02 oracleの「resource存在の機械確認」を担うunit testが存在しない（軽微・oracle文字面からの逸脱）**:
   新規JIT文言5 key（EN/ja）の存在・placeholder一致を機械確認するunit testは追加されていない
   （T-06文言を参照する既存instrumentationはあるが、JIT key自体のname-based assertは無し）。
   実害は限定される — production `R.string` 参照によりdefault localeの存在はcompile時に保証され、
   ja側の存在は監査者のdiff reviewで確認済み、かつ文言はformat resource不使用（placeholderなし）のため
   placeholder一致要件は空集合として自足する。しかしspec Test oracleは「単体testでresourceの存在と
   placeholder一致を機械確認」を要求しており、文字面は未充足。ja文言欠落が将来のrefactorで
   静かに起きる（fallbackでEN表示される）回帰検出網は無い。docs-onlyでないtest追加を伴うため
   本監査では修正しない（監査は実装を変更しないため）。追後PRまたは#372系の共有面re-entry時の
   追加を推奨する。
2. **PR本文の「意味要素checklist」は説明文としての記録（軽微・形式）**: JIT-AC-02/06は「意味要素checklistを
   PR evidenceに記録」を要求する。PR本文は3要素・任意性・privacy修飾の各要素を説明文として列挙しており
   実質は満たすが、checklist形式の表ではない。監査者が要素を独立検証して全充足を確認したため
   受入条件の実質は損なわれないと判定した。
3. **PR本文のtest数表記のずれ（参考・非material）**: gate unit testを「14」と記載するが実fileは
   16 test。実装より少ない申告であり安全方向のずれ。他の申告数（run 9・exchange 6・instrumentation 8）は
   実数と一致。
4. **CI未確定job（監査時点）**: 11 jobがpending。`organizer-instrumentation-issue52-tests`（本PRの
   新規instrumentation class含む）と `organizer-unit-tests` の完了を待たずに本監査のscopeでは
   判断しない（merge gate）。監査者の独立再実行（unit 1607 tests green・spotless・instrumentation
   compile）は本headで成立済み。

## 未確認範囲

- 監査者自身によるemulator instrumentation実行は未実施（本監査の再実行scopeはspotless/unit/
  instrumentation compile）。JIT flowの実機振る舞い（実app-op付与復帰・deny fallback・Back非漏出・
  cross-origin dialog 1つ・200% font scale）は実装review記録・CI lane（監査時点pending）に依存する。
- 実device/OEM matrixでの `ACTION_USAGE_ACCESS_SETTINGS` 解決可否（unsupported pathは契約・
  test注入済み。device evidence未取得 — planのexplicitly unverified areasのとおり）。
- 付与直後のapp-op伝播timingの実device分布（0.5〜2秒レンジ内の具体値1500msの根拠はprobe evidenceの
  1秒待機。実測分布は未取得）。
- onboarding moment（onboarding由来runのcomposition直前）でのJIT要求表示のproduct受容
  （spec Open questions 1。owner review待ち。表示自体は現行実装のspecどおりの挙動）。
- JIT-AC-09のscreenshot/aspect evidence（CI artifact依存。監査時点未確定）。
- CI pending jobの完了後の状態（`final-status` を含む）は監査外のmerge gate。

## 最終判定

**条件付きGO。**

- 受入条件JIT-AC-01〜08は、監査者自身のコード読み取り（gate state machine・abandon原子性・観測seam・
  bounded re-read・run入口pause・CAPTURE commit集約・cancel/dismiss連携・exchange attempt token・
  dispose無効化・spec 203 amendment・EN/ja文言の意味要素）と本headでの独立再実行（`spotlessCheck` /
  unit gate 148 suite 1607 tests 0 failure / instrumentation compile、すべてPASS）により満たす。
  JIT-AC-09のみevidenceがCI artifact依存で監査時点未確定である。
- 実装review loop（3ラウンドのChanges requested→対応→最終No findings）の記録をIssue #371コメントで
  照合した。実装最終承認対象headは `05ca58944047` であり、承認後の実質変更はない。
  監査で挙げたFindings 1〜3は受入条件の実質を損なわない軽微事項である。
- **merge条件**: 本head `05ca58944047` でのCI `final-status` green（pending 11 jobの完了確認を含む。
  特に本PRの新規instrumentation class `UsageAccessJitInstrumentationTest` を含む
  `organizer-instrumentation-issue52-tests` と `organizer-unit-tests`）を条件とする。
  失敗jobが出た場合は、本PR由来か否かをjob単位で切り分け、本PRと無関係な場合は別途起票のうえ
  先に対処すること。JIT-AC-09のemulator evidence（screenshot/TalkBack）はCI lane完了後に
  PR記録へ残すこと。

---

## Re-audit（新head `1923d51928bc`、2026-09-21）

初回監査（@ `05ca58944047`）後、PR headがCI実行で判明した修正とreview対応3コミットで更新された
ため、同日に再監査を実施した。merge条件は変わらず「本headでのCI `final-status` green」である。

### 追越コミット（`05ca58944047..1923d51928bc`、3件）

| commit | 内容（監査者のdiff読み取り） |
|---|---|
| `8a52cf9306` | CI修正: (a) **#370 test-only render traceの復元** — `ManualOrganizationPreferences.kt` へ `committedFace` ＋ `SideEffect { ManualOrganizationRunFaceTrace.recorder?.invoke(committedFace) }` を復元（実装者が誤削除していたもので、recorderはproductionでnullのため挙動不変。#370の監査記録どおりのtest-only観測seam）。(b) `Issue265ManualEditRecoveryInstrumentationTest` のsetUpへapp-op付与（shell `appops set ... allow` ＋1秒待機。production wired runがJIT pauseに捕まらないgranted fast path。#371導入による既存testの正当な適合）。(c) JIT instrumentationのsettings往復2本をnavigation依存から確定的なproduction predicate観測へ置換（後続commitでlifecycle駆動へ確定化）。(d) cross-origin oracleをproduction実挙動へ整合（下記）。(e) exchange dialog hostのunmount cleanup追加（後続commitでattempt-bindへ強化） |
| `85b8b63d81` | review対応: (a) **`ExchangeFlowStateHolder.disposeUsageAccessJitAttempt(token)`** 新設 — 現行screenのtoken一致時のみabandon＋無効化。dialog hostの `DisposableEffect` は効果作成時のtokenをcaptureしてonDisposeで使用（stale host unmountが新attemptへ作用しない。JIT-AC-05 identity bindingの補強。新規unit oracle `staleAttemptTeardownDoesNotActOnANewerAttempt` 付き）。(b) **`RunUsageAccessJitDialogHost` のstale observer実バグ修正** — host状態（presenter/settingsRequested/settingsLaunchFailed/grantCheckTick）をkey付き `remember(awaitingRunId)` からunkeyed `remember` ＋ `DisposableEffect(awaitingRunId)` のreset effectへ変更。key付きState再生成でlifecycle observerが古いStateを読み続け、settings復帰時のbounded re-read→resumeが発火しないCI/ローカル実行で判明した実バグの修正（reset effectは同一State instanceを再導出するため、観察者と状態の整合が回復する）。(c) settings-return 2本をinjected lifecycle（`TestLifecycleOwner` ＋ ON_PAUSE/ON_RESUME drive）へ確定化 |
| `1923d51928` | test: `newProductionRunner(application)` helper（`resetForTests()` → gate取得 → runner構築の順序を1箇所に固定。reviewで指摘された順序逆転再発の構造的防止。両settings-return testともhelper経由） |

### 契約への影響判定 — 本体契約は不変

- **不変をdiffで確認**: `UsageAccessJitGate` 本体（state machine・abandon・snapshot seam）、
  `ManualOrganizationRun.kt`（**追越コミットでの変更0件**。pause位置・CAPTURE commit集約・
  cancel/dismiss連携は初回監査時の確認どおり）、bounded re-read定数（1500ms）、
  spec 203 amendment、strings（EN/ja）、CI workflow行はいずれも `05ca58944047..1923d51928bc` の
  diff対象外。
- **production変更は2点のみ**（いずれも契約強化で契約違反なし）:
  1. host状態のunkeyed remember化＋reset effect（上記バグ修正。pause identityごとの再導出は
     初回監査で確認した「key付きrememberの意図」と同じ意味論を、State instanceの安定性を保って
     実現し直したもの。gate・run state machineへは触れない）
  2. exchange dialog host unmountのcleanupを `dispose()`（holder全体）から
     `disposeUsageAccessJitAttempt(ownToken)`（attempt-bound）へ限定。holder全体のteardownは
     `DisposableEffect(exchangeHolder)` 経由の `dispose()` が引き続き担い、役割分担は明確化。
     stale unmountが新attemptをabandonする経路が塞がれた点でJIT-AC-05の強化である。
- **test seam追加**（production既定値不変）: `ManualOrganizationPreferences` へ
  `jitLifecycleOwner: LifecycleOwner? = null`（既定は従来どおり `LocalLifecycleOwner.current`）。
  `usageAccessSettingsOpener` は `05ca58944047` 時点から存在する既存seam。
- **cross-origin oracleの更新は契約の弱化でない**: run pauseでIdle faceを抜けるとexchange sectionが
  unmountする実挙動に合わせ、oracleは「presenter unmount → attempt-bound teardown → 放棄解決 →
  waiter観測seamで進行（test側はcontinueを呼ばない）→ 破棄されたattemptの生成は再開されない
  （generationAttempts == 0・screen Closed）」を検証する。barrier livenessと
  「放棄された生成の無効化」の両方を自動で確認する、初回版より強いoracleである。

### 再検証（worktree同所、head `1923d51928bc`、2026-09-21）

| command | 結果 |
|---|---|
| `git pull` 後の `git rev-parse HEAD` | `1923d51928bcd174328e399c34f1ab1ee4fc2c61` = PR headRefOid一致、worktreeクリーン |
| `git diff --check origin/main...HEAD` | **PASS**（whitespace error 0件） |
| `./gradlew spotlessCheck` | **PASS**（exit 0） |
| `./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.*' --tests 'app.lawnchair.ui.preferences.navigation.*' --tests 'app.lawnchair.bugreport.*' --tests 'app.lawnchair.backup.*'` | **PASS**（exit 0。XML **148 suite / 1608 tests、failures 0、errors 0、skipped 0**。+1件は新規stale teardown oracle。結果XMLのmtimeは再監査実行時刻と一致） |
| `./gradlew compileLawnWithQuickstepGithubDebugAndroidTestSources` | **PASS**（exit 0。追越コミットでtest変更があるため再確認） |

### Review loop記録の照合（新head分）

Issue #371コメントで次のloopを確認した: 初回監査対象head `05ca58944047` の実装最終確認（No findings）→
CI修正1（`8a52cf9306`）→ 再レビュー2指摘 → 対応（`85b8b63d81`）→ 再レビュー1指摘
（settings-return testでの `resetForTests()` 順序再逆転）→ 対応（`1923d51928` のhelper化）→
**CI修正3最終確認 No findings**（対象head `1923d51928bcd1`、test fixture順序のみの差分と確認済み）。
実装最終承認対象headは `1923d51928bc` であり、承認後の実質変更はない。

### CI status（再監査時点、2026-09-21、run 35553671592 @ `1923d51928bc`）

| 結果 | job |
|---|---|
| pass（14） | changes（17s）/ validate-repo-contract（43s）/ check-style（1m13s）/ build-debug-apk（3m57s）/ organizer-unit-tests（4m27s）/ high-risk-evidence（35s、run 35553671582）/ organizer-instrumentation-api35-tests（7m3s）/ db-migration（9m7s）/ issue155（10m42s）/ issue299（10m25s）/ issue332（9m11s）/ issue53（9m54s — **#370 render trace復元のrender oracleを含むlane**）/ issue99（6m25s）/ shared-writer（9m13s） |
| pending（1） | organizer-instrumentation-issue52-tests（**本PRのoracle class `UsageAccessJitInstrumentationTest` を含むlane**） |
| 最終 | `final-status` = **再監査時点未確定**（pending 1 jobの完了待ち） |

初回監査時点で未確定だった11 jobのうち、`organizer-unit-tests` とinstrumentation laneの大半は
新head runでpassが確定した（初回監査対象head `05ca58944047` のrun 35545992004は旧headとして
置き換わり、本記録のCI根拠は新head runに更新される）。

### Re-audit Findings

1. **Findings（初回監査1〜3）の再判定**:
   - 初回Findings 1（JIT文言のresource存在機械確認unit test欠落）: 追越コミットでも未整備のまま。
     受入条件の実質への影響判定は初回どおり（軽微・追後推奨）。
   - 初回Findings 2（意味要素checklistが説明文形式）: 変化なし（軽微・形式）。
   - 初回Findings 3（gate unit数表記ずれ14→実16）: 変化なし（参考・非material。exchange unitは
     追越コミットで+1の7 test）。
2. **stale observer修正の評価（指摘に至らない注記）**: host状態のunkeyed remember＋reset effectは、
   Composeのeffect capture意味論に対する正しい修正であることを監査者が独立に確認した
   （lifecycle observerは `DisposableEffect(lifecycleOwner)` で1回生成され同一State instanceを
   読み続けるため、State instanceを置き換えず内容を再導出する方式が整合する）。reset effectは
   composition順でlifecycle observerより先に宣言されており、pause identity変化時に状態が先に
   再導出される順序も確認した。この実バグはCI実行・ローカル実行で判明・修正されたものであり、
   初回監査の静的読み取りでは検出できていなかった点は監査の限界として記録する。
3. **JIT-AC-09のevidence**: 引き続きCI issue52 lane（再監査時点pending）のartifact依存。

### Re-audit 最終判定

**条件付きGO（更新・本head `1923d51928bc` 基準）。**

- 追越3コミットは、test fixtureの確定化・test-only seam追加・#370 render trace復元（test-only）と、
  production変更2点（host状態のstale observer修正・exchange unmount cleanupのattempt-bound化）から
  なる。production変更はいずれも初回監査で確認した契約（JIT-AC-03のresume経路・JIT-AC-05の
  identity binding）の強化であり、契約違反・scope拡大・persistent/permission変更は無い。
  `ManualOrganizationRun.kt` とgate本体・spec/stringsは追越コミットで不変。
- 監査者の独立再実行（spotless / unit 1608 tests 0 failure / instrumentation compile）は新headで
  すべてPASS。実装review loop＋CI修正loopの最終承認対象headは `1923d51928bc`（最終No findings）で
  承認後の実質変更はない。
- **merge条件**（初回から不変）: 本head `1923d51928bc` でのCI `final-status` green。再監査時点の
  未確定jobは `organizer-instrumentation-issue52-tests`（本PRのoracle class含む）1件のみであり、
  その完了とJIT-AC-09のemulator evidence（screenshot/TalkBack）のPR記録を確認すること。
  失敗した場合は本PR由来か否かをjob単位で切り分けること。
