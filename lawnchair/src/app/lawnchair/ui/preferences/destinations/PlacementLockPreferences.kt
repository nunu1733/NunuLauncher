/*
 * Copyright 2026, Lawnchair
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
import android.os.Process
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.lawnchair.organizer.application.public.OptionalText
import app.lawnchair.organizer.application.public.OrganizerLockState
import app.lawnchair.organizer.locks.LockAuthoringModule
import app.lawnchair.organizer.locks.LockBatchReviewRequest
import app.lawnchair.organizer.locks.LockChangeResult
import app.lawnchair.organizer.locks.LockExplanation
import app.lawnchair.organizer.locks.LockPlacementSummary
import app.lawnchair.organizer.locks.LockStateChangeRequest
import app.lawnchair.organizer.locks.LockStateEntry
import app.lawnchair.organizer.locks.LockTargetState
import app.lawnchair.organizer.locks.OrganizerLocks
import app.lawnchair.organizer.locks.UserReviewedIntent
import app.lawnchair.organizer.ui.LockMessages
import app.lawnchair.ui.preferences.LocalIsExpandedScreen
import app.lawnchair.ui.preferences.components.controls.ClickablePreference
import app.lawnchair.ui.preferences.components.layout.PreferenceLazyColumn
import app.lawnchair.ui.preferences.components.layout.PreferenceScaffold
import app.lawnchair.ui.preferences.components.layout.PreferenceTemplate
import app.lawnchair.ui.preferences.components.layout.preferenceGroupItems
import com.android.launcher3.R
import com.android.launcher3.pm.UserCache
import com.android.launcher3.util.UserIconInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Issue #38: placement lock management and `UNKNOWN` review screen. Lists every
 * captured row — folder, folder child, Dock, widget, app pair, member — with
 * its lock state rendered as text, hosts the review flow, and mutates only
 * through confirmed dialogs that supply [UserReviewedIntent].
 */
