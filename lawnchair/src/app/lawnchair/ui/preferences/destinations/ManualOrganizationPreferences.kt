package app.lawnchair.ui.preferences.destinations

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.lawnchair.organizer.application.public.ApplyResult
import app.lawnchair.organizer.application.public.RecoveryPreviewResult
import app.lawnchair.organizer.application.public.RecoveryResult
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
) {
    val context = LocalContext.current
    val coordinator = run ?: remember { ManualOrganizationModule.get(context) }
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }
    val state = remember(refresh) { coordinator.state }

    fun execute(action: () -> Unit) {
        scope.launch {
            withContext(Dispatchers.IO) { action() }
            refresh++
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
            when (state) {
                ManualOrganizationRun.State.Idle,
                ManualOrganizationRun.State.Cancelled,
                -> item {
                    ClickablePreference(
                        label = stringResource(R.string.manual_organization_start),
                        onClick = { execute(coordinator::start) },
                    )
                }

                ManualOrganizationRun.State.Capturing -> item {
                    Text(stringResource(R.string.manual_organization_capturing))
                }

                is ManualOrganizationRun.State.InputUnavailable -> item {
                    Text(stringResource(R.string.manual_organization_input_unavailable))
                    ClickablePreference(
                        label = stringResource(R.string.manual_organization_retry),
                        onClick = { execute(coordinator::start) },
                    )
                }

                is ManualOrganizationRun.State.PlanningRejected -> item {
                    Text(
                        stringResource(
                            if (state.kind == ManualOrganizationRun.PlanningFailureKind.IMPOSSIBLE) {
                                R.string.manual_organization_impossible
                            } else {
                                R.string.manual_organization_rejected
                            },
                        ),
                    )
                    ClickablePreference(
                        label = stringResource(R.string.manual_organization_retry),
                        onClick = { execute(coordinator::start) },
                    )
                }

                ManualOrganizationRun.State.NoChanges -> item {
                    Text(stringResource(R.string.manual_organization_no_changes))
                    ClickablePreference(
                        label = stringResource(R.string.manual_organization_start_again),
                        onClick = { execute(coordinator::start) },
                    )
                }

                is ManualOrganizationRun.State.Preview -> {
                    item { Text(stringResource(R.string.manual_organization_preview)) }
                    item { Text(stringResource(R.string.manual_organization_moved_count, state.summary.movedCount)) }
                    item { Text(stringResource(R.string.manual_organization_preserved_count, state.summary.preservedCount)) }
                    item { Text(stringResource(R.string.manual_organization_new_folders_count, state.summary.newFolderCount)) }
                    item { Text(stringResource(R.string.manual_organization_new_pages_count, state.summary.newPageCount)) }
                    if (state.summary.warningCodes.isNotEmpty()) {
                        item { Text(stringResource(R.string.manual_organization_warnings_present)) }
                    }
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
                    Text(stringResource(R.string.manual_organization_applying))
                }

                ManualOrganizationRun.State.Stale -> item {
                    Text(stringResource(R.string.manual_organization_stale))
                    ClickablePreference(
                        label = stringResource(R.string.manual_organization_recapture),
                        onClick = { execute(coordinator::start) },
                    )
                }

                is ManualOrganizationRun.State.Applied -> {
                    item { Text(applyMessage(state.result)) }
                    if (state.result is ApplyResult.Applied) {
                        item {
                            ClickablePreference(
                                label = stringResource(R.string.manual_organization_recovery),
                                onClick = { execute(coordinator::beginRecoveryPreview) },
                            )
                        }
                    }
                    item {
                        ClickablePreference(
                            label = stringResource(R.string.manual_organization_start_again),
                            onClick = { execute(coordinator::start) },
                        )
                    }
                }

                is ManualOrganizationRun.State.RecoveryPreview -> {
                    item { Text(recoveryPreviewMessage(state.result)) }
                    if (state.result is RecoveryPreviewResult.Restorable) {
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
                    Text(stringResource(R.string.manual_organization_recovering))
                }

                is ManualOrganizationRun.State.RecoveryResultState -> {
                    item { Text(recoveryResultMessage(state.result)) }
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
