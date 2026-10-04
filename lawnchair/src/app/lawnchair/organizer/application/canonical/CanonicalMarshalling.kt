package app.lawnchair.organizer.application.canonical

import app.lawnchair.organizer.application.public.ApplicationItemRef
import app.lawnchair.organizer.application.public.ApplicationPageRef
import app.lawnchair.organizer.application.public.ApplyAction
import app.lawnchair.organizer.application.public.CanonicalItemKind
import app.lawnchair.organizer.application.public.CanonicalItemState
import app.lawnchair.organizer.application.public.DeviceCapabilities
import app.lawnchair.organizer.application.public.DeviceOrientation
import app.lawnchair.organizer.application.public.ImmutableByteString
import app.lawnchair.organizer.application.public.ItemAvailability
import app.lawnchair.organizer.application.public.LayoutState
import app.lawnchair.organizer.application.public.ModifiedAtMillis
import app.lawnchair.organizer.application.public.OptionalBytes
import app.lawnchair.organizer.application.public.OptionalSnapPosition
import app.lawnchair.organizer.application.public.OptionalText
import app.lawnchair.organizer.application.public.OrganizerLockState
import app.lawnchair.organizer.application.public.PageState
import app.lawnchair.organizer.application.public.PlacementState
import app.lawnchair.organizer.application.public.ProfileAvailability
import app.lawnchair.organizer.application.public.ProfileState
import app.lawnchair.organizer.application.public.RankedMember
import app.lawnchair.organizer.application.public.StructureState
import app.lawnchair.organizer.application.public.WidgetOptions
import app.lawnchair.organizer.application.public.WidgetRestoreState
import app.lawnchair.organizer.application.public.WidgetSource
import app.lawnchair.organizer.application.public.WidgetState
import app.lawnchair.organizer.planning.AppPairId
import app.lawnchair.organizer.planning.AppWidgetId
import app.lawnchair.organizer.planning.ComponentKey
import app.lawnchair.organizer.planning.ContainerCode
import app.lawnchair.organizer.planning.FolderId
import app.lawnchair.organizer.planning.GridCell
import app.lawnchair.organizer.planning.GridSpan
import app.lawnchair.organizer.planning.ItemId
import app.lawnchair.organizer.planning.KindCode
import app.lawnchair.organizer.planning.NewFolderOrdinal
import app.lawnchair.organizer.planning.NewPageOrdinal
import app.lawnchair.organizer.planning.PackageName
import app.lawnchair.organizer.planning.PageId
import app.lawnchair.organizer.planning.PageOrder
import app.lawnchair.organizer.planning.ProfileId
import app.lawnchair.organizer.planning.ShortcutId
import app.lawnchair.organizer.planning.SnapPositionToken
import app.lawnchair.organizer.planning.SplitStage
import app.lawnchair.organizer.planning.TargetKey

/**
 * Deterministic canonical encoding for [LayoutState] digests and for the
 * action-set fingerprint. Pure, allocation-explicit; no platform types.
 *
 * The wire form is private to this module — external code must never persist
 * these bytes; recovery payloads use [PersistenceManifest]. The two encodings
 * are deliberately distinct so a change to one cannot accidentally break the
 * other.
 *
 * Issue #14 Stage B step 1.
 */
object CanonicalMarshalling {

    /** Open a sink for the named digest kind. The kind participates in the bytes. */
    fun sink(kind: String): Digest.DigestSink = Digest.tagged(kind)

    /** Encode the full [LayoutState] into [sink]. */
    fun LayoutState.encode(sink: Digest.DigestSink) {
        sink.int(pages.size)
        pages.sortedBy { it.order }.forEach { it.encode(sink) }
        sink.int(profiles.size)
        profiles.sortedBy { it.id }.forEach { it.encode(sink) }
        deviceCapabilities.encode(sink)
        sink.int(items.size)
        items.sortedBy { it.ref.encodeOrderKey() }.forEach { it.encode(sink) }
    }

    /** Encode a single [ApplyAction]; order of the list is preserved. */
    fun List<ApplyAction>.encode(sink: Digest.DigestSink) {
        sink.int(size)
        for (action in this) {
            action.encode(sink)
        }
    }

    private fun PageState.encode(sink: Digest.DigestSink) {
        ref.encode(sink)
        order.encode(sink)
    }

