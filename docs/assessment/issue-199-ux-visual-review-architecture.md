# Assessment: Issue #199 — UX visual review architecture decision

> Status: proposed; Codex E2E and five-case calibration executed, human/ZCode validation incomplete
> Date: 2026-09-03
> Issue: [#199](https://github.com/nunu1733/NunuLauncher/issues/199)

## Decision

Use `.agents/skills/ux-visual-review/` as the only authoritative evaluation contract. It owns activation boundaries, visual-evidence policy, the UX rubric, Observer/Critic responsibilities, severity/confidence semantics, deterministic-test boundaries, and report schemas.

Runtime integrations are adapters:

- Codex: project-scoped `.codex/agents/ux-observer.toml` and `ux-critic.toml`, read-only and model-unpinned.
- ZCode: plugin-provided Observer/Critic is the intended team-distribution adapter, with `injectAgentsMd: false` for Observer and a verified image-capable model. ZCode's bundled CLI directly discovers the repository Skill at `.agents/skills/ux-visual-review`; do not commit a permanent role adapter until custom-role activation and the required read-only tool surface are runtime-validated.

The Observer receives only a neutral user goal and an Observer-visible manifest containing neutral frame IDs, branch/order relations, preceding user actions, and named images. Expected states, risk, intended actions, known defects, AC/spec context, and the human baseline remain in a separate hidden layer. The Critic receives the same evidence plus the Observer report and only the product/risk context needed to judge impact. Neither role performs implementation, functional/spec review, or deterministic accessibility measurement.

This is a proposed process decision, not an ADR: the runtime boundary remains Beta/evolving and the required cross-runtime evidence is incomplete. If later adoption makes the adapter boundary expensive to reverse and alternatives remain material, reassess the ADR criteria then.

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
| Codex 0.151.0 | Official repo discovery at `.agents/skills` | Official project `.codex/agents/*.toml`; static TOML validation passed | No documented TOML equivalent of `injectAgentsMd: false`; hidden workspace files remained technically readable and reports correctly mark `limited` | Explicit Luna/xhigh invocation received all named images | Observer/Critic image E2E and write denial runtime-validated; limited isolation |
| ZCode 3.10.2 / bundled CLI 0.16.5 | Runtime enumeration directly discovered the exact repository `.agents/skills/ux-visual-review` path without a copy | Plugin agent distribution documented; concrete role adapter withheld | `injectAgentsMd: false` is documented for custom subagents | Installed client currently exposes `BAI/glm-5.3-flash`; image delivery has not been validated | Shared Skill discovery validated; adapter and image E2E unvalidated |

Detailed evidence:

- [Codex runtime support](issue-199-codex-runtime-support.md)
- [ZCode / Agent Skills portability](issue-199-zcode-agent-skills-runtime-portability.md)

## Pilot calibration result

The corrected Codex two-stage pilot used neutral filenames and a manifest with no expected-state/risk labels. Project-scoped `ux_observer` and `ux_critic` ran with `gpt-5.6-luna` / `xhigh` under `read-only`, reproduced the human baseline's central pre-apply trust finding, and correctly disclosed limited isolation. Four additional pre-baselined cases bring the corpus to five; one clean case was repeated. The repeat preserved two semantic findings with one-level severity drift. Several extras still require blind human adjudication, and the known dark-theme root cause was missed.

See [pilot calibration](issue-199-ux-visual-review-calibration.md).

## Adoption level

Current recommendation: **advisory only, experimental invocation**.

Do not yet add a required review step to `AGENTS.md` or `docs/engineering/quality-strategy.md`, and do not add a merge gate. Promotion requires:

1. blind human adjudication of the extra findings from the five-case corpus;
2. at least one more stateful case with a concrete change list, since three added cases are single-frame entry/settings surfaces;
3. a real ZCode custom/plugin role test satisfying the remaining evidence items in the ZCode assessment;
4. an owner decision on advisory adoption after reviewing the recorded false-negative and severity-drift behavior.

## Residual risks

- Codex adapter configuration does not itself prove that all implementation context was excluded.
- ZCode project Skill discovery is observed, but activation from an isolated custom/plugin subagent is not yet proved.
- Model/provider image capability and output stability can change independently of the shared contract.
- Aesthetic preference can still be mislabeled as a finding; clean negative cases are required to measure this.
- Screenshot evidence can hide off-screen, motion, focus, and interaction problems.

## Remaining Issue #199 work

Do not close #199 or move its current AC-07/AC-08 scope to follow-up Issues without first changing and approving the spec scope. Under the current spec, #199 still owns:

- ZCode real-client custom/plugin role, image-delivery, negative-fixture, and isolation validation on a named version and model/provider.
- Calibration blind human adjudication and one additional stateful case if the owner considers the current entry/settings-heavy corpus insufficiently representative.
- Product triage for pilot-only findings not already owned by #194/#195, including terminal-summary provenance and English singular/plural copy.
- Repository process adoption after calibration; this follow-up would own any `AGENTS.md` / `quality-strategy.md` change and enforcement decision.
