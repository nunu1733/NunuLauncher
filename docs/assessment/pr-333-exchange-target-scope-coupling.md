# High-risk audit: PR #333 Exchange対象scopeに未配置アプリ候補を含める (spec 331)

> Status: accepted
> Audit date: 2026-09-16

- Auditor: Independent audit session (ZCode subagent, GLM; implementation PRを担当したsessionとは別の作業として実施)
- PR: https://github.com/nunu1733/NunuLauncher/pull/333
- Head SHA: 780fdd6bd728a69a4c0497419401e2d08928d57f
- CI run: https://github.com/nunu1733/NunuLauncher/actions/runs/35087139174
- Criteria: specs/331-exchange-target-scope-coupling/spec.md AC-1, AC-2, AC-3, AC-4, AC-5, AC-6, AC-7, AC-8, AC-9, AC-10, AC-11, AC-12, FR-017

## Scope

本監査は、accepted spec 331 (`specs/331-exchange-target-scope-coupling/spec.md`、受入head `41252343d4`、status更新commit `b4c83045a2`) とplan (`specs/331-exchange-target-scope-coupling/plan.md`) を正本として、PR #333のhead `780fdd6bd728a69a4c0497419401e2d08928d57f` (base `4f555450bd`) の全diff (~34 files, +2065/−123) を自ら読んで確認したものである。実装側の主張 (PR本文・Issue 331コメントのreview記録) は参照したが、依拠していない。

確認した変更面:

- **契約v2** (`ContextExportModels.kt` / `ContextExportCodec.kt` / `ExchangePackageComposer.kt`): schema定数 `personalization-context-v2` / `personalized-intent-v2`、`ExportItemSubject` (`PLACED`/`CANDIDATE`)、`Mobility.CANDIDATE`、`ExportItem.subject`。candidate subjectには `mobility == CANDIDATE`、`role == APP_OR_SHORTCUT`、current-position field不在の不変条件が`init` requireで強制されている。`ExportItem.init` の `mobility==CANDIDATE ⇔ subject==CANDIDATE` も双向に検証済み。
- **candidate export partition** (`ContextExportBuilder.kt`): `targets.additions` を (component, profile) 順で走査しcandidate itemを生成。labelはtier制御、`category` は解決済み分類、usageは#203 projection対象。session ref mapをplaced/candidateの単一mapに拡張し、`scopeCandidates` (安定identity) と `scopeCandidateDigest` をsessionに記録。
- **candidate投影digest** (`CandidateScopeIdentity.kt` 新規): 安定identity + availability + 解決済み分類のcanonical serializationに対するsha256。列挙順非依存 (ソート)。placed側 `SourceContextIdentity` は無変更 (spec D-4どおり)。
- **ScopeBindingGate** (`exchange/ScopeBindingGate.kt` 新規, 純粋): 完全一致 (欠落・追加の両方を拒否) → 解決可能性 (検出cut不在・AVAILABLE以外を `CANDIDATE_UNRESOLVED`) → 投影digest照合 (`PROJECTION_MISMATCH`) の3段。
- **validator/pipeline** (`IntentValidator.kt` / `ExchangeImportPipeline.kt`): candidate宛 `preserve` → `MOBILITY_CONTRADICTION`。`IntentValidationFailure.ScopeMismatch(cause)` を13th classとして追加。unified failure surfaceは13 + 4 = 17種に更新。
- **run結合** (`ManualOrganizationRun.kt` / `ExchangeFlowUi.kt` / `ManualOrganizationPreferences.kt` / `MissingAppSelectionScreen.kt`): `attachIntent` (selection surface上で1回限定) / confirm時の早期完全一致gate / composition後の投影gate。拒否時は選択surfaceへ復帰 (`State.Selecting.scopeRejection`、stale intent破棄)、surfaceが存在しないrunはtyped `State.ScopeMismatchFailed` 終端。両経路とも `IntentValidationFailure.ScopeMismatch` を生む。freezeは `editsEnabled = !exchangeBusy` により選択UI全体 (検索・bulk・toggle・confirm/cancel) に適用。
- **session store v2** (`AndroidExportSessionStore.kt`): file名 `..._v2.json`、`SCHEMA_VERSION = 2`、candidate scope record拡張。旧v1 recordはpath変更で孤立 + v2 path上のv1 recordもversion checkで拒否 (fail-closed)。
- **正本更新**: spec 204/205のChange history、`CONTEXT.md` 用語2件、`DESIGN.md` gate 12/13行、strings (values + values-ja)。

layout application path・DB migration・Launcher3 bridgeには変更がない (diffに該当pathなし。`organizer/application/`、Launcher3側fileは未変更)。書込み経路の変化はexport session store (app-private file、schema v2) のみである。

