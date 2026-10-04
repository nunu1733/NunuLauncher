/*
 * Issue #497: one-shot fallback notice surfaced on the settings row. When
 * the destination policy falls back to the upstream default, the typed
 * reason is stored here and consumed (cleared) exactly once when the
 * settings row next displays it — the user is told once per fallback, not
 * nagged (ADR-0015 Decision 4/5, spec 497). The reason key never carries a
 * package name (organizer-diagnostics §7 Never classification). Plain
 * SharedPreferences (Utilities.getPrefs) — no change listener is needed for
 * a one-shot store.
 */
package app.lawnchair.homeedit

import android.content.Context
import com.android.launcher3.Utilities

object AppDestinationNotice {

    private const val KEY_PENDING = "pref_new_app_destination_pending_notice"

    fun postPending(context: Context, reasonKey: String) {
        Utilities.getPrefs(context).edit().putString(KEY_PENDING, reasonKey).apply()
    }

    /**
     * 設定行の表示時に一度だけ消費する。戻り値は表示すべき理由キー
     * （フォールバックが無ければnull）。
     */
    fun consumePending(context: Context): String? {
        val prefs = Utilities.getPrefs(context)
        val pending = prefs.getString(KEY_PENDING, null) ?: return null
        prefs.edit().remove(KEY_PENDING).apply()
        return pending
    }
}
