#!/usr/bin/env python3
"""Verify Nunu organizer Japanese resource coverage and format contracts.

The checker is intentionally standard-library-only and has no runtime or
Gradle dependency. It reconstructs the Nunu resource set from the accepted
baseline and the current default resources, then checks the corresponding
``values-ja`` files. Resources marked ``translatable="false"`` are reported as
non-translatable and excluded from the required Japanese set. Required
resources must preserve the source ``translatable`` contract in Japanese.
"""

from __future__ import annotations

import argparse
import re
import subprocess
import sys
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable, Sequence

RESOURCE_PAIRS = (
    ("lawnchair/res/values/strings.xml", "lawnchair/res/values-ja/strings.xml"),
    ("res/values/strings.xml", "res/values-ja/strings.xml"),
)
RESOURCE_TAGS = {"string", "plurals", "string-array", "array", "integer-array"}
NUNU_PREFIXES = ("organizer_", "manual_organization_", "organization_onboarding_")
FORMAT_RE = re.compile(
    r"%(?:(?P<position>\d+)\$)?"
    r"(?P<flags>[-#+ 0,(<]*)"
    r"(?P<width>\d*)"
    r"(?:\.(?P<precision>\d+))?"
    r"(?P<date>[tT])?"
    r"(?P<conversion>[a-zA-Z])"
)


@dataclass(frozen=True)
class ResourceEntry:
    name: str
    kind: str
    value: object
    translatable: bool


def _text(node: ET.Element) -> str:
    return "".join(node.itertext())


def _format_contract(
    value: str, prefix: str = ""
) -> tuple[tuple[tuple[str, ...], ...], tuple[str, ...]]:
    """Return ordered format tokens and unsupported-format findings.

    ``%%`` is consumed as one literal-percent token and cannot accidentally be
    read as the ``%s`` that follows it. Width, flags, precision, and date
    conversions are retained because changing them can alter rendering or the
    argument type. Unknown/incomplete percent sequences are explicit findings,
    rather than being silently treated as ordinary text.
    """

    tokens: list[tuple[str, ...]] = []
    errors: list[str] = []
    index = 0
    while index < len(value):
        if value[index] != "%":
            index += 1
            continue
        if index + 1 < len(value) and value[index + 1] == "%":
            index += 2
            continue
        match = FORMAT_RE.match(value, index)
        if match is None:
            errors.append(f"unsupported format sequence at character {index}")
            index += 1
            continue
        tokens.append(
            (
                prefix,
                match.group("position") or "",
                match.group("flags") or "",
                match.group("width") or "",
                match.group("precision") or "",
                match.group("date") or "",
                match.group("conversion") or "",
            )
        )
        index = match.end()
    return tuple(tokens), tuple(errors)


def _placeholder_contract(value: str, prefix: str = "") -> tuple[tuple[str, ...], ...]:
    """Return the ordered Android/Java format contract for a value."""

    return _format_contract(value, prefix)[0]


def _entry_value(node: ET.Element) -> object:
    if node.tag == "string":
        return _placeholder_contract(_text(node))
    if node.tag == "plurals":
        return tuple(
            (item.get("quantity", ""), _placeholder_contract(_text(item), item.get("quantity", "")))
            for item in node
            if item.tag == "item"
        )
    return tuple(_placeholder_contract(_text(item), str(index)) for index, item in enumerate(node))


def _entry_format_errors(node: ET.Element) -> Iterable[str]:
    if node.tag == "string":
        _, errors = _format_contract(_text(node))
        yield from errors
        return
    if node.tag == "plurals":
        for item in node:
            if item.tag == "item":
                _, errors = _format_contract(_text(item), item.get("quantity", ""))
                yield from errors
        return
    for index, item in enumerate(node):
        _, errors = _format_contract(_text(item), str(index))
        yield from errors


def parse_resources(text: str, source: str) -> tuple[dict[str, ResourceEntry], list[str]]:
    """Parse supported Android resource nodes and return entries/findings."""

    findings: list[str] = []
    try:
        root = ET.fromstring(text)
    except ET.ParseError as exc:
        return {}, [f"{source}: invalid XML: {exc}"]

    entries: dict[str, ResourceEntry] = {}
    for node in root:
        if node.tag not in RESOURCE_TAGS:
            continue
        name = node.get("name")
        if not name:
            findings.append(f"{source}: {node.tag} has no name")
            continue
        if name in entries:
            findings.append(f"{source}: duplicate resource name {name!r}")
            continue
        text_value = _text(node)
        if node.tag == "string" and not text_value.strip():
            findings.append(f"{source}: empty resource {name!r}")
        if node.tag == "plurals" and not any(child.tag == "item" for child in node):
            findings.append(f"{source}: plural {name!r} has no item")
        for index, child in enumerate(node):
            if child.tag == "item" and not _text(child).strip():
                quantity = child.get("quantity", str(index))
                findings.append(
                    f"{source}: empty {node.tag} item {name!r}[{quantity!r}]"
                )
        for error in _entry_format_errors(node):
            findings.append(f"{source}: {name!r}: {error}")
        entries[name] = ResourceEntry(
            name=name,
            kind=node.tag,
            value=_entry_value(node),
            translatable=node.get("translatable") != "false",
        )
    return entries, findings


def is_nunu_resource(name: str) -> bool:
    return name.startswith(NUNU_PREFIXES)


def required_names(
    baseline: dict[str, ResourceEntry], current: dict[str, ResourceEntry]
) -> list[str]:
    """Return current Nunu additions that require a Japanese resource."""

    return sorted(
        name
        for name, entry in current.items()
        if name not in baseline and is_nunu_resource(name) and entry.translatable
    )


def validate_pair(
    baseline_text: str,
    default_text: str,
    japanese_text: str,
    default_source: str,
    japanese_source: str,
) -> list[str]:
    """Validate one baseline/default/Japanese resource pair."""

    baseline, findings = parse_resources(baseline_text, f"baseline:{default_source}")
    default, default_findings = parse_resources(default_text, default_source)
    japanese, japanese_findings = parse_resources(japanese_text, japanese_source)
    findings.extend(default_findings)
    findings.extend(japanese_findings)

    for name in required_names(baseline, default):
        source_entry = default[name]
        translated = japanese.get(name)
        if translated is None:
            findings.append(f"{japanese_source}: missing Japanese resource {name!r}")
            continue
        if source_entry.kind != translated.kind:
            findings.append(
                f"{japanese_source}: {name!r} kind mismatch: "
                f"default={source_entry.kind}, ja={translated.kind}"
            )
        if source_entry.translatable != translated.translatable:
            findings.append(
                f"{japanese_source}: {name!r} translatable mismatch: "
                f"default={source_entry.translatable}, ja={translated.translatable}"
            )
        if source_entry.value != translated.value:
            findings.append(
                f"{japanese_source}: {name!r} placeholder contract mismatch: "
                f"default={source_entry.value!r}, ja={translated.value!r}"
            )
    return findings


def _git_show(root: Path, revision: str, path: str) -> str:
    result = subprocess.run(
        ["git", "show", f"{revision}:{path}"],
        cwd=root,
        check=False,
        capture_output=True,
        text=True,
    )
    if result.returncode != 0:
        raise RuntimeError(result.stderr.strip() or f"unable to read {revision}:{path}")
    return result.stdout


def run(root: Path, baseline_revision: str) -> list[str]:
    findings: list[str] = []
    for default_rel, japanese_rel in RESOURCE_PAIRS:
        default_path = root / default_rel
        japanese_path = root / japanese_rel
        if not default_path.is_file():
            findings.append(f"{default_rel}: default resource file is missing")
            continue
        if not japanese_path.is_file():
            findings.append(f"{japanese_rel}: Japanese resource file is missing")
            continue
        try:
            baseline_text = _git_show(root, baseline_revision, default_rel)
        except RuntimeError as exc:
            findings.append(str(exc))
            continue
        findings.extend(
            validate_pair(
                baseline_text,
                default_path.read_text(encoding="utf-8"),
                japanese_path.read_text(encoding="utf-8"),
                default_rel,
                japanese_rel,
            )
        )
    return findings


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--root",
        type=Path,
        default=Path(__file__).resolve().parents[2],
        help="repository root (default: inferred from this file)",
    )
    parser.add_argument(
        "--baseline",
        required=True,
        help="accepted baseline commit used to identify Nunu additions",
    )
    args = parser.parse_args(argv)
    findings = run(args.root.resolve(), args.baseline)
    if findings:
        print(f"FAIL: {len(findings)} Nunu Japanese resource finding(s)")
        for finding in findings:
            print(f"- {finding}")
        return 1
    print("PASS: Nunu Japanese resource names and placeholder contracts match.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
