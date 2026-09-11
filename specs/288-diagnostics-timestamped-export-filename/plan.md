# Implementation Plan: Organizer diagnostics export suggests a timestamped, privacy-safe filename

> Issue: #288
> Spec: [spec.md](./spec.md)
> Status: implemented (merged as PR #290, merge commit 806bef2450; accepted rev 3 after review rounds 1–2 on #288; independent audit: APPROVE, code-reviewer-2 session, code head 350f82cfb6)

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
- **Async boundary (review P1, rev 2)**: the timestamp must survive from
  `onClick` (`launcher.launch(intent)`, `ExportUi.kt:76`) to the
  `rememberLauncherForActivityResult` callback (`ExportUi.kt:32-39`) where the
  write actually runs. The androidx `ActivityResultRegistry` persists pending
  results across activity recreation and process death and re-delivers them;
  whatever holds the timestamp must be restored through the same saved
  instance state, or the re-delivered result recombines with a lost value.
- **Result delivery is one-shot (review round 2)**: once a picker result has
  been delivered to the callback, the registry never re-delivers it after a
  recreation — re-delivery applies only to results not yet delivered. Also,
  `ExportUi` launches the write with `rememberCoroutineScope()`
  (`ExportUi.kt:30,41`) and rethrows `CancellationException`
  (`ExportUi.kt:52`), so a recreation mid-write cancels the write and leaves
  the composition. Therefore the saved-instance-state session is meaningful
  **only until result delivery**; holding it until write completion would
  strand a stale session (restored value with a result that can never arrive),
  and `RESULT_OK` with a null URI (`ExportUi.kt:39`) early-returns before any
  "clear after outcome" site would run.
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
- Test harnesses available for the oracle:
  - `tests/organizer-instrumentation/.../OrganizerDiagnosticsRouteInstrumentationTest.kt`
    already stubs SAF results through a `RecordingRegistry`
    (`ActivityResultRegistry` subclass dispatching `dispatchResult`),
    composed under `LocalActivityResultRegistryOwner`.
  - compose `StateRestorationTester` (androidx compose ui-test) can exercise
    `rememberSaveable` restoration; a recreation variant can re-deliver a
    result through the stubbed registry after restoration.
  - `tests/unit/.../diagnostics/export/ExportWriterTest.kt` holds the D-10
    suite that must keep passing unchanged.
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
      fun format(exportedAtWallMillis: Long): String
  }
  ```

  - Small interface; no state; no filesystem/clock access (pure function of
    the captured instant). Platform types (`Uri`, `Context`) do not appear.
  - Renders `organizer_diagnostics_yyyyMMdd_HHmmss_SSS.jsonl` with
    zero-padded Gregorian fields
    (`DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS")` with
    `ZoneOffset.UTC`) and the fixed prefix from the existing non-translatable
    string resource logic (prefix stays `organizer_diagnostics`).
  - **UTC is fixed, not injectable** (review P2): rendering must be injective
    over epoch millis so AC-4 ("distinct instants never reuse a name") holds
    unconditionally. Device-local rendering collides on DST fall-back folds
    (two distinct instants share one local wall representation); UTC + `SSS`
    makes the mapping injective. A `ZoneId` parameter would invite
    non-injective callers, so it is not offered.
- **Pending export session** (review P1 rev 2, consumed-at-delivery rev 3):
  `ExportUi` holds the captured instant in `rememberSaveable` state:

  ```kotlin
  var pendingExportedAtWallMillis by rememberSaveable { mutableLongStateOf(0L) } // 0 = NO_PENDING
  ```

  - `onClick` (single wall-clock read of the whole flow):
    capture now → store in the saveable state → derive `EXTRA_TITLE` via the
    formatter → `launcher.launch(intent)`.
  - Result callback — **consume first, then handle the outcome**:

    ```kotlin
    val exportedAt = pendingExportedAtWallMillis
    pendingExportedAtWallMillis = NO_PENDING

    if (result.resultCode != Activity.RESULT_OK || exportedAt == NO_PENDING) return@…
    val uri = result.data?.data ?: return@…

    scope.launch {
        ExportWriter.writeToUri(context, diagnosticsPort, uri, exportedAt)
    }
    ```

    The consume happens unconditionally at delivery, before resultCode/URI
    validation, so every delivery path — cancel (non-OK), OK + null URI,
    result without a restorable session — leaves the session empty.
  - After consumption, ownership of the instant transfers to the in-flight
    write (a plain local `val`). A recreation mid-write cancels the write
    coroutine via `rememberCoroutineScope` and can never re-deliver the
    consumed result, so nothing restores or preserves a pending session — no
    stale state is possible by construction.
  - While the picker is open (result not yet delivered), the registry
    re-delivers the result after recreation/process death and the saveable
    value restores from the same saved instance state, so callback and
    timestamp recombine. This is framework behavior
    (`ActivityResultRegistry` + `SavedStateRegistry`), not something this
    feature implements.
