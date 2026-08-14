#!/usr/bin/env python3
"""Self-tests for the high-risk independent-evidence gate.

Standard library only (``unittest``), so this runs identically in CI and on a
fresh checkout without network access or ``gh``. Run with:

    python3 tools/repo-contract/test_validate_high_risk_evidence.py

These tests are part of the acceptance evidence for Issue #43: they prove the
gate classifies high-risk PRs by label and path, rejects audit records with
missing or malformed evidence fields, and only accepts Actions run URLs that
point at this repository. The GitHub API and git-lineage checks are thin
wrappers exercised end to end by the workflow's PR demonstrations.
"""

from __future__ import annotations

import re
import tempfile
import unittest
from pathlib import Path

import validate_high_risk_evidence as gate

REPO = "nunu1733/NunuLauncher"
WORKFLOW_DOC = Path(__file__).resolve().parents[2] / "docs" / "project" / "github-workflow.md"

VALID_AUDIT = """# High-risk audit: PR #47 demo

> Status: accepted
> Audit date: 2026-08-14

- Auditor: independent-session agent (separate from the implementer)
- PR: https://github.com/nunu1733/NunuLauncher/pull/47
- Head SHA: 0123456789abcdef0123456789abcdef01234567
- CI run: https://github.com/nunu1733/NunuLauncher/actions/runs/1234567890
- Criteria: specs/13-safe-layout-application/spec.md FR-1, \
docs/adr/0003-organizer-recovery-point-storage.md

## Scope

Covered the transactional writer and correlated reload path.

## Criteria check

specs/13-safe-layout-application/spec.md FR-1 (transactional apply or rollback)
checked against the diff; ADR-0003 recovery-point requirements confirmed.

## Executed test surface

./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.*' -> pass

## Findings

No new shared-writer gaps.
"""


class ClassifyPrTests(unittest.TestCase):
    def test_risk_labels_trigger_high_risk(self) -> None:
        for label in gate.HIGH_RISK_LABELS:
            high, reasons = gate.classify_pr([label], ["README.md"])
            self.assertTrue(high, f"{label} must trigger the gate")
            self.assertTrue(any(label in r for r in reasons))

    def test_high_risk_paths_trigger_without_labels(self) -> None:
        for path in (
            "lawnchair/src/app/lawnchair/organizer/application/store/Foo.kt",
            "src/com/android/launcher3/provider/RestoreDbTask.java",
            "lawnchair/src/app/lawnchair/backup/LawnchairBackup.kt",
            "lawnchair/src/app/lawnchair/deck/LawndeckManager.kt",
            "src/com/android/launcher3/model/GridSizeMigrationUtil.java",
            "src/com/android/launcher3/model/LayoutWriteCoordinator.java",
            "src/com/android/launcher3/model/ModelWriter.java",
            "src/com/android/launcher3/model/ModelDbController.java",
            "src/com/android/launcher3/LauncherProvider.java",
        ):
            high, reasons = gate.classify_pr([], [path])
            self.assertTrue(high, f"{path} must trigger the gate")
            self.assertTrue(any("high-risk path" in r for r in reasons))

    def test_many_path_hits_are_collapsed(self) -> None:
        changed = [f"lawnchair/src/app/lawnchair/organizer/application/store/F{i}.kt" for i in range(5)]
        high, reasons = gate.classify_pr([], changed)
        self.assertTrue(high)
        self.assertEqual(len(reasons), 1)
        self.assertIn("5 total", reasons[0])

    def test_docs_and_planning_only_pr_is_low_risk(self) -> None:
        changed = [
            "AGENTS.md",
            "docs/project/github-workflow.md",
            "tools/repo-contract/validate_high_risk_evidence.py",
            ".github/workflows/ci.yml",
            # Pure planning code mutates no persisted layout state.
            "lawnchair/src/app/lawnchair/organizer/planning/Planner.kt",
            "tests/unit/app/lawnchair/organizer/application/store/FooTest.kt",
        ]
        high, _ = gate.classify_pr(["type: maintenance"], changed)
        self.assertFalse(high, "low-risk PR must not be burdened by the gate")

    def test_unrelated_labels_do_not_trigger(self) -> None:
        high, _ = gate.classify_pr(["risk: privacy", "status: review"], ["README.md"])
        self.assertFalse(high)


