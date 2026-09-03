# Implementation Plan: Semantic naming for Organizer-generated folders

> Issue: #201
> Spec: [spec.md](./spec.md)
> Status: proposed

## Current evidence

- 固定名の発生源: `lawnchair/src/app/lawnchair/organizer/application/actions/OrganizationPlanMaterializer.kt:249-264` — `placeholderFolder` が `title = OptionalText.Present("Folder")` を設定する。生成 row の title はそのまま `Favorites.TITLE` へ書かれる (`application/adapter/LauncherLayoutAdapter.kt:663`、`application/adapter/RowManifestCodec.kt:185` の書き込み、`:358` の読み戻し = DB 側 exact verification は title を含む)。
- planner は grouping key の category を保持していない: `planning/PlanningPlacement.kt:448-475` の `formFolderGroups` は `(profile, category)` で grouping するが、`FolderGroup` (`:442`) は ordinal/profile/members のみ。full run の `FormedFolder` (`:95`) と incremental run (`:291`、共通の `formFolderGroups` を利用) も category を落として `NewFolder` を構築する。
- planning 型: `planning/PlanningResult.kt:86-91` — `NewFolder(ordinal, profile, workspacePlacement, members)`。`ValidatedLayoutPlan.newFolders` は同じ `NewFolder` 型 (`application/actions/OrganizationPlanMaterializer.kt:153`)。
- materialize の呼び出し site は2箇所: `application/protocol/PlanPreviewProtocol.kt:62` (preview、P6) と `application/protocol/LayoutApplicationModule.kt:173` (`materializeManualFullOrganizationPlan`、confirm 時 fallback)。composition root は `LayoutApplicationModule` (`:49` constructor、production wiring は `lawnchair/src/app/lawnchair/LawnchairApp.kt:111` の `LayoutApplicationModule.production(this)`)。
- preview 契約: `application/public/PlanPreview.kt:86-90` — `NewFolderChange(ordinal, placement, memberLabels)` は名前を持たない。projector は `application/preview/PlanPreviewProjector.kt:74-91` で `plan.actions` の `Insert` から構築しており、`insert.intended` に title が乗れば読める状態にある。
- UI: `organizer/ui/OrganizationPreviewContent.kt:164-169` — `newFolderRowText` は ordinal / position / members で文言を構成。文言 source は `lawnchair/res/values/strings.xml:1144` と `values-ja/strings.xml:244` の `manual_organization_preview_new_folder_row`。
- category → ローカライズ文言の既存投影: `organizer/ui/CategoryOverridePresentation.kt:16-60` — v1 taxonomy 34 category すべての label resource map (`organizer_category_*`)。bundle category との対応は既存 test (`mappedIdsForTest`) が担保。label 文言は root `res/values/strings.xml` (en、`organizer_category_art` :519 起) / `res/values-ja/strings.xml` (ja、`organizer_category_communication` :220 =「通信」) に存在。
- taxonomy authority: `organizer/rules/BuiltInOrganizerPolicyBundleSource.kt` — v1 taxonomy。fallback category `OTHER` は `formFolderGroups` が grouping 対象から除外 (`PlanningPlacement.kt:464` の `category == fallbackCategory` skip)。
- planner test harness: `tests/unit/app/lawnchair/organizer/planning/harness/PostPlanMaterializer.kt` が planner contract test 内で materialize 相当を行う。`NewFolder` を直接構築する test (`application/actions/NewFolderPlanFixtures.kt`、`IntendedStateCanonicalOrderTest`、`NewFolderCanonicalOrderProtocolTest`、`OrganizationPlanMaterializerReservationGuardTest`、`planning/ContractShapeTest`、`Oracle.kt` 等) は field 追加による compile fix が必要。
- 既存 policy: `OrganizerPolicyBundle.canonicalRepresentation()` (`organizer/rules/PolicyModels.kt:66-94`) は bundle semantics のみを含む。naming presentation を含めない。

## Design

### Modules and interfaces

```text
lawnchair/src/app/lawnchair/organizer/
├── planning/PlanningResult.kt            # FolderNaming (sealed, v1 = FromCategory) 追加
│                                        # NewFolder に naming field 追加
├── planning/PlanningPlacement.kt         # FolderGroup / FormedFolder へ category を通す
├── application/public/FolderTitleResolver.kt (新規)
│                                        # fun interface resolve(FolderNaming): String
├── application/actions/OrganizationPlanMaterializer.kt
│                                        # materialize に titleResolver 引数、Insert intended title を解決
├── application/preview/PlanPreviewProjector.kt
│                                        # NewFolderChange.name を intended title から構築
├── application/public/PlanPreview.kt     # NewFolderChange に name: PreviewLabel 追加
├── application/protocol/LayoutApplicationModule.kt
│                                        # constructor に FolderTitleResolver、2 materialize site へ渡す
└── ui/GeneratedFolderTitles.kt (新規)    # production resolver (presentation map + fallback 文言)
```

