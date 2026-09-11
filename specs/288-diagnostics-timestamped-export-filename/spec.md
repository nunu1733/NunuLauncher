---
issue: "#288"
status: draft
requirements: [EF-AC-01, EF-AC-02, EF-AC-03, EF-AC-04, EF-AC-05, EF-AC-06, EF-AC-07, EF-AC-08]
risk: []
updated: 2026-09-11
---

# Organizer diagnostics export suggests a timestamped, privacy-safe filename

## Problem

Organizer diagnostics export (`ExportUi.kt` → SAF `ACTION_CREATE_DOCUMENT`)
suggests a fixed filename `organizer_diagnostics.jsonl`
(`organizer_diagnostics_export_default_filename`). When multiple diagnostic
captures are collected during repeated physical-device/emulator investigations,
the files are difficult to distinguish outside the app and can be accidentally
overwritten or confused during sharing/archival.

## Outcome

Every newly generated diagnostics export defaults to a filename that embeds the
export timestamp, e.g. `organizer_diagnostics_20260911_230243_969.jsonl`, so
captures are distinguishable and safe to archive. The filename timestamp and
the header `exportedAtWallMillis` come from one captured clock instant, so the
name always agrees with the exported metadata. The `.jsonl` format, payload
schema (D-10), and the explicit-user-initiated SAF flow are unchanged.

## Scope

- The suggested export filename (`EXTRA_TITLE` of `ACTION_CREATE_DOCUMENT`)
  embeds the export timestamp in a filesystem-safe form:
  `organizer_diagnostics_yyyyMMdd_HHmmss_SSS.jsonl`.
- The export timestamp is captured exactly once per export and drives both the
  suggested filename and the header `exportedAtWallMillis`.
- A pure filename formatter makes naming deterministic and testable with a
  fixed clock value and a fixed timezone.
- Documentation of the filename convention in the diagnostics contract
  (`docs/engineering/organizer-diagnostics.md` §9).

## Non-goals

- Changing the export payload, header fields, or `.jsonl` line format (D-10).
- Changing the export trigger model: export remains explicit-user-initiated via
  SAF (contract §9); no automatic/background export is added.
- Adding a share-sheet / FileProvider path. Export has no FileProvider, cache
  file, or temp file today (verified against `ExportWriter.kt` / `ExportUi.kt`);
  this spec does not introduce one.
- A cleanup/retention policy for exported files: export writes only to a
  user-chosen destination outside app storage, so the app does not own exported
  files and there is nothing to accumulate or prune.
- Renaming the diagnostics journal itself or changing journal retention (§8).

## Domain language

- **Export timestamp**: the single wall-clock instant captured at export
  initiation. Derives both the suggested filename and
  `ExportHeader.exportedAtWallMillis`. To be reflected in `CONTEXT.md` only if
  it becomes load-bearing beyond this spec; otherwise implementation-local.

## Behavior scenarios

### Scenario: Default filename carries the export timestamp

Given the user opens the diagnostics export control in Settings
When the export intent is created
Then the suggested document name is
  `organizer_diagnostics_yyyyMMdd_HHmmss_SSS.jsonl`
  where the timestamp is rendered from the captured export timestamp in the
  device-local timezone
And the journal is unchanged until the user confirms a destination.

### Scenario: Filename and header share one captured instant

Given an export is started and the export timestamp is captured once
When the user confirms a destination and the export is written
Then the header line's `exportedAtWallMillis` equals the instant the filename
  was derived from
And no second wall-clock read occurs for the export path.

### Scenario: Distinct exports produce distinct suggested names

Given two exports are initiated at different instants
When each export intent is created
Then the suggested filenames differ in their timestamp segment
And an export started at a later instant never reuses a previous name.

### Scenario: Filename is filesystem- and privacy-safe

