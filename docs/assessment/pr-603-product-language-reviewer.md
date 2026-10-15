# Independent audit: PR #603 product-language full-pass copy revision (Issue #324 final implementation PR)

> Status: **request_changes** (one evidence-file defect; resource/oracle surfaces pass)
> Audit date: 2026-10-15

- Auditor: independent product-language-reviewer audit session (non-implementing; no file modified except this record)
- PR: https://github.com/nunu1733/NunuLauncher/pull/603
- Audited head SHA: `bef433290d3e2301db7e3fc492a9a5881ce790e5` (branch `issue-324-implementation`)
- Base: `main` = `25f97aef4d353566b442a4c40552c49e77fbd9c0` (verified: `git merge-base HEAD main` = `25f97aef4d…`; working tree clean at audit start)
- Criteria: `specs/324-product-language-reviewer/spec.md` — AC-324-09, AC-324-10, AC-324-11, AC-324-12 (evidence-chain items); AC-324-04 blind-adjudication packet condition referenced in Finding 1. AC-324-13 (on-device rendered evidence) is outside this audit's surfaces and remains an owner gate.

## Scope

Independent re-execution of the localization oracle and self-tests, diff hygiene, Japanese resource contract safety, oracle-change scope versus the documented D6 dispositions, and internal consistency of the checked-in evidence files. No statement in the PR text was trusted without re-running.

## Verification surfaces (commands and outcomes)

All commands run at the audited head in `/Users/nunu/Documents/work2/NunuLauncher`.

### 1. Oracle and self-tests

| Command | Outcome |
|---|---|
| `python3 tools/localization/verify_nunu_ja_resources.py --baseline 505dbc40e6154c05158b5d0271c45f6a885a411b` | `PASS: Nunu Japanese resource names and placeholder contracts match.` (exit 0) |
| `python3 tools/localization/test_verify_nunu_ja_resources.py` | `Ran 9 tests … OK` (9/9, exit 0) |

### 2. Diff hygiene and repo contract

| Command | Outcome |
|---|---|
| `git diff --check main...HEAD` | clean (exit 0, no output); `git diff --check` on worktree also clean |
| `python3 tools/repo-contract/validate_repo_contract.py` | 2 findings, both under `refocus-drafts/` (`refocus-drafts/adr/0014-edit-surface.md`, `refocus-drafts/open-issue-dispositions.md`). `git check-ignore -v` confirms `refocus-drafts/` is ignored via `.gitignore:24` (local-only, not on CI) — out of audit scope per instructions. Zero findings outside that directory. |

### 3. Resource diff contract safety (`git diff main...HEAD -- lawnchair/res/values-ja/strings.xml res/values-ja/strings.xml`)

