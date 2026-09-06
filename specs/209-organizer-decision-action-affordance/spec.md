---
issue: "#209"
status: accepted
requirements: []
risk: []
updated: 2026-09-07
---

# Organizer の decision action がボタンとして視認でき、Apply と Cancel が decision 時点で揃って見える

## Problem

[Exploratory UX review](https://github.com/nunu1733/NunuLauncher/issues/209) (2026-09-05, F-02 / F-06 / F-16) が、Organizer フローの対話要素がすべて plain text で視覚的 affordance を欠くことを報告した。実装上の根拠は次のとおり (head `7eeb947e` 時点、2026-09-06 確認):

- `ManualOrganizationPreferences` の全 action は `ClickablePreference` (クリック可能な `PreferenceTemplate` 行) で描画される。この行は視覚的には静的 text 行と同一の表現であり、fill / border / icon によるコントロール表現を持たない。`Apply reviewed organization` (confirm) が `Proposed changes` 見出しや `Show all N items` トグルと同スタイルで並ぶため、高影響な commit 操作と低影響な toggle が視覚的に等価である。
- Preview state の描画順は 見出し → 入力文脈 → 件数 → 変更一覧 (group + 展開トグル) → constraint → **confirm** → **cancel** の末尾配置である。既定 (未展開) の proposal では `Cancel` が fold 下にあり、展開・scroll すると今度は `Apply` が画面下端で切れる。proposal 展開状態によって「見える道」が入れ替わり、決定時点で両者が揃って視認できない。
- TalkBack の観点でも、decision action が click 可能な text として報告され (role 無し)、button としての意味が伝わらない。

## Outcome

Organizer の decision 系 action (confirm / cancel / restore) が、Lawnchair 既存の visual language に整合する Material3 button として描画され、静的 text や list toggle から視覚的に区別される。Preview 画面では `Apply` と `Cancel` が proposal の展開状態によらず、同一 viewport 内に揃って視認できる。TalkBack はこれらを button として報告する。

## Scope

- **decision pair の button 化と同時視認配置**: `State.Preview` の confirm (`Apply reviewed organization`) と cancel (`Cancel`) を Material3 button の decision group (縦並び全幅) として描画する。具体変更一覧がある場合 (`details != null`) は preview 見出しの直後に置き、degraded の場合 (`details == null`) は欠落告知行と count-only summary の後に置く (§D2)。`State.RecoveryPreview` の `Restore saved layout` と `Cancel` も同一の decision group 構成へ統一する。
- **復旧導線の button 化**: `State.Applied` (apply 成功時) の safety net `Restore the previous layout` を、preference row より強調された Material3 button で描画する。
- **TalkBack role**: 本 spec が button 化する全 control が `Role.Button` を報告することを instrumentation test で検証する。
- **spec 52 / spec 195 の契約調整**: decision group の先頭配置に伴い keyboard/Switch traversal の到達順序が変わるため、spec 52 の §"Preview and details" 関連記述と spec 195 の traversal 契約 (status → 展開 action → confirm → cancel) を本 spec の順序 (status → confirm → cancel → 展開 action) へ更新する。同一 PR で行う。
- 既存の a11y 契約 (status 見出しの focus + liveRegion、cancel 後の start action への focus 復帰、展開後の展開 action focus 保持、200% font scale で wrap、ja/en strings 解決) は退化させない。

## Non-goals

- run-state machine、confirm/cancel/recovery の coordinator 契約、適用 semantics、safety messaging の変更。zero-write の presentation 変更である。
- decision 系以外の navigation/entry action (`Review organization`、`Start a new review`、`Try again`、`Capture and review again`、`Open organizer diagnostics`) の button 化。これらは Lawnchair 設定画面の standard である preference row のままとし、周辺 Lawnchair settings UI の visual language を優先する (§D2)。
- 展開トグル (`Show all N items`) 自体の表現変更。list toggle は decision action と区別される対象であり、その情報設計は spec 195 の ownership である (§D4)。
- #195 confirmation UI の情報設計変更、visual before/after preview、#123 の visual convergence の再実施。
- 新規 string resource の追加。すべて既存 label の再利用であり、ja/en の新規翻訳は発生しない。

## Domain language

実装語のみのため空。

## Design decisions

### D1: decision action の表現 — **Material3 button (既存参照: 確認 bottom sheet の confirm/cancel pair)**

confirm / cancel / restore は `ClickablePreference` 行から Material3 button へ置換する。参照は Lawnchair 内の既存 decision 表現、`PreferenceClickConfirmation` bottom sheet (`lawnchair/src/app/lawnchair/ui/preferences/components/controls/ClickablePreference.kt`) と `PermissionDialog` である:

- **confirm (`Apply reviewed organization` / `Restore saved layout`)**: `Button` (filled) — 画面上の primary decision であることを示す。
- **cancel (`Cancel`)**: `OutlinedButton` — 同一 decision 時点で視認できるが、confirm より低い視覚重み。
- **safety net (`Restore the previous layout`, Applied 後)**: `FilledTonalButton` — 周囲の preference row からは区別され、かつ破壊的でない再確認 action (recovery preview への導線) として filled confirm より低い重み。

いずれも既存 string の再利用であり、theme token 外の色・shape・独自装飾を導入しない (#123 の「既存 component / theme token 再利用」契約)。Material3 の minimum interactive component enforcement (既定有効) により touch target は 48dp 以上が保証される。

### D2: decision group の配置 — **具体一覧モードでは見出し直後、degraded モードでは告知・summary の後。末尾配置・sticky bar は不採用**

具体変更一覧がある場合 (`details != null`)、`State.Preview` の描画順を 見出し → **decision group** → 入力文脈 → 件数 → 変更一覧 (group + 展開トグル) → constraint へ変更する。decision group を list item の 2 番目に置くことで、画面サイズ・font scale・proposal の展開状態にかかわらず confirm と cancel が entry 直後に同一 viewport へ描画されることが構造的に保証される。

**degraded モード (`details == null`) では spec 195 D1 が優先される**: 描画順を 見出し → 欠落告知行 → count-only summary → **decision group** へ変更する。ユーザーは「具体的な変更一覧を用意できなかった」告知と件数のみの確認である事実を、primary action に到達する前に読む。#195 の「review → confirm」情報設計と decision group の先頭配置が衝突するのは degraded モードのみであり、具体一覧モードでは「cancel の即時視認 = レビューを強制されない・中断可能であることの表明」として先頭配置を採用する (変更一覧そのものは decision の直後に読める)。

- **scroll に対する契約の限界を明記**: decision group は通常の list item であり、persistent / sticky surface ではない。ユーザーが変更一覧を下へ scroll すれば decision group は viewport 外へ出る (通常の設定画面と同じ挙動)。本 spec が保証するのは「**展開によって片方の decision のみが fold 下へ押し出されない**」ことまでであり、scroll 後の可視性は要求しない (F-02 の実害は展開時の押し出しであり、F-16 の解消は §D4 のとおり)。persistent surface を要求する場合は別 spec を要求する。
- **末尾維持 + sticky footer の不採用理由**: sticky bar は `PreferenceScaffold` / `PreferenceLazyColumn` の構造変更 (全 settings 画面が共有する component への介入) か、この画面専用の overlay 表現 (Nunu-only convention、#123 違反) を要求する。リスクに対して得られるものは少ない (先頭配置で展開時の同時視認は満たされる)。
- **decision group の先頭配置によるUX影響の受容**: ユーザーが変更一覧を読む前に decision 操作が視界に入るが、cancel が即座に視認できることは「レビューを強制されない・中断可能である」ことの表明として機能する。誤 tap リスクは confirm を filled / cancel を outlined と重み分けし、confirm が previewed plan の A2 exact precondition gate によって guard されていることで緩和される (適用安全性は本 spec の対象外)。
- **`State.Applying` の `Cancel before applying` は preference row のまま**: 適用進行中の安全文脈 (checkpoint 前のみ中断可能) を持つ遷移状態であり、decision pair ではない。変更する場合は別 spec とする。

### D3: decision action の layout — **縦並び全幅、label は button 内で折り返し**

decision action は見出し直下に縦並び (confirm → cancel の読み順・traversal 順) の全幅 button で配置する。DPAD / Switch Access の垂直 traversal が視覚順どおり confirm → cancel を訪れること (spec 52 契約) を単純な実装で保証するため、同一行への横並び (横方向が DPAD down で到達しない実装依存挙動) は採用しない。幅が不足する環境 (200% font scale 等) では label が button 内で折り返され、両 button は常に同一 viewport 内に留まり、clip も横 scroll 依存も発生しない。実装は stable API (`Column` + `Button` / `OutlinedButton`) のみを使用する。

### D4: 展開トグルと navigation action の取り扱い — **変更しない (residual を記録)**

展開トグル `Show all N items` は `ClickablePreference` 行のまま残す。本 spec の問題は「decision action が toggle と視覚的に等価」であり、F-16 の実害 (展開で confirm が fold 下へ押し出される) は D2 の先頭配置により構造的に解消される。toggle 自体の affordance 弱さと、navigation/entry action の plain-text 性 (F-06 のうち decision 系以外) は、Lawnchair settings の preference row 規約を優先する判断としてここに residual を記録する。Lawnchair 設定画面群との収束を壊して全 action を button 化しない。必要になった場合は #195 / #123 の ownership で扱う。

### D5: TalkBack role — **button 化対象は `Role.Button` を報告し、preference row は現行規約を維持**

本 spec が button 化する control (confirm / cancel / recovery confirm / restore safety net) は Material3 button の標準 semantics により `Role.Button` を報告する。`ClickablePreference` 行 (Lawnchair 全 settings 共通) への role 追加は共有 component への介入となるため行わない。deterministic deferral に掲げられた touch target 寸法は D1 の M3 既定に依存し、200% font scale reflow は既存 test で継続検証する。

## Behavior scenarios

### Scenario: Apply と Cancel が展開状態によらず同時に視認できる

Given 具体的変更一覧 (`details != null`) を含む `State.Preview` が公開されており、移動 group が truncation 閾値を超えている,

When preview 画面が表示される,

Then `Apply reviewed organization` と `Cancel` がどちらも初期 viewport 内で表示され、いずれも scroll 無しに到達できる,

And 変更 group を展開して行数が増えた後も、両者は引き続き同一 viewport 内で表示される。

### Scenario: degraded preview では欠落告知が decision に先行する

Given 環境的失敗により `State.Preview(summary, details = null)` が公開されている,

When preview 画面が表示される,

Then「具体的な変更一覧を用意できなかった」告知行と count-only summary が `Apply reviewed organization` より先に表示される,

And decision group は summary の直後に配置され、confirm / cancel が同時に視認できる。

### Scenario: Decision actions がボタンとして区別される

Given `State.Preview` または `State.RecoveryPreview` が表示されている,

When decision action (`Apply reviewed organization`, `Cancel`, `Restore saved layout`) を観察する,

Then それぞれが Material3 button として描画され (fill / border を持ち)、静的 text 行や展開トグルと同一表現ではない,

And TalkBack semantics として `Role.Button` を報告する。

### Scenario: 復旧導線が安全網として視認できる

Given apply が検証済み成功で終了し (`ApplyResult.Applied`)、recovery point が残存している,

When 結果画面を観察する,

Then `Restore the previous layout` が周囲の preference row より強調された button として描画される。

### Scenario: 既存の a11y・focus 契約が退化しない

Given preview が表示されている,

When keyboard / Switch traversal または TalkBack で操作する,

Then traversal は status 見出し → confirm → cancel → (変更一覧) → 展開 action の順で全 action に到達でき、status 見出しは focus と `liveRegion = Polite` を保持する,

And cancel 後は start action へ focus が復帰し、展開後は展開 action が focus を保持する (spec 52 契約),

And 200% font scale で decision group は label を button 内で折り返して表示し、全必須 content が到達可能である。

## Data and state

- 読む data: 変更なし (`State.Preview` / `State.Applied` / `State.RecoveryPreview` の observable 値のみ)。
- 永続化: なし。migration / backup / restore / Launcher DB への影響なし。ホームレイアウト安全規約の適用対象外 (zero-write)。
- rollback: PR revert で現行描画へ戻る。

## Permissions, privacy, and security

None。新たな permission、通信、telemetry は追加しない。表示する文字列は既存 resource の再利用のみである。

## Accessibility and localization

- button 化対象は `Role.Button` を報告する (§D5)。label は既存 string を再利用し、新規 strings は発生しないため ja/en 解決契約 (#123) に新たな面は追加されない。
- focus 契約: status 見出しの focus + `liveRegion = Polite`、cancel 後の focus 復帰、展開後の展開 action focus 保持は spec 52 / spec 195 契約を継承する。
- traversal 順序は (具体一覧モードでは) status → confirm → cancel → 変更一覧 → 展開 action、(degraded モードでは) status → 告知行 → summary → confirm → cancel へ更新される (spec 52 / spec 195 の記述を同一 PR で更新)。
- touch target は Material3 minimum interactive component enforcement (48dp) に依存する。200% font scale で label は button 内で折り返し、clipping 無しに到達可能である。

## Acceptance criteria

| AC | Acceptance criterion |
|---|---|
| AC-1 | 具体変更一覧 (`details != null`) の `State.Preview` では、confirm / cancel が preview 見出し直後の decision group に Material3 button として描画され、truncation が発生する大きさの変更一覧 (展開トグルが存在する fixture) において、初期表示時と group 展開後の両方で confirm と cancel が同時に表示される。 |
| AC-1a | `details == null` (degraded) の `State.Preview` では、欠落告知行と count-only summary が decision group より先に描画され、decision group の confirm / cancel は summary の直後に同時に表示される。 |
| AC-2 | `State.RecoveryPreview` の `Restore saved layout` / `Cancel` と、`State.Applied` (成功) の `Restore the previous layout` が §D1 の指定どおり button として描画される。 |
| AC-3 | button 化対象の全 control が TalkBack semantics で `Role.Button` を報告する。 |
| AC-4 | 既存 a11y 契約が退化しない: status 見出しの focus + liveRegion、cancel 後の start action focus 復帰、展開後の展開 action focus 保持、traversal 到達性 (新順序)、200% font scale (label の button 内折り返し含む)、ja/en string 解決。 |
| AC-5 | spec 52 と spec 195 の traversal 契約記述が新順序へ更新され、同一 PR に含まれる。 |
| AC-6 | coordinator / application 契約の変更が存在しない (run-state 遷移・適用・recovery の既存 test が無変更で通過する)。 |

## Test oracle

| AC | Evidence |
|---|---|
| AC-1 | `ManualOrganizationPreferencesInstrumentationTest` に追加した同時視認 test (移動 6 件 + truncation トグル fixture で初期表示時と展開後の `assertIsDisplayed` × confirm/cancel) |
| AC-1a | 同 instrumentation test: degraded fixture で告知行 / summary が confirm より先に存在することと decision pair の同時表示を主張 |
| AC-2 | 同 instrumentation test: Applied→recovery entry と RecoveryPreview の描画主張 |
| AC-3 | 同 instrumentation test: `SemanticsProperties.Role == Role.Button` 主張 |
| AC-4 | 既存 instrumentation test 群 (focus 復帰 / liveRegion / traversal / 200% font scale / ja locale 解決) の無変更または新順序への整合的更新で通過 |
| AC-5 | PR diff review (spec 52 §Preview and details 関連、spec 195 change history / traversal 記述) |
| AC-6 | JVM organizer unit gate + organizer instrumentation lane の green (CI `final-status`) |

## Open questions

None。設計判断は §D1–D5 のとおり spec 時点で確定した。実装 PR で owner review を継続する。

## Change history

- 2026-09-06: Drafted for Issue #209。Issue 本文の exploratory review 結果 (F-02/F-06/F-16)、spec 195 / spec 52 の a11y・traversal 契約、#123 の visual language 契約、現行実装 (`ManualOrganizationPreferences.kt`、`ClickablePreference.kt`、decision 表現の既存参照 `PreferenceClickConfirmation` / `PermissionDialog`) の調査を入力に作成。Issue owner の実施指示に基づき受理し、owner review は実装 PR で継続する。
- 2026-09-06: 実装 ([PR #239](https://github.com/nunu1733/NunuLauncher/pull/239))。D3 は初版の横並び案から縦並び全幅へ決め直し (DPAD down が同一行内の横方向へ到達しない実装依存挙動を traversal test が再現したため、失敗するテストで確認のうえ修正)。CI issue52 lane (pixel_7_pro / 560dpi) でのみ toggle 折りたたみ test が timeout する問題を pixel_7_pro AVD で再現・分析し、focus 復元後に toggle が gesture-navigation 領域へ追い込まれ tap が system pill に奪われることが原因と判明 — test を ScrollBy semantics action で toggle を安全領域へ移動してから tap する方式へ修正 (製品コードの変更なし)。
- 2026-09-07: Owner review ([PR #239](https://github.com/nunu1733/NunuLauncher/pull/239)) 対応 (Blocking 2件): (1) 「展開・scroll 状態によらず同時視認」のうち scroll 部分を契約から削除 — decision group は通常の list item であり、scroll 後は viewport 外に出ることを §D2 に明記し、本 spec の保証を「展開による片方の fold 下押し出しの防止」に限定。(2) spec 195 D1 の degraded 告知契約との衝突を解消 — `details == null` の描画順を 見出し → 告知行 → count-only summary → decision group へ変更 (AC-1a 追加、§D2 に degraded 優先規定)。通常 preview では decision group 先頭配置を正本とすることを §D2 に明記。`status` を実装 PR 再review のため `accepted` へ戻す。

## References

- [Issue #209: Organizerの決定操作がplain textでaffordanceを欠き、ApplyとCancelがdecision時点で揃って見えない](https://github.com/nunu1733/NunuLauncher/issues/209)
- [Spec 195: organizer confirmation change list (traversal 契約の調整先)](../195-organizer-confirmation-change-list/spec.md)
- [Spec 52: manual full-organization vertical slice (a11y 契約)](../52-manual-full-organization-vertical-slice/spec.md)
- [Spec 123: organizer UI convergence (既存 visual language / theme token 契約)](../123-organizer-ui-convergence/spec.md)
- [AGENTS.md](../../AGENTS.md)
