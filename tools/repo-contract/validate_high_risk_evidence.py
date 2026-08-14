#!/usr/bin/env python3
"""High-risk independent-evidence gate for NunuLauncher pull requests.

Implements the Issue #43 rule: a PR that can mutate persisted home layout,
recovery state, or schema migration must not merge on the implementing agent's
own PR-body summary alone. The gate is satisfied by two artifacts the PR body
cannot fake:

  1. Independently executed CI evidence: a completed, successful ``CI``
     workflow run on the audited commit, verified through the GitHub API.
  2. A distinct audit record at ``docs/assessment/pr-<PR number>-<slug>.md``
     whose machine-checked fields (Auditor, Head SHA, CI run, spec/ADR
     criteria) tie the audit to the exact code being merged.

A PR is high-risk when it carries the ``risk: layout-data`` or
``risk: migration`` label, or when it changes one of the known layout/migration
paths (a backstop for missed labels). Low-risk PRs pass immediately.

Runs on Python 3.9+ with the standard library only, mirroring
validate_repo_contract.py. Requires ``git`` and ``gh`` (GH_TOKEN) in the CI
environment. Exit code is non-zero when the gate fails.
"""

from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
from dataclasses import dataclass, field
from pathlib import Path
from typing import List, Optional, Sequence, Tuple

# Labels that make a PR high-risk. Labeling is the primary, authoritative
# trigger; the path list below is only a backstop for missed labels.
HIGH_RISK_LABELS: Tuple[str, ...] = (
    "risk: layout-data",
    "risk: migration",
)

# Production paths that own persisted home-layout, recovery, or schema state.
# Derived from the Issue #44 shared-writer audit's runtime writer inventory;
# pure planning code and tests are deliberately not listed.
HIGH_RISK_PATH_PREFIXES: Tuple[str, ...] = (
    "lawnchair/src/app/lawnchair/organizer/application/",
    "src/com/android/launcher3/provider/",
    "lawnchair/src/app/lawnchair/backup/",
    "lawnchair/src/app/lawnchair/deck/",
)

HIGH_RISK_PATH_FILES: Tuple[str, ...] = (
    "src/com/android/launcher3/LauncherProvider.java",
    "src/com/android/launcher3/model/LayoutWriteCoordinator.java",
    "src/com/android/launcher3/model/ModelWriter.java",
    "src/com/android/launcher3/model/ModelDbController.java",
    "src/com/android/launcher3/model/GridSizeMigrationUtil.java",
)

AUDIT_DIR = "docs/assessment"
AUDIT_TEMPLATE = "docs/assessment/_template.md"
CI_WORKFLOW_PATH = ".github/workflows/ci.yml"

# Audit fields may appear as list items ("- Auditor: ...") or blockquote
# metadata ("> Audit date: ..."); accept an optional single marker prefix.
_FIELD_PREFIX = r"^(?:[-*>]\s*)?"

_AUDITOR_RE = re.compile(_FIELD_PREFIX + r"Auditor:\s*(\S.*?)\s*$", re.MULTILINE)
_AUDIT_DATE_RE = re.compile(
    _FIELD_PREFIX + r"Audit date:\s*(\d{4}-\d{2}-\d{2})\s*$", re.MULTILINE
)
_HEAD_SHA_RE = re.compile(
    _FIELD_PREFIX + r"Head SHA:\s*([0-9a-f]{40})\s*$", re.MULTILINE
)
_CI_RUN_LINE_RE = re.compile(
    _FIELD_PREFIX + r"CI run:\s*(.+)$", re.MULTILINE
)
_RUN_URL_RE = re.compile(
    r"^https?://github\.com/([^/]+)/([^/]+)/actions/runs/(\d+)/?"
)
_CRITERIA_RE = re.compile(r"(specs/[\w.-]+/spec\.md|docs/adr/[\w.-]+\.md)")


