# Independent audit: PR #389 onboarding提案と再表示hintの接続先をhubへ更新する（#370）

> Status: accepted（audit完了）
> Audit date: 2026-09-21
> Verdict: **条件付きGO**（受入条件OCB-AC-01〜05はすべてPASS。mergeは本headでのCI `final-status` green確認を条件とする。詳細は CI status / 最終判定）

- Auditor: 実装を行っていない独立session（実装agent/実装sessionとは別の監査として実施。production/test codeの修正は行っていない）
- PR: https://github.com/nunu1733/NunuLauncher/pull/389
- Head SHA: `715838bb4fea9e1b8185e68447a2e81fb18acd17`（`git fetch origin issue-370-onboarding-hub-connection` 後の `FETCH_HEAD` とworktree HEAD `issue-370-onboarding-hub-connection` の一致を確認。`git status` クリーン）
- Base: `main` = `adeebe6fb1f24e179a2ddbe917ba0c28f6a347c2`（merge-base `origin/main` との3-dot diffで検証）
- CI run: https://github.com/nunu1733/NunuLauncher/actions/runs/35534330610 ／ high-risk-evidence: https://github.com/nunu1733/NunuLauncher/actions/runs/35534330634（監査時点で複数job pending。job単位の結果は「CI status」節）
- Criteria: [specs/370-onboarding-hub-connection/spec.md](../../specs/370-onboarding-hub-connection/spec.md)（accepted、OCB-AC-01〜05、Test oracle表）、[plan.md](../../specs/370-onboarding-hub-connection/plan.md)、[specs/53-onboarding-organization-proposal/spec.md](../../specs/53-onboarding-organization-proposal/spec.md)（§3.2/§5.2/§5.3改訂後）、[specs/232-organizer-reentry-discoverability/spec.md](../../specs/232-organizer-reentry-discoverability/spec.md)（AC-1/AC-3改訂後）、Issue #370本文・全コメント14件（Phase1 review loop 3回＋実装review 2回の記録含む）、accepted disposition `docs/product/organizer-disposition-migration.md` §2.3/§3.4/§5順序4/§7.2(b)(c)（PR本文の参照）
- 監査方法: PR本文・`git diff origin/main...HEAD` 全差分（21 file）review、変更後production/test/spec sourceの実読み取り、下記「独立再実行」のコマンド実行、`gh pr checks` によるCI照合。受入条件の判定は実装者の主張（PR本文・Issue snapshot）を引用するだけでなく、監査者自身のcode読み取りで再確認した。

## Scope

対象コミット（7件、`47fbbb6070` → `715838bb4f`）:

| commit | 内容 |
|---|---|
| `47fbbb6070` | Phase1 spec/plan re-entry（hub接続・直行row廃止の所有、T-07非介在oracle固定） |
| `93249df3a0` | Phase1 review対応（spec 53 workflow block参照化、guard test fixture改修要件） |
| `41fab38fee` | Phase1 review対応（plan Risk節のsampling fallback除去） |
| `7210a8d2ca` | spec/planをacceptedへ更新 |
| `23e742095a` | feat本体（hint copy差し替え、直行row削除、guard test再work、specs 53/232改訂） |
| `887ed17b9b` | device evidence追加（docs/assessment/evidence/issue-370/ 11 file） |
| `715838bb4f` | 実装review対応（test-only render trace `ManualOrganizationRunFaceTrace` による決定的render oracle。production不変） |

diff全体（21 file、+1170/−45）:

