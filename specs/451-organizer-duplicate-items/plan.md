# Implementation Plan: 全体整理が同じ起動先の重複アイテムを同じ新規フォルダに入れず、重複をpreviewで示す

> Issue: #451
> Spec: [spec.md](./spec.md)
> Status: accepted（2026-09-30。spec acceptedに基づく）
> Risk tier: H（2026-09-30にowner確定。`risk: layout-data` labelを付与し、高リスクPRの独立エビデンス契約を適用する。spec冒頭のRisk tier引用ブロック参照）
> Analysis baseline: `origin/main` `092c44b46e7c6074f0623b146cc975d9ec862e53`（2026-09-30のRe-entryで再基準化。初版のcode調査は `c5a7840b880ed4c436b67170930ca87d4ef7f148`（2026-09-28）基準。行番号は再基準化時点のもの）

## Current evidence

現行実装（調査済み。推測を含まない）:

- **起動先identityはplanner入力に既にある**: `CapturedItem.target: TargetKey`（`lawnchair/src/app/lawnchair/organizer/planning/OrganizationInput.kt`）。`AppKey(component, profile)` / `ShortcutKey(packageName, shortcutId, profile)`。capture側の構成は `RowManifestCodec.kt`（`targetKey(row, kind)`。DB行のintentから）と `ModelProjectionCodec.kt`（`targetKey(info, kind, profile)`。`ItemInfo` から）、plannerへの移送は `OrganizationInputComposer.kt` の `mapItem`（`CanonicalItemState.targetKey` をそのまま渡す）。
- **folder形成は `formFolderGroups` に集約されている**: `lawnchair/src/app/lawnchair/organizer/planning/FolderFormation.kt`。`FolderCandidate(item: ItemId, profile, key: FormationKey)` は `TargetKey` を運ばず、group化は `(profile, FormationKey)`、member順は `ItemId` 昇順。呼び出し側は4箇所:
  - `FullRunExecution.kt`（`executeGlobalCompact` / `executeRegionSweep` / `executeCanonicalPageCompact`。3つのfull-run executor内）
  - `PlanningPlacement.kt` の `appendCandidatePlacements`（`IncrementalPlacement` と `ScopeComposedOrganization` のcandidate tail。`CandidateItem.target: CandidateTarget` を使う）
