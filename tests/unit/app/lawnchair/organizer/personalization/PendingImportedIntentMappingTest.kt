package app.lawnchair.organizer.personalization

import app.lawnchair.organizer.integration.AndroidPendingImportedIntentStore
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #374 DI-AC-10: the durable record's privacy boundary is fixed by its
 * shape — no rationale, confidence, app label, folder title, or category
 * display-name field exists anywhere on the record model, and the persisted
 * bytes of a record derived from a rationale/confidence-carrying completed
 * intent contain neither the free text nor the field names. Also pins the
 * record-to-canonical-decisions mapping fidelity.
 */
class PendingImportedIntentMappingTest {

    private val originalDecisions = mapOf(
        "r1" to RefDecision.Authored(
            ItemIntent(
                ref = "r1",
                importance = Importance.HIGH,
                desiredGroupRefs = listOf("r2"),
                groupSemantic = GroupSemantic(categoryRef = "cat-1", proposalLabel = null),
                pageAffinity = 1,
                regionAffinity = ExportRegionKind.BOTTOM,
                preserve = true,
            ),
        ),
        "r2" to RefDecision.Authored(
            ItemIntent(ref = "r2", groupSemantic = GroupSemantic(categoryRef = null, proposalLabel = "Morning")),
        ),
        "r3" to RefDecision.Authored(
            ItemIntent(ref = "r3", groupSemantic = GroupSemantic(categoryRef = null, proposalLabel = "Evening")),
        ),
        "r4" to RefDecision.UnresolvedAuthored,
        "r5" to RefDecision.UnresolvedByOmission,
    )

    private fun completedWithSecretFreeText() = CompletedPersonalIntent(
        exportId = "export-1",
        decisions = originalDecisions,
        globalPreference = GlobalPreference(minimizeMovement = true),
        rationale = "secret free text",
        confidence = 88,
    )

    @Test
    fun recordModelCarriesNoRationaleConfidenceOrHumanReadableItemField() {
        // The shape is itself the privacy guarantee (the summary-model
        // contract-test style): a forbidden field cannot be persisted, shown,
        // or leaked by accident if it cannot be named.
        val forbidden = setOf("rationale", "confidence", "displayName", "label", "itemName")
        val recordTypes = listOf(
            DurablePendingIntent::class.java,
            DurableRefEntry::class.java,
            DurableRefDecision.Authored::class.java,
            DurableGroupSemantic.ExistingCategory::class.java,
            DurableGroupSemantic.ProposedGroup::class.java,
        )
        for (type in recordTypes) {
            val fields = type.declaredFields
                .filterNot { it.isSynthetic || it.name.startsWith("$") }
                .map { it.name }
                .toSet()
            assertTrue(
                "$type must declare at least one field",
                fields.isNotEmpty(),
            )
            val offenders = fields.filter { it in forbidden }
            assertTrue(
                "$type must not declare any of $forbidden (found $offenders)",
                offenders.isEmpty(),
            )
        }
    }

    @Test
    fun persistedBytesContainNoRationaleConfidenceOrFreeText() {
        val directory = File.createTempFile("pending-intent-privacy", "test")
        directory.delete()
        directory.mkdirs()
        try {
            val completed = completedWithSecretFreeText()
            val identity = IntentIdentityCalculator.identity(completed)
            val record = durablePendingIntentFrom(
                completed = completed,
                identity = identity,
                entryKind = PendingImportEntryKind.RUN_IN,
                nowEpochMs = 1_000L,
                expiresAtEpochMs = 5_000L,
            )
            val store = AndroidPendingImportedIntentStore(File(directory, "pending"))
            assertTrue(store.save(record))

            // Read every file the store may have produced (base + AtomicFile
            // companions) so the assertion is independent of the write layout.
            val persisted = directory.walkTopDown()
                .filter { it.isFile }
                .map { it.readBytes().decodeToString() }
                .joinToString("\n")
            assertFalse(persisted.contains("secret free text"))
            assertFalse(persisted.contains("rationale"))
            assertFalse(persisted.contains("confidence"))

            // The identity digest still reproduces the import-time identity
            // (it was computed over the canonical representation INCLUDING the
            // dropped fields), which is exactly what #375's rebind needs.
            assertEquals(identity.schemaVersion, record.intentIdentitySchemaVersion)
            assertEquals(identity.digest, record.intentIdentityDigest)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun toDecisionsMapRoundTripsEveryDecisionVariant() {
        val completed = completedWithSecretFreeText()
        val record = durablePendingIntentFrom(
            completed = completed,
            identity = IntentIdentityCalculator.identity(completed),
            entryKind = PendingImportEntryKind.IDLE,
            nowEpochMs = 1_000L,
            expiresAtEpochMs = 5_000L,
        )

        assertEquals(originalDecisions, record.toDecisionsMap())
    }

    @Test
    fun minimizeMovementKeepsOnlyThePlannerEffectiveValue() {
        for (global in listOf(GlobalPreference(minimizeMovement = true), GlobalPreference(minimizeMovement = false), null)) {
            val completed = completedWithSecretFreeText().copy(globalPreference = global)
            val record = durablePendingIntentFrom(
                completed = completed,
                identity = IntentIdentityCalculator.identity(completed),
                entryKind = PendingImportEntryKind.IDLE,
                nowEpochMs = 1_000L,
                expiresAtEpochMs = 5_000L,
            )
            assertEquals(global?.minimizeMovement == true, record.minimizeMovement)
        }
    }
}