- **(a) No name removed/renamed.** Diff-level set comparison of `name="…"` on `-`/`+` lines: every removed name re-added; full-file name-set comparison `main` vs `HEAD` (string/plurals/string-array) shows zero names only-in-main for both files.
- **(b) Placeholders preserved per changed string.** Scripted pairing of all 110 modified single-line `<string>` entries and comparison of placeholder signatures (positional `%N$s`/`%N$d` with flags/width/precision/date/conversion, non-positional tokens, literal `%%`): **0 mismatches**. The 9 modified plurals changed only `<item>` bodies (no `<plurals name=` lines changed); their contracts are covered by the oracle PASS (all Nunu resources are in the required set — see §4).
- **(c) Plurals structure.** No `<plurals>` element was added/removed/renamed; all 23 plural elements in the two ja files retain valid `<item quantity="…">` structure.
- **(d) Three net-new Japanese resources** (`exchange_ai_consultation_toggle_label`, `exchange_ai_consultation_toggle_description`, `manual_organization_rejection_invalid_category_provenance`) exist in `lawnchair/res/values-ja/strings.xml` with placeholder contracts identical to the defaults in `lawnchair/res/values/strings.xml` (lines 1325/1326/1422): none/none/`%1$d` — ja has none/none/`%1$d` (「…割り当て元: %1$d件」).
- **(e) Meaning spot-read (10 revised safety/warning/failure strings vs default English).** Checked: `exchange_failure_capability_unsupported`, `exchange_failure_forbidden_content`, `homeedit_error_write_failed`, `manual_organization_failed`, `manual_organization_recovery_not_available`, `manual_organization_recovery_restored`, `manual_organization_rejection_locked_out_of_bounds`, `manual_organization_rejection_overlap`, `manual_organization_stale_proposal_not_reviewed`, `organizer_lock_effect_folder_children_own_lock_remains`. No meaning loss found. Two terminology shifts are documented, glossary-driven accepted revisions in `issue-324-fullpass-table.md` with meaning-invariance notes: 整理案→回答 for the exchange failure-detail strings (rows 546/548: "整理案はorganizer計画語でAI回答と混同…「回答」主体で統一") and アイテム→配置 for the locked-out-of-bounds rejection (row 196: "ロック済みアイテムはglossaryのavoid形。ロックした配置へ"). Safety qualifiers (layout-unchanged, restore-safety, irreversibility, count semantics with `%1$d` + 件) are retained in all ten.
- **Full-pass reflection completeness (extra check):** all 123 `REVISE` rows of the full-pass table were script-compared to the HEAD ja resources: 114 strings + 9 plurals match the proposed text verbatim (plural rows' `other=` / `<item>` wrapper notation aside); the remaining row (`exchange_failure_framing_empty`, table line 534) already matched the proposed text in `main`. 123/123 accepted revisions are reflected.

### 4. Oracle change scope vs documented D6 dispositions (`git diff main...HEAD -- tools/localization/verify_nunu_ja_resources.py`)

Code reading of the +74/−12 diff plus an adversarial re-run:

- Prefix extension: `NUNU_PREFIXES` gains `exchange_` and `homeedit_` — strictly widens the required set. Verified baseline `505dbc40e6…` contains **zero** Nunu-prefixed names in both resource roots, so every Nunu resource is required and fully checked (presence, kind, translatable, placeholder contract).
- Locale-aware relaxation is exactly the two documented classes: (i) ja may omit plural quantities in `JA_UNUSED_QUANTITIES = {one, zero, two, few}` (a conservative subset of CLDR-ja-unused; "many"/"other" remain required, and ja cannot declare quantities absent from the default); (ii) `_tokens_compatible` accepts reordering only when **every** token in **both** locales is explicitly positional, comparing full 7-field token tuples (prefix, position, flags, width, precision, date, conversion) as sorted multisets — conversion-type swaps (`%1$s`↔`%1$d`), count changes, flags/width/precision changes, and any unnumbered-sequence difference still fail. The existing self-tests continue to assert those failure cases (type/order, flags/dates, extra plural quantity) and pass.
- Empty-structure checks (`empty resource`, `plural has no item`, `empty item`) are scoped to `is_nunu_resource(name)` only; duplicate-name detection and unsupported-format-sequence detection remain global. Justification verified: `res/values/strings.xml:506` ships upstream empty `translatable="false"` placeholder `work_profile_scheduler_intent`.
- **Adversarial re-run:** extracted `main`'s oracle and ran it against the audited tree → exactly 16 findings, all in the documented D6 classes: 13 × `manual_organization_*` (D6 i: ja omitting CLDR-unused `one`, or fully-positional reorders), 2 × `organizer_custom_category_*` (D6 iii), 1 × `work_profile_scheduler_intent` empty (D6 v). No other class of finding is masked by the change. (D6 ii/iv — the missing-ja findings — were invisible under the old prefixes and are fixed by resource additions plus the prefix extension.) Reconciles with the spec's "17 existing + 2 newly visible" finding count.

### 5. Evidence files

| File | Check | Result |
|---|---|---|
| `docs/assessment/evidence/issue-324-fullpass-table.md` | 626 tab-separated rows (script count = 626); dispositions OK 494 / REVISE 123 (low 83 + medium 40) / PRODUCT_DECISION 4 / TECHNICAL_ONLY 5; no duplicate names; all REVISE reflected (see §3) | Pass |
| `docs/assessment/evidence/issue-324-product-language-review.md` | Disposition counts, base SHA `25f97aef4d`, 120+3 application statement, D6 disposition table, glossary 22→29 — consistent with table and resources (the "120 revised" figure = 119 changed entries + 1 pre-applied row; counting-convention difference only) | Pass |
| `docs/localization/ja-review-workflow.md` §10 "Ongoing review pass" | Present; covers trigger, named-subagent invocation, model-family requirement, explicit-skip rule, PRODUCT_DECISION boundary, #199 boundary, CI stance | Pass |
| `docs/localization/ja-glossary.tsv` | 7 terms appended (22→29 lines 24→30 with header) | Pass |
| `docs/assessment/evidence/issue-324-bakeoff.md` | References the sealed packet; contains **no sealed-id→model correspondence** (its α/β table is the documented provisional scoring round, explicitly superseded for owner scoring) | Pass |
| `docs/assessment/evidence/issue-324-owner-blind-packet.md` | then-current sealed labels present, no literal model names (`[identity redacted]` applied) — **but the seal was broken by non-model identifiers; see Finding 1** | **Fail** (historical; since resealed with fresh ids) |

## Finding 1 (blocking, historical) — owner-blind packet published the then-sealed-id→model correspondence

The packet's stated contract: candidate ids are "fresh random labels with no published correspondence to any model, adapter, or earlier α/β record", echoed by `issue-324-bakeoff.md` §"Owner blind adjudication packet" ("fresh sealed ids with no published correspondence … the sealed id → model mapping is deliberately not checked in"). Three checked-in facts defeated the then-current seal:

1. Packet line 167: the Q section is titled `# Candidate β review (identity withheld)`. `issue-324-bakeoff.md` lines 27–28 publish `β = C1 deepseek-v4.1-flash` and `α = C2 gpt-6.1-sol`. Hence the candidate-to-model correspondence was derivable from the repository alone (specific derivation redacted per the round-5 review: this record must not publish mappings for identifiers that are or were used as blind IDs).
2. Packet P-section run footer (line ~161): `実行情報: OpenAI／[identity redacted]… GPT系候補は採点可能だが採用対象外` — the surviving vendor string plus a family note identified the candidate's model family (specific derivation redacted per the round-5 review), consistent with (1).
3. Packet line 3 states "The identity mapping is recorded in issue-324-bakeoff.md and must be consulted only after scoring", directly contradicting bakeoff's "deliberately not checked in; held in the implementing agent's hand-off report". (Q's footer also preserves `provider zai-offpeak-idle-plan`, an unredacted host-provider identifier.)

Impact: AC-324-04 requires owner blind adjudication "候補名を伏せた出力に対し" (outputs with candidate identity concealed). The owner's still-pending blind scoring would run on a packet whose candidate identities — and, via the bakeoff's provisional α 373 / β 383 totals, their provisional scores — are published in the same evidence set. The blindness property the packet exists to provide does not hold. The Q footer's self-reported family ("implementation worker's model family") additionally conflicts with the β/C1 identity, making the preserved self-reports internally misleading.

Fix is small and does not touch resources: redact the α/β-era labels and vendor/provider strings inside the packet (the line-167 title, "OpenAI"/"GPT系候補" in the P footer, `zai-offpeak-idle-plan` in the Q footer) to `[identity redacted]`, and reconcile packet line 3 with bakeoff's not-checked-in statement — before owner scoring is performed.

## Non-blocking notes

- The oracle self-test file was not extended with positive tests for the new relaxation paths (ja-omitted CLDR-unused quantity, accepted positional reorder). The strictness boundary is still asserted by the existing failure-case tests and by `test_real_repository_passes`; acceptable for this change, but positive-path tests would pin the D6 contract against future over-relaxation.
- AC-324-13 (rendered on-device owner verification, normal + enlarged font) remains open per the spec status note and is outside this audit's surfaces.

## Verdict

**REQUEST_CHANGES** — solely for Finding 1 (broken blind-adjudication seal and the contradicting header statement in `docs/assessment/evidence/issue-324-owner-blind-packet.md`). The substantive deliverable is sound and independently verified: oracle PASS and 9/9 self-tests at the audited head; diff hygiene clean; repo contract clean outside the gitignored local-only `refocus-drafts/`; zero resource names removed or renamed; placeholder/plural contracts preserved across all 110 modified strings, 9 modified plurals, and the 3 net-new resources; 123/123 accepted REVISE rows reflected; 10/10 meaning spot-reads show no safety/semantic loss with documented glossary rationale for the two terminology shifts; and the oracle change relaxes exactly the documented D6 (i)/(iii) classes and scopes empty-structure checks to Nunu-owned names, confirmed by adversarial re-run of the pre-change oracle. Once the packet redaction is fixed (a docs-only change re-verifiable by inspection), this audit's remaining surfaces stand as approved.

## Re-audit — seal re-verification (2026-10-15, head `1595397566`)

Scope limited to Finding 1 (packet seal), per instruction. Diff `bef433290d…`→`1595397566` touches only `docs/assessment/evidence/issue-324-bakeoff.md` (2 lines) and `issue-324-owner-blind-packet.md` (22 lines), so every other surface verified above carries over unchanged.

| Check | Result |
|---|---|
| (a) Candidate headings carry only the then-sealed labels | Pass — both section headings used only the then-sealed labels and the inner heading was `# Candidate [sealed] review (identity withheld)`; zero α/β (and alpha/beta) occurrences in the packet |
| (b) Identity strings | Partial — the enumerated list (openai/gpt/deepseek/gemini/glm/codex/zcode/anthropic/α/β, case-insensitive) has zero hits; P's footer is fully redacted (`OpenAI`, `GPT系候補は採点可能だが採用対象外` → `[sealed]`). **Residual: packet line 586 (Q footer) still publishes ``provider `zai-offpeak-idle-plan` ``** — the only other checked-in occurrences of that exact account string are bakeoff lines 11/15, both tied to `GLM-5.3`. The same line retains "[identity redacted] is the implementation worker's model family … this candidate can be scored but cannot be adopted", and the implementation worker's family is published as GLM-5.3 at bakeoff line 11 and bakeoff-context line 6. Q's vendor account, model family, and family-barred adoption status therefore remain derivable from checked-in files — this was item 3 of Finding 1's fix list and was not applied. |
| (c) sealed-id→model correspondence in checked-in files | Pass — repository-wide grep for the then-sealed labels and alternative phrasings outside the packet: no hits; the labels were not mapped to α/β/C1/C2 or any model anywhere; the id→model mapping was not reconstructible from checked-in files at that head. The bakeoff α/β provisional table remains by design (explicitly superseded for owner scoring); git history still contains the pre-redaction packet, which bakeoff line 39 expressly excludes from reconstruction before scoring. |
| (d) Packet vs bakeoff wording | Pass at the header level — packet line 4 now states the id→model mapping "is held outside the repository (implementing agent's hand-off)", matching bakeoff line 39; the old "recorded in issue-324-bakeoff.md and must be consulted" contradiction is gone. However, line 4's new claim "no model name, vendor, or adapter correspondence for P or Q is published anywhere in this repository" is falsified by line 586 itself (the surviving `zai-offpeak-idle-plan` vendor/provider string). |

Non-blocking note: the redaction pass also replaced `ChatGPT等`/`ChatGPTなど` with `Chat[sealed]` in unit-12 rows (both sections). There "ChatGPT" denoted the reviewed product copy's example external AI app, not a candidate identity, so those rows are no longer verbatim candidate outputs — defensible under the line-4 blanket policy, but the owner scoring the packet should know unit-12 rows are policy-redacted.

### Updated verdict

**REQUEST_CHANGES (narrowed).** The exact-model seal break of Finding 1 is fixed: the α/β section title, the OpenAI/GPT vendor strings in P's footer, and the contradicting header statement are gone, and checks (a), (c), (d) pass. One prescribed redaction was not applied: `zai-offpeak-idle-plan` at packet line 586. Joined with bakeoff lines 11/15 (same account string = `GLM-5.3`) and bakeoff-context line 6, it still publishes a vendor/family correspondence for Q and pre-announces Q's family-barred adoption status — an adjudication-relevant property that per bakeoff line 39 is meant to be applied only after unsealing — and it contradicts the packet's own line-4 contract. Fix remains a one-line, docs-only redaction (`zai-offpeak-idle-plan` → `[identity redacted]`), re-verifiable by inspection, before owner scoring is performed. All other surfaces stand as approved from the original audit.

## Final seal verification (final receipt round, 2026-10-15, head `3bb99f0f86a26dc1d4ea8611e8783ace8d9ac8a9`)

Scope: the three round-4 fixes (owner-blind packet seal, full-pass table schema, semantic guard record). Diff `1595397566…HEAD` touches only evidence files plus this record, spec checkboxes, and the oracle's array comparator + regression tests; resource files unchanged, so the §1–§4 surfaces verified above carry over.

| Check | Result |
|---|---|
| 1. Packet seal (`issue-324-owner-blind-packet.md`) | **Pass.** Case-insensitive grep for openai/gpt/deepseek/gemini/glm/codex/zcode/anthropic/α/β/adopt over the packet: zero identity hits (the only word-boundary matches are "adoption ruling"/"Family eligibility" in the scoring-instruction lines 5/586, which direct the owner to *apply* identity at unseal and reveal none). Footers: P line 158 `実行情報: [sealed]／[identity redacted]、[identity redacted]…`; Q line 586 `provider [identity redacted]`; model ` [identity redacted]`; the prior `zai-offpeak-idle-plan` string is gone; family eligibility is explicitly "deliberately NOT stated here" (line 586) and P's "family rule上、[sealed]" carries no family name. Only the then-sealed labels, scoring instructions, `[sealed]`/`[identity redacted]` placeholders remain. |
| 1b. sealed-id→model mapping elsewhere in checked-in files | **Pass.** `git grep` for the then-sealed labels / sealed id phrasings outside the packet (excluding the prior audit-record findings text) yields no correspondence; bakeoff line 39 restates the mapping is not checked in. Residual (non-blocking): this audit record's own Finding-1 text (rounds 1/2) contained derivation chains to the provisional α/β table, and git history contains pre-reseal packet states — both expressly excluded from reconstruction before scoring by the bakeoff record; the historical derivation text was redacted in the round-5 docs pass, and the packet was resealed under fresh ids that never appeared in any published text. |
| 2. Full-pass table (`issue-324-fullpass-table.md`) | **Pass.** Data rows = 626, 1:1 with dispositions: OK 494 / REVISE 123 / PRODUCT_DECISION 4 / TECHNICAL_ONLY 5 (sum 626); no duplicate names (carried from §5). All 626 `eight_class` values are within the eight AC-324-09 canonical classes, with the `"eight_class"` header row counted: primary action / screen title 116, warning / confirmation / failure / stale / recovery 164, Organizer strategy / preview / apply flow 103, placement lock / category override / settings 147, onboarding / entry guidance 26, progress / result / empty state 35, diagnostics / support entry points 34, accessibility text 1. Header line 5 documents `ui_role`/`user_class`/`surface`/`eight_class`/`layout_risk` as "deterministic post-processing derivations … not reviewer output (per review round 4)". |
| 3. Semantic guard (`issue-324-semantic-guard.tsv`) | **Pass.** 123 data rows (124 lines minus header; leading `#` comment lines are provenance). Name set is an exact 1:1 match (sorted diff = empty) with the 123 REVISE names of the full-pass table. All verdicts are ACCEPT (123/123). Header provenance includes agent id `agent_6255b7b7-…`, session `sess_28f8ae8c-…`, date 2026-10-15, model family GLM with the explicit unknown-effective-model explanation; `behavior_authority` column exists and is non-empty on all 123 rows (0 empty). |

Note on independence: the guard record's GLM family combined with this and prior records that publish the reviewer/ChatGPT families (DeepSeek/GPT) does NOT seal-imply any candidate — the packet contains no correspondence, and no checked-in file maps the sealed ids to any model/family (check 1b).

AC-324-13 (on-device rendered evidence, normal + enlarged font) remains open and owner-gated; per the final verification round it is treated as a non-blocking follow-up, not a defect.

### Final verdict

**APPROVE** — all three round-4 fixes verified at head `3bb99f0f86a26dc1d4ea8611e8783ace8d9ac8a9`; the packet seal holds, the table is schema-complete at 626/1:1 with the canonical 8 classes and documented post-processing, and the guard record is complete and provenance-bearing. Non-blocking follow-ups: (1) optionally strip the historical Finding-1 identity derivation from this record's earlier rounds; (2) AC-324-13 on-device rendered verification remains an owner gate outside this audit's surfaces.

## Reseal verification (round 5, 2026-10-15, head `374874855615783e854c4b93b5c48e797f766e22`)

Scope: the round-5 reseal only (fresh random ids on the owner-blind packet). Diff since `af2a2c8b40`/`3bb99f0f86…` touches only evidence files and this record; the lifecycle note below is superseded by this head.

| Check | Result |
|---|---|
| 1. New labels and mapping leakage | **Pass.** The packet's two candidate section headings (lines 8 and 163) use only `candidate-2FB9` and `candidate-E34A`. `git grep -in "E34A"` / `"2FB9"` over all checked-in files: the only text occurrences are inside the packet itself (lines 4/8/163); the sole other matches are character-level byte coincidences in binary `prebuilts/libs/framework-14.jar`/`framework-16.jar` (no correspondence). No checked-in file maps candidate-E34A or candidate-2FB9 to any model, vendor, family, or α/β/P/Q/C1/C2. |
| 2. Historical finding text identity-neutrality | **Pass with a non-blocking residual.** Round-1 Finding items 1–2 carry the "specific derivation redacted per the round-5 review" notes; the record contains no reference to candidate-E34A/2FB9. Residual (non-blocking): the round-2 historical text (Re-audit rows (b)/updated-verdict) still derives the *former* id Q's vendor/family via `zai-offpeak-idle-plan` = GLM-5.3; that id was retired by the reseal and its pre-reseal packet is already in git history, so the live blind scoring (on candidate-E34A/2FB9) is not affected. |
| 3. Packet completeness after resealing | **Pass.** 593-line packet retains both full candidate outputs (`## Candidate candidate-2FB9 output`, 12 units; `## Candidate candidate-E34A output`, 24 field-table units) and the intact scoring instructions (line 5: 24 units of `issue-324-bakeoff-context.md`, 8 axes, 0–2 scale, accuracy/safety hard-failure rule, tie-break, record + unseal-at-time instruction). Reseal diff vs prior head is structurally label/footer redaction only; both outputs' bodies are unchanged. |

### Reseal verdict

**PASS (final).** At head `374874855615783e854c4b93b5c48e797f766e22` the resealed packet satisfies the blind-adjudication seal: fresh ids appear only in the packet with no correspondence published anywhere, historical derivation text is identity-neutral for the live ids, and the two candidate outputs plus scoring instructions are complete. Prior audit surfaces carry over unchanged.

## Head correction (baseline-only commit)

The verification above ran on the working tree that became commit `af2a2c8b40` (the round-4 fix commit). Final audited head superseded by the reseal head `374874855615783e854c4b93b5c48e797f766e22` above.
