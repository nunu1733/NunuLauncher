/*
 * Copyright 2022, Lawnchair
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package app.lawnchair.ui.preferences.destinations

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lawnchair.organizer.application.protocol.ReadinessGate
import app.lawnchair.organizer.application.public.OrganizerDurableStatus
import app.lawnchair.organizer.application.public.RemainingWindow
import app.lawnchair.organizer.application.public.RestorableRecoveryEntry
import app.lawnchair.organizer.ui.ManualOrganizationModule
import app.lawnchair.organizer.ui.ManualOrganizationRun
import app.lawnchair.ui.preferences.LocalIsExpandedScreen
import app.lawnchair.ui.preferences.LocalNavController
import app.lawnchair.ui.preferences.components.NavigationActionPreference
import app.lawnchair.ui.preferences.components.layout.PreferenceGroup
import app.lawnchair.ui.preferences.components.layout.PreferenceLazyColumn
import app.lawnchair.ui.preferences.components.layout.PreferenceScaffold
import app.lawnchair.ui.preferences.components.layout.PreferenceTemplate
import app.lawnchair.ui.preferences.navigation.HomeScreenCategoryOverrides
import app.lawnchair.ui.preferences.navigation.HomeScreenCustomCategories
import app.lawnchair.ui.preferences.navigation.HomeScreenManualOrganization
import app.lawnchair.ui.preferences.navigation.HomeScreenOrganizerDiagnostics
import app.lawnchair.ui.preferences.navigation.HomeScreenOrganizerStrategy
import app.lawnchair.ui.preferences.navigation.HomeScreenPlacementLocks
import com.android.launcher3.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Issue #366: the Organizer hub (TO-BE T-01) — the persistent organizing
 * workspace opened from Settings → Home screen → Organizer. It composes
 * existing seams only: the run coordinator's state/readiness/durable-status
 * projection (spec #271) for the status card, and navigation rows to the
 * existing authoring surfaces for the materials. The run surface, the
 * coordinator, and the application module are not changed by this screen.
 *
 * The start CTA only navigates to the existing run surface: `start()` stays
 * exclusive to the run surface's start row, so the spec #328/#205 admission
 * gates are never re-implemented or bypassed here (1-tap start is T-07,
 * owned by #369).
 */