Given any export timestamp value
When the filename is formatted
Then it contains only `[A-Za-z0-9_.]` characters, contains no `:`, `/`, `\`,
  or whitespace, and ends in `.jsonl`
And it consists of nothing but the fixed generic prefix and the timestamp
  (no package name, device/user identifier, layout content, or strategy name).

### Scenario: SAF flow works with the timestamped name

Given the user confirms a destination for an export with the timestamped
  suggested name
When the export is written
Then the destination receives the exact D-10 content
  (`{"header":{...}}` first line, then `RunEvent` lines in ascending
  `journalSequence`)
And a success or error toast is shown as today.

### Scenario: The provider may rename; export still succeeds

Given SAF providers are free to adjust the suggested name (e.g. collisions,
  unsupported characters)
When the write executes against the returned URI
Then the export writes to the returned URI regardless of the provider's final
  display name
And no code path assumes the destination filename equals the suggestion.

### Scenario: Cancel and failure leave the journal intact

Given the user cancels the SAF dialog
When the activity result is not `RESULT_OK`
Then nothing is written and no persistent change is made
And on write failure the existing error toast behavior and the intact journal
  are preserved.

### Scenario: No app-owned export accumulation

Given any number of exports performed
When they complete
Then no exported file is created inside app storage (no cache/temp/export
  directory grows)
And journal retention behavior (§8) is unchanged.

## Data and state

- Read: live journal snapshot via `DiagnosticsPort.snapshot()` (unchanged).
- Written: the D-10 export content to the user-selected SAF destination only.
- Persisted app-owned state: none added. The export timestamp is
  process-transient and is not persisted.
- No migration, no backup/restore impact (journal is already excluded from
  backup; exported files live outside app storage).
- Layout data: not involved.

## Permissions, privacy, and security

- No new permissions, no network, no new outbound path (export remains the
  existing SAF write to a user-chosen destination).
- The filename adds only a timestamp to the existing generic prefix; no
  package names, device/user identifiers, layout content, or strategy names.

## Accessibility and localization

- The export control's label, subtitle, focus/TalkBack behavior are unchanged.
- The suggested filename remains a non-translatable technical identifier
  (`translatable="false"`); timestamp digits are locale-independent
  (Gregorian, zero-padded) regardless of device locale.

## Acceptance criteria

- [ ] AC-1 (EF-AC-01): Every new diagnostics export suggests a filename of the
      form `organizer_diagnostics_yyyyMMdd_HHmmss_SSS.jsonl`.
- [ ] AC-2 (EF-AC-02): The filename timestamp and
      `ExportHeader.exportedAtWallMillis` are derived from one captured
      instant; the export path reads the wall clock exactly once per export.
- [ ] AC-3 (EF-AC-03): Export content and schema (D-10) are byte-shape
      unchanged; no new fields, no format change.
- [ ] AC-4 (EF-AC-04): Exports started at different instants produce different
      suggested filenames; exports at the same captured instant produce the
      same filename (deterministic).
- [ ] AC-5 (EF-AC-05): The formatted filename contains only
      `[A-Za-z0-9_.]`, ends in `.jsonl`, and carries no identifying
      information beyond prefix + timestamp.
- [ ] AC-6 (EF-AC-06): The SAF export flow succeeds end-to-end with the
      timestamped suggested name; cancel/failure behavior is unchanged.
- [ ] AC-7 (EF-AC-07): No code path assumes a fixed destination filename, and
      no app-owned export/temp/cache file is created (no unbounded
      accumulation).
- [ ] AC-8 (EF-AC-08): The filename convention is documented in the
      diagnostics contract (§9).

## Test oracle

| AC | Evidence |
|---|---|
| AC-1 | Unit test: fixed clock value → expected filename string (formatter seam) |
| AC-2 | Unit test: `ExportWriter.write` with an explicit export timestamp writes that exact value as `exportedAtWallMillis`; single-clock-call verified by construction (UI captures once) |
| AC-3 | Existing D-10 fixture/unit tests (export header shape, event ordering) pass unchanged |
| AC-4 | Unit test: two distinct fixed instants → two distinct names; same instant → same name |
| AC-5 | Unit/property test: character-class + suffix assertion over representative and boundary timestamps |
| AC-6 | Instrumentation test on the diagnostics Settings route: export flow with timestamped `EXTRA_TITLE` completes; cancel path leaves journal intact |
| AC-7 | Unit test: formatter/output is pure (no filesystem access); grep-level evidence in PR that no export cache/temp file path exists |
| AC-8 | Contract doc review in PR |

## Open questions

- Filename timezone: this draft uses the device-local timezone so the filename
  correlates with the device clock shown to the user (and matches the issue
  example's intent). Alternative: UTC for cross-device sort stability. To be
  resolved at approval; either choice keeps AC-1..AC-5 testable via the
  injected timezone seam.

## Change history

- 2026-09-11: Draft created for #288.
