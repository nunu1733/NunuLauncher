package app.lawnchair.organizer.personalization

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #204 AC-14 (cross-export identifier unlinkability) and the sentinel
 * identity contract (AC-6, 4th review P1).
 */
class UnlinkabilityAndIdentityContractTest {

    private fun app(id: String, x: Int = 0) = app.lawnchair.organizer.planning.CapturedItem(
        id = app.lawnchair.organizer.planning.ItemId(id),
        profile = app.lawnchair.organizer.planning.ProfileId("p0"),
        kind = app.lawnchair.organizer.planning.ItemKind.APPLICATION,
        target = app.lawnchair.organizer.planning.TargetKey.AppKey(
            app.lawnchair.organizer.planning.ComponentKey("com.example.$id"),
            app.lawnchair.organizer.planning.ProfileId("p0"),
        ),
        placement = app.lawnchair.organizer.planning.CapturedPlacement.Workspace(
            app.lawnchair.organizer.planning.PageRef(app.lawnchair.organizer.planning.PageId("p0")),
            app.lawnchair.organizer.planning.GridCell(x, 0),
            app.lawnchair.organizer.planning.GridSpan(1, 1),
        ),
        locked = false,
        availability = app.lawnchair.organizer.planning.Availability.AVAILABLE,
    )

    private fun build(idOffset: Int): BuiltExport {
        val snapshot = app.lawnchair.organizer.planning.LayoutSnapshot(
            app.lawnchair.organizer.planning.RevisionId("rev"),
            app.lawnchair.organizer.planning.DeviceCapabilities(4, 6, 5, 3, 5, app.lawnchair.organizer.planning.Orientation.PORTRAIT),
            listOf(app.lawnchair.organizer.planning.Page(app.lawnchair.organizer.planning.PageId("p0"), app.lawnchair.organizer.planning.PageOrder(0))),
            listOf(app("a"), app("b", x = 1)),
        )
        val targets = app.lawnchair.organizer.planning.TargetSet(
            snapshot.items.map { app.lawnchair.organizer.planning.ExistingTargetMembership(it.id, app.lawnchair.organizer.planning.ExistingRole.Movable) },
            emptyList(),
        )
        return ContextExportBuilder.build(
            app.lawnchair.organizer.personalization.ExportInputs(
                snapshot = snapshot,
                targets = targets,
                resolvedCategories = emptyMap(),
                userLabels = mapOf(app.lawnchair.organizer.planning.ItemId("a") to "Some App"),
                nowEpochMs = 1L,
            ),
            PrivacyTier.LOCAL_FULL,
            SequentialIdAllocator(idOffset),
        )
    }

    @Test
    fun reExportsShareNoExternalIdentifierAndNoStateFingerprint() {
        val first = build(0)
        val second = build(1_000)

        assertNotEquals(first.export.exportId, second.export.exportId)
        assertNotEquals(
            first.export.items.map { it.ref }.toSet(),
            second.export.items.map { it.ref }.toSet(),
        )
        // The structural digest never appears in the export document.
        val firstDocument = first.export.toString()
        val secondDocument = second.export.toString()
        assertFalse(firstDocument.contains(first.session.sourceContextDigest))
        assertFalse(firstDocument.contains(second.session.sourceContextDigest))
        // Internal identities are not exported.
        val internalIds = first.session.itemRefs.values.map { it.value }
        for (id in internalIds) {
            assertFalse("internal ItemId must not appear in the export document", firstDocument.contains("|$id"))
        }
    }

    private fun internalIdsAbsentFrom(document: String, internalIds: List<String>) {
        for (id in internalIds) {
            assertFalse("internal ItemId must not appear in the export document", document.contains("|$id"))
        }
    }

    @Test
    fun noIntentSentinelIsAValidPolicyInputIdentity() {
        val sentinel = PersonalizedIntentIdentity.noIntentSentinel()
        assertEquals(app.lawnchair.organizer.rules.PolicySourceKind.PERSONALIZED_INTENT, sentinel.source)
        assertEquals(PersonalizedIntentIdentity.NO_INTENT_VERSION, sentinel.versionOrGeneration)
        assertEquals(64, sentinel.sha256.length)
        // Same canonical representation → same sentinel identity.
        assertEquals(
            app.lawnchair.organizer.rules.sha256Canonical(PersonalizedIntentIdentity.NO_INTENT_CANONICAL),
            sentinel.sha256,
        )
    }

    @Test
    fun acceptedIntentIdentityJoinsProvenanceWithTheIntentDigest() {
        val identity = IntentIdentity(schemaVersion = ContextExportContract.INTENT_SCHEMA_VERSION, digest = "a".repeat(64))
        val provenance = identity.policyIdentity()
        assertEquals(app.lawnchair.organizer.rules.PolicySourceKind.PERSONALIZED_INTENT, provenance.source)
        assertEquals(identity.digest, provenance.sha256)
    }
}
