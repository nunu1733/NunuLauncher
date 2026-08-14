package app.lawnchair.organizer.application.store

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import java.io.File

/**
 * Pre-open gate that reads `PRAGMA user_version` from the recovery DB file in
 * read-only mode. Avoids invoking [android.database.sqlite.SQLiteOpenHelper]
 * on a newer-format DB and the destructive `onDowngrade` path.
 *
 * Spec §“Recovery DB lifecycle”: incompatible format returns
 * `INCOMPATIBLE_VERSION` without touching Launcher state.
 *
 * Issue #14 Stage B step 3.
 */
object RecoveryDbVersionGate {

    sealed interface VersionDecision {
        data object CreateNew : VersionDecision
        data class OpenExisting(val version: Int) : VersionDecision
        data class Incompatible(val version: Int) : VersionDecision
        data class ReadFailed(val cause: Throwable) : VersionDecision
    }

    fun probe(file: File): VersionDecision {
        if (!file.exists() || file.length() == 0L) {
            return VersionDecision.CreateNew
        }
        val db: SQLiteDatabase = try {
            SQLiteDatabase.openDatabase(
                file.absolutePath,
                /* factory = */
                null,
                SQLiteDatabase.OPEN_READONLY,
            )
        } catch (e: SQLiteException) {
            return VersionDecision.ReadFailed(e)
        }
        return try {
            val cursor = db.rawQuery("PRAGMA user_version", null)
            val version = cursor.use {
                if (!it.moveToFirst()) 0 else it.getInt(0)
            }
            when {
                version == RecoveryDbSchema.FORMAT_VERSION -> VersionDecision.OpenExisting(version)
                else -> VersionDecision.Incompatible(version)
            }
        } catch (e: SQLiteException) {
            VersionDecision.ReadFailed(e)
        } finally {
            db.close()
        }
    }
}
