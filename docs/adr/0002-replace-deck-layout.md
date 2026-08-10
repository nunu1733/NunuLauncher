---
status: accepted
---

# Replace Deck layout with the organizer modules

NunuLauncher does not reuse or incrementally refactor Deck layout as its
organization engine. It builds the pure Organization Planning module and the
transactional Layout Application module described in `DESIGN.md`, while using
Flowerpot classification and `WorkspaceItemSpaceFinder` only as investigated
reference material.

Deck combines classification, placement, delayed execution, database writes,
raw database-file backup, and process restart. The Issue #2 audit found that
adding revision checks, invariant validation, atomic application, and recovery
would require replacing its core control flow; a nominal refactor would retain
the same upstream patch surface without reducing implementation cost.

The existing Deck feature is not removed by this decision. Runtime coexistence,
hook removal, preference migration, and Deck-output
compatibility fixtures require their own accepted Issues. New organizer work
must not add a second live hook before that migration is defined.
