# Calibration and adjudication

Freeze the case manifest, human baseline, and these rules before running the Observer or Critic. The human baseline author must not see model output before the baseline is recorded. Keep the baseline and known defects out of both model contexts until their reports are final. Human adjudication may be blind or non-blind, but the adjudicator's prior exposure to model output or baseline comparison must be recorded.

## Unit of comparison

Compare findings by semantic root cause and materially affected user outcome, not wording.

- `match`: root cause and user impact are materially the same. Rubric-category disagreement does not prevent a match, but record it as category drift.
- `partial-match`: the model detects the same visible symptom but misses or materially changes the root cause or impact.
- `miss`: a human-baseline finding of `moderate` or higher has no match or partial match in the model report.
- `extra`: a model finding has no human-baseline counterpart.

## Severity and confidence

Map severity in order: `polish`, `minor`, `moderate`, `major`, `critical`.

- Exact severity is `agreement`.
- A one-level difference is `near-agreement` and must be recorded as severity drift.
- A difference of two or more levels is `disagreement`.
- Confidence is reported separately and never used to erase a semantic miss or extra.

## Extra findings and clean cases

A human adjudicator reviews every extra against the evidence and labels it `accept`, `reject`, or `needs-evidence`. When the adjudication is blind, the adjudicator must not see model rationale beyond the canonical finding fields. Blind adjudication is preferred when an independent reviewer is available, but it is not required for Issue completion. A non-blind adjudication records the adjudicator's prior exposure to model output and/or baseline comparison, is not treated as equivalent to independent blind validation, and remains a calibration limitation.

- `accept` adds a newly discovered human finding.
- `reject` is a false positive.
- `needs-evidence` remains unresolved and is not counted as a false positive or true positive.
- On a pre-baselined clean case, any rejected finding at any severity, including `minor` or `polish`, counts as a false positive. Accepted observations with no credible task impact remain polish opportunities and are not defects.

## Reproducibility

For a repeated case, compare semantic matches, severity, and confidence across runs. Record:

- finding recurrence by semantic root cause;
- severity exact/near/disagreement;
- findings appearing in only one run;
- whether any high-confidence `major` or `critical` conclusion changes.

Do not collapse results into one UX score. Report numerator/denominator counts and unresolved items directly. A calibration summary must state the case count, repeat count, runtime/model combinations, and evidence limitations.