# --- High-risk classification ------------------------------------------------


def is_high_risk_path(path: str) -> bool:
    return path in HIGH_RISK_PATH_FILES or path.startswith(HIGH_RISK_PATH_PREFIXES)


def classify_pr(labels: Sequence[str], changed_files: Sequence[str]) -> Tuple[bool, List[str]]:
    """Return (is_high_risk, human-readable reasons) for a PR."""

    reasons: List[str] = []
    for label in labels:
        if label in HIGH_RISK_LABELS:
            reasons.append(f"label {label!r}")
    path_hits = [p for p in changed_files if is_high_risk_path(p)]
    if path_hits:
        examples = ", ".join(path_hits[:3])
        suffix = f", ... ({len(path_hits)} total)" if len(path_hits) > 3 else ""
        reasons.append(f"high-risk path change(s): {examples}{suffix}")
    return (bool(reasons), reasons)


# --- Audit document parsing --------------------------------------------------


@dataclass
class AuditDocument:
    """A parsed high-risk audit record and its structural problems."""

    path: Path
    auditor: Optional[str] = None
    audit_date: Optional[str] = None
    head_sha: Optional[str] = None
    ci_run_urls: List[str] = field(default_factory=list)
    criteria_refs: List[str] = field(default_factory=list)
    findings: List[str] = field(default_factory=list)


def find_audit_file(root: Path, pr_number: int) -> Tuple[Optional[Path], Optional[str]]:
    """Locate ``docs/assessment/pr-<number>-<slug>.md`` in the checked-out tree.

    Returns (path, error). Multiple matches are ambiguous and rejected so an
    audit cannot be shadowed by a second file with a competing Head SHA.
    """

    audit_dir = root / AUDIT_DIR
    if not audit_dir.is_dir():
        return None, f"no {AUDIT_DIR}/ directory; audit record missing"

    matches = sorted(
        p
        for p in audit_dir.iterdir()
        if p.is_file()
        and p.suffix == ".md"
        and (p.name == f"pr-{pr_number}.md" or p.name.startswith(f"pr-{pr_number}-"))
    )
    if not matches:
        return None, (
            f"no {AUDIT_DIR}/pr-{pr_number}-<slug>.md audit record for this PR"
        )
    if len(matches) > 1:
        names = ", ".join(p.name for p in matches)
        return None, f"ambiguous audit records for PR #{pr_number}: {names}"
    return matches[0], None


def parse_audit(path: Path) -> AuditDocument:
    """Parse the machine-checked fields of an audit record."""

    text = path.read_text(encoding="utf-8")
    doc = AuditDocument(path=path)

    match = _AUDITOR_RE.search(text)
    if match:
        doc.auditor = match.group(1)
    else:
        doc.findings.append("missing 'Auditor:' line naming the auditing party")

    match = _AUDIT_DATE_RE.search(text)
    if match:
        doc.audit_date = match.group(1)
    else:
        doc.findings.append("missing 'Audit date:' line (YYYY-MM-DD)")

    match = _HEAD_SHA_RE.search(text)
    if match:
        doc.head_sha = match.group(1)
    else:
        doc.findings.append("missing 'Head SHA:' line with a 40-hex commit")

    for line_match in _CI_RUN_LINE_RE.finditer(text):
        for token in line_match.group(1).split():
            if token.startswith("http://") or token.startswith("https://"):
                doc.ci_run_urls.append(token.rstrip(".,;)"))
    if not doc.ci_run_urls:
        doc.findings.append("missing 'CI run:' line with an Actions run URL")

    doc.criteria_refs = _CRITERIA_RE.findall(text)
    if not doc.criteria_refs:
        doc.findings.append(
            "no spec/ADR criteria reference (expected specs/<n>-<slug>/spec.md "
            "or docs/adr/*.md)"
        )
    return doc


