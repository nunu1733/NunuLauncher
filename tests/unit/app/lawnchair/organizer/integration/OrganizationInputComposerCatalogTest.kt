package app.lawnchair.organizer.integration

import app.lawnchair.organizer.application.adapter.FakeLayoutWriter
import app.lawnchair.organizer.application.canonical.CanonicalFixtures
import app.lawnchair.organizer.application.protocol.CaptureId
import app.lawnchair.organizer.application.public.LayoutState
import app.lawnchair.organizer.planning.CategoryId
import app.lawnchair.organizer.planning.CategoryIdentity
import app.lawnchair.organizer.planning.ComponentKey
import app.lawnchair.organizer.planning.PackageName
import app.lawnchair.organizer.planning.ProfileId
import app.lawnchair.organizer.planning.SignalSource
import app.lawnchair.organizer.planning.TargetKey
import app.lawnchair.organizer.planning.UserCategoryId
import app.lawnchair.organizer.planning.UserDefinedCategory
import app.lawnchair.organizer.planning.WorkspaceOverlapToleranceSource
import app.lawnchair.organizer.rules.BuiltInOrganizerPolicyBundleSource
import app.lawnchair.organizer.rules.BundleReadResult
import app.lawnchair.organizer.rules.CategoryOverrideDecodeOutcome
import app.lawnchair.organizer.rules.CategoryOverrideFullStoreCodec
import app.lawnchair.organizer.rules.CategoryOverrideKey
import app.lawnchair.organizer.rules.CategoryOverrideSnapshot
import app.lawnchair.organizer.rules.CategoryOverrideSnapshotSource
import app.lawnchair.organizer.rules.ClassificationPolicy
import app.lawnchair.organizer.rules.LayoutStrategySelectionReadResult
import app.lawnchair.organizer.rules.LayoutStrategySelectionSnapshot
import app.lawnchair.organizer.rules.LayoutStrategySelectionSource
import app.lawnchair.organizer.rules.OrganizerPolicyBundleSource
import app.lawnchair.organizer.rules.OverrideSnapshotReadResult
import app.lawnchair.organizer.rules.PolicyInputIdentity
import app.lawnchair.organizer.rules.PolicySourceKind
import app.lawnchair.organizer.rules.UserDefinedCategoryCatalogIdentity
import app.lawnchair.organizer.rules.UserDefinedCategoryCatalogReadResult
import app.lawnchair.organizer.rules.UserDefinedCategoryCatalogSnapshot
import app.lawnchair.organizer.rules.UserDefinedCategoryCatalogSource
import app.lawnchair.organizer.rules.UserDefinedCategoryStoreCodec
import app.lawnchair.organizer.rules.UserDefinedCategoryStoreDecodeOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #336 composer integration tests: the catalog provenance row with its
 * empty-catalog sentinel, dynamic-cut participation (bounded two-attempt
 * instability), fail-closed unreadable/unsupported reads, the dangling
 * user-defined override reference, and fresh-run S1 consumption of a
 * user-defined assignment.
 */
class OrganizationInputComposerCatalogTest {

    private val userId = UserCategoryId("0a000000-0000-4000-8000-00000000000a")

    // --- provenance ----------------------------------------------------------

    @Test
    fun emptyCatalogCarriesTheDefinedSentinelProvenanceIdentity() {
        val ready = readyOf(catalogReads = listOf(emptyCatalog(), emptyCatalog()))

        assertEquals(PolicySourceKind.USER_DEFINED_CATEGORY_CATALOG, ready.provenance.userDefinedCategoryCatalog.source)
        assertEquals(UserDefinedCategoryCatalogIdentity.emptyCatalogSentinel(), ready.provenance.userDefinedCategoryCatalog)
        // The composed catalog carries the built-in taxonomy only.
        assertTrue(ready.input.catalog.userDefined.isEmpty())
        assertEquals(ready.input.catalog.builtIn, ready.input.taxonomy)
    }

    @Test
    fun nonEmptyCatalogCarriesSchemaGenerationDigestAndJoinsTheComposedCatalog() {
        val catalog = catalogOf(UserDefinedCategory(userId, "AI tools"))
        val ready = readyOf(catalogReads = listOf(catalog, catalog))

        assertEquals("schema-1-generation-3", ready.provenance.userDefinedCategoryCatalog.versionOrGeneration)
        assertEquals(
            UserDefinedCategoryCatalogIdentity.identityOf(1, 3L, listOf(UserDefinedCategory(userId, "AI tools"))),
            ready.provenance.userDefinedCategoryCatalog,
        )
        assertEquals(listOf(UserDefinedCategory(userId, "AI tools")), ready.input.catalog.userDefined)
        assertTrue(CategoryIdentity.UserDefined(userId) in ready.input.catalog.allowedIdentities)
    }

