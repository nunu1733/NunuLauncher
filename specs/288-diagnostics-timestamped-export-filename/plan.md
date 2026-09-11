# Implementation Plan: Organizer diagnostics export suggests a timestamped, privacy-safe filename

> Issue: #288
> Spec: [spec.md](./spec.md)
> Status: draft

## Current evidence

- Suggested filename is a fixed string resource:
  `lawnchair/res/values/strings.xml:1006` defines
  `organizer_diagnostics_export_default_filename` =
  `organizer_diagnostics.jsonl` (`translatable="false"`).
- The export UI builds `ACTION_CREATE_DOCUMENT` with that fixed
  `EXTRA_TITLE`: `ExportUi.kt:67-76`
  (`lawnchair/src/app/lawnchair/organizer/diagnostics/export/ExportUi.kt`).
  The SAF result URI is written via
  `ExportWriter.writeToUri(context, diagnosticsPort, uri)`
  (`ExportUi.kt:41-45`); the returned URI is used as-is, so no code assumes
  the destination name today.
- The header timestamp is read independently inside the writer:
  `ExportWriter.write` sets `exportedAtWallMillis = System.currentTimeMillis()`
  (`ExportWriter.kt:84`). This is the double-clock-read hazard the issue
  calls out: filename (captured at intent creation) and header (captured at
  write time) would disagree unless the value is captured once and shared.
- Clock abstraction precedent: `JournalStore` takes an injectable
  `clock: () -> Long` defaulting to `System.currentTimeMillis()`
  (`JournalStore.kt:87`). `ExportWriter` has no clock seam yet.
- No fixed-filename-dependent paths exist beyond the two files above:
  - no temp/cache file creation, no FileProvider, no share intent besides
    `ACTION_CREATE_DOCUMENT` (grep over the `export` package: only
    `ExportUi.kt` and `ExportWriter.kt`),
  - no cleanup/deletion of exports (destination is user-owned),
  - contract doc (`docs/engineering/organizer-diagnostics.md` §9, D-10) fixes
    the file *shape*, not the filename.
- Confirmed facts vs inference: file/line references above are confirmed by
  reading current `main`; the claim "no other path assumes the fixed name"
  is a grep-based negative confirmed at plan time and must be re-confirmed at
  implementation time.

## Design

### Modules and interfaces

- New pure formatter in the export package
  (`lawnchair/src/app/lawnchair/organizer/diagnostics/export/`),
  e.g. `DiagnosticsExportFilename`:

  ```kotlin
  object DiagnosticsExportFilename {
      fun format(exportedAtWallMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String
  }
  ```

  - Small interface; no state; no filesystem/clock access (pure function of
    the captured instant + zone). Platform types (`Uri`, `Context`) do not
    appear.
  - Renders `organizer_diagnostics_yyyyMMdd_HHmmss_SSS.jsonl` with zero-padded
    Gregorian fields (`DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS")`)
    and the fixed prefix from the existing non-translatable string resource
    logic (prefix stays `organizer_diagnostics`).
- `ExportWriter.write` gains an explicit export timestamp parameter:

  ```kotlin
  fun write(events, outputStream, deviceProfile, exportedAtWallMillis: Long)
  ```

  and uses that value for `ExportHeader.exportedAtWallMillis` instead of
  calling `System.currentTimeMillis()` internally
  (`ExportWriter.kt:82-86`). `writeToUri` forwards the same parameter. The
  wall clock is read exactly once per export, in `ExportUi`'s
  `onClick` (`ExportUi.kt:66-77`), immediately before launching SAF.
- No new adapter is created; the seam is (formatter function, explicit
  timestamp parameter) — both directly testable with fixed values, matching
  the "production/test実体が必要になるまで仮想interfaceを増やさない" rule.

### Data flow

1. User taps the export preference → `onClick` captures
   `exportedAtWallMillis = System.currentTimeMillis()` once.
2. `DiagnosticsExportFilename.format(exportedAtWallMillis)` produces the
   `EXTRA_TITLE`; the intent launches SAF.
3. User confirms (or cancels) → on confirm,
   `ExportWriter.writeToUri(context, port, uri, exportedAtWallMillis)` writes
   the D-10 content whose header reuses the captured value.
4. Errors/cancel: unchanged behavior (`ExportUi.kt:34-39, 51-58`).
   The journal is never mutated by the export path (unchanged).

### Alternatives rejected

- Capture the timestamp inside `ExportWriter.writeToUri` (writer owns the
  clock): rejected because the filename must exist before SAF launches; the
  value would still need to come from the UI to stay identical, so the
  writer owning the clock adds a second read or a callback seam for no gain.
