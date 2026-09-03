# Assessment: Issue #199 — UX visual review architecture decision

> Status: proposed; research and Codex negative-path validation complete, calibration incomplete
> Date: 2026-09-03
> Issue: [#199](https://github.com/nunu1733/NunuLauncher/issues/199)

## Decision

Use `.agents/skills/ux-visual-review/` as the only authoritative evaluation contract. It owns activation boundaries, visual-evidence policy, the UX rubric, Observer/Critic responsibilities, severity/confidence semantics, deterministic-test boundaries, and report schemas.

Runtime integrations are adapters:

- Codex: project-scoped `.codex/agents/ux-observer.toml` and `ux-critic.toml`, read-only and model-unpinned.
- ZCode: plugin-provided Observer/Critic is the intended team-distribution adapter, with `injectAgentsMd: false` for Observer and a verified image-capable model. Do not commit that adapter until a real ZCode client proves a no-copy link to the shared Skill and the required read-only skill tool surface.

The Observer receives only a minimal user scenario and ordered visual evidence. The Critic receives the same evidence plus the Observer report and user/product goal. Neither role performs implementation, functional/spec review, or deterministic accessibility measurement.

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
| Codex 0.151.0 | Official repo discovery at `.agents/skills` | Official project `.codex/agents/*.toml`; static TOML validation passed | Role instruction plus fresh/no-history invocation; no documented TOML equivalent of `injectAgentsMd: false` | Invocation selects a vision-capable model; adapter intentionally unpinned | Observer discovery and no-image negative path runtime-validated; custom-agent image path pending |
| ZCode | Official Skill import by symlink/copy; plugin skill layout documented | Plugin agent distribution documented; concrete repository adapter withheld | `injectAgentsMd: false` is documented for custom subagents | Adapter/operator must select a model/provider/protocol classified image-capable | Design-supported / runtime-unvalidated |

Detailed evidence:

- [Codex runtime support](issue-199-codex-runtime-support.md)
- [ZCode / Agent Skills portability](issue-199-zcode-agent-skills-runtime-portability.md)

## Pilot calibration result

The Codex two-stage pilot used separate no-history `gpt-5.6-luna` / `xhigh` subagents and a five-frame branched Organizer flow. It reproduced the human baseline's central pre-apply trust finding and correctly deferred deterministic checks. Several additional findings require human adjudication, and repeatability was not measured.

See [pilot calibration](issue-199-ux-visual-review-calibration.md).

## Adoption level

Current recommendation: **advisory only, experimental invocation**.

Do not yet add a required review step to `AGENTS.md` or `docs/engineering/quality-strategy.md`, and do not add a merge gate. Promotion requires:

1. 5–10 independently baselined cases, including clean negative cases and at least one repeated run;
2. a Codex custom-agent image-delivery test and a recorded write-denial probe;
3. a real ZCode plugin/import test satisfying the six evidence items in the ZCode assessment;
4. human adjudication of high-confidence major findings and recorded false-positive/negative behavior.

## Residual risks

- Codex adapter configuration does not itself prove that all implementation context was excluded.
- ZCode no-copy plugin-to-repository Skill loading is not yet documented or observed.
- Model/provider image capability and output stability can change independently of the shared contract.
- Aesthetic preference can still be mislabeled as a finding; clean negative cases are required to measure this.
- Screenshot evidence can hide off-screen, motion, focus, and interaction problems.

## Follow-up work to split

Create follow-up Issues rather than expanding the final implementation PR if these remain open after owner review:

- ZCode real-client plugin/import/isolation validation on a named version and model/provider.
- Calibration corpus completion and blind human adjudication.
- Product triage for pilot-only findings not already owned by #194/#195, including terminal-summary provenance and English singular/plural copy.
- Repository process adoption after calibration; this follow-up would own any `AGENTS.md` / `quality-strategy.md` change and enforcement decision.
