# Issue #161 Japanese UI copy LQA evidence

> Status: locally verified — rendered UI, repository, and build checks pass locally; the post-review fix head is awaiting the PR CI final-status.

Issue: [#161](https://github.com/nunu1733/NunuLauncher/issues/161)

## Scope and anchors

- Stage A was accepted by the repository owner. The accepted Stage A contract is at commit 48720a0a8bc965de10df70a24b48e9300eaf51ca.
- Baseline: 505dbc40e6154c05158b5d0271c45f6a885a411b (Lawnchair v15.0.0-beta3.0).
- Comparison: the current issue branch against the baseline, with the main branch Japanese text used as the pre-review comparison for changed copy.
- Required set: 223 translatable current Nunu resources — 166 in lawnchair/res and 57 in res.
- organizer_diagnostics_export_default_filename is excluded from the required copy set because the resource is explicitly translatable=false in the default resource.
- Scope is Japanese UI copy, review workflow, evidence, and a standard-library resource contract validator. No runtime behavior, API, dependency, navigation, or layout semantics were changed.

## Independent review identities

| Role | Identity | Provider | Session/evidence boundary |
| --- | --- | --- | --- |
| Reviewer A | Codex default (host model identifier not exposed in this session) | OpenAI/Codex | issue161-main-session-2026-08-28 |
| Reviewer B | gpt-5.6-sol (Sol-Low) | OpenAI/Codex | separate Codex task 01a0468f-1c1c-7a10-867b-f4eaed4fb41b and separate worktree/session |
| Owner/adjudicator | repository owner via main task | — | final acceptance after blinded bake-off and discrepancy resolution |

Reviewer A and Reviewer B independently inspected the same 223-unit required set. A different provider was not available in the configured environment; the independence boundary is the separate model/session/worktree. Reviewer identities were not exposed during the owner scoring phase.

## Review method

- Each unit is a resource plus its Android surface family and surrounding action/status meaning.
- Dispositions are OK, REVISE, PRODUCT_DECISION, and TECHNICAL_ONLY.
- REVISE required a before/after entry, placeholder/resource-semantic check, and acceptance decision. PRODUCT_DECISION would have stopped implementation; none remained.
- The full pass used independent A/B review. Low/medium disagreements were adjudicated against the style guide, glossary, and semantic anchor. No unresolved high-severity disagreement remains.
- Safety/recovery copy was checked for no-change, rollback, recovery, and retry distinctions. Placeholder order, format flags/date conversions, escaped percent, plural quantity selectors, duplicate resources, and empty values are checked by the validator.

## Family summary

| Surface family | Total | OK | REVISE | TECHNICAL_ONLY | PRODUCT_DECISION |
| --- | ---: | ---: | ---: | ---: | ---: |
| manual organization | 91 | 26 | 48 | 17 | 0 |
| onboarding | 5 | 3 | 2 | 0 | 0 |
| diagnostics | 6 | 0 | 0 | 6 | 0 |
| lock | 64 | 48 | 16 | 0 | 0 |
| category settings | 57 | 41 | 16 | 0 | 0 |
| Total | 223 | 118 | 82 | 23 | 0 |

## Full required-resource disposition

The following table is the complete 223-unit inventory. The `Final disposition` column is authoritative: every resource changed against `main` is `REVISE`; unchanged technical resources are `TECHNICAL_ONLY`; unchanged user-facing resources are `OK`. The filename resource excluded above is covered by the default-resource `translatable=false` contract and validator test. A/B disagreements on changed rows are explicitly recorded as owner resolutions in the `Resolution` column.

| Root | Resource | Surface | Reviewer A | Reviewer B | Resolution | Final disposition |
| --- | --- | --- | --- | --- | --- | --- |
| lawnchair | manual_organization_app_pair_constraint | manual organization | REVISE | OK | owner resolution: accepted copy-only revision; semantics preserved | REVISE |
| lawnchair | manual_organization_apply_concurrent | manual organization | OK | OK | A/B agreement; no resource change | OK |
| lawnchair | manual_organization_apply_recovered | manual organization | REVISE | OK | owner resolution: accepted copy-only revision; semantics preserved | REVISE |
| lawnchair | manual_organization_apply_recovery_failed | manual organization | REVISE | OK | owner resolution: accepted copy-only revision; semantics preserved | REVISE |
| lawnchair | manual_organization_apply_rejected | manual organization | OK | OK | A/B agreement; no resource change | OK |
| lawnchair | manual_organization_apply_rolled_back | manual organization | REVISE | REVISE | A/B agreement; accepted revision; semantics preserved | REVISE |
| lawnchair | manual_organization_apply_success | manual organization | OK | OK | A/B agreement; no resource change | OK |
| lawnchair | manual_organization_apply_unresolved | manual organization | REVISE | OK | owner resolution: accepted copy-only revision; semantics preserved | REVISE |
| lawnchair | manual_organization_applying | manual organization | REVISE | OK | owner resolution: accepted copy-only revision; semantics preserved | REVISE |
| lawnchair | manual_organization_available_constraint | manual organization | OK | OK | A/B agreement; no resource change | OK |
| lawnchair | manual_organization_cancel | manual organization | OK | OK | A/B agreement; no resource change | OK |
| lawnchair | manual_organization_cancel_before_checkpoint | manual organization | OK | OK | A/B agreement; no resource change | OK |
| lawnchair | manual_organization_capturing | manual organization | OK | OK | A/B agreement; no resource change | OK |
| lawnchair | manual_organization_confirm | manual organization | REVISE | OK | owner resolution: accepted copy-only revision; semantics preserved | REVISE |
| lawnchair | manual_organization_device_scope | manual organization | REVISE | OK | owner resolution: accepted copy-only revision; semantics preserved | REVISE |
| lawnchair | manual_organization_disabled_constraint | manual organization | REVISE | OK | owner resolution: accepted copy-only revision; semantics preserved | REVISE |
| lawnchair | manual_organization_empty_folder_constraint | manual organization | OK | OK | A/B agreement; no resource change | OK |
| lawnchair | manual_organization_explainer | manual organization | REVISE | OK | owner resolution: accepted copy-only revision; semantics preserved | REVISE |
| lawnchair | manual_organization_impossible | manual organization | REVISE | OK | owner resolution: accepted copy-only revision; semantics preserved | REVISE |
| lawnchair | manual_organization_input_unavailable | manual organization | REVISE | OK | owner resolution: accepted copy-only revision; semantics preserved | REVISE |
| lawnchair | manual_organization_legacy_shortcut_constraint | manual organization | REVISE | REVISE | A/B agreement; accepted revision; semantics preserved | REVISE |
| lawnchair | manual_organization_locked_constraint | manual organization | REVISE | OK | owner resolution: accepted copy-only revision; semantics preserved | REVISE |
| lawnchair | manual_organization_moved_count | manual organization | REVISE | OK | owner resolution: accepted copy-only revision; semantics preserved | REVISE |
| lawnchair | manual_organization_moved_folder_member | manual organization | REVISE | OK | owner resolution: accepted copy-only revision; semantics preserved | REVISE |
| lawnchair | manual_organization_moved_folder_unit | manual organization | OK | OK | A/B agreement; no resource change | OK |
| lawnchair | manual_organization_moved_single_placement | manual organization | REVISE | OK | owner resolution: accepted copy-only revision; semantics preserved | REVISE |
| lawnchair | manual_organization_new_folders_count | manual organization | REVISE | OK | owner resolution: accepted copy-only revision; semantics preserved | REVISE |
| lawnchair | manual_organization_new_pages_count | manual organization | REVISE | OK | owner resolution: accepted copy-only revision; semantics preserved | REVISE |
| lawnchair | manual_organization_no_changes | manual organization | OK | OK | A/B agreement; no resource change | OK |
| lawnchair | manual_organization_open_diagnostics | manual organization | TECHNICAL_ONLY | TECHNICAL_ONLY | A/B agreement; technical precision retained | TECHNICAL_ONLY |
| lawnchair | manual_organization_open_diagnostics_summary | manual organization | OK | OK | A/B agreement; no resource change | OK |
| lawnchair | manual_organization_planning | manual organization | REVISE | OK | owner resolution: accepted copy-only revision; semantics preserved | REVISE |
| lawnchair | manual_organization_preserved_already_canonical | manual organization | REVISE | OK | owner resolution: accepted copy-only revision; semantics preserved | REVISE |
| lawnchair | manual_organization_preserved_app_pair | manual organization | OK | OK | A/B agreement; no resource change | OK |
| lawnchair | manual_organization_preserved_count | manual organization | REVISE | OK | owner resolution: accepted copy-only revision; semantics preserved | REVISE |
| lawnchair | manual_organization_preserved_dock | manual organization | OK | OK | A/B agreement; no resource change | OK |
| lawnchair | manual_organization_preserved_legacy_shortcut | manual organization | REVISE | REVISE | A/B agreement; accepted revision; semantics preserved | REVISE |
| lawnchair | manual_organization_preserved_locked | manual organization | OK | OK | A/B agreement; no resource change | OK |
| lawnchair | manual_organization_preserved_non_target | manual organization | OK | OK | A/B agreement; no resource change | OK |
| lawnchair | manual_organization_preserved_structural | manual organization | REVISE | OK | owner resolution: accepted copy-only revision; semantics preserved | REVISE |
| lawnchair | manual_organization_preserved_unavailable | manual organization | REVISE | OK | owner resolution: accepted copy-only revision; semantics preserved | REVISE |
| lawnchair | manual_organization_preserved_widget | manual organization | OK | OK | A/B agreement; no resource change | OK |
| lawnchair | manual_organization_preview | manual organization | REVISE | OK | owner resolution: accepted copy-only revision; semantics preserved | REVISE |
| lawnchair | manual_organization_private_space_constraint | manual organization | OK | OK | A/B agreement; no resource change | OK |
| lawnchair | manual_organization_quiet_constraint | manual organization | REVISE | OK | owner resolution: accepted copy-only revision; semantics preserved | REVISE |
| lawnchair | manual_organization_recapture | manual organization | REVISE | OK | owner resolution: accepted copy-only revision; semantics preserved | REVISE |
| lawnchair | manual_organization_recovering | manual organization | OK | OK | A/B agreement; no resource change | OK |
| lawnchair | manual_organization_recovery | manual organization | REVISE | OK | owner resolution: accepted copy-only revision; semantics preserved | REVISE |
| lawnchair | manual_organization_recovery_confirm | manual organization | REVISE | OK | owner resolution: accepted copy-only revision; semantics preserved | REVISE |
| lawnchair | manual_organization_recovery_failed | manual organization | REVISE | OK | owner resolution: accepted copy-only revision; semantics preserved | REVISE |
| lawnchair | manual_organization_recovery_inspecting | manual organization | OK | OK | A/B agreement; no resource change | OK |
| lawnchair | manual_organization_recovery_not_available | manual organization | OK | REVISE | B proposal retained; no resource change | OK |
| lawnchair | manual_organization_recovery_preview | manual organization | REVISE | OK | owner resolution: accepted copy-only revision; semantics preserved | REVISE |
| lawnchair | manual_organization_recovery_restored | manual organization | OK | OK | A/B agreement; no resource change | OK |
| lawnchair | manual_organization_rejected | manual organization | OK | OK | A/B agreement; no resource change | OK |
| lawnchair | manual_organization_rejection_additions | manual organization | TECHNICAL_ONLY | TECHNICAL_ONLY | A/B agreement; technical precision retained | TECHNICAL_ONLY |
| lawnchair | manual_organization_rejection_bounds | manual organization | TECHNICAL_ONLY | TECHNICAL_ONLY | A/B agreement; technical precision retained | TECHNICAL_ONLY |
| lawnchair | manual_organization_rejection_dangling_reference | manual organization | TECHNICAL_ONLY | TECHNICAL_ONLY | A/B agreement; technical precision retained | TECHNICAL_ONLY |
| lawnchair | manual_organization_rejection_duplicate_item | manual organization | TECHNICAL_ONLY | TECHNICAL_ONLY | A/B agreement; technical precision retained | TECHNICAL_ONLY |
| lawnchair | manual_organization_rejection_duplicate_page | manual organization | TECHNICAL_ONLY | TECHNICAL_ONLY | A/B agreement; technical precision retained | TECHNICAL_ONLY |
| lawnchair | manual_organization_rejection_duplicate_target | manual organization | TECHNICAL_ONLY | REVISE | owner resolution: accepted copy-only revision; semantics preserved | REVISE |
| lawnchair | manual_organization_rejection_incomplete_target_partition | manual organization | TECHNICAL_ONLY | TECHNICAL_ONLY | A/B agreement; technical precision retained | TECHNICAL_ONLY |
| lawnchair | manual_organization_rejection_invalid_container | manual organization | TECHNICAL_ONLY | TECHNICAL_ONLY | A/B agreement; technical precision retained | TECHNICAL_ONLY |
| lawnchair | manual_organization_rejection_invalid_dimensions | manual organization | TECHNICAL_ONLY | TECHNICAL_ONLY | A/B agreement; technical precision retained | TECHNICAL_ONLY |
| lawnchair | manual_organization_rejection_invalid_rules | manual organization | TECHNICAL_ONLY | TECHNICAL_ONLY | A/B agreement; technical precision retained | TECHNICAL_ONLY |
| lawnchair | manual_organization_rejection_kind_target_mismatch | manual organization | TECHNICAL_ONLY | REVISE | owner resolution: accepted copy-only revision; semantics preserved | REVISE |
| lawnchair | manual_organization_rejection_locked_out_of_bounds | manual organization | TECHNICAL_ONLY | TECHNICAL_ONLY | A/B agreement; technical precision retained | TECHNICAL_ONLY |
| lawnchair | manual_organization_rejection_malformed_app_pair | manual organization | TECHNICAL_ONLY | TECHNICAL_ONLY | A/B agreement; technical precision retained | TECHNICAL_ONLY |
| lawnchair | manual_organization_rejection_missing_target | manual organization | TECHNICAL_ONLY | REVISE | owner resolution: accepted copy-only revision; semantics preserved | REVISE |
| lawnchair | manual_organization_rejection_overlap | manual organization | TECHNICAL_ONLY | TECHNICAL_ONLY | A/B agreement; technical precision retained | TECHNICAL_ONLY |
| lawnchair | manual_organization_rejection_target_profile_mismatch | manual organization | TECHNICAL_ONLY | REVISE | owner resolution: accepted copy-only revision; semantics preserved | REVISE |
| lawnchair | manual_organization_rejection_unknown_category | manual organization | TECHNICAL_ONLY | TECHNICAL_ONLY | A/B agreement; technical precision retained | TECHNICAL_ONLY |
| lawnchair | manual_organization_rejection_unknown_item_kind | manual organization | TECHNICAL_ONLY | TECHNICAL_ONLY | A/B agreement; technical precision retained | TECHNICAL_ONLY |
| lawnchair | manual_organization_rejection_unknown_page | manual organization | TECHNICAL_ONLY | TECHNICAL_ONLY | A/B agreement; technical precision retained | TECHNICAL_ONLY |
| lawnchair | manual_organization_rejection_unknown_signal_item | manual organization | TECHNICAL_ONLY | TECHNICAL_ONLY | A/B agreement; technical precision retained | TECHNICAL_ONLY |
| lawnchair | manual_organization_retry | manual organization | OK | OK | A/B agreement; no resource change | OK |
| lawnchair | manual_organization_safe_terminal | manual organization | OK | OK | A/B agreement; no resource change | OK |
| lawnchair | manual_organization_scope | manual organization | REVISE | OK | owner resolution: accepted copy-only revision; semantics preserved | REVISE |
| lawnchair | manual_organization_stale | manual organization | REVISE | REVISE | A/B agreement; accepted revision; semantics preserved | REVISE |
| lawnchair | manual_organization_start | manual organization | REVISE | OK | owner resolution: accepted copy-only revision; semantics preserved | REVISE |
| lawnchair | manual_organization_start_again | manual organization | REVISE | OK | owner resolution: accepted copy-only revision; semantics preserved | REVISE |
| lawnchair | manual_organization_summary | manual organization | REVISE | OK | owner resolution: accepted copy-only revision; semantics preserved | REVISE |
| lawnchair | manual_organization_title | manual organization | OK | OK | A/B agreement; no resource change | OK |
| lawnchair | manual_organization_unavailable_constraint | manual organization | REVISE | REVISE | A/B agreement; accepted revision; semantics preserved | REVISE |
| lawnchair | manual_organization_unavailable_item_constraint | manual organization | OK | OK | A/B agreement; no resource change | OK |
| lawnchair | manual_organization_unplaced_grid | manual organization | REVISE | OK | owner resolution: accepted copy-only revision; semantics preserved | REVISE |
| lawnchair | manual_organization_unplaced_target | manual organization | REVISE | REVISE | A/B agreement; accepted revision; semantics preserved | REVISE |
| lawnchair | manual_organization_warning_fallback_category | manual organization | REVISE | OK | owner resolution: accepted copy-only revision; semantics preserved | REVISE |
| lawnchair | manual_organization_warning_legacy_shortcut | manual organization | REVISE | REVISE | A/B agreement; accepted revision; semantics preserved | REVISE |
| lawnchair | manual_organization_warning_unavailable | manual organization | REVISE | OK | owner resolution: accepted copy-only revision; semantics preserved | REVISE |
| lawnchair | manual_organization_widget_constraint | manual organization | REVISE | OK | owner resolution: accepted copy-only revision; semantics preserved | REVISE |
| lawnchair | organization_onboarding_proposal_defer | onboarding | OK | OK | A/B agreement; no resource change | OK |
| lawnchair | organization_onboarding_proposal_review | onboarding | REVISE | OK | owner resolution: accepted copy-only revision; semantics preserved | REVISE |
| lawnchair | organization_onboarding_proposal_skip | onboarding | OK | OK | A/B agreement; no resource change | OK |
| lawnchair | organization_onboarding_proposal_summary | onboarding | REVISE | OK | owner resolution: accepted copy-only revision; semantics preserved | REVISE |
| lawnchair | organization_onboarding_proposal_title | onboarding | OK | OK | A/B agreement; no resource change | OK |
| lawnchair | organizer_diagnostics_description | diagnostics | TECHNICAL_ONLY | TECHNICAL_ONLY | A/B agreement; technical precision retained | TECHNICAL_ONLY |
| lawnchair | organizer_diagnostics_export_error | diagnostics | TECHNICAL_ONLY | OK | owner resolution: retained technical wording; no user-facing copy change | TECHNICAL_ONLY |
| lawnchair | organizer_diagnostics_export_label | diagnostics | TECHNICAL_ONLY | TECHNICAL_ONLY | A/B agreement; technical precision retained | TECHNICAL_ONLY |
| lawnchair | organizer_diagnostics_export_subtitle | diagnostics | TECHNICAL_ONLY | TECHNICAL_ONLY | A/B agreement; technical precision retained | TECHNICAL_ONLY |
| lawnchair | organizer_diagnostics_export_success | diagnostics | TECHNICAL_ONLY | OK | owner resolution: retained technical wording; no user-facing copy change | TECHNICAL_ONLY |
| lawnchair | organizer_diagnostics_title | diagnostics | TECHNICAL_ONLY | TECHNICAL_ONLY | A/B agreement; technical precision retained | TECHNICAL_ONLY |
| lawnchair | organizer_lock_action_keep_locked | lock | REVISE | OK | owner resolution: accepted copy-only revision; semantics preserved | REVISE |
| lawnchair | organizer_lock_action_lock | lock | OK | OK | A/B agreement; no resource change | OK |
| lawnchair | organizer_lock_action_mark_unlocked | lock | REVISE | OK | owner resolution: accepted copy-only revision; semantics preserved | REVISE |
| lawnchair | organizer_lock_action_unlock | lock | OK | OK | A/B agreement; no resource change | OK |
| lawnchair | organizer_lock_dialog_current_state | lock | OK | OK | A/B agreement; no resource change | OK |
| lawnchair | organizer_lock_dialog_review_intro | lock | OK | OK | A/B agreement; no resource change | OK |
| lawnchair | organizer_lock_dialog_title_lock | lock | OK | OK | A/B agreement; no resource change | OK |
| lawnchair | organizer_lock_dialog_title_review | lock | REVISE | OK | owner resolution: accepted copy-only revision; semantics preserved | REVISE |
| lawnchair | organizer_lock_dialog_title_unlock | lock | OK | OK | A/B agreement; no resource change | OK |
| lawnchair | organizer_lock_effect_app_pair_member_own_lock_binds | lock | OK | OK | A/B agreement; no resource change | OK |
| lawnchair | organizer_lock_effect_app_pair_member_scope | lock | OK | OK | A/B agreement; no resource change | OK |
| lawnchair | organizer_lock_effect_app_pair_member_unlock_ineffective | lock | OK | OK | A/B agreement; no resource change | OK |
| lawnchair | organizer_lock_effect_app_pair_parent_covers_members | lock | OK | OK | A/B agreement; no resource change | OK |
| lawnchair | organizer_lock_effect_app_pair_scope | lock | OK | OK | A/B agreement; no resource change | OK |
| lawnchair | organizer_lock_effect_dock_slot | lock | OK | OK | A/B agreement; no resource change | OK |
| lawnchair | organizer_lock_effect_folder_child_own_lock_binds | lock | OK | OK | A/B agreement; no resource change | OK |
| lawnchair | organizer_lock_effect_folder_child_scope | lock | OK | OK | A/B agreement; no resource change | OK |
| lawnchair | organizer_lock_effect_folder_child_unlock_ineffective | lock | OK | OK | A/B agreement; no resource change | OK |
| lawnchair | organizer_lock_effect_folder_children_own_lock_remains | lock | OK | OK | A/B agreement; no resource change | OK |
| lawnchair | organizer_lock_effect_folder_parent_covers_children | lock | OK | OK | A/B agreement; no resource change | OK |
| lawnchair | organizer_lock_effect_folder_scope | lock | OK | OK | A/B agreement; no resource change | OK |
| lawnchair | organizer_lock_effect_own_placement | lock | OK | OK | A/B agreement; no resource change | OK |
| lawnchair | organizer_lock_effect_widget_region | lock | OK | OK | A/B agreement; no resource change | OK |
| lawnchair | organizer_lock_error_busy | lock | REVISE | OK | owner resolution: accepted copy-only revision; semantics preserved | REVISE |
| lawnchair | organizer_lock_error_failed | lock | REVISE | OK | owner resolution: accepted copy-only revision; semantics preserved | REVISE |
| lawnchair | organizer_lock_error_intent_required | lock | REVISE | REVISE | A/B agreement; accepted revision; semantics preserved | REVISE |
| lawnchair | organizer_lock_error_item_not_found | lock | REVISE | OK | owner resolution: accepted copy-only revision; semantics preserved | REVISE |
| lawnchair | organizer_lock_error_item_not_unknown | lock | REVISE | OK | owner resolution: accepted copy-only revision; semantics preserved | REVISE |
| lawnchair | organizer_lock_error_profile_unavailable | lock | REVISE | OK | owner resolution: accepted copy-only revision; semantics preserved | REVISE |
| lawnchair | organizer_lock_error_stale | lock | REVISE | OK | owner resolution: accepted copy-only revision; semantics preserved | REVISE |
| lawnchair | organizer_lock_error_unsupported | lock | OK | OK | A/B agreement; no resource change | OK |
| lawnchair | organizer_lock_kind_app | lock | OK | OK | A/B agreement; no resource change | OK |
| lawnchair | organizer_lock_kind_app_pair | lock | OK | OK | A/B agreement; no resource change | OK |
| lawnchair | organizer_lock_kind_folder | lock | OK | OK | A/B agreement; no resource change | OK |
| lawnchair | organizer_lock_kind_shortcut | lock | OK | OK | A/B agreement; no resource change | OK |
| lawnchair | organizer_lock_kind_unknown | lock | OK | OK | A/B agreement; no resource change | OK |
| lawnchair | organizer_lock_kind_widget | lock | OK | OK | A/B agreement; no resource change | OK |
| lawnchair | organizer_lock_menu_title | lock | OK | OK | A/B agreement; no resource change | OK |
| lawnchair | organizer_lock_result_locked | lock | OK | OK | A/B agreement; no resource change | OK |
| lawnchair | organizer_lock_result_no_change | lock | OK | OK | A/B agreement; no resource change | OK |
| lawnchair | organizer_lock_result_unlocked | lock | OK | OK | A/B agreement; no resource change | OK |
| lawnchair | organizer_lock_screen_effectively_locked | lock | REVISE | REVISE | A/B agreement; accepted revision; semantics preserved | REVISE |
| lawnchair | organizer_lock_screen_item_state_description | lock | OK | OK | A/B agreement; no resource change | OK |
| lawnchair | organizer_lock_screen_known_heading | lock | OK | OK | A/B agreement; no resource change | OK |
| lawnchair | organizer_lock_screen_placement_app_pair | lock | OK | OK | A/B agreement; no resource change | OK |
| lawnchair | organizer_lock_screen_placement_desktop | lock | OK | OK | A/B agreement; no resource change | OK |
| lawnchair | organizer_lock_screen_placement_dock | lock | OK | OK | A/B agreement; no resource change | OK |
| lawnchair | organizer_lock_screen_placement_folder | lock | OK | OK | A/B agreement; no resource change | OK |
| lawnchair | organizer_lock_screen_placement_summary_double | lock | OK | OK | A/B agreement; no resource change | OK |
| lawnchair | organizer_lock_screen_placement_summary_triple | lock | OK | OK | A/B agreement; no resource change | OK |
| lawnchair | organizer_lock_screen_profile_cloned | lock | OK | OK | A/B agreement; no resource change | OK |
| lawnchair | organizer_lock_screen_profile_other | lock | OK | OK | A/B agreement; no resource change | OK |
| lawnchair | organizer_lock_screen_profile_private | lock | OK | OK | A/B agreement; no resource change | OK |
| lawnchair | organizer_lock_screen_profile_work | lock | OK | OK | A/B agreement; no resource change | OK |
| lawnchair | organizer_lock_screen_review_all_confirm | lock | REVISE | OK | owner resolution: accepted copy-only revision; semantics preserved | REVISE |
| lawnchair | organizer_lock_screen_review_all_keep_locked | lock | REVISE | OK | owner resolution: accepted copy-only revision; semantics preserved | REVISE |
| lawnchair | organizer_lock_screen_review_all_mark_unlocked | lock | REVISE | OK | owner resolution: accepted copy-only revision; semantics preserved | REVISE |
| lawnchair | organizer_lock_screen_summary | lock | OK | OK | A/B agreement; no resource change | OK |
| lawnchair | organizer_lock_screen_title | lock | OK | OK | A/B agreement; no resource change | OK |
| lawnchair | organizer_lock_screen_unknown_banner | lock | REVISE | OK | owner resolution: accepted copy-only revision; semantics preserved | REVISE |
| lawnchair | organizer_lock_screen_unknown_banner_none | lock | OK | OK | A/B agreement; no resource change | OK |
| lawnchair | organizer_lock_state_locked | lock | OK | OK | A/B agreement; no resource change | OK |
| lawnchair | organizer_lock_state_unknown | lock | OK | OK | A/B agreement; no resource change | OK |
| lawnchair | organizer_lock_state_unlocked | lock | OK | OK | owner follow-up re-review: state/action distinction accepted; semantics preserved | REVISE |
| root | organizer_category_art | category settings | OK | OK | A/B agreement; no resource change | OK |
| root | organizer_category_auto | category settings | OK | OK | A/B agreement; no resource change | OK |
| root | organizer_category_beauty | category settings | OK | OK | A/B agreement; no resource change | OK |
| root | organizer_category_books | category settings | OK | OK | A/B agreement; no resource change | OK |
| root | organizer_category_business | category settings | OK | OK | A/B agreement; no resource change | OK |
| root | organizer_category_comics | category settings | OK | OK | A/B agreement; no resource change | OK |
| root | organizer_category_communication | category settings | OK | OK | A/B agreement; no resource change | OK |
| root | organizer_category_dating | category settings | OK | OK | A/B agreement; no resource change | OK |
| root | organizer_category_education | category settings | OK | OK | A/B agreement; no resource change | OK |
| root | organizer_category_entertainment | category settings | OK | OK | A/B agreement; no resource change | OK |
| root | organizer_category_events | category settings | OK | OK | A/B agreement; no resource change | OK |
| root | organizer_category_finance | category settings | OK | OK | A/B agreement; no resource change | OK |
| root | organizer_category_food | category settings | OK | OK | A/B agreement; no resource change | OK |
| root | organizer_category_game | category settings | OK | OK | A/B agreement; no resource change | OK |
| root | organizer_category_health | category settings | OK | OK | A/B agreement; no resource change | OK |
| root | organizer_category_house | category settings | OK | OK | A/B agreement; no resource change | OK |
| root | organizer_category_libraries | category settings | OK | OK | A/B agreement; no resource change | OK |
| root | organizer_category_lifestyle | category settings | OK | OK | A/B agreement; no resource change | OK |
| root | organizer_category_maps | category settings | OK | OK | A/B agreement; no resource change | OK |
| root | organizer_category_medical | category settings | OK | OK | A/B agreement; no resource change | OK |
| root | organizer_category_music | category settings | OK | OK | A/B agreement; no resource change | OK |
| root | organizer_category_news | category settings | OK | OK | A/B agreement; no resource change | OK |
| root | organizer_category_other | category settings | OK | OK | A/B agreement; no resource change | OK |
| root | organizer_category_override_app_description | category settings | OK | OK | A/B agreement; no resource change | OK |
| root | organizer_category_override_app_description_with_profile | category settings | OK | OK | A/B agreement; no resource change | OK |
| root | organizer_category_override_app_status | category settings | OK | OK | A/B agreement; no resource change | OK |
| root | organizer_category_override_automatic | category settings | REVISE | OK | owner resolution: accepted copy-only revision; semantics preserved | REVISE |
| root | organizer_category_override_automatic_description | category settings | REVISE | OK | owner resolution: accepted copy-only revision; semantics preserved | REVISE |
| root | organizer_category_override_busy | category settings | REVISE | OK | owner resolution: accepted copy-only revision; semantics preserved | REVISE |
| root | organizer_category_override_cancel | category settings | OK | OK | A/B agreement; no resource change | OK |
| root | organizer_category_override_category_description | category settings | REVISE | OK | owner resolution: accepted copy-only revision; semantics preserved | REVISE |
| root | organizer_category_override_choose | category settings | OK | OK | A/B agreement; no resource change | OK |
| root | organizer_category_override_conflict | category settings | REVISE | OK | owner resolution: accepted copy-only revision; semantics preserved | REVISE |
| root | organizer_category_override_explicit | category settings | REVISE | OK | owner resolution: accepted copy-only revision; semantics preserved | REVISE |
| root | organizer_category_override_failed | category settings | REVISE | OK | owner resolution: accepted copy-only revision; semantics preserved | REVISE |
| root | organizer_category_override_no_change | category settings | REVISE | OK | owner resolution: accepted copy-only revision; semantics preserved | REVISE |
| root | organizer_category_override_profile_personal | category settings | OK | OK | A/B agreement; no resource change | OK |
| root | organizer_category_override_profile_work | category settings | REVISE | OK | owner resolution: accepted copy-only revision; semantics preserved | REVISE |
| root | organizer_category_override_save | category settings | REVISE | OK | owner resolution: accepted copy-only revision; semantics preserved | REVISE |
| root | organizer_category_override_saved | category settings | REVISE | OK | owner resolution: accepted copy-only revision; semantics preserved | REVISE |
| root | organizer_category_override_unavailable | category settings | REVISE | OK | owner resolution: accepted copy-only revision; semantics preserved | REVISE |
| root | organizer_category_override_unavailable_store | category settings | REVISE | OK | owner resolution: accepted copy-only revision; semantics preserved | REVISE |
| root | organizer_category_override_unnamed_app | category settings | OK | OK | A/B agreement; no resource change | OK |
| root | organizer_category_override_use_automatic | category settings | REVISE | OK | owner resolution: accepted copy-only revision; semantics preserved | REVISE |
| root | organizer_category_overrides_summary | category settings | REVISE | OK | owner resolution: accepted copy-only revision; semantics preserved | REVISE |
| root | organizer_category_overrides_title | category settings | REVISE | OK | owner resolution: accepted copy-only revision; semantics preserved | REVISE |
| root | organizer_category_parenting | category settings | OK | OK | A/B agreement; no resource change | OK |
| root | organizer_category_personalization | category settings | OK | OK | A/B agreement; no resource change | OK |
| root | organizer_category_photography | category settings | OK | OK | A/B agreement; no resource change | OK |
| root | organizer_category_productivity | category settings | OK | OK | A/B agreement; no resource change | OK |
| root | organizer_category_shopping | category settings | OK | OK | A/B agreement; no resource change | OK |
| root | organizer_category_social | category settings | OK | OK | A/B agreement; no resource change | OK |
| root | organizer_category_sports | category settings | OK | OK | A/B agreement; no resource change | OK |
| root | organizer_category_tools | category settings | OK | OK | A/B agreement; no resource change | OK |
| root | organizer_category_travel | category settings | OK | OK | A/B agreement; no resource change | OK |
| root | organizer_category_video | category settings | OK | OK | A/B agreement; no resource change | OK |
| root | organizer_category_weather | category settings | OK | OK | A/B agreement; no resource change | OK |

Reconciliation: 82 resources differ from `main` and therefore have final disposition `REVISE`. Nine were changed with initial A/B agreement, 72 changed rows with an initial A/B disagreement have an explicit owner resolution above, and `organizer_lock_state_unlocked` was reopened after review as a state/action distinction and accepted as `REVISE`. The remaining 23 unchanged technical rows are `TECHNICAL_ONLY`; the remaining 118 unchanged user-facing rows are `OK`. This final-disposition reconciliation, rather than the initial reviewer disposition alone, is the source for the family summary.

## Accepted revision register

| Root | Resource | Before (main) | After (issue branch) | Context |
| --- | --- | --- | --- | --- |
| lawnchair | manual_organization_app_pair_constraint | アプリペア制約を保持: %1$d件 | アプリペアを保持: %1$d件 | manual organization |
| lawnchair | manual_organization_apply_recovered | 実行した整理を完了できませんでした。以前のレイアウトを復元しました。 | 整理を完了できませんでした。以前のレイアウトを復元しました。 | manual organization |
| lawnchair | manual_organization_apply_recovery_failed | 自動復旧を検証できませんでした。今は他の整理変更を行わないでください。 | 自動復旧を確認できませんでした。今は別の整理を開始しないでください。 | manual organization |
| lawnchair | manual_organization_apply_rolled_back | 実行した整理はロールバックされました。以前のレイアウトのままです。 | 整理を取り消しました。以前のレイアウトのままです。 | manual organization |
| lawnchair | manual_organization_apply_unresolved | 現在のレイアウトを検証できませんでした。今は他の整理変更を行わないでください。 | 現在のレイアウトを確認できませんでした。今は別の整理を開始しないでください。 | manual organization |
| lawnchair | manual_organization_applying | 確認した整理を適用しています。このステップは安全に中断できません。 | 確認した整理案を適用しています。この処理は安全に中断できません。 | manual organization |
| lawnchair | manual_organization_confirm | 確認した整理を適用 | 確認した整理案を適用 | manual organization |
| lawnchair | manual_organization_device_scope | デバイスプロファイル: %1$d列 × %2$d行、Dockスロット%3$d個。 | 画面レイアウト: %1$d列 × %2$d行、Dock %3$d枠。 | manual organization |
| lawnchair | manual_organization_disabled_constraint | 無効化されたプロファイルの配置: %1$d件 | 利用できないプロファイルの配置: %1$d件 | manual organization |
| lawnchair | manual_organization_explainer | 適用前に変更内容を確認できます。現在のレイアウトが自動的に変わることはありません。 | 適用前に整理案を確認できます。現在のレイアウトは自動的に変更されません。 | manual organization |
| lawnchair | manual_organization_impossible | 利用可能なスペースでは、このレイアウトを整理できません。何も変更されていません。 | 現在の空きスペースでは、このレイアウトを整理できません。何も変更されていません。 | manual organization |
| lawnchair | manual_organization_input_unavailable | 現在のレイアウトまたは必要な整理情報を利用できません。何も変更されていません。 | 現在のレイアウトまたは必要な情報を利用できません。何も変更されていません。 | manual organization |
| lawnchair | manual_organization_legacy_shortcut_constraint | レガシーショートカット制約を保持: %1$d件 | 旧形式のショートカットを保持: %1$d件 | manual organization |
| lawnchair | manual_organization_locked_constraint | ロック制約により保持: %1$d件 | ロックした配置を保持: %1$d件 | manual organization |
| lawnchair | manual_organization_moved_count | %1$d件の配置が移動されます | 移動する配置: %1$d件 | manual organization |
| lawnchair | manual_organization_moved_folder_member | フォルダメンバーとして移動: %1$d件 | フォルダ内の項目として移動: %1$d件 | manual organization |
| lawnchair | manual_organization_moved_single_placement | 単一配置として移動: %1$d件 | 単独で移動: %1$d件 | manual organization |
| lawnchair | manual_organization_new_folders_count | 新しいフォルダが%1$d個作成されます | 新しいフォルダ: %1$d個 | manual organization |
| lawnchair | manual_organization_new_pages_count | 新しいホーム画面ページが%1$d枚作成されます | 新しいホーム画面ページ: %1$d枚 | manual organization |
| lawnchair | manual_organization_planning | 安全な整理案を作成しています… | 安全に整理できる案を作成しています… | manual organization |
| lawnchair | manual_organization_preserved_already_canonical | 既に正規化済みの配置: %1$d件 | 変更不要の配置: %1$d件 | manual organization |
| lawnchair | manual_organization_preserved_count | %1$d件の配置が保持されます | 保持する配置: %1$d件 | manual organization |
| lawnchair | manual_organization_preserved_legacy_shortcut | レガシーショートカットを保持: %1$d件 | 旧形式のショートカットを保持: %1$d件 | manual organization |
| lawnchair | manual_organization_preserved_structural | 構造メンバーを保持: %1$d件 | 関連する項目を保持: %1$d件 | manual organization |
| lawnchair | manual_organization_preserved_unavailable | 利用不可の配置を保持: %1$d件 | 利用できない配置を保持: %1$d件 | manual organization |
| lawnchair | manual_organization_preview | 提案された整理を確認 | 整理案を確認 | manual organization |
| lawnchair | manual_organization_quiet_constraint | クワイエットモードのプロファイル配置: %1$d件 | 一時停止中のプロファイルの配置: %1$d件 | manual organization |
| lawnchair | manual_organization_recapture | 読み込んで再度確認 | もう一度読み込んで確認 | manual organization |
| lawnchair | manual_organization_recovery | 以前のレイアウトを復元 | 以前のレイアウトに戻す | manual organization |
| lawnchair | manual_organization_recovery_confirm | 保存されたレイアウトを復元 | 保存したレイアウトに戻す | manual organization |
| lawnchair | manual_organization_recovery_failed | 保存されたレイアウトを安全に復元できませんでした。 | 保存したレイアウトに安全に戻せませんでした。 | manual organization |
| lawnchair | manual_organization_recovery_preview | 保存されたレイアウトの復元には確認が必要です。 | 保存したレイアウトに戻すには確認が必要です。 | manual organization |
| lawnchair | manual_organization_rejection_duplicate_target | 重複する配置先: %1$d件 | 重複する整理対象: %1$d件 | manual organization |
| lawnchair | manual_organization_rejection_kind_target_mismatch | アイテムと配置先の不整合: %1$d件 | アイテム種別と対象情報の不整合: %1$d件 | manual organization |
| lawnchair | manual_organization_rejection_missing_target | 欠落している配置先: %1$d件 | 見つからない整理対象: %1$d件 | manual organization |
| lawnchair | manual_organization_rejection_target_profile_mismatch | 配置先プロファイルの不一致: %1$d件 | アイテムと対象情報のプロファイル不一致: %1$d件 | manual organization |
| lawnchair | manual_organization_scope | 対象範囲: %1$d件の対象（%2$d個のプロファイル、%3$dページ）。 | 整理対象: %1$d件（%2$d個のプロファイル、%3$dページ）。 | manual organization |
| lawnchair | manual_organization_stale | ホームレイアウトが変更されました。適用前に新しい提案を確認してください。 | ホームレイアウトが変更されました。適用前に新しい整理案を確認してください。 | manual organization |
| lawnchair | manual_organization_start | 整理を確認 | 整理案を確認 | manual organization |
| lawnchair | manual_organization_start_again | 新しく確認を開始 | 新しい整理案を確認 | manual organization |
| lawnchair | manual_organization_summary | 適用前に、提案されたホームレイアウトを確認できます。 | 適用前に、ホームレイアウトの整理案を確認できます。 | manual organization |
| lawnchair | manual_organization_unavailable_constraint | 利用不可・プロファイル制約のある配置: %1$d件 | 利用不可またはプロファイルの制限がある配置: %1$d件 | manual organization |
| lawnchair | manual_organization_unplaced_grid | グリッド寸法を超えるため未配置: %1$d件 | 画面の範囲を超えるため未配置: %1$d件 | manual organization |
| lawnchair | manual_organization_unplaced_target | 配置先が利用できないため未配置: %1$d件 | 対象を利用できないため未配置: %1$d件 | manual organization |
| lawnchair | manual_organization_warning_fallback_category | フォールバックカテゴリを使用: %1$d件 | 代替カテゴリを使用: %1$d件 | manual organization |
| lawnchair | manual_organization_warning_legacy_shortcut | 確認が必要なレガシーショートカット: %1$d件 | 旧形式のショートカットの確認が必要: %1$d件 | manual organization |
| lawnchair | manual_organization_warning_unavailable | 利用不可の配置を保持しました: %1$d件 | 利用できない配置を保持: %1$d件 | manual organization |
| lawnchair | manual_organization_widget_constraint | ウィジェット制約を保持: %1$d件 | ウィジェットを保持: %1$d件 | manual organization |
| lawnchair | organization_onboarding_proposal_review | 整理を確認 | 整理案を確認 | onboarding |
| lawnchair | organization_onboarding_proposal_summary | まず提案された整理を確認できます。プレビューを確定するまで、何も変更されません。 | まず整理案を確認できます。プレビューを確定するまで、何も変更されません。 | onboarding |
| lawnchair | organizer_lock_action_keep_locked | ロックを維持 | ロックしたままにする | lock |
| lawnchair | organizer_lock_action_mark_unlocked | ロック解除として記録 | ロック解除に設定 | lock |
| lawnchair | organizer_lock_dialog_title_review | この配置を確認してください | この配置を確認 | lock |
| lawnchair | organizer_lock_error_busy | 別のレイアウト変更が進行中です。しばらくしてからもう一度お試しください。 | 別のレイアウト変更を実行中です。しばらくしてからもう一度お試しください。 | lock |
| lawnchair | organizer_lock_error_failed | 変更に失敗しました。内容は変更されていません。 | 変更に失敗しました。変更は適用されていません。 | lock |
| lawnchair | organizer_lock_error_intent_required | 適用するには、変更を確定してください。 | 適用する変更内容を確認してください。 | lock |
| lawnchair | organizer_lock_error_item_not_found | このアイテムは存在しなくなっています。内容は変更されていません。 | このアイテムは存在しません。変更は適用されていません。 | lock |
| lawnchair | organizer_lock_error_item_not_unknown | このアイテムは既に確認済みです。内容は変更されていません。 | このアイテムはすでに確認済みです。変更は適用されていません。 | lock |
| lawnchair | organizer_lock_error_profile_unavailable | このアイテムのプロファイルは現在利用できません。内容は変更されていません。 | このアイテムのプロファイルは現在利用できません。変更は適用されていません。 | lock |
| lawnchair | organizer_lock_error_stale | このアイテムまたはレイアウトが変更されました。内容は変更されていません。もう一度お試しください。 | このアイテムまたはレイアウトが変更されました。変更は適用されていません。もう一度お試しください。 | lock |
| lawnchair | organizer_lock_screen_effectively_locked | ロック済みの親により保護されています | 所属するフォルダまたはアプリペアのロックにより保護されています | lock |
| lawnchair | organizer_lock_state_unlocked | ロック解除 | ロック解除済み | lock |
| lawnchair | organizer_lock_screen_review_all_confirm | %1$d件の配置を「%2$s」として確認しますか？ この操作は表示中の配置のみに適用されます。 | %1$d件の配置を「%2$s」として確認しますか？ この操作は一覧の配置のみに適用されます。 | lock |
| lawnchair | organizer_lock_screen_review_all_keep_locked | すべて「ロック」として確認 | すべてロックとして確認 | lock |
| lawnchair | organizer_lock_screen_review_all_mark_unlocked | すべて「ロック解除」として確認 | すべてロック解除として確認 | lock |
| lawnchair | organizer_lock_screen_unknown_banner | %1$d件の配置が、整理の前に確認が必要です。 | 整理の前に%1$d件の配置を確認してください。 | lock |
| root | organizer_category_override_automatic | "自動カテゴリを使用中" | "自動分類を使用中" | category settings |
| root | organizer_category_override_automatic_description | "明示的なオーバーライドは保存されていません。次回の全体整理では自動分類が使用されます。" | "手動設定はありません。次回の全体整理では自動分類を使用します。" | category settings |
| root | organizer_category_override_busy | "整理が実行中です。カテゴリの編集の前に完了してください。" | "整理を実行中です。カテゴリを編集する前に完了してください。" | category settings |
| root | organizer_category_override_category_description | "明示的なカテゴリとして %1$s を使用します。" | "カテゴリを%1$sに設定します。" | category settings |
| root | organizer_category_override_conflict | "カテゴリソースが変更されました。一覧を再読み込みして、もう一度選択してください。" | "カテゴリの情報が変更されました。リストを再読み込みして、もう一度選択してください。" | category settings |
| root | organizer_category_override_explicit | "オーバーライド: %1$s" | "手動設定: %1$s" | category settings |
| root | organizer_category_override_failed | "カテゴリのオーバーライドを保存できませんでした。変更は確定されていません。" | "カテゴリ設定を保存できませんでした。変更は確定していません。" | category settings |
| root | organizer_category_override_no_change | "変更は不要でした。既存のカテゴリのオーバーライドを維持します。" | "変更はありません。現在のカテゴリ設定を維持します。" | category settings |
| root | organizer_category_override_profile_work | "仕事用プロファイル" | "仕事用" | category settings |
| root | organizer_category_override_save | "カテゴリのオーバーライドを保存" | "カテゴリ設定を保存" | category settings |
| root | organizer_category_override_saved | "カテゴリのオーバーライドを保存しました。" | "カテゴリ設定を保存しました。" | category settings |
| root | organizer_category_override_unavailable | "このアプリまたはプロファイルは利用できなくなっています。一覧を再読み込みしてから、もう一度お試しください。" | "このアプリまたはプロファイルは利用できません。リストを再読み込みして、もう一度お試しください。" | category settings |
| root | organizer_category_override_unavailable_store | "カテゴリのオーバーライドを利用できません。何も保存されていません。" | "カテゴリ設定を利用できません。変更は保存されていません。" | category settings |
| root | organizer_category_override_use_automatic | "自動カテゴリを使用" | "自動分類を使用" | category settings |
| root | organizer_category_overrides_summary | "アプリのカテゴリを選択するか、自動分類を使用します。" | "アプリごとにカテゴリを選択できます。自動分類も利用できます。" | category settings |
| root | organizer_category_overrides_title | "アプリカテゴリのオーバーライド" | "アプリカテゴリの手動設定" | category settings |

## Anonymous model bake-off

Twenty-four contextual units were selected before scoring: CTA/title, onboarding/progress/status, failure/safety/recovery, settings/category/lock, accessibility, and diagnostic/technical surfaces. The exact fixed inputs are preserved in [issue-161-ja-lqa-bakeoff-context.md](./issue-161-ja-lqa-bakeoff-context.md), including source/current Japanese, surface, role, neighboring copy, placeholder contract, behavior anchor, and rendered context. Candidate outputs were anonymized as X and Y during owner scoring.

Score axis order: Accuracy / Fluency / Terminology / UI style-concision / Context fit / Safety preservation / Resource correctness. Each axis is scored 0–2; total is out of 14. A 0 on meaning or safety is a hard failure.

| # | Resource | Context | Candidate X output | Candidate X | Candidate Y output | Candidate Y | Hard failure |
| ---: | --- | --- | --- | ---: | --- | ---: | --- |
| 1 | manual_organization_start | manual organization | 整理案を確認 | 2/2/2/2/2/2/2 (14/14) | 整理案を確認 | 2/2/2/2/2/2/2 (14/14) | none |
| 2 | manual_organization_preview | manual organization | 整理案を確認 | 2/2/2/2/2/2/2 (14/14) | 整理案を確認 | 2/2/2/2/2/2/2 (14/14) | none |
| 3 | manual_organization_confirm | manual organization | 確認した整理案を適用 | 2/2/2/2/2/2/2 (14/14) | 確認した整理案を適用 | 2/2/2/2/2/2/2 (14/14) | none |
| 4 | organizer_lock_action_keep_locked | lock | ロックしたままにする | 2/2/2/2/2/2/2 (14/14) | ロックしたままにする | 2/2/2/2/2/2/2 (14/14) | none |
| 5 | organizer_category_overrides_title | category settings | "アプリカテゴリの手動設定" | 2/2/2/2/2/2/2 (14/14) | "アプリカテゴリの手動設定" | 2/2/2/2/2/2/2 (14/14) | none |
| 6 | organization_onboarding_proposal_summary | onboarding | まず整理案を確認できます。プレビューを確定するまで、何も変更されません。 | 2/2/2/2/2/2/2 (14/14) | まず整理案を確認できます。プレビューを確定するまで、何も変更されません。 | 2/2/2/2/2/2/2 (14/14) | none |
| 7 | manual_organization_capturing | manual organization | 現在のホームレイアウトを読み込んでいます… | 2/2/2/2/2/2/2 (14/14) | 現在のホームレイアウトを読み込んでいます… | 2/2/2/2/2/2/2 (14/14) | none |
| 8 | manual_organization_planning | manual organization | 安全に整理できる案を作成しています… | 2/2/2/2/2/2/2 (14/14) | 安全に整理できる案を作成しています… | 2/2/2/2/2/2/2 (14/14) | none |
| 9 | manual_organization_apply_success | manual organization | 整理を適用し、検証しました。 | 2/2/2/2/2/2/2 (14/14) | 整理を適用し、検証しました。 | 2/2/2/2/2/2/2 (14/14) | none |
| 10 | organizer_lock_error_stale | lock | このアイテムまたはレイアウトが変更されました。変更は適用されていません。もう一度お試しください。 | 2/2/2/2/2/2/2 (14/14) | このアイテムまたはレイアウトが変更されました。変更は適用されていません。もう一度お試しください。 | 2/2/2/2/2/2/2 (14/14) | none |
| 11 | manual_organization_rejected | manual organization | このレイアウトは安全に整理できません。何も変更されていません。 | 2/2/2/2/2/2/2 (14/14) | このレイアウトは安全に整理できません。何も変更されていません。 | 2/2/2/2/2/2/2 (14/14) | none |
| 12 | manual_organization_apply_rolled_back | manual organization | 整理を取り消し、以前のレイアウトに戻しました。 | 2/2/2/2/2/1/2 (13/14) | 整理を取り消しました。以前のレイアウトのままです。 | 2/2/2/2/2/2/2 (14/14) | none |
| 13 | manual_organization_apply_recovery_failed | manual organization | 自動復旧を確認できませんでした。今は別の整理を開始しないでください。 | 2/2/2/2/2/2/2 (14/14) | 自動復旧を確認できませんでした。今は別の整理を開始しないでください。 | 2/2/2/2/2/2/2 (14/14) | none |
| 14 | manual_organization_recovery_not_available | manual organization | この保存済みレイアウトには、安全に戻れなくなっています。 | 2/1/2/2/2/2/2 (13/14) | この保存済みレイアウトは、安全に復元できなくなっています。 | 2/2/2/2/2/2/2 (14/14) | none |
| 15 | organizer_category_override_automatic | category settings | "自動分類を使用中" | 2/2/2/2/2/2/2 (14/14) | "自動分類を使用中" | 2/2/2/2/2/2/2 (14/14) | none |
| 16 | organizer_category_override_explicit | category settings | "手動設定: %1$s" | 2/2/2/2/2/2/2 (14/14) | "手動設定: %1$s" | 2/2/2/2/2/2/2 (14/14) | none |
| 17 | organizer_category_override_save | category settings | "カテゴリ設定を保存" | 2/2/2/2/2/2/2 (14/14) | "カテゴリ設定を保存" | 2/2/2/2/2/2/2 (14/14) | none |
| 18 | organizer_lock_screen_review_all_confirm | lock | %1$d件の配置を「%2$s」として確認しますか？ この操作は一覧の配置のみに適用されます。 | 2/2/2/2/2/2/2 (14/14) | %1$d件の配置を「%2$s」として確認しますか？ この操作は一覧の配置のみに適用されます。 | 2/2/2/2/2/2/2 (14/14) | none |
| 19 | organizer_category_override_app_description | category settings | "%1$s、%2$s、%3$s" | 2/2/2/2/2/2/2 (14/14) | "%1$s、%2$s、%3$s" | 2/2/2/2/2/2/2 (14/14) | none |
| 20 | organizer_category_override_app_description_with_profile | category settings | "%1$s、%2$s" | 2/2/2/2/2/2/2 (14/14) | "%1$s、%2$s" | 2/2/2/2/2/2/2 (14/14) | none |
| 21 | organizer_lock_screen_item_state_description | lock | %1$s、%2$s | 2/2/2/2/2/2/2 (14/14) | %1$s、%2$s | 2/2/2/2/2/2/2 (14/14) | none |
| 22 | organizer_diagnostics_title | diagnostics | オーガナイザー診断 | 2/2/2/2/2/2/2 (14/14) | オーガナイザー診断 | 2/2/2/2/2/2/2 (14/14) | none |
| 23 | organizer_diagnostics_description | diagnostics | プライバシーに配慮したオーガナイザー診断ジャーナルをサポート用にファイルへ書き出します。 | 2/2/2/2/2/2/2 (14/14) | プライバシーに配慮したオーガナイザー診断ジャーナルをサポート用にファイルへ書き出します。 | 2/2/2/2/2/2/2 (14/14) | none |
| 24 | organizer_diagnostics_export_label | diagnostics | オーガナイザー診断データを書き出す | 2/2/2/2/2/2/2 (14/14) | オーガナイザー診断データを書き出す | 2/2/2/2/2/2/2 (14/14) | none |

- Candidate X aggregate: 334/336 (99.4%). Candidate Y aggregate: 336/336 (100.0%).
- No candidate received a 0 on accuracy or safety; no hard-failure condition was triggered.
- After scoring, X mapped to Reviewer A (Codex default) and Y mapped to Reviewer B (gpt-5.6-sol / Sol-Low). The accepted copy follows the adjudicated Y wording where the candidates differed.

## Disagreements and adjudication

| Topic | Sol-Low finding | Decision |
| --- | --- | --- |
| Rollback vs recovery | The initial rollback wording could imply a recovery operation. | Adopted: 整理を取り消しました。以前のレイアウトのままです。 |
| Recovery unavailable | The initial alternative was less natural and less precise than the existing wording. | Retained: この保存済みレイアウトは、安全に復元できなくなっています。 |
| Legacy shortcut | レガシー was less user-facing than the glossary term. | Adopted 旧形式のショートカット consistently. |
| Target rejection messages | 配置先 and generic mismatch wording could obscure that the organizer target is invalid. | Adopted 整理対象, 対象情報, and profile-specific wording. |
| Unplaced target | The check concerns candidate availability, not destination availability. | Adopted 対象を利用できないため未配置. |
| Effectively locked | ロック済みの親 was too vague for folder/app-pair inheritance. | Adopted 所属するフォルダまたはアプリペアのロックにより保護されています. |
| Intent-required failure | The action was not explicit enough. | Adopted 適用する変更内容を確認してください。 |
| Stale proposal | Proposal terminology should align across the review flow. | Adopted 新しい整理案を確認してください. |
| Validator coverage | Format parsing and empty styled-string cases were initially under-covered. | Added flags/date/escaped-percent parsing and empty styled/plural tests; validator now passes. |

No item required PRODUCT_DECISION escalation, and no high-severity A/B disagreement remains.

## Rendered evidence

Captures were taken from the current issue branch APK on AVD issue142_api36 (Android 16 / API 36, 1080x2400, 420 dpi). The capture test exercised the manual-organization start, preview, success, stale, and recovery-failure states. The 200% preview capture is intentionally a scrollable screen; the related instrumentation test verified that both actions remain reachable.

| State | Japanese, normal | Japanese, 200% | en-XA |
| --- | --- | --- | --- |
| Start | [capture](issue-161/captures/ja-normal/start.png) | [capture](issue-161/captures/ja-200pct/start.png) | [capture](issue-161/captures/en-xa/start.png) |
| Preview and confirm | [capture](issue-161/captures/ja-normal/preview-confirm.png) | [capture](issue-161/captures/ja-200pct/preview-confirm.png) | [capture](issue-161/captures/en-xa/preview-confirm.png) |
| Success | [capture](issue-161/captures/ja-normal/success.png) | [capture](issue-161/captures/ja-200pct/success.png) | [capture](issue-161/captures/en-xa/success.png) |
| Stale | [capture](issue-161/captures/ja-normal/stale.png) | [capture](issue-161/captures/ja-200pct/stale.png) | [capture](issue-161/captures/en-xa/stale.png) |
| Recovery failure | [capture](issue-161/captures/ja-normal/recovery-failure.png) | [capture](issue-161/captures/ja-200pct/recovery-failure.png) | [capture](issue-161/captures/en-xa/recovery-failure.png) |

Japanese semantic, focus, touch-target, and action-reachability checks were exercised by the manual-organization, category-override, onboarding, lock-screen, and diagnostics instrumentation suites. The category-override test builds expected spoken descriptions from the localized resource so the same a11y contract is verified in English and Japanese.

## Verification record

| Check | Result |
| --- | --- |
| Structural resource validator unit tests | PASS — 9 tests |
| Structural resource validator against baseline | PASS |
| git diff --check | PASS |
| Gradle spotlessCheck | PASS — `./gradlew spotlessCheck` |
| Organizer unit tests | PASS — `./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.*'` |
| Debug assemble | PASS — `./gradlew assembleLawnWithQuickstepGithubDebug` |
| Android test APK build | PASS — `./gradlew assembleLawnWithQuickstepGithubDebugAndroidTest` |
| Repository contract check | PASS — `python3 tools/repo-contract/validate_repo_contract.py` |
| Repository contract tests | PASS — 11 tests |
| Related organizer UI instrumentation | PASS — 10 manual-organization tests; 30 category/onboarding/lock/diagnostics tests in English and Japanese locales |
| Rendered Japanese/en-XA/font-scale evidence | PASS — issue142_api36 captures and 200% reachability test; links above |
| PR CI final-status | PENDING — workflow is running or awaiting completion for the pushed post-review-fix head |

The local evidence is complete. PR CI final-status remains pending until the repository workflow completes on the pushed post-review-fix head.
