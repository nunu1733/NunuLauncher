---
issue: "#398"
status: draft
requirements: [FR-016]
risk:
  - layout-data
  - migration
updated: 2026-09-23
---

# Organizer strategyをユーザー意図一致優先で再評価し、下部領域semanticsのsuccessor strategy `BOTTOM_REGION_V1` を追加する

> Status: **draft** — Phase 1 (spec/plan) review待ち。本specは strategy catalog のproduct objectiveの明文化と、下寄せ空間構成の新successor strategy 1件の追加だけを対象とし、既存strategyのobservable semanticsは1つも変更しない。

## Problem

#182 でversioned catalogとして整備されたlayout strategyのうち、`BOTTOM_FIRST_V1` は `CANONICAL_PAGE_COMPACT_V1` とfolder/unit/page policyが同一で、cell traversalだけをbottom-upに変えた「各ページを下段から全面充填する」設計である（spec 182）。このため次の代表入力ではstrategy間の差が大きく退化する。

1. Home配置をリセットして実質ゼロ状態にする
2. アプリ追加で全アプリを対象にする（dense input）
3. Organizerで整理する

full pageはどのtraversalでも全面が埋まるため、`CANONICAL_PAGE_COMPACT_V1` は上側から、`BOTTOM_FIRST_V1` は下側から、`GLOBAL_COMPACT` 系はcross-pageで埋め、ユーザーから見た主な差が「ページ数・密度はほぼ同じで、アプリ順序と最終ページの寄り方が多少違う」程度まで縮小する。これはedge caseではなく、**strategyが何を最適化すべきかというproduct objectiveの再定義**を要求する兆候である。

#356系譜（Organizer全体TO-BE再設計とそのbacklog #365〜#377）は2026-09-23時点で全てclosedであり、本Issueのscheduling/dependency gate（#356のexit criteria充足 + #365〜#377完了 + 派生必須修正の完了。#398本文）を満たしている。open Issue一覧に#365〜#377由来の未完了migration/follow-upは存在しない（2026-09-23確認）。本specはその後のlayout-strategy再評価として、#356系譜のTO-BE情報設計・run構造を先取りして固定しない。

## Outcome

1. **Strategy objectiveの明文化**: built-in layout strategyのprimary success metricは「ユーザーが選んだ/表明したHome構成意図（strategy identityのobservable空間semantics）との一致」である。page count / folder count / occupied-cell density / move count等の数量指標は、意図一致を損なわない範囲の補助指標・tie-breakerであり、意図のvisual identityを消すために使わない。この優先関係を本specが定義し、将来のcatalog追加・改訂の評価基準とする。
2. **新successor strategy `BOTTOM_REGION_V1` の追加**: 各pageの **下部優先領域 (lower preferred region)** を主な配置領域とし、領域が満タンでも上段を埋めずに次pageの下部領域へoverflowする（page数増加・上部余白を正当な結果として許容する）strategy。dense inputでもvisual identityが単なるitem order差へ退化しない。
3. **`BOTTOM_FIRST_V1` のversioning判断**: semanticsは無変更のままcatalogに維持する（後述「Versioning and migration」）。picker copyのみ、全面的な下詰め充填であることをより正確にする（copy-only、semantics変更ではない）。
4. **AI personalizationとの合成規則の明示**: strategyが空間構造（下部優先領域・sweep順序・page成長）のauthorityを持ち、`PersonalizedIntent` の各preferenceはその構造の内側でのみitem assignmentへ効く。合成の可否・意味をstrategy族別のmatrixで固定する。

`OrganizationPlanner.plan(OrganizationInput)` は唯一の外部planning seamのままである。新strategyの実装は内部seam（`StrategyDefinition` とshared allocator）の内側に留まり、既存strategy・safety invariantには触れない。

## Strategy objective (product principle)

NunuLauncher Organizerの目的は、ページ数・フォルダ数・空きセル数を最小化すること自体ではない。優先する成果は:

> **ユーザーがイメージしたHome構成を、手作業コストを削減しながら、できるだけ忠実に再現すること。**

運用規則（built-in strategy catalog全体に適用）:

- 各strategy identityは、そのobservable空間semantics（意図するHome全体の形）をprimary success metricとして定義・評価される。picker copy / preview / diagnosticsはこのsemanticsと一致しなければならない。
- page/folder/density/move count等は、primary metricを満たす前提での補助指標・tie-breakerである。「1ページ減らすために意図した余白を埋める」結果は、数量的にcompactでもproduct目的上は劣る。
- dense input（reset/zero-layout + 全アプリ等）は、strategy identityが退化しないことを検証する代表条件である。新規strategy・既存strategyの改訂候補は、dense inputでprimary metricが維持されることを示さなければならない。
- 本規則は既存strategyのaccepted semanticsを遡って変更しない。既存strategyの評価は今後の改訂候補時に本規則を基準に行う。

## Domain language

`CONTEXT.md` への追加用語案（受入時に反映）:

**下部優先領域 (lower preferred region)**:
layout strategyが配置候補cellを制限するために、device profileの行数から決定的に導出する、page下部の行帯。`BOTTOM_REGION_V1` が採用する。#204 `regionAffinity` のBOTTOM band（行数3等分区の下带）とは別の、strategy構造の正本概念である。
_Avoid_: widget band（#235のcaptured widget帯との混同）、region band（intent hintの行帯との混同）、下段（行数非依存の概念であることの不明瞭化）

## Decision 1: `BOTTOM_REGION_V1` — normative rules

新strategy IDは `BOTTOM_REGION_V1`（immutable semantic identity、ADR-0012。behavior変更は新ID）。catalog memberとしての宣言:

| 項目 | 値 |
|---|---|
| Intent | 各pageの下部を主な配置領域にし、上側を意図した余白として保持する |
| Folder policy | `CANONICAL_PAGE_COMPACT_V1` と同一（canonical P-04/P-05 grouping、新folder形成あり。既存folderはplacement unit） |
| Eligible movable units | app/deep shortcut（任意span。既存folderは別unit stream。spec 182 canonical族と同一集合） |
| Unit order | canonical族順（既存folder → 新folder → singleton、identity-based。下記詳細） |
| Page scope | 下部領域sweep（下記）。page成長を許容 |
| Cell traversal | 下部優先領域内でbottom-up row-major |
| Widget policy | null（widgetは移動しない。canonical族と同一の保持扱い `PreserveReason.WIDGET`） |

### 下部優先領域の定義

`rows` を当該runの `DeviceCapabilities.rows` とするとき、下部優先領域の行数は:

```text
lowerRegionRows(rows) = (rows + 1) / 2   // ceil(rows/2)
lowerRegion = (rows - lowerRegionRows(rows)) until rows
```

候補比較（rows=3/4/5/6の実例で比較。決定の根拠）:

| 案 | 定義 | rows=3 | rows=4 | rows=5 | rows=6 | 評価 |
|---|---|---|---|---|---|---|
| 下1/3（intent BOTTOM band `((2*rows)/3) until rows` と同一） | 3等分の下带 | 1行 | 2行 | 2行 | 2行 | rows=3で1行と主領域として機能しない。行数に対する比率が整数除算で非一様（67%/50%/40%/33%）。移動可能な複数行spanがregion外固定になる頻度が上がる |
| 下 `floor(rows/2)` 行 | 半分（切り捨て） | 1行 | 2行 | 2行 | 3行 | rows=3で1行。rows=5で40%であり「主な配置領域」として弱い |
| **下 `ceil(rows/2)` 行（採用）** | 半分（切り上げ） | 2行 | 2行 | 3行 | 3行 | rows≥3で常に2行以上。ページの過半を下部に使いつつ上側に意図的余白を残す。「下半分を主領域にする」意図と全グリッドで対応する |
| 下 `rows - 1` 行（上1行だけ余白） | 上1行を除く全行 | 2行 | 3行 | 4行 | 5行 | 余白が薄く、dense inputでcanonical/bottom-firstとの判別可能なidentityが消えやすい（#398 Problemの再発） |

領域は幅方向には制限しない（page全幅）。領域導出はrow数のみから決定的に行い、device種別・orientation・grid presetへのハードコードはしない（既存全strategyと同一の原則）。

### Page scope: 下部領域sweep

各placement unitは、unit順に:

1. capture済み全pageを `PageOrder` 順に走査し、各pageの下部優先領域内の空きcellをbottom-up row-majorで探す
2. 見つからなければ、既に作成済みの新pageを同じ順で走査する
3. それでも見つからなければ新pageを作成し、その下部優先領域内に配置する

**上段への配置は決して行わない。** pageの下部領域が満タンでも上段は埋めず、次pageの下部領域へ進む（#398要求の「preferred region が埋まった場合、上段を直ちに埋めるのではなく次pageの lower region を優先する」）。その結果page数は増加し得、全pageの上側余白は意図したlayout結果として保持される。既存page recordの物理削除は行わない（既存policy不変）。

