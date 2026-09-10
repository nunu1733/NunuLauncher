---
issue: "#235"
status: draft
requirements:
  - FR-003
  - FR-016
risk:
  - layout-data
updated: 2026-09-10
---

# Strategy-aware fixed-span widget placement in Organizer

> Status: draft — 仕様準備 (spec-prep) snapshot。本specは未審査・未承認であり、実装開始の根拠にならない。Open questions (後述) に所有者判断を要する未決定事項が残っている。

## Problem

Organizerのlayout strategy catalog (spec 182 / ADR-0012) は、現状すべてのstrategyで captured widgetを固定占有率制約 (fixed occupancy constraint) として扱う。観測可能な現行挙動 (`origin/main` @ `6b6bf8dd9f`):

- `PlanningPlacement.determinePreservation` (`lawnchair/src/app/lawnchair/organizer/planning/PlanningPlacement.kt:239`) は `ItemKind.APPWIDGET` / `CUSTOM_APPWIDGET` を無条件で `PreserveReason.WIDGET` により保持する。widgetは `movableItems` に入らず、strategy実行前にallocator上で占有済みセルとして印付けられる。
- したがって6つのruntime-supported strategy (`CANONICAL_PAGE_COMPACT_V1`, `STABLE_PAGE_TIDY_V1`, `BOTTOM_FIRST_V1`, `GLOBAL_COMPACT_V1`, `GLOBAL_COMPACT_V2`, `CATEGORY_CONTIGUOUS_V1`) はいずれも、widgetの周囲のapp/folderのみを再構成する。strategy間で変化するのは「widgetをどこに残すか」ではなく「残されたwidgetの周りをどう詰めるか」だけである。

これは安全だが、strategyの意図と結果が食い違う。upper-left compactでは大きなwidgetが最高価値領域 (画面上部) を占めたままになり、bottom-firstではappとwidgetが同一の走査順で_bottom行を取り合う。widgetは意味的に異なるHome要素であり (resizeはUI/機能を変え得るため不変、位置変更は許容、`4×2` rectangleは`1×1` appと同一のfirst-fit列に入れるべきでない)、単なる「大きいmovable item」として扱うべきではない。

## Outcome

ユーザーがwidget移動に対応した後継strategyを選ぶと、plannerはwidgetを「span不変の固定的矩形 (fixed-span rectangle)」として、app/folderとは別個のrole固有配置意味論 (placement semantics) に従って再配置できる。widgetのサイズ・provider identity・`appWidgetId`・構成・bindingは一切変化せず、共有のoccupancy/bounds/検証契約が引き続き権威であり、previewはwidget再配置をapp/folder移動とは区別して可視化する。既存の全strategy ID (`CANONICAL_PAGE_COMPACT_V1` を含む) のobservable behaviorは一切変更しない。

## Scope

- 計画modelへのsemantic placement role分類の導入: 計算上少なくとも `APP_OR_SHORTCUT` / `FOLDER` / `WIDGET` を区別し、strategyがrole別の配置意思 (preferred region / gravity / movement intent) を宣言できるようにする。
- Widget移動に対応する**新規versioned strategy ID** (後継strategy)。対象は少なくとも stable/tidy 系と bottom/upper compact 系の2 family (issue受入条件)。既存IDの意味変更はしない。
- Strategy別 `WidgetPlacementPolicy` (または等価の内部strategy metadata): preferred region/gravity、movement cost/stability preference、page affinity、決定的な矩形候補走査、overflow/fallback。
- 固定span矩形配置のための共有allocator拡張 (既存の単一occupancy/bounds実装を保つ)。
- 保持されたwidgetへのtruthful rationale (spanが安全に置けない場合の明示的保留とその理由)。
- Preview/説明へのwidget再配置の暴露: widget移動数の分離、cross-page widget移動の可視化、strategy説明文のapp領域とwidget領域の関係説明。
- 新規strategy有効化に伴うbundle semantic version publish (ADR-0007 §8 / ADR-0012)。

