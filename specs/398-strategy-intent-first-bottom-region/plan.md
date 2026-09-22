# Implementation Plan: Organizer strategyをユーザー意図一致優先で再評価し、下部領域semanticsのsuccessor strategy `BOTTOM_REGION_V1` を追加する

> Issue: #398
> Spec: [spec.md](./spec.md)
> Status: draft — review rev.3（2026-09-23 ChatGPT再レビュー反映）

## Current evidence

依存gateの確認（2026-09-23、`main` = `b4a2012640`）:

- #356 CLOSED、#365〜#377 全てCLOSED（`gh issue view <n> -R nunu1733/NunuLauncher --json state` を #356, #365〜#377 の14件に対して実行、全て `"state":"CLOSED"`）。
- 派生follow-up不在の再現可能な確認（2026-09-23実行）:
  1. **title/body探索**: `gh issue list -R nunu1733/NunuLauncher --state open --limit 200 --json number,title` → 18件のopen issue。titleに `#356〜#377` を含むもの0件。`gh issue list --state open --search "in:body <n>"`（#356, #365〜#377 の各番号）→ #356/#368/#377 を参照するopen issueは **#398自身のみ**、それ以外は`none`。
  2. **comments探索**: `gh issue list --state open --search "in:comments <n>"`（同14番号）→ ヒット10件（#398を除く）: #356 ← #323/#324/#170/#293/#304/#109/#206、#372 ← #351、#373 ← #337/#328、#374 ← #328。
  3. **ヒット個別判定**（該当コメント本文を `gh api repos/.../issues/<n>/comments` で取得し判定。2026-09-23）:
     - #323（PR #322/#204のfollow-up）、#170（PR 169のfollow-up）、#293（#228のfollow-up）: 親は#356系譜外。#356言及はsnapshot baselineの範囲記録。
     - #324（Product Language Reviewer導入）、#304（api36 window focus root cause）、#109（MVP Switch Access evidence）: #356文書を背景・分類説明として参照するのみ。
     - #206（Managed Grounded AI）: #356/#361で確定したIAの受け皿説明（coordination）。必須化されたmigration/follow-upではない。
     - #351（#327 evidence）: 「#372が先にlandした場合は再確認する」というcoordination noteのみ。
     - #337、#328: 自らの着手時期・表示構造を#373/#374と座標合わせするnote（#373/#374自体はCLOSED/implemented済み）。#365〜#377が生んだ必須修正ではない。
  4. **補助証拠（規約）**: 本repoのfollow-up Issueは親をtitle/bodyに明示する慣行（#323＝PR #322、#170＝PR 169、#293＝issue #228）が確認できる。title/body探索の網羅性をこの慣行が支える。
  5. 結論: #356系譜由来の未完了migration/follow-upは存在しない。#398のscheduling/dependency gateを満たす。
- baselineのplanner/rules unit testはgreen（`./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.planning.*' --tests 'app.lawnchair.organizer.rules.*'` → BUILD SUCCESSFUL、2026-09-23実行）。canonical族（folder形成含む）のharness idempotenceが実行可能証明としてgreenであることを確認済み。

関連code pathと現在の振る舞い（確認済み事実。行番号は2026-09-23時点）:

- `lawnchair/src/app/lawnchair/organizer/planning/LayoutStrategyRegistry.kt`
  - `StrategyDefinition`（L41-62）: strategyの宣言的semanticsをdataとして持つ。
  - 登録済み8 strategy（L161-251）。`GLOBAL_COMPACT_V1`（L192-203）: 1×1 singletonのみeligible、`CAPTURED_VISUAL_GLOBAL` 順、`CAPTURED_THEN_NEW`、既存folderはSTRATEGY_PRESERVED — 本strategyの形状の鏡像（traversal/順序方向/領域制限が差分）。
- `lawnchair/src/app/lawnchair/organizer/planning/FullRunExecution.kt`
  - `execute`（L110-126）: `unitOrder` でdispatch。未知組合せはloud failure。
  - `executeGlobalCompact`（L558-682）: strategy-fixed handling（L565-577）、formation（eligible candidatesのみ、既存folder除外、L581-601）、単一stream + `allocateCapturedThenNew`、形成folderをunitsの後に `(preferred page key, ordinal)` 順で配置（L647-672）— 本strategyのexecutorの直接的な雛形。
  - intent hint helpers: `preferenceAllocateOnPage`（L441-464、preserve cell / region band hint）等のpage-local allocation helperは **既存に存在するが、`BOTTOM_REGION_V1` では一切使用しない**（allocation例外なし。intentは消費順序biasとしてのみ消費。plan rev.4決定）。