候補だったoverflow順序の比較:

| 案 | 挙動 | 評価 |
|---|---|---|
| region満タン後、上段へフォールバック | page数を抑えるがdense inputでidentityが消える | #398が明示的に避けるよう要求する設計。却下 |
| **次pageの下部領域へ（採用）** | captured pageをPageOrder順にsweepした後、新page | 「次pageの lower region を優先」に一致。既存の疎な複数pageでもstrategy shapeが実現される |
| 自page優先・overflowは新page | page affinityを保つが、既存page 2の下部領域が空のまま新しいpageの下部が埋まる不整合が生じる | 可視的に不自然（前に空き領域があるのに後ろのpageを使う）。却下 |

### Fixed set（strategy固定）

- `determinePreservation` で保持される対象（lock、reservation、dock、widget、app pair、legacy shortcut、unavailable等）は既存優先順位の理由で固定である（全strategy共通。変更なし）。
- 加えて、movable unitのうち **span.heightが下部優先領域の行数を超えるもの** は、captured位置に固定し `PreserveReason.STRATEGY_PRESERVED` で報告する。領域は行数を1以上満たすため、複数行spanでも行数以下なら領域内に配置可能であり、超過する場合のみ固定する。これはtruthfulなstrategy意図の固定であり（spec 182/237の `STRATEGY_PRESERVED` 機構と同一）、既存の `PreserveReason` 優先順位を変更しない。identity-basedなunit順序はcaptured位置に依存しないため、この固定は再plan安定である。
- 上段に存在するfixed対象（widget等）はそのまま保持され、sweepのoccupancy制約として機能する。identityの主張は「movable unitを上段へ移動しない」であり「上段を空にする」ではない。

### Unit order（canonical族順・identity-based）

`CANONICAL_PAGE_COMPACT_V1` / `BOTTOM_FIRST_V1` と同一のunit順序構造を、page groupingなしの単一streamで使う:

1. 既存folder unitを `ItemId` 順
2. 新folder unitを `NewFolderOrdinal` 順（canonical grouping政策はintentの `groupSemantic` 含めcanonical族と同一）
3. singletonを（intent preference biasを含むcanonical族のsort key群。下記合成規則）順

identity-basedな順序はplacementに不変であり、#204 preference biasの各keyは該当preferenceなしでは定数である（既存canonical flowと同一の構造）。

### Idempotence argument

1. unit順序はidentity-based（captured位置非依存。intentのpreserve hint等、位置を参照するsoft hintは後述のclip規則の下で決定的）。
2. fixed setは入力状態と領域幾何の決定的関数であり、run間で不変（movable unitの再配置はfixed setを構成しない）。
3. sweep割当は（unit順序、fixed occupancy、領域幾何）の決定的関数である: k番目のunitは、captured page群→新page群の走査順でk番目の空き領域cellを得る。
4. 適用後のmaterialized layoutで再planすると、(1)(2)(3)が同一入力から再計算され、同一assignmentが得られ、差分は空になる。新folderは再plan時、既存folderとして同一のメンバー不変でEF streamへ入る（canonical族と同一のfolder遷移。folder形成候補から既存folderは除外されるため残留groupの再形成は起こらない。spec 237と同一の論法）。
5. 実行可能証明として、既存harnessのIDEMPOTENCE/DETERMINISM/CONSERVATION等の全契約が新strategyを含むregistry駆動testを通過すること（`CrossStrategyCorpusTest` は `acceptedIds` を自動巡回する）に加え、dense入力の再plan差分ゼロを専用fixtureで固定する。

対比として、captured位置（positional）順序をsweepに使うことは採用しない。bottom-up充填とvisual順序（y昇順）の読み出し順序が逆になるため、materialized状態のvisual順序が消費順序を復元せず、replanで順序が回転する（ADR-0012決定4のpositional順序の安定条件と同型の問題）。

### Scope-composed run（#228 candidate tail）

`pageScope` としては既存 `CAPTURED_THEN_NEW` を宣言した上で、candidate tailは **下部優先領域内に限って** `CAPTURED_THEN_NEW` 相当の走査を行う。領域内に配置できないcandidate（span.heightが領域行数を超える場合を含む）は `UnplacedReason.STRATEGY_SCOPE_FULL` でunplaced報告される（candidateにはcaptured位置が存在しないため固定はできない）。full-run phaseのsemanticsは既存のとおりstrategyのfull-run executorへ委譲される。

