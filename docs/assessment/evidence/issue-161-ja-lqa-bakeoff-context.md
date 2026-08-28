# Issue #161 bake-off fixed contextual input

This file is the immutable contextual input for the 24-unit bake-off recorded in [the assessment](./issue-161-ja-lqa.md). Candidate outputs were generated from these same units; candidate identities were hidden during owner scoring. The resource text below is pinned to the issue-branch head `d0caf80dc920ed4a91d660df16dd9ccce337f566` and the accepted Stage A semantic anchors.

Each unit contains the source/current copy, surface and role, neighboring copy, placeholder contract, behavior anchor, and rendered-context note supplied to both candidates.

## Fixed units

### 01 — `manual_organization_start`

- Source: `Review organization`
- Current Japanese: `整理案を確認`
- Surface / role: manual organization settings entry / action
- User class: normal user
- Neighboring copy: title `ホームレイアウトを整理`; summary `適用前に、ホームレイアウトの整理案を確認できます。`
- Placeholder contract: none
- Behavior anchor: opens the capture/planning flow and leads to a preview; it does not immediately modify the home layout.
- Reference / rendered context: Issue #52 manual organization entry, normal Japanese settings screen.

### 02 — `manual_organization_preview`

- Source: `Review the proposed organization`
- Current Japanese: `整理案を確認`
- Surface / role: manual organization preview screen / action
- User class: normal user
- Neighboring copy: title `ホームレイアウトを整理`; summary rows describe moved, retained, and unplaced placements.
- Placeholder contract: none
- Behavior anchor: opens the review screen for a proposed organization; it does not apply the proposal.
- Reference / rendered context: Issue #52 preview screen, normal Japanese and 200% font-scale screen.

### 03 — `manual_organization_confirm`

- Source: `Apply reviewed organization`
- Current Japanese: `確認した整理案を適用`
- Surface / role: manual organization preview screen / primary action
- User class: safety-critical
- Neighboring copy: preview summary; `現在のレイアウトは自動的に変更されません。`
- Placeholder contract: none
- Behavior anchor: applies the user-confirmed organization proposal; confirmation is required before layout mutation.
- Reference / rendered context: accepted Issue #52 application contract, preview confirmation button.

### 04 — `organizer_lock_action_keep_locked`

- Source: `Keep locked`
- Current Japanese: `ロックしたままにする`
- Surface / role: placement-lock review dialog / action
- User class: safety-critical
- Neighboring copy: `この配置のロック状態は不明です`; alternate action marks the placement unlocked.
- Placeholder contract: none
- Behavior anchor: preserves the locked target state and its placement during organization.
- Reference / rendered context: Issue #38 placement-lock review dialog.

### 05 — `organizer_category_overrides_title`

- Source: `App category overrides`
- Current Japanese: `アプリカテゴリの手動設定`
- Surface / role: category settings destination / title
- User class: normal user
- Neighboring copy: summary explains per-app category selection and automatic classification.
- Placeholder contract: none
- Behavior anchor: opens per-app manual category settings; it does not change an app category by itself.
- Reference / rendered context: root category override settings screen.

### 06 — `organization_onboarding_proposal_summary`

- Source: `Review a proposed organization first. Nothing changes until you confirm the preview.`
- Current Japanese: `まず整理案を確認できます。プレビューを確定するまで、何も変更されません。`
- Surface / role: onboarding proposal / summary
- User class: safety-critical
- Neighboring copy: title `整理案を確認`; action leads to proposal review.
- Placeholder contract: none
- Behavior anchor: explicitly preserves the no-change-before-confirmation guarantee.
- Reference / rendered context: Issue #53 onboarding proposal screen.

### 07 — `manual_organization_capturing`

- Source: `Reading the current home layout…`
- Current Japanese: `現在のホームレイアウトを読み込んでいます…`
- Surface / role: manual organization flow / progress status
- User class: normal user
- Neighboring copy: start action `整理案を確認`; next state prepares a proposal.
- Placeholder contract: none
- Behavior anchor: reports capture of the current home layout before planning.
- Reference / rendered context: manual organization progress state.

### 08 — `manual_organization_planning`

