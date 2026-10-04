---
issue: "#398"
status: implemented
requirements: [FR-016]
risk:
  - layout-data
  - migration
updated: 2026-09-23
---

# Organizer strategyをユーザー意図一致優先で再評価し、下部領域semanticsのsuccessor strategy `BOTTOM_REGION_V1` を追加する

本specは strategy catalog のproduct objectiveの明文化と、下寄せ空間構成の新successor strategy 1件の追加だけを対象とし、既存strategyのobservable semanticsは1つも変更しない。

## Problem

#182 でversioned catalogとして整備されたlayout strategyのうち、`BOTTOM_FIRST_V1` は `CANONICAL_PAGE_COMPACT_V1` とfolder/unit/page policyが同一で、cell traversalだけをbottom-upに変えた「各ページを下段から順に配置する」設計である（spec 182）。このため次の代表入力ではstrategy間の差が大きく退化する。

1. Home配置をリセットして実質ゼロ状態にする
2. アプリ追加で全アプリを対象にする（dense input）
3. Organizerで整理する

full pageはどのtraversalでも埋まるため、`CANONICAL_PAGE_COMPACT_V1` は上側から、`BOTTOM_FIRST_V1` は下側から、`GLOBAL_COMPACT` 系はcross-pageで埋め、ユーザーから見た主な差が「ページ数・密度はほぼ同じで、アプリ順序と最終ページの寄り方が多少違う」程度まで縮小する。これはedge caseではなく、**strategyが何を最適化すべきかというproduct objectiveの再定義**を要求する兆候である。

#356系譜（Organizer全体TO-BE再設計とそのbacklog #365〜#377）は2026-09-23時点で全てclosedであり、本Issueのscheduling/dependency gate（#356のexit criteria充足 + #365〜#377完了 + 派生必須修正の完了。#398本文）を満たしている。派生follow-up不在の再現可能な確認は [plan.md Current evidence](./plan.md) に記録した。本specはその後のlayout-strategy再評価として、#356系譜のTO-BE情報設計・run構造を先取りして固定しない。

## Outcome

1. **Strategy objectiveの明文化**: built-in layout strategyのprimary success metricは「ユーザーが選んだ/表明したHome構成意図（strategy identityのobservable空間semantics）との一致」である。page count / folder count / occupied-cell density / move count等の数量指標は、意図一致を損なわない範囲の補助指標・tie-breakerであり、意図のvisual identityを消すために使わない。この優先関係を本specが定義し、将来のcatalog追加・改訂の評価基準とする。
2. **新successor strategy `BOTTOM_REGION_V1` の追加**: 各pageの **下部優先領域 (lower preferred region)** を主な配置領域とし、領域が満タンでも上段を埋めずに次pageの下部領域へoverflowする（page数増加・上部余白を正当な結果として許容する）strategy。dense inputでもvisual identityが単なるitem order差へ退化しない。cross-page moverはADR-0012決定4のaccepted制約に従い `1×1` に制限する（「Decision 1」）。
3. **`BOTTOM_FIRST_V1` のversioning判断**: semanticsは無変更のままcatalogに維持する（「Decision 2」）。picker copyは実挙動により正確に合わせる（copy-only、semantics変更ではない）。
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
| Eligible movable units | movable `1×1` な `APPLICATION`/`DEEP_SHORTCUT` singletonのみ（`GLOBAL_COMPACT_V1` と同一の制限。根拠は下記idempotence節） |
| Fixed set（strategy固定） | movableな非 `1×1` unit（`2×1`/`1×2`等）と全既存folder unitはcaptured位置に固定し `STRATEGY_PRESERVED`。自然保持対象（lock、reservation、dock、widget、app pair、legacy shortcut、unavailable等）は既存優先順位の理由で固定（変更なし） |
| Folder policy | canonical P-04/P-05 grouping を **eligible `1×1` candidates のみ** に適用（既存folderはformation候補から除外）。形成されたfolderはcompacting unitsの **後** に `(preferred page key, NewFolderOrdinal)` 順で配置（`GLOBAL_COMPACT_V1` と同一） |
| Unit order | 逆captured visual順 `(PageOrder, PageId, cell.y DESC, cell.x, ItemId)` をbaseに、intent preference bias（identity-stable class keys: grouping > importance > `regionAffinity: BOTTOM`）を上位keyとしてlayer（下記合成規則）。catalog宣言は新enum値 `CAPTURED_VISUAL_GLOBAL_REVERSED` としてsemanticsをregistry dataに載せる |
| Page scope | 下部領域sweep（captured page群を `PageOrder` 順、次に作成済み新page群、最後に新page作成） |
| Cell traversal | 下部優先領域内でbottom-up row-major |
| Widget policy | null（widgetは移動しない。canonical族・GLOBAL_COMPACT族と同一の保持扱い `PreserveReason.WIDGET`） |

`GLOBAL_COMPACT_V1` との差は次の3点に限定される: (a) cell候補を下部優先領域に制限 (b) 走査・消費順序がbottom-up（逆visual順） (c) 上段を一切使わずpage成長を許容する。既存 `1×1` folderをstreamへ入れる `GLOBAL_COMPACT_V2` の拡張（spec 237）は採用しない（証明対象を最小に保つため。必要になれば別IDで検討）。

### 下部優先領域の定義

`rows` を当該runの `DeviceCapabilities.rows` とするとき、下部優先領域の行数は:

```text
lowerRegionRows(rows) = (rows + 1) / 2   // ceil(rows/2)
lowerRegion = (rows - lowerRegionRows(rows)) until rows
```