- Source（4 fileのみ）: `OrganizationOnboardingProposal.kt`（hint第3引数差し替え＋javadocのみ。遷移コード無編集）、`HomeScreenPreferences.kt`（直行row削除＋comment整理）、`ManualOrganizationFace.kt`（test-only `ManualOrganizationRunFaceTrace` object追加のみ）、`ManualOrganizationPreferences.kt`（import 2件＋post-apply `SideEffect` 2行のみ）
- Test: `OnboardingOrganizationProposalInstrumentationTest.kt`、`OrganizerDiagnosticsRouteInstrumentationTest.kt`
- Docs: specs 370（新規spec/plan）、53、232、evidence README＋screenshot 10枚
- `lawnchair/res/`（strings.xml含む）への差分: **0件**（format template `organization_onboarding_reentry_hint_body` のEN/jaは3引数のまま無編集。監査者が `values/strings.xml:1308` と `values-ja/strings.xml:390` の実内容でplaceholder一致を確認）

### 非変更範囲の検証（OCB-AC-04前提）— PASS

`git diff --name-only origin/main...HEAD` の全21 pathを確認し、次を検証した:

- persistent store / proposal outcome store / provenance / run-journal / diagnostics契約 / `PreferenceRoutes.kt` / `PreferenceNavigation.kt` / `OrganizerHubPreferences.kt` / run state machine / planning / application 配下: **変更なし**
- Launcher layout DB / `favorites` / migration / 新規preference key・route schema: **接触なし**（ホームレイアウト安全規約は適用対象外と判定）
- `OrganizationOnboardingProposalController.review`（`Started` のときのみ `REVIEWED` 記録。実装file 124-130行を実読み取りで確認）: 無編集
- 依存追加・permission追加・通信追加: なし
- `ManualOrganizationPreferences.kt` の `SideEffect` 追加は `ManualOrganizationRunFaceTrace.recorder` がnullの場合no-opであり（productionはrecorderを設定しない。objectのKDocに明記）、production挙動を変えない

## 独立再実行（worktree `/Users/nunu/Documents/work/NunuLauncher-370`、head `715838bb4f`、2026-09-21）

| command | 結果 |
|---|---|
| `git status` | クリーン。HEAD = `715838bb4f` = `origin/issue-370-onboarding-hub-connection`（FETCH_HEAD一致） |
| `git diff --check origin/main...HEAD` | **PASS**（whitespace error 0件） |
| `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew spotlessCheck --console=plain` | **PASS**（exit 0。compatLibのbuild出力による既知のworktree環境問題は発生せず、削除対応も不要だった） |
| `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.*' --console=plain` | **PASS**（exit 0。`build/test-results` のXML 140 class / **1540 tests、failures 0、errors 0、skipped 0**。結果XMLのmtimeは監査者の実行時刻と一致することでキャッシュ流入でなく実行の証跡を確認） |
| `python3 tools/repo-contract/validate_repo_contract.py` | **PASS**（`repository contract OK`。exit 0） |

instrumentation lane（`OnboardingOrganizationProposalInstrumentationTest` 20 tests / `OrganizerDiagnosticsRouteInstrumentationTest` 対象test）は本監査ではemulator実行していない。実装記録（Issue #370 Implementation snapshot 2026-09-20T20:00Z、render trace版head `715838bb4f` で20 tests OK、emulator `issue142_api36` 2026-09-21）とCI issue53 lane（監査時点pending、下記）で確認する。

## Criteria check（OCB-AC-01〜05）