@Composable
fun OrganizerHubPreferences(
    modifier: Modifier = Modifier,
    run: ManualOrganizationRun? = null,
) {
    val context = LocalContext.current
    val coordinator = run ?: remember { ManualOrganizationModule.get(context) }
    val state by coordinator.stateFlow.collectAsStateWithLifecycle()

    // Spec #271 render contract, mirrored from the run surface: the durable
    // status is read only while no run operation is active (Idle/Cancelled),
    // re-read when the readiness gate moves so a fail-closed read taken
    // during startup reconciliation recovers on this surface, and every read
    // failure maps to a no-row outcome (fail-closed, no invented state).
    val showDurableStatus = state is ManualOrganizationRun.State.Idle || state is ManualOrganizationRun.State.Cancelled
    val readinessState by coordinator.readinessState.collectAsStateWithLifecycle()
    var durableStatus by remember { mutableStateOf<OrganizerDurableStatus?>(null) }

    // Issue #376 (spec D6 read serialization): the restore-entry hint is read
    // only after the status read, and only for the restorable status. Both
    // reads share the application module's non-blocking mutex, so running
    // them concurrently would starve one into its fail-closed value and
    // invent a missing-CTA state; a fail-closed hint read leaves the plain
    // restorable line (display only) and a later re-read recovers.
    var restorableEntry by remember { mutableStateOf<RestorableRecoveryEntry?>(null) }
    LaunchedEffect(showDurableStatus, readinessState) {
        if (!showDurableStatus) {
            durableStatus = null
            restorableEntry = null
            return@LaunchedEffect
        }
        val status = withContext(Dispatchers.IO) { coordinator.readDurableOrganizerStatus() }
        durableStatus = status
        restorableEntry = if (status == OrganizerDurableStatus.ORGANIZED_RESTORABLE) {
            withContext(Dispatchers.IO) { coordinator.readRestorableRecoveryEntry() }
        } else {
            null
        }
    }
    val showCheckingRow = showDurableStatus && (
        durableStatus == null ||
            (
                durableStatus == OrganizerDurableStatus.UNAVAILABLE &&
                    (
                        readinessState == ReadinessGate.State.IDLE ||
                            readinessState == ReadinessGate.State.RECONCILING
                        )
                )
        )

    // Issue #366 (organization-run-ux §6): entry focus lands deterministically
    // on the start CTA — on first entry and again when Back restores the hub
    // from a child surface — mirroring the run surface's start-row focus.
    val focusRequester = remember { FocusRequester() }
    val focusTargetReady = remember { mutableStateOf(false) }
    val focusTargetModifier = Modifier.onGloballyPositioned { focusTargetReady.value = true }
    LaunchedEffect(focusTargetReady.value) {
        if (!focusTargetReady.value) return@LaunchedEffect
        withFrameNanos { }
        runCatching { focusRequester.requestFocus() }
    }

    // Issue #376 (spec D5): the restore CTA opens the hub-origin recovery flow
    // and navigates to the existing confirmation-face host only when the entry
    // was admitted — a silent rejection (lease busy, expired hint) leaves the
    // status card untouched and the row stays for another tap.
    val navController = LocalNavController.current
    val scope = rememberCoroutineScope()
    val onRestore: () -> Unit = {
        scope.launch {
            val admitted = withContext(Dispatchers.IO) { coordinator.beginRecoveryPreviewFromDurableEntry() }
            if (admitted) navController.navigate(HomeScreenManualOrganization())
        }
    }

    PreferenceScaffold(
        label = stringResource(R.string.organizer_hub_label),
        modifier = modifier,
        isExpandedScreen = LocalIsExpandedScreen.current,
    ) { paddingValues ->
        PreferenceLazyColumn(paddingValues) {
            // Status card, phase 1 (TO-BE D-02): durable status rows → start
            // CTA → diagnostics. TalkBack order follows the composed order:
            // state first, then actions (TO-BE §13-5).
            if (showCheckingRow) {
                item(key = "organizer-hub-status-checking") {
                    HubCheckingLine(R.string.manual_organization_durable_status_checking)
                }
            }
            // Re-review: the rows are guarded by showDurableStatus itself, so a
            // transition into a run state can never render the previous
            // durable row for one recomposition while the read effect is
            // still catching up (HUB-AC-02 run-active hiding).
            if (showDurableStatus) durableStatus?.let { hubDurableStatusItems(it, restorableEntry, onRestore) }
            item(key = "organizer-hub-start") {
                NavigationActionPreference(
                    label = stringResource(R.string.manual_organization_start),
                    destination = HomeScreenManualOrganization(),
                    // clickable() owns the focus target and the Enter/Space
                    // activation; focusRequester only aims at that same target
                    // so keyboard focus and activation stay one node.
                    modifier = Modifier
                        .focusRequester(focusRequester)
                        .then(focusTargetModifier),
                )
            }
            item(key = "organizer-hub-diagnostics") {
                NavigationActionPreference(
                    label = stringResource(R.string.organizer_diagnostics_title),
                    destination = HomeScreenOrganizerDiagnostics,
                    subtitle = stringResource(R.string.organizer_diagnostics_description),
                )
            }
            // Materials (TO-BE §10): the existing authoring surfaces, plus
            // the strategy surface (T-05, issue #368) — the picker's only
            // home since the run surface no longer offers it.
            item(key = "organizer-hub-materials") {
                PreferenceGroup(heading = stringResource(R.string.organizer_hub_materials_heading)) {
                    NavigationActionPreference(
                        label = stringResource(R.string.organizer_category_overrides_title),
                        destination = HomeScreenCategoryOverrides,
                        subtitle = stringResource(R.string.organizer_category_overrides_summary),
                    )
                    NavigationActionPreference(
                        label = stringResource(R.string.organizer_custom_category_title),
                        destination = HomeScreenCustomCategories,
                        subtitle = stringResource(R.string.organizer_custom_category_summary),
                    )
                    NavigationActionPreference(
                        label = stringResource(R.string.organizer_lock_screen_title),
                        destination = HomeScreenPlacementLocks,
                        subtitle = stringResource(R.string.organizer_lock_screen_summary),
                    )
                    NavigationActionPreference(
                        label = stringResource(R.string.organizer_strategy_title),
                        destination = HomeScreenOrganizerStrategy,
                        subtitle = stringResource(R.string.organizer_strategy_summary),
                    )
                    OrganizerUsageMaterialRows()
                }
            }
        }
    }
}

