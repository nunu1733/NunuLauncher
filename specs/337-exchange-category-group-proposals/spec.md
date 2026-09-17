---
issue: "#337"
status: draft
requirements: [FR-017]
risk:
  - privacy
  - layout-data
updated: 2026-09-18
---

# AI personalizationにおけるユーザー定義カテゴリの参照と新規グループ提案

> Status: **draft** (2026-09-18)。本specは [spec 336](../336-user-defined-categories/spec.md) (status: **implemented**、PR #341 merge commit `45711f53dd40`) で確定した `CategoryIdentity` / `ActiveCategoryCatalog` / `UserDefinedCategoryStore` / #336 authoring pathの実コード (baseline `8fd05a40d51abd24b40a7b93579bb9b76d046f75`) と、v3にあるexchange契約 ([spec 204](../204-ai-personalization-context-intent-contract/spec.md) accepted・実装済み、[spec 331](../331-exchange-target-scope-coupling/spec.md)・[spec 330](../330-partial-intent-authoring/spec.md) によるv2/v3拡張、[spec 348](../348-exchange-ai-facing-contract/spec.md) によるAI-facing instruction派生) を調査した上で起草した。本文の決定 (D-1〜D-8) はすべて **draft decision** であり、Owner reviewによる受入れまで確定しない。本spec単独では実装を開始しない (「依存関係」節)。

## Problem and outcome

#204/#205のAI personalizationでは `groupSemantic` / `desiredGroup` によりsemantic groupingを表現できるが、`groupSemantic.category` が効くのはactive built-in taxonomyに存在する値に限られる (実装: `FullRunExecution.effectiveCategory` がtaxonomy membershipを検査し、非一致値を黙ってclassification decisionへフォールバックする)。#336でuser-defined categoryがfirst-class category identity (`CategoryIdentity.UserDefined`) になった後も、exchange側は#336の規律によりuser-defined categoryを一切表現できない ([spec 336](../336-user-defined-categories/spec.md)「Exchange export and #331 binding projection」。`ContextExportBuilder.exportPresentationValue()` がuser-defined分類をabsent categoryへ一括redactionする)。

その結果、外部AIがユーザー固有の整理概念 (「AIツール」「通勤」「朝使う」等) を扱う手段は、既定カテゴリへの無理な寄せか、planner効果を持たないrun-local自由文のいずれかしかない。

本Issueは、#336のcategory modelをAI exchange側から利用するadapter/contract/UXを定義する。outcomeは次のモデルである:

1. **既存categoryの参照** — exportがactive category catalog (built-in + user-defined) をprivacy-safeに投影し、AIはexport-scopedなcategory refで既存categoryを参照できる。参照は表示名ではなくidentityに解決される。
2. **新規グループの提案** — AIはactive taxonomyに存在しないsemantic groupを、そのrun限定のproposal (bounded自由文) として表現できる。
3. **永続化は明示確認後のみ** — import・validation・plan・applyのいずれの成功もcategory catalogを変更しない。AI proposalを残したい場合、ユーザーの明示的操作のみが#336の通常authoring pathを通してpersistent categoryを作成する。AI専用のcategory writerは作らない。

#336はcategory identity/persistence/authoring/Planner integrationを所有し、本IssueはAI exchange/personalizationからそのcategory modelを利用する面を所有する。第二のcategory store/write pathは作らない。

## Scope

- `PersonalizationContextExport` へのcategory catalog projection追加 (export-scoped category ref、kind、tier制御付き表示名) と、item-level category投影の改訂 (#336 absent-category redactionの、ref経由の投影への置き換え)。
- `PersonalizedIntent` の `groupSemantic` 変更: raw `category: String` をexport-advertised category refへの参照へ置き換え、existing-category referenceとrun-scoped proposal (自由文) を契約上区別する。
- contract version bump (`personalization-context-v4` / `personalized-intent-v4`。spec 331 D-1 / spec 330 D-3と同じ手順)。unknown versionのfail-closed拒否は不変。
- import validationへのcategory ref厳格解決 (unknown/deleted/stale refのfail-closed、同名カテゴリへのfallback禁止) と、export/import間のcategory rename/deleteのstale semantics。
- run-scoped proposalのPlanner投影の境界明示 (cohesion、new-folder label、category-based orderingとの関係、strategyがgroup creationを行わない場合の扱い)。
- import成功サマリ / previewにおける「既存categoryへの提案」「AI提案の一時グループ」「永続化済みカテゴリ」の区別表示。
- 明示promotion UX (「カテゴリとして保存」) の#336 authoring path (`UserDefinedCategoryAuthoringCoordinator`) 経由への接続。
- [spec 348](../348-exchange-ai-facing-contract/spec.md) 所有のAI-facing instruction (`IntentWireContract` descriptor派生) へのcategory/group representationの反映とcanonical例の同期。
- 上記のcontract / property / security / UI test要件と、代表的外部agent flowのdevice evidence要件。

## Non-goals

- AI出力のみによるcategoryの自動永続化 (作成・rename・delete・persistent assignment変更のいずれもimport成功の帰結としない)。
- category display nameをidentityとして扱うこと。display nameは常にpresentationであり、ref↔identity解決はexport sessionが保持するmappingのみで行う。
- unknown category nameの既存built-in categoryへのfuzzy mapping、およびunknown/deleted category refの同名カテゴリへのsilent fallback。
- AIによるbuilt-in categoryのrename/delete、built-in taxonomy membershipの変更。
- Planner strategy authorityのAIへの移譲。intentはordering/preference biasであり、`determinePreservation`・constraints・strategy選択を弱めない (spec 204「plannerが正本」の不変)。
- AI importと同時のlayout auto-apply。適用は #194 preview → #195 confirmation → 既存transactional apply pathのみ。
- #336 category store (`UserDefinedCategoryStore`)・override store (`CategoryOverrideStore`)・そのschemaの変更。#336 authoring contractの意味変更。
- exchange framing・envelope上限・privacy tierの枠組み変更 (#205所有。tierの適用対象fieldが増えるのみ)。
- #206 managed AI pathの実装 (単一契約のconsumerとしてv4を利用する)。
- marker framing抽出・#329 normalizerの変更 (#205/#329所有。受理外形3種は不変)。

## Domain language (CONTEXT.md追加案)

**exportカテゴリ参照 (Export Category Reference)**:
1つのcontext export内でadvertiseされた1つの `CategoryIdentity` (built-inまたはuser-defined) を指す、export-scopedなopaque識別子。itemのexport-scoped `ref` と同一の乱数seam・entropy契約で生成ごとに新鮮に割り当てられ、対応付け (ref→`CategoryIdentity`) はexport sessionのみが保持する。stableな `UserCategoryId` も表示名もexport文書へ現れない。
_Avoid_: UserCategoryId (stable local IDとの混同)、category name (identityではない)

**既存カテゴリ参照 (Existing-Category Reference)**:
intentがexportでadvertise済みのcategory refを指す表現。当該runのcategory preferenceとしてfirst-classに効く。表示名ベースの解決は存在しない。
_Avoid_: category指定 (v3までのraw `category: String` との混同)

**提案グループ (Proposed Group)**:
intentが自由文 (`groupSemantic.freeText`、bounded) で表現する、active taxonomyに存在しないsemantic group。そのrunのgrouping/label preferenceとしてのみ利用され、永続category identityではない。import・適用のいずれもcatalogを変更しない。
_Avoid_: 新カテゴリ (永続化を想起させる。永続化はpromotionのみ)、freeText category

**昇格 (Promotion / カテゴリとして保存)**:
ユーザーが明示的に選んだ場合に、提案グループを#336の通常authoring pathでpersistent user-defined categoryへ保存する操作。AI専用writerは存在しない。
_Avoid_: 自動保存、AI保存

## 現行契約 (v3) の該当実装と、v4で変わる点

| 項目 | v3 (現main `8fd05a40`) | v4 (本spec) |
|---|---|---|
| export envelope | `schemaVersion`/`exportId`/`tier`/`grid`/`items`/`preservedConstraints`/`capabilities`/`usageSignals` (`ContextExportModels.kt`) | `categories` (category catalog projection) を追加 |
| itemのcategory field | `ExportItem.category: String?` — built-in値のみ。user-defined分類は `exportPresentationValue()` によりabsent (`null`) | user-defined分類もadvertise済みcategory refとして投影 (built-in値のbyte互換は維持) |
| intent `groupSemantic.category` | raw `String?`。schema/validatorは値を検証せず、plannerがtaxonomy membershipを検査し非一致を黙って落とす (`FullRunExecution.effectiveCategory`) | `categoryRef: String?` — export advertise済みrefのみ。validatorが厳格解決し、未知refはtyped reject |
| user-defined categoryのexport出現 | 一切なし (#336規律) | opaque ref (+ tier許可時の表示名) のみ。raw `UserCategoryId`・名前の無制限公開は不変に禁止 |
| session | `itemRefs` (ref→`ItemId`)、digest、candidate scope等 | category ref→`CategoryIdentity` mappingを追加 |
| 失敗分類 | contract 13 class / UI 19種 | contract 14 class (`UNKNOWN_CATEGORY_REF` 追加) / UI 20種 |

## Contract design (draft decisions)

### D-1: export-scoped opaque category ref (stable ID直接公開・表示名identityの不採用)

- export envelopeにcategory catalog projection (`categories`) を追加する。1 entryは少なくとも: export-scoped `ref`、`kind` (`BUILT_IN` / `USER_DEFINED`)、`name` (tier制御付き、下記)。
- `ref` はitemの `ref` / `exportId` と **同一の `RandomIdAllocator` seam・同一entropy契約** で生成ごとに新鮮な乱数とする (spec 204「生成規則」の規律をcategoryへ拡張)。ref→`CategoryIdentity` の対応付けはexport sessionのみが保持し、backup対象外である。
- **stable `UserCategoryId` を直接exportしない**: stable local UUIDはcross-exportで安定な識別子であり、これを外部に載せることはspec 204がitemについて排除したcross-export unlinkability (「契約自身がstableなopaque identifierを提供しない」) をcategory面で裏切る。表示名もidentityではない (#336 identity model)。
- built-in categoryも同一ref機構でadvertiseする (名前文字列をintentに書かせる方式に戻らない)。v3までの `ExportItem.category` (built-in raw値) はv4ではitem-level category ref (D-2) に置き換わる。
- catalog容量 (user-defined最大64件、#336) とbuilt-in 34件により、`categories` は既存content limits内に収まる。export size上限 (`MAX_EXPORT_BYTES` 256 KiB) の検査は既存builder pathがそのまま適用する。

### D-2: 利用可能カテゴリのexport context (privacy tierとの関係)

- `name` の扱い: built-in entryのnameはtaxonomy enum値 (既存のitem.category投影と同一の値。自由文classではないので **いずれのtierでも含む** — 現行のitem category投影と同じ扱い)。user-defined entryのdisplay nameは **ユーザー入力自由文class** に属し、#204のsingle-point tier controlに従う:
  - `LOCAL_FULL` / `EXTERNAL_WITH_LABELS`: display nameを含む (既存自由文上限200字内。#336のname検証済み1〜50 code points)。
  - `EXTERNAL_REDACTED` (既定): display nameを含まない。entryは `ref` + `kind: USER_DEFINED` のみをadvertiseする。
- **redacted tierで名前のないuser-defined categoryの有用性 (draft decision)**: opaque refのみをadvertiseし、参照は許す。AIは「このカテゴリが存在し、どのitemが現在属しているか (D-2 item投影)」を知れるが、名前は知らない。ローカルのpreview/importサマリは実名を表示するため、ユーザーは意味を確認してから受け入れられる。既定tierで機能を殺さないことが目的の draft decision であり、代替 (redacted tierではuser-defined entry自体を省略し、機能をlabel-inclusive tierに限定) はOwner reviewの比較対象として残す。
- membership projection: 専用の集計fieldは持たない。**item-level category ref投影 (次項) によりmembershipは各itemから読み取れる**。集計の二重正本は作らない。
- catalog読み失敗時のexport: `OrganizationInput` composition自体が #336 のtyped read-failure (`InputCompositionCode` 拡張) でNotReadyになるため、exchange生成は既存の `ExchangeInputResult.NotReady` としてtyped失敗する。「読めない源を空catalogとして表現しない」#336規律の継承であり、新機構を追加しない。

### D-3: item-level category投影の改訂 (#336 redaction規律の意図的revision)

- v4では、resolved分類がuser-defined categoryであるitem (`ExportItem.category` がv3でabsentだった対象) は、そのcategoryのadvertise済み **export-scoped ref** を投影する。built-in分類のraw値投影は現行どおりbyte互換で維持する。
- これは [spec 336](../336-user-defined-categories/spec.md)「Exchange export and #331 binding projection」の 「user-defined分類はabsent categoryへ投影」規律を、本Issueが所有するprivacy-reviewed projectionへ置き換えるrevisionである。raw `UserCategoryId`・display nameの文書露出禁止は不変。#336 specの該当行・AC-14は、本specの実装PRでnormative更新する (spec 348 Decision 6と同じ「実装PR必須更新」扱い)。
- freshness digestへの影響なし: `SourceContextIdentity` / `CandidateScopeIdentity` はresolved `CategoryIdentity` (kind + stable ID) をdigest入力とする (#336)。export fieldのredaction緩和はdigest定義に触れない。reassignment / assigned-category deleteの検出は現行どおりである。
- `SessionExportReconstructor` は、sessionのcategory mappingと現行compositionのcatalogから、validation viewの `categories` とitem category refを同一規則で再構築する (spec 205 reconstruction-parity契約の延長)。

### D-4: existing-category referenceとnew-group proposalの契約上の分離

- intent v4の `groupSemantic` は次の2 fieldに分離する:
  - `categoryRef: String?` — **ExistingCategoryRef**。同一exportの `categories` でadvertiseされたrefのみ。v3のraw `category: String?` を置き換える (schema field名の変更を含む意味変更がversion bumpの主因の一つ)。
  - `freeText: String?` — **ProposedGroupSemantic**。bounded自由文 (上限100字はv3から不変)。run-scoped proposal専用であり、category identityとしては解釈されない。
- any-of規則 (少なくとも1つ非null) は不変。新しい自由文を既存category ID/refとして解釈する経路は存在しない。
- desiredGroup (ref集合によるcohesion希望) はv3から不変。`desiredGroup` なしの単独 `groupSemantic` もv3どおり受理される (cohesionはplannerの既存folder semanticsを通じてのみ実効化)。

### D-5: category refの厳格解決とstale semantics (fail-closed、名前fallback禁止)

- import validationは、intent内のすべての `categoryRef` を次の順で解決する:
  1. export sessionのcategory mapping (ref→`CategoryIdentity`) に存在するか。存在しないrefは **`UNKNOWN_CATEGORY_REF`** (新typed class、D-8) でreject。v3までの「taxonomyに無い文字列を黙って落とす」挙動をv4では許さない。
  2. 解決した `CategoryIdentity` が、import時に同一composition cutから得たactive catalogに存在するか。存在しない (export後に削除された等) 場合も `UNKNOWN_CATEGORY_REF` でreject。**表示名が一致する別categoryへのremapは行わない**。
- stale semantics (#336の規律と矛盾しない):
  - **rename (export→import間)**: identity不変のため新鮮。ref→identityは解決し、importは成立する。preview・folder titleは **composition時点のcatalog snapshot** の現在名で表示される (#336 title binding規律の継続)。export時点の古い名前でplanが固定されることはない (canonical plan bytesはidentityのみを運ぶ)。
  - **割当を伴うdelete**: 削除されたcategoryにexport対象itemの割当があれば、resolved identityが変化し、既存のstructural `sourceContextDigest` 照合が `CONTEXT_STALE` でrejectする (#336で確立済みの経路)。
  - **無割当delete**: digest不変のため `CONTEXT_STALE` にはならないが、当該categoryへの `categoryRef` はcatalog不在となり `UNKNOWN_CATEGORY_REF` でfail-closed rejectされる。remedyは再export。
  - **catalog作成 (export後に新categoryを作成)**: resolved identity不変のため新鮮。advertiseされていない新categoryはintentから参照できない (refが存在しないため自然に `UNKNOWN_CATEGORY_REF`)。
- validatorの検証順序への追加位置: ref解決 (item/desiredGroup/unresolved) の後、mobility検証の前。既存class間の排他構造は維持する。
- Planner接続: `IntentPlannerAdapter` は解決済み `CategoryIdentity` をpreferenceへ載せる (raw文字列をplannerへ渡さない)。`FullRunExecution.effectiveCategory` はidentityを直接消費するようになり、文字列membership検査と黙って落とす経路は廃止される (検証済みintentのみがadapterへ到達するため、planner側の防御は不要かつ有害でなくなる)。**determinism**: 同一accepted intent identity + 同一canonical planning inputs (catalog identityを含む `InputProvenance` 全体、#336) からdownstream planは決定的 (既存契約の再確認)。

### D-6: run-scoped proposalのPlanner利用境界

`groupSemantic.freeText` のproposalと `desiredGroup` のcohesion希望について、Plannerでの利用を次に限定する (draft decision):

- **cohesion**: `desiredGroup` によるfolder cohesion希望は現行どおり。実効化は既存strategyのfolder placement semanticsのみを通り、intent由来の新folder機構は導入しない (spec 204「Planner接続」の不変)。
- **new-folder label (新規消費)**: strategyがfolder creationを行うrun (`strategy.createsFolders`) で、groupに既存category identityが紐かない場合 (`groupSemantic` がfreeTextのみ)、生成される新folderの表示名のsourceとしてfreeTextを利用できる。labelはfree text classとして扱い、既存title pathの非blank検証・generic fallback policy (#336 `FolderTitleResolver` 契約と同型) を通す。raw ref・ID露出はしない。
- **category-based orderingとの関係**: run-scoped proposalはcategory identityではないため、canonical identity order / `CATEGORY_CONTIGUOUS_V1` 等のcategory消費strategyのordering入力には **ならない**。orderingに効くのはresolved category identity (existing-category reference由来またはclassification由来) のみ。
- **strategyがgroup creationを行わない場合**: cohesion希望・labelともplanner効果を持たない (preference入力として存在するのみ)。戦略選択の正本は従来どおりユーザー/既存selection pathにある。
- preview表示: new-folder labelとして採用されたfreeTextは、既存preview change listのfolder titleとして表示される (AI専用のpreview truthは作らない、spec 204/205の原則)。

### D-7: 自動永続化の不存在と明示promotion

- **import成功だけでは何も保存しない**: intent validation成功・plan適用成功のいずれも、user-defined categoryの作成/rename/delete、persistent assignment変更を行わない。構造的に、import〜apply pathはcategory storeへのwriterを持たない。この不変条件をproperty testで固定する (「Testing strategy」)。
- **promotion (「カテゴリとして保存」)**: import成功サマリ / preview系surfaceから、ユーザーが提案グループをpersistent categoryへ昇格できるUXを提供する (任意・明示的操作)。
  - writerは #336 の `UserDefinedCategoryAuthoringCoordinator.create()` **のみ**。stable ID発行・atomic persistence・duplicate/name collision typed failure・capacity (64)・lease検査はすべて#336契約のものをそのまま使う。AI専用の別writer・validation成功への自動フックは作らない。
  - 昇格対象名はproposalのfreeText (正規化・検証は#336 rules: trim、NFC、1〜50 code points、catalog内一意)。重複時はtyped `DuplicateName` としてユーザーへ提示し、自動remapしない。
  - **authority boundary**: 昇格は既存assignmentの一括再分類を行わない (それ自体は #99 override editorの通常操作として後から可能)。v1の昇格は「カテゴリの作成」までとし、作成後の割当は既存assignment UIで行う (draft decision。作成+割当を1操作にする拡張はOwner reviewの比較対象)。
  - **実行時機会 (draft decision)**: #336のauthoring操作はsingle organization-operation leaseにより、activityなrun中はrejectされる (「reject/busy、no invalidation」)。したがってpromotionは (a) run非active時のimportサマリsurface、または (b) run完了後のsurfaceから提供し、activity run中の選択はtyped busy案内 (既存lease挙動) とする。run中の確認画面に置かない。
  - privacy: promotion操作・結果のdiagnostics記録は #336 diagnostics規律 (ID・display name非記録) に従う。

### D-8: failure分類・versioning・AI-facing instruction

- **新typed class `UNKNOWN_CATEGORY_REF`** (contract 14th class): D-5の条件でreject。既存classとの排他は検証順序で担保。UI失敗表示は19種→20種 (ja/en copy追加)。`CONTEXT_STALE` との对应は既存経路が先に判定する (D-5の順序)。
- **version bump**: `personalization-context-v4` / `personalized-intent-v4` への同時bump (exportがintent schema versionをadvertiseするため単独bumpは不可能、spec 330 D-3と同様)。dual-version runtimeは持たない。v1/v2/v3文書は `SCHEMA_MISMATCH` でfail-closed拒否。
  - 保存済み文書への影響はspec 330 D-3と同一: intent本文は永続化されず、export sessionはintent schema versionを保持しないため無影響。bumpによりv3 era session宛の返答は `SCHEMA_MISMATCH` となり再exportが必要 (TTL 24時間・single-active-sessionで一時的)。session recordへのcategory mapping追加はadditive (record schemaのversion扱いはplan.mdで確定)。
- **AI-facing instruction同期 (#348所有機構)**: `IntentWireContract` descriptorに `categoryRef` / `categories` 参照規則を追加し、instructionへ次を反映する:
  - Output contract: `groupSemantic.categoryRef` の型と「CONTEXT dataの `categories` 配列内の `ref` のみ」。
  - You must: 「category名や新しい名前をcategoryとして捏造して書かない。既存カテゴリは `categoryRef` で、新しい概念は `freeText` で表現する」「grouping等の未定義fieldを作らない」(self-checkにも追加)。
  - canonical例: `categories` を利用する例と `freeText` proposalの例を、#348のdescriptor派生・drift防止機構 (parity/policy matrix) に乗せて提供する。#327 (interview-first、OPEN) のcanonical example/template表示とはこの材料を共有する。
  - production-enforced parity fixture: 未知 `categoryRef` → `UNKNOWN_CATEGORY_REF` をproduction path (`ExchangeImportPipeline.import`) でpinning。canonical fixtureの受理 (canonical ⊆ accepted) も既存policy matrix方式で追加。
- spec 204 / 205 / 336 / 348 へのnormative更新・change history追記は、本specの **実装PRで必須** とする (spec 348 Decision 6の前例)。特に: spec 204 (groupSemantic定義・content limits表の反映)、spec 205 (Data and stateの #204由 来制約の実名更新)、spec 336 (export投影規律の改訂 + AC-14の実装後記述)、spec 348 (descriptor/instructionの現状記述)。

## Privacy / security

- **export新fieldの自由文分類**: `categories[].name` (user-defined時) のみが新たな自由文class対象であり、既存single-point tier controlに登録される (`EXTERNAL_REDACTED` では存在しない)。built-in nameはtaxonomy enum (既存扱い)。item category ref・kindは自由文ではない。
- **unlinkability**: category refはitem refと同一乱数seam・entropy契約であり、cross-exportで安定なcategory識別子は契約として提供しない。session外にref↔identity対応は残らない。ただし **残存risk** (spec 204の確立した確率的linkage保証外と同型): user-defined category数・kind・membership shape・自由文名 (label-inclusive時) の組合せはstate fingerprintになり得る。tier選択 (ユーザー同意対象) に属するriskとして脅威modelに明記する。
- **prompt injection**: user-defined display nameはdataでありinstructionではない (既存脅威modelの適用対象へ明示追加)。AIが名前をcategory refの代わりに返しても、ref allow-list照合で `UNKNOWN_CATEGORY_REF` としてfail-closedする。名前からの一括category捏造は構造的に受理されない。
- **diagnostics**: category ref・display name・catalog内容をdiagnostics/journalへ出さない ([organizer-diagnostics.md](../../docs/engineering/organizer-diagnostics.md) 規律の継承)。session (category mapping追加) はapp-private・backup対象外・user作成自由文を含まない (名前はsessionに載せない。載るのはref→stable identityのみ)。
- 外部送信は既存Pre-send Disclosure (生成済みpackage対象・同一immutable value・送信前確認) の枠内。v4の新fieldも同じ生成済みpackageに含まれ、確認なしの送信経路は増えない。

## Stale state / concurrency

- export生成は既存single canonical composition seam (`ExchangeInputAdapter` → `OrganizationInputComposer`) の1回のcutから得たcatalog snapshotを使う。export時点のcatalog identity (generation/digest) は、`InputProvenance` の既存catalog参加を通じてrun provenanceへ現れる (#336)。
- import時の検証用catalogは、structural digest再計算と同一composition cutから得る (既存pipeline順序: envelope → framing → decode → session → expiry → **digest照合** → reconstruction → validation)。digest照合が通った場合のみcatalog解決へ進むため、resolved identityに影響するcatalog変化は既存 `CONTEXT_STALE` に収束する (D-5)。
- promotionを含むcategory authoringとrun操作の相互排除は既存lease domain (#336) に従う。本specは新しいlease・新しい排他機構を導入しない。

## Behavior scenarios

### Scenario: 既存user-defined categoryの参照 (正常系)

**Given** ユーザーが #336 で「通勤」categoryを作成し、Maps/Suicaを #99 override editorで割り当てている、
**When** `EXTERNAL_REDACTED` でexchange packageを生成し、AIがMaps/Suicaのrefsに「通勤」をadvertiseされたcategory refで指す `groupSemantic.categoryRef` を返してimportする、
**Then** intentは受理され、当該runで「通勤」(`CategoryIdentity.UserDefined`) がcategory preferenceとしてfirst-classに効く、
**And** previewには「通勤」という実名で提案が表示され (ローカル表示)、AIへは名前が渡っていない、
**And** import・適用のいずれもcatalogを変更しない。

### Scenario: run-scoped proposal (新規グループの提案)

**Given** active taxonomyに「朝使う」が存在せず、AIが複数itemの `desiredGroup` と `groupSemantic.freeText: "朝使う"` を返した、
**When** importする、
**Then** intentは受理され、cohesion希望としてrun内で利用され (strategyがfolder creationを行う場合)、生成される新folderのlabelが「朝使う」になる、
**And** category-based orderingの入力にはならず、user-defined catalogには何も作られない、
**And** import成功サマリは「AI提案の一時グループ」として既存category参照と区別して表示する。

### Scenario: display nameの捏造・未advertise名の参照

**Given** AIが `groupSemantic.categoryRef: "通勤"` (名前文字列) やadvertiseされていない値を返した、
**When** importする、
**Then** `UNKNOWN_CATEGORY_REF` でfail-closed rejectされ (zero-write)、失敗説明は再export・canonical form再依頼を案内する、
**And** 名前に類似するcategoryへのfuzzy解決・silent fallbackは発生しない。

### Scenario: redacted tierでのuser-defined entry

**Given** `EXTERNAL_REDACTED` 選択時、
**When** exportが生成される、
**Then** `categories` のuser-defined entryは `ref` と `kind` のみを含み、display nameを含まない、
**And** built-in entryはtaxonomy enum値をnameとして含む (現行のitem category投影と同じ露出度)、
**And** 送信前確認画面には「ユーザー定義カテゴリの参照情報 (名前を除く) が含まれる」ことが示される。

### Scenario: export→import間のrename

**Given** export後にユーザーが「通勤」を「交通」へrenameした (割当は不変)、
**When** export時のcategory refを参照するintentをimportする、
**Then** identityは不変のため `CONTEXT_STALE` にならず、importは成立する、
**And** preview / folder titleはcomposition時点catalog snapshotから「交通」と表示される。

### Scenario: 割当を伴うdelete (stale) と無割当delete (unknown ref)

**Given** export後に「通勤」がdeleteされた、
**When** そのcategoryを参照するintentをimportする、
**Then** 割当itemがexport対象に含まれていた場合はresolved identity変化により既存digest照合が `CONTEXT_STALE` でrejectし、
**And** 割当がなかった場合は `UNKNOWN_CATEGORY_REF` でrejectされる (どちらもzero-write、remedyは再export)、
**And** 同名の新categoryが存在しても、それへのremapは発生しない。

### Scenario: promotion (明示保存)

**Given** import成功サマリにAI提案グループ「朝使う」が表示されている、
**When** ユーザーが「カテゴリとして保存」を明示選択する、
**Then** #336 authoring path (`create`) のみが実行され、stable IDが発行されatomicに永続化される、
**And** import成功や適用だけではcategoryが作成されていなかったことが、作成前のcatalog stateで検証できる、
**And** 名前重複・capacity超過・store失敗は#336のtyped failureとして表示され、自動remap・自動再試行はしない。

### Scenario: activityなrun中のpromotion

**Given** AI intent由来のrunがactivityである、
**When** ユーザーがpromotionを選ぶ、
**Then** 既存single organization-operation leaseによりtyped busyでrejectされ、既存run・catalog・plan状態は不変である (案内に従いrun終了後に再試行できる)。

### Scenario: 同一intent + 同一catalogからの決定的再現

**Given** 同一accepted intent identity (category ref解決結果を含むcompleted表現のidentity) と同一canonical planning inputs (同一catalog content) がある、
**When** downstream planを再構成する、
**Then** planはbyte-deterministicであり、category rename (identity不変) が直前runのcanonical plan bytesを変えない (#336規律の継続)。

### Scenario: 自動永続化の不存在 (property)

**Given** validation成功・適用成功を含む任意のimport経路の実行、
**When** category catalog・override storeの状態を比較する、
**Then** 両storeは無変更である (promotionの明示操作を除く全経路でzero-write)。

### Scenario: 旧version文書の拒否

**Given** `schemaVersion: personalized-intent-v3` (またはv1/v2) の文書がimportされる、
**When** decodeされる、
**Then** `SCHEMA_MISMATCH` でfail-closed rejectされ、再exportが案内される (spec 330 D-3と同一の移行扱い)。

## Accessibility

- import成功サマリ・preview・promotion確認・typed失敗表示の新規/変更UIは、既存 #205/#332 surfaceと同じbarを満たす: TalkBack labels/roles、focus restoration、keyboard/DPAD・Switch Access操作、非色依存の状態表現 (既存category/一時提案/永続済みの区別を色だけにしない)、large font (200%) 、long localized names。
- 「カテゴリとして保存」の結果表示 (成功・重複・capacity・busy) はtypedかつlocalized (ja正本 + en)。
- raw category ID (UUID含む) はユーザーUIへ一切出さない (#336規律)。

## 互換性 / migration

- category参照・proposalを使わないv4 intent (全 `categoryRef` null) と、user-defined catalogが空の環境のv4 exportは、現行v3のplanner効果と同一である。built-in categoryのitem投影はraw値のままbyte互換。
- Launcher DB schema・favorites・recovery契約の変更なし。永続化の増分はexport session recordへのcategory mapping追加のみ (app-private・backup対象外)。
- downgrade: 旧binaryはv4 session recordの新fieldを読まない (spec 330 D-3と同一の扱い。旧binaryの既存fail-closed挙動は変更しない)。
- 既存run (intentなし) のplan・provenance・UIは不変。

## Acceptance criteria

- [ ] **AC-1**: export contextが#336 active category catalogをprivacy-safeに表現する。`categories` はexport-scoped random ref (item refと同一乱数seam・entropy契約) + kind + tier制御付きnameを含み、redacted tierのuser-defined entryにdisplay nameが現れないことがcontract testで検証される。raw `UserCategoryId`・意図しない名前露出の走査を含む。
- [ ] **AC-2**: existing built-in/user-defined categoryをexport-scoped identityで参照でき、表示名だけではidentityを解決できない。ref↔identity mappingがsessionのみに存在し、export文書・intent・diagnosticsのいずれにもstable IDが現れないことのtest。
- [ ] **AC-3**: existing-category reference (`categoryRef`) とrun-scoped proposal (`freeText`) が契約上区別され、自由文がcategory identityとして解釈される経路が存在しないこと (validator/planner/adapterのunit test)。
- [ ] **AC-4**: AIは既存taxonomyにないsemantic groupをrun用に提案でき、そのplanner効果がD-6の境界 (cohesion、new-folder labelのみ、ordering非参加、非folder-creation strategyで不変) に収まることがplanner unit/property testで検証される。
- [ ] **AC-5**: proposalのimportはuser categoryを自動作成しない。validation・plan・applyの全経路でcatalog/override storeが無変更であることのproperty test (AC-7の明示経路と対照)。
- [ ] **AC-6**: deleted/stale/unknown category refはfail-closed (`UNKNOWN_CATEGORY_REF` / 既存 `CONTEXT_STALE` 経路) し、同名カテゴリへのsilent fallback・fuzzy remapが存在しないことのcorpus test。export→import間のrename (成立・現名表示) / 割当ありdelete (`CONTEXT_STALE`) / 無割当delete (`UNKNOWN_CATEGORY_REF`) のstale testを含む。
- [ ] **AC-7**: ユーザーが明示的に選んだ場合のみ#336 authoring path (`UserDefinedCategoryAuthoringCoordinator.create`) でpersistent categoryへ保存できる。AI専用writerが存在しないこと、重複・capacity・busy (lease) がtyped表示されること、作成後に既存assignment UIで割当できることのUI/instrumentation test。
- [ ] **AC-8**: `personalization-context-v4` / `personalized-intent-v4` への同時bump、旧versionの `SCHEMA_MISMATCH` 拒否、session recordの移行扱いがcodec contract testで検証される。
- [ ] **AC-9**: #348 descriptor由来のinstructionにcategory/groupのcanonical schema/exampleが含まれる (`categoryRef` 参照規則・捏造禁止・self-check追加)。descriptor↔codec↔instructionの同期test、未知 `categoryRef` のproduction path parity fixture、canonical fixture受理 (canonical ⊆ accepted) を含む。#327 example/templateとの材料共有が記録される。
- [ ] **AC-10**: import成功サマリ/previewでexisting category参照 / AI提案の一時グループ / 永続化済みcategoryを区別でき、import成功が「保存済み」に見える表示にならないことのUI test。
- [ ] **AC-11**: 同一accepted intent + 同一category catalog identityからのdownstream planがdeterministicであること (既存property suiteのmixed-catalog拡張)。category renameがcanonical plan bytesを変えないことの再確認testを含む。
- [ ] **AC-12**: 代表的なExternal Agent flow (「既存custom category利用」「新規group提案」「保存しない」「明示保存」) のdevice evidenceが取得される (privacy境界はspec 348 Decision 7と同じpolicy: sanitized artifactのみ)。
- [ ] **AC-13**: 新規/変更UI surfaceのaccessibility evidence (TalkBack、focus、keyboard/DPAD、Switch Access、非色区別、200% font) がある。
- [ ] **AC-14**: spec 204/205/336/348のnormative更新とchange history追記が実装PRで完了する (D-8)。CONTEXT.md用語・DESIGN.md gate 12/13の更新を含む。

## Test oracle

| AC | Evidence |
|---|---|
| AC-1 | `ContextExportBuilder` contract test (tier matrix、ref乱数性/uniqueness、名前redaction走査) + codec round-trip |
| AC-2 | builder/codec/session store test (mapping在所、文書走査) |
| AC-3 | validator/planner adapter unit test (解釈経路の不在) |
| AC-4 | planner unit/property test (`FullRunExecution` freeText label、ordering非参加、strategy別) |
| AC-5 | store不変のproperty test (import pipeline経由) |
| AC-6 | validator corpus test (unknown/stale/rename/delete matrix) + `SessionExportReconstructor` parity test |
| AC-7 | `UserDefinedCategoryAuthoringCoordinator` 接続のintegration test + UI test (typed failures、lease busy) |
| AC-8 | codec contract test (version拒否、session record移行) |
| AC-9 | `Issue348` 方式のdescriptor/instruction同期test + parity fixture (production path経由) |
| AC-10 | importサマリ/preview UI test |
| AC-11 | planner determinism/idempotence property (mixed catalog) |
| AC-12 | device evidence記録 (docs/assessment/) |
| AC-13 | accessibility instrumentation/manual evidence |
| AC-14 | spec/docs diff (実装PR内) |

## Open questions (draft decisions — Owner review対象)

1. **D-2 redacted tierのuser-defined entry扱い**: opaque ref + kindのみのadvertise (本spec案) か、redacted tierではentry自体を省略するか。
2. **D-6 new-folder labelへのfreeText利用**: 本spec案は「strategyがfolder creationを行うrunで新folderの表示名sourceに使う」。「labelには使わずcohesion+既存naming policyのまま」も代替として比較対象。
3. **D-7 promotionの操作範囲**: v1は「カテゴリ作成のみ」(割当は既存UI) を提案。作成+一括割当を1操作にする拡張は別評価。
4. **D-7 promotionの提示位置**: run非active時のimportサマリ / run完了後surface (本spec案)。activity run中の確認画面には置かない。
5. **`UNKNOWN_CATEGORY_REF` を独立classにするか**: 既存 `UNKNOWN_REF` への統合 (UI表示の使い分けを文字copyのみで行う) も代替。独立class案 (UI 20種化) を提案する。

## 依存関係

| 依存先 | 状態 | 関係 |
|---|---|---|
| #336 (spec 336) | **implemented** (PR #341) | `CategoryIdentity` / `ActiveCategoryCatalog` / `UserDefinedCategoryStore` / authoring coordinator / export投影規律 / stale規律の正本。本specはその利用面を所有し、store/authoring契約を変更しない。実装の前提は解消済み |
| #204 (spec 204) | accepted・実装済み | context/intent契約の正本。本specはv4への意図的拡張を所有し、spec 204へのnormative更新を実装PRで行う |
| #205 (spec 205) | implemented | framing・envelope・Pre-send Disclosure・session置換の所有者。変更なし (表示の延長のみ) |
| #330 (spec 330) | implemented | v3 partial authoring。v4でもomission/unresolved意味は不変。versioning手順の前例 |
| #331 (spec 331) | implemented | candidate subject・scope binding gate。category投影改訂時もscope gateのcandidate投影digestはidentity基準のまま不変 |
| #348 (spec 348) | accepted・実装済み (PR #349) | AI-facing instruction/descriptorの所有者。本specのinstruction変更はこの機構を通す。spec 348自体のnormative更新を実装PRで行う |
| #327 (OPEN) | spec未merge | canonical example/template表示の合成先。blockingではないが、example材料の共有先として調整が必要 |
| #332 (spec 332) | implemented | import UI (parse state表示)。サマリ差別化表示の実装面の土台 |

**Source implementationの開始条件**: 本specのacceptance (Owner review) を必須とする。#336/#330/#348由 来のcontract前提は解消済みだが、Open questions 1〜5が未受入れのままの実装開始はしない。

## References

- [Issue #337](https://github.com/nunu1733/NunuLauncher/issues/337)
- [Spec 336: user-defined categories](../336-user-defined-categories/spec.md) (implemented。identity/catalog/store/authoring/export投影の正本)
- [Spec 204: AI personalization context/intent contract](../204-ai-personalization-context-intent-contract/spec.md) (accepted・実装済み)
- [Spec 205: external agent exchange](../205-external-agent-exchange/spec.md) (implemented)
- [Spec 330: partial intent authoring](../330-partial-intent-authoring/spec.md) (implemented。v3手順の前例)
- [Spec 331: exchange target scope coupling](../331-exchange-target-scope-coupling/spec.md) (implemented)
- [Spec 348: exchange AI-facing contract](../348-exchange-ai-facing-contract/spec.md) (implemented。descriptor/instruction機構)
- [Spec 329: import normalizer](../329-import-normalizer/spec.md) (implemented。受理外形)
- [Spec 332: exchange import input UI](../332-exchange-import-input-ui/spec.md) (implemented)
- [Spec 99: user-authored category overrides](../99-user-authored-category-overrides/spec.md)
- [ADR-0007: authoritative organization policy sources](../../docs/adr/0007-authoritative-organization-policy-sources.md)
- [organizer-diagnostics.md](../../docs/engineering/organizer-diagnostics.md)
- [requirements.md](../../docs/product/requirements.md) (FR-017)
- 実装調査対象: `lawnchair/src/app/lawnchair/organizer/personalization/` (契約・codec・validator・adapter・exchange pipeline)、`organizer/planning/CategoryIdentity.kt` / `OrganizationInput.kt` / `FullRunExecution.kt`、`organizer/rules/UserDefinedCategoryStore.kt`、`organizer/ui/UserDefinedCategoryAuthoring.kt`、`organizer/integration/exchange/ExchangeInputAdapter.kt` / `ExchangeFlowController.kt`
