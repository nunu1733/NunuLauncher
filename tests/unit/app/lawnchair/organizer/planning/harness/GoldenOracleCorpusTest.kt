package app.lawnchair.organizer.planning.harness

import app.lawnchair.organizer.planning.DeterministicOrganizationPlanner
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spec 182 byte-equivalence gate (child 2 and child 3).
 *
 * The digest under `tests/unit/resources/planner-golden-corpus/sha256.txt` is
 * pinned from the accepted pre-#182 baseline commit (`5fdab48082`, recorded in
 * spec 182). Both the selection child (registry/adapter dispatch) and the
 * extraction child must reproduce it over the accepted corpus: example
 * fixtures, validation fixtures, and the generated property corpus. The
 * strategy echo is excluded from the payload — it is metadata, not layout
 * observation.
 *
 * Regeneration on a baseline checkout:
 * `./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests '*GoldenOracleCorpusTest*' -Dgolden.write=true`
 */
class GoldenOracleCorpusTest {

    @Test
    fun planPayloadDigestMatchesThePinnedPre182Baseline() {
        val digest = GoldenOracleCorpus.digestOf(GoldenOracleCorpus.planAll().map { it.second })
        if (System.getProperty("golden.write") == "true") {
            val file = goldenFile()
            file.parentFile.mkdirs()
            file.writeText(digest + "\n")
            println("golden digest written: $digest")
            return
        }
        assertTrue("golden digest file is missing", goldenFile().exists())
        assertEquals(goldenFile().readText().trim(), digest)
    }

    private fun goldenFile(): File {
        var dir: File? = File(System.getProperty("user.dir"))
        while (dir != null && !File(dir, "tests/unit").isDirectory) {
            dir = dir.parentFile
        }
        checkNotNull(dir) { "repository root not found from ${System.getProperty("user.dir")}" }
        return File(dir, "tests/unit/resources/planner-golden-corpus/sha256.txt")
    }
}
