# Assessment: Issue #199 — UX visual review architecture decision

> Status: accepted; Codex/ZCode runtime evidence and five-case calibration complete; advisory experimental use only
> Date: 2026-09-03
> Issue: [#199](https://github.com/nunu1733/NunuLauncher/issues/199)

## Decision

Use `.agents/skills/ux-visual-review/` as the only authoritative evaluation contract. It owns activation boundaries, visual-evidence policy, the UX rubric, Observer/Critic responsibilities, severity/confidence semantics, deterministic-test boundaries, and report schemas.

Runtime integrations are adapters:

- Codex: project-scoped `.codex/agents/ux-observer.toml` and `ux-critic.toml`, read-only and model-unpinned.
- ZCode: plugin-provided Observer/Critic is the intended team-distribution adapter, with `injectAgentsMd: false` for Observer and a verified image-capable model. ZCode's bundled CLI directly discovers the repository Skill at `.agents/skills/ux-visual-review`. The recorded temporary custom-role run validated the role flow and exposed read-only tools, but its outer dispatcher had to attest runtime provenance; no permanent role adapter is committed.

The Observer receives only a neutral user goal and an Observer-visible manifest containing neutral frame IDs, branch/order relations, preceding user actions, and named images. Expected states, risk, intended actions, known defects, AC/spec context, and the human baseline remain in a separate hidden layer. The Critic receives the same evidence plus the Observer report and only the product/risk context needed to judge impact. Neither role performs implementation, functional/spec review, or deterministic accessibility measurement.

This is an accepted process decision, not an ADR: the runtime boundary remains Beta/evolving and inexpensive to reverse. The completed non-blind adjudication is not equivalent to independent blind validation and remains a calibration limitation. If later adoption makes the adapter boundary expensive to reverse and alternatives remain material, reassess the ADR criteria then.

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

The corrected Codex two-stage pilot used neutral filenames and a manifest with no expected-state/risk labels. Project-scoped `ux_observer` and `ux_critic` ran with `gpt-5.6-luna` / `xhigh` under `read-only`, reproduced the human baseline's central pre-apply trust finding, and correctly disclosed limited isolation. Four additional pre-baselined cases bring the corpus to five; one clean case was repeated. The repeat preserved two semantic findings with one-level severity drift. Non-blind human adjudication disclosed prior exposure and left all 13 extras as `needs-evidence`; none is counted as a true or false positive. The known dark-theme root cause was missed.

See [pilot calibration](issue-199-ux-visual-review-calibration.md).

## Adoption level

Current recommendation: **advisory only, experimental invocation**.

Do not add a required review step to `AGENTS.md` or `docs/engineering/quality-strategy.md`, and do not add a merge gate. Future promotion beyond experimental advisory use should consider:

1. additional evidence sufficient to accept or reject the 13 unresolved extras, preferably with independent blind adjudication;
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

The approved AC-08 scope keeps human adjudication while making blind review
preferred rather than mandatory. The completed non-blind record discloses prior
model-output and baseline-comparison exposure and leaves all 13 extras as
`needs-evidence`; this is a valid unresolved disposition, not independent blind
validation or a false-positive-rate estimate.

No remaining item blocks Issue #199 closure. Additional stateful cases,
independent blind validation, product triage for unresolved extras, or later
repository process enforcement require new scope rather than silently extending
this research Issue.
