#!/usr/bin/env python3
"""Self-tests for tools/repo-contract/validate_ci_portfolio.py (Issue #422).

Run: python3 tools/repo-contract/test_validate_ci_portfolio.py

Builds small ci.yml / ci_portfolio_map.yml / portfolio-doc fixtures in a temp
directory and asserts every drift class the validator must catch, including the
round-5 case: the map and a lane `if` agreeing on the SAME unknown surface
while another lane uses the correct one (only the bidirectional vocabulary
check can catch that).
"""

from __future__ import annotations

import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import validate_ci_portfolio as vcp  # noqa: E402

LANE_IF_TEMPLATE = (
    "needs.changes.outputs.instrumentation_enabled == 'true' && "
    "(needs.changes.outputs.full == 'true'{surface_refs})"
)


def lane_if(surfaces: list[str]) -> str:
    refs = "".join(
        f" || needs.changes.outputs.{surface} == 'true'" for surface in surfaces
    )
    return LANE_IF_TEMPLATE.format(surface_refs=refs)


def build_workflow(
    lanes: dict[str, list[str]],
    surfaces_defined: list[str],
    final_needs: list[str] | None = None,
    drop_capture: str | None = None,
    live_capture: set[str] | None = None,
) -> str:
    import yaml

    jobs: dict[str, object] = {
        "changes": {
            "runs-on": "ubuntu-latest",
            "outputs": {
                name: "${{ steps.filter.outputs." + name + " }}"
                for name in surfaces_defined
            },
        },
        "validate-repo-contract": {"runs-on": "ubuntu-latest"},
        "check-style": {"runs-on": "ubuntu-latest"},
        "build-debug-apk": {"runs-on": "ubuntu-latest"},
        "organizer-unit-tests": {"runs-on": "ubuntu-latest"},
    }
    live_capture = live_capture or set()
    for lane, surfaces in lanes.items():
        steps: list[dict[str, object]] = [
            {"name": "Run tests", "run": "./gradlew connectedTest"},
            {
                "name": "Capture failure-time emulator evidence",
                "run": "timeout --kill-after=30 300 bash tools/ci/capture-emulator-failure-evidence.sh emulator-5554 build/x",
            },
        ]
        if lane in live_capture:
            steps = [
                {
                    "name": "Run tests inside live emulator",
                    "with": {
                        "script": (
                            "bash tools/ci/run-emulator-command-with-failure-capture.sh "
                            "emulator-5554 build/x -- ./run-tests.sh"
                        )
                    },
                },
            ]
        if drop_capture and lane == drop_capture:
            steps = steps[:1]
        jobs[lane] = {
            "needs": "changes",
            "if": lane_if(surfaces),
            "runs-on": "ubuntu-latest",
            "steps": steps,
        }
    if final_needs is None:
        final_needs = [
            "changes",
            "validate-repo-contract",
            "check-style",
            "build-debug-apk",
            "organizer-unit-tests",
            *lanes.keys(),
        ]
    jobs["final-status"] = {
        "needs": final_needs,
        "runs-on": "ubuntu-latest",
        "if": "always()",
    }
    return yaml.safe_dump({"name": "CI", "jobs": jobs}, sort_keys=False)


def build_map(
    lanes: dict[str, list[str]],
    permanent_gates: list[str] | None = None,
    permanent_only: list[str] | None = None,
) -> str:
    import yaml

    if permanent_gates is None:
        permanent_gates = ["organizer-unit-tests", "check-style", "build-debug-apk"]
    if permanent_only is None:
        permanent_only = ["surface_jvm"]
    return yaml.safe_dump(
        {
            "lanes": {lane: {"surfaces": surfaces} for lane, surfaces in lanes.items()},
            "permanent_gates": permanent_gates,
            "permanent_only_surfaces": permanent_only,
        },
        sort_keys=False,
    )