## Non-goals

- Widgetのresize、`spanX`/`spanY`変更、異なるsize/layout modeの選択。
- Widgetの再作成・rebind・再構成・`appWidgetId`/provider identity/構成の変更、widgetの自動置換。
- 既存strategy ID (`CANONICAL_PAGE_COMPACT_V1`, `STABLE_PAGE_TIDY_V1`, `BOTTOM_FIRST_V1`, `GLOBAL_COMPACT_V1`, `GLOBAL_COMPACT_V2`, `CATEGORY_CONTIGUOUS_V1`) のobservable behavior変更、golden corpus・既存test主張の変更。`CANONICAL_PAGE_COMPACT_V1` は #182 のregression/rollback oracleとして不変。
- Widget内容の意味解釈 (provider種別による位置決定)。
- Locked / reserved-overlapping (`RESERVED_REGION`) / unavailable / Dock / app-pair / legacy-shortcut 要素の移動。これらは引き続き全strategyで固定。
- 適用・復旧・transaction・recovery point契約の変更 (spec 13無変更)。
- 組み合わせ式public toggle (role × region × cost)。catalogはcuratedのまま (ADR-0012 Decision 1)。
- Incremental placement run modeでのwidget移動 (現行どおりstrategyはFullOrganizationのみに作用; 変更する場合は別decision)。

## Domain language

`CONTEXT.md` への追記 (承認時)。用語の最終形はOpen questions解消後に確定する。

**semantic placement role (セマンティック配置role)**:
計画対象のtop-level unitを、配置意味論上の種別 (app/shortcut、folder、widget) として分類したもの。strategyはroleごとにpreferred regionとmovement intentを別個に宣言する。
_Avoid_: movable flag (widgetをgeneric movable unitへ退化させる)、item kind (capture側の型名との混同)

**widget placement policy**:
あるstrategyがwidget roleに対して宣言する、span不変の再配置意思。preferred region/gravity、movement cost/stability preference、page affinity、決定的矩形候補走査、fallbackからなる。
_Avoid_: widget settings (user設定との混同)

## Constraints from accepted specs/ADRs (non-negotiable)

本specは以下を受入済み正本の制約として従う。本specがこれらと衝突する内容を採らないこと。

1. **ADR-0012 Decision 2 / spec 182**: strategy IDはimmutable semantic identity。挙動変更は新IDで表現し、出荷済みIDのrename・無言再解釈は禁止。
2. **ADR-0012 Decision 4 (idempotence制約)**: heterogeneous spanのunitをfirst-fitでcross-page移動するとfragmented fixed obstacle下で再計画時にvisual orderが入れ変わりINV-8 (idempotence) を破る。cross-page moverを`1×1`に制限したのはこのためであり、**heterogeneous-span variantは完全な証明を伴う新strategy IDとしてのみ許容される**。widget (多くがnon-`1×1`) の移動、特にcross-page移動は、この反例に対して各strategyが独自の証明を与えなければならない。これが本specの最も重い設計制約である。
3. **spec 237のversioning前例**: 後継strategy (`GLOBAL_COMPACT_V2`) の導入機構 — `LayoutStrategyRegistry` への新ID登録、`runtimeSupported` 追加、新bundle semantic version publish (`organization-policy-v2.5` からの増分)、`rule-v2`/selection store schema不変、V1はcatalogに残りfail-closedしない。
4. **spec 182の保全理由の truthful さ**: strategyが意図的に固定する本来movableな要素は `STRATEGY_PRESERVED` であり、`ALREADY_CANONICAL` や`NON_TARGET`を偽って使わない。
5. **spec 10/12の適用不可能意味論**: V-21/V-22のみがcandidateをimpossibleにでき、silent dropは禁止。配置できないwidgetは保留+理由として表面化する。

## Observable behavior (draft normative semantics)

