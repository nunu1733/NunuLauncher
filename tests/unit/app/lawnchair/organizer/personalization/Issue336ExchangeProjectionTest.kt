package app.lawnchair.organizer.personalization

import app.lawnchair.organizer.personalization.exchange.DetectedCandidateScope
import app.lawnchair.organizer.personalization.exchange.ReconstructionResult
import app.lawnchair.organizer.personalization.exchange.ScopeBindingCurrentScope
import app.lawnchair.organizer.personalization.exchange.ScopeBindingGate
import app.lawnchair.organizer.personalization.exchange.ScopeBindingOutcome
import app.lawnchair.organizer.personalization.exchange.ScopeBindingSessionScope
import app.lawnchair.organizer.personalization.exchange.SessionExportReconstructor
import app.lawnchair.organizer.planning.ActiveCategoryCatalog
import app.lawnchair.organizer.planning.Availability
import app.lawnchair.organizer.planning.CandidatePlanningIds
import app.lawnchair.organizer.planning.CandidateTarget
import app.lawnchair.organizer.planning.CapturedItem
import app.lawnchair.organizer.planning.CapturedPlacement
import app.lawnchair.organizer.planning.CategoryId
import app.lawnchair.organizer.planning.CategoryIdentity
import app.lawnchair.organizer.planning.ComponentKey
import app.lawnchair.organizer.planning.DeviceCapabilities
import app.lawnchair.organizer.planning.ExistingRole
import app.lawnchair.organizer.planning.ExistingTargetMembership
import app.lawnchair.organizer.planning.GridCell
import app.lawnchair.organizer.planning.GridSpan
import app.lawnchair.organizer.planning.ItemId
import app.lawnchair.organizer.planning.ItemKind
import app.lawnchair.organizer.planning.LayoutSnapshot
import app.lawnchair.organizer.planning.Orientation
import app.lawnchair.organizer.planning.Page
import app.lawnchair.organizer.planning.PageId
import app.lawnchair.organizer.planning.PageOrder
import app.lawnchair.organizer.planning.PageRef
import app.lawnchair.organizer.planning.ProfileId
import app.lawnchair.organizer.planning.RevisionId
import app.lawnchair.organizer.planning.TargetKey
import app.lawnchair.organizer.planning.TargetSet
import app.lawnchair.organizer.planning.TaxonomyContract
import app.lawnchair.organizer.planning.TaxonomyVersion
import app.lawnchair.organizer.planning.UserCategoryId
import app.lawnchair.organizer.planning.UserDefinedCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #336 exchange parity contract (accepted plan, "Exchange parity tests"),
 * as revised by Issue #337 (v4):
 *
 * - the export document advertises the active catalog as export-scoped refs
 *   and every item-level category exposure is one of those refs: no raw
 *   `UserCategoryId` appears in the document, the built-in value survives only
 *   as the advertised entry's `taxonomyId`, and the user-defined display name
 *   appears only in the advertised entry when the privacy tier admits the
 *   free-text class (never at `EXTERNAL_REDACTED`). The session holds the
 *   ref → identity mapping (app-private, no-backup) and never a display name;
 * - the session-local freshness layer digests the resolved `CategoryIdentity`
 *   (kind + stable ID), so an A→B reassignment and an assigned-category
 *   deletion (falling through to built-in classification) are detected, while
 *   creating, renaming (stable ID unchanged), or deleting an unassigned
 *   category never moves a digest;
 * - the scope binding gate and the session reconstruction consume the same
 *   identity-preserving digest.
 */
class Issue336ExchangeProjectionTest {

    private val now = 1_000_000L

    // ---- fixtures ---------------------------------------------------------

    private fun device() = DeviceCapabilities(4, 6, 5, 3, 5, Orientation.PORTRAIT)

    private fun pages() = listOf(Page(PageId("p0"), PageOrder(0)))

