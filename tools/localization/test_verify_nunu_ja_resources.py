#!/usr/bin/env python3
"""Self-tests for the Issue #161 Japanese resource validator."""

from __future__ import annotations

import unittest

import verify_nunu_ja_resources as validator


def xml(*resources: str) -> str:
    return "<resources>\n" + "\n".join(resources) + "\n</resources>"


class ResourceValidatorTests(unittest.TestCase):
    def test_real_repository_passes(self) -> None:
        from pathlib import Path

        root = Path(__file__).resolve().parents[2]
        self.assertEqual(
            validator.run(root, "505dbc40e6154c05158b5d0271c45f6a885a411b"),
            [],
        )

    def test_missing_japanese_name_is_reported(self) -> None:
        findings = validator.validate_pair(
            xml('<string name="old">old</string>'),
            xml('<string name="organizer_title">Title</string>'),
            xml(),
            "res/values/strings.xml",
            "res/values-ja/strings.xml",
        )
        self.assertTrue(any("missing Japanese resource 'organizer_title'" in item for item in findings))

    def test_placeholder_type_and_order_are_reported(self) -> None:
        findings = validator.validate_pair(
            xml('<string name="old">old</string>'),
            xml('<string name="manual_organization_scope">%1$d of %2$s</string>'),
            xml('<string name="manual_organization_scope">%1$s の %2$d</string>'),
            "res/values/strings.xml",
            "res/values-ja/strings.xml",
        )
        self.assertTrue(any("placeholder contract mismatch" in item for item in findings))

    def test_format_flags_dates_and_escaped_percent_are_not_collapsed(self) -> None:
        findings = validator.validate_pair(
            xml('<string name="old">old</string>'),
            xml('<string name="manual_organization_format">%1$02d %1$tY %%</string>'),
            xml('<string name="manual_organization_format">%1$02s %1$tH %%</string>'),
            "res/values/strings.xml",
            "res/values-ja/strings.xml",
        )
        self.assertTrue(any("placeholder contract mismatch" in item for item in findings))

        escaped_percent = validator.validate_pair(
            xml('<string name="old">old</string>'),
            xml('<string name="manual_organization_percent">%%s</string>'),
            xml('<string name="manual_organization_percent">%s</string>'),
            "res/values/strings.xml",
            "res/values-ja/strings.xml",
        )
        self.assertTrue(any("placeholder contract mismatch" in item for item in escaped_percent))

    def test_plural_quantities_and_placeholders_are_checked(self) -> None:
        findings = validator.validate_pair(
            xml('<plurals name="old"><item quantity="one">%1$d</item></plurals>'),
            xml('<plurals name="organizer_count"><item quantity="one">%1$d item</item><item quantity="other">%1$d items</item></plurals>'),
            xml('<plurals name="organizer_count"><item quantity="one">%1$s件</item><item quantity="many">%1$d件</item></plurals>'),
            "res/values/strings.xml",
            "res/values-ja/strings.xml",
        )
        self.assertTrue(any("placeholder contract mismatch" in item for item in findings))

    def test_non_translatable_resource_is_excluded(self) -> None:
        findings = validator.validate_pair(
            xml('<string name="old">old</string>'),
            xml('<string name="organizer_filename" translatable="false">file.jsonl</string>'),
            xml(),
            "res/values/strings.xml",
            "res/values-ja/strings.xml",
        )
        self.assertEqual(findings, [])

    def test_duplicate_and_empty_resources_are_reported(self) -> None:
        _, findings = validator.parse_resources(
            xml(
                '<string name="organizer_empty"></string>',
                '<string name="organizer_duplicate">one</string>',
                '<string name="organizer_duplicate">two</string>',
            ),
            "res/values/strings.xml",
        )
        self.assertTrue(any("empty resource" in item for item in findings))
        self.assertTrue(any("duplicate resource" in item for item in findings))

    def test_empty_styled_string_and_plural_item_are_reported(self) -> None:
        _, findings = validator.parse_resources(
            xml(
                '<string name="organizer_styled"><b></b></string>',
                '<plurals name="organizer_plural"><item quantity="one"></item><item quantity="other">%1$d</item></plurals>',
            ),
            "res/values/strings.xml",
        )
        self.assertTrue(any("empty resource 'organizer_styled'" in item for item in findings))
        self.assertTrue(any("empty plurals item 'organizer_plural'" in item for item in findings))


if __name__ == "__main__":
    unittest.main(verbosity=2)