- `ExportWriter.write` gains an explicit export timestamp parameter:

  ```kotlin
  fun write(events, outputStream, deviceProfile, exportedAtWallMillis: Long)
  ```

  and uses that value for `ExportHeader.exportedAtWallMillis` instead of
  calling `System.currentTimeMillis()` internally
  (`ExportWriter.kt:82-86`). `writeToUri` forwards the same parameter.
  No clock seam is added to the writer; the single read site is `ExportUi`'s
  `onClick`.
- No new adapter is created; the seams are (formatter function, explicit
  timestamp parameter, saveable session state) — all directly testable with
  fixed values, matching the "production/test実体が必要になるまで仮想interfaceを増やさない" rule.

### Data flow

1. User taps the export preference → `onClick` captures
   `exportedAtWallMillis = System.currentTimeMillis()` once and stores it in
   the pending export session (saveable state).
2. `DiagnosticsExportFilename.format(exportedAtWallMillis)` produces the
   `EXTRA_TITLE`; the intent launches SAF.
3. Picker open: the session survives configuration change and process death
   via saved instance state; the registry re-delivers the result after
   restoration.
4. Result delivered → the callback **consumes** the session (read once,
   clear immediately), then validates the outcome:
   cancel/OK-with-null-URI/no-session → return (session already empty);
   OK with URI → `scope.launch { ExportWriter.writeToUri(context, port, uri,
   exportedAt) }` with the local consumed value; the header reuses the
   captured instant.
5. After delivery, the write owns the instant; mid-write recreation cancels
   the write and restores no pending session. Errors/cancel: unchanged
   behavior (`ExportUi.kt:34-39, 51-58`), with the session consumed at
   delivery. The journal is never mutated by the export path (unchanged).

### Alternatives rejected

- Capture the timestamp inside `ExportWriter.writeToUri` (writer owns the
  clock): rejected because the filename must exist before SAF launches; the
  value would still need to come from the UI to stay identical, so the
  writer owning the clock adds a second read or a callback seam for no gain.
- Re-capture the timestamp in the result callback (what a plain
  `remember {}`/local variable or "just read the clock again" does):
  rejected — a plain `remember` slot dies with recreation and a re-read
  breaks AC-2 ("filename and header share one instant"); this is the exact
  failure mode review P1 called out.
- Keep the saveable session until write completion, clearing on
  write success/failure (rev 2 design): rejected in round 2 — the picker
  result is delivered once, so after delivery a mid-write recreation cancels
  the write coroutine (`rememberCoroutineScope`) while the consumed result can
  never re-arrive, restoring a saveable value whose result/write will never
  happen (stale session, violating AC-9). `RESULT_OK` with a null URI also
  early-returns before any post-outcome clear site. Consuming at delivery
  removes the stale-state class entirely.
- ViewModel + `SavedStateHandle` for the session: rejected for now — this
  surface has no ViewModel today; `rememberSaveable` provides the same
  saved-instance-state ownership in the Compose-idiomatic form with less
  machinery. Revisit if the diagnostics settings surface grows a ViewModel.
- Derive the filename from the header after writing (post-hoc rename):
  rejected — SAF destinations are provider-owned; rename is not portable.
- Device-local timezone rendering: rejected (review P2) — not injective
  across DST folds; violates AC-4. UTC fixed instead.
- Inject a `Clock`/`() -> Long` into a new exporter class: rejected for now —
  the explicit-timestamp parameter keeps `ExportWriter` stateless and gives
  the same testability with a smaller surface. Revisit only if more
  time-dependent behavior lands in the writer (would then meet the ADR bar).

## Change set

