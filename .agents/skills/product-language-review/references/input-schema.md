# Review unit input schema

Adapted from `docs/localization/ja-review-workflow.md` §3 (canon). A review pass is over units, never over isolated XML values.

```text
resource_name:
default_text:
current_ja:
surface:            # screen / flow where the string renders
ui_role:            # title | action | menu | summary | dialog | warning | error | progress | result | toast | a11y | diagnostic
user_class:         # normal | safety-critical | accessibility | diagnostic
neighboring_copy:   # other visible strings on the same surface
placeholder_contract: # count/types/order, plural categories, translatable
example_rendered_value:
behavior_or_spec_context: # what the user did / what happens / accepted spec or ADR that fixes the meaning
aosp_or_lawnchair_reference: # compatible upstream wording for the same concept, when one exists
screenshot_or_rendered_context: # optional
```

Rules:

- Fields that cannot be filled are recorded as `unknown`, never left blank and never guessed.
- A unit missing surface, ui_role, or placeholder contract, or missing behavior/safety context when `user_class` is safety-critical, cannot be revised: classify it `PRODUCT_DECISION`.
- Both default English and current Japanese are part of the unit. When the Japanese reads poorly because the default English is developer-oriented or awkward, the unit covers both languages so they can be fixed consistently.
- Upstream Lawnchair/AOSP general wording is in scope only when it is Nunu-specific or directly wired into a Nunu flow; upstream wording is otherwise reference material, not a rewrite target.
