---
issue: "#451"
status: draft
requirements:
  - FR-021
  - NFR-014
updated: 2026-09-30
---

# 全体整理が同じ起動先の重複アイテムを同じ新規フォルダに入れず、重複をpreviewで示す

> Risk tier: H（本spec draftの提案判定。最終判定は実装時に [docs/project/github-workflow.md](../../docs/project/github-workflow.md) Risk tiers の基準で確定し、実装PR本文へも記載する）。理由: planner（純粋計画module）の出力契約を変えるspec 12 amendmentであり、適用経路・recovery契約は変えないが計画結果（適用pathが永続化するlayout data）が変わるため、spec 12不変条件（P-09/P-10）の再証明とbyte-equivalence oracleの再固定を伴う。高リスクpath一覧は `organizer/application/**` を含み `organizer/planning/**` を明示的に除外しているため、path一致による `high-risk-gate` の自動発火は想定しない。tier Hとして扱う場合は `risk: layout-data` labelの明示的付与で独立エビデンス契約を適用する。
> Epic: #439（再焦点化）。出典: 再焦点化方針メモ（2026-09-24承認、Revision 5。以下「メモ§x」）§4.5（R-7 Now「#451 重複」の確定事項）、§4.9（FR-021）。
> ベンチマークの正本: [editing-burden-benchmark](../../docs/engineering/editing-burden-benchmark.md)（#441で確定・implemented。baseline B7 = 重み付き操作コスト9、操作数5。B7の目標は本specが確定する）。
> 本specは [spec 12](../12-deterministic-full-layout-planner-v1/spec.md)（implemented）のamendmentであり、P-03/P-04/P-07/P-08/P-10/P-12への変更を本Issueが所有する。spec 12本文の該当節の改正は実装PRで行う。

## Problem

2026-09-23の実機記録で、全体整理が同じアプリの重複アイコンを同じ新規フォルダに入れた（新規フォルダ「Photography」にPhotos×2、「Productivity」にGmail×2。`evidence-work/03-preview.png` の変更一覧と `evidence-work/04-after.png` で確認。未commitの実機画像、Issue本文参照）。

原因は、plannerがアイテムを `ItemId`（favorites行のID）でしか識別せず、同じ起動先を持つ複数アイテムを扱う概念がないことである。起動先を表すidentityはplanner入力に既に存在する:

- `CapturedItem.target` は `TargetKey` で、アプリは `AppKey(component, profile)`、deep shortcutは `ShortcutKey(packageName, shortcutId, profile)`（`lawnchair/src/app/lawnchair/organizer/planning/OrganizationInput.kt`）。
- この `TargetKey` はcapture段でDB行・`ItemInfo` のintentから組まれ、planner入力に届く（`lawnchair/src/app/lawnchair/organizer/application/adapter/RowManifestCodec.kt` の `targetKey`、`ModelProjectionCodec.kt` の `targetKey`、`lawnchair/src/app/lawnchair/organizer/integration/OrganizationInputComposer.kt` の `mapItem` が `CanonicalItemState.targetKey` を `CapturedItem.target` へそのまま渡す）。

新規フォルダのgroup化は `(profile, category)`（spec 12 P-04。実装は `FormationKey`（`lawnchair/src/app/lawnchair/organizer/planning/FolderFormation.kt`）で `(profile, CategoryIdentity | proposalLabel)` へ一般化済み）で行われ、同一起動先の2個のアイテムは同じcategoryに分類されれば同じgroupに入る。group化の前段（`FullRunExecution.kt` の3箇所と `PlanningPlacement.kt` の `appendCandidatePlacements` からの `formFolderGroups` 呼び出し）でもtarget keyによる重複の排除・警告は行われていない。`RejectionCode.DUPLICATE_TARGET` はtarget set内の同一 `ItemId` 重複（V-06/V-19系）であり、起動先の重複は検出しない。