候補比較（rows=3/4/5/6の実例で比較。決定の根拠）:

| 案 | 定義 | rows=3 | rows=4 | rows=5 | rows=6 | 評価 |
|---|---|---|---|---|---|---|
| 下1/3（intent BOTTOM band `((2*rows)/3) until rows` と同一） | 3等分の下带 | 1行 | 2行 | 2行 | 2行 | rows=3で1行と主領域として機能しない。行数に対する比率が整数除算で非一様（67%/50%/40%/33%） |
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

### Idempotence argument（ADR-0012決定4の要求水準・本strategy固有の完全証明）

cross-page moverを `1×1` に制限する理由: ADR-0012決定4（およびGLOBAL_COMPACT_V1の受入経緯、spec 182）により、captured位置順のcross-page戦略はheterogeneous span + fragmented fixed occupancy下でfirst-fitがvisual sequenceを並べ替えINV-8を破るため、cross-page moverは `1×1` に制限され、それ以外は `STRATEGY_PRESERVED` 固定であることがaccepted制約である。本strategyはこの制約をそのまま継承する。

本strategyは「形成folderが次runでfixed化する」点でspec 237（材料化folderが次回mover streamへ再参加する形状）と異なるため、証明も本strategy固有の形で行う。 **intent preferenceはspec 204どおりsoftなordering/preferenceとして消費される** — `preserve` は領域内captured-cell hint（順番時に空きのときのみ使用、不成立時はpreserveなしと同一のsweep配置）、他のfieldはidentity-stableな消費順序classとして扱う。

1. **Base fixed setの不変**: fixed set F は (a) 自然保持対象（`determinePreservation`、入力状態の決定的関数。intentはこれを変更しない）、(b) 非 `1×1` movable unit、(c) **run開始以前からcapturedに存在するfolder** からなる。F はmovable `1×1` unitの再配置を含まないため、run間で不変である。
2. **形成folderの二重性**: run 1のformationで形成されたfolderのmembersはfolder member配置へ移るため、run 2のmovable streamから消える（eligible unitはtop-level `1×1` app/shortcutのみ。preserve hintはformation候補の扱いを変えない — canonical族と同一）。形成folderはrun 2ではF' = F ∪ {形成folder群（各自の配置cell）} として **fixed occupancyとしてのみ** 再参加する。formation候補から既存folderは除外されるため、run 2で新たなformationは起こらず（残留singleton candidate群は `minGroupSize` 未満。spec 237と同一の論法）、F' で固定である。
3. **消費順序の安定性（class順のみがidentity-stable）**: sweep対象streamの消費順序は `(bias class keys（grouping > importance > BOTTOM-affinity。いずれもidentity-basedでintentなしでは定数）, PageOrder, PageId, y DESC, x ASC, ItemId)` であり、**class間の順序だけがrun間で不変である**。soft hintはunitの最終cellを前後させ得るため、同class内の処理順はrun 1のcaptured位置順ではなく **materialized状態の位置順** として読み直される（`σ₂ = σ₁` は主張しない）。下寄せfallback（first-fit）は「F を除いたglobal bottom-up領域順（captured pages → 新pages、各pageの領域内bottom-up）でk番目の空きcell」を載せる単調走査であり、この性質はrun 1・run 2で同一である。
4. **allocation cellの帰納的再生（class順を不変条件とする）**: 保存されるのは成否でもstream順でもなく **各unitの最終allocation cell** である。σ₂（= class順 + class内materialized位置順）の順に処理しながら、「σ₂の先行unit群は各自のrun 1最終cellを回収済み」を帰納法で仮定して各unitを検証する:
   - **preserve unit**: run 2のhint cellはrun 1の最終cell p（hint成功cellは領域内である）。pはunit自身のrun 1 cellであり他unit・形成folder cellと非重複、Fも同一であるため、その順番時に空きであり、hint成功でpを回収する。
   - **non-preserve unit（fallback）**: run 2のfirst-fitがp（run 1のfallback cell）を返すことを示す。pより前方のcellはすべてrun 2のその順番時点で占有である: (i) F分と形成folder cell分 — 後者はunit phase終了時未使用cell（5）であり、unit phase中ずっと空きだったcellがpより前方にあればrun 1のfallbackがpの代わりに取得していたはずで矛盾、(ii) 先行unitのcell分 — pより前方のcellを最終cellとするunitは必ずσ₂でuに先行する（同class内ならmaterialized位置順そのもの、前classならclass順、後classのunitはそのような前方cellを持ち得ない: 後classのfallback unitはu配置後のfirst-fitで前方の占有済みcellを取れず、後classのhint成功unitは「uの順番時点で前方cellが空きならuが先に取得していたはず」という同じ論法で前方cellを最終cellとし得ない）。よってrun 2でもfirst-fitはpを返す。
   - **実行順の変化（σ₂ ≠ σ₁）は許容される**: 例えば同classで preserve=true unit A（captured c2）、非preserve unit B（captured c3）、空きc1 < c2 < c3 では、run 1はA→Bの順でA@c2・B@c1となり、materialized位置順によりrun 2はB→Aの順で処理される。それでもBはc1、Aはc2を回収しassignment不変である（AC-5専用fixtureで直接固定）。
