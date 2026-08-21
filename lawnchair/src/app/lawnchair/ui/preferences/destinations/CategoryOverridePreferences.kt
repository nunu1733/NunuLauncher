package app.lawnchair.ui.preferences.destinations

import androidx.compose.foundation.focusable
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import app.lawnchair.organizer.planning.CategoryId
import app.lawnchair.organizer.ui.CategoryOverrideApp
import app.lawnchair.organizer.ui.CategoryOverrideAuthoringCoordinator
import app.lawnchair.organizer.ui.CategoryOverrideAuthoringResult
import app.lawnchair.ui.preferences.LocalIsExpandedScreen
import app.lawnchair.ui.preferences.components.controls.ClickablePreference
import app.lawnchair.ui.preferences.components.layout.PreferenceLazyColumn
import app.lawnchair.ui.preferences.components.layout.PreferenceScaffold
import com.android.launcher3.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Issue #99 local-only, profile-scoped S1 override authoring surface. */
@Composable
internal fun CategoryOverridePreferences(
    modifier: Modifier = Modifier,
    coordinator: CategoryOverrideAuthoringCoordinator? = null,
) {
    val context = LocalContext.current
    val authoring = coordinator ?: remember { CategoryOverrideAuthoringCoordinator(context) }
    val scope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }
    val categories = remember { authoring.categories().orEmpty() }
    var loadResult by remember { mutableStateOf<CategoryOverrideAuthoringResult?>(null) }
    var selectedApp by remember { mutableStateOf<CategoryOverrideApp?>(null) }
    var pendingCategory by remember { mutableStateOf<CategoryId?>(null) }
    var message by remember { mutableStateOf<Int?>(null) }

    fun reload() {
        scope.launch {
            val result = withContext(Dispatchers.IO) { authoring.load() }
            loadResult = result
        }
    }

    LaunchedEffect(Unit) { reload() }

    PreferenceScaffold(
        label = stringResource(R.string.organizer_category_overrides_title),
        modifier = modifier,
        isExpandedScreen = LocalIsExpandedScreen.current,
    ) { paddingValues ->
        PreferenceLazyColumn(paddingValues) {
            item {
                Text(
                    text = message?.let { stringResource(it) }
                        ?: stringResource(R.string.organizer_category_overrides_summary),
                    modifier = Modifier
                        .focusRequester(focusRequester)
                        .focusable()
                        .semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            when (val result = loadResult) {
                null -> item { Text(stringResource(R.string.all_apps_loading_message)) }

                is CategoryOverrideAuthoringResult.Loaded -> {
                    val app = selectedApp
                    if (app == null) {
                        result.apps.forEach { candidate ->
                            item(key = "${candidate.key.profile.value}:${candidate.key.packageName.value}") {
                                val state = candidate.assignedCategory?.let { categoryDisplayName(it) }
                                ClickablePreference(
                                    label = candidate.label,
                                    subtitle = candidate.assignedCategory?.let {
                                        stringResource(R.string.organizer_category_override_explicit, state ?: it.value)
                                    } ?: stringResource(R.string.organizer_category_override_automatic),
                                    onClick = {
                                        selectedApp = candidate
                                        pendingCategory = candidate.assignedCategory
                                        message = null
                                    },
                                )
                            }
                        }
                    } else {
                        item {
                            Text(
                                text = app.label,
                                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                            )
                        }
                        item {
                            Text(
                                text = pendingCategory?.let {
                                    stringResource(R.string.organizer_category_override_explicit, categoryDisplayName(it))
                                } ?: stringResource(R.string.organizer_category_override_automatic),
                            )
                        }
                        item {
                            ClickablePreference(
                                label = stringResource(R.string.organizer_category_override_use_automatic),
                                onClick = { pendingCategory = null },
                            )
                        }
                        categories.forEach { category ->
                            item(key = category.value) {
                                ClickablePreference(
                                    label = categoryDisplayName(category),
                                    subtitle = if (pendingCategory == category) {
                                        stringResource(R.string.organizer_category_override_choose)
                                    } else {
                                        null
                                    },
                                    onClick = { pendingCategory = category },
                                )
                            }
                        }
                        item {
                            ClickablePreference(
                                label = stringResource(R.string.organizer_category_override_save),
                                onClick = {
                                    scope.launch {
                                        val saveResult = withContext(Dispatchers.IO) {
                                            authoring.save(app, pendingCategory)
                                        }
                                        when (saveResult) {
                                            is CategoryOverrideAuthoringResult.Saved -> {
                                                message = R.string.organizer_category_override_saved
                                                selectedApp = null
                                                reload()
                                            }

                                            CategoryOverrideAuthoringResult.OrganizationRunActive -> message = R.string.organizer_category_override_busy

                                            CategoryOverrideAuthoringResult.TargetUnavailable -> message = R.string.organizer_category_override_unavailable

                                            CategoryOverrideAuthoringResult.Conflict -> message = R.string.organizer_category_override_conflict

                                            CategoryOverrideAuthoringResult.InvalidCategory,
                                            CategoryOverrideAuthoringResult.TaxonomyUnavailable,
                                            CategoryOverrideAuthoringResult.StoreUnreadable,
                                            CategoryOverrideAuthoringResult.UnsupportedSchema,
                                            CategoryOverrideAuthoringResult.MigrationBarrierUncertain,
                                            CategoryOverrideAuthoringResult.WriteFailed,
                                            CategoryOverrideAuthoringResult.VerificationFailed,
                                            -> message = R.string.organizer_category_override_failed

                                            is CategoryOverrideAuthoringResult.Loaded -> error("load result is not a save result")
                                        }
                                    }
                                },
                            )
                        }
                        item {
                            ClickablePreference(
                                label = stringResource(R.string.organizer_category_override_cancel),
                                onClick = {
                                    selectedApp = null
                                    message = null
                                },
                            )
                        }
                    }
                }

                else -> item {
                    Text(
                        text = when (result) {
                            CategoryOverrideAuthoringResult.TargetUnavailable -> stringResource(R.string.organizer_category_override_unavailable)
                            CategoryOverrideAuthoringResult.Conflict -> stringResource(R.string.organizer_category_override_conflict)
                            else -> stringResource(R.string.organizer_category_override_unavailable_store)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun categoryDisplayName(category: CategoryId): String = stringResource(
    when (category.value) {
        "ART" -> R.string.organizer_category_art
        "AUTO" -> R.string.organizer_category_auto
        "BEAUTY" -> R.string.organizer_category_beauty
        "BOOKS" -> R.string.organizer_category_books
        "BUSINESS" -> R.string.organizer_category_business
        "COMICS" -> R.string.organizer_category_comics
        "COMMUNICATION" -> R.string.organizer_category_communication
        "DATING" -> R.string.organizer_category_dating
        "EDUCATION" -> R.string.organizer_category_education
        "ENTERTAINMENT" -> R.string.organizer_category_entertainment
        "EVENTS" -> R.string.organizer_category_events
        "FINANCE" -> R.string.organizer_category_finance
        "FOOD" -> R.string.organizer_category_food
        "GAME" -> R.string.organizer_category_game
        "HEALTH" -> R.string.organizer_category_health
        "HOUSE" -> R.string.organizer_category_house
        "LIBRARIES" -> R.string.organizer_category_libraries
        "LIFESTYLE" -> R.string.organizer_category_lifestyle
        "MAPS" -> R.string.organizer_category_maps
        "MEDICAL" -> R.string.organizer_category_medical
        "MUSIC" -> R.string.organizer_category_music
        "NEWS" -> R.string.organizer_category_news
        "OTHER" -> R.string.organizer_category_other
        "PARENTING" -> R.string.organizer_category_parenting
        "PERSONALIZATION" -> R.string.organizer_category_personalization
        "PHOTOGRAPHY" -> R.string.organizer_category_photography
        "PRODUCTIVITY" -> R.string.organizer_category_productivity
        "SHOPPING" -> R.string.organizer_category_shopping
        "SOCIAL" -> R.string.organizer_category_social
        "SPORTS" -> R.string.organizer_category_sports
        "TOOLS" -> R.string.organizer_category_tools
        "TRAVEL" -> R.string.organizer_category_travel
        "VIDEO" -> R.string.organizer_category_video
        "WEATHER" -> R.string.organizer_category_weather
        else -> R.string.organizer_category_override_unavailable_store
    },
)