### AI personalizationとの合成規則

authority分離（#204契約の再確認と、strategy軸での明示化）:

- **strategyは空間構造を決める**: 領域幾何、sweep順序、page成長、上段非使用。intentはこの構造を緩めない。AIがraw座標・最終 `(page,x,y)` をauthoritativeに決めない既存契約（#204 `FORBIDDEN_CONTENT`）は不変であり、下寄せstrategyの選択・構造をintentが強制・無効化する経路も存在しない。
- **preferenceは構造の内側でのitem assignmentに効く**: sweep stream内の順序bias（どのアプリが早く消費され、より良い領域cellを得るか）と、構造内で充足可能なsoft hint。

合成matrix（normative）:

| intent field | `CANONICAL` 族（TOP_LEFT系） | `BOTTOM_REGION_V1` |
|---|---|---|
| `importance` | 順序bias（既存どおり） | sweep streamの順序bias（早い= page 1の最下段等、より良い領域cell） |
| `desiredGroup`/`groupSemantic` | folder形成key（既存どおり） | 同一（canonical groupingで新folder形成、folder unitはsweep配置） |
| `pageAffinity` | preferred page bias（既存どおり） | inert（sweepはpage affinityを消費しない。GLOBAL_COMPACT sweepと同一の先例） |
| `regionAffinity` = `BOTTOM` | 既存どおりordering bias | 領域内でより早い消費（より低いcell）へのbias |
| `regionAffinity` = `TOP`/`MIDDLE` | 既存どおりordering bias | inert（上段配置なしでは充足不可能。決定的に無視する） |
| `preserve`（per-item） | captured cell hint（既存どおり） | captured cellが領域内にある場合のみhint（領域外captured cellは無視）。領域内ならそのcellが空きのとき正確に使う |
| `globalPreference.minimizeMovement` | singleton順序をcaptured visual順へ（既存どおり） | **不消費**（identity順序を維持。positional順序への切替はreplan安定性を壊すため、下寄せsweepでは定義しない） |

この合成により、#398の要求「compact + 重要アプリを下へ」は、既存の `CANONICAL_PAGE_COMPACT_V1`（または`STABLE_PAGE_TIDY_V1`等page内compaction）+ intent `importance: HIGH` + `regionAffinity: BOTTOM` の組合せで、compact構造を保ったまま表現できる。下寄せstrategyの選択は強制しない。専用fixtureで固定する（evaluation scenario 3）。

preserve hintのclip規則: `BOTTOM_REGION_V1` ではhint配置が決して下部優先領域の外へ出ないことをtype levelで保証する（領域内判定後の使用/無視）。hint無視は決定的かつ無音ではなく、plan上は通常のsweep配置として現れる（warningは発生させない。preferenceはnon-authoritativeであるため）。

## Decision 2: `BOTTOM_FIRST_V1` のdisposition

- **維持（retireしない・非表示にしない）**: `BOTTOM_FIRST_V1` のaccepted semantics（spec 182: canonical族policy + bottom-up全面traversal）はそのまま正しく、versioned identity規律（ADR-0012）により無変更で維持する。既存selection・既存golden corpus・既存test oracleは不変。
- **役割の明確化（copy-only）**: picker descriptionを「各ページを下段から順に詰めます（全面充填）」の趣旨へより正確にする。semantics変更ではなく、#398で判明した「下部領域」との差の説明である。`_V2`（widget対応）のcopyも同様に整合させる。
- defect correctionではなく、product objectiveの進化に対する **successor追加** として扱う（#182のidentity規律: behavior変更は新ID）。

## Decision 3: Versioning and migration

