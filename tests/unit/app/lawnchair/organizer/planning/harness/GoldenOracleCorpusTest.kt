package app.lawnchair.organizer.planning.harness

import app.lawnchair.organizer.planning.DeterministicOrganizationPlanner
import java.io.File
import java.security.MessageDigest
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

    /**
     * Issue #451 (spec 451 N-7/AC-5): the #451 planner change (duplicate
     * surplus exclusion + warnings) must not affect any fixture that existed
     * before it. The duplicate fixtures added by #451 are excluded from this
     * pin by name — they exist precisely to exercise the new behavior — and
     * every remaining corpus source must still carry its pre-#451 per-source
     * digest. When a future change deliberately adds corpus fixtures, extend
     * `ExampleCorpus.duplicateFixtureSources`-style exclusion here and record
     * the reason in the same PR.
     */
    @Test
    fun everyPre451CorpusSourceKeepsItsPerFixtureDigest() {
        val digests = GoldenOracleCorpus.digestsBySource()
        val pre451 = digests.filter { it.first !in ExampleCorpus.duplicateFixtureSources }

        // Guard the exclusion itself: the #451 fixtures must be present and
        // must be the only sources beyond the pinned pre-#451 set.
        assertEquals(ExampleCorpus.duplicateFixtureSources, digests.map { it.first }.toSet() - pre451.map { it.first }.toSet())

        val canonical = pre451.joinToString("\n") { "${it.first}=${it.second}" }
        val aggregate = MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        assertEquals(PRE451_SOURCE_DIGEST_AGGREGATE, aggregate)
    }

    /**
     * SHA-256 over the newline-joined `source=digest` pairs of every corpus
     * source that existed before #451 (140 sources: example, validation, and
     * generated cases), pinned on `issue-451-spec-plan` at the #451 planning
     * change (commit `c1ba1d1caa`) before the duplicate fixtures were added.
     * Any change to a pre-#451 fixture's plan output breaks this pin.
     */
    private companion object {
        const val PRE451_SOURCE_DIGEST_AGGREGATE = "a8e55b89f638e13869f2dec02ea3c6b85549a2a655bc0234cb68c6604d9c9a20"
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