- **movable streamの入口は `PlanningPlacement.place`**: `movableItems = input.snapshot.items.filter { determinePreservation(...) == null }`（`FullOrganization` と `ScopeComposedOrganization` のfull-run phaseの両方。`PlanningPlacement.kt`）。`determinePreservation`（同file末尾）が保全predicateの優先順序を所有する（RESERVED_REGION > LOCKED > UNAVAILABLE_TARGET > DOCK > WIDGET > APP_PAIR > LEGACY_SHORTCUT > NON_TARGET > STRUCTURAL > movable）。保全itemのcellは `place()` の冒頭で `allocator.markOccupied` され、`appendPreservedPlacements`（`FullRunExecution.kt`）が `PlannedPlacement(Preserved(reason), captured)` を発行する。**このため、`determinePreservation` に新しいpredicateを追加するだけで、除外（movable streamから外れる）・占有cell処理・保持行発行の3つが同時に成立する。executor 3種は無変更でよい。**
- **警告語彙**: `WarningCode` は3値（`LEGACY_SHORTCUT_REVIEW` / `FALLBACK_CATEGORY` / `UNAVAILABLE_PRESERVED`。`PlanningResult.kt`）。`preservationWarnings` は `PlanningPlacement.place` で組み立て、`PlanningResultCanonicalization.assemble` が `warningComparator`（code ordinal → params）で正準化する。`RejectionCode.DUPLICATE_TARGET` はtarget set内の `ItemId` 重複のみに使われ、起動先の重複はどこにも検出されていない。
- **preview**: `PlanPreviewProjector`（`lawnchair/src/app/lawnchair/organizer/application/preview/PlanPreviewProjector.kt`）は `Warning`（`ItemParam` 1個・captured itemとjoinできるもの）を `ItemWarningChange` 行へ投影する。`WarningCode` は値として素通しし、application層にexhaustiveな分岐はない。文言は `organizer/ui/OrganizationPreviewContent.kt` の `warningText` / `preservedReasonText`（exhaustiveな `when`）→ `OrganizationPreviewWording` → `ManualOrganizationPreferences.kt` の実装 → `lawnchair/res/values/strings.xml` / `values-ja/strings.xml`。
- **oracle**: `tests/unit/app/lawnchair/organizer/planning/harness/GoldenOracleCorpus.kt` が `ExampleCorpus.allExamples` + `validationFixtures` + `SyntheticFixtureGenerator`（seed `0x4E554E55L`、64 case）を `planAll()` し、digestを `tests/unit/resources/planner-golden-corpus/sha256.txt` と比較する。再固定は `-Dgolden.write=true`。現corpusには同一 `(profile, TargetKey)` の重複を含むfixtureが存在しない（generator template 1〜7と `ExampleCorpus` を確認済み。template 5の同一componentはpersonal/workのprofile違いであり重複ではない）ことを確認した。
- **policy bundle**: `rules/PolicyModels.kt` の `POLICY_BUNDLE_VERSION = "organization-policy-v2.7"`。strategy enablementごとに1 increment上げる運用（`BuiltInOrganizerPolicyBundleSource.kt` の注記、ADR-0007 §8 / ADR-0012）。
- **P-10再実行testのseam**: `harness/PostPlanMaterializer` + `PlannerContractHarness`（既存のidempotence testがこのseamを使う）。

## Re-entry記録

- **2026-09-30**: `origin/main` `092c44b46e7c6074f0623b146cc975d9ec862e53`（#449/#450着地後）へ再基準化。`origin/issue-451-spec-plan` へのmergeで実施し、planningコードは無変更。baseline以降のmain差分のうちplanning配下は `PlanningResult.kt` の `FolderNaming.FromUserCreation` 追加（#449、本planの主張に触れない）のみ。
- **再確認した範囲**（いずれも現mainで本plan・specの記載と整合。陳腐化箇所なし）:
  - `PlanningResult.kt`: `WarningCode` 3値（`LEGACY_SHORTCUT_REVIEW` / `FALLBACK_CATEGORY` / `UNAVAILABLE_PRESERVED`）、`PreserveReason` 語彙（`STRATEGY_PRESERVED`（spec 182）を含むが、これは `determinePreservation` のpredicate連鎖外でありspec N-3のpredicate列挙と整合）、`Disposition.Moved` / `Preserved` のみ。
  - `PlanningPlacement.kt`: `place()` のmovable stream入口（`determinePreservation(...) == null` フィルタ。`FullOrganization` と `ScopeComposedOrganization` の両方）、`preservationWarnings` の組み立て、`determinePreservation` 優先順序（RESERVED_REGION > LOCKED > UNAVAILABLE_TARGET > DOCK > WIDGET > APP_PAIR > LEGACY_SHORTCUT > NON_TARGET > STRUCTURAL > movable）、`determinePreservation` 呼び出し site（place系3箇所、`placeIncrementalRun`、`appendPreservedPlacements`（`FullRunExecution.kt`）、executor内再呼び出し1箇所（widget stream判定））。`relocateWidgets` default引数の先例は本planの `duplicateSurplus` default引数方針と両立。
  - `FolderFormation.kt` / `FullRunExecution.kt`: `FolderCandidate` は `TargetKey` を運ばない、`(profile, FormationKey)` group化、member順 `ItemId` 昇順。`formFolderGroups` 呼び出し側4箇所（3 executor + `appendCandidatePlacements`）。
  - `OrganizationInput.kt`: `CapturedItem.target: TargetKey`、`TargetKey.AppKey` / `ShortcutKey` / `LegacyShortcutKey` / `WidgetKey` / `FolderKey` / `AppPairKey`、`CandidateItem.target: CandidateTarget`。`Identity.kt` の `ItemId` canonical順（`compareUtf8Bytes`、UTF-8 byte順）。
  - capture側の `targetKey` 構成: `RowManifestCodec.kt` / `ModelProjectionCodec.kt` / `OrganizationInputComposer.kt` `mapItem` は不変。
  - rules: `POLICY_BUNDLE_VERSION = "organization-policy-v2.7"`、runtime-supported 9 strategy、default `CANONICAL_PAGE_COMPACT_V1`。
  - preview / 文言: `PlanPreviewProjector` の `capturedWarnings` → `ItemWarningChange` / `warningCounts`、`OrganizationPreviewContent.kt` の `warningText` / `preservedReasonText`（exhaustive `when`）、wording実装 `ResourceOrganizationPreviewWording`（`ManualOrganizationPreferences.kt`）。
  - oracle: `tests/unit/resources/planner-golden-corpus/sha256.txt` はbaselineから不変。`GoldenOracleCorpus.kt` へのmain差分は #449 の `FolderNaming.FromUserCreation` digest token追加のみ（corpus構成・seed `0x4E554E55L`・`digestsBySource` 契約は不変）。
  - `docs/engineering/editing-burden-benchmark.md`: B7 baseline 9（操作数5）は現mainに存在し、目標は#451のspecで確定（§6）。
  - `docs/product/requirements.md`: FR-021 は `proposed（2026-09-24）` のまま（accept時に本planのChange setどおり更新）。NFR-014 は `accepted（2026-09-26）`。
  - 依存: #441（B7 baseline）はCLOSED済みを再確認。
