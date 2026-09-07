---
issue: "#210"
status: accepted
requirements: []
risk: []
updated: 2026-09-07
---

# stale 適用試行後に「何も適用されていない・現 layout 無変更・次の一手」を画面文言で説明できる

## Problem

[Issue #210](https://github.com/nunu1733/NunuLauncher/issues/210) (exploratory UX review 2026-09-05, F-04 / major) が、home layout が変化した後に古い proposal に対して `Apply` を tap した結果の状態表示が操作の結果を語っていないことを報告した。実装上の根拠は次のとおり (head `a84aab807f` 時点、2026-09-07 確認):

- `State.Stale` の描画は 2 行のみである: `manual_organization_stale` (`The home layout changed. Review a new proposal before applying anything.`) と `manual_organization_recapture` (`Capture and review again`)。
- この文言からは、tap した proposal が破棄されたのか保持されているのか、何も適用されていないこと (home layout は tap 前のまま)、`Capture and review again` が何を起点に何を作るのかを読み取れない。
- state machine 上、`State.Stale` には 3 つの到達経路がある (`ManualOrganizationRun.kt`):
  1. `start()` 内の preview-time stale (`PlanPreviewResult.Stale`) — proposal が表示される前に検出される
  2. `confirm()` 内の materialize 失敗 (`transitionToStale`) — ユーザーが `Apply` を tap 済み
  3. `confirm()` → `apply()` が `ApplyResult.Rejected(STALE_REVISION | EXACT_PRECONDITION_FAILED)` を返す — ユーザーが `Apply` を tap 済み
- いずれの経路でも `pending = null` で proposal は破棄され、書込みは発生しない (zero-write)。ただし経路 1 は「確認した proposal」が存在しないため、「確認済み proposal が破棄された」という単一の文言は不正確になる。

## Outcome

stale 適用試行の後、ユーザーは画面文言だけで次を正しく説明できる:

1. 確認していた proposal が適用されなかったこと。
2. この適用試行が現在の home layout を変更しなかったこと (ユーザー自身の操作による既存の変更を否定しない)。
3. 確認していた proposal が破棄されたこと。
4. 次の一手 (`Capture and review again`) が現在の home layout を起点に確認用の新しい proposal を作ること。

proposal がまだ確認されていない stale 検出 (経路 1) では、破棄の主体と時点が正確に表現され、「確認済み proposal の破棄」と誤認させない。

## Scope

- **`State.Stale` に stale 検出時点を表す origin を追加**: `State.Stale` を `data object` から origin (`APPLY_BLOCKED` / `DETECTED_BEFORE_REVIEW`) を持つ data class へ変更する。経路 2・3 は `APPLY_BLOCKED`、経路 1 は `DETECTED_BEFORE_REVIEW` を運ぶ。遷移・diagnostics (`A2` rejection event)・適用 semantics は変更しない。
- **outcome 文と origin 別詳細文の導入**: `State.Stale` の描画を (1) 共通 outcome 見出し (`This proposal was not applied. This attempt did not change your current home layout.`)、(2) origin 別の詳細文 (proposal の破棄と時点)、(3) `Capture and review again` action へ再構成する。
- **recapture action の説明追加**: `Capture and review again` に summary 行を追加し、現在の home layout を起点に確認用の新しい proposal を作ることを文言から分かるようにする。label 自体と preference row 表現 (spec 209 non-goals) は変更しない。
- **string 見直し**: `manual_organization_stale` を outcome 文で置換し、新規 3 string (origin 別詳細 ×2、recapture summary ×1) を追加する。ja も同時に提供する。
- **既存契約の維持**: stale 経路の zero-write 契約、`A2` stale-rejection diagnostics、status 見出しの focus + `liveRegion = Polite`、cancel/遷移の扱いは退化させない。

## Non-goals

- `State.Cancelled` の outcome 表示 (review F-09)。UX review の次 iteration で扱う (Issue 本文の補足どおり)。
- stale 検出機構、revision 照合、適用ブロックの安全設計の変更。問題は状態遷移の表現のみである。
- `Capture and review again` の Material3 button 化。spec 209 が navigation/entry action は preference row のままと規定済みであり、本 spec は summary 行の追加のみを行う。
- `State.Stale` 以外の状態の文言見直し。
- diagnostics record 形式の変更 (origin は UI observable state のみに載せる)。

## Domain language

- **stale origin**: `State.Stale` が到達した経路の区別。`APPLY_BLOCKED` (ユーザーの適用試行が blocking され、確認済み proposal が破棄された) と `DETECTED_BEFORE_REVIEW` (proposal が確認画面に到達する前に stale 検出された) の 2 値。UI 文言の正確性のためだけに存在し、適用 blocking の意味は変わらない。`APPLY_BLOCKED` は原因 (layout 変化) まで保証しない。

## Design decisions

### D1: origin の導入 — **単一文言では 3 経路を正確に語れないため state で区別する**

`State.Stale` に origin を持たせる。理由は、「確認済み proposal の破棄」の主張が経路 1 では偽になるためである。経路 1 (preview-time stale) は proposal が一度も画面に表示されていない run 内の競合であり、ユーザーは何も確認していない。一方で経路 2・3 はユーザーが明示的に `Apply` を tap した後である。

origin は UI observable state (`State`) にのみ載せる。適用 blocking の意味・diagnostics event・`transitionToStale` の契約は変わらないため、既存の unit/E2E 契約は主張対象を origin 付き equality へ更新するだけで維持される。`APPLY_BLOCKED` / `DETECTED_BEFORE_REVIEW` という 2 値の理由は、UI が分けるべき事実が「proposal を確認済みか」の 1 軸だからである。materialize 失敗と apply 拒否を同一 origin に畳むのは、ユーザーへの説明事実が同一であることに加え、materialize 経路の `Result.Invalid` は原因を保持しないため state 側で確実に主張できる事実が「適用を安全に行えなかった」までであるためである (D2 の cause-neutral 詳細文を参照)。

### D2: 文言構成 — **共通 outcome 見出し + origin 別詳細文 + recapture summary**

`State.Stale` の描画を次の 3 要素へ再構成する。outcome が status 見出しとして focus + `liveRegion` を引き継ぐ。

- **共通 outcome 見出し** (`manual_organization_stale_outcome`、旧 `manual_organization_stale` を置換):
  - en: `This proposal was not applied. This attempt did not change your current home layout.`
  - ja: `この整理案は適用されませんでした。この操作によるホーム画面の変更はありません。`
- **origin 別詳細文**:
  - `APPLY_BLOCKED` (`manual_organization_stale_proposal_discarded`) — en: `The reviewed proposal could no longer be applied safely, so it was discarded.` / ja: `確認した整理案を安全に適用できなくなったため、整理案は破棄されました。`
  - `DETECTED_BEFORE_REVIEW` (`manual_organization_stale_proposal_not_reviewed`) — en: `The home layout changed while the proposal was being prepared, so it was discarded before you could review it.` / ja: `整理案の作成中にホームレイアウトが変更されたため、確認前に整理案は破棄されました。`
- **recapture summary** (`manual_organization_recapture_summary`、label は既存 `Capture and review again` を維持): en: `Captures your current home layout and prepares a new proposal to review.` / ja: `現在のホームレイアウトを読み込み、確認用の新しい整理案を作成します。`

outcome 文は操作主体・試行にスコープする。stale 画面には失敗の文脈を伝える詳細文が直後に続くため、outcome を `Your current home layout is unchanged.` のような無条件な現在状態の主張にすると、画面上で自己矛盾に読める (owner review で指摘)。`This attempt did not change your current home layout.` は「この適用試行が追加の変更を行わなかった」ことを主張し、ユーザー自身の drag による既存の変更を否定しない。production E2E の oracle (`expectedAfterMutation` と confirm 後の DB の一致) もこの意味を検証する。

`APPLY_BLOCKED` の詳細文は **cause-neutral** である。`APPLY_BLOCKED` には 2 つの下位経路があるが (confirm 時の materialize 失敗 / apply の stale revision 拒否)、materializer の `Result.Invalid` は stale 専用の結果ではなく readiness unavailable・capture 失敗・revision mismatch・構造整合性違反をまとめて畳む契約であり、state から「home layout が変更された」という原因は保証できない (2nd owner review で指摘)。そのため詳細文は「確認した整理案を安全に適用できなくなったため破棄された」と、両経路で真である事実だけを述べる。原因 (layout 変化) が state から確実に言える `DETECTED_BEFORE_REVIEW` (`PlanPreviewResult.Stale` 由来) のみが layout-change 文を維持する。原因まで state に運ぶ案 (`APPLY_STALE` / `APPLY_BLOCKED_OTHER` 分離) は application/materializer 側の失敗理由細分化を要求し、presentation fix の範囲を超えるため不採用とした。

### D3: 詳細文の表現 — **既存の静的 body text 規約に従う**

origin 別詳細文は `manual_organization_safe_terminal` と同一の表現 (`Text` + `bodyMedium` + 16dp padding) を使う。新規 component・装飾を導入しない (#123 の既存 component / theme token 再利用契約)。recapture summary は `ClickablePreference` の既存 `subtitle` parameter を使う (`manual_organization_open_diagnostics` と同一構成)。

### D4: 適用安全性の再主張はしない — **zero-write は既存契約の継続である**

stale 経路の zero-write は既に `LayoutApplicationModule` の revision 照合と E2E test (`staleProductionConfirmationDoesNotWrite`) が担保する。本 spec はその結果の「表現」を直すものであり、新たな安全機構を追加しない。outcome 文が「この整理案は適用されない・この操作による変更はない」と主張する対象は、この既存契約が保証する事実である。

## Behavior scenarios

### Scenario: stale 適用試行後に outcome と次の一手が説明できる

Given preview 表示後に home layout が変化しており、`Apply reviewed organization` を tap して stale で拒否される (`State.Stale(APPLY_BLOCKED)`),

When stale 状態の画面が表示される,

Then `This proposal was not applied. This attempt did not change your current home layout.` が status 見出しとして表示される,

And `確認した整理案を安全に適用できなくなったため、整理案は破棄されました。` (en 同義) に相当する詳細文が表示される,

And `Capture and review again` とその summary (`Captures your current home layout and prepares a new proposal to review.`) が表示される。

### Scenario: proposal 確認前の stale 検出では「確認済み」と誤認させない

Given run 開始後の preview 準備中に `PlanPreviewResult.Stale` が検出され (`State.Stale(DETECTED_BEFORE_REVIEW)`)、proposal が一度も表示されていない,

When stale 状態の画面が表示される,

Then 共通 outcome 見出しが表示される,

And 詳細文は「確認する前に整理案が破棄された」ことを伝え、「確認した整理案が破棄された」とは主張しない。

### Scenario: stale 経路の安全契約が退化しない

Given stale が検出される経路 (1〜3 のいずれか) で run が終了する,

When 適用の成否と diagnostics を観察する,

Then Launcher DB への書込みは発生せず、`favorites` は試行前のままである,

And 既存の `A2` stale-rejection diagnostics 契約が維持される。

### Scenario: ja locale で新規文言が解決される

Given device locale が ja である,

When stale 状態の画面が表示される,

Then outcome・詳細文・recapture summary が英語 fallback なしに ja で解決される。

## Data and state

- 変更する observable state: `State.Stale` を origin 付き data class へ変更 (上記のとおり)。他の状態と coordinator 契約は不変。
- 読む data: 変更なし。永続化・migration・backup/restore・Launcher DB への影響なし。ホームレイアウト安全規約の適用対象外 (zero-write、DB 接触なし)。
- rollback: PR revert で現行描画へ戻る (旧 `manual_organization_stale` string は同一 PR で復元される)。

## Permissions, privacy, and security

None。新たな permission、通信、telemetry は追加しない。表示文字列の変更のみである。

## Accessibility and localization

- status 見出し (outcome 文) は既存 `FocusTargetText` の focus + `liveRegion = Polite` 契約を引き継ぐ。詳細文・summary は静的 text として読み上げされる。
- ja/en の同時提供を必須とし、既存の ja locale 解決 test に新規 4 string を追加する。既存用語に合わせ「整理案」で統一する (spec 161 の ja copy 規約)。
- `Capture and review again` の touch target・preference row 表現は既存構成のままである。

## Acceptance criteria

| AC | Acceptance criterion |
|---|---|
| AC-1 | `Apply` 試行が stale で拒否された場合 (`State.Stale(APPLY_BLOCKED)`)、画面に (1) outcome 文 `This proposal was not applied. This attempt did not change your current home layout.`、(2) 整理案の破棄を伝える詳細文、(3) `Capture and review again` とその summary が表示される。 |
| AC-2 | proposal 確認前の stale 検出 (`State.Stale(DETECTED_BEFORE_REVIEW)`) では、outcome 文と「確認前に破棄」の詳細文が表示され、「確認済み整理案」を主張しない。 |
| AC-3 | ja locale で新規 4 string が英語 fallback なしに解決される。 |
| AC-4 | stale 経路の zero-write 契約と `A2` stale-rejection diagnostics が退化しない (既存 unit / E2E 契約が origin 付き主張へ更新されて green)。 |
| AC-5 | 実 production module + Launcher DB を使う E2E instrumentation test が「stale 適用試行 → 何も適用されない → `favorites` 無変更」を検証し、結果 state が `APPLY_BLOCKED` を運ぶことを主張する。 |

## Test oracle

| AC | Evidence |
|---|---|
| AC-1 | `ManualOrganizationPreferencesInstrumentationTest` に追加する stale 適用試行 test (`FakeApplication` に `ApplyResult.Rejected(STALE_REVISION)` を設定し、confirm 後に outcome / 詳細文 / recapture summary の `assertIsDisplayed`) |
| AC-2 | 同 instrumentation test (`inspectPlanOverride = PlanPreviewResult.Stale` で start 後の表示主張) と、unit test の origin equality 主張 |
| AC-3 | 同 instrumentation test の ja locale 解決 test へ新規 string を追加 |
| AC-4 | `ManualOrganizationRunTest` の 3 stale test を origin 付き equality へ更新して green、既存 diagnostics phase 主張の維持 |
| AC-5 | `ManualOrganizationProductionE2EInstrumentationTest.staleProductionConfirmationDoesNotWrite` を origin 主張付きへ拡張して green |

## Open questions

None。文言と state 形状は §D1–D4 のとおり spec 時点で確定した。owner review は実装 PR で継続する。

## Change history

- 2026-09-07: Drafted for Issue #210。Issue 本文 (exploratory review F-04)、`ManualOrganizationRun.kt` / `ManualOrganizationPreferences.kt` / strings の現行実装調査、spec 209・195 の UI 契約、E2E stale test (`staleProductionConfirmationDoesNotWrite`) の調査を入力に作成。実装 PR ([#240](https://github.com/nunu1733/NunuLauncher/pull/240)) で owner review を実施する。
- 2026-09-07: Spec/Plan owner review ([Issue #210 コメント](https://github.com/nunu1733/NunuLauncher/issues/210#issuecomment-review), Request changes) 対応: (1) Blocker — D2 / AC-1 / Scenario の共通 outcome 文を適用試行スコープ (`This proposal was not applied. This attempt did not change your current home layout.` / `この整理案は適用されませんでした。この操作によるホーム画面の変更はありません。`) へ変更。旧文言「現在のホームレイアウトは変更されていません」が直後の「layout が変更されたため破棄」詳細文と画面上で自己矛盾に読めるため。strings・ja を同時更新。(2) status を accepted → proposed へ訂正 (承認は owner review 完了後に行う)。plan.md に en/ja screenshot・visual review・`assembleLawnWithQuickstepGithubDebug` 完了確認を検証として追加。
- 2026-09-07: Spec/Plan re-review ([Issue #210 コメント](https://github.com/nunu1733/NunuLauncher/issues/210#issuecomment-review), Request changes) 対応: Blocker — `APPLY_BLOCKED` の詳細文を cause-neutral 化 (`The reviewed proposal could no longer be applied safely, so it was discarded.` / `確認した整理案を安全に適用できなくなったため、整理案は破棄されました。`)。`APPLY_BLOCKED` の materialize 失敗経路は `OrganizationPlanMaterializer.Result.Invalid` (readiness unavailable・capture 失敗・revision mismatch・構造整合性違反を畳む契約) を含み、state から「home layout が変更された」原因は保証できないため。D1 / D2 (cause-neutral 規定を新設) / Domain language / Scenario / strings を更新。`DETECTED_BEFORE_REVIEW` は `PlanPreviewResult.Stale` 由来で原因が確実なため layout-change 文を維持。原因分離 origin (`APPLY_STALE` / `APPLY_BLOCKED_OTHER`) は materializer 失敗理由の細分化を要求し範囲外として不採用。
- 2026-09-07: Owner approval (「Approve 進めてください」の実施指示)。spec を `accepted` へ進める。実装 ([PR #240](https://github.com/nunu1733/NunuLauncher/pull/240)) は commit `dfde97cc6f` 時点で承認済み spec の受入条件 (AC-1〜AC-5) を満たす。merge 後に `implemented` へ進める。

## References

- [Issue #210: stale layoutでのApply試行後に操作結果(outcome)が表示されない](https://github.com/nunu1733/NunuLauncher/issues/210)
- [Spec 209: organizer decision action affordance (preference row 規約・focus 契約)](../209-organizer-decision-action-affordance/spec.md)
- [Spec 195: organizer confirmation change list (degraded 告知・traversal 契約)](../195-organizer-confirmation-change-list/spec.md)
- [Spec 52: manual full-organization vertical slice (run state 契約)](../52-manual-full-organization-vertical-slice/spec.md)
- [Spec 194: plan preview seam (preview-time stale の由来)](../194-plan-preview-seam/spec.md)
- [AGENTS.md](../../AGENTS.md)
