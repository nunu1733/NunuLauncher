# Implementation Plan: Safe layout application and recovery

> Issue: #14
> Spec: [spec.md](./spec.md)
> Status: implemented
> Baseline: `main` at `8788cb7f97440096b47dfb1efc10ebf5509504fd`; upstream `505dbc40e6154c05158b5d0271c45f6a885a411b`.
> Stage: B implemented and verified on the API 36.1 `nunu_qpr2_api36_1` AVD.
> Scope correction: Issue #14 comment `#issuecomment-5248038572` authorizes only this public-shape clarification after review identified the Stage B API stop condition.

## Current evidence

All observations are against the baseline commits named above. Confirmed facts only.

### Launcher DB, schema, and transaction helper

- `DatabaseHelper.SCHEMA_VERSION = 32` (`src/com/android/launcher3/model/DatabaseHelper.java:75`). `onUpgrade` switches on `oldVersion`; `case 32` currently `return`s (`DatabaseHelper.java:283-286`). After the switch, any unhandled fall-through calls `createEmptyDB(db)` (`DatabaseHelper.java:289-292`) — the wipe fallback ADR-0004 forbids for the organizer migration.
- `LauncherSettings.Favorites` column order is fixed in `getColumnsToTypes(long)` (`src/com/android/launcher3/LauncherSettings.java:368-390`): `_id, title, intent, container, screen, cellX, cellY, spanX, spanY, itemType, appWidgetId, icon, appWidgetProvider, modified, restored, profileId, rank, options, appWidgetSource`. `getColumns(profileId)` joins the keys (`LauncherSettings.java:405-408`) and is used by `LauncherDbUtils.copyTable` and grid-migration copy paths, so any column added to `favorites` changes every schema-dependent copy.
- `LauncherDbUtils.SQLiteTransaction` (`src/com/android/launcher3/provider/LauncherDbUtils.java:201-221`) is `beginTransaction()` in the constructor, `setTransactionSuccessful()` in `commit()`, and `endTransaction()` in `close()`. A `try(...)` block therefore marks success **before** `close()` resolves durability. Spec §“Transaction outcome classification” makes this the authoritative reason no exception location proves commit.
- `ModelDbController.newTransaction()` returns a `SQLiteTransaction` on `getWritableDatabase()` (`src/com/android/launcher3/model/ModelDbController.java:269-273`). `ModelDbController.getDb()` exposes the same writable DB (`:375-378`). `tryMigrateDB` falls back to `createEmptyDB()` on migration failure (`:288-302`) — incompatible with “source layout remains authoritative” and is bridged, not reused, by this plan.

### Existing layout writers that must share serialization

- `ModelWriter.UpdateItemsRunnable.runImpl` opens `getModelDbController().newTransaction()` and commits per batch (`src/com/android/launcher3/model/ModelWriter.java:469-483`). Single-item writes go through `getModelDbController().insert/update/delete` (`:178-217`), each of which auto-begins/ends a transaction inside `SQLiteDatabase`.
- `GridSizeMigrationUtil.migrateGridIfNeeded` opens `new SQLiteTransaction(target.getWritableDatabase())` directly, not through `ModelDbController`, and calls `copyTable` (`src/com/android/launcher3/model/GridSizeMigrationUtil.java:115-160`). `copyTable` uses `Favorites.getColumns(userSerial)` and `ATTACH DATABASE` (`LauncherDbUtils.java:88-105`), so it is schema-version-sensitive in both directions.
- `RestoreDbTask.performRestore` opens `new SQLiteTransaction(db)` on `controller.getDb()` and runs `sanitizeDB`, which deletes rows for unrestored profiles and remaps `profileId` on survivors (`src/com/android/launcher3/provider/RestoreDbTask.java:201-335`).
- `ModelDbController.deleteEmptyFolders / deleteBadAppPairs / deleteUnparentedApps` each open their own `SQLiteTransaction` on the writable DB (`:390-477`).
- `LawnchairBackup.restore` performs **file-level** mutation: `context.getDatabasePath(LAUNCHER_DB_FILE_NAME).parentFile?.deleteRecursively()` then writes the archived files (`lawnchair/src/app/lawnchair/backup/LawnchairBackup.kt:60-86`). This bypasses SQLite entirely.
- `LawndeckManager` is negative evidence: it copies DB files (`db.copyTo(backupDb)`), waits on a fixed `postDelayed(800)` delay, and calls `restartLauncher` (`lawnchair/src/app/lawnchair/deck/LawndeckManager.kt:61-92, 147-162`). ADR-0002 already rejects this approach.

### Model reload generation

- `LauncherModel.forceReload()` stops the loader, clears `mModelLoaded`, and posts a new `LoaderTask` on `MODEL_EXECUTOR` when callbacks exist (`LauncherModel.java:311-324,379-428`). Existing bind completion carries no causal request token; `getLastLoadId()` is neither a layout revision nor proof that a particular caller's reload completed. The minimal exact-task token bridge below is therefore required.

### Backup/restore allowlists (recovery DB exclusion)

- `LawnchairBackup.getFiles(context, forRestore)` returns exactly `LAUNCHER_DB_FILE_NAME`, the shared-prefs XML, the preferences DB, and the preferences DataStore (`LawnchairBackup.kt:134-141`). A new recovery DB filename is therefore absent from the ZIP both on `create` and on `restore`.
- `res/xml/backupscheme.xml` allowlists only `launcher.db` and the five `launcher_*_by_*.db` grid files, plus `com.android.launcher3.prefs.xml` and `downgrade_schema.json`. A recovery DB filename is not included.
- `LauncherFiles.ALL_FILES` enumerates the same set (`src/com/android/launcher3/LauncherFiles.java:34-57`). The recovery DB is intentionally not added here.

### Lock schema baseline (ADR-0004)

- `downgrade_schema.json` (`res/raw/downgrade_schema.json`) declares `"version": 32` and uses the rename/create/explicit-copy/drop recipe for column-removing downgrades (see `downgrade_to_28` and `downgrade_to_22`). `DbDowngradeHelper.onDowngrade` executes each `downgrade_to_NN` array in one `SQLiteTransaction` and throws `SQLiteException("Downgrade path not supported to version " + i)` if a step is missing (`src/com/android/launcher3/model/DbDowngradeHelper.java:56-73`). There is no per-statement fallback, so direct `ALTER TABLE … DROP COLUMN` is not portable.
- `GridBackupTable.copyTable` uses positional `SELECT *` (`src/com/android/launcher3/model/GridBackupTable.java:70-74`), which is unsafe across schema 32↔33 without prior normalization; ADR-0004 forbids schema-33 copies against an unnormalized source.
- `GridSizeMigrationUtil.copyEntryAndUpdate` assigns new `_id` values to copied rows and may remove or recreate rows (`GridSizeMigrationUtil.java:245-294`). Per ADR-0004, every row in the migration result is `UNKNOWN` regardless of whether it was retained, copied, or recreated.

### Frozen planner artifact (do not change)

