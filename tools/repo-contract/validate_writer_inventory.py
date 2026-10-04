#!/usr/bin/env python3
"""Executable writer-inventory source scan for NunuLauncher (ER-02).

Implements the Issue #60 executable allowlist rule: a CI-enforced check that
every ``favorites``/DB-file writer path in the source tree is accounted for
in a curated allowlist.  New writers must be added to the allowlist with a
lease-kind or documented lifecycle reason before they can pass CI.

Scan rules (heuristic, low false-positive by design):

  A. *Direct favorites SQL*: any file whose source contains a raw SQL string
     targeting the ``favorites`` table (``INSERT INTO favorites``,
     ``UPDATE favorites``, ``DELETE FROM favorites``, ``REPLACE INTO
     favorites``, ``ALTER TABLE favorites``, ``execSQL`` or
     ``compileStatement`` calls with ``favorites`` in the argument).

  B. *Direct db operations on favorites*: any file that calls
     ``.delete(TABLE_NAME,``, ``.update(TABLE_NAME,``, or
     ``mCallback.insertAndCheck`` (the ``TABLE_NAME`` constant resolves to
     ``LauncherSettings.Favorites.TABLE_NAME`` where this pattern appears).

  C. *ModelDbController mutation calls*: any file that calls an instance
     method of ``ModelDbController`` that mutates the database:
     ``insert``, ``delete``, ``update``, ``newTransaction``,
     ``createEmptyDB``, ``removeGhostWidgets``, ``deleteEmptyFolders``,
     ``deleteBadAppPairs``, ``deleteUnparentedApps``,
     ``closeActiveHelperForRestore``, ``refreshMaxItemIdFromCommittedRows``,
     ``migrateGridIfNeeded``, ``tryMigrateDB``.  Matches calls on variables
     named ``controller``, ``db``, ``mDbController``, or through the
     ``getModelDbController()`` accessor.

  D. *Raw launcher DB file operations*: any file that (i) references a
     launcher DB file path (``getDatabasePath``, ``LauncherFiles``,
     ``LAUNCHER_DB``, ``LAUNCHER_DB_FILE_NAME``, ``dbFile``) *and* (ii)
     performs a file-system operation on it (``.copyTo(``, ``.renameTo(``,
     ``.deleteRecursively(``, or ``.delete()`` on a ``getDatabasePath``
     result).

Every file matching a pattern above must appear in the ``ALLOWLIST`` dict
below.  A file that matches but is not on the allowlist causes a non-zero
exit.

Runs on Python 3.9+ with the standard library only.  Intended for CI
(``.github/workflows/ci.yml``) and local verification.
"""

from __future__ import annotations

import re
import sys
from pathlib import Path
from typing import Dict, List, Tuple

# ---------------------------------------------------------------------------
# Allowlist
# ---------------------------------------------------------------------------
# Mapping from relative file path to a list of (pattern_kind, reason) entries.
# pattern_kind matches a scan pattern category (see module docstring).
# Add a new entry when adding a new writer path, with a short reason
# identifying the lease kind or documented lifecycle.