- **本Re-entryによる文書変更**: なし（陳腐化箇所なし。本記録とAnalysis baseline行の更新のみ。planningコード・spec本文の規則は無変更）。
- **2026-09-30（ChatGPT review対応。P1×2、P2×1）**: Verification節のproperty test実装方式を重複注入wrapper (b) に確定し、`SyntheticFixtureGenerator` へのtemplate追加を不採用とした（`template = index % 8` の固定64 caseが変わり、spec N-7 / AC-5の既存fixture個別digest不変と衝突するため）。Explicitly unverified areasの当該行を更新。spec側の修正（scenario代表の `ItemId` canonical順への整合、N-1の契約拡張明記、Open questions追加）は [spec.md](./spec.md) のChange history参照。planning codeへの記載は無変更（generatorの現行挙動は現mainで再確認済み）。
- **2026-09-30（ChatGPT re-review対応。残P2×2）**: Dependency / blockerをspec Open questionsの2件のowner decision（代表選択の `ItemId` canonical順採用、重複判定の全captured item拡張の採否）へ更新。spec側はTest oracleのAC-2行を「重複注入wrapper」へ一本化（[spec.md](./spec.md) のChange history参照）。

## Design

### Modules and interfaces

- **planning module（主変更）**:
  - `PlanningResult.kt`: `WarningCode` へ `DUPLICATE_LAUNCH_TARGET` を**末尾に**追加（既存警告のcanonical順序を変えないため）。`PreserveReason` へ `DUPLICATE_LAUNCH_TARGET` を追加（列挙順は `STRUCTURAL` と `ALREADY_CANONICAL` の間を推奨。`ALREADY_CANONICAL` より前であることが規則上の意味を持つ場面は現状ないが、spec N-3の優先度と並びを合わせる）。
  - 重複検出の純粋関数（新規。`FolderFormation.kt` へ同居または `planning/DuplicateLaunchTargets.kt` を新設）: `internal fun duplicateSurplusIds(items: List<CapturedItem>): Set<ItemId>` — `TargetKey.AppKey` / `ShortcutKey` を持つitemをtarget値でgroup化し、groupサイズ ≥ 2の各groupを `ItemId` 昇順に並べ、index ≥ 1 の `ItemId` を返す。`IllegalStateException` 等を投げず、入力のみから決まる。
  - `PlanningPlacement.place`: 冒頭で `duplicateSurplusIds(input.snapshot.items)` を1回計算し、(a) 既存のpreservation warning loopに追加分（surplus 1個につき `Warning(DUPLICATE_LAUNCH_TARGET, [ItemParam])`）を加える、(b) `determinePreservation` へsurplus集合を渡す（default引数 `duplicateSurplus: Set<ItemId> = emptySet()`。全呼び出し site — `place()` 内3箇所、`placeIncrementalRun`、`appendPreservedPlacements`、各executor内の再呼び出し — が同じ集合を見る。`FullRunContext` へ持たせて `appendPreservedPlacements` が読む形でもよい）。
  - `determinePreservation`: `STRUCTURAL` 分岐の後、`else` の前に「`item.id in duplicateSurplus` なら `PreserveReason.DUPLICATE_LAUNCH_TARGET`」を追加。これにより `FullOrganization` / `ScopeComposedOrganization` のmovable streamから外れ、`IncrementalPlacement` ではreasonのみ付け替わる（spec N-3）。
  - `FolderFormation.kt` / `FullRunExecution.kt` の3 executor: **変更不要**（surplusは`movableItems`に現れないためgroup化にもunit streamにも入らない）。
  - `appendCandidatePlacements`（candidate tail）: 変更しない（spec Non-goals）。
