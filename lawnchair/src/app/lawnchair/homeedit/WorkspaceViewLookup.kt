package app.lawnchair.homeedit

import android.view.View
import com.android.launcher3.Workspace
import com.android.launcher3.model.data.ItemInfo

/**
 * Rebase Phase 2 adapt: anchor's Workspace lost the fork-era
 * `getHomescreenIconByItemId` lookup; restore it as extensions so the
 * direct-edit/homeedit contract keeps working unchanged.
 */
fun Workspace<*>.getHomescreenIconByItemId(info: ItemInfo): View? = getViewByItemId(info.id)

fun Workspace<*>.getHomescreenIconByItemId(itemId: Int): View? = getViewByItemId(itemId)
