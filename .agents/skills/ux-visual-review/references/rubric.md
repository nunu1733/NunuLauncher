# UX visual review rubric

Apply every category, but report only evidence-backed findings. Do not reward or penalize a visual style merely for personal preference.

| Category | Review question |
|---|---|
| `orientation` | Can a first-time viewer quickly tell the screen's purpose and current state? |
| `visual-hierarchy` | Does visual emphasis match the importance and urgency of information and actions? |
| `scanability-density` | Are grouping, repetition, line length, density, and scan path manageable for the task? |
| `affordance-action` | Do interactive elements and the primary action look actionable and distinct from static content? |
| `mental-model-language` | Do visible labels, grouping, and information structure fit the user's likely mental model? |
| `feedback-continuity` | Does the sequence make the relationship between action, progress, result, and next step understandable? |
| `trust-risk` | Does a destructive or high-impact decision provide enough visible scope, consequence, reversibility, and warning to act confidently? |
| `visual-coherence` | Do spacing, alignment, component treatment, and state-to-state consistency look intentional and complete? |

## Severity

- `critical`: The visible experience is likely to cause an irreversible or high-impact mistake, or leaves no credible safe path through a high-risk decision.
- `major`: The primary goal or decision is likely to be misunderstood, abandoned, or completed without information most users need.
- `moderate`: Repeated hesitation, re-reading, or avoidable uncertainty is likely, but the goal remains achievable.
- `minor`: A localized clarity or consistency problem causes limited friction.
- `polish`: A visible refinement opportunity has little credible task impact. Keep it clearly separate from defects.

Severity is impact, not visual intensity. Do not raise severity because a design differs from personal taste.

## Confidence

- `high`: The observation is directly visible in adequate evidence, and the user impact follows with little unsupported inference.
- `medium`: The observation is clear, but impact depends on plausible user behavior or an omitted neighboring state.
- `low`: Evidence or context is ambiguous. Prefer an additional-validation request over a strong recommendation.

Confidence is evidence quality, not model certainty. A severe but low-confidence finding remains low confidence.

## Finding threshold

Create a finding only when all are present:

1. a concrete visual observation tied to evidence IDs;
2. a plausible effect on the stated user goal;
3. the matching rubric category;
4. a desired outcome that does not prescribe implementation unnecessarily.

If an issue is purely functional, accessibility-semantic, or numeric, record it under deterministic deferrals rather than as a rubric finding.
