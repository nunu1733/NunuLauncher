package app.lawnchair.ui.preferences.destinations

import android.content.Context
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selectableGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lawnchair.organizer.application.public.ApplyResult
import app.lawnchair.organizer.application.public.PlanPreviewDetails
import app.lawnchair.organizer.application.public.PreviewCounts
import app.lawnchair.organizer.application.public.RecoveryPreviewResult
import app.lawnchair.organizer.application.public.RecoveryResult
import app.lawnchair.organizer.diagnostics.model.Trigger
import app.lawnchair.organizer.planning.Availability
import app.lawnchair.organizer.planning.PlacementCode
import app.lawnchair.organizer.planning.PreserveReason
import app.lawnchair.organizer.planning.RejectionCode
import app.lawnchair.organizer.planning.StrategyId
import app.lawnchair.organizer.planning.UnplacedReason
import app.lawnchair.organizer.planning.WarningCode
import app.lawnchair.organizer.rules.BuiltInOrganizerPolicyBundleSource
import app.lawnchair.organizer.rules.LayoutStrategySelectionModule
import app.lawnchair.organizer.rules.LayoutStrategySelectionReadResult
import app.lawnchair.organizer.rules.LayoutStrategySelectionSnapshot
import app.lawnchair.organizer.rules.LayoutStrategySelectionWriteResult
import app.lawnchair.organizer.ui.ManualOrganizationModule
import app.lawnchair.organizer.ui.ManualOrganizationRun
import app.lawnchair.organizer.ui.OrganizationPreviewContent
import app.lawnchair.organizer.ui.OrganizationPreviewSection
import app.lawnchair.organizer.ui.OrganizationPreviewWording
import app.lawnchair.ui.preferences.LocalIsExpandedScreen
import app.lawnchair.ui.preferences.components.controls.ClickablePreference
import app.lawnchair.ui.preferences.components.layout.PreferenceLazyColumn
import app.lawnchair.ui.preferences.components.layout.PreferenceScaffold
import com.android.launcher3.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Issue #52 explicit manual/full organization surface. */
@Composable
fun ManualOrganizationPreferences(
    modifier: Modifier = Modifier,
    run: ManualOrganizationRun? = null,
    trigger: Trigger = Trigger.MANUAL_FULL,
    onOpenDiagnostics: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val coordinator = run ?: remember { ManualOrganizationModule.get(context) }
    val scope = rememberCoroutineScope()
    val state by coordinator.stateFlow.collectAsStateWithLifecycle()
    val focusRequester = remember { FocusRequester() }

    // Issue #195: the concrete change list is planned once per preview state.
    // Expansion state is UI-local and resets when new details arrive.
    val previewDetails = (state as? ManualOrganizationRun.State.Preview)?.details
    val previewSections = remember(previewDetails, context) {
        previewDetails
            ?.let { OrganizationPreviewContent.sections(it, organizationPreviewWording(context)) }
            .orEmpty()
    }
    val expandedPreviewGroups = remember(previewDetails) { mutableStateOf(emptySet<Int>()) }

    ManualOrganizationBackHandler(coordinator)

    LaunchedEffect(state) {
        withFrameNanos { }
        runCatching { focusRequester.requestFocus() }
    }

    fun execute(action: () -> Unit) {
        scope.launch {
            withContext(Dispatchers.IO) { action() }
        }
    }

    // Spec 182 child 8: strategy picker. The catalog is display-only (the
    // composer still validates); the current selection is read from and every
    // change is issued through Rule Management's validated write command — the
    // UI never mutates the store directly. On a committed change while a run
    // is active, the run is dismissed (pre-checkpoint cancellation writes
    // nothing) and a fresh compose/plan cycle starts with a fresh capture.
    val strategyCatalog = remember {
        (BuiltInOrganizerPolicyBundleSource.readActive() as? app.lawnchair.organizer.rules.BundleReadResult.Ready)
            ?.bundle?.layoutStrategies
    }
    // Spec 182: a valid absent selection means the bundle default is what the
    // planner uses, so the picker shows the default as the effective choice.
    // Only a failed read hides the active selection (fail-closed).
    var selectedStrategy by remember {
        val snapshot = readSelectedStrategy(context)
        // Read succeeded: an absent selection means the bundle default is
        // what the planner resolves, so show the default as effective.
        // Read failed (unreadable/unsupported): show nothing — fail-closed,
        // matching the composer.
        mutableStateOf(if (snapshot == null) null else snapshot.selection ?: strategyCatalog?.default)
    }
    fun onStrategySelected(id: StrategyId) {
        // Radio semantics: re-selecting the effective strategy is a no-op, not
        // a new policy generation or a run restart.
        if (id == selectedStrategy) return
        execute {
            when (val write = LayoutStrategySelectionModule.store(context).select(id)) {
                is LayoutStrategySelectionWriteResult.Committed -> {
                    selectedStrategy = write.snapshot.selection
                    val runActive = coordinator.state !is ManualOrganizationRun.State.Idle &&
                        coordinator.state !is ManualOrganizationRun.State.Cancelled
                    if (runActive) {
                        coordinator.dismiss()
                        coordinator.start(trigger)
                    }
                }

                else -> Unit
            }
        }
    }

    PreferenceScaffold(
        label = stringResource(R.string.manual_organization_title),
        modifier = modifier,
        isExpandedScreen = LocalIsExpandedScreen.current,
    ) { paddingValues ->
        PreferenceLazyColumn(paddingValues) {
            item {
                Text(
                    text = stringResource(R.string.manual_organization_explainer),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(16.dp),
                )
            }
            when (val currentState = state) {
                ManualOrganizationRun.State.Idle,
                ManualOrganizationRun.State.Cancelled,
                -> item {
                    ClickablePreference(
                        label = stringResource(R.string.manual_organization_start),
                        modifier = Modifier.focusRequester(focusRequester).focusable(),
                        onClick = { execute { coordinator.start(trigger) } },
                    )
                }

                ManualOrganizationRun.State.Capturing -> item {
                    ProgressText(R.string.manual_organization_capturing, focusRequester)
                }

                ManualOrganizationRun.State.Planning -> item {
                    ProgressText(R.string.manual_organization_planning, focusRequester)
                }

                is ManualOrganizationRun.State.InputUnavailable -> item {
                    FocusTargetText(
                        text = stringResource(currentState.reason.copyKind()),
                        focusRequester = focusRequester,
                    )
                    ClickablePreference(
                        label = stringResource(R.string.manual_organization_retry),
                        onClick = { execute { coordinator.start(trigger) } },
                    )
                }

                is ManualOrganizationRun.State.PlanningRejected -> {
                    item {
                        FocusTargetText(
                            focusRequester = focusRequester,
                            text = stringResource(
                                if (currentState.kind == ManualOrganizationRun.PlanningFailureKind.IMPOSSIBLE) {
                                    R.string.manual_organization_impossible
                                } else {
                                    R.string.manual_organization_rejected
                                },
                            ),
                        )
                    }
                    summaryItems(currentState.summary)
                    item {
                        ClickablePreference(
                            label = stringResource(R.string.manual_organization_retry),
                            onClick = { execute { coordinator.start(trigger) } },
                        )
                    }
                }

                ManualOrganizationRun.State.NoChanges -> item {
                    FocusTargetText(
                        text = stringResource(R.string.manual_organization_no_changes),
                        focusRequester = focusRequester,
                    )
                    ClickablePreference(
                        label = stringResource(R.string.manual_organization_start_again),
                        onClick = { execute { coordinator.start(trigger) } },
                    )
                }

                is ManualOrganizationRun.State.Preview -> {
                    item {
                        FocusTargetText(
                            text = stringResource(R.string.manual_organization_preview),
                            focusRequester = focusRequester,
                        )
                    }
                    if (currentState.details == null) {
                        // Issue #195 spec D1: environmental preview failures keep the
                        // existing count-only flow, but announce the missing details
                        // instead of silently equating them with a normal preview.
                        // Issue #209 review: the degraded announcement and the
                        // count-only summary precede the decision pair, so the user
                        // sees that the concrete list is missing before reaching the
                        // primary action (spec 195 D1 over leading placement).
                        item {
                            SummaryText(
                                stringResource(R.string.manual_organization_preview_details_unavailable),
                            )
                        }
                        summaryItems(currentState.summary)
                        item {
                            PreviewDecisionActions(
                                onConfirm = { execute(coordinator::confirm) },
                                onCancel = { execute(coordinator::cancel) },
                            )
                        }
                    } else {
                        // Issue #209: the decision pair leads the concrete change
                        // list so Apply and Cancel are visible together no matter
                        // how much of the list is expanded.
                        item {
                            PreviewDecisionActions(
                                onConfirm = { execute(coordinator::confirm) },
                                onCancel = { execute(coordinator::cancel) },
                            )
                        }
                        previewDetailsItems(
                            summary = currentState.summary,
                            counts = currentState.details.counts,
                            sections = previewSections,
                            expandedGroups = expandedPreviewGroups,
                        )
                    }
                }

                ManualOrganizationRun.State.Applying -> item {
                    ProgressText(R.string.manual_organization_applying, focusRequester)
                    ClickablePreference(
                        label = stringResource(R.string.manual_organization_cancel_before_checkpoint),
                        onClick = { execute(coordinator::cancel) },
                    )
                }

                ManualOrganizationRun.State.Stale -> item {
                    FocusTargetText(
                        text = stringResource(R.string.manual_organization_stale),
                        focusRequester = focusRequester,
                    )
                    ClickablePreference(
                        label = stringResource(R.string.manual_organization_recapture),
                        onClick = { execute { coordinator.start(trigger) } },
                    )
                }

                is ManualOrganizationRun.State.Applied -> {
                    item {
                        FocusTargetText(
                            text = applyMessage(currentState.result),
                            focusRequester = focusRequester,
                        )
                    }
                    summaryItems(currentState.summary)
                    if (currentState.result is ApplyResult.Applied) {
                        // Issue #209: the safety net must read as a control,
                        // not as a caption row among the summary lines.
                        item {
                            DecisionActionsRow {
                                FilledTonalButton(
                                    onClick = { execute(coordinator::beginRecoveryPreview) },
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text(text = stringResource(R.string.manual_organization_recovery))
                                }
                            }
                        }
                    }
                    if (currentState.result.requiresSafeSupport()) {
                        item {
                            Text(
                                text = stringResource(R.string.manual_organization_safe_terminal),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(16.dp),
                            )
                        }
                        item {
                            ClickablePreference(
                                label = stringResource(R.string.manual_organization_open_diagnostics),
                                subtitle = stringResource(R.string.manual_organization_open_diagnostics_summary),
                                onClick = { onOpenDiagnostics?.invoke() },
                            )
                        }
                    } else {
                        item {
                            ClickablePreference(
                                label = stringResource(R.string.manual_organization_start_again),
                                onClick = { execute { coordinator.start(trigger) } },
                            )
                        }
                    }
                }

                ManualOrganizationRun.State.InspectingRecovery -> item {
                    ProgressText(R.string.manual_organization_recovery_inspecting, focusRequester)
                }

                is ManualOrganizationRun.State.RecoveryPreview -> {
                    item {
                        FocusTargetText(
                            text = recoveryPreviewMessage(currentState.result),
                            focusRequester = focusRequester,
                        )
                    }
                    // Issue #209: restore-or-cancel renders as the same
                    // decision pair as the apply preview.
                    item {
                        DecisionActionsRow {
                            if (currentState.result is RecoveryPreviewResult.Restorable) {
                                Button(
                                    onClick = { execute(coordinator::confirmRecovery) },
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text(text = stringResource(R.string.manual_organization_recovery_confirm))
                                }
                            }
                            OutlinedButton(
                                onClick = { execute(coordinator::cancelRecoveryPreview) },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(text = stringResource(R.string.manual_organization_cancel))
                            }
                        }
                    }
                }

                ManualOrganizationRun.State.Recovering -> item {
                    ProgressText(R.string.manual_organization_recovering, focusRequester)
                }

                is ManualOrganizationRun.State.RecoveryResultState -> {
                    item {
                        FocusTargetText(
                            text = recoveryResultMessage(currentState.result),
                            focusRequester = focusRequester,
                        )
                    }
                    if (currentState.result.requiresSafeSupport()) {
                        item {
                            Text(
                                text = stringResource(R.string.manual_organization_safe_terminal),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(16.dp),
                            )
                        }
                        item {
                            ClickablePreference(
                                label = stringResource(R.string.manual_organization_open_diagnostics),
                                subtitle = stringResource(R.string.manual_organization_open_diagnostics_summary),
                                onClick = { onOpenDiagnostics?.invoke() },
                            )
                        }
                    } else {
                        item {
                            ClickablePreference(
                                label = stringResource(R.string.manual_organization_start_again),
                                onClick = { execute { coordinator.start(trigger) } },
                            )
                        }
                    }
                }
            }
            strategyPickerItems(
                catalog = strategyCatalog?.runtimeSupported,
                selected = selectedStrategy,
                onSelect = ::onStrategySelected,
            )
        }
    }
}

/**
 * Reads the persisted selection snapshot. `Ready` is returned even for the
 * first-run absent state (`selection = null`) — the caller then displays the
 * bundle default as the effective selection. A failed read (unreadable,
 * unsupported schema) returns `null` and the picker shows no active
 * selection, failing closed exactly like the composer.
 */
private fun readSelectedStrategy(context: Context): LayoutStrategySelectionSnapshot? {
    val read = LayoutStrategySelectionModule.store(context).read()
    return (read as? LayoutStrategySelectionReadResult.Ready)?.snapshot
}

private fun strategyDisplayName(id: StrategyId): Int = when (id.value) {
    "CANONICAL_PAGE_COMPACT_V1" -> R.string.organization_strategy_canonical_name
    "STABLE_PAGE_TIDY_V1" -> R.string.organization_strategy_tidy_name
    "BOTTOM_FIRST_V1" -> R.string.organization_strategy_bottom_first_name
    "GLOBAL_COMPACT_V1" -> R.string.organization_strategy_global_name
    "CATEGORY_CONTIGUOUS_V1" -> R.string.organization_strategy_category_contiguous_name
    else -> R.string.organization_strategy_unknown_name
}

private fun strategyDescription(id: StrategyId): Int = when (id.value) {
    "CANONICAL_PAGE_COMPACT_V1" -> R.string.organization_strategy_canonical_description
    "STABLE_PAGE_TIDY_V1" -> R.string.organization_strategy_tidy_description
    "BOTTOM_FIRST_V1" -> R.string.organization_strategy_bottom_first_description
    "GLOBAL_COMPACT_V1" -> R.string.organization_strategy_global_description
    "CATEGORY_CONTIGUOUS_V1" -> R.string.organization_strategy_category_contiguous_description
    else -> R.string.organization_strategy_unknown_description
}

/**
 * Spec 182: strategy picker. Only the active bundle's runtime-supported
 * strategies are offered, each with a localized name and intent description.
 * Selection uses radio semantics so TalkBack announces name, state, and
 * description as one node; a store read failure hides the active selection
 * instead of inventing one (fail-closed, matching the composer).
 */
private fun androidx.compose.foundation.lazy.LazyListScope.strategyPickerItems(
    catalog: List<StrategyId>?,
    selected: StrategyId?,
    onSelect: (StrategyId) -> Unit,
) {
    if (catalog.isNullOrEmpty()) return
    // The whole picker lives in one selectableGroup so TalkBack announces the
    // rows as a single mutually-exclusive radio group ("x of N" semantics).
    // Six rows fit one screen, so losing LazyColumn virtualization here is
    // harmless (spec 182 child 8 a11y contract).
    item(key = "strategy-picker") {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("manual-organization-strategy-picker")
                .semantics { selectableGroup() },
        ) {
            Text(
                text = stringResource(R.string.manual_organization_strategy_section),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            catalog.forEach { id ->
                val name = stringResource(strategyDisplayName(id))
                val description = stringResource(strategyDescription(id))
                val isSelected = selected == id
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = isSelected,
                            role = Role.RadioButton,
                            onClick = { onSelect(id) },
                        )
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Column {
                        Text(name, style = MaterialTheme.typography.bodyLarge)
                        Text(description, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun ManualOrganizationBackHandler(coordinator: ManualOrganizationRun) {
    val dispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    val callbackRef = remember { mutableStateOf<OnBackPressedCallback?>(null) }
    val onBack = rememberUpdatedState {
        when (coordinator.dismiss()) {
            ManualOrganizationRun.DismissalOutcome.ApplicationInProgress -> Unit

            ManualOrganizationRun.DismissalOutcome.CancelledAndMayNavigate,
            ManualOrganizationRun.DismissalOutcome.NoActiveOperation,
            -> {
                callbackRef.value?.isEnabled = false
                dispatcher?.onBackPressed()
                callbackRef.value?.isEnabled = true
            }
        }
    }
    val callback = remember(dispatcher) {
        object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                onBack.value()
            }
        }
    }
    callbackRef.value = callback

    DisposableEffect(dispatcher, callback) {
        dispatcher?.addCallback(callback)
        onDispose {
            callback.remove()
            if (callbackRef.value === callback) callbackRef.value = null
        }
    }
    DisposableEffect(coordinator) {
        onDispose { coordinator.dismiss() }
    }
}

@Composable
private fun ProgressText(
    @androidx.annotation.StringRes resourceId: Int,
    focusRequester: FocusRequester? = null,
) {
    val focusModifier = if (focusRequester == null) {
        Modifier
    } else {
        Modifier
            .focusRequester(focusRequester)
            .focusable()
    }
    Text(
        text = stringResource(resourceId),
        modifier = focusModifier
            .padding(horizontal = 16.dp)
            .semantics {
                liveRegion = LiveRegionMode.Polite
            },
    )
}

@Composable
private fun FocusTargetText(
    text: String,
    focusRequester: FocusRequester,
) {
    Text(
        text = text,
        modifier = Modifier
            .focusRequester(focusRequester)
            .focusable()
            .padding(horizontal = 16.dp)
            .semantics {
                liveRegion = LiveRegionMode.Polite
            },
    )
}

/**
 * Issue #209: the preview decision pair (confirm / cancel) as Material3
 * buttons — the same emphasis split as the lawnchair confirmation bottom
 * sheet — instead of preference rows that read like plain text. In the
 * concrete-list mode it sits directly below the status heading so both paths
 * of the decision are visible together no matter how far the list is
 * expanded; the degraded mode keeps it after the missing-details announcement
 * and the count-only summary (spec 195 D1).
 */
@Composable
private fun PreviewDecisionActions(
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    DecisionActionsRow {
        Button(
            onClick = onConfirm,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(text = stringResource(R.string.manual_organization_confirm))
        }
        OutlinedButton(
            onClick = onCancel,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(text = stringResource(R.string.manual_organization_cancel))
        }
    }
}

/**
 * Issue #209: decision actions (confirm / cancel / restore) render as
 * Material3 buttons — the same emphasis split as the lawnchair confirmation
 * bottom sheet — instead of preference rows that read like plain text. The
 * actions sit directly below the state heading so both paths of a decision
 * are visible together regardless of how far the change list is expanded,
 * stacked full-width so keyboard/DPAD traversal visits them in the visual
 * confirm-then-cancel order and long labels wrap instead of clipping.
 */
@Composable
private fun DecisionActionsRow(
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

private fun androidx.compose.foundation.lazy.LazyListScope.summaryItems(
    summary: ManualOrganizationRun.Summary,
) {
    contextItems(summary)
    changeCountItems(summary)
    constraintItems(summary)
}

/**
 * Input-context lines. These describe the captured planning input, not the
 * changes, so both preview modes render them from [ManualOrganizationRun.Summary]
 * (spec §D2: the PreviewCounts truth split covers change counts only).
 */
private fun androidx.compose.foundation.lazy.LazyListScope.contextItems(
    summary: ManualOrganizationRun.Summary,
) {
    item {
        SummaryText(
            stringResource(
                R.string.manual_organization_scope,
                summary.scope.targetCount,
                summary.scope.targetProfileCount,
                summary.scope.pageCount,
            ),
        )
    }
    item {
        SummaryText(
            stringResource(
                R.string.manual_organization_device_scope,
                summary.scope.columns,
                summary.scope.rows,
                summary.scope.hotseatSlots,
            ),
        )
    }
    item {
        SummaryText(
            stringResource(
                R.string.manual_organization_preview_strategy,
                stringResource(strategyDisplayName(summary.organizationStrategy)),
            ),
        )
    }
}

/**
 * Change-count lines from the planning [ManualOrganizationRun.Summary]. Only
 * the degraded count-only preview renders these; the concrete change list uses
 * [previewDetailsItems] whose header counts come from `PreviewCounts` so rows
 * and header always share one truth (spec 194).
 */
private fun androidx.compose.foundation.lazy.LazyListScope.changeCountItems(
    summary: ManualOrganizationRun.Summary,
) {
    item { SummaryText(stringResource(R.string.manual_organization_moved_count, summary.movedCount)) }
    summary.movedByReason.forEach { (reason, count) ->
        item { SummaryText(stringResource(movedReasonString(reason), count)) }
    }
    item { SummaryText(stringResource(R.string.manual_organization_preserved_count, summary.preservedCount)) }
    summary.preservedByReason.forEach { (reason, count) ->
        item { SummaryText(stringResource(preservedReasonString(reason), count)) }
    }
    item { SummaryText(stringResource(R.string.manual_organization_new_folders_count, summary.newFolderCount)) }
    item { SummaryText(stringResource(R.string.manual_organization_new_pages_count, summary.newPageCount)) }
    summary.rejectedByReason.forEach { (reason, count) ->
        item { SummaryText(stringResource(rejectionReasonString(reason), count)) }
    }
    summary.unplacedByReason.forEach { (reason, count) ->
        item { SummaryText(stringResource(unplacedReasonString(reason), count)) }
    }
    summary.warningCounts.forEach { (code, count) ->
        item { SummaryText(stringResource(warningString(code), count)) }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.constraintItems(
    summary: ManualOrganizationRun.Summary,
) {
    val constraints = summary.constraints
    if (constraints.lockedCount > 0) {
        item { SummaryText(stringResource(R.string.manual_organization_locked_constraint, constraints.lockedCount)) }
    }
    if (constraints.unavailableCount > 0) {
        item { SummaryText(stringResource(R.string.manual_organization_unavailable_constraint, constraints.unavailableCount)) }
    }
    constraints.availabilityCounts.forEach { (availability, count) ->
        if (availability != Availability.AVAILABLE) {
            item { SummaryText(stringResource(availabilityString(availability), count)) }
        }
    }
    if (constraints.widgetCount > 0) {
        item { SummaryText(stringResource(R.string.manual_organization_widget_constraint, constraints.widgetCount)) }
    }
    if (constraints.appPairCount > 0) {
        item { SummaryText(stringResource(R.string.manual_organization_app_pair_constraint, constraints.appPairCount)) }
    }
    if (constraints.legacyShortcutCount > 0) {
        item { SummaryText(stringResource(R.string.manual_organization_legacy_shortcut_constraint, constraints.legacyShortcutCount)) }
    }
    if (constraints.emptyFolderCount > 0) {
        item { SummaryText(stringResource(R.string.manual_organization_empty_folder_constraint, constraints.emptyFolderCount)) }
    }
}

/**
 * Issue #195: the concrete change list. Header counts come from the
 * materialized-plan [PreviewCounts], groups render the projected rows in
 * deterministic order, and large groups truncate behind a per-group expand
 * action whose semantics carry the expansion state.
 */
private fun androidx.compose.foundation.lazy.LazyListScope.previewDetailsItems(
    summary: ManualOrganizationRun.Summary,
    counts: PreviewCounts,
    sections: List<OrganizationPreviewSection>,
    expandedGroups: MutableState<Set<Int>>,
) {
    contextItems(summary)
    item { SummaryText(stringResource(R.string.manual_organization_moved_count, counts.movedCount)) }
    item { SummaryText(stringResource(R.string.manual_organization_preserved_count, counts.preservedCount)) }
    item { SummaryText(stringResource(R.string.manual_organization_new_folders_count, counts.newFolderCount)) }
    item { SummaryText(stringResource(R.string.manual_organization_new_pages_count, counts.newPageCount)) }
    if (counts.crossPageMovedCount > 0) {
        item { SummaryText(stringResource(R.string.manual_organization_cross_page_moved_count, counts.crossPageMovedCount)) }
    }
    if (counts.preservedByStrategyCount > 0) {
        item { SummaryText(stringResource(R.string.manual_organization_preserved_by_strategy_count, counts.preservedByStrategyCount)) }
    }
    counts.warningCounts.forEach { (code, count) ->
        item { SummaryText(stringResource(warningString(code), count)) }
    }
    item {
        Text(
            text = stringResource(R.string.manual_organization_changes_heading),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
    sections.forEachIndexed { sectionIndex, section ->
        // Stable keys keep the toggle's node identity across the expansion
        // reflow so focus and item state survive the rows inserted before it.
        item(key = "preview-section-$sectionIndex") {
            Text(
                text = section.heading,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        val expanded = sectionIndex in expandedGroups.value
        val visibleRows = if (expanded) section.rows else section.rows.take(PREVIEW_ROWS_BEFORE_EXPANSION)
        visibleRows.forEachIndexed { rowIndex, row ->
            item(key = "preview-row-$sectionIndex-$rowIndex") { SummaryText(row) }
        }
        if (section.rows.size > PREVIEW_ROWS_BEFORE_EXPANSION) {
            item(key = "preview-toggle-$sectionIndex") {
                val expandedNow = sectionIndex in expandedGroups.value
                val stateText = stringResource(
                    if (expandedNow) {
                        R.string.manual_organization_preview_expanded_state
                    } else {
                        R.string.manual_organization_preview_collapsed_state
                    },
                )
                val label = stringResource(
                    if (expandedNow) {
                        R.string.manual_organization_preview_show_fewer
                    } else {
                        R.string.manual_organization_preview_show_all
                    },
                    if (expandedNow) PREVIEW_ROWS_BEFORE_EXPANSION else section.totalCount,
                )
                val toggleFocus = remember { FocusRequester() }
                // Spec 52 focus restoration: the action that announced the extra
                // rows keeps focus after the list reflows around it.
                LaunchedEffect(expandedNow) {
                    if (expandedNow) {
                        withFrameNanos { }
                        runCatching { toggleFocus.requestFocus() }
                    }
                }
                ClickablePreference(
                    label = label,
                    modifier = Modifier
                        .focusRequester(toggleFocus)
                        .semantics { stateDescription = stateText },
                    onClick = {
                        expandedGroups.value = if (expandedNow) {
                            expandedGroups.value - sectionIndex
                        } else {
                            expandedGroups.value + sectionIndex
                        }
                    },
                )
            }
        }
    }
    constraintItems(summary)
}

/**
 * Resolves the change-list wording with the given context; row planning itself
 * stays in the pure [OrganizationPreviewContent] builder, which the caller
 * caches per preview details.
 */
private fun organizationPreviewWording(context: Context): OrganizationPreviewWording = ResourceOrganizationPreviewWording(
    groupMoved = context.getString(R.string.manual_organization_group_moved),
    groupNewFolders = context.getString(R.string.manual_organization_group_new_folders),
    groupNewPages = context.getString(R.string.manual_organization_group_new_pages),
    groupPreserved = context.getString(R.string.manual_organization_group_preserved),
    groupWarnings = context.getString(R.string.manual_organization_group_warnings),
    moveRow = context.getString(R.string.manual_organization_preview_move_row),
    sameBandMoveRow = context.getString(R.string.manual_organization_preview_same_band_move_row),
    rowOrdinalNote = context.getString(R.string.manual_organization_preview_row_ordinal_note),
    itemRow = context.getString(R.string.manual_organization_preview_item_row),
    itemDescriptor = context.getString(R.string.manual_organization_preview_item_descriptor),
    itemDescriptorWithoutKind = context.getString(R.string.manual_organization_preview_item_descriptor_without_kind),
    moveReasonSinglePlacement = context.getString(R.string.manual_organization_preview_move_reason_single_placement),
    moveReasonFolderMember = context.getString(R.string.manual_organization_preview_move_reason_folder_member),
    moveReasonFolderUnit = context.getString(R.string.manual_organization_preview_move_reason_folder_unit),
    moveReasonUnspecified = context.getString(R.string.manual_organization_preview_move_reason_unspecified),
    preservedReasonLocked = context.getString(R.string.manual_organization_preview_preserved_reason_locked),
    preservedReasonReservedRegion = context.getString(R.string.manual_organization_preview_preserved_reason_reserved_region),
    preservedReasonUnavailable = context.getString(R.string.manual_organization_preview_preserved_reason_unavailable),
    preservedReasonDock = context.getString(R.string.manual_organization_preview_preserved_reason_dock),
    preservedReasonWidget = context.getString(R.string.manual_organization_preview_preserved_reason_widget),
    preservedReasonAppPair = context.getString(R.string.manual_organization_preview_preserved_reason_app_pair),
    preservedReasonLegacyShortcut = context.getString(R.string.manual_organization_preview_preserved_reason_legacy_shortcut),
    preservedReasonNonTarget = context.getString(R.string.manual_organization_preview_preserved_reason_non_target),
    preservedReasonStrategyPreserved = context.getString(R.string.manual_organization_preview_preserved_reason_strategy),
    preservedReasonStructural = context.getString(R.string.manual_organization_preview_preserved_reason_structural),
    preservedReasonAlreadyCanonical = context.getString(R.string.manual_organization_preview_preserved_reason_already_canonical),
    warningLegacyShortcutReview = context.getString(R.string.manual_organization_preview_warning_legacy_shortcut_item),
    warningFallbackCategory = context.getString(R.string.manual_organization_preview_warning_fallback_category_item),
    warningUnavailablePreserved = context.getString(R.string.manual_organization_preview_warning_unavailable_item),
    pagePosition = context.getString(R.string.manual_organization_preview_page),
    newPagePosition = context.getString(R.string.manual_organization_preview_new_page_position),
    workspacePosition = context.getString(R.string.manual_organization_preview_position_workspace),
    regionTopLeft = context.getString(R.string.manual_organization_preview_region_top_left),
    regionTopCenter = context.getString(R.string.manual_organization_preview_region_top_center),
    regionTopRight = context.getString(R.string.manual_organization_preview_region_top_right),
    regionMiddleLeft = context.getString(R.string.manual_organization_preview_region_middle_left),
    regionMiddleCenter = context.getString(R.string.manual_organization_preview_region_middle_center),
    regionMiddleRight = context.getString(R.string.manual_organization_preview_region_middle_right),
    regionBottomLeft = context.getString(R.string.manual_organization_preview_region_bottom_left),
    regionBottomCenter = context.getString(R.string.manual_organization_preview_region_bottom_center),
    regionBottomRight = context.getString(R.string.manual_organization_preview_region_bottom_right),
    dockPosition = context.getString(R.string.manual_organization_preview_position_dock),
    folderPositionExisting = context.getString(R.string.manual_organization_preview_position_folder_existing),
    folderPositionPlanned = context.getString(R.string.manual_organization_preview_position_folder_planned),
    appPairPosition = context.getString(R.string.manual_organization_preview_position_app_pair),
    unidentifiedPosition = context.getString(R.string.manual_organization_preview_position_unidentified),
    positionWithSupplement = context.getString(R.string.manual_organization_preview_position_with_supplement),
    supplementCell = context.getString(R.string.manual_organization_preview_supplement_cell),
    supplementParentWithStage = context.getString(R.string.manual_organization_preview_supplement_parent_with_stage),
    supplementStageTop = context.getString(R.string.manual_organization_preview_supplement_stage_top),
    supplementStageBottom = context.getString(R.string.manual_organization_preview_supplement_stage_bottom),
    kindApplication = context.getString(R.string.manual_organization_preview_kind_application),
    kindDeepShortcut = context.getString(R.string.manual_organization_preview_kind_deep_shortcut),
    kindShortcutLegacy = context.getString(R.string.manual_organization_preview_kind_shortcut_legacy),
    kindFolder = context.getString(R.string.manual_organization_preview_kind_folder),
    kindAppWidget = context.getString(R.string.manual_organization_preview_kind_app_widget),
    kindCustomAppWidget = context.getString(R.string.manual_organization_preview_kind_custom_app_widget),
    kindAppPair = context.getString(R.string.manual_organization_preview_kind_app_pair),
    kindUnknown = context.getString(R.string.manual_organization_preview_kind_unknown),
    newFolderRow = context.getString(R.string.manual_organization_preview_new_folder_row),
    newPageRow = context.getString(R.string.manual_organization_preview_new_page_row),
)

/** Resource-backed [OrganizationPreviewWording]; all values resolved up front. */
private class ResourceOrganizationPreviewWording(
    override val groupMoved: String,
    override val groupNewFolders: String,
    override val groupNewPages: String,
    override val groupPreserved: String,
    override val groupWarnings: String,
    override val moveRow: String,
    override val sameBandMoveRow: String,
    override val rowOrdinalNote: String,
    override val itemRow: String,
    override val itemDescriptor: String,
    override val itemDescriptorWithoutKind: String,
    override val moveReasonSinglePlacement: String,
    override val moveReasonFolderMember: String,
    override val moveReasonFolderUnit: String,
    override val moveReasonUnspecified: String,
    override val preservedReasonLocked: String,
    override val preservedReasonReservedRegion: String,
    override val preservedReasonUnavailable: String,
    override val preservedReasonDock: String,
    override val preservedReasonWidget: String,
    override val preservedReasonAppPair: String,
    override val preservedReasonLegacyShortcut: String,
    override val preservedReasonNonTarget: String,
    override val preservedReasonStrategyPreserved: String,
    override val preservedReasonStructural: String,
    override val preservedReasonAlreadyCanonical: String,
    override val warningLegacyShortcutReview: String,
    override val warningFallbackCategory: String,
    override val warningUnavailablePreserved: String,
    override val pagePosition: String,
    override val newPagePosition: String,
    override val workspacePosition: String,
    override val regionTopLeft: String,
    override val regionTopCenter: String,
    override val regionTopRight: String,
    override val regionMiddleLeft: String,
    override val regionMiddleCenter: String,
    override val regionMiddleRight: String,
    override val regionBottomLeft: String,
    override val regionBottomCenter: String,
    override val regionBottomRight: String,
    override val dockPosition: String,
    override val folderPositionExisting: String,
    override val folderPositionPlanned: String,
    override val appPairPosition: String,
    override val unidentifiedPosition: String,
    override val positionWithSupplement: String,
    override val supplementCell: String,
    override val supplementParentWithStage: String,
    override val supplementStageTop: String,
    override val supplementStageBottom: String,
    override val kindApplication: String,
    override val kindDeepShortcut: String,
    override val kindShortcutLegacy: String,
    override val kindFolder: String,
    override val kindAppWidget: String,
    override val kindCustomAppWidget: String,
    override val kindAppPair: String,
    override val kindUnknown: String,
    override val newFolderRow: String,
    override val newPageRow: String,
) : OrganizationPreviewWording

/** Groups larger than this show the first rows plus a per-group expand action. */
private const val PREVIEW_ROWS_BEFORE_EXPANSION = 5

@Composable
private fun SummaryText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(horizontal = 16.dp),
    )
}

private fun movedReasonString(reason: PlacementCode): Int = when (reason) {
    PlacementCode.SINGLE_PLACEMENT -> R.string.manual_organization_moved_single_placement
    PlacementCode.FOLDER_MEMBER -> R.string.manual_organization_moved_folder_member
    PlacementCode.FOLDER_UNIT -> R.string.manual_organization_moved_folder_unit
}

private fun preservedReasonString(reason: PreserveReason): Int = when (reason) {
    PreserveReason.LOCKED -> R.string.manual_organization_preserved_locked
    PreserveReason.RESERVED_REGION -> R.string.manual_organization_preserved_reserved_region
    PreserveReason.UNAVAILABLE_TARGET -> R.string.manual_organization_preserved_unavailable
    PreserveReason.DOCK -> R.string.manual_organization_preserved_dock
    PreserveReason.WIDGET -> R.string.manual_organization_preserved_widget
    PreserveReason.APP_PAIR -> R.string.manual_organization_preserved_app_pair
    PreserveReason.LEGACY_SHORTCUT -> R.string.manual_organization_preserved_legacy_shortcut
    PreserveReason.NON_TARGET -> R.string.manual_organization_preserved_non_target
    PreserveReason.STRATEGY_PRESERVED -> R.string.manual_organization_preserved_strategy
    PreserveReason.STRUCTURAL -> R.string.manual_organization_preserved_structural
    PreserveReason.ALREADY_CANONICAL -> R.string.manual_organization_preserved_already_canonical
}

private fun unplacedReasonString(reason: UnplacedReason): Int = when (reason) {
    UnplacedReason.EXCEEDS_GRID_DIMENSIONS -> R.string.manual_organization_unplaced_grid
    UnplacedReason.TARGET_UNAVAILABLE -> R.string.manual_organization_unplaced_target
}

private fun rejectionReasonString(reason: RejectionCode): Int = when (reason) {
    RejectionCode.UNKNOWN_ITEM_KIND -> R.string.manual_organization_rejection_unknown_item_kind
    RejectionCode.INVALID_CONTAINER -> R.string.manual_organization_rejection_invalid_container
    RejectionCode.UNKNOWN_PAGE -> R.string.manual_organization_rejection_unknown_page
    RejectionCode.BOUNDS_VIOLATION -> R.string.manual_organization_rejection_bounds
    RejectionCode.OVERLAP -> R.string.manual_organization_rejection_overlap
    RejectionCode.DANGLING_REFERENCE -> R.string.manual_organization_rejection_dangling_reference
    RejectionCode.MALFORMED_APP_PAIR -> R.string.manual_organization_rejection_malformed_app_pair
    RejectionCode.LOCKED_OUT_OF_BOUNDS -> R.string.manual_organization_rejection_locked_out_of_bounds
    RejectionCode.DUPLICATE_TARGET -> R.string.manual_organization_rejection_duplicate_target
    RejectionCode.MISSING_TARGET -> R.string.manual_organization_rejection_missing_target
    RejectionCode.INCOMPLETE_TARGET_PARTITION -> R.string.manual_organization_rejection_incomplete_target_partition
    RejectionCode.ADDITIONS_UNDER_FULL_ORGANIZATION -> R.string.manual_organization_rejection_additions
    RejectionCode.INVALID_RULES -> R.string.manual_organization_rejection_invalid_rules
    RejectionCode.DUPLICATE_ITEM_ID -> R.string.manual_organization_rejection_duplicate_item
    RejectionCode.DUPLICATE_PAGE -> R.string.manual_organization_rejection_duplicate_page
    RejectionCode.INVALID_DIMENSIONS -> R.string.manual_organization_rejection_invalid_dimensions
    RejectionCode.KIND_TARGET_MISMATCH -> R.string.manual_organization_rejection_kind_target_mismatch
    RejectionCode.TARGET_PROFILE_MISMATCH -> R.string.manual_organization_rejection_target_profile_mismatch
    RejectionCode.UNKNOWN_SIGNAL_ITEM -> R.string.manual_organization_rejection_unknown_signal_item
    RejectionCode.UNKNOWN_CATEGORY -> R.string.manual_organization_rejection_unknown_category
}

private fun availabilityString(availability: Availability): Int = when (availability) {
    Availability.AVAILABLE -> R.string.manual_organization_available_constraint
    Availability.DISABLED -> R.string.manual_organization_disabled_constraint
    Availability.QUIET -> R.string.manual_organization_quiet_constraint
    Availability.LOCKED_PRIVATE_SPACE -> R.string.manual_organization_private_space_constraint
    Availability.UNAVAILABLE -> R.string.manual_organization_unavailable_item_constraint
}

private fun warningString(code: WarningCode): Int = when (code) {
    WarningCode.LEGACY_SHORTCUT_REVIEW -> R.string.manual_organization_warning_legacy_shortcut
    WarningCode.FALLBACK_CATEGORY -> R.string.manual_organization_warning_fallback_category
    WarningCode.UNAVAILABLE_PRESERVED -> R.string.manual_organization_warning_unavailable
}

/**
 * Issue #172: `ReconciliationPending` is the "model still loading / try again
 * later" family; every other readiness reason is a source/config problem that
 * warrants a bug report. The copy split follows the diagnostics contract §2
 * (user-facing reason is derived from typed results, never from the journal).
 */
private fun app.lawnchair.organizer.integration.InputReadinessReason.copyKind(): Int = when (this) {
    app.lawnchair.organizer.integration.InputReadinessReason.ReconciliationPending -> R.string.manual_organization_input_not_ready_yet
    else -> R.string.manual_organization_input_unavailable_bug
}

@Composable
private fun applyMessage(result: ApplyResult): String = stringResource(
    when (result) {
        is ApplyResult.NoChanges -> R.string.manual_organization_no_changes
        is ApplyResult.Applied -> R.string.manual_organization_apply_success
        is ApplyResult.Rejected -> R.string.manual_organization_apply_rejected
        is ApplyResult.RolledBack -> R.string.manual_organization_apply_rolled_back
        is ApplyResult.Recovered -> R.string.manual_organization_apply_recovered
        is ApplyResult.Unresolved -> R.string.manual_organization_apply_unresolved
        is ApplyResult.RecoveryFailed -> R.string.manual_organization_apply_recovery_failed
        ApplyResult.ConcurrentRun -> R.string.manual_organization_apply_concurrent
    },
)

@Composable
private fun recoveryPreviewMessage(result: RecoveryPreviewResult): String = stringResource(
    when (result) {
        is RecoveryPreviewResult.Restorable -> R.string.manual_organization_recovery_preview

        is RecoveryPreviewResult.NotRestorable -> R.string.manual_organization_recovery_not_available

        is RecoveryPreviewResult.Unavailable -> R.string.manual_organization_recovery_not_available

        RecoveryPreviewResult.WriterBusy,
        RecoveryPreviewResult.Concurrent,
        -> R.string.manual_organization_apply_concurrent
    },
)

@Composable
private fun recoveryResultMessage(result: RecoveryResult): String = stringResource(
    when (result) {
        is RecoveryResult.Restored -> R.string.manual_organization_recovery_restored

        is RecoveryResult.NotRestorable -> R.string.manual_organization_recovery_not_available

        is RecoveryResult.RestoreFailed -> R.string.manual_organization_recovery_failed

        RecoveryResult.WriterBusy,
        RecoveryResult.ConcurrentRun,
        -> R.string.manual_organization_apply_concurrent
    },
)

private fun ApplyResult.requiresSafeSupport(): Boolean = this is ApplyResult.Unresolved || this is ApplyResult.RecoveryFailed

private fun RecoveryResult.requiresSafeSupport(): Boolean = this is RecoveryResult.RestoreFailed
