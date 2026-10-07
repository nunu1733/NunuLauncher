package app.lawnchair.ui.preferences.components.search

import android.os.Build
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import app.lawnchair.preferences.PreferenceAdapter
import app.lawnchair.search.algorithms.engine.provider.web.CustomWebSearchProvider
import app.lawnchair.search.algorithms.engine.provider.web.LanNetworkContract
import app.lawnchair.search.algorithms.engine.provider.web.SuggestionFetchOutcome
import app.lawnchair.search.algorithms.engine.provider.web.SuggestionUrlCategory
import app.lawnchair.search.algorithms.engine.provider.web.SuggestionUrlClassifier
import app.lawnchair.search.algorithms.engine.provider.web.WebSearchProvider
import app.lawnchair.ui.preferences.components.controls.ListPreference
import app.lawnchair.ui.preferences.components.controls.ListPreferenceEntry
import app.lawnchair.ui.preferences.components.layout.PreferenceTemplate
import app.lawnchair.util.openAppPermissionSettings
import com.android.launcher3.R
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.google.accompanist.permissions.shouldShowRationale

@Composable
fun WebSearchProvider(
    adapter: PreferenceAdapter<WebSearchProvider>,
    nameAdapter: PreferenceAdapter<String>,
    urlAdapter: PreferenceAdapter<String>,
    suggestionsUrlAdapter: PreferenceAdapter<String>,
    modifier: Modifier = Modifier,
) {
    val entries = remember {
        WebSearchProvider.values().map { mode ->
            ListPreferenceEntry(
                value = mode,
                label = { stringResource(id = mode.label) },
            )
        }
    }

    Column(modifier) {
        ListPreference(
            adapter = adapter,
            entries = entries,
            label = stringResource(R.string.allapps_web_suggestion_provider_label),
        )
        if (adapter.state.value == WebSearchProvider.fromString("custom")) {
            SearchPopupPreference(
                title = stringResource(R.string.custom_search_label),
                initialValue = nameAdapter.state.value,
                placeholder = stringResource(R.string.custom_search_input_placeholder),
                onConfirm = nameAdapter::onChange,
                isErrorCheck = { it.isEmpty() },
            )
            SearchPopupPreference(
                title = stringResource(R.string.custom_search_url),
                initialValue = urlAdapter.state.value,
                placeholder = stringResource(R.string.custom_search_input_placeholder),
                hint = stringResource(R.string.custom_search_input_hint),
                onConfirm = urlAdapter::onChange,
                modifier = Modifier,
            )
            val suggestionsTemplate = suggestionsUrlAdapter.state.value
            SearchPopupPreference(
                title = stringResource(R.string.custom_search_suggestions_url),
                initialValue = suggestionsTemplate,
                placeholder = stringResource(R.string.custom_search_input_placeholder),
                hint = stringResource(R.string.custom_search_suggestions_hint),
                onConfirm = suggestionsUrlAdapter::onChange,
                modifier = Modifier,
                // Issue #528: validity is judged by the same classifier the
                // fetch guard uses (shared canonicalization), so the dialog's
                // error verdict can never diverge from the typed guidance.
                isErrorCheck = { SuggestionUrlClassifier.classify(it) == SuggestionUrlCategory.INVALID },
            )
            // Issue #528 review round 1: the zero-write template-change hook
            // must run for blank templates too, so `A → blank → A` re-clears
            // the outcome instead of resurfacing the stale failure. It lives
            // in the custom-provider scope, before/independent of the
            // non-blank condition; the status UI itself stays hidden while
            // the template is blank.
            LaunchedEffect(suggestionsTemplate) {
                CustomWebSearchProvider.onSuggestionsTemplateChanged(suggestionsTemplate)
            }
            if (suggestionsTemplate.isNotBlank()) {
                CustomSuggestionStatus(template = suggestionsTemplate)
            }
        }
    }
}

/**
 * Issue #528: typed inline guidance under the custom suggestions URL row.
 *
 * Shows (a) a typed message for non-HTTPS / invalid templates (the same
 * classifier verdict the fetch guard uses), (b) the provider's in-memory typed
 * fetch outcome for the current template as a status line, and (c) — only for
 * statically-local templates on SDK 37+ — the ACCESS_LOCAL_NETWORK guidance.
 */