- **rules module**: `PolicyModels.kt` の `POLICY_BUNDLE_VERSION` を `organization-policy-v2.8` へ。`BuiltInOrganizerPolicyBundleSource.kt` のversion注記へ #451 の行を追加。catalog（runtime-supported 9 strategy、default `CANONICAL_PAGE_COMPACT_V1`）は不変。
- **ui module（文言のみ）**: `OrganizationPreviewContent.kt` の `warningText` / `preservedReasonText` へ新enum値の分岐、`OrganizationPreviewWording` interfaceへfield追加、`ManualOrganizationPreferences.kt` の実装と `R.string` 追加（en/ja）。文言は「重複のため整理では移動させずに残した」意味（削除を促さない）。
- **application module**: 変更不要（`PlanPreviewProjector` は `WarningCode` を素通しする。`OrganizationPlanMaterializer` は `Preserve` 汎用処理。AC-7のunit testは `tests/unit/app/lawnchair/organizer/application/preview/` に追加するが、これはtest追加でありproduction application moduleの変更ではない）。**この構成では高リスクpath一覧（`organizer/application/**`）に触れない。**

### Data flow

`place()` 開始 → surplus集合の計算（純粋）→ 保全警告の組み立て（既存2種+新規1種）→ 保全cellの占用marking（surplusを含む）→ run mode別の配置（full-run: surplusはmovable stream外。incremental: 全保持、surplusは新reason）→ `assemble` による警告の正準化 → `Planned`。失敗経路（`Invalid` / `Impossible`）は不変（新しい `RejectionCode` を追加しない）。

### Alternatives rejected

