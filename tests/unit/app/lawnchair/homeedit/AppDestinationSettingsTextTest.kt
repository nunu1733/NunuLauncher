/*
 * Issue #497: settings-row state and resource-mapping oracles (spec AC-1
 * three-choice consistency with the existing toggle, AC-11 resource-derived
 * text). Pure JVM, HomeEditUndoRecoveryText precedent: the mapper returns
 * R.string values, never raw ids or empty text.
 */
package app.lawnchair.homeedit

import com.android.launcher3.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppDestinationSettingsTextTest {

    // --- three-choice ↔ toggle consistency (AC-1) ---

    @Test
    fun `toggle off shows DONT_ADD regardless of a stored folder designation`() {
        for (folderId in listOf<Int?>(null, 42)) {
            for (exists in listOf(true, false)) {
                assertEquals(
                    DestinationSummaryKind.DONT_ADD,
                    AppDestinationSummaryState.resolve(false, folderId, exists, "T"),
                )
            }
        }
    }

    @Test
    fun `toggle on with no designation shows UPSTREAM`() {
        assertEquals(
            DestinationSummaryKind.UPSTREAM,
            AppDestinationSummaryState.resolve(true, null, false, null),
        )
    }

    @Test
    fun `designated id that no longer resolves shows FOLDER_MISSING`() {
        // A deleted folder id must not display as a valid designation, even
        // if a same-named folder is recreated later (spec AC-3, id-based).
        assertEquals(
            DestinationSummaryKind.FOLDER_MISSING,
            AppDestinationSummaryState.resolve(true, 42, false, null),
        )
    }

    @Test
    fun `existing folder shows NAMED or UNTITLED by its title`() {
        assertEquals(
            DestinationSummaryKind.FOLDER_NAMED,
            AppDestinationSummaryState.resolve(true, 42, true, "Benchmark"),
        )
        assertEquals(
            DestinationSummaryKind.FOLDER_UNTITLED,
            AppDestinationSummaryState.resolve(true, 42, true, ""),
        )
        assertEquals(
            DestinationSummaryKind.FOLDER_UNTITLED,
            AppDestinationSummaryState.resolve(true, 42, true, null),
        )
    }

    // --- resource mappings (AC-11) ---

    @Test
    fun `every summary kind maps to its own non-zero launcher resource`() {
        val mappings = mapOf(
            DestinationSummaryKind.DONT_ADD to R.string.destination_policy_summary_dont_add,
            DestinationSummaryKind.UPSTREAM to R.string.destination_policy_summary_upstream,
            DestinationSummaryKind.FOLDER_MISSING to
                R.string.destination_policy_summary_folder_missing,
            DestinationSummaryKind.FOLDER_NAMED to R.string.destination_policy_summary_folder,
            DestinationSummaryKind.FOLDER_UNTITLED to R.string.destination_policy_summary_folder,
        )
        for ((kind, expected) in mappings) {
            assertEquals(expected, destinationSummaryText(kind))
        }
        // All referenced resources are distinct and defined (non-zero).
        val distinct = mappings.values.toSet()
        assertTrue("plain summaries must be distinct resources", distinct.size >= 4)
        for (res in distinct) {
            assertTrue(res != 0)
        }
    }

    @Test
    fun `every typed fallback reason maps to its notice resource`() {
        assertEquals(
            R.string.destination_policy_notice_missing,
            destinationNoticeText("DEST_FOLDER_MISSING"),
        )
        assertEquals(
            R.string.destination_policy_notice_profile,
            destinationNoticeText("DEST_PROFILE_MISMATCH"),
        )
        assertEquals(
            R.string.destination_policy_notice_dock,
            destinationNoticeText("DEST_DOCK_FOLDER"),
        )
        assertEquals(
            R.string.destination_policy_notice_constraint,
            destinationNoticeText("DEST_CONSTRAINT_VIOLATION"),
        )
        assertEquals(
            R.string.destination_policy_notice_snapshot,
            destinationNoticeText("DEST_SNAPSHOT_INVALID"),
        )
        // Unknown keys fail closed to the generic snapshot text, never an
        // internal id or empty string.
        assertEquals(
            R.string.destination_policy_notice_snapshot,
            destinationNoticeText("SOMETHING_ELSE"),
        )
    }
}
