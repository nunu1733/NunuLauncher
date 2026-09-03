# Report schema

The portable contract is `ux-visual-review/v1`. YAML below describes required fields; runtimes may render the same structure as JSON or Markdown if field names and meanings are preserved.

## Canonical envelope

Every retained report starts with enough provenance to reproduce and audit the review. `contract_revision` is the Git commit containing the exact contract or a deterministic content digest when the report and contract are committed together.

```yaml
contract: ux-visual-review/v1
report_type: observer | critic
status: observed | reviewed | insufficient-evidence
report_id: <stable local identifier>
provenance:
  contract_revision: <git SHA or content digest>
  subject:
    revision: <app/source commit>
    build_ref: <CI run, build, or artifact reference>
  runtime:
    client: codex | zcode | other
    client_version: <version>
    model: <effective model ID>
    reasoning: <effective reasoning configuration>
    permission_mode: <effective sandbox or permission mode>
    tool_surface:
      file_read: allowed | denied | unknown
      file_write: allowed | denied | unknown
      shell: allowed | read-only | denied | unknown
      network: allowed | denied | unknown
      external_side_effects: allowed | denied | unknown
    tools_used: [<subset actually invoked>]
  evidence_manifest_ref: <retained manifest or immutable reference>
```

Use `unknown` rather than omitting unavailable provenance. Never place secrets, credentials, or private endpoint details in the envelope.

## Observer report

```yaml
contract: ux-visual-review/v1
report_type: observer
status: observed | insufficient-evidence
report_id: <stable local identifier>
provenance: <canonical envelope provenance>
scenario: <neutral user goal>
isolation:
  mode: isolated | limited
  limitations: []
evidence:
  - id: frame-01
    apparent_state: <state inferred from the pixels, not copied from the input manifest>
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
contract: ux-visual-review/v1
report_type: critic
status: reviewed | insufficient-evidence
report_id: <stable local identifier>
provenance: <canonical envelope provenance>
scenario: <user or product goal>
evidence_refs: [frame-01, frame-02]
observer_report_ref: <artifact or inline identifier>
critic_context_ref: <artifact, inline identifier, or none>
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
  baseline_ref: <human baseline revealed only after the Critic report is complete>
  agreement: []
  false_positive_candidates: []
  missed_baseline_findings: []
  severity_drift: []
```

For `status: insufficient-evidence`, leave `findings` empty and list the missing capture in `limitations` or `additional_validation`. `reviewed` means the available evidence was reviewed; it does not mean the UI passed.
