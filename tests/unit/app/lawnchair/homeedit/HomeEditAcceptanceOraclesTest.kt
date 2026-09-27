/*
 * Issue #448 acceptance oracles that are checkable on the JVM:
 * AC-1 (popup eligibility predicate), AC-8 (pure module boundary), and
 * AC-14 (shortcut/dialog strings exist and are non-empty in en and ja).
 */
package app.lawnchair.homeedit

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeEditAcceptanceOraclesTest {

    // --- AC-1: popup eligibility predicate ---

    @Test
    fun `popup predicate accepts saved app and deep shortcut rows only`() {
        val filter = app.lawnchair.homeedit.ui.EditActionTargetFilter
        assertEquals(true, filter.isEligible(HomeEditItemTypes.APPLICATION, 42))
        assertEquals(true, filter.isEligible(HomeEditItemTypes.DEEP_SHORTCUT, 42))
        // Widgets, folders, app pairs, and unsaved rows are out of scope.
        assertEquals(false, filter.isEligible(5, 42))
        assertEquals(false, filter.isEligible(HomeEditItemTypes.FOLDER, 42))
        // ItemInfo.NO_ID is -1; id 0 is a legitimate saved row.
        assertEquals(true, filter.isEligible(HomeEditItemTypes.APPLICATION, 0))
        assertEquals(false, filter.isEligible(HomeEditItemTypes.APPLICATION, -1))
    }

    // --- AC-8: the pure planning module stays free of model/DB/UI types ---

    @Test
    fun `homeedit pure module references only boundary or jdk types`() {
        val allowedPrefixes = setOf(
            "app.lawnchair.homeedit",
            "com.android.launcher3.model.DirectEditContract",
            "java.",
            "javax.",
            "kotlin.",
            "kotlin.jvm.internal.Intrinsics",
        )
        val pureClasses = listOf(
            HomeEditPlanner::class.java,
            HomeEditSnapshot::class.java,
            HomeEditItem::class.java,
            HomeEditIntent::class.java,
            HomeEditPlan::class.java,
        )
        for (clazz in pureClasses) {
            val referenced = mutableSetOf<String>()
            for (method in clazz.methods) {
                referenced.add(method.returnType.name)
                method.parameterTypes.forEach { referenced.add(it.name) }
                method.exceptionTypes?.forEach { referenced.add(it.name) }
            }
            for (field in clazz.fields) {
                referenced.add(field.type.name)
            }
            val offending = referenced.filter { type ->
                allowedPrefixes.none { type.startsWith(it) } &&
                    !type.startsWith("boolean") && !type.startsWith("int") &&
                    !type.startsWith("long") && !type.startsWith("void")
            }
            assertTrue(
                "pure module leaks platform types: $offending",
                offending.isEmpty(),
            )
        }
    }

    // --- AC-14: shortcut/dialog strings exist and are non-empty (en + ja) ---

    private fun stringsFile(localeDir: String): File {
        var dir = File(System.getProperty("user.dir")!!)
        repeat(4) {
            val candidate = File(dir, "lawnchair/res/$localeDir/strings.xml")
            if (candidate.exists()) return candidate
            dir = dir.parentFile ?: dir
        }
        error("strings.xml not found for $localeDir from ${System.getProperty("user.dir")}")
    }

    private fun readHomeeditStrings(localeDir: String): Map<String, String> {
        val text = stringsFile(localeDir).readText()
        val regex = Regex("<string name=\"(homeedit_[^\"]+)\">(.*?)</string>")
        return regex.findAll(text).associate { it.groupValues[1] to it.groupValues[2] }
    }

    @Test
    fun `all homeedit strings are defined and non-empty in en and ja`() {
        val expected = listOf(
            "homeedit_menu_move_to_page",
            "homeedit_menu_add_to_folder",
            "homeedit_menu_remove_from_home",
            "homeedit_dialog_title_move_to_page",
            "homeedit_dialog_title_add_to_folder",
            "homeedit_dialog_title_new_folder_page",
            "homeedit_list_new_folder",
            "homeedit_page_label",
            "homeedit_folder_entry",
            "homeedit_folder_default_label",
            "homeedit_lock_note_locked",
            "homeedit_lock_note_locked_remove",
            "homeedit_error_stale",
            "homeedit_error_item_gone",
            "homeedit_error_no_space",
            "homeedit_error_redundant",
            "homeedit_error_folder_gone",
            "homeedit_error_profile_mismatch",
            "homeedit_error_unsupported",
            "homeedit_error_write_failed",
        )
        for (localeDir in listOf("values", "values-ja")) {
            val strings = readHomeeditStrings(localeDir)
            for (name in expected) {
                val value = strings[name]
                assertTrue("$localeDir/$name missing", value != null)
                assertTrue("$localeDir/$name empty", value!!.isNotBlank())
            }
        }
    }
}