- **bundle**: runtime-supported catalogへ `BOTTOM_REGION_V1` を追加し、ADR-0007 §8 / ADR-0012 に従い新semantic version `organization-policy-v2.7` としてpublishする（`rule-v2`・selection store schemaは不変。`POLICY_BUNDLE_VERSION` の履歴コメントを更新）。defaultは `CANONICAL_PAGE_COMPACT_V1` のまま。
- **selection store**: schema変更なし。既存の読みsource・validated write command・fail-closed契約はそのまま動く（write-time catalog validationが新IDを受け、generation/digestが更新される）。
- **downgrade**: 新IDを選択した状態で旧binary（v2.6以下のbundle）へ戻した場合、既存のselection-layer規則（spec 182 downgrade 3-case model）によりtyped `NotReady` でfail-closedになる。layout変更なし。re-upgradeで再検証・復帰。plan適用済みlayoutはrecovery contract（strategy非依存）で常に復旧可能。
- **diagnostics**: `RunEvent.APPROVED_VERSIONS` へ `BOTTOM_REGION_V1` を追加。strategy identityのecho（既存機構）が新semanticsのidentityを正しく運ぶ。
- **picker copy / preview**: 新strategyのlocalized name/description（EN/JA）を追加し、semantics（下部領域・上側余白・page増加の可能性・widget不動）と一致させる。previewは既存 `PreviewCounts`（`newPageCount` / `crossPageMovedCount` / `preservedByStrategyCount` 等）でpage増加・cross-page移動・strategy固定をそのまま可視化する。新projection種別は追加しない。

## Representative evaluation scenarios

以下を同一入力でstrategy比較可能なfixture/contract testとして固定する。#398本文の6シナリオへの対応:

1. **Reset / zero-layout + all apps（dense input）**: 空ホーム + 全アプリを対象に、`CANONICAL_PAGE_COMPACT_V1` / `GLOBAL_COMPACT_V2` / `BOTTOM_REGION_V1` を同一入力で実行し、3者のcanonical payloadが互いに異なり、`BOTTOM_REGION_V1` は (a) 全pageでmovable unitが下部優先領域の外へ配置されない (b) page数がcanonical以上になる (c) 上部余白が保持される、をassertする。identityが単なるitem order差へ退化しないことの主証拠。
2. **既存の疎な複数page**: 複数pageに疎に散在した状態で `BOTTOM_REGION_V1` を実行し、早期pageの下部領域から順に充填され（cross-page移動が発生し）、上段は常に空のまま保たれることをassert。compact intent（`GLOBAL_COMPACT_V2` は全面へ、`STABLE_PAGE_TIDY_V1` はpage内）との差を同一入力で比較。
3. **重要度personalizationあり**: (a) `CANONICAL_PAGE_COMPACT_V1` + `importance: HIGH` + `regionAffinity: BOTTOM` で、compact構造（page数増なし・全面活用）を保ちながら優先アプリが各pageの下部cellへ寄ること (b) `BOTTOM_REGION_V1` + `importance: HIGH` で、優先アプリがpage 1の最下段から配置されることをassert。「compact + priority appsを下へ」がpersonalization合成で表現できることの証拠。
4. **widgets / folders / locks / reservationsあり**: 上段のwidget、lockされたcell、reservation、既存folderが存在する状態で、sweepがそれらをoccupancy制約として尊重し、全不変条件（conservation/bounds/no-overlap/reference/lock/profile isolation）を維持すること。複数行span（領域内に収まるもの・超えるもの）の両方を含む。
5. **少数apps**: 少数アプリが1pageの最下段にのみ載ること（上部余白が明確な下寄せになる）。
6. **portrait / landscape / tablet / foldable / two-panel**: 領域行数が `DeviceCapabilities.rows` から決定的に導出され（hardcoded rowなし）、各form factorで下部優先領域semanticsが一貫すること。既存 `BottomFirstStrategyTest` のorientation matrixと同型のassert。

加えて、全strategy共通のharness契約（spec 11）と `CrossStrategyCorpusTest` が新strategyを自動巡回し、determinism / idempotence / conservation / bounds / overlap / lock / profile isolationを検証する。`StrategyPerformanceSampleTest` による性能sampleも自動対象である。

## Scope

- 本specの「Strategy objective (product principle)」の明文化と、そのrequirements.md（FR-016備考）への反映。
- `BOTTOM_REGION_V1` の内部seam実装（`StrategyDefinition` への下部領域宣言の追加、shared allocatorへの領域付きcaptured-then-new走査の追加、sweep executor、unit順序、fixed set、intent合成）。
- scope-composed candidate tailの領域遵守。
- bundle `organization-policy-v2.7` publish、selection store経由の選択、fail-closed/downgrade（既存機構の適用）。
- picker copy（EN/JA）、`BOTTOM_FIRST_V1`/`_V2` descriptionの明確化、diagnostics allowlist追加。
- representative evaluation scenarios（1〜6）のfixture/contract test。
- `CONTEXT.md`（下部優先領域）、requirements.md（FR-016備考）、spec 182との差分説明（change historyへのAmend参照は行わない。本specが正本）。
- 代表的なphysical-device before/preview/after evidenceの記録。

