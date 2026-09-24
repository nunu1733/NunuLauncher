#!/usr/bin/env python3
"""Validate the CI portfolio consistency (Issue #422).

Compares .github/workflows/ci.yml against tools/repo-contract/ci_portfolio_map.yml
(the normative lane<->surface edge source) and checks:

1. The map's lane set equals ci.yml's organizer-instrumentation-* job set.
2. Every lane's surface set in the map equals the set of `surface_*` outputs
   referenced in that lane's `if` condition (exact edge comparison). Global
   control outputs (full / instrumentation_enabled / permanent_run) are not
   surfaces and are ignored.
3. The surface vocabulary matches in BOTH directions: the `surface_*` outputs
   defined by the `changes` job equal the union of map surfaces plus
   permanent-only surfaces. This catches unused surfaces and typo'd or unknown
   surfaces even when the map and the lane `if` agree on the same typo.
4. final-status `needs` equals exactly {changes, validate-repo-contract} +
   map.permanent_gates + all instrumentation lanes.
5. map.permanent_gates equals the fixed set bound to
   validate_high_risk_evidence.py, and those jobs plus final-status exist in
   the workflow.
6. Every instrumentation lane has a bounded failure-evidence capture step,
   either as a post-run helper or inside the live emulator-runner wrapper.
7. docs/engineering/ci-test-portfolio.md mentions every lane ID and every
   surface name (existence check; content review owns the prose).

Run: python3 tools/repo-contract/validate_ci_portfolio.py
"""

from __future__ import annotations

import os
import re
import sys

import yaml

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
CI_YML_PATH = os.path.join(REPO_ROOT, ".github", "workflows", "ci.yml")
MAP_PATH = os.path.join(REPO_ROOT, "tools", "repo-contract", "ci_portfolio_map.yml")
PORTFOLIO_DOC_PATH = os.path.join(
    REPO_ROOT, "docs", "engineering", "ci-test-portfolio.md"
)

# Fixed coupling: validate_high_risk_evidence.py names these jobs and the
# final-status aggregation. Renaming them requires updating that validator and
# the branch protection required checks in the same change.
FIXED_PERMANENT_GATES = {"organizer-unit-tests", "check-style", "build-debug-apk"}
FIXED_AGGREGATION_JOB = "final-status"
CHANGES_JOB = "changes"
EVERY_RUN_JOB = "validate-repo-contract"
LIVE_CAPTURE_WRAPPER = "run-emulator-command-with-failure-capture.sh"

SURFACE_OUTPUT_RE = re.compile(r"needs\.changes\.outputs\.(surface_[a-z0-9_]+)")


class PortfolioError(Exception):
    pass


def load_yaml(path: str) -> dict:
    with open(path, encoding="utf-8") as handle:
        data = yaml.safe_load(handle)
    if not isinstance(data, dict):
        raise PortfolioError(f"{path}: top-level YAML is not a mapping")
    return data


def lane_ids(workflow: dict) -> set[str]:
    return {
        job for job in workflow.get("jobs", {}) if job.startswith("organizer-instrumentation-")
    }


def lane_surface_refs(job_def: dict) -> set[str]:
    condition = job_def.get("if")
    if not isinstance(condition, str) or "needs.changes.outputs" not in condition:
        raise PortfolioError(
            "instrumentation lane lacks a needs.changes.outputs if-condition"
        )
    return set(SURFACE_OUTPUT_RE.findall(condition))


def defined_surfaces(workflow: dict) -> set[str]:
    outputs = workflow["jobs"][CHANGES_JOB].get("outputs", {})
    return {name for name in outputs if name.startswith("surface_")}


