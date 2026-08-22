#!/usr/bin/env python3
"""Regression tests for the offline upstream patch-surface measurement."""

from __future__ import annotations

import importlib.util
import sys
import unittest
from pathlib import Path

SCRIPT = Path(__file__).with_name("measure_upstream_patch_surface.py")
SPEC = importlib.util.spec_from_file_location("patch_surface", SCRIPT)
assert SPEC is not None and SPEC.loader is not None
patch_surface = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = patch_surface
SPEC.loader.exec_module(patch_surface)


class PatchSurfaceMeasurementTest(unittest.TestCase):
    def test_source_path_limits_metric_to_production_java_and_kotlin(self) -> None:
        self.assertTrue(patch_surface.source_path("src/com/android/launcher3/Launcher.java"))
        self.assertTrue(patch_surface.source_path("lawnchair/src/app/lawnchair/organizer/Plan.kt"))
        self.assertFalse(patch_surface.source_path("tests/unit/PlanTest.kt"))
        self.assertFalse(patch_surface.source_path("docs/assessment/baseline.json"))
        self.assertFalse(patch_surface.source_path("src/com/android/launcher3/res/values.xml"))

    def test_aggregate_reports_project_owned_additions_separately(self) -> None:
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
            },
            report["totals"],
        )
        self.assertEqual({"files": 1, "additions": 7, "deletions": 2}, report["groups"]["model-bridge"])

    def test_comparison_and_growth_ignore_project_owned_module_volume(self) -> None:
        current = {
            "totals": {
                "counted_files": 4,
                "counted_additions": 29,
                "counted_deletions": 7,
                "project_owned_files": 100,
                "project_owned_additions": 1000,
                "project_owned_deletions": 0,
            }
        }
        expected = {"counted_files": 4, "counted_additions": 29, "counted_deletions": 7}

        deltas = patch_surface.comparison(current, expected)

        self.assertEqual({"counted_files": 0, "counted_additions": 0, "counted_deletions": 0}, deltas)
        self.assertFalse(patch_surface.has_growth(deltas))
        self.assertTrue(patch_surface.has_growth({"counted_files": 0, "counted_additions": 1, "counted_deletions": -9}))

    def test_duplicate_bridge_assignment_is_rejected(self) -> None:
        groups = [
            {"id": "first", "paths": ["src/com/android/launcher3/Model.java"]},
            {"id": "second", "paths": ["src/com/android/launcher3/Model.java"]},
        ]

        with self.assertRaisesRegex(patch_surface.MeasurementError, "assigned twice"):
            patch_surface.group_index(groups)


if __name__ == "__main__":
    unittest.main()