    // --- dynamic cut ---------------------------------------------------------

    @Test
    fun catalogChangeBetweenCutReadsIsTypedUnstableNotReady() {
        val first = catalogOf(UserDefinedCategory(userId, "AI tools"))
        val second = catalogOf(UserDefinedCategory(userId, "Renamed"))
        // Both attempts must be internally unstable for the bounded protocol
        // to end typed-unstable.
        val reads = listOf(first, second, first, second)

        val result = compose(catalogReads = reads).composeFullOrganization()

        assertTrue(result is OrganizationInputComposition.NotReady)
        val notReady = result as OrganizationInputComposition.NotReady
        assertEquals(InputCompositionCode.DYNAMIC_CUT_UNSTABLE, notReady.diagnostic.code)
    }

    @Test
    fun catalogReadFailureInsideTheCutFailsClosedBeforePlanning() {
        val result = compose(catalogReads = listOf(unreadable())).composeFullOrganization()

        assertTrue(result is OrganizationInputComposition.NotReady)
        val notReady = result as OrganizationInputComposition.NotReady
        assertEquals(InputCompositionCode.CATALOG_UNREADABLE, notReady.diagnostic.code)
        assertEquals(
            InputReadinessReason.SourceUnreadable(PolicySourceKind.USER_DEFINED_CATEGORY_CATALOG),
            notReady.reason,
        )
    }

    @Test
    fun codecBackedNewerCatalogSchemaReachesCATALOG_UNSUPPORTED_SCHEMA() {
        // Regression pin for the accepted forward-compat routing: the SAME
        // codec-to-read-result mapping the production access performs, driven
        // by a well-formed schema-2 catalog file (supported set is schema 1).
        val newerCatalogBytes = "schema=2\ngeneration=1\ndigest=${"0".repeat(64)}\nentries\n\n".toByteArray()
        val source = UserDefinedCategoryCatalogSource {
            when (val outcome = UserDefinedCategoryStoreCodec.decodeOutcome(newerCatalogBytes)) {
                is UserDefinedCategoryStoreDecodeOutcome.Ready -> UserDefinedCategoryCatalogReadResult.Ready(
                    UserDefinedCategoryCatalogSnapshot(
                        schemaVersion = outcome.snapshot.identity.schemaVersion,
                        generation = outcome.snapshot.identity.generation,
                        categories = outcome.snapshot.categories,
                        identity = UserDefinedCategoryCatalogIdentity.identityOf(
                            outcome.snapshot.identity.schemaVersion,
                            outcome.snapshot.identity.generation,
                            outcome.snapshot.categories,
                        ),
                    ),
                )

                UserDefinedCategoryStoreDecodeOutcome.Unreadable -> UserDefinedCategoryCatalogReadResult.Unreadable

                UserDefinedCategoryStoreDecodeOutcome.UnsupportedSchema -> UserDefinedCategoryCatalogReadResult.UnsupportedSchema
            }
        }

        val result = compose(catalogSource = source).composeFullOrganization()

        assertTrue(result is OrganizationInputComposition.NotReady)
        assertEquals(
            InputCompositionCode.CATALOG_UNSUPPORTED_SCHEMA,
            (result as OrganizationInputComposition.NotReady).diagnostic.code,
        )
    }

    @Test
    fun codecBackedNewerOverrideSchemaReachesOVERRIDE_UNSUPPORTED_SCHEMA() {
        // Well-formed schema-3 override snapshot through the same
        // codec-to-read-result mapping the production access performs.
        val newerOverrideBytes = "schema=3\ngeneration=1\ndigest=${"0".repeat(64)}\nentries\n\n".toByteArray()
        val source = object : CategoryOverrideSnapshotSource {
            override fun read(capturedProfiles: Set<ProfileId>): OverrideSnapshotReadResult = when (val outcome = CategoryOverrideFullStoreCodec.decodeOutcome(newerOverrideBytes)) {
                is CategoryOverrideDecodeOutcome.Ready -> OverrideSnapshotReadResult.Ready(
                    CategoryOverrideSnapshot(
                        schemaVersion = outcome.snapshot.identity.schemaVersion,
                        generation = outcome.snapshot.identity.generation,
                        assignments = outcome.snapshot.assignments,
                        identity = PolicyInputIdentity(
                            PolicySourceKind.CATEGORY_OVERRIDE_SNAPSHOT,
                            "schema-${outcome.snapshot.identity.schemaVersion}-generation-${outcome.snapshot.identity.generation}",
                            outcome.snapshot.identity.sha256,
                        ),
                    ),
                )

                CategoryOverrideDecodeOutcome.Unreadable -> OverrideSnapshotReadResult.Unreadable

                CategoryOverrideDecodeOutcome.UnsupportedSchema -> OverrideSnapshotReadResult.UnsupportedSchema
            }
        }

        val result = compose(overrides = source).composeFullOrganization()

        assertTrue(result is OrganizationInputComposition.NotReady)
        assertEquals(
            InputCompositionCode.OVERRIDE_UNSUPPORTED_SCHEMA,
            (result as OrganizationInputComposition.NotReady).diagnostic.code,
        )
    }

