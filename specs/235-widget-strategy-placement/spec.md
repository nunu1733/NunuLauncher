---
issue: "#235"
status: accepted
requirements:
  - FR-003
  - FR-016
risk:
  - layout-data
updated: 2026-09-12
---

# Strategy-aware fixed-span widget placement in Organizer

> Status: accepted — 2026-09-12。phase-1 review (2 round、下記change history) を経て所有者により承認された。実装は本specとplan.mdに従い、`STABLE_PAGE_TIDY_V2` → `BOTTOM_FIRST_V2` の縦切りで行う。`GLOBAL_COMPACT_V3` / `CATEGORY_CONTIGUOUS_V2` は本specのnormative rulesを実装する後続child issueとする。

## Problem

Organizerのlayout strategy catalog (spec 182 / ADR-0012) は、現状すべてのstrategyで captured widgetを固定占有率制約 (fixed occupancy constraint) として扱う。観測可能な現行挙動 (`origin/main` @ `3e113302b9`):

- `determinePreservation` (`lawnchair/src/app/lawnchair/organizer/planning/PlanningPlacement.kt:373`) は `item.kind == ItemKind.APPWIDGET || item.kind == ItemKind.CUSTOM_APPWIDGET` を `PreserveReason.WIDGET` により保持する (`PlanningPlacement.kt:392`)。この判定は `RESERVED_REGION`/`LOCKED`/`UNAVAILABLE_TARGET`/`DOCK` より低く、`APP_PAIR`/`LEGACY_SHORTCUT`/`NON_TARGET`/`STRUCTURAL` より高いprecedenceである。widgetは `movableItems` に入らず、strategy実行前にallocator上で占有済みセルとして印付けられる (`PlanningPlacement.kt:42-50`)。
- したがって6つのruntime-supported strategy (`CANONICAL_PAGE_COMPACT_V1`, `STABLE_PAGE_TIDY_V1`, `BOTTOM_FIRST_V1`, `GLOBAL_COMPACT_V1`, `GLOBAL_COMPACT_V2`, `CATEGORY_CONTIGUOUS_V1`) はいずれも、widgetの周囲のapp/folderのみを再構成する。strategy間で変化するのは「widgetをどこに残すか」ではなく「残されたwidgetの周りをどう詰めるか」だけである。

これは安全だが、strategyの意図と結果が食い違う。upper-left / bottom-first系では大きなwidgetが主要icon領域を占めたまま残るか、appと同一の走査順で高価値行を取り合い、global compactではwidgetが原因の歯抜けpageが解放されない。widgetは意味的に異なるHome要素であり (resizeはUI/機能を変え得るため不変、位置変更は許容、`4×2` rectangleは`1×1` appと同一のfirst-fit列に入れるべきでない)、単なる「大きいmovable item」として扱うべきではない。

## Outcome

ユーザーがwidget移動に対応した後継strategyを選ぶと、plannerはwidgetを「span不変の固定的矩形 (fixed-span rectangle)」として、app/folderとは別個のrole固有配置意味論 (placement semantics) に従って再配置する。widgetのサイズ・provider identity・`appWidgetId`・構成・bindingは一切変化せず、共有のoccupancy/bounds/検証契約が引き続き権威であり、previewはwidget再配置をapp/folder移動とは区別して可視化する。既存の全strategy ID (`CANONICAL_PAGE_COMPACT_V1` を含む) のobservable behaviorは一切変更しない。

## Resolved decisions (旧Open questions → D-1〜D-6)

初版draftが所有者判断に保留した6問を、Issue本文の要件・受入済み正本の制約・実装可能性から次のとおり決定する。各判断は本spec受入の一部としてreview対象である。

### D-1: 後継strategy IDの命名と packaging

- 導入する後継ID: `STABLE_PAGE_TIDY_V2`、`BOTTOM_FIRST_V2`、`GLOBAL_COMPACT_V3`、`CATEGORY_CONTIGUOUS_V2`。`GLOBAL_COMPACT` 系は `_V2` が存在するため次版は `_V3` とする (ADR-0012: IDはimmutable semantic identity、版はsuffix)。
- 初期縦切り (本issueの実装child): **`STABLE_PAGE_TIDY_V2` を最初、`BOTTOM_FIRST_V2` を2番目**。Issue受入条件「stable/tidy系とbottom/upper compact系の少なくとも2 family」をこの2本で満たす (`BOTTOM_FIRST_V2` がissueの「bottom-firstではapp下部・widget上部の相補配置」を直接実装する)。`GLOBAL_COMPACT_V3` と `CATEGORY_CONTIGUOUS_V2` は本specでnormative rulesを確定するが、実装は後続child issueとする (spec 182が `GLOBAL_COMPACT_V1`/`CATEGORY_CONTIGUOUS_V1` を定義のみ先行受理したのと同じ分割)。
- **canonical (upper-left) 系の後継は本specでは定義しない**: `CANONICAL_PAGE_COMPACT_V1` はcompatibility oracle/rollback baselineであり、issue本文はcanonical系のwidget移動を「望むならversioned successorを」と任意としている。現行catalogには純粋なupper-left compact strategyも存在しない (canonical系はfolder形成込みのcomposite intentである)。将来必要になった場合は新ID + 本specと同じ様式の証明を要件とする。
- 既存IDのdeprecate・置換・選択migrationは**行わない**。旧strategyはcatalogに残り、既存selectionはfail-closedにならない (spec 237と同一の共存機構)。

### D-2: Global compact系でのwidget cross-page移動 — 完全証明付きで許容