    private fun ProfileState.encode(sink: Digest.DigestSink) {
        id.encode(sink)
        sink.byte(availability.ordinal)
    }

    private fun DeviceCapabilities.encode(sink: Digest.DigestSink) {
        sink.int(columns)
        sink.int(rows)
        sink.int(hotseatSlots)
        sink.int(folderMaxColumns)
        sink.int(folderMaxRows)
        sink.byte(orientation.ordinal)
    }

    private fun CanonicalItemState.encode(sink: Digest.DigestSink) {
        ref.encode(sink)
        kind.encode(sink)
        targetKey.encode(sink)
        profile.encode(sink)
        sink.byte(profileAvailability.ordinal)
        sink.byte(itemAvailability.ordinal)
        placement.encode(sink)
        title.encode(sink)
        intent.encode(sink)
        icon.encode(sink)
        widget.encode(sink)
        modified.encode(sink)
        sink.byte(lockState.ordinal)
        structure.encode(sink)
    }

    private fun ApplyAction.encode(sink: Digest.DigestSink) {
        when (this) {
            is ApplyAction.Preserve -> {
                sink.byte(ACTION_PRESERVE)
                ref.encode(sink)
                expected.encode(sink)
            }

            is ApplyAction.Update -> {
                sink.byte(ACTION_UPDATE)
                ref.encode(sink)
                expected.encode(sink)
                intended.encode(sink)
            }

            is ApplyAction.Insert -> {
                sink.byte(ACTION_INSERT)
                ref.encode(sink)
                intended.encode(sink)
            }
        }
    }

    private fun ApplicationItemRef.encode(sink: Digest.DigestSink) {
        when (this) {
            is ApplicationItemRef.PersistentItem -> {
                sink.byte(REF_PERSISTENT_ITEM)
                itemId.encode(sink)
            }

            is ApplicationItemRef.PlannedCandidate -> {
                sink.byte(REF_PLANNED_CANDIDATE)
                itemId.encode(sink)
            }

            is ApplicationItemRef.PlannedFolder -> {
                sink.byte(REF_PLANNED_FOLDER)
                ordinal.encode(sink)
            }
        }
    }

    private fun ApplicationItemRef.encodeOrderKey(): String = when (this) {
        is ApplicationItemRef.PersistentItem -> "0:${itemId.value}"
        is ApplicationItemRef.PlannedCandidate -> "1:${itemId.value}"
        is ApplicationItemRef.PlannedFolder -> "2:${ordinal.value}"
    }

    private fun ApplicationPageRef.encode(sink: Digest.DigestSink) {
        when (this) {
            is ApplicationPageRef.PersistentPage -> {
                sink.byte(REF_PERSISTENT_PAGE)
                pageId.encode(sink)
            }

            is ApplicationPageRef.PlannedPage -> {
                sink.byte(REF_PLANNED_PAGE)
                ordinal.encode(sink)
            }
        }
    }

    private fun PlacementState.encode(sink: Digest.DigestSink) {
        when (this) {
            is PlacementState.Workspace -> {
                sink.byte(PLACEMENT_WORKSPACE)
                page.encode(sink)
                cell.encode(sink)
                span.encode(sink)
            }

            is PlacementState.Dock -> {
                sink.byte(PLACEMENT_DOCK)
                sink.int(rank)
            }

            is PlacementState.FolderChild -> {
                sink.byte(PLACEMENT_FOLDER_CHILD)
                parent.encode(sink)
                sink.int(rank)
            }

            is PlacementState.AppPairChild -> {
                sink.byte(PLACEMENT_APP_PAIR_CHILD)
                parent.encode(sink)
                sink.byte(stage.ordinal)
            }

            is PlacementState.UnsupportedContainer -> {
                sink.byte(PLACEMENT_UNSUPPORTED)
                sink.int(code.value)
            }
        }
    }