class FindAuditFileTests(unittest.TestCase):
    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.root = Path(self._tmp.name)
        (self.root / "docs" / "assessment").mkdir(parents=True)

    def tearDown(self) -> None:
        self._tmp.cleanup()

    def test_finds_audit_for_matching_pr_number(self) -> None:
        path = self.root / "docs" / "assessment" / "pr-47-transactional-writer.md"
        path.write_text(VALID_AUDIT, encoding="utf-8")
        found, error = gate.find_audit_file(self.root, 47)
        self.assertIsNone(error)
        self.assertEqual(found, path)

    def test_missing_audit_reports_error(self) -> None:
        found, error = gate.find_audit_file(self.root, 47)
        self.assertIsNone(found)
        self.assertIsNotNone(error)
        self.assertIn("pr-47", error)

    def test_other_pr_numbers_do_not_satisfy_this_pr(self) -> None:
        (self.root / "docs" / "assessment" / "pr-48-other.md").write_text(
            VALID_AUDIT, encoding="utf-8"
        )
        found, error = gate.find_audit_file(self.root, 47)
        self.assertIsNone(found)
        self.assertIsNotNone(error)

    def test_multiple_audits_are_ambiguous(self) -> None:
        d = self.root / "docs" / "assessment"
        (d / "pr-47-a.md").write_text(VALID_AUDIT, encoding="utf-8")
        (d / "pr-47-b.md").write_text(VALID_AUDIT, encoding="utf-8")
        found, error = gate.find_audit_file(self.root, 47)
        self.assertIsNone(found)
        self.assertIn("ambiguous", error)


