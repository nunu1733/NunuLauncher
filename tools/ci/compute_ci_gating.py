#!/usr/bin/env python3
"""Compute CI portfolio gating outputs for .github/workflows/ci.yml (Issue #422).

Reads the paths-filter results and the triggering event through environment
variables only. File lists arrive as JSON array strings (paths-filter
``list-files: json``), so filenames with spaces, quotes, or shell
metacharacters never pass through a shell. This script deliberately performs
no shell interpolation and no eval; it is the single place where the
per-path fail-closed rule is applied:

    unmapped_files = source_files - (union of surface-matched files)
    smoke          = workflow_dispatch && full-portfolio == false
    full           = !smoke && ( ci || unmapped exists || schedule
                                 || workflow_call || main push || dispatch-full )
    permanent_run  = source || ci || full || smoke
    instrumentation_enabled = !smoke

The mapping outputs of paths-filter are ignored for workflow_dispatch runs:
smoke forces the permanent gates on and every instrumentation lane off, and a
full dispatch run forces everything on, regardless of what paths-filter
guessed for a non-PR event.
"""

from __future__ import annotations

import json
import os
import sys

SURFACE_NAMES = (
    "surface_layout_write",
    "surface_db_schema",
    "surface_backup_restore",
    "surface_production_input",
    "surface_organizer_ui",
    "surface_jvm",
)


def parse_file_list(raw: str | None, env_name: str) -> list[str]:
    """Parse a JSON array of path strings; reject anything else loudly."""
    if raw is None or raw.strip() == "":
        return []
    try:
        data = json.loads(raw)
    except json.JSONDecodeError as exc:
        raise SystemExit(f"gating: {env_name} is not valid JSON: {exc}") from exc
    if not isinstance(data, list) or not all(
        isinstance(item, str) for item in data
    ):
        raise SystemExit(
            f"gating: {env_name} must be a JSON array of path strings"
        )
    return data


def flag(env: dict[str, str], name: str) -> bool:
    return env.get(name, "") == "true"


def compute(env: dict[str, str]) -> dict[str, object]:
    event = env.get("GATING_EVENT", "")
    ref = env.get("GATING_REF", "")
    full_portfolio = env.get("GATING_FULL_PORTFOLIO", "")
    workflow_call = flag(env, "GATING_WORKFLOW_CALL")

    source = flag(env, "GATING_SOURCE")
    ci_change = flag(env, "GATING_CI")

    source_files = parse_file_list(
        env.get("GATING_SOURCE_FILES"), "GATING_SOURCE_FILES"
    )
    surface_files: set[str] = set()
    for name in SURFACE_NAMES:
        env_name = "GATING_" + name.upper() + "_FILES"
        surface_files.update(parse_file_list(env.get(env_name), env_name))

    unmapped = [path for path in source_files if path not in surface_files]

    smoke = event == "workflow_dispatch" and full_portfolio == "false"
    dispatch_full = event == "workflow_dispatch" and full_portfolio != "false"
    full = not smoke and (
        ci_change
        or bool(unmapped)
        or event == "schedule"
        or workflow_call
        or (event == "push" and ref == "refs/heads/main")
        or dispatch_full
    )
    permanent_run = source or ci_change or full or smoke
    instrumentation_enabled = not smoke

    return {
        "smoke": smoke,
        "full": full,
        "permanent_run": permanent_run,
        "instrumentation_enabled": instrumentation_enabled,
        "unmapped_count": len(unmapped),
        "unmapped_sample": unmapped[:5],
        "source_file_count": len(source_files),
    }


def main() -> int:
    result = compute(os.environ)
    output_file = os.environ.get("GITHUB_OUTPUT")
    if output_file:
        with open(output_file, "a", encoding="utf-8") as handle:
            for key in ("smoke", "full", "permanent_run", "instrumentation_enabled"):
                handle.write(f"{key}={'true' if result[key] else 'false'}\n")
            handle.write(f"unmapped_count={result['unmapped_count']}\n")
    print(
        "gating: smoke={smoke} full={full} permanent_run={permanent_run} "
        "instrumentation_enabled={instrumentation_enabled} "
        "source_files={source_file_count} unmapped={unmapped_count}".format(
            **result
        )
    )
    if result["unmapped_sample"]:
        print(
            "gating: unmapped examples: "
            + ", ".join(repr(path) for path in result["unmapped_sample"])
        )
    return 0


if __name__ == "__main__":
    sys.exit(main())