| AC | 判定 | 根拠（監査者の独立読み取り） |
|---|---|---|
| OCB-AC-01 | **PASS** | 「確認」経路のproduction実装は無編集で、既定 `admitReview` = `ManualOrganizationModule.get(launcher).start(Trigger.ONBOARDING_PROPOSAL)`（`OrganizationOnboardingProposal.kt:212-215`確認）、`beginReview()` は `Started` のときのみ `HomeScreenManualOrganization(OrganizationEntry.ONBOARDING)` へ遷移し、Busy時はbutton再有効化・提案維持・遷移なし（405-434行確認）。guard test `realTouchStreamOnReviewAdmitsAFreshRunAndRoutesToTheReviewSurface` は `TouchActivationGate(useProductionAdmission = true)` でviewを**既定のproduction `admitReview`** で構築し（stub `reviewOutcome.get()` を経ない）、tap前に `installProcessLocalRunner`（diagnostics testと同一の `ManualOrganizationModule.instance` reflection pattern）でprocess-local runner fixtureを注入する。決定的oracleは **render commit観測**（`ManualOrganizationRunFaceTrace.recorder` に記録されたcommit済みface列）: `faces.none { it == PREAMBLE }`（T-07 compose/render 0回の直接記録）＋ `faces.first() == CONFIRMATION` を直接assertし、StateFlow state trace（post-admission状態がすべてPREAMBLE非写像。RD-7純関数の補助証拠）と `manual_organization_start` のa11y不在scan（settle付き）は補助に降格している。spec oracleどおり。既存 `busyReviewKeepsProposalOutcomeUntouchedAndRetryableByRealTouch` は無編集で維持（diff 0件、375行存在確認）。admission→`REVIEWED` 記録順序のunit回帰（`OrganizationOnboardingProposalTest`）は無編集で監査者のunit再実行がgreen |
| OCB-AC-02 | **PASS** | `reentryBodyText()` の第3引数を `manual_organization_title` → `organizer_hub_title` へ差し替え（`OrganizationOnboardingProposal.kt:582-589`確認。format resourceは3引数のまま無編集）。javadocもhub入口row案内へ更新済み。EN/jaのformat templateは `%1$s → %2$s → %3$s` のplaceholder一致（実file確認）。instrumentation `laterTapShowsTheReentryHintAndPreservesTheDeferOutcome` は実label構成assertを `organizer_hub_title` 含有＋`manual_organization_title` 不含有（assertFalse新設）へ更新 — assertがresource ID参照のためlocale非依存に「実UI labelからの合成」の真実性を検証する構造は維持され、ja側の合成結果はemulator evidence（`06-ja-light-hint.png` / `08-ja-dark-hint.png`。READMEに「オーガナイザー」合成を記録）で確認。hintの6秒・非block・dismiss・focus復帰・表示失敗系の既存test群は無編集で、監査者のunit再実行と実装evidence（20 tests OK）がgreen。`res/` diff 0件によりstring契約の不変も確認 |
| OCB-AC-03 | **PASS** | `HomeScreenPreferences.kt` のdiffはGeneral groupの直行row（`NavigationActionPreference(label = manual_organization_title, destination = HomeScreenManualOrganization())` 1件）と暫定併存commentの削除のみで、hub入口row（`organizer_hub_title` → `HomeScreenOrganizer`）は残置。organizer由来rowはhub入口row 1件になる。test: `homeScreenSettingsShowsTheOrganizerEntryInGeneralAboveTheFold` はentryLabelを `organizer_hub_title` へ更新（General headingと`home_screen_actions` headingの間・first viewport内の既存assert維持）し、削除row labelの不在scan（`awaitAccessibilityTextAbsent` 新設helper、settle recheck付き）を追加。`OrganizerDiagnosticsRouteInstrumentationTest.homeScreenMaterialsRelocationRoutesDiagnosticsThroughHub` は暫定併存assertを `onAllNodesWithText(manual_organization_title).assertCountEquals(0)` ＋hub row displayed へ更新し、KDocにobsolete理由（D-01 entry-row-only end state、#370適用後の正しい構成）を記載。obsolete理由はPR本文の「旧oracleの廃止理由」表にも記録済み。手動開始の `MANUAL_FULL` 導出はhub開始CTAと同一routeのため不変（route schema無編集） |
| OCB-AC-04 | **PASS** | 非変更範囲検証のとおり、persistent store / diagnostics契約 / preference key / route schema / DB へのdiff 0件。outcome semanticsは `controller.review`（admission成功後のみ `REVIEWED` 記録）が無編集。defer/skip/provenance fail-closed/journal非発行の回帰unit・instrumentation群は無編集のまま監査者のunit再実行（1540 tests、0 failure）がgreen。`ManualOrganizationRunFaceTrace` はtest-only observerでありproduction挙動を変えない（前述） |
| OCB-AC-05 | **PASS** | spec 53: §3.2へ「T-07前置き面をskipし方法は固定」との追記、post-admission workflowの `explicit Review/Start -> Capture -> Plan` 直結列挙を「pre-#369 baseline shape。normative order and display integration live in Issue #52/#369」と明示するlead-inで**非normative化**（旧直結表記のnormative残留なし。spec OCB-AC-05の要件どおり）、§5.2/§5.3の接続先を#369統合run面・hub入口参照へ更新、§12 Revision historyを新設。AC節・outcome表・§3.3/§3.4・diagnostics契約はdiff上無変更（AC-003不変を含む。diff grepで確認）。spec 232: AC-1案内先を「設定 → Home screen → Organizer hub 入口row」へ、AC-3を「organizer由来rowはhub入口row 1件（直行row廃止済み）」へ改訂し、Scenario・Accessibility節・Test oracle・Change historyを連動更新。旧案内先・旧入口構成を固定したtest 3件の更新とobsolete理由はPR本文に表形式で記録済み。`spotlessCheck` とorganizer unit gateは監査者の独立再実行でgreen。CI `final-status` はmerge gateとして監査外（監査時点pending、下記） |

