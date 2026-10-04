package app.lawnchair.organizer.application.contract

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AC-13: `apply`/`recover` are the only public behavior entry points of the
 * Layout Application module. All public value shapes are platform-free (no
 * Android, SQLite, or `RevisionId`-internal bytes cross the seam).
 *
 * Issue #14 Stage B step 1.
 */
class PublicSeamShapeTest {

    private val forbiddenPrefixes = listOf(
        "android.",
        "androidx.",
        "com.android.",
        "android.database",
        "android.content",
        "java.sql",
        "javax.sql",
    )

    private val applicationPublicDir = File("lawnchair/src/app/lawnchair/organizer/application/public")
    private val applicationCanonicalDir = File("lawnchair/src/app/lawnchair/organizer/application/canonical")
    private val applicationRevisionDir = File("lawnchair/src/app/lawnchair/organizer/application/revision")
    private val applicationLifecycleDir = File("lawnchair/src/app/lawnchair/organizer/application/lifecycle")
    private val applicationActionsDir = File("lawnchair/src/app/lawnchair/organizer/application/actions")

    @Test
    fun publicDirectoryExistsAndContainsCanonicalShapes() {
        assertTrue(
            "Public dir must exist: ${applicationPublicDir.absolutePath}",
            applicationPublicDir.exists() && applicationPublicDir.isDirectory,
        )
        val files: Array<File> = applicationPublicDir.listFiles { f ->
            f.isFile && f.name.endsWith(".kt")
        } ?: emptyArray()
        assertTrue("Public dir must contain at least one .kt file", files.isNotEmpty())
    }

    @Test
    fun publicFilesDoNotImportAndroidOrSqlite() {
        assertNoForbiddenImports(
            applicationPublicDir,
            "public application value shapes must not import platform types",
        )
    }

    @Test
    fun canonicalFilesDoNotImportAndroidOrSqlite() {
        if (!applicationCanonicalDir.exists()) return
        assertNoForbiddenImports(
            applicationCanonicalDir,
            "canonical marshalling must be pure",
        )
    }

    @Test
    fun revisionCalculatorIsPure() {
        if (!applicationRevisionDir.exists()) return
        assertNoForbiddenImports(
            applicationRevisionDir,
            "RevisionCalculator must not depend on Android/SQLite",
        )
    }

    @Test
    fun lifecycleModuleIsPure() {
        if (!applicationLifecycleDir.exists()) return
        assertNoForbiddenImports(
            applicationLifecycleDir,
            "lifecycle state machine must be pure",
        )
    }

    @Test
    fun actionsModuleIsPure() {
        if (!applicationActionsDir.exists()) return
        assertNoForbiddenImports(
            applicationActionsDir,
            "action materializer must be pure",
        )
    }

    @Test
    fun publicSeamDoesNotExposeSecondaryEntryPoints() {
        val publicFiles: Array<File> = applicationPublicDir.listFiles { f ->
            f.isFile && f.name.endsWith(".kt")
        } ?: emptyArray()
        val text = publicFiles.joinToString("\n") { it.readText() }
        // Public seam exposes only data and the result/value shapes; behavior lives behind protocol.
        assertFalse(
            "Public module must not declare `fun apply` or `fun recover` — those are protocol-only",
            text.contains("fun apply(") || text.contains("fun recover("),
        )
        assertFalse(
            "Public module must not declare an interface or class with side effects",
            text.contains("interface LayoutApplication") && text.contains("fun apply"),
        )
    }

    private fun assertNoForbiddenImports(root: File, message: String) {
        assertTrue("Directory must exist: ${root.absolutePath}", root.exists() && root.isDirectory)
        val files = root.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".kt") }
            .toList()
        assertTrue("At least one .kt file must exist under ${root.absolutePath}", files.isNotEmpty())
        val violations = files.flatMap { file ->
            val text = file.readText()
            forbiddenPrefixes
                .filter { prefix -> prefix in text }
                .map { prefix -> "${file.name}: forbidden prefix '$prefix'" }
        }
        assertTrue(
            "$message — forbidden package prefixes found:\n${violations.joinToString("\n")}",
            violations.isEmpty(),
        )
    }
}
