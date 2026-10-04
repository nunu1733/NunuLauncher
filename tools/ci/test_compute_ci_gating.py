#!/usr/bin/env python3
"""Self-tests for tools/ci/compute_ci_gating.py (Issue #422).

Run directly: python3 tools/ci/test_compute_ci_gating.py

Covers the contract in specs/422-impact-based-ci-portfolio/spec.md:
- per-path fail-closed, including mapped + unmapped changes in one diff
- smoke dispatch: permanent gates on, instrumentation off, paths ignored
- full dispatch / schedule / workflow_call / main push force the full portfolio
- JSON file lists with spaces, quotes, and shell metacharacters stay intact
"""

from __future__ import annotations

import copy
import sys
import unittest

sys.path.insert(0, __file__.rsplit("/", 1)[0])

import compute_ci_gating  # noqa: E402


def base_env() -> dict[str, str]:
    return {
        "GATING_EVENT": "pull_request",
        "GATING_REF": "refs/heads/issue-422-x",
        "GATING_FULL_PORTFOLIO": "",
        "GATING_WORKFLOW_CALL": "false",
        "GATING_SOURCE": "false",
        "GATING_CI": "false",
        "GATING_SOURCE_FILES": "[]",
        "GATING_SURFACE_LAYOUT_WRITE_FILES": "[]",
        "GATING_SURFACE_DB_SCHEMA_FILES": "[]",
        "GATING_SURFACE_BACKUP_RESTORE_FILES": "[]",
        "GATING_SURFACE_PRODUCTION_INPUT_FILES": "[]",
        "GATING_SURFACE_ORGANIZER_UI_FILES": "[]",
        "GATING_SURFACE_JVM_FILES": "[]",
    }


