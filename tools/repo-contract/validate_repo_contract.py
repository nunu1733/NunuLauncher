#!/usr/bin/env python3
"""Repository contract validator for NunuLauncher.

Runs on Python 3.9+ with a standard-library fallback for local use. CI installs
PyYAML and performs the authoritative YAML parse. Exit code is non-zero when a
contract is violated so the surrounding CI job fails loudly.

Validated contracts:
  1. Markdown local links resolve to a tracked file in the repository.
  2. Issue form templates under ``.github/ISSUE_TEMPLATE`` parse as YAML.
  3. Required project files referenced by AGENTS.md exist.

Remote links (http/https) and mailto links are intentionally ignored: validating
them needs network access and would make the result non-deterministic.
"""

from __future__ import annotations

import argparse
import sys
import urllib.parse
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable, List, Sequence, Tuple

# Minimum stdlib YAML subset that is sufficient for GitHub Issue forms.
# GitHub renders these forms and a syntax error breaks issue intake, so we
# validate structure rather than schema depth here.
try:
    import yaml  # type: ignore
except ImportError:  # pragma: no cover - CI installs pyyaml, local fallback below
    yaml = None


REPO_ROOT_NAME = ".git"


@dataclass(frozen=True)
class Finding:
    """A single contract violation with enough context to act on."""

    path: Path
    line: int
    message: str

    def format(self, root: Path) -> str:
        rel = self.path.relative_to(root) if self.path.is_absolute() else self.path
        return f"{rel}:{self.line}: {self.message}"


# --- Markdown link validation -------------------------------------------------

# Capture [label](target). We deliberately avoid a full CommonMark parser:
# the repository's markdown is hand-written, and a focused regex keeps the
# validator dependency-free and deterministic.
_MD_LINK_RE = None  # populated lazily so the stdlib import stays at module top


def _compile_link_regex():
    import re

    # Match [text](target). `target` may contain a fragment (#anchor) or a
    # query (?raw=true) that we strip before resolving.
    return re.compile(r"\[(?P<label>[^\]]*)\]\((?P<target>[^)\s]+)(?:\s+\"[^\"]*\")?\)")


def _is_remote(target: str) -> bool:
    lowered = target.lower()
    return lowered.startswith(("http://", "https://", "mailto:", "ftp://", "tel:"))


def _is_absolute_path(target: str) -> bool:
    # Leading slash means "repository root" for GitHub rendered markdown.
    return target.startswith("/")


def _split_fragment(target: str) -> str:
    path_part, _, _frag = target.partition("#")
    return path_part


def _resolve_link(source: Path, root: Path, target: str) -> Path | None:
    """Return the file path a local link points at, or None if unresolvable."""

    # Drop any fragment (#anchor) or query string before resolving on disk.
    target_no_frag = _split_fragment(target)
    target_no_frag, _, _query = target_no_frag.partition("?")
    if not target_no_frag:
        # A pure fragment link such as [see below](#section) points inside the
        # current document; we do not validate headings, so treat as valid.
        return source

    if _is_absolute_path(target):
        candidate = (root / target.lstrip("/")).resolve()
    else:
        candidate = (source.parent / target_no_frag).resolve()

    # Resolve directory links (e.g. [adr](./docs/adr/)) to their index file,
    # matching how docs/README.md references folders. GitHub itself does not
    # render a directory link to a file, but this repository uses directory
    # links as section anchors, so we accept a directory that contains a
    # README.md as a valid target.
    if candidate.is_dir():
        index = candidate / "README.md"
        if index.exists():
            return index
        return None

    if candidate.exists():
        return candidate

    # GitHub renders `./file` and `file` identically; some links omit the .md
    # extension. Accept that convention for markdown sources.
    if not target_no_frag.endswith(".md") and source.suffix == ".md":
        with_md = candidate.with_name(candidate.name + ".md")
        if with_md.exists():
            return with_md

    return None


