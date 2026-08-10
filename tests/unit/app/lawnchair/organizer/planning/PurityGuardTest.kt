package app.lawnchair.organizer.planning

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class PurityGuardTest {

    private val forbiddenPrefixes = listOf(
        "android.",
        "com.android.",
        "androidx.",
        "kotlinx.coroutines",
        "kotlinx.io",
        "java.io",
        "java.net",
        "java.nio",
        "java.sql",
        "javax.sql",
    )

    private val planningDir = File("lawnchair/src/app/lawnchair/organizer/planning")

    @Test
    fun everyProductionPlanningFileIsFreeOfForbiddenPackagePrefixes() {
        assertTrue(
            "Planning source directory must exist: ${planningDir.absolutePath}",
            planningDir.exists() && planningDir.isDirectory,
        )

        val ktFiles = planningDir.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".kt") }
            .toList()

        assertTrue(
            "At least one production .kt file must exist under the planning path",
            ktFiles.isNotEmpty(),
        )

        val violations = ktFiles.flatMap { file ->
            val text = file.readText()
            forbiddenPrefixes
                .filter { prefix -> prefix in text }
                .map { prefix -> "${file.name}: forbidden prefix '$prefix'" }
        }

        assertTrue(
            "Forbidden package prefixes found in production planning sources:\n" +
                violations.joinToString("\n"),
            violations.isEmpty(),
        )
    }
}