ALL_SURFACES = [
    "surface_layout_write",
    "surface_db_schema",
    "surface_backup_restore",
    "surface_production_input",
    "surface_organizer_ui",
    "surface_jvm",
]

CONSISTENT_LANES = {
    "organizer-instrumentation-shared-writer-tests": ["surface_layout_write"],
    "organizer-instrumentation-db-migration-tests": ["surface_db_schema"],
    "organizer-instrumentation-manual-organization-ui-tests": ["surface_organizer_ui"],
}


class PortfolioValidatorTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.ci_path = os.path.join(self.tmp.name, "ci.yml")
        self.map_path = os.path.join(self.tmp.name, "map.yml")
        self.doc_path = os.path.join(self.tmp.name, "portfolio.md")
        self._saved = (
            vcp.CI_YML_PATH,
            vcp.MAP_PATH,
            vcp.PORTFOLIO_DOC_PATH,
        )
        vcp.CI_YML_PATH = self.ci_path
        vcp.MAP_PATH = self.map_path
        vcp.PORTFOLIO_DOC_PATH = self.doc_path
        self.addCleanup(self._restore)

    def _restore(self):
        vcp.CI_YML_PATH, vcp.MAP_PATH, vcp.PORTFOLIO_DOC_PATH = self._saved

    def write_fixtures(
        self,
        lanes: dict[str, list[str]],
        map_lanes: dict[str, list[str]] | None = None,
        surfaces_defined: list[str] | None = None,
        permanent_gates: list[str] | None = None,
        permanent_only: list[str] | None = None,
        final_needs: list[str] | None = None,
        drop_capture: str | None = None,
        live_capture: set[str] | None = None,
        doc_lines: list[str] | None = None,
    ):
        if map_lanes is None:
            map_lanes = lanes
        if permanent_only is None:
            permanent_only = ["surface_jvm"]
        if surfaces_defined is None:
            # Consistent default: the changes job defines exactly what the map
            # declares. Tests that need a mismatch pass surfaces_defined
            # explicitly.
            surfaces_defined = sorted(
                set().union(*map_lanes.values()) | set(permanent_only)
            )
        if doc_lines is None:
            doc_lines = ["| lane | surface |", "|---|---|"]
            for lane in map_lanes:
                doc_lines.append(f"| {lane} | x |")
            doc_lines.append(" ".join(surfaces_defined))
        with open(self.ci_path, "w", encoding="utf-8") as handle:
            handle.write(
                build_workflow(
                    lanes,
                    surfaces_defined,
                    final_needs=final_needs,
                    drop_capture=drop_capture,
                    live_capture=live_capture,
                )
            )
        with open(self.map_path, "w", encoding="utf-8") as handle:
            handle.write(
                build_map(
                    map_lanes,
                    permanent_gates=permanent_gates,
                    permanent_only=permanent_only,
                )
            )
        with open(self.doc_path, "w", encoding="utf-8") as handle:
            handle.write("\n".join(doc_lines) + "\n")

    def assert_problem(self, fragment: str):
        problems = vcp.validate()
        self.assertTrue(
            any(fragment in problem for problem in problems),
            f"expected a problem containing {fragment!r}, got {problems}",
        )

    def test_consistent_portfolio_passes(self):
        self.write_fixtures(CONSISTENT_LANES)
        self.assertEqual(vcp.validate(), [])

    def test_lane_set_mismatch_detected(self):
        extra = dict(CONSISTENT_LANES)
        extra["organizer-instrumentation-ghost-tests"] = ["surface_db_schema"]
        self.write_fixtures(CONSISTENT_LANES, map_lanes=extra)
        self.assert_problem("lane set mismatch")

    def test_edge_mismatch_detected(self):
        drifted = {
            lane: (
                ["surface_db_schema"]
                if lane.endswith("shared-writer-tests")
                else surfaces
            )
            for lane, surfaces in CONSISTENT_LANES.items()
        }
        self.write_fixtures(CONSISTENT_LANES, map_lanes=drifted)
        self.assert_problem("surface edges differ")

    def test_shared_typo_surface_detected_by_vocabulary(self):
        # Round-5 case: map and lane `if` agree on the SAME typo'd surface
        # while another lane uses the correct spelling, and the changes job
        # defines only the correct vocabulary. Only the bidirectional
        # vocabulary check catches this.
        typoed = {
            "organizer-instrumentation-shared-writer-tests": ["surface_organizer_iu"],
            "organizer-instrumentation-db-migration-tests": ["surface_db_schema"],
            "organizer-instrumentation-manual-organization-ui-tests": [
                "surface_organizer_ui"
            ],
        }
        self.write_fixtures(typoed, surfaces_defined=ALL_SURFACES)
        self.assert_problem("surface vocabulary mismatch")

    def test_unused_surface_in_changes_detected(self):
        # The changes job defines a surface that no lane or permanent-only
        # entry declares (changes-only direction of the vocabulary check).
        self.write_fixtures(
            CONSISTENT_LANES,
            surfaces_defined=sorted(
                set().union(*CONSISTENT_LANES.values()) | {"surface_jvm", "surface_never_used"}
            ),
        )
        self.assert_problem("surface vocabulary mismatch")

    def test_final_status_needs_drift_detected(self):
        self.write_fixtures(
            CONSISTENT_LANES,
            final_needs=[
                "changes",
                "validate-repo-contract",
                "organizer-unit-tests",
                *CONSISTENT_LANES.keys(),
            ],
        )
        self.assert_problem("final-status needs mismatch")

    def test_permanent_gates_drift_detected(self):
        self.write_fixtures(
            CONSISTENT_LANES,
            permanent_gates=["organizer-unit-tests", "check-style"],
        )
        self.assert_problem("permanent_gates must equal")

    def test_missing_capture_step_detected(self):
        self.write_fixtures(
            CONSISTENT_LANES,
            drop_capture="organizer-instrumentation-db-migration-tests",
        )
        self.assert_problem("capture step is missing")

    def test_live_capture_wrapper_in_runner_script_is_accepted(self):
        self.write_fixtures(
            CONSISTENT_LANES,
            live_capture={"organizer-instrumentation-manual-organization-ui-tests"},
        )
        self.assertEqual(vcp.validate(), [])

    def test_doc_missing_lane_and_surface_detected(self):
        doc_lines = ["| lane | surface |", "|---|---|"]
        for lane in CONSISTENT_LANES:
            if lane.endswith("manual-organization-ui-tests"):
                continue
            doc_lines.append(f"| {lane} | x |")
        doc_lines.append("surface_layout_write")  # missing the other surfaces
        self.write_fixtures(CONSISTENT_LANES, doc_lines=doc_lines)
        problems = vcp.validate()
        self.assertTrue(
            any("does not mention lane" in p for p in problems), problems
        )
        self.assertTrue(
            any("does not mention surface" in p for p in problems), problems
        )

    def test_lane_without_changes_condition_detected(self):
        import yaml

        with open(self.ci_path, "w", encoding="utf-8") as handle:
            handle.write(build_workflow(CONSISTENT_LANES, ALL_SURFACES))
        with open(self.map_path, "w", encoding="utf-8") as handle:
            handle.write(build_map(CONSISTENT_LANES))
        with open(self.doc_path, "w", encoding="utf-8") as handle:
            handle.write("all lanes and surfaces listed\n")
        # Rewrite one lane's if to remove the needs.changes reference.
        workflow = yaml.safe_load(open(self.ci_path, encoding="utf-8"))
        victim = "organizer-instrumentation-shared-writer-tests"
        workflow["jobs"][victim]["if"] = "always()"
        with open(self.ci_path, "w", encoding="utf-8") as handle:
            yaml.safe_dump(workflow, handle, sort_keys=False)
        with self.assertRaises(vcp.PortfolioError):
            vcp.validate()


if __name__ == "__main__":
    unittest.main()
