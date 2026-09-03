# Assessment: Issue #199 — UX visual review architecture decision

> Status: proposed; Codex E2E, five-case calibration, and recorded ZCode runtime E2E executed; human adjudication remains incomplete (blind preferred when an independent reviewer is available)
> Date: 2026-09-03
> Issue: [#199](https://github.com/nunu1733/NunuLauncher/issues/199)

## Decision

Use `.agents/skills/ux-visual-review/` as the only authoritative evaluation contract. It owns activation boundaries, visual-evidence policy, the UX rubric, Observer/Critic responsibilities, severity/confidence semantics, deterministic-test boundaries, and report schemas.

Runtime integrations are adapters:

- Codex: project-scoped `.codex/agents/ux-observer.toml` and `ux-critic.toml`, read-only and model-unpinned.
- ZCode: plugin-provided Observer/Critic is the intended team-distribution adapter, with `injectAgentsMd: false` for Observer and a verified image-capable model. ZCode's bundled CLI directly discovers the repository Skill at `.agents/skills/ux-visual-review`. The recorded temporary custom-role run validated the role flow and exposed read-only tools, but its outer dispatcher had to attest runtime provenance; no permanent role adapter is committed.

The Observer receives only a neutral user goal and an Observer-visible manifest containing neutral frame IDs, branch/order relations, preceding user actions, and named images. Expected states, risk, intended actions, known defects, AC/spec context, and the human baseline remain in a separate hidden layer. The Critic receives the same evidence plus the Observer report and only the product/risk context needed to judge impact. Neither role performs implementation, functional/spec review, or deterministic accessibility measurement.

This is a proposed process decision, not an ADR: the runtime boundary remains Beta/evolving and adoption evidence (especially human adjudication and its exposure record) is incomplete. Blind adjudication is preferred when an independent reviewer is available, but a non-blind result is not equivalent to independent blind validation and remains a calibration limitation. If later adoption makes the adapter boundary expensive to reverse and alternatives remain material, reassess the ADR criteria then.

## Why this boundary

| Option | Result |
|---|---|
| Duplicate complete prompts in Codex and ZCode agent files | Rejected: rubric, evidence, and report semantics would drift. |
| One shared Skill with thin runtime role adapters | Selected: both documented runtimes can consume Agent Skills-compatible content while keeping model/context/tool mechanics local. |
| One agent observes and critiques with full implementation context | Rejected: expectation leakage defeats independent first-impression evidence. |
| Vision owns measurable accessibility/functional checks | Rejected: screenshots cannot authoritatively establish numeric or semantic behavior. |
| Immediate merge gate | Rejected: the pilot does not establish reproducibility or false-positive/negative rates. |

## Support level

| Runtime | Skill reuse | Role adapter | Context isolation | Vision guarantee | Current level |
|---|---|---|---|---|---|
| Codex 0.151.0 | Official repo discovery at `.agents/skills` | Official project `.codex/agents/*.toml`; static TOML validation passed | No documented TOML equivalent of `injectAgentsMd: false`; hidden workspace files remained technically readable and reports correctly mark `limited` | Explicit Luna/xhigh invocation received all named images | Observer/Critic image E2E, write denial, and screenshot-instruction non-obedience runtime-validated; limited isolation |
| ZCode 3.10.2 / bundled CLI 0.16.5 | Runtime enumeration directly discovered the exact repository `.agents/skills/ux-visual-review` path; post-restart custom Observer/Critic activation completed | Temporary user-level custom Observer/Critic used for the recorded run; plugin distribution remains the intended team adapter | `injectAgentsMd: false` was configured; the probe observed no repository instructions or 40-character baseline SHA in initial context, which is behavior-consistent but not an independent enforcement trace; report marks `limited` | `BAI/glm-5.3-flash` grounded case 06 with 6/6 prominent anchors and the Critic verified the transcription | Observer/Critic image E2E, missing-image negative fixture, screenshot-instruction non-obedience, exact contract digest, and write/shell tool-surface unavailability validated; limited isolation and outer provenance attestation |

Detailed evidence:

- [Codex runtime support](issue-199-codex-runtime-support.md)
- [ZCode / Agent Skills portability](issue-199-zcode-agent-skills-runtime-portability.md)

## Pilot calibration result

The corrected Codex two-stage pilot used neutral filenames and a manifest with no expected-state/risk labels. Project-scoped `ux_observer` and `ux_critic` ran with `gpt-5.6-luna` / `xhigh` under `read-only`, reproduced the human baseline's central pre-apply trust finding, and correctly disclosed limited isolation. Four additional pre-baselined cases bring the corpus to five; one clean case was repeated. The repeat preserved two semantic findings with one-level severity drift. Several extras still require human adjudication; blind is preferred when an independent reviewer is available, and any prior model-output or baseline-comparison exposure must be recorded. The known dark-theme root cause was missed.

See [pilot calibration](issue-199-ux-visual-review-calibration.md).

## Adoption level

Current recommendation: **advisory only, experimental invocation**.

Do not yet add a required review step to `AGENTS.md` or `docs/engineering/quality-strategy.md`, and do not add a merge gate. Promotion requires:

1. human adjudication of the extra findings from the five-case corpus, with prior model-output or baseline-comparison exposure recorded. Blind adjudication is preferred when an independent reviewer is available, but is not required; non-blind results remain a calibration limitation and are not treated as equivalent to independent blind validation;
2. at least one more stateful case with a concrete change list, since three added cases are single-frame entry/settings surfaces;
3. an owner decision on advisory adoption after reviewing the recorded false-negative, severity-drift, and ZCode runtime limitations.

## Residual risks

- Codex adapter configuration does not itself prove that all implementation context was excluded.
- ZCode's recorded case-06 Observer grounded all six prominent anchors and its Critic verified the visual claims; the inner reports still lack complete runtime provenance, so the outer dispatcher attestation is part of the evidence. `injectAgentsMd: false` is behavior-consistent but has no independent input-context trace, and repository-scoped Read leaves isolation `limited`.
- ZCode's write/shell checks were unavailable because those tools were absent from the custom-role surface; this is not a platform permission-error denial proof. The screenshot URL/path non-obedience probe did pass and both marker files remained absent.
- Model/provider image capability and output stability can change independently of the shared contract.
- Aesthetic preference can still be mislabeled as a finding; clean negative cases are required to measure this.
- Screenshot evidence can hide off-screen, motion, focus, and interaction problems.

## Remaining Issue #199 work

The recorded ZCode runtime evidence completes AC-07 for the tested
version/configuration: grounded case-06 Observer/Critic, case-07 missing-image
refusal, screenshot-instruction non-obedience, exact contract digest, and absent
write/shell tools are retained with explicit dispatcher provenance attestation.
Universal plugin-level isolation or platform denial is not claimed.

The approved AC-08 scope keeps human adjudication in #199 while making blind
review preferred rather than mandatory. Any non-blind adjudication must record
prior model-output or baseline-comparison exposure and is not equivalent to
independent blind validation. The retained queue currently has no completed
human dispositions, so #199 still owns:

- Calibration human adjudication and exposure recording for the extra findings, plus one additional stateful case if the owner considers the current entry/settings-heavy corpus insufficiently representative.
- Product triage for pilot-only findings not already owned by #194/#195, including terminal-summary provenance and English singular/plural copy.
- Repository process adoption after calibration; this follow-up would own any `AGENTS.md` / `quality-strategy.md` change and enforcement decision.