影響: 利用者は重複を見つけて1つにする手間（ベンチマークB7。baseline 9）を負い、整理を「散らかりを増やすもの」と知覚する。

## Outcome

全体整理（およびスコープ合成整理のfull-run phase）の計画は、同じ起動先（アプリは component + profile、deep shortcutは package + shortcut id + profile。`TargetKey.AppKey` / `TargetKey.ShortcutKey` の値等価性）の複数のアイテムを、同じ新規フォルダのメンバーにしない。重複のうち代表を超える分（後述の「重複超過分」）はcaptured位置にそのまま保持され、previewの変更一覧で項目警告行として識別できる。除外は `(input, result)` の純粋関数として決定的に行われ、materialize後の再実行での冪等性（spec 12 P-10）は維持される（FR-021、メモ§4.9）。

本Issueは重複の「発生防止」であり、既存の重複の「発見と削除」の操作コストを直接下げるものではない。B7との関係と本Issueが確定する目標は「B7目標」節のとおり。

## Scope

- **重複の定義と検出**: captured item集合における起動先の重複の検出規則（「重複の定義」節）。plannerの入力のみから計算し、新しい入力sourceや権限は追加しない。
- **新規フォルダ形成からの除外とその場保持**: 同一起動先の重複のうち、代表（canonical順で最初の1個）を除く全アイテム（重複超過分）を、full-run再編成（`FullOrganization` と `ScopeComposedOrganization` のfull-run phase）のmovable streamから外し、captured位置に `Preserved` で残す（メモ§4.5案1の確定）。移動させないため、単体として再配置する案2（メモ§4.5で不採用）とはならない。
- **previewでの重複の表示**: 重複超過分を既存の項目警告行の経路（plannerのtyped warning → `ItemWarningChange`。spec 195の変更種別grouping「警告」）で示す。
- **決定性・冪等性の維持**: 除外は入力のみから決まり、同入力→同出力（P-09）、materialize後の再実行で全target不変・`Moved`/`newPages`/`newFolders`なし（P-10）。P-04/P-05のgroup化規則への反映とP-10の再実行証明への取り込みを含む。
- **version契約**: 不具合修正として扱い、`StrategyId` / `RuleSemantics` / strategy catalog構成は変更しない（ADR-0012「A behavior change is a new ID」には触れない。strategyのsemantics（unit order、page scope等）は変えないため）。policy bundleのsemantic versionはADR-0007 §8の手順で1つ上げる。byte-equivalence oracle（`CANONICAL_PAGE_COMPACT_V1`、`tests/unit/resources/planner-golden-corpus/sha256.txt`）の扱いは「version契約とoracle」節のとおり。
- **B7目標の確定**: 本specが確定する（「B7目標」節）。`docs/engineering/editing-burden-benchmark.md` の目標表への反映は実装PRで行う。

## Non-goals

- 重複アイコンの削除提案（planner/applicationへの削除dispositionの追加）。Next（RF-next backlog）。現行のplannerは `Disposition.Moved` / `Preserved` のみを持つ。
- 既存フォルダ内の重複の解消、既存フォルダへの重複追加の防止（spec 12 P-04「No item joins an existing folder in v1」。既存フォルダは intact unit）。
- widget、legacy shortcut、folder、app pairの「起動先の重複」への定義拡張（`TargetKey.LegacyShortcutKey` / `WidgetKey` / `FolderKey` / `AppPairKey` は重複判定の対象外。widgetは provider + appWidgetId で一意）。
- 候補追加（`TargetSet.additions`）側の重複の扱い。productionの選択面（spec 228）は `ComponentKey` + `ProfileId` の安定identityで「ホーム未配置」を判定するため、captured itemと候補、候補同士の起動先重複はproduction経路では構成されない。harness等で同一 `CandidateTarget` を複数のadditionに与えた入力は、本変更の対象外（検証も除外もしない）とする。
- 重複の発生自体の防止（新規installが重複を作る問題はADR-0015 / #446と上流 `SessionCommitReceiver` の範囲。メモ§4.4）。
- ベンチマークB7の計測・fixtureの整備（#441が所有。implemented）。
- `IncrementalPlacement` の配置計算の変更（captured item全保全は既存どおり。本変更がIncrementalPlacementの結果に与える差分は「preservation reason語彙の付け替え」のみ。後述）。

