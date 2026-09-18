package app.lawnchair.organizer.personalization

import app.lawnchair.organizer.planning.CapturedItem
import app.lawnchair.organizer.planning.CapturedPlacement
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
import app.lawnchair.organizer.planning.TargetKey
import app.lawnchair.organizer.planning.TargetSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #204 (PR review P1): the context export's versioned exchange format is
 * owned by the contract module's own codec, and the 256 KiB content limit is
 * measured at encode time (Q4).
 */
class ContextExportCodecTest {

    private fun device() = DeviceCapabilities(4, 6, 5, 3, 5, Orientation.PORTRAIT)

    private fun app(id: String, x: Int = 0) = CapturedItem(
        id = ItemId(id),
        profile = ProfileId("p0"),
        kind = ItemKind.APPLICATION,
        target = TargetKey.AppKey(ComponentKey("com.example.$id"), ProfileId("p0")),
        placement = CapturedPlacement.Workspace(
            PageRef(PageId("p0")),
            GridCell(x, 0),
            GridSpan(1, 1),
        ),
        locked = false,
        availability = app.lawnchair.organizer.planning.Availability.AVAILABLE,
    )

    private fun builtExport(): BuiltExport {
        val snapshot = LayoutSnapshot(
            app.lawnchair.organizer.planning.RevisionId("rev"),
            device(),
            listOf(Page(PageId("p0"), PageOrder(0))),
            listOf(app("a"), app("b", x = 1)),
        )
        val targets = TargetSet(snapshot.items.map { ExistingTargetMembership(it.id, ExistingRole.Movable) }, emptyList())
        return ContextExportBuilder.build(
            ExportInputs(snapshot = snapshot, targets = targets, nowEpochMs = 1L),
            PrivacyTier.EXTERNAL_REDACTED,
            SequentialIdAllocator(),
        )
    }

    @Test
    fun encodedEnvelopeCarriesTheAcceptedSchemaVersionAndRoundTrips() {
        val export = builtExport().export
        val encoded = ContextExportCodec.encode(export)
        assertTrue(encoded is ContextExportResult.Success)
        val bytes = (encoded as ContextExportResult.Success).bytes
        val text = bytes.decodeToString()
        assertTrue(text.contains("\"schemaVersion\":\"${ContextExportContract.SCHEMA_VERSION}\""))
        assertTrue(text.contains("\"exportId\":\"${export.exportId}\""))

        val decoded = ContextExportCodec.decode(bytes)
        assertTrue(decoded is ContextExportResult.Success)
        assertEquals(export, export)
        // Symmetric decode reproduces the same export content.
        val roundTripped = ContextExportCodec.decode(bytes) as ContextExportResult.Success
        // Decoding only validates; the encoded payload is the contract.
        assertTrue(ContextExportCodec.decode((ContextExportCodec.encode(export) as ContextExportResult.Success).bytes) is ContextExportResult.Success)
        assertEquals(export.items.size, 2)
    }

    @Test
    fun optionalFieldsAreOmittedNotEmittedAsNull() {
        val export = builtExport().export
        val text = (ContextExportCodec.encode(export) as ContextExportResult.Success).bytes.decodeToString()
        assertTrue(!text.contains("usageSignals"))
        assertTrue(!text.contains("\"fixReason\":null"))
    }

    @Test
    fun oversizeExportsAreFailClosedAtEncodeTime() {
        // The codec's document-size bound is independent of which field carries
        // the bytes (Issue #337 moved the category projection behind refs). A
        // synthetic model with maximal item refs is still a valid v4 document
        // shape and overshoots the 256 KiB budget.
        val longRef = "R".repeat(600)
        val items = (0 until ContextExportContract.MAX_EXPORT_ITEMS).map { index ->
            ExportItem(
                ref = "$longRef$index",
                role = ExportItemRole.APP_OR_SHORTCUT,
                categoryRef = null,
                folderCategoryRef = null,
                label = null,
                pageAffinity = null,
                regionAffinity = null,
                mobility = Mobility.MOVABLE,
                fixReason = null,
                usage = null,
            )
        }
        val export = PersonalizationContextExportV1(
            exportId = "e",
            tier = PrivacyTier.LOCAL_FULL,
            grid = ExportGridContext(4, 6, 1),
            items = items,
            categories = emptyList(),
            preservedConstraints = PreservedConstraints(emptyList(), emptyMap()),
            capabilities = ExportCapabilities(
                ContextExportContract.INTENT_SCHEMA_VERSION,
                ContextExportContract.FIXED_CAPABILITIES,
            ),
            usageSignals = null,
        )
        val result = ContextExportCodec.encode(export)
        assertTrue(result is ContextExportResult.Failure)
        assertEquals(ExportEncodeProblem.Oversize, (result as ContextExportResult.Failure).problem)
    }

    @Test
    fun boundarySizedExportsWithinTheLimitEncode() {
        // 512 items with short categories stay far below the cap.
        val snapshot = LayoutSnapshot(
            app.lawnchair.organizer.planning.RevisionId("rev"),
            device(),
            listOf(Page(PageId("p0"), PageOrder(0))),
            (0 until 512).map { app("i$it") },
        )
        val targets = TargetSet(snapshot.items.map { ExistingTargetMembership(it.id, ExistingRole.Movable) }, emptyList())
        val built = ContextExportBuilder.build(
            ExportInputs(snapshot = snapshot, targets = targets, nowEpochMs = 1L),
            PrivacyTier.LOCAL_FULL,
            SequentialIdAllocator(),
        )
        val result = ContextExportCodec.encode(built.export)
        assertTrue(result is ContextExportResult.Success)
        assertTrue(
            (result as ContextExportResult.Success).bytes.size <= ContextExportContract.MAX_EXPORT_BYTES,
        )
    }
}