ALLOWLIST: Dict[str, List[Tuple[str, str]]] = {
    # -- Direct favorites SQL (raw SQL strings) --
    "src/com/android/launcher3/provider/RestoreDbTask.java": [
        ("favorites-sql", "Restore: INSERT INTO favorites SELECT * FROM favorites_old"),
        ("favorites-db",
         "Restore: db.delete/update on Favorites.TABLE_NAME during sanitizeDB"),
        ("controller-call",
         "Restore: .getModelDbController().closeActiveHelperForRestore() "
         "during restore lifecycle"),
        ("db-file-delete", "Restore cleanup: delete old DB file after restore"),
    ],
    "src/com/android/launcher3/model/GridSizeMigrationUtil.java": [
        ("favorites-sql", "Grid migration: UPDATE favorites SET cellX/cellY"),
    ],
    "src/com/android/launcher3/model/DatabaseHelper.java": [
        ("favorites-sql",
         "Schema upgrade: ALTER TABLE favorites, UPDATE favorites "
         "(rank, organizerLockState, appWidgetProvider)"),
        ("favorites-db",
         "Schema upgrade: db.delete on favorites table during schema cleanup"),
    ],
    "src/com/android/launcher3/provider/LauncherDbUtils.java": [
        ("favorites-sql",
         "DB utility: execSQL ALTER TABLE favorites DROP COLUMN during schema repair"),
        ("favorites-db",
         "DB utility: db.update/delete on Favorites.TABLE_NAME during shortcut migration"),
    ],
    "lawnchair/src/app/lawnchair/backup/NovaBackupConverter.kt": [
        ("favorites-sql", "Backup restore: INSERT INTO favorites from Nova backup"),
        ("db-file-copy", "Backup restore: copyTo staged DB file to launcher DB"),
    ],
    # -- Direct db operations on favorites table --
    "src/com/android/launcher3/AutoInstallsLayout.java": [
        ("favorites-db",
         "Bootstrap layout: mDb.delete/update on favorites via LayoutParserCallback"),
    ],
    "src/com/android/launcher3/DefaultLayoutParser.java": [
        ("favorites-db",
         "Bootstrap layout: mCallback.insertAndCheck on favorites table"),
    ],
    # -- ModelDbController mutation calls --
    "src/com/android/launcher3/LauncherProvider.java": [
        ("controller-call",
         "ContentProvider gateway: controller.insert/delete/update via "
         "MODEL_EXECUTOR + LayoutWriteCoordinator.runOrDeferWithOperationFuture"),
    ],
    "src/com/android/launcher3/model/ModelWriter.java": [
        ("controller-call",
         "Model writer: controller.newTransaction()/delete/update via "
         "executeOnModelThread gate (LayoutWriteCoordinator.runOrDefer)"),
        ("favorites-db",
         "Model writer: db.delete(TABLE_NAME, ...) on ModelDbController db var"),
    ],
    "src/com/android/launcher3/model/LoaderCursor.java": [
        ("controller-call",
         "Loader cursor: controller.delete/update during model load/restore"),
        ("favorites-db",
         "Loader cursor: .getModelDbController().delete(TABLE_NAME, ...)"),
    ],
    "src/com/android/launcher3/model/LoaderTask.java": [
        ("controller-call",
         "Loader task: .getModelDbController().deleteEmptyFolders, "
         "deleteBadAppPairs, deleteUnparentedApps, removeGhostWidgets "
         "via LayoutWriteCoordinator.runOrDefer"),
    ],
    "src/com/android/launcher3/util/ContentWriter.java": [
        ("controller-call",
         "Content writer utility: mDbController.update(Favorites.TABLE_NAME, ...) "
         "via commit()"),
        ("favorites-db",
         "Content writer: mDbController.update(Favorites.TABLE_NAME, ...)"),
    ],
    "lawnchair/src/app/lawnchair/organizer/application/adapter/LauncherLayoutAdapter.kt": [
        ("controller-call",
         "Organizer writer: controller.newTransaction(lease.token) via "
         "LayoutWriteCoordinator"),
        ("favorites-db",
         "Organizer writer: db.update/delete/insert on Favorites.TABLE_NAME "
         "through controller transaction"),
    ],
    "lawnchair/src/app/lawnchair/organizer/locks/adapter/LockStateDbAdapter.kt": [
        ("controller-call",
         "Lock state writer: controller.newTransaction(lease.token()) via "
         "LayoutWriteCoordinator"),
        ("favorites-db",
         "Lock state writer: tx.db.update(Favorites.TABLE_NAME, ...) "
         "through controller transaction"),
    ],
    # -- Raw launcher DB file operations --
    "lawnchair/src/app/lawnchair/LawnchairApp.kt": [
        ("db-file-rename", "DB rename: renameTo for restored DB migration"),
        ("db-file-copy", "DB migration: copyTo/delete on old DB files"),
    ],
    "lawnchair/src/app/lawnchair/backup/LawnchairBackup.kt": [
        ("db-file-recursive", "Backup: deleteRecursively on launcher DB directory"),
    ],
    "lawnchair/src/app/lawnchair/deck/LawndeckManager.kt": [
        ("db-file-copy", "Lawndeck: copyTo for DB backup/restore"),
    ],
    "src/com/android/launcher3/LauncherBackupAgent.java": [
        ("db-file-delete",
         "Backup agent: destination.delete() on obsolete backup file"),
    ],
    "src/com/android/launcher3/InvariantDeviceProfile.java": [
        ("db-file-delete",
         "Grid: delete old grid DB files during grid migration"),
    ],
    # -- ModelDbController self-mutations --
    "src/com/android/launcher3/model/ModelDbController.java": [
        ("favorites-db",
         "ModelDbController: db.delete(TABLE_NAME, ...) in deleteEmptyFolders, "
         "deleteBadAppPairs, deleteUnparentedApps"),
    ],
}

# ---------------------------------------------------------------------------
# Scan patterns
# ---------------------------------------------------------------------------