## Domain language

ドメイン用語の正本は [CONTEXT.md](../../CONTEXT.md) の既存語「**重複アイテム (duplicate item)**」（同一起動先を持つ複数の配置アイテム。B7の対象）を使う。本specは新しいドメイン用語を `CONTEXT.md` に追加しない。以下は実装・契約語彙であり、本specが正本である:

- **起動先 (launch target)**: captured itemの `TargetKey` のうち `AppKey`（`component: ComponentKey` + `profile: ProfileId`）と `ShortcutKey`（`packageName` + `shortcutId` + `profile`）の2種のみ。等価はdata classの値等価（profileを含むため、同一componentのpersonal/workは重複ではない）。
- **重複集合 (duplicate set)**: 同一起動先を持つcaptured itemの集合。サイズ1の集合は重複ではない。
- **代表 (representative)**: 重複集合を `ItemId` のcanonical順（UTF-8 byte順。spec 12 / `Identity.kt` の既定の比較）で並べた最初の1個。代表は通常どおり計画される（特別扱いしない）。
- **重複超過分 (duplicate surplus)**: 重複集合から代表を除いた全アイテム。

## Normative rules（spec 12 amendment）

### N-1: 重複の検出（純粋関数）

plannerは `Planned` を返す全run modeで、captured item集合（`LayoutSnapshot.items`）から重複集合を計算する。種別は `APPLICATION` と `DEEP_SHORTCUT` のみ（`TargetKey.AppKey` / `ShortcutKey`）。その他のkindは重複判定に参加しない。代表は `ItemId` canonical順で最初の1個。この計算は入力のみから決まり、strategy選択・分類結果・偏好（intent preferences）に依存しない。

### N-2: 新規フォルダ形成からの除外（P-04/P-05 amendment）

full-run再編成（`FullOrganization`、`ScopeComposedOrganization` のfull-run phase）において、重複超過分は新規フォルダのeligible memberにもmovable workspace unitにもならない。すなわち:

- 重複超過分は `ExistingRole.Movable` かつ他の保全predicateに掛からない場合、`PreserveReason.DUPLICATE_LAUNCH_TARGET`（新規語彙）でcaptured位置に保持される。移動・単体再配置は行わない（メモ§4.5案1）。
- 除外はgroup化（P-04）・分割（P-05）の前に行われる。groupのサイズ判定（`minGroupSize`、fallback category）は除外後のmembersで行う。
- 代表は除外されず、通常どおりgroup化・配置される。group内のmember順（`ItemId` 順）は変更しない。

P-04の規則文は「eligible members are top-level, available, unlocked `APPLICATION` or `DEEP_SHORTCUT` items with `ExistingRole.Movable`」に「かつ重複超過分でない」を追加する形で改正する。

### N-3: preservation precedence（P-03/P-07 amendment）

`PreserveReason.DUPLICATE_LAUNCH_TARGET` は既存の全保全predicate（RESERVED_REGION、LOCKED、UNAVAILABLE_TARGET、DOCK、WIDGET、APP_PAIR、LEGACY_SHORTCUT、NON_TARGET、STRUCTURAL）より低い優先度で、`ALREADY_CANONICAL` より高い位置に置く。重複超過分がより高いpredicateに掛かる場合（locked、dock、既存フォルダ内等）は既存のreasonが勝つ。重複超過分が保持される場合、配置計算はそのcaptured占有cellを保全cellとして扱う（他のunitの配置先にならない）。

