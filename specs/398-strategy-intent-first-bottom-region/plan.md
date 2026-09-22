# Implementation Plan: Organizer strategyをユーザー意図一致優先で再評価し、下部領域semanticsのsuccessor strategy `BOTTOM_REGION_V1` を追加する

> Issue: #398
> Spec: [spec.md](./spec.md)
> Status: draft

## Current evidence

依存gateの確認（2026-09-23、`main` = `b4a2012640`）:

- #356 CLOSED、#365〜#377 全てCLOSED。open issue一覧に#365〜#377由来の未完了migration/follow-upなし。#398のscheduling/dependency gateを満たす。
- baselineのplanner/rules unit testはgreen（`./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.planning.*' --tests 'app.lawnchair.organizer.rules.*'` → BUILD SUCCESSFUL、2026-09-23実行）。canonical族（folder形成含む）のharness idempotenceが実行可能証明としてgreenであることを確認済み。

関連code pathと現在の振る舞い（確認済み事実。行番号は2026-09-23時点）:

- `lawnchair/src/app/lawnchair/organizer/planning/LayoutStrategyRegistry.kt`
  - `StrategyDefinition`（L41-62）: strategyの宣言的semanticsをdataとして持つ。`strategyFixes`（L61）は `unitOrder != CANONICAL_TIE_BREAK && !eligibleUnitFilter(item)` 派生。
  - 登録済み8 strategy（L161-251）。`BOTTOM_FIRST_V1`（L183-191）はcanonical族policy + `CellTraversal.BOTTOM_UP_ROW_MAJOR`。
- `lawnchair/src/app/lawnchair/organizer/planning/FullRunExecution.kt`
  - `execute`（L110-126）: `unitOrder` でdispatch。未知組合せはloud failure（L100-107 doc、L950-952 throw）。
  - `executeCanonicalPageCompact`（L709-1037）: EF(ItemId順) → NF(ordinal順) → singleton（intent bias + profile/category/ItemId順）、page grouping by preferred page、`allocatePreferred`。
  - `executeGlobalCompact`（L558-682）: 単一stream + `allocateCapturedThenNew`、strategy-fixed handling（L565-577）の先例。
  - intent hint helpers: `preferenceAllocateOnPage`（L441-464）、`preferenceCellHint`/`preferenceBandHint`（L811-827）、region band計算（`rows/3` 3等分、L274-276等）。
- `lawnchair/src/app/lawnchair/organizer/planning/PlacementAllocator.kt`
  - `findRowMajorFirstFit`（L230-289）: 唯一のtraversal実装。`rowWindow`（L224-228、#235 widget bandで使用済み）と `BOTTOM_UP_ROW_MAJOR`（L261-266）は両方とも既存であり、領域付きbottom-up走査は既存部品の組合せで表現できる。
  - `allocateCapturedThenNew`（L167-177）: sweep走査の既存実装。領域付きvariantは未存在（新規追加が必要）。
  - `allocateOnPageOnlyInBand`（L148-165）: page-local領域hint用（fallbackでfull-pageに抜けない点に注意）。
- `lawnchair/src/app/lawnchair/organizer/planning/PlanningPlacement.kt`
  - incremental runは `TOP_LEFT_ROW_MAJOR` 強制（L26-29）。strategyのcellTraversalはfull-run/scope-composedのみ効く。
  - scope-composed candidate tail（L236-378）: `strategy.pageScope` と `strategy.createsFolders` を消費。`CAPTURED_THEN_NEW` → `allocateCapturedThenNew`（L335-340）。
- `lawnchair/src/app/lawnchair/organizer/rules/PolicyModels.kt`
  - `POLICY_BUNDLE_VERSION = "organization-policy-v2.6"`（L185）と履歴コメント（L175-184）。catalog coherence検証（L167-171）。
- `lawnchair/src/app/lawnchair/organizer/rules/BuiltInOrganizerPolicyBundleSource.kt`（L47-59）: runtimeSupported=8戦略、default=CANONICAL。
- `lawnchair/src/app/lawnchair/organizer/diagnostics/model/RunEvent.kt`（L49-68）: `APPROVED_VERSIONS` allowlist（strategy ID 8種）。
- `lawnchair/src/app/lawnchair/organizer/ui/preferences/destinations/ManualOrganizationPreferences.kt`（L1288-1310）: `strategyDisplayName` / `strategyDescription` のID→R.string写像。
- `lawnchair/res/values/strings.xml`（L1041-1067）/ `values-ja/strings.xml`（L228-245）: strategy名・説明。
- tests: `CrossStrategyCorpusTest` / `StrategyPerformanceSampleTest` / `RunEventSerializationTest` / `BuiltInOrganizerPolicyBundleSourceTest` は `LayoutStrategyRegistry.acceptedIds` またはbundle等価でregistry駆動（新strategy自動対象）。`StrategyPickerInstrumentationTest`（L121-128）はofferされるstrategy名の文字列リストを列挙（新nameの追加が必要）。