# A: Direct favorites SQL in raw SQL strings.
# Matches execSQL/compileStatement with "favorites" in the argument,
# and raw SQL keywords like INSERT INTO favorites, UPDATE favorites, etc.
_FAVORITES_SQL_RE = re.compile(
    r"(?i)"
    r"(?:execSQL\s*\([^)]*favorites"
    r"|compileStatement\s*\([^)]*favorites"
    r"|(?:INSERT\s+INTO|UPDATE|DELETE\s+FROM|REPLACE\s+INTO|ALTER\s+TABLE)\s+favorites"
    r")",
    re.MULTILINE,
)

# B: Direct db operations on favorites via the TABLE_NAME constant.
# Matches .delete(TABLE_NAME, or .update(TABLE_NAME, or
# .delete(Favorites.TABLE_NAME, or .update(Favorites.TABLE_NAME,
_FAVORITES_DB_RE = re.compile(
    r"\.(?:delete|update)\s*\(\s*(?:Favorites\.)?TABLE_NAME",
    re.MULTILINE,
)

# B (alt): insertAndCheck callback pattern used by bootstrap layout.
_INSERT_AND_CHECK_RE = re.compile(
    r"mCallback\.insertAndCheck\s*\(",
    re.MULTILINE,
)

# C: ModelDbController mutation method calls.
# Matches controller.method(), mDbController.method(),
# .getModelDbController().method().
# NOTE: \bdb\b is deliberately NOT included because it is too broad --
# many utility files use "db" for a raw SQLiteDatabase, not a ModelDbController.
# Those files are caught by the favorites-db or favorites-sql patterns instead.
_CONTROLLER_CALL_RE = re.compile(
    r"(?:controller\.|mDbController\.|\.getModelDbController\(\)\.)"
    r"(?:insert|delete|update|newTransaction|createEmptyDB"
    r"|removeGhostWidgets|deleteEmptyFolders"
    r"|deleteBadAppPairs|deleteUnparentedApps"
    r"|closeActiveHelperForRestore|refreshMaxItemIdFromCommittedRows"
    r"|migrateGridIfNeeded|tryMigrateDB"
    r")\s*\(",
    re.MULTILINE,
)

# D: Launcher DB file path references (used to qualify DB file operations).
_DB_PATH_REF_RE = re.compile(
    r"(?:getDatabasePath|LauncherFiles|LAUNCHER_DB|LAUNCHER_DB_FILE_NAME|\bdbFile\b)",
    re.MULTILINE,
)

# D1: Direct DB file deletion via getDatabasePath(...).delete().
_DB_FILE_DELETE_RE = re.compile(
    r"getDatabasePath\s*\([^)]*\)\s*\.\s*delete\s*\(",
    re.MULTILINE | re.DOTALL,
)

# D2-4: File operations on launcher DB files -- handled at the line level
# in scan_file() below.

# ---------------------------------------------------------------------------
# Allowlist index
# ---------------------------------------------------------------------------

# Build a reverse index: which pattern_kind values does each allowlisted file
# claim?  (A file whose entry has no pattern_kind matching the scan is flagged
# as a warning — the entry may be stale or too imprecise.)
_ALLOWED_FILES: Dict[str, List[str]] = {
    path: [kind for kind, _ in entries] for path, entries in ALLOWLIST.items()
}


def _make_source_path_set(source_dirs: List[Path]) -> List[Path]:
    """Collect all source files under *source_dirs*, excluding test dirs."""
    files: List[Path] = []
    for sd in source_dirs:
        if not sd.is_dir():
            continue
        for f in sd.rglob("*"):
            if f.suffix not in (".java", ".kt"):
                continue
            # Skip test directories using any common pattern.
            rel = str(f)
            if "/test/" in rel or "/test_" in rel or "/Test" in rel or "/tests/" in rel:
                continue
            files.append(f)
    return files


def _relative_path(root: Path, file: Path) -> str:
    """Return the path of *file* relative to the repository root."""
    try:
        return str(file.relative_to(root))
    except ValueError:
        return str(file)


