# Output schema

One row per review unit, machine-readable (TSV or Markdown table), in this order:

```text
resource_name | disposition | ui_role | user_class | surface
current (ja, and default when revised) | proposed | reason | severity
meaning_preserved (yes/no/why, mandatory for safety-class) | layout_risk
rule (style-guide section / glossary term / rubric axis / ai-register tell id)
```

Rules:

- Exactly one disposition per unit; `OK` and `TECHNICAL_ONLY` rows may omit `proposed`.
- `reason` explains briefly why the change makes the copy better for a human reading the screen — not merely "more natural".
- `layout_risk` flags longer proposals on constrained surfaces (buttons, list rows, dialogs) for the rendered check at normal + enlarged font scale.
- `PRODUCT_DECISION` rows state what decision is needed and by whom (owner resolution / split issue).
- Run-level footer records: base SHA of the audited tree, inventory regeneration command, reviewer runtime/model/provider/date/session id, batches covered, counts per disposition, and any explicit skip reasons (reviewer unavailability is a skip reason, never a silent pass).
