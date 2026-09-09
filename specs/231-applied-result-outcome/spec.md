---
issue: "#231"
status: implemented
requirements: []
risk: []
updated: 2026-09-09
---

# Organizer適用成功後の result surface が完了済み outcome を完了形で報告する

## Problem

[Issue #231](https://github.com/nunu1733/NunuLauncher/issues/231) (Astra blind exploratory UX review 2026-09-06, Finding 5) が、Organizer の適用成功後の result surface に **完了済み状態と future-tense の proposal 集計が混在する** ことを報告した。実装上の根拠は次のとおり (head `2411c76082` 時点、2026-09-09 確認):

- `State.Applied(result, summary)` は confirm 時に `pendingPlan.summary` (planning 時の `Summary`) をそのまま保持し ([ManualOrganizationRun.kt:421-424](../../lawnchair/src/app/lawnchair/organizer/ui/ManualOrganizationRun.kt))、UI はこれを `summaryItems` で描画する ([ManualOrganizationPreferences.kt:336](../../lawnchair/src/app/lawnchair/ui/preferences/destinations/ManualOrganizationPreferences.kt))。
- この描画は preview と同一の future-tense 文字列を再利用する: `manual_organization_moved_count` (`%1$d placements will move`)、`manual_organization_preserved_count` (`%1$d placements will be preserved`)、`manual_organization_new_folders_count` (`%1$d new folders will be created`)、`manual_organization_new_pages_count` (`%1$d new home screen pages will be created`)。
- 結果として成功見出し (`Organization was applied and verified.` / `整理を適用し、検証しました。`) の直後に「これから動く」読みの予定形集計が並び、ユーザーは「まだ preview / pending なのか」「すでに適用済みなのか」「表示中の件数が予定なのか結果なのか」を再解釈する必要がある。
- 適用成功後の表示内容は `State.Applied.summary` (検証済み適用の plan 要約) を唯一の truth とし、UI はそれを proposal の語彙のまま表示している。

本 Issue は **正常に適用された後の success result** (`State.Applied` に `ApplyResult.Applied` を運ぶ case) に限定する。stale path は [spec 210](../210-stale-apply-outcome/spec.md) が、preview の concrete change list は [spec 195](../195-organizer-confirmation-change-list/spec.md) が、recovery history は [spec 230](../230-restore-confirmation-target/spec.md) がそれぞれ扱う。

## Outcome

検証済み適用 (`ApplyResult.Applied`) の結果画面は、成功見出しに続いて **適用結果の件数を完了形 (past tense) で報告する**。ユーザーは画面文言だけで「適用は完了した」「何が変わった」「何が維持された」を説明でき、proposal の `will move / will be preserved` の語彙が success result として表示されることはない。表示件数は適用された plan の `Summary` counts と deterministic に一致し、Home 確認後に result surface へ再訪しても完了済み semantics が維持される。

## Scope

- **`State.Applied` の成功 case 描画の変更**: `currentState.result is ApplyResult.Applied` の場合、`changeCountItems` が描画する 4 件の count 行 (moved / preserved / new folders / new pages) を、完了形の新規 applied-count 文字列へ置き換える。
- **applied-count 文字列の追加**: en は `<plurals>` (one / other) とし、単数件数が自然に読めるようにする (「1 placement moved」)。ja は既存の recovery history plurals と同じ `other` のみの構成とする (#123 の日本語 fallback 禁止契約)。
- **既存の行構成の維持**: scope / device / strategy の入力文脈行、movedByReason / preservedByReason の理由 breakdown 行、planning failure breakdown 反復 (`rejectedByReason` / `unplacedByReason`、`Applied` では常に空)、warning 行、constraint 行は文字列変更なしで維持する (後述 D3 の時制評価と行構成)。
- **非成功 case の不変**: `State.Applied` に運ばれる非成功 result (`Rejected` 非 stale 理由、`RolledBack`、`Recovered`、`Unresolved`、`RecoveryFailed`、`ConcurrentRun`) の描画は現行のままとする (非対象)。
- **spec 52 の result surface 契約への追記**: §"Result and recovery" に、検証済み成功画面が完了形で applied counts を報告することを追記する ([spec 195 AC-8](../195-organizer-confirmation-change-list/spec.md) と同一 PR 更新の先例)。
- **representative device evidence**: 適用成功後の result surface の en/ja screenshot evidence を PR へ記録する。

## Non-goals

- planner / apply transaction の正しさ変更 ([spec 52](../52-manual-full-organization-vertical-slice/spec.md) の適用・検証契約は不変)。
- proposal preview 自体の再設計 (#195 / #208 / #212)。preview 画面の future-tense 文言と preview の grammar (件数 1 で "1 placements will move") は本 Issue で変更しない。
- stale 適用試行の outcome (#210、implemented)。
- **非成功の `State.Applied` results** (`Rejected` の非 stale 理由 / `RolledBack` / `Recovered` / `Unresolved` / `RecoveryFailed` / `ConcurrentRun`) の summary 描画。これらの表面は現行どおり proposal summary を表示する。過去形の件数行は「適用された」という主張であり、非成功結果の画面で表示すると偽になるため、成功 case に限定して導入する (D2)。
- **result surface 上の per-item (app / placement 単位) の適用済み変更行**: `State.Applied` は `PlanPreviewDetails` を保持せず、applied projection を新設する契約変更を伴う (D1)。件数レベルの outcome 報告で Issue の受入条件は満たされる。
- `ManualOrganizationRun.State` の shape 変更、新 state、新 diagnostics phase、diagnostics record 形式の変更。
- recovery history 行 ([spec 230](../230-restore-confirmation-target/spec.md)) の文言・契約の変更。

## Domain language

なし (UI 文字列の語彙整理であり、ドメイン概念の追加・変更はない)。「適用結果 (applied result)」は plan ([CONTEXT.md](../../CONTEXT.md) のレイアウトplan: まだ適用されていない結果) に対する検証済み適用後の確定した記述を指す presentation 語彙であり、本 spec 内で定義する。

## Design decisions

### D1: 件数の truth — **`State.Applied.summary` (適用された plan の planning Summary) をそのまま使う**

`State.Applied(result, summary)` が保持する `Summary` は、confirm で適用された plan の planning 時件数である。`ApplyResult.Applied` は application seam が適用後の状態を plan と照合して検証した場合にのみ返される ([spec 52](../52-manual-full-organization-vertical-slice/spec.md) §"Result and recovery": "It never claims success merely because the transaction was attempted or committed")。よって検証済み適用の画面で planning Summary を完了形の語彙で表示することは真である。既存の recovery 確認画面も同一の truth を「apply history」として表示している ([spec 230](../230-restore-confirmation-target/spec.md) の correlation gate、`appliedSummary`)。

代替案として (a) `ValidatedLayoutPlan` の actions から適用後件数を再投影する、(b) 適用後 DB 差分から再計算する、はいずれも application 契約の拡張または projection module の新設を要求し、presentation fix の範囲を超えるため不採用。E2E が既に「plan 件数 = 検証済み DB 差分」を検証するため、deterministic test は (1) UI が `State.Applied.summary` の件数をそのまま表示すること (fixture instrumentation test)、(2) production E2E で `applied.summary` 件数 = 適用後 DB 差分、の 2 点で担保する。

### D2: 完了形件数行の適用範囲 — **`ApplyResult.Applied` のみ。非成功 result では現行描画を維持**

過去形の件数行は「N 件が移動された」という完了済みの主張である。`State.Applied` は `ApplyResult.Rejected` (非 stale 理由) や `RolledBack` 等の非成功結果も運ぶため、これらの画面で過去形行を表示すると偽の主張になる。よって成功 case (`result is ApplyResult.Applied`) のみ描画を分岐し、非成功 case は現行の proposal summary 描画 (outcome 見出し + 件数行) を維持する。非成功画面の summary 語彙 (future tense が残る) は既知の残余であり、[Issue #210](https://github.com/nunu1733/NunuLauncher/issues/210) が stale path を、本 Issue が success path を扱う残りの outcome 表現改善は将来の Issue に分離する。

### D3: 文言構成 — **現行の行構成・順序を維持し、4 count 行のみ完了形へ in-place 差し替え**

成功 case の描画は、現行 `summaryItems` (`contextItems` → `changeCountItems` → `constraintItems`) の行順と interleave をそのまま維持し、`changeCountItems` 内の **4 件の count 行の文字列のみ**新規完了形 plurals へ in-place に差し替える (UI 内の新規 private `appliedResultItems`)。すなわち描画順は現行どおり moved → movedByReason → preserved → preservedByReason → new folders → new pages → (`rejectedByReason` / `unplacedByReason`) → warnings → constraints であり、count 行と breakdown 行の相対位置は変えない。成功見出し直後の各行の性質は次のとおり:

1. 成功見出し (既存 `manual_organization_apply_success`、focus + `liveRegion` 契約を維持)。
2. 入力文脈行 (scope / device / strategy — 既存)。
3. **applied counts (新規、完了形)**: moved / preserved / new folders / new pages の 4 行 — 現行 count 行と同一位置。
4. 理由 breakdown 行 (既存 "Moved as a single placement: N" / "Preserved locked placements: N" 等) — en は過去形・ja は中立形で、完了済み画面の読みに矛盾しない。
5. planning failure breakdown 行 (`rejectedByReason` / `unplacedByReason` — 既存文字列の反復を**そのまま維持**)。これらの map は `State.Applied` では構造的に常に空である: `Summary` の `rejectedByReason` は `Rejected.Invalid` から、`unplacedByReason` は `Rejected.Impossible` からのみ生成され ([ManualOrganizationRun.kt:667-668](../../lawnchair/src/app/lawnchair/organizer/ui/ManualOrganizationRun.kt))、`State.Applied` は `Planned` outcome からのみ到達可能であるため。反復を維持することで行構成の parity を保ち、planner 契約が変化して `Planned` が unplaced 情報を運ぶようになった場合も行が黙って消えない (spec 52 が検証済み成功に "applied/preserved/unplaced summary counts" を要求しているため、行集合の欠落を起こさない)。
6. warning 行 (既存 "Fallback category used: N" 等) — 適用後も継続する条件の告知であり中立。
7. constraint 行 (既存 "Lock constraint preserved: N" 等) — 中立〜完了形。
8. recovery action / safe terminal / start again (既存、不変)。

件数行がゼロ件でも既存の proposal 件数行と同様に**無条件で表示する** (preview の現在動作と同一構造で、行の有無で件数の解釈が変わることを避ける)。ja の中立形ラベル (「保持」「新しいフォルダ」等の名詞形) は日本語では時制を標示せず、成功見出し「整理を適用し、検証しました。」の直後に置かれることで完了済みとして読める。将来的に ja copy の LQA で精度が必要になった場合は別途調整する (spec 161 規約の適用範囲)。

### D4: 新規文字列 — **4 件の `<plurals>` (en: one/other、ja: other) と、既存 recovery history と同型の構成**

en は単数/複数の自然な英語になるよう plurals を使う ([spec 230](../230-restore-confirmation-target/spec.md) の recovery history plurals と同一パターン、Issue 本文の例「1 placement moved」を満たす)。ja は数量 `other` のみ (既存 ja plurals と同一構成)。

- en (values/strings.xml):
  - `manual_organization_applied_moved_count` — one: `%1$d placement moved` / other: `%1$d placements moved`
  - `manual_organization_applied_preserved_count` — one: `%1$d placement was preserved` / other: `%1$d placements were preserved`
  - `manual_organization_applied_new_folders_count` — one: `%1$d new folder was created` / other: `%1$d new folders were created`
  - `manual_organization_applied_new_pages_count` — one: `%1$d new home screen page was created` / other: `%1$d new home screen pages were created`
- ja (values-ja/strings.xml):
  - `manual_organization_applied_moved_count` — other: `移動した配置: %1$d件`
  - `manual_organization_applied_preserved_count` — other: `保持した配置: %1$d件`
  - `manual_organization_applied_new_folders_count` — other: `作成した新しいフォルダ: %1$d個`
  - `manual_organization_applied_new_pages_count` — other: `作成した新しいホーム画面ページ: %1$d枚`

既存の proposal 件数文字列 (`manual_organization_moved_count` 等) とその ja は **変更しない** (preview / degraded preview / `PlanningRejected` が proposal 語彙を必要とし続けるため)。ja copy の最終措語は spec 161 の ja copy 規約と既存語彙 (整理案/配置/保持) に合わせ、実装 PR で visual review を行う。

### D5: PreviewChange 情報モデルの result surface への再利用 — **本 Issue では採用しない**

Issue 本文が「#194/#195 の `PreviewChange` 表示と同じ情報モデルを再利用できるかは spec で確認する」ことを要求したため、次の通り確認し判断する:

- **技術的には可能である**: `ApplyResult.Applied` の適用対象は preview 済み plan そのもの (`pendingPlan.previewPlan`、非 null 時) であり、その投影 (`PlanPreviewDetails`) は適用された actions の記述と一致する。
- **採用しない理由**: (1) `State.Applied` は `PlanPreviewDetails` を保持せず、保持するには coordinator の observable state 契約を拡張する必要がある。(2) preview の行語彙 (「名前 — 移動元 → 移動先」、group 見出し「Move (N)」) は proposal の presentation であり、result として再利用すると [Issue #231](https://github.com/nunu1733/NunuLauncher/issues/231) が明示的に禁止する preview / result の意味の同一視 (「preview と result の意味を同一視しないこと」) に触れる。(3) Issue の受入条件は件数レベルの outcome 説明を要求し、完了形件数行で満たされる。(4) 検証済み適用の per-item 証明は Home 画面上の実際の配置と、recovery point 経由の restore が提供する。
- per-item の適用済み変更一覧を result surface に追加する場合は、applied projection の新設 (spec 194 クラスの契約作業) と state shape 変更を伴う別 spec を要求する。

## Behavior scenarios

### Scenario: 検証済み適用の画面が完了形の件数を報告する

Given preview を confirm し、apply が `ApplyResult.Applied` を返して `State.Applied(result, summary)` が公開されている,

When 適用結果画面が描画される,

Then 成功見出し (`Organization was applied and verified.`) が表示され、applied counts が新規 plurals の完了形で `summary` の件数と一致して表示される (fixture 件数 移動 1 の場合 `1 placement moved`),

And proposal の件数文字列 (`manual_organization_moved_count` / `manual_organization_preserved_count` / `manual_organization_new_folders_count` / `manual_organization_new_pages_count`) はこの画面に一切表示されない,

And scope / device / strategy の入力文脈行、理由 breakdown 行、planning failure breakdown 反復 (`Applied` では常に空)、warning 行、constraint 行は現行と同一の行順で表示される。

### Scenario: 再訪しても完了済み semantics が維持される

Given `State.Applied(ApplyResult.Applied)` で recovery preview を開いた後、cancel して `State.Applied` へ戻っている (または Home 確認後に Organizer へ再訪している),

When 適用結果画面が再描画される,

Then 完了形の applied counts が同じ内容 (`State.Applied.summary` 由来) で再表示され、time や再訪回数に依存する語彙変化はない。

### Scenario: 非成功の適用結果は完了形を主張しない

Given apply が非 stale の非成功 result (`ApplyResult.RolledBack` 等を `State.Applied(result, summary)` で運ぶ) を返した,

When 結果画面が描画される,

Then 現行どおり outcome 見出しと proposal summary 行が表示され、本 spec の完了形件数行は表示されない (過去形の「移動された」主張は行われない)。

### Scenario: ja locale で新規文言が解決される

Given device locale が ja である,

When 適用結果画面が表示される,

Then 新規 4 plurals が英語 fallback なしに ja で解決され、en と異なる値になる (#123 契約)。

### Scenario: 表示件数が検証済みの適用と整合する

Given fixture plan (移動 1 / 保持 0 / 新規フォルダ 0 / 新規ページ 0) で FakeApplication の apply が `Applied` を返した,

When 適用結果画面の件数行を読む,

Then 表示件数は fixture plan の `Summary` counts と一致する,

And production E2E では、検証済み適用後の `State.Applied.summary` 件数が実際の適用後 DB 差分と一致する。

## Data and state

- 読む data: `State.Applied(result, summary)` の observable 値のみ (state shape 変更なし)。UI は planner / application の型や DB に直接触れない。
- 書く data: **なし**。永続化、migration、backup/restore、Launcher DB への影響なし (zero-write の表示層)。ホームレイアウト安全規約の適用対象外。
- rollback: PR revert で現行描画へ戻る。新規 4 plurals は同一 PR で削除される。

## Permissions, privacy, and security

None。新たな permission、通信、telemetry、export は追加しない。表示する件数は既に preview が表示している `Summary` と同一であり、raw package / `ItemId` / page id 等の privacy 契約対象は一切表示へ現れない。

## Accessibility and localization

- 新規件数行は既存 `SummaryText` (静的 text、`liveRegion` なし) と同一の表現であり、TalkBack の focus / liveRegion 契約 (status 見出しが `FocusTargetText` で focus + `liveRegion = Polite`) は不変。
- 新規 interactive 要素なし。traversal・focus 復帰契約 ([spec 52](../52-manual-full-organization-vertical-slice/spec.md) / [spec 209](../209-organizer-decision-action-affordance/spec.md)) に影響しない。
- 200% font scale で全行が wrap する (短文 1 行 / `%1$d` placeholder のみ)。
- 追加 plurals は `values/` + `values-ja/` の両方に置く (#123: 日本語実行時の英語 fallback 禁止)。ja は `other` のみ。en は one / other で単数件数が自然に読める。

## Acceptance criteria

| AC | Acceptance criterion |
|---|---|
| AC-1 | `State.Applied` のうち `result is ApplyResult.Applied` の画面は、成功見出しに続き moved / preserved / new folders / new pages の件数を新規完了形 plurals で表示し、proposal の件数文字列 (`manual_organization_moved_count` / `manual_organization_preserved_count` / `manual_organization_new_folders_count` / `manual_organization_new_pages_count`) をその surface に一切表示しない。入力文脈行・理由 breakdown・planning failure breakdown (`Applied` では常に空)・warning・constraint 行と recovery action / start again は現行と同一の行構成で機能する。 |
| AC-2 | ユーザーは画面文言だけで「適用は完了した」ことと「何が変わった／維持された」こと (件数レベル) を説明できる。 |
| AC-3 | recovery preview の cancel による `State.Applied` への再訪 (および同 process 内の再描画) で、完了形の件数行が同一内容で維持される。 |
| AC-4 | 完了形件数行の表示値が `State.Applied.summary` の件数と deterministic に一致し (fixture instrumentation test)、production E2E で適用前後の layout capture 差分から導出した件数と `applied.summary` の件数が一致することが主張される。 |
| AC-5 | 非成功の `State.Applied` 結果 (Rejected 非 stale / RolledBack / Recovered / Unresolved / RecoveryFailed / ConcurrentRun) は現行の proposal summary 描画を維持し、完了形件数行を表示しない。他の run state (preview / PlanningRejected / stale 等) も退化しない。 |
| AC-6 | 新規 4 plurals が ja locale で英語 fallback なしに解決され、en で単数/複数が数量に応じて解決される。 |
| AC-7 | spec 52 §"Result and recovery" に完了形 applied counts の契約が同じ PR で追記される。 |
| AC-8 | 適用成功後の result surface の representative device evidence (en + ja screenshot、Home 往復後の再訪 capture を含む) が PR へ記録される。 |

## Test oracle

| AC | Evidence |
|---|---|
| AC-1, AC-2 | `ManualOrganizationPreferencesInstrumentationTest` に追加する成功適用 test (`FakeApplication` + `planningResult()` fixture で confirm 後、成功見出し + 4 件の完了形件数行の `assertIsDisplayed`、proposal 件数行 (`manual_organization_moved_count` 等) の `assertCountEquals(0)`) |
| AC-3 | 同 instrumentation test: `beginRecoveryPreview` → `cancelRecoveryPreview` 後に `State.Applied` へ戻り、完了形件数行の再表示を主張。描画が保持された `State.Applied` の純粋関数であるため (coordinator singleton が process 内で保持)、この re-render 主張が Home 再訪経路 (Organizer を離れて戻る) も包含する。Home 再訪の代表 evidence は AC-8 の device evidence で確認する。 |
| AC-4 | 同 instrumentation test の fixture 件数一致 + `ManualOrganizationProductionE2EInstrumentationTest.manualRunUsesProductionCaptureApplyVerificationAndRecovery` に、適用前後の layout capture (`before` / `afterApply`) 差分から導出した件数と `applied.summary` 件数の一致主張を追加 |
| AC-5 | 同 instrumentation test に非成功 (`ApplyResult.Unresolved` 既存 test に加え `RolledBack` 等の 1 case) の現行描画主張を追加し、既存 test が無変更で green |
| AC-6 | 同 instrumentation test の ja locale 解決 test へ新規 plurals を追加 (fallback 検出) と en quantity 解決の主張 |
| AC-7 | PR diff review (spec 52) |
| AC-8 | `capturesManualOrganizationReviewSurfaces` の `success` screenshot (en) + ja evidence。emulator (API 36.1) 上で実行し、PR へ記録。Home 往復後の再訪 capture を含める |

## Open questions

None。文言と適用範囲は §D1–D5 のとおり spec 時点で確定した。実装 PR で owner review を行う。

## Change history

- 2026-09-09: Drafted for Issue #231。Issue 本文 (Astra blind exploratory UX review 2026-09-06, Finding 5)、`ManualOrganizationRun.kt` / `ManualOrganizationPreferences.kt` / strings / 既存 instrumentation・E2E test の調査、spec 210 (stale outcome の文言構成先例)・spec 195 (PreviewCounts truth 分担)・spec 230 (apply history 語彙・correlation gate) の契約調査を入力に作成。Phase1 review はセッション指示に基づき code-reviewer サブエージェントが行う。
- 2026-09-09: Phase1 review (code-reviewer-2 サブエージェントによる独立 review セッション、REQUEST CHANGES) 対応: (1) Medium — D3 / AC-1 / plan を修正し、成功 case の描画を現行 `summaryItems` と同一の行構成 (`rejectedByReason` / `unplacedByReason` 反復を含む) に揃え、両 map が `State.Applied` で常に空である不変条件 (`Planned` outcome からのみ到達可能) を明記。spec 52 の "applied/preserved/unplaced summary counts" 文との整合を保持。(2) AC-3 の test oracle に Home 再訪経路の包含根拠を追記。(3) AC-4 の E2E 主張を「適用前後の capture 差分からの導出」に強化。(4) plan の行番号参照を修正。
- 2026-09-09: Phase1 re-review (同一 reviewer) が 4 条件の解消を確認し APPROVE。non-blocking の整備指摘 (scope bullet / Scenario の行構成言及、D3 の in-place 差し替えの明確化、AC-8 への Home 往復 capture 明記、変更履歴 link の整理) を本 revision で反映し、spec を `accepted` へ進める。実装 (同一 PR) で owner review を継続する。
- 2026-09-09: [PR #262](https://github.com/nunu1733/NunuLauncher/pull/262) owner review (PR コメント): Medium — AC-1 instrumentation test の future-tense 不在確認が固定値 (`getString(id, 1)`) でなく `applied.summary` 実件数と組にすべきとの指摘に対応 (head `b2eb1851e8`)。非成功 test も 4 count 対称化。test-only delta に対し独立監査セッションが PASS 継続を再確認し、[audit 記録](../../docs/assessment/pr-262-applied-result-outcome.md) に Head SHA 更新 + re-audit note を追記 (`c7c7736141`)。re-run CI 全 job green。
- 2026-09-09: [PR #262](https://github.com/nunu1733/NunuLauncher/pull/262) を squash merge (merge commit `0f8638692136a5f4db8b3ccac38159eef7320dea`)。CI run [34322893724](https://github.com/nunu1733/NunuLauncher/actions/runs/34322893724) が全 job pass (`final-status` green)。`Closes #231` により Issue #231 は自動 close。受入条件 AC-1〜AC-8 が満たされたため spec を `implemented` へ進める。

## References

- [Issue #231: Organizer適用済み結果画面に予定形の説明が残り、完了状態と混同する](https://github.com/nunu1733/NunuLauncher/issues/231)
- [Spec 210: stale 適用試行後の outcome 表現 (outcome 文 + 詳細文の文言構成の先例)](../210-stale-apply-outcome/spec.md)
- [Spec 52: manual full-organization vertical slice (Result and recovery 契約)](../52-manual-full-organization-vertical-slice/spec.md)
- [Spec 230: restore confirmation target (apply history 語彙と correlation gate)](../230-restore-confirmation-target/spec.md)
- [Spec 195: organizer confirmation change list (PreviewCounts truth 分担、degraded 描画)](../195-organizer-confirmation-change-list/spec.md)
- [Spec 123: organizer UI convergence (日本語 fallback 禁止契約)](../123-organizer-ui-convergence/spec.md)
- [Spec 161: Japanese UI copy LQA (ja copy 規約)](../161-japanese-ui-copy-lqa/spec.md)
- [AGENTS.md](../../AGENTS.md)
- [Quality strategy](../../docs/engineering/quality-strategy.md)