    private fun CanonicalItemKind.encode(sink: Digest.DigestSink) {
        when (this) {
            is CanonicalItemKind.Application -> sink.byte(KIND_APPLICATION)

            is CanonicalItemKind.DeepShortcut -> sink.byte(KIND_DEEP_SHORTCUT)

            is CanonicalItemKind.ShortcutLegacy -> sink.byte(KIND_SHORTCUT_LEGACY)

            is CanonicalItemKind.Folder -> sink.byte(KIND_FOLDER)

            is CanonicalItemKind.AppWidget -> sink.byte(KIND_APPWIDGET)

            is CanonicalItemKind.CustomAppWidget -> sink.byte(KIND_CUSTOM_APPWIDGET)

            is CanonicalItemKind.AppPair -> sink.byte(KIND_APP_PAIR)

            is CanonicalItemKind.Unknown -> {
                sink.byte(KIND_UNKNOWN)
                sink.int(code.value)
            }
        }
    }

    private fun TargetKey.encode(sink: Digest.DigestSink) {
        when (this) {
            is TargetKey.AppKey -> {
                sink.byte(TARGET_APP)
                component.encode(sink)
                profile.encode(sink)
            }

            is TargetKey.ShortcutKey -> {
                sink.byte(TARGET_SHORTCUT)
                packageName.encode(sink)
                shortcutId.encode(sink)
                profile.encode(sink)
            }

            TargetKey.LegacyShortcutKey -> sink.byte(TARGET_LEGACY_SHORTCUT)

            is TargetKey.WidgetKey -> {
                sink.byte(TARGET_WIDGET)
                provider.encode(sink)
                appWidgetId.encode(sink)
                profile.encode(sink)
            }

            is TargetKey.FolderKey -> {
                sink.byte(TARGET_FOLDER)
                folderId.encode(sink)
            }

            is TargetKey.AppPairKey -> {
                sink.byte(TARGET_APP_PAIR)
                appPairId.encode(sink)
            }
        }
    }

    private fun WidgetState.encode(sink: Digest.DigestSink) {
        when (this) {
            WidgetState.NoWidget -> sink.byte(WIDGET_NONE)

            is WidgetState.Widget -> {
                sink.byte(WIDGET_PRESENT)
                provider.encode(sink)
                appWidgetId.encode(sink)
                sink.int(restored.value)
                sink.int(options.value)
                sink.int(source.value)
            }
        }
    }

    private fun StructureState.encode(sink: Digest.DigestSink) {
        when (this) {
            StructureState.Plain -> sink.byte(STRUCTURE_PLAIN)

            is StructureState.FolderMembers -> {
                sink.byte(STRUCTURE_FOLDER_MEMBERS)
                sink.int(members.size)
                members.sortedBy { it.rank }.forEach { it.encode(sink) }
            }

            is StructureState.AppPairMembers -> {
                sink.byte(STRUCTURE_APP_PAIR_MEMBERS)
                first.encode(sink)
                second.encode(sink)
                sink.byte(firstStage.ordinal)
                sink.byte(secondStage.ordinal)
                snapPosition.encode(sink)
            }
        }
    }

    private fun RankedMember.encode(sink: Digest.DigestSink) {
        item.encode(sink)
        sink.int(rank)
    }

    private fun OptionalText.encode(sink: Digest.DigestSink) {
        when (this) {
            OptionalText.Absent -> sink.byte(OPTIONAL_ABSENT)

            is OptionalText.Present -> {
                sink.byte(OPTIONAL_PRESENT)
                sink.text(value)
            }
        }
    }

    private fun OptionalBytes.encode(sink: Digest.DigestSink) {
        when (this) {
            OptionalBytes.Absent -> sink.byte(OPTIONAL_ABSENT)

            is OptionalBytes.Present -> {
                sink.byte(OPTIONAL_PRESENT)
                sink.bytes(value.asByteArray())
            }
        }
    }

    private fun OptionalSnapPosition.encode(sink: Digest.DigestSink) {
        when (this) {
            OptionalSnapPosition.Absent -> sink.byte(OPTIONAL_ABSENT)

            is OptionalSnapPosition.Present -> {
                sink.byte(OPTIONAL_PRESENT)
                token.encode(sink)
            }
        }
    }

    private fun ModifiedAtMillis.encode(sink: Digest.DigestSink) {
        sink.long(value)
    }

    private fun GridCell.encode(sink: Digest.DigestSink) {
        sink.int(x)
        sink.int(y)
    }

    private fun GridSpan.encode(sink: Digest.DigestSink) {
        sink.int(width)
        sink.int(height)
    }

    private fun PageOrder.encode(sink: Digest.DigestSink) {
        sink.text(value)
    }

