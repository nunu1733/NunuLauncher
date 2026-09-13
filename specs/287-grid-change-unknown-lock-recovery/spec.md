---
issue: "#287"
status: implemented
requirements:
  - FR-003
  - NFR-002
  - NFR-007
  - NFR-011
updated: 2026-09-13
---

# グリッド変更後もOrganizerの配置ロックreviewが完結し、input unavailableから回復できる

## Problem

グリッド設定変更の後、Organizerは `INPUT_NOT_READY` / `CAPTURE_UNKNOWN_LOCK`
で恒常的に失敗し、ユーザー操作でもプロセス再起動でも回復できない (#287)。
Issueコメントの実機調査 (2026-09-12, Pixel 9a, main HEAD `d0f40446c7`) が
メカニズムを特定した。失敗は単一の欠陥ではなく、正しい設計2つの合間に
ある回復導線の1か所の判定欠陥である。

1. **グリッド移行のUNKNOWN化は設計通り。** グリッド移行は
   `GridSizeMigrationUtil#markOrganizerLocksUnknown` により全 `favorites` 行の
   `organizerLockState` を `UNKNOWN` (0) にする (ADR-0004)。移行後の配置は
   移動・再作成され得るためlock truthは証明できず、UNKNOWN化とreviewによる
   再確認が正規の経路である。状態はfavoritesに永続化されるため、実機で確認
   されたとおりプロセス再起動では消えない。
2. **composerのfail-closedも設計通り。** `OrganizationInputComposer` は
   capture中に1行でも `UNKNOWN` があれば `CAPTURE_UNKNOWN_LOCK` で
   fail-closedする (spec #83 / #172)。この意味論は変更しない。
3. **回復導線がフォルダ内項目に対して完結しない (本Issueの欠陥)。**
   配置ロックreview画面はunknown行を正しく表示するが、実際の書込み判定
   `LockAuthoringDecision.fitsProfile` がフォルダ子の配置を
   `rank < folderMaxColumns * folderMaxRows` (フォルダ1ページ分のcell数) で
   上限切りし、超過rankを `PLACEMENT_OUT_OF_PROFILE` として拒否する。
   実際のプラットフォームではフォルダ内容はページングして表示される
   (`FolderPagedView#addViewForRank` の `pageNo = rank / maxItemsPerPage`)。
   1ページ容量は表示のpage分割に使われるだけで、loaderが永続化した
   フォルダメンバーのrank (capacity以上を含む) はそのままloadされ、
   範囲外のセルへ割り当てられることはない。一方グリッドpresetは
   workspaceより小さいフォルダgridを宣言する
   (`res/xml/device_profiles.xml`: `5_by_5` は4×4=16、`6_by_5` は3×3=9)。
   グリッド変更でcaptureされる容量が既存メンバーのrankを下回ると、
   そのメンバーは個別review・一括reviewのどちらでも解消できなくなる。
   実機では unknown 125件のうち上位47件のみ解消でき、残り78件
   (すべてフォルダ内項目) が「このアイテムはロックできません。」で拒否され、
   `CAPTURE_UNKNOWN_LOCK` が恒常化した。
4. **表示と実効の不一致。** review画面のlistingとdialogの事前説明は
   行を決める際にkind判定 (およびlisting membership) のみを参照し、
   書込み判定 (`evaluateChange` / `evaluateReviewBatch` の
   `sharedRejection`) は追加でbounds判定をする。この分岐の結果、一括確認は
   「78件の配置を…」と表示しながら全体atomicityにより0件しか適用しない。

## Outcome

グリッド変更で全行がUNKNOWN化しても、ユーザーは配置ロックreview画面だけで
すべてのunknown行 (フォルダ内項目を含む) を解消でき、同一プロセスでの次の
Organizer起動が新profileへのfresh captureでpreviewに到達する。フォルダ子の
review可能性はプラットフォームの実際のフォルダ意味論 (ページング表示) に
一致し、グリッドpresetの1ページ容量に依存しない。グリッド変更前のcaptureに
由来するreview書込みは、既存のrevision/precondition機構により新profile
世代のlayoutに対して拒否され、旧世代のstateが新世代を上書きすることはない。
#83/#172のfail-closed意味論、ADR-0004の永続化とmigration規約、
spec #38の一括review atomicityはすべて不変である。

## Scope

- `LockAuthoringDecision` のbounds判定から、フォルダ子placementに対する
  1ページ容量 (`folderMaxColumns * folderMaxRows`) の上限を削除する。
  `rank >= 0` の検証は保持する。workspace cell / Dock rankのbounds判定、
  kind/container actionability判定、profile判定は不変である。
- review画面がlistingするunknown行のうち、書込み対象として適格な行
  (本specの「listingと書込み判定の一致」scenarioで定義する述語) が
  実際の書込み判定でも受け入れられることを、回帰testで固定する
  (listing/explainと書込み判定の表示・実効不一致の当該インスタンスの封じ込め)。
- 実DB・実loader・実composerを使う同一プロセスの自動回帰
  (organizer実行 → 復元 → in-processグリッド変更 → `CAPTURE_UNKNOWN_LOCK`
  → review解消 → preview再到達) をinstrumentation testとして追加する。
- 実機で確認された再現シーケンス (Nova restore → Organizer適用 → 復元 →
  グリッド変更 → preview失敗 → review解消 → preview成功) をemulatorで検証し、
  `docs/assessment/` に証跡を記録する。

## Non-goals

- グリッド移行時の `markOrganizerLocksUnknown` の挙動変更。migrationが行を
  UNKNOWN化すること自体はADR-0004 / spec #38の設計であり、lock truthの
  証明不能を正直に表す正しい挙動である。
- `OrganizationInputComposer` のreadiness判定・fail-closed動作の変更
  (spec #83 / #172が正本)。
- 一括reviewの全体atomicity (全部か無書込みか) とexact preconditionの変更
  (spec #38が正本)。
- writer (`LockStateDbAdapter`) のtransaction・revision再読取・precondition
  機構の変更。stale世代の拒否はこの既存機構が担う (「Data and state」参照)。
- 利用不可profileのunknown行やunsupported container行など、本欠陥以外の
  理由で一括確認が全体拒否になる場合の文言・UX一般化。fail-closedとして
  正しい挙動であり、必要なら別のUX Issueへ分離する。
- capture側の例外・`CAPTURE_INVALID` 系の追跡 (#299)、Nova restore中の
  thread-affinity違反 (#298)。別系統として分離済み。
- diagnostics journal/exportのschema追加 (profile/grid寸法のjournaling等)。
  既存の #172 診断surfaceで本欠陥の観測は十分であった。
- planner・apply・recovery経路の変更。plannerは既存メンバーのrankを
  容量でgateしない (容量は新規フォルダ形成のgroup sizeのみに使用)。

## Domain language

新しいdomain語は追加しない。フォルダ1ページのcell数がフォルダの保持容量を
意味しないことは既存の「配置 (Placement)」の定義内の事実であり、
`CONTEXT.md` への追加は不要である。

## Behavior scenarios

### Scenario: グリッド変更後のunknown reviewがフォルダ内項目を含めて完結する

Given グリッド変更により全 `favorites` 行が `UNKNOWN` になり、
Organizerは `CAPTURE_UNKNOWN_LOCK` でfail-closedしている。
レイアウトには、変更後のcaptureが報告する1ページ容量を超えるrankを
持つフォルダメンバーが存在する。
When ユーザーが配置ロックreview画面で、そのフォルダ内項目を個別に
reviewする (「ロックしたままにする」または「ロック解除に設定」)。
Then その行の `organizerLockState` のみが選択stateへ1トランザクションで
変化し、unknown件数が1減る。
And 書込みは既存のwriter seam (`LayoutWriteCoordinator` lease、
in-transaction revision再読取、exact precondition) を通る。

### Scenario: 一括reviewが容量超過rankのメンバーを含めて成立する

Given review画面のunknown listingに、1ページ容量を超えるrankの
フォルダ内項目が含まれる。
When ユーザーが「すべてロック解除として確認」(または「すべてロックとして
確認」) を選び、確認dialogの件数と同じ行集合に対して確認する。
Then listingされた全行が1トランザクションで選択stateになり、unknown件数は
0になる。
And 確認dialogが表示した件数と、実際に状態が変化した件数が一致する。

### Scenario: グリッド変更後に同一プロセスでOrganizerが回復する

Given organizer適用と復元が成功した同一プロセスで、グリッド変更を
production経路 (グリッドpreference変更とIDP再計算による実migration) で
実施し、全行が `UNKNOWN` になった。
When すべてのunknown行をreviewで解消した後、同一プロセス内で再度
Organizerの整理案確認を実行する。
Then composerは新profile (グリッド変更後の端末能力) に対するfresh
captureを取り、previewへ到達する。`CAPTURE_UNKNOWN_LOCK` は記録されない。
And composerのcaptureはcomposition毎に取り直されるため、プロセス再起動や
profile世代の無効化といった追加機構は不要である。

### Scenario: グリッド変更前のcaptureは新世代のlayoutを書換えない

Given グリッド変更前に取得したcaptureを `sourceRevision` に持つ
review書込みplan (単一・一括のいずれか) が存在する。
When production経路のグリッド変更 (全行のUNKNOWN化を含むlayout書換) の
後に、そのplanをwriterへ渡す。
Then transaction内のrevision再読取が `STALE_REVISION` を検出し、
呼出側には `STALE_CAPTURE` が返り、layout・lock stateは一切変化しない。
And revisionはcanonical layout state全体 (lock stateを含む) の内容digest
であるため、UNKNOWN化を含む移行は必ずrevisionを変え、旧captureのplanは
exact preconditionが偶然一致する場合でもrevision不一致で拒否される。

### Scenario: fail-closed意味論は不変

Given 1行でも `UNKNOWN` が残るレイアウト。
When Organizerの整理案確認を実行する。
Then `INPUT_NOT_READY` / `CAPTURE_UNKNOWN_LOCK` でfail-closedする
(spec #83 / #172と同一)。
And 負のrankを持つフォルダ子のreview要求は引き続き
`PLACEMENT_OUT_OF_PROFILE` で拒否され、書込みは発生しない。
And 利用不可profileの行を含む一括reviewは引き続き全体拒否し、
部分書込みは発生しない。

### Scenario: review listingと書込み判定の一致 (書込み適格行の述語)

Given captureされた任意のレイアウト。
When `LockReviewListing` および `lockStateListing` のunknown区分が
listingした行のうち、次の述語をすべて満たす行集合を考える:
(a) `ApplicationItemRef.PersistentItem` である、
(b) kindが既知である (`CanonicalItemKind.Unknown` でない)、
(c) placementがsupported containerである
    (`PlacementState.UnsupportedContainer` でない)、
(d) profileが利用可能である、
(e) placementが端末能力内である
    (workspace cell / Dock rank / フォルダ子 `rank >= 0` / app pair)。
Then その各行は、実際の書込みと同じ `LockAuthoringDecision` 判定で
受け入れられる (intentとrevisionは別契約)。
And listingが、この述語を満たす行をbounds理由で拒否することはない。
And 述語を満たさない行 (負rank、unsupported container、利用不可profile等)
の扱いは既存のfail-closed意味論のままである。

## Data and state

- 書込み経路・列・transaction境界の追加変更はnone。本specは判定
  (`LockAuthoringDecision`) のみを変更し、`LockStateDbAdapter` /
  `LayoutWriteCoordinator` / recovery store / journalには触れない。
- **世代越しの上書き防止は既存機構が担う。** `LockStateDbAdapter.write` は
  transaction内でcaptureを取り直し、`before.revision != plan.sourceRevision`
  を `STALE_REVISION` で拒否し、さらに各行のexact precondition
  (期待行の完全一致) を検証する。revisionは
  `RevisionCalculator.revisionOf` によるcanonical layout state全体の
  内容digestであり、全行UNKNOWN化を含むグリッド移行は必ずrevisionを
  変える。したがってグリッド変更前のcaptureに由来する単一・一括planは
  新世代で必ず拒否され、旧世代のstateが新世代を上書きする経路は存在しない。
  本specはこの機構の回帰testを追加するのみで、実装は変更しない。
- migration・backup/restore・downgradeへの影響はnone
  (ADR-0004 / spec #59の規約は不変)。
- home layout安全規約の適用対象 (配置の変換) は含まない。lock stateのみの
  既存review書込み (spec #38確立済み) を通るため、recovery point新設は
  不要である。

## Permissions, privacy, and security

none。permission・network・telemetry・diagnostics schemaの追加はない。

## Accessibility and localization

新規のuser-facing文字列は追加しない。既存のreview dialog・一括確認・
結果messageが容量超過rankのフォルダ内項目に対してそのまま機能する。
TalkBack到達性はspec #38の既存実装 (UI testで検証済み) を変更しない。

## Acceptance criteria

- [x] **AC-1 — フォルダ子のreview可能性:** loaderが永続化し得るフォルダ子の
  rank (1ページ容量 `folderMaxColumns * folderMaxRows` 以上を含む、
  `rank >= 0`) が、個別review・一括reviewのどちらの経路でも
  `LockAuthoringDecision` に受け入れられ、書込みは `organizerLockState`
 のみを変更する。負のrankは引き続き拒否される。JVM testで固定する。
- [x] **AC-2 — 表示と実効の一致:** review listingがlistingするunknown行の
  うち「書込み適格行の述語」scenarioで定義した行 (persistent ref・既知kind・
  supported container・利用可能profile・能力内placement) は、実際の
  書込み判定でも受け入れられる。JVM testで固定する。
- [x] **AC-3 — 実DB reviewと旧世代拒否:** 実DB上で (a) 1ページ容量超過の
  rankを持つフォルダメンバーが `markOrganizerLocksUnknown` によるUNKNOWN化
  後にreview経由で解消でき、再captureのlock stateがUNKNOWN 0になること、
  および (b) 移行前captureに由来する書込みplanが移行後に `STALE_CAPTURE`
  で拒否され無変更であること。instrumentation test (実DB、実migration
  primitive、実writer) で固定する。
- [x] **AC-4 — 同一プロセス自動回帰:** 実DB・実loader・実composerを使う
  instrumentation testが、同一プロセス内で organizer実行 (preview到達) →
  復元 → production経路のグリッド変更 → `CAPTURE_UNKNOWN_LOCK` での
  input unavailable → review解消 (容量超過rankのフォルダメンバーを含む) →
  preview再到達、を実行し各状態をassertする。
- [x] **AC-5 — fail-closed不変:** 既存のcomposer/readiness・lock authoring
  test群が意味論変更なしで通過する。UNKNOWN残存時の`CAPTURE_UNKNOWN_LOCK`、
  batch atomicity、exact precondition、busy/stale経路は不変である。
- [x] **AC-6 — 再現フローの端末検証:** Issue再現シーケンス (適用 → 復元 →
  グリッド変更 → 失敗 → review解消 → 同一プロセスでpreview成功) を
  emulatorで実行し、`docs/assessment/` に手順・journal証跡付きで記録する。
- [x] **AC-7 — gate:** `spotlessCheck`、`assembleLawnWithQuickstepGithubDebug`、
  organizer unit-test gate (`app.lawnchair.organizer.*`) が成功する。

## Test oracle

| AC | Evidence |
|---|---|
| AC-1 | `LockAuthoringDecisionTest`: 1ページ容量を境界とするrank (capacity-1 / capacity / capacity超過) のフォルダ子 single/batch 受入、負rank拒否。`LockAuthoringModuleProtocolTest`: fake writer経由の単一列書込み確認 |
| AC-2 | `LockReviewListingTest`: 書込み適格行の述語でlisting行を絞ったdecision受入invariant。述語外行 (負rank・unsupported container・利用不可profile) は拒否維持の対证 |
| AC-3 | `LockAuthoringInstrumentationTest` 拡張: 実DB seed (容量超過フォルダ) → `GridSizeMigrationUtil#markOrganizerLocksUnknown` → module経由review → capture再取得でunknown 0。移行前capture由来planの `STALE_CAPTURE` 拒否と無変更assert |
| AC-4 | 新規instrumentation test (`ManualOrganizationProductionE2EInstrumentationTest` の実loader/実writer harness patternを利用): 同一プロセスchainの各段階assert (preview到達 → 復元 → 実migration → `InputUnavailable(CAPTURE_UNKNOWN_LOCK)` → review解消 → preview再到達) |
| AC-5 | 既存 `OrganizationInputComposer` / readiness / lock系testの無変更通過 |
| AC-6 | `docs/assessment/issue-287-grid-change-unknown-lock-recovery.md` (journal `INPUT_NOT_READY`/`CAPTURE_UNKNOWN_LOCK` と回復後のpreview到達の記録) |
| AC-7 | `./gradlew spotlessCheck` / `assembleLawnWithQuickstepGithubDebug` / `testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.*'` の結果をPRに記録 |

## Open questions

なし。修正対象は `LockAuthoringDecision.fitsProfile` のフォルダ子上限1か所
であり、判定をプラットフォームの実際のフォルダ意味論 (ページング表示) へ
一致させる。旧世代captureの拒否は既存のrevision/precondition機構が担う。
migration仕様・composer意味論・batch atomicityは既存正本
(ADR-0004 / spec #38 / #83 / #172) がそのまま正である。

## Change history

- 2026-09-13: Draft作成。Issue #287本文と実機調査コメント (T0/T5) の
  handoffに基づく。
- 2026-09-13: Review rev 2。Phase 1 review (code-reviewer-1) の指摘対応:
  旧世代capture由来の書込みを既存revision/precondition機構が拒否することの
  scenario/AC/test化 (Blocker)、同一プロセス自動回帰chainのinstrumentation
  test化 (Major)、書込み適格行の述語明確化 (Major)、path・rank表現の修正
  (Minor)。
- 2026-09-13: re-reviewで指摘なし。statusを `accepted` へ更新し、
  plan.mdに従って実装を開始する。
- 2026-09-13: rev 3実装。`fitsProfile` フォルダ子上限の削除、JVM/instrumentation
  回帰 (AC-1〜AC-4)、emulator検証とassessment記録 (AC-6)。AC-7はCI
  `final-status` の確認後に完了とする。
- 2026-09-13: PR [#306](https://github.com/nunu1733/NunuLauncher/pull/306)
  がmerge (cbb329f095)。実装・回帰・emulator検証 (AC-1〜AC-6) とCI
  `final-status` (run 34711506777) / 独立high-risk audit
  ([docs/assessment/pr-306-287-grid-change-unknown-lock-recovery.md](../../docs/assessment/pr-306-287-grid-change-unknown-lock-recovery.md))
  によりAC-7も完了。statusを `implemented` へ更新。Issue側の当初AC/
  regression記述はReview条件対応として確定仕様へ更新済み
  ([issue comment](https://github.com/nunu1733/NunuLauncher/issues/287#issuecomment-5650042996))。
