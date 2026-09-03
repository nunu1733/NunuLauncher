# Report schema

The portable contract is `ux-visual-review/v1`. YAML below describes required fields; runtimes may render the same structure as JSON or Markdown if field names and meanings are preserved.

## Observer report

```yaml
schema: ux-visual-review/observer-v1
status: observed | insufficient-evidence
scenario: <minimal user goal>
isolation:
  mode: isolated | limited
  limitations: []
evidence:
  - id: frame-01
    state: <visible state>
observations:
  - evidence_refs: [frame-01]
    visible: <what is directly visible>
    inference: <optional cautious interpretation>
sequence_changes:
  - from: frame-01
    to: frame-02
    visible_change: <description>
uncertainties: []
missing_evidence: []
```

An insufficient Observer report may omit `observations` and `sequence_changes`, but must name `missing_evidence`.

## Critic report

```yaml
schema: ux-visual-review/v1
status: reviewed | insufficient-evidence
review_id: <stable local identifier>
scenario: <user or product goal>
evidence_refs: [frame-01, frame-02]
observer_report_ref: <artifact or inline identifier>
findings:
  - id: UVR-001
    category: orientation | visual-hierarchy | scanability-density | affordance-action | mental-model-language | feedback-continuity | trust-risk | visual-coherence
    observation: <visible fact, without impact or fix>
    evidence_refs: [frame-02]
    user_impact: <likely effect on the stated goal>
    severity: critical | major | moderate | minor | polish
    confidence: high | medium | low
    recommendation: <desired user-visible outcome>
    additional_validation: []
deterministic_deferrals:
  - surface: <contrast | touch-target | clipping | font-scale | semantics | focus-order | traversal | functional-state | spec-compliance | other>
    visible_symptom: <optional evidence-bound symptom>
    required_check: <authoritative test or measurement>
limitations: []
calibration:
  baseline_ref: <optional human-baseline artifact>
  agreement: []
  false_positive_candidates: []
  missed_baseline_findings: []
  severity_drift: []
```

For `status: insufficient-evidence`, leave `findings` empty and list the missing capture in `limitations` or `additional_validation`. `reviewed` means the available evidence was reviewed; it does not mean the UI passed.
