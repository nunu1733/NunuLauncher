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

> Status: **draft — 2nd revision** (2026-09-18)。本revisionは、1st revision (snapshot commit `8ee469ec69ebb68f5f1fa34e7370b4f38ed341da`、baseline `8fd05a40d51abd24b40a7b93579bb9b76d046f75`) に対するレビュー (Issue #337 コメント 2026-09-18、**Changes requested**、高2 / 中3) への対応と、そのレビューが要求した current `main` (`34ba8ff447`) へのre-entryを記録する。各指摘と対応は「Review response」節に1対1で記録し、D-1〜D-8は 1st revision の draft decision を **確定decision** へ置き換えたものである (旧 Open questions 1〜5 も同節で決着)。
>
> 依存する #336 (`45711f53dd`) / #330 (`dce8f5779c`) / #348 (`8fd05a40d5`) は実装済みで、#327 (`a9ec3c2cf9`) も本revision時点で main に実装済みである (1st revision時点の「#327 OPEN・spec未merge」は stale であった)。本spec単独では実装を開始しない (「依存関係」節)。

## Review response (1st revision → 2nd revision)

| # | 1st revisionへの指摘 | 本revisionでの解決 |
|---|---|---|
| R-1 [高] | D-1 (built-inもopaque ref化) と D-3 / Compatibility / plan Interface 3 (built-in raw値はbyte互換、user-definedのみref) が矛盾し、codec / reconstructor / AI-facing contractの正解が一意に決まらない | **v4は全category露出をexport-scoped refへ一本化する** (D-1)。item-levelのraw built-in値と「built-inはbyte互換」という主張を削除し、item fieldは`categoryRef` / `folderCategoryRef`のみとする。混在方式は採らない (下記「Why full refs」) |
| R-2 [高] | `ProposedGroupSemantic`の中核Planner semanticsが現行Plannerと接続できない (folder形成は`CategoryIdentity`の`formFolderGroups`のみ、`desiredGroup`は`groupRank`のみ)。一般ケースでfolderが形成されず、per-item `groupSemantic`のcomponent内衝突規則もない | D-6を「**run-scoped formation key**」として再定義し、現行`effectiveCategory`が効いているexecutorと同じ範囲でproposalがfolder形成のkeyになる (per-strategy matrixでnegativeも固定)。D-4を**exactly-one-of**化し、D-5に**component内semantic一意規則** (違反は新typed `CONFLICTING_GROUP_SEMANTIC`) を追加 |
| R-3 [中] | proposalの`freeText`上限100字 / blank許容と、#336 category name規則 (trim/NFC後1〜50 code points) が不一致で、「カテゴリとして保存」時に`InvalidName`になる | proposal labelの値域を**#336 name規則そのもの** (`UserDefinedCategoryNameRules`のnormalize+validate) へ揃え、promotionを無変換pass-through可能にする。100字上限は50 code pointsへ意図的に狭める (D-4 / AC-5) |
| R-4 [中] | snapshotの#327前提が stale。#327のcanonical templateはjudgment-bearing optional fieldを意図的に掲載しない契約なので、D-8/AC-9の「canonical exampleを#327と共有」が張力を持つ | #327を implemented (`a9ec3c2cf9`) として依存表を更新し、**canonical templateは一切変更しない** (spec 327 Decision 2のnon-seeding不変)。category/group guidanceはdescriptor派生Output contract (単一source) + You-must/self-check + production-enforced parity fixtureで提供し、Issue 337 AC-9の「schema/example」はdescriptor schema + 受理fixture群で満たす (D-8 / AC-12) |
| R-5 [中] | planのsecurity oracle「prompt-like文を`freeText`に含む応答 → fail-closed」にnormative contractがなく、heuristic判定を導入しないなら削除すべき | **削除した**。labelの唯一のcontent規則は値域 (D-4) であり、heuristicな「promptらしさ」判定は導入しない。prompt injectionは既存の「dataであってinstructionではない」+ ref allow-list方式で扱い、そのnormativeな扱いと残存riskを「Privacy / security」節に固定する (AC-5 / AC-7) |

### Re-entry record (current main への再anchor)

- 1st revision baseline: `8fd05a40d51abd24b40a7b93579bb9b76d046f75` (origin/main、#348 merge後)
- 本revision baseline: `34ba8ff447` (origin/main、#328 / PR #353 merge後)。1st revision branchとの差分範囲を全量確認した。
- baseline以降に main へ入った、本specへ影響する変更:
  - **#327 implemented** (`a9ec3c2cf9`、PR #350): instructionへinterview-first構成、`CANONICAL_INTENT_TEMPLATE`、`Issue327InterviewFirstContractTest`。canonical templateはjudgment-bearing optional fieldを掲載しない (R-4の前提)。
  - **#328 implemented** (`34ba8ff447`、PR #353): `ExchangeImportSummary`、import成功state、`StrategyWriteArbiter`、strings (en/ja)。AC-10 (summary差別化) とpromotionの提示位置はこの実装面上に置く。
  - `IntentPlannerAdapterTest` の更新 (adapter契約の回帰test)。`FullRunExecution` / `FolderFormation` / `IntentValidator` / codec のcategory消費契約に変更はない。
- 1st revisionが「未検証」としていた範囲の再確認: `AndroidExportSessionStore` のrecord schema (v2 / `organizer_personalization_export_session_v2.json` / strict decode) を実コードで確認し、D-8のsession扱いを確定した。

## Problem and outcome

#204/#205のAI personalizationでは `groupSemantic` / `desiredGroup` によりsemantic groupingを表現できるが、`groupSemantic.category` が効くのはactive built-in taxonomyに存在する値に限られる (実装: `FullRunExecution.effectiveCategory` がtaxonomy membershipを検査し、非一致値を黙ってclassification decisionへフォールバックする)。#336でuser-defined categoryがfirst-class category identityになった後も、exchange側は #336 の規律によりuser-defined categoryを一切表現できない ([spec 336](../336-user-defined-categories/spec.md)「Exchange export and #331 binding projection」。`ContextExportBuilder` の `exportPresentationValue()` がuser-defined分類をabsent categoryへ一括redactionする)。

その結果、外部AIがユーザー固有の整理概念 (「AIツール」「通勤」「朝使う」等) を扱う手段は、既定カテゴリへの無理な寄せか、planner効果を持たないrun-local自由文のいずれかしかない。

本Issueは、#336のcategory modelをAI exchange側から利用するadapter / contract / UXを定義する。outcomeは次のモデルである:

1. **既存categoryの参照** — exportがactive category catalog (built-in + user-defined) をprivacy-safeに投影し、AIはexport-scopedなcategory refで既存categoryを参照できる。参照は表示名ではなくidentityに解決される。
2. **新規グループの提案** — AIはactive taxonomyに存在しないsemantic groupを、そのrun限定のproposal (bounded label) として表現でき、**そのrunのfolder形成keyとして実際に効く**。永続category identityではない。
3. **永続化は明示確認後のみ** — import・validation・plan・applyのいずれの成功もcategory catalogを変更しない。AI proposalを残したい場合、ユーザーの明示的操作のみが#336の通常authoring pathを通してpersistent categoryを作成する。AI専用のcategory writerは作らない。

#336はcategory identity / persistence / authoring / Planner integrationを所有し、本IssueはAI exchange / personalizationからそのcategory modelを利用する面を所有する。第二のcategory store / write pathは作らない。

## Why full refs (R-1の判断根拠)

v4では **すべてのcategory露出をexport-scoped refへ統一する** (built-inもuser-definedも `categories` のadvertised refを経由する)。

- v4は単一version runtimeで、v3文書とのbyte比較は契約に存在しない。したがって「built-in raw値のbyte互換」は互換性要件ではなく、担保すべき要件は「**planner効果の等価性**」(built-inのみのcatalog + refを使わないintentがv3と同じplanになること) である。
- 混在方式 (built-inはraw値、user-definedはref) は、untagged `String` fieldを「refかtaxonomy値か」で場合分けする規則をcodec / validator / instructionの3箇所へ要求し、namespace衝突規則も必要とする。これはまさに1st revisionで曖昧だった点であり、単一規則 (常にref) にすれば判別規則そのものが存在しない。
- refはitem refと同じ `RandomIdAllocator` seam・entropy契約で生成し、item ref / candidate ref / category refの3 namespaceで一意性を検査する (衝突はbuilder不変条件違反としてfail loudly)。

## Scope

- `PersonalizationContextExport` への **category catalog projection** 追加 (export-scoped ref、kind、tier制御付きname) と、item-level category投影の **ref一本化** (#336 redaction規律の意図的revision)。
- `PersonalizedIntent` の `groupSemantic` 改訂: `categoryRef` (既存category参照) と `proposalLabel` (run-scoped proposal) の **exactly-one-of** 契約、およびlabel値域の#336 name規則への統合。
- contract version bump (`personalization-context-v4` / `personalized-intent-v4`。spec 331 D-1 / spec 330 D-3と同じ手順)。unknown versionのfail-closed拒否は不変。
- import validationへのcategory ref厳格解決 (unknown / deleted / stale refのfail-closed、同名カテゴリへのfallback禁止)、export/import間のrename・deleteのstale semantics、desiredGroup component内のsemantic一意検査。
- run-scoped proposalの **formation key化** (folder形成・folder naming) と、proposalが効かないexecutor範囲の明示。
- import成功サマリ / previewにおける「既存categoryへの提案」「AI提案の一時グループ」「永続化済みカテゴリ」の区別表示。
- 明示promotion UX (「カテゴリとして保存」) の#336 authoring path (`UserDefinedCategoryAuthoringCoordinator`) 経由への接続。
- [spec 348](../348-exchange-ai-facing-contract/spec.md) 所有のAI-facing instruction (`IntentWireContract` descriptor派生) へのcategory / group representationの反映 (canonical templateは変更しない)。
- 上記のcontract / property / security / UI test要件と、代表的外部agent flowのdevice evidence要件。

## Non-goals

- AI出力のみによるcategoryの自動永続化 (作成・rename・delete・persistent assignment変更のいずれもimport成功の帰結としない)。
- category display nameをidentityとして扱うこと。display nameは常にpresentationであり、ref↔identity解決はexport sessionが保持するmappingのみで行う。
- unknown category nameの既存built-in categoryへのfuzzy mapping、およびunknown / deleted category refの同名カテゴリへのsilent fallback。
- AIによるbuilt-in categoryのrename / delete、built-in taxonomy membershipの変更。
- Planner strategy authorityのAIへの移譲。intentはpreference / grouping biasであり、`determinePreservation`・constraints・strategy選択 (`createsFolders` を含む) を弱めない (spec 204「plannerが正本」の不変)。proposalは既存strategyが使うformation keyの一種であり、strategyがfolderを作らない選択を覆せない。
- AI importと同時のlayout auto-apply。適用は #194 preview → #195 confirmation → 既存transactional apply pathのみ。
- #336 category store (`UserDefinedCategoryStore`)・override store (`CategoryOverrideStore`)・そのschemaの変更。#336 authoring contractの意味変更。
- exchange framing・envelope上限・privacy tierの枠組み変更 (#205所有。tierの適用対象fieldが増えるのみ)。
- #206 managed AI pathの実装 (単一契約のconsumerとしてv4を利用する)。
- marker framing抽出・#329 normalizerの変更 (#205 / #329所有。受理外形3種は不変)。

## Domain language (CONTEXT.md追加案)

**exportカテゴリ参照 (Export Category Reference)**:
1つのcontext export内でadvertiseされた1つの `CategoryIdentity` (built-inまたはuser-defined) を指す、export-scopedなopaque識別子。itemのexport-scoped `ref` と同一の乱数seam・entropy契約で生成ごとに新鮮に割り当てられ、対応付け (ref→`CategoryIdentity`) はexport sessionのみが保持する。stableな `UserCategoryId` も表示名もexport文書へ現れない。
_Avoid_: UserCategoryId (stable local IDとの混同)、category name (identityではない)

**既存カテゴリ参照 (Existing-Category Reference)**:
intentがexportでadvertise済みのcategory refを指す表現 (`groupSemantic.categoryRef`)。当該runのcategory preferenceとしてfirst-classに効く。表示名ベースの解決は存在しない。
_Avoid_: category指定 (v3までのraw `category: String` との混同)

**提案グループ (Proposed Group / proposalLabel)**:
intentが `groupSemantic.proposalLabel` (bounded label) で表現する、active taxonomyに存在しないsemantic group。そのrunの**formation key**としてfolder形成にだけ利用され (同じlabelを持つitemが1つのfolder候補groupになる)、永続category identityではない。import・適用のいずれもcatalogを変更しない。
_Avoid_: 新カテゴリ (永続化を想起させる。永続化はpromotionのみ)、freeText category

**昇格 (Promotion / カテゴリとして保存)**:
ユーザーが明示的に選んだ場合に、提案グループを#336の通常authoring pathでpersistent user-defined categoryへ保存する操作。AI専用writerは存在しない。
_Avoid_: 自動保存、AI保存

## 現行契約 (v3) の該当実装と、v4で変わる点

| 項目 | v3 (現main `34ba8ff4`) | v4 (本spec) |
|---|---|---|
| export envelope | `schemaVersion`/`exportId`/`tier`/`gridContext`/`items`/`preservedConstraints`/`capabilities`/`usageSignals` | `categories` (category catalog projection) を追加 |
| itemのcategory field | `ExportItem.category: String?` (built-in raw値。user-defined分類はabsent) と `ExportItem.groupSemantic: String?` (folderのsemantic投影) | `ExportItem.categoryRef: String?` と `ExportItem.folderCategoryRef: String?` — **どちらもadvertised refのみ**。raw built-in値・user-defined ID・名前はitem levelに現れない |
| intent `groupSemantic` | `{ category: String?, freeText: String? }` any-of。schema/validatorは値を検証せず、plannerがtaxonomy membershipを検査し非一致を黙って落とす | `{ categoryRef: String?, proposalLabel: String? }` **exactly-one-of**。validatorが厳格解決し、未知refはtyped reject。labelは#336 name規則の値域 |
| user-defined categoryのexport出現 | 一切なし (#336規律) | opaque ref (+ label-inclusive tierでのみ表示名) のみ。raw `UserCategoryId`の露出は不変に禁止 |
| intent `freeText` のplanner効果 | なし (どこからも消費されない) | `proposalLabel` として **formation key** に効く (D-6のmatrix範囲) |
| session | `itemRefs` (ref→`ItemId`)、digest、candidate scope等 | `categoryRefs` (ref→`CategoryIdentity`) を追加 |
| 失敗分類 | contract 13 class / UI 19種 | contract 15 class (+`UNKNOWN_CATEGORY_REF` / `CONFLICTING_GROUP_SEMANTIC`) / UI 21種 |

## Contract design (decisions)

### D-1: export-scoped opaque category refとcategory catalog projection (R-1で確定)

- export envelopeに `categories: List<ExportCategory>` を追加する。1 entryは:
  - `ref`: export-scoped opaque識別子。itemの `ref` / `exportId` と **同一の `RandomIdAllocator` seam・同一entropy契約** で生成ごとに新鮮な乱数 (spec 204「生成規則」の規律をcategoryへ拡張)。ref→`CategoryIdentity` の対応付けはexport sessionのみが保持し、backup対象外である。
  - `kind`: `BUILT_IN` / `USER_DEFINED`。
  - `taxonomyId: String?`: `kind == BUILT_IN` のときのみ非null。built-in taxonomyの不変値 (既存のitem.category投影と同値)。自由文classではないため **いずれのtierでも含む**。
  - `displayName: ExportCategoryName?`: `kind == USER_DEFINED` のときのみ意味を持つ自由文carrier。`FreeTextClass.USER_CATEGORY_NAME` (新規登録) を持ち、tier制御はこのclassのsingle-point controlで行う (`EXTERNAL_REDACTED` では存在しない)。
  - 不変条件: `kind == BUILT_IN ⇒ taxonomyId != null && displayName == null`、`kind == USER_DEFINED ⇒ taxonomyId == null && (displayName != null ⟺ tier != EXTERNAL_REDACTED)`。
- **advertise範囲はactive catalog全体** (built-in全件 + user-defined全件) とする。AIが「まだ分類されていないitemを既存categoryへ寄せる」判断をするには、参照可能なcatalogがexport時点で確定している必要がある (部分advertiseではitemに既に付いているcategoryしか使えない)。catalog読み失敗は #336 のtyped read-failureにより `OrganizationInput` composition自体がNotReadyとなり、exchange生成は既存の `ExchangeInputResult.NotReady` として失敗する (空catalogとして表現しない)。
- `categories` の順序はcanonical identity順 (built-inは`CategoryId`のbyte順、user-definedはstable IDのbyte順) とする。rename不変で、plannerのcanonical category順と同一規則である。
- ref namespace: category refはitem ref / candidate refと**別allow-list**で照合され、builderは3 namespace横断の一意性を検査する。intent側でもitem ref空間とcategory ref空間は別のallow-listとして扱う (`categoryRef` にitem refを書いても解決しない)。
- **stable `UserCategoryId` を直接exportしない**: stable local UUIDはcross-exportで安定な識別子であり、これを外部に載せることはspec 204がitemについて排除したcross-export unlinkability (「契約自身がstableなopaque identifierを提供しない」) をcategory面で裏切る。表示名もidentityではない (#336 identity model)。
- v4のitem-level fieldは `categoryRef` (そのitemの分類) と `folderCategoryRef` (itemが所属する既存folderのsemantic投影) の2つで、いずれもadvertised ref集合に属する (builder不変条件 + codec契約test)。
- catalog容量 (user-defined最大64件、#336) とbuilt-in taxonomyの全件により `categories` は既存content limits内に収まる。export size上限 (`MAX_EXPORT_BYTES` 256 KiB) の検査は既存builder pathがそのまま適用し、超過は既存typed `Oversize` でfail-closedする (容量testを追加)。

### D-2: name投影のprivacy tier (single-point control)

- built-in entryの `taxonomyId` はtaxonomy enum値 (自由文classではない) であり、**いずれのtierでも含む** (現行のitem category投影と同じ露出度)。
- user-defined entryの `displayName` はユーザー入力自由文class (`FreeTextClass.USER_CATEGORY_NAME`) に属し、#204のsingle-point tier controlに従う:
  - `LOCAL_FULL` / `EXTERNAL_WITH_LABELS`: display nameを含む (既存自由文上限200字内。値域は#336 name規則の1〜50 code points)。
  - `EXTERNAL_REDACTED` (既定): display nameを含まない。entryは `ref` + `kind: USER_DEFINED` のみをadvertiseする。
- **redacted tierで名前のないuser-defined categoryの扱い (確定decision)**: opaque refのみをadvertiseし、参照は許す。AIは「このカテゴリが存在し、どのitemが現在属しているか」を知れるが名前は知らない。ローカルのpreview / importサマリ / promotion surfaceは実名を表示するため、ユーザーは意味を確認してから受け入れられる。既定tierで機能を殺さないことを優先する。代替案 (redacted tierではuser-defined entry自体を省略し、機能をlabel-inclusive tierへ限定) は不採用: 既定tierで本Issueの価値 (既存のユーザー定義カテゴリを使う) が消え、AIはrefのみを知るitem群を意味不明なまま並べることになる。
- **残存risk (明示)**: user-defined entry数・kind・membership shape・(label-inclusive時の) 自由文名の組み合わせはstate fingerprintになり得る。これはtier選択 (ユーザー同意対象) に属するriskであり、脅威modelに明記する (unlinkabilityの確率的保証の外側)。
- membershipは専用の集計fieldを持たない: item-level `categoryRef` から読み取れる。集計の二重正本は作らない。
- 送信前確認 (Pre-send Disclosure) のcopyは、v4で「ユーザー定義カテゴリの参照情報 (名前を除く。label-inclusive tierでは名前を含む)」が含まれることを示す。

### D-3: item-level category投影のref一本化 (#336 redaction規律の意図的revision)

- v4では、resolved分類がuser-defined categoryであるitemも、built-inであるitemも、**advertised category ref** を `categoryRef` として投影する (v3の「user-definedはabsent」規律と「built-inはraw値」規律の両方を置き換える)。
- これは [spec 336](../336-user-defined-categories/spec.md)「Exchange export and #331 binding projection」(:97) と :105 の規律を、本Issueが所有するprivacy-reviewed projectionへ置き換えるrevisionである。raw `UserCategoryId`・display nameの文書露出禁止は不変。spec 336 の該当行・AC-14は、本specの実装PRでnormative更新する (spec 348 Decision 6と同じ「実装PR必須更新」扱い)。
- freshness digestへの影響なし: `SourceContextIdentity` / `CandidateScopeIdentity` はresolved `CategoryIdentity` (kind + stable ID) をdigest入力とする (#336)。export fieldのredaction緩和はdigest定義に触れない。reassignment / assigned-category deleteの検出は現行どおりである。
- `SessionExportReconstructor` は、sessionのcategory mappingと現行compositionのcatalogから、validation viewの `categories` とitem category refを同一規則で再構築する (spec 205 reconstruction-parity契約の延長)。再構築viewで **ref集合はsession mappingと1対1** (advertiseされたrefの追加・欠落がない) であり、name値はauthorityではない (renameは検証結果を変えない)。

### D-4: existing-category referenceとnew-group proposalの契約 (R-2 / R-3で確定)

- intent v4の `groupSemantic` は次の2 fieldの **exactly-one-of** とする:
  - `categoryRef: String?` — **ExistingCategoryRef**。同一exportの `categories` でadvertiseされたrefのみを指す。v3のraw `category: String?` を置き換える (field名変更を含む意味変更がversion bumpの主因の一つ)。
  - `proposalLabel: String?` — **ProposedGroupSemantic**。run-scoped proposalの label。v3の `freeText` を置き換える (名前変更は「自由文」ではなく「グループlabel」であることを契約上明示するため)。
- **exactly-one-of** の理由: v3のany-ofは「既存category ID」と「自由なsemantic grouping」を同一field空間で混在させ、どちらがgroupingのauthorityか一意に決まらない (1st revisionへの指摘R-2の核心)。両方setされた `groupSemantic` は意図が一意でないため受理しない。
- `proposalLabel` の値域は **#336のcategory name規則そのもの** とする: `UserDefinedCategoryNameRules.normalize` (trim + NFC) を適用したうえで `isValid` (空でない / 50 code points以下 / `|` を含まない / 改行を含まない) を満たすこと。これにより:
  - 「カテゴリとして保存」は正規化済みlabelを無変換で `create()` へ渡せる (R-3の不一致を構造的に消す)。
  - 生成されるfolder titleが既存のfolder title policy (非blank・fallback) と同じ値域で扱える。
  - v3の100文字freeTextからは上限が狭まる (意図的なtightening。v3ではplanner効果がなく、labelとしての用途もなかった)。
- 値域違反の失敗class: 長さ超過 (50 code points超) は既存 **`Oversize`** (content limit overshoot。v3の100字超と同じ扱い)、空 / `|` / 改行は既存 **`SchemaMismatch`** (文字列の形状違反。v3の `groupSemantic: {}` → `SchemaMismatch` と同じ扱い)。新しいclassは導入しない。
- `desiredGroup` (ref集合によるcohesion希望) はv3から不変。`desiredGroup` なしの単独 `groupSemantic` もv3どおり受理される。新しい自由文を既存category ID / refとして解釈する経路は存在しない。

### D-5: category refの厳格解決、stale semantics、component一意規則 (R-2で確定)

- import validationは、intent内のすべての `categoryRef` を次の順で解決する:
  1. 再構築されたexport viewの `categories` にadvertiseされたrefか。存在しないrefは **`UNKNOWN_CATEGORY_REF`** (新typed class、D-8) でreject。
  2. export sessionの `categoryRefs` (ref→`CategoryIdentity`) にidentityがあるか。sessionはadvertise済みrefのみを保持する (builder不変条件) ため、ここが空になるのはsession破損時のみで、同じく `UNKNOWN_CATEGORY_REF` とする。
  3. 解決した `CategoryIdentity` が、import時の同一composition cutから得たactive catalogに存在するか。存在しない (export後に削除された等) 場合も `UNKNOWN_CATEGORY_REF` でreject。**表示名が一致する別categoryへのremapは行わない**。
- **検証順序 (確定)**: expiry → export/session一致 → item/desiredGroup/unresolved ref解決 → **category ref解決 (`UNKNOWN_CATEGORY_REF`)** → **component semantic一意 (`CONFLICTING_GROUP_SEMANTIC`)** → duplicate → coverage → pageAffinity → mobility → structural digest (`CONTEXT_STALE`) → completion。category ref解決はstructural digest照合より **前** に走る。したがって割当を伴うcategory削除 (resolved identityが消える) は **`UNKNOWN_CATEGORY_REF`** として報告され、`CONTEXT_STALE` にはならない (削除以外の構造変化に対する `CONTEXT_STALE` は不変)。この優先順位は単一classを決定的に選ぶためのもので、test でpinする。
- **component semantic一意規則 (新規)**: `desiredGroup` のrelation graphを `{self} ∪ desiredGroup` の推移閉包でconnected componentへ閉じ (plannerの `intentComponentRanks` と同一規則・同一canonical順)、1 component内で **宣言されたsemanticは高々1種類** とする。すなわち「2つ以上の異なる `categoryRef`」「2つ以上の異なる `proposalLabel`」「`categoryRef` と `proposalLabel` の混在」は `CONFLICTING_GROUP_SEMANTIC` でrejectする。semanticを宣言しないcomponent member (cohesionのみのitem) は許容する。
  - 理由: 1つのgroup宣言 (`desiredGroup`) に対してAIが2つの意味を与えた場合、どちらを採用しても user の意図と一致する保証がない。fail-closedで再依頼する方が、2つの半端なgroupを無言で作るより安全である。
  - 決定性: componentはcanonical (sorted member key) に計算され、failureは当該componentのbyte最小refを伴う。
- stale semantics:
  - **rename (export→import間)**: identity不変のため新鮮。ref→identityは解決し、importは成立する。preview・folder titleは **composition時点のcatalog snapshot** の現在名で表示される (#336 title binding規律の継続)。export時点の古い名前でplanが固定されることはない (canonical plan bytesはidentityのみを運ぶ)。
  - **割当を伴うdelete**: 上の検証順序により `UNKNOWN_CATEGORY_REF` (remedyは再export)。既存のstructural digest照合も同じrunでstaleになるが、単一classとして前者を報告する。
  - **無割当delete**: 同じく `UNKNOWN_CATEGORY_REF`。
  - **catalog作成 (export後に新categoryを作成)**: resolved identity不変のため新鮮。advertiseされていない新categoryはintentから参照できない (refが存在しないため自然に `UNKNOWN_CATEGORY_REF`)。
- Planner接続: `IntentPlannerAdapter` は解決済み `CategoryIdentity` をpreferenceへ載せる (raw文字列をplannerへ渡さない)。`FullRunExecution.effectiveCategory` はidentityを直接消費するようになり、文字列membership検査と黙って落とす経路は廃止される (検証済みintentのみがadapterへ到達するため、planner側の防御は不要かつ有害でなくなる)。**determinism**: 同一accepted intent identity + 同一canonical planning inputs (catalog identityを含む `InputProvenance` 全体、#336) からdownstream planは決定的 (既存契約の再確認)。

### D-6: run-scoped proposalのPlanner利用境界 (R-2で確定)

proposal labelは **run-scoped formation key** であり、既存のcategory-based formation機構にそのまま乗る。新しいfolder機構・新しいstrategy権限は導入しない。

- **formation key**: folder作成を行うexecutorで、movable itemの形成keyを次の順で決める:
  1. `groupSemantic.categoryRef` がある → 解決済み `CategoryIdentity` (Existing key)。
  2. なければ `groupSemantic.proposalLabel` がある → `Proposed(normalized label)` (run-scoped key)。
  3. どちらもなければ → classification決定のidentity (現行どおり)。
- **実効範囲 (per-strategy matrix、現行実装に対する正確な記述)**:

  | executor (unitOrder) | 対象strategy | v3のintent category効果 | v4 |
  |---|---|---|---|
  | `executeCanonicalPageCompact` (`CANONICAL_TIE_BREAK`) | `CANONICAL_PAGE_COMPACT_V1`, `BOTTOM_FIRST_V1`, `BOTTOM_FIRST_V2` | formation key + 並び順tie-break (`sortCategory`) | formation key = Existing (現行どおり、identity基準化) / **Proposed (新規)**。並び順tie-breakはProposedを消費しない (下記) |
  | `executeGlobalCompact` (`CAPTURED_VISUAL_GLOBAL`) | `GLOBAL_COMPACT_V1`, `GLOBAL_COMPACT_V2` | なし (classificationのみでformation) | なし (不変。Existing / Proposedともformationに効かない) |
  | `executePageLocalLiftThenPlace` (`CAPTURED_VISUAL_PAGE_LOCAL` / `CATEGORY_CONTIGUOUS_PAGE_LOCAL`) | `STABLE_PAGE_TIDY_V1/V2`, `CATEGORY_CONTIGUOUS_V1` | なし (folder作成なし。並び順はclassification) | なし (不変) |
  | candidate tail (`appendCandidatePlacements`) | candidate配置 | なし | なし (不変) |

- **formationの力学は既存と同一**: `(profile, formation key)` でグループ化し、fallback identityのgroupは形成せず、`minGroupSize` / folder capacity / partition規則は現行のまま。proposal keyがfallbackと等しくなることはないため、proposal groupは通常のcategory groupと同じ条件でfolderになる。profileをまたぐproposalはprofileごとに分かれる (folderがprofileを跨げない既存不変)。
- **folder naming**: proposal groupから作られるfolderの `FolderNaming` は新しい `FromProposalLabel(label)` とし、title解決は既存 `FolderTitleResolver` (`withCompositionCatalog` を通る) の非blank契約・generic fallback方針のまま行う。ユーザーには空でないlabelがtitleとして表示・適用される (適用時は通常のapply pathがfolder titleとして書き込む)。
- **並び順 (category-based orderingとの関係)**: proposalはcategory identityではないため、canonical category order / `CATEGORY_CONTIGUOUS_PAGE_LOCAL` のordering入力には **ならない**。proposal itemのordering keyはそのitemのclassification identityのままで、これはv3の `freeText` がorderingに効かなかった挙動と同一である。既存category refは現行どおりtie-breakに効く。
- **cohesion**: 順序上の凝集は `desiredGroup` のconnected component rank (既存機構) が担う。proposal label単独は (formation以外の) 順序効果を持たない。strategyがfolderを作らないrunでは、proposalはpreferenceとして存在するだけでplanner効果を持たない (「効かない範囲」をmatrixで固定)。
- **決定性**: formation keyの全順序は `Existing(CategoryIdentity)` を先、`Proposed(label)` を後、labelはUTF-8 byte順とする。したがって既存のgroup ordinalはproposal追加で変化せず、proposalの有無だけが異なる2つのrunは既存部分で同一のplan bytesを持つ。同一labelは同一groupとして統合され、ordinalは決定的である。
- preview表示: proposal folderのtitleは既存preview change listのfolder titleとして表示される (AI専用のpreview truthは作らない、spec 204 / 205の原則)。

### D-7: 自動永続化の不存在と明示promotion

- **import成功だけでは何も保存しない**: intent validation成功・plan適用成功のいずれも、user-defined categoryの作成 / rename / delete、persistent assignment変更を行わない。構造的に、import〜apply pathはcategory storeへのwriterを持たない。この不変条件をproperty testで固定する。
- **promotion (「カテゴリとして保存」)**: import成功サマリ / run完了後surfaceから、ユーザーが提案グループをpersistent categoryへ昇格できるUXを提供する (任意・明示的操作)。
  - writerは #336 の `UserDefinedCategoryAuthoringCoordinator.create()` **のみ**。stable ID発行・atomic persistence・duplicate / name collision typed failure・capacity (64)・lease検査はすべて#336契約のものをそのまま使う。AI専用の別writer・validation成功への自動フックは作らない。
  - 昇格対象名は `proposalLabel` を正規化した値 (D-4により既に#336規則を満たす)。したがって `create()` が `InvalidName` で落ちる経路は存在せず、残るtyped failureは `DuplicateName` / `CapacityExceeded` / `OrganizationRunActive` (lease) / store系である。
  - **authority boundary**: 昇格は既存assignmentの一括再分類を行わない (それ自体は #99 override editorの通常操作として後から可能)。v1の昇格は「カテゴリの作成」までとし、作成後の割当は既存assignment UIで行う (確定decision。作成+一括割当を1操作にする拡張は不採用)。
  - **実行時機会**: #336のauthoring操作はsingle organization-operation leaseにより、activityなrun中はrejectされる (「reject/busy、no invalidation」)。したがってpromotionは (a) run非active時のimportサマリsurface、または (b) run完了後のsurfaceから提供し、activity run中の選択はtyped busy案内 (既存lease挙動) とする。run中の確認画面に置かない。
  - privacy: promotion操作・結果のdiagnostics記録は #336 diagnostics規律 (ID・display name非記録) に従う。proposal labelもjournal / diagnosticsへ記録しない。
  - 「今回だけ使う」が既定であり、何もしないことが「保存しない」である (import成功だけでは保存されない、をUI文言でも裏切らない)。

### D-8: failure分類・versioning・AI-facing instruction (R-4で確定)

- **新typed class** (contract 13 → 15):
  - `UNKNOWN_CATEGORY_REF`: D-5の条件。
  - `CONFLICTING_GROUP_SEMANTIC`: D-5のcomponent一意違反。
  - 既存classとの排他はD-5の検証順序で担保する。UI失敗表示は19種 → 21種 (ja/en copy追加)。`exchangeContractFailureText` のexhaustive `when` がコンパイル時の保証であり続ける (doc commentの「13-class」記述も更新)。
- **version bump**: `personalization-context-v4` / `personalized-intent-v4` への同時bump (exportがintent schema versionをadvertiseするため単独bumpは不可能、spec 330 D-3と同様)。dual-version runtimeは持たない。v1 / v2 / v3文書は `SCHEMA_MISMATCH` でfail-closed拒否。
  - 保存済み文書への影響はspec 330 D-3と同一: intent本文は永続化されず、export sessionはintent schema versionを保持しないため無影響。bumpによりv3 era session宛の返答は `SCHEMA_MISMATCH` となり再exportが必要 (TTL 24時間・single-active-sessionで一時的)。
  - **session record**: `AndroidExportSessionStore` のrecord (schema version 2 / `organizer_personalization_export_session_v2.json` / strict decode) へ `categoryRefs` を **additive field** として追加する。旧recordはdefault (空mapping) でdecodeされ、category refは解決不能 → `UNKNOWN_CATEGORY_REF` でfail-closedする (v3 era session宛のv4 intentは `SCHEMA_MISMATCH` / `EXPORT_MISMATCH` で先に落ちるため実害はない)。新recordを旧binaryが読む場合はunknown keyでdecode失敗 → 既存の「session無し」fail-closed挙動 (再export案内) となる。record version bump / file renameは行わない (additiveなfield追加であり、v2 recordの意味を変えない)。両方向の挙動をtestでpinする。
- **AI-facing instruction同期 (#348所有機構)**: `IntentWireContract` descriptorをv4のfield構成へ更新し、instructionは構造派生で次を反映する (canonical templateは **変更しない**、R-4):
  - Output contract: `groupSemantic.categoryRef` / `groupSemantic.proposalLabel` の型・exactly-one-of・`proposalLabel` の長上限、および「`categoryRef` はCONTEXT dataの `categories` 配列内の `ref` のみ」というref scope規則。
  - You must / self-check: 「category名や新しい名前をcategory IDとして書かない。既存カテゴリは `categoryRef`、新しい概念は `proposalLabel` で表現する」「未定義field (例: `grouping`) を作らない」。
  - canonical例について: spec 327 Decision 2 (`:135`) がjudgment-bearing optional field (`desiredGroup` / `groupSemantic` / …) をtemplateへ **意図的に掲載しない** と定めているため、本specはtemplateへのexample追加を行わない。Issue #337 AC-9の「canonical schema / example」は (a) descriptor派生のschema記述 (単一source、drift不能) と (b) production-enforced parity fixture + authoring policy matrixの受理fixture群 (`canonical authoring ⊆ production accepted`) で満たす。templateのminimality / non-seeding oracle (spec 327 AC-1、`Issue327InterviewFirstContractTest`) は回帰testとして不変を固定する。
  - production-enforced parity fixture: 未知 `categoryRef` → `UNKNOWN_CATEGORY_REF`、両field同時指定 / 空label → `SCHEMA_MISMATCH`、長すぎるlabel → `OVERSIZE`、component衝突 → `CONFLICTING_GROUP_SEMANTIC` をproduction path (`ExchangeImportPipeline.import`) でpinningする。
- spec 204 / 205 / 336 / 348へのnormative更新・change history追記は、本specの **実装PRで必須** とする (spec 348 Decision 6の前例)。特に: spec 204 (groupSemantic定義・content limits表の `groupSemantic` 行 → 50 code points / change historyのv4追記)、spec 205 (Data and stateの #204由来制約の実名更新)、spec 336 (export投影規律の改訂 + AC-14の実装後記述 + status注記)、spec 348 (descriptor/instructionの現状記述)。

## Privacy / security

- **export新fieldの自由文分類**: `categories[].displayName` (user-defined時) のみが新たな自由文class対象 (`FreeTextClass.USER_CATEGORY_NAME`) であり、既存single-point tier controlに登録される (`EXTERNAL_REDACTED` では存在しない)。built-in `taxonomyId` はtaxonomy enum (既存扱い)。item category ref・kindは自由文ではない。
- **unlinkability**: category refはitem refと同一乱数seam・entropy契約であり、cross-exportで安定なcategory識別子は契約として提供しない。session外にref↔identity対応は残らない。残存risk (catalog shapeのfingerprint) はD-2に明示し、脅威model更新対象とする。
- **prompt injection**: user-defined display nameとproposal labelはdataでありinstructionではない (既存脅威modelの適用対象へ明示追加)。labelは値域 (長さ・文字) だけが規則であり、**「promptらしさ」のheuristic判定は導入しない** (1st revisionのplanにあったsecurity oracleは削除した)。AIが名前文字列を `categoryRef` として返してもallow-list照合で `UNKNOWN_CATEGORY_REF` としてfail-closedし、名前→categoryの解決経路は存在しないため、名前からの一括category捏造は構造的に受理されない。
- **diagnostics**: category ref・display name・catalog内容・proposal labelをdiagnostics / journalへ出さない ([organizer-diagnostics.md](../../docs/engineering/organizer-diagnostics.md) 規律の継承)。sessionはapp-private・backup対象外で、載るのはref→identityのみ (名前・labelを載せない)。
- **適用時のfolder title**: proposal labelはユーザーがpreviewで確認したplanの一部として、通常のapply pathがfolder titleへ書き込む (home画面でユーザーに見える入力済みデータ)。これはcategory永続化ではなく、診断・journal・exportへの露出も増やさない。
- 外部送信は既存Pre-send Disclosure (生成済みpackage対象・同一immutable value・送信前確認) の枠内。v4の新fieldも同じ生成済みpackageに含まれ、確認なしの送信経路は増えない (copy更新はD-2)。

## Stale state / concurrency

- export生成は既存single canonical composition seam (`ExchangeInputAdapter` → `OrganizationInputComposer`) の1回のcutから得たcatalog snapshotを使う。export時点のcatalog identity (generation / digest) は、`InputProvenance` の既存catalog参加を通じてrun provenanceへ現れる (#336)。
- import時の検証用catalogは、structural digest再計算と同一composition cutから得る (既存pipeline順序: envelope → framing → decode → session → expiry → digest照合 → reconstruction → validation)。D-5の検証順序に従い、category ref解決はdigest照合の後・validation内でdigest比較より前に走る。
- promotionを含むcategory authoringとrun操作の相互排除は既存lease domain (#336) に従う。本specは新しいlease・新しい排他機構を導入しない。strategy書込との相互排他は #328 の `StrategyWriteArbiter` 契約のまま (新しいwriterを追加しないため変更不要)。

## Behavior scenarios

### Scenario: 既存user-defined categoryの参照 (正常系)

**Given** ユーザーが #336 で「通勤」categoryを作成し、Maps/Suicaを #99 override editorで割り当てている、
**When** `EXTERNAL_REDACTED` でexchange packageを生成し、AIがMaps/Suicaのrefsに「通勤」をadvertiseされたcategory refで指す `groupSemantic.categoryRef` を返してimportする、
**Then** intentは受理され、当該runで「通勤」(`CategoryIdentity.UserDefined`) がcategory preferenceとしてfirst-classに効く、
**And** previewには「通勤」という実名で提案が表示され (ローカル表示)、AIへは名前が渡っていない、
**And** import・適用のいずれもcatalogを変更しない。

### Scenario: run-scoped proposalが一時folderを形成する (R-2の一般ケース)

**Given** `CANONICAL_PAGE_COMPACT_V1` (または `BOTTOM_FIRST_V1/V2`) が選択され、異なる既存カテゴリに属する Maps / Suica / Transit があり、AIが3件の `groupSemantic.proposalLabel: "朝使う"` (およびcohesion希望の `desiredGroup`) を返した、
**When** importしてpreviewする、
**Then** 3件は1つのrun-scoped formation keyとして扱われ、`minGroupSize` / capacity を満たす限り **新しいfolderが形成され、そのtitleが「朝使う」** になる、
**And** category-based orderingの入力にはならず (ordering keyは各itemのclassification identityのまま)、user-defined catalogには何も作られない、
**And** import成功サマリは「AI提案の一時グループ」として既存category参照と区別して表示する。

### Scenario: proposalが効かない範囲 (negative)

**Given** strategyが `GLOBAL_COMPACT_V1/V2`、`STABLE_PAGE_TIDY_*`、`CATEGORY_CONTIGUOUS_V1` のいずれか (matrix上proposalのformation効果がない)、
**When** 同じ `proposalLabel` を返してimportする、
**Then** intentは受理されるがformation / folder titleには効かず、`desiredGroup` がある場合のみ既存のcohesion (順序) 効果を持つ、
**And** この差はstrategy選択の帰結であり、proposalがstrategy権限を覆うことはない。

### Scenario: display nameの捏造・未advertise名の参照

**Given** AIが `groupSemantic.categoryRef: "通勤"` (名前文字列) やadvertiseされていない値を返した、
**When** importする、
**Then** `UNKNOWN_CATEGORY_REF` でfail-closed rejectされ (zero-write)、失敗説明は再export・canonical form再依頼を案内する、
**And** 名前に類似するcategoryへのfuzzy解決・silent fallbackは発生しない。

### Scenario: redacted tierでのuser-defined entry

**Given** `EXTERNAL_REDACTED` 選択時、
**When** exportが生成される、
**Then** `categories` のuser-defined entryは `ref` と `kind` のみを含み、display nameを含まない、
**And** built-in entryはtaxonomy enum値を `taxonomyId` として含む、
**And** 送信前確認画面には「ユーザー定義カテゴリの参照情報 (名前を除く)」が示される。

### Scenario: exactly-one-ofとcomponent一意の違反

**Given** AIが1つの `groupSemantic` に `categoryRef` と `proposalLabel` の両方を書いた、または1つの `desiredGroup` component内で異なるsemantic (「通勤」と「仕事」、categoryRefとproposalLabelの混在) を宣言した、
**When** importする、
**Then** 前者は `SCHEMA_MISMATCH`、後者は `CONFLICTING_GROUP_SEMANTIC` でrejectされ (どちらもzero-write)、再依頼の案内が出る、
**And** 部分的に採用されたgroupは存在しない。

### Scenario: export→import間のrename

**Given** export後にユーザーが「通勤」を「交通」へrenameした (割当は不変)、
**When** export時のcategory refを参照するintentをimportする、
**Then** identityは不変のためimportは成立し (digest・ref解決とも新鮮)、
**And** preview / folder titleはcomposition時点catalog snapshotから「交通」と表示される、
**And** canonical plan bytesはrenameの前後で変化しない。

### Scenario: category delete

**Given** export後に「通勤」がdeleteされた、
**When** そのcategoryを参照するintentをimportする、
**Then** 割当の有無にかかわらず `UNKNOWN_CATEGORY_REF` でrejectされる (D-5の検証順序により、割当を伴う場合も `CONTEXT_STALE` ではなく本classが決定的に選ばれる)、
**And** remedyは再exportであり、同名の新categoryが存在してもそれへのremapは発生しない。

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

**Given** 同一accepted intent identityと同一canonical planning inputs (同一catalog content) がある、
**When** downstream planを再構成する、
**Then** planはbyte-deterministicであり、category rename (identity不変) が直前runのcanonical plan bytesを変えない (#336規律の継続)、
**And** proposalが存在しないrunのplanはv3と同じ規則で形成される (既存部分のbyte不変)。

### Scenario: 自動永続化の不存在 (property)

**Given** validation成功・適用成功を含む任意のimport経路の実行、
**When** category catalog・override storeの状態を比較する、
**Then** 両storeは無変更である (promotionの明示操作を除く全経路でzero-write)。

### Scenario: 旧version文書の拒否

**Given** `schemaVersion: personalized-intent-v3` (またはv1/v2) の文書がimportされる、
**When** decodeされる、
**Then** `SCHEMA_MISMATCH` でfail-closed rejectされ、再exportが案内される (spec 330 D-3と同一の移行扱い)。

## Accessibility

- import成功サマリ・preview・promotion確認・typed失敗表示の新規 / 変更UIは、既存 #205 / #332 surfaceと同じbarを満たす: TalkBack labels / roles、focus restoration、keyboard / DPAD・Switch Access操作、非色依存の状態表現 (既存category / 一時提案 / 永続済みの区別を色だけにしない)、large font (200%)、long localized names。
- 「カテゴリとして保存」の結果表示 (成功・重複・capacity・busy) はtypedかつlocalized (ja正本 + en)。
- raw category ID (UUID含む) はユーザーUIへ一切出さない (#336規律)。proposal labelはAIが提案した表示文字列であり、そのまま表示してよい。

## 互換性 / migration

- category参照・proposalを使わないv4 intent (全 `categoryRef` / `proposalLabel` null) と、user-defined catalogが空の環境のv4 exportは、**planner効果が現行v3と同一** である (wire形式はv4のref形式に変わる)。
- Launcher DB schema・favorites・recovery契約の変更なし。永続化の増分はexport session recordへの `categoryRefs` (app-private・backup対象外・additive) のみ。
- downgrade: 旧binaryはv4 session recordの新fieldを読めず「session無し」のfail-closedとなる (spec 330 D-3と同一の扱い。旧binaryが生成したv3 recordを新binaryが読むとref解決不能となるが、そのsession宛のv4 intentは存在しない)。
- 既存run (intentなし) のplan・provenance・UIは不変。

## Acceptance criteria

- [ ] **AC-1**: export contextが #336 active category catalogをprivacy-safeに表現する。`categories` はexport-scoped random ref (item refと同一乱数seam・entropy契約) + kind + `taxonomyId` (built-in) + tier制御付き `displayName` (user-defined) を含み、canonical identity順に並び、refはitem ref / candidate refと横断的に一意であること、redacted tierでuser-defined nameが文書に現れないこと (raw `UserCategoryId` の走査を含む) がcontract testで検証される。
- [ ] **AC-2**: v4のitem-level category露出は `categoryRef` / `folderCategoryRef` のrefのみであり、raw built-in値がitem levelに現れない。全ての非null refがadvertise済みref集合に属する (builder不変条件 + codec round-trip)。
- [ ] **AC-3**: existing-category reference (`categoryRef`) とrun-scoped proposal (`proposalLabel`) が契約上区別され (exactly-one-of)、表示名からcategory identityを解決する経路が存在しないこと (validator / planner / adapterのunit test)。
- [ ] **AC-4**: proposalがrun-scoped formation keyとしてD-6のmatrixどおりに効く。folder形成strategy (canonical-family) では新しいfolder titleがlabelになり、matrix上効果のないstrategy (GLOBAL_COMPACT / page-local / candidate tail) ではformation・titleに効かず、ordering入力にもならないことがplanner unit / property testで検証される。
- [ ] **AC-5**: `proposalLabel` の値域が#336 name規則と同一であり、受理されたlabelは正規化済みで `create()` に無変換で渡せる (property test: accepted label ⇒ 正規化後 `isValid`)。長さ超過は `Oversize`、空 / `|` / 改行は `SchemaMismatch` でrejectされる。
- [ ] **AC-6**: proposalのimportはuser categoryを自動作成しない。validation・plan・applyの全経路でcatalog / override storeが無変更であることのproperty test。
- [ ] **AC-7**: deleted / stale / unknown category refはfail-closed (`UNKNOWN_CATEGORY_REF` / 既存 `CONTEXT_STALE` 経路) し、同名カテゴリへのsilent fallback・fuzzy remapが存在しないことのcorpus test。D-5の検証順序 (category ref解決 → component一意 → … → digest) と、delete時の単一class決定を含む。rename成立 / 現名表示のstale testを含む。
- [ ] **AC-8**: component一意規則が固定される。1 component内の異なるcategoryRef / 異なるlabel / 混在は `CONFLICTING_GROUP_SEMANTIC`、両field同時指定・空labelは `SCHEMA_MISMATCH`、長さ超過は `Oversize` (validation corpus test、zero-write)。
- [ ] **AC-9**: ユーザーが明示的に選んだ場合のみ#336 authoring path (`UserDefinedCategoryAuthoringCoordinator.create`) でpersistent categoryへ保存できる。AI専用writerが存在しないこと、重複・capacity・busy (lease) がtyped表示されること、作成後に既存assignment UIで割当できることのUI / instrumentation test。昇格labelが `InvalidName` で落ちないこと。
- [ ] **AC-10**: import成功サマリ / previewで「既存built-in category参照」「永続化済みuser-defined category参照」「AI提案の一時グループ」を区別でき (summaryはkind別countのみを追加し、#328の「label / ref / free-text fieldを持たない」形状保証を維持する)、import成功が「保存済み」に見える表示にならないことのUI test。
- [ ] **AC-11**: `personalization-context-v4` / `personalized-intent-v4` への同時bump、v1 / v2 / v3の `SCHEMA_MISMATCH` 拒否、session recordのadditive `categoryRefs` (旧record受理 / 新recordの旧binary fail-closed) がcodec / store contract testで検証される。
- [ ] **AC-12**: #348 descriptor由来のinstructionにv4のcategory / group schemaが反映される (`categoryRef` のref scope規則、`proposalLabel` の型・上限、exactly-one-of、捏造禁止のYou-must / self-check)。descriptor↔codec↔instructionの同期test、新規則のproduction parity fixture (`UNKNOWN_CATEGORY_REF` / `SCHEMA_MISMATCH` / `OVERSIZE` / `CONFLICTING_GROUP_SEMANTIC`)、canonical fixture受理 (canonical ⊆ accepted) を含む。**canonical templateは変更されず**、spec 327のnon-seeding / placeholder oracle (`Issue327InterviewFirstContractTest`) が回帰として緑であること。
- [ ] **AC-13**: 同一accepted intent + 同一category catalog identityからのdownstream planがdeterministicであること (既存property suiteのmixed-catalog拡張)。category renameがcanonical plan bytesを変えないこと、proposalの有無だけが異なるrunで既存groupのordinalが不変であることの再確認testを含む。
- [ ] **AC-14**: spec 204 (groupSemantic定義・content limits・change history) / 205 (Data and state) / 336 (exchange投影規律・AC-14・status注記) / 348 (descriptor現状記述) のnormative更新とchange history追記が実装PRで完了する。`CONTEXT.md` 用語 (D-1〜D-4の4語) と `DESIGN.md` の該当gate記述の更新を含む。
- [ ] **AC-15**: 代表的なExternal Agent flow (「既存custom category利用」「新規group提案」「保存しない」「明示保存」) のdevice evidenceが取得される (privacy境界はspec 348 Decision 7と同じpolicy: sanitized artifactのみ)。
- [ ] **AC-16**: 新規 / 変更UI surfaceのaccessibility evidence (TalkBack、focus、keyboard / DPAD、Switch Access、非色区別、200% font) がある。

## Test oracle

| AC | Evidence |
|---|---|
| AC-1 | `ContextExportBuilder` contract test (tier matrix、`categories`順序、ref乱数性 / uniqueness / 3-namespace横断、name redaction走査) + codec round-trip + 容量test |
| AC-2 | builder不変条件test + codec round-trip + 文書走査 (raw built-in値・user ID非出現) |
| AC-3 | codec / validator / planner adapter unit test (exactly-one-of、名前解決経路の不在) |
| AC-4 | planner unit / property test (formation key matrix、`FromProposalLabel` naming、ordering非参加、strategy別negative、ordinal安定性) |
| AC-5 | label値域test (property: accepted ⇒ #336 `isValid`)、`Oversize` / `SchemaMismatch` 境界test |
| AC-6 | store不変のproperty test (import pipeline経由、apply経路含む) |
| AC-7 | validator corpus test (unknown / rename / delete matrix、検証順序、単一class決定) + `SessionExportReconstructor` parity test |
| AC-8 | validator corpus test (component一意、both-field、空label、長さ) |
| AC-9 | `UserDefinedCategoryAuthoringCoordinator` 接続のintegration test + UI test (typed failures、lease busy) |
| AC-10 | `ExchangeImportSummary` unit test (kind別count、形状保証) + importサマリ / preview UI test |
| AC-11 | codec contract test (version拒否) + `AndroidExportSessionStore` 両方向test |
| AC-12 | `Issue348AiFacingContractSyncTest` 方式のdescriptor / instruction同期test + parity fixture (production path経由) + `Issue327InterviewFirstContractTest` 回帰 |
| AC-13 | planner determinism / idempotence property (mixed catalog、rename不変、ordinal安定) |
| AC-14 | spec / docs diff (実装PR内) |
| AC-15 | device evidence記録 (`docs/assessment/`) |
| AC-16 | accessibility instrumentation / manual evidence |

## Resolved decisions (旧 Open questions)

1. **D-2 redacted tierのuser-defined entry**: opaque ref + kindのみをadvertiseする (確定)。代替 (entry省略) は既定tierで機能を失うため不採用、残存riskは明示。
2. **D-6 proposalの効果**: run-scoped formation keyとしてfolder形成に効き、labelがfolder titleになる (確定)。「ordering biasのみでfolderを作らない」代替は、Issueの一般ケース (異なるカテゴリのアプリを「朝使う」でまとめる) を表現できず、1st revisionの矛盾そのものであるため不採用。
3. **D-4 exactly-one-of**: 採用 (確定)。両field同時指定を許すとgroupingのauthorityが一意にならない。
4. **D-5 component一意規則**: 採用 (確定、fail-closed)。
5. **D-7 promotionの操作範囲**: v1は「カテゴリ作成のみ」(割当は既存UI) (確定)。
6. **D-7 promotionの提示位置**: run非active時のimportサマリ / run完了後surface (確定)。activity run中の確認画面には置かない。
7. **`UNKNOWN_CATEGORY_REF` を独立classにするか**: 独立classを採用 (確定)。remedyが「カテゴリが消えた / refが不正」であり、item refの `UNKNOWN_REF` とは案内・診断が異なる。
8. **`CONFLICTING_GROUP_SEMANTIC` を独立classにするか**: 独立classを採用 (確定)。両field同時指定 (形状) の `SCHEMA_MISMATCH` とは原因も remedy も異なる。

## 依存関係

| 依存先 | 状態 | 関係 |
|---|---|---|
| #336 (spec 336) | **implemented** (PR #341、`45711f53dd40`) | `CategoryIdentity` / `ActiveCategoryCatalog` / `UserDefinedCategoryStore` / authoring coordinator / export投影規律 / stale規律の正本。本specはその利用面を所有し、store / authoring契約を変更しない。export投影規律の改訂 (D-3) は本specが所有し、実装PRでspec 336を更新する |
| #204 (spec 204) | accepted・実装済み | context / intent契約の正本。本specはv4への意図的拡張を所有し、spec 204へのnormative更新を実装PRで行う |
| #205 (spec 205) | implemented | framing・envelope・Pre-send Disclosure・session置換の所有者。変更なし (表示・field追加の延長のみ) |
| #330 (spec 330) | implemented (PR #335、`dce8f5779c8e`) | v3 partial authoring。v4でもomission / unresolved意味は不変。versioning手順の前例 |
| #331 (spec 331) | implemented | candidate subject・scope binding gate。category投影改訂時もscope gateのcandidate投影digestはidentity基準のまま不変 |
| #348 (spec 348) | implemented (PR #349、`8fd05a40d5`) | AI-facing instruction / descriptorの所有者。本specのinstruction変更はこの機構を通す。spec 348自体のnormative更新を実装PRで行う |
| #327 (spec 327) | **implemented** (PR #350、`a9ec3c2cf9`) | interview-first instructionとcanonical template (judgment-bearing optional fieldを掲載しない) の所有者。本specはtemplateを変更せず、回帰testで不変を固定する |
| #328 (spec 328) | implemented (PR #353、`34ba8ff447`) | import成功サマリ (`ExchangeImportSummary`)・`StrategyWriteArbiter`。AC-10のsummary拡張とpromotion提示面の土台 |
| #332 (spec 332) | implemented | import UI (parse state表示)。サマリ差別化表示の実装面の土台 |

**Source implementationの開始条件**: 本specのacceptance (Owner review) を必須とする。#336 / #330 / #348 / #327 / #328 由来のcontract前提は解消済みである。

## References

- [Issue #337](https://github.com/nunu1733/NunuLauncher/issues/337)
- [Spec 336: user-defined categories](../336-user-defined-categories/spec.md) (implemented。identity / catalog / store / authoring / export投影の正本)
- [Spec 204: AI personalization context/intent contract](../204-ai-personalization-context-intent-contract/spec.md) (accepted・実装済み)
- [Spec 205: external agent exchange](../205-external-agent-exchange/spec.md) (implemented)
- [Spec 330: partial intent authoring](../330-partial-intent-authoring/spec.md) (implemented。v3手順の前例)
- [Spec 331: exchange target scope coupling](../331-exchange-target-scope-coupling/spec.md) (implemented)
- [Spec 348: exchange AI-facing contract](../348-exchange-ai-facing-contract/spec.md) (implemented。descriptor / instruction機構)
- [Spec 327: agent exchange interview-first](../327-agent-exchange-interview-first/spec.md) (implemented。canonical template minimality)
- [Spec 328: exchange import success state](../328-exchange-import-success-state/spec.md) (implemented。summary)
- [Spec 329: import normalizer](../329-import-normalizer/spec.md) (implemented。受理外形)
- [Spec 332: exchange import input UI](../332-exchange-import-input-ui/spec.md) (implemented)
- [Spec 99: user-authored category overrides](../99-user-authored-category-overrides/spec.md)
- [ADR-0007: authoritative organization policy sources](../../docs/adr/0007-authoritative-organization-policy-sources.md)
- [organizer-diagnostics.md](../../docs/engineering/organizer-diagnostics.md)
- [requirements.md](../../docs/product/requirements.md) (FR-017)
- 実装調査対象: `lawnchair/src/app/lawnchair/organizer/personalization/` (契約・codec・validator・adapter・exchange pipeline)、`organizer/planning/CategoryIdentity.kt` / `FolderFormation.kt` / `FullRunExecution.kt` / `LayoutStrategyRegistry.kt` / `PlanningResult.kt` (FolderNaming)、`organizer/rules/UserDefinedCategoryStore.kt` (name規則)、`organizer/ui/UserDefinedCategoryAuthoring.kt`、`organizer/ui/GeneratedFolderTitles.kt`、`organizer/integration/AndroidExportSessionStore.kt`、`organizer/integration/exchange/ExchangeInputAdapter.kt` / `ExchangeFlowController.kt`
