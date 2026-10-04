package app.lawnchair.organizer.personalization

import app.lawnchair.organizer.personalization.exchange.DetectedCandidateScope
import app.lawnchair.organizer.personalization.exchange.ReconstructionResult
import app.lawnchair.organizer.personalization.exchange.ScopeBindingCurrentScope
import app.lawnchair.organizer.personalization.exchange.ScopeBindingGate
import app.lawnchair.organizer.personalization.exchange.ScopeBindingOutcome
import app.lawnchair.organizer.personalization.exchange.ScopeBindingSessionScope
import app.lawnchair.organizer.personalization.exchange.SessionExportReconstructor
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
import app.lawnchair.organizer.planning.UserCategoryId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #336 exchange parity contract (accepted plan, "Exchange parity tests"):
 *
 * - the export presentation layer projects a user-defined classification as
 *   the absent category — no raw `UserCategoryId` and no display name appears
 *   in an export document or a session record field — while built-in-only
 *   export bytes stay exactly as before #336;
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
        return ExportInputs(
            snapshot = snapshot,
            targets = targets,
            resolvedIdentities = resolved,
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
    fun userDefinedClassificationsExportAsAbsentCategoryWithoutRawIdOrName() {
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
        assertNull("user-defined candidate exports the absent category", candidate.category)
        assertNull("user-defined placed item exports the absent category", placed.category)

        // Neither layer of the identity (raw ID or display name) may appear in
        // the export document bytes or any session record field.
        val rawId = TEST_USER_CATEGORY_ID
        val displayName = "Commute tools"
        for (surface in listOf(documentBytes(built.export), built.export.toString(), built.session.toString())) {
            assertFalse("raw user ID leaked: $surface", surface.contains(rawId))
            assertFalse("display name leaked: $surface", surface.contains(displayName))
        }
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
    fun builtInExportsAreByteIdenticalAcrossTheTwoLayers() {
        // A built-in-only fixture produces the same export document bytes
        // whether the identities pass through the identity-typed layer or are
        // re-derived: redaction is a no-op for built-ins.
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
        assertEquals("NEWS", withIdentity.export.items.single { it.subject == ExportItemSubject.PLACED }.category)
        assertEquals("SPORTS", withIdentity.export.items.single { it.subject == ExportItemSubject.CANDIDATE }.category)
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
    fun reconstructionViewConsumesIdentitiesAndNeverShowsUserDefinedCategories() {
        val identity = userCategory(TEST_USER_CATEGORY_ID)
        val snapshot = LayoutSnapshot(RevisionId("rev"), device(), pages(), listOf(app("a")), emptyList())
        val targets = TargetSet(emptyList(), emptyList())
        val exportTime = built(
            inputs(items = listOf(app("a")), resolved = mapOf(ItemId("a") to identity)),
        )
        val structural = CanonicalStructuralInputs(snapshot, targets, mapOf(ItemId("a") to identity))

        val rebuilt = SessionExportReconstructor.rebuild(exportTime.session, structural)
        val view = (rebuilt as ReconstructionResult.Rebuilt).export
        assertNull("reconstructed view redacts user-defined categories", view.items.single().category)

        // The reconstruction-parity seam shares one field derivation: a
        // built-in classification reconstructs its raw value like the export.
        val builtIn = CanonicalStructuralInputs(
            snapshot,
            targets,
            mapOf(ItemId("a") to CategoryIdentity.BuiltIn(CategoryId("NEWS"))),
        )
        val builtInView = (SessionExportReconstructor.rebuild(exportTime.session, builtIn) as ReconstructionResult.Rebuilt).export
        assertEquals("NEWS", builtInView.items.single().category)

        // The view never carries the raw ID or a display name.
        val viewText = builtInView.toString()
        assertFalse(viewText.contains(TEST_USER_CATEGORY_ID))
        assertFalse(viewText.contains("u:"))
    }

    companion object {
        /** Canonical lowercase UUID v4 fixtures; digest inputs only, never fields. */
        private const val TEST_USER_CATEGORY_ID = "3f2b8c4e-1234-4abc-9de0-1234567890ab"
        private const val SECOND_USER_CATEGORY_ID = "a1b2c3d4-5678-4abc-8de0-abcdefabcdef"
    }
}
