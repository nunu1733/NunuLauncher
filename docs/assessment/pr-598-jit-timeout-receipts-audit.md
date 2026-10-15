# PR #598 独立監査 — test(586): JIT settings-return timeoutのfailure-time receipts

> Status: complete (audit)。実装sessionとは独立に、gh CLI (`-R nunu1733/NunuLauncher` 明示) とlocal checkout (branch `issue-586-impl`) によって検証した。コミット・プッシュは行っていない（本ファイルは作成のみ）。
> Date: 2026-10-15
> 監査対象PR: https://github.com/nunu1733/NunuLauncher/pull/598
> 参照spec: `specs/586-jit-timeout-cause-distinguish/spec.md`（PR #597 で2026-10-14T17:11:31Zにmerge、MERGED確認）

## 監査対象

- head SHA: `b1417ef3f18f7c26686e33ead0ed4f8002d25fe5`（local `origin/issue-586-impl` とworking treeのHEADと一致確認済み）
- base SHA: `cd5025514609c774bb1ab84f02a0dc29e8a8ddf3`（main、spec #597のmerge commit）
- Risk tier: L（test-only diagnostics + docs）。`risk: layout-data` / `risk: migration` label無しを確認 → 高リスク独立エビデンス要件（`docs/assessment/pr-<n>-*.md` audit + CI merge gate）は本監査自体が任意実施であり、blocker条件に該当しない。

## 差分の検証

production source変更（2ファイル、`gh pr diff 598` で確認）:

1. `lawnchair/src/app/lawnchair/organizer/ui/UsageAccessJitRequest.kt`
   - `RunUsageAccessJitDialogHost` に `grantCheckTickTracer: ((Int) -> Unit)? = null`（既定null）を追加。
   - grant-check `LaunchedEffect` 内、guard直後（`withContext(Dispatchers.IO) { awaitUsageAccessGrant… }` の前）に `grantCheckTickTracer?.invoke(grantCheckTick)` 1回のみ。既定のnull経路ではno-op。状態遷移・公開API・既存引数の順序・振る舞いは不変。specが要求する「guard通過直後・bounded re-read前」のtick入口観測点と一致。
2. `lawnchair/src/app/lawnchair/ui/preferences/destinations/ManualOrganizationPreferences.kt`
   - 既定nullの `grantCheckTickTracer` 引数を host 呼出しへ素通しする1引数のみ。legacy caller（null既定）はno-op。

その他のproduction source変更は無い（`ManualOrganizationRun.kt`、`RunPublicationThread.kt`、`UsageAccess.kt`、run machine、`ci_portfolio_map.yml` などはdiff外＝spec Non-goalsどおり）。

テスト変更（1ファイルのみ）: `tests/organizer-instrumentation/app/lawnchair/organizer/ui/UsageAccessJitInstrumentationTest.kt`

- (a) 待ち条件・上限不変: settings-return 2テストの最終待ちは `waitUntilInputUnavailableOrCaptureReceipts` に置換されたが、内部は同一条件（`runner.state is State.InputUnavailable`）・同一上限（`timeoutMillis = 30_000`）。pass後の `assertEquals(true/false, usageGrantedAtCompose)` と `RUN_STARTED` assertionは無変更。他テストの6箇所の `waitUntil` はunwrappedのまま（settings-return 2テスト限定のdiagnostics＝spec Scopeどおり）。
- (b) receiptsは `ConcurrentLinkedQueue<Int>` (`GrantCheckTickTrace.enteredTicks`) とjournalの単一snapshot (`application.events.toList()`) から構築。`RUN_STARTED` 有無とevents列は同一snapshotから導出され自己矛盾不能。読み出しはfailure時のみ（passive/append-only契約）。
- (c) compose観察（dialog tag / settings-unavailable tag / `onRoot().printToString()`）はすべて `runCatching` で非throw化し、`<unavailable>` をreceipt値とする。semantics treeは8,000字truncate。`NotReadyApplication.events` も `mutableListOf` から `ConcurrentLinkedQueue` へ変更（worker emissionとのdata race除去で、append-only契約と整合）。
- timeout時は `ComposeTimeoutException` をcauseに保持した `AssertionError` を再throw。cleanup（finally appops復元）前にreceipts取得を満たす（catch→build→throwの順序でfinallyより前）。

## spec AC対応

| AC | 検証結果 |
|---|---|
| AC-1 | receiptsフィールド9項目すべてが `buildSettingsReturnTimeoutReceipts` に出力され、cause保持・passive/append-only・既存owner限定・timeout/lane/filter変更なしをコードで確認。意図発火の証跡XMLで出力内容を実証（下記）。production変更は既定no-op seam1引数のみ。 |
| AC-2 | baseline（impl `34ab763528` 2回 + 最終head `9ac0b7eb60` 1回、対照main 2回）で settings-return 2本は6/6（impl系列）+2/2（対照）PASS。XML解析で `settingsResumeAfterGrant…` / `settingsResumeWithoutGrant…` は全run PASS、時間11–16s帯。 |
| AC-3 | candidate（Direct-publication overlay）5/5 PASS（12–22s）。candidate-overlay.diff は `publicationThread = HandlerRunPublicationThread()` の除去1行のみ（diff解析確認）。原signature（30s無復帰timeout）非再現の記録はassessment docの分類と一致。 |
| AC-4 | `docs/assessment/issue-586-jit-settings-timeout-receipts.md`（本PRヘッドでAdded）：original run事実（CI run 38321690999 / source 77b6b97… / 36.081s / line 259）、対象SHA・環境（API36 arm64 AVD issue142_api36）・回数・結果表、receipt→原因クラス対応に基づく分類（publication-fixture exposure不成立、original unknown維持）、根拠境界と未証明範囲を記録。Issueコメント（id 6176452056）にも同一内容。flake解決主張・timeout増加・恒久lane追加なし。 |