    @Test
    fun unsupportedCatalogSchemaIsItsOwnTypedCode() {
        val result = compose(catalogReads = listOf(unsupported())).composeFullOrganization()

        assertTrue(result is OrganizationInputComposition.NotReady)
        val notReady = result as OrganizationInputComposition.NotReady
        assertEquals(InputCompositionCode.CATALOG_UNSUPPORTED_SCHEMA, notReady.diagnostic.code)
        assertEquals(
            InputReadinessReason.UnsupportedVersion(PolicySourceKind.USER_DEFINED_CATEGORY_CATALOG, null),
            notReady.reason,
        )
    }

    // --- membership ----------------------------------------------------------

    @Test
    fun danglingUserDefinedOverrideReferenceIsItsOwnZeroWriteFailure() {
        // The override references a user-defined ID the catalog does not
        // contain — external corruption only; never silently remapped.
        val overrides = snapshot(
            mapOf(CategoryOverrideKey(PackageName("com.example.a"), ProfileId("personal")) to CategoryIdentity.UserDefined(userId)),
        )
        val result = compose(
            overrides = SequenceOverrides(overrides, overrides),
            catalogReads = listOf(emptyCatalog(), emptyCatalog()),
        ).composeFullOrganization()

        assertTrue(result is OrganizationInputComposition.NotReady)
        val notReady = result as OrganizationInputComposition.NotReady
        assertEquals(InputCompositionCode.OVERRIDE_DANGLING_CATEGORY, notReady.diagnostic.code)
        assertEquals(
            InputReadinessReason.ContradictorySource(PolicySourceKind.CATEGORY_OVERRIDE_SNAPSHOT),
            notReady.reason,
        )
    }

    @Test
    fun userDefinedAssignmentInsideTheCatalogIsConsumedAsS1OnAFreshRun() {
        val overrides = snapshot(
            mapOf(CategoryOverrideKey(PackageName("com.example.a"), ProfileId("personal")) to CategoryIdentity.UserDefined(userId)),
        )
        val catalog = catalogOf(UserDefinedCategory(userId, "AI tools"))
        val ready = readyOf(overrides = SequenceOverrides(overrides, overrides), catalogReads = listOf(catalog, catalog))

        val signal = ready.input.signals.entries.single { it.item.value == "a" }
        assertEquals(SignalSource.S1, signal.source)
        assertEquals(CategoryIdentity.UserDefined(userId), signal.candidate)
        assertEquals("u:${userId.value}", signal.candidate.canonicalValue)
    }

    @Test
    fun builtInOverrideOutsideTheTaxonomyRemainsOVERRIDE_CATEGORY_INVALID() {
        val overrides = snapshot(
            mapOf(CategoryOverrideKey(PackageName("com.example.a"), ProfileId("personal")) to CategoryIdentity.BuiltIn(CategoryId("NOT_IN_V1"))),
        )
        val result = compose(overrides = SequenceOverrides(overrides, overrides)).composeFullOrganization()

        assertTrue(result is OrganizationInputComposition.NotReady)
        assertEquals(
            InputCompositionCode.OVERRIDE_CATEGORY_INVALID,
            (result as OrganizationInputComposition.NotReady).diagnostic.code,
        )
    }

    // --- fixtures ------------------------------------------------------------

    private fun readyOf(
        overrides: CategoryOverrideSnapshotSource = SequenceOverrides(emptySnapshot(), emptySnapshot()),
        catalogReads: List<UserDefinedCategoryCatalogReadResult>,
    ): OrganizationInputComposition.Ready = compose(overrides = overrides, catalogReads = catalogReads)
        .composeFullOrganization() as OrganizationInputComposition.Ready

