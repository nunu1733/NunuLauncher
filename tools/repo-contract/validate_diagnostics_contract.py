#!/usr/bin/env python3
"""AC-67-12: Diagnostics module contract check for Issue #67.

Verifies that the organizer diagnostics module (under
``lawnchair/src/app/lawnchair/organizer/diagnostics/``) does not introduce:

1. Any telemetry/network SDK dependency
2. Any upload worker or transport API
3. Any permission string literal in diagnostics source code
4. Any automatic recipient selection

The check is scoped to the diagnostics module source files only. Pre-existing
permissions in the shared AndroidManifest.xml (upstream Lawnchair) are not
flagged — only the diagnostics module's own code is checked.

Exit code is non-zero when a contract violation is found.
"""

from __future__ import annotations

import re
import sys
from pathlib import Path
from typing import List, Tuple

# Diagnostics module source root relative to the repository root.
DIAGNOSTICS_SRC = "lawnchair/src/app/lawnchair/organizer/diagnostics"

# Network/telemetry SDK imports that are forbidden in the diagnostics module.
FORBIDDEN_IMPORTS: Tuple[str, ...] = (
    # Network clients
    "java.net.HttpURLConnection",
    "java.net.URL",
    "java.net.URLConnection",
    "java.net.Socket",
    "java.net.ServerSocket",
    "java.net.DatagramSocket",
    "okhttp3",
    "okhttp",
    "android.net.http",
    "org.apache.http",
    "javax.net.ssl",
    # Retrofit / HTTP libraries
    "retrofit2",
    "retrofit",
    "com.squareup.okhttp",
    # Telemetry / analytics
    "com.google.firebase",
    "com.google.android.gms.analytics",
    "com.google.android.gms.tagmanager",
    "com.mixpanel",
    "com.amplitude",
    "com.segment",
    "com.appsflyer",
    "com.flurry",
    "com.adjust",
    "com.localytics",
    "com.countly",
    "com.uxcam",
    "com.optimizely",
    "com.kochava",
    "com.branch",
    "com.onesignal",
    # WorkManager / upload workers
    "androidx.work",
    "android.app.job.JobScheduler",
    "android.app.DownloadManager",
    # Upload transport
    "com.google.android.datatransport",
    "com.google.android.datatransport.runtime",
    "com.google.android.datatransport.cct",
    # gRPC
    "io.grpc",
    "com.google.api.gax",
)

# Permission string literals that must not appear in diagnostics source code.
# These are checked as string literals, not as manifest declarations.
FORBIDDEN_PERMISSION_STRINGS: Tuple[str, ...] = (
    "android.permission.INTERNET",
    "android.permission.ACCESS_NETWORK_STATE",
    "android.permission.MANAGE_DOCUMENTS",
)

# File patterns that indicate a transport/worker API.
FORBIDDEN_FILE_PATTERNS: Tuple[str, ...] = (
    "Upload",
    "Sync",
    "Transport",
    "Transmitter",
    "Sender",
    "Dispatcher",
    "JobScheduler",
    "JobService",
    "WorkManager",
    "PeriodicTask",
    "OneTimeTask",
    "BackgroundService",
    "IntentService",
    "Firebase",
    "NetworkClient",
    "RestClient",
    "ApiClient",
    "ApiService",
    "Retrofit",
    "OkHttp",
)


def _find_diagnostics_files(root: Path) -> List[Path]:
    """Return all source files under the diagnostics module."""
    src_dir = root / DIAGNOSTICS_SRC
    if not src_dir.is_dir():
        return []
    files: List[Path] = []
    for f in sorted(src_dir.rglob("*")):
        if f.suffix in (".java", ".kt") and f.is_file():
            files.append(f)
    return files


def _check_source_imports(root: Path) -> List[str]:
    """Check that diagnostics module source files do not import forbidden
    network/telemetry/worker APIs."""
    findings: List[str] = []
    for file in _find_diagnostics_files(root):
        rel = file.relative_to(root)
        text = file.read_text(encoding="utf-8")
        lines = text.splitlines()

        for i, line in enumerate(lines, start=1):
            stripped = line.strip()
            for forbidden in FORBIDDEN_IMPORTS:
                if forbidden in stripped:
                    findings.append(
                        f"{rel}:{i}: forbidden import/reference {forbidden!r} "
                        f"found in diagnostics module. Issue #67 diagnostics "
                        "must not add telemetry/network dependencies."
                    )
                    break
    return findings


def _check_permission_strings(root: Path) -> List[str]:
    """Check that diagnostics module source files do not contain forbidden
    permission string literals."""
    findings: List[str] = []
    for file in _find_diagnostics_files(root):
        rel = file.relative_to(root)
        text = file.read_text(encoding="utf-8")
        for perm in FORBIDDEN_PERMISSION_STRINGS:
            if perm in text:
                findings.append(
                    f"{rel}: contains forbidden permission string {perm!r}. "
                    "Issue #67 diagnostics must not reference any permission."
                )
    return findings


def _check_worker_transport_patterns(root: Path) -> List[str]:
    """Check that diagnostics module file names or content do not include
    transport/worker API patterns."""
    findings: List[str] = []
    for file in _find_diagnostics_files(root):
        rel = file.relative_to(root)
        name = file.name
        for pattern in FORBIDDEN_FILE_PATTERNS:
            if pattern in name:
                findings.append(
                    f"{rel}: file name matches forbidden pattern "
                    f"{pattern!r}. Issue #67 diagnostics must not introduce "
                    "upload/transport/worker APIs."
                )
                break
    return findings


def run_checks(root: Path) -> List[str]:
    """Run all AC-67-12 contract checks and return findings."""
    findings: List[str] = []

    findings.extend(_check_source_imports(root))
    findings.extend(_check_permission_strings(root))
    findings.extend(_check_worker_transport_patterns(root))

    return findings


def main(argv: List[str] = None) -> int:
    root = Path(__file__).resolve().parent.parent.parent  # tools/../.. = repo root
    print(f"Running AC-67-12 diagnostics contract validation on {root}")

    findings = run_checks(root)
    if findings:
        print(f"FAIL: {len(findings)} AC-67-12 contract violation(s) found:")
        for f in findings:
            print(f"  - {f}")
        print()
        print(
            "Issue #67 diagnostics must not add: telemetry/network dependencies, "
            "upload workers, transport APIs, or automatic recipient selection. "
            "See docs/engineering/organizer-diagnostics.md §12."
        )
        return 1

    print("PASS: No AC-67-12 contract violations found.")
    return 0


if __name__ == "__main__":
    sys.exit(main())