中間SHA `34ab763528`（impl commit）と `9ac0b7eb60`（review round 2 commit）はbranch historyに実在し、assessment docのSHA provenance表と一致。

## 証跡XMLの機械解析

`docs/assessment/586-jit-timeout-receipts/` をpythonでパースしassessment doc/PR本文の主張と突合:

- `impl-class-run1.xml/run2.xml`（impl系列）: 8テスト、settings-return 2本PASS（15.58/12.12s、11.84/12.90s）。`jitDialogBackDeclines…` のみFAIL(30s timeout) — BACK keyevent環境観察（scope外記録）と一致。
- `impl-class-run3.xml`（最終head系列）: 8テスト全PASS、settings-return 15.13/15.82s、BACK testも9.84s PASS。
- `clean-class-run1/2.xml`（対照系列）: settings-return 2本PASS、BACK testのみFAIL（同一signature）— BACK失敗がdiff非依存の根拠どおり。
- `candidate-grant-run1..5.xml`: 5/5 PASS（22.31/14.25/14.94/14.43/12.49s）＝PR本文の「5/5 PASS、12–22s」と一致。
- `receipts-intentional-fire.xml`: failure messageに全receiptフィールド（`grantCheckTickEntered=[1]`、`runnerState=Capturing`、`gatePhase=Resolved gateRevision=3`、`usageAccessPredicateGranted=true`、`journalRunStarted=false (events=[])`、`preparationPhase=CAPTURE`、`operationActive=false`、`dialogPresent=false settingsUnavailablePresent=false`、truncate済semantics tree）を確認。`Caused by: ComposeTimeoutException: Condition still not satisfied after 2000 ms` をcauseで保持。`journalRunStarted=false` と `events=[]` は同一snapshotから導出され自己矛盾なし（RUN_STARTED不在と整合）。tracebackはhelper `UsageAccessJitInstrumentationTest.kt:169` → テスト本体 `:357` で、headソースのhelper構造（waitUntil 165行目、catch 168行目付近）と整合。
- `candidate-overlay.diff`: `publicationThread = HandlerRunPublicationThread()` の除去1行のみ。assessment docの「77b6b97時代相当」の説明と一致。

## CI検証

- head `b1417ef3f1` のCI run [38619485918](https://github.com/nunu1733/NunuLauncher/actions/runs/38619485918): **manual-organization-ui lane PASS（23m24s）** — 本diagnostics込みで settings-return 2テストを含むclassがCI PASS。`build-debug-apk`、`check-style`（=spotless）、`changes`、`validate-repo-contract`、`organizer-unit-tests`、他instrumentation lanes（exchange-import / method-choice / onboarding）もPASS。
- 同runで `category-override` laneが `pending`（監査時点でin-progress）。re-run前の直近失敗（同一PR push run 38614863515、head `a3dd8c2e`）は **category-override laneがsuccess** であり、本diff系列でlane不安定が継続しているわけではない。
- main最新run [38604546753](https://github.com/nunu1733/NunuLauncher/actions/runs/38604546753)（head `cd50255146`＝本PRのbase）のcategory-override失敗をtest report artifactから確認: 失敗テストは **`CustomCategoryPreferencesInstrumentationTest.editorAndDialogTransitionsRestoreInputFocusToTheSummaryNode`**（15s `ComposeTimeoutException`、CustomCategoryPreferencesInstrumentationTest.kt:328）。監査依頼にあった `OrganizerLockScreenTest` ではなくこれが実際の失敗テストであった。ただし **同クラスは本PR diff外であり、PR失敗という主張の屋台骨（「lane失敗は本diff由来ではなくlane断続不安定」）は変わらない**。mainでも同laneが別テスト失敗で落ちたことは確認を根拠づける事实として以下に記録。
- manual-organization-ui laneは禁cons観察上、base runでsuccess、head runでもsuccess（失敗時点無し）。

### 発見事項（問題評価）

1. **[軽微] 監査依頼の前提と実際の失敗テストの不一致**: 依頼内容ではcategory-override失敗を `OrganizerLockScreenTest` としたが、main run 38604546753の実際の失敗は `CustomCategoryPreferencesInstrumentationTest`（diff外テスト、15s timeout）。結論への影響なし（いずれもdiff外・manual-organization-ui laneはPASS・本diffは UsageAccessJitInstrumentationTest のみ触る）。ただし将来のre-run待ち完了前にmergeしない。
2. **[軽微] CI pending**: 監査時点でcategory-override jobがin-progress。merge gate (`final-status`) はこの完踏を待つ必要がある（tier Lのため高リスクwork例要件は適用外だが、手実行契約上のmerge gateは別）。
3. **[なし] production差分・テスト差分・証跡・assessment doc・Issueコメントの間に矛盾なし。**

## 結論

**APPROVE**（category-override re-runの完了とfinal-statusの成功をmerge直前条件として）。

- 本PRはspec 586のAC-1〜AC-4を受入条件どおり実装・証跡つき満たす。
- production挙動・公開API・状態遷移は不変（既定no-op tracer seamのみ）。
- 証跡XMLはassessment docと機械的に整合（6/6 baseline PASS、candidate 5/5 PASS、意図発火で全receipt出力+cause保持）。
- 失敗中のcategory-override laneはdiff外テスト（base commit上のmain runでも同様に失敗）であり、本diffの責務ではない。

---

監査session: 独立（実装sessionと別実行）。監査対象head: `b1417ef3f18f7c26686e33ead0ed4f8002d25fe5`。
