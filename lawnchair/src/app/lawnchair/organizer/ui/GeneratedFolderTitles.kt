package app.lawnchair.organizer.ui

import android.content.Context
import app.lawnchair.organizer.application.public.FolderTitleResolver
import app.lawnchair.organizer.planning.FolderNaming
import com.android.launcher3.R

/**
 * Production [FolderTitleResolver] for Organizer-generated folders (Issue
 * #201). Projects the v1 taxonomy category into its localized label and falls
 * back to a generic title for content the presentation map does not know —
 * via the total lookup, never by catching exceptions and never by exposing a
 * raw category ID.
 *
 * Resolution happens once per planned folder inside the application
 * materializer, so the returned string is the creation-time locale snapshot
 * that preview and apply both persist. Lives in the UI layer because it reads
 * Android resources; the outer composition (LawnchairApp) injects it into
 * [app.lawnchair.organizer.application.protocol.LayoutApplicationModule].
 */
object GeneratedFolderTitles {

    /**
     * Indirection over resource lookup so unit tests can verify the total
     * lookup / fallback policy without actual Android resources.
     */
    fun interface StringProvider {
        fun string(resId: Int): String
    }

    fun resolver(context: Context): FolderTitleResolver {
        val appContext = context.applicationContext
        return resolver { resId -> appContext.getString(resId) }
    }

    fun resolver(stringProvider: StringProvider): FolderTitleResolver = FolderTitleResolver { naming ->
        when (naming) {
            is FolderNaming.FromCategory ->
                CategoryOverrideCategoryPresentations.findForCategory(naming.category)
                    ?.let { stringProvider.string(it.labelRes) }
                    ?: stringProvider.string(R.string.organizer_generated_folder_fallback_name)
        }
    }
}