    private fun app(id: String) = CapturedItem(
        id = ItemId(id),
        profile = ProfileId("p0"),
        kind = ItemKind.APPLICATION,
        target = TargetKey.AppKey(ComponentKey("com.example.$id"), ProfileId("p0")),
        placement = CapturedPlacement.Workspace(PageRef(PageId("p0")), GridCell(0, 0), GridSpan(1, 1)),
        locked = false,
        availability = Availability.AVAILABLE,
    )

    private fun target(component: String) = CandidateTarget.AppKey(ComponentKey(component), ProfileId("p0"))

    private fun userCategory(id: String) = CategoryIdentity.UserDefined(UserCategoryId(id))

    private fun inputs(
        items: List<CapturedItem>,
        additions: List<CandidateTarget.AppKey> = emptyList(),
        resolved: Map<ItemId, CategoryIdentity?> = emptyMap(),
    ): ExportInputs {
        val snapshot = LayoutSnapshot(RevisionId("rev"), device(), pages(), items, emptyList())
        val additionItems = additions.map { component ->
            app.lawnchair.organizer.planning.CandidateItem(
                id = CandidatePlanningIds.planningId(component),
                profile = component.profile,
                kind = app.lawnchair.organizer.planning.CandidateKind.APPLICATION,
                target = component,
                availability = Availability.AVAILABLE,
                span = GridSpan(1, 1),
            )
        }
        val targets = TargetSet(items.map { ExistingTargetMembership(it.id, ExistingRole.Movable) }, additionItems)
        // Issue #337: the advertised catalog of this fixture — the built-ins
        // the fixture classifies plus the two user-defined fixtures.
        val catalog = ActiveCategoryCatalog(
            builtIn = TaxonomyContract(
                version = TaxonomyVersion("tv1"),
                allowedCategories = listOf("NEWS", "SPORTS", "OTHER").map { CategoryId(it) },
                fallbackCategory = CategoryId("OTHER"),
            ),
            userDefined = listOf(
                UserDefinedCategory(UserCategoryId(TEST_USER_CATEGORY_ID), "Commute tools"),
                UserDefinedCategory(UserCategoryId(SECOND_USER_CATEGORY_ID), "Second"),
            ),
        )
        return ExportInputs(
            snapshot = snapshot,
            targets = targets,
            resolvedIdentities = resolved,
            catalog = catalog,
            nowEpochMs = now,
        )
    }

    private fun built(inputs: ExportInputs): BuiltExport = ContextExportBuilder.build(
        inputs,
        PrivacyTier.EXTERNAL_WITH_LABELS,
        SequentialIdAllocator(),
    )

    private fun documentBytes(export: PersonalizationContextExportV1): String = (ContextExportCodec.encode(export) as ContextExportResult.Success).bytes.decodeToString()

    // ---- export presentation layer -----------------------------------------

    @Test
    fun userDefinedClassificationsExportAsAdvertisedRefsWithoutRawIdOrNameAtItemLevel() {
        val addition = target("com.assigned")
        val identity = userCategory(TEST_USER_CATEGORY_ID)
        val built = built(
            inputs(
                items = listOf(app("a")),
                additions = listOf(addition),
                resolved = mapOf(
                    ItemId("a") to identity,
                    CandidatePlanningIds.planningId(addition) to identity,
                ),
            ),
        )

        val candidate = built.export.items.single { it.subject == ExportItemSubject.CANDIDATE }
        val placed = built.export.items.single { it.subject == ExportItemSubject.PLACED }
        val advertised = built.export.categories.single { it.ref == candidate.categoryRef }
        assertEquals("the user-defined entry is advertised as an opaque ref", advertised.ref, candidate.categoryRef)
        assertEquals("the placed item points at the same advertised entry", advertised.ref, placed.categoryRef)
        assertNull("a user-defined entry carries no taxonomy id", advertised.taxonomyId)

        // The document never carries the raw stable ID; the display name is a
        // free-text class that only the advertised entry carries (here the tier
        // is label-inclusive).
        val rawId = TEST_USER_CATEGORY_ID
        val displayName = "Commute tools"
        val document = documentBytes(built.export)
        assertFalse("raw user ID leaked: $document", document.contains(rawId))
        assertEquals(displayName, advertised.displayName?.value)
        assertEquals(FreeTextClass.USER_CATEGORY_NAME, advertised.displayName?.freeTextClass)
        // The session keeps the ref → identity mapping (app-private, never
        // backed up) and never a display name.
        assertFalse("display name leaked into the session", built.session.toString().contains(displayName))
        assertEquals(userCategory(rawId), built.session.categoryRefs[advertised.ref])
        // The identity still reaches the one-way session digests: the same
        // export session must be distinguishable from one built without it.
        val withoutAssignment = built(
            inputs(items = listOf(app("a")), additions = listOf(addition)),
        )
        assertNotEquals(built.session.sourceContextDigest, withoutAssignment.session.sourceContextDigest)
        assertNotEquals(built.session.scopeCandidateDigest, withoutAssignment.session.scopeCandidateDigest)
    }

    @Test
    fun builtInOnlyExportsKeepThePre336BytesExactly() {
        // The canonical candidate row and placed-item row for a built-in
        // classification are byte-identical to the pre-336 string projection.
        val addition = target("com.a")
        val builtIn = CategoryIdentity.BuiltIn(CategoryId("NEWS"))
        val canonical = CandidateScopeIdentity.canonicalRepresentation(
            listOf(CandidateScopeProjection(addition, Availability.AVAILABLE, builtIn)),
        )
        assertEquals("candidate|com.a|p0|AVAILABLE|NEWS", canonical)
        assertEquals(
            "digest input bytes unchanged for built-in classifications",
            sha256Hex("candidate|com.a|p0|AVAILABLE|NEWS"),
            CandidateScopeIdentity.digest(listOf(CandidateScopeProjection(addition, Availability.AVAILABLE, builtIn))),
        )
        val placedCanonical = SourceContextIdentity.canonicalRepresentation(
            CanonicalStructuralInputs(
                LayoutSnapshot(RevisionId("rev"), device(), pages(), listOf(app("a")), emptyList()),
                TargetSet(emptyList(), emptyList()),
                mapOf(ItemId("a") to builtIn),
            ),
        )
        assertTrue(placedCanonical.substringAfterLast("\n").endsWith("|NEWS"))
    }

    @Test
    fun builtInClassificationsProjectAdvertisedRefsCarryingTheTaxonomyId() {
        // Issue #337: a built-in classification also projects a ref; the
        // taxonomy enum value lives on the advertised entry only.
        val addition = target("com.a")
        val withIdentity = built(
            inputs(
                items = listOf(app("a")),
                additions = listOf(addition),
                resolved = mapOf(
                    ItemId("a") to CategoryIdentity.BuiltIn(CategoryId("NEWS")),
                    CandidatePlanningIds.planningId(addition) to CategoryIdentity.BuiltIn(CategoryId("SPORTS")),
                ),
            ),
        )
        val placedRef = withIdentity.export.items.single { it.subject == ExportItemSubject.PLACED }.categoryRef
        val candidateRef = withIdentity.export.items.single { it.subject == ExportItemSubject.CANDIDATE }.categoryRef
        assertEquals(
            "NEWS",
            withIdentity.export.categories.single { it.ref == placedRef }.taxonomyId,
        )
        assertEquals(
            "SPORTS",
            withIdentity.export.categories.single { it.ref == candidateRef }.taxonomyId,
        )
        assertNotEquals("the item carries a ref, not the raw value", "NEWS", placedRef)
    }

    // ---- session-local freshness layer --------------------------------------

    @Test
    fun digestTracksIdentityNotDisplayName() {
        val addition = target("com.a")
        val identityA = userCategory(TEST_USER_CATEGORY_ID)

        val beforeRename = CandidateScopeIdentity.digest(
            listOf(CandidateScopeProjection(addition, Availability.AVAILABLE, identityA)),
        )
        // Rename: the display name changes, the stable ID does not — the
        // freshness identity is rename-invariant.
        val afterRename = CandidateScopeIdentity.digest(
            listOf(CandidateScopeProjection(addition, Availability.AVAILABLE, identityA)),
        )
        assertEquals(beforeRename, afterRename)

        // A→B reassignment: a different user-defined stable ID moves the
        // digest exactly as a built-in resolved-category change does.
        val identityB = userCategory(SECOND_USER_CATEGORY_ID)
        val afterReassignment = CandidateScopeIdentity.digest(
            listOf(CandidateScopeProjection(addition, Availability.AVAILABLE, identityB)),
        )
        assertNotEquals(beforeRename, afterReassignment)

        // Assigned-category delete: classification falls through to the
        // built-in resolution — also a digest change.
        val afterDeleteFallsToBuiltIn = CandidateScopeIdentity.digest(
            listOf(
                CandidateScopeProjection(addition, Availability.AVAILABLE, CategoryIdentity.BuiltIn(CategoryId("NEWS"))),
            ),
        )
        assertNotEquals(beforeRename, afterDeleteFallsToBuiltIn)
        assertNotEquals(afterReassignment, afterDeleteFallsToBuiltIn)

        // Create / unassigned delete: no candidate's resolved identity
        // changes, so no digest moves (the projection stays unresolved).
        val unresolved = CandidateScopeIdentity.digest(
            listOf(CandidateScopeProjection(addition, Availability.AVAILABLE, null)),
        )
        assertEquals(
            unresolved,
            CandidateScopeIdentity.digest(
                listOf(CandidateScopeProjection(addition, Availability.AVAILABLE, null)),
            ),
        )
    }

    @Test
    fun placedItemDigestBehavesLikeTheCandidateDigest() {
        val identityA = userCategory(TEST_USER_CATEGORY_ID)
        val identityB = userCategory(SECOND_USER_CATEGORY_ID)
        fun structural(category: CategoryIdentity?): CanonicalStructuralInputs = CanonicalStructuralInputs(
            LayoutSnapshot(RevisionId("rev"), device(), pages(), listOf(app("a")), emptyList()),
            TargetSet(emptyList(), emptyList()),
            mapOf(ItemId("a") to category),
        )

        val renamed = SourceContextIdentity.digest(structural(identityA))
        assertEquals("rename must not move the placed-item digest", renamed, SourceContextIdentity.digest(structural(identityA)))
        assertNotEquals("A→B reassignment must move the placed-item digest", renamed, SourceContextIdentity.digest(structural(identityB)))
        assertNotEquals(
            "assigned delete falls through to built-in and moves the digest",
            renamed,
            SourceContextIdentity.digest(structural(CategoryIdentity.BuiltIn(CategoryId("NEWS")))),
        )
        assertNotEquals(
            "unresolved must differ from resolved",
            SourceContextIdentity.digest(structural(null)),
            renamed,
        )
    }

    // ---- gate + reconstruction consume the same identity ---------------------

    @Test
    fun scopeBindingGateConsumesTheIdentityPreservingDigest() {
        val addition = target("com.a")
        val identityA = userCategory(TEST_USER_CATEGORY_ID)
        val exportProjection = listOf(CandidateScopeProjection(addition, Availability.AVAILABLE, identityA))
        val sessionScope = ScopeBindingSessionScope(
            scopeCandidates = listOf(addition),
            scopeCandidateDigest = CandidateScopeIdentity.digest(exportProjection),
        )

        fun current(category: CategoryIdentity?) = ScopeBindingCurrentScope(
            detected = listOf(DetectedCandidateScope(addition, Availability.AVAILABLE)),
            selectedTargets = setOf(addition),
            candidateProjections = listOf(CandidateScopeProjection(addition, Availability.AVAILABLE, category)),
        )

        assertEquals(ScopeBindingOutcome.Pass, ScopeBindingGate.evaluate(sessionScope, current(identityA)))
        assertEquals(
            "A→B reassignment is a typed scope mismatch",
            ScopeBindingOutcome.Mismatch(ScopeMismatchCause.PROJECTION_MISMATCH),
            ScopeBindingGate.evaluate(sessionScope, current(userCategory(SECOND_USER_CATEGORY_ID))),
        )
        assertEquals(
            "assigned delete (falls to built-in) is a typed scope mismatch",
            ScopeBindingOutcome.Mismatch(ScopeMismatchCause.PROJECTION_MISMATCH),
            ScopeBindingGate.evaluate(sessionScope, current(CategoryIdentity.BuiltIn(CategoryId("NEWS")))),
        )
    }