| Area | Intended change | Why here |
|---|---|---|
| `export/DiagnosticsExportFilename.kt` (new) | Pure UTC timestamped filename formatter | Naming logic needs a deterministic, unit-testable seam |
| `export/ExportWriter.kt` | `write`/`writeToUri` take `exportedAtWallMillis`; remove internal `System.currentTimeMillis()` | Header and filename must share one captured instant (EF-AC-02) |
| `export/ExportUi.kt` | Capture the instant once in `onClick`; hold it as `rememberSaveable` pending session; set timestamped `EXTRA_TITLE`; consume it (read once + clear) at result delivery before outcome handling; run the write with the local consumed value | Only the UI knows the intent-creation moment and owns the async SAF boundary; consume-at-delivery prevents stale sessions (EF-AC-02, EF-AC-09) |
| `lawnchair/res/values/strings.xml` | Replace `organizer_diagnostics_export_default_filename` value or fold into the formatter (keep `translatable="false"`) | Fixed technical identifier, not UI copy |
| `tests/unit/.../diagnostics/export/` | Formatter tests (fixed clock, distinctness incl. DST-fold pair, character class, `.jsonl` suffix); `ExportWriter` header-timestamp test | EF-AC-01..05, EF-AC-08 evidence |
| `tests/organizer-instrumentation/.../OrganizerDiagnosticsRouteInstrumentationTest.kt` (extend) | Export flow with timestamped suggested name via `RecordingRegistry`; cancel path; recreation/restoration variant (`StateRestorationTester` or saved-state re-delivery); session-cleared-after-end; result-without-session ignored | EF-AC-06, EF-AC-09 evidence |
| `docs/engineering/organizer-diagnostics.md` §9 | Document the filename convention (prefix + UTC timestamp, `.jsonl`, injective naming) | EF-AC-08 |

## Migration and recovery

- No schema/rule migration; no persisted app-owned state is added or changed.
- Failure rollback: nothing to roll back — a failed write leaves the journal
  intact (unchanged, existing behavior). A lost pending session (restoration
  failed) results in an ignored result, not a wrong-timestamp write.
- Release rollback/downgrade: a downgraded build simply suggests the fixed
  filename again; previously exported timestamped files are ordinary user
  files and remain untouched.
- Backup/restore compatibility: journal is already excluded from backup;
  exported files live outside app storage. No impact.

## Verification

| Acceptance criterion | Automated/manual evidence | Command or environment |
|---|---|---|
| AC-1 / AC-4 / AC-5 | Formatter unit tests (fixed clock; distinct instants → distinct names incl. a DST-fold pair; character class + suffix) | `./gradlew :lawnchair:testLawnWithQuickstepGithubDebugUnitTest --tests '*DiagnosticsExportFilename*'` (exact task per building guide at implementation time) |
| AC-2 | `ExportWriter` unit test: explicit timestamp appears verbatim as `exportedAtWallMillis`; no clock call inside writer | Same unit test task, `--tests '*ExportWriter*'` |
| AC-3 | Existing export/D-10 fixture tests (`ExportWriterTest`) pass unmodified | Existing export unit tests |
| AC-6 | Instrumentation: export route flow with timestamped `EXTRA_TITLE` via stubbed registry; cancel leaves journal intact | Connected device/emulator instrumentation run (`tests/organizer-instrumentation`) |
| AC-7 | Formatter purity (no fs/clock deps) by construction + PR grep evidence that no export cache/temp path exists | PR description evidence |
| AC-8 | Contract doc §9 diff in PR | Review |
| AC-9 | Instrumentation/compose: (a) state restoration + registry re-delivery while the picker is open → writer receives the original instant; (b) consume-at-delivery — pending session reads as empty after any delivered result (cancel, OK+URI, OK+null URI); (c) mid-write recreation/cancellation leaves no restorable pending session; (d) result-without-session ignored | Connected device/emulator instrumentation run (`tests/organizer-instrumentation`) |

含めるべき観点: unit/contract（formatter、writer header）、UI/accessibility（export route instrumentation、label不変）、failure injection（write失敗・cancel・session restoration失敗の既存挙動保持）。layout-data/migration該当なし（`risk: []`）。

## Documentation updates

- [ ] spec status/history（承認時: `draft` → `accepted`、実装後 `implemented`）
- [ ] CONTEXT.md — 不要（implementation語のみ。domain languageが定着した場合のみ追記）
- [ ] DESIGN.md — 不要（module構造・不変条件の変更なし）
- [ ] ADR — 不要（UTC固定・rememberSaveable選択はspecとこのplanに記録済み。変更困難になればADRを再評価）
- [ ] AGENTS.md — 不要（workflow/verified commandの変更なし）

## Execution checklist

- [ ] Current behavior reproduced（固定ファイル名の `EXTRA_TITLE` と writer内の独立clock読み取りを確認）。
- [ ] Tests fail for the missing behavior（formatter新設・header timestamp引数化・session consume-at-delivery/restorationに対する先行test）。
- [ ] Minimal implementation completed.
- [ ] Migration/recovery verified（該当なし — 該当なしことをPRに明記）。
- [ ] Full relevant verification completed（spotlessCheck、unit tests、instrumentation）。
- [ ] PR evidence and remaining risks recorded.