- `FolderNaming` は planning module の closed sealed interface。resolver 側 `when` を exhaustive に保ち、#182 の strategy が種別を追加できる。
- `FolderTitleResolver.resolve` の契約は spec §Single-resolution rule のとおり (non-blank、raw id 禁止、locale 適切)。blank は `OrganizationPlanMaterializer` が `Result.Invalid` へ落とす (黙って補完しない)。
- production resolver は `CategoryOverrideCategoryPresentations.forCategory(...).labelRes` を既定とし、map 外の category は `organizer_generated_folder_fallback_name` へ決定的に fallback する。`LayoutApplicationModule.production(context)` で wiring し、既定引数は設けない。
- interface の外へ漏らさない complexity: resolver の文字列 table、locale、`FolderNaming` → 文言の写像。planner と application core は Android resource に触れない。

### Data flow

```text
formFolderGroups (profile, category) ──> FolderGroup(category) ──> NewFolder(naming = FromCategory(category))
        │ semantic plan (Planned) — locale 非依存・決定的
        ▼
OrganizationPlanMaterializer.materialize(input, result, sourceState, titleResolver)
        └─ planned folder ごとに1回 resolve ──> Insert.intended.title = Present(resolved)
        ▼
ValidatedLayoutPlan (title 確定済み)
        ├──> PlanPreviewProjector ──> NewFolderChange.name = Named(intended.title) ──> 確認 UI 行文言
        └──> apply ──> Favorites.TITLE = intended.title ──> DB exact verification (TITLE 含む)
```