def scan_file(file: Path, root: Path) -> List[str]:
    """Scan a single file for writer patterns.

    Returns a list of pattern_kind strings for every match found.
    """
    text = file.read_text(encoding="utf-8", errors="replace")
    lines = text.splitlines()
    matches: List[str] = []  # stores "pattern_kind" strings

    # A: Direct favorites SQL
    if _FAVORITES_SQL_RE.search(text):
        matches.append("favorites-sql")

    # B: Direct db ops on favorites table
    if _FAVORITES_DB_RE.search(text) or _INSERT_AND_CHECK_RE.search(text):
        matches.append("favorites-db")

    # C: ModelDbController mutation calls
    if _CONTROLLER_CALL_RE.search(text):
        matches.append("controller-call")

    # D: Raw DB file operations (line-level: check if the file operation
    # line or nearby lines reference a launcher DB file path).
    has_db_path_ref = bool(_DB_PATH_REF_RE.search(text))

    # D1: Direct DB file deletion via getDatabasePath(...).delete().
    # This is already specific enough to match at the file level.
    if _DB_FILE_DELETE_RE.search(text):
        matches.append("db-file-delete")

    # D2-4: copyTo, renameTo, deleteRecursively -- check at the line
    # level so that unrelated file operations on non-DB paths are not
    # flagged.
    if has_db_path_ref:
        for i, line in enumerate(lines):
            op_match = re.search(r"\.(copyTo|renameTo|deleteRecursively)\s*\(", line)
            if op_match:
                op = op_match.group(1)  # "copyTo", "renameTo", or "deleteRecursively"
                # Check nearby lines (2 above, 2 below) for a DB path ref.
                start = max(0, i - 2)
                end = min(len(lines), i + 3)
                context = "\n".join(lines[start:end])
                if _DB_PATH_REF_RE.search(context):
                    kind = "db-file-" + {
                        "copyTo": "copy",
                        "renameTo": "rename",
                        "deleteRecursively": "recursive",
                    }[op]
                    if kind not in matches:
                        matches.append(kind)

    # Deduplicate but preserve order.
    seen: set = set()
    return [m for m in matches if not (m in seen or seen.add(m))]


def run_scan(source_dirs: List[Path], root: Path) -> int:
    """Run the writer inventory scan across all source dirs.

    Returns 0 on success, 1 on failure.
    """
    errors: List[str] = []
    warnings: List[str] = []
    tested: List[str] = []

    files = _make_source_path_set(source_dirs)
    for file in files:
        rel = _relative_path(root, file)
        matches = scan_file(file, root)

        if not matches:
            continue  # Not a writer file — fine.

        if rel not in _ALLOWED_FILES:
            errors.append(
                f"{rel}: matches writer pattern(s) {matches} but is NOT in the "
                "allowlist. Add it to ALLOWLIST in "
                "tools/repo-contract/validate_writer_inventory.py with a "
                "lease-kind or documented lifecycle reason."
            )
        else:
            allowed_kinds = _ALLOWED_FILES[rel]
            # Check that each matched pattern_kind is claimed by the allowlist entry.
            for match in matches:
                if match not in allowed_kinds:
                    errors.append(
                        f"{rel}: matches pattern {match!r} but the allowlist entry "
                        f"only claims {allowed_kinds}. Update the ALLOWLIST entry "
                        f"to include {match!r} or confirm the match is a false "
                        "positive."
                    )
            # Warn if the allowlist entry claims a kind that the scan didn't match.
            # The file may still be a legitimate writer (e.g. it routes through a
            # different mechanism), so this is only a warning.
            for claimed in allowed_kinds:
                if claimed not in matches:
                    warnings.append(
                        f"{rel}: allowlist claims {claimed!r} but scan did not "
                        "detect it. Either the pattern is missed (false negative) "
                        "or the claim is stale."
                    )
            tested.append(rel)

    # Also check for stale allowlist entries (files that no longer exist).
    for rel in _ALLOWED_FILES:
        full_path = root / rel
        if not full_path.exists():
            errors.append(
                f"{rel}: allowlist entry points to a file that no longer exists. "
                "Remove or update the entry."
            )

    # Report
    if warnings:
        print("Warnings:")
        for w in warnings:
            print(f"  - {w}")
        print()

    if errors:
        print(f"FAIL: {len(errors)} writer inventory issue(s) found:")
        for e in errors:
            print(f"  - {e}")
        print()
        print(
            f"Scanned {len(files)} files, {len(tested)} allowlisted writer files "
            f"verified."
        )
        return 1

    print(
        f"PASS: {len(tested)} writer files verified against allowlist "
        f"({len(files)} source files scanned, 0 errors, {len(warnings)} warnings)."
    )
    return 0


def main(argv: List[str] = None) -> int:
    root = Path(__file__).resolve().parent.parent.parent  # tools/../.. = repo root
    source_dirs = [
        root / "src",
        root / "lawnchair" / "src",
    ]
    return run_scan(source_dirs, root)


if __name__ == "__main__":
    sys.exit(main())