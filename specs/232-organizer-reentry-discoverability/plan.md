# Implementation Plan: `Later` 選択後もユーザーが自力でOrganizerの再開導線を再発見できる

> Issue: #232
> Spec: [spec.md](./spec.md)
> Status: draft

## Current evidence

- `Later` ボタンは `OrganizationOnboardingProposalContent.laterButton` で、click handler は `resolved = true; controller.defer(); close(false)` のみである。defer 後の案内はない ([OrganizationOnboardingProposal.kt:295-299](../../lawnchair/src/app/lawnchair/organizer/ui/OrganizationOnboardingProposal.kt), head `cc41f3bb1b` 2026-09-10 確認)。
- proposal の host は `OrganizationOnboardingProposalView : AbstractFloatingView` で、`launcher.dragLayer.addView` で bottom sheet 形式に表示する。`show()` / `handleClose()` / focus 復帰 (`focusBeforeOpen`) の実装済み pattern がある ([OrganizationOnboardingProposal.kt:265-391](../../lawnchair/src/app/lawnchair/organizer/ui/OrganizationOnboardingProposal.kt))。
- Organizer の恒常入口は `HomeScreenPreferences` の `Layout` セクション内、`home_screen_grid` と `organizer_lock_screen_title` の後に置かれている ([HomeScreenPreferences.kt:143-183](../../lawnchair/src/app/lawnchair/ui/preferences/destinations/HomeScreenPreferences.kt))。`Layout` セクションは wallpaper セクションの下にあり、Issue #232 の観測 (「Wallpaper より下の Layout セクション内」) と一致する。
- `HomeScreenPreferences` の `General` セクションは `auto_add_shortcuts_label` / `gesture_double_tap` / `infinite_scrolling_label` の 3 行である ([HomeScreenPreferences.kt:76-94](../../lawnchair/src/app/lawnchair/ui/preferences/destinations/HomeScreenPreferences.kt))。
- 既存テスト: `tests/unit/app/lawnchair/organizer/ui/OrganizationOnboardingProposalTest.kt` (controller unit)、`tests/organizer-instrumentation/app/lawnchair/organizer/ui/OnboardingOrganizationProposalInstrumentationTest.kt` (1059 行、proposal UI 全般)。
- 確認済み事実と推測の区別: 上記は source 読み取りによる確認済み。「hint の自動 dismiss 時間の最適値」は推測であり、plan で仮決定して emulator で検証する。

## Design

### Modules and interfaces

変更は presenter 層 (`organizer/ui`) と settings 画面構成のみ。interface 追加なし、planning / application / diagnostics への変更なし。

1. **Re-entry hint (新規内部 view、`OrganizationReentryHint`)**
   - `OrganizationOnboardingProposal.kt` と同じ file (同 fixture を共有する内部 view) に、`AbstractFloatingView` を継承した小さな hint view を追加する。proposal と同じ dragLayer host / bottom sheet 配置 / focus 復帰 pattern を再利用する。
   - content は title (`organization_reentry_hint_title`) + body (`organization_reentry_hint_body`) の read-only 表示のみで、action button は持たない。
   - 表示契約: `OrganizationOnboardingProposalController.defer()` の呼び出し後 (persistence は既に完了)、proposal の `close(false)` と同じ dragLayer へ `OrganizationReentryHint.show()` する。表示は `runCatching` で囲み、失敗時に logcat へ warning を出して握りつぶす (AC-5 の crash 非発生)。
   - dismiss 契約: Back (`AbstractFloatingView` の back handling)、hint 外 touch、タイムアウト (auto-dismiss) の 3 経路で閉じる。いずれも persistence を書かない。
   - タイムアウト: `postDelayed` ベースの 6 秒 (仮決定)。view detach 時に callback を cancel する。`6_000L` を file 内 const にし、テストからは参照しない (挙動は integration で確認)。
   - accessibility: `importantForAccessibility=YES`、title + body を 1 つの contentDescription に統合しない ( TalkBack が 2 ノードとして読める構成を維持しつつ、hint の役割を伝える)。`TYPE_ON_BOARD_POPUP` と同種の view type は持たせず、`isOfType` は専用 type を返す。focus 復帰は proposal と同じ `focusBeforeOpen` pattern を使う。
2. **`OrganizationOnboardingProposalView` の `onLater` 変更 (最小)**
   - 現在の `onLater = { resolved = true; controller.defer(); close(false) }` に、`close(false)` 後の hint 表示を 1 行追加する。`resolved = true` と `controller.defer()` の順序・意味は変更しない (spec 53 の outcome semantics を保持)。
   - `Skip` / `Review organization` / outside-dismiss 経路は変更しない (hint を出すのは `Later` 経路だけ)。