ADR-0012 Decision 4 のheterogeneous-span反例は「**captured visual (位置) 順**でorderedされたcross-page mover」に対する反例である。位置順は移動後の再捕獲で順序が入れ替わり得るため、同span (`1×1`) 制限が必要だった。本specは `GLOBAL_COMPACT_V3` のwidget streamを**不変key順 (identity順: span降順 → target key → ItemId、いずれも移動で変化しない)**で消費することにより、この反例が適用できない構成をとる。配置が不変dataのみの純関数になるため、spec 237様式の完全な冪等性証明を構成できる (§GLOBAL_COMPACT_V3の証明参照)。したがって:

- `GLOBAL_COMPACT_V3` ではwidgetの**cross-page移動を許容する** (ADR-0012 Decision 4の要求する「独自の完全証明を伴う新strategy ID」として)。
- `STABLE_PAGE_TIDY_V2` / `BOTTOM_FIRST_V2` はpage-local (captured page固定) とする。stable/tidyは「captured近傍に留める」意図そのものであり、bottom-firstはpage内の相補領域で完結する。

### D-3: Stable/tidy系のmovement costの形式化 — 探索なしの構成的表現

「icon移動だけでは達成できない場合のみwidgetを動かす」「不要なwidget間入れ替えを避ける」を、cost関数や探索なしに次の構成要素で表現する。

1. **Page affinity**: eligible widgetはcaptured pageから出ない。
2. **Widget band制限**: 各pageのwidget再配置領域を、**そのpageのcaptured widget群が占有する行範囲 [minRow, maxRow] (widget band)**に限定する。bandは「ユーザーが既に作ったwidget領域」であり、widgetはband外 (icon専用行) へ出ないし、band内の隙間 (widgetとwidgetの間のicon行) を埋める方向にのみ動く。単独widgetのbandは自身の行であるため、大きく動くことがない。
3. **不変key順**: widget stream内の処理順を `(span.height, span.width) 降順 → target key → ItemId` に固定する。この順は移動後も不変のため、(a) replanでwidget間の処理順が入れ替わらない (不要なwidget-to-widget再順序化の排除)、(b) heterogeneous-spanでも順序が位置に依存せず冪等性証明が構成できる。
4. **Degrade規則**: strategyの規則でwidgetが配置不能なpageでは、そのpageのwidget再配置全体を無効化し全widgetを元位置固定 (`STRATEGY_PRESERVED`) とする (§共通規則)。

「高movement cost」の意味は「領域制限 + 不変順 + page固定」で表現され、cost値の比較や最適化は導入しない (現行plannerにsearchはなく、導入は別decision)。

### D-4: Preview / 結果reportingのpublic形状変更

- `PlacementCode` に `WIDGET_UNIT` を追加する (spec 10 delta。閉じたenumへの値追加)。widget移動は `Moved{WIDGET_UNIT}` として報告され、`SINGLE_PLACEMENT` へ偽装しない。
- `PreviewCounts` に `widgetMovedCount: Int = 0` を追加する (**意図的なshape拡張**。spec 228が `addedCount` を追加したのと同じ形式: 既存countの意味は変更しない、default引数で後方互換)。値は `rationale == WIDGET_UNIT` の `MoveChange` 行数。初期2 strategyはwidget移動がpage-localであるためcross-page widget移動は発生せず、既存 `crossPageMovedCount` にwidget分は含まれない。`GLOBAL_COMPACT_V3` (cross-page widget) は後続child有効化時に `widgetMovedCount` と page ordinal表示 (spec 194正規化) で可視化する。
- **Delta正本化regime (単一規則)**: 両deltaとも本spec本文を受入時点での正本とし、**受入PRではspec 10/194のfile本文を編集しない** (spec 228の `addedCount` と同じ受入PR scope)。code変更を land させる最初の実装PRが、対応する正本fileへ反映する: spec 10は閉じたgrammar (PlacementCode enum) を自らnormativeに所有するため、`WIDGET_UNIT` 追加を同PRのspec 10 delta (PlacementCode定義行 + change history行) として適用する (PlacementCodeへの事後追加は本regimeが初回であり、既存の3値 `SINGLE_PLACEMENT`/`FOLDER_MEMBER`/`FOLDER_UNIT` はspec 10の初期grammarに由来する。前例としては、spec 182は受入時 (acceptance commit) にspec 10 delta (`organizationStrategy`/`STRATEGY_PRESERVED`/V-20) を適用しており、本specは受入scopeをspec 228方式へ寄せた上でfile反映を実装PRへ遅らせる意図的な選択を取る)。spec 194は本文で「将来の拡張の乖離は拡張側specが決定する」と委任しているためfile本文を更新せず、`widgetMovedCount` の意味は本specと実装code (KDoc) が正本とする (spec 228が `addedCount` で取った扱いと同一)。

### D-5: Run mode毎の適用範囲

- strategyは `FullOrganization` と `ScopeComposedOrganization` (spec 228) に適用される。scope-composed runは選択strategyのfull-run executorを先に実行するため、widget対応strategy選択時は**widget再配置もscope-composed runで適用される** (その後、同一strategyのpage/folder scope下で候補が追加される。候補はappでありwidgetではない)。
- `IncrementalPlacement` は現行どおりpre-182のcanonical candidate tailのまま (strategy不適用) であり、widgetは固定されたままである。これを変更する場合は別decisionとする。

### D-6: 既存strategy選択ユーザーへの案内

能動的な通知・選択migration・非推奨化は**行わない** (ADR-0012のfail-closed/no-substitution規律: ユーザーの選択を勝手に変えない)。発見経路はstrategy pickerのみとし、次のcopyで対応する:

- 新strategyのname/descriptionはapp領域とwidget領域の結果の関係を説明する (issueの例示文言参照)。
- `STABLE_PAGE_TIDY_V1` / `BOTTOM_FIRST_V1` のdescriptionは「ウィジェットは動かしません」を挿入して真実化する (spec 237が `GLOBAL_COMPACT_V1` copyに対して行ったのと同じ)。

onboarding nudge等の追加UXが必要になった場合は別issueとする。

