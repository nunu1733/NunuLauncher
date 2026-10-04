/*
 * Copyright 2026, NunuLauncher
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package app.lawnchair.migration

import com.android.launcher3.LauncherFiles
import org.junit.Assert
import org.junit.Test

class DeckRetirementArtifactNamesTest {

    @Test
    fun singleBasenameProducesFourNamesInCorrectOrder() {
        val names = DeckRetirementMigration.recognizedArtifactNames(listOf("launcher.db"))
        Assert.assertEquals(4, names.size)
        Assert.assertEquals("bk_launcher.db", names[0])
        Assert.assertEquals("bk_launcher.db-journal", names[1])
        Assert.assertEquals("lawndeck_launcher.db", names[2])
        Assert.assertEquals("lawndeck_launcher.db-journal", names[3])
    }

    @Test
    fun multipleBasenamesProduceFourTimesNNames() {
        val basenames = listOf("a.db", "b.db", "c.db")
        val names = DeckRetirementMigration.recognizedArtifactNames(basenames)
        Assert.assertEquals(12, names.size)
        Assert.assertEquals("bk_a.db", names[0])
        Assert.assertEquals("bk_a.db-journal", names[1])
        Assert.assertEquals("lawndeck_a.db", names[2])
        Assert.assertEquals("lawndeck_a.db-journal", names[3])
        Assert.assertEquals("bk_b.db", names[4])
        Assert.assertEquals("bk_b.db-journal", names[5])
        Assert.assertEquals("lawndeck_b.db", names[6])
        Assert.assertEquals("lawndeck_b.db-journal", names[7])
        Assert.assertEquals("bk_c.db", names[8])
        Assert.assertEquals("bk_c.db-journal", names[9])
        Assert.assertEquals("lawndeck_c.db", names[10])
        Assert.assertEquals("lawndeck_c.db-journal", names[11])
    }

    @Test
    fun emptyInputProducesEmptyOutput() {
        val names = DeckRetirementMigration.recognizedArtifactNames(emptyList())
        Assert.assertTrue(names.isEmpty())
    }

    @Test
    fun gridDbFilesActualValues() {
        val gridFiles = LauncherFiles.GRID_DB_FILES.toList()
        val names = DeckRetirementMigration.recognizedArtifactNames(gridFiles)
        Assert.assertEquals(gridFiles.size * 4, names.size)
        Assert.assertTrue(names.contains("bk_launcher.db"))
        Assert.assertTrue(names.contains("bk_launcher.db-journal"))
        Assert.assertTrue(names.contains("lawndeck_launcher.db"))
        Assert.assertTrue(names.contains("lawndeck_launcher.db-journal"))
        Assert.assertTrue(names.contains("bk_launcher_6_by_5.db"))
        Assert.assertTrue(names.contains("bk_launcher_6_by_5.db-journal"))
        Assert.assertTrue(names.contains("lawndeck_launcher_6_by_5.db"))
        Assert.assertTrue(names.contains("lawndeck_launcher_6_by_5.db-journal"))
        Assert.assertTrue(names.contains("bk_launcher_4_by_4.db"))
        Assert.assertTrue(names.contains("bk_launcher_4_by_4.db-journal"))
        Assert.assertTrue(names.contains("lawndeck_launcher_4_by_4.db"))
        Assert.assertTrue(names.contains("lawndeck_launcher_4_by_4.db-journal"))
    }

    @Test
    fun noUnknownNames() {
        val gridFiles = LauncherFiles.GRID_DB_FILES.toList()
        val names = DeckRetirementMigration.recognizedArtifactNames(gridFiles)
        for (name in names) {
            val startsWithBk = name.startsWith("bk_")
            val startsWithLawndeck = name.startsWith("lawndeck_")
            Assert.assertTrue(
                "Unexpected name: $name",
                startsWithBk || startsWithLawndeck,
            )
            val endsWithDb = name.endsWith(".db")
            val endsWithJournal = name.endsWith(".db-journal")
            Assert.assertTrue(
                "Unexpected suffix: $name",
                endsWithDb || endsWithJournal,
            )
        }
    }

    @Test
    fun journalSuffixCorrectness() {
        val basenames = listOf("x.db", "y.db")
        val names = DeckRetirementMigration.recognizedArtifactNames(basenames)
        Assert.assertEquals("bk_x.db-journal", names[1])
        Assert.assertEquals("lawndeck_x.db-journal", names[3])
        Assert.assertEquals("bk_y.db-journal", names[5])
        Assert.assertEquals("lawndeck_y.db-journal", names[7])
        for (name in names) {
            if (name.endsWith("-journal")) {
                val base = name.removeSuffix("-journal")
                Assert.assertTrue(
                    "Journal $name has no matching non-journal entry",
                    names.contains(base),
                )
            }
        }
    }
}