- **`formFolderGroups` 内で `FolderCandidate` に `TargetKey` を持たせて除外する**: 除外されたitemがmovable streamに残り単体として再配置される（メモ§4.5の案2になる）。メモが確定した「その場保持（案1）」と矛盾するため不採用。また3 executor + candidate tailの4呼び出し側すべてに同じ除外を入れなければならない。
- **代表の選択に視覚順（ページ、行、列）を使う**: メモ§4.5の文言だが、materialize後に代表が新規フォルダへ移ると視覚順の基準位置が変わり（folder unitの配置先ページはallocatorが決める）、再実行で代表/超過分が入れ替わり得る。具体例: 重複A(ページ2)・B(ページ1)ともう1つの同category app C(ページ2)で、run 1はBとCがフォルダへ入りAは超過分として保持。フォルダがページ3へ配置された場合、再実行の視覚順はA(ページ2)が先頭になりAがeligible survivorとなる。Aは単体streamでcanonical化され移動し得る（Cが空けたページ2のcellへ）、P-10（`Moved` なし）に違反する。`ItemId`（favorites行ID）は移動で不変であるため安定し、spec N-5として `ItemId` canonical順を採用する（owner確認事項はspec Open questions参照）。
- **移動行・保持行に理由語を添える形のpreview表示**: `ItemWarningChange` の既存経路（警告group、件数truth、a11y表現）がそのまま使え、行種別を増やさないため不採用（spec N-6）。保持行の理由語は自動的に `preservedReasonText` に乗る。
- **candidate tail（`additions`）への同様の除外**: productionの選択面（spec 228）が `ComponentKey` + `ProfileId` で候補の一意性を構成しており、重複は構成されない。harness専用の入力形状にplanner規則を広げない（spec Non-goals）。
- **`RejectionCode` を使った入力拒否**: 重複は不正な入力ではなく観察可能な状態であり、FR-021は計画の振る舞いを定める。拒否は既存V-01〜V-20の意味論と衝突する。

## Change set

| Area | Intended change | Why here |
|---|---|---|
| `lawnchair/src/app/lawnchair/organizer/planning/PlanningResult.kt` | `WarningCode` / `PreserveReason` へ `DUPLICATE_LAUNCH_TARGET` 追加 | 語彙の所有者はplanning module（spec 12 P-07/P-12） |
| `lawnchair/src/app/lawnchair/organizer/planning/`（`FolderFormation.kt` または新規file） | `duplicateSurplusIds` 純粋関数 | 検出は `(input)` の純粋関数（spec N-1） |
| `lawnchair/src/app/lawnchair/organizer/planning/PlanningPlacement.kt` | surplus計算、警告追加、`determinePreservation` へのpredicate追加 | movable stream入口と保全優先順序の所有者 |
| `lawnchair/src/app/lawnchair/organizer/rules/PolicyModels.kt` | bundle version `v2.7` → `v2.8` | ADR-0007 §8 / ADR-0012の手順 |
| `lawnchair/src/app/lawnchair/organizer/rules/BuiltInOrganizerPolicyBundleSource.kt` | version注記へ #451 行を追加 | 先例（strategy enablement注記）と同じ記録方法 |
| `lawnchair/src/app/lawnchair/organizer/ui/OrganizationPreviewContent.kt` | `warningText` / `preservedReasonText` の分岐、wording field | exhaustiveな `when` の所有者 |
| `lawnchair/src/app/lawnchair/ui/preferences/destinations/ManualOrganizationPreferences.kt` | wording実装と `R.string` 参照 | 既存の文言seam |
| `lawnchair/res/values/strings.xml` / `values-ja/strings.xml` | 新警告文・保持理由文（en/ja） | spec 195 AC-6と同じ要求 |
| `tests/unit/app/lawnchair/organizer/planning/`（harness含む） | 重複fixture、決定性/P-10/優先度/警告test、corpus追加とdigest再固定 | AC-1〜AC-5 |
| `tests/unit/app/lawnchair/organizer/application/preview/` | `PlanPreviewProjector` 経由の警告行test | AC-7（testのみ。production application moduleは無変更） |
| `tests/unit/resources/planner-golden-corpus/sha256.txt` | digest再固定（理由: 重複fixtureのcorpus追加のみ。既存fixtureの個別digestは不変） | AC-5 |
| `docs/engineering/editing-burden-benchmark.md` | 目標表のB7行へ本spec確定の目標を反映 | AC-8（実装PRで） |
| `specs/12-.../spec.md` | P-03/P-04/P-05/P-07/P-08/P-10/P-12へのamendment注記とChange history | spec 12が本Issueをamendment所有者として指すようにする |
| `docs/product/requirements.md` | FR-021のstatus（accept時にproposed → acceptedへ更新） | 要件正本 |

## Migration and recovery

- DB migration: なし。schema・適用経路・recovery store・backup/restoreは不変。
- policy bundle: `v2.8` はADR-0007 §8の「新しいsemantic version + digest」。保存store（選択・override）のmigrationは不要。 downgrade時は既存のfail-closed規則に従う（v2.8 bundleを知らないbinaryは組織化をunavailableにするだけで、layoutは変更しない）。
- rollback（binary revert）: v2.7挙動へ戻る only。本変更で作られたフォルダ構成は通常の有効なlayoutであり、rollbackを妨げない（重複は再び形成され得るだけ）。
- planはrevision付きの一時artifactであり、version跨ぎの再適用は既存のstale検証が所有する。追加のrollback手順は不要。

## Failure handling

- plannerは純粋関数であり、新しい失敗経路を作らない。surplus計算はいかなる入力でも total（group化とsortのみ）。
- 警告・preservation reasonは観察であり、`Invalid` / `Impossible` のreasons集合は不変。
- 適用時の失敗（stale、transaction失敗等）は既存の適用module契約（spec 13）が所有し、本変更は触れない。

## Verification

| Acceptance criterion | Automated/manual evidence | Command or environment |
|---|---|---|
| AC-1, AC-3, AC-4 | 新規unit test（planning fixture。重複2組・locked代表・minGroupSize境界・incremental・対象外kindを含む） | `./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.planning.*'` |
| AC-2 | P-10再実行test（`PostPlanMaterializer` seam）+ 決定性test（permutation/locale/thread。既存 `PlannerGeneratedPropertyTest` と同じseamで重複入り入力を供給） | 同上 |
| AC-5 | `GoldenOracleCorpusTest`（再固定digest）+ 個別digest安定test（`digestsBySource` の既存fixture分の不変） | 同上（再固定時のみ `-Dgolden.write=true`。手順はtest classのdoc参照） |
| AC-6 | bundle version/catalog test（rules module） | 同上 |
| AC-7 | preview unit test（`PlanPreviewProjector`）+ ja/en resource test + 実機スクリーンショット（owner確認） | unitは同上。実機は `docs/engineering/building.md` のdebug build（`./gradlew assembleLawnWithQuickstepGithubDebug`） |
| AC-8 | docs差分review | PR |
| AC-9 | `./gradlew spotlessCheck`、`./gradlew assembleLawnWithQuickstepGithubDebug`、`git diff --check` | CI（organizer-unit-tests gateを含む `final-status`） |

追加のproperty観点（AGENTSテスト規約の計画module要件）: 「任意の生成入力へ重複注入 → 全新規フォルダ内で起動先が一意」「surplus itemのtargetは常にcaptured targetと一致（conservationの部分命題）」「同入力2回のplanが値等価」。実装方法は (b) 生成fixtureへの重複注入wrapper（独立class化。既存generatorとpinned corpusは不変）を採用する。`SyntheticFixtureGenerator` への重複template追加（旧案 (a)）は不採用とする: 現行generatorは `template = index % 8` で固定64 caseを生成するため、template集合・割当の変更は既存 `generated:<id>` caseの内容を変え、spec N-7 / AC-5が要求する既存fixtureの個別digest不変（`digestsBySource`）と衝突する。

## Documentation updates

- [ ] spec status/history（accept時に `draft` → `accepted`）
- [ ] spec 12（`specs/12-deterministic-full-layout-planner-v1/spec.md`）: amendmentのChange history追記と該当節（P-03/P-04/P-05/P-07/P-08/P-10/P-12）への参照
- [ ] `docs/engineering/editing-burden-benchmark.md`: B7目標行の反映（AC-8）
- [ ] `docs/product/requirements.md`: FR-021 status（accept時）
- [ ] CONTEXT.md: 変更なし（「重複アイテム」既存語を使用。新ドメイン用語なし）
- [ ] DESIGN.md: 変更なし（module構造・interfaceは不変）
- [ ] ADR: 不要（「変更が高コスト」「理由がコードから分からない」「実際の選択肢があった」の3条件を満たさない。代表選択の`ItemId`採用はspec N-5とOpen questionsで十分）

