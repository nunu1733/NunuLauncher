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
import unittest.mock
from pathlib import Path
from typing import List

import validate_high_risk_evidence as gate

REPO = "nunu1733/NunuLauncher"
REPO_ROOT = Path(__file__).resolve().parents[2]
WORKFLOW_DOC = REPO_ROOT / "docs" / "project" / "github-workflow.md"

VALID_AUDIT = """# High-risk audit: PR #47 demo

> Status: accepted
> Audit date: 2026-08-14

- Auditor: independent-session agent (separate from the implementer)
- PR: https://github.com/nunu1733/NunuLauncher/pull/47
- Head SHA: 0123456789abcdef0123456789abcdef01234567
- CI run: https://github.com/nunu1733/NunuLauncher/actions/runs/1234567890
- Criteria: specs/13-safe-layout-application/spec.md FR-004, \
docs/adr/0003-organizer-recovery-point-storage.md ADR-0003

## Scope

Covered the transactional writer and correlated reload path.

## Criteria check

specs/13-safe-layout-application/spec.md FR-004 (transactional apply or rollback)
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
            # DatabaseHelper owns onUpgrade: a schema migration change without
            # a risk label must still be caught (fourth-review P1 finding).
            "src/com/android/launcher3/model/DatabaseHelper.java",
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

    # The criteria machine-checks read only 'Criteria:' lines, so these tests
    # rewrite that line directly; prose elsewhere in the audit is irrelevant.
    _CRITERIA_LINE = (
        "- Criteria: specs/13-safe-layout-application/spec.md FR-004, "
        "docs/adr/0003-organizer-recovery-point-storage.md ADR-0003\n"
    )

    def test_missing_criteria_reference_is_flagged(self) -> None:
        doc = self._parse(VALID_AUDIT.replace(self._CRITERIA_LINE, "- Criteria: FR-004\n"))
        self.assertTrue(any("criteria" in f for f in doc.findings))
        self.assertEqual(doc.criteria_refs, [])

    def test_criteria_line_without_requirement_ids_is_flagged(self) -> None:
        doc = self._parse(VALID_AUDIT.replace(
            self._CRITERIA_LINE,
            "- Criteria: specs/13-safe-layout-application/spec.md（受入条件全体）\n",
        ))
        self.assertTrue(any("requirement IDs" in f for f in doc.findings))

    def test_criteria_ids_from_body_prose_do_not_count(self) -> None:
        # IDs and doc paths mentioned only in Scope/Findings prose must not
        # satisfy the criteria requirement (third-review P1 finding).
        doc = self._parse(VALID_AUDIT.replace(self._CRITERIA_LINE, "- Criteria: （なし）\n"))
        self.assertTrue(any("criteria" in f for f in doc.findings))
        self.assertEqual(doc.criteria_refs, [])

    def test_id_before_document_reference_is_flagged(self) -> None:
        doc = self._parse(VALID_AUDIT.replace(
            self._CRITERIA_LINE,
            "- Criteria: FR-004 specs/13-safe-layout-application/spec.md\n",
        ))
        self.assertTrue(any("appears before" in f for f in doc.findings))

    def test_criteria_pairs_keep_line_ordering(self) -> None:
        doc = self._parse(VALID_AUDIT)
        self.assertEqual(
            doc.criteria_entries,
            [
                ("specs/13-safe-layout-application/spec.md", ["FR-004"]),
                ("docs/adr/0003-organizer-recovery-point-storage.md", ["ADR-0003"]),
            ],
        )

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


class CriteriaSubstanceTests(unittest.TestCase):
    """verify_criteria_substance must check the referenced documents.

    Each (document, IDs) pair comes from a 'Criteria:' line with the IDs bound
    to the document they were cited with: the document has to exist, be
    accepted, and define those exact IDs. Cross-document ID mix-ups and IDs
    smuggled from prose must both fail (third-review P1 finding).
    """

    SPEC_13 = "specs/13-safe-layout-application/spec.md"
    ADR_0003 = "docs/adr/0003-organizer-recovery-point-storage.md"

    def test_real_accepted_refs_with_correctly_paired_ids_pass(self) -> None:
        problems = gate.verify_criteria_substance(
            REPO_ROOT,
            [
                (self.SPEC_13, ["FR-004", "AC-1"]),
                (self.ADR_0003, ["ADR-0003"]),
            ],
        )
        self.assertEqual(problems, [], f"real accepted criteria flagged: {problems}")

    def test_nonexistent_reference_is_flagged(self) -> None:
        problems = gate.verify_criteria_substance(
            REPO_ROOT, [("specs/99-does-not-exist/spec.md", ["FR-004"])]
        )
        self.assertTrue(any("does not exist" in p for p in problems))

    def test_draft_reference_is_flagged(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            spec = root / "specs" / "42-demo"
            spec.mkdir(parents=True)
            (spec / "spec.md").write_text(
                "---\nstatus: draft\n---\n\n# Demo\n\nAC-1 demo\n",
                encoding="utf-8",
            )
            problems = gate.verify_criteria_substance(
                root, [("specs/42-demo/spec.md", ["AC-1"])]
            )
        self.assertTrue(any("status" in p for p in problems))

    def test_invented_requirement_id_is_flagged(self) -> None:
        # FR-999 is not defined anywhere in spec 13; the gate must reject it
        # instead of trusting the audit's prose.
        problems = gate.verify_criteria_substance(REPO_ROOT, [(self.SPEC_13, ["FR-999"])])
        self.assertTrue(any("FR-999" in p for p in problems))

    def test_id_cited_with_wrong_document_is_flagged(self) -> None:
        # FR-004 is defined in spec 13, not in ADR-0003; citing it with the
        # ADR must fail even though both documents exist and are accepted.
        problems = gate.verify_criteria_substance(REPO_ROOT, [(self.ADR_0003, ["FR-004"])])
        self.assertTrue(any("FR-004" in p and "is not defined in" in p for p in problems))

    def test_adr_id_cited_with_wrong_document_is_flagged(self) -> None:
        problems = gate.verify_criteria_substance(REPO_ROOT, [(self.SPEC_13, ["ADR-0003"])])
        self.assertTrue(any("ADR-0003" in p and "belongs to" in p for p in problems))

    def test_document_without_ids_is_flagged(self) -> None:
        problems = gate.verify_criteria_substance(REPO_ROOT, [(self.SPEC_13, [])])
        self.assertTrue(any("lists no requirement IDs" in p for p in problems))

    def test_zero_padded_id_variant_is_accepted(self) -> None:
        # Specs write FR-004; the audit may cite FR-4 for the same criterion.
        problems = gate.verify_criteria_substance(REPO_ROOT, [(self.SPEC_13, ["FR-4"])])
        self.assertEqual(problems, [])

    def test_requirement_id_must_match_as_a_whole_token(self) -> None:
        # A document that only defines FR-0040 must not satisfy a citation of
        # FR-004; substring matching is not enough (fourth-review P1 finding).
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            spec = root / "specs" / "42-demo"
            spec.mkdir(parents=True)
            (spec / "spec.md").write_text(
                "---\nstatus: accepted\n---\n\n# Demo\n\nAC-1 demo\n\nFR-0040 only\n",
                encoding="utf-8",
            )
            problems = gate.verify_criteria_substance(
                root, [("specs/42-demo/spec.md", ["FR-004"])]
            )
        self.assertTrue(any("FR-004" in p for p in problems))
        self.assertFalse(any("FR-0040" in p for p in problems))

    def test_id_defined_as_exact_token_passes(self) -> None:
        self.assertTrue(gate._id_defined("FR-004", "criteria FR-004 holds"))
        self.assertTrue(gate._id_defined("FR-004", "- FR-004"))
        self.assertFalse(gate._id_defined("FR-004", "FR-0040 only"))
        self.assertFalse(gate._id_defined("FR-004", "XFR-004 typo"))
        self.assertFalse(gate._id_defined("AC-1", "AC-12 only"))


class CiRunsVerificationTests(unittest.TestCase):
    """verify_ci_runs must require this PR's merge gate, not any green run.

    Mocked gh_api responses: a push run, a run GitHub does not associate with
    this PR (another PR on the same ref and commit), a foreign-branch run, and
    a run whose source jobs were skipped must all be rejected; only a
    pull_request run of ci.yml that GitHub associates with this PR, on the
    audited SHA and head ref, with final-status green and the source jobs
    executed qualifies (second/third-review P1 findings).
    """

    AUDIT_SHA = "0" * 40
    HEAD_REF = "feature-branch"
    PR_NUMBER = 47

    def _run(self, **overrides: object) -> dict:
        run = {
            "head_sha": self.AUDIT_SHA,
            "head_branch": self.HEAD_REF,
            "event": "pull_request",
            "pull_requests": [{"number": self.PR_NUMBER}],
            "path": ".github/workflows/ci.yml",
            "status": "completed",
            "conclusion": "success",
        }
        run.update(overrides)
        return run

    def _jobs(self, organizer: str = "success") -> dict:
        return {
            "jobs": [
                {"name": "changes", "conclusion": "success"},
                {"name": "check-style", "conclusion": "success"},
                {"name": "build-debug-apk", "conclusion": "success"},
                {"name": "organizer-unit-tests", "conclusion": organizer},
                {"name": "validate-repo-contract", "conclusion": "success"},
                {"name": "final-status", "conclusion": "success"},
            ]
        }

    def _verify(self, run: dict, jobs: dict) -> List[str]:
        def fake_gh_api(repo: str, endpoint: str) -> dict:
            if endpoint.endswith("/jobs"):
                return jobs
            return run

        with unittest.mock.patch.object(gate, "gh_api", side_effect=fake_gh_api):
            return gate.verify_ci_runs(
                [1], self.AUDIT_SHA, REPO, self.HEAD_REF, self.PR_NUMBER
            )

    def test_qualifying_merge_gate_run_passes(self) -> None:
        problems = self._verify(self._run(), self._jobs())
        self.assertEqual(problems, [], f"qualifying run rejected: {problems}")

    def test_push_triggered_run_is_rejected(self) -> None:
        problems = self._verify(self._run(event="push"), self._jobs())
        self.assertTrue(any("pull request merge gate" in p for p in problems))
        self.assertTrue(problems)

    def test_dispatch_run_is_rejected(self) -> None:
        problems = self._verify(self._run(event="workflow_dispatch"), self._jobs())
        self.assertTrue(any("merge gate" in p for p in problems))

    def test_run_of_another_pr_is_rejected(self) -> None:
        # Same branch and commit, but GitHub associates the run with a
        # different PR: it cannot serve as this PR's merge-gate evidence.
        problems = self._verify(
            self._run(pull_requests=[{"number": 99}]), self._jobs()
        )
        self.assertTrue(any("not associated with PR #47" in p for p in problems))

    def test_run_without_pr_association_is_rejected(self) -> None:
        problems = self._verify(self._run(pull_requests=[]), self._jobs())
        self.assertTrue(any("no pull request" in p for p in problems))

    def test_foreign_branch_run_is_rejected(self) -> None:
        problems = self._verify(
            self._run(head_branch="other-branch"), self._jobs()
        )
        self.assertTrue(any("other-branch" in p for p in problems))

    def test_run_with_skipped_source_jobs_is_rejected(self) -> None:
        # A docs-only CI run skips organizer tests; it must not count as
        # merge-gate evidence for a high-risk PR.
        problems = self._verify(self._run(), self._jobs(organizer="skipped"))
        self.assertTrue(any("source job" in p for p in problems))
        self.assertTrue(any("not merge-gate evidence" in p for p in problems))

    def test_run_without_green_final_status_is_rejected(self) -> None:
        jobs = self._jobs()
        jobs["jobs"][-1]["conclusion"] = "failure"
        problems = self._verify(self._run(), jobs)
        self.assertTrue(any("final-status" in p for p in problems))


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
