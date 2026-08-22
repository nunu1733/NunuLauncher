#!/usr/bin/env python3
"""Measure the organizer's upstream patch surface without network access.

The checked-in baseline file records one accepted organizer patch inventory at a
fixed upstream commit and main commit. This tool compares an arbitrary target
commit against an upstream commit, distinguishes explicit exclusions and added
project-owned modules from Lawnchair/Launcher3 patches, and assigns every
counted patch path to an explicit bridge responsibility. It uses only the local
Git object database and Python's standard library.

Default invocation reproduces the accepted baseline exactly::

    python3 tools/repo-contract/measure_upstream_patch_surface.py --verify

To inspect a rebase or organizer branch, give the candidate upstream and head::

    python3 tools/repo-contract/measure_upstream_patch_surface.py \
        --upstream <new-upstream-sha> --target HEAD --enforce-baseline

``--enforce-baseline`` intentionally treats counted growth as a review
requirement. It is not a quality score and does not assert that a smaller
surface is necessarily safer.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable, Mapping, Sequence

ROOT = Path(__file__).resolve().parent.parent.parent
DEFAULT_BASELINE = ROOT / "docs" / "assessment" / "upstream-patch-surface-baseline.json"


class MeasurementError(RuntimeError):
    """A local Git state or baseline-inventory error that prevents measurement."""


@dataclass(frozen=True)
class ChangedPath:
    status: str
    path: str
    additions: int
    deletions: int


@dataclass(frozen=True)
class ClassifiedPath:
    change: ChangedPath
    category: str
    group_id: str | None


def run_git(args: Sequence[str], *, root: Path = ROOT) -> str:
    """Run a read-only Git command and return its UTF-8 stdout."""
    result = subprocess.run(
        ["git", *args],
        cwd=root,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=False,
    )
    if result.returncode:
        raise MeasurementError(result.stderr.strip() or "git command failed")
    return result.stdout


def require_commit(revision: str, *, root: Path = ROOT) -> str:
    """Resolve a revision to a full commit SHA in the local object database."""
    return run_git(["rev-parse", "--verify", f"{revision}^{{commit}}"], root=root).strip()


def ensure_ancestor(upstream: str, target: str, *, root: Path = ROOT) -> None:
    """Reject comparisons that would accidentally count unrelated upstream history."""
    result = subprocess.run(
        ["git", "merge-base", "--is-ancestor", upstream, target],
        cwd=root,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=False,
    )
    if result.returncode:
        raise MeasurementError(
            "the upstream commit must be an ancestor of the target; fetch or select "
            "the candidate upstream commit explicitly before measuring"
        )


def parse_numstat(raw: str) -> dict[str, tuple[int, int]]:
    """Parse tab-delimited ``git diff --numstat`` output for text files."""
    result: dict[str, tuple[int, int]] = {}
    for line in raw.splitlines():
        additions, deletions, path = line.split("\t", 2)
        if additions == "-" or deletions == "-":
            raise MeasurementError(f"binary file is outside the metric unless explicitly excluded: {path}")
        result[path] = (int(additions), int(deletions))
    return result


def changed_paths(upstream: str, target: str, *, root: Path = ROOT) -> list[ChangedPath]:
    """Return every no-rename path change between two local commits.

    Classification, rather than a source-root allowlist, decides whether each
    path is counted. This prevents production resources, schemas, manifests, and
    other upstream-owned production files from being silently omitted.
    """
    name_status = run_git(["diff", "--no-renames", "--name-status", f"{upstream}..{target}"], root=root)
    numstat = parse_numstat(run_git(["diff", "--no-renames", "--numstat", f"{upstream}..{target}"], root=root))
    changes: list[ChangedPath] = []
    for line in name_status.splitlines():
        status, path = line.split("\t", 1)
        additions, deletions = numstat[path]
        changes.append(ChangedPath(status=status, path=path, additions=additions, deletions=deletions))
    return sorted(changes, key=lambda item: item.path)


def path_exists_at(revision: str, path: str, *, root: Path = ROOT) -> bool:
    """Return whether a tracked path exists at the given local commit."""
    result = subprocess.run(
        ["git", "cat-file", "-e", f"{revision}:{path}"],
        cwd=root,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=False,
    )
    return result.returncode == 0


def is_explicitly_excluded(path: str, exclusions: Mapping[str, object]) -> bool:
    """Return whether a path matches a documented non-production exclusion."""
    exact_paths = tuple(str(value) for value in exclusions.get("paths", []))
    prefixes = tuple(str(value) for value in exclusions.get("prefixes", []))
    suffixes = tuple(str(value) for value in exclusions.get("suffixes", []))
    return path in exact_paths or path.startswith(prefixes) or path.endswith(suffixes)


def group_index(groups: Iterable[Mapping[str, object]]) -> dict[str, str]:
    """Map every explicitly counted path to exactly one bridge-responsibility group."""
    index: dict[str, str] = {}
    for group in groups:
        group_id = str(group["id"])
        for path in group["paths"]:  # type: ignore[index]
            path = str(path)
            if path in index:
                raise MeasurementError(f"counted path is assigned twice: {path}")
            index[path] = group_id
    return index


def classify_paths(
    changes: Iterable[ChangedPath],
    *,
    upstream: str,
    project_owned_prefixes: Sequence[str],
    exclusions: Mapping[str, object],
    groups: Iterable[Mapping[str, object]],
    root: Path = ROOT,
) -> list[ClassifiedPath]:
    """Classify all changes and fail if a non-excluded path lacks an owner."""
    index = group_index(groups)
    classified: list[ClassifiedPath] = []
    missing: list[str] = []
    for change in changes:
        if is_explicitly_excluded(change.path, exclusions):
            classified.append(ClassifiedPath(change, "explicit exclusion", None))
            continue
        existed_upstream = path_exists_at(upstream, change.path, root=root)
        project_owned_addition = (
            change.status == "A"
            and not existed_upstream
            and any(change.path.startswith(prefix) for prefix in project_owned_prefixes)
        )
        if project_owned_addition:
            classified.append(ClassifiedPath(change, "project-owned addition", None))
            continue
        group_id = index.get(change.path)
        if group_id is None:
            missing.append(change.path)
            continue
        category = "upstream-file patch" if existed_upstream else "bridge addition"
        classified.append(ClassifiedPath(change, category, group_id))
    if missing:
        lines = "\n".join(f"  - {path}" for path in sorted(missing))
        raise MeasurementError(
            "changed path is neither explicitly excluded, project-owned, nor assigned to a bridge responsibility:\n"
            f"{lines}\nAdd the path to docs/assessment/upstream-patch-surface-baseline.json "
            "with an owning bridge group, or document an explicit non-production exclusion."
        )
    return classified


def aggregate(classified: Iterable[ClassifiedPath], groups: Iterable[Mapping[str, object]]) -> dict[str, object]:
    """Aggregate counted, project-owned, and explicitly excluded path metrics."""
    by_group = {str(group["id"]): {"files": 0, "additions": 0, "deletions": 0} for group in groups}
    totals = {
        "counted_files": 0,
        "counted_additions": 0,
        "counted_deletions": 0,
        "project_owned_files": 0,
        "project_owned_additions": 0,
        "project_owned_deletions": 0,
        "excluded_files": 0,
        "excluded_additions": 0,
        "excluded_deletions": 0,
    }
    for item in classified:
        change = item.change
        if item.category == "explicit exclusion":
            totals["excluded_files"] += 1
            totals["excluded_additions"] += change.additions
            totals["excluded_deletions"] += change.deletions
            continue
        if item.category == "project-owned addition":
            totals["project_owned_files"] += 1
            totals["project_owned_additions"] += change.additions
            totals["project_owned_deletions"] += change.deletions
            continue
        group = by_group[item.group_id or ""]
        group["files"] += 1
        group["additions"] += change.additions
        group["deletions"] += change.deletions
        totals["counted_files"] += 1
        totals["counted_additions"] += change.additions
        totals["counted_deletions"] += change.deletions
    return {"totals": totals, "groups": by_group}


def paths_digest(paths: Iterable[str]) -> str:
    """Return a stable SHA-256 digest of a sorted path set."""
    content = "\n".join(sorted(paths)).encode("utf-8")
    return hashlib.sha256(content).hexdigest()


def expected_measurement_from(
    classified: Iterable[ClassifiedPath], report: Mapping[str, object]
) -> dict[str, object]:
    """Create the complete exact-baseline snapshot from one classified diff."""
    classified = list(classified)
    totals = report["totals"]  # type: ignore[index]
    groups = report["groups"]  # type: ignore[index]
    expected: dict[str, object] = {
        key: int(totals[key])
        for key in (
            "counted_files",
            "counted_additions",
            "counted_deletions",
            "project_owned_files",
            "project_owned_additions",
            "project_owned_deletions",
            "excluded_files",
            "excluded_additions",
            "excluded_deletions",
        )
    }
    expected["counted_paths_sha256"] = paths_digest(
        item.change.path for item in classified if item.category not in {"explicit exclusion", "project-owned addition"}
    )
    expected["project_owned_paths_sha256"] = paths_digest(
        item.change.path for item in classified if item.category == "project-owned addition"
    )
    expected["excluded_paths_sha256"] = paths_digest(
        item.change.path for item in classified if item.category == "explicit exclusion"
    )
    expected["groups"] = {
        group_id: {
            "files": int(metrics["files"]),
            "additions": int(metrics["additions"]),
            "deletions": int(metrics["deletions"]),
            "paths_sha256": paths_digest(
                item.change.path
                for item in classified
                if item.category not in {"explicit exclusion", "project-owned addition"} and item.group_id == group_id
            ),
        }
        for group_id, metrics in sorted(groups.items())
    }
    return expected


def expected_metrics(baseline: Mapping[str, object]) -> Mapping[str, object]:
    """Return the accepted exact baseline, raising a helpful error if absent."""
    try:
        return baseline["expected_measurement"]  # type: ignore[return-value]
    except KeyError as error:
        raise MeasurementError("baseline file has no expected_measurement section") from error


def exact_baseline_mismatches(
    *,
    upstream: str,
    target: str,
    baseline: Mapping[str, object],
    actual: Mapping[str, object],
) -> list[str]:
    """Return exact-baseline mismatches, including path membership and group ownership."""
    mismatches: list[str] = []
    if upstream != str(baseline["upstream_commit"]):
        mismatches.append(f"upstream is {upstream}, expected {baseline['upstream_commit']}")
    if target != str(baseline["main_commit"]):
        mismatches.append(f"target is {target}, expected {baseline['main_commit']}")
    expected = expected_metrics(baseline)
    expected_keys = set(expected)
    actual_keys = set(actual)
    for key in sorted(expected_keys | actual_keys):
        if expected.get(key) != actual.get(key):
            mismatches.append(f"{key}: expected {expected.get(key)!r}, measured {actual.get(key)!r}")
    return mismatches


def comparison(current: Mapping[str, object], expected: Mapping[str, object]) -> dict[str, int]:
    """Return counted-only deltas; project-owned and excluded volume stay visible but unscored."""
    return {
        key: int(current[key]) - int(expected[key])
        for key in ("counted_files", "counted_additions", "counted_deletions")
    }


def has_growth(deltas: Mapping[str, int]) -> bool:
    """Growth is any positive counted file or changed-line delta, not a quality judgment."""
    return any(value > 0 for value in deltas.values())


def requested_exit_code(
    *, verify: bool, enforce_baseline: bool, exact_mismatches: Sequence[str], deltas: Mapping[str, int]
) -> tuple[int, str | None]:
    """Return the CLI result for exact reproduction and growth-review requests."""
    if verify and exact_mismatches:
        return 1, "FAIL: the requested measurement does not reproduce the accepted exact baseline."
    if enforce_baseline and has_growth(deltas):
        return 1, (
            "REVIEW REQUIRED: counted patch surface grew; record the owning Issue and rationale "
            "before accepting a new baseline."
        )
    return 0, None


def print_report(
    *,
    upstream: str,
    target: str,
    classified: Sequence[ClassifiedPath],
    report: Mapping[str, object],
    baseline: Mapping[str, object],
    deltas: Mapping[str, int],
) -> None:
    """Render an auditable, stable text report suitable for local and CI logs."""
    group_metadata = {str(group["id"]): group for group in baseline["bridge_groups"]}  # type: ignore[index]
    totals = report["totals"]  # type: ignore[index]
    groups = report["groups"]  # type: ignore[index]
    print("Upstream patch-surface measurement")
    print(f"  upstream: {upstream}")
    print(f"  target:   {target}")
    print("  metric:   all changed repository paths minus explicit non-production exclusions and project-owned additions")
    print()
    print("Bridge responsibilities:")
    for group_id in sorted(groups):
        metrics = groups[group_id]
        responsibility = group_metadata[group_id]["responsibility"]
        print(
            f"  - {group_id}: {metrics['files']} file(s), +{metrics['additions']} / -{metrics['deletions']} lines"
        )
        print(f"    {responsibility}")
    print()
    print(
        "Counted upstream/bridge surface: "
        f"{totals['counted_files']} file(s), +{totals['counted_additions']} / -{totals['counted_deletions']} lines"
    )
    print(
        "Excluded project-owned additions: "
        f"{totals['project_owned_files']} file(s), +{totals['project_owned_additions']} / -{totals['project_owned_deletions']} lines"
    )
    print(
        "Explicitly excluded non-production churn: "
        f"{totals['excluded_files']} file(s), +{totals['excluded_additions']} / -{totals['excluded_deletions']} lines"
    )
    print(
        "Baseline delta: "
        f"files {deltas['counted_files']:+d}, additions {deltas['counted_additions']:+d}, "
        f"deletions {deltas['counted_deletions']:+d}"
    )
    print()
    print("Counted paths:")
    for item in classified:
        if item.category not in {"explicit exclusion", "project-owned addition"}:
            print(f"  - [{item.group_id}] {item.change.status} {item.change.path}")


def load_baseline(path: Path) -> Mapping[str, object]:
    """Load and minimally validate the checked-in baseline inventory."""
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except FileNotFoundError as error:
        raise MeasurementError(f"baseline file not found: {path}") from error
    except json.JSONDecodeError as error:
        raise MeasurementError(f"invalid baseline JSON: {error}") from error
    required = {
        "upstream_commit",
        "main_commit",
        "project_owned_addition_prefixes",
        "explicit_exclusions",
        "bridge_groups",
        "expected_measurement",
    }
    missing = required.difference(data)
    if missing:
        raise MeasurementError(f"baseline file is missing required keys: {', '.join(sorted(missing))}")
    return data


def parse_args(argv: Sequence[str] | None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--baseline-file", type=Path, default=DEFAULT_BASELINE)
    parser.add_argument("--upstream", help="local upstream commit; defaults to the recorded baseline")
    parser.add_argument("--target", help="local target commit; defaults to the recorded main commit")
    parser.add_argument("--verify", action="store_true", help="fail unless the recorded baseline is reproduced exactly")
    parser.add_argument(
        "--enforce-baseline",
        action="store_true",
        help="fail when counted patch files or changed lines grow beyond the accepted baseline",
    )
    parser.add_argument(
        "--print-expected-measurement",
        action="store_true",
        help="print the exact-measurement JSON fragment for a reviewed baseline update",
    )
    return parser.parse_args(argv)


def main(argv: Sequence[str] | None = None) -> int:
    args = parse_args(argv)
    try:
        baseline = load_baseline(args.baseline_file)
        upstream = require_commit(args.upstream or str(baseline["upstream_commit"]))
        target = require_commit(args.target or str(baseline["main_commit"]))
        ensure_ancestor(upstream, target)
        changes = changed_paths(upstream, target)
        classified = classify_paths(
            changes,
            upstream=upstream,
            project_owned_prefixes=tuple(baseline["project_owned_addition_prefixes"]),  # type: ignore[arg-type]
            exclusions=baseline["explicit_exclusions"],  # type: ignore[arg-type]
            groups=baseline["bridge_groups"],  # type: ignore[arg-type]
        )
        report = aggregate(classified, baseline["bridge_groups"])  # type: ignore[arg-type]
        actual = expected_measurement_from(classified, report)
        if args.print_expected_measurement:
            print(json.dumps(actual, indent=2, sort_keys=True))
            return 0
        deltas = comparison(actual, expected_metrics(baseline))
        print_report(
            upstream=upstream,
            target=target,
            classified=classified,
            report=report,
            baseline=baseline,
            deltas=deltas,
        )
        exact_mismatches = exact_baseline_mismatches(
            upstream=upstream, target=target, baseline=baseline, actual=actual
        )
        exit_code, message = requested_exit_code(
            verify=args.verify,
            enforce_baseline=args.enforce_baseline,
            exact_mismatches=exact_mismatches,
            deltas=deltas,
        )
        if message:
            print(message, file=sys.stderr)
        if exit_code:
            if args.verify:
                for mismatch in exact_mismatches:
                    print(f"  - {mismatch}", file=sys.stderr)
            return exit_code
        print("PASS: measurement completed with complete bridge ownership.")
        return 0
    except MeasurementError as error:
        print(f"FAIL: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