以下はdraftである。戦略ID命名・packaging (Open questions参照) は未決定だが、受け入れられるspecは各対象strategy familyについて次の内容をnormativeに定義しなければならない。

### 共通のwidget role規則 (全対象strategy)

- Eligible widget: unlocked、`AVAILABLE`、workspace配置、`RESERVED_REGION`重複なしの `APPWIDGET`/`CUSTOM_APPWIDGET`。それ以外のwidgetは現行どおり高いprecedenceの保持理由で固定される。
- 移動時もwidgetのtarget spanはcaptured spanと厳密に等しい (`PlacementTarget.WorkspaceTarget` のspan)。resizeは一切発生しない。
- Widgetはfolder形成候補にならず、category orderingに参加せず、`1×1` unit streamに混入しない。
- Widgetの移動は専用の`PlacementCode` (例: `WIDGET_UNIT`) で報告される (既存`SINGLE_PLACEMENT`への偽装を避ける; 最終名はplanで確定)。
- 配置候補走査は決定的であり (page → cell走査の全順序がstrategy定義で固定)、allocator iteration orderに依存しない。
- **Packing orderの明示的定義 (issueの核心要件)**: 各strategyは「1×1 appを先に全配置すると4×2 widgetの残る矩形がない」対「widgetを先に配置するとapp幾何が大きく変わる」問題に対し、role間の配置順 (またはregion分離による非干渉) を明示的に定義する。偶発的なiteration順に委ねない。
- **Idempotence**: 各strategyはwidget移動を含む再計画がempty diffになることのargumentをspecに記載し、property test (replan) で検証する。page-localなwidget再配置であれば「lift-then-place」型の構成的argument、cross-pageであれば消費順の単調性argument (spec 237 §証明参照) が必要になる。
- Fallback: strategyのpreferred region内にcaptured spanの安全な矩形がないwidgetは、(a) 同page内の他の決定的候補、(b) page scopeが許すなら他page、(c) いずれも不可なら元位置で `STRATEGY_PRESERVED` (または位置が既に最適なら`ALREADY_CANONICAL`) としてtruthfulに保留される。resize・黙示dropはしない。

### Stable/tidy系 (例: `STABLE_PAGE_TIDY_V2` — IDは未決定)

- Widgetはcaptured pageに留まる (page affinity最大)。移動は「icon移動だけでは達成できない穴の解消」に限られ、widget間の不要な入れ替えを避ける。
- 決定的実装方向 (draft): page内でwidgetを先に「captured位置に近い決定的候補」へ置き直し (移動不要なら自位置)、その後に`1×1` unitをlift-then-placeで詰める。widgetのmovement costは「自位置への留在を最優先候補とする走査順序」として決定的に表現し、探索的最適化は導入しない (現行plannerにsearchはなく、導入は別decision)。
- 冪等性: widgetの最終位置がcaptured位置の関数として決定的であり、次回captureでは同じ位置に既にいるため再移動しない。

### Bottom-first / upper-left compact系 (例: `BOTTOM_FIRST_V2` — IDは未決定)

- App/folderは従来どおりの走査 (bottom-first または top-left row-major) で主要領域を埋める。
- Widgetは相補領域 (bottom-firstなら上部、upper-leftならapp領域の下) をpreferred regionとし、region内の決定的走査で配置される。region内に置けない場合は上記fallback。
- Role別領域分離により、4×2 rectangleが高価値icon領域を分割しない。
- Portrait/landscape/tablet/two-panel各device profileで決定的であり、phone専用の行仮定を置かない。

### Global compact系 (最も慎重な検討を要する)

- Widgetのcross-page移動はADR-0012 Decision 4のheterogeneous-span反例に直接触れる。許容する場合は、widgetをapp streamとは独立の順序で (例: widget streamを先に、またはpage別の決定的順で) 消費し、消費順の単調性による完全な冪等性証明をspec 237様式で与える。証明できない場合はwidgetをpage-local再配置に限定する (`GLOBAL_COMPACT`後継でもwidgetはpage内でのみ動く) か、widget移動を当該strategyの対象外とする。**この選択は未決定である (Open questions)。**

