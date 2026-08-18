package app.lawnchair.organizer.planning.harness

import app.lawnchair.organizer.planning.CandidateKind
import app.lawnchair.organizer.planning.CandidateTarget
import app.lawnchair.organizer.planning.CapturedPlacement
import app.lawnchair.organizer.planning.ItemKind
import app.lawnchair.organizer.planning.Orientation
import app.lawnchair.organizer.planning.PreserveReason
import app.lawnchair.organizer.planning.RunMode
import app.lawnchair.organizer.planning.TargetKey
import app.lawnchair.organizer.planning.WarningCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SyntheticFixtureGeneratorTest {

    @Test
    fun generateProducesExpectedCount() {
        val fixtures = SyntheticFixtureGenerator.generate(seed = 0x4E554E55L, count = 64)
        assertEquals(64, fixtures.size)
    }

    @Test
    fun generateRejectsNonPositiveCount() {
        assertThrows(IllegalArgumentException::class.java) {
            SyntheticFixtureGenerator.generate(seed = 0x4E554E55L, count = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            SyntheticFixtureGenerator.generate(seed = 0x4E554E55L, count = -1)
        }
    }

    @Test
    fun sameSeedProducesIdenticalResults() {
        val a = SyntheticFixtureGenerator.generate(seed = 0x4E554E55L, count = 10)
        val b = SyntheticFixtureGenerator.generate(seed = 0x4E554E55L, count = 10)
        assertEquals(a, b)
    }

    @Test
    fun differentSeedsProduceDifferentResults() {
        val a = SyntheticFixtureGenerator.generate(seed = 0x4E554E55L, count = 10)
        val b = SyntheticFixtureGenerator.generate(seed = 0x12345678L, count = 10)
        assertNotEquals(a, b)
    }

    @Test
    fun generationIsPrefixStable() {
        val full = SyntheticFixtureGenerator.generate(seed = 0x4E554E55L, count = 64)
        val partial = SyntheticFixtureGenerator.generate(seed = 0x4E554E55L, count = 10)
        assertEquals(full.take(10), partial)
        assertNotEquals(full.first().reproduction, partial.first().reproduction)
    }

    @Test
    fun fixtureEqualitySeparatesReproductionCoordinatesFromCorpusCount() {
        val fixture = SyntheticFixtureGenerator.generate(seed = 42, count = 10).first()
        val differentCount = fixture.copy(reproduction = Reproduction(seed = 42, caseIndex = 0, corpusCount = 64))
        val differentCase = fixture.copy(reproduction = Reproduction(seed = 42, caseIndex = 1, corpusCount = 64))

        assertEquals(fixture, differentCount)
        assertNotEquals(fixture, differentCase)
    }

    @Test
    fun eachFixtureHasUniqueId() {
        val fixtures = SyntheticFixtureGenerator.generate(seed = 0x4E554E55L, count = 64)
        val ids = fixtures.map { it.id.value }
        assertEquals(ids.size, ids.distinct().size)
    }

    @Test
    fun fixtureIdsIncludeIndex() {
        val fixtures = SyntheticFixtureGenerator.generate(seed = 0x4E554E55L, count = 5)
        for (i in fixtures.indices) {
            assertTrue(fixtures[i].id.value.contains("$i"))
        }
    }

    @Test
    fun revisionsIncludeIndex() {
        val fixtures = SyntheticFixtureGenerator.generate(seed = 0x4E554E55L, count = 5)
        for (i in fixtures.indices) {
            assertTrue(fixtures[i].input.snapshot.revision.value.contains("$i"))
        }
    }

    @Test
    fun fixtureHasReproduction() {
        val seed = -987654321L
        val fixtures = SyntheticFixtureGenerator.generate(seed = seed, count = 5)
        for (i in fixtures.indices) {
            val r = fixtures[i].reproduction
            assertNotNull(r)
            assertEquals(seed, r!!.seed)
            assertEquals(i, r.caseIndex)
        }
    }

    @Test
    fun fixturesSpanEveryOrientation() {
        val fixtures = SyntheticFixtureGenerator.generate(seed = 0x4E554E55L, count = 64)
        val orientations = fixtures.map { it.input.snapshot.device.orientation }.distinct()
        assertEquals(Orientation.entries.toSet(), orientations.toSet())
    }

    @Test
    fun fixturesCoverMultipleModes() {
        val fixtures = SyntheticFixtureGenerator.generate(seed = 0x4E554E55L, count = 64)
        val modes = fixtures.map { it.input.runMode }.distinct()
        assertEquals(setOf(RunMode.FullOrganization, RunMode.IncrementalPlacement), modes.toSet())
    }

    @Test
    fun fixturesHavePositiveDimensions() {
        val fixtures = SyntheticFixtureGenerator.generate(seed = 0x4E554E55L, count = 64)
        for (f in fixtures) {
            val d = f.input.snapshot.device
            assertTrue(d.columns >= 3)
            assertTrue(d.rows >= 3)
            assertTrue(d.hotseatSlots >= 3)
            assertTrue(d.columns <= 8)
            assertTrue(d.rows <= 8)
        }
    }

    @Test
    fun selectCaseReturnsCorrectFixture() {
        val fixtures = SyntheticFixtureGenerator.generate(seed = 0x4E554E55L, count = 64)
        val selected = SyntheticFixtureGenerator.selectCase(fixtures, 0x4E554E55L, 5)
        assertEquals(fixtures[5], selected)
        assertEquals(5, selected.reproduction!!.caseIndex)
    }

    @Test
    fun selectCaseRejectsOutOfBounds() {
        val fixtures = SyntheticFixtureGenerator.generate(seed = 0x4E554E55L, count = 10)
        assertThrows(IllegalArgumentException::class.java) {
            SyntheticFixtureGenerator.selectCase(fixtures, 0x4E554E55L, 10)
        }
        assertThrows(IllegalArgumentException::class.java) {
            SyntheticFixtureGenerator.selectCase(fixtures, 0x4E554E55L, -1)
        }
    }

    @Test
    fun selectCaseRejectsWrongSeed() {
        val fixtures = SyntheticFixtureGenerator.generate(seed = 0x4E554E55L, count = 10)
        assertThrows(IllegalArgumentException::class.java) {
            SyntheticFixtureGenerator.selectCase(fixtures, 0x12345678L, 0)
        }
    }

    @Test
    fun reproductionStringFormat() {
        val r = Reproduction(seed = 0x4E554E55L, caseIndex = 0)
        val text = r.toString()
        assertEquals(
            "./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests '*PlannerGeneratedPropertyTest*' " +
                "-Dplanner.seed=1314213461 -Dplanner.case=0",
            text,
        )
    }

    @Test
    fun nonDefaultReproductionIncludesCorpusCount() {
        val r = Reproduction(seed = 42, caseIndex = 511, corpusCount = 512)
        assertEquals(
            "./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests '*PlannerGeneratedPropertyTest*' " +
                "-Dplanner.seed=42 -Dplanner.case=511 -Dplanner.count=512",
            r.toString(),
        )
    }

    @Test
    fun extendedCorpusSelectsHighCaseWithMatchingReproduction() {
        val fixtures = SyntheticFixtureGenerator.generate(seed = 42, count = 512)
        val selected = SyntheticFixtureGenerator.selectCase(fixtures, seed = 42, caseIndex = 511)
        assertEquals(Reproduction(42, 511, 512), selected.reproduction)
        assertNotEquals(Reproduction(42, 511, 513), selected.reproduction)

        val wrongMetadata = fixtures.toMutableList()
        wrongMetadata[511] = selected.copy(reproduction = Reproduction(42, 511, 513))
        assertThrows(IllegalArgumentException::class.java) {
            SyntheticFixtureGenerator.selectCase(wrongMetadata, seed = 42, caseIndex = 511)
        }
    }

    @Test
    fun generatedCorpusCoversRequiredShapes() {
        val fixtures = SyntheticFixtureGenerator.generate(count = 64)
        val inputs = fixtures.map { it.input }
        assertTrue(inputs.any { it.snapshot.pages.isEmpty() })
        assertTrue(inputs.any { it.snapshot.pages.size > 1 })
        assertEquals(
            setOf(
                ItemKind.APPLICATION,
                ItemKind.DEEP_SHORTCUT,
                ItemKind.SHORTCUT_LEGACY,
                ItemKind.FOLDER,
                ItemKind.APPWIDGET,
                ItemKind.CUSTOM_APPWIDGET,
                ItemKind.APP_PAIR,
            ),
            inputs.flatMap { it.snapshot.items }.map { it.kind }.toSet(),
        )
        assertTrue(inputs.flatMap { it.snapshot.items }.any { it.placement is CapturedPlacement.Dock })
        assertTrue(inputs.flatMap { it.snapshot.items }.any { it.locked })
        assertTrue(inputs.flatMap { it.snapshot.items }.any { it.availability != app.lawnchair.organizer.planning.Availability.AVAILABLE })
        assertTrue(
            inputs.any { input ->
                input.snapshot.items.filter { it.kind == ItemKind.APPLICATION }
                    .groupBy { (it.target as TargetKey.AppKey).component }
                    .values.any { apps -> apps.map { it.profile }.distinct().size > 1 }
            },
        )
    }

    @Test
    fun everyGeneratedInputIsValidByConstruction() {
        SyntheticFixtureGenerator.generate(count = 64).forEach { fixture ->
            val input = fixture.input
            val capturedIds = input.snapshot.items.map { it.id }
            assertEquals(capturedIds.size, capturedIds.distinct().size)
            assertEquals(capturedIds.toSet(), input.targets.existing.map { it.item }.toSet())
            assertEquals(input.targets.existing.size, input.targets.existing.map { it.item }.distinct().size)
            assertEquals(input.targets.additions.size, input.targets.additions.map { it.id }.distinct().size)
            assertTrue(capturedIds.toSet().intersect(input.targets.additions.map { it.id }.toSet()).isEmpty())
            if (input.runMode == RunMode.FullOrganization) assertTrue(input.targets.additions.isEmpty())
            input.signals.entries.forEach {
                assertTrue(it.item in capturedIds || input.targets.additions.any { candidate -> candidate.id == it.item })
                assertTrue(it.candidate in input.taxonomy.allowedCategories)
            }
            input.targets.additions.forEach {
                assertTrue(
                    (it.kind == CandidateKind.APPLICATION && it.target is CandidateTarget.AppKey) ||
                        (it.kind == CandidateKind.DEEP_SHORTCUT && it.target is CandidateTarget.ShortcutKey),
                )
                assertEquals(
                    it.profile,
                    when (val target = it.target) {
                        is CandidateTarget.AppKey -> target.profile
                        is CandidateTarget.ShortcutKey -> target.profile
                    },
                )
            }
            input.snapshot.items.forEach { item ->
                if (item.kind == ItemKind.FOLDER) {
                    assertNotNull(item.folderId)
                    assertTrue(item.members.isNotEmpty())
                    assertEquals(null, item.appPairId)
                    assertEquals(null, item.appPair)
                } else {
                    assertEquals(null, item.folderId)
                    assertTrue(item.members.isEmpty())
                }
                if (item.kind == ItemKind.APP_PAIR) {
                    assertNotNull(item.appPairId)
                    assertNotNull(item.appPair)
                } else {
                    assertEquals(null, item.appPairId)
                    assertEquals(null, item.appPair)
                }
            }
        }
    }

    @Test
    fun generatedCorpusContainsRequiredScenarioShapesAndExpectations() {
        val fixtures = SyntheticFixtureGenerator.generate(count = 64)

        val incrementalTargeting = fixtures.single { it.input.snapshot.items.any { item -> item.id.value.endsWith(".7.workspace") } }
        assertEquals(RunMode.IncrementalPlacement, incrementalTargeting.input.runMode)
        assertEquals(2, incrementalTargeting.input.targets.additions.size)
        assertEquals(1, incrementalTargeting.input.targets.existing.count { it.role == app.lawnchair.organizer.planning.ExistingRole.Movable })

        val overflow = fixtures.first { it.input.snapshot.pages.isEmpty() }
        assertEquals(RunMode.IncrementalPlacement, overflow.input.runMode)
        assertTrue(overflow.input.targets.additions.isNotEmpty())
        assertEquals(1, (overflow.expectation.outcome as ExpectedOutcome.Planned).expectedNewPageCount)

        val tie = fixtures.first { fixture ->
            fixture.input.signals.entries.groupBy { it.item to it.source }.values.any { entries ->
                entries.map { it.candidate }.distinct().size > 1 && entries.size > entries.distinct().size
            }
        }
        val tieExpectation = tie.expectation.outcome as ExpectedOutcome.Planned
        assertTrue(tieExpectation.requiredCategories.isNotEmpty())

        val unavailableCaptured = fixtures.first { fixture ->
            fixture.input.snapshot.items.any { it.availability != app.lawnchair.organizer.planning.Availability.AVAILABLE }
        }
        val unavailableId = unavailableCaptured.input.snapshot.items.single {
            it.availability != app.lawnchair.organizer.planning.Availability.AVAILABLE
        }.id
        assertEquals(
            PreserveReason.UNAVAILABLE_TARGET,
            (unavailableCaptured.expectation.outcome as ExpectedOutcome.Planned).requiredPreservations[unavailableId],
        )
        assertTrue(WarningCode.UNAVAILABLE_PRESERVED in unavailableCaptured.expectation.requiredWarningCodes)
    }

    @Test
    fun exampleCorpusHasExactlyElevenIds() {
        val ids = ExampleCorpus.allExamples.keys.map { it.value }.sorted()
        assertEquals(11, ids.size)
        assertEquals(
            listOf(
                "apps-only",
                "deck-output-compatibility",
                "device-profile-variation",
                "empty-home",
                "folder-container-integrity",
                "full-grid-no-capacity",
                "locked-fragmented-space",
                "mixed-app-shortcut-widget",
                "multiple-pages-and-dock",
                "same-package-personal-work",
                "undefined-category",
            ),
            ids,
        )
    }

    @Test
    fun deckOutputCompatibilityFixtureIsRegistered() {
        val fixture = ExampleCorpus.allExamples[FixtureId("deck-output-compatibility")]
        assertNotNull("deck-output-compatibility fixture must be registered in ExampleCorpus.allExamples", fixture)
        assertEquals(FixtureId("deck-output-compatibility"), fixture!!.id)
        assertNotNull(fixture.input)
    }

    @Test
    fun exampleCorpusUsesCompleteTargetPartitions() {
        ExampleCorpus.allExamples.values.forEach { fixture ->
            val captured = fixture.input.snapshot.items.map { it.id }.toSet()
            assertEquals(captured, fixture.input.targets.existing.map { it.item }.toSet())
            assertEquals(
                fixture.input.targets.existing.size,
                fixture.input.targets.existing.map { it.item }.distinct().size,
            )
            if (fixture.input.runMode == RunMode.FullOrganization) {
                assertTrue(fixture.input.targets.additions.isEmpty())
            }
        }
    }

    @Test
    fun positiveScenarioExamplesCarryTypedBehaviorExpectations() {
        val apps = ExampleCorpus.appsOnly.expectation.outcome as ExpectedOutcome.Planned
        assertEquals(1, apps.expectedNewFolderCount)
        assertEquals(3, apps.requiredCategories.size)

        val mixed = ExampleCorpus.mixedAppShortcutWidget
        val mixedOutcome = mixed.expectation.outcome as ExpectedOutcome.Planned
        assertEquals(PreserveReason.LEGACY_SHORTCUT, mixedOutcome.requiredPreservations[app.lawnchair.organizer.planning.ItemId("app.legacy")])
        assertEquals(PreserveReason.LOCKED, mixedOutcome.requiredPreservations[app.lawnchair.organizer.planning.ItemId("app.locked.unavailable")])
        assertEquals(PreserveReason.LOCKED, mixedOutcome.requiredPreservations[app.lawnchair.organizer.planning.ItemId("app.locked.dock")])
        assertTrue(mixed.input.snapshot.items.any { it.kind == ItemKind.APP_PAIR })
        assertTrue(mixed.input.snapshot.items.any { it.placement is CapturedPlacement.Dock })
        assertTrue(WarningCode.LEGACY_SHORTCUT_REVIEW in mixed.expectation.requiredWarningCodes)
        assertTrue(WarningCode.FALLBACK_CATEGORY in mixed.expectation.requiredWarningCodes)

        val locked = ExampleCorpus.lockedFragmentedSpace
        val lockedFolder = locked.input.snapshot.items.single { it.kind == ItemKind.FOLDER }
        assertTrue(lockedFolder.locked)
        assertEquals(2, lockedFolder.members.size)
        assertTrue(locked.input.snapshot.items.filter { it.id in lockedFolder.members }.all { it.locked })

        val fallback = ExampleCorpus.undefinedCategory
        val fallbackOutcome = fallback.expectation.outcome as ExpectedOutcome.Planned
        assertEquals(0, fallbackOutcome.expectedNewFolderCount)
        assertEquals(1, fallbackOutcome.requiredCategories.size)
        assertTrue(WarningCode.FALLBACK_CATEGORY in fallback.expectation.requiredWarningCodes)
    }

    @Test
    fun everyValidationRuleIdHasAFixture() {
        for (entry in ExampleCorpus.validationFixtures) {
            val id = entry.key.value
            assertTrue(id.startsWith("V-"))
            assertNotNull("Fixture missing for $id", entry.value)
        }
    }

    @Test
    fun validationVariantsMatchAcceptedPlan() {
        val required = setOf(
            "V-01", "V-02", "V-03",
            "V-04-neg-x", "V-04-neg-y", "V-04-right", "V-04-bottom", "V-04-neg-dock", "V-04-dock-eq",
            "V-05",
            "V-06-absent-ref", "V-06-dup-folder-id", "V-06-absent-app-pair-ref", "V-06-dup-app-pair-id",
            "V-06-parent-child-mismatch", "V-06-child-parent-mismatch", "V-06-duplicate-child",
            "V-06-disallowed-child", "V-06-two-containers", "V-06-cycle",
            "V-07-wrong-count", "V-07-dup-id", "V-07-dup-stage", "V-07-invalid-kind",
            "V-07-missing-snap", "V-07-unequal-snap", "V-07-incoherent-placement",
            "V-08", "V-09-dup-page-id", "V-09-dup-order",
            "V-10-captured-width", "V-10-captured-height", "V-10-candidate-width", "V-10-candidate-height",
            "V-10-columns", "V-10-rows", "V-10-hotseat", "V-10-folder-columns", "V-10-folder-rows",
            "V-11-kind-target", "V-11-candidate-kind-target", "V-11-folder-missing-id", "V-11-non-folder-id",
            "V-11-non-folder-members", "V-11-app-pair-missing-id", "V-11-non-app-pair-id",
            "V-11-app-pair-missing-metadata", "V-11-non-app-pair-metadata",
            "V-12-captured", "V-12-candidate", "V-13", "V-14",
            "V-15-dup-existing", "V-15-dup-addition", "V-15-captured-collision",
            "V-16", "V-17", "V-18", "V-19",
            "V-20-bad-version", "V-20-min-group", "V-20-dup-category", "V-20-bad-fallback",
            "V-21", "V-22",
        )
        assertEquals(required, ExampleCorpus.validationFixtures.keys.map { it.value }.toSet())
    }

    @Test
    fun everyValidationCoverageKeyIsPresent() {
        val keys = ExampleCorpus.validationCoverage.map { it.id.value }.toSet()
        assertEquals((1..22).map { "V-${it.toString().padStart(2, '0')}" }.toSet(), keys)
    }

    @Test
    fun everyScenarioCoverageKeyIsPresent() {
        val keys = ExampleCorpus.scenarioCoverage.map { it.id.value }.toSet()
        assertEquals((1..20).map { "S-${it.toString().padStart(2, '0')}" }.toSet(), keys)
    }

    @Test
    fun scenarioValidationMappingsContainEveryRequiredVariant() {
        fun mappedFixtures(scenario: String): Set<FixtureId> = ExampleCorpus.scenarioCoverage
            .single { it.id.value == scenario }
            .evidence
            .filterIsInstance<CoverageEvidence.Fixture>()
            .map { it.fixture }
            .toSet()

        val s14Rules = setOf("V-01", "V-02", "V-04", "V-05", "V-08", "V-09", "V-10", "V-11", "V-12")
        val expectedS14 = ExampleCorpus.validationFixtures.filterKeys { it.rule in s14Rules }.values.map { it.id }.toSet()
        val expectedS15 = ExampleCorpus.validationFixtures.filterKeys { it.rule == "V-07" }.values.map { it.id }.toSet()
        assertEquals(expectedS14, mappedFixtures("S-14"))
        assertEquals(expectedS15, mappedFixtures("S-15"))
    }

    @Test
    fun validationCoverageHasNoDuplicateKeys() {
        val keys = ExampleCorpus.validationCoverage.map { it.id }
        assertEquals(keys.size, keys.distinct().size)
    }

    @Test
    fun scenarioCoverageHasNoDuplicateKeys() {
        val keys = ExampleCorpus.scenarioCoverage.map { it.id }
        assertEquals(keys.size, keys.distinct().size)
    }

    @Test
    fun everyCoverageFixtureReferenceExists() {
        val allExampleIds = ExampleCorpus.allExamples.keys.toSet()
        val allValidationFixtureIds = ExampleCorpus.validationFixtures.values.map { it.id }.toSet()
        val allKnownIds = allExampleIds + allValidationFixtureIds

        for (row in ExampleCorpus.validationCoverage) {
            for (evidence in row.evidence) {
                if (evidence is CoverageEvidence.Fixture) {
                    assertTrue(
                        "Coverage references unknown fixture ${evidence.fixture.value}",
                        evidence.fixture in allKnownIds,
                    )
                    val fixture = ExampleCorpus.allExamples[evidence.fixture]
                        ?: ExampleCorpus.validationFixtures.values.single { it.id == evidence.fixture }
                    assertTrue(fixture.checks.containsAll(evidence.checks))
                }
            }
        }

        for (row in ExampleCorpus.scenarioCoverage) {
            for (evidence in row.evidence) {
                if (evidence is CoverageEvidence.Fixture) {
                    assertTrue(
                        "Coverage references unknown fixture ${evidence.fixture.value}",
                        evidence.fixture in allKnownIds,
                    )
                    val fixture = ExampleCorpus.allExamples[evidence.fixture]
                        ?: ExampleCorpus.validationFixtures.values.single { it.id == evidence.fixture }
                    assertTrue(fixture.checks.containsAll(evidence.checks))
                }
            }
        }
    }

    @Test
    fun everyCoverageRowHasNonEmptyEvidence() {
        for (row in ExampleCorpus.validationCoverage) {
            assertTrue("Validation coverage row ${row.id.value} has empty evidence", row.evidence.isNotEmpty())
        }
        for (row in ExampleCorpus.scenarioCoverage) {
            assertTrue("Scenario coverage row ${row.id.value} has empty evidence", row.evidence.isNotEmpty())
        }
    }

    @Test
    fun everyCoverageEvidenceHasNonEmptyCheckSet() {
        for (row in ExampleCorpus.validationCoverage) {
            for (evidence in row.evidence) {
                val checks = when (evidence) {
                    is CoverageEvidence.Fixture -> evidence.checks
                    is CoverageEvidence.Generated -> evidence.checks
                    is CoverageEvidence.Downstream -> continue
                }
                assertTrue("Validation coverage ${row.id.value} has empty check set", checks.isNotEmpty())
            }
        }
        for (row in ExampleCorpus.scenarioCoverage) {
            for (evidence in row.evidence) {
                val checks = when (evidence) {
                    is CoverageEvidence.Fixture -> evidence.checks
                    is CoverageEvidence.Generated -> evidence.checks
                    is CoverageEvidence.Downstream -> continue
                }
                assertTrue("Scenario coverage ${row.id.value} has empty check set", checks.isNotEmpty())
            }
        }
    }

    @Test
    fun scenarioCoverageHasLocalFixtureOrGeneratedEvidence() {
        for (row in ExampleCorpus.scenarioCoverage) {
            val hasLocalOrGenerated = row.evidence.any {
                it is CoverageEvidence.Fixture || it is CoverageEvidence.Generated
            }
            assertTrue(
                "Scenario ${row.id.value} has no local fixture or generated evidence",
                hasLocalOrGenerated,
            )
        }
    }
}