@Composable
fun PlacementLockPreferences(
    modifier: Modifier = Modifier,
    module: LockAuthoringModule? = null,
) {
    val context = LocalContext.current
    // Resolved once; OrganizerLocks construction performs no DB I/O.
    val lockModule = module ?: remember { OrganizerLocks.get(context) }
    var entries by remember { mutableStateOf<List<LockStateEntry>?>(null) }
    var profileLabels by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var message by remember { mutableStateOf<String?>(null) }
    var dialogEntry by remember { mutableStateOf<LockStateEntry?>(null) }
    var dialogExplanation by remember { mutableStateOf<LockExplanation?>(null) }
    var batchTarget by remember { mutableStateOf<LockTargetState?>(null) }
    var refreshKey by remember { mutableIntStateOf(0) }
    val listState: LazyListState = rememberLazyListState()

    val workProfileLabel = stringResource(R.string.organizer_lock_screen_profile_work)
    val clonedProfileLabel = stringResource(R.string.organizer_lock_screen_profile_cloned)
    val privateProfileLabel = stringResource(R.string.organizer_lock_screen_profile_private)
    val otherProfileLabel = stringResource(R.string.organizer_lock_screen_profile_other)
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        val userCache = UserCache.INSTANCE.get(context)
        profileLabels = userCache.userProfiles.mapNotNull { user ->
            if (user == Process.myUserHandle()) return@mapNotNull null
            val serial = userCache.getSerialNumberForUser(user).toString()
            val label = when (userCache.getUserInfo(user).type) {
                UserIconInfo.TYPE_WORK -> workProfileLabel
                UserIconInfo.TYPE_CLONED -> clonedProfileLabel
                UserIconInfo.TYPE_PRIVATE -> privateProfileLabel
                else -> otherProfileLabel
            }
            serial to label
        }.toMap()
    }

    LaunchedEffect(refreshKey) {
        withContext(Dispatchers.IO) {
            runCatching { lockModule.lockStateListing() }
        }.onSuccess { entries = it }
            .onFailure { message = context.getString(R.string.organizer_lock_error_failed) }
    }

    val loadedEntries = entries.orEmpty()
    val unknownEntries = loadedEntries.filter { it.stored == OrganizerLockState.UNKNOWN }
    val knownEntries = loadedEntries.filter { it.stored != OrganizerLockState.UNKNOWN }

    fun applyChange(entry: LockStateEntry, target: LockTargetState, intent: UserReviewedIntent) {
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                lockModule.setLock(LockStateChangeRequest(entry.item, target, intent))
            }
            message = resultMessage(context, result, target)
            dialogEntry = null
            dialogExplanation = null
            refreshKey++
        }
    }

    fun applyBatch(target: LockTargetState, items: List<app.lawnchair.organizer.planning.ItemId>) {
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                lockModule.reviewBatch(LockBatchReviewRequest(items, target, UserReviewedIntent("screen_review_all")))
            }
            message = resultMessage(context, result, target)
            batchTarget = null
            refreshKey++
        }
    }

    fun openDialog(entry: LockStateEntry) {
        scope.launch {
            dialogExplanation = withContext(Dispatchers.IO) {
                lockModule.explain(entry.item, LockTargetState.LOCKED)
            }
            dialogEntry = entry
        }
    }

    PreferenceScaffold(
        label = stringResource(R.string.organizer_lock_screen_title),
        modifier = modifier,
        isExpandedScreen = LocalIsExpandedScreen.current,
    ) {
        PreferenceLazyColumn(it, state = listState) {
            message?.let { text ->
                preferenceGroupItems(count = 1, isFirstChild = true) {
                    ClickablePreference(label = text, onClick = { message = null })
                }
            }
            if (unknownEntries.isNotEmpty()) {
                preferenceGroupItems(
                    items = unknownEntries,
                    isFirstChild = message == null,
                    heading = {
                        context.getString(R.string.organizer_lock_screen_unknown_banner, unknownEntries.size)
                    },
                ) { _, entry ->
                    LockRow(
                        entry = entry,
                        profileLabel = profileLabels[entry.profile.value],
                        onClick = { openDialog(entry) },
                    )
                }
                preferenceGroupItems(
                    count = 2,
                    isFirstChild = false,
                ) { index ->
                    val target = if (index == 0) LockTargetState.LOCKED else LockTargetState.UNLOCKED
                    val label = stringResource(
                        if (index == 0) {
                            R.string.organizer_lock_screen_review_all_keep_locked
                        } else {
                            R.string.organizer_lock_screen_review_all_mark_unlocked
                        },
                    )
                    ClickablePreference(
                        label = label,
                        onClick = { batchTarget = target },
                    )
                }
            } else {
                preferenceGroupItems(
                    count = 1,
                    isFirstChild = message == null,
                    heading = { context.getString(R.string.organizer_lock_screen_unknown_banner_none) },
                ) {
                    Text(
                        text = stringResource(R.string.organizer_lock_screen_summary),
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            preferenceGroupItems(
                items = knownEntries,
                isFirstChild = false,
                heading = { context.getString(R.string.organizer_lock_screen_known_heading) },
            ) { _, entry ->
                LockRow(
                    entry = entry,
                    profileLabel = profileLabels[entry.profile.value],
                    onClick = { openDialog(entry) },
                )
            }
        }
    }

    val entry = dialogEntry
    val explanation = dialogExplanation
    if (entry != null && explanation != null) {
        LockChangeDialog(
            entry = entry,
            explanation = explanation,
            onDismiss = {
                dialogEntry = null
                dialogExplanation = null
            },
            onConfirm = { target, intent -> applyChange(entry, target, intent) },
        )
    }

    val target = batchTarget
    if (target != null && unknownEntries.isNotEmpty()) {
        val targetLabel = stringResource(
            if (target == LockTargetState.LOCKED) R.string.organizer_lock_state_locked else R.string.organizer_lock_state_unlocked,
        )
        AlertDialog(
            onDismissRequest = { batchTarget = null },
            title = { Text(stringResource(R.string.organizer_lock_dialog_title_review)) },
            text = {
                Text(
                    stringResource(R.string.organizer_lock_screen_review_all_confirm, unknownEntries.size, targetLabel),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { applyBatch(target, unknownEntries.map { it.item }) },
                ) { Text(stringResource(android.R.string.ok)) }
            },
            dismissButton = {
                TextButton(onClick = { batchTarget = null }) { Text(stringResource(android.R.string.cancel)) }
            },
        )
    }
}

@Composable
private fun LockRow(
    entry: LockStateEntry,
    profileLabel: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val stateLabel = stringResource(LockMessages.stateLabel(entry.stored))
    val title = entry.title.textOrFallback(entry)
    PreferenceTemplate(
        modifier = modifier.clickable(onClick = onClick),
        title = { Text(title) },
        description = { Text(placementDescription(entry, profileLabel)) },
        endWidget = {
            StateBadge(
                stateLabel = stateLabel,
                contentDescription = stringResource(
                    R.string.organizer_lock_screen_item_state_description,
                    title,
                    stateLabel,
                ),
            )
        },
    )
}

@Composable
private fun StateBadge(stateLabel: String, contentDescription: String) {
    Text(
        text = stateLabel,
        modifier = Modifier
            .padding(horizontal = 8.dp)
            .semantics { this.contentDescription = contentDescription },
        style = MaterialTheme.typography.labelMedium,
    )
}

@Composable
private fun LockChangeDialog(
    entry: LockStateEntry,
    explanation: LockExplanation,
    onDismiss: () -> Unit,
    onConfirm: (LockTargetState, UserReviewedIntent) -> Unit,
) {
    when (explanation) {
        is LockExplanation.Unavailable -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.organizer_lock_screen_title)) },
            text = { Text(stringResource(LockMessages.rejection(explanation.reason))) },
            confirmButton = {
                TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.ok)) }
            },
        )

        is LockExplanation.Available -> {
            val stateText = stringResource(LockMessages.stateLabel(entry.stored))
            val scopeText = stringResource(LockMessages.scopeDescription(entry.scope))
            val effectLines = explanation.notes.map { stringResource(LockMessages.effectNote(it)) }.joinToString("\n")
            val reviewIntro = stringResource(R.string.organizer_lock_dialog_review_intro)
            val body = buildString {
                append(stringResource(R.string.organizer_lock_dialog_current_state, stateText))
                append("\n\n")
                append(scopeText)
                if (effectLines.isNotEmpty()) {
                    append("\n\n")
                    append(effectLines)
                }
            }
            AlertDialog(
                onDismissRequest = onDismiss,
                title = {
                    Text(
                        stringResource(
                            when (entry.stored) {
                                OrganizerLockState.LOCKED -> R.string.organizer_lock_dialog_title_unlock
                                OrganizerLockState.UNLOCKED -> R.string.organizer_lock_dialog_title_lock
                                OrganizerLockState.UNKNOWN -> R.string.organizer_lock_dialog_title_review
                            },
                        ),
                    )
                },
                text = {
                    Text(if (entry.stored == OrganizerLockState.UNKNOWN) "$reviewIntro\n\n$body" else body)
                },
                confirmButton = {
                    when (entry.stored) {
                        OrganizerLockState.LOCKED -> TextButton(
                            onClick = { onConfirm(LockTargetState.UNLOCKED, UserReviewedIntent("screen_unlock")) },
                        ) { Text(stringResource(R.string.organizer_lock_action_unlock)) }

                        OrganizerLockState.UNLOCKED -> TextButton(
                            onClick = { onConfirm(LockTargetState.LOCKED, UserReviewedIntent("screen_lock")) },
                        ) { Text(stringResource(R.string.organizer_lock_action_lock)) }

                        OrganizerLockState.UNKNOWN -> Row {
                            TextButton(
                                onClick = { onConfirm(LockTargetState.LOCKED, UserReviewedIntent("screen_review_keep_locked")) },
                            ) { Text(stringResource(R.string.organizer_lock_action_keep_locked)) }
                            Spacer(Modifier.width(4.dp))
                            TextButton(
                                onClick = { onConfirm(LockTargetState.UNLOCKED, UserReviewedIntent("screen_review_mark_unlocked")) },
                            ) { Text(stringResource(R.string.organizer_lock_action_mark_unlocked)) }
                        }
                    }
                },
                dismissButton = {
                    TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
                },
            )
        }
    }
}