    private fun compose(
        state: LayoutState = defaultState(),
        overrides: CategoryOverrideSnapshotSource = SequenceOverrides(emptySnapshot(), emptySnapshot()),
        catalogReads: List<UserDefinedCategoryCatalogReadResult> = listOf(emptyCatalog(), emptyCatalog()),
        catalogSource: UserDefinedCategoryCatalogSource? = null,
    ): OrganizationInputComposer = DefaultOrganizationInputComposer(
        captureSource = CanonicalCaptureSource {
            CanonicalCaptureReadResult.Ready(FakeLayoutWriter(state).captureCurrent(CaptureId("test")))
        },
        bundleSource = BuiltInOrganizerPolicyBundleSource,
        overrides = overrides,
        userDefinedCategories = catalogSource ?: SequenceCatalogs(*catalogReads.toTypedArray()),
        layoutStrategySelections = SequenceSelections(),
        platformEvidence = SequenceEvidence(),
        overlapTolerance = WorkspaceOverlapToleranceSource { true },
    )

    private fun defaultState(): LayoutState = CanonicalFixtures.state(
        items = listOf(
            CanonicalFixtures.appItem(
                itemId = "a",
                profile = "personal",
                target = TargetKey.AppKey(ComponentKey("com.example.a/.Main"), ProfileId("personal")),
            ),
        ),
        profiles = listOf(CanonicalFixtures.profile("personal")),
    )

    private fun emptyCatalog() = UserDefinedCategoryCatalogReadResult.Ready(emptyCatalogSnapshot())

    private fun catalogOf(vararg categories: UserDefinedCategory) = UserDefinedCategoryCatalogReadResult.Ready(
        UserDefinedCategoryCatalogSnapshot(
            schemaVersion = 1,
            generation = 3L,
            categories = categories.toList(),
            identity = UserDefinedCategoryCatalogIdentity.identityOf(1, 3L, categories.toList()),
        ),
    )

    private fun unreadable() = UserDefinedCategoryCatalogReadResult.Unreadable

    private fun unsupported() = UserDefinedCategoryCatalogReadResult.UnsupportedSchema

    private fun emptyCatalogSnapshot() = UserDefinedCategoryCatalogSnapshot(
        schemaVersion = 1,
        generation = 0L,
        categories = emptyList(),
        identity = UserDefinedCategoryCatalogIdentity.emptyCatalogSentinel(),
    )

    private fun emptySnapshot() = CategoryOverrideSnapshot(
        schemaVersion = 1,
        generation = 0L,
        assignments = emptyMap(),
        identity = PolicyInputIdentity(PolicySourceKind.CATEGORY_OVERRIDE_SNAPSHOT, "schema-1-generation-0", "a".repeat(64)),
    )

    private fun snapshot(assignments: Map<CategoryOverrideKey, CategoryIdentity>) = CategoryOverrideSnapshot(
        schemaVersion = 1,
        generation = 1L,
        assignments = assignments,
        identity = PolicyInputIdentity(PolicySourceKind.CATEGORY_OVERRIDE_SNAPSHOT, "schema-1-generation-1", "b".repeat(64)),
    )

    private class SequenceCatalogs(
        private vararg val reads: UserDefinedCategoryCatalogReadResult,
    ) : UserDefinedCategoryCatalogSource {
        private var index = 0

        override fun read(): UserDefinedCategoryCatalogReadResult = reads[index.coerceAtMost(reads.lastIndex)].also {
            index += 1
        }
    }

    private class SequenceOverrides(
        private vararg val snapshots: CategoryOverrideSnapshot,
    ) : CategoryOverrideSnapshotSource {
        private var index = 0

        override fun read(capturedProfiles: Set<ProfileId>): OverrideSnapshotReadResult = OverrideSnapshotReadResult.Ready(
            snapshots[minOf(index++, snapshots.lastIndex)],
        )
    }

    private class SequenceEvidence : ClassificationSignalSnapshotSource {
        override fun read(
            requests: List<ClassificationEvidenceRequest>,
            policy: ClassificationPolicy,
        ): PlatformEvidenceReadResult = PlatformEvidenceReadResult.Ready(
            PlatformClassificationEvidence(
                s2 = emptyMap(),
                s5 = emptyMap(),
                identity = PolicyInputIdentity(PolicySourceKind.PLATFORM_CLASSIFICATION_EVIDENCE, "platform-evidence-v1", "c".repeat(64)),
            ),
        )
    }

    private class SequenceSelections : LayoutStrategySelectionSource {
        override fun read(): LayoutStrategySelectionReadResult = LayoutStrategySelectionReadResult.Ready(
            LayoutStrategySelectionSnapshot(
                schemaVersion = 1,
                generation = 0L,
                selection = null,
                identity = PolicyInputIdentity(PolicySourceKind.LAYOUT_STRATEGY_SELECTION, "schema-1-generation-0", "d".repeat(64)),
            ),
        )
    }
}
