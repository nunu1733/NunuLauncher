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

    def test_japanese_translatable_contract_must_match_default(self) -> None:
        findings = validator.validate_pair(
            xml('<string name="old">old</string>'),
            xml('<string name="organizer_title">Title</string>'),
            xml('<string name="organizer_title" translatable="false">タイトル</string>'),
            "res/values/strings.xml",
            "res/values-ja/strings.xml",
        )
        self.assertTrue(any("translatable mismatch" in item for item in findings))

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

    # Issue #324: locale-aware contract comparison and coverage rules.

    def test_positional_reorder_is_accepted_when_every_placeholder_is_numbered(self) -> None:
        findings = validator.validate_pair(
            xml('<string name="old">old</string>'),
            xml('<string name="organizer_pair">%1$s of %2$d</string>'),
            xml('<string name="organizer_pair">%2$d の %1$s</string>'),
            "res/values/strings.xml",
            "res/values-ja/strings.xml",
        )
        self.assertEqual(findings, [])

    def test_implicit_order_mismatch_is_reported(self) -> None:
        findings = validator.validate_pair(
            xml('<string name="old">old</string>'),
            xml('<string name="organizer_pair">%s of %d</string>'),
            xml('<string name="organizer_pair">%d の %s</string>'),
            "res/values/strings.xml",
            "res/values-ja/strings.xml",
        )
        self.assertTrue(any("placeholder contract mismatch" in item for item in findings))

    def test_japanese_may_omit_unused_plural_categories_but_not_other(self) -> None:
        default = xml(
            '<plurals name="organizer_count">'
            '<item quantity="one">%1$d item</item>'
            '<item quantity="other">%1$d items</item>'
            "</plurals>"
        )
        ja_without_one = xml(
            '<plurals name="organizer_count"><item quantity="other">%1$d件</item></plurals>'
        )
        self.assertEqual(
            validator.validate_pair(
                xml('<string name="old">old</string>'),
                default,
                ja_without_one,
                "res/values/strings.xml",
                "res/values-ja/strings.xml",
            ),
            [],
        )
        ja_without_other = xml(
            '<plurals name="organizer_count"><item quantity="one">%1$d個</item></plurals>'
        )
        self.assertTrue(
            any(
                "placeholder contract mismatch" in item
                for item in validator.validate_pair(
                    xml('<string name="old">old</string>'),
                    default,
                    ja_without_other,
                    "res/values/strings.xml",
                    "res/values-ja/strings.xml",
                )
            )
        )

    def test_japanese_may_not_invent_plural_categories(self) -> None:
        findings = validator.validate_pair(
            xml('<string name="old">old</string>'),
            xml('<plurals name="organizer_count"><item quantity="other">%1$d</item></plurals>'),
            xml(
                '<plurals name="organizer_count">'
                '<item quantity="many">%1$d</item>'
                '<item quantity="other">%1$d</item>'
                "</plurals>"
            ),
            "res/values/strings.xml",
            "res/values-ja/strings.xml",
        )
        self.assertTrue(any("placeholder contract mismatch" in item for item in findings))

    def test_empty_upstream_resource_outside_nunu_prefixes_is_not_reported(self) -> None:
        findings = validator.validate_pair(
            xml('<string name="old">old</string>'),
            xml('<string name="work_profile_scheduler_intent" translatable="false"/>'),
            xml(),
            "res/values/strings.xml",
            "res/values-ja/strings.xml",
        )
        self.assertEqual(
            [item for item in findings if "empty resource" in item],
            [],
        )

    def test_exchange_and_homeedit_names_require_japanese(self) -> None:
        findings = validator.validate_pair(
            xml('<string name="old">old</string>'),
            xml(
                '<string name="exchange_entry_title">Exchange</string>'
                '<string name="homeedit_undo_label">Edit applied</string>'
            ),
            xml(),
            "lawnchair/res/values/strings.xml",
            "lawnchair/res/values-ja/strings.xml",
        )
        self.assertTrue(any("missing Japanese resource 'exchange_entry_title'" in item for item in findings))
        self.assertTrue(any("missing Japanese resource 'homeedit_undo_label'" in item for item in findings))

    def test_array_contract_is_compared_per_item_without_crashing(self) -> None:
        default = xml(
            '<string-array name="organizer_modes">'
            "<item>%1$d row</item>"
            "<item>Fill from the top-left</item>"
            "</string-array>"
        )
        ja_matching = xml(
            '<string-array name="organizer_modes">'
            "<item>%1$d行</item>"
            "<item>左上から詰めます</item>"
            "</string-array>"
        )
        self.assertEqual(
            validator.validate_pair(
                xml('<string name="old">old</string>'),
                default,
                ja_matching,
                "res/values/strings.xml",
                "res/values-ja/strings.xml",
            ),
            [],
        )
        ja_item_count_mismatch = xml(
            '<string-array name="organizer_modes"><item>%1$d行</item></string-array>'
        )
        self.assertTrue(
            any(
                "placeholder contract mismatch" in item
                for item in validator.validate_pair(
                    xml('<string name="old">old</string>'),
                    default,
                    ja_item_count_mismatch,
                    "res/values/strings.xml",
                    "res/values-ja/strings.xml",
                )
            )
        )


if __name__ == "__main__":
    unittest.main(verbosity=2)