`IncrementalPlacement` では、captured itemの全保全は既存どおりだが、重複超過分のreasonは `ALREADY_CANONICAL` に代えて `DUPLICATE_LAUNCH_TARGET` を返す（P-08 note。検出（N-1）はmode共通の観察であるため）。

### N-4: 警告（P-12 amendment）

重複超過分1個につき、`Warning(DUPLICATE_LAUNCH_TARGET, [ItemParam(item)])` を1件返す。代表には警告を出さない。警告はstrategyの `createsFolders` やrun modeによらず、重複が存在すれば常に発生する（入力の観察であるため。既存の `LEGACY_SHORTCUT_REVIEW` / `UNAVAILABLE_PRESERVED` と同じ性格）。`WarningCode` の列挙順では既存3値の末尾に追加し、既存警告のcanonical順序を変えない。診断は `ItemParam` のみで構成し、component名・package名等のraw dataを載せない（P-12）。

### N-5: 決定性と冪等性（P-09/P-10 の維持）

- 除外の決定は `(input)` の純粋関数であり、locale・thread・列挙順に依存しない（P-09）。
- 代表の選択を `ItemId` canonical順とするのはmaterialization安定性のためである。`ItemId`（favorites行ID）は移動・folder参加で不変であるため、materialize後の再実行でも同じitemが代表のままになり、重複超過分は同じ `DUPLICATE_LAUNCH_TARGET` で同じ位置に保持される。P-10の「exact preservation reason」はこの語彙を含めて再証明する。
- メモ§4.5は代表の選択を「視覚順（ページ、行、列）で最初」と定めていたが、視覚順はmaterialize後に安定しない（代表が新規フォルダへ入った際、フォルダ単位の配置先ページが変わると視覚順が逆転し、再実行で代表と超過分が入れ替わり得る。P-10違反）。よって本specは代表を `ItemId` canonical順で確定する。これはメモ確定事項の文言からの意図的な逸脱であり、accept時にownerの確認を要する（Open questions参照）。

### N-6: preview表示

重複超過分は既存の項目警告行としてpreviewへ現れる（planner warning → `PlanPreviewProjector` の `capturedWarnings` → `ItemWarningChange`。join契約の変更は不要。超過分は `ApplyAction.Preserve` を持つためjoinは必ず成立する）。`PreviewCounts.warningCounts` に含まれ、spec 195 D2のwarning group（見出し件数は `ItemWarningChange` 行数）に表示される。追加の行種別・画面は作らない。

### N-7: version契約とoracle

- 不具合修正として扱い、`StrategyId`・`RuleSemantics`・runtime-supported strategy集合・default strategyは変更しない（ADR-0012）。bundleのsemantic versionは `organization-policy-v2.7` → `v2.8` とし、`BuiltInOrganizerPolicyBundleSource` / `PolicyModels` のversion定義と注記を更新する（ADR-0007 §8。strategy enablementの先例と同じ1 increment）。
- byte-equivalence oracle（`GoldenOracleCorpus`。 pinned digest `tests/unit/resources/planner-golden-corpus/sha256.txt`）: 既存corpus（example fixtures・validation fixtures・generated property corpus）には同一起動先の重複を含むfixtureが存在しないことを本spec作成時の調査で確認済みであるため、既存fixtureに対する出力はbyte不変である。実装では重複を含むfixtureをexample corpusへ追加し、digestを理由付きで再固定する（手順は `GoldenOracleCorpusTest` の `-Dgolden.write=true`）。追加前の全既存fixtureの個別digest（`digestsBySource`）が不変であることをtestで示し、「既存入力への影響なし」を証明する。

## Behavior scenarios

### Scenario: 同一カテゴリの重複が同じ新規フォルダに入らない