5. **形成folder cellの扱い（後方性を使わない）**: 形成folderのcellは「run 1のunit phase終了時に未使用だったcell」から `(preferred page key, NewFolderOrdinal)` 順に取得される。したがって形成folder cellは任意のunitの最終cellと非重複であり、F' = F ∪ {形成folder cell群} への追加は任意のunitのhint cellを占拠しない。よってstep 4の帰納法は形成folderのcell位置の幾何（前方・後方）に依存せず成立し、replanでは形成folder自身が自身のcellをfixed保持する。
6. **帰結（空差分とdisplacement非悪化）**: 1〜5より、materialized状態のrecapture/replanで全unit（および形成folder）が自身のcellを回収し差分は空になる。さらにpreserve hintは「成功時displacement 0、不成立時はpreserveなしと同一配置」であるため、同一入力のpreserveなし版と比較してdisplacement（page-crossing・slot-distance）が悪化することはない。

実行可能証明として、既存harnessのIDEMPOTENCE/DETERMINISM/CONSERVATION等の全契約が新strategyを含むregistry駆動testを通過すること（`CrossStrategyCorpusTest` は `acceptedIds` を自動巡回する）に加え、**証明が扱う要素を同一fixtureに同居させた専用counterexample fixture** — 複数既存folder + 新folder形成（`minGroupSize` 以上の `1×1` candidate群）+ `2×1`/`1×2` movable + fragmented lock/reservation + 複数page + intent bias（`preserve=true`（領域内・領域外のcaptured cellを含む）/ `importance: HIGH` / `regionAffinity: BOTTOM`、および `minimizeMovement=true` のcase）— で、次を直接固定する（spec 237の専用fixtureと同趣旨。shared suiteが通らない状態遷移を直接踏む）:

- 適用→recapture→replanの空差分。
- **run 1 hint不成立→run 2 hint成功でも空差分** のcase: 領域内captured cellが先行unitに占有されてrun 1のhintが不成立→fallback配置され、materialize後のrun 2では新captured cellへのhintが成功する（成否が変わっても最終assignmentは不変であることの直接証拠）。
- **成功hintが前方holeを残し、そのholeへ形成folderが配置される** case: 後方cellへのhint成功により前方に空きholeが残り、unit phase後に形成folderがそのholeへ配置される（step 5の「後方性に依存しない」ことの直接証拠）。これらを含めてreplan空差分をassertする。
- **同class内の実行順が反転しても空差分** のcase: 同一bias classで preserve=true unit A（captured c2）と非preserve unit B（captured c3）、空き c1 < c2 < c3 に対し、run 1はA→B（A@c2、B@c1）、materialized後のrun 2はB→Aの順で処理されるが、B@c1・A@c2を回収し空差分である（「stream順が変わってもfixed point」であることの直接証拠）。
- preserve hint成功unitがcaptured位置に留まること、および同一入力のpreserveなし版との比較でdisplacementが悪化しないこと。

対比として、順順captured visual順（y昇順）をbottom-up充填に使うことは採用しない（visual読み出し順と消費順が逆になりmaterialized状態が消費順序を復元せず、replanで回転する）。

### Scope-composed run（#228 candidate tail）

`pageScope` としては既存 `CAPTURED_THEN_NEW` を宣言した上で、candidate tailは **下部優先領域内に限って** `CAPTURED_THEN_NEW` 相当の走査を行う。領域内に配置できないcandidate（非 `1×1` candidateを含む）は `UnplacedReason.STRATEGY_SCOPE_FULL` でunplaced報告される（candidateにはcaptured位置が存在しないため固定はできない）。full-run phaseのsemanticsは既存のとおりstrategyのfull-run executorへ委譲される。

### AI personalizationとの合成規則

authority分離（#204契約の再確認と、strategy軸での明示化）:

- **strategyは空間構造を決める**: 領域幾何、sweep順序、page成長、上段非配置。intentはこの構造を緩めない。AIがraw座標・最終 `(page,x,y)` をauthoritativeに決めない既存契約（#204 `FORBIDDEN_CONTENT`）は不変であり、下寄せstrategyの選択・構造をintentが強制・無効化する経路も存在しない。
- **preferenceはspec 204どおりsoftなordering/preferenceとして消費され、保持判断（`determinePreservation`）は変更しない**: `preserve` は **soft captured-cell hint**（canonical族・GLOBAL_COMPACT族の `preferenceCellHint` と同一契約）として消費する — captured cellが下部優先領域内かつunitの割当順番時に空きのときだけ正確にそのcellを使い、それ以外はpreserveなしと同一のsweep配置へフォールバックする。したがってpreserve指定が同一入力のpreserveなし版と比較してdisplacementを悪化させることはない（現行 `preferenceCellHint` の「can never worsen the item's displacement」契約の充足）。領域外captured cellへのhintは構造authorityとして決定的に無視する（fallbackはsweep）。他のfield（grouping > importance > `regionAffinity: BOTTOM`）はidentity-stableな消費順序classとして消費される。いずれのbias keyもintentなしでは定数（既存runに影響しない）。

合成matrix（normative）:

