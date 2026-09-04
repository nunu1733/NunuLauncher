---
issue: "#201"
status: accepted
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
2. application 側に `FolderTitleResolver` port を追加し、`OrganizationPlanMaterializer` が planned folder ごとに resolver を正確に1回呼び、その結果を `ApplyAction.Insert` の intended state (`CanonicalItemState.title`) へ書き込むようにする。既存の2つの materialize 呼び出し site (plan preview protocol と confirm 時 materialize) は module が保持する resolver を渡す。production adapter は v1 taxonomy presentation map への total lookup (未知 category は `null`) と新設の fallback 文言で実装し、outer composition (`LawnchairApp`) が `LayoutApplicationModule.production(...)` へ注入する。
3. preview projector の `NewFolderChange` に生成フォルダ名を追加する。値は intended state の title (解決済み文字列) から得て、apply と同一の値を preview が運べるようにする。#195 の確認 UI の新規フォルダ行文言にフォルダ名を反映する (行構造・group 構造は変更しない)。
4. spec 194 の「新規 folder の名前は planner が決めないため、`NewFolderChange` 自身の label は持たない」点と spec 195 の「新規フォルダ行は配置 + メンバー label 一覧で識別する」点を本契約が置き換えることを明記し、両 spec の該当行を同じ PR で更新する。
5. `CONTEXT.md` に生成フォルダ名の用語を追加し、`DESIGN.md` に naming seam の所在を反映する。
6. planner / materializer / production resolver / preview / UI の既存 test surface へ naming 関連の assertion と fixture を追加する (materializer と production resolver の test 責務は分離する)。

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

