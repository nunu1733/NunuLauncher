---
status: accepted
---

# Retire the Deck runtime without selecting a historical layout

## Context

[ADR-0002](0002-replace-deck-layout.md) chose replacement rather than incremental
refactoring of Deck. It deliberately left runtime coexistence, preference
migration, hook removal, and compatibility evidence to later work. The completed
[Issue #56 assessment](../assessment/issue-56-deck-runtime-retirement.md) shows
that Deck still has raw database copies, preference readers, UI gates, a package
path, and drag/delete behavior.

Removing those paths requires durable choices about which layout is authoritative,
how historical state is treated, and what a downgrade can claim. The observable
contract is specified separately in [Issue #57's spec](../../specs/57-deck-runtime-retirement/spec.md).

## Decision

1. The active Launcher database is the sole authority for the current home
   layout. Retirement does not invoke Deck enable or disable, and does not restore
   `bk` or `lawndeck` artifacts.
2. The current persisted `swipeUpGesture` and `addIconToHome` values remain as
   they are. The original values before a Deck enable operation are unknowable,
   so retirement will not infer or reconstruct them.
3. `enable_lawn_deck` and `show_deck_layout` remain persisted-false compatibility
   tombstones. Their values are normalized before cleanup. Only then may exact
   historical files recognized from the installed version's finite Launcher
   grid-database set be removed.
4. No replacement package hook is introduced. Issue #55's latest decision makes
   the organizer ineligible for this package event.
5. The synthetic Deck-output fixture remains evidence that planner ingestion can
   accept historical output. It does not preserve a Deck runtime contract.
6. Downgrade support is limited. Rollback before cleanup is best effort because
   the old binary may still find its own files and preferences. A downgrade after
   cleanup does not promise restored Deck behavior or snapshots. A downgrade to
   an old binary, or restoration of an old backup, before the new version has
   initialized is an unsupported boundary. No cross-grid downgrade safety is
   promised while Issue #59 remains unresolved.

## Alternatives considered

### Restore `bk` or `lawndeck` during retirement

Rejected. A historical copy can overwrite the user's current layout, and the
assessment shows raw restore lifecycle gaps already assigned to Issue #58.

### Delete all similarly prefixed database files

Rejected. Prefix matching can remove unknown or future artifacts. The finite
Launcher grid database inventory supplies a bounded recognition set.

### Reconstruct gesture and add-icon preferences

Rejected. Deck overwrote them without preserving their prior values. A guessed
value would be a new user-visible change rather than a retirement action.

### Replace the removed package branch with an organizer branch

Rejected. The latest Issue #55 decision disables organizer eligibility for this
event, and a replacement would create a separate product decision.

## Consequences

The current active layout survives retirement even when it was produced by Deck.
Old preference or backup data becomes inert after initialization rather than a
signal to restore a layout. Historical raw artifacts have a narrow cleanup rule,
and a failed cleanup can leave inert files behind until a later attempt.

Users who downgrade after cleanup cannot rely on the old Deck experience
returning. This is an explicit compatibility limit, not a promise of recovery.
Issue #57 remains subject to its Issue-owned dependency and start conditions.