| intent field | `CANONICAL` 族（TOP_LEFT系） | `GLOBAL_COMPACT` 族（TOP_LEFT sweep） | `BOTTOM_REGION_V1` |
|---|---|---|---|
| `importance` | 順序bias（既存どおり） | 順序bias（既存どおり） | 消費順序bias（早い= page 1の最下段等、より良い領域cell） |
| `desiredGroup`/`groupSemantic` | folder形成key（既存どおり） | 形成key（eligible candidatesのみ） | 同一（canonical groupingをeligible `1×1` candidatesに適用、形成folderはunitsの後にsweep配置） |
| `pageAffinity` | preferred page bias（既存どおり） | inert（sweepは消費しない） | inert（同左。`GLOBAL_COMPACT_V1` と同一の先例） |
| `regionAffinity` = `BOTTOM` | 既存どおりordering bias | 既存どおり | **消費順序biasのみ**（より早い消費=より低いcell）。page局所allocation hintは持たない（隠れたpage affinityを混入させない） |
| `regionAffinity` = `TOP`/`MIDDLE` | 既存どおりordering bias | 既存どおり | inert（上段配置なしでは充足不可能。決定的に無視する） |
| `preserve`（per-item） | captured cell hint（既存どおり） | page-local hint（既存どおり） | **soft captured-cell hint（既存契約と同一）**: captured cellが下部優先領域内かつ割当順番時に空きのときだけ正確に使用、それ以外はpreserveなしと同一のsweep配置へフォールバック。同一入力のpreserveなし版と比較してdisplacementが悪化しない（spec 204「移動最小化bias」契約の充足）。領域外captured cellへのhintは決定的に無視（fallbackはsweep） |
| `globalPreference.minimizeMovement` | singleton順序をcaptured visual順へ（既存どおり） | 順序が既にcaptured visual順のため実質内包 | **base順序の採用として消費**（本strategyのbase順序はcaptured位置順=移動最小化順であり、GLOBAL_COMPACT族と同一の扱い）。canonicalのidentity順への切替は行わない |

この合成により、#398の要求「compact + 重要アプリを下へ」は、既存の `CANONICAL_PAGE_COMPACT_V1`（または `STABLE_PAGE_TIDY_V1` 等page内compaction）+ intent `importance: HIGH` + `regionAffinity: BOTTOM` の組合せで、compact構造を保ったまま表現できる。下寄せstrategyの選択は強制しない。専用fixtureで固定する（evaluation scenario 3）。

## Decision 2: `BOTTOM_FIRST_V1` のdisposition

- **維持（retireしない・非表示にしない）**: `BOTTOM_FIRST_V1` のaccepted semantics（spec 182: canonical族policy + bottom-up traversal）はそのまま正しく、versioned identity規律（ADR-0012）により無変更で維持する。既存selection・既存golden corpus・既存test oracleは不変。
- **役割の明確化（copy-only）**: descriptionを **実挙動に正確に** 合わせる。V1は「app/folderを下段から順に配置し、widgetは移動しない」こと、V2はspec 235のaccepted semanticsどおり「appを下側へ、移動可能widgetを上側（top-anchored）へ配置する」ことを表現する。充填の完全性（「すべて埋める」等）はfixed occupancyの存在で一般に保証されないため、保証語として使わない。semantics変更ではなく説明の修正である。EN/JA双方でID→copyの対応をtestし、spec 235のpicker copy受入（AC-9）で固定された「app領域とwidget領域の関係」の記述を退行させない。
- defect correctionではなく、product objectiveの進化に対する **successor追加** として扱う（#182のidentity規律: behavior変更は新ID）。

## Decision 3: Versioning and migration

- **bundle**: runtime-supported catalogへ `BOTTOM_REGION_V1` を追加し、ADR-0007 §8 / ADR-0012 に従い新semantic version `organization-policy-v2.7` としてpublishする（`rule-v2`・selection store schemaは不変。`POLICY_BUNDLE_VERSION` の履歴コメントを更新）。defaultは `CANONICAL_PAGE_COMPACT_V1` のまま。
- **selection store**: schema変更なし。既存の読みsource・validated write command・fail-closed契約はそのまま動く（write-time catalog validationが新IDを受け、generation/digestが更新される）。
- **downgrade**: 新IDを選択した状態で旧binary（v2.6以下のbundle）へ戻した場合、既存のselection-layer規則（spec 182 downgrade 3-case model）によりtyped `NotReady` でfail-closedになる。layout変更なし。re-upgradeで再検証・復帰。plan適用済みlayoutはrecovery contract（strategy非依存）で常に復旧可能。
- **diagnostics**: `RunEvent.APPROVED_VERSIONS` へ `BOTTOM_REGION_V1` を追加。strategy identityのecho（既存機構）が新semanticsのidentityを正しく運ぶ。それ以上のdiagnostics拡張はしない（既存privacy契約どおり）。
- **picker copy / preview**: 新strategyのlocalized name/description（EN/JA）を追加し、semantics（下部領域・上側余白・page増加の可能性・widget不動）と一致させる。previewの **空間semantics検証** は既存projectionで可能である: `PreviewChange` の `MoveChange.destination` / `PreservedChange.current` / `AddChange` は `PreviewPosition`（`pageDisplayOrdinal`、`rowBand`、`rowOrdinal` 等）を運ぶため、preview projection levelの契約testで「movable宛先が下部優先領域内（`rowOrdinal` が領域行内）/ 上部領域へのmovable宛先0件」を直接assertする（「Test oracle」）。新projection種別は追加しない。

## Representative evaluation scenarios

以下を同一入力でstrategy比較可能なfixture/contract testとして固定する。#398本文の6シナリオへの対応:

