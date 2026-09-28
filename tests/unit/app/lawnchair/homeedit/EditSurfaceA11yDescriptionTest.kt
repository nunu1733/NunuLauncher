/*
 * Issue #449: JVM oracle for the AC-15 semantics supply contract (review
 * round 3 residual). The pure description builder is the single source the
 * Compose semantics reads; each selection/eligibility state's description
 * content (title, kind/reason, position, selection state) is pinned here so
 * removing any element from the supply path fails this test.
 */
package app.lawnchair.homeedit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EditSurfaceA11yDescriptionTest {

    // 固定テキスト（リソース由来の値の代わり。資源の存在/非空は別oracleで検証済み）。
    private val texts = mapOf(
        "selected" to "selected",
        "locked" to "placement locked",
        "lockUnknown" to "lock state unavailable",
        "notSelectable" to "not selectable",
        "folder" to "folder, 2 items",
    )

    private fun describe(
        eligibility: SelectionEligibility,
        selected: Boolean,
        isFolder: Boolean = false,
        label: String = "Alpha",
        pageLabel: String? = "Page 1",
        cellLabel: String? = "(0, 1)",
    ): String = app.lawnchair.homeedit.ui.editSurfaceItemDescription(
        label = label,
        eligibility = eligibility,
        isFolder = isFolder,
        memberCount = 2,
        selected = selected,
        selectedText = texts.getValue("selected"),
        lockedText = texts.getValue("locked"),
        lockUnknownText = texts.getValue("lockUnknown"),
        notSelectableText = texts.getValue("notSelectable"),
        folderText = texts.getValue("folder"),
        pageLabel = pageLabel,
        cellLabel = cellLabel,
    )

    @Test
    fun `selectable item carries title position and selection state when selected`() {
        val description = describe(SelectionEligibility.SELECTABLE, selected = true)
        assertTrue("title missing: $description", description.contains("Alpha"))
        assertTrue("position missing: $description", description.contains("Page 1") && description.contains("(0, 1)"))
        assertTrue("selection state missing: $description", description.contains("selected"))
    }

    @Test
    fun `deselected selectable item drops the selection state`() {
        val description = describe(SelectionEligibility.SELECTABLE, selected = false)
        assertTrue(description.contains("Alpha"))
        assertFalse("selection state must be absent: $description", description.contains("selected"))
    }

    @Test
    fun `locked item carries the lock reason`() {
        val description = describe(SelectionEligibility.LOCKED, selected = false)
        assertTrue("lock reason missing: $description", description.contains("placement locked"))
    }

    @Test
    fun `lock unknown item carries the lock unknown reason`() {
        val description = describe(SelectionEligibility.LOCK_UNKNOWN, selected = false)
        assertTrue("lock unknown reason missing: $description", description.contains("lock state unavailable"))
    }

    @Test
    fun `unsupported item carries the not selectable reason and never a selection state`() {
        val selected = describe(SelectionEligibility.UNSUPPORTED, selected = true)
        val deselected = describe(SelectionEligibility.UNSUPPORTED, selected = false)
        for (description in listOf(selected, deselected)) {
            assertTrue("not-selectable reason missing: $description", description.contains("not selectable"))
            assertFalse("unsupported items never read as selected: $description", description.contains("selected"))
        }
    }

    @Test
    fun `folder item carries the member count kind`() {
        // A selectable folder row (session-created folders render as folders;
        // eligibility for real folder rows is UNSUPPORTED and tested above).
        val description = describe(SelectionEligibility.SELECTABLE, selected = false, isFolder = true)
        assertTrue("folder kind missing: $description", description.contains("folder, 2 items"))
    }

    @Test
    fun `missing page or cell label degrades without empty fragments`() {
        val description = describe(SelectionEligibility.SELECTABLE, selected = false, pageLabel = null, cellLabel = null)
        assertEquals("Alpha", description)
    }
}