title を解決するのは materializer の1回だけである。preview と writer は同一 plan オブジェクトの同一文字列を読む (#194 の preview 済み plan 同一性により、確認画面と永続化結果は文字列レベルで一致する)。

### Alternatives rejected

- **planner が resolved string を持つ案**: planner が locale / resource を参照することになり、spec 10/12 の純粋性と locale 非依存 determinism を壊す。非採用。
- **UI / projector が category から名前を再推論する案**: preview と apply の title が別ロジックになり得る。issue が明示的に禁止する構造。非採用。
- **writer (adapter) が Insert 時に名前を決める案**: DB 書き込み層に naming semantics が漏れ、preview が表示しない名前が永続化され得る。非採用。
- **連番接尾辞 ("ゲーム 2")**: planner ordinal 依存でユーザー操作後に陳腐化し、文言構成も localization 負担を増やす。spec §Split folders により恒久排除。
- **`NewFolder` に resolved title を直接持たせる案**: semantic plan が locale 依存になり byte-equivalent determinism を失う。二層 (semantic + resolver) を採用。
- **resolver を既定引数で提供する案**: production call site が渡し忘れても固定名 `Folder` 相当へ silently 退行する。constructor 注入を必須化。
- **`FolderNaming` を string ベースの自由型にする案**: #182 拡張時に解釈が複数箇所に散らばる。typed sealed 階層で resolver の網羅性を compiler に強制する。

## Change set

| Area | Intended change | Why here |
|---|---|---|
| `planning/PlanningResult.kt` | `FolderNaming` (sealed, `FromCategory(category)`) 追加、`NewFolder` に `naming` field | grouping semantic の正本を plan へ載せる (spec scope 1) |
| `planning/PlanningPlacement.kt` | `FolderGroup` / `FormedFolder` / incremental 経路へ category を通し `NewFolder.naming` を設定 | grouping key が既に存在する唯一の地点 |
| `application/public/FolderTitleResolver.kt` (新規) | resolver port | single-resolution rule の seam |
| `application/actions/OrganizationPlanMaterializer.kt` | `materialize` に `titleResolver` 引数、`placeholderFolder` の固定名を廃止、`resolve` 結果を intended title へ、blank は `Invalid` | title 解決の唯一の点 |
| `application/protocol/LayoutApplicationModule.kt` | constructor に resolver、`PlanPreviewProtocol` と `materializeManualFullOrganizationPlan` へ渡す、`production(context)` wiring | 2 materialize site の共通供給点 |
| `application/protocol/PlanPreviewProtocol.kt` | module から resolver を受け、P6 の materialize 呼び出しへ渡す (preview protocol の read-only 契約は無変更) | P6 のみの機械的変更 |
| `application/public/PlanPreview.kt` | `NewFolderChange` に `name: PreviewLabel` | preview が apply と同一 title を運ぶ (spec §Preview integration) |
| `application/preview/PlanPreviewProjector.kt` | `insert.intended.title` から `name` を構築、`Absent` / blank は `Invalid` | projector は plan から読むだけ |
| `organizer/ui/GeneratedFolderTitles.kt` (新規) | production resolver (presentation map + fallback) | resource 参照は UI 層に局所化 |
| `organizer/ui/OrganizationPreviewContent.kt` + `ui/preferences/destinations/ManualOrganizationPreferences.kt` | `newFolderRowText` が `name` を含む、wording へ folder name を追加 | 確認 UI が意味を説明する (spec scope 3) |
| `lawnchair/res/values/strings.xml` + `values-ja/strings.xml` | `organizer_generated_folder_fallback_name` 新規、`manual_organization_preview_new_folder_row` を folder name 含む形式へ更新 | localization 正本 |
| `tests/unit/.../planning/*` | fixture / property へ naming assertion、`ContractShapeTest` へ shape 検証、harness の compile fix | FN-AC-07 / FN-AC-10 |
| `tests/unit/.../application/actions/*` | materializer test (既定 title / fallback / blank fail-closed / 既存 title 不変 / split 同一 title)、fixtures compile fix | FN-AC-02/03/04/05 |
| `tests/unit/.../application/preview/PlanPreviewProjectorTest.kt` | `name` ↔ intended title 一致、Absent / blank で Invalid | FN-AC-06 |
| `tests/unit/.../ui/*Preview*Test.kt` / `ManualOrganizationRunTest.kt` | folder name 含む row 文言、run 経路の resolver 注入 | FN-AC-09 |
| `specs/194-plan-preview-seam/spec.md` | §Labels and privacy の「`NewFolderChange` 自身の label は持たない」行を spec 201 参照へ更新 | 契約置き換えの正本更新 (spec scope 4) |
| `specs/195-organizer-confirmation-change-list/spec.md` | 「新規フォルダ行は配置 + メンバー label 一覧で識別する」行を同様に更新 | 同上 |
| `CONTEXT.md` / `DESIGN.md` | 生成フォルダ名の用語、naming seam の所在 (§4.1 / §4.2 に1文ずつ) | 正本反映 (spec scope 5) |

## Migration and recovery

- schema / rule format / policy bundle / recovery record 形式の変更なし。migration 不要。
- 既存 checkpoint / write set / recovery は row 単位で title 列を既に扱うため、生成フォルダの title は既存経路で適用・復元される。naming 固有の recovery 経路は追加しない。
- 旧 fixed 名 `Folder` で作成済みの端末への遡及 rename は non-goal (既存フォルダは不変)。

## Verification

- `./gradlew spotlessCheck`
- `./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.*'` (FN-AC-02〜10 の主要 evidence)
- `./gradlew assembleLawnWithQuickstepGithubDebug`
- planner property gate: `--tests '*PlannerGeneratedPropertyTest*'` (既存 64-case corpus が `naming` 追加後も決定的であること)
- 実機確認 (FN-AC-02 の manual evidence): representative fixture での Organizer run を実機で実行し、複数 generated folder の名称 (ja locale)・TalkBack 読み上げ・適用後 reload での title 一致を確認して PR へ記録する。
- PR は `Closes #201` を含め `risk: layout-data` label を付ける。high-risk independent-evidence gate (`final-status` + `docs/assessment/pr-201-generated-folder-semantic-naming.md`) を満たすまで merge しない。audit は実装 session とは別の作業として行う。

## Documentation updates

- `specs/194-plan-preview-seam/spec.md`: §Labels and privacy の該当行へ「spec 201 が生成フォルダ名を `NewFolderChange.name` として導入した」旨を追記。
- `specs/195-organizer-confirmation-change-list/spec.md`: 新規フォルダ行の識別規約行を更新。
- `CONTEXT.md`: 「生成フォルダ名 (Generated Folder Title)」用語を追加。
- `DESIGN.md`: §4.1 (planner が grouping semantic を plan へ載せる) と §4.2 (title 解決は application 側の1点) へ短く追記。
- `docs/engineering/organizer-diagnostics.md`: 変更なし (title 流出禁止は既存契約のまま)。影響確認のみ。

## Execution checklist

1. planning: `FolderNaming` と `NewFolder.naming` を追加し、`PlanningPlacement` 両経路で category を通す。planner unit test (fixture + property) を naming assertion 付きで先に更新し、失敗→実装を確認する。
2. application: `FolderTitleResolver` port、materializer の resolver 引数と blank fail-closed、`LayoutApplicationModule` / `PlanPreviewProtocol` の注入、`production` wiring を実装する。materializer test を fake resolver で追加する。
3. preview: `NewFolderChange.name` と projector 構築 (fail-closed 含む) を実装し、projector test を更新する。
4. UI / resources: production resolver、行文言の folder name 対応、strings (en/ja) を実装し、preview rendering test を更新する。
5. compile fix sweep: `NewFolder` を直接構築する既存 test / harness (`NewFolderPlanFixtures`、`Oracle`、`PostPlanMaterializer`、`ContractShapeTest`、`IntendedCanonicalOrder*` 等) を synthetic naming 付きへ更新する。
6. docs: spec 194 / 195 の該当行、`CONTEXT.md`、`DESIGN.md` を同じ PR で更新する。
7. Verification セクションのコマンドを全て実行し、結果を PR へ記録する。実機 Organizer run の evidence を添付する。
8. independent audit (`docs/assessment/pr-201-*.md`) を別 session で作成し、high-risk gate を通して merge する。