1. **Reset / zero-layout + all apps（dense input）**: 空ホーム + 全アプリを対象に、`CANONICAL_PAGE_COMPACT_V1` / `GLOBAL_COMPACT_V2` / `BOTTOM_REGION_V1` を同一入力で実行し、3者のcanonical payloadが互いに異なり、`BOTTOM_REGION_V1` は (a) movable placement（`Moved`/新folder配置）が全pageで下部優先領域の外へ出ない (b) page数がcanonical以上になる (c) 上部余白が保持される、をassertする。identityが単なるitem order差へ退化しないことの主証拠。
2. **既存の疎な複数page**: 複数pageに疎に散在した状態で `BOTTOM_REGION_V1` を実行し、早期pageの下部領域から順に充填され（cross-page移動が発生し）、上段は常に空のまま保たれることをassert。compact intent（`GLOBAL_COMPACT_V2` は全面へ、`STABLE_PAGE_TIDY_V1` はpage内）との差を同一入力で比較。
3. **重要度personalizationあり**: (a) `CANONICAL_PAGE_COMPACT_V1` + `importance: HIGH` + `regionAffinity: BOTTOM` で、compact構造（page数増なし・全面活用）を保ちながら優先アプリが各pageの下部cellへ寄ること (b) `BOTTOM_REGION_V1` + `importance: HIGH` で、優先アプリがpage 1の最下段から配置されることをassert。加えて (c) page 3のcaptured itemへ `regionAffinity: BOTTOM` を与え、page 1の下部領域に空きがある状態で当該itemがpage 1の領域へ移動すること（BOTTOM affinityがcaptured pageを優先する隠れたpage affinityとして作用 **しない** こと）をassertする。
4. **widgets / folders / locks / reservationsあり**: 上段のwidget、lockされたcell、reservation、 **複数の既存folder** が存在する状態で、sweepがそれらをoccupancy制約として尊重し、全不変条件（conservation/bounds/no-overlap/reference/lock/profile isolation）を維持すること。加えてidempotence節の専用counterexample fixture（複数既存folder + 複数新folder + `2×1`/`1×2` movable + fragmented lock/reservation + 複数page）を含む。
5. **少数apps**: 少数アプリが1pageの最下段にのみ載ること（上部余白が明確な下寄せになる）。
6. **portrait / landscape / tablet / foldable / two-panel**: 領域行数が `DeviceCapabilities.rows` から決定的に導出され（hardcoded rowなし）、各form factorで下部優先領域semanticsが一貫すること。既存 `BottomFirstStrategyTest` のorientation matrixと同型のassert。

加えて、全strategy共通のharness契約（spec 11）と `CrossStrategyCorpusTest` が新strategyを自動巡回し、determinism / idempotence / conservation / bounds / overlap / lock / profile isolationを検証する（registry駆動自動対象に加え、上記4の専用fixtureで状態遷移を直接踏む）。`StrategyPerformanceSampleTest` による性能sampleも自動対象である。

## Scope

- 本specの「Strategy objective (product principle)」の明文化と、そのrequirements.md（FR-016備考）への反映。
- `BOTTOM_REGION_V1` の内部seam実装（`StrategyDefinition` への下部領域宣言の追加、shared allocatorへの領域付きcaptured-then-new走査の追加、sweep executor、逆visual順序、fixed set、intent合成）。
- scope-composed candidate tailの領域遵守。
- bundle `organization-policy-v2.7` publish、selection store経由の選択、fail-closed/downgrade（既存機構の適用）。
- picker copy（EN/JA）、`BOTTOM_FIRST_V1`/`_V2` descriptionの実挙動への一致修正、diagnostics allowlist追加。
- representative evaluation scenarios（1〜6）のfixture/contract test、preview projection levelの空間semantics oracle。
- `CONTEXT.md`（下部優先領域）、requirements.md（FR-016備考）。
- representative physical-device before/preview/after evidence（AC-12。Issue本文どおりblocking）。

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
**Then** 上段ではなく次のpageの下部優先領域へ配置される（captured page群をPageOrder順に走査した後、新page）、
**And** 上段へのmovable unit配置はplan全体のどこにも発生しない。

### Scenario: heterogeneous spanの扱い（GLOBAL_COMPACT_V1 counterexampleの鏡像）

**Given** fragmented fixed occupancy（lock等）と、movable `2×1`、movable `1×2`、movable `1×1` が混在する、
**When** full runを実行する、
**Then** `2×1` と `1×2` はcaptured位置に固定され `Preserved{STRATEGY_PRESERVED}` で報告され、
**And** `1×1` のみが下部優先領域へsweep配置され、
**And** materialized結果の再planで差分は空である（spec 182記載のINV-8 violation counterexampleは本strategyでは発生しない）。

### Scenario: 既存folderと新folderの共存（専用counterexample fixture）

**Given** 複数の既存folder unit、新folder形成が起こる `1×1` candidate群、fragmented lock/reservation、複数page、およびintent bias対象item群（`preserve=true`（領域内・領域外のcaptured cellを含む）、`importance: HIGH`、`regionAffinity: BOTTOM`）と `minimizeMovement=true` のcaseが存在する、
**When** full runを実行し、materialized結果をrecaptureして再planする、
**Then** 既存folderは全て `Preserved{STRATEGY_PRESERVED}` でcaptured位置に留まり、
**And** hint成功itemはcaptured位置に留まり、
**And** 形成済みfolderは再形成されず残留candidateはsingletonのまま残り、
**And** run 1でhint不成立→fallbackしたunitはrun 2で新captured cellへのhint成功となっても同一cellに留まり、
**And** 再planの差分は空である（idempotence節の6段証明の実行可能証拠）。

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
**Then** 優先アプリが消費順序の早期に置かれ、page 1の最下段から配置される、
**And** 構造（領域・sweep順序・上段非使用）はintentによって変化しない。

### Scenario: BOTTOM affinityはpage affinityとして作用しない