Given ページ1にPhotos（`ItemId` "10"、PHOTOGRAPHY）、ページ2にPhotos（`ItemId` "2"、PHOTOGRAPHY）があり、両方ともtop-level・available・unlocked・Movableである,
When 全体整理を実行する,
Then 代表は `ItemId` canonical順で "2" の方であり、新規PHOTOGRAPHYフォルダのmemberになる（`Moved(FOLDER_MEMBER)`）
And "10" は `Preserved(DUPLICATE_LAUNCH_TARGET)` でcaptured位置（ページ1）に留まる
And 出力に `Warning(DUPLICATE_LAUNCH_TARGET, [ItemParam("10")])` が1件含まれる
And 代表 "2" には警告がない。

### Scenario: 重複の一方が既に保全predicateに掛かる

Given Photos（locked、ページ1）とPhotos（Movable、ページ2）の重複があり、`ItemId` 順でlocked側が代表である,
When 全体整理を実行,
Then locked側は `Preserved(LOCKED)` のまま（N-3の優先度）
And もう一方（重複超過分）は `Preserved(DUPLICATE_LAUNCH_TARGET)` でcaptured位置に留まり、警告1件が出る
And どちらも新規フォルダに入らない。

### Scenario: 除外でgroupが最小サイズを下回る

Given 同一 `(profile, category)` のeligible memberが3個で、そのうち1個が重複超過分、`minGroupSize = 2` である,
When 全体整理を実行,
Then 除外後の2個で新規フォルダが形成される（P-05は除外後のmembersに適用）
And 重複超過分は `Preserved(DUPLICATE_LAUNCH_TARGET)`。

Given 同一 `(profile, category)` のeligible memberが2個で、そのうち1個が重複超過分、`minGroupSize = 2` である,
When 全体整理を実行,
Then 除外後の1個ではフォルダは形成されず、代表は通常の単体として配置される
And 重複超過分は `Preserved(DUPLICATE_LAUNCH_TARGET)`。

### Scenario: materialize後の再実行（P-10）

Given 重複（ Photos "2" と "10"）を含む入力で全体整理のplanが適用され、"2" が新規フォルダ内、"10" がcaptured位置に残っている,
When 同じ状態でもう一度全体整理を実行する,
Then 全てのtargetは不変であり、`Moved`・`newPages`・`newFolders` は空である
And "10" は前回と同じ `DUPLICATE_LAUNCH_TARGET` reasonで保持される（代表は引き続き "2"。folder memberは `STRUCTURAL`）。

### Scenario: previewでの識別

Given ベンチマークfixture（`editing-burden-benchmark` §5。重複2組）のような重複を含むホームで全体整理を実行する,
When previewの変更一覧を表示する,
Then 重複超過分の各行が警告groupに項目警告行として現れ、対象アイテムの現在位置が分かる
And 警告groupの見出し件数は表示された `ItemWarningChange` 行数と一致する（spec 195 D2）
And 重複超過分は保持行でも表示され、保持理由が重複であることが分かる。

### Scenario: スコープ合成整理とincremental

Given スコープ合成整理（`ScopeComposedOrganization`）でcaptured側に重複がある,
When planを作成する,
Then full-run phaseは `FullOrganization` と同じ除外・保持・警告を受ける
And 候補追加（additions）のfolder形成・配置は本変更の影響を受けない（Non-goals参照）。

Given `IncrementalPlacement` でcaptured側に重複がある,
When planを作成する,
Then 全てのcaptured itemがcaptured位置に保持されるのは既存どおりで、重複超過分のreasonは `DUPLICATE_LAUNCH_TARGET`、その他は既存のreason/`ALREADY_CANONICAL` のままである。

### Scenario: 対象外のkindとcross-profile

Given 同一providerのwidget 2個、同一folderId参照、app pairメンバー、legacy shortcutのみの入力,
When 全体整理を実行する,
Then 重複の検出・除外・警告はいずれも発生しない（`WidgetKey` / `FolderKey` / `AppPairKey` / `LegacyShortcutKey` は対象外）
And 同一componentでもprofileが異なる2個のappは重複として扱われない（spec 12 L-19の挙動は不変）。

## B7目標（本Issueが確定する）

`docs/engineering/editing-burden-benchmark.md` §6のB7行に相当する目標を次のとおり確定する（実装PRで同文書の目標表へ反映する）:

- **不変条件型目標（構造保証）**: 全体整理（full-run再編成）の適用後、任意の新規フォルダ内で同一起動先のmember数は常に0組の重複である。つまり「整理runが新たに同じ新規フォルダへ重複を入れる」ことが構造的に発生しない（N-2/N-5の性質 test で検証）。
- **可視性目標**: 重複を含むホームでの新規runのpreviewにおいて、重複の存在が警告行で識別できる（重複超過分の行数 ≥ 1/組）。
- 本Issueは重複の「発見と削除」の操作コスト（B7の手動削除コスト、baseline 9）を直接削減しない。削除提案はNext（RF-next backlog）であり、B7の操作コスト目標の確定はそちらが所有する。上記2目標は#441メモ§12 B3の例示に沿う形で、B7の発生源を計画段階で排除することを確定値とする。

## Data and state

- 読むdata: planner入力（`OrganizationInput.snapshot.items` の `target`）のみ。新しい読み込みや権限は追加しない。
- 永続化するdata: なし。警告とpreservation reasonはplan（revision付きの一時artifact）にのみ乗る。
- migration / backup / restore / recoveryへの影響: なし。適用経路・recovery契約・DB schemaは変更しない。policy bundleのsemantic version上げ（v2.8）はADR-0007 §8の既存手順内で、保存storeのmigrationを伴わない。
- layoutを扱う安全規約（AGENTS.md）との関係: 本変更は純粋計画moduleの変更であり、適用moduleの契約（transaction、recovery point、適用後検証）は不変。計画結果が変わるため、spec 12の不変条件（特に7決定性・8冪等性）の再検証をACに含める。

## Permissions, privacy, and security

None。追加permission・外部送信・sensitive dataなし。警告は `ItemParam`（opaque `ItemId`）のみで、P-12のとおりcomponent・package等のraw dataを診断へ出さない。`TargetKey` は既にplanner入力に存在する値であり、新しい情報流を作らない。

## Accessibility and localization

- 新しい `WarningCode.DUPLICATE_LAUNCH_TARGET` と `PreserveReason.DUPLICATE_LAUNCH_TARGET` に対応する文言をja / en両localeで用意する（spec 195 AC-6と同一の要求。`warningText` / `preservedReasonText` のexhaustiveな対応）。
- 警告行は既存の項目警告行の表現契約（spec 195 / spec 208の行表現。先頭source descriptor、展開actionのfocus保持）に乗せ、新しい行種別・朗読規則を作らない。文言は「このアイコンは同じ起動先の重複であるため、整理では移動させずに残した」意味が伝わるものとし、削除を促す表現にしない（削除提案は本Issueの対象外のため）。

## Acceptance criteria

