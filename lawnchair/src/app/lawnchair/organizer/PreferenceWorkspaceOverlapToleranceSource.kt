package app.lawnchair.organizer

import android.content.Context
import app.lawnchair.organizer.planning.WorkspaceOverlapToleranceSource
import app.lawnchair.preferences2.PreferenceManager2
import com.patrykmichalik.opto.core.firstBlocking

/**
 * Issue #185 / ADR-0010 production truth for the overlap acceptance predicate:
 * the same preference the platform model loader consults when it decides
 * whether an item overlapping the QSB reservation survives a load. Read fresh
 * at every evaluation (compose gate, A5 precondition, recovery re-evaluation);
 * never persisted into revisions, digests, or recovery records.
 */
class PreferenceWorkspaceOverlapToleranceSource(context: Context) : WorkspaceOverlapToleranceSource {
    private val appContext = context.applicationContext

    override fun isOverlapTolerated(): Boolean = PreferenceManager2.getInstance(appContext).allowWidgetOverlap.firstBlocking()
}