**Given** page 3のcaptured `1×1` itemへ `regionAffinity: BOTTOM` を与え、page 1の下部領域に空きがある、
**When** `BOTTOM_REGION_V1` のfull runを実行する、
**Then** 当該itemはpage 3に留まらず、sweep順でpage 1の下部領域へ移動する、
**And** `regionAffinity: BOTTOM` は消費順序biasとしてのみ働き、page局所allocation hintとしては働かない。

### Scenario: preserve hintはsoftでありdisplacementを悪化させない

**Given** `BOTTOM_REGION_V1` 選択下で、page 2の下部領域にcapturedされた `1×1` itemへ `preserve: true` を与え、page 1の下部領域に空きがある、
**When** 同一入力でpreserveあり・なしのfull runを比較する、
**Then** preserveあり版では当該itemは空いているcaptured cellへ正確に配置され（領域内であるためhint成功、displacement 0）、
**And** 対照として、captured cellが領域外のitemへのpreserve hintは無視され、preserveなしと同一のsweep配置になる、
**And** いずれの場合もpreserve指定によってdisplacement（page-crossing・slot-distance）が悪化することはなく、
**And** materialized結果の再planで差分は空である。

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

**Given** `BOTTOM_REGION_V1` 選択下のscope-composed runで、非 `1×1` のcandidateが選択されている、
**When** candidate tailが配置を試みる、
**Then** 当該candidateは `UnplacedReason.STRATEGY_SCOPE_FULL` でunplaced報告され、
**And** 領域外への配置は発生しない。

## Failure behavior

| Condition | Observable outcome |
|---|---|
| sweepで一時的な配置失敗 | 新page作成により必ず配置可能（eligible unitは `1×1`、新pageの領域行数はrows≥3で2行以上）。失敗はinjected faultのみで、既存のloud invariant failureとして扱う |
| selection storeに新IDがない旧binary | 既存のselection-layer `NotReady`（fail-closed、zero-write） |
| catalog外の `StrategyId` 直接構築（defense-in-depth） | 既存 `V-20 INVALID_RULES` → `Rejected.Invalid`（変更なし） |
| intent hintが本strategyで充足不可能（`TOP`/`MIDDLE` affinity、`pageAffinity`） | 決定的にinert（消費順序bias以外では無視）。エラー・warningにしない（preferenceはnon-authoritative） |

## Data and state

- plannerは純粋のまま。新strategyはI/O・状態・platform依存を追加しない。
- 新規永続化なし。selection store・recovery point・checkpoint・apply write-setはすべて既存のまま。bundle `organization-policy-v2.7` はapplication所有のimmutable artifact変更（in-place migrationなし、ADR-0007 §8）。
- Launcher DB schema変更なし。新page作成は既存の `NewPage` 機構を使用。
- layout安全規約（AGENTS.md）: 入力snapshotと適用時のrevision同一、全アイテムの保持/移動/明示的削除の説明可能性（新strategyのdispositionは既存理由語彙のみ）、lock配置不変、bounds/非重複/参照有効性、recovery point作成、transaction、適用後再検証——すべて既存spec 13/10/12契約の内側で満たされる。

## Permissions, privacy, and security

None — 新permission・network・telemetryは一切追加しない。diagnosticsは既存のversion identifier allowanceでstrategy identityを運ぶのみである。

## Accessibility and localization

- picker の新strategy名・説明はlocalized（`values/` + `values-ja/`）。TalkBack（label/state/selection announcement）、Switch Access/keyboard traversal、200% font scalingはspec 52/195/368の既存期待を継承する。
- preview の結果计数（page増加・cross-page移動数・strategy固定数）は既存text表現で読み上げ可能であり、color-onlyではない。空間semantics（下部領域）の検証はpreview projection契約testが担う（UI表現の領域語彙は既存band表現を流用）。

## Acceptance criteria