- [ ] AC-1: planner interface（`OrganizationPlanner.plan`）経由のtestで、同一 `TargetKey.AppKey` / `ShortcutKey` の重複超過分が同じ新規フォルダのmemberにならないこと（重複を含むfixtureで、各新規フォルダ内の起動先が一意であること）を検証する。
- [ ] AC-2: 代表選択が `ItemId` canonical順で決定的であること（同入力→同出力、permutation・locale・threadで値等価。P-09）、およびmaterialize後の再実行で全target不変・`Moved`/`newPages`/`newFolders`なし・同一preservation reason（`DUPLICATE_LAUNCH_TARGET` 含む。P-10）を検証する。
- [ ] AC-3: 重複超過分1個につき `Warning(DUPLICATE_LAUNCH_TARGET, [ItemParam])` が1件、代表に0件であること、警告が `createsFolders` の値によらず発生すること、および `WarningCode` の追加が既存警告のcanonical順序を変えないことを検証する。
- [ ] AC-4: 除外がP-03の優先度（より高い保全predicateが勝つ）とP-05の分割（除外後のmembersでサイズ判定）に正しく反映されること、`IncrementalPlacement` では配置が不変でreason語彙のみ付け替わることを検証する。
- [ ] AC-5: 既存corpusの個別digest（`digestsBySource`）が全て不変であること、および重複fixture追加後の再固定digestが理由付きでcommitされていることを検証する。
- [ ] AC-6: policy bundleのsemantic versionが `v2.8` に上がり、strategy catalog（runtime-supported集合、default）と `StrategyId` が不変であることを検証する。
- [ ] AC-7: previewの変更一覧で重複超過分が警告groupの項目警告行として表示され、件数truth（spec 195 D2）と保持行の理由表示が一致することを、単体test（`PlanPreviewProjector` 経由）と実機確認で検証する。ja / en両localeで文言が解決する。
- [ ] AC-8: B7との関係（本specのB7目標節）が実装PRで `docs/engineering/editing-burden-benchmark.md` の目標表へ反映され、#441のbaseline（B7 = 9）と矛盾しない。
- [ ] AC-9: 対象ACの検証コマンド（unit/contract test、spotless、debug assembly、`git diff --check`）が成功し、結果がPRに記録される。

## Test oracle

| AC | Evidence |
|---|---|
| AC-1, AC-2, AC-4 | `tests/unit/app/lawnchair/organizer/planning/` の新規fixture test（重複を含むexample corpus追加を含む）と `harness/PostPlanMaterializer` を使ったP-10再実行test |
| AC-2 | 既存の `PlannerGeneratedPropertyTest` / determinism testと同じseamでの重複入り入力の決定性test（生成器拡張または重複注入wrapper） |
| AC-3 | planner出力の警告列の直接検証 + 既存corpusでの警告順序不変（AC-5と同test面） |
| AC-5 | `GoldenOracleCorpusTest`（digest再固定）と個別digest安定test |
| AC-6 | rules moduleのbundle test（version文字列とcatalog内容） |
| AC-7 | `PlanPreviewProjector` 経由のunit test（`tests/unit/app/lawnchair/organizer/application/preview/`）+ 実機スクリーンショット（owner確認。tier判定に従う）+ ja/en文言のresource test |
| AC-8 | docs差分のreview（`editing-burden-benchmark.md` 目標表） |
| AC-9 | PR本文への実行結果記録 |

## Open questions

- **代表選択の `ItemId` canonical順採用（メモ§4.5「視覚順」からの逸脱）**: N-5に記載のとおり、視覚順はmaterialize後に安定せずP-10と両立しないため、本draftは `ItemId` canonical順を提案する。accept時にownerがこの逸脱を承認するか、P-10を維持した別の安定順（例: 視覚順をcapture時点で確定させる追加情報の導入）を指示する必要がある。実装開始前に解消することが望ましいが、本draftの規則は自己完結している（どちらの結論でもN-1〜N-4の他の規則は不変）。
- （参考・blockingではない）tier判定の最終確認: Risk tier引用ブロック参照。実装PRでの `risk: layout-data` label の要否をownerが確定する。

## Change history

- 2026-09-28: Draft created for #451（spec/plan準備task。`origin/main` `c5a7840b880ed4c436b67170930ca87d4ef7f148` 基準のcode調査に基づく）。
- 2026-09-30: Re-entry — `origin/main` `092c44b46e7c6074f0623b146cc975d9ec862e53`（#449/#450着地後）へ再基準化。planning module・benchmark B7 baseline（9）・FR-021 status（proposed）を再確認し、本文の陳腐化なし（文書変更なし。確認範囲と詳細は [plan.md](./plan.md) の「Re-entry記録」参照）。
