package app.lawnchair.organizer.ui

import androidx.annotation.StringRes
import app.lawnchair.organizer.application.public.CanonicalItemKind
import app.lawnchair.organizer.application.public.OrganizerLockState
import app.lawnchair.organizer.locks.LockEffectNote
import app.lawnchair.organizer.locks.LockProtectionScope
import app.lawnchair.organizer.locks.LockRejection
import com.android.launcher3.R

/**
 * Issue #38: mapping from typed lock-authoring domain values to localized
 * string resources. The domain layer never formats user text; every surface
 * (popup dialog, preferences screen) renders through this single mapping so
 * wording and accessibility stay consistent.
 */
object LockMessages {

    @StringRes
    fun stateLabel(state: OrganizerLockState): Int = when (state) {
        OrganizerLockState.LOCKED -> R.string.organizer_lock_state_locked
        OrganizerLockState.UNLOCKED -> R.string.organizer_lock_state_unlocked
        OrganizerLockState.UNKNOWN -> R.string.organizer_lock_state_unknown
    }

    @StringRes
    fun scopeDescription(scope: LockProtectionScope): Int = when (scope) {
        LockProtectionScope.OWN_PLACEMENT -> R.string.organizer_lock_effect_own_placement
        LockProtectionScope.WIDGET_REGION -> R.string.organizer_lock_effect_widget_region
        LockProtectionScope.DOCK_SLOT -> R.string.organizer_lock_effect_dock_slot
        LockProtectionScope.FOLDER_WITH_CHILDREN -> R.string.organizer_lock_effect_folder_scope
        LockProtectionScope.APP_PAIR_WITH_MEMBERS -> R.string.organizer_lock_effect_app_pair_scope
        LockProtectionScope.FOLDER_CHILD_RANK -> R.string.organizer_lock_effect_folder_child_scope
        LockProtectionScope.APP_PAIR_MEMBER_PLACEMENT -> R.string.organizer_lock_effect_app_pair_member_scope
    }

    @StringRes
    fun effectNote(note: LockEffectNote): Int = when (note) {
        LockEffectNote.FOLDER_PARENT_COVERS_CHILDREN -> R.string.organizer_lock_effect_folder_parent_covers_children

        LockEffectNote.FOLDER_CHILDREN_OWN_LOCK_REMAINS -> R.string.organizer_lock_effect_folder_children_own_lock_remains

        LockEffectNote.FOLDER_CHILD_OWN_LOCK_BINDS -> R.string.organizer_lock_effect_folder_child_own_lock_binds

        LockEffectNote.FOLDER_CHILD_UNLOCK_INEFFECTIVE_UNDER_PARENT_LOCK ->
            R.string.organizer_lock_effect_folder_child_unlock_ineffective

        LockEffectNote.APP_PAIR_PARENT_COVERS_BOTH_MEMBERS -> R.string.organizer_lock_effect_app_pair_parent_covers_members

        LockEffectNote.APP_PAIR_MEMBER_UNLOCK_INEFFECTIVE_UNDER_PARENT_LOCK ->
            R.string.organizer_lock_effect_app_pair_member_unlock_ineffective

        LockEffectNote.APP_PAIR_MEMBER_OWN_LOCK_BINDS -> R.string.organizer_lock_effect_app_pair_member_own_lock_binds
    }

    @StringRes
    fun rejection(reason: LockRejection): Int = when (reason) {
        LockRejection.ITEM_NOT_FOUND -> R.string.organizer_lock_error_item_not_found
        LockRejection.STALE_CAPTURE -> R.string.organizer_lock_error_stale
        LockRejection.ITEM_NOT_UNKNOWN -> R.string.organizer_lock_error_item_not_unknown
        LockRejection.PROFILE_UNAVAILABLE -> R.string.organizer_lock_error_profile_unavailable
        LockRejection.PROFILE_UNKNOWN -> R.string.organizer_lock_error_profile_unavailable
        LockRejection.UNSUPPORTED_ITEM -> R.string.organizer_lock_error_unsupported
        LockRejection.PLACEMENT_OUT_OF_PROFILE -> R.string.organizer_lock_error_unsupported
        LockRejection.INTENT_REQUIRED -> R.string.organizer_lock_error_intent_required
        LockRejection.WRITER_BUSY -> R.string.organizer_lock_error_busy
    }

    @StringRes
    fun kindLabel(kind: CanonicalItemKind): Int = when (kind) {
        CanonicalItemKind.Application -> R.string.organizer_lock_kind_app
        CanonicalItemKind.DeepShortcut -> R.string.organizer_lock_kind_shortcut
        CanonicalItemKind.ShortcutLegacy -> R.string.organizer_lock_kind_shortcut
        CanonicalItemKind.Folder -> R.string.organizer_lock_kind_folder
        CanonicalItemKind.AppWidget, CanonicalItemKind.CustomAppWidget -> R.string.organizer_lock_kind_widget
        CanonicalItemKind.AppPair -> R.string.organizer_lock_kind_app_pair
        is CanonicalItemKind.Unknown -> R.string.organizer_lock_kind_unknown
    }
}