- `lawnchair/src/app/lawnchair/organizer/planning/PlacementAllocator.kt`
  - `findRowMajorFirstFit`（L230-289）: 唯一のtraversal実装。`rowWindow`（L224-228、#235 widget bandで使用済み）と `BOTTOM_UP_ROW_MAJOR`（L261-266）は両方とも既存であり、領域付きbottom-up走査は既存部品の組合せで表現できる。
  - `allocateCapturedThenNew`（L167-177）: sweep走査の既存実装。領域付きvariantは未存在（新規追加が必要）。
  - `allocateOnPageOnlyInBand`（L148-165）: 本strategyでは **使用しない**（レビュー高2対応: BOTTOM affinityのpage局所hintは廃止）。
- `lawnchair/src/app/lawnchair/organizer/planning/PlanningPlacement.kt`
  - incremental runは `TOP_LEFT_ROW_MAJOR` 強制（L26-29）。strategyのcellTraversalはfull-run/scope-composedのみ効く。
  - scope-composed candidate tail（L236-378）: `strategy.pageScope` と `strategy.createsFolders` を消費。`CAPTURED_THEN_NEW` → `allocateCapturedThenNew`（L335-340）。
- `lawnchair/src/app/lawnchair/organizer/application/public/PlanPreview.kt`
  - `MoveChange.destination` / `PreservedChange.current` / `AddChange` は `PreviewPosition`（`pageDisplayOrdinal`、`isNewPage`、`rowBand: RowBand{TOP,CENTER,BOTTOM}`、`rowOrdinal`、`columnOrdinal`、L208-254）を運ぶ → preview projection levelで領域内判定（`rowOrdinal` vs 領域行範囲）が可能。新projectionは不要。
- `lawnchair/src/app/lawnchair/organizer/rules/PolicyModels.kt`
  - `POLICY_BUNDLE_VERSION = "organization-policy-v2.6"`（L185）と履歴コメント（L175-184）。catalog coherence検証（L167-171）。
- `lawnchair/src/app/lawnchair/organizer/rules/BuiltInOrganizerPolicyBundleSource.kt`（L47-59）: runtimeSupported=8戦略、default=CANONICAL。
- `lawnchair/src/app/lawnchair/organizer/diagnostics/model/RunEvent.kt`（L49-68）: `APPROVED_VERSIONS` allowlist（strategy ID 8種）。
- `lawnchair/src/app/lawnchair/organizer/ui/preferences/destinations/ManualOrganizationPreferences.kt`（L1288-1310）: `strategyDisplayName` / `strategyDescription` のID→R.string写像。
- `lawnchair/res/values/strings.xml`（L1041-1067）/ `values-ja/strings.xml`（L228-245）: strategy名・説明。
- tests: `CrossStrategyCorpusTest` / `StrategyPerformanceSampleTest` / `RunEventSerializationTest` / `BuiltInOrganizerPolicyBundleSourceTest` は `LayoutStrategyRegistry.acceptedIds` またはbundle等価でregistry駆動（新strategy自動対象）。`StrategyPickerInstrumentationTest`（L121-128）はofferされるstrategy名の文字列リストを列挙（新nameの追加が必要）。

推測（実装時に確認）: なし。idempotenceの実行可能証明はspec AC-5の専用counterexample fixtureで直接固定する（shared suiteが通らない状態遷移を直接踏む。spec 237前例）。

## Design

### Modules and interfaces

外部seam `OrganizationPlanner.plan(OrganizationInput) -> PlanningResult` は不変。すべて内部seam:

1. `planning/LayoutStrategyRegistry.kt`
   - `StrategyId` 定数 `BOTTOM_REGION_V1` を追加（ADR-0012: 新semanticsは新ID）。
   - `StrategyDefinition` に `preferredRegion: PreferredRegion? = null` fieldを追加（sealed interface `PreferredRegion`、`data object LowerHalf` のみ）。既存8戦略はデフォルトnullで不変。`placeFullRun` dispatchを `preferredRegion != null` 優先で分岐（未知組合せは既存どおりloud failure）。
   - 領域計算は純粋関数 `lowerPreferredRegion(rows: Int): IntRange = (rows - (rows + 1) / 2) until rows`（planning package内、device rowsのみから決定的）。
   - 定義（spec Decision 1の表どおり）: `eligibleUnitFilter` は **`1×1` かつ app/deep shortcut**（`GLOBAL_COMPACT_V1` と同一のfilter式）、`createsFolders = true`、`unitOrder = CAPTURED_VISUAL_GLOBAL_REVERSED`（**新enum値をcatalog宣言の正本とする。executor内部の隠れreverseは持たない**。semanticsをregistry dataへ載せる）、`pageScope = CAPTURED_THEN_NEW`、`cellTraversal = BOTTOM_UP_ROW_MAJOR`、`widgetPolicy = null`、`placeFullRun = FullRunExecution::executeRegionSweep`。
     - `strategyFixes()` の派生規則（`unitOrder != CANONICAL_TIE_BREAK && !eligibleUnitFilter`）は新enumでもそのまま機能する（非 `1×1`・既存folderが `STRATEGY_PRESERVED`。specのfixed setと一致）。
     - executor dispatchは `preferredRegion != null` を優先し、既存 `CAPTURED_VISUAL_GLOBAL` 経路には影響しない。registry駆動test（`CrossStrategyCorpusTest` 等）は新enumを自動巡回し、`ContractShapeTest` がenum値を列挙している場合はそこへ追加する（実装時に確認）。
2. `planning/PlacementAllocator.kt`
   - `allocateCapturedThenNewInRegion(span, rowWindow)` を追加: captured page群（PageOrder順）→作成済み新page群を `findRowMajorFirstFit(..., rowWindow=rowWindow)` で走査し、なければ新pageを作成して領域内first-fit。`allocateOnNewPages` にwindow引数の内部variant（既存呼び出し元は無変更）。
3. `planning/FullRunExecution.kt`
   - `executeRegionSweep(context)` を追加。`executeGlobalCompact` と同一の構造で、差分は次のとおり:
     - **preserve hint（soft）**: preserve=true unitのcaptured cellが下部優先領域内かつ割当順番時に空きのときだけ正確に使用（allocatorにoccupancy判定付きの領域内cell直接配置helperを追加、または既存helperの領域clip版）。不成立時はpreserveなしと同一の `allocateCapturedThenNewInRegion` へフォールバック。領域外captured cellは決定的に無視。既存 `preferenceAllocateOnPage` 系は領域clipがないため **そのまま使わない**。
     - **消費順序**: `(componentRank, importanceRank, BOTTOM-affinity-class(0/1), PageOrder, PageId, cell.y DESC, cell.x, ItemId)`。上位3keyはidentity-stableなintent bias classで、intentなしでは定数（既存intent layeringと同一パターン）。class間の順序はrun間不変、同class内はbaseの逆captured visual順を復元する。`minimizeMovement` はbase順序（captured位置順=移動最小化順）の採用として消費され、追加の切替を行わない（spec合成matrix）。
     - **領域**: `lowerPreferredRegion(device.rows)` を全allocationに適用（新page含む）。上段cellへの新規配置は発生しない。
     - strategy-fixed handling / formation / 形成folderのunits後配置 / disposition / `appendPreservedPlacements` / 出力canonical化は `executeGlobalCompact` と同一。
   - `planning/PlanningPlacement.kt` のcandidate tail（L335-340）: `strategy.preferredRegion != null` のとき候補割当を領域付き走査へ変更し、領域内不成立candidate（非 `1×1` を含む）を `STRATEGY_SCOPE_FULL` でunplaced。
4. policy/selection/diagnostics/UI
   - `rules/BuiltInOrganizerPolicyBundleSource.kt`: runtimeSupportedへ `BOTTOM_REGION_V1` 追加。
   - `rules/PolicyModels.kt`: `POLICY_BUNDLE_VERSION = "organization-policy-v2.7"`、履歴コメントに「#398 published -v2.7 (BOTTOM_REGION_V1)」を追記。
   - `diagnostics/model/RunEvent.kt`: `APPROVED_VERSIONS` へ `"BOTTOM_REGION_V1"` 追加。
   - `ui/.../ManualOrganizationPreferences.kt`: `strategyDisplayName`/`strategyDescription` に新IDの写像追加。
   - `lawnchair/res/values/strings.xml` + `values-ja/strings.xml`:
     - 新規: `organization_strategy_bottom_region_name/description`。説明は下部領域・上側余白・page増加の可能性・widget不動を述べる。
     - 修正（copy-only、semantics変更なし）: `bottom_first_description` は「app/folderを下段から順に配置する。widgetは移動しない」の趣旨へ（「すべて埋める」等の充填保証語は使わない）。`bottom_first_v2_description` はspec 235 semantics（app下側、移動可能widgetを上側top-anchored）を維持したまま文言のみ精緻化。
     - EN/JA双方でID→copyのexact mappingを `strategyDisplayName`/`strategyDescription` testで固定し、spec 235 picker copy oracle（app領域とwidget領域の関係の記述）を退行させない。