## Non-goals

- 既存strategy（`BOTTOM_FIRST_V1` を含む）のobservable semantics・test oracle・golden corpusの変更。
- widget移動（新strategyはwidget不動。widget対応は #235 のV2 widget successorsが担う領域）。
- page数を常に増やすこと自体を目的にする設計、またはpacking効率を目的に戻す設計。
- AIに最終座標やLauncher DB mutation authorityを渡すこと。personalization schema（#204系）の変更。
- #356系譜の情報設計・画面遷移の再変更、strategy pickerの配置変更（#368のmaterials面配置は不変）。
- page recordの物理削除policyの変更。
- widget resize、arbitrary user-script layout engine、combinational public toggles（catalogはcuratedのまま、ADR-0012）。
- region比率のUI設定化（領域はstrategy identityの一部であり、user調整対象にしない）。

## Behavior scenarios

### Scenario: dense inputでstrategy identityが残る

**Given** 空のホームと、十分な数のmovable app（folder形成が起こる規模を含む）、
**When** 同一入力で `CANONICAL_PAGE_COMPACT_V1`、`GLOBAL_COMPACT_V2`、`BOTTOM_REGION_V1` のfull runを実行する、
**Then** 3者のcanonical plan payloadは互いに異なり、
**And** `BOTTOM_REGION_V1` の全movable placementは各pageの下部優先領域内にあり、
**And** 各pageの上部余白が保持され、page数はcanonical以上である、
**And** materialized結果の再planで差分は空である。

### Scenario: 下部領域満タン後のoverflow

**Given** あるpageの下部優先領域が満たされ、さらにunitが残っている、
**When** 次のunitが配置される、
**Then** 上段ではなく次のpageの下部優先領域へ配置される（captured page群をPageOrder順に走査した後、新page），
**And** 上段へのmovable unit配置はplan全体のどこにも発生しない。

### Scenario: 複数行spanの扱い

**Given** 領域行数以下の高さのmovable unit `2×1` と、領域行数を超えるmovable unit `1×3`（rows=4のgrid等）が存在する、
**When** full runを実行する、
**Then** `2×1` は下部優先領域内にsweep配置され、
**And** `1×3` はcaptured位置に固定され `Preserved{STRATEGY_PRESERVED}` で報告され、
**And** 再planで両者の判断は不変である。

### Scenario: 既存の疎な複数page

**Given** 3つのpageにunitが疎に散在する、
**When** `BOTTOM_REGION_V1` のfull runを実行する、
**Then** page 1の下部領域、page 2の下部領域、…の順にsweep充填され、早期pageへcross-page移動が発生する、
**And** 全pageの上段は空のまま（fixed対象の保持を除く）である、
**And** 再planで差分は空である。

### Scenario: compact構造を保った優先アプリの下寄せ

**Given** `CANONICAL_PAGE_COMPACT_V1` 選択下で、intentが特定アプリへ `importance: HIGH` と `regionAffinity: BOTTOM` を与えている、
**When** full runを実行する、
**Then** page構成はcompactのまま（region構造には従わず）、優先アプリが各page下部のcellへ寄る、
**And** 下寄せstrategyの選択なしに「compact + 重要アプリを下へ」が表現されている。

### Scenario: 下寄せstrategy + importance

**Given** `BOTTOM_REGION_V1` 選択下で、intentが特定アプリへ `importance: HIGH` を与えている、
**When** full runを実行する、
**Then** 優先アプリがsweep streamの早期に消費され、page 1の最下段から配置される、
**And** 構造（領域・sweep順序・上段非使用）はintentによって変化しない。

### Scenario: fixed対象と領域の共存

**Given** 上段にwidget、中部にlockされたapp、下部にreservation領域がある、
**When** `BOTTOM_REGION_V1` のfull runを実行する、
**Then** sweepはこれらをoccupancy制約として尊重し、領域内の空きcellのみを使う、
**And** lock・widget・reservationの保持理由と位置は不変である、
**And** 全不変条件（spec 11契約）が満たされる。

### Scenario: 選択・downgrade・fail-closed

**Given** userがpicker（T-05 materials面）で `BOTTOM_REGION_V1` を選択する、
**When** write commandが実行される、
**Then** write-time catalog validationが成功し、generation/digest付きでatomicにpublishされ、次のrunのcomposer readで有効になる、
**And** 旧bundle（`organization-policy-v2.6`）のbinaryが当該selectionを読むとtyped `NotReady` でfail-closedし、layout・storeは不変である、
**And** re-upgrade後にselectionが再検証・復帰する。