class GatingTest(unittest.TestCase):
    def test_docs_only_pr_skips_source_gates(self):
        result = compute_ci_gating.compute(base_env())
        self.assertFalse(result["full"])
        self.assertFalse(result["permanent_run"])
        self.assertTrue(result["instrumentation_enabled"])
        self.assertEqual(result["unmapped_count"], 0)

    def test_planner_only_change_maps_to_jvm_surface(self):
        env = base_env()
        env["GATING_SOURCE"] = "true"
        planner = "lawnchair/src/app/lawnchair/organizer/planning/Planner.kt"
        env["GATING_SOURCE_FILES"] = json_dumps([planner])
        env["GATING_SURFACE_JVM_FILES"] = json_dumps([planner])
        result = compute_ci_gating.compute(env)
        self.assertFalse(result["full"])
        self.assertTrue(result["permanent_run"])
        self.assertEqual(result["unmapped_count"], 0)

    def test_unmapped_source_forces_full(self):
        env = base_env()
        env["GATING_SOURCE"] = "true"
        env["GATING_SOURCE_FILES"] = json_dumps(["quickstep/src/Foo.java"])
        result = compute_ci_gating.compute(env)
        self.assertTrue(result["full"])
        self.assertTrue(result["instrumentation_enabled"])
        self.assertEqual(result["unmapped_count"], 1)

    def test_mapped_and_unmapped_mix_still_forces_full(self):
        env = base_env()
        env["GATING_SOURCE"] = "true"
        ui = "lawnchair/src/app/lawnchair/organizer/ui/Hub.kt"
        env["GATING_SOURCE_FILES"] = json_dumps(
            [ui, "quickstep/src/Bar.java", "Android.bp"]
        )
        env["GATING_SURFACE_ORGANIZER_UI_FILES"] = json_dumps([ui])
        result = compute_ci_gating.compute(env)
        self.assertTrue(result["full"])
        self.assertEqual(result["unmapped_count"], 2)
        self.assertIn("Android.bp", result["unmapped_sample"])

    def test_ci_change_forces_full(self):
        env = base_env()
        env["GATING_CI"] = "true"
        result = compute_ci_gating.compute(env)
        self.assertTrue(result["full"])

    def test_schedule_forces_full(self):
        env = base_env()
        env["GATING_EVENT"] = "schedule"
        result = compute_ci_gating.compute(env)
        self.assertTrue(result["full"])
        self.assertTrue(result["permanent_run"])

    def test_workflow_call_forces_full(self):
        env = base_env()
        env["GATING_WORKFLOW_CALL"] = "true"
        result = compute_ci_gating.compute(env)
        self.assertTrue(result["full"])

    def test_main_push_forces_full(self):
        env = base_env()
        env["GATING_EVENT"] = "push"
        env["GATING_REF"] = "refs/heads/main"
        result = compute_ci_gating.compute(env)
        self.assertTrue(result["full"])

    def test_dev_branch_push_stays_conditional(self):
        env = base_env()
        env["GATING_EVENT"] = "push"
        env["GATING_REF"] = "refs/heads/422-demo-dev"
        env["GATING_SOURCE"] = "true"
        planner = "lawnchair/src/app/lawnchair/organizer/rules/Policy.kt"
        env["GATING_SOURCE_FILES"] = json_dumps([planner])
        env["GATING_SURFACE_JVM_FILES"] = json_dumps([planner])
        result = compute_ci_gating.compute(env)
        self.assertFalse(result["full"])
        self.assertTrue(result["permanent_run"])

    def test_dispatch_default_is_full(self):
        env = base_env()
        env["GATING_EVENT"] = "workflow_dispatch"
        env["GATING_FULL_PORTFOLIO"] = ""
        result = compute_ci_gating.compute(env)
        self.assertFalse(result["smoke"])
        self.assertTrue(result["full"])

    def test_dispatch_smoke_ignores_paths(self):
        env = base_env()
        env["GATING_EVENT"] = "workflow_dispatch"
        env["GATING_FULL_PORTFOLIO"] = "false"
        env["GATING_SOURCE"] = "true"
        # Even an unmapped-looking diff must not widen a smoke run, and a
        # mapped surface must not leak an instrumentation lane into it.
        env["GATING_SOURCE_FILES"] = json_dumps(
            ["quickstep/src/Foo.java", "lawnchair/src/app/lawnchair/organizer/ui/H.kt"]
        )
        env["GATING_SURFACE_ORGANIZER_UI_FILES"] = json_dumps(
            ["lawnchair/src/app/lawnchair/organizer/ui/H.kt"]
        )
        result = compute_ci_gating.compute(env)
        self.assertTrue(result["smoke"])
        self.assertFalse(result["full"])
        self.assertTrue(result["permanent_run"])
        self.assertFalse(result["instrumentation_enabled"])

    def test_hostile_filenames_survive_json_path(self):
        env = base_env()
        env["GATING_SOURCE"] = "true"
        nasty = [
            "a dir/with spaces/Foo.kt",
            "quote'and\"double.kt",
            "$(rm -rf x).kt",
            "back`tick`.kt",
            "semi;colon.kt",
            "new\\line.kt",
        ]
        env["GATING_SOURCE_FILES"] = json_dumps(nasty)
        env["GATING_SURFACE_JVM_FILES"] = json_dumps(nasty[:1])
        result = compute_ci_gating.compute(env)
        self.assertTrue(result["full"])
        self.assertEqual(result["unmapped_count"], len(nasty) - 1)

    def test_invalid_json_rejected(self):
        env = base_env()
        env["GATING_SOURCE"] = "true"
        env["GATING_SOURCE_FILES"] = "quickstep/Foo.java"  # not a JSON array
        with self.assertRaises(SystemExit):
            compute_ci_gating.compute(env)

    def test_non_string_array_rejected(self):
        env = base_env()
        env["GATING_SOURCE_FILES"] = "[1, 2]"
        with self.assertRaises(SystemExit):
            compute_ci_gating.compute(env)

    def test_env_is_not_mutated(self):
        env = base_env()
        snapshot = copy.deepcopy(env)
        compute_ci_gating.compute(env)
        self.assertEqual(env, snapshot)


def json_dumps(value: list[str]) -> str:
    import json

    return json.dumps(value)


if __name__ == "__main__":
    unittest.main()
