package app.lawnchair.ui.preferences.destinations

import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.focusable
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lawnchair.organizer.application.public.ApplyResult
import app.lawnchair.organizer.application.public.RecoveryPreviewResult
import app.lawnchair.organizer.application.public.RecoveryResult
import app.lawnchair.organizer.planning.Availability
import app.lawnchair.organizer.planning.PlacementCode
import app.lawnchair.organizer.planning.PreserveReason
import app.lawnchair.organizer.planning.RejectionCode
import app.lawnchair.organizer.planning.UnplacedReason
import app.lawnchair.organizer.planning.WarningCode
import app.lawnchair.organizer.ui.ManualOrganizationModule
import app.lawnchair.organizer.ui.ManualOrganizationRun
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
    onOpenDiagnostics: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val coordinator = run ?: remember { ManualOrganizationModule.get(context) }
    val scope = rememberCoroutineScope()
    val state by coordinator.stateFlow.collectAsStateWithLifecycle()
    val focusRequester = remember { FocusRequester() }

    ManualOrganizationBackHandler(coordinator)

    LaunchedEffect(state) {
        runCatching { focusRequester.requestFocus() }
    }

    fun execute(action: () -> Unit) {
        scope.launch {
            withContext(Dispatchers.IO) { action() }
        }
    }

    PreferenceScaffold(
        label = stringResource(R.string.manual_organization_title),
        modifier = modifier,
        isExpandedScreen = LocalIsExpandedScreen.current,
    ) { paddingValues ->
        PreferenceLazyColumn(paddingValues) {
            item {
                Text(stringResource(R.string.manual_organization_explainer))
            }
            when (val currentState = state) {
                ManualOrganizationRun.State.Idle,
                ManualOrganizationRun.State.Cancelled,
                -> item {
                    ClickablePreference(
                        label = stringResource(R.string.manual_organization_start),
                        modifier = Modifier.focusRequester(focusRequester).focusable(),
                        onClick = { execute(coordinator::start) },
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
                        text = stringResource(R.string.manual_organization_input_unavailable),
                        focusRequester = focusRequester,
                    )
                    ClickablePreference(
                        label = stringResource(R.string.manual_organization_retry),
                        onClick = { execute(coordinator::start) },
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
                            onClick = { execute(coordinator::start) },
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
                        onClick = { execute(coordinator::start) },
                    )
                }

                is ManualOrganizationRun.State.Preview -> {
                    item {
                        FocusTargetText(
                            text = stringResource(R.string.manual_organization_preview),
                            focusRequester = focusRequester,
                        )
                    }
                    summaryItems(currentState.summary)
                    item {
                        ClickablePreference(
                            label = stringResource(R.string.manual_organization_confirm),
                            onClick = { execute(coordinator::confirm) },
                        )
                    }
                    item {
                        ClickablePreference(
                            label = stringResource(R.string.manual_organization_cancel),
                            onClick = { execute(coordinator::cancel) },
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
                        onClick = { execute(coordinator::start) },
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
                        item {
                            ClickablePreference(
                                label = stringResource(R.string.manual_organization_recovery),
                                onClick = { execute(coordinator::beginRecoveryPreview) },
                            )
                        }
                    }
                    if (currentState.result.requiresSafeSupport()) {
                        item {
                            Text(stringResource(R.string.manual_organization_safe_terminal))
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
                                onClick = { execute(coordinator::start) },
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
                    if (currentState.result is RecoveryPreviewResult.Restorable) {
                        item {
                            ClickablePreference(
                                label = stringResource(R.string.manual_organization_recovery_confirm),
                                onClick = { execute(coordinator::confirmRecovery) },
                            )
                        }
                    }
                    item {
                        ClickablePreference(
                            label = stringResource(R.string.manual_organization_cancel),
                            onClick = { execute(coordinator::cancelRecoveryPreview) },
                        )
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
                            Text(stringResource(R.string.manual_organization_safe_terminal))
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
                                onClick = { execute(coordinator::start) },
                            )
                        }
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
        coordinator.dismiss()
        callbackRef.value?.isEnabled = false
        dispatcher?.onBackPressed()
        callbackRef.value?.isEnabled = true
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
        modifier = focusModifier.semantics {
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
            .focusable(),
    )
}

private fun androidx.compose.foundation.lazy.LazyListScope.summaryItems(
    summary: ManualOrganizationRun.Summary,
) {
    item {
        Text(
            stringResource(
                R.string.manual_organization_scope,
                summary.scope.targetCount,
                summary.scope.targetProfileCount,
                summary.scope.pageCount,
            ),
        )
    }
    item {
        Text(
            stringResource(
                R.string.manual_organization_device_scope,
                summary.scope.columns,
                summary.scope.rows,
                summary.scope.hotseatSlots,
            ),
        )
    }
    item { Text(stringResource(R.string.manual_organization_moved_count, summary.movedCount)) }
    summary.movedByReason.forEach { (reason, count) ->
        item { Text(stringResource(movedReasonString(reason), count)) }
    }
    item { Text(stringResource(R.string.manual_organization_preserved_count, summary.preservedCount)) }
    summary.preservedByReason.forEach { (reason, count) ->
        item { Text(stringResource(preservedReasonString(reason), count)) }
    }
    item { Text(stringResource(R.string.manual_organization_new_folders_count, summary.newFolderCount)) }
    item { Text(stringResource(R.string.manual_organization_new_pages_count, summary.newPageCount)) }
    summary.rejectedByReason.forEach { (reason, count) ->
        item { Text(stringResource(rejectionReasonString(reason), count)) }
    }
    summary.unplacedByReason.forEach { (reason, count) ->
        item { Text(stringResource(unplacedReasonString(reason), count)) }
    }
    summary.warningCounts.forEach { (code, count) ->
        item { Text(stringResource(warningString(code), count)) }
    }
    val constraints = summary.constraints
    if (constraints.lockedCount > 0) {
        item { Text(stringResource(R.string.manual_organization_locked_constraint, constraints.lockedCount)) }
    }
    if (constraints.unavailableCount > 0) {
        item { Text(stringResource(R.string.manual_organization_unavailable_constraint, constraints.unavailableCount)) }
    }
    constraints.availabilityCounts.forEach { (availability, count) ->
        if (availability != Availability.AVAILABLE) {
            item { Text(stringResource(availabilityString(availability), count)) }
        }
    }
    if (constraints.widgetCount > 0) {
        item { Text(stringResource(R.string.manual_organization_widget_constraint, constraints.widgetCount)) }
    }
    if (constraints.appPairCount > 0) {
        item { Text(stringResource(R.string.manual_organization_app_pair_constraint, constraints.appPairCount)) }
    }
    if (constraints.legacyShortcutCount > 0) {
        item { Text(stringResource(R.string.manual_organization_legacy_shortcut_constraint, constraints.legacyShortcutCount)) }
    }
    if (constraints.emptyFolderCount > 0) {
        item { Text(stringResource(R.string.manual_organization_empty_folder_constraint, constraints.emptyFolderCount)) }
    }
}

private fun movedReasonString(reason: PlacementCode): Int = when (reason) {
    PlacementCode.SINGLE_PLACEMENT -> R.string.manual_organization_moved_single_placement
    PlacementCode.FOLDER_MEMBER -> R.string.manual_organization_moved_folder_member
    PlacementCode.FOLDER_UNIT -> R.string.manual_organization_moved_folder_unit
}

private fun preservedReasonString(reason: PreserveReason): Int = when (reason) {
    PreserveReason.LOCKED -> R.string.manual_organization_preserved_locked
    PreserveReason.UNAVAILABLE_TARGET -> R.string.manual_organization_preserved_unavailable
    PreserveReason.DOCK -> R.string.manual_organization_preserved_dock
    PreserveReason.WIDGET -> R.string.manual_organization_preserved_widget
    PreserveReason.APP_PAIR -> R.string.manual_organization_preserved_app_pair
    PreserveReason.LEGACY_SHORTCUT -> R.string.manual_organization_preserved_legacy_shortcut
    PreserveReason.NON_TARGET -> R.string.manual_organization_preserved_non_target
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