/**
 * Spec #271 durable status rows for the hub, reusing the run surface's exact
 * closed-vocabulary strings. Only the three informative statuses render;
 * `NEVER_ORGANIZED` and the fail-closed `UNAVAILABLE` render nothing. The
 * unresolved status keeps the existing safe-support guidance line; its
 * diagnostics entry is the standing hub diagnostics row above.
 *
 * Issue #376 (D-15/D6): the restorable row grows the coarse remaining window
 * and the restore CTA — the TO-BE §13-5 reading order (状態 → 残期限 → 操作)
 * is the compose order. Both extras require a successful entry read; a
 * fail-closed null keeps the row display-only (no invented state).
 */
private fun LazyListScope.hubDurableStatusItems(
    status: OrganizerDurableStatus,
    restorableEntry: RestorableRecoveryEntry?,
    onRestore: () -> Unit,
) {
    when (status) {
        OrganizerDurableStatus.ORGANIZED_RESTORABLE -> {
            item(key = "organizer-hub-status") {
                HubRestorableLine(restorableEntry?.remainingWindow)
            }
            if (restorableEntry != null) {
                item(key = "organizer-hub-restore") {
                    HubRestoreCta(onRestore)
                }
            }
        }

        OrganizerDurableStatus.RESTORED_OR_EXPIRED -> item(key = "organizer-hub-status") {
            HubStatusLine(stringResource(R.string.manual_organization_durable_status_restored_or_expired))
        }

        OrganizerDurableStatus.UNRESOLVED -> {
            item(key = "organizer-hub-status") {
                HubStatusLine(stringResource(R.string.manual_organization_durable_status_unresolved))
            }
            item(key = "organizer-hub-safe-support") {
                HubStatusLine(stringResource(R.string.manual_organization_safe_terminal))
            }
        }

        OrganizerDurableStatus.NEVER_ORGANIZED,
        OrganizerDurableStatus.UNAVAILABLE,
        -> Unit
    }
}

@Composable
private fun HubStatusLine(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(horizontal = 16.dp),
    )
}

/**
 * Issue #376 (D-15): the restorable status line with its optional coarse
 * remaining-window line, composed in the TO-BE §13-5 order (状態 → 残期限).
 */
@Composable
private fun HubRestorableLine(remainingWindow: RemainingWindow?) {
    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        Text(
            text = stringResource(R.string.manual_organization_durable_status_restorable),
            style = MaterialTheme.typography.bodyMedium,
        )
        if (remainingWindow != null) {
            Text(
                text = remainingWindowText(remainingWindow),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun remainingWindowText(window: RemainingWindow): String = when (window) {
    is RemainingWindow.HoursRemaining -> stringResource(
        R.string.manual_organization_recovery_remaining_hours,
        window.value,
    )

    RemainingWindow.LessThanOneHour -> stringResource(
        R.string.manual_organization_recovery_remaining_under_one_hour,
    )
}

/**
 * Issue #376 (D-15): the restore CTA on the hub status card — the only
 * restore operation entry (D-15), reusing the existing confirmation face's
 * closed-vocabulary label. `clickable()` owns the activation exactly like
 * [NavigationActionPreference]; the navigation itself is decided by the
 * caller (only after an admitted entry).
 */
@Composable
private fun HubRestoreCta(onRestore: () -> Unit) {
    PreferenceTemplate(
        modifier = Modifier.clickable(onClick = onRestore),
        title = { Text(text = stringResource(R.string.manual_organization_recovery)) },
    )
}

@Composable
private fun HubCheckingLine(
    @androidx.annotation.StringRes resourceId: Int,
) {
    Text(
        text = stringResource(resourceId),
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
    )
}