## guard testの観点チェック（監査項目d）— PASS

1. **render commit oracleがspec OCB-AC-01どおりであること**: `ManualOrganizationRunFaceTrace`（`ManualOrganizationFace.kt` 併置、`@Volatile` recorder、KDocに「Production never sets the recorder」明記）は `ManualOrganizationPreferences` の `manualOrganizationFace(state)` 値をpost-apply `SideEffect` で報告する。これはcompositionが成功してcommitしたface値の全件記録であり、StateFlow観測（coordinator stateの推測）やa11y不在scan（sampling）とは区別される。guard testの直接assertは「PREAMBLE commit 0件」「最初のcommit face = `CONFIRMATION`」で、1 frameでもT-07がcommitされる回帰（route先行commit→Preview進行）は先頭PREAMBLEとして確実に検出される。fixture進行（`start()` がPreview確認面で駐留後に `Started` 返却）による「最初のface = CONFIRMATION」の期待値はplan「Implementation re-entry evidence」と一致。
2. **production path（既定admitReview＋fixture注入）であること**: `useProductionAdmission = true` のときview構築に `admitReview` 引数を渡さず、既定実装（実 `ManualOrganizationModule.get(launcher).start(ONBOARDING_PROPOSAL)`）を通る。fixtureは `installProcessLocalRunner` でtap前に注入され、遷移先routeの `ManualOrganizationPreferences` が同一instanceを観測する。旧stub（`reviewOutcome.get()` のみでrunnerを開始しない）は他test用途に残るが、guard testは使用しない。
3. **cleanup**: `finally` でrecorder復帰（null）、trace job cancel、`gate.restore()`、fixture復帰（`installProcessLocalRunner(null)`）を閉じている。

## CI status（監査時点、2026-09-21、run 35534330610 @ `715838bb4f`）

| 結果 | job |
|---|---|
| pass（4） | changes（16s）/ validate-repo-contract（56s）/ check-style（1m9s）/ high-risk-evidence（34s、run 35534330634） |
| pending（11） | build-debug-apk / organizer-unit-tests / organizer-instrumentation-api35-tests / organizer-instrumentation-db-migration-tests / organizer-instrumentation-issue155-tests / organizer-instrumentation-issue299-tests / organizer-instrumentation-issue52-tests / organizer-instrumentation-shared-writer-tests / organizer-instrumentation-issue332-tests / organizer-instrumentation-issue53-tests（本PRのoracle class群を含むonboarding lane）/ organizer-instrumentation-issue99-tests |
| 最終 | `final-status` = 監査時点未確定 |