- `OrganizationPlanMaterializer.materialize` は `titleResolver` を引数に受け、planned folder ごとに `resolve` を**正確に1回**呼び、その結果を `title = OptionalText.Present(resolved)` として `ApplyAction.Insert` の intended state へ設定する (folder を複数回構築しても解決は1回である)。resolver が blank を返すことは port 契約違反であり、`Result.Invalid` として fail-closed に扱う (preview では既存の `MATERIALIZATION_INVALID` 経路へ、confirm 時 materialize では既存の Invalid 経路へ落ちる)。fallback を materializer 側で黙って補完しない。
- resolver の production 実装は organizer UI 層に置き、v1 taxonomy の presentation map への **total lookup** (既存 `forCategory` のような例外 throw ではなく、未知 category は `null` を返す `findForCategory` 系 API を presentation API に追加) と、解決不能時の汎用 fallback 文言 (新 string `organizer_generated_folder_fallback_name`、en "Folder" / ja "フォルダ") で構成する。resolver は `null` を fallback 文言へ決定的に写像し、raw id を返さない。例外 (`IllegalStateException` 等) を捕捉して fallback する構成は採らない。bundle category と presentation map の対応は既存 test が担保する。
- 依存方向は `UI / outer composition → FolderTitleResolver port → application` である。`LayoutApplicationModule` は resolver を constructor で受け、plan preview protocol と `materializeManualFullOrganizationPlan` の両呼び出し site に渡す。production wiring は outer composition (`LawnchairApp`) が production adapter を生成して `LayoutApplicationModule.production(...)` へ注入する形とし、application package が organizer ui package を import する構造は作らない。既定値による暗黙 fallback は設けない (固定名 `Folder` への退行を構造的に不可能にするため)。
- test の責務分離: materializer 側の test は fake resolver による戻り値の intended title への伝播、blank 時の fail-closed、resolve 呼び出し回数 (新規 folder N 個 → 正確に N 回) を検証する。production resolver 側の test は active taxonomy category → localized title、未知 `CategoryId` → generic fallback、raw ID 非露出を en / ja で検証する。
- **Creation-time locale snapshot**: 生成フォルダ title は materialize 時点の process locale で解決され、その時点で凍結される。locale / configuration 変更は必ずしも process death を伴わず、coordinator は materialize 済み plan を保持し続けるため (#194 の preview 済み plan 同一適用)、run の途中 (preview → confirm 間を含む) に locale が変わっても凍結された文字列がそのまま永続化される。再解決・plan 破棄は行わない。適用後のフォルダはユーザーフォルダとして扱われるため、locale 変更後の自動再命名も発生しない。次回 run の materialize がその時点の locale で解決し直す。

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
- v1 の runtime fallback 対象は **未知の `CategoryId`** (presentation map 未収録) である。production resolver は total lookup の `null` を汎用 fallback 文言へ決定的に写像する。raw category ID / package name / `ItemId` / ordinal を user-facing surface へ出してはならない。
- `FolderNaming` は同一 build 内の closed sealed 階層であり、subtype を追加すれば resolver の exhaustive `when` が compile 時に更新を要求する。したがって「未知 subtype が runtime に到達する」経路は存在しない。将来 strategy (#182) が subtype を追加する変更は、その subtype の解決規約 (resolver 実装と、必要なら fallback 文言) を同じ変更内で必ず定義する。
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
- **planned folder への移動先参照も名前で行う** (実装レビュー Major 1 で確定): 新規フォルダへの `MoveChange.destination` が運ぶ `PreviewFolderRef.Planned` は `(ordinal, name: PreviewLabel)` を持ち、UI は ordinal ではなく解決済み名前を表示する。projector は `NewFolderOrdinal → resolved PreviewLabel` を同じ intended title から構築し、join 不成立は `Invalid` (fail-closed) とする。planner ordinal は user-facing surface のどこにも現れず、移動行の文言も `新規フォルダ「ソーシャル」` 形式になる。category からの再解決は行わない。
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

Given materialize 済み `ValidatedLayoutPlan` が N 個の planned folder を含む,

When preview projector が `NewFolderChange` を構築し、apply writer が `Favorites.TITLE` を書き込む,

Then 両者が読む値は同一 plan の同一 intended state の title であり、どちらも `FolderNaming` からの再解決を行わない,

And title を解決する `resolve` 呼び出しは materializer の正確に N 回だけである (folder ごとに1回)。

### Scenario: Locale changes between preview and confirm

Given ja locale で preview が表示され、coordinator が materialize 済み plan (title「通信」) を保持している,

When process を維持したまま locale が en へ変わり、user が confirm する,

Then coordinator は preview 済み plan を再 materialize / 再解決せず、凍結された ja title「通信」がそのまま `Favorites.TITLE` へ適用される,

And 次回 run の materialize はその時点の locale で解決し直す (適用済みフォルダの自動再命名は発生しない)。

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
- actual resource / locale 統合 (en・ja の resource 解決、未知 category の fallback 文言解決) と 200% font scale での表示は organizer instrumentation で検証する (FN-AC-15)。unit test は resolver の total lookup / fallback policy を fake string provider で純粋に検証する。
- TalkBack: フォルダアイコンの既存 content description (`folder_name_format_exact`) が title を読むため、意味のある名前がそのまま読み上げ対象になる。確認画面の新規フォルダ行は既存の row 単位の label 構成を保つ。TalkBack の動作は実機 Organizer run の manual evidence で確認する。
- font scaling: 追加は text row のみであり、200% font scale の instrumentation assertion (FN-AC-15) で名前付き new-folder row が displayed / reachable であることを確認する。

## Acceptance criteria

| AC | Acceptance criterion |
|---|---|
| FN-AC-01 | `spec.md` と `plan.md` が、naming の authoritative source (`NewFolder.naming: FolderNaming`)、single-resolution rule (`FolderTitleResolver` + materializer)、preview / writer が plan から読むだけの構造を定義している。 |
| FN-AC-02 | 現行 category grouping で生成される folder が、固定 `Folder` ではなく category semantic に基づくローカライズ済み user-facing 名を `Favorites.TITLE` に持つことを、interface 経由の materializer contract test が検証する。同一 test で fake resolver の invocation counter により、新規 folder N 個に対する `resolve` 呼び出しが正確に N 回であること (現行 `newFolder()` の二重構築を解消した上での resolve-once 契約) を検証する。 |
| FN-AC-03 | 未知 `CategoryId` は production resolver が total lookup (`null`) 経由で汎用 fallback 文言へ決定的に落ちること (例外捕捉による fallback でないこと) を production resolver の en / ja test が検証する。resolver の blank 返却は materializer が `Invalid` (preview では `MATERIALIZATION_INVALID`) として fail-closed に扱うことを materializer test が検証する。user-facing surface に raw category ID / package / ItemId / ordinal が現れないことを fixture 全体で検証する。 |
| FN-AC-04 | 同一 category の移動可能アプリ群を folder capacity 超過させ、real planner が同 category を複数フォルダへ split した上で、materializer が生成する全 `Insert` の title が完全一致し、ordinal / 接尾辞 (数字) を含まないことを planner → materializer を通す fixture test が直接検証する。fallback category が grouping の対象外である現行規約が test で維持されている。 |
| FN-AC-05 | 既存フォルダの title が保持・移動のみの run で変化しないことを、既存 materializer / application contract test への assertion 追加で検証する。 |
| FN-AC-06 | `NewFolderChange` が `name` を運び、その値が当該 `ApplyAction.Insert` の intended title と文字列一致することを projector contract test が検証する。preview 専用の名前再推論 (category → 名前の写像の重複実装) が存在しないことを code review で確認する。 |
| FN-AC-07 | planner の決定性・metamorphic・idempotence test が `naming` 追加後も無変更で通り、fixture corpus が「`NewFolder.naming` が member の grouping category と一致する」property を検証する。同一入力からの canonical plan が byte-equivalent であることを維持する。 |
| FN-AC-08 | 適用後の persisted folder title が intended plan と一致することを、既存の DB 側 exact verification / protocol test が TITLE 含めて検証する。apply / recovery / reload の既存 invariants test が無変更で通る。 |
| FN-AC-09 | 確認 UI の新規フォルダ行がフォルダ名を含むことを test が検証する。影響を受ける new-folder row test は新しい format に更新し、group 順序 / 行順 / 件数 truth / truncation 等の既存 #195 invariants は維持する。 |
| FN-AC-10 | `FolderNaming` が category 専用の特殊実装に閉じない構造 (closed sealed 階層 + 網羅 `when`) であり、`ValidatedLayoutPlan.newFolders` に同じ型で伝播することを contract shape test が検証する。 |
| FN-AC-11 | 新規 user-facing 原文が汎用 fallback 1組のみであり、values / values-ja が揃っていることを resource review で確認する。 |
| FN-AC-12 | spec 194 / spec 195 の置き換えられる契約行の更新、`CONTEXT.md` 用語追加、`DESIGN.md` の seam 反映が同じ PR で完了する。 |
| FN-AC-13 | 生成フォルダ title が creation-time locale snapshot であること (preview → confirm 間の locale 変化で再 materialize / 再解決されず、凍結文字列が適用される) を、coordinator / materializer test で検証する。 |
| FN-AC-14 | application package が organizer ui package を import しない依存方向 (`UI / outer composition → port → application`) であることを code review と (可能な場合) 静的確認で検証する。production wiring は `LawnchairApp` が resolver を生成して `LayoutApplicationModule.production(...)` へ注入する。 |
| FN-AC-15 | actual Android resources に対する localization / font scaling 検証を organizer instrumentation で担保する: production `GeneratedFolderTitles` が en / ja の actual resource から category title を解決すること、未知 `CategoryId` が generic fallback 文言へ解決すること、および 200% font scale の concrete preview test で**名前付き new-folder row が displayed / reachable** であること。representative fixture は可能なら ja で比較的長い category label を使用する。 |

## Test oracle

| AC | Automated/manual evidence |
|---|---|
| FN-AC-01 | spec / plan review。 |
| FN-AC-02 | `OrganizationPlanMaterializerTest` (拡張): fake resolver (invocation counter 付き) で既定 title の伝播と N folder → N 呼び出しを検証。`Favorites.TITLE` への書き込みは既存 application adapter / protocol test が意図 state 経由で検証。実機確認は実装完了後の Organizer run (複数 generated folder の名称確認) を PR へ記録。 |
| FN-AC-03 | production resolver の **unit test** は fake string provider 等で total lookup / fallback policy を純粋に検証する (既知 category → 解決済み title、未知 `CategoryId` → `null` → generic fallback、例外捕捉なし、raw ID 非露出)。materializer test: blank resolver で `Invalid`; projector test で `MATERIALIZATION_INVALID`。raw id 露出の不在は planner / projector fixture 全体の assertion。**actual resource / locale 統合は FN-AC-15 の instrumentation で担保する**。 |
| FN-AC-04 | planner fixture test: capacity 超過 fixture で split 2 フォルダが同一 `naming` / 同一 resolved title。`formFolderGroups` の fallback skip の既存 test 維持。 |
| FN-AC-05 | materializer / application contract test: Preserve / Update 対象の既存フォルダ title 不変 assertion。 |
| FN-AC-06 | `PlanPreviewProjectorTest`: `NewFolderChange.name` と `insert.intended.title` の一致、`Absent` / blank intended title で `Invalid`。 |
| FN-AC-07 | `PlannerGeneratedPropertyTest` + fixture corpus: naming = grouping category property、既存 determinism / idempotence suite 無変更通過。 |
| FN-AC-08 | 既存 application protocol test (`NewFolderCanonicalOrderProtocolTest` 等) の TITLE 含む無変更 / 拡張通過。 |
| FN-AC-09 | 影響を受ける new-folder row test (`OrganizationPreviewContent` 系 unit test、`ManualOrganizationPreferencesInstrumentationTest` の該当行) を新 format へ更新し、group / order / count / truncation の既存 #195 invariants を更新後も維持することを確認。 |
| FN-AC-10 | `ContractShapeTest` (拡張): `FolderNaming` の shape と plan 伝播の検証。 |
| FN-AC-11 | PR diff review (`lawnchair/res/values{,-ja}/strings.xml`)。 |
| FN-AC-12 | PR diff review (spec 194 / 195 / CONTEXT.md / DESIGN.md)。 |
| FN-AC-13 | `ManualOrganizationRunTest` / materializer test: preview 保持中の locale 変化を模擬し、`materialize` が再実行されず resolve 呼び出しが増えないこと (同一 plan インスタンス適用 + counter) を検証。 |
| FN-AC-14 | PR diff review (import 方向) + `LawnchairApp` wiring の review。 |
| FN-AC-15 | organizer instrumentation (`ManualOrganizationPreferencesInstrumentationTest` 拡張): locale-aware `Context` を **production `GeneratedFolderTitles.resolver(Context)` 経路自体**に通し、actual resource での ja / en 解決と未知 category → fallback 文言を検証する。さらに 200% font scale (`Density(1f, fontScale = 2f)`) の concrete preview で名前付き new-folder row の displayed / reachable を assert。representative fixture に ja の長め category label を使用。**正式 evidence は既存 `organizer-instrumentation-issue52-tests` CI job の成功** (同 class への method 追加で自動収録)。local には class filter 付き targeted 実行を使用する。 |

検証 command は building guide の正本に従う (`./gradlew spotlessCheck`、`./gradlew assembleLawnWithQuickstepGithubDebug`、`./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.*'`)。

## Dependencies and sequencing

- #194 (preview seam) は accepted・実装済みであり、本契約はその `NewFolderChange` を拡張する。#194 の境界 (preview 済み plan の同一性、`MATERIALIZATION_INVALID` の fail-closed) を変更しない。
- #182 (strategy Epic) は `needs-spec` であり、本 Issue は strategy catalog / selection UI を実装しない。逆に #182 が strategy 定義を抽出する際、`FolderNaming` / `FolderTitleResolver` の seam を失わないこと (naming seam は `NewFolder` と resolver に局所化済み)。#182 起票時に本 spec への参照を要求する。
- #192 は investigation issue (closed) であり、本 Issue はその「new folder の category / title の扱い」未解決点への focused implementation である。

## Open questions

None。Stage A で決定済み:

1. semantic key と resolved string の二層構造を採用した。planner は semantic の正本、materializer が解決の唯一の点、preview / writer は plan から読むだけ (issue §Required design 1 の「UI、planner、application writer が独立に title を再計算しない」の直接実装)。
2. split folder は同一 title (連番なし)。連番は陳腐化する内部識別子の user-facing 露出であり、Launcher は重複 title を許容する。
3. 解決不能時の fallback は resolver 内蔵の汎用文言 (新 string 1組) への写像とし、presentation API には total lookup (`findForCategory` 系、未知 category は `null`) を追加する。例外捕捉による fallback、raw ID への fallback は禁止。blank 返却は契約違反として fail-closed。
4. 確認 UI への反映は新規フォルダ行文言への名前追加に限定する (#195 の行構造・件数 truth を維持)。
5. policy bundle / taxonomy version / schema / diagnostics は無変更。naming presentation は bundle semantics ではない。
6. `risk: layout-data` label を付ける (生成 row の TITLE 内容が変わるため)。high-risk independent-evidence gate (CI `final-status` + `docs/assessment/pr-201-*.md`) を満たす。
7. locale は **creation-time snapshot** 契約とする: title は materialize 時 locale で凍結され、run 中の locale 変化では再解決せずそのまま適用する (レビュー Blocking 2)。invalidation (locale 変化時の preview plan 破棄) は採用しない — #194 の preview 済み plan 同一適用の構造を変えないため。
8. production resolver の wiring は outer composition (`LawnchairApp`) 注入とし、application → ui の import を作らない (レビュー Major 3)。

## Change history

- 2026-09-04: Drafted for Issue #201。source trace (title data flow / preview contract / taxonomy presentation の確認) に基づき作成。production 実装は spec / plan 承認まで停止。
- 2026-09-04: Review revision (owner review @ `d9cce13fe6`): unknown category fallback を presentation API への total lookup 追加 (`null` → fallback、例外捕捉なし) へ修正し、materializer test と production resolver test の責務を分離 (Blocking 1)。locale を creation-time snapshot 契約として明示し、process death 前提の不正確な記述を削除、scenario を追加 (Blocking 2)。production resolver の wiring を outer composition (`LawnchairApp`) 注入へ変更し依存方向を修正、FN-AC-14 を新設 (Major 3)。materializer の二重構築 (`newFolder()` が `placeholderFolder` を2回呼ぶ) を解消する resolve-once 実装形状と invocation counter test を明記、FN-AC-02 を拡張 (Major 4)。runtime fallback 対象を未知 `CategoryId` に整理し、sealed subtype 追加時は解決規約を同一変更で定義することを明記 (Minor 5)。
- 2026-09-04: Re-review revision (owner re-review @ `c03896c927`): actual Android resources を使う en / ja 検証と未知 category の fallback 文言解決を organizer instrumentation へ移動し、unit test は fake string provider による resolver の純粋検証に限定 (Major)。200% font scale の concrete preview test を名前付き new-folder row の displayed / reachable assertion へ拡張し、representative fixture へ ja の長め category label を要求する FN-AC-15 を新設。FN-AC-09 の「既存 #195 test 無変更通過」を「新 format への更新 + 既存 #195 invariants (group / order / count / truncation) の維持」へ修正。TalkBack の実機 manual evidence は現行どおり。
- 2026-09-04: Spec approved / plan revision (owner re-review @ `8be8435c7e`): instrumentation の正式 evidence を既存 `organizer-instrumentation-issue52-tests` CI job の成功へ固定し、local は class filter 付き targeted 実行へ変更 (新規 CI lane は設けない)。FN-AC-15 oracle 行の evidence 所在を計画側と揃えた整合修正。product contract の変更なし。
- 2026-09-04: Accepted by the Issue #201 owner at head `8be8435c7e` (spec approved)。plan は既存 `organizer-instrumentation-issue52-tests` lane への整合を条件に実装開始可能とされ、本 revision でその条件を満たした。Implementation may begin within this specification and plan.
- 2026-09-04: Implementation review revision (PR #202 review): planned folder への移動先参照 (`MoveChange.destination` の `PreviewFolderRef.Planned`) も resolved name を運び ordinal を user-facing に露出しない契約を §Preview integration へ明記 (Major 1)。FN-AC-04 の split 同一 title 検証を real planner → materializer の capacity 分割 fixture で直接 assert するよう test oracle を強化 (Major 2)。FN-AC-15 の production `resolver(Context)` 経路を instrumentation で通すよう追記 (Minor)。

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