推測（実装時に確認）: harnessのdense generated caseに「既存folder + 新folder形成」の共存が含まれるか。専用dense fixture（AC-3）で再plan差分ゼロを明示固定するため、この推測に依存しない。

## Design

### Modules and interfaces

外部seam `OrganizationPlanner.plan(OrganizationInput) -> PlanningResult` は不変。すべて内部seam:

1. `planning/LayoutStrategyRegistry.kt`
   - `StrategyId` 定数 `BOTTOM_REGION_V1` を追加（ADR-0012: 新semanticsは新ID）。
   - `StrategyDefinition` に `preferredRegion: PreferredRegion? = null` fieldを追加（sealed interface `PreferredRegion`、`data object LowerHalf` のみ）。既存8戦略はデフォルトnullで不変。`placeFullRun` dispatchを `preferredRegion != null` 優先で分岐（未知組合せは既存どおりloud failure）。
   - 領域計算は純粋関数 `lowerPreferredRegion(rows: Int): IntRange = (rows - (rows + 1) / 2) until rows`（planning package内、device rowsのみから決定的）。
   - 定義: `createsFolders = true`、`eligibleUnitFilter` はcanonical族と同一（app/deep shortcut、任意span）、`unitOrder = CANONICAL_TIE_BREAK`、`pageScope = CAPTURED_THEN_NEW`、`cellTraversal = BOTTOM_UP_ROW_MAJOR`、`widgetPolicy = null`、`placeFullRun = FullRunExecution::executeRegionSweep`。
2. `planning/PlacementAllocator.kt`
   - `allocateCapturedThenNewInRegion(span, rowWindow)` を追加: captured page群（PageOrder順）→作成済み新page群を `findRowMajorFirstFit(..., rowWindow=rowWindow)` で走査し、なければ新pageを作成して領域内first-fit。`allocateOnNewPages` の領域付きvariant（既存は触らず新規追加。第2のallocator実装ではなく、同一 `findRowMajorFirstFit` の呼び出し形の追加である）。
3. `planning/FullRunExecution.kt`
   - `executeRegionSweep(context)` を追加:
     - tall-span fixed set: `span.height > region行数` のmovable unitをcaptured位置にmarkOccupiedし `Preserved{STRATEGY_PRESERVED}`（`executeGlobalCompact` のstrategy-fixed handlingと同一パターン）。
     - folder formation: `executeCanonicalPageCompact` と同一の `formationKey`（intent `groupSemantic` 含む）+ `formFolderGroups`。
     - 単一stream順序: EF（ItemId順）→ NF（ordinal順）→ singleton（preserve-first、intent componentRank、importance、`regionAffinity=BOTTOM` を早い消費へbias、profile、category、ItemId）。`minimizeMovement` のcaptured visual順への切替は行わない（spec合成matrix）。
     - 割当: intent hintのclip（preserve cellが領域内のときのみ、page-local直接試行。`regionAffinity=BOTTOM` は `allocateOnPageOnlyInBand(span, capturedPage, regionWindow)`。どちらも不成立ならsweepへ）→ `allocateCapturedThenNewInRegion(span, regionWindow)`。
     - disposition / `NewFolder` output / `appendPreservedPlacements` / 出力canonical化は既存executorと同一構造。
   - `planning/PlanningPlacement.kt` のcandidate tail（L335-340）: `strategy.preferredRegion != null` のとき候補割当を領域付き走査へ変更し、領域内不成立candidateを `STRATEGY_SCOPE_FULL` でunplaced。