### Data flow

入力（`OrganizationInput`）は既存のままで追加fieldなし。run mode:

- `FullOrganization`: `PlanningPlacement.place` → `FullRunContext` → `executeRegionSweep`。movable `1×1` unitsを単一streamでsweep、fixed set（自然保持 + 非 `1×1` + 既存folder）はoccupancy、新pageは領域付きで作成。出力 `PlacementOutput` は既存型のまま。
- `ScopeComposedOrganization`: full-run phaseは同一executor、candidate tailは領域遵守 + unplaced。
- `IncrementalPlacement`: 既存どおりcanonical強制（新strategyは関与しない）。

provenance: selection identityが既存の第5policy inputとして自動参加（store契約は不変）、rules identityはeffective `RuleSemantics` のdigestへ新strategy IDが入る（既存機構）。

### Alternatives rejected

- **`BOTTOM_FIRST_V1` のsemantics改訂（defect correction扱い）**: ADR-0012のidentity規律（behavior変更は新ID）に反する。却下。
- **V1のpicker非表示/retire**: 既存ユーザーselectionを突然unsupported化する価値がない。維持を採用。
- **canonical族unit順序（EF→NF→singleton、identity-based）+ 任意spanでのcross-page sweep**: run 1のNF（ordinal順）→run 2のEF（ItemId順）遷移で消費順序の同値性を保証する契約がなく、heterogeneous span下でINV-8を破り得る（レビュー高1）。採用案は `GLOBAL_COMPACT_V1` 形状（1×1制限 + 逆visual順 + 既存folder固定）。
- **順順captured visual順（y昇順）のsweep**: bottom-up充填とvisual読み出し順序が逆で、materialized状態が消費順序を復元せずreplanで回転する。逆visual順を採用（spec「Idempotence argument」節）。
- **`GLOBAL_COMPACT_V2` 型の既存1×1 folderのstream参加（spec 237）**: 証明対象が形成込みの状態遷移へ拡大する。本strategyではV1型の固定を採用し、必要なら別IDで検討。
- **自page優先overflow（PREFERRED_THEN_NEW相当）**: 既存pageの下部領域が空のまま新pageの下部が埋まる可視的不整合。却下。
- **region比率を下1/3（intent BOTTOM bandと統一）にする案**: rows=3で1行、比率が整数除算で非一様。下1/2（ceil）を採用（spec比較表）。
- **widget移動対応（PageLocalTopAnchored併用）**: widget移動は #235 V2 successorsの領域。最小diffのためwidget不動を採用。
- **`regionAffinity=BOTTOM` のpage局所allocation hint（`allocateOnPageOnlyInBand`）**: captured pageを優先する隠れたpage affinityがnormative sweepと矛盾（レビュー高2）。消費順序biasのみへ変更。

## Change set

