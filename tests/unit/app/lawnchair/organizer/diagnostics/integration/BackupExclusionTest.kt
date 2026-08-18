package app.lawnchair.organizer.diagnostics.integration

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * AC-67-11: Backup exclusion — the diagnostics journal resource is absent from
 * the Lawnchair backup allowlist and Android backup scheme.
 *
 * The journal file is an app-private file under context.filesDir/organizer_diagnostics/
 * and is NOT included in:
 * 1. res/xml/backupscheme.xml (Android full-backup content scheme)
 * 2. [app.lawnchair.backup.LawnchairBackup.getFiles()] (Lawnchair ZIP backup)
 */
class BackupExclusionTest {

    /**
     * Verify that the diagnostics journal file is NOT listed in the Android
     * backup scheme (res/xml/backupscheme.xml).
     *
     * The backup scheme explicitly includes only launcher.db, grid DBs,
     * shared preferences, and downgrade_schema.json. The diagnostics journal
     * is a file-based resource under context.filesDir and must NOT be added
     * to this allowlist.
     */
    @Test
    fun journalNotInBackupSchemeXml() {
        val schemeFile = File("res/xml/backupscheme.xml")
        org.junit.Assume.assumeTrue("backupscheme.xml must exist", schemeFile.exists())

        val content = schemeFile.readText(Charsets.UTF_8)

        // The journal file name must not appear in the backup scheme
        assertFalse(
            "organizer_diagnostics.journal must not appear in backup scheme",
            content.contains("organizer_diagnostics"),
        )

        // The journal directory must not appear
        assertFalse(
            "organizer_diagnostics directory must not appear in backup scheme",
            content.contains("organizer_diagnostics"),
        )

        // Verify the expected entries are present (sanity check)
        org.junit.Assert.assertTrue(
            "backupscheme.xml must include launcher.db",
            content.contains("launcher.db"),
        )
        org.junit.Assert.assertTrue(
            "backupscheme.xml must include shared prefs",
            content.contains("com.android.launcher3.prefs.xml"),
        )
    }

    /**
     * Verify that the diagnostics journal file is NOT included in the
     * LawnchairBackup.getFiles() return set.
     *
     * This test reads the source of LawnchairBackup.getFiles() to verify
     * the journal file is not among the explicitly backuped files.
     */
    @Test
    fun journalNotInLawnchairBackupFiles() {
        // LawnchairBackup.getFiles() returns only LAUNCHER_DB_FILE_NAME,
        // PREFS_FILE_NAME, PREFS_DB_FILE_NAME, PREFS_DATASTORE_FILE_NAME.
        // The diagnostics journal is not among them.
        val sourceFile = File(
            "lawnchair/src/app/lawnchair/backup/LawnchairBackup.kt",
        )
        org.junit.Assume.assumeTrue(
            "LawnchairBackup.kt must exist",
            sourceFile.exists(),
        )

        val content = sourceFile.readText(Charsets.UTF_8)

        // The getFiles() method should not reference the journal file
        assertFalse(
            "LawnchairBackup must not include organizer_diagnostics",
            content.contains("organizer_diagnostics"),
        )
    }

    /**
     * Verify that the journal file is NOT named in the backup scheme's
     * database, sharedpref, or file include directives.
     */
    @Test
    fun journalFileNotInAnyIncludeDirective() {
        val schemeFile = File("res/xml/backupscheme.xml")
        org.junit.Assume.assumeTrue("backupscheme.xml must exist", schemeFile.exists())

        val lines = schemeFile.readLines()
        for (line in lines) {
            if (line.contains("organizer_diagnostics")) {
                org.junit.Assert.fail(
                    "Found 'organizer_diagnostics' reference in backup scheme: $line",
                )
            }
        }
    }
}