- Derive the filename from the header after writing (post-hoc rename):
  rejected — SAF destinations are provider-owned; rename is not portable.
- Inject a `Clock`/`() -> Long` into a new exporter class: rejected for now —
  the explicit-timestamp parameter keeps `ExportWriter` stateless and gives
  the same testability with a smaller surface. Revisit only if more
  time-dependent behavior lands in the writer (would then meet the ADR bar).

## Change set

| Area | Intended change | Why here |
|---|---|---|
| `export/DiagnosticsExportFilename.kt` (new) | Pure timestamped filename formatter | Naming logic needs a deterministic, unit-testable seam |
| `export/ExportWriter.kt` | `write`/`writeToUri` take `exportedAtWallMillis`; remove internal `System.currentTimeMillis()` | Header and filename must share one captured instant (EF-AC-02) |
| `export/ExportUi.kt` | Capture the instant once in `onClick`; set timestamped `EXTRA_TITLE`; pass the value through to `writeToUri` | Only the UI knows the intent-creation moment; single read site |
| `lawnchair/res/values/strings.xml` | Replace `organizer_diagnostics_export_default_filename` value or fold into the formatter (keep `translatable="false"`) | Fixed technical identifier, not UI copy |
| `tests/unit/.../diagnostics/export/` | Formatter tests (fixed clock/zone, distinctness, character class, `.jsonl` suffix); `ExportWriter` header-timestamp test | EF-AC-01..05, EF-AC-08 evidence |
| `tests/organizer-instrumentation/.../OrganizerDiagnosticsRouteInstrumentationTest.kt` (extend if a harness exists) | Export flow with timestamped suggested name; cancel path | EF-AC-06 evidence |
| `docs/engineering/organizer-diagnostics.md` §9 | Document the filename convention (prefix + local-time timestamp, `.jsonl`) | EF-AC-08 |

## Migration and recovery

- No schema/rule migration; no persisted app-owned state is added or changed.
- Failure rollback: nothing to roll back — a failed write leaves the journal
  intact (unchanged, existing behavior).
- Release rollback/downgrade: a downgraded build simply suggests the fixed
  filename again; previously exported timestamped files are ordinary user
  files and remain untouched.
- Backup/restore compatibility: journal is already excluded from backup;
  exported files live outside app storage. No impact.

## Verification

| Acceptance criterion | Automated/manual evidence | Command or environment |
|---|---|---|
| AC-1 / AC-4 / AC-5 | Formatter unit tests (fixed clock + fixed zone; distinct instants → distinct names; character class + suffix) | `./gradlew :lawnchair:testLawnWithQuickstepGithubDebugUnitTest --tests '*DiagnosticsExportFilename*'` (exact task per building guide at implementation time) |
| AC-2 | `ExportWriter` unit test: explicit timestamp appears verbatim as `exportedAtWallMillis`; no clock call inside writer | Same unit test task, `--tests '*ExportWriter*'` |
| AC-3 | Existing export/D-10 fixture tests pass unmodified | Existing export unit tests |
| AC-6 | Instrumentation: export route flow with timestamped `EXTRA_TITLE`; cancel leaves journal intact | Connected device/emulator instrumentation run (`tests/organizer-instrumentation`) |
| AC-7 | Formatter purity (no fs/clock deps) by construction + PR grep evidence that no export cache/temp path exists | PR description evidence |
| AC-8 | Contract doc §9 diff in PR | Review |

含めるべき観点: unit/contract（formatter、writer header）、UI/accessibility（export route instrumentation、label不変）、failure injection（write失敗・cancelの既存挙動保持）。layout-data/migration該当なし（`risk: []`）。

## Documentation updates

- [ ] spec status/history（承認時: `draft` → `accepted`、実装後 `implemented`）
- [ ] CONTEXT.md — 不要（implementation語のみ。domain languageが定着した場合のみ追記）
- [ ] DESIGN.md — 不要（module構造・不変条件の変更なし）
- [ ] ADR — 不要（タイムゾーン選択等、判断はspecのopen questionで解消。変更困難になればADRを再評価）
- [ ] AGENTS.md — 不要（workflow/verified commandの変更なし）

## Execution checklist

- [ ] Current behavior reproduced（固定ファイル名の `EXTRA_TITLE` と writer内の独立clock読み取りを確認）。
- [ ] Tests fail for the missing behavior（formatter新設・header timestamp引数化に対する先行test）。
- [ ] Minimal implementation completed.
- [ ] Migration/recovery verified（該当なし — 該当なしことをPRに明記）。
- [ ] Full relevant verification completed（spotlessCheck、unit tests、instrumentation）。
- [ ] PR evidence and remaining risks recorded.