| Area | Intended change | Why here |
|---|---|---|
| `planning/LayoutStrategyRegistry.kt` | `BOTTOM_REGION_V1` 定数・定義、`PreferredRegion` field、`CAPTURED_VISUAL_GLOBAL_REVERSED` | strategy catalogの正本 |
| `planning/PlacementAllocator.kt` | `allocateCapturedThenNewInRegion`（+新page window variant） | 唯一のshared allocatorへ走査variantを追加（第2実装を作らない） |
| `planning/FullRunExecution.kt` | `executeRegionSweep`、dispatch | 単一full-run executorの分岐先 |
| `planning/PlanningPlacement.kt` | candidate tailの領域遵守 | scope-composed semanticsをstrategy宣言から消費 |
| `rules/BuiltInOrganizerPolicyBundleSource.kt` | runtimeSupported追加 | bundle宣言の正本 |
| `rules/PolicyModels.kt` | `-v2.7` bump + 履歴 | ADR-0007 §8 enablement規律 |
| `diagnostics/model/RunEvent.kt` | allowlist追加 | version identifier許容の正本 |
| `ui/.../ManualOrganizationPreferences.kt` | 表示写像追加 | picker copyの唯一の消費点 |
| `res/values{,-ja}/strings.xml` | 新copy + V1/V2説明の実挙動一致修正 | localized copyの正本 |
| `tests/unit/.../planning/BottomRegionStrategyTest.kt`（新規） | spec scenario群 + 専用counterexample fixture + orientation matrix | public seam経由の契約test |
| `tests/unit/.../application/preview/`（既存projector testに追加） | preview projection空間oracle（領域内assert） | preview契約の検証 |
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
| AC-2 | `BottomRegionStrategyTest`（normative rules各項） | `./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.*'` |
| AC-3 | 同上（dense 3strategy比較fixture） | 同上 |
| AC-4 | `GoldenOracleCorpusTest` + 既存strategy test群 無変更green | 同上 |
| AC-5 | `CrossStrategyCorpusTest` / `PlannerGeneratedPropertyTest` / 専用counterexample fixture（複数既存folder + 新folder形成 + 非 `1×1` + fragmented lock/reservation + 複数page + intent bias item群の同居、適用→recapture→replan空差分） | 同上 |
| AC-6 | `IntentPreferenceStrategyMatrixTest` / `WidgetIntentAuthorityTest` / BOTTOM-affinity非page-affinity fixture / preserve soft-hint fixture（hint成功=displacement 0・hint不成立=無preserveと同一・displacement非悪化比較・空差分replan） | 同上 |
| AC-7 | `BuiltInOrganizerPolicyBundleSourceTest` | 同上 |
| AC-8 | `LayoutStrategySelectionStoreTest` | 同上 |
| AC-9 | ID→copy exact mapping test、spec 235 picker oracle regression、preview projection空間oracle | unit test + API 36 emulator instrumentation lane（CI） |
| AC-10 | `RunEventSerializationTest` | unit test |
| AC-11 | `BottomRegionStrategyTest` orientation matrix | unit test |
| AC-12 | physical-device evidence artifact、または「Issue #398本文Acceptance criteriaの明示改訂（commit/編集履歴）+ owner decision comment」。**コメント単独では代替不可** | physical device / Issue |
| AC-13 | `final-status` CI + 独立監査記録 | GitHub Actions + `docs/assessment/` |

その他の必須gate: `./gradlew spotlessCheck`、`./gradlew assembleLawnWithQuickstepGithubDebug`、`python3 tools/repo-contract/validate_repo_contract.py`（docs変更時。既存の未追跡 `worktree-371/` が地元で検出される場合はCI基準を正本とする）。

含めるべき観点: unit/contract（上記）、property（harness/registry駆動、自動）、failure injection（既存 `AllocationFault` loud failure経路は無変更）、performance（`StrategyPerformanceSampleTest` 自動対象、測定値をPRへ記録）、UI/accessibility（instrumentation lane。新copyのTalkBack文言は既存picker構造を流用するため新規accessibility surfaceは追加しない）。

## Documentation updates

- [x] spec status/history（本spec、受入時にstatus更新）
- [x] CONTEXT.md（下部優先領域）
- [ ] DESIGN.md（変更不要。strategyはplanning module内部seamのまま、gate表への新規行も不要と判断。spec 182行で既にカバー）
- [ ] ADR（不要と判断: 判断の正本は本specと既存ADR-0007/0012。catalog member追加の既存手順内）
- [ ] AGENTS.md（変更不要。新必須commandなし）
- [x] docs/product/requirements.md（FR-016備考）

## Execution checklist

- [x] Current behavior reproduced（baseline test green、依存gate確認と再現可能な証拠記録済み。2026-09-23）
- [ ] Tests fail for the missing behavior（`BottomRegionStrategyTest` を先に追加し、未実装IDで失敗することを確認）
- [ ] Minimal implementation completed（registry/allocator/executor/policy/UI/test）
- [ ] Migration/recovery verified（bundle v2.7 publish、selection fail-closed、downgrade case test）
- [ ] Full relevant verification completed（上表gate一式）
- [ ] PR evidence and remaining risks recorded（AC-12 evidence、AC-13独立監査、残余risk）