private fun resultMessage(context: Context, result: LockChangeResult, target: LockTargetState): String = when (result) {
    is LockChangeResult.Changed -> context.getString(
        if (target == LockTargetState.LOCKED) R.string.organizer_lock_result_locked else R.string.organizer_lock_result_unlocked,
    )

    LockChangeResult.NoChange -> context.getString(R.string.organizer_lock_result_no_change)

    is LockChangeResult.Rejected -> context.getString(LockMessages.rejection(result.reason))

    is LockChangeResult.Failed -> context.getString(R.string.organizer_lock_error_failed)
}

@Composable
private fun placementDescription(entry: LockStateEntry, profileLabel: String?): String {
    val placement = when (val p = entry.placement) {
        is LockPlacementSummary.Desktop -> stringResource(
            R.string.organizer_lock_screen_placement_desktop,
            (p.pageOrder ?: 0) + 1,
        )

        is LockPlacementSummary.DockSlot -> stringResource(R.string.organizer_lock_screen_placement_dock, p.rank + 1)

        is LockPlacementSummary.InFolder -> stringResource(
            R.string.organizer_lock_screen_placement_folder,
            p.rank + 1,
        )

        is LockPlacementSummary.InAppPair -> stringResource(R.string.organizer_lock_screen_placement_app_pair)

        is LockPlacementSummary.Unsupported -> stringResource(R.string.organizer_lock_error_unsupported)
    }
    val protectedByParent =
        if (entry.stored == OrganizerLockState.UNLOCKED && entry.effectivelyProtected) {
            stringResource(R.string.organizer_lock_screen_effectively_locked)
        } else {
            null
        }
    return when {
        profileLabel != null && protectedByParent != null -> stringResource(
            R.string.organizer_lock_screen_placement_summary_triple,
            placement,
            profileLabel,
            protectedByParent,
        )

        profileLabel != null -> stringResource(
            R.string.organizer_lock_screen_placement_summary_double,
            placement,
            profileLabel,
        )

        protectedByParent != null -> stringResource(
            R.string.organizer_lock_screen_placement_summary_double,
            placement,
            protectedByParent,
        )

        else -> placement
    }
}

private fun OptionalText.textOrFallback(entry: LockStateEntry): String = when (this) {
    is OptionalText.Present -> value
    OptionalText.Absent -> ""
}.ifBlank { entry.item.value }