## Criteria check

spec 331の受入条件ごとの確認結果。AC番号はspec本文に定義がある。

- **AC-1 (run内entry export、選択済みcandidateを含む、未選択は不在、zero-write)**: PASS (unit)。`ExchangeTargetScopeCouplingTest.selectedCandidatesBecomeCandidateSubjectItems` / `unselectedCandidatesNeverAppearInTheExport`。zero-writeはread-only composition上の構造的性質 + run-level testの `applyCalls == 0` / `composeScopeComposedCalls == 0` 主張で裏付け。選択surface自体のinstrumentation testは本PRに含まれない (Findings参照)。
- **AC-2 (単一canonical composition seam、scope変化の伝播、parity)**: PASS (unit)。`composeForExport()` と `composeForExport(selection)` が同一adapterの同一 `composeFrom` 経由で `composeFullOrganization` / `composeScopeComposedOrganization` を呼ぶ構造をdiffで確認。`reconstructorRebuildsCandidateEntriesAndTheGatePassesTheSameScope` (placed構造parity + 同一scopeのgate通過)、`candidateScopeDigestIsDeterministicAndDriftSensitive` (選択・分類・availability変化がdigestを変える)。
- **AC-3 (privacy-safe candidate identity)**: PASS (unit)。`candidateLabelsAndCategoriesFollowTierControl` がredacted tier文書にpackage名・planning IDが現れないことを走査し、category (taxonomy値) の保持とlabelのtier制御を検証。session内のidentity対応 (`ExportSession` require: candidate planning IDがitemRefs値に存在) も検証。raw identifier走査はredacted tier文書1種に対してのみ実施 (Findings参照)。
- **AC-4 (未選択candidate ref / 新規install appの `UNKNOWN_REF`)**: PASS (構造的)。未選択candidateはexport/sessionに存在しないため (`unselectedCandidatesNeverAppearInTheExport`)、任意の不在refは既存fail-closed `UnknownRef` pathに落ちる (`IntentValidatorTest` のghost ref corpusで既存保証)。331専用の未選択candidate宛ref corpus testは追加されていないが、AIがrefを知り得ない (exportに現れない) 構造であって、閉じ方は既存classの再利用である。
- **AC-5 (candidate宛intent field policy、planner消費)**: PASS (unit)。`preserveOnACandidateRefIsAMobilityContradiction` (preserve拒否 + `importance`/`pageAffinity` 受理)、`adapterResolvesCandidateRefsToTheirPlanningIds` (candidate ref → planning ID宛preferenceのplanner投影)。planner本体のpreference消費機構は既存 (#204/#228) suiteがcoverし本PRで無変更。
- **AC-6 (3段stale検出、`SCOPE_MISMATCH` 13th class、17種UI)**: PASS (unit)。gate unit test (`gateRejectsDivergentSelectionsAndCategoryDrift`: 欠落/追加/unresolved/availability/category drift/Pass)、run-level 4 tests (SET_MISMATCH復帰・PROJECTION_MISMATCH復帰・CANDIDATE_UNRESOLVED typed終端・attach 1回限定)、`failureTaxonomyCarriesThirteenContractClassesIncludingScopeMismatch`、UI側のexhaustive `when` (`exchangeContractFailureText` が13 class全てを網羅、`SCOPE_MISMATCH` 含む17種; 網羅性はcompile-time保証)。placed構造変化 → `CONTEXT_STALE` は既存digest path無変更 (既存suite)。
- **AC-7 (空workspace + 選択候補のみでexchange成立)**: PARTIAL (unit、segment単位)。export/reconstruct/gateはplaced 0件のfixtureで検証済み。export → import → plan → previewを1本で駆動する統合testは本PRに存在しない (Findings参照)。
- **AC-8 (mixed existing + missing appsの統合test)**: PARTIAL (unit、segment単位)。placed + candidate混在export (`selectedCandidatesBecomeCandidateSubjectItems`)、preference消費 (AC-5)、preview区別は #228既存suite (`AddChange`) が担う。単一のmixed統合testは未追加。
- **AC-9 (AI未使用path無変更、idle宛intentへのgate適用)**: PASS (regression)。full unit suite (CI `organizer-unit-tests` 含む) がgreenで、既存 #204/#205/#228 suiteの劣化なし。gateはintent所持runに無差別に適用される設計であり (早期gateは `operation.intent` のみを分岐条件とする)、export {A} vs 選択∅ を直接検証 (`scopeBindingRejectsASelectionMissingTheExportedCandidate`)。idle (∅ export) vs 候補選択の鏡像caseは同一比較式を通るが専用testは未追加。
- **AC-10 (v2 bump、v1 fail-closed、content limits)**: PASS (unit)。`codecRoundTripsTheCandidateSubjectAndRejectsV1`、`v1IntentsFailClosedOnDecode`、`pre331Schema1RecordIsRejectedFailClosed` (store v2)。content limitsはbuilderの既存require + 既存OVERSIZE suite (無変更)。
- **AC-11 (freeze/中止/失効/process death復帰)**: PARTIAL (実装確認のみ)。freeze・取消経路 (`ExportSessionStore.invalidate` 既存規則 + freeze解除)・件数案内 (`exchange_scope_intent_count` plurals、自動選択なし) の実装はdiffで確認。automated run-flow/instrumentation testは本PRに含まれず、手動evidenceは後続evidence PR扱い (PR本文の明記どおり、#205 AC-9/AC-10と同一枠)。
- **AC-12 (a11y evidence + ja/en strings)**: PARTIAL。stringsはvalues + values-ja両方に追加 (plurals含む)、freeze通知とscope拒否文は `liveRegion = Assertive`、checkbox role維持を確認。TalkBack/Switch Access/large fontの手動evidenceは未実施 (AC-11と同じく後続evidence PR枠)。
- **FR-017**: spec 331 frontmatter `requirements: [FR-017]` の対象。本PRはspec 331受入条件を満たす範囲でFR-017を損なわない (planner制約・fail-closed検証・privacy boundaryの不変条件を上記AC検証で確認)。

## Executed test surface

監査session自らが実行 (clean tree、head `780fdd6bd728a69a4c0497419401e2d08928d57f`、source未変更):

- `git status` → clean; `git rev-parse HEAD` → `780fdd6bd728a69a4c0497419401e2d08928d57f`
- `gh repo view nunu1733/NunuLauncher --json nameWithOwner,defaultBranchRef` → `nunu1733/NunuLauncher` / `main` を確認
- `./gradlew :testLawnWithQuickstepGithubDebugUnitTest` → **BUILD SUCCESSFUL in 26s** (exit 0)。test task実行 (結果xmlは本日時点で更新)。
- `./gradlew spotlessCheck` → **BUILD SUCCESSFUL** (exit 0、up-to-date cache実行)
- `gh run view 35087139174 --repo nunu1733/NunuLauncher` → **completed success**。全14 job success: `final-status`、`organizer-unit-tests`、`check-style`、`build-debug-apk`、`changes`、`validate-repo-contract`、instrumentation 8 lane (db-migration / shared-writer / issue52/53/99/155/299 / api35)。runは `pull_request` event、PR #333、head_sha = 監査対象SHA でGitHub自身が紐付けていることをAPIで確認。
- 高リスクgate run 35087139204 (cancelled) / 35087175831 (failed) は監査記録不在時の評価である (失敗原因は `no docs/assessment/pr-333-<slug>.md audit record` のみ)。本記録push (docs-only) 後の再評価をmerge条件とする。

## Findings

1. **CI merge gate**: CI run 35087139174 が監査対象commit上で完全緑 (`final-status` + 必須source job実行済み)。高リスクgateの機械要件は本記録のpush後に再評価される。
2. **非ブロッキングの検証gap (後続evidence PRでの解消を推奨)**:
   - AC-1 (選択surface) / AC-11 (freeze・中止・process death模擬) / AC-12 (TalkBack・Switch Access・large font) のinstrumentation/手動evidenceは本PR対象外。PR本文がこの未実施を正直に宣言しており、#205のAC-9/AC-10 evidence PR先例と同一の扱いである。
   - AC-7/AC-8の単一統合test (export → import → planner → previewを1本で駆動、mixed workspace) が未追加。segment単位の検証と既存 #228 suiteで主要不変条件は抑えられているが、planの検証表どおりのtest表面には満たない。
   - AC-3のraw identifier走査はredacted tierの1文書に対してのみ実施 (with-labels tierの走査はlabel値のみ確認)。`ExportItem`/codec構造上、package名・planning IDは文書に流れ得ない位置にあり、重大度は低い。
   - idle export (∅) vs 候補選択の鏡像case (AC-9) の専用testなし。同一比較式を通るため実質riskは低い。
3. **gate run失敗の由来**: 35087175831の失敗は監査記録不在のみであり、コード品質の問題ではない。
4. **独立verdict**: **Approve** (head `780fdd6bd728a69a4c0497419401e2d08928d57f` に対する)。accepted spec 331のD-1〜D-5は実装どおりであり、fail-closed不変条件 (完全一致・投影digest・`SCOPE_MISMATCH` typed契約・v2 fail-closed session・privacy境界) はautomated testで検証されている。上記2のgapはmerge blockではなく、evidence PRでの追及を条件とする。本記録push以降はdocs-only commitのみが許容され、code変更時は本SHAに対する再監査を要する。
