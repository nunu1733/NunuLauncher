package app.lawnchair.ui.preferences.destinations

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.lawnchair.organizer.planning.CategoryId
import app.lawnchair.organizer.ui.CategoryOverrideApp
import app.lawnchair.organizer.ui.CategoryOverrideAuthoringCoordinator
import app.lawnchair.organizer.ui.CategoryOverrideAuthoringResult
import app.lawnchair.organizer.ui.CategoryOverrideCategoryPresentations
import app.lawnchair.organizer.ui.CategoryOverrideProfile
import app.lawnchair.ui.preferences.LocalIsExpandedScreen
import app.lawnchair.ui.preferences.components.controls.ClickablePreference
import app.lawnchair.ui.preferences.components.layout.PreferenceLazyColumn
import app.lawnchair.ui.preferences.components.layout.PreferenceScaffold
import app.lawnchair.ui.preferences.components.layout.PreferenceTemplate
import com.android.launcher3.R
import com.google.accompanist.drawablepainter.rememberDrawablePainter
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
    var categoryOptions by remember { mutableStateOf<List<CategoryId>?>(null) }
    var loadResult by remember { mutableStateOf<CategoryOverrideAuthoringResult?>(null) }
    var selectedApp by remember { mutableStateOf<CategoryOverrideApp?>(null) }
    var pendingCategory by remember { mutableStateOf<CategoryId?>(null) }
    var message by remember { mutableStateOf<Int?>(null) }

    fun reload() {
        scope.launch {
            val options = withContext(Dispatchers.IO) { authoring.categories() }
            categoryOptions = options
            loadResult = if (options == null) {
                CategoryOverrideAuthoringResult.TaxonomyUnavailable
            } else {
                withContext(Dispatchers.IO) { authoring.load() }
            }
        }
    }

    LaunchedEffect(Unit) { reload() }
    LaunchedEffect(selectedApp, message) {
        if (selectedApp == null) focusRequester.requestFocus()
    }

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
                                OverrideAppPreference(
                                    app = candidate,
                                    onClick = {
                                        selectedApp = candidate
                                        pendingCategory = candidate.assignedCategory
                                        message = null
                                    },
                                )
                            }
                        }
                    } else {
                        item { SelectedOverrideAppHeader(app) }
                        item {
                            Text(
                                text = pendingCategory?.let {
                                    stringResource(
                                        R.string.organizer_category_override_explicit,
                                        categoryLabel(it),
                                    )
                                } ?: stringResource(R.string.organizer_category_override_automatic),
                            )
                        }
                        item {
                            ClickablePreference(
                                label = stringResource(R.string.organizer_category_override_use_automatic),
                                subtitle = stringResource(R.string.organizer_category_override_automatic_description),
                                onClick = { pendingCategory = null },
                            )
                        }
                        categoryOptions.orEmpty().forEach { category ->
                            item(key = category.value) {
                                val presentation = CategoryOverrideCategoryPresentations.forCategory(category)
                                ClickablePreference(
                                    label = stringResource(presentation.labelRes),
                                    subtitle = stringResource(presentation.descriptionRes, stringResource(presentation.labelRes)),
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

                                            CategoryOverrideAuthoringResult.OrganizationRunActive -> {
                                                message = R.string.organizer_category_override_busy
                                                selectedApp = null
                                                reload()
                                            }

                                            CategoryOverrideAuthoringResult.TargetUnavailable -> {
                                                message = R.string.organizer_category_override_unavailable
                                                selectedApp = null
                                                reload()
                                            }

                                            CategoryOverrideAuthoringResult.Conflict -> {
                                                message = R.string.organizer_category_override_conflict
                                                selectedApp = null
                                                reload()
                                            }

                                            CategoryOverrideAuthoringResult.InvalidCategory,
                                            CategoryOverrideAuthoringResult.TaxonomyUnavailable,
                                            CategoryOverrideAuthoringResult.StoreUnreadable,
                                            CategoryOverrideAuthoringResult.UnsupportedSchema,
                                            CategoryOverrideAuthoringResult.MigrationBarrierUncertain,
                                            CategoryOverrideAuthoringResult.WriteFailed,
                                            CategoryOverrideAuthoringResult.VerificationFailed,
                                            -> {
                                                message = R.string.organizer_category_override_failed
                                                selectedApp = null
                                                reload()
                                            }

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
                                    pendingCategory = null
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
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
            }
        }
    }
}

@Composable
private fun OverrideAppPreference(
    app: CategoryOverrideApp,
    onClick: () -> Unit,
) {
    val label = appLabel(app)
    val profile = profileLabel(app.profile)
    val state = app.assignedCategory?.let {
        stringResource(R.string.organizer_category_override_explicit, categoryLabel(it))
    } ?: stringResource(R.string.organizer_category_override_automatic)
    PreferenceTemplate(
        title = { Text(label) },
        description = { Text("$profile · $state") },
        startWidget = app.icon?.let { icon ->
            {
                Image(
                    painter = rememberDrawablePainter(icon),
                    contentDescription = null,
                    modifier = Modifier.size(30.dp),
                )
            }
        },
        modifier = Modifier
            .heightIn(min = 48.dp)
            .clickable(onClick = onClick)
            .semantics { contentDescription = "$label, $profile, $state" },
        verticalPadding = 12.dp,
    )
}

@Composable
private fun SelectedOverrideAppHeader(app: CategoryOverrideApp) {
    val label = appLabel(app)
    val profile = profileLabel(app.profile)
    PreferenceTemplate(
        title = { Text(label) },
        description = { Text(profile) },
        startWidget = app.icon?.let { icon ->
            {
                Image(
                    painter = rememberDrawablePainter(icon),
                    contentDescription = null,
                    modifier = Modifier.size(30.dp),
                )
            }
        },
        modifier = Modifier.semantics { contentDescription = "$label, $profile" },
        verticalPadding = 12.dp,
    )
}

@Composable
private fun appLabel(app: CategoryOverrideApp): String = app.label ?: stringResource(R.string.organizer_category_override_unnamed_app)

@Composable
private fun profileLabel(profile: CategoryOverrideProfile): String = stringResource(
    when (profile) {
        CategoryOverrideProfile.PERSONAL -> R.string.organizer_category_override_profile_personal
        CategoryOverrideProfile.WORK -> R.string.organizer_category_override_profile_work
    },
)

@Composable
private fun categoryLabel(category: CategoryId): String = stringResource(CategoryOverrideCategoryPresentations.forCategory(category).labelRes)