### Category-contiguous系

- Widgetはcategory memberではなくcategory orderingに参加しない。category blockの外側の決定的領域 (例: page末尾のrow-major空き) に配置される。category contiguity保証はwidget配置後も意味を保つ。

## Preview / UX (draft)

- `PreviewCounts` (spec 194 seamのpublic形状) へwidget移動数の分離count追加 (例: `widgetMovedCount`)、cross-page widget移動の可視化。これはspec 194/195 projectionの拡張であり、既存countの意味は変えない。
- Strategy説明文はapp領域とwidget領域の結果の関係を説明する (例: 「アプリを上部に詰め、ウィジェットをその下に配置します」)。
- 保留widget (spanが置けず`STRATEGY_PRESERVED`) は理由付きで確認画面に現れる。

## Failure / stale / concurrency behavior

| Condition | Observable outcome |
|---|---|
| Strategyのrules上widgetを配置できない (preferred region・fallback全滅) | 元位置で `Preserved{STRATEGY_PRESERVED}` + truthful rationale。resize/dropなし。plan全体は成功 (full-run plannerのsilent drop禁止は「admitted unitの配置失敗はloud failure」を意味する — widgetのfallback保留はstrategy定義に含まれるためcontradictionしない。この境界の正確な定義はplanで確定) |
| Widgetがlocked / unavailable / reservation重複 / Dock / app-pair関連 | 現行どおり高いprecedenceの保持理由で固定。新strategyでも移動しない |
| Widget host unavailable等でcapture側でspan不明・placement unsupported | 既存のcapture/preservation契約に従い固定 (unsupported case) |
| 適用中の失敗 / stale revision | 既存spec 13契約無変更 (transactional apply, recovery point) |
| persisted selectionが旧strategyのまま | 変化なし。旧strategyはcatalogに残るためfail-closedしない |
| APK downgradeで新strategyを知らないbundleへ戻る | selection-layer `NotReady` (fail-closed)。既存機構無変更 |

## Unsupported cases

- Spanがdevice profileを超えるwidget (capture時にinvalid → 既存検証で拒否/固定)。新strategyはresizeしてでも置こうとしない。
- Capture上`CapturedPlacement.UnsupportedContainer`の要素 — 従来どおり扱い変更なし。
- Widget providerの存在確認・bind修復 — plannerの責務外。

## Recovery / rollback

- 適用済みlayoutは既存recovery point契約で復旧可能 (strategy非依存)。
- 戦略面のrollback: `CANONICAL_PAGE_COMPACT_V1` はwidgetを含む全widget挙動を現行 (固定) のまま維持するregression/rollback oracleであり、golden corpus・byte-equivalence要件は本specでは一切変更しない。

## Data and state

- 新規永続化なし。strategy選択store・bundle機構はspec 182/237と同一 (新strategy有効化は新bundle semantic version/generation/digest publish、`rule-v2`・selection store schema不変)。
- Plannerは純関数のまま。widget role分類は計画module内部分類であり、platform型/DB行をpublic seamへ漏らさない。

## Permissions, privacy, and security

None — 新規permission・network・telemetryなし。diagnosticsは既存のversion identifier許可の範囲内でstrategy identityのみを露出し、widget provider名・座標を追加しない (既存契約無変更)。

## Accessibility and localization

- Strategy名・説明文 (app領域とwidget領域の関係を説明するcopy) は `values/` + `values-ja/` でlocalize。
- Previewのwidget移動count は色だけでなくtextとしてannounceされる (spec 52/195継承)。

## Compatibility / migration

- 既存6 strategyのruntime-supported set・挙動・golden corpus・既存test主張は不変。
- 新strategy IDの登録・有効化は spec 237 と同じ機構で行う (bundle semantic version増分、catalog coherence test `runtimeSupported == 実装ID` を各child issueで実行)。
- Layout DB schema・apply write-set・recovery pointは無変更。

