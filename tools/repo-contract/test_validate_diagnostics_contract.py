#!/usr/bin/env python3
"""Self-tests for the Issue #67 diagnostics contract validator.

Standard library only (``unittest``), so this runs identically in CI and on a
fresh checkout. Run with:

    python3 tools/repo-contract/test_validate_diagnostics_contract.py

These tests prove that the validator catches the violations it is designed to
detect.
"""

from __future__ import annotations

import unittest
from pathlib import Path

import validate_diagnostics_contract as vdc

REPO_ROOT = Path(__file__).resolve().parents[2]


class DiagnosticsContractValidatorTests(unittest.TestCase):
    """Verify that the validator's individual checks work correctly."""

    def test_real_repo_diagnostics_module_has_no_forbidden_imports(self) -> None:
        """The diagnostics module source files must not import forbidden APIs."""
        findings = vdc._check_source_imports(REPO_ROOT)
        self.assertEqual(
            findings,
            [],
            f"Diagnostics module has forbidden imports: {findings}",
        )

    def test_real_repo_diagnostics_module_has_no_permission_strings(self) -> None:
        """The diagnostics module must not contain permission string literals."""
        findings = vdc._check_permission_strings(REPO_ROOT)
        self.assertEqual(
            findings,
            [],
            f"Diagnostics module has permission strings: {findings}",
        )

    def test_real_repo_diagnostics_module_has_no_worker_patterns(self) -> None:
        """The diagnostics module must not contain worker/transport patterns."""
        findings = vdc._check_worker_transport_patterns(REPO_ROOT)
        self.assertEqual(
            findings,
            [],
            f"Diagnostics module has forbidden patterns: {findings}",
        )

    def test_full_validation_passes_on_real_repo(self) -> None:
        """The full validation suite must pass on the real repository."""
        findings = vdc.run_checks(REPO_ROOT)
        self.assertEqual(
            findings,
            [],
            f"Full validation failed on real repo: {findings}",
        )


if __name__ == "__main__":
    unittest.main(verbosity=2)