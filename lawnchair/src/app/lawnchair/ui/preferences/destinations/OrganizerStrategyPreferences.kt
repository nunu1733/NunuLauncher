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

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selectableGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lawnchair.organizer.planning.StrategyId
import app.lawnchair.organizer.rules.BuiltInOrganizerPolicyBundleSource
import app.lawnchair.organizer.rules.LayoutStrategySelectionModule
import app.lawnchair.organizer.rules.LayoutStrategySelectionReadResult
import app.lawnchair.organizer.rules.LayoutStrategySelectionSnapshot
import app.lawnchair.organizer.rules.LayoutStrategySelectionWriteResult
import app.lawnchair.organizer.ui.ManualOrganizationModule
import app.lawnchair.organizer.ui.ManualOrganizationRun
import app.lawnchair.organizer.ui.StrategyWriteArbiter
import app.lawnchair.ui.preferences.LocalIsExpandedScreen
import app.lawnchair.ui.preferences.components.layout.PreferenceLazyColumn
import app.lawnchair.ui.preferences.components.layout.PreferenceScaffold
import com.android.launcher3.R

/**
 * Issue #368: the strategy materials surface (TO-BE T-05) — the permanent
 * home of the strategy picker, reached only from the hub materials section.
 * The manual-run surface no longer hosts the picker, and the run-time change
 * special case (silent dismiss + restart) is gone: a committed selection
 * reaches planning through the next composer read of a future run.
 *
 * Write admission joins the shared
 * [app.lawnchair.organizer.ui.OrganizationOperationLease] domain: a write
 * holds an AUTHORING token from start to its terminal path, so it mutually
 * excludes runs, recovery, and other authoring regardless of navigation.
 * The disabled affordance below is display only — it derives from the
 * coordinator's run/recovery operation lifetime, while the structural gate
 * is the token acquisition (refusals surface as a typed retry notice, never
 * as a silent drop).
 */
@Composable
fun OrganizerStrategyPreferences(
    modifier: Modifier = Modifier,
    run: ManualOrganizationRun? = null,
) {
    val context = LocalContext.current
    val coordinator = run ?: remember { ManualOrganizationModule.get(context) }
    val scope = rememberCoroutineScope()
    // Spec #368: the run/recovery operation lifetime — not the display State
    // enumeration — drives the frozen affordance, so a finished run (Applied,
    // NoChanges, Stale, …) never leaves the picker frozen.
    val operationActive by coordinator.operationActive.collectAsStateWithLifecycle()

    // Spec 182 child 8: the catalog is display-only (the composer still
    // validates); the current selection is read from and every change is
    // issued through Rule Management's validated write command — the UI
    // never mutates the store directly.
    val strategyCatalog = remember {
        (BuiltInOrganizerPolicyBundleSource.readActive() as? app.lawnchair.organizer.rules.BundleReadResult.Ready)
            ?.bundle?.layoutStrategies
    }
    // Spec 182: a valid absent selection means the bundle default is what the
    // planner uses, so the picker shows the default as the effective choice.
    // Only a failed read hides the active selection (fail-closed).
    var selectedStrategy by remember {
        val snapshot = readSelectedStrategy(context)
        mutableStateOf(if (snapshot == null) null else snapshot.selection ?: strategyCatalog?.default)
    }
    // Spec #368: a typed refusal (another authoring operation or this
    // arbiter's own write in flight) is announced as a retry notice instead
    // of silently dropping the tap. Cleared on the next selection attempt.
    var retryNotice by remember { mutableStateOf(false) }

    val strategyArbiter = remember {
        StrategyWriteArbiter(
            scope = scope,
            writeStrategy = { id ->
                LayoutStrategySelectionModule.store(context).select(id) is
                    LayoutStrategySelectionWriteResult.Committed
            },
            runOrRecoveryActive = { coordinator.operationActive.value },
        )
    }

    fun onStrategySelected(id: StrategyId) {
        // Radio semantics: re-selecting the effective strategy is a no-op, not
        // a new policy generation.
        if (id == selectedStrategy) return
        retryNotice = false
        when (
            strategyArbiter.onStrategySelected(id) { committedId ->
                selectedStrategy = committedId
            }
        ) {
            // The run/recovery case is already shown by the frozen affordance
            // (same operation-lifetime truth); no extra notice is needed.
            StrategyWriteArbiter.StartOutcome.Started -> Unit

            StrategyWriteArbiter.StartOutcome.RefusedRunOrRecoveryActive -> Unit

            StrategyWriteArbiter.StartOutcome.RefusedAuthoringBusy,
            StrategyWriteArbiter.StartOutcome.RefusedWriteBusy,
            -> retryNotice = true
        }
    }

    val frozenReason = if (operationActive) {
        stringResource(R.string.organizer_strategy_frozen_operation_active)
    } else {
        null
    }
    val retryText = if (retryNotice) {
        stringResource(R.string.organizer_strategy_retry_when_busy)
    } else {
        null
    }

    PreferenceScaffold(
        label = stringResource(R.string.manual_organization_strategy_section),
        modifier = modifier,
        isExpandedScreen = LocalIsExpandedScreen.current,
    ) { paddingValues ->
        PreferenceLazyColumn(paddingValues) {
            strategyPickerItems(
                catalog = strategyCatalog?.runtimeSupported,
                selected = selectedStrategy,
                enabled = !operationActive && !strategyArbiter.busy,
                frozenReason = frozenReason,
                retryNotice = retryText,
                onSelect = ::onStrategySelected,
            )
        }
    }
}

private fun readSelectedStrategy(context: Context): LayoutStrategySelectionSnapshot? {
    val read = LayoutStrategySelectionModule.store(context).read()
    return (read as? LayoutStrategySelectionReadResult.Ready)?.snapshot
}

/**
 * Spec 182: strategy picker. Only the active bundle's runtime-supported
 * strategies are offered, each with a localized name and intent description.
 * Selection uses radio semantics so TalkBack announces name, state, and
 * description as one node; a store read failure hides the active selection
 * instead of inventing one (fail-closed, matching the composer).
 */
internal fun androidx.compose.foundation.lazy.LazyListScope.strategyPickerItems(
    catalog: List<StrategyId>?,
    selected: StrategyId?,
    enabled: Boolean,
    frozenReason: String?,
    retryNotice: String?,
    onSelect: (StrategyId) -> Unit,
) {
    if (catalog.isNullOrEmpty()) return
    // The whole picker lives in one selectableGroup so TalkBack announces the
    // rows as a single mutually-exclusive radio group ("x of N" semantics).
    // The catalog's rows may exceed one small screen (eight strategies since
    // issue #235); losing LazyColumn virtualization here only composes rows
    // off-screen — never clips them — so the radio-group a11y contract holds
    // (spec 182 child 8; picker tests scroll rows into view).
    item(key = "strategy-picker") {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("manual-organization-strategy-picker")
                .semantics { selectableGroup() },
        ) {
            if (!enabled && frozenReason != null) {
                // Spec #368: the frozen state and its reason are read out,
                // not only shown (a11y; the run/recovery lifetime is the
                // same truth the structural gate consults).
                Text(
                    text = frozenReason,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .semantics { liveRegion = LiveRegionMode.Polite }
                        .testTag("strategy-picker-frozen-reason"),
                )
            }
            if (retryNotice != null) {
                // Spec #368: a typed refusal (other authoring occupancy or
                // single flight) gets its own retry copy, distinct from the
                // frozen reason.
                Text(
                    text = retryNotice,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .semantics { liveRegion = LiveRegionMode.Polite }
                        .testTag("strategy-picker-retry-notice"),
                )
            }
            catalog.forEach { id ->
                val name = stringResource(strategyDisplayName(id))
                val description = stringResource(strategyDescription(id))
                val isSelected = selected == id
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = isSelected,
                            enabled = enabled,
                            role = Role.RadioButton,
                            onClick = { onSelect(id) },
                        )
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    RadioButton(
                        selected = isSelected,
                        onClick = null,
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(name, style = MaterialTheme.typography.bodyLarge)
                        Text(description, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}