- Source: `Preparing a safe organization proposal…`
- Current Japanese: `安全に整理できる案を作成しています…`
- Surface / role: manual organization flow / progress status
- User class: safety-critical
- Neighboring copy: capture status; later preview and confirm actions.
- Placeholder contract: none
- Behavior anchor: reports proposal planning; “safe” describes the accepted planner constraint, not a success guarantee for application.
- Reference / rendered context: manual organization planning state.

### 09 — `manual_organization_apply_success`

- Source: `Organization was applied and verified.`
- Current Japanese: `整理を適用し、検証しました。`
- Surface / role: manual organization result / success message
- User class: safety-critical
- Neighboring copy: apply action; failure and recovery messages are separate states.
- Placeholder contract: none
- Behavior anchor: reports that application completed and post-application verification succeeded.
- Reference / rendered context: Issue #52 successful application result.

### 10 — `organizer_lock_error_stale`

- Source: `This item or the layout changed. Nothing was modified — try again.`
- Current Japanese: `このアイテムまたはレイアウトが変更されました。変更は適用されていません。もう一度お試しください。`
- Surface / role: placement-lock action error / warning
- User class: safety-critical
- Neighboring copy: lock review actions; stale state blocks applying the outdated intent.
- Placeholder contract: none
- Behavior anchor: confirms no change was applied and directs the user to retry from fresh state.
- Reference / rendered context: Issue #38 stale-intent error state.

### 11 — `manual_organization_rejected`

- Source: `This layout cannot be organized safely. Nothing was changed.`
- Current Japanese: `このレイアウトは安全に整理できません。何も変更されていません。`
- Surface / role: manual organization result / rejection
- User class: safety-critical
- Neighboring copy: retry and diagnostics actions; no layout mutation occurred.
- Placeholder contract: none
- Behavior anchor: rejects an unsafe proposal and preserves the existing layout.
- Reference / rendered context: planner rejection terminal state.

### 12 — `manual_organization_apply_rolled_back`

- Source: `The attempted organization was rolled back. Your previous layout remains in place.`
- Current Japanese: `整理を取り消しました。以前のレイアウトのままです。`
- Surface / role: manual organization result / recovery status
- User class: safety-critical
- Neighboring copy: apply failure and recovery-failed states; previous layout remains the reference state.
- Placeholder contract: none
- Behavior anchor: reports rollback result without promising a new recovery operation.
- Reference / rendered context: accepted organizer recovery contract, rollback result screen.

### 13 — `manual_organization_apply_recovery_failed`

- Source: `Automatic recovery could not be verified. Do not make another organization change yet.`
- Current Japanese: `自動復旧を確認できませんでした。今は別の整理を開始しないでください。`
- Surface / role: manual organization result / recovery warning
- User class: safety-critical
- Neighboring copy: unresolved and rollback results; diagnostics remains available.
- Placeholder contract: none
- Behavior anchor: recovery verification failed; tells the user not to start another organization change.
- Reference / rendered context: accepted organizer recovery-failure terminal state.

### 14 — `manual_organization_recovery_not_available`

- Source: `This saved layout can no longer be restored safely.`
- Current Japanese: `この保存済みレイアウトは、安全に復元できなくなっています。`
- Surface / role: saved-layout recovery / warning
- User class: safety-critical
- Neighboring copy: recovery preview and confirm actions; safety check precedes restore.
- Placeholder contract: none
- Behavior anchor: says the saved layout is no longer safely restorable; it does not promise fallback recovery.
- Reference / rendered context: recovery availability check screen.

### 15 — `organizer_category_override_automatic`

- Source: `Using automatic category`
- Current Japanese: `自動分類を使用中`
- Surface / role: category settings / current-state label
- User class: normal user
- Neighboring copy: manual setting label and `自動分類を使用` action.
- Placeholder contract: none
- Behavior anchor: reports the current category source; it is not an action label.
- Reference / rendered context: per-app category override row.

### 16 — `organizer_category_override_explicit`

- Source: `Override: %1$s`
- Current Japanese: `手動設定: %1$s`
- Surface / role: category settings / current-state label
- User class: normal user
- Neighboring copy: app name, profile, automatic/manual category actions.
- Placeholder contract: positional string `%1$s` is the selected category name.
- Behavior anchor: reports an explicitly saved per-app category override.
- Reference / rendered context: per-app category override row with category example `仕事`.