### Scenario: scope-composed runのcandidate tail

**Given** `BOTTOM_REGION_V1` 選択下のscope-composed runで、領域内に収まらない高さのcandidateが選択されている、
**When** candidate tailが配置を試みる、
**Then** 当該candidateは `UnplacedReason.STRATEGY_SCOPE_FULL` でunplaced報告され、
**And** 領域外への配置は発生しない。

## Failure behavior

| Condition | Observable outcome |
|---|---|
| 領域内に配置できないmovable unit（span.height > 領域行数） | `STRATEGY_PRESERVED` でcaptured位置に固定（full run）。candidateは `STRATEGY_SCOPE_FULL` でunplaced |
| sweepで一時的な配置失敗 | 新page作成により必ず配置可能（領域行数 ≥ span.height のunitに対して新pageの領域は常に十分）。失敗はinjected faultのみで、既存のloud invariant failureとして扱う |
| selection storeに新IDがない旧binary | 既存のselection-layer `NotReady`（fail-closed、zero-write） |
| catalog外の `StrategyId` 直接構築（defense-in-depth） | 既存 `V-20 INVALID_RULES` → `Rejected.Invalid`（変更なし） |
| intent hintが領域外を指示 | 決定的にclip/無視。エラーにしない（preferenceはnon-authoritative） |

## Data and state

- plannerは純粋のまま。新strategyはI/O・状態・platform依存を追加しない。
- 新規永続化なし。selection store・recovery point・checkpoint・apply write-setはすべて既存のまま。bundle `organization-policy-v2.7` はapplication所有のimmutable artifact変更（in-place migrationなし、ADR-0007 §8）。
- Launcher DB schema変更なし。新page作成は既存の `NewPage` 機構を使用。
- layout安全規約（AGENTS.md）: 入力snapshotと適用時のrevision同一、全アイテムの保持/移動/明示的削除の説明可能性（新strategyのdispositionは既存理由語彙のみ）、lock配置不変、bounds/非重複/参照有効性、recovery point作成、transaction、適用後再検証——すべて既存spec 13/10/12契約の内側で満たされる。

## Permissions, privacy, and security

None — 新permission・network・telemetryは一切追加しない。diagnosticsは既存のversion identifier allowanceでstrategy identityを運ぶのみである。

## Accessibility and localization

- picker の新strategy名・説明はlocalized（`values/` + `values-ja/`）。 TalkBack（label/state/selection announcement）、Switch Access/keyboard traversal、200% font scalingはspec 52/195/368の既存期待を継承する。
- preview の結果计数（page増加・cross-page移動数・strategy固定数）は既存text表現で読み上げ可能であり、color-onlyではない。

## Acceptance criteria

- [ ] AC-1: Strategy objective（primary success metric = ユーザー意図との一致、数量指標の補助性、dense inputでのidentity維持要求）が本specで明文化され、requirements.md FR-016備考へ反映されている。
- [ ] AC-2: `BOTTOM_REGION_V1` のobservable semantics（領域定義 `ceil(rows/2)`、sweep overflow順序、fixed set、unit順序、folder/widget policy）が本specのnormative rulesどおりに実装され、専用contract testで検証されている。
- [ ] AC-3: dense input（reset/zero + all apps）で `CANONICAL_PAGE_COMPACT_V1` / `GLOBAL_COMPACT_V2` / `BOTTOM_REGION_V1` のcanonical payloadが互いに異なり、下部領域semantics（領域外非配置・上部余白保持・page数 ≥ canonical）がassertされている。
- [ ] AC-4: 既存strategyのsemantics・golden corpus・selection store契約が無変更のまま全契約testを通過する（regression保証）。
- [ ] AC-5: idempotence / determinism / conservation / bounds / overlap / lock / profile isolationが、既存harnessと `CrossStrategyCorpusTest`（registry駆動、新strategy自動対象）で検証されている。
- [ ] AC-6: intent合成matrix（page affinity inert、TOP/MIDDLE inert、minimizeMovement不消費、preserve clip、BOTTOM/importance bias）がcontract testで検証され、`IntentPreferenceStrategyMatrixTest` が新strategyを含めてgreenである。
- [ ] AC-7: bundle `organization-policy-v2.7` がpublishされ、catalog coherence（`runtimeSupported` == 実装済みregistry IDs、default ∈ runtimeSupported）が `BuiltInOrganizerPolicyBundleSourceTest` で検証されている。
- [ ] AC-8: selection書込み・読取・fail-closed・downgrade（旧bundleでの `NotReady`）が `LayoutStrategySelectionStoreTest` 等で検証されている。
- [ ] AC-9: picker copy（EN/JA）とpreview表示（`newPageCount` / `crossPageMovedCount` / `preservedByStrategyCount`）が新semanticsと一致し、`BOTTOM_FIRST_V1`/`_V2` descriptionの明確化がsemantics変更なしに行われている。
- [ ] AC-10: diagnosticsのstrategy identity echoが `BOTTOM_REGION_V1` で機能する（`APPROVED_VERSIONS` 追加、run journalへの記録）。
- [ ] AC-11: portrait / landscape / tablet / foldable / two-panelで領域が `DeviceCapabilities.rows` から決定的に導出され、hardcoded rowが存在しないことがtestで検証されている。
- [ ] AC-12: representativeなphysical-device before/preview/after evidenceが記録されている。ハードウェア都合で取得できない場合は、#351/#345の前例に従い明示的なevidence追跡Issueが同一PRで起票され、本ACはそのIssueへ委譲される。
- [ ] AC-13: 高リスクPR要件（`final-status` CI成功 + `docs/assessment/` 独立監査記録）が満たされている。

