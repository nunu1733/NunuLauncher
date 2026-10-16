# Review rubric

Axes (aligned with `docs/localization/ja-review-workflow.md` §4–§5; do not contradict them):

| Axis | Question |
|---|---|
| naturalness / fluency | Does this read like Japanese (or English) a normal Android user reads on a screen, per the #161 style guide? |
| brevity without loss of meaning | Can it be shorter without dropping a state, constraint, or condition? (Never shorten by removing warnings or recovery meaning.) |
| CTA predictability | Does the action label tell the user what happens on tap? Object included when the verb alone is ambiguous? |
| terminology | Consistent with `docs/localization/ja-glossary.tsv` and CONTEXT.md domain vocabulary? Unmapped user-facing terms become glossary-addition proposals. |
| implementation leakage | Do implementation/domain terms (strategy ids, rejection codes, module names, plan vocabulary) surface where the user's decision does not need them? Diagnostics surfaces may keep them (`TECHNICAL_ONLY`). |
| redundancy / consistency | Redundant with neighboring copy? Same flow, same tone, same terms? States vs actions not conflated (「使用中」 vs 「使用する」)? |
| a11y read-aloud | Does accessibility text work as a spoken sentence, not a truncated visual label? |
| default English quality | Is the source itself user-facing copy rather than developer notes? |
| AI register (AI 感) | Per [ai-register-tells.md](ai-register-tells.md), at the applicable granularity. |

Severity:

- **high**: safety-class meaning at risk, or primary CTA / screen title that misleads.
- **medium**: user-visible awkwardness or inconsistency on a main flow surface.
- **low**: polish; diagnostics-only surfaces.

Dispositions (exactly one per unit):

- `OK` — current copy fits the context.
- `REVISE` — meaning-preserving improvement available; carry the required output fields.
- `PRODUCT_DECISION` — better wording depends on unresolved UX / IA / domain meaning, or required context is missing, or the fix would change behavior meaning.
- `TECHNICAL_ONLY` — diagnostics/support surface where technical precision is intentional.

`REVISE` proposals must keep resource name, placeholder count/type/order, plural categories, escaping, and `translatable` intact. A proposal that needs to break any of these is `PRODUCT_DECISION`. Safety-class units additionally need an explicit meaning-preservation note (what the warning guaranteed before and after).