4. policy/selection/diagnostics/UI
   - `rules/BuiltInOrganizerPolicyBundleSource.kt`: runtimeSupportedへ `BOTTOM_REGION_V1` 追加。
   - `rules/PolicyModels.kt`: `POLICY_BUNDLE_VERSION = "organization-policy-v2.7"`、履歴コメントに「#398 published -v2.7 (BOTTOM_REGION_V1)」を追記。
   - `diagnostics/model/RunEvent.kt`: `APPROVED_VERSIONS` へ `"BOTTOM_REGION_V1"` 追加。
   - `ui/.../ManualOrganizationPreferences.kt`: `strategyDisplayName`/`strategyDescription` に新IDの写像追加。
   - `lawnchair/res/values/strings.xml` + `values-ja/strings.xml`: `organization_strategy_bottom_region_name/description` 追加。`bottom_first_description` / `bottom_first_v2_description` を全面充填である旨へ明確化（semantics変更なしのcopy-only）。EN例: "Fills each screen completely, starting from the bottom rows." / JA例: 「各ページを下段から順にすべて埋めます。」新strategy説明には、下部領域・上側余白・page増加の可能性・widget不動を明記。

### Data flow

入力（`OrganizationInput`）は既存のままで追加fieldなし。run mode:

- `FullOrganization`: `PlanningPlacement.place` → `FullRunContext` → `executeRegionSweep`。movable unitsを単一streamでsweep、fixed setはoccupancy、新pageは領域付きで作成。出力 `PlacementOutput` は既存型のまま。
- `ScopeComposedOrganization`: full-run phaseは同一executor、candidate tailは領域遵守 + unplaced。
- `IncrementalPlacement`: 既存どおりcanonical強制（新strategyは関与しない）。

provenance: selection identityが既存の第5policy inputとして自動参加（store契約は不変）、rules identityはeffective `RuleSemantics` のdigestへ新strategy IDが入る（既存機構）。

### Alternatives rejected

- **`BOTTOM_FIRST_V1` のsemantics改訂（defect correction扱い）**: ADR-0012のidentity規律（behavior変更は新ID）に反する。既存selection・golden corpusを壊す。却下。
- **V1のpicker非表示/retire**: 既存ユーザーselectionを突然unsupported化する価値がない。全面下詰めは意図的に選ぶ価値を保つ。維持を採用。
- **positional（captured visual）順序のsweep**: bottom-up充填とvisual読み出し順序が逆で、materialized状態が消費順序を復元せずreplanで回転する（spec「Idempotence argument」節）。identity-based順序を採用。
- **自page優先overflow（PREFERRED_THEN_NEW相当）**: 既存pageの下部領域が空のまま新pageの下部が埋まる可視的不整合。却下（spec比較表）。
- **region比率を下1/3（intent BOTTOM bandと統一）にする案**: rows=3で1行、比率が整数除算で非一様、複数行spanの領域外固定が増える。下1/2（ceil）を採用（spec比較表）。
- **widget移動対応（PageLocalTopAnchored併用）**: widget移動は #235 V2 successorsの領域であり、本strategyのidentity（app/folder streamの下部領域構成）とは直交。最小diffのためwidget不動を採用。将来のwidget対応successorは新IDで検討。

## Change set

| Area | Intended change | Why here |
|---|---|---|
| `planning/LayoutStrategyRegistry.kt` | `BOTTOM_REGION_V1` 定数・定義、`PreferredRegion` field | strategy catalogの正本 |
| `planning/PlacementAllocator.kt` | `allocateCapturedThenNewInRegion` | 唯一のshared allocatorへ走査variantを追加（第2実装を作らない） |
| `planning/FullRunExecution.kt` | `executeRegionSweep`、dispatch | 単一full-run executorの分岐先 |
| `planning/PlanningPlacement.kt` | candidate tailの領域遵守 | scope-composed semanticsをstrategy宣言から消費 |
| `rules/BuiltInOrganizerPolicyBundleSource.kt` | runtimeSupported追加 | bundle宣言の正本 |
| `rules/PolicyModels.kt` | `-v2.7` bump + 履歴 | ADR-0007 §8 enablement規律 |
| `diagnostics/model/RunEvent.kt` | allowlist追加 | version identifier許容の正本 |
| `ui/.../ManualOrganizationPreferences.kt` | 表示写像追加 | picker copyの唯一の消費点 |
| `res/values{,-ja}/strings.xml` | 新copy + V1/V2説明明確化 | localized copyの正本 |
| `tests/unit/.../planning/BottomRegionStrategyTest.kt`（新規） | spec scenario群 | public seam経由の契約test |
| `tests/unit/.../rules/LayoutStrategySelectionStoreTest.kt` | 新ID write/read case | selection契約の検証 |
| `tests/organizer-instrumentation/.../StrategyPickerInstrumentationTest.kt` | offerされるstrategy名リストへ追加 | UI面の検証 |
| `CONTEXT.md` | 下部優先領域の用語 | domain languageの正本 |
| `docs/product/requirements.md` | FR-016備考にobjective明記 | 要件traceabilityの正本 |
| `docs/assessment/pr-<番号>-<slug>.md` | 独立監査記録（実装PRで別session/subagent実施） | high-risk gate要件 |

