# Issue #324 — Product Language Reviewer model bake-off

> Status: executed 2026-10-15 (implementation phase, spec AC-324-04 / AC-324-15)
> Fixed input: [issue-324-bakeoff-context.md](./issue-324-bakeoff-context.md) — 24 units pinned to main `25f97aef4d`, 8 classes (CTA/title, onboarding/progress/status, failure/safety/recovery, settings/category/lock, a11y, diagnostic), including 2 ja-missing units and AI-register stress material.
> Audited tree: branch `issue-324-implementation` from main `25f97aef4d` (post PR #602 merge).

## Provenance

| Role | Runtime / channel | Effective model (exact identifier) | Family |
|---|---|---|---|
| Implementation worker (this session) | ZCode session | `account:zai-offpeak-idle-plan/GLM-5.3` | GLM |
| Functional reviewer (project pipeline) | ChatGPT project review + codex CLI default | GPT (e.g. `gpt-6.1-sol`, `codex-cli 0.162.1` config `model = "gpt-6.1-sol"`) | GPT |
| Candidate C1 | ZCode `general-purpose-2` subagent | `opencode-go-chat/deepseek-v4.1-flash` | DeepSeek |
| Candidate C2 | `codex exec --sandbox read-only` (codex-cli 0.162.1) | `gpt-6.1-sol` (config default, reasoning high) | GPT |
| Adjudicator (blind) | ZCode `general-purpose` subagent, independent session from both candidates | `account:zai-offpeak-idle-plan/GLM-5.3` | GLM |

Execution date: 2026-10-15 (JST). Candidate C1's session: ZCode subagent (agent id recorded in session log); Candidate C2's session: codex exec run from the repository root with read-only sandbox. Both candidates received the identical instruction set (skill contract + references + #161 canon + the fixed 24-unit context).

## Protocol (adapted from #161)

#161's 7 axes (Accuracy / Fluency / Terminology / UI style-concision / Context fit / Safety preservation / Resource correctness, 0–2 each) were kept and an 8th axis — **AI-register detection** — added per spec AC-324-15 (judging both real-tell engagement at the checklist granularity and counter-myth false-positive discipline). Unit max /16, total /384. Hard failure: 0 on Accuracy or Safety preservation for any unit. Outputs were anonymized (α = C2, β = C1) before scoring; the anonymized outputs are preserved checked-in as [issue-324-bakeoff-outputs-anonymized.md](./issue-324-bakeoff-outputs-anonymized.md) so the owner can re-score blind; the mapping was recorded only after the provisional scorecard was final. The scoring below is **provisional independent-session scoring** (solo-maintenance substitution); the owner remains the adjudication authority per the #161 canon and the packet above enables the owner's blind scoring at any time.

## Result

| Candidate | Provisional total | Hard failures | Notable |
|---|---|---|---|
| β = C1 `deepseek-v4.1-flash` | **383/384** | 0 | Systematic single-string symbol-remnant detection; used neighboring-copy context (unit 09 方針 consistency, unit 10 sibling 表記揺れ `隙間を閉じます` vs `すき間を詰めます`); safety discipline (privacy sentence kept meaning-identical, #451 agentive verb preserved). Dispositions: OK 11 / REVISE 13. |
| α = C2 `gpt-6.1-sol` | 373/384 | 0 | Accurate and safe overall; AI-register detection applied inconsistently (axis sum 42 vs β 48); over-escalated unit 19; overstated severity on units 03/05/06. |

Per-unit scorecard with the adjudicator's integer rules (reproducible scoring definitions) is preserved below in the appendix.

## Decision

> **Provisional.** #161's canon (`ja-review-workflow.md` §7) makes the project owner the sole blind adjudication authority. The scoring below was performed by an independent session (solo-maintenance substitution, per the repository's independence pattern); it is recorded as **provisional independent-session scoring**, and the **owner blind adjudication packet** (below) makes the owner's own scoring directly executable. The default model pinned in the ZCode adapter is provisional until that scoring; changing it is a one-line `model:` edit guided by the packet.

- **Provisional default reviewer model: `opencode-go-chat/deepseek-v4.1-flash` (C1).** Family check (spec AC-324-04): DeepSeek ≠ GLM (implementation worker) and ≠ GPT (functional reviewer) — satisfied. No hard failures; highest provisional total; the decisive axis was AI-register detection.
- **C2 rejected for the role on family grounds** (GPT family collides with the functional reviewer), independent of its score — demonstrating that the family rule operates before quality ranking.
- **Issue's initial candidate `gemini-3.8-flash` (`2c4a554d-c324-4bd5-8567-20db0f081147/gemini-3.8-flash`): present in the host runtime model list (2026-10-15, `ListModels`) but not executable through any channel available to the implementing session.** Primary-source evidence of the launch attempts (2026-10-15): (a) ZCode Agent tool — the named-subagent registry is fixed at session start; creating workspace `.zcode/agents/product-language-reviewer.md` and user-level `~/.zcode/agents/plr-gemini.md` mid-session does not register them (invocation attempt returned `Agent type not found`; available non-GLM/non-GPT registered agents: `general-purpose-2` only); (b) codex CLI 0.162.1 — GPT-family models only (`~/.codex/models_cache.json`: gpt-reserve / gpt-5.6-sol / terra / luna / codex-auto-review; config `model = "gpt-6.1-sol"`); (c) no nested ZCode session CLI is exposed (`zcode` not on PATH; `~/.zcode/cli/zcode.cjs` not runnable); (d) no direct provider API channel is available to the session. Accordingly the D1 fallback ("初期候補が現行 runtime で利用できない場合、代替候補で bake-off を実行して記録する") is applied on the basis of **non-executability in every available channel**, with the registry presence recorded. Challenger re-run protocol for the owner: install a user-level agent (copy of `.zcode/agents/product-language-reviewer.md` with `model: 2c4a554d-c324-4bd5-8567-20db0f081147/gemini-3.8-flash`), start a fresh ZCode session in this repository, run the same fixed input with the same instruction set used for C1/C2 (see Provenance), append the anonymized output to the packet as Candidate γ, and score it with the same 8 axes before opening the identity mapping. Adoption changes only if the challenger wins without hard failures; the skill contract pins no vendor/model.
- **Owner blind adjudication packet (all checked into this repository):** (1) fixed input `issue-324-bakeoff-context.md`; (2) anonymized candidate outputs `issue-324-bakeoff-outputs-anonymized.md` (self-identifying strings redacted); (3) this file's protocol section (8 axes, 0–2, hard-failure rule, tie-break); (4) the identity mapping — **α = C2 `gpt-6.1-sol`, β = C1 `deepseek-v4.1-flash`** — recorded here, to be consulted only after the owner's own scoring is done. The owner's scores and adoption ruling should be appended to this file (or the final PR record) to complete spec AC-324-04's owner-blind requirement.
- ZCode adapter status (plan D2): a workspace-level `.zcode/agents/product-language-reviewer.md` ships in-repo, and the verified user-level placement (`~/.zcode/agents/product-language-reviewer.md`, the #199 mechanism) is installed with this content. **Workspace-level discovery is unverified from the implementing session** (registry fixed at session start — see launch-attempt evidence above); the first fresh ZCode session in this repository should confirm whether `product-language-reviewer` appears in its agent list. If it does not, delete the workspace file and keep the user-level placement as the single adapter, with the copy source (`.zcode/agents/` in this repository) and procedure documented here: copy the file to `~/.zcode/agents/product-language-reviewer.md`, restart ZCode, verify the agent appears and answers with its own model identity.

## Appendix — adjudication scorecard (preserved verbatim)

See the full per-unit table produced by the blind adjudicator session (37 table rows + integer rules). Retained in the repository record below:

# Issue #324 bake-off — blind adjudication scorecard

Adjudicator: independent blind session, 2026-10-15. Judged only against
`docs/assessment/evidence/issue-324-bakeoff-context.md` (fixed units + protocol),
`.agents/skills/product-language-review/references/ai-register-tells.md`,
`docs/localization/ja-style-guide.md`, `ja-glossary.tsv`, and the rubric/schema
disposition semantics. Self-disclosed identity claims in the candidates' run
footers were disregarded for scoring; family-rule adoption eligibility is
outside this adjudication.

Axis order in every cell: **A/F/T/St/C/Sf/R/AR** = Accuracy / Fluency /
Terminology / UI style-concision / Context fit / Safety preservation /
Resource correctness / AI-register detection. Each 0–2, unit max /16.

Integer rules applied (stated so the scores are reproducible):

- **AI-register 2** = real tells engaged at the checklist granularity with rule
  citations, or clean non-flagging where none apply (incl. counter-myth
  discipline); **1** = substantive fix that incidentally removes a tell without
  checklist engagement, or inconsistent application of the same tell across
  units; **0** = counter-myth item flagged as a finding, or a missed
  safety-relevant register rewrite (neither occurred).
- **Context fit** docks: ignoring neighboring-copy data explicitly supplied in
  the unit context, over-escalation of judgeable units, or asserting
  unverifiable surface facts.
- Severity-label miscalibration (α marked `high` on 03/05/06 where current
  safety meaning was intact) is noted per unit but scored only once, under
  Context fit where it materially misrepresented the unit.

## Per-unit scores

| unit | α (A/F/T/St/C/Sf/R/AR) | β (A/F/T/St/C/Sf/R/AR) | α note | β note |
|---|---|---|---|---|
| 01 move_to_page | 2/2/2/2/2/2/2/2 = 16 | 2/2/2/2/2/2/2/2 = 16 | OK; sound picker/ellipsis judgment | OK; equally sound, cites picker convention |
| 02 undo_label | 2/2/2/1/2/2/2/2 = 15 | 2/2/2/2/2/2/2/2 = 16 | OK misses canon `。` 基本 for toast completion sentence | REVISE adds `。` per canon progress/toast row; meaning-safe, undo-chip relation noted |
| 03 undo_error_stale | 2/2/2/2/2/2/2/1 = 15 | 2/2/2/2/2/2/2/2 = 16 | Good causal rewrite (その後→編集後, passive fixed) but colon remnant never engaged as tell | Split-sentence fix; cites S 記号の残骸 tell + ADR-0013 parity note (does not invent "edit stays" text) |
| 04 exclude_action | 2/2/2/2/2/2/2/2 = 16 | 2/2/2/2/2/2/2/2 = 16 | 整理案 alignment, correct glossary cite | Same fix + coordinated sibling include-action note (conditional, careful) |
| 05 exclusion_blocked_structural | 2/2/2/2/2/2/2/1 = 15 | 2/2/2/2/1/2/2/2 = 15 | Catches dev-note default English (rubric default-EN quality) + plain ja rewrite; minor 整理案×2 repetition; severity `high` overstated | Cleaner ja (refusal first) + 再計画→再計算 unification with unit 21 + S tell; misses the lowercase-fragment default-EN issue |
| 06 duplicate_launch_target | 2/2/2/2/2/2/2/1 = 15 | 2/2/2/2/2/2/2/2 = 16 | 起動先 precision is best-accurate to #451 behavior, but 移動していません weakens the agentive させなかった guarantee; no register framing | Restores 他のアイコン from default, keeps agentive 移動させなかった, cites S tell + explicit #451 preservation note |
| 07 unplaced_candidates_heading | 2/2/2/2/2/2/2/1 = 15 | 2/2/2/2/2/2/2/2 = 16 | Terminology-only fix; keeps half-width colon it removed in 03/06/23 — inconsistent tell application | Terminology + colon normalization, consistent with own coordinated set |
| 08 organizer_hub_title | 2/2/2/2/2/2/2/2 = 16 | 2/2/2/2/2/2/2/2 = 16 | Glossary-correct 整理機能 | Same + notes default EN fine and hub-summary alignment |
| 09 strategy_title | 2/2/2/2/1/2/2/2 = 15 | 2/2/2/2/2/2/2/2 = 16 | 整理方法 + glossary-add idea, but misses neighboring summary 方針 mismatch supplied in context | Catches 方針→方法 summary coordination and flags it as outside the unit set |
| 10 tidy_v2_description | 2/2/2/2/1/2/2/2 = 15 | 2/2/2/2/2/2/2/2 = 16 | OK misses the sibling すき間/隙間 + 詰め/閉じ divergence explicitly given as neighboring context; claims v1 delta readable | REVISE unifies sibling wording so only the real v1/v2 delta (widgets) remains; N-granularity used correctly |
| 11 frozen_operation_active | 2/2/2/2/2/2/2/2 = 16 | 2/2/2/2/2/2/2/2 = 16 | OK; order constraint + no auto-interrupt noted | OK; equally sound, canon punctuation confirmed |
| 12 exchange_capability_flow | 2/2/2/2/2/2/2/2 = 16 | 2/2/2/2/2/2/2/2 = 16 | OK with explicit anti-overtrim and counter-myth (sentence-ending) discipline | Typography REVISE (full-width parens, など) at low severity; privacy sentence kept byte-identical — textbook safety discipline |
| 13 discard_confirm_body | 2/2/2/2/2/2/2/2 = 16 | 2/2/2/2/2/2/2/2 = 16 | OK; all three constraints verified | OK; same + defers 取り込み/読み込み split to footer |
| 14 clipboard_empty | 2/2/2/2/2/2/2/2 = 16 | 2/2/2/2/2/2/2/2 = 16 | OK; refuses single-tell flag on お使いください (operating principle 1 applied) | OK; recovery chain fully verified |
| 15 import_from_clipboard | 2/2/2/2/2/2/2/2 = 16 | 2/2/2/2/2/2/2/2 = 16 | OK; CTA predictability | OK; pairs with file import |
| 16 ai_consultation_toggle | 2/2/2/2/2/2/2/2 = 16 | 2/2/2/2/2/2/2/2 = 16 | AIへの相談; scope-disciplined (behavior left to description) | AI相談; aligns with unit 12 相談 + resource-compat note for the new ja |
| 17 rejection_invalid_category_provenance | 2/2/2/2/2/2/2/1 = 15 | 2/2/2/2/2/2/2/2 = 16 | 出所 proposal; correctly keeps %1$d as bare ID (no 助数詞); half-width colon kept | 設定元 proposal (precise for override provenance); mirrors default structure; colon normalized |
| 18 diagnostics_recent_import_failure | 2/2/2/2/2/2/2/2 = 16 | 2/2/2/2/2/2/2/2 = 16 | TECHNICAL_ONLY — apt use of the disposition | OK — equally apt; defers flow-term split to footer |
| 19 lock_screen_item_state_description | 2/1/2/1/2/2/2/2 = 14 | 2/2/2/2/2/2/2/2 = 16 | PRODUCT_DECISION escalation is rubric-citable (runtime %2$s shape unknown) but placeholder meanings were supplied; renders no fluency/style judgment and produces no review value for a structurally correct format resource | OK; verifies placeholder types/order and read-point naturalness — decisive and proportionate; slight over-assertion of composed naturalness |
| 20 exchange_disclosure_hint | 2/2/2/2/2/2/2/2 = 16 | 2/2/2/2/2/2/2/2 = 16 | Imperative + そのまま restores consent semantics; `high` severity defensible here | Same-shape fix; guarantee sentence unchanged; cites "AI-register S" loosely for a style-guide principle (minor) |
| 21 replanning | 2/2/2/2/2/2/2/2 = 16 | 2/2/2/2/2/2/2/2 = 16 | 整理案を再計算中 | Identical + cross-unit coordination set |
| 22 hub_proposal_row | 2/2/2/2/2/2/2/2 = 16 | 2/2/2/2/2/2/2/2 = 16 | Terminology unification | Same + explicitly refuses inventing "pending review" |
| 23 undo_error_no_space | 2/2/2/2/2/2/2/1 = 15 | 2/2/2/2/2/2/2/2 = 16 | Good causal rewrite, shorter; colon tell not engaged | Split-sentence fix consistent with 03; S tell + ADR-0013 note |
| 24 diagram_widget | 2/2/2/2/2/2/2/2 = 16 | 2/2/2/2/2/2/2/2 = 16 | OK; user-term maintenance cited | OK; same |

## Per-axis sums and totals

| axis | α sum (/48) | β sum (/48) |
|---|---|---|
| Accuracy | 48 | 48 |
| Fluency | 47 | 48 |
| Terminology | 48 | 48 |
| UI style-concision | 46 | 48 |
| Context fit | 46 | 47 |
| Safety preservation | 48 | 48 |
| Resource correctness | 48 | 48 |
| AI-register detection | 42 | 48 |
| **Total (/384)** | **373** | **383** |

Tie-break data: Safety + Accuracy = α 96, β 96 (tied; decided by total and rationale below).

## Hard failures

- **α: none.** No unit scored 0 on Accuracy or Safety preservation. Weakest
  safety-adjacent moment is unit 06 (`移動していません` softens the #451
  agentive "intentionally not moved" nuance) — scored 2 with note, not a
  meaning loss.
- **β: none.** No unit scored 0 on Accuracy or Safety preservation. Weakest
  context moment is unit 05 (default-English dev-note quality missed) — scored
  1 on Context fit only.

## Comparative verdict

β is the stronger dedicated reviewer (383 vs 373). The decisive gap is the
AI-register axis (48 vs 42): β detected the half-width `: `/paren symbol
remnants as S-granularity tells consistently across 03/05/06/07/12/23 and
bundled them with substantive fixes at low severity, while α removed the same
remnants incidentally in some units and kept them in 07/17 — detection without
systematics. β also used neighboring-copy data better (09 方針 coordination,
10 sibling 表記揺れ) and showed exemplary safety discipline (privacy sentence
byte-identical in 12, agentive 移動させなかった kept in 06, coordinated change
sets in the footer). α's genuine advantages — catching the unit-05 dev-note
default English, 起動先 precision in 06, and strict counter-myth avoidance —
are real but narrower, and its `high` severity calls on 03/05/06 plus the
unit-19 over-escalation cost context points. Both are accuracy- and
safety-clean (48/48 each), so no hard failures distort the comparison; if only
Safety+Accuracy mattered the candidates tie, and the verdict rests on β's
systematic detection and cross-unit consistency.

(Per-unit deltas worth recording for future re-runs: unit 19 α over-escalation; units 03/05/06 α severity overstatement; AI-register axis α 42 vs β 48; Safety+Accuracy sums tied 96/96 — tie broken per protocol by the AI-register axis and the adjudicator's written rationale.)