- `app.lawnchair.organizer.planning.OrganizationInput`, `PlanningResult`, `Planned`, `PlannedPlacement`, `NewPage`, `NewFolder`, and the identity types in `Identity.kt` are the production planner contract (Issues #10 and #12). `CapturedItem.locked: Boolean` is the only lock field on the planner side (`lawnchair/src/app/lawnchair/organizer/planning/OrganizationInput.kt:45-57`). The application module bridges the row tri-state to this Boolean at the snapshot boundary (Issue #23’s responsibility, already closed) and re-validates the raw column at A2 and A5.

### Repository contract gates

- `python3 tools/repo-contract/validate_repo_contract.py` validates Markdown local links, `.github/ISSUE_TEMPLATE` YAML, and required project files; `python3 tools/repo-contract/test_validate_repo_contract.py` is its 11-case self-test (`tools/repo-contract/validate_repo_contract.py:1-60`, `docs/engineering/quality-strategy.md:100-111`).

## Design

### Public seam (unchanged from spec.md)

```text
apply(ValidatedLayoutPlan)  -> ApplyResult
recover(RecoveryRequest)    -> RecoveryResult
```

These two functions are the only public entry points. Their platform-free input,
state, action, and result value types are public only so callers can construct and
inspect the accepted contract; they expose no behavior or secondary entry point.
Every adapter, schema, codec, port, and protocol helper below is internal.
Production callers and tests invoke behavior only through `apply`/`recover`. No
SQLite, Android `ContentValues`, cursor, `ItemInfo`, profile handle, or
`RevisionId`-internal bytes crosses this seam.

### Modules and file ownership

Organizer production code lives under
`lawnchair/src/app/lawnchair/organizer/application/`; the minimal cross-flavor
writer/reload bridge lives in the existing Launcher3 paths named below. Pure
automated tests use `tests/unit` with JUnit and in-memory fakes. Android and
SQLite-backed tests use the dedicated instrumentation source set below. One isolated
instrumentation source set is added explicitly for database and process-death recovery;
it uses the platform `android.test.InstrumentationTestRunner`, adds no library,
and is not mixed with the repository's currently unwired `tests/src` tree.

#### Domain layer — pure Kotlin, no Android/SQLite imports

| File (new) | Responsibility | Internal boundary |
|---|---|---|
| `application/public/ValidatedLayoutPlan.kt` | Public apply input composed from the exact `OrganizationInput` and `PlanningResult.Planned`. Carries `sourceRevision`, public platform-free source/intended `LayoutState`, exact actions, the planner-issued `newPages`/`newFolders`, and rule/taxonomy versions. | Spec §“Apply input” fixes the shape. |
| `application/public/LayoutState.kt` | Public immutable platform-free values for the complete canonical layout/resource state and exact `Preserve`/`Update`/`Insert` preconditions. It exposes no marshalling bytes or digest internals. | Makes `ValidatedLayoutPlan` constructible through the accepted seam. |
| `application/public/RecoveryRequest.kt` | Public recover input: `{ pointId: RecoveryPointId, expectedCurrentRevision: RevisionId }`. | Spec §“Recovery input”. |
| `application/public/Results.kt` | `ApplyResult`, `RecoveryResult`, `PreWriteRejection`, `ApplyFailure`, `RecoveryRejection`, `RecoveryFailure`, `AuthoritativeState` — exactly the variants in spec.md §§“Results”. Sealed hierarchies; no raw exception text or row bytes leak. | Spec fixes every variant. |
| `application/canonical/CanonicalMarshalling.kt` | Deterministic serialization of public `LayoutState` for revision/action digests and, separately, internal `PersistenceManifest` for recovery payloads. Pure, allocation-explicit, no platform types. | The two encodings have distinct domain tags; the private manifest wire format is versioned by the recovery format version. |
| `application/canonical/PersistenceManifest.kt` | Internal immutable lossless schema-33 `favorites` rows plus deterministic profile/device context resources. Context resources are Preserve-only; persistent page order is row-derived and model-only empty pages are excluded. Never returned by the public seam. | Sole recovery-payload state; distinct from public `LayoutState`. |
| `application/canonical/Digest.kt` | SHA-256 over canonical bytes, with domain-separated tags per digest kind (`pre-state`, `intended-post-state`, `action-set`, `recovery-action-set`, `reviewed-current-state`). Pure wrapper over `java.security.MessageDigest`. | Used for `RevisionId` derivation, checkpoint digests, and read-after-write checksums. |
| `application/revision/RevisionCalculator.kt` | Derives `RevisionId` from `LayoutState`, covering every spec revision dimension. | `getLastLoadId()` is never used here. |
| `application/actions/ActionMaterializer.kt` | Validates and consumes the planner-issued page/folder ordinals and creates exactly one action per represented item. It never recomputes ordinals. | Persistent IDs are allocated only inside the Launcher transaction. |
| `application/actions/RecoveryWriteSet.kt` | Pure computation of the recovery write-set from a checkpoint pre-state manifest and a reviewed current-state manifest: every current item is explicitly `Preserve`/`Update`/`Insert`/`Delete`. Deletion is permitted only because the confirmed `RecoveryRequest` accounts for the exact current revision. | Spec §“Recovery protocol” steps 4–5. |
| `application/lifecycle/LifecycleState.kt` | The lifecycle state machine: `CREATING, READY, APPLYING, COMMITTED_UNVERIFIED, VERIFIED, RESTORING, RESTORED, EXPIRED, CORRUPT, INCOMPATIBLE`. Pure transitions + legality table; no I/O. | Spec §“Recovery record and lifecycle”. |
| `application/lifecycle/LifecycleReconciler.kt` | Pure reconciliation logic: given a persisted record (state + pre/post/current intents + digests) and an authoritative Launcher digest classification (`PRE`, `INTENDED_POST`, `RECOVERY_TARGET`, `REVIEWED_CURRENT`, `NEITHER`), returns the next legal lifecycle transition and the public result variant. This is the brain that makes restart reconciliation work without in-memory plan state. | Spec §§“Restart reconciliation”, “Transaction outcome classification”. |
| `application/lifecycle/RetentionPolicy.kt` | Pure retention logic: 24 h (`86_400_000 ms`) from creation, at most three capacity-bearing points, never expire `APPLYING/COMMITTED_UNVERIFIED/RESTORING`, final non-restorable records can become exact tombstones without consuming capacity, and `RECOVERY_POINT_ADMISSION_BLOCKED` when three unresolved points block cleanup. | Spec §“Retention”. |
| `application/protocol/ApplyProtocol.kt` | Orchestrates phases A0–A8 against injected ports (`LayoutWriterPort`, `RecoveryStorePort`, `ModelReloadPort`, `Clock`, `OperationIdSource`, `FaultInjector`). Holds the in-process run mutex (A0). | The single place A0–A8 live. |
| `application/protocol/RecoveryProtocol.kt` | Orchestrates spec §“Recovery protocol” steps 1–7 against the same ports. Holds the same run mutex. Returns `RecoveryResult`. | The single place recovery phases live. |
| `application/protocol/RestartReconciler.kt` | Entry point invoked once per process start before any new `apply`/`recover` is accepted. Loads every non-final record from `RecoveryStorePort`, classifies Launcher state via `LayoutWriterPort`, and applies `LifecycleReconciler` transitions (validate-and-prune `CREATING`/`READY`, complete `RESTORED`, resume `RESTORING`, surface unresolved). | Spec §“Restart reconciliation”. |
| `application/protocol/LayoutApplicationModule.kt` | The composition root. Constructs the production `LayoutWriterPort`/`RecoveryStorePort`/`ModelReloadPort` adapters, wires `FaultInjector.NOOP`, and exposes the `apply`/`recover` functions. Single instance per process. | The only place Android/SQLite types touch the protocol. |
| `application/protocol/Ports.kt` | Internal ports. `OperationIdSource` emits canonical 128-bit lowercase-hex run/point IDs from `SecureRandom`; tests inject a fixed sequence. Recovery-point insert retries a primary-key collision three times, then returns `CHECKPOINT_CREATE_FAILED`. | These ports are not public seams. |

#### Closed public value shapes

Stage B implements exactly the platform-free public shapes now fixed in
spec.md §“Apply input”: persistent/plan-local page and item reference unions,
complete canonical item/resource values, explicit optional values, typed
availability/lock state, unsupported-container preservation, actions, `RunId`,
and `RecoveryPointId`. It does not add fields or variants while coding.
Persistence-lossless Launcher rows remain internal: `RowManifestCodec` captures
them independently at A4 and never exposes a row, column name, or byte encoding
through `apply` or `recover`.

#### Recovery store layer — private SQLite DB, own schema

| File (new) | Responsibility |
|---|---|
| `application/store/RecoveryDbSchema.kt` | File name, format version, and exact format-1 DDL below. Independent of Launcher schema. |
| `application/store/RecoveryDbVersionGate.kt` | Reads `PRAGMA user_version` through `SQLiteDatabase.OPEN_READONLY` before constructing the helper. A version greater than 1 returns `INCOMPATIBLE_VERSION` without a write-open; version 1 opens normally; version 0 is created only for an absent/empty new file. | Avoids destructive `SQLiteOpenHelper.onDowngrade`. |
| `application/store/RecoveryDbHelper.kt` | Recovery DB only. Creates format 1, enables WAL, configures `PRAGMA synchronous=FULL`, and never touches Launcher DB. There is no automatic downgrade path. |
| `application/store/RecoveryStore.kt` | Each lifecycle transition is one transaction. Its durability boundary is successful `endTransaction` under SQLite `synchronous=FULL`, followed by close/reopen read-back validation for checkpoint/lifecycle records. It does not claim a separate Java `fsync` call. |
| `application/store/RecoveryRecordCodec.kt` | (De)serializes a recovery record (canonical pre-state manifest, intended post-state, action-set digest, reviewed-current manifest/digest for `RESTORING`, lifecycle state, counts, checksums, timestamps) to/from the recovery-DB blob. Versioned by `FORMAT_VERSION`. |

The recovery DB and the Launcher DB are **not** a distributed transaction (ADR-0003). The protocol persists post intent and marks `APPLYING` before the Launcher transaction; reconciliation classifies pre/post/neither from persisted intent.

Format 1 uses this closed schema (all enum values are canonical integers; all
digests are 32-byte SHA-256 values):

```sql
CREATE TABLE recovery_points (
  point_id TEXT PRIMARY KEY NOT NULL,
  format_version INTEGER NOT NULL,
  run_id TEXT NOT NULL,
  created_at_ms INTEGER NOT NULL,
  updated_at_ms INTEGER NOT NULL,
  lifecycle INTEGER NOT NULL,
  prior_lifecycle INTEGER,
  pre_manifest BLOB NOT NULL,
  pre_revision TEXT NOT NULL,
  pre_digest BLOB NOT NULL,
  intended_manifest BLOB NOT NULL,
  intended_digest BLOB NOT NULL,
  apply_action_digest BLOB NOT NULL,
  reviewed_manifest BLOB,
  reviewed_digest BLOB,
  recovery_action_digest BLOB,
  item_count INTEGER NOT NULL,
  resource_count INTEGER NOT NULL, -- deterministic Preserve-only profile/device context count
  payload_checksum BLOB NOT NULL
);
CREATE TABLE recovery_tombstones (
  point_id TEXT PRIMARY KEY NOT NULL,
  reason INTEGER NOT NULL,
  format_version INTEGER NOT NULL,
  expires_at_ms INTEGER NOT NULL
);
PRAGMA user_version = 1;
```

Run IDs are created at `apply` entry; point IDs are created at checkpoint A4.
Both are canonical 32-character lowercase hex from `OperationIdSource`. The
checksum is SHA-256 over the format tag plus every preceding record column in
the DDL's fixed order with length prefixes, excluding `payload_checksum`.
Lifecycle updates recompute it. The codec rejects NULLs in required columns,
negative counts/times, unknown lifecycle/reason integers, malformed IDs,
non-32-byte digests, and checksum/count mismatches.

#### Launcher adapter layer — minimal Launcher3/AOSP bridge

| File (new) | Responsibility | Bridge scope |
|---|---|---|
| `application/adapter/LauncherLayoutAdapter.kt` | Production `LayoutWriterPort`. Reads one consistent authoritative snapshot (`captureCurrent()`), computes its canonical digest via `RevisionCalculator`, materializes the row-accounted write-set from `MaterializedWriteSet`, opens one `ModelDbController.newTransaction()`, performs the in-transaction full re-read and exact-precondition check, applies inserts/updates (no page row, no apply deletion), closes the transaction, and supports the correlated reload wait. | Only file that calls `ModelDbController.getDb()/newTransaction()` from the organizer side. |
| `lawnchair/src/com/android/launcher3/OrganizerModelReloadAdapter.kt` | Internal Lawnchair adapter deliberately placed in the same Java package as `LauncherModel`. It creates an organizer request token and calls the package-private model method, which passes it through one exact loader task to bind completion. A replaced/cancelled task fails the request; unrelated reloads cannot complete it. | Owns reload correlation without an exported middle-man bridge. |
| `application/adapter/RowManifestCodec.kt` | One DB capture produces both (a) public canonical `LayoutState` and (b) internal lossless `PersistenceManifest`. Re-materialization consumes only the internal manifest; raw rows never enter public values. | Adapter-private. |

#### Schema/migration layer — Launcher DB, schema 33

| File (changed, upstream) | Change | Reason |
|---|---|---|
| `src/com/android/launcher3/LauncherSettings.java` | Add `public static final String ORGANIZER_LOCK_STATE = "organizerLockState";` and append `columnsToTypes.put(ORGANIZER_LOCK_STATE, "INTEGER NOT NULL DEFAULT 1");` as the last entry in `getColumnsToTypes`. | ADR-0004 column; default `UNLOCKED` (`1`) so every fresh row is explicit and usable. |
| `src/com/android/launcher3/model/DatabaseHelper.java` | Schema-33 migration plus a package-private `refreshMaxItemIdFromCommittedRows()` used after organizer transaction classification; no pre-commit cache mutation. | ADR-0004 and rollback-invisible ID allocation. |
| `res/raw/downgrade_schema.json` | Bump `"version"` to `33`; add `"downgrade_to_32"` array using the rename/create-with-explicit-schema-32-column-list/copy-from-temp_favorites/drop-temp_favorites recipe (explicit columns = the 19 baseline columns in order, **excluding** `organizerLockState`). No `ALTER TABLE … DROP COLUMN`. | Portable 33->32 per ADR-0004. |
| `src/com/android/launcher3/model/LayoutWriteCoordinator.java` | Cross-flavor process-wide reentrant writer lease with owner kind/token and non-blocking organizer acquisition. It contains no reload registry. | Minimal shared-writer bridge. |
| `src/com/android/launcher3/provider/LauncherDbUtils.java` | Add a `SQLiteTransaction` constructor accepting an owned lease; `close()` always calls `endTransaction()` first and releases the lease in `finally`. Existing constructor remains for bootstrap-only callers. | Makes transaction lifetime equal lease lifetime, including close exceptions. |
| `src/com/android/launcher3/model/ModelDbController.java` | Acquire the lease in every mutation entry (`insert/update/delete/newTransaction`, cleanup transactions, `createEmptyDB`, migration). A coordinated transaction owns it through `close`. Retain the old helper until organizer-aware migration succeeds. | Central runtime mutation choke point. |
| `src/com/android/launcher3/model/ModelWriter.java` | Wrap each MODEL_EXECUTOR mutation runnable with `LayoutWriteCoordinator.runOrDefer`; a tokenless runnable returns without entering `ModelDbController` when an organizer lease is active and is reposted after release. | Prevents single-executor self-deadlock. |
| `src/com/android/launcher3/LauncherProvider.java` | Gate `executeControllerTask` before its runnable invokes `ModelDbController`; tokenless provider work defers without blocking MODEL_EXECUTOR. | Covers direct provider insert/update/delete callers. |
| `src/com/android/launcher3/model/GridSizeMigrationUtil.java` | Put fast/general paths and target-wide `UNKNOWN` update in one transaction; normalize both DBs first; write device prefs only after success. | ADR-0004. |
| `src/com/android/launcher3/provider/RestoreDbTask.java` | Hold one outer reentrant lease around `performRestore`; surviving lock state travels with profile remap. | Covers direct SQLite writes. |
| `lawnchair/src/app/lawnchair/backup/LawnchairBackup.kt` | Under an outer lease, quiesce application/model work, close both the active Launcher helper and recovery helper, replace the DB directory, reconstruct both helpers (the excluded recovery DB restarts empty), rebind, then request an exact-token reload. Failure keeps organizer unavailable. | No directory deletion against open handles. |
| `lawnchair/src/app/lawnchair/deck/LawndeckManager.kt` | Until Deck removal, hold the lease and close/reopen the active helper around its raw-file backup/restore. | Covers the other identified raw-file writer. |
| `src/com/android/launcher3/LauncherModel.java`, `src/com/android/launcher3/model/LoaderTask.java`, `src/com/android/launcher3/model/BaseLauncherBinder.java` | Carry a package-internal organizer reload token through one exact loader task into its bind-complete signal. `LoaderTask.run()` uses the same non-blocking gate; only the matching token bypasses it. | Causal correlation and deadlock-free loader mutation. |

All upstream-touching changes carry an inline comment `// Issue #14: <reason>` and remain minimal bridges. When the organizer module is disabled, existing writers still gain correctness-only serialization; no organizer result, recovery write, or reload-token behavior occurs (see “Feature disable / release rollback”).

#### Test layer

| File (new) | Responsibility |
|---|---|
| `tests/unit/app/lawnchair/organizer/application/contract/ApplyResultContractTest.kt` | Asserts every `ApplyResult`/`RecoveryResult` variant is constructible exactly as spec.md fixes it, with no leaked platform types. |
| `tests/unit/app/lawnchair/organizer/application/contract/PublicSeamShapeTest.kt` | Asserts `apply`/`recover` are the only public entry points and all public values carry no Android/SQLite types. |
| `tests/unit/app/lawnchair/organizer/application/canonical/RevisionCalculatorTest.kt` | Property + fixture tests proving the revision changes for every dimension in spec §“Revision semantics”, and is stable across locale/timezone/scheduling. |
| `tests/unit/app/lawnchair/organizer/application/lifecycle/LifecycleReconcilerTest.kt` | Every (record state, authoritative digest class) -> (next state, result) row from spec §§“Restart reconciliation”, “Transaction outcome classification”. |
| `tests/unit/app/lawnchair/organizer/application/lifecycle/RetentionPolicyTest.kt` | Clock-driven 24 h / max-three / final-tombstone admission / unresolved-retention behavior (spec §“Retention”). |
| `tests/unit/app/lawnchair/organizer/application/protocol/ApplyProtocolTest.kt` | A0–A8 happy path, empty diff, A2 stale, A5 precondition race, checkpoint failure, Nth-write failure, outcome-unknown pre/post/neither, reload/verify failure -> recovery, with `FaultInjector` deterministic counts. |
| `tests/unit/app/lawnchair/organizer/application/protocol/RecoveryProtocolTest.kt` | Recovery happy path, missing/expired/corrupt/incompatible/`ALREADY_RESTORED`/`STALE_REVISION`/`LOCK_STATE_UNAVAILABLE`, commit-unknown, and reload/verify failure. |
| `tests/unit/app/lawnchair/organizer/application/adapter/FakeLayoutWriter.kt` + `FakeRecoveryStore.kt` | In-memory `LayoutWriterPort`/`RecoveryStorePort` used by every contract test through the **same** public seam (AC-13). |
| `tests/organizer-instrumentation/com/android/launcher3/organizer/DatabaseHelperSchema33Test.java` | Fresh 33 and non-wiping 32→33 with platform SQLite. |
| `tests/organizer-instrumentation/com/android/launcher3/organizer/DowngradeSchema33Test.java` | Exact 33→32→33 rebuild and final `UNKNOWN` state. |
| `tests/organizer-instrumentation/com/android/launcher3/organizer/InactiveGridDbNormalizationTest.java` | Schema-mismatched inactive grid DB normalization before copy. |
| `tests/organizer-instrumentation/com/android/launcher3/organizer/GridMigrationUnknownMarkingTest.java` | Both migration paths, target-wide `UNKNOWN`, source-authoritative failure, helper/prefs state. |
| `tests/organizer-instrumentation/com/android/launcher3/organizer/RestoreProfileRemapTest.java` | Android restore profile remap/deletion and lock-state behavior. |
| `tests/organizer-instrumentation/com/android/launcher3/organizer/BackupExclusionTest.java` | Active-only ZIP behavior, recovery-DB exclusion, and close/replace/reopen ordering. |
| `tests/organizer-instrumentation/com/android/launcher3/organizer/LayoutWriteCoordinatorTest.java` | Controller direct/transaction/cleanup paths, grid, restore, backup restore, and Deck raw-file paths serialize; a source-scan allowlist fails when a new direct `favorites` mutation lacks coordinator ownership. |
| `tests/organizer-instrumentation/com/android/launcher3/organizer/RecoveryStoreLifecycleTest.java` | Real recovery DB; every transition failure; close/reopen durability read; corruption/newer-format behavior. |
| `tests/organizer-instrumentation/AndroidManifest.xml` | Test-only platform instrumentation targeting the GitHub debug application. |
| `tests/organizer-instrumentation/com/android/launcher3/organizer/OrganizerRecoveryInstrumentationTest.java` | Public-seam real-DB apply/recover driver. Accepts one canonical fault phase and verify-only mode; stores no private row dump. |
| `tools/organizer-recovery-smoke.sh` | Host orchestrator: install debug/test APKs, start one fault phase, wait for its test-only readiness log, force-stop, relaunch verify-only, and record typed result/lifecycle for SA-12/13/14/interrupted recovery. Uses explicit package/component names and fails on timeout. |

### Shared writer serialization (deep closure)

Runtime SQLite mutation is centralized at `ModelDbController` where possible;
outer leases cover direct restore/grid transactions and both raw-file writers.
Schema/bootstrap writes occur before the organizer module accepts calls. A
source-scan allowlist test inventories every remaining direct `favorites`
mutation (`RestoreDbTask`, `GridSizeMigrationUtil`, database creation/upgrade,
and initial-layout loading) and requires either an outer lease or a documented
pre-organizer lifecycle. Adding an unowned runtime writer fails that test.

**Lease semantics**

- A reentrant lock records owning thread, recursion count, owner kind
  (`ORGANIZER`, `MODEL_WRITER`, `GRID_MIGRATION`, `RESTORE`, `BACKUP_RESTORE`,
  `DECK_FILE_RESTORE`), and monotonic logical lease token. Reentrancy requires
  both the owning thread and the exact token; same-thread work without that
  capability cannot enter. An `AtomicReference` alone is not treated as a mutex.
- Every kind is mutually exclusive. Organizer acquisition is non-blocking;
  baseline writers wait off the UI thread before opening a transaction.
- Acquisition is non-blocking for the organizer: if another lease is held, `apply` returns `Rejected(WRITER_BUSY)` and `recover` returns `WriterBusy` without creating a checkpoint or mutating state (spec §“Apply protocol” A2, SA-24).
- `ApplyProtocol` holds one outer `ORGANIZER` lease token continuously from A2 through
  A8, automatic recovery, authoritative classification, max-ID cache refresh,
  and final result construction. `NoChanges` and pre-write rejections release it
  after their final read. The A5/A6 transaction reenters the lease; `close()`
  drops only that nested hold, and `refreshMaxItemIdFromCommittedRows()` runs
  before the outer hold is released. `RecoveryProtocol` uses the same scope from
  reviewed-revision capture through restore classification, reload,
  verification, cache refresh, and final result. No external allocator can
  observe stale `mMaxItemId` between transaction close and classification.
- The token is propagated only to the organizer transaction and to the exact
  organizer-requested `LoaderTask` cleanup calls. `ModelWriter.java` owns the
  MODEL_EXECUTOR gate: every mutation runnable enters through
  `LayoutWriteCoordinator.runOrDefer(ownerKind, optionalToken, runnable)` before
  it calls `ModelDbController`. `LoaderTask.run()` and
  `LauncherProvider.executeControllerTask` use the same gate, covering loader
  cursor/cleanup/default-layout/grid work and direct provider mutations. A
  source-scan allowlist test fails for any other tokenless MODEL_EXECUTOR caller
  that reaches a `ModelDbController` mutation without this gate. If a tokenless runnable reaches the single
  executor while an organizer lease is active, it is appended to a coordinator
  FIFO and returns immediately; it never blocks the executor. Lease release
  reposts deferred runnables to `MODEL_EXECUTOR` in arrival order. The exact
  loader carries the matching token, bypasses that queue, and may perform its
  tokened cleanup; other loader/model/provider writes cannot. Coordinator tests
  put each tokenless path ahead of the exact loader, prove it defers, prove the
  loader completes and releases, then prove it runs once. A second case has an
  unrelated reload supersede the exact loader and proves the replacement loader
  defers while cancellation drives typed recovery and release.
- `LauncherProvider.executeControllerTask` preserves its synchronous Binder
  contract with two futures. The Binder thread waits on an operation-completion
  future. Its initial `MODEL_EXECUTOR` attempt either runs the callable or
  appends that exact callable plus completion future to the coordinator FIFO and
  returns immediately from the executor. After lease release, the reposted
  callable completes the operation future with the original value or exception;
  reload/notification side effects execute exactly once after the mutation.
  Binder threads may wait, but UI and MODEL_EXECUTOR never do. Instrumentation
  covers deferred query/read result stability plus insert/update/delete counts,
  exception propagation, and exactly-once reload/notification behavior.
- `apply`/`recover` dispatch DB phases to `MODEL_EXECUTOR` and never block that
  executor waiting for work posted to itself. Reload waiting occurs off
  `MODEL_EXECUTOR`; completion only signals the waiting protocol.

**Correlated reload**

- Internal same-package `OrganizerModelReloadAdapter` creates a unique request
  ID and calls package-private
  `LauncherModel.forceReloadForOrganizer(requestId, completion)`. The ID is
  attached to the exact new `LoaderTask` and its `BaseLauncherBinder`
  `onCompleteSignal`. Only that signal completes the request. Supersession,
  cancellation, no callbacks, or timeout returns `MODEL_RELOAD_FAILED`.
- `getLastLoadId()` remains diagnostic only; it is neither correlation nor
  `RevisionId`.

**Why this is not a DESIGN seam change**

`DESIGN.md` §4.4 already names a “model write adapter” whose job is to put a validated plan into the Launcher model thread and DB transaction, and §4.2 says the Launcher DB is a local-substitutable dependency accessed through a production/test adapter. `LayoutWriteCoordinator` is the implementation of that adapter’s writer-serialization concern; `LauncherLayoutAdapter` is the row bridge. No new module, seam, or public type is added to DESIGN. No ADR is contradicted: ADR-0003 explicitly defers the locking primitive to this plan; ADR-0004 requires row-level row writer support for lock state changes through the same model-writer transaction, which the coordinator serializes.

**Stop-condition check**

This plan does **not** require changing any accepted public result, any DESIGN seam, or any ADR. The shared serialization, correlated reload, and adapter boundary all live inside the Layout Application module defined by DESIGN §4.2/§4.4. No destructive fallback is needed (`createEmptyDB` is explicitly avoided on the organizer path). Therefore no stop condition in the Stage A packet is triggered.

### Data flow

`apply(ValidatedLayoutPlan)`:

1. A0 `ApplyProtocol` acquires the run mutex; on failure returns `ConcurrentRun`.
2. A1 `ActionMaterializer` validates planner-issued ordinals and canonical actions without allocating persistent IDs; malformed/duplicate ordinals return `Rejected(INVALID_PLAN)`.
3. A2 `LauncherLayoutAdapter.captureCurrent()` under the writer lease computes `RevisionId` and every exact precondition; mismatch returns `Rejected(STALE_REVISION/EXACT_PRECONDITION_FAILED/LOCK_STATE_UNAVAILABLE)`. Lease contention returns `Rejected(WRITER_BUSY)`.
4. A3 if `MaterializedWriteSet` is empty, returns `NoChanges` with no DB write and no reload.
5. A4 `RecoveryStore` reads every checkpoint row and deterministic profile/device context resource through the same snapshot, requires its digest to equal the plan source state, commits the complete record (`CREATING` -> `READY`) in its own recovery-DB transaction, then reads it back and validates version/count/digest. Page order is row-derived; model-only empty pages are excluded. Failure returns the typed checkpoint rejection.
6. A5 `RecoveryStore` marks the record `APPLYING` with complete post intent. `LauncherLayoutAdapter` opens `ModelDbController.newTransaction()` and, after the writer lock is effective, re-reads the full `RevisionId` and every exact precondition. Stale/precondition failure rolls the Launcher transaction back, prunes the unused record (or leaves it `READY` for restart reconciliation), and returns `Rejected`.
7. A6 inserts/updates execute using the plan-local ID map; no page row, no apply deletion. Outcome classification re-reads the authoritative Launcher digest and dispatches to `RolledBack`/continue/automatic-recovery per spec §“Transaction outcome classification”.
8. A7 `RecoveryStore` marks `COMMITTED_UNVERIFIED`; `ModelReloadAdapter` requests a correlated reload and waits for the matching generation; `LauncherLayoutAdapter` independently recaptures the DB. Reload/convergence failure triggers automatic recovery.
9. A8 `LauncherLayoutAdapter` verifies intended state and DESIGN §5 invariants; `RecoveryStore` marks `VERIFIED` and returns `Applied`.

`recover(RecoveryRequest)` follows spec §“Recovery protocol” steps 1–7 through the same ports, with row-accounted deletion permitted only because the confirmed request accounts for the exact current revision.

### Persistent ID allocation

Planner `NewPageOrdinal`/`NewFolderOrdinal` values are validated for uniqueness
and canonical order at A1 and never rewritten. After the full A5 re-read, while
the exclusive writer lease and Launcher transaction are held, the adapter:

1. queries the maximum non-negative desktop `screen` and maximum `_id` from the
   exact current DB;
2. assigns screen IDs by page-ordinal position. For row IDs it builds one
   collision-free canonical sequence: new folders ordered by
   `NewFolderOrdinal`, then remaining inserted candidates ordered by canonical
   `ItemId`; position `i` receives `maxId + i + 1`. All arithmetic is `Long` and
   any non-negative-`Int` overflow returns `IDENTITY_EXHAUSTED` before writing;
3. maintains one transaction-local map used for every parent/member reference;
4. inserts through an adapter-private raw transaction path that does not mutate
   `DatabaseHelper.mMaxItemId` before durability is known; and
5. after authoritative commit classification, reinitializes the helper's max-ID
   cache from committed rows. It also reinitializes after rollback/unknown
   classification, so failed attempts create no later observable ID gap.

Pages create no row. The same source DB plus the same canonical plan produces
the same mapping; writer exclusion prevents an intervening allocation.

### Alternatives rejected

- **Per-DB-file raw copy (Deck approach).** Rejected by ADR-0002; `LawndeckManager` is negative evidence (file copy + `postDelayed(800)` + `restartLauncher`).
- **Shared `favorites`-table backup (`GridBackupTable` pattern).** Uses positional `SELECT *`, lives in the exported DB file, and replaces the Favorites table on restore. Disqualified by ADR-0003.
- **Dedicated tables inside the Launcher DB.** The whole DB file is exported by `LawnchairBackup.getFiles` and `backupscheme.xml`; ADR-0003 rejects it.
- **Cross-DB distributed transaction.** Not claimed, not needed: ADR-0003 deliberately makes recovery-DB commit and Launcher-DB commit independent and reconciles pre/post/neither after a crash.
- **Direct `ALTER TABLE … DROP COLUMN` for 33->32.** Not portable across supported SQLite versions per ADR-0004/`DbDowngradeHelper`; the rename/create/explicit-copy/drop recipe is used instead.
- **Organizer-only mutex.** Insufficient: the coordinator must cover every runtime DB mutation and raw-file writer named above.
- **`getLastLoadId()` for revision or correlation.** It is neither layout identity nor causal proof; the exact-loader request token is used instead.
- **Cancellation side channel.** Rejected by spec.md; v1 has no cancellation.

## Change set

| Area | Intended change | Why here |
|---|---|---|
| `lawnchair/src/app/lawnchair/organizer/application/` (new) | Domain, protocol, recovery store, Launcher adapter, public seam. | DESIGN §4.2/§9 module location. |
| `src/com/android/launcher3/LauncherSettings.java` | Add `organizerLockState` column (schema 33). | ADR-0004. |
| `src/com/android/launcher3/model/DatabaseHelper.java` | Bump `SCHEMA_VERSION` to 33; non-wiping `case 32` migration + `case 33` return. | ADR-0004. |
| `res/raw/downgrade_schema.json` | `version: 33`; portable `downgrade_to_32` table rebuild. | ADR-0004. |
| `src/com/android/launcher3/model/LayoutWriteCoordinator.java` | Cross-flavor writer lease; no reload responsibility. | Shared serialization choke point. |
| `src/com/android/launcher3/model/ModelDbController.java` | Coordinate every mutation/transaction/cleanup/migration entry, rollback-aware max-ID refresh, and source-authoritative helper swap. | Covers controller, ModelWriter, LoaderCursor, provider callers, and cleanup transactions. |
| `src/com/android/launcher3/model/ModelWriter.java` | Apply the non-blocking MODEL_EXECUTOR writer gate and defer tokenless runnables during organizer ownership. | Prevents an unrelated queued writer from blocking the exact reload task. |
| `src/com/android/launcher3/LauncherProvider.java` | Gate `executeControllerTask` before direct controller mutations and defer tokenless work. | Covers the provider's same-executor path. |
| `src/com/android/launcher3/provider/LauncherDbUtils.java` | Lease-owning transaction overload; release after `endTransaction` in `finally`. | Covers commit/close ambiguity without early unlock. |
| `src/com/android/launcher3/model/GridSizeMigrationUtil.java` | One transaction for fast/general copy + target-wide `UNKNOWN`; normalized source/target; prefs only after success. | ADR-0004. |
| `src/com/android/launcher3/provider/RestoreDbTask.java` | Outer reentrant lease; profile behavior unchanged except copied lock column. | Shared serialization. |
| `lawnchair/src/app/lawnchair/backup/LawnchairBackup.kt`, `lawnchair/src/app/lawnchair/deck/LawndeckManager.kt` | Lease plus model/DB quiesce-close-replace-reopen ordering for raw files. | Prevent open-handle races. |
| `lawnchair/src/com/android/launcher3/OrganizerModelReloadAdapter.kt`, `src/com/android/launcher3/LauncherModel.java`, `src/com/android/launcher3/model/LoaderTask.java`, `src/com/android/launcher3/model/BaseLauncherBinder.java` | Same-package internal adapter, token-aware non-blocking LoaderTask gate, and exact bind completion/cancellation signal. | Causal reload verification without a public helper or executor deadlock. |
| `tests/unit/app/lawnchair/organizer/application/**` (new) | Contract, canonical, lifecycle, protocol suites through the public seam. | AC-13. |
| `build.gradle`, `tests/organizer-instrumentation/**`, `tools/organizer-recovery-smoke.sh` | Wire only the dedicated platform-runner source set and host process-death smoke. No external dependency. After the command succeeds on a clean checkout/CI, add it to `building.md`; until then it is PR evidence, not a repository-wide required command. | Real restart evidence without importing the unwired legacy `tests/src` tree. |

No dependency, permission, network, telemetry, UI, planner, or trigger changes.

## Migration and recovery

### Schema 32 -> 33 (Launcher DB)

- Fresh 33: column default `1` (`UNLOCKED`); every normal new row is explicit and usable.
- Upgrade `case 32`: inside the framework transaction, `ALTER TABLE favorites ADD COLUMN organizerLockState INTEGER NOT NULL DEFAULT 1;` then `UPDATE favorites SET organizerLockState = 0;`. On `SQLException`, **throw** so the framework transaction rolls back; the schema-32 DB/layout is unchanged and the organizer remains unavailable. The baseline `createEmptyDB(db)` wipe is explicitly not reached because the case throws rather than `break`ing.
- `case 33`: `return;` (success).
- Every active and inactive grid DB in `LauncherFiles.GRID_DB_FILES` is normalized to 33 before it can be an organizer source; legacy rows become `UNKNOWN`.

### Schema 33 -> 32 (portable downgrade)

- `downgrade_schema.json` `version: 33`; `downgrade_to_32` array performs `ALTER TABLE favorites RENAME TO temp_favorites;`, `CREATE TABLE favorites(<exact 19 baseline columns in order>);`, `INSERT INTO favorites(...) SELECT <exact 19 columns> FROM temp_favorites;`, `DROP TABLE temp_favorites;`. No `DROP COLUMN`.
- Re-upgrade (32 -> 33 after downgrade): all pre-existing rows become `UNKNOWN`; lock truth is never interpreted as `UNLOCKED` from a lost column.

### 33 -> 32 -> 33 unknown-state behavior

- Tests execute the full cycle and prove (a) layout rows survive, (b) the final `organizerLockState` is `0` for every pre-existing row, (c) fresh rows still default to `1`. Organizer remains fail-closed until Issue #38 review resolves every `UNKNOWN` row.

### Grid migration

> Corrective implementation ownership: [Issue #59](../59-preserve-source-grid-migration-failure/spec.md) owns the source-preserving grid-migration failure fix; this historical plan remains unchanged.

- `ModelDbController` keeps `oldHelper` installed while a local target helper is
  prepared. Opening each helper performs/validates schema 33 before any copy.
- Refactor the current growth fast path and general path so copy, placement,
  target-wide `UPDATE ... organizerLockState = 0`, and temp-table cleanup occur
  before the same target transaction commits.
- Only after successful close does the controller install the target helper,
  close the old helper, and write `destDeviceState` preferences. Remove the
  current unconditional preference write from `finally`.
- On any open/copy/update/close failure, close/discard the target helper, retain
  the old helper and preferences, and return the typed migration failure. The
  organizer-aware path never calls `createEmptyDB`.

### Recovery DB lifecycle

- Separate private SQLite DB `organizer_recovery.db`, format 1 and exact DDL
  above, independent of `DatabaseHelper.SCHEMA_VERSION`.
- Lifecycle states per spec §“Recovery record and lifecycle”; transitions are single recovery-DB transactions; read-after-write validation after every checkpoint commit.
- Restart reconciliation (`RestartReconciler`) runs before any new `apply`/`recover` and resolves every non-final record using persisted pre/post/current intent + digests, never process memory.
- Retention: 24 h, max three capacity-bearing points, never expire unresolved, final non-restorable records may be moved to exact tombstones during admission, and `RECOVERY_POINT_ADMISSION_BLOCKED` is returned when three unresolved points block cleanup.
- Corruption: checksum mismatch -> `CORRUPT`; payload quarantined/deleted without touching Launcher state.
- Incompatible format: the read-only pre-open version gate returns
  `INCOMPATIBLE_VERSION` for newer data without invoking helper downgrade;
  future older compatible formats migrate only inside the recovery DB.
- Backup exclusion: proven by `BackupExclusionTest` (ZIP contents + `backupscheme.xml` + round-trip restore).

### Failure injection

`FaultInjector` is an internal port with `NOOP` (production) and a deterministic test double. Injection points (every one has a before-commit and after-commit variant, plus a process-death variant where the spec calls for it):

- before/after every recovery lifecycle commit (`CREATING`, `READY`, `APPLYING`, `COMMITTED_UNVERIFIED`, `VERIFIED`, `RESTORING`, `RESTORED`, `EXPIRED`, tombstone cleanup);
- before/after the Nth Launcher write inside the A6 transaction;
- at transaction close / unknown outcome (`SQLiteException` from `endTransaction`, process death between `setTransactionSuccessful` and `endTransaction`);
- before/after model reload request and before/after the correlated-generation wait;
- before/after independent DB recapture and before/after verification;
- serialization contention (another lease acquired mid-protocol);
- restart boundary represented before/after every durable phase in temp-DB tests,
  plus real process death at SA-12/13/14 and interrupted recovery in the emulator smoke;
- grid migration success/failure and migration-target `UNKNOWN` marking;
- recovery-store read/write/checksum failure;
- cleanup/retention transaction failure;
- lock-state column read failure (missing/NULL/out-of-range) at A2 and A5 and at recovery preflight.

Each injection asserts the exact typed public result and the resulting (Launcher DB, recovery DB) state.

### Feature disable / release rollback

- Feature disable: no application composition root is registered. The coordinator
  still serializes existing runtime writers (a correctness-only ordering change)
  but never returns organizer results or touches the recovery DB. Schema 33 and
  `UNKNOWN` migration remain data compatibility requirements, not a runtime
  feature toggle.
- Release rollback (33 -> 32): the portable `downgrade_to_32` rebuild preserves layout rows; recovery DB may be discarded; the Launcher layout is untouched.
- Migration rollback: a failed 32 -> 33 throws and leaves the schema-32 DB authoritative; the organizer is unavailable until the migration succeeds.
- Rollback authority: the Launcher DB remains the source of truth for the current layout; the recovery DB is a derived, separately-versioned store.

## Verification

| Acceptance criterion | Automated/manual evidence | Command or environment |
|---|---|---|
| AC-1 stale/full-precondition at A2 and inside A5/recovery | `ApplyProtocolTest` plus instrumented `LayoutWriteCoordinatorTest` race coverage. | Unit + instrumentation. |
| AC-2 checkpoint commits/validates before mutation; survives rollback/death | Instrumented `RecoveryStoreLifecycleTest` reopens both DBs; emulator smoke covers real process death. | Instrumentation + API 36.1 smoke. |
| AC-3 empty diff is `NoChanges`, zero writes/reloads | `ApplyProtocolTest` “empty diff”; write/reload counters on `FakeLayoutWriter`/`FakeRecoveryStore`. | Unit test. |
| AC-4 all-or-nothing; ordinary rollback is `RolledBack` | `ApplyProtocolTest` Nth-write failure plus instrumented coordinator test. | Unit + instrumentation. |
| AC-5 pre/post/neither and all lifecycle states reconcile without in-memory plan | `LifecycleReconcilerTest`, instrumented DB reopen tests, and recorded emulator death smoke. | Unit + instrumentation + smoke. |
| AC-6 correlated reload + independent recapture + invariants | `ApplyProtocolTest` “reload/convergence”; `ModelReloadAdapter` token-strictness test. | Unit. |
| AC-7 post-commit reload/verify failure -> recovery, never false success | `ApplyProtocolTest` reload/recapture/verify/recovery-write/recovery-reload/recovery-verify injection matrix. | Unit. |
| AC-8 recovery binds to reviewed revision; row-accounted with exact preconditions | `RecoveryProtocolTest` unchanged-then-changed layouts incl. added rows and container references. | Unit. |
| AC-9 multiple new pages/folders unique, overflow-safe, rollback-invisible, no page row | `ApplyProtocolTest` ID exhaustion/rollback plus instrumented DB/cache assertions. | Unit + instrumentation. |
| AC-10 lock-state missing is fail-closed; profiles/widgets/folders/app pairs round-trip | `RecoveryProtocolTest` “LOCK_STATE_UNAVAILABLE”; resource-matrix fixture test. | Unit. |
| AC-11 recovery DB absent from ZIP/Android backup; incompatible version does not touch layout | `BackupExclusionTest`; `RecoveryStoreLifecycleTest` “INCOMPATIBLE”. | Instrumented. |
| AC-12 24 h / max-three retention atomic; final tombstones do not consume capacity; never removes unresolved | `RetentionPolicyTest`, fake-store admission, and production recovery-store transaction coverage with fake clock. | Unit + instrumentation. |
| AC-13 production + fault-injection adapters exercised only through the same seam | Shared fakes driven by `ApplyProtocolTest`/`RecoveryProtocolTest`, `PublicSeamShapeTest`, and instrumented `OrganizerRecoveryInstrumentationTest` through public `apply`/`recover`. | Unit + instrumentation. |
| AC-14 every recovery-store/lifecycle write failure and `CREATING` crash boundary typed and restart-reconcilable | `RecoveryStoreLifecycleTest` owns the complete production-store before/after matrix and recovery-DB reopen; public-seam fault tests own typed results and Launcher-state preservation; the API 36.1 smoke owns real process death and both-DB reopen for `READY`, commit ambiguity, `COMMITTED_UNVERIFIED`, and `RESTORING`. Do not duplicate the lifecycle matrix in each layer. | Unit + instrumentation + API 36.1 smoke. |
| AC-15 recovery rollback preserves/reports reviewed-current; contention typed | `RecoveryProtocolTest` plus instrumented coordinator contention. | Unit + instrumentation. |

### SA-01–SA-25 coverage (no normative text duplicated)

SA-01 `ApplyProtocolTest.happyPath`; SA-02 `emptyDiff`; SA-03 `a2StalePerDimension`; SA-04 `a5PreconditionRace`; SA-05 `checkpointCreateReadbackVersionChecksumFailure` + `RecoveryStoreLifecycleTest`; SA-06 `idExhaustion` + `uniquePageFolderIds`; SA-07 `nthWriteFailurePreState`; SA-08 `commitUnknownPostState`; SA-09 `commitUnknownNeither`; SA-10 `reloadOrConvergenceFailure`; SA-11 `postApplyInvariantMismatch`; SA-12 `restartReadyCheckpointPrune`; SA-13 `restartAroundCommitClassification`; SA-14 `restartAfterCommitVerifyOrRecover`; SA-15 `lockStateUnavailableApplyAndRecovery`; SA-16 `recoveryMatchingReviewedRevision`; SA-17 `recoveryStaleRevision`; SA-18 `rowCreatedAfterCheckpoint`; SA-19 `recoveryCommitRollbackOrDeath`; SA-20 `recoveryStoreOrLifecycleFailurePerPhase`; SA-21 `repeatRecoveryAlreadyRestored`; SA-22 `missingExpiredCorruptIncompatible`; SA-23 `concurrentRun`; SA-24 `externalWriterBusy` + `LayoutWriteCoordinatorTest`; SA-25 `retentionLimitDuringUnresolved`.

### Scenario matrix (Stage A packet)

Upgrade 32 -> 33, fresh 33, 33 -> 32 -> 33, schema-mismatched inactive grid DB, successful/failed grid migration, active-only Lawnchair ZIP restore, Android profile remap/deletion, folder, Dock, widget, app pair, unavailable profile, and `UNKNOWN` lock fixtures are covered by `DatabaseHelperSchema33Test`, `DowngradeSchema33Test`, `InactiveGridDbNormalizationTest`, `GridMigrationUnknownMarkingTest`, `BackupExclusionTest`, `RestoreProfileRemapTest`, and the resource-matrix tests under `RecoveryProtocolTest`.

### Concurrency and process-restart tests

`LayoutWriteCoordinatorTest` proves every inventoried runtime writer shares the
lease and contention is typed. Temp-DB tests simulate every durable boundary;
the emulator smoke uses `am force-stop` for checkpoint-only, around-commit,
committed-unverified, and interrupted-recovery boundaries.

### Smallest vertical implementation sequence (tests first)

The sequence is intentionally vertical and incremental; no single monolithic commit, no duplication of the Issue #11 planner harness.

1. Public seam shape + canonical types + digest/revision (tests: `PublicSeamShapeTest`, `RevisionCalculatorTest`). Implementation: `public/`, `canonical/`, `revision/`.
2. Lifecycle state machine + reconciler + retention (tests: `LifecycleReconcilerTest`, `RetentionPolicyTest`). Implementation: `lifecycle/`.
3. Recovery store on a private SQLite DB with format version 1 (tests: `RecoveryStoreLifecycleTest`). Implementation: `store/`.
4. Apply protocol A0–A8 with `FakeLayoutWriter`/`FakeRecoveryStore` and full `FaultInjector` matrix (tests: `ApplyProtocolTest`, `RecoveryProtocolTest`). Implementation: `protocol/`, `Ports.kt`.
5. Launcher adapter + `LayoutWriteCoordinator` + `ModelReloadAdapter` (tests: `LayoutWriteCoordinatorTest`, model reload token test). Implementation: `adapter/`.
6. Schema 33 migration + portable downgrade + grid migration `UNKNOWN` marking + restore/backup bridges (tests: schema/downgrade/grid/restore/backup suites). Implementation: upstream changes listed above.
7. End-to-end emulator smoke at SA-12/13/14 and interrupted recovery; record
   commands and observed DB/recovery lifecycle evidence in the PR. No new test
   source set or runtime-only production hook is introduced.

The implementation follows these vertical responsibility boundaries. The final
change is reviewed and verified as one Issue #14 delivery.

## Documentation updates

- [x] amended spec and this plan are accepted after renewed Spec/Standards review; Stage B must not invent API.
- [x] CONTEXT.md — no domain-language change required.
- [x] DESIGN.md — no structural change required; `LayoutWriteCoordinator` implements the existing §4.4 model-write adapter seam.
- [x] ADR — ADR-0003 was aligned with the accepted resource-model correction; no new ADR was triggered.
- [x] AGENTS.md — no repository-wide instruction change required.

## Execution checklist

- [x] Current behavior reproduced (baseline evidence above).
- [x] Regression tests cover the missing behavior and reviewed failure paths.
- [x] Minimal implementation completed per vertical responsibility.
- [x] Migration/recovery verified (schema 33, downgrade, grid, restore, backup exclusion).
- [x] Full relevant verification completed (`spotlessCheck`, debug APK, pure unit suites, dedicated instrumentation suites, repo-contract validator, recorded emulator smoke).
- [x] PR evidence prepared with `Closes #14` against the AC table.

## Commands

Verified toolchain per [docs/engineering/building.md](../../docs/engineering/building.md): JDK 21.0.12, Gradle Wrapper 9.3.0, AGP 9.0.1, Kotlin 2.2.21, Android SDK 36.1, Build Tools 36.1.0, Platform Tools 37.0.1, source JVM target 17.

```bash
git submodule update --init --recursive
./gradlew spotlessCheck
./gradlew assembleLawnWithQuickstepGithubDebug
./gradlew testLawnWithQuickstepGithubDebugUnitTest \
  --tests "app.lawnchair.organizer.application.*"
./gradlew testLawnWithQuickstepGithubDebugUnitTest \
  --tests "app.lawnchair.organizer.application.protocol.ApplyProtocolTest" \
  --tests "app.lawnchair.organizer.application.protocol.RecoveryProtocolTest"
./gradlew connectedLawnWithQuickstepGithubDebugAndroidTest
# Optional focused rerun used by the process-death smoke driver:
./gradlew connectedLawnWithQuickstepGithubDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.android.launcher3.organizer.OrganizerRecoveryInstrumentationTest
tools/organizer-recovery-smoke.sh --serial <api-36.1-emulator-serial>
# Repository contract gates (Markdown links, Issue-form YAML, required files):
python3 tools/repo-contract/validate_repo_contract.py
python3 tools/repo-contract/test_validate_repo_contract.py
```

### Emulator recovery smoke scenario

1. Boot a clean API 36.1 emulator and pass its serial to the host script. The
   script builds/installs the GitHub debug app and dedicated test APK and
   confirms package paths with `adb shell pm path`; it never assumes an APK
   filename.
2. The instrumentation driver seeds mixed app/folder/Dock/widget/app-pair/
   profile/`LOCKED` data and invokes only `apply`/`recover`.
3. For `READY`, around Launcher commit, `COMMITTED_UNVERIFIED`, and
   `RESTORING`, the test-only `FaultInjector` emits a readiness marker. The host
   script force-stops the target package, then invokes verify-only mode after
   restart.
4. Verify-only mode reopens both DBs and asserts the exact reconciled typed
   result/lifecycle. The final recovery case asserts `Restored` only after the
   exact-token reload and independent DB/model verification.
5. The PR records serial/API/ABI, commands, pass/fail summary, and filtered
   lifecycle logs only; no row contents or private layout data are retained.

## Stage B handoff

Stage B implements steps (1)–(7) as reviewable test-first commits on one Issue
#14 branch and one PR containing `Closes #14`; each commit leaves the relevant
focused suite green. It must not add a public result, dependency, permission,
network use, UI, trigger, or planner change. Any such need is a stop condition.

## Change history

- 2026-08-14: Stage B implemented. Production correctness and repository
  standards reviews passed; unit/build/contract gates, API 36.1 instrumentation,
  and four-phase process-death recovery smoke passed.
- 2026-08-11: Stage A `proposed` plan. Closed shared writer serialization, correlated reload, recovery DB format/lifecycle, schema-33 non-wiping migration, portable downgrade, 33->32->33 unknown-state behavior, grid normalization, grid-migration `UNKNOWN` marking, row-accounted apply/recovery, complete failure injection, AC/SA evidence mapping, vertical implementation sequence, exact commands, and rollback. No accepted public result, DESIGN seam, or ADR is changed; no destructive fallback is required; no stop condition is triggered.