@Composable
private fun CustomSuggestionStatus(
    template: String,
    modifier: Modifier = Modifier,
) {
    // The template-change hook does not live here (Issue #528 review round 1):
    // this composable is only composed for non-blank templates, so a hook here
    // would miss `A → blank`. It runs in the parent custom-provider scope.

    val category = remember(template) { SuggestionUrlClassifier.classify(template) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
    ) {
        if (category == SuggestionUrlCategory.INVALID) {
            Text(
                text = stringResource(invalidTemplateMessageRes(template)),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(modifier = Modifier.height(4.dp))
        }

        // Typed fetch outcome status line (memory-only, cleared on change).
        Text(
            text = stringResource(CustomWebSearchProvider.lastOutcome.toDisplayRes()),
            style = MaterialTheme.typography.bodySmall,
        )

        if (category == SuggestionUrlCategory.STATICALLY_LOCAL &&
            Build.VERSION.SDK_INT >= LanNetworkContract.SDK_CINNAMON_BUN
        ) {
            Spacer(modifier = Modifier.height(4.dp))
            LanPermissionGuidance()
        }
    }
}

/**
 * Typed supporting message for an invalid template: a non-http(s) scheme gets
 * the dedicated non-HTTPS message, everything else invalid the generic one.
 */
@StringRes
private fun invalidTemplateMessageRes(template: String): Int {
    val scheme = SuggestionUrlClassifier.schemeOf(template)
    return if (scheme != null && scheme != "http" && scheme != "https") {
        R.string.custom_suggestion_url_not_https
    } else {
        R.string.custom_suggestion_url_invalid
    }
}

@StringRes
private fun SuggestionFetchOutcome.toDisplayRes(): Int = when (this) {
    SuggestionFetchOutcome.NOT_RUN -> R.string.custom_suggestion_status_not_run
    SuggestionFetchOutcome.SUCCESS -> R.string.custom_suggestion_status_success
    SuggestionFetchOutcome.BLOCKED_BY_PERMISSION -> R.string.custom_suggestion_status_blocked_by_permission
    SuggestionFetchOutcome.CLEARTEXT_BLOCKED -> R.string.custom_suggestion_status_cleartext_blocked
    SuggestionFetchOutcome.TLS_CT_FAILURE -> R.string.custom_suggestion_status_tls_ct_failure
    SuggestionFetchOutcome.HTTP_ERROR -> R.string.custom_suggestion_status_http_error
    SuggestionFetchOutcome.GENERIC_NETWORK_FAILURE -> R.string.custom_suggestion_status_generic_network_failure
}

/**
 * ACCESS_LOCAL_NETWORK guidance for statically-local suggestion templates
 * (Issue #528).
 *
 * The permission state is derived only from observable platform information:
 * granted / rationale-required / denied-no-rationale. First-time requests and
 * permanent denials are indistinguishable via public APIs, so the default
 * display is the normal request button — a first-time user is never sent to
 * Settings. The switch to the app-permission-settings guidance happens only
 * within the same session, after a request was launched in that session and
 * rationale stopped showing. Nothing persists across processes (zero-write);
 * a fresh process starts with the plain request button again.
 *
 * The session flag is deliberately process-local `remember`, NOT
 * `rememberSaveable` (Issue #528 review round 1): `rememberSaveable` restores
 * through saved instance state across a system-initiated process death, which
 * would carry the previous process's request history into the new process and
 * could resurface the Settings guidance there, violating the accepted
 * "同一sessionのみ・zero-write" contract. Accepted consequence: a configuration
 * change / activity recreation also resets the in-session history. That is
 * strictly narrower — the UI can only fall back to the plain request button
 * again, it can never show stale settings-guidance — which is the safe side
 * of the contract.
 */
@OptIn(ExperimentalPermissionsApi::class)
@Composable
private fun LanPermissionGuidance(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val permissionState = rememberPermissionState(LanNetworkContract.PERMISSION_ACCESS_LOCAL_NETWORK)
    var requestedThisSession by remember { mutableStateOf(false) }

    val granted = permissionState.status.isGranted
    val rationaleRequired = !granted && permissionState.status.shouldShowRationale
    val preferSettingsGuidance = !granted && !permissionState.status.shouldShowRationale && requestedThisSession

    Column(modifier = modifier) {
        when {
            granted -> Text(
                text = stringResource(R.string.custom_suggestion_lan_granted),
                style = MaterialTheme.typography.bodySmall,
            )

            rationaleRequired -> {
                Text(
                    text = stringResource(R.string.custom_suggestion_lan_rationale),
                    style = MaterialTheme.typography.bodySmall,
                )
                Button(
                    onClick = {
                        requestedThisSession = true
                        permissionState.launchPermissionRequest()
                    },
                ) {
                    Text(text = stringResource(R.string.custom_suggestion_lan_request_button))
                }
            }

            preferSettingsGuidance -> {
                Text(
                    text = stringResource(R.string.custom_suggestion_lan_denied_guidance),
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedButton(
                    onClick = { context.openAppPermissionSettings() },
                ) {
                    Text(text = stringResource(R.string.custom_suggestion_lan_open_settings_button))
                }
            }

            // denied-no-rationale default: normal request button.
            else -> Button(
                onClick = {
                    requestedThisSession = true
                    permissionState.launchPermissionRequest()
                },
            ) {
                Text(text = stringResource(R.string.custom_suggestion_lan_request_button))
            }
        }
    }
}

@Composable
fun SearchPopupPreference(
    title: String,
    initialValue: String,
    placeholder: String,
    onConfirm: (String) -> Unit,
    modifier: Modifier = Modifier,
    hint: String? = null,
    isErrorCheck: (String) -> Boolean = { it.isEmpty() || !it.contains("%s") },
) {
    var showPopup by remember { mutableStateOf(false) }
    var value by remember { mutableStateOf(TextFieldValue(initialValue)) }

    if (showPopup) {
        AlertDialog(
            onDismissRequest = { showPopup = false },
            confirmButton = {
                Button(
                    onClick = {
                        showPopup = false
                        onConfirm(value.text)
                    },
                ) {
                    Text(text = stringResource(id = android.R.string.ok))
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = {
                        showPopup = false
                    },
                ) {
                    Text(text = stringResource(id = android.R.string.cancel))
                }
            },
            title = {
                Text(title)
            },
            text = {
                Column {
                    OutlinedTextField(
                        value = value,
                        onValueChange = { value = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        isError = isErrorCheck(value.text),
                        placeholder = {
                            Text(placeholder)
                        },
                    )
                    if (hint != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(hint)
                    }
                }
            },
        )
    }

    PreferenceTemplate(
        modifier = modifier.clickable {
            showPopup = true
        },
        contentModifier = Modifier
            .fillMaxHeight()
            .padding(vertical = 16.dp)
            .padding(start = 16.dp),
        title = { Text(text = title) },
        description = { Text(initialValue) },
        applyPaddings = false,
    )
}