### 17 — `organizer_category_override_save`

- Source: `Save category override`
- Current Japanese: `カテゴリ設定を保存`
- Surface / role: category settings / primary action
- User class: normal user
- Neighboring copy: category picker, cancel action, saved/no-change result.
- Placeholder contract: none
- Behavior anchor: saves the selected manual category; it does not enable automatic classification.
- Reference / rendered context: category picker confirmation action.

### 18 — `organizer_lock_screen_review_all_confirm`

- Source: `Review %1$d placements as %2$s? This applies to the listed placements only.`
- Current Japanese: `%1$d件の配置を「%2$s」として確認しますか？ この操作は一覧の配置のみに適用されます。`
- Surface / role: placement-lock screen / confirmation dialog
- User class: safety-critical
- Neighboring copy: bulk review actions `すべてロックとして確認` and `すべてロック解除として確認`.
- Placeholder contract: `%1$d` placement count; `%2$s` target lock-state label; order is preserved.
- Behavior anchor: confirms the selected state only for the listed placements, not all placements.
- Reference / rendered context: placement-lock bulk review dialog with count `3`.

### 19 — `organizer_category_override_app_description`

- Source: `%1$s, %2$s, %3$s`
- Current Japanese: `%1$s、%2$s、%3$s`
- Surface / role: category settings / accessibility description
- User class: accessibility
- Neighboring copy: app label, profile label, and automatic/manual category state.
- Placeholder contract: three positional strings in app, profile, state order.
- Behavior anchor: provides a spoken description of the app row without changing settings.
- Reference / rendered context: TalkBack/content-description row with example `Example、個人用、自動分類を使用中`.

### 20 — `organizer_category_override_app_description_with_profile`

- Source: `%1$s, %2$s`
- Current Japanese: `%1$s、%2$s`
- Surface / role: category settings / accessibility description
- User class: accessibility
- Neighboring copy: app label and profile/category state when one profile field is applicable.
- Placeholder contract: two positional strings in source order.
- Behavior anchor: provides the compact spoken description for the profile-aware app row.
- Reference / rendered context: TalkBack/content-description row with example `Example、仕事用`.

### 21 — `organizer_lock_screen_item_state_description`

- Source: `%1$s, %2$s`
- Current Japanese: `%1$s、%2$s`
- Surface / role: placement-lock list / accessibility description
- User class: accessibility
- Neighboring copy: placement label and lock-state label.
- Placeholder contract: two positional strings in placement, state order.
- Behavior anchor: reads the placement and its current lock state as one spoken unit.
- Reference / rendered context: placement-lock list row with example `ホーム画面 1、ロック中`.

### 22 — `organizer_diagnostics_title`

- Source: `Organizer diagnostics`
- Current Japanese: `オーガナイザー診断`
- Surface / role: support settings / title
- User class: diagnostic
- Neighboring copy: privacy-safe diagnostic journal export description.
- Placeholder contract: none
- Behavior anchor: names the technical support surface; technical terminology is intentional here.
- Reference / rendered context: diagnostics settings destination.

### 23 — `organizer_diagnostics_description`

- Source: `Export the privacy-safe organizer diagnostics journal to a file for support.`
- Current Japanese: `プライバシーに配慮したオーガナイザー診断ジャーナルをサポート用にファイルへ書き出します。`
- Surface / role: support settings / description
- User class: diagnostic
- Neighboring copy: export action and export result/error messages.
- Placeholder contract: none
- Behavior anchor: explains that a privacy-safe technical journal is exported for support; it does not describe normal-user organization.
- Reference / rendered context: diagnostics settings description.

### 24 — `organizer_diagnostics_export_label`

- Source: `Export organizer diagnostics`
- Current Japanese: `オーガナイザー診断データを書き出す`
- Surface / role: support settings / action
- User class: diagnostic
- Neighboring copy: diagnostics title and privacy-safe export description.
- Placeholder contract: none
- Behavior anchor: exports the diagnostic journal to a file for support.
- Reference / rendered context: diagnostics export action.