- [ ] AC-1: Strategy objective（primary success metric = ユーザー意図との一致、数量指標の補助性、dense inputでのidentity維持要求）が本specで明文化され、requirements.md FR-016備考へ反映されている。
- [ ] AC-2: `BOTTOM_REGION_V1` のobservable semantics（領域定義 `ceil(rows/2)`、sweep overflow順序、fixed set=非 `1×1`+既存folderの `STRATEGY_PRESERVED`、逆visual順序、folder形成units後配置、widget不動）が本specのnormative rulesどおりに実装され、専用contract testで検証されている。
- [ ] AC-3: dense input（reset/zero + all apps）で `CANONICAL_PAGE_COMPACT_V1` / `GLOBAL_COMPACT_V2` / `BOTTOM_REGION_V1` のcanonical payloadが互いに異なり、下部領域semantics（領域外非配置・上部余白保持・page数 ≥ canonical）がassertされている。
- [ ] AC-4: 既存strategyのsemantics・golden corpus・selection store契約が無変更のまま全契約testを通過する（regression保証）。
- [ ] AC-5: idempotence / determinism / conservation / bounds / overlap / lock / profile isolationが、既存harnessと `CrossStrategyCorpusTest`（registry駆動、新strategy自動対象）に加え、**複数既存folder + 新folder形成 + 非 `1×1` movable + fragmented lock/reservation + 複数page + intent bias item群 を同一fixtureに同居させた専用counterexample fixture** の適用→recapture→replan空差分testで検証されている。fixtureは **「run 1 hint不成立→fallback→run 2 hint成功でも空差分」case**、**「成功hintが前方holeを残しそのholeへ形成folderが配置される」case**、**「同class内の実行順が反転（A/B swap）しても空差分」case** を直接含む（spec 237前例どおり、shared suiteが通らない状態遷移を直接踏む）。
- [ ] AC-6: intent合成matrix（page affinity inert、TOP/MIDDLE inert、preserve=soft captured-cell hint（領域内clip・displacement非悪化比較を含む）、minimizeMovement=base順序の採用としての消費、BOTTOM/importance=消費順序bias）がcontract testで検証される。`IntentPreferenceStrategyMatrixTest` が新strategyを含めてgreenである。「BOTTOM affinityはpage affinityとして作用しない」「preserve hintはsoftでありdisplacementを悪化させない」scenarioのfixtureを含む。
- [ ] AC-7: bundle `organization-policy-v2.7` がpublishされ、catalog coherence（`runtimeSupported` == 実装済みregistry IDs、default ∈ runtimeSupported）が `BuiltInOrganizerPolicyBundleSourceTest` で検証されている。
- [ ] AC-8: selection書込み・読取・fail-closed・downgrade（旧bundleでの `NotReady`）が `LayoutStrategySelectionStoreTest` 等で検証されている。
- [ ] AC-9: picker copy（EN/JA）がID→copyのexact mapping testを持ち、新semanticsと一致する。`BOTTOM_FIRST_V1`/`_V2` descriptionの修正がsemantics変更なしに実挙動へ一致し、spec 235のpicker copy受入を退行させない。preview projection levelの空間semantics oracle（movable宛先の領域内assert・上部領域宛先0件assert・`newPageCount`/`crossPageMovedCount`/`preservedByStrategyCount` 计数）が検証されている。
- [ ] AC-10: diagnosticsのstrategy identity echoが `BOTTOM_REGION_V1` で機能する（`APPROVED_VERSIONS` 追加、run journalへの記録）。
- [ ] AC-11: portrait / landscape / tablet / foldable / two-panelで領域が `DeviceCapabilities.rows` から決定的に導出され、hardcoded rowが存在しないことがtestで検証されている。
- [ ] AC-12: representative physical-device before/preview/after evidenceが **mergeのblocking条件** である（Issue本文のexit criterionどおり）。物理端末での取得が不能な場合の唯一の代替は、**Issue #398本文のAcceptance criteriaを明示改訂したcommit/編集履歴 + owner decision comment** の両方を揃えることである。Issue上のコメント単独では代替にならず、spec/plan側の委譲や無言の緩和も行わない。本文が現状のままならAC-12は無条件blockingである。
- [ ] AC-13: 高リスクPR要件（`final-status` CI成功 + `docs/assessment/` 独立監査記録）が満たされている。

## Test oracle

| AC | Evidence |
|---|---|
| AC-1 | 本spec受入 + requirements.md diff |
| AC-2 | `BottomRegionStrategyTest`（新規、public seam。normative rules各項のfixture） |
| AC-3 | 同上（dense fixture、3strategy比較assert） |
| AC-4 | `GoldenOracleCorpusTest`、既存strategy test群の無変更green |
| AC-5 | `PlannerContractHarness` 系、`CrossStrategyCorpusTest`、専用counterexample fixture test |
| AC-6 | `IntentPreferenceStrategyMatrixTest`、`WidgetIntentAuthorityTest`、BOTTOM-affinity非page-affinity fixture |
| AC-7 | `BuiltInOrganizerPolicyBundleSourceTest` |
| AC-8 | `LayoutStrategySelectionStoreTest` |
| AC-9 | ID→copy exact mapping test、spec 235 picker oracle regression、preview projection空間oracle test（`PlanPreviewProjector` 経由、`PreviewPosition.rowOrdinal`/`rowBand` のassert） |
| AC-10 | diagnostics model test |
| AC-11 | `BottomRegionStrategyTest` orientation matrix |
| AC-12 | physical-device evidence artifact、または「Issue #398本文Acceptance criteriaの明示改訂（commit/編集履歴）+ owner decision comment」。コメント単独では代替不可 |
| AC-13 | `final-status` run URL + `docs/assessment/pr-<番号>-<slug>.md` |

## Open questions

受入時点で未決のproduct判断は存在しない。領域比率・overflow順序は「候補比較と決定」節で決定済み。`BOTTOM_FIRST_V1` の扱いは維持（copy明確化のみ）で決定済み。AC-12のphysical-device evidence取得手段（物理端末の確保、または「Issue本文改訂commit + owner decision comment」による明示変更）は実装PR段階で確定させる作業項目であり、specの判断としては確定済み（blocking維持、コメント単独の代替は不許可）。

## Change history