def parse_run_url(url: str, repo: str) -> Optional[int]:
    """Return the Actions run id when ``url`` points at this repository."""

    match = _RUN_URL_RE.match(url)
    if not match:
        return None
    if f"{match.group(1)}/{match.group(2)}".lower() != repo.lower():
        return None
    return int(match.group(3))


# --- Git lineage and GitHub API verification ---------------------------------


def _run_git(args: Sequence[str]) -> subprocess.CompletedProcess:
    return subprocess.run(
        ["git", *args], capture_output=True, text=True, check=False
    )


def is_ancestor(sha: str, head: str) -> bool:
    return _run_git(["merge-base", "--is-ancestor", sha, head]).returncode == 0


def non_docs_files_after(audit_sha: str, head_sha: str) -> List[str]:
    """Files changed after the audited commit that are outside ``docs/``.

    The audit may pin an earlier commit only when every later PR commit is a
    documentation change (typically the audit record itself); any code change
    invalidates the audit and forces a re-audit.
    """

    proc = _run_git(["diff", "--name-only", audit_sha, head_sha])
    if proc.returncode != 0:
        return [f"<git diff failed: {proc.stderr.strip()}>"]
    return [
        line
        for line in proc.stdout.splitlines()
        if line.strip() and not line.startswith("docs/")
    ]


def gh_api(repo: str, endpoint: str) -> dict:
    proc = subprocess.run(
        ["gh", "api", f"repos/{repo}/{endpoint}"],
        capture_output=True,
        text=True,
        check=False,
    )
    if proc.returncode != 0:
        raise RuntimeError(f"gh api repos/{repo}/{endpoint} failed: {proc.stderr.strip()}")
    return json.loads(proc.stdout)


def verify_ci_runs(
    run_ids: Sequence[int], audit_sha: str, repo: str
) -> List[str]:
    """Verify referenced runs against the GitHub API.

    Passes only when at least one referenced run is a completed, successful
    ``CI`` workflow run on the audited commit. This is the part the PR author
    cannot satisfy by writing prose: the run must exist in GitHub's records.
    """

    problems: List[str] = []
    qualified = False
    for run_id in run_ids:
        try:
            run = gh_api(repo, f"actions/runs/{run_id}")
        except (RuntimeError, json.JSONDecodeError) as exc:
            problems.append(f"cannot verify CI run {run_id}: {exc}")
            continue
        if run.get("head_sha") != audit_sha:
            problems.append(
                f"CI run {run_id} ran on {run.get('head_sha')}, not the audited "
                f"commit {audit_sha}"
            )
            continue
        if (
            run.get("path") == CI_WORKFLOW_PATH
            and run.get("status") == "completed"
            and run.get("conclusion") == "success"
        ):
            qualified = True
    if not qualified:
        problems.append(
            "no referenced Actions run is a completed, successful CI workflow "
            f"run ({CI_WORKFLOW_PATH}) on the audited commit"
        )
    return problems


# --- Gate orchestration ------------------------------------------------------


def fetch_labels(repo: str, pr_number: int) -> List[str]:
    proc = subprocess.run(
        [
            "gh", "pr", "view", str(pr_number), "--repo", repo,
            "--json", "labels", "--jq", ".labels[].name",
        ],
        capture_output=True,
        text=True,
        check=False,
    )
    if proc.returncode != 0:
        raise RuntimeError(f"gh pr view failed: {proc.stderr.strip()}")
    return [line.strip() for line in proc.stdout.splitlines() if line.strip()]


def fetch_changed_files(repo: str, pr_number: int) -> List[str]:
    proc = subprocess.run(
        ["gh", "pr", "diff", str(pr_number), "--repo", repo, "--name-only"],
        capture_output=True,
        text=True,
        check=False,
    )
    if proc.returncode != 0:
        raise RuntimeError(f"gh pr diff failed: {proc.stderr.strip()}")
    return [line.strip() for line in proc.stdout.splitlines() if line.strip()]