## Execution checklist

- [ ] 1. `WarningCode` / `PreserveReason` への語彙追加（既存testが通ることを確認）
- [ ] 2. `duplicateSurplusIds` 純粋関数と `determinePreservation` への統合、`place()` での警告追加
- [ ] 3. planning unit test一式（AC-1〜AC-4）を追加。この時点で既存corpusのdigestが不変であることを確認
- [ ] 4. corpusへ重複fixtureを追加し、digestを再固定（理由をcommit messageとPRに記録）
- [ ] 5. bundle version上げ（AC-6）とrules test
- [ ] 6. ui文言（en/ja）とpreview test（AC-7 unit分）
- [ ] 7. docs更新（spec 12注記、benchmark目標表、requirements status）とspotless / assemble / `git diff --check`
- [ ] 8. 実機確認（AC-7のスクリーンショット）。tier H確定（2026-09-30 owner決定）のため `risk: layout-data` labelを付与し、高リスクPRの独立エビデンス契約が必須: CI merge gate（`final-status`）の成功に加え、独立audit記録 `docs/assessment/pr-<n>-<slug>.md`（対象head SHA、参照したspecの受入条件、実行したtest表面、成功したCI runへのlink）を作成する

各stepで `./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.planning.*'` を実行する。

## Dependency / blocker

- 依存: なし（メモ§5「#451、#453、#444は他に依存しない」）。#441（B7 baseline）はCLOSED済み。
- 被依存: 重複の削除提案（Next）が本Issueの規則を前提にする。
- blocker: 解消済み（2026-09-30にowner承認済み）。spec Open questionsの2件のowner decision（① 代表選択の `ItemId` canonical順採用（メモ§4.5「視覚順」からの逸脱）、② 重複判定の全captured item拡張（N-1の契約拡張））は承認済みで、tier Hも同日に確定した（`risk: layout-data` label付与）。実装開始のblockerはなし。

## Risk

- P-10（冪等性）の再証明が最大のリスク。特に「surplusが単体streamに再出入りする」経路（Alternatives rejectedの具体例参照）。`PostPlanMaterializer` seamのtestで、重複を含む複数scenario（代表がfolderへ入る、代表がlocked、groupがmin未満になる、新規pageへ溢れる）の再実行を網羅する。
- `WarningCode` 列挙順の挿入位置を誤ると既存警告のcanonical順序が変わりdigestが壊れる。末尾追加に限定する。
- `determinePreservation` の呼び出し site取り漏らし（executor内の再呼び出しを含む）は、surplusが`appendPreservedPlacements`で行落ちしてconservation違反になる。全呼び出し siteに同一のsurplus集合が渡ることをtest（surplus itemが必ず1個の `PlannedPlacement` を持つ）で保証する。

## Explicitly unverified areas

- 実装は未着手（本planは文書整備のみ）。行番号はbaseline `c5a7840b` 時点のものであり、実装時に再確認する。
- 実機での警告行の見え方・読み上げの実際の体験は未検証（AC-7の実機確認で行う）。
- 文言の最終語（ja/en）は実装PRで確定する。
- property testは重複注入wrapper方式（Verification節の (b)。既存generator・pinned corpusは不変）で実装する。実装は未着手のため、wrapperの実形状は実装時に確認する。
- tier判定は2026-09-30にowner確定済み（tier H、`risk: layout-data` label付与）。実装PRでの追加のtier判断は不要（label運用と独立auditはExecution checklist step 8の確定文言に従う）。
