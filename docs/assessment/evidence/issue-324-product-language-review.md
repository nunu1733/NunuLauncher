# Issue #324 — full re-audit evidence

> Status: executed 2026-10-15 (implementation phase, spec AC-324-09–13)
> Full table: [issue-324-fullpass-table.md](./issue-324-fullpass-table.md) — all 626 units.

## Inventory

- Base SHA: branch `issue-324-implementation` from main `25f97aef4d` (post PR #602; resource values identical to main — resource reflection happens only in this change set).
- Regeneration: Nunu prefixes `organizer_` / `manual_organization_` / `organization_onboarding_` / `exchange_` / `homeedit_` across both roots (`lawnchair/res`, root `res`), strings+plurals, `translatable != false` (0 excluded). Total 626 units (lawnchair 540 + root 86). The prefix set now matches the oracle's extended `NUNU_PREFIXES`.
- 8-class mapping per spec AC-324-09 was assigned per unit by the reviewer (ui_role/user_class in the table); class coverage: CTA/title, onboarding/progress/status, warning/confirmation/failure/stale/recovery, settings/category/lock, progress/result/empty, a11y, diagnostics.

## Reviewer provenance

- Provisional dedicated reviewer: `opencode-go-chat/deepseek-v4.1-flash` via ZCode `general-purpose-2` subagents (7 batches, agent ids in session log `sess_28f8ae8c-812-49f3-aa27-75e75919e46d`; per-batch slices + outputs preserved in the implementing session's scratch and consolidated into the checked-in table). Family-separated from the GLM implementation worker and the GPT functional reviewer. **Provisional** pending the owner blind adjudication packet ([issue-324-owner-blind-packet.md](./issue-324-owner-blind-packet.md)) and the fresh-session Gemini check — if the adopted model changes, re-running this pass with the final model is the follow-up (the fixed inventory and table make that mechanical).
- Canon used: `product-language-review` skill + references, `ja-style-guide.md`, `ja-glossary.tsv` (22 terms at pass time), CONTEXT.md.

## Dispositions (626 units)

- `OK` 494 / `REVISE` 123 (low 83 / medium 40) / `PRODUCT_DECISION` 4 / `TECHNICAL_ONLY` 5.
- Semantic guard (independent GLM session, functional side): 123/123 ACCEPT, 0 reject — placeholder contracts, default-meaning preservation, and safety semantics verified per row (verdicts in the implementing session's scratch `guard-verdicts.tsv`; rejected set empty).
- Default English: no `REVISE` proposed an English-default change; the pass confirmed the Japanese side is the locus of the accepted revisions (spec AC-324-10 satisfied vacuously this round — developer-oriented defaults flagged as `PRODUCT_DECISION` where rewriting would change meaning).

## `PRODUCT_DECISION` (owner resolution pending; resources unchanged)

1. `manual_organization_rejection_unknown_signal_item` — how much of the internal rejection-code taxonomy to surface.
2. `organizer_custom_category_title` — hub/category title terminology choice (「ユーザー定義カテゴリ」 vs shorter label) needs an IA-level naming decision.
3. `exchange_import_fallback_toggle` — fallback-toggle wording depends on an undecided default behavior presentation.
4. `exchange_rebind_anchor_refused` — rebind refusal wording depends on the accepted scope-remedy semantics nuance.

These stay unchanged in resources; resolve on the Issue or split follow-ups.

## Resource reflection (this change set)

- 120 revised Japanese strings/plurals applied to `lawnchair/res/values-ja/strings.xml` and `res/values-ja/strings.xml` (escaping preserved; placeholder/plural contracts intact).
- 3 missing Japanese resources added: `exchange_ai_consultation_toggle_label`（AIに相談）, `exchange_ai_consultation_toggle_description`, `manual_organization_rejection_invalid_category_provenance`（許可されていないカテゴリの割り当て元: %1$d件）.
- Glossary: 7 accepted terms appended (22 → 29).

## D6 disposition record (oracle)

| Finding class | Count | Disposition |
|---|---|---|
| (i) `manual_organization_*` placeholder/plural 契約 | 13 | oracle 契約修正 — locale-aware comparison (positional multiset; ja may omit CLDR-unused categories one/zero/two/few) |
| (ii) `manual_organization_rejection_invalid_category_provenance` ja 未提供 | 1 | resource 修正（本変更 set で ja 追加） |
| (iii) `organizer_custom_category_*` only-other plurals | 2 | oracle 契約修正（(i) と同 class） |
| (iv) `exchange_ai_consultation_toggle_*` ja 未提供 | 2 | resource 修正（ja 追加）+ required set を `exchange_`/`homeedit_` へ拡張して可視化 |
| (v) `work_profile_scheduler_intent` empty resource（upstream 16-dev 由来） | 1 | oracle 契約修正 — empty 検査を Nunu 固有 resource に限定（upstream の `translatable="false"` 空 placeholder は本 oracle の契約対象外） |
| oracle baseline `505dbc40e6154`（pre-cutover 15 tree） | — | 維持。cutover merge が ancestry を保持するため required set 再構成は現行でも健全（拡張 prefix の required set は baseline に存在しないため全数 required となる） |

Result: `python3 tools/localization/verify_nunu_ja_resources.py --baseline 505dbc40e6154c05158b5d0271c45f6a885a411b` → **PASS**, self-test 9/9 OK（spec AC-324-12: 新規 finding ゼロ・全 disposition 完了・oracle pass）。

## Verification (this change set)

- `git diff --check` PASS; `python3 tools/repo-contract/validate_repo_contract.py` PASS.
- Oracle + self-test as above. Gradle gates and rendered-screen evidence (normal + enlarged font, owner on-device per tier-M rule, emulator auxiliary) recorded on the final PR; NFR-014 benchmark exempt (copy-only change; no interaction-count or write-contract change).