class ParseAuditTests(unittest.TestCase):
    def test_valid_audit_has_no_findings(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "audit.md"
            path.write_text(VALID_AUDIT, encoding="utf-8")
            doc = gate.parse_audit(path)
        self.assertEqual(doc.findings, [], f"valid audit flagged: {doc.findings}")
        self.assertEqual(doc.head_sha, "0123456789abcdef0123456789abcdef01234567")
        self.assertEqual(doc.auditor, "independent-session agent (separate from the implementer)")
        self.assertEqual(doc.audit_date, "2026-08-14")
        self.assertEqual(
            doc.ci_run_urls,
            ["https://github.com/nunu1733/NunuLauncher/actions/runs/1234567890"],
        )
        self.assertIn("specs/13-safe-layout-application/spec.md", doc.criteria_refs)
        self.assertIn(
            "docs/adr/0003-organizer-recovery-point-storage.md", doc.criteria_refs
        )

    def _parse(self, text: str) -> gate.AuditDocument:
        import tempfile as _tempfile

        tmp = _tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        path = Path(tmp.name) / "audit.md"
        path.write_text(text, encoding="utf-8")
        return gate.parse_audit(path)

    def test_missing_head_sha_is_flagged(self) -> None:
        doc = self._parse(VALID_AUDIT.replace(
            "- Head SHA: 0123456789abcdef0123456789abcdef01234567\n", ""
        ))
        self.assertTrue(any("Head SHA" in f for f in doc.findings))

    def test_short_sha_is_rejected(self) -> None:
        doc = self._parse(VALID_AUDIT.replace(
            "0123456789abcdef0123456789abcdef01234567", "0123456789abcdef"
        ))
        self.assertTrue(any("Head SHA" in f for f in doc.findings))
        self.assertIsNone(doc.head_sha)

    def test_missing_ci_run_is_flagged(self) -> None:
        doc = self._parse(VALID_AUDIT.replace(
            "- CI run: https://github.com/nunu1733/NunuLauncher/actions/runs/1234567890\n",
            "",
        ))
        self.assertTrue(any("CI run" in f for f in doc.findings))
        self.assertEqual(doc.ci_run_urls, [])

    def test_missing_auditor_and_date_are_flagged(self) -> None:
        doc = self._parse(VALID_AUDIT.replace(
            "> Audit date: 2026-08-14\n", ""
        ).replace(
            "- Auditor: independent-session agent (separate from the implementer)\n", ""
        ))
        self.assertTrue(any("Auditor" in f for f in doc.findings))
        self.assertTrue(any("Audit date" in f for f in doc.findings))

    # The criteria/ID checks scan the whole document (IDs may live in the
    # Criteria check section), so these tests must scrub both the field line
    # and the body references.
    _CRITERIA_BODY = (
        "specs/13-safe-layout-application/spec.md FR-1 (transactional apply or rollback)\n"
        "checked against the diff; ADR-0003 recovery-point requirements confirmed."
    )

    def test_missing_criteria_reference_is_flagged(self) -> None:
        text = VALID_AUDIT.replace(
            "- Criteria: specs/13-safe-layout-application/spec.md FR-1, "
            "docs/adr/0003-organizer-recovery-point-storage.md\n",
            "- Criteria: FR-1\n",
        ).replace(self._CRITERIA_BODY, "checked against the diff.")
        doc = self._parse(text)
        self.assertTrue(any("criteria" in f for f in doc.findings))
        self.assertEqual(doc.criteria_refs, [])

    def test_criteria_without_requirement_ids_is_flagged(self) -> None:
        text = VALID_AUDIT.replace(
            "- Criteria: specs/13-safe-layout-application/spec.md FR-1, "
            "docs/adr/0003-organizer-recovery-point-storage.md\n",
            "- Criteria: specs/13-safe-layout-application/spec.md（受入条件全体）\n",
        ).replace(self._CRITERIA_BODY, "受入条件全体を確認した。")
        doc = self._parse(text)
        self.assertTrue(any("requirement IDs" in f for f in doc.findings))

    def test_missing_required_section_is_flagged(self) -> None:
        doc = self._parse(VALID_AUDIT.replace("## Findings\n", "## Results\n"))
        self.assertTrue(
            any("## Findings" in f for f in doc.findings),
            f"missing Findings section must be flagged: {doc.findings}",
        )

    def test_empty_required_section_is_flagged(self) -> None:
        text = VALID_AUDIT.replace(
            "## Scope\n\nCovered the transactional writer and correlated reload path.\n",
            "## Scope\n\n## Criteria check\n",
        )
        doc = self._parse(text)
        self.assertTrue(any("'## Scope' is empty" in f for f in doc.findings))

    def test_executed_surface_without_concrete_commands_is_flagged(self) -> None:
        doc = self._parse(VALID_AUDIT.replace(
            "./gradlew testLawnWithQuickstepGithubDebugUnitTest "
            "--tests 'app.lawnchair.organizer.*' -> pass",
            "unit tests -> pass",
        ))
        self.assertTrue(any("concrete commands" in f for f in doc.findings))

    def test_form_only_audit_cannot_pass(self) -> None:
        # The core Issue #43 requirement: a five-line audit with a valid CI
        # URL but no scope/results/commands/findings must not pass.
        form_only = "\n".join(
            [
                "# High-risk audit: PR #47 demo",
                "> Audit date: 2026-08-14",
                "",
                "- Auditor: independent session",
                f"- Head SHA: {'a' * 40}",
                f"- CI run: https://github.com/{REPO}/actions/runs/1234567890",
                "- Criteria: specs/13-safe-layout-application/spec.md",
                "",
                "## Scope",
                "## Criteria check",
                "## Executed test surface",
                "## Findings",
                "",
            ]
        )
        doc = self._parse(form_only)
        self.assertTrue(len(doc.findings) >= 5, doc.findings)
        # All four required sections are empty headers, not an audit.
        self.assertEqual(
            sum(1 for f in doc.findings if "is empty" in f), 4, doc.findings
        )
        # The criteria reference carries no requirement IDs either.
        self.assertTrue(any("requirement IDs" in f for f in doc.findings))


class DocConsistencyTests(unittest.TestCase):
    """The high-risk path list must not drift between code and the guide.

    github-workflow.md documents the trigger paths in prose and the validator
    enforces them in code (Issue #43 review finding). Instead of a second
    data source, this test parses the guide's list and requires set equality
    with the validator constants, so adding a path in one place forces the
    other to follow.
    """

    def test_doc_path_list_matches_validator(self) -> None:
        text = WORKFLOW_DOC.read_text(encoding="utf-8")
        match = re.search(r"^### 適用条件$(.*?)^### ", text, re.MULTILINE | re.DOTALL)
        self.assertIsNotNone(match, "github-workflow.md must keep a 適用条件 section")
        tokens = re.findall(r"`([^`]+)`", match.group(1))
        # Only production paths are listed as triggers; labels and module
        # shorthand (e.g. organizer/planning) are filtered by root.
        doc_prefixes = set()
        doc_files = set()
        for token in tokens:
            if not token.startswith(("lawnchair/src/", "src/")):
                continue
            if token.endswith("/**"):
                doc_prefixes.add(token[: -len("/**")] + "/")
            else:
                doc_files.add(token)
        self.assertEqual(
            doc_prefixes,
            set(gate.HIGH_RISK_PATH_PREFIXES),
            "path prefixes in github-workflow.md must match the validator; "
            "update both together",
        )
        self.assertEqual(
            doc_files,
            set(gate.HIGH_RISK_PATH_FILES),
            "exact file paths in github-workflow.md must match the validator; "
            "update both together",
        )


class ParseRunUrlTests(unittest.TestCase):
    def test_accepts_run_url_of_this_repo(self) -> None:
        run_id = gate.parse_run_url(
            "https://github.com/nunu1733/NunuLauncher/actions/runs/1234567890", REPO
        )
        self.assertEqual(run_id, 1234567890)

    def test_rejects_other_repo(self) -> None:
        run_id = gate.parse_run_url(
            "https://github.com/someone/else/actions/runs/1234567890", REPO
        )
        self.assertIsNone(run_id)

    def test_rejects_non_run_url(self) -> None:
        for url in (
            "https://github.com/nunu1733/NunuLauncher/pull/47",
            "https://example.com/actions/runs/1",
        ):
            self.assertIsNone(gate.parse_run_url(url, REPO))


if __name__ == "__main__":
    unittest.main(verbosity=2)
