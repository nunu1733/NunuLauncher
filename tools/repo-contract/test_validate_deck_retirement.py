#!/usr/bin/env python3
"""
Self-contained repo-contract validation for Deck runtime retirement (ADR-0006).

Validates that production sources no longer reference the retired app.lawnchair.deck
package, and that the deck-output-compatibility fixture is retained in the test corpus.
"""

import unittest
import sys
import re
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]

PRODUCTION_DIRS = [
    REPO_ROOT / "lawnchair" / "src",
    REPO_ROOT / "src",
]

# Files that are allowed to mention deck-related references (e.g. retirement
# documentation comments).
ALLOWED_DECK_FILES = {
    REPO_ROOT / "lawnchair" / "src" / "app" / "lawnchair" / "migration" / "DeckRetirementMigration.kt",
}

DECK_STRING_KEYS = [
    "show_deck_layout",
    "show_deck_layout_description",
    "home_lawn_deck_label",
    "home_lawn_deck_label_beta",
    "home_lawn_deck_description",
]


class DeckRetirementContractTestCase(unittest.TestCase):
    """Validates the Deck retirement repo contract."""

    # -- helpers -----------------------------------------------------------

    @staticmethod
    def _production_files():
        for d in PRODUCTION_DIRS:
            for ext in ("*.kt", "*.java"):
                yield from d.rglob(ext)

    @staticmethod
    def _production_files_filtered():
        allowed = ALLOWED_DECK_FILES
        for f in DeckRetirementContractTestCase._production_files():
            if f.resolve() not in allowed:
                yield f

    # -- tests -------------------------------------------------------------

    def test_no_deck_imports_in_production_sources(self):
        """No production file (outside allowed list) contains 'import app.lawnchair.deck'."""
        violators = []
        for f in self._production_files_filtered():
            try:
                text = f.read_text(encoding="utf-8", errors="replace")
                if "import app.lawnchair.deck" in text:
                    violators.append(str(f))
            except Exception:
                pass
        self.assertEqual(
            [],
            violators,
            f"Found 'import app.lawnchair.deck' in {len(violators)} file(s)",
        )

    def test_no_deck_runtime_class_references_in_production(self):
        """No production file references LawndeckManager or AddFoldersWithItemsTask."""
        violators = []
        for f in self._production_files_filtered():
            try:
                text = f.read_text(encoding="utf-8", errors="replace")
                for line in text.splitlines():
                    stripped = line.strip()
                    if stripped.startswith("//") or stripped.startswith("*") or stripped.startswith("/*"):
                        continue
                    if "LawndeckManager" in line or "AddFoldersWithItemsTask" in line:
                        violators.append(f"{f}: {line.strip()}")
            except Exception:
                pass
        self.assertEqual(
            [],
            violators,
            f"Found LawndeckManager/AddFoldersWithItemsTask references in {len(violators)} location(s)",
        )

    def test_deck_strings_absent_from_all_locales(self):
        """All 5 deck-related string keys are absent from every values*/strings.xml."""
        strings_files = list(REPO_ROOT.glob("lawnchair/res/values*/strings.xml"))
        self.assertGreater(len(strings_files), 0, "No strings.xml files found to check")
        found = {}
        for sf in strings_files:
            text = sf.read_text(encoding="utf-8", errors="replace")
            for key in DECK_STRING_KEYS:
                pattern = re.compile(rf'<string\s+name="{re.escape(key)}">')
                if pattern.search(text):
                    found.setdefault(key, []).append(str(sf.relative_to(REPO_ROOT)))
        self.assertEqual(
            {},
            found,
            f"Deck string keys still present in {len(found)} key(s). "
            f"Files checked: {len(strings_files)}",
        )

    def test_deck_file_restore_absent_from_owner_kind(self):
        """DECK_FILE_RESTORE must not appear in OwnerKind enum or isRestoreFamily()."""
        coordinator_path = (
            REPO_ROOT / "src" / "com" / "android" / "launcher3" / "model" / "LayoutWriteCoordinator.java"
        )
        self.assertTrue(coordinator_path.exists(), f"Missing {coordinator_path}")
        text = coordinator_path.read_text(encoding="utf-8", errors="replace")

        # Check OwnerKind enum
        enum_match = re.search(r"enum\s+OwnerKind\s*\{([^}]+)\}", text, re.DOTALL)
        self.assertIsNotNone(enum_match, "Could not locate OwnerKind enum")
        enum_body = enum_match.group(1)
        self.assertNotIn(
            "DECK_FILE_RESTORE",
            enum_body,
            "DECK_FILE_RESTORE found in OwnerKind enum",
        )

        # Check isRestoreFamily() body
        restore_family_match = re.search(
            r"public\s+static\s+boolean\s+isRestoreFamily\s*\([^)]+\)\s*\{([^}]+)\}",
            text,
            re.DOTALL,
        )
        self.assertIsNotNone(restore_family_match, "Could not locate isRestoreFamily() body")
        restore_body = restore_family_match.group(1)
        self.assertNotIn(
            "DECK_FILE_RESTORE",
            restore_body,
            "DECK_FILE_RESTORE found in isRestoreFamily() body",
        )

        # Check WriterKind enum in Ports.kt
        ports_path = (
            REPO_ROOT / "lawnchair" / "src" / "app" / "lawnchair" / "organizer" / "application" / "protocol" / "Ports.kt"
        )
        self.assertTrue(ports_path.exists(), f"Missing {ports_path}")
        ports_text = ports_path.read_text(encoding="utf-8", errors="replace")
        writer_kind_match = re.search(
            r"enum\s+class\s+WriterKind\s*\{([^}]+)\}",
            ports_text,
            re.DOTALL,
        )
        self.assertIsNotNone(writer_kind_match, "Could not locate WriterKind enum in Ports.kt")
        writer_kind_body = writer_kind_match.group(1)
        self.assertNotIn(
            "DECK_FILE_RESTORE",
            writer_kind_body,
            "DECK_FILE_RESTORE found in WriterKind enum in Ports.kt",
        )

    def test_deck_output_compatibility_fixture_retained(self):
        """deck-output-compatibility fixture is retained in ExampleCorpus."""
        corpus_path = (
            REPO_ROOT
            / "tests"
            / "unit"
            / "app"
            / "lawnchair"
            / "organizer"
            / "planning"
            / "harness"
            / "ExampleCorpus.kt"
        )
        self.assertTrue(corpus_path.exists(), f"Missing {corpus_path}")
        text = corpus_path.read_text(encoding="utf-8", errors="replace")

        # Check fixture ID string appears
        self.assertIn(
            'FixtureId("deck-output-compatibility")',
            text,
            "FixtureId('deck-output-compatibility') not found in ExampleCorpus.kt",
        )

        # Check the fixture is mentioned in allExamples map
        self.assertIn(
            "deckOutputCompatibility",
            text,
            "deckOutputCompatibility property not found in ExampleCorpus.kt",
        )

        # Verify allExamples map contains the key
        # Look for the associateBy block that builds the map
        self.assertIn(
            "deckOutputCompatibility",
            text,
            "deckOutputCompatibility is not included in allExamples",
        )

    def test_no_deck_type_references_in_production(self):
        """No production file uses 'app.lawnchair.deck.' as a package prefix."""
        violators = []
        for f in self._production_files_filtered():
            try:
                text = f.read_text(encoding="utf-8", errors="replace")
                if re.search(r'(?<![a-zA-Z0-9_])app\.lawnchair\.deck\.', text):
                    violators.append(str(f))
            except Exception:
                pass
        self.assertEqual(
            [],
            violators,
            f"Found 'app.lawnchair.deck.' references in {len(violators)} file(s)",
        )


if __name__ == "__main__":
    unittest.main(verbosity=2)