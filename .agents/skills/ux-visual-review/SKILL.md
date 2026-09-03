---
name: ux-visual-review
description: Review screenshots or ordered UI-state evidence for qualitative UX risks using separated observer and critic roles. Use for user-visible UI review, first-impression critique, or UX calibration; do not use as a substitute for functional, accessibility, contrast, touch-target, clipping, or spec-compliance tests.
---

# UX Visual Review

Produce evidence-bound, advisory UX findings without turning taste into a defect or claiming that deterministic checks passed.

## Select a mode

- For an ordinary review, run the Observer stage and then the Critic stage.
- When acting as the Observer, read [evidence-policy.md](references/evidence-policy.md), [observer-agent.md](references/observer-agent.md), and [report-schema.md](references/report-schema.md). Do not read the implementation issue, spec, plan, source code, intended hierarchy, or prior critique.
- When acting as the Critic, read [evidence-policy.md](references/evidence-policy.md), [rubric.md](references/rubric.md), [critic-agent.md](references/critic-agent.md), and [report-schema.md](references/report-schema.md).
- For calibration, also compare the resulting findings with an independently recorded human baseline. Record agreement, false-positive candidates, missed baseline findings, severity drift, and evidence limitations. Do not infer a merge policy from one run.

## Workflow

1. Check that the supplied scenario and visual evidence satisfy the evidence policy. Return `insufficient-evidence` when they do not; never turn missing evidence into a pass.
2. Give the Observer only the minimum user scenario and the ordered visual evidence. Prefer an isolated subagent or session. If isolation is unavailable, disclose which implementation context the Observer could see.
3. Give the Critic the same evidence, the Observer report, and the user or product goal. The Critic applies the shared rubric and emits the canonical report.
4. Keep the result advisory. A separate human or repository policy decides disposition. Do not edit product code as part of this review.

Use a vision-capable model selected by the runtime. Do not pin a vendor or model version in this shared contract.

## Boundaries

- No single aggregate UX score.
- No numeric guesses for contrast, target size, clipping, font scaling, focus order, or functional state transitions.
- A visible symptom may be reported, but its deterministic verification must be deferred to the owning test surface.
- Each finding must keep observation, likely user impact, and recommendation separate and cite specific evidence IDs.