    @Test
    fun reconstructionViewAdvertisesOnlyIdentitiesThatStillExistAndShowsNoRawId() {
        val identity = userCategory(TEST_USER_CATEGORY_ID)
        val snapshot = LayoutSnapshot(RevisionId("rev"), device(), pages(), listOf(app("a")), emptyList())
        val targets = TargetSet(emptyList(), emptyList())
        val exportTime = built(
            inputs(items = listOf(app("a")), resolved = mapOf(ItemId("a") to identity)),
        )
        val structural = CanonicalStructuralInputs(
            snapshot,
            targets,
            mapOf(ItemId("a") to identity),
            catalog = exportTime.export.categories.let { entries ->
                // The catalog of the export attempt (rebuilt from the same
                // fixture): the reconstruction intersects it with the session
                // mapping.
                ActiveCategoryCatalog(
                    builtIn = TaxonomyContract(
                        version = TaxonomyVersion("tv1"),
                        allowedCategories = listOf("NEWS", "OTHER").map { CategoryId(it) },
                        fallbackCategory = CategoryId("OTHER"),
                    ),
                    userDefined = listOf(UserDefinedCategory(UserCategoryId(TEST_USER_CATEGORY_ID), "Commute tools")),
                ).also { require(entries.isNotEmpty()) }
            },
        )

        val rebuilt = SessionExportReconstructor.rebuild(exportTime.session, structural)
        val view = (rebuilt as ReconstructionResult.Rebuilt).export
        val exportTimeEntry = exportTime.export.categories.single { it.ref == exportTime.export.items.single().categoryRef }
        val entry = view.categories.single { it.ref == exportTimeEntry.ref }
        assertEquals("the view advertises the same opaque ref", exportTimeEntry.ref, entry.ref)
        assertEquals("the reconstructed item still points at it", entry.ref, view.items.single().categoryRef)

        // A catalog that no longer contains the identity advertises nothing:
        // the stale reference fails closed instead of resolving silently.
        val withoutIdentity = CanonicalStructuralInputs(
            snapshot,
            targets,
            mapOf(ItemId("a") to identity),
            catalog = ActiveCategoryCatalog(
                builtIn = TaxonomyContract(TaxonomyVersion("tv1"), listOf(CategoryId("OTHER")), CategoryId("OTHER")),
                userDefined = emptyList(),
            ),
        )
        val staleView = (SessionExportReconstructor.rebuild(exportTime.session, withoutIdentity) as ReconstructionResult.Rebuilt).export
        assertTrue("a deleted category is not advertised", staleView.categories.none { it.kind == CategoryRefKind.USER_DEFINED })
        assertNull("its items project the absent category instead", staleView.items.single().categoryRef)

        // The view never carries the raw ID (the session mapping is not part of
        // the document view).
        val viewText = view.toString()
        assertFalse(viewText.contains(TEST_USER_CATEGORY_ID))
        assertFalse(viewText.contains("u:"))
    }

    companion object {
        /** Canonical lowercase UUID v4 fixtures; digest inputs only, never fields. */
        private const val TEST_USER_CATEGORY_ID = "3f2b8c4e-1234-4abc-9de0-1234567890ab"
        private const val SECOND_USER_CATEGORY_ID = "a1b2c3d4-5678-4abc-8de0-abcdefabcdef"
    }
}