## Test oracle

| AC | Evidence |
|---|---|
| AC-1 | 本spec受入 + requirements.md diff |
| AC-2 | `BottomRegionStrategyTest`（新規、public seam） |
| AC-3 | 同上（dense fixture、3strategy比較assert） |
| AC-4 | `GoldenOracleCorpusTest`、既存strategy test群の無変更green |
| AC-5 | `PlannerContractHarness` 系、`CrossStrategyCorpusTest`、`PlannerGeneratedPropertyTest` |
| AC-6 | `IntentPreferenceStrategyMatrixTest`、`WidgetIntentAuthorityTest` |
| AC-7 | `BuiltInOrganizerPolicyBundleSourceTest` |
| AC-8 | `LayoutStrategySelectionStoreTest` |
| AC-9 | picker instrumentation test / strings確認 |
| AC-10 | diagnostics model test |
| AC-11 | `BottomRegionStrategyTest` orientation matrix |
| AC-12 | evidence artifact またはevidence追跡Issue |
| AC-13 | `final-status` run URL + `docs/assessment/pr-<番号>-<slug>.md` |

## Open questions

受入時点で未決のproduct判断は存在しない。領域比率・overflow順序は「候補比較と決定」節で決定済み。`BOTTOM_FIRST_V1` の扱いは維持（copy明確化のみ）で決定済み。evidence取得のハードウェア依存のみAC-12に明示的な委譲条件を設けている（前例 #351/#345 と同一の扱い）。

## Change history

- 2026-09-23: Draft created for #398。strategy objective明文化、`BOTTOM_REGION_V1` 提案、`BOTTOM_FIRST_V1` 維持判断、bundle v2.7、intent合成matrix、代表評価scenario群。

## References

- [Issue #398](https://github.com/nunu1733/NunuLauncher/issues/398)
- [Spec 182: layout strategy catalog](../182-layout-strategy-catalog/spec.md)
- [Spec 237: GLOBAL_COMPACT_V2 folder relocation](../237-global-compact-v2-folder-relocation/spec.md)
- [Spec 235: widget strategy placement](../235-widget-strategy-placement/spec.md)
- [Spec 204: AI personalization context/intent contract](../204-ai-personalization-context-intent-contract/spec.md)
- [Spec 228: organizer missing-app selection](../228-organizer-missing-app-selection/spec.md)
- [Spec 368: strategy picker material relocation](../368-strategy-picker-material-relocation/spec.md)
- [Spec 194: plan preview seam](../194-plan-preview-seam/spec.md)
- [ADR-0007: authoritative organization policy sources](../../docs/adr/0007-authoritative-organization-policy-sources.md)
- [ADR-0012: versioned layout strategy catalog](../../docs/adr/0012-versioned-layout-strategy-catalog.md)
- [Issue #356: Organizer全体TO-BE再設計（closed）](https://github.com/nunu1733/NunuLauncher/issues/356)
- [AGENTS.md](../../AGENTS.md), [DESIGN.md](../../DESIGN.md), [CONTEXT.md](../../CONTEXT.md)
