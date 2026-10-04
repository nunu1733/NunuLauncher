package app.lawnchair.organizer.application.store

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Recovery DB only. Creates format 1, enables WAL, configures
 * `PRAGMA synchronous=FULL`, and never touches Launcher DB. There is no
 * automatic downgrade path — see [RecoveryDbVersionGate].
 *
 * Issue #14 Stage B step 3.
 */
internal class RecoveryDbHelper(
    context: Context,
) : SQLiteOpenHelper(
    context,
    RecoveryDbSchema.FILE_NAME,
    /* factory = */
    null,
    RecoveryDbSchema.FORMAT_VERSION,
) {

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.enableWriteAheadLogging()
        db.execSQL("PRAGMA synchronous=FULL")
    }

    override fun onCreate(db: SQLiteDatabase) {
        for (stmt in RecoveryDbSchema.DDL_FORMAT_1.trimIndent().split(";")) {
            val trimmed = stmt.trim()
            if (trimmed.isNotEmpty()) {
                db.execSQL(trimmed)
            }
        }
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Future-compatible migrations of the recovery DB live here. Format 1
        // is the initial version; no upgrade path is needed yet.
        if (oldVersion != newVersion) {
            throw UnsupportedOperationException(
                "Recovery DB upgrade from $oldVersion to $newVersion is not implemented",
            )
        }
    }

    override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        throw UnsupportedOperationException(
            "Recovery DB downgrade is not supported; use RecoveryDbVersionGate to reject newer formats",
        )
    }
}
