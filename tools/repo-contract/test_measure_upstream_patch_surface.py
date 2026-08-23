#!/usr/bin/env python3
"""Regression tests for the offline upstream patch-surface measurement."""

from __future__ import annotations

import importlib.util
import io
import sys
import unittest
from contextlib import redirect_stderr, redirect_stdout
from pathlib import Path
from unittest.mock import ANY, patch

SCRIPT = Path(__file__).with_name("measure_upstream_patch_surface.py")
SPEC = importlib.util.spec_from_file_location("patch_surface", SCRIPT)
assert SPEC is not None and SPEC.loader is not None
patch_surface = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = patch_surface
SPEC.loader.exec_module(patch_surface)


class PatchSurfaceMeasurementTest(unittest.TestCase):
    @staticmethod
    def run_main(argv: list[str]) -> int:
        with redirect_stdout(io.StringIO()), redirect_stderr(io.StringIO()):
            return patch_surface.main(argv)

    def test_explicit_exclusions_do_not_hide_base_resources_or_schemas(self) -> None:
        exclusions = {
            "prefixes": ["docs/", "tests/", "res/values-", "lawnchair/res/values-"],
            "suffixes": [".md"],
            "paths": ["build.gradle"],
        }

        self.assertTrue(patch_surface.is_explicitly_excluded("docs/readme.md", exclusions))
        self.assertTrue(
            patch_surface.is_explicitly_excluded("lawnchair/res/values-ja/strings.xml", exclusions)
        )
        self.assertFalse(
            patch_surface.is_explicitly_excluded("lawnchair/res/values/strings.xml", exclusions)
        )
        self.assertTrue(patch_surface.is_explicitly_excluded("res/values-ja/strings.xml", exclusions))
        self.assertFalse(patch_surface.is_explicitly_excluded("res/values/strings.xml", exclusions))
        self.assertFalse(patch_surface.is_explicitly_excluded("res/raw/downgrade_schema.json", exclusions))
        self.assertFalse(patch_surface.is_explicitly_excluded("AndroidManifest.xml", exclusions))

    def test_bridge_assignment_overrides_broad_localized_exclusion(self) -> None:
        changes = [patch_surface.ChangedPath("M", "lawnchair/res/values-ja/strings.xml", 4, 0)]
        groups = [{"id": "ui", "paths": ["lawnchair/res/values-ja/strings.xml"]}]
        exclusions = {"prefixes": ["lawnchair/res/values-"], "suffixes": [], "paths": []}

        with patch.object(patch_surface, "path_exists_at", return_value=True):
            classified = patch_surface.classify_paths(
                changes,
                upstream="upstream",
                project_owned_prefixes=(),
                exclusions=exclusions,
                groups=groups,
            )

        self.assertEqual("upstream-file patch", classified[0].category)
        self.assertEqual("ui", classified[0].group_id)

    def test_production_capable_paths_are_not_excluded_by_pattern(self) -> None:
        exclusions = {
            "prefixes": [".github/", "docs/", "tests/", "tools/"],
            "suffixes": [".md"],
            "paths": [".gitignore"],
        }

        self.assertFalse(patch_surface.is_explicitly_excluded("res/drawable/launcher.webp", exclusions))
        self.assertFalse(patch_surface.is_explicitly_excluded("build.gradle", exclusions))
        self.assertFalse(patch_surface.is_explicitly_excluded("gradle/libs.versions.toml", exclusions))
        self.assertFalse(patch_surface.is_explicitly_excluded("crowdin.yml", exclusions))
        self.assertTrue(patch_surface.is_explicitly_excluded("docs/readme.md", exclusions))

    def test_pinned_content_change_is_excluded_while_blob_matches(self) -> None:
        changes = [patch_surface.ChangedPath("M", "build.gradle", 3, 1)]
        pins = {"build.gradle": {"blob_sha256_git": "blob-1"}}
        with patch.object(patch_surface, "blob_id_at", return_value="blob-1") as blob_lookup:
            classified = patch_surface.classify_paths(
                changes,
                upstream="upstream",
                project_owned_prefixes=(),
                exclusions={"prefixes": [], "suffixes": [], "paths": []},
                groups=[],
                pinned_exclusions=pins,
                target="target",
            )

        self.assertEqual("explicit exclusion", classified[0].category)
        blob_lookup.assert_called_once_with("target", "build.gradle", root=ANY)

    def test_diverging_pinned_content_requires_ownership_review(self) -> None:
        changes = [
            patch_surface.ChangedPath("M", "build.gradle", 3, 1),
            patch_surface.ChangedPath("M", "gradle/libs.versions.toml", 2, 0),
        ]
        pins = {
            "build.gradle": {"blob_sha256_git": "blob-1"},
            "gradle/libs.versions.toml": {"blob_sha256_git": "catalog-1"},
        }
        with patch.object(patch_surface, "blob_id_at", return_value="changed-blob"):
            with self.assertRaisesRegex(patch_surface.MeasurementError, "content-pinned") as captured:
                patch_surface.classify_paths(
                    changes,
                    upstream="upstream",
                    project_owned_prefixes=(),
                    exclusions={"prefixes": [], "suffixes": [], "paths": []},
                    groups=[],
                    pinned_exclusions=pins,
                    target="target",
                )

        message = str(captured.exception)
        self.assertIn("build.gradle", message)
        self.assertIn("gradle/libs.versions.toml", message)

    def test_unowned_production_asset_change_fails_closed_without_pattern_exclusion(self) -> None:
        changes = [patch_surface.ChangedPath("M", "res/drawable/launcher.webp", 0, 0)]
        with patch.object(patch_surface, "path_exists_at", return_value=True):
            with self.assertRaisesRegex(patch_surface.MeasurementError, "neither explicitly excluded"):
                patch_surface.classify_paths(
                    changes,
                    upstream="upstream",
                    project_owned_prefixes=(),
                    exclusions={"prefixes": [], "suffixes": [], "paths": []},
                    groups=[],
                )

    def test_binary_numstat_is_retained_until_classification(self) -> None:
        with patch.object(
            patch_surface,
            "run_git",
            side_effect=["M\tdocs/evidence/screenshot.png\n", "-\t-\tdocs/evidence/screenshot.png\n"],
        ):
            changes = patch_surface.changed_paths("upstream", "target")

        self.assertEqual(
            [patch_surface.ChangedPath("M", "docs/evidence/screenshot.png", None, None)], changes
        )

    def test_explicitly_excluded_binary_is_counted_as_a_file_without_lines(self) -> None:
        changes = [patch_surface.ChangedPath("M", "docs/evidence/screenshot.png", None, None)]
        with patch.object(patch_surface, "path_exists_at", return_value=True):
            classified = patch_surface.classify_paths(
                changes,
                upstream="upstream",
                project_owned_prefixes=(),
                exclusions={"prefixes": ["docs/"], "suffixes": [], "paths": []},
                groups=[],
            )

        report = patch_surface.aggregate(classified, [])
        self.assertEqual("explicit exclusion", classified[0].category)
        self.assertEqual(
            {"excluded_files": 1, "excluded_additions": 0, "excluded_deletions": 0},
            {
                key: report["totals"][key]  # type: ignore[index]
                for key in ("excluded_files", "excluded_additions", "excluded_deletions")
            },
        )

    def test_counted_binary_path_is_rejected_after_classification(self) -> None:
        changes = [patch_surface.ChangedPath("M", "res/drawable/bridge.bin", None, None)]
        groups = [{"id": "bridge", "paths": ["res/drawable/bridge.bin"]}]
        with patch.object(patch_surface, "path_exists_at", return_value=True):
            with self.assertRaisesRegex(patch_surface.MeasurementError, "binary"):
                patch_surface.classify_paths(
                    changes,
                    upstream="upstream",
                    project_owned_prefixes=(),
                    exclusions={"prefixes": [], "suffixes": [], "paths": []},
                    groups=groups,
                )

    def test_resource_and_schema_paths_require_bridge_ownership(self) -> None:
        changes = [
            patch_surface.ChangedPath("M", "lawnchair/res/values/strings.xml", 7, 1),
            patch_surface.ChangedPath("M", "res/raw/downgrade_schema.json", 4, 0),
        ]
        groups = [
            {"id": "ui", "paths": ["lawnchair/res/values/strings.xml"]},
            {"id": "schema", "paths": ["res/raw/downgrade_schema.json"]},
        ]
        with patch.object(patch_surface, "path_exists_at", return_value=True):
            classified = patch_surface.classify_paths(
                changes,
                upstream="upstream",
                project_owned_prefixes=("lawnchair/src/app/lawnchair/organizer/",),
                exclusions={"prefixes": [], "suffixes": [], "paths": []},
                groups=groups,
            )

        self.assertEqual(["ui", "schema"], [item.group_id for item in classified])
        self.assertEqual(["upstream-file patch", "upstream-file patch"], [item.category for item in classified])

    def test_unowned_nonexcluded_path_is_rejected(self) -> None:
        changes = [patch_surface.ChangedPath("M", "res/raw/downgrade_schema.json", 4, 0)]
        with patch.object(patch_surface, "path_exists_at", return_value=True):
            with self.assertRaisesRegex(patch_surface.MeasurementError, "neither explicitly excluded"):
                patch_surface.classify_paths(
                    changes,
                    upstream="upstream",
                    project_owned_prefixes=(),
                    exclusions={"prefixes": [], "suffixes": [], "paths": []},
                    groups=[],
                )

    def test_aggregate_reports_project_owned_and_excluded_paths_separately(self) -> None:
        groups = [{"id": "model-bridge", "paths": ["src/com/android/launcher3/Model.java"]}]
        classified = [
            patch_surface.ClassifiedPath(
                patch_surface.ChangedPath("M", "src/com/android/launcher3/Model.java", 7, 2),
                "upstream-file patch",
                "model-bridge",
            ),
            patch_surface.ClassifiedPath(
                patch_surface.ChangedPath("A", "lawnchair/src/app/lawnchair/organizer/Plan.kt", 11, 0),
                "project-owned addition",
                None,
            ),
            patch_surface.ClassifiedPath(
                patch_surface.ChangedPath("M", "tests/unit/PlanTest.kt", 3, 1),
                "explicit exclusion",
                None,
            ),
        ]

        report = patch_surface.aggregate(classified, groups)

        self.assertEqual(
            {
                "counted_files": 1,
                "counted_additions": 7,
                "counted_deletions": 2,
                "project_owned_files": 1,
                "project_owned_additions": 11,
                "project_owned_deletions": 0,
                "excluded_files": 1,
                "excluded_additions": 3,
                "excluded_deletions": 1,
            },
            report["totals"],
        )
        self.assertEqual({"files": 1, "additions": 7, "deletions": 2}, report["groups"]["model-bridge"])

    def test_exact_baseline_detects_path_and_group_replacement_even_when_totals_match(self) -> None:
        groups = [
            {"id": "first", "paths": ["src/com/android/launcher3/First.java"]},
            {"id": "second", "paths": ["src/com/android/launcher3/Second.java"]},
        ]
        original = [
            patch_surface.ClassifiedPath(
                patch_surface.ChangedPath("M", "src/com/android/launcher3/First.java", 7, 2),
                "upstream-file patch",
                "first",
            )
        ]
        replacement = [
            patch_surface.ClassifiedPath(
                patch_surface.ChangedPath("M", "src/com/android/launcher3/Second.java", 7, 2),
                "upstream-file patch",
                "second",
            )
        ]
        expected = patch_surface.expected_measurement_from(original, patch_surface.aggregate(original, groups))
        actual = patch_surface.expected_measurement_from(replacement, patch_surface.aggregate(replacement, groups))
        baseline = {"upstream_commit": "upstream", "main_commit": "main", "expected_measurement": expected}

        mismatches = patch_surface.exact_baseline_mismatches(
            upstream="upstream", target="main", baseline=baseline, actual=actual
        )

        self.assertTrue(any("counted_paths_sha256" in mismatch for mismatch in mismatches))
        self.assertTrue(any("groups" in mismatch for mismatch in mismatches))

    def test_duplicate_bridge_assignment_is_rejected(self) -> None:
        groups = [
            {"id": "first", "paths": ["src/com/android/launcher3/Model.java"]},
            {"id": "second", "paths": ["src/com/android/launcher3/Model.java"]},
        ]

        with self.assertRaisesRegex(patch_surface.MeasurementError, "assigned twice"):
            patch_surface.group_index(groups)

    def test_invalid_ancestor_is_rejected(self) -> None:
        completed = patch_surface.subprocess.CompletedProcess([], 1, "", "not an ancestor")
        with patch.object(patch_surface.subprocess, "run", return_value=completed):
            with self.assertRaisesRegex(patch_surface.MeasurementError, "must be an ancestor"):
                patch_surface.ensure_ancestor("upstream", "target")

    def test_main_verify_and_enforce_exit_behavior(self) -> None:
        expected = {
            "counted_files": 1,
            "counted_additions": 7,
            "counted_deletions": 2,
            "counted_paths_sha256": "paths",
            "project_owned_files": 0,
            "project_owned_additions": 0,
            "project_owned_deletions": 0,
            "project_owned_paths_sha256": "project",
            "excluded_files": 0,
            "excluded_additions": 0,
            "excluded_deletions": 0,
            "excluded_paths_sha256": "excluded",
            "groups": {},
        }
        baseline = {
            "upstream_commit": "upstream",
            "main_commit": "main",
            "project_owned_addition_prefixes": [],
            "explicit_exclusions": {"prefixes": [], "suffixes": [], "paths": []},
            "pinned_content_exclusions": {},
            "bridge_groups": [],
            "expected_measurement": expected,
        }
        report = {
            "totals": {
                "counted_files": 1,
                "counted_additions": 7,
                "counted_deletions": 2,
                "project_owned_files": 0,
                "project_owned_additions": 0,
                "project_owned_deletions": 0,
                "excluded_files": 0,
                "excluded_additions": 0,
                "excluded_deletions": 0,
            },
            "groups": {},
        }
        mismatched = {**expected, "counted_additions": 8}
        with (
            patch.object(patch_surface, "load_baseline", return_value=baseline),
            patch.object(patch_surface, "require_commit", side_effect=lambda revision: revision),
            patch.object(patch_surface, "ensure_ancestor"),
            patch.object(patch_surface, "changed_paths", return_value=[]),
            patch.object(patch_surface, "classify_paths", return_value=[]),
            patch.object(patch_surface, "aggregate", return_value=report),
            patch.object(patch_surface, "print_report"),
            patch.object(patch_surface, "expected_measurement_from", return_value=expected),
        ):
            self.assertEqual(0, self.run_main(["--verify", "--enforce-baseline"]))
        with (
            patch.object(patch_surface, "load_baseline", return_value=baseline),
            patch.object(patch_surface, "require_commit", side_effect=lambda revision: revision),
            patch.object(patch_surface, "ensure_ancestor"),
            patch.object(patch_surface, "changed_paths", return_value=[]),
            patch.object(patch_surface, "classify_paths", return_value=[]),
            patch.object(patch_surface, "aggregate", return_value=report),
            patch.object(patch_surface, "print_report"),
            patch.object(patch_surface, "expected_measurement_from", return_value=mismatched),
        ):
            self.assertEqual(1, self.run_main(["--verify"]))
            self.assertEqual(1, self.run_main(["--enforce-baseline"]))

    def test_exit_status_policy_for_verify_and_enforce(self) -> None:
        verify_code, verify_message = patch_surface.requested_exit_code(
            verify=True,
            enforce_baseline=False,
            exact_mismatches=["counted_paths_sha256 changed"],
            deltas={"counted_files": 0, "counted_additions": 0, "counted_deletions": 0},
        )
        enforce_code, enforce_message = patch_surface.requested_exit_code(
            verify=False,
            enforce_baseline=True,
            exact_mismatches=[],
            deltas={"counted_files": 0, "counted_additions": 1, "counted_deletions": -3},
        )
        pass_code, pass_message = patch_surface.requested_exit_code(
            verify=True,
            enforce_baseline=True,
            exact_mismatches=[],
            deltas={"counted_files": 0, "counted_additions": 0, "counted_deletions": 0},
        )

        self.assertEqual(1, verify_code)
        self.assertIn("exact baseline", verify_message or "")
        self.assertEqual(1, enforce_code)
        self.assertIn("REVIEW REQUIRED", enforce_message or "")
        self.assertEqual((0, None), (pass_code, pass_message))


if __name__ == "__main__":
    unittest.main()