    private fun ItemId.encode(sink: Digest.DigestSink) {
        sink.text(value)
    }

    private fun PageId.encode(sink: Digest.DigestSink) {
        sink.text(value)
    }

    private fun ProfileId.encode(sink: Digest.DigestSink) {
        sink.text(value)
    }

    private fun FolderId.encode(sink: Digest.DigestSink) {
        sink.text(value)
    }

    private fun AppPairId.encode(sink: Digest.DigestSink) {
        sink.text(value)
    }

    private fun ComponentKey.encode(sink: Digest.DigestSink) {
        sink.text(value)
    }

    private fun PackageName.encode(sink: Digest.DigestSink) {
        sink.text(value)
    }

    private fun ShortcutId.encode(sink: Digest.DigestSink) {
        sink.text(value)
    }

    private fun SnapPositionToken.encode(sink: Digest.DigestSink) {
        sink.text(value)
    }

    private fun AppWidgetId.encode(sink: Digest.DigestSink) {
        sink.int(value)
    }

    private fun ContainerCode.encode(sink: Digest.DigestSink) {
        sink.int(value)
    }

    private fun KindCode.encode(sink: Digest.DigestSink) {
        sink.int(value)
    }

    private fun NewPageOrdinal.encode(sink: Digest.DigestSink) {
        sink.int(value)
    }

    private fun NewFolderOrdinal.encode(sink: Digest.DigestSink) {
        sink.int(value)
    }

    @Suppress("unused")
    private fun DeviceOrientation.encodeStable(sink: Digest.DigestSink) {
        sink.byte(ordinal)
    }

    @Suppress("unused")
    private fun WidgetRestoreState.encodeStable(sink: Digest.DigestSink) {
        sink.int(value)
    }

    @Suppress("unused")
    private fun WidgetOptions.encodeStable(sink: Digest.DigestSink) {
        sink.int(value)
    }

    @Suppress("unused")
    private fun WidgetSource.encodeStable(sink: Digest.DigestSink) {
        sink.int(value)
    }

    @Suppress("unused")
    private fun ImmutableByteString.encodeStable(sink: Digest.DigestSink) {
        sink.bytes(asByteArray())
    }

    private const val ACTION_PRESERVE: Int = 0
    private const val ACTION_UPDATE: Int = 1
    private const val ACTION_INSERT: Int = 2
    private const val REF_PERSISTENT_ITEM: Int = 0
    private const val REF_PLANNED_CANDIDATE: Int = 1
    private const val REF_PLANNED_FOLDER: Int = 2
    private const val REF_PERSISTENT_PAGE: Int = 0
    private const val REF_PLANNED_PAGE: Int = 1
    private const val PLACEMENT_WORKSPACE: Int = 0
    private const val PLACEMENT_DOCK: Int = 1
    private const val PLACEMENT_FOLDER_CHILD: Int = 2
    private const val PLACEMENT_APP_PAIR_CHILD: Int = 3
    private const val PLACEMENT_UNSUPPORTED: Int = 4
    private const val KIND_APPLICATION: Int = 0
    private const val KIND_DEEP_SHORTCUT: Int = 1
    private const val KIND_SHORTCUT_LEGACY: Int = 2
    private const val KIND_FOLDER: Int = 3
    private const val KIND_APPWIDGET: Int = 4
    private const val KIND_CUSTOM_APPWIDGET: Int = 5
    private const val KIND_APP_PAIR: Int = 6
    private const val KIND_UNKNOWN: Int = 7
    private const val TARGET_APP: Int = 0
    private const val TARGET_SHORTCUT: Int = 1
    private const val TARGET_LEGACY_SHORTCUT: Int = 2
    private const val TARGET_WIDGET: Int = 3
    private const val TARGET_FOLDER: Int = 4
    private const val TARGET_APP_PAIR: Int = 5
    private const val WIDGET_NONE: Int = 0
    private const val WIDGET_PRESENT: Int = 1
    private const val STRUCTURE_PLAIN: Int = 0
    private const val STRUCTURE_FOLDER_MEMBERS: Int = 1
    private const val STRUCTURE_APP_PAIR_MEMBERS: Int = 2
    private const val OPTIONAL_ABSENT: Int = 0
    private const val OPTIONAL_PRESENT: Int = 1
}