## Acceptance criteria (draft)

- [ ] AC-1: 受入済みspecが、widgetをgeneric movable unitではなくdistinct semantic placement role (span不変・role別placement intent) として定義している。
- [ ] AC-2: widget再配置はcaptured spanを厳密に保存し、Organizerはwidgetを決してresizeしない (contract/property test)。
- [ ] AC-3: 既存互換baseline strategyの挙動は黙って変更されない。observable変更は新versioned strategy IDでのみ提供される (golden corpus無変更)。
- [ ] AC-4: stable/tidy系とbottom/upper compact系の少なくとも2 familyがwidget固有の配置挙動を定義・実装する。
- [ ] AC-5: multi-cell packing order・候補走査・fallback・idempotenceが各strategyで決定的に定義され、testで検証される (2×2, 4×2等の代表的widget fixtureを含む)。
- [ ] AC-6: locked/unsupported/unplaceable widgetがtruthful rationale付きで保留される。
- [ ] AC-7: apply/recovery/provider identity/`appWidgetId`/構成の安全性は無変更のまま検証される。
- [ ] AC-8: previewがwidget再配置を分離して報告し、page変更を適用前に可視化する。
- [ ] AC-9: strategy説明copyがapp領域とwidget領域の意図する関係を伝える (ja/en)。
- [ ] AC-10: 実機/emulator評価が代表的 2×2 / 4×2 (またはdevice相当) widgetを含むlayoutで行われ、結果が各strategyの意図と一致することが確認される。

## Test oracle (draft)

| AC | Evidence |
|---|---|
| AC-1/AC-3 | spec review + golden corpus test (既存corpus無変更で通過) |
| AC-2/AC-5 | planner unit/contract/property test (public seam経由; fixture: 2×2, 4×2, 1×1 widget, widget+lock混在, fragmented obstacle + widget, portrait/landscape/tablet/two-panel) + replan idempotence property |
| AC-4 | 各新strategyのnormative fixture test |
| AC-6 | preservation reason assertion (`STRATEGY_PRESERVED`/precedence) |
| AC-7 | 既存application/recovery testの無変更通過 + widget移動を含むapplyの実機evidence |
| AC-8 | preview projection test + Compose UI test |
| AC-9 | localization review (LQA) |
| AC-10 | 物理端末 before/preview/after/recovery evidence |

## Open questions (実装開始前に所有者判断が必要)

以下は未決定であり、本draftは推測によって固定しない。

1. **新規strategy IDの命名と packaging**: 後継IDの名前 (`STABLE_PAGE_TIDY_V2` / `BOTTOM_FIRST_V2` 等) と、初期縦切りでどのfamilyから提供するか。既存strategyの置換 (旧IDのdeprecate) は行うか。
2. **Global compact系でのwidget cross-page移動の可否**: ADR-0012 Decision 4の反例に対する完全な冪等性証明が構成できるか、page-localに限定するか、対象外とするか。
3. **Stable/tidy系のmovement costの形式化**: 探索なしの決定的planlerで「icon移動だけでは達成できない場合のみwidgetを動かす」をどう構成的に表現するか (draftの「widget先・自位置最優先」案の採否を含む)。
4. **Preview projectionのpublic形状変更**: `PreviewCounts`へのwidget count追加がspec 194 seamへの形状変更となるため、spec 194/195側へのdelta反映の要否と形状。
5. **Incremental placement run mode**でのwidget扱い (現行はstrategy不適用のため自動的に対象外。明示的に対象外とするか)。
6. **既存strategy選択ユーザーへの提案**: 新後継strategyの存在を既存選択ユーザーにどう通知・案内するか (UX判断)。

## Change history

- 2026-09-10: Draft created for #235 (spec-prep worker; baseline `origin/main` @ `6b6bf8dd9f`)。