## Scope

- 計画modelへのsemantic placement role分類の導入: 計算上 `APP_OR_SHORTCUT` / `FOLDER` / `WIDGET` を区別し、strategyがrole別の配置意思 (preferred region / traversal / movement intent) を宣言できるようにする。
- 後継strategy 4 ID (`STABLE_PAGE_TIDY_V2`, `BOTTOM_FIRST_V2`, `GLOBAL_COMPACT_V3`, `CATEGORY_CONTIGUOUS_V2`) のnormative rulesと、うち2本 (`STABLE_PAGE_TIDY_V2`, `BOTTOM_FIRST_V2`) の実装・有効化。
- `StrategyDefinition` への `WidgetPlacementPolicy` (内部strategy metadata) 追加と、widget streamをapp/folder streamより先に消費するexecutor拡張。
- 固定span矩形配置のための共有allocator拡張 (既存の単一occupancy/bounds実装を保つ)。
- `PlacementCode.WIDGET_UNIT` 追加、`PreviewCounts.widgetMovedCount` 追加、strategy説明copy (ja/en)、V1系copyの真実化。
- 新規strategy有効化に伴うbundle semantic version publish (ADR-0007 §8 / ADR-0012)。
- `CONTEXT.md` へのdomain language追記 (承認時)。

## Non-goals

- Widgetのresize、`spanX`/`spanY`変更、異なるsize/layout modeの選択。
- Widgetの再作成・rebind・再構成・`appWidgetId`/provider identity/構成の変更、widgetの自動置換。
- 既存6 strategy IDのobservable behavior変更、golden corpus・既存test主張の変更。`CANONICAL_PAGE_COMPACT_V1` は #182 のregression/rollback oracleとして不変。
- Widget内容の意味解釈 (provider種別による位置決定)。
- Locked / reserved-overlapping (`RESERVED_REGION`) / unavailable / Dock / app-pair / legacy-shortcut 要素の移動。これらは引き続き全strategyで固定 (widgetに対しても、より高いprecedenceのnatural preservationが先に効く)。
- 適用・復旧・transaction・recovery point契約の変更 (spec 13無変更)。
- 組み合わせ式public toggle (role × region × cost)。catalogはcuratedのまま (ADR-0012 Decision 1)。
- `IncrementalPlacement` でのwidget移動 (D-5)。
- `GLOBAL_COMPACT_V3` / `CATEGORY_CONTIGUOUS_V2` の実装・有効化 (本issueでは定義のみ。実装は後続child issue)。

## Domain language

`CONTEXT.md` への追記 (承認時)。

**semantic placement role (セマンティック配置role)**:
計画対象のtop-level unitを、配置意味論上の種別 (app/shortcut、folder、widget) として分類したもの。strategyはroleごとにpreferred regionとmovement intentを別個に宣言する。
_Avoid_: movable flag (widgetをgeneric movable unitへ退化させる)、item kind (capture側の型名との混同)

**widget placement policy (ウィジェット配置ポリシー)**:
あるstrategyがwidget roleに対して宣言する、span不変の再配置意思。preferred region (widget band / page全域 / cross-page)、cell走査、widget streamの処理順 (不変key順)、page affinity、fallback (degrade) からなる純data。
_Avoid_: widget settings (user設定との混同)、movement cost (数値costや探索を想起させる)

**widget band (ウィジェット帯)**:
1つのcaptured page上で、そのpageのeligible widget群がcapture時点で占有する行の閉区間 [minRow, maxRow]。stable/tidy系のwidget再配置領域として使う。
_Avoid_: widget area (領域サイズが固定であるような誤解)、widget zone

## Constraints from accepted specs/ADRs (non-negotiable)

本specは以下を受入済み正本の制約として従う。

1. **ADR-0012 Decision 2 / spec 182**: strategy IDはimmutable semantic identity。挙動変更は新IDで表現し、出荷済みIDのrename・無言再解釈は禁止。
2. **ADR-0012 Decision 4 (idempotence制約)**: heterogeneous spanのunitを**位置順**でcross-page移動するとfragmented fixed obstacle下で再計画時にvisual orderが入れ変わりINV-8を破る。cross-page moverを`1×1`に制限したのはこのため。heterogeneous-span variantは**完全な証明を伴う新strategy IDとしてのみ**許容される (D-2の構成と証明がこれに応答する)。
3. **spec 237のversioning前例**: 後継strategyの導入機構 — `LayoutStrategyRegistry` への新ID登録、`runtimeSupported` 追加、bundle semantic version増分publish、`rule-v2`/selection store schema不変、V1はcatalogに残りfail-closedしない。
4. **spec 182 / 237の保全理由の truthful さ**: strategyが意図的に固定する本来movableな要素は `STRATEGY_PRESERVED` であり、`ALREADY_CANONICAL` や`NON_TARGET`を偽って使わない。
5. **spec 10/12の適用不可能意味論**: V-21/V-22のみがcandidateをimpossibleにでき、silent dropは禁止。full-run plannerのadmitted unit配置失敗はloud failureである。widgetのdegrade保留 (§共通規則) は「strategy定義に含まれる明示的fallback」であってadmitted unitの配置失敗ではなく、loud failure契約と矛盾しない。
6. **spec 228のscope-composed契約**: `ScopeComposedOrganization` は選択strategyのfull-run executorを先に実行し、候補は同一strategyの `createsFolders`/`pageScope` 下で追加される。widget policyもこの構造に乗る (full-run相位でwidget再配置が走る)。
7. **spec 194のpreview拡張契約**: preview projection拡張は拡張側specが意味と表示方針を決定する。`PreviewCounts` の既存count意味は不変 (D-4)。

## Observable behavior (normative semantics)

### 共通のwidget role規則 (全widget対応strategy)

