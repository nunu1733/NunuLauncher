#!/usr/bin/env python3
"""Self-tests for the repository contract validator.

Standard library only (``unittest``), so this runs identically in CI and on a
fresh checkout without installing anything beyond Python. Run with:

    python3 tools/repo-contract/test_validate_repo_contract.py

These tests are the acceptance evidence for Issue #8: they prove that a broken
internal Markdown link and an invalid Issue form YAML both make the validator
fail, which is what the CI gate relies on.
"""

from __future__ import annotations

import unittest
from pathlib import Path

import validate_repo_contract as vrc

FIXTURES = Path(__file__).resolve().parent / "fixtures"
VALID = FIXTURES / "valid"
INVALID = FIXTURES / "invalid"
REPO_ROOT = Path(__file__).resolve().parents[2]


def _gather_link_and_form_findings(root: Path) -> list:
    """Run only the link and issue-form checks against ``root``.

    ``run()`` skips the fixtures tree (its broken links are intentional), so
    callers that want the fixture's own links checked must iterate the files
    explicitly. This helper keeps that detail in one place.
    """

    findings: list = []
    for md in sorted(root.rglob("*.md")):
        findings.extend(vrc.validate_markdown_links(md, root))
    findings.extend(vrc.validate_issue_forms(root))
    return [
        f
        for f in findings
        if "broken local link" in f.message
        or "YAML" in f.message
        or "Issue form" in f.message
    ]


class MarkdownLinkValidationTests(unittest.TestCase):
    def test_valid_fixture_has_no_broken_links(self) -> None:
        # Check each markdown file in the valid fixture directly, mirroring how
        # run() iterates but without the fixtures-skip used for the real repo.
        md_files = [
            VALID / "README.md",
            VALID / "AGENTS.md",
            VALID / "docs" / "guide.md",
            VALID / "docs" / "templates" / "README.md",
        ]
        for md in md_files:
            findings = vrc.validate_markdown_links(md, VALID)
            link_findings = [
                f for f in findings if "broken local link" in f.message
            ]
            self.assertEqual(
                link_findings,
                [],
                f"{md} must have no broken links, got {link_findings}",
            )

    def test_invalid_fixture_reports_broken_markdown_link(self) -> None:
        # Validate the fixture file directly: run() skips the fixtures tree by
        # design (its broken links are intentional), so we exercise the link
        # checker at the function level here.
        src = INVALID / "README.md"
        findings = vrc.validate_markdown_links(src, INVALID)
        broken = [f for f in findings if "broken local link" in f.message]
        self.assertTrue(
            broken,
            "invalid fixture must surface at least one broken markdown link",
        )
        # The specific ghost link must be flagged.
        self.assertTrue(
            any("does-not-exist.md" in f.message for f in broken),
            f"expected does-not-exist.md in findings, got {[f.message for f in broken]}",
        )

    def test_remote_links_are_not_validated(self) -> None:
        src = VALID / "README.md"
        # Sanity: a remote URL must never produce a finding.
        findings = vrc.validate_markdown_links(src, VALID)
        self.assertFalse(
            any("http" in f.message or "https" in f.message for f in findings),
            "remote links must be ignored",
        )

    def test_directory_link_resolves_to_readme(self) -> None:
        src = VALID / "docs" / "guide.md"
        findings = vrc.validate_markdown_links(src, VALID)
        # [templates dir](./templates/) points at a directory with a README.
        self.assertFalse(
            any("templates" in f.message for f in findings),
            "directory link to a folder with README.md must resolve",
        )


class IssueFormValidationTests(unittest.TestCase):
    def test_valid_form_passes(self) -> None:
        findings = vrc.validate_issue_forms(VALID)
        self.assertEqual(
            findings, [], "valid fixture issue form must parse cleanly"
        )

    def test_invalid_form_is_flagged(self) -> None:
        findings = vrc.validate_issue_forms(INVALID)
        self.assertTrue(
            findings,
            "invalid fixture issue form must produce a finding",
        )

    def test_config_yml_is_not_treated_as_a_form(self) -> None:
        # The real repo has a config.yml chooser; it must never be flagged as a
        # malformed form because it intentionally lacks name/body.
        findings = vrc.validate_issue_forms(REPO_ROOT)
        config_findings = [
            f for f in findings if "config.yml" in str(f.path)
        ]
        self.assertEqual(
            config_findings, [], "config.yml must be excluded from form checks"
        )


class RequiredFilesTests(unittest.TestCase):
    def test_real_repo_has_required_files(self) -> None:
        # The contract is only meaningful against the real repository; this
        # guards against an accidental deletion of a load-bearing file.
        findings = vrc.validate_required_files(REPO_ROOT)
        self.assertEqual(
            findings, [], f"required files missing in real repo: {findings}"
        )


class CliExitCodeTests(unittest.TestCase):
    def test_valid_fixture_links_and_forms_are_clean(self) -> None:
        # The trimmed valid fixture does not carry every required project file,
        # so we assert only on the link/form subset of run() rather than the
        # CLI exit code (which is driven by the full contract set).
        link_and_form = [
            f
            for f in _gather_link_and_form_findings(VALID)
        ]
        self.assertEqual(link_and_form, [])

    def test_invalid_fixture_exits_nonzero(self) -> None:
        rc = vrc.main(["--root", str(INVALID)])
        self.assertNotEqual(rc, 0, "invalid fixture must exit non-zero")

    def test_real_repo_exits_zero(self) -> None:
        # The real repository is the contract we actually gate CI on; it must
        # pass end to end.
        rc = vrc.main(["--root", str(REPO_ROOT)])
        self.assertEqual(rc, 0, "real repo must satisfy every contract")


if __name__ == "__main__":
    unittest.main(verbosity=2)