## Migration and recovery

- schema/rule migrationなし。bundle `organization-policy-v2.7` はimmutable artifactとしてpublish（in-place migration禁止、ADR-0007 §8）。selection store schema不変。
- failure中のrollback: apply path（spec 13）は既存のまま。新strategyのplanも既存のrecovery point・transaction・post-apply verificationを通る。
- release rollback/downgrade: v2.6以下のbundleを持つbinaryが `BOTTOM_REGION_V1` selectionを読むとselection-layer `NotReady`（fail-closed、zero-write、store不変）。re-upgradeで再検証・復帰（spec 182 3-case modelの適用）。
- backup/restore: selection storeは既存どおりbackup対象外。recovery pointはstrategy非依存。

## Verification

| Acceptance criterion | Automated/manual evidence | Command or environment |
|---|---|---|
| AC-1 | requirements.md diff + spec受入 | review |
| AC-2 | `BottomRegionStrategyTest`（scenario群） | `./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.*'` |
| AC-3 | 同上（dense 3strategy比較fixture） | 同上 |
| AC-4 | `GoldenOracleCorpusTest` + 既存strategy test群 無変更green | 同上 |
| AC-5 | `CrossStrategyCorpusTest` / `PlannerGeneratedPropertyTest` / harness系 | 同上 |
| AC-6 | `IntentPreferenceStrategyMatrixTest` / `WidgetIntentAuthorityTest` | 同上 |
| AC-7 | `BuiltInOrganizerPolicyBundleSourceTest` | 同上 |
| AC-8 | `LayoutStrategySelectionStoreTest` | 同上 |
| AC-9 | `StrategyPickerInstrumentationTest` + strings diff | API 36 emulator instrumentation lane（CI） |
| AC-10 | `RunEventSerializationTest` | unit test |
| AC-11 | `BottomRegionStrategyTest` orientation matrix | unit test |
| AC-12 | device evidence またはevidence追跡Issue | physical device |
| AC-13 | `final-status` CI + 独立監査記録 | GitHub Actions + `docs/assessment/` |

その他の必須gate: `./gradlew spotlessCheck`、`./gradlew assembleLawnWithQuickstepGithubDebug`、`python3 tools/repo-contract/validate_repo_contract.py`（docs変更時）。

含めるべき観点: unit/contract（上記）、property（harness/registry駆動、自動）、failure injection（既存 `AllocationFault` loud failure経路は無変更）、performance（`StrategyPerformanceSampleTest` 自動対象、測定値をPRへ記録）、UI/accessibility（instrumentation lane。新copyのTalkBack文言は既存picker構造を流用するため新規accessibility surfaceは追加しない）。

## Documentation updates

- [x] spec status/history（本spec、受入時にstatus更新）
- [x] CONTEXT.md（下部優先領域）
- [ ] DESIGN.md（変更不要。strategyはplanning module内部seamのまま、gate表への新規行も不要と判断。spec 182行で既にカバー）
- [ ] ADR（不要と判断: 判断の正本は本specと既存ADR-0007/0012。新しい方向性の判断はcatalog member追加の既存手順内）
- [ ] AGENTS.md（変更不要。新必須commandなし）
- [x] docs/product/requirements.md（FR-016備考）

## Execution checklist

- [x] Current behavior reproduced（baseline test green、依存gate確認済み。2026-09-23）
- [ ] Tests fail for the missing behavior（`BottomRegionStrategyTest` を先に追加し、未実装IDで失敗することを確認）
- [ ] Minimal implementation completed（registry/allocator/executor/policy/UI/test）
- [ ] Migration/recovery verified（bundle v2.7 publish、selection fail-closed、downgrade case test）
- [ ] Full relevant verification completed（上表gate一式）
- [ ] PR evidence and remaining risks recorded（AC-12 evidence、AC-13独立監査、残余risk）