本PRには `risk: layout-data` / `risk: migration` labelは付与されておらず（`gh pr view` でlabels空を確認）、変更pathもhigh-risk対象外のため `high-risk-gate` の独立エビデンス要件自体は適用外（high-risk-evidence jobはpass）。ただし `final-status` は通常のmerge gateであり、確定を待たずにmergeへ進めない。

## Findings

1. **CI未確定job（監査時点）**: 11 jobがpending。本監査のscopeでは完了を待たない（最終 `final-status` の確認はmerge gate）。本PRのoracle class群（onboarding lane・diagnostics route含む）を含むため、merge前にgreenを確認すること。監査者の独立unit再実行（1540 tests green）と`spotlessCheck`/`validate_repo_contract`（local PASS、CI該当jobもpass）は本headで成立済み。
2. **evidence READMEのhead表記（軽微・hygiene）**: `docs/assessment/evidence/issue-370/README.md` のAutomated evidence節は実行headを `23e742095a` と記載する。これはbranch内の中間commit（feat本体。evidence追加commit `887ed17b9b` の直前）であり、render trace版guardを含む最終head `715838bb4f` での20 tests OKはIssue #370のImplementation snapshot（2026-09-20T20:00Z）と実装再レビューで記録されている。証跡の実体に不足はないが、READMEのhead表記が最終検証headとずれて残っている。docs修正のみで解消する事項であり、本監査では修正しない（監査は実装を変更しないため）。
3. **OCB-AC-02の「EN/ja双方のformat resource合成assert」の解釈（指摘に至らない注記）**: instrumentationのlabel構成assertはresource ID参照（`context.getString`）のため、実行localeの実labelとの一致をlocale非依存に検証する構造であり、jaでの合成結果の直接assertはemulator evidence（ja screenshots）が担う。spec oracleの文言に対するこの構成は、#268由来の既存test構造の継承であり、string diff・placeholder一致（監査者確認済み）・ja evidenceの組み合わせでOCB-AC-02の実質を満たすと判定した。

## 未確認範囲

- 監査者自身によるemulator instrumentation実行は未実施（本監査の再実行scopeはdiff-check/spotless/unit/repo-contract）。instrumentationの根拠は実装記録（20 tests OK、render trace版head）・実装再レビュー（指摘なし、`715838bb4f`）・CI lane（監査時点pending）に依存する。
- emulator screenshot 10枚の視覚内容はfile存在・README記載・実装snapshotとの照合まで（画素単位の検証はしていない）。
- CI pending jobの完了後の状態（`final-status` を含む）は監査外のmerge gate。

## 最終判定

**条件付きGO。**

- 受入条件OCB-AC-01〜05は、監査者自身のコード読み取りと本headでの独立再実行（`git diff --check` / `spotlessCheck` / organizer unit 1540 tests / `validate_repo_contract.py`、すべてPASS）によりすべて満たす。guard testの決定的oracleはspec OCB-AC-01が要求するrender commit観測（PREAMBLE 0件・first = CONFIRMATION）を正しく実装し、production admission経路（既定 `admitReview` ＋ process-local runner fixture注入）を通っている。非変更範囲（persistent state・diagnostics契約・route schema・DB）の汚染はない。spec 53/232の改訂はspec 370のScopeに対応する行のみで、AC-003・outcome表・diagnostics契約は無変更。旧oracle 3件の廃止理由はPR本文に記録済み。
- Phase1 review（re-entry revision以降3ラウンド、最終指摘なし→accepted）と実装review（Changes requested 1点→render traceで解消→再レビュー指摘なし）の記録をIssue #370コメントで照合した。実装最終承認対象headは `715838bb4f` であり、承認後の実質変更はない。
- **merge条件**: 本head `715838bb4f` でのCI `final-status` green（pending 11 jobの完了確認を含む）を条件とする。失敗jobが出た場合は、本PR由来か否かをjob単位で切り分け、本PRと無関係な場合は別途起票のうえ先に対処すること。
