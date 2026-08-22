package app.lawnchair.organizer.ui

import androidx.annotation.StringRes
import app.lawnchair.organizer.planning.CategoryId
import com.android.launcher3.R

/**
 * Resource-only presentation metadata for the immutable v1 taxonomy. This map
 * does not authorize category IDs: [OrganizerPolicyBundleSource] remains that authority.
 */
internal data class CategoryOverrideCategoryPresentation(
    @StringRes val labelRes: Int,
    @StringRes val descriptionRes: Int,
)

internal object CategoryOverrideCategoryPresentations {
    private val descriptions = R.string.organizer_category_override_category_description

    private val byId = mapOf(
        "ART" to R.string.organizer_category_art,
        "AUTO" to R.string.organizer_category_auto,
        "BEAUTY" to R.string.organizer_category_beauty,
        "BOOKS" to R.string.organizer_category_books,
        "BUSINESS" to R.string.organizer_category_business,
        "COMICS" to R.string.organizer_category_comics,
        "COMMUNICATION" to R.string.organizer_category_communication,
        "DATING" to R.string.organizer_category_dating,
        "EDUCATION" to R.string.organizer_category_education,
        "ENTERTAINMENT" to R.string.organizer_category_entertainment,
        "EVENTS" to R.string.organizer_category_events,
        "FINANCE" to R.string.organizer_category_finance,
        "FOOD" to R.string.organizer_category_food,
        "GAME" to R.string.organizer_category_game,
        "HEALTH" to R.string.organizer_category_health,
        "HOUSE" to R.string.organizer_category_house,
        "LIBRARIES" to R.string.organizer_category_libraries,
        "LIFESTYLE" to R.string.organizer_category_lifestyle,
        "MAPS" to R.string.organizer_category_maps,
        "MEDICAL" to R.string.organizer_category_medical,
        "MUSIC" to R.string.organizer_category_music,
        "NEWS" to R.string.organizer_category_news,
        "OTHER" to R.string.organizer_category_other,
        "PARENTING" to R.string.organizer_category_parenting,
        "PERSONALIZATION" to R.string.organizer_category_personalization,
        "PHOTOGRAPHY" to R.string.organizer_category_photography,
        "PRODUCTIVITY" to R.string.organizer_category_productivity,
        "SHOPPING" to R.string.organizer_category_shopping,
        "SOCIAL" to R.string.organizer_category_social,
        "SPORTS" to R.string.organizer_category_sports,
        "TOOLS" to R.string.organizer_category_tools,
        "TRAVEL" to R.string.organizer_category_travel,
        "VIDEO" to R.string.organizer_category_video,
        "WEATHER" to R.string.organizer_category_weather,
    )

    fun forCategory(category: CategoryId): CategoryOverrideCategoryPresentation = checkNotNull(byId[category.value]) { "No localized presentation mapping for bundle category" }
        .let { CategoryOverrideCategoryPresentation(it, descriptions) }

    internal fun mappedIdsForTest(): Set<String> = byId.keys
}