def validate_markdown_links(source: Path, root: Path) -> List[Finding]:
    """Return findings for local markdown links that do not resolve."""

    global _MD_LINK_RE
    if _MD_LINK_RE is None:
        _MD_LINK_RE = _compile_link_regex()

    findings: List[Finding] = []
    text = source.read_text(encoding="utf-8")

    for line_no, line in enumerate(text.splitlines(), start=1):
        for match in _MD_LINK_RE.finditer(line):
            target = match.group("target")
            if _is_remote(target):
                continue
            if target.startswith("data:"):
                continue
            if _resolve_link(source, root, target) is None:
                findings.append(
                    Finding(
                        path=source,
                        line=line_no,
                        message=f"broken local link -> {target!r}",
                    )
                )
    return findings


# --- Issue form YAML validation ----------------------------------------------


def _iter_issue_forms(root: Path) -> Iterable[Path]:
    template_dir = root / ".github" / "ISSUE_TEMPLATE"
    if not template_dir.is_dir():
        return []
    # config.yml is GitHub's template-chooser configuration, not an Issue form:
    # it carries keys like blank_issues_enabled and contact_links, and never has
    # the name/body fields a real form requires.
    return sorted(
        p
        for p in template_dir.iterdir()
        if p.suffix in (".yml", ".yaml")
        and p.is_file()
        and p.name != "config.yml"
    )


def validate_issue_forms(root: Path) -> List[Finding]:
    """Return findings for Issue form templates that are not valid YAML."""

    findings: List[Finding] = []
    for form in _iter_issue_forms(root):
        if yaml is None:
            # Without PyYAML we fall back to a structural smoke check: a
            # non-empty document with the required top-level keys. This keeps
            # local runs useful; CI installs PyYAML for full parsing.
            findings.extend(_yaml_smoke_check(form))
            continue

        text = form.read_text(encoding="utf-8")
        try:
            parsed = yaml.safe_load(text)
        except yaml.YAMLError as exc:
            # Mark on line 1; yaml errors are document-level and a precise
            # line is not always available without parsing internals.
            findings.append(
                Finding(
                    path=form,
                    line=1,
                    message=f"invalid Issue form YAML: {exc}",
                )
            )
            continue

        if not isinstance(parsed, dict):
            findings.append(
                Finding(
                    path=form,
                    line=1,
                    message="Issue form YAML must be a mapping at the top level",
                )
            )
            continue

        for required in ("name", "body"):
            if required not in parsed:
                findings.append(
                    Finding(
                        path=form,
                        line=1,
                        message=f"Issue form missing required field {required!r}",
                    )
                )
    return findings


_YAML_REQUIRED_KEYS = ("name", "body")


def _yaml_smoke_check(form: Path) -> List[Finding]:
    """Best-effort YAML check used when PyYAML is unavailable locally.

    It only catches gross structural problems (empty file, non-mapping,
    missing required keys). CI always has PyYAML and does the real parse.
    """

    findings: List[Finding] = []
    text = form.read_text(encoding="utf-8").strip()
    if not text:
        findings.append(Finding(form, 1, "Issue form YAML is empty"))
        return findings

    top_keys: List[str] = []
    for line in text.splitlines():
        stripped = line.lstrip()
        if not stripped or stripped.startswith("#"):
            continue
        if not line.startswith(" "):  # top-level key
            # A YAML mapping key must be followed by ":" plus whitespace or
            # end-of-line. A bare word like a typo'd "body" (no colon) is not a
            # valid key and must not satisfy the required-field check.
            key, sep, rest = stripped.partition(":")
            if not sep:
                findings.append(
                    Finding(
                        form,
                        1,
                        "Issue form has a top-level line that is not a YAML "
                        "mapping key (missing ':') (smoke check)",
                    )
                )
                continue
            if rest and not rest.startswith((" ", "\t")):
                # "key:value" with no space is also structurally suspect for
                # these hand-written forms; flag it so the smoke check stays
                # meaningful without PyYAML.
                findings.append(
                    Finding(
                        form,
                        1,
                        f"Issue form top-level key {key.strip()!r} is not "
                        "followed by a space after ':' (smoke check)",
                    )
                )
                continue
            top_keys.append(key.strip())

    for required in _YAML_REQUIRED_KEYS:
        if required not in top_keys:
            findings.append(
                Finding(
                    form,
                    1,
                    f"Issue form missing required field {required!r} (smoke check)",
                )
            )
    return findings


