---
issue: "#201"
status: proposed
requirements: []
risk:
  - layout-data
updated: 2026-09-04
---

# Semantic naming for Organizer-generated folders

## Problem

実機で Organizer の整理を実行すると、カテゴリ単位で作成された複数のフォルダがすべて固定名 **`Folder`** で作成される。閉じた状態では何を基準に整理されたフォルダか判別できず、Organizer が行った整理結果の意味をユーザーへ伝えられない。

原因は data flow にある。planner はカテゴリごとに folder grouping を形成するが、 grouping key であるカテゴリを `NewFolder` から落としており (`PlanningPlacement.kt` の `FolderGroup` → `FormedFolder` ともに category を保持しない)、application 側の `OrganizationPlanMaterializer.placeholderFolder` が title を `OptionalText.Present("Folder")` とハードコードしている (`OrganizationPlanMaterializer.kt:257`)。この固定名は localization もされていない英語 literal であり、writer がそのまま `Favorites.TITLE` へ書き込む。

preview 側も同様である。planner が名前を決めないため、spec 194 は「`NewFolderChange` 自身の label は持たず、配置 + member labels で識別する」契約とし、#195 の確認 UI も「新規フォルダ行は配置 + メンバー label 一覧で識別する」実装になっている。apply される実際のフォルダ名を preview が表現できない。

## Outcome

Organizer が新規生成するフォルダは、その grouping semantic に基づく意味のある user-facing 名を持つ。現行の category-based strategy では、可能な場合はカテゴリをローカライズ済みのユーザー理解可能な名称へ投影し、生成フォルダ名として使う。

名前の権威は次の二層で分離する:

- **semantic naming identity (意味の正本)**: planner が grouping key から導出し、`NewFolder` (したがって `ValidatedLayoutPlan.newFolders`) に typed に載せる。locale 非依存・決定的。
- **resolved title (表示文字列の正本)**: materializer が単一の注入された resolver 経由で semantic identity から1回だけ解決し、`ApplyAction.Insert` の intended state の title として確定させる。preview projector と apply writer はどちらもその解決済み値を plan から読むだけで、title を再計算しない。

UI、planner、application writer が独立に title を推論する構造にはしない。#182 が将来 layout strategy を追加する際にも、strategy が folder grouping を作るなら naming semantic を供給できる seam であり、Play Store category 専用フィールドには固定しない。

## Scope

1. planning 型 `NewFolder` に semantic naming identity (`FolderNaming`) を追加する。v1 は category 由来の1種別のみで、#182 の strategy 拡張に備えた closed sealed 階層とする。full organization と incremental placement の両 planner 経路 (共通の `formFolderGroups`) で grouping key から決定的に導出する。
2. application 側に `FolderTitleResolver` port を追加し、`OrganizationPlanMaterializer` が planned folder ごとに1回だけ title を解決して `ApplyAction.Insert` の intended state (`CanonicalItemState.title`) へ書き込むようにする。既存の2つの materialize 呼び出し site (plan preview protocol と confirm 時 materialize) は module が保持する resolver を渡す。production adapter は既存の v1 taxonomy presentation map と新設の fallback 文言で実装し、`LayoutApplicationModule.production` で wiring する。
3. preview projector の `NewFolderChange` に生成フォルダ名を追加する。値は intended state の title (解決済み文字列) から得て、apply と同一の値を preview が運べるようにする。#195 の確認 UI の新規フォルダ行文言にフォルダ名を反映する (行構造・group 構造は変更しない)。
4. spec 194 の「新規 folder の名前は planner が決めないため、`NewFolderChange` 自身の label は持たない」点と spec 195 の「新規フォルダ行は配置 + メンバー label 一覧で識別する」点を本契約が置き換えることを明記し、両 spec の該当行を同じ PR で更新する。
5. `CONTEXT.md` に生成フォルダ名の用語を追加し、`DESIGN.md` に naming seam の所在を反映する。
6. planner / materializer / preview / UI の既存 test surface へ naming 関連の assertion と fixture を追加する。

