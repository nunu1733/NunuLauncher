---
issue: "#232"
status: draft
requirements:
  - NFR-009
risk: []
updated: 2026-09-10
---

# `Later` 選択後もユーザーが自力でOrganizerの再開導線を再発見できる

## Problem

[Issue #232](https://github.com/nunu1733/NunuLauncher/issues/232) (Astra blind exploratory UX review 2026-09-06) が、onboarding proposal ([spec 53](../53-onboarding-organization-proposal/spec.md)で実装済み) で `Later` を選んだ後、ユーザーがOrganizerを再開するための入口を発見しにくいことを報告した。

`Later` は「今回は実行しないが後で行う」という選択であるが ([spec 53](../53-onboarding-organization-proposal/spec.md) §3.1 outcome semantics: defer)、選択後に表示される画面は一切なく、その後の再開はユーザーの記憶だけに依存する。source上の経路は次のとおり (head `cc41f3bb1b` 時点、2026-09-10 確認):

- `Later` (`organization_onboarding_proposal_defer`) は `OrganizationOnboardingProposalView` で `controller.defer()` を呼んで proposal を閉じるだけである ([OrganizationOnboardingProposal.kt:295-299](../../lawnchair/src/app/lawnchair/organizer/ui/OrganizationOnboardingProposal.kt))。defer後の案内・再開経路の表示はない。
- Organizerの恒常的な手動入口は settings 内 `Home screen` 画面の `Layout` セクション内の `Organize home layout` 行 ([HomeScreenPreferences.kt:157-161](../../lawnchair/src/app/lawnchair/ui/preferences/destinations/HomeScreenPreferences.kt)) だけであり、dashboard (`PreferencesDashboard`) からは3階層目 (Settings → Home screen → Layout section 内) に位置する。`Layout` セクションは wallpaper セクションより下にあり、上部からは視認できない。
- `Later` 選択直後の launcher Home には、Organizer へ至るいかなる視覚的手がかりも追加されない。

観測された失敗モードは「Home 長押し → Home settings を開く → 設定トップに入口が見えない → Home screen を開く → 上部に見えない → 別メニュー探索 → 後から Home screen を下へスクロールして発見」という、設定構造の探索と往復である。一度入口を覚えても、Home で結果確認した後に戻るには同じ階層を辿る必要がある。

これは onboarding proposal の activation bug ([spec 137](../137-proposal-touch-activation/spec.md)) とは異なり、`Later` が正常に動作した後の re-entry / discoverability の問題である。

## Outcome

`Later` を選んだユーザーが、選択直後に表示される案内から再開場所を記憶できる。また、案内を忘れたユーザーでも、launcher Home から settings を開いた際に探索と往復なしに Organizer の入口を合理的な経路で再発見できる。

## Scope

- `Later` 選択直後に表示する、再開経路を案内する非ブロッキングな一時的 surface (re-entry hint)。
- Home settings (`HomeScreenPreferences`) の `Layout` セクションから `General` セクションへの Organizer 入口行の移動 (視認性の向上)。挙動・destination は変更しない。
- `OrganizationOnboardingProposal` の presenter 層に限定した変更。coordinator、run state machine、apply/recovery、diagnostics は変更しない。

## Non-goals

- `Later` / `Skip` の persistence semantics の変更 ([spec 53](../53-onboarding-organization-proposal/spec.md) §3.1 の outcome table は不変)。
- onboarding proposal の touch activation ([spec 137](../137-proposal-touch-activation/spec.md))、eligibility / provenance 分類の変更。
- Organizer confirmation / preview / result UI の変更 ([spec 195](../195-organizer-confirmation-change-list/spec.md) / [spec 209](../209-organizer-decision-action-affordance/spec.md) / [spec 231](../231-applied-result-outcome/spec.md))。
- Home settings 全体の大規模な情報設計の再設計、Organizer を不自然に突出させる扱い。
- Home 長押しメニューや drawer 等の settings 以外への新規入口の追加。
- 恒常的な shortcut / widget 形式の Organizer 入口の追加。
- run-journal への新規 event の追加 (hint 表示は organizer run ではない)。
- `Later` 後の次回 cold start における proposal の自動再表示の変更 (既存の defer semantics に従う)。

## Domain language

追加なし。既存語彙 (`Later`/defer、Organizer、Home settings) のみを使用する。`CONTEXT.md` の変更は不要である。

## Behavior scenarios

### Scenario: Later 選択直後に再開場所が案内される

Given fresh-install onboarding proposal が表示されている
When ユーザーが `Later` を選択する
Then proposal が閉じ、既存の defer semantics (process 内 suppress、`DEFERRED` persistence) がそのまま適用される
And proposal の元の位置に、Organizer の再開場所 (Home settings の Home screen 内) を案内する非ブロッキングな hint が短時間表示される
And hint は layout も organizer-owned state も変更せず、run-journal event を発行しない

### Scenario: hint は自動的に消え、操作を妨げない

Given re-entry hint が表示されている
When ユーザーが Home を操作する (workspace touch、hint 以外の領域、Back) または一定時間経過する
Then hint が閉じ、launcher は通常どおり操作可能である
And hint 表示中も Home の touch が阻害されない
And dismiss が `Later` / `Skip` の選択として扱われず、persistence を変更しない

### Scenario: 既存の outcome semantics は変化しない

Given onboarding proposal が表示されている
When `Skip` または `Review organization` を選択する、または proposal 外を dismiss する
Then [spec 53](../53-onboarding-organization-proposal/spec.md) §3.1 の既存 outcome (`SKIPPED` / `REVIEWED` / defer on dismiss) が変更なく適用される
And `Review organization` の fresh-run admission と `ONBOARDING_PROPOSAL` trigger ([spec 53](../53-onboarding-organization-proposal/spec.md) §3.2) は変化しない

### Scenario: Home settings の Organizer 入口が上位セクションで発見できる

Given `Later` 済みのユーザーが Home 長押しから Home settings を開く
When `Home screen` を開く
Then `Organize home layout` 入口が `Layout` セクション内ではなく、画面上部の `General` セクションに配置される
And 入口の label / destination / 副説明 (`manual_organization_summary`) は変化しない
And 同セクション内の既存行との相対順序は既存の Lawnchair 情報設計と整合する

### Scenario: TalkBack と keyboard で hint が到達可能

Given re-entry hint が表示されている
When TalkBack ユーザーが hint に到達する、または keyboard / DPAD で操作する
Then hint は hint の役割と内容を音声化し (title + body が 1 つの announcement として扱える)
And hint が消えた際、focus は元の target に戻る
And 200% font scale で hint の text が切れずに reflow する

### Scenario: hint の表示失敗は launcher を壊さない

Given proposal の `Later` が選択された直後である
When hint の host 追加または表示が例外で失敗する
Then defer persistence と launcher の通常動作に影響しない
And crash せず、次回の proposal 表示 (次の qualifying cold start) に影響しない

## Data and state

- 読む data: proposal controller の既存 state のみ。新規 preference / persistent store は追加しない。
- 永続化: 変更なし。hint は process 内の一時的 view であり、retention を持たない。
- migration / backup / restore: 影響なし (DB、schema、preference key を変更しない)。
- layout を扱わない: hint と入口移動は organizer-owned state を一切書き換えない。

## Permissions, privacy, and security

None。新規 permission、外部送信、sensitive data は存在しない。hint は app-private な view であり、diagnostics への新規出力もない。

## Accessibility and localization

- hint は TalkBack で到達可能であり、title と body の内容を伝える。`AbstractFloatingView` 系の proposal と同等の focus 付与 / 復帰 ([spec 53](../53-onboarding-organization-proposal/spec.md) §6 の accessibility contract) に従う。
- hint の文言は `lawnchair/res/values/strings.xml` と `lawnchair/res/values-ja/strings.xml` に追加し ([spec 161](../161-japanese-ui-copy-lqa/spec.md) の対訳規約)、文字列名は `organization_onboarding_` prefix に従う。path 案内は `settings_button_text` / `home_screen_label` / `manual_organization_title` の実表示 label を参照して構成し、locale 間で実 UI と一致させる。
- 200% font scale で reflow し、非色依存で情報を伝える。
- 入口移動は既存行 (`organizer_lock_screen_title` など) の label / 副説明を変更せず、比較可能性を維持する。

## Acceptance criteria

- [ ] AC-1: `Later` 選択直後に、Organizer の再開場所 (Home settings の Home screen 内) を案内する非ブロッキングな hint が表示され、既存 defer semantics・persistence・run-journal 非発行は変化しない。
- [ ] AC-2: hint は Back / hint 外 touch / タイムアウトで閉じ、dismiss が outcome persistence を変更せず、launcher の通常操作を阻害しない。
- [ ] AC-3: Home settings の `Organize home layout` 入口が `Layout` セクション内ではなく `General` セクションに配置され、label / destination / 副説明が変化せず、同一画面内の既存行の挙動に影響しない。
- [ ] AC-4: hint は TalkBack / keyboard / DPAD で到達可能であり、focus 復帰と 200% font scale reflow を満たす。
- [ ] AC-5: hint の表示経路で例外が発生しても、defer persistence と launcher の動作は壊れず、自動テストとエミュレータ手動確認の証跡が PR に残る。

## Test oracle

| AC | Evidence |
|---|---|
| AC-1 | instrumentation (`OnboardingOrganizationProposalInstrumentationTest` 拡張: `Later` 後 hint 表示) + 既存 unit (`OrganizationOnboardingProposalTest`: defer semantics 不変の回帰) |
| AC-2 | instrumentation (hint dismiss / タイムアウト / persistence 不変) |
| AC-3 | instrumentation または UI test (Home settings 画面構成の回帰) |
| AC-4 | instrumentation (TalkBack semantics / focus 復帰 / 200% font scale) |
| AC-5 | instrumentation (表示失敗を注入した crash 非発生) + emulator 手動確認 |

## Open questions

- (実装前に解消、非 blocking) hint の表示時間の具体値 (秒) は plan で決め、spec では「短時間 (自動 dismiss)」の範囲で固定する。

## Change history

- 2026-09-10: Draft created for #232.