- 2026-09-23: Draft created for #398。strategy objective明文化、`BOTTOM_REGION_V1` 提案、`BOTTOM_FIRST_V1` 維持判断、bundle v2.7、intent合成matrix、代表評価scenario群。
- 2026-09-23: Review rev.2（ChatGPT Phase 1レビュー、[Issueコメント](https://github.com/nunu1733/NunuLauncher/issues/398#issuecomment-5780203252) を反映）。**高1**: idempotence証明をADR-0012決定4の要求水準へ引き上げ — cross-page moverを `1×1` に制限（非 `1×1`・既存folderは `STRATEGY_PRESERVED`）、unit順序を逆captured visual順へ変更、専用counterexample fixtureを要求（旧案のcanonical族unit順序・任意span・tall-span規則は取下げ）。**高2**: `regionAffinity=BOTTOM` のpage局所allocation hintを削除し消費順序biasのみに統一。**中1**: `BOTTOM_FIRST_V1`/`_V2` copy指針を実挙動一致へ修正（「全面充填」保証語の禁止、spec 235 semanticsの維持、ID→copy exact mapping test）。**中2**: preview oracleをcountsだけからpreview projection levelの空間semantics検証へ拡張。**中3**: AC-12をIssue本文どおりblockingに戻す。**低1**: 依存gate確認を再現可能な形でplanへ記録。
- 2026-09-23: Review rev.3（ChatGPT再レビュー、[Issueコメント](https://github.com/nunu1733/NunuLauncher/issues/398#issuecomment-5780606963) を反映）。**高**: idempotence証明を本strategy固有の5段構成へ書き直し（base fixed setと形成folderの二重性の分離、形成folder cellの単調配置、単調first-fit前提の明示）。page-local allocation例外を全廃。専用fixtureへintent bias item群を同居。**中1**: unit順序のcatalog宣言を新enum `CAPTURED_VISUAL_GLOBAL_REVERSED` に統一。**中2**: AC-12/Test oracleの代替条件を「Issue本文Acceptance criteriaの明示改訂commit/編集履歴 + owner decision comment」に統一。**低1**: 依存gate証拠へ `in:comments` 探索とヒット個別判定を追加。
- 2026-09-23: Review rev.4（ChatGPT再レビュー rev.3 comment 5780876399 を反映）。**中**: rev.3の `preserve`/`minimizeMovement` 完全inert化がspec 204のplanner接続契約（ordering/preference専用の消費）を弱めるため、page-local allocation例外は復活させずに両fieldを **identity-stableな消費順序bias** として再定義 — `preserve` は最も早い消費class、`minimizeMovement` はcaptured位置順であるbase順序の採用としての消費（GLOBAL_COMPACT族と同一）。class keyの優先順位と「class間不変・同class内は逆visual順復元」を合成規則と証明step 3へ明記。**低**: plan Current evidenceに残っていたrev.2の `preferenceAllocateOnPage` 記述を統一。
- 2026-09-23: Review rev.5（ChatGPT再レビュー rev.4 comment 5781075485 を反映）。**中**: rev.4の「preserve=最速消費class」は入力によってはpreserve itemのcross-page移動を増やし、spec 204の「移動最小化ordering bias」（現行 `preferenceCellHint` の「displacementを悪化させない」契約）の意味を反転させるため、 **preserveをreserved集合方式へ変更**。**低**: plan Verification AC-6をpreserve方式のoracleへ統一。
- 2026-09-23: Review rev.6（ChatGPT第5回レビューの応答喪失を受け、部分文で指摘されたreserved方式の契約境界 — spec 204「preserveはsoftな移動最小化preferenceであり保持判断は変えない」— に先行対応）。 **preserveをreserved集合（hard保持）からsoft captured-cell hintへ設計変更** — canonical族・GLOBAL_COMPACT族の `preferenceCellHint` と同一契約（領域内かつ割当順番時に空きのときだけ正確に使用、不成立時はpreserveなしと同一のsweep配置、領域外captured cellは決定的に無視）。これにより (a) spec 204のsoftness規則・「保持判断（determinePreservation）は変更しない」への完全適合、(b) displacement非悪化の構造的保証を同時に満たす。formation候補の扱いはcanonical族と同一（preserve hintはformation対象性を変えない）。
- 2026-09-23: Review rev.7（ChatGPT第6回レビュー、[Issueコメント](https://github.com/nunu1733/NunuLauncher/issues/398#issuecomment-5781772809) を反映）。**中**: soft hint下のfixed-point証明の誤り2箇所を修正 — (1) step 4の「hint成否集合はrun間で保存」は自己矛盾（run 1不成立→run 2成功があり得る）のため、 **保存されるのは成否ではなく最終allocation cellである** と帰納法を定式化し直した（σ₂ = σ₁ の下で直前occupancy同一、hint cell = run 1最終cellは形成folder cellと非重複ゆえ必ず空き）。(2) step 5の「形成folder cellは必ずunit cellより後方」はhint成功が前方holeを残し得るため偽 — 後方性の主張を廃止し、 **形成folder cellを「run 1 unit phase終了時に未使用だったcell」** として扱う（unit最終cellと非重複であることだけを使う）。専用fixtureへ「run 1 hint不成立→run 2 hint成功でも空差分」caseと「成功hintが前方holeを残しそのholeへ形成folderが配置される」caseをAC-5に追加（plan AC-5/AC-6も同様）。
- 2026-09-23: Review rev.8（ChatGPT第7回レビュー、[Issueコメント](https://github.com/nunu1733/NunuLauncher/issues/398#issuecomment-5781877107) を反映）。**中**: soft hintが同class内の実行順を反転させ得るため `σ₂ = σ₁` は成立しないという指摘により、証明の不変条件を **class順（bias class keys）のみがidentity-stable** へ修正 — step 3は `σ₂ = σ₁` を主張から外し「class間順序は不変、class内はmaterialized位置順」と定義、step 4は「class順 → class内materialized位置順」で処理する帰納法へ置換（preserve unitはhintでpを直接回収、non-preserve unitは「前方cellはF/F'/先行unitのcellで占有」論法でpがfirst-fitと示す。後classのunitが前方cellを最終cellとし得ないことを含む）。AC-5へ「同class内の実行順が反転（A=preserve@c2、B=非preserve@c3、空きc1）しても空差分」の直接fixtureを追加（plan AC-5も同様）。

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