## Non-goals

- layout strategy の追加・選択 UI (#182)。
- 既存フォルダの一括 rename。既存フォルダの保持・移動時に title を変更しない。既存フォルダの自動 rename は別の明示的な product decision / Issue とする。
- ユーザーによる手動フォルダ名編集機能。
- LLM / network を用いたフォルダ命名。
- raw Play Store metadata を UI へそのまま表示すること。
- apply / recovery safety contract の緩和。schema、rule format、policy bundle (`canonicalRepresentation()`)、taxonomy version、diagnostics 契約は変更しない。
- confirm UI の layout 再構成 (#195 済)。本 Issue の UI 変更は新規フォルダ行の文言へのフォルダ名追加にとどめる。
- フォルダ名への連番付与 (split folder の識別子としての)。§Split folders 参照。

## Domain language

`CONTEXT.md` に次の用語を追加する (承認時に反映):

**生成フォルダ名 (Generated Folder Title)**:
Organizer が新規生成したフォルダへ、その grouping semantic から単一の resolver 経由で決定して適用される user-facing なタイトル。semantic naming identity が plan 内の正本であり、解決済み文字列は preview と apply の両方が同一 plan から消費する。
_Avoid_: フォルダ名の自動推論 (UI 側再計算を想起させる)、Folder (固定名)

## Generated-folder naming contract

### Authoritative source and types

grouping semantic の正本は planner であり、生成フォルダ名の意味を決めるのはその grouping key である。planner は grouping 形成時に持っている semantic を `NewFolder` へ載せる:

```kotlin
// planning/PlanningResult.kt
sealed interface FolderNaming {
    data class FromCategory(val category: CategoryId) : FolderNaming
}

data class NewFolder(
    val ordinal: NewFolderOrdinal,
    val profile: ProfileId,
    val naming: FolderNaming,
    val workspacePlacement: PlacementTarget.WorkspaceTarget,
    val members: List<ItemId>,
)
```

- `FolderNaming` は closed sealed 階層とし、v1 は `FromCategory` のみを許す。resolver 側の `when` を網羅的に保ち、#182 の strategy が新種の grouping semantic を導入する際はここに種別を追加する (category-only の特殊実装に閉じない)。
- `NewFolder` は `ValidatedLayoutPlan.newFolders` にも同じ型のまま載るため、semantic naming identity は semantic plan から executable plan へ劣化なく伝播する。
- `FolderNaming.FromCategory.category` は既に taxonomy version の管理下にある (`CategoryId` は `TaxonomyContract.version` で解釈が固定される)。naming のために新しい version field や digest を導入しない。taxonomy / rule version の不一致は既存の materializer gate (`result.taxonomyVersion != input.taxonomy.version` 等) が stale として扱う。
- planner の決定性契約 (spec 12) は変えない: `naming` は grouping key の純関数であり、同一入力から byte-equivalent な canonical plan を生む性質を保つ。planner は locale や文字列 resource を一切扱わない。

### Single-resolution rule (title の解決点)

user-facing 文字列への解決は application 側の1点でのみ行う:

```kotlin
// application/public/FolderTitleResolver.kt
fun interface FolderTitleResolver {
    /** 契約: non-blank かつ locale 適切な title を返す。
     *  raw category ID / package / 内部 ID を返してはならない。 */
    fun resolve(naming: FolderNaming): String
}
```

- `OrganizationPlanMaterializer.materialize` は `titleResolver` を引数に受け、planned folder ごとに `title = OptionalText.Present(resolver.resolve(folder.naming))` を `ApplyAction.Insert` の intended state へ設定する。resolver が blank を返すことは port 契約違反であり、`Result.Invalid` として fail-closed に扱う (preview では既存の `MATERIALIZATION_INVALID` 経路へ、confirm 時 materialize では既存の Invalid 経路へ落ちる)。fallback を materializer 側で黙って補完しない。
- resolver の production 実装は organizer UI 層に置き、v1 taxonomy の presentation map (`CategoryOverrideCategoryPresentations` の label resource) と、解決不能時の汎用 fallback 文言 (新 string `organizer_generated_folder_fallback_name`、en "Folder" / ja "フォルダ") で構成する。presentation map に存在しない category id が来ても raw id を返さず fallback 文言へ落とす。bundle category と presentation map の対応は既存 test が担保する。
- `LayoutApplicationModule` は resolver を constructor で受け、plan preview protocol と `materializeManualFullOrganizationPlan` の両呼び出し site に渡す。`LayoutApplicationModule.production(context)` で production adapter を wiring する。既定値による暗黙 fallback は設けない (固定名 `Folder` への退行を構造的に不可能にするため)。
- 1回の run 内で title は materialize 時に確定し、以後不変である。plan は process-local であり (#194)、process death 後は run 全体が作り直されるため、locale 変更を跨いだ古い文字列の適用は発生しない。

### Determinism, idempotence, and stale-plan semantics

- semantic plan の決定性: 同一入力からの canonical plan は `naming` を含めて byte-equivalent である (grouping key の純関数だから)。planner の determinism / metamorphic / idempotence test は無変更で通り、fixture corpus に naming の assertion を追加する。
- 解決済み title は resolver と文字列 table に対して決定的である。materializer / preview / writer のどれも locale を再照会して再計算しないため、同一 plan オブジェクトからは常に同一文字列が観測される。
- 適用後の冪等性: 生成フォルダは次回 run では既存フォルダとして扱われ、既存フォルダは grouping の候補にならず title も変更されない (現行の preservation 規約のまま)。したがって同一状態での再実行は空差分であり、naming は冪等性を壊さない。
- policy bundle / taxonomy version は不変である。命名 presentation は bundle semantics ではないため `OrganizerPolicyBundle.canonicalRepresentation()` は変えず、bundle digest も変化しない。

### Split folders

同一 grouping が容量や `minGroupSize` で複数フォルダへ split された場合も、各フォルダは同一の resolved title を持つ (連番や接尾辞を付けない)。

- 連番は planner 内部の ordinal に依存し、ユーザー操作でフォルダを動かされた瞬間に陳腐化する。内部識別子を user-facing 名へ反映しないことを本契約は恒久化する。
- Launcher は同一 title の複数フォルダを許容する。split の識別は preview では member label 一覧、ホーム画面では内容で行う。
- 命名は ordinal から独立に決まるため、split の仕方 (capacity, partition) が変わっても title は不変であり、deterministic である。

### Unsupported, unknown, and fallback categories

- v1 の planner は fallback category (`OTHER`) を grouping の対象にしない (既存 `formFolderGroups` 規約)。したがって通常経路で生成されるフォルダは常に非 fallback category を持つ。この規約を本契約は明示的に維持する。
- 解決不能な `FolderNaming` (presentation map 未収録の category id、将来種別の未実装到達) は、production resolver が汎用 fallback 文言へ決定的に落ちる。raw category ID / package name / `ItemId` / ordinal を user-facing surface へ出してはならない。
- resolver の blank 返却は port 契約違反として fail-closed (§Single-resolution rule)。黙って内部 ID へ落ちる経路は存在しない。

### Existing folders

Organizer が既存フォルダを保持・移動するだけの場合、その title は capture 時の値のままであり、本変更は既存フォルダの title を一切書き換えない。materializer が title を設定するのは `ApplyAction.Insert` (planned folder) のみである。既存フォルダの自動 rename は別 product decision とする。

## Preview integration

`PlanPreviewProjector` の `NewFolderChange` に生成フォルダ名を追加する:

```kotlin
data class NewFolderChange(
    val ordinal: NewFolderOrdinal,
    val name: PreviewLabel,     // 常に Named(resolved title)。apply 対象と同一の値
    val placement: PreviewPosition,
    val memberLabels: List<PreviewLabel>,
) : PreviewChange
```

- 名前の source は当該 `ApplyAction.Insert` の intended state (`CanonicalItemState.title`) であり、projector は plan から読むだけである。preview 専用に category から名前を再推論しない。#194 の projector 責務分担 (action / before-after の正本は `ValidatedLayoutPlan`) に従う。
- intended title が `Absent` または blank な Insert は projection 契約違反であり、`Result.Invalid` (`NotPlannable(MATERIALIZATION_INVALID)`) として fail-closed に扱う。
- `name` は常に `PreviewLabel.Named` である (解決不能時も resolver が fallback 文字列を返すため)。`KindFallback` は生成フォルダ名には使わない。raw id は運ばない。
- 確認 UI の新規フォルダ行は、既存の行構造 (group 順序・truncation・決定的行順) を保ったままフォルダ名を含む文言へ更新する (例: 「新規フォルダ「通信」を Page 2 に作成（メンバー: A, B）」)。行構造・件数 truth (`PreviewCounts`) の変更はしない。
- #194 の「`NewFolderChange` 自身の label は持たない」契約と #195 の「配置 + メンバー label 一覧で識別する」行規約は、本契約が置き換える。両 spec の該当行を同じ PR で更新する。

## Apply and persistence

- title は既存の canonical state → row 書き込み経路 (`Favorites.TITLE`) で永続化される。DB schema、row codec、write-set 形式、checkpoint / recovery manifest は変更しない。生成フォルダの title は適用対象の write set に既に含まれる title 列へ乗る。
- 適用後の検証は既存契約のまま強化も緩和もしない: DB 側の exact verification が TITLE を含む intended state との一致を検証し、相関リロード生成の model snapshot による検証 (spec 152) は title を model-verifiable projection の対象外 (DB 側検証で担保) として扱う現行規約に従う。
- A2 exact precondition gate は intended state を対象としないため、locale 依存の文字列が gate の意味を変えない。preview → confirm は同一 plan オブジェクトが適用されるため (#194)、preview 表示の title と適用される title は文字列レベルで同一である。
- recovery / rollback は既存の row 単位の復元で機能し (適用前の rows には旧 title が含まれる)、naming 固有の recovery 経路は追加しない。

## UX expectations

- 生成された複数フォルダがすべて `Folder` にならない。category grouping では例えば通信系 grouping に「通信」(en: "Communication") が表示される。
- ホーム画面でフォルダを開かなくても grouping の意味を識別できる。
- 名前解決不能時は安全で一貫した fallback 文言を使用し、raw category ID / package name / 内部 ID を露出しない。
- localization: 文言は strings (`values/` + `values-ja/`) で管理し、planner / application core に日本語・英語の固定文言をハードコードしない。
- TalkBack: フォルダアイコンの既存 content description (`folder_name_format_exact`) が title を読むため、意味のある名前がそのまま読み上げ対象になる。確認画面の新規フォルダ行は既存の row 単位の label 構成を保つ。
- font scaling: 追加は text row のみであり、既存の confirmation UI の scaling 挙動に従う。

## Behavior scenarios

### Scenario: Category grouping produces named folders

Given 通信 category とゲーム category に各3件以上の移動可能アプリがあり、planner が same-profile/category grouping で2つの新規フォルダを生成した,

When materializer が plan を materialize する,

Then 各 `ApplyAction.Insert` の intended state の title は、resolver が各 grouping の category から解決した user-facing 名 (ja locale で「通信」「ゲーム」) であり、固定名 `Folder` ではない,

And semantic plan の `NewFolder.naming` はそれぞれの category を保持し、preview の `NewFolderChange.name` は適用される title と同一の文字列を運ぶ。

### Scenario: Planner / preview / writer do not recompute titles independently

Given materialize 済み `ValidatedLayoutPlan` が1つの planned folder を含む,

When preview projector が `NewFolderChange` を構築し、apply writer が `Favorites.TITLE` を書き込む,

Then 両者が読む値は同一 plan の同一 intended state の title であり、どちらも `FolderNaming` からの再解決を行わない,

And title を解決する呼び出しは materializer の1回だけである。

### Scenario: Same category split across multiple folders keeps one deterministic title

Given 1つの category の移動可能アプリが folder capacity を超え、planner が同 category の2フォルダへ split した,

When materializer が plan を materialize する,

Then 両フォルダの title は同一の resolved 文字列であり、連番・接尾辞・ordinal を含まない,

And 同一 `(input, result, resolver)` からの再実行でも同一の title 群が得られる。

### Scenario: Unknown category falls back without exposing raw identifiers

Given resolver が presentation map 未収録の category を含む `FolderNaming.FromCategory` を受けた,

When materializer が title を解決する,

Then resolver は汎用 fallback 文言を返し、raw category ID や package 名を title に含まない,

And resolver が契約に反して blank を返した場合は materializer が `Invalid` となり、preview では fail-closed (`MATERIALIZATION_INVALID`) となって apply へ進まない。

### Scenario: Existing folders keep their titles

Given 既存フォルダ F (title "自分で作った") が capture され、organizer run が F を保持または移動する only の plan を生んだ,

When plan が適用される,

Then F の `Favorites.TITLE` は適用前と同一であり、naming seam は F に触れない,

And 次回 run でも F は grouping の候補にならず、空差分である。

### Scenario: Preview and apply carry the same title through the previewed plan

Given preview が成功し、coordinator が preview 済み plan を保持している,

When user が confirm して apply される,

Then 適用される plan は preview 対象と同一オブジェクトであるため、確認画面に表示されたフォルダ名と永続化される title は文字列レベルで一致する,

And 適用後の DB 側 exact verification が TITLE を含めて intended との一致を検証する。

## Data and state

- 永続化される新しい種別は存在しない。title は既存 `Favorites.TITLE` 列へ乗る。semantic naming identity (`FolderNaming`) は plan 内の process-local な値であり、DB へは解決済み文字列のみが書かれる。schema / migration / backup-restore への影響なし。
- `FolderNaming` を DB や recovery record へ永続化しない。適用後のフォルダは通常のユーザーフォルダであり、organizer は以後 title を権威的には扱わない (capture された title を保持するだけ)。
- diagnostics への影響なし。生成フォルダ名・title を diagnostics journal / logcat / export へ投影しない (organizer-diagnostics.md の既存契約)。

## Permissions, privacy, and security

新たな permission、network 通信、telemetry は追加しない。生成フォルダ名は taxonomy の汎用カテゴリ名の投影であり、個人情報 (アプリ名・使用状況) を含まない。test fixture は synthetic identity / synthetic category table のみを使用する。

## Accessibility and localization

- 追加する文言はすべて strings (`values/` + `values-ja/`) で管理する。planner・application core に fixed locale 文言を置かない。
- 生成フォルダ名の source 文言は既存 category label resource を再利用し、新しい原文は汎用 fallback の1組のみとする (LQA 対象を最小化)。
- TalkBack / font scaling は §UX expectations のとおり既存構成に従う。確認画面の新規フォルダ行の label 構成変更は既存 UI test パターンで検証する。

## Acceptance criteria

| AC | Acceptance criterion |
|---|---|
| FN-AC-01 | `spec.md` と `plan.md` が、naming の authoritative source (`NewFolder.naming: FolderNaming`)、single-resolution rule (`FolderTitleResolver` + materializer)、preview / writer が plan から読むだけの構造を定義している。 |
| FN-AC-02 | 現行 category grouping で生成される folder が、固定 `Folder` ではなく category semantic に基づくローカライズ済み user-facing 名を `Favorites.TITLE` に持つことを、interface 経由の materializer contract test が検証する。 |
| FN-AC-03 | unknown category は汎用 fallback 文言へ決定的に落ち、resolver の blank 返却は `Invalid` (preview では `MATERIALIZATION_INVALID`) として fail-closed になることを test が検証する。user-facing surface に raw category ID / package / ItemId / ordinal が現れないことを fixture 全体で検証する。 |
| FN-AC-04 | split folder が同一 category で同一 title を持ち、連番を含まないことを planner fixture / materializer test が検証する。fallback category が grouping の対象外である現行規約が test で維持されている。 |
| FN-AC-05 | 既存フォルダの title が保持・移動のみの run で変化しないことを、既存 materializer / application contract test への assertion 追加で検証する。 |
| FN-AC-06 | `NewFolderChange` が `name` を運び、その値が当該 `ApplyAction.Insert` の intended title と文字列一致することを projector contract test が検証する。preview 専用の名前再推論 (category → 名前の写像の重複実装) が存在しないことを code review で確認する。 |
| FN-AC-07 | planner の決定性・metamorphic・idempotence test が `naming` 追加後も無変更で通り、fixture corpus が「`NewFolder.naming` が member の grouping category と一致する」property を検証する。同一入力からの canonical plan が byte-equivalent であることを維持する。 |
| FN-AC-08 | 適用後の persisted folder title が intended plan と一致することを、既存の DB 側 exact verification / protocol test が TITLE 含めて検証する。apply / recovery / reload の既存 invariants test が無変更で通る。 |
| FN-AC-09 | 確認 UI の新規フォルダ行がフォルダ名を含むことを UI test が検証する。行構造・group 構造・件数 truth が不変であることを既存 #195 test の無変更通過で示す。 |
| FN-AC-10 | `FolderNaming` が category 専用の特殊実装に閉じない構造 (closed sealed 階層 + 網羅 `when`) であり、`ValidatedLayoutPlan.newFolders` に同じ型で伝播することを contract shape test が検証する。 |
| FN-AC-11 | 新規 user-facing 原文が汎用 fallback 1組のみであり、values / values-ja が揃っていることを resource review で確認する。 |
| FN-AC-12 | spec 194 / spec 195 の置き換えられる契約行の更新、`CONTEXT.md` 用語追加、`DESIGN.md` の seam 反映が同じ PR で完了する。 |

## Test oracle

| AC | Automated/manual evidence |
|---|---|
| FN-AC-01 | spec / plan review。 |
| FN-AC-02 | `OrganizationPlanMaterializerTest` (拡張): fake resolver で既定 title の検証、`Favorites.TITLE` への書き込みは既存 application adapter / protocol test が意図 state 経由で検証。実機確認は実装完了後の Organizer run (複数 generated folder の名称確認) を PR へ記録。 |
| FN-AC-03 | materializer test: unknown category fixture で fallback 文言、blank resolver で `Invalid`; projector test で `MATERIALIZATION_INVALID`。raw id 露出の不在は planner / projector fixture 全体の assertion。 |
| FN-AC-04 | planner fixture test: capacity 超過 fixture で split 2 フォルダが同一 `naming` / 同一 resolved title。`formFolderGroups` の fallback skip の既存 test 維持。 |
| FN-AC-05 | materializer / application contract test: Preserve / Update 対象の既存フォルダ title 不変 assertion。 |
| FN-AC-06 | `PlanPreviewProjectorTest`: `NewFolderChange.name` と `insert.intended.title` の一致、`Absent` / blank intended title で `Invalid`。 |
| FN-AC-07 | `PlannerGeneratedPropertyTest` + fixture corpus: naming = grouping category property、既存 determinism / idempotence suite 無変更通過。 |
| FN-AC-08 | 既存 application protocol test (`NewFolderCanonicalOrderProtocolTest` 等) の TITLE 含む無変更 / 拡張通過。 |
| FN-AC-09 | 確認 UI の既存 text rendering test (OrganizationPreviewContent 系) の拡張: フォルダ名を含む row 文言。 |
| FN-AC-10 | `ContractShapeTest` (拡張): `FolderNaming` の shape と plan 伝播の検証。 |
| FN-AC-11 | PR diff review (`lawnchair/res/values{,-ja}/strings.xml`)。 |
| FN-AC-12 | PR diff review (spec 194 / 195 / CONTEXT.md / DESIGN.md)。 |

検証 command は building guide の正本に従う (`./gradlew spotlessCheck`、`./gradlew assembleLawnWithQuickstepGithubDebug`、`./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.*'`)。

## Dependencies and sequencing

- #194 (preview seam) は accepted・実装済みであり、本契約はその `NewFolderChange` を拡張する。#194 の境界 (preview 済み plan の同一性、`MATERIALIZATION_INVALID` の fail-closed) を変更しない。
- #182 (strategy Epic) は `needs-spec` であり、本 Issue は strategy catalog / selection UI を実装しない。逆に #182 が strategy 定義を抽出する際、`FolderNaming` / `FolderTitleResolver` の seam を失わないこと (naming seam は `NewFolder` と resolver に局所化済み)。#182 起票時に本 spec への参照を要求する。
- #192 は investigation issue (closed) であり、本 Issue はその「new folder の category / title の扱い」未解決点への focused implementation である。

## Open questions

None。Stage A で決定済み:

1. semantic key と resolved string の二層構造を採用した。planner は semantic の正本、materializer が解決の唯一の点、preview / writer は plan から読むだけ (issue §Required design 1 の「UI、planner、application writer が独立に title を再計算しない」の直接実装)。
2. split folder は同一 title (連番なし)。連番は陳腐化する内部識別子の user-facing 露出であり、Launcher は重複 title を許容する。
3. 解決不能時の fallback は resolver 内蔵の汎用文言 (新 string 1組)。raw ID への fallback は禁止。blank 返却は契約違反として fail-closed。
4. 確認 UI への反映は新規フォルダ行文言への名前追加に限定する (#195 の行構造・件数 truth を維持)。
5. policy bundle / taxonomy version / schema / diagnostics は無変更。naming presentation は bundle semantics ではない。
6. `risk: layout-data` label を付ける (生成 row の TITLE 内容が変わるため)。high-risk independent-evidence gate (CI `final-status` + `docs/assessment/pr-201-*.md`) を満たす。

## Change history

- 2026-09-04: Drafted for Issue #201。source trace (title data flow / preview contract / taxonomy presentation の確認) に基づき作成。production 実装は spec / plan 承認まで停止。

## References

- [Issue #201: Organizer生成フォルダに整理単位を反映した意味のある名前を付与する](https://github.com/nunu1733/NunuLauncher/issues/201)
- [Issue #182: layout strategies Epic](https://github.com/nunu1733/NunuLauncher/issues/182)
- [Issue #194: plan preview seam + PreviewChange projection contract](https://github.com/nunu1733/NunuLauncher/issues/194) / [Spec 194](../194-plan-preview-seam/spec.md)
- [Issue #195: confirmation UI 更新](https://github.com/nunu1733/NunuLauncher/issues/195) / [Spec 195](../195-organizer-confirmation-change-list/spec.md)
- [Issue #192: concrete preview investigation (closed)](https://github.com/nunu1733/NunuLauncher/issues/192)
- [Spec 12: deterministic full layout planner v1](../12-deterministic-full-layout-planner-v1/spec.md)
- [Spec 164: new-folder A7 item order](../164-new-folder-a7-item-order/spec.md)
- [Spec 152: reload model snapshot verification](../152-reload-model-snapshot-verification/spec.md)
- [ADR-0007: authoritative organization policy sources](../../docs/adr/0007-authoritative-organization-policy-sources.md)
- [AGENTS.md: source-of-truth, safety, and quality rules](../../AGENTS.md)
- [CONTEXT.md: organizer domain language](../../CONTEXT.md)
- [DESIGN.md: modules and invariants](../../DESIGN.md)
- [Quality strategy](../../docs/engineering/quality-strategy.md)
- [Building guide](../../docs/engineering/building.md)
