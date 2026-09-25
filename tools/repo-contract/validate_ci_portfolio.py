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
6. Every instrumentation lane runs its bounded failure-evidence capture inside
   the live emulator-runner wrapper (Issue #438 end state). A runner-external
   capture step is rejected: after the runner tears the emulator down it can
   only record a dead device.
7. Every supporting contract test in the map has a real path, an every-run
   owner job, and an exact workflow invocation.
8. Every instrumentation lane uploads the capture output directory with a
   failure-time artifact path.
9. docs/engineering/ci-test-portfolio.md mentions every lane ID and every
   surface name (existence check; content review owns the prose).

Run: python3 tools/repo-contract/validate_ci_portfolio.py
"""

from __future__ import annotations

import os
import re
import shlex
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
LIVE_CAPTURE_RUNNER = "reactivecircus/android-emulator-runner@v2"
CAPTURE_SCRIPT = "capture-emulator-failure-evidence.sh"
CONTRACT_TEST_ID = "emulator_failure_capture_lifecycle"
CONTRACT_TEST_IMPACT = "ci-wrapper-artifact-handling"

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


def shell_tokens(command: object) -> list[str]:
    if not isinstance(command, str):
        return []
    try:
        return shlex.split(command)
    except ValueError:
        return []


def post_run_capture_output_dirs(steps: list[object]) -> set[str]:
    expected_prefix = [
        "timeout",
        "--kill-after=30",
        "300",
        "bash",
        f"tools/ci/{CAPTURE_SCRIPT}",
    ]
    dirs: set[str] = set()
    for step in steps:
        if not isinstance(step, dict):
            continue
        tokens = shell_tokens(step.get("run"))
        if (
            len(tokens) >= len(expected_prefix) + 2
            and tokens[: len(expected_prefix)] == expected_prefix
        ):
            output_dir = tokens[len(expected_prefix) + 1]
            if output_dir:
                dirs.add(output_dir)
    return dirs


def live_capture_output_dirs(steps: list[object]) -> set[str]:
    dirs: set[str] = set()
    for step in steps:
        if not isinstance(step, dict) or step.get("uses") != LIVE_CAPTURE_RUNNER:
            continue
        with_block = step.get("with")
        if not isinstance(with_block, dict):
            continue
        tokens = shell_tokens(with_block.get("script"))
        if len(tokens) < 5 or tokens[:2] != ["bash", f"tools/ci/{LIVE_CAPTURE_WRAPPER}"]:
            continue
        try:
            delimiter = tokens.index("--", 2)
        except ValueError:
            continue
        if delimiter >= 4 and delimiter < len(tokens) - 1 and tokens[3]:
            dirs.add(tokens[3])
    return dirs


def failure_evidence_upload_paths(steps: list[object]) -> set[str]:
    paths: set[str] = set()
    for step in steps:
        if not isinstance(step, dict) or step.get("uses") != "actions/upload-artifact@v6":
            continue
        with_block = step.get("with")
        if not isinstance(with_block, dict):
            continue
        path = with_block.get("path")
        if isinstance(path, str):
            paths.update(line.strip() for line in path.splitlines() if line.strip())
    return paths


def validate_contract_tests(ci_map: dict, jobs: dict, problems: list[str]) -> None:
    contract_tests = ci_map.get("contract_tests")
    if not isinstance(contract_tests, dict) or not contract_tests:
        problems.append("ci_portfolio_map.yml: contract_tests mapping is missing/empty")
        return
    if CONTRACT_TEST_ID not in contract_tests:
        problems.append(f"ci_portfolio_map.yml: missing contract test {CONTRACT_TEST_ID}")
    for test_id, entry in contract_tests.items():
        if not isinstance(entry, dict):
            problems.append(f"contract test {test_id}: metadata must be a mapping")
            continue
        path = entry.get("path")
        command = entry.get("command")
        owner_job = entry.get("owner_job")
        trigger = entry.get("trigger")
        impact = entry.get("impact")
        if not isinstance(path, str) or not path:
            problems.append(f"contract test {test_id}: path is missing")
        elif not os.path.isfile(os.path.join(REPO_ROOT, path)):
            problems.append(f"contract test {test_id}: path does not exist: {path}")
        if not isinstance(command, str) or not command:
            problems.append(f"contract test {test_id}: command is missing")
        if owner_job != EVERY_RUN_JOB:
            problems.append(
                f"contract test {test_id}: owner_job must be {EVERY_RUN_JOB}"
            )
        if trigger != "every_run":
            problems.append(f"contract test {test_id}: trigger must be every_run")
        if impact != CONTRACT_TEST_IMPACT:
            problems.append(
                f"contract test {test_id}: impact must be {CONTRACT_TEST_IMPACT}"
            )
        owner = jobs.get(owner_job) if isinstance(owner_job, str) else None
        if not isinstance(owner, dict):
            problems.append(f"contract test {test_id}: owner job is missing")
            continue
        if "if" in owner:
            problems.append(
                f"contract test {test_id}: every-run owner must not have an if condition"
            )
        run_commands = [
            step.get("run")
            for step in owner.get("steps", [])
            if isinstance(step, dict)
        ]
        if command not in run_commands:
            problems.append(
                f"contract test {test_id}: command is not invoked by {EVERY_RUN_JOB}"
            )


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

    # 6. Every instrumentation lane captures inside the live emulator-runner
    # wrapper and no longer carries a runner-external capture step (Issue
    # #438). A live wrapper only counts when it is the command in the expected
    # emulator-runner action; arbitrary echoes or unrelated action scripts must
    # not satisfy this contract.
    for lane in sorted(wf_lanes):
        steps = jobs[lane].get("steps", [])
        capture_dirs = live_capture_output_dirs(steps)
        if not capture_dirs:
            problems.append(
                f"lane {lane}: live failure-evidence capture wrapper is missing"
            )
        if post_run_capture_output_dirs(steps):
            problems.append(
                f"lane {lane}: runner-external failure-evidence capture step must "
                "be removed once the live wrapper captures before teardown"
            )
        if capture_dirs:
            upload_paths = failure_evidence_upload_paths(steps)
            expected_upload_paths = {
                f"{output_dir.rstrip('/')}/**" for output_dir in capture_dirs
            }
            if not upload_paths & expected_upload_paths:
                if upload_paths:
                    problems.append(
                        f"lane {lane}: failure-time upload path does not match capture output directory "
                        f"(expected one of {sorted(expected_upload_paths)}, got {sorted(upload_paths)})"
                    )
                else:
                    problems.append(
                        f"lane {lane}: failure-time evidence upload path is missing"
                    )

    # 7. Supporting contract test ownership and trigger are machine-checked
    # separately from lane/surface edges.
    validate_contract_tests(ci_map, jobs, problems)

    # 8. Portfolio doc mentions every lane and surface.
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
