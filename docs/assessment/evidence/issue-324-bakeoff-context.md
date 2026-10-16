# Issue #324 bake-off fixed contextual input

> Status: fixed input for the Product Language Reviewer model bake-off (spec AC-324-04 / AC-324-15).
> Pinned to main `25f97aef4d` (post PR #602 merge). Values below are the current default/ja at that head.
> Method: #161 bake-off adapted — same units to every candidate, anonymized outputs, per-item blind scoring on the #161 7 axes plus an 8th AI-register axis (spec AC-324-15), each 0–2 (total /16). A 0 on Accuracy (meaning) or Safety preservation is a hard failure regardless of total.
> Family rule (spec AC-324-04): the dedicated reviewer must run on a model family different from the implementation worker (GLM-5.3) and the functional reviewer (GPT/ChatGPT). Family-barred candidates can score but cannot be adopted.
> Classes per unit: CTA/title (T), onboarding/progress/status (O), failure/safety/recovery (S), settings/category/lock (L), a11y (A), diagnostic (D).

## Fixed units

### 01 `homeedit_menu_move_to_page` — T/CTA
- Default: `Move to page…` / Current ja: `ページへ移動…`
- Surface/role: per-item home edit bottom-sheet menu / menu action. Safety: normal.
- Neighboring: `homeedit_menu_add_to_folder`, `homeedit_menu_remove_from_home`.
- Placeholder: none. Behavior: opens a page picker for the selected item; does not move yet.

### 02 `homeedit_undo_label` — O/status
- Default: `Edit applied` / Current ja: `編集を適用しました`
- Surface/role: edit confirmation surface / status + entry point to undo. Safety: normal.
- Neighboring: undo action chip.
- Placeholder: none. Behavior: single-item edit committed; tapping undo starts the inverse operation.

### 03 `homeedit_undo_error_stale` — S/failure
- Default: `Can’t undo: the icon has moved since.` / Current ja: `元に戻せません: アイコンがその後移動されています。`
- Surface/role: undo failure snackbar/dialog / error. Safety: safety-critical (ADR-0013 undo contract).
- Neighboring: the failed undo action label.
- Placeholder: none. Behavior: undo refused because the item changed after the edit; the applied edit stays.

### 04 `manual_organization_exclude_action` — T/CTA
- Default: `Exclude` / Current ja: `提案から外す`
- Surface/role: visual before/after preview, per-item row action / action. Safety: normal.
- Neighboring: `manual_organization_include_action`, row shows the proposed placement.
- Placeholder: none. Behavior: removes this item from the proposal and triggers replanning.

### 05 `manual_organization_exclusion_blocked_structural` — S/failure
- Default: `not excludable: replanning decides this row automatically` / Current ja: `除外できません: 再計画が自動的に決定する行です`
- Surface/role: preview exclusion refusal / warning. Safety: safety-critical.
- Neighboring: the disabled Exclude control.
- Placeholder: none. Behavior: this row is computed structurally (e.g. page/dock frame) and cannot be excluded.

### 06 `manual_organization_warning_duplicate_launch_target` — S/warning
- Default: `Duplicates of another icon were kept in place: %1$d` / Current ja: `重複のため移動させなかった配置: %1$d件`
- Surface/role: preview warning list / warning. Safety: safety-critical.
- Placeholder: `%1$d` count.
- Behavior: items that resolve to the same launch target were intentionally not moved (#451).

### 07 `manual_organization_unplaced_candidates_heading` — O/status
- Default: `Not placed by this proposal: %1$d` / Current ja: `この提案では配置されない候補: %1$d件`
- Surface/role: preview summary section / heading. Safety: normal.
- Placeholder: `%1$d`.
- Behavior: count of candidates this proposal leaves unplaced; user decision not forced.

### 08 `organizer_hub_title` — T/title
- Default: `Organizer` / Current ja: `オーガナイザー`
- Surface/role: organizer hub screen / title. Safety: normal.
- Neighboring: `organizer_hub_summary` `整理の状態、整理案の確認、材料を1か所で扱えます。`
- Placeholder: none. Behavior: entry hub for status/proposals/materials.
- Note: `ja-glossary.tsv` maps organizer → 整理機能 (オーガナイザーは診断/サポートのみ許容).

### 09 `organizer_strategy_title` — L/settings
- Default: `Organization strategy` / Current ja: `整理ストラテジー`
- Surface/role: strategy settings / title. Safety: normal.
- Neighboring: `organizer_strategy_summary` `ホーム画面を整理するときの方針を選択します。`
- Placeholder: none. Behavior: chooses the planning strategy.

### 10 `organization_strategy_tidy_v2_description` — L/settings
- Default: `Closes gaps on each screen without moving apps to other screens. Widgets are tidied within their screen as well.` / Current ja: `アプリを別のページへ移動せず、各ページのすき間を詰めます。ウィジェットも同じページ内で整えます。`
- Surface/role: strategy picker row / description. Safety: normal.
- Neighboring: sibling strategy descriptions, e.g. tidy v1 ja `アプリを別のページへ移動せずに、各ページの隙間を閉じます。ウィジェットは移動しません。`
- Placeholder: none. Behavior: describes Tidy v2 planning behavior.

### 11 `organizer_strategy_frozen_operation_active` — S/stale-state
- Default: `An organize operation is in progress. Interrupt it before changing the strategy.` / Current ja: `整理操作を実行中のため、中断してから整理方法を変更してください。`
- Surface/role: strategy settings blocked state / warning. Safety: safety-critical.
- Placeholder: none. Behavior: strategy change blocked while a run is active; user must interrupt first.

### 12 `exchange_capability_flow` — O/onboarding
- Default: `The conversation happens inside your external AI app (such as ChatGPT). Send the request once, answer a few short questions (2–4), confirm the plan, and receive one final proposal. Your conversation is never sent to NunuLauncher.` / Current ja: `相談はChatGPT等の外部AIアプリ内で行われます。依頼文は1回送り、AIからの質問 (2〜4問) に答えて整理方針を確認すると、最終案を1回だけ受け取れます。会話の内容がNunuLauncherに送信されることはありません。`
- Surface/role: exchange capability explanation / onboarding copy. Safety: safety-critical (privacy guarantee sentence).
- Placeholder: none. Behavior: interview-first flow + privacy boundary (#327).

### 13 `exchange_discard_confirm_body` — S/confirmation
- Default: `Discarding invalidates this request; the AI's reply can no longer be imported. This cannot be undone.` / Current ja: `破棄するとこの依頼は無効になり、AIの回答を取り込めなくなります。この操作は取り消せません。`
- Surface/role: discard confirmation dialog / body. Safety: safety-critical (irreversible).
- Placeholder: none. Behavior: discarding invalidates the imported request permanently (#328).

### 14 `exchange_status_clipboard_empty` — S/failure-recovery
- Default: `The clipboard is empty or could not be read. Copy the reply in the AI app again, return to this app, and import once more. If it still fails, use the file import or paste the text manually.` / Current ja: `クリップボードが空か、読み取れませんでした。AIアプリで回答をコピーし直し、このアプリに戻ってからもう一度読み込んでください。うまくいかない場合はファイル読み込みか手動貼り付けをお使いください。`
- Surface/role: import input UI failure / error with recovery guidance. Safety: safety-critical.
- Placeholder: none. Behavior: clipboard import failed; recovery steps + alternates (#332).

### 15 `exchange_import_from_clipboard` — T/CTA
- Default: `Import from clipboard` / Current ja: `クリップボードから読み込む`
- Surface/role: import input UI / action. Safety: normal.
- Neighboring: file import action, fallback toggle.
- Placeholder: none. Behavior: reads the AI reply from the clipboard and starts import.

### 16 `exchange_ai_consultation_toggle_label` — L/settings (ja missing)
- Default: `AI consultation` / Current ja: *(missing — propose a ja label)*
- Surface/role: exchange settings toggle / label. Safety: normal.
- Neighboring: toggle description describing freeze application during consultation (#443).
- Placeholder: none. Behavior: enables AI-consultation mode coupling.
- Note: this string currently has no ja resource; the candidate is asked to propose one.

### 17 `manual_organization_rejection_invalid_category_provenance` — D/diagnostic (ja missing)
- Default: `Category source not allowed: %1$d` / Current ja: *(missing — propose a ja label)*
- Surface/role: manual organization rejection code list / diagnostic row. Safety: diagnostic.
- Placeholder: `%1$d` rejection code id.
- Behavior: displayed when a category override is rejected because its provenance is not allowed (#336).
- Note: no ja resource today; diagnostics surface may keep technical precision (`TECHNICAL_ONLY` acceptable).

### 18 `organizer_diagnostics_recent_import_failure` — D/diagnostic
- Default: `Recent import failure` / Current ja: `直近の取り込み失敗`
- Surface/role: diagnostics screen row / label. Safety: diagnostic.
- Placeholder: none. Behavior: shows the most recent exchange import failure.

### 19 `organizer_lock_screen_item_state_description` — A/a11y
- Default: `%1$s, %2$s` / Current ja: `%1$s、%2$s`
- Surface/role: lock review list item / accessibility state description (read aloud).
- Placeholder: `%1$s` item label, `%2$s` lock state.
- Behavior: composes item + lock state for TalkBack.

### 20 `exchange_disclosure_hint` — O/guidance
- Default: `Send this exact text to the AI app of your choice. Nothing is sent until you choose a send method.` / Current ja: `この内容を任意のAIアプリへ送信します。送信方法を選ぶまで何も送信されません。`
- Surface/role: pre-send disclosure / hint. Safety: safety-critical (send consent).
- Placeholder: none. Behavior: explains manual send; nothing transmits automatically.

### 21 `manual_organization_replanning` — O/progress
- Default: `Replanning the proposal` / Current ja: `提案を再計算中`
- Surface/role: preview after exclusion change / progress. Safety: normal.
- Placeholder: none. Behavior: planner recomputes the proposal after per-item exclusion (#508).

### 22 `organizer_hub_proposal_row` — O/status
- Default: `You have an imported proposal` / Current ja: `取り込み済みの提案があります`
- Surface/role: organizer hub row / status entry. Safety: normal.
- Placeholder: none. Behavior: indicates an imported exchange proposal is pending review.

### 23 `homeedit_undo_error_no_space` — S/failure
- Default: `Can’t undo: no space at the original spot.` / Current ja: `元に戻せません: 元の位置に空きがありません。`
- Surface/role: undo failure / error. Safety: safety-critical (ADR-0013).
- Placeholder: none. Behavior: undo refused for lack of space; applied edit stays.

### 24 `manual_organization_diagram_widget` — T/label
- Default: `Widget` / Current ja: `ウィジェット`
- Surface/role: visual before/after diagram legend / label. Safety: normal.
- Neighboring: `manual_organization_diagram_dock` `Dock`, `manual_organization_diagram_page`, `manual_organization_diagram_new_page`.
- Placeholder: none. Behavior: legend entry for widget-kind items (#508).

## Scoring protocol

Per unit and candidate, the adjudicator scores 0–2 on: Accuracy (meaning preserved) / Fluency / Terminology / UI style-concision / Context fit / Safety preservation / Resource correctness / **AI-register detection** (does the candidate correctly identify or avoid AI-register issues at the applicable granularity, without false positives from the counter-myth list). A 0 on Accuracy or Safety preservation is a hard failure for the candidate. Totals and per-axis sums are recorded; ties are broken by Safety + Accuracy sums, then by the adjudicator's written rationale.
