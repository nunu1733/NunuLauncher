# Assessment: Issue #199 — UX visual review calibration

> Status: pilot complete; required 5–10 case calibration incomplete
> Date: 2026-09-03
> Runtime: Codex subagents, `gpt-5.6-luna`, reasoning effort `xhigh`
> Contract: `.agents/skills/ux-visual-review` draft

## Pilot outcome

The two-stage workflow produced a perception-only Observer report and a schema-conforming Critic report from a five-frame, branched Organizer scenario. The Critic independently recovered the human baseline's central trust problem: aggregate counts do not let a user judge the concrete home-layout change before applying it. The run also produced plausible additional findings, but one run and one scenario are not enough to estimate reproducibility or false-positive/negative rates. Adoption therefore remains advisory and the Issue #199 calibration acceptance criterion is not complete.

## Evidence manifest

Source: PR #198 CI run [`33707975932`](https://github.com/nunu1733/NunuLauncher/actions/runs/33707975932), artifact `issue52-ui-evidence`, downloaded 2026-09-03. The fixture is synthetic and contains no private device data. Repository-retained copies and SHA-256 values:

| ID | Branch/state | File | SHA-256 |
|---|---|---|---|
| frame-01 | entry | [start](evidence/issue-199/case-01-organizer-flow/start.png) | `503063c5f16e21e622a1aca44478aee9bb5b533c92961c19d1543ae1023d5974` |
| frame-02 | review/decision | [preview](evidence/issue-199/case-01-organizer-flow/preview-confirm.png) | `b51b3357fece4f5184582612fbaee1c9e0127c5bfd0e911b7b5ec6e7ff3d0a2f` |
| frame-03a | stale branch | [stale](evidence/issue-199/case-01-organizer-flow/stale.png) | `3c15d368bdc617be60c4461924b722d9eb1f415fe444ad21dcdbb8f33b2b022f` |
| frame-03b | success branch | [success](evidence/issue-199/case-01-organizer-flow/success.png) | `c53e810e644ed9f387eeaa47d5e19ba71d1a1b948eed9208e1008ef530b64c36` |
| frame-03c | recovery-failure branch | [failure](evidence/issue-199/case-01-organizer-flow/recovery-failure.png) | `5974bd1868f5736cdd618d23044ee84caaa85eecbf273bab8fbb43286838fb95` |

The three terminal frames are alternative branches after frame-02. They were declared as branches rather than misrepresented as one linear run.

## Invocation and isolation

- Observer: fresh subagent with no parent-turn history, model `gpt-5.6-luna`, effort `xhigh`; supplied only the shared Observer references, minimal scenario, evidence manifest, and named images.
- Critic: separate fresh subagent with no parent-turn history, same model/effort; supplied only shared Critic references, product goal, evidence, and Observer report.
- Both agents were instructed not to inspect code, Issues, specs, plans, or prior critique and made no file changes.
- This proves the explicit fresh-spawn path used by the calibration harness, not automatic isolation by `.codex/agents/*.toml`. The Codex adapter limitation remains documented separately.

Artifacts:

- [Observer report](evidence/issue-199/pilot-01-observer.yaml)
- [Critic report](evidence/issue-199/pilot-01-critic.yaml)

## Human-baseline comparison

Baseline: [Issue #192 Organizer concrete-preview investigation](issue-192-organizer-concrete-preview-investigation.md), especially its finding that a count-only preview makes the user approve counts rather than concrete affected items and destinations. PR #198 review is functional/spec/a11y evidence, not a human visual-UX baseline, so it was not treated as one.

| Critic result | Baseline comparison | Calibration disposition |
|---|---|---|
| UVR-001: no concrete item/destination before apply (`major/high`) | Direct agreement with the central #192 finding. | Useful true positive; severity is plausible for a high-impact layout decision. |
| UVR-002: fallback-category language is implementation-like | Not covered by #192. | Unadjudicated extra finding; candidate for human review, not automatically a false positive. |
| UVR-003: uninterrupted counts and singular/plural wording | The density concern is adjacent to #192; grammar was not part of its baseline. | Mixed: grouping concern supports baseline; copy defects need separate localization triage. |
| UVR-004: success does not connect approval to concrete result | Not covered by #192's pre-apply focus. | Plausible scope-expanding finding; requires product review before acceptance. |
| UVR-005: failure summary does not identify its source state | Not covered by #192. | Plausible trust-risk finding with appropriately reduced `medium` confidence. |

## False-negative and evidence limits

- #192 records a residual risk in same-band text descriptions. This pilot could not evaluate it because frame-02 is the explicitly degraded `details unavailable` state. That is a corpus gap, not a demonstrated model miss.
- Still images do not verify touch targets, 200% font scaling, semantics, focus order, traversal, or functional transitions. The Critic correctly deferred those surfaces.
- The Critic did not produce an aggregate score or a pass/fail verdict.
- Reproducibility is unknown because the same case has not yet been repeated with a blind second run.

## Required next calibration cases

Before changing repository process policy, add at least four more independently baselined cases:

1. concrete change-list details with same-band movement and truncation/expansion;
2. known dark-theme onboarding defect versus corrected evidence from Issue #123;
3. dense Japanese 200% evidence with deterministic accessibility results kept separate;
4. a deliberately clean, low-risk settings screen to estimate aesthetic false positives.

Repeat at least one case to measure finding/severity stability. Record adjudication by a human reviewer who did not see the Critic output first.

## Adoption recommendation at pilot stage

Keep the workflow **advisory only**. It is already useful as an additional qualitative surface, but the required 5–10 case calibration, ZCode runtime validation, and Codex project-agent image-delivery/write-denial tests remain open. Fresh-session Codex Observer discovery and the no-image negative path have been validated separately. Do not update `AGENTS.md`, `docs/engineering/quality-strategy.md`, or merge gates based on this pilot alone.