# --- Required project files ---------------------------------------------------

# Files that AGENTS.md and docs/README.md treat as load-bearing for the
# development workflow. Losing any of these silently breaks the intake path.
REQUIRED_PROJECT_FILES: Tuple[str, ...] = (
    "AGENTS.md",
    "CONTEXT.md",
    "DESIGN.md",
    "README.md",
    "CONTRIBUTING.md",
    "SECURITY.md",
    "CODE_OF_CONDUCT.md",
    "ROADMAP.md",
    "docs/README.md",
    "docs/product/product-brief.md",
    "docs/product/requirements.md",
    "docs/project/github-workflow.md",
    "docs/project/seed-backlog.md",
    "docs/engineering/building.md",
    "docs/engineering/quality-strategy.md",
    "docs/engineering/upstream-strategy.md",
    "specs/README.md",
    ".github/pull_request_template.md",
    ".github/ISSUE_TEMPLATE/config.yml",
)


def validate_required_files(root: Path) -> List[Finding]:
    findings: List[Finding] = []
    for rel in REQUIRED_PROJECT_FILES:
        if not (root / rel).is_file():
            findings.append(
                Finding(
                    path=root / rel,
                    line=1,
                    message=f"required project file missing: {rel}",
                )
            )
    return findings


# --- Orchestration -----------------------------------------------------------


def _iter_markdown_files(root: Path) -> Iterable[Path]:
    # Validate hand-authored docs only. Skip vendored/generated trees, the
    # gradle build output, and any checkout metadata.
    #
    # ``wmshell`` and ``quickstep`` carry AOSP source with its own internal
    # docs whose links are root-relative to the AOSP monorepo (e.g.
    # ``/libs/WindowManager/Shell``). They are upstream-owned and out of scope
    # for this fork's documentation contract; touching them would conflict
    # with the "minimize the Launcher3/AOSP patch surface" rule in AGENTS.md.
    skip_dirs = {
        ".git",
        ".gradle",
        ".idea",
        "build",
        "node_modules",
        "specs/_template",
        "wmshell",
    }
    skip_path_prefixes = (
        "quickstep/src",
        # The validator's own fixtures intentionally contain broken links and
        # malformed forms; they are exercised by the self-tests instead.
        "tools/repo-contract/fixtures",
    )
    for path in sorted(root.rglob("*.md")):
        if any(part in skip_dirs for part in path.parts):
            continue
        rel = path.relative_to(root).as_posix()
        if any(rel.startswith(prefix) for prefix in skip_path_prefixes):
            continue
        yield path


def run(root: Path) -> List[Finding]:
    """Run every contract check against ``root`` and return all findings."""

    findings: List[Finding] = []
    for md in _iter_markdown_files(root):
        findings.extend(validate_markdown_links(md, root))
    findings.extend(validate_issue_forms(root))
    findings.extend(validate_required_files(root))
    return findings


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Validate NunuLauncher repository contracts."
    )
    parser.add_argument(
        "--root",
        type=Path,
        default=None,
        help="Repository root to validate (defaults to this script's git root).",
    )
    args = parser.parse_args(argv)

    root = args.root
    if root is None:
        here = Path(__file__).resolve().parent
        root = here
        for parent in [here, *here.parents]:
            if (parent / REPO_ROOT_NAME).exists():
                root = parent
                break
    root = root.resolve()

    findings = run(root)
    if not findings:
        print(f"repository contract OK ({root})")
        return 0

    print(f"repository contract FAILED: {len(findings)} finding(s) in {root}")
    for finding in findings:
        print("  " + finding.format(root))
    return 1


if __name__ == "__main__":
    sys.exit(main())
