package app.lawnchair.organizer.application.actions

import app.lawnchair.organizer.application.adapter.ContextResourceCodec
import app.lawnchair.organizer.application.canonical.Digest
import app.lawnchair.organizer.application.canonical.PersistenceManifest
import app.lawnchair.organizer.application.canonical.PersistentResource
import app.lawnchair.organizer.application.canonical.PersistentRow

/**
 * Complete, row-accounted recovery intent. Unlike organizer apply, explicit
 * deletion is valid here because the user confirmed the exact reviewed
 * revision from which these actions are derived.
 */
data class RecoveryWriteSet(
    val targetManifest: PersistenceManifest,
    val actions: List<RecoveryAction>,
) {
    /** Stable digest over every typed action and all lossless row/resource fields. */
    fun digest(): ByteArray {
        val sink = Digest.tagged(Digest.Kind.RECOVERY_ACTION_SET).int(actions.size)
        actions.forEach { action ->
            when (action) {
                is RecoveryAction.PreserveRow -> sink.int(0).row(action.expected)
                is RecoveryAction.UpdateRow -> sink.int(1).row(action.expected).row(action.intended)
                is RecoveryAction.InsertRow -> sink.int(2).row(action.intended)
                is RecoveryAction.DeleteRow -> sink.int(3).row(action.expected)
                is RecoveryAction.PreserveResource -> sink.int(4).resource(action.expected)
            }
        }
        return sink.result()
    }
}

sealed interface RecoveryAction {
    data class PreserveRow(val expected: PersistentRow) : RecoveryAction
    data class UpdateRow(val expected: PersistentRow, val intended: PersistentRow) : RecoveryAction
    data class InsertRow(val intended: PersistentRow) : RecoveryAction
    data class DeleteRow(val expected: PersistentRow) : RecoveryAction
    data class PreserveResource(val expected: PersistentResource) : RecoveryAction
}

/** Pure deterministic manifest diff used by both explicit and automatic recovery. */
object RecoveryWriteSetMaterializer {
    fun materialize(
        target: PersistenceManifest,
        reviewedCurrent: PersistenceManifest,
    ): RecoveryWriteSet {
        val targetRows = target.rows.associateBy { it.rowId }
        val currentRows = reviewedCurrent.rows.associateBy { it.rowId }
        val rowActions = (targetRows.keys + currentRows.keys).sorted().map { id ->
            val expected = currentRows[id]
            val intended = targetRows[id]
            when {
                expected == null -> RecoveryAction.InsertRow(requireNotNull(intended))
                intended == null -> RecoveryAction.DeleteRow(expected)
                expected == intended -> RecoveryAction.PreserveRow(expected)
                else -> RecoveryAction.UpdateRow(expected, intended)
            }
        }

        require(ContextResourceCodec.recoveryContextsMatch(target.resources, reviewedCurrent.resources)) {
            "Externally-owned recovery context changed"
        }
        // Resources have no Launcher DB mutation. Their precondition must be
        // the user-reviewed current context; the target manifest is verified
        // only after restored rows recreate its logical page inventory.
        val resourceActions = reviewedCurrent.resources.map(RecoveryAction::PreserveResource)
        return RecoveryWriteSet(targetManifest = target, actions = rowActions + resourceActions)
    }
}

private fun Digest.DigestSink.row(row: PersistentRow): Digest.DigestSink = this
    .long(row.rowId)
    .text(row.itemId.value)
    .text(row.profileId.value)
    .int(row.containerCode.value)
    .optionalText(row.screenId?.value)
    .optionalInt(row.cellX)
    .optionalInt(row.cellY)
    .optionalInt(row.spanX)
    .optionalInt(row.spanY)
    .int(row.rank)
    .int(row.itemType.value)
    .optionalInt(row.appWidgetId?.value)
    .optionalText(row.appWidgetProvider?.value)
    .optionalBytes(row.iconBytes)
    .optionalText(row.title)
    .optionalText(row.intent)
    .optionalInt(row.restored)
    .optionalInt(row.options)
    .optionalInt(row.appWidgetSource)
    .long(row.modified)
    .int(row.organizerLockState.ordinal)
    .optionalInt(row.rawCell?.x)
    .optionalInt(row.rawCell?.y)
    .optionalInt(row.rawSpan?.width)
    .optionalInt(row.rawSpan?.height)

private fun Digest.DigestSink.resource(resource: PersistentResource): Digest.DigestSink = this
    .int(resource.kind.ordinal)
    .optionalText(resource.profileId?.value)
    .long(resource.order)
    .bytes(resource.payload)

private fun Digest.DigestSink.optionalInt(value: Int?): Digest.DigestSink = boolean(value != null).also { if (value != null) it.int(value) }

private fun Digest.DigestSink.optionalText(value: String?): Digest.DigestSink = boolean(value != null).also { if (value != null) it.text(value) }

private fun Digest.DigestSink.optionalBytes(value: ByteArray?): Digest.DigestSink = boolean(value != null).also { if (value != null) it.bytes(value) }