- **Eligible widget**: captured page上のtop-level workspace配置 (`CapturedPlacement.Workspace`) かつ unlocked・`AVAILABLE`・`RESERVED_REGION`重複なしの `APPWIDGET`/`CUSTOM_APPWIDGET`。widget分岐はprecedence chainにおいて **widget kindに対してterminal** である: `RESERVED_REGION` > `LOCKED` > `UNAVAILABLE_TARGET` > `DOCK` の各検査はwidget分岐より前にあり、これらに該当するwidgetは新strategyでも高いprecedenceの保持理由で固定される。widget分岐より後の `NON_TARGET` (role == `Preserved`) 検査はwidget kindには到達しない — production composer (`FullTargetSetMaterializer`) は全widgetをkind基準で `ExistingRole.Preserved` に付与する (widgetはユーザーが整理対象に選べる要素ではない、#235以前からの規則)。widgetのmovabilityをroleに紐付けるとproductionでwidget移動が到達不能になるため (AC-10実機評価で発見)、widget対応strategy下ではrole検査はwidget kindに適用されない。widgetのユーザー向けscopingはstrategy選択 (widgetPolicyの有無) そのものである。非widget対応strategyでは従来どおり `PreserveReason.WIDGET` で固定される。
- **Movable化の宣言性**: widgetが `movableItems` に入るのは、選択strategyが `widgetPolicy` を宣言している場合のみである。宣言なきstrategy (既存6 ID + incremental candidate tail) では `PreserveReason.WIDGET` のまま固定され、出力は現行とbyte同一である。
- **Span不変**: 移動時もwidgetのtarget spanはcaptured spanと厳密に等しい (`PlacementTarget.WorkspaceTarget` のspan)。resizeは一切発生しない。
- **Role分離**: widgetはfolder形成候補にならず、category orderingに参加せず、`1×1` unit streamに混入しない。widgetは専用の**widget stream**として、app/folder streamより**先に**消費される (role間配置順の明示的定義。偶発的なiteration順に委ねない)。
- **Widget streamの障害物集合**: widget配置時点のoccupancyは、(1) natural preservation itemのcaptured占有 (`PlanningPlacement.place` が既に印付け)、(2) **strategy-fixed movable item** (そのstrategyのapp-stream規則が固定するmovable item — 例: `STABLE_PAGE_TIDY_V2` では既存folderとnon-`1×1` app、`GLOBAL_COMPACT_V3` ではnon-`1×1` top-level unit。`BOTTOM_FIRST_V2` のcanonical flowでは該当なし)、(3) 先に配置したwidget、である。strategy-fixed movable itemは毎run同じcaptured位置に固定されるため (`STRATEGY_PRESERVED`)、この障害物集合はrun間で不変であり、冪等性証明の固定集合に含まれる。widgetがstrategy-fixed itemの占有と重ならないことは共有allocatorのoccupancy検証が保証する。
- **Widget streamの処理順 (不変key順)**: `(span.height desc, span.width desc, target key のcanonical順, ItemId)`。高い矩形ほど候補行が少ないため先に配置する (multi-cell packing orderの決定的回答)。target keyのcanonical順はspec 10のcanonical orderingに従う**型どおりの比較**であり、`(provider の UTF-8 byte order (ComponentKey), appWidgetId の numeric order (AppWidgetId), profile の UTF-8 byte order (ProfileId))` とする。連結文字列 (例: `"3:provider:appWidgetId"`) の比較は用いない — 文字列化した `appWidgetId` は辞書順になり (`"10" < "2"`)、profile segmentが欠落するため、spec 10 の integer-code numeric order・UTF-8 byte order 規律に反する。全segmentは移動によって変化しない。
- **報告**: 移動したwidgetは `Moved{WIDGET_UNIT}`、配置先がcaptured位置と同一のwidgetは `Preserved{ALREADY_CANONICAL}`、degradeで固定したwidgetは `Preserved{STRATEGY_PRESERVED}`。
- **Degrade規則 (page-local系)**: strategyのregion規則で当該pageのeligible widgetの1つでも配置不能になった場合、そのpageのwidget再配置全体を無効化する (当該pageの全eligible widgetが元位置で `STRATEGY_PRESERVED`、occupancyとして印付け、app streamは V1/V2 family規則どおり)。**Degrade規則 (cross-page系)**: `GLOBAL_COMPACT_V3` ではwidget stream全体 (全page) を無効化する。いずれもresize・黙示dropはしない。app streamはdegradeしたwidgetをfixed occupancyとして通常どおり実行される (plan全体は失敗しない)。
- **Idempotence**: 各strategyはwidget移動を含む再計画がempty diffになることの構成的argumentを下記のとおり持ち、replan property testで検証する。

### STABLE_PAGE_TIDY_V2 — normative rules (実装child 1)

`STABLE_PAGE_TIDY_V1` の規則 (eligible `1×1` app/deep shortcut、page-local lift-then-place、captured visual order `(cell.y, cell.x, ItemId)`、top-left row-major、folder不作成、page不作成) をapp streamについて無変更で継承し、次のwidget policyを追加する。

- **Page affinity**: eligible widgetはcaptured pageに留まる。pageの新規作成・crossはしない。
- **Widget band**: 各captured pageについて、そのpageのeligible widget群のcaptured extentsから行閉区間 `[minRow, maxRow]` を計算する。eligible widgetがいないpageにbandはない (何も起きない)。
- **Widget配置**: 当該pageのeligible widgetを不変key順に、band内 (候補top-left `y` は `minRow ≤ y` かつ `y + span.height - 1 ≤ maxRow`) でtop-left row-major first-fit (共通規則の障害物集合 = natural preservation占有 + strategy-fixed movable item (既存folder・non-`1×1` app等) + 先に置いたwidget) で配置する。
- **Degrade**: band内で1つでもwidgetが配置不能なら、そのpageのwidget再配置を全体無効化 (共通規則)。
- **App stream**: V1どおり、widgetのtargetをoccupancyとして `1×1` eligible unitのpage-local lift-then-place。
- **Idempotence (構成)**: widget配置は「pageの固定障害物 (natural preservation + strategy-fixed movable item。後者は毎run同じcaptured位置に固定されるため不変) + eligible widgetの不変key順multiset (span付き)」の純関数である。run 2のbandはrun 1の配置結果の行範囲 `B' ⊆ B` になるが、run 1のfirst-fit選択はすべて `B'` 内にあり、走査時点のoccupancyが同一 (帰納法) であるため、`B'` 内のより早い候補はrun 1でも利用可能だったはずであり矛盾する。ゆえに各widgetは同じcellを再選択し、degrade判定も再現され、widgetのdiffは空になる。app streamはV1のplaceability argument (eligible `1×1` count ≤ lifting後の空きcell数) をwidget occupancy込みで満たし、replanでempty diffになる。
- **代表fixture**:
  - (a) 同一pageのwidget間のicon行が埋まる: `4×2` widgetが行1〜2、`2×2` widgetが行4にあるpage → band = 行1〜4、大きい方が先にband先頭へ、`2×2` がその直後へ詰め、間のiconはband外へlift-then-placeされる。
  - (b) 単独widget: band = 自身の行。行内で左寄せになる以外動かない (captured位置が既にband先頭なら `ALREADY_CANONICAL`)。
  - (c) band内にlocked cellがあり `4×2` が入らない → 当該pageはdegrade、widgetは全て `STRATEGY_PRESERVED`、iconはV1どおり。
  - (d) band内に既存folder / non-`1×1` app (strategy-fixed movable item) がある → それは障害物として避けて配置され、重ならない。障害物の分断でwidgetが置けない場合は (c) と同じdegrade。

### BOTTOM_FIRST_V2 — normative rules (実装child 2)

`BOTTOM_FIRST_V1` の規則 (canonical folder/unit/page policy、`PREFERRED_THEN_NEW`、bottom-up row-major走査) をapp streamについて無変更で継承し、次のwidget policyを追加する。

- **Page affinity**: eligible widgetはcaptured pageに留まる。
- **Widget配置**: 各captured pageのeligible widgetを不変key順に、**page全域** (固定occupantのみ除く) をtop-left row-major first-fitで配置する。app streamがbottom-upで下から詰めるのに対し、widget streamが先に上側を占有することで相補領域 (widget上部 / app下部) が決定的に形成される。regionは入力のみ (固定occupant) から決まり、app配置結果に依存しない。
- **Degrade**: page内で1つでもwidgetが配置不能なら、そのpageのwidget再配置を全体無効化 (共通規則)。
- **App stream**: V1のcanonical flow。widgetのtargetがoccupancyとなるため、widget分の面積だけappはpageから溢れ、`PREFERRED_THEN_NEW` により新規pageへoverflowし得る (V1がwidget固定だったときと同じoverflow意味論)。
- **Idempotence (構成)**: widget配置は「fixed occupancy + 不変key順widget multiset」の純関数 (regionがpage全域のためbandのような入力依存もない) であり、replanで同一targetを再現する。app streamはV1の既存argument (identity基準のunit order + 決定的bottom-up候補順) を、replanで同一に再現されるwidget occupancy込みで適用し、empty diffになる。
- **Device profiles**: portrait / landscape / tablet / two-panel両方向で決定的 (region・走査がgrid寸法の関数であり、phone専用の行仮定を置かない)。各profileのfixture検証を必須とする。
- **代表fixture**: 4×5 pageに `4×2` widget (行2中央) とmovable app 8個 (全app異なるcategory — folder形成なし) → widgetが `(0,0)` へ、appが行4→行3へbottom-upで埋まる。行2は空く (V1ではwidgetが行2固定のまま周囲を埋めたのと対照)。

### GLOBAL_COMPACT_V3 — normative rules (定義確定、実装は後続child)

`GLOBAL_COMPACT_V2` の規則 (movable `1×1` singleton + 既存`1×1` folderのglobal captured visual order stream、`CAPTURED_THEN_NEW`、folder形成は`1×1` singleton候補のみ) をapp streamについて無変更で継承し、次のwidget policyを追加する。

- **Cross-page widget compaction**: 全captured pageのeligible widgetを、不変key順に (app streamより先に) `CAPTURED_THEN_NEW` 相当の走査 (captured pageをPageOrder順、次にwidget stream自身が必要なら作る新規page) で配置する。widgetはcaptured spanのまま前方pageの空きへ詰められ、後方pageに置かれたままのwidgetが原因で解放されないpageが残る結果を防ぐ。
- **Degrade**: 1つでもwidgetが配置不能なら、widget stream全体を無効化 (全widgetが元位置で `STRATEGY_PRESERVED`、app streamはV2どおり)。
- **Idempotence (完全証明、spec 237の4段構成に準拠)**:
  1. **固定集合の不変性**: naturally preserved itemと `STRATEGY_PRESERVED` unitはreplan間で位置も理由も変わらない。widget streamの障害物はこの固定集合のみ (eligible widgetは全てliftされる)。
  2. **widget streamの再現性**: widget streamの割当は「固定occupancy + page表面 (PageOrder順) + 不変key順widget multiset (span付き)」の純関数である。run 2のcaptured page表面 = run 1のcaptured page群 + run 1に作成されたpage群 (作成順 = PageOrder続行)。run 1で作られたpageは全captured pageより後続するため、run 2の表面はrun 1の走査表面の後方延長に一致し、前方に新たな候補は現れない。走査時点のoccupancyはkey順の帰納法でrun 1と同一になる。ゆえに各widgetは同一cellを再選択する。
  3. **formation の replan安定性**: folder形成は`1×1` singleton候補のみを対象とし (spec 237どおり)、widgetは決して候補にならない。形成のreplan安定性はspec 237の証明がそのまま適用される。
  4. **app stream**: `1×1` streamは「全cellから固定占有とwidget targetを除いた空きcell列」(1, 2よりrun間で同一) をcaptured visual順の単調消費で埋める (spec 237の消費順単調性argument)。replanは各unitの自cellを回収し、新規folder・新規pageなし、diffは空である。
- この構成では、ADR-0012 Decision 4の反例 (位置順moverの順序入れ替え) が適用される経路が存在しない。app streamの位置順moverは引き続き`1×1`のみであり、heterogeneous spanを持つのは**不変key順**のwidget streamだけである。

### CATEGORY_CONTIGUOUS_V2 — normative rules (定義確定、実装は後続child)

`CATEGORY_CONTIGUOUS_V1` の規則 (page-local、`(profile, category fallback last, canonical target key, ItemId)` 順、folder不作成、既存folder固定) をapp streamについて無変更で継承し、次のwidget policyを追加する。

- Widgetはcategory memberではなくcategory orderingに参加しない。
- **Widget配置**: 各captured pageのeligible widgetを不変key順に、page全域をtop-left row-major first-fitでapp streamより先に配置する (category blockの外側・上側領域に決定的に確保される)。
- **Degrade**: page単位の全体無効化 (共通規則)。
- **Idempotence (構成)**: widget配置は位置非依存の純関数。category streamのorder key (`profile, category, target key, ItemId`) は全て移動不変であり、page membershipが変わらない (page-local) ため、V1のargumentがwidget occupancy込みでそのまま成り立つ。
- category contiguity保証 (同一categoryの連続性) はwidget配置後も意味を保つ (widgetが先に占有する領域はcategory unitの割当から除外されるため、category blockは残る領域内で連続に形成される)。

## Preview / UX (normative)

- `PreviewCounts` に `widgetMovedCount` (default 0) を追加 (D-4)。`MoveChange.rationale == WIDGET_UNIT` の行数。
- Widget移動の `MoveChange` 行は source/destination の `PreviewPosition` (page ordinal含む、spec 194正規化) を運ぶため、適用前にwidgetの移動先page・位置が分かる。初期2 strategyはpage-localであるためwidgetのcross-page移動行は存在しない。
- 保留widget (degradeによる `STRATEGY_PRESERVED`、またはnatural preservation) は既存 `PreservedChange` 行とreasonとして確認画面に現れる。
- Strategy説明copy (ja/en) はapp領域とwidget領域の結果の関係を説明する。方向性 (最終文言は実装PR):
  - `STABLE_PAGE_TIDY_V2`: 「アプリの隙間を埋め、ウィジェットは今ある範囲の中にまとめて配置します。」
  - `BOTTOM_FIRST_V2`: 「アプリを画面下部に詰め、ウィジェットをその上に配置します。」
  - `GLOBAL_COMPACT_V1` と同じく、`STABLE_PAGE_TIDY_V1` / `BOTTOM_FIRST_V1` のdescriptionに「ウィジェットは移動しません」を明記して真実化する (D-6)。
- UI はspec 52 (MFO-15) / 195 / 182 のTalkBack・Switch Access・200% font scaling期待値を継承する。widget移動count は色だけでなくtextとしてannounceされる。

## Failure / stale / concurrency behavior

| Condition | Observable outcome |
|---|---|
| Widgetがstrategyのregion規則で配置不能 (band内/page内/stream内にfitなし) | Degrade規則: 該当scopeのwidget再配置を無効化し `Preserved{STRATEGY_PRESERVED}` + truthful rationale。resize/dropなし。plan全体は成功する (degradeはstrategy定義に含まれる明示的fallbackであり、admitted unitの配置失敗ではない) |
| Widgetがlocked / unavailable / reservation重複 / Dock / app-pair関連 | 現行どおり高いprecedenceのnatural preservation理由で固定。新strategyでも移動しない |
| Widget host unavailable等でcapture側でspan不明・placement unsupported | 既存のcapture/preservation契約に従い固定 (unsupported case) |
| 適用中の失敗 / stale revision | 既存spec 13契約無変更 (transactional apply, recovery point) |
| persisted selectionが旧strategyのまま | 変化なし。旧strategyはcatalogに残るためfail-closedしない |
| APK downgradeで新strategyを知らないbundleへ戻る | selection-layer `NotReady` (fail-closed)。既存機構無変更 (spec 237と同一) |
| `ScopeComposedOrganization` でwidget対応strategy選択 | full-run相位でwidget再配置が実行され、その後同一strategy scopeで候補が追加される (spec 228契約、D-5) |

## Unsupported cases

- Spanがdevice profileを超えるwidget (capture時にinvalid → 既存検証で拒否/固定)。新strategyはresizeしてでも置こうとしない。
- Capture上`CapturedPlacement.UnsupportedContainer`の要素 — 従来どおり扱い変更なし。
- Widget providerの存在確認・bind修復 — plannerの責務外。

## Recovery / rollback

- 適用済みlayoutは既存recovery point契約で復旧可能 (strategy非依存)。widget移動は既存writer経路のworkspace cell更新として書かれ (`cellX`/`cellY`/`screen` のみ。`appWidgetId`/provider行は不変)、spec 237のfolder移動と同じ適用表面である。
- 戦略面のrollback: `CANONICAL_PAGE_COMPACT_V1` はwidget挙動を含め現行 (固定) のまま維持するregression/rollback oracleであり、golden corpus・byte-equivalence要件は本specでは一切変更しない。ユーザーは旧strategyへ選択を戻すことで旧挙動に戻れる。

## Data and state

- 新規永続化なし。strategy選択store・bundle機構はspec 182/237と同一 (新strategy有効化はbundle semantic version/generation/digestの増分publish、`rule-v2`・selection store schema不変)。実装は両strategyを同一mainlineで有効化するため、単一のshipped artifact変更として `organization-policy-v2.6` を一度にpublishする (ADR-0007 §8の「policy content変更ごとの新semantic version」要件は、中間versionを出荷しない単一mainlineでは1増分で満たされる)。
- Plannerは純関数のまま。widget role分類は計画module内部分類であり、platform型/DB行をpublic seamへ漏らさない。
- `PlacementCode.WIDGET_UNIT` はspec 10のpublic shapeへの値追加である (閉じたenumへの事後追加としては初回)。delta正本化は D-4 の単一規則に従う: 受入時点の正本は本spec本文であり、codeを land させる最初の実装PRがspec 10 fileへ反映する (PlacementCode定義 + change history)。

## Permissions, privacy, and security

None — 新規permission・network・telemetryなし。diagnosticsは既存のversion identifier許可の範囲内でstrategy identityのみを露出し、widget provider名・座標を追加しない (既存契約無変更)。

## Accessibility and localization

- Strategy名・説明文 (app領域とwidget領域の関係を説明するcopy) は `values/` + `values-ja/` でlocalizeする。
- Previewのwidget移動countは色だけでなくtextとしてannounceされる (spec 52/195継承)。
- `MoveChange.rationale == WIDGET_UNIT` に対するUI文言 (移動理由) をja/enで追加する。

## Compatibility / migration

- 既存6 strategyのruntime-supported set・挙動・golden corpus・既存test主張は不変。widget未対応strategyの出力は構造的に現行と同一 (`PreserveReason.WIDGET` 経路が残る)。
- 新strategy IDの登録・有効化は spec 237 と同じ機構で行う (bundle semantic version増分、catalog coherence test `runtimeSupported == 実装ID` を各child issueで実行)。
- Layout DB schema・apply write-set・recovery pointは無変更。

## Acceptance criteria

- [ ] AC-1: 受入済みspecが、widgetをgeneric movable unitではなくdistinct semantic placement role (span不変・role別placement intent・専用widget stream) として定義している。
- [ ] AC-2: widget再配置はcaptured spanを厳密に保存し、Organizerはwidgetを決してresizeしない (contract/property test)。
- [ ] AC-3: 既存互換baseline strategyの挙動は黙って変更されない。observable変更は新versioned strategy IDでのみ提供される (golden corpus無変更)。
- [ ] AC-4: `STABLE_PAGE_TIDY_V2` と `BOTTOM_FIRST_V2` がwidget固有の配置挙動を実装し、runtime-supported catalogへ有効化される (bundle semantic version増分、coherence test)。
- [ ] AC-5: multi-cell packing order (role順・不変key順)・候補走査・degrade fallback・idempotenceが各strategyで決定的に定義され、testで検証される (2×2, 4×2, 1×1 widget fixture、widget+lock混在、band内の既存folder/non-`1×1` app (strategy-fixed障害物)、production-role widget (`ExistingRole.Preserved` でも移動する)、band内障害物degrade、複数widget順序安定、portrait/landscape/tablet/two-panelを含む)。
- [ ] AC-6: locked/unsupported/unplaceable widgetがtruthful rationale (`STRATEGY_PRESERVED` または高いprecedenceのnatural preservation) 付きで保留される。
- [ ] AC-7: apply/recovery/provider identity/`appWidgetId`/構成の安全性は無変更のまま検証される (widget移動を含むapplyが既存transaction/recovery test表面で検証される)。
- [ ] AC-8: previewがwidget再配置を分離して報告し (`widgetMovedCount` + `WIDGET_UNIT` 行)、移動先page・位置を適用前に可視化する。
- [ ] AC-9: strategy説明copyがapp領域とwidget領域の意図する関係を伝え (ja/en)、V1系copyが真実化される。
- [ ] AC-10: 実機/emulator評価が代表的 2×2 / 4×2 (またはdevice相当) widgetを含むlayoutで行われ、結果が各strategyの意図と一致することが確認される (before/preview/after evidence)。
- [ ] AC-11: `GLOBAL_COMPACT_V3` / `CATEGORY_CONTIGUOUS_V2` のnormative rulesと冪等性argumentが本specで確定している (実装は後続child issue。本issueの実装PRはこれらを有効化しない)。

## Test oracle

| AC | Evidence |
|---|---|
| AC-1/AC-3 | spec review + golden corpus test (既存corpus無変更で通過) + 既存6 strategyの既存test無修正通過 |
| AC-2/AC-5 | planner unit/contract/property test (public seam経由; fixture: 2×2, 4×2, 1×1 widget, widget+lock混在, band内の既存folder/non-`1×1` app (strategy-fixed障害物) + 重なりなしassertion, production-role widget (`ExistingRole.Preserved` でも移動), band内障害物degrade, 複数widgetの順序安定, portrait/landscape/tablet/two-panel, page容量境界) + replan idempotence property (各新strategy) + span保存assertion |
| AC-4 | catalog coherence contract test + bundle identity assertion (`organization-policy-v2.6`) |
| AC-6 | preservation reason assertion (`STRATEGY_PRESERVED`/natural precedence) |
| AC-7 | 既存application/recovery testの無変更通過 + widget移動を含むplanの適用経路test (writer write-set: cell/screenのみ) |
| AC-8 | preview projection test (`PreviewCounts.widgetMovedCount`, `MoveChange.rationale`) + scope-composed runでのwidget再配置test |
| AC-9 | strings assertion (ja/en) + picker instrumentation test |
| AC-10 | 物理端末/emulator before/preview/after evidence |
| AC-11 | 本specのreview記録 |

## Open questions

None — 初版draftのOpen questions 1〜6は D-1〜D-6 として解消した。後続child issue (`GLOBAL_COMPACT_V3`, `CATEGORY_CONTIGUOUS_V2` 実装) は本specのnormative rulesを実装する。

## Change history

- 2026-09-10: Draft created for #235 (spec-prep worker; baseline `origin/main` @ `6b6bf8dd9f`)。Open questions 6件を所有者判断待ちとして記録。
- 2026-09-12: Re-anchor revision (worker; baseline `origin/main` @ `3e113302b9`)。re-entry check: baseline `6b6bf8dd..3e113302b9` の差分 (issue 228 missing-app selection / 271 durable status / 269 folder representability / 233 backup preview / 265 recovery reconciliation / 288 export filename) を確認し、(1) `ScopeComposedOrganization` run modeとscope-composed契約をD-5および共通規則へ反映、(2) `PreviewCounts.addedCount` (spec 228) の拡張前例に D-4 を整合、(3) `UnplacedReason.STRATEGY_SCOPE_FULL` / `allocateCapturedPageOnly` の現行実装を現行コード参照として更新、(4) コード参照 (PlanningPlacement.kt:373/392 等) を更新。Open questions 1〜6を判断 D-1〜D-6 として解消 (後継ID命名・packaging、cross-page証明方針、movement costの構成的形式化、preview shape拡張、run mode適用範囲、UX案内)。`GLOBAL_COMPACT_V3`/`CATEGORY_CONTIGUOUS_V2` のnormative rulesと冪等性証明を追加、AC-11を追加。
- 2026-09-12: Review revision (code-reviewer-1 review on `f62c51e530`, verdict Request changes; 冪等性証明は全て検証済み健全)。M1: widget streamの障害物集合にstrategy-fixed movable item (既存folder・non-`1×1` app) を明示的に含め、STABLE_PAGE_TIDY_V2の配置規則・冪等性構成・fixture (d) を修正 (wrapperがstrategy-fixed占有を先に印付けする実装方針はplanへ反映)。M2: D-4/§Data and state/planのdelta正本化regimeを単一規則 (受入PRは本spec本文のみ正本、実装PRがspec 10 fileへ反映、spec 194 fileは更新しない) に統一。Low: D-1へcanonical系後継を作らない理由を記録、eligible条件に `ExistingRole == Preserved` の `NON_TARGET` fall-throughを明記 (direct-seam fixtureをAC-5/test oracleへ追加)、BOTTOM_FIRST_V2 fixtureにfolder形成なし条件を固定。
- 2026-09-12: Second review revision (code-reviewer-2 re-review on `fb420a33b4` — M1/M2/L1-L4解消を確認、残条件1件)。D-4のspec 182前例引用を訂正: `FOLDER_UNIT` はspec 10の初期grammar由来でありspec 182の追加ではなく、spec 182は受入時にspec 10 deltaを適用した (acceptance commit `182fc7ec28`)。PlacementCodeへの事後追加は本specが初回であり、受入scopeはspec 228方式の意図的な選定である旨を正確に記述。plan step 3(a') を `strategyFixes` predicate基準 (`unitOrder != CANONICAL_TIE_BREAK && !eligibleUnitFilter`) に固定し、単純filter外判定の誤用 (canonical flowの既存folder) を排除。
- 2026-09-12: Accepted。review経緯: round 1 code-reviewer-1 (Request changes on `f62c51e530`、冪等性証明は全段検証済み健全) → fix `fb420a33b4` → round 2 code-reviewer-2 (M1/M2/L1-L4解消確認、残条件: D-4前例引用の史実誤り) → fix `3d6364445b` → code-reviewer-2 final **Approve on `3d6364445b`** (全条件解消、regressionなし)。所有者 (session 2026-09-12) はreviewクリア後のPhase 2 (実装) 開始を指示済みであり、これを本specの受入判断として記録する。実装は `STABLE_PAGE_TIDY_V2` → `BOTTOM_FIRST_V2` の順とする。
- 2026-09-12: Implementation-prep correction (plan.mdの単一PR統合パスを採用): 両strategyを同一mainlineで有効化するためbundle増分は `organization-policy-v2.6` の1回のみとする (旧記述の `-v2.6`/`-v2.7` の2段階増分は個別child PR分割を想定したもので、ADR-0007 §8 の要件は中間versionを出荷しなければ単一増分で満たされる)。behavior定義への影響なし。
- 2026-09-12: Owner re-review correction (PR #296 re-review round 2): widget streamの不変key順におけるtarget keyのcanonical順をspec 10の型どおり比較として明記した — `(provider UTF-8 byte order, appWidgetId numeric order, profile UTF-8 byte order)`。実装が `targetKeySortValue` の連結文字列を比較していたため、`appWidgetId` の辞書順化 (`"10" < "2"`) とprofile segment欠落がAC-5のdeterministic invariant-key orderと不一致だった (owner review発見)。実装 comparator と fixture (appWidgetId 2/10 numeric順、同provider同appWidgetId異profile順) を修正、不変keyは全segment移動不変のため冪等性証明に影響なし。
- 2026-09-12: AC-10 production-finding amendment (device evaluation on the merged build found the widget stream never fires in production): the eligibility rule is corrected — the widget branch is terminal for widget kinds under widget-capable strategies, superseding the `ExistingRole` check. The production composer marks every widget `ExistingRole.Preserved` by kind (widgets are never user-selected organization targets, pre-#235 rule), so the round-1 "NON_TARGET fall-through" wording made widget relocation unreachable in production; the withdrawn-rule discriminator fixture is replaced by a production-role fixture. Locked/unavailable/reserved-overlapping/Dock widgets remain fixed via the higher-precedence reasons. Non-widget-capable strategies are unchanged (`PreserveReason.WIDGET`). Idempotence proofs unaffected: all widget-stream segments remain relocation-invariant.