3. **`HomeScreenPreferences` の入口移動**
   - `Organize home layout` の `NavigationActionPreference` を `Layout` セクションから `General` セクションへ移動する。label / destination / subtitle は変更しない。
   - `General` セクション内の位置: 既存 3 行の後 (既存行の下)。`auto_add_shortcuts` は home 追加挙動、`gesture` は home 操作、`infinite_scrolling` は home 挙動であり、その後に「整理」という home 全体の操作を置くのが既存 IA と整合する。移動後も `Layout` セクションの lock / grid / diagnostics / category overrides の各行は変更しない。

### Data flow

- hint: proposal defer → persistence (`DEFERRED`) → proposal close → hint show (dragLayer attach) → (Back / 外 touch / 6 秒) → hint close → focus 復帰。persistence への書込みは proposal の既存 defer のみで、hint は書かない。
- 入口移動: data flow への影響なし (route と destination は不変)。

### Alternatives rejected

1. **`Later` copy に再開場所を含める** — 案内が 1 文に圧縮され、Home settings の階層 (Settings → Home screen → Organize home layout) を正確に伝えられない。hint の方が情報量と視認性が高い。copy 修正のみでは Issue #232 の「再発見」要求 (AC-1) を満たす証跡が弱い。
2. **Home 長押しメニューへの直接入口追加** — launcher popup は上流 (Launcher3) 由来の menu 構造であり、fork の patch surface を拡大する。Non-goal (settings 以外の新規入口) でもある。
3. **`Later` 後の次回 cold start で proposal を再表示する仕組みの変更** — spec 53 の outcome semantics の変更であり、Non-goal。
4. **proposal の再表示を permanent shortcut 化** — Issue #232 が「permanent shortcut 化は不要」と明記している。
5. **Organizer 入口を dashboard 直下へ出す** — Home settings 全体の IA 再設計に近く、Non-goal。既存 `General` セクション内の移動にとどめる。

## Change set

| Area | Intended change | Why here |
|---|---|---|
| `lawnchair/src/app/lawnchair/organizer/ui/OrganizationOnboardingProposal.kt` | `OrganizationReentryHint` (内部 view) を追加し、`onLater` 経路で defer 後に表示する | proposal presenter と同 fixture で再利用性が高く、`Later` 経路の局所変更で済む |
| `lawnchair/res/values/strings.xml` / `values-ja-rJP/strings.xml` | `organization_reentry_hint_title` / `organization_reentry_hint_body` を追加 | 既存 organizer 文言の対訳規約に従う |
| `lawnchair/src/app/lawnchair/ui/preferences/destinations/HomeScreenPreferences.kt` | `Organize home layout` 行を `Layout` → `General` セクションへ移動 | 入口の視認性向上。destination 不変 |
| `tests/unit/app/lawnchair/organizer/ui/OrganizationOnboardingProposalTest.kt` | hint 表示契約の unit 拡張 | controller / presenter 契約の回帰 |
| `tests/organizer-instrumentation/app/lawnchair/organizer/ui/OnboardingOrganizationProposalInstrumentationTest.kt` | hint 表示 / dismiss / persistence 不変 / focus 復帰の instrumentation 拡張 | AC-1/2/4/5 の実機確認 |

## Migration and recovery

- schema / rule migration: なし。
- failure 中の rollback: hint は永続化せず、表示失敗時は runCatching で握りつぶすため、rollback 不要である。
- release rollback / downgrade: 新規 preference / DB を持たないため影響なし。
- backup / restore: 影響なし。

## Verification

| Acceptance criterion | Automated/manual evidence | Command or environment |
|---|---|---|
| AC-1 | unit: defer 後の hint 表示契約 / instrumentation: `Later` 後 hint 表示 | `./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.ui.*'`; API 36.1 emulator (nunu_qpr2_api36_1 以外) |
| AC-2 | instrumentation: hint dismiss / persistence 不変 | 同上 |
| AC-3 | instrumentation: Home settings 構成回帰 (入口行の存在/位置) | 同上 |
| AC-4 | instrumentation: focus 復帰 / TalkBack semantics / 200% font scale | 同上 + emulator 手動確認 |
| AC-5 | instrumentation: 表示失敗注入で crash 非発生 / emulator 手動確認 (`Later` → hint → Home settings → Organizer 到達) | 同上 |

含めるべき観点: unit/contract、UI/accessibility、failure injection。DB/property/performance は対象外 (layout data / DB を扱わないため)。

## Documentation updates

- [ ] spec status/history (accepted → implemented)
- [ ] CONTEXT.md (変更なし — domain language 追加なし)
- [ ] DESIGN.md (変更なし — module structure 変更なし)
- [ ] ADR (不要 — 3 条件を満たす判断なし)
- [ ] AGENTS.md (変更なし — workflow / verified command 変更なし)

## Execution checklist

- [ ] Current behavior reproduced (emulator: `Later` → hint なしで Organizer 再発見が困難な現状確認)。
- [ ] Tests fail for the missing behavior。
- [ ] Minimal implementation completed。
- [ ] Migration/recovery verified (該当なし — 永続化変更なし。hint 表示失敗時の安全性のみ検証)。
- [ ] Full relevant verification completed。
- [ ] PR evidence and remaining risks recorded。
