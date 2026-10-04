package app.lawnchair.bugreport

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Contract for the Issue #242 report file save: success persists the file under
 * the hex id directory with the contents as written, failure degrades to null
 * without leaking exceptions (createNewFile collision, or IOException such as
 * dest itself being a regular file).
 */
class ReportFileSaveTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `successful save creates the file under the hex id directory`() {
        val logs = tmp.newFolder("logs")
        val dest = File(logs, "deadbeef")

        val saved = writeReportFile(dest, "Lawnchair bug report 2026-09-07_19-09-17", "contents")

        assertEquals(dest, saved?.parentFile)
        assertEquals("Lawnchair bug report 2026-09-07_19-09-17.txt", saved?.name)
        assertEquals("contents", saved?.readText())
    }

    @Test
    fun `save into an unwritable dest degrades to null without leaking an exception`() {
        val dest = tmp.newFile("dest-is-a-file")

        val saved = writeReportFile(dest, "Lawnchair bug report 2026-09-07_19-09-17", "contents")

        assertNull(saved)
    }

    @Test
    fun `id collision via existing target path degrades to null`() {
        val dest = tmp.newFolder("logs")
        val fileName = "Lawnchair bug report 2026-09-07_19-09-17"
        // A pre-existing entry at the exact target path makes createNewFile() return false.
        File(dest, "$fileName.txt").createNewFile()

        val saved = writeReportFile(dest, fileName, "contents")

        assertNull(saved)
        assertEquals("", File(dest, "$fileName.txt").readText())
    }

    @Test
    fun `createNewFile on an existing path returns false instead of throwing`() {
        // Fixture for the collision branch: createNewFile() must return false (not
        // throw) when the target path exists, which is why the collision branch and
        // the IOException branch are separate (Issue #242 owner review, Blocker 2).
        val existing = tmp.newFile("existing")

        assertFalse(existing.createNewFile())
    }
}