def _remediation(pr_number: int) -> str:
    return (
        "To satisfy the high-risk independent-evidence gate "
        "(docs/project/github-workflow.md):\n"
        f"  1. Ensure CI / final-status is green on the audited commit.\n"
        f"  2. Add {AUDIT_DIR}/pr-{pr_number}-<slug>.md using "
        f"{AUDIT_TEMPLATE} as the form.\n"
        "  3. Fill Auditor, Audit date, Head SHA (the commit the audit "
        "covers), CI run (a successful CI run on that commit), and the "
        "spec/ADR criteria checked.\n"
        "  4. If code changes after the audit, only docs-only commits may "
        "follow; otherwise re-audit against the new head."
    )


def run_gate(repo: str, pr_number: int, head_sha: str, root: Path) -> int:
    labels = fetch_labels(repo, pr_number)
    changed_files = fetch_changed_files(repo, pr_number)

    high_risk, reasons = classify_pr(labels, changed_files)
    if not high_risk:
        print(
            "low-risk PR: independent-evidence gate not required "
            f"(labels={labels!r}, no high-risk paths changed)"
        )
        return 0

    print(f"high-risk PR ({'; '.join(reasons)}): independent evidence required")
    audit_path, error = find_audit_file(root, pr_number)
    if error is not None:
        print(f"FAIL: {error}")
        print(_remediation(pr_number))
        return 1

    audit = parse_audit(audit_path)
    problems = list(audit.findings)

    if audit.head_sha is not None:
        if audit.head_sha == head_sha:
            pass  # Audit covers the exact PR head.
        elif is_ancestor(audit.head_sha, head_sha):
            stray = non_docs_files_after(audit.head_sha, head_sha)
            if stray:
                problems.append(
                    "changes after the audited Head SHA are not docs-only "
                    f"({', '.join(stray[:5])}); re-audit against the new head"
                )
        else:
            problems.append(
                f"audited Head SHA {audit.head_sha} is not part of this PR's history"
            )

    if audit.head_sha is not None and audit.ci_run_urls:
        run_ids = []
        for url in audit.ci_run_urls:
            run_id = parse_run_url(url, repo)
            if run_id is None:
                problems.append(f"CI run URL is not an Actions run of {repo}: {url}")
            else:
                run_ids.append(run_id)
        if run_ids:
            problems.extend(verify_ci_runs(run_ids, audit.head_sha, repo))

    if problems:
        print(f"FAIL: high-risk evidence gate ({audit_path.relative_to(root)}):")
        for problem in problems:
            print(f"  - {problem}")
        print(_remediation(pr_number))
        return 1

    print(
        f"PASS: audit {audit_path.relative_to(root)} covers "
        f"{audit.head_sha} with independent CI evidence "
        f"({', '.join(audit.criteria_refs)})"
    )
    return 0


def main(argv: Optional[Sequence[str]] = None) -> int:
    parser = argparse.ArgumentParser(
        description="Evaluate the NunuLauncher high-risk independent-evidence gate."
    )
    parser.add_argument("--repo", required=True, help="owner/name of the repository")
    parser.add_argument("--pr-number", required=True, type=int)
    parser.add_argument("--head-sha", required=True, help="current PR head commit")
    parser.add_argument(
        "--root",
        type=Path,
        default=None,
        help="checkout root (defaults to this script's git root)",
    )
    args = parser.parse_args(argv)

    root = args.root
    if root is None:
        here = Path(__file__).resolve().parent
        root = here
        for parent in [here, *here.parents]:
            if (parent / ".git").exists():
                root = parent
                break
    root = root.resolve()

    try:
        return run_gate(args.repo, args.pr_number, args.head_sha, root)
    except RuntimeError as exc:
        print(f"FAIL: gate could not evaluate the PR (fail closed): {exc}")
        return 1


if __name__ == "__main__":
    sys.exit(main())
