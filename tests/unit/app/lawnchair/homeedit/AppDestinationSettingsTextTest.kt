/*
 * Issue #497: settings-row state and resource-mapping oracles (spec AC-1
 * three-choice consistency with the existing toggle, AC-11 resource-derived
 * text). Pure JVM, HomeEditUndoRecoveryText precedent: the mapper returns
 * R.string values, never raw ids or empty text.
 */
package app.lawnchair.homeedit

import com.android.launcher3.R
import java.io.File
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

    // --- dialog resource contract (AC-11): resource-derived, non-empty, ja included ---

    @Test
    fun `dialog resource key contract matches the launcher resources`() {
        assertEquals(R.string.destination_policy_dialog_title, AppDestinationPolicyTextKeys.dialogTitle)
        assertEquals(R.string.destination_policy_choice_upstream, AppDestinationPolicyTextKeys.choiceUpstream)
        assertEquals(R.string.destination_policy_choice_folder, AppDestinationPolicyTextKeys.choiceFolder)
        assertEquals(R.string.destination_policy_choice_dont_add, AppDestinationPolicyTextKeys.choiceDontAdd)
        assertEquals(R.string.destination_policy_folder_picker_title, AppDestinationPolicyTextKeys.folderPickerTitle)
        assertEquals(R.string.destination_policy_folder_stop, AppDestinationPolicyTextKeys.folderStop)
        assertEquals(R.string.destination_policy_other_profile, AppDestinationPolicyTextKeys.otherProfile)
        assertEquals(7, AppDestinationPolicyTextKeys.all.size)
        assertEquals("keys must be distinct resources", 7, AppDestinationPolicyTextKeys.all.toSet().size)
    }

    @Test
    fun `every destination policy string is non-empty in default and ja`() {
        for (localeDir in listOf("values", "values-ja")) {
            val entries = destinationPolicyStrings(localeDir)
            val required = setOf(
                "destination_policy_label",
                "destination_policy_summary_upstream",
                "destination_policy_summary_folder",
                "destination_policy_summary_folder_missing",
                "destination_policy_summary_dont_add",
                "destination_policy_dialog_title",
                "destination_policy_choice_upstream",
                "destination_policy_choice_folder",
                "destination_policy_choice_dont_add",
                "destination_policy_folder_picker_title",
                "destination_policy_folder_stop",
                "destination_policy_other_profile",
                "destination_policy_notice_missing",
                "destination_policy_notice_profile",
                "destination_policy_notice_dock",
                "destination_policy_notice_constraint",
                "destination_policy_notice_snapshot",
            )
            assertEquals("missing entries in $localeDir", required, entries.keys)
            for ((name, value) in entries) {
                assertTrue("$localeDir/$name must be non-blank", value.isNotBlank())
            }
        }
    }

    /** repo内のlawnchair strings.xmlからdestination_policy_* entryを読む
     *  （source-contract test慣行。ExchangeRequestFlowContractTestと同一の
     *  unit-test working directory規約）。 */
    private fun destinationPolicyStrings(localeDir: String): Map<String, String> {
        var dir: File? = File(System.getProperty("user.dir"))
        repeat(4) {
            val candidate = File(dir, "lawnchair/res/$localeDir/strings.xml")
            if (candidate.exists()) {
                val content = candidate.readText()
                val pattern = Regex("""<string name="(destination_policy_[A-Za-z0-9_]+)">([\s\S]*?)</string>""")
                return pattern.findAll(content).associate { it.groupValues[1] to it.groupValues[2] }
            }
            dir = dir?.parentFile
        }
        error("lawnchair strings.xml not found for $localeDir from ${System.getProperty("user.dir")}")
    }
}