def validate() -> list[str]:
    workflow = load_yaml(CI_YML_PATH)
    ci_map = load_yaml(MAP_PATH)
    jobs = workflow.get("jobs", {})
    problems: list[str] = []

    if CHANGES_JOB not in jobs:
        raise PortfolioError(f"ci.yml: missing {CHANGES_JOB} job")
    if EVERY_RUN_JOB not in jobs:
        raise PortfolioError(f"ci.yml: missing {EVERY_RUN_JOB} job")

    map_lanes_raw = ci_map.get("lanes")
    if not isinstance(map_lanes_raw, dict) or not map_lanes_raw:
        raise PortfolioError("ci_portfolio_map.yml: 'lanes' mapping is missing/empty")
    map_lanes: dict[str, set[str]] = {}
    for lane, entry in map_lanes_raw.items():
        surfaces = entry.get("surfaces") if isinstance(entry, dict) else None
        if not isinstance(surfaces, list) or not all(
            isinstance(item, str) for item in surfaces
        ):
            raise PortfolioError(
                f"ci_portfolio_map.yml: lane {lane} needs a 'surfaces' list"
            )
        map_lanes[lane] = set(surfaces)

    permanent_gates_raw = ci_map.get("permanent_gates")
    if not isinstance(permanent_gates_raw, list):
        raise PortfolioError("ci_portfolio_map.yml: 'permanent_gates' list is missing")
    permanent_gates = set(permanent_gates_raw)
    permanent_only_raw = ci_map.get("permanent_only_surfaces", [])
    if not isinstance(permanent_only_raw, list):
        raise PortfolioError(
            "ci_portfolio_map.yml: 'permanent_only_surfaces' must be a list"
        )
    permanent_only = set(permanent_only_raw)

    wf_lanes = lane_ids(workflow)

    # 1. Lane set equality.
    if set(map_lanes) != wf_lanes:
        problems.append(
            "lane set mismatch between ci_portfolio_map.yml and ci.yml: "
            f"map-only={sorted(set(map_lanes) - wf_lanes)} "
            f"workflow-only={sorted(wf_lanes - set(map_lanes))}"
        )

    # 2. Per-lane edge equality (only for lanes present in both).
    for lane in sorted(set(map_lanes) & wf_lanes):
        refs = lane_surface_refs(jobs[lane])
        if refs != map_lanes[lane]:
            problems.append(
                f"lane {lane}: surface edges differ between map and workflow "
                f"if-condition: map={sorted(map_lanes[lane])} "
                f"workflow={sorted(refs)}"
            )

    # 3. Bidirectional surface vocabulary check.
    declared = set().union(*map_lanes.values()) if map_lanes else set()
    declared = declared | permanent_only
    defined = defined_surfaces(workflow)
    if defined != declared:
        problems.append(
            "surface vocabulary mismatch (defined == declared required): "
            f"changes-only={sorted(defined - declared)} "
            f"map-only={sorted(declared - defined)}"
        )

    # 4. final-status needs exact set.
    expected_needs = (
        {CHANGES_JOB, EVERY_RUN_JOB} | permanent_gates | wf_lanes
    )
    agg = jobs.get(FIXED_AGGREGATION_JOB, {})
    needs = agg.get("needs")
    needs_set = {needs} if isinstance(needs, str) else set(needs or [])
    if needs_set != expected_needs:
        problems.append(
            f"final-status needs mismatch: expected={sorted(expected_needs)} "
            f"actual={sorted(needs_set)}"
        )

    # 5. Permanent gate fixed point.
    if permanent_gates != FIXED_PERMANENT_GATES:
        problems.append(
            "map.permanent_gates must equal the high-risk-coupled job set "
            f"{sorted(FIXED_PERMANENT_GATES)}; got {sorted(permanent_gates)}"
        )
    for gate in sorted(FIXED_PERMANENT_GATES | {FIXED_AGGREGATION_JOB}):
        if gate not in jobs:
            problems.append(f"ci.yml: required job {gate} is missing")

    # 6. Every instrumentation lane carries the bounded capture step.
    for lane in sorted(wf_lanes):
        steps = jobs[lane].get("steps", [])
        step_commands = [str(step.get("run", "")) for step in steps]
        step_commands.extend(
            str(step.get("with", {}).get("script", ""))
            for step in steps
            if isinstance(step.get("with"), dict)
        )
        has_capture = any(
            "capture-emulator-failure-evidence.sh" in command
            or LIVE_CAPTURE_WRAPPER in command
            for command in step_commands
        )
        if not has_capture:
            problems.append(f"lane {lane}: failure-evidence capture step is missing")

    # 7. Portfolio doc mentions every lane and surface.
    if not os.path.exists(PORTFOLIO_DOC_PATH):
        problems.append("docs/engineering/ci-test-portfolio.md is missing")
    else:
        with open(PORTFOLIO_DOC_PATH, encoding="utf-8") as handle:
            doc = handle.read()
        for lane in sorted(wf_lanes):
            if lane not in doc:
                problems.append(f"ci-test-portfolio.md does not mention lane {lane}")
        for surface in sorted(defined):
            if surface not in doc:
                problems.append(
                    f"ci-test-portfolio.md does not mention surface {surface}"
                )

    return problems


def main() -> int:
    try:
        problems = validate()
    except PortfolioError as exc:
        print(f"CI portfolio validation error: {exc}")
        return 1
    if problems:
        print("CI portfolio validation failed:")
        for problem in problems:
            print(f"- {problem}")
        return 1
    print("CI portfolio validation OK")
    return 0


if __name__ == "__main__":
    sys.exit(main())
