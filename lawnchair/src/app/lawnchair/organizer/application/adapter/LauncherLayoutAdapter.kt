package app.lawnchair.organizer.application.adapter

import android.content.Context
import android.content.res.Configuration
import android.os.Process
import android.os.UserManager
import app.lawnchair.organizer.application.actions.RecoveryAction
import app.lawnchair.organizer.application.actions.RecoveryWriteSetMaterializer
import app.lawnchair.organizer.application.canonical.PersistenceManifest
import app.lawnchair.organizer.application.canonical.PersistentRow
import app.lawnchair.organizer.application.protocol.ApplyTxOutcome
import app.lawnchair.organizer.application.protocol.AuthoritativeClass
import app.lawnchair.organizer.application.protocol.CaptureId
import app.lawnchair.organizer.application.protocol.CapturedSnapshot
import app.lawnchair.organizer.application.protocol.FaultInjector
import app.lawnchair.organizer.application.protocol.LayoutWriterPort
import app.lawnchair.organizer.application.protocol.LeaseHandle
import app.lawnchair.organizer.application.protocol.MaterializationIdentityMapping
import app.lawnchair.organizer.application.protocol.MaterializedWriteSet
import app.lawnchair.organizer.application.protocol.ReloadResult
import app.lawnchair.organizer.application.protocol.WriteSetPreparation
import app.lawnchair.organizer.application.protocol.WriterKind
import app.lawnchair.organizer.application.public.ApplicationItemRef
import app.lawnchair.organizer.application.public.ApplicationPageRef
import app.lawnchair.organizer.application.public.ApplyAction
import app.lawnchair.organizer.application.public.CanonicalItemState
import app.lawnchair.organizer.application.public.DeviceCapabilities
import app.lawnchair.organizer.application.public.DeviceOrientation
import app.lawnchair.organizer.application.public.LayoutState
import app.lawnchair.organizer.application.public.OptionalBytes
import app.lawnchair.organizer.application.public.OptionalText
import app.lawnchair.organizer.application.public.PlacementState
import app.lawnchair.organizer.application.public.PreWriteRejection
import app.lawnchair.organizer.application.public.ProfileAvailability
import app.lawnchair.organizer.application.public.ProfileState
import app.lawnchair.organizer.application.public.RankedMember
import app.lawnchair.organizer.application.public.RecoveryPointId
import app.lawnchair.organizer.application.public.StructureState
import app.lawnchair.organizer.application.public.ValidatedLayoutPlan
import app.lawnchair.organizer.application.public.WidgetState
import app.lawnchair.organizer.application.revision.RevisionCalculator
import app.lawnchair.organizer.planning.AppWidgetId
import app.lawnchair.organizer.planning.ComponentKey
import app.lawnchair.organizer.planning.ContainerCode
import app.lawnchair.organizer.planning.FolderId
import app.lawnchair.organizer.planning.GridCell
import app.lawnchair.organizer.planning.GridSpan
import app.lawnchair.organizer.planning.ItemId
import app.lawnchair.organizer.planning.KindCode
import app.lawnchair.organizer.planning.PageId
import app.lawnchair.organizer.planning.ProfileId
import app.lawnchair.organizer.planning.TargetKey
import com.android.launcher3.InvariantDeviceProfile
import com.android.launcher3.LauncherModel
import com.android.launcher3.LauncherSettings.Favorites
import com.android.launcher3.OrganizerModelReloadAdapter
import com.android.launcher3.model.LayoutWriteCoordinator
import com.android.launcher3.model.ModelDbController
import com.android.launcher3.pm.UserCache

/** Production Launcher DB implementation of the Issue #14 application writer port. */
internal class LauncherLayoutAdapter(
    context: Context,
    private val controller: ModelDbController,
    private val model: LauncherModel,
) : LayoutWriterPort {
    private val appContext = context.applicationContext
    private val reload = OrganizerModelReloadAdapter(model, android.os.Handler(appContext.mainLooper))

    override fun tryAcquireLease(kind: WriterKind, token: Long): LeaseHandle? = LayoutWriteCoordinator.getInstance().tryAcquire(kind.bridge())?.let(::CoordinatorLease)

    override fun <T> withLease(
        kind: WriterKind,
        token: Long,
        block: (LeaseHandle) -> T,
    ): T? {
        val lease = tryAcquireLease(kind, token) ?: return null
        return try {
            block(lease)
        } finally {
            lease.close()
        }
    }

    override fun captureCurrent(captureId: CaptureId): CapturedSnapshot = capture()
    override fun recaptureDb(): CapturedSnapshot = capture()

    private fun capture(): CapturedSnapshot {
        val pages = dbDesktopPageIds()
        val userCache = UserCache.INSTANCE.get(appContext)
        val userManager = requireNotNull(appContext.getSystemService(UserManager::class.java)) {
            "UserManager unavailable; canonical profile availability cannot be captured"
        }
        val profiles = (userCache.userProfiles + Process.myUserHandle()).distinct().map { user ->
            val serial = userCache.getSerialNumberForUser(user)
            require(serial >= 0L) { "User profile has no stable serial" }
            ProfileState(
                ProfileId(serial.toString()),
                if (userManager.isUserUnlocked(user) && !userManager.isQuietModeEnabled(user)) {
                    ProfileAvailability.AVAILABLE
                } else {
                    ProfileAvailability.UNAVAILABLE
                },
            )
        }.sortedBy { it.id }
        val captured = RowManifestCodec.capture(controller.db, capabilities(), pages, profiles)
        val revision = RevisionCalculator.revisionOf(captured.state)
        return CapturedSnapshot(
            captured.state,
            captured.manifest,
            revision,
            RevisionCalculator.classificationDigestOf(captured.state),
        )
    }

    // Issue #14: deterministic desktop page inventory derived from committed
    // favorites rows, ordered ascending. Model-only empty pages have no row and
    // are therefore excluded by construction (spec "Recovery record and lifecycle").
    private fun dbDesktopPageIds(): List<PageId> {
        val sql = "SELECT DISTINCT ${Favorites.SCREEN} FROM ${Favorites.TABLE_NAME} " +
            "WHERE ${Favorites.CONTAINER} = ? ORDER BY ${Favorites.SCREEN}"
        return controller.db.rawQuery(sql, arrayOf(Favorites.CONTAINER_DESKTOP.toString())).use { c ->
            val pages = ArrayList<PageId>(c.count)
            while (c.moveToNext()) pages += PageId(c.getLong(0).toString())
            pages
        }
    }

    override fun prepareApplyWriteSet(
        capture: CapturedSnapshot,
        plan: ValidatedLayoutPlan,
    ): WriteSetPreparation {
        if (capture.revision != plan.sourceRevision || capture.layoutState != plan.sourceState) {
            return WriteSetPreparation.InvalidPlan
        }
        val current = capture.manifest.rows.associateBy { it.itemId }
        var nextId = capture.manifest.rows.maxOfOrNull { it.rowId } ?: 0L
        val plannedIds = mutableMapOf<ApplicationItemRef, Long>()
        val plannedPages = mutableMapOf<ApplicationPageRef.PlannedPage, Long>()
        val maxPage = capture.manifest.rows.mapNotNull { it.screenId?.value?.toLongOrNull() }.maxOrNull() ?: -1L
        plan.intendedState.pages.map { it.ref }.filterIsInstance<ApplicationPageRef.PlannedPage>()
            .sortedBy { it.ordinal.value }.forEachIndexed { index, ref -> plannedPages[ref] = maxPage + index + 1L }
        val rows = mutableListOf<PersistentRow>()
        try {
            // Allocate every planned item before materializing rows. A folder child
            // can precede its planned folder in the canonical item order, so IDs
            // must not depend on the order in which rowFor happens to be called.
            for (item in plan.intendedState.items) {
                if (item.ref !is ApplicationItemRef.PersistentItem) {
                    if (nextId == Long.MAX_VALUE) return WriteSetPreparation.IdentityExhausted
                    nextId += 1L
                    plannedIds[item.ref] = nextId
                }
            }
            for (item in plan.intendedState.items) {
                val base = when (val ref = item.ref) {
                    is ApplicationItemRef.PersistentItem -> current[ref.itemId]
                        ?: return WriteSetPreparation.InvalidPlan

                    else -> null
                }
                rows += rowFor(item, base, plannedIds, plannedPages)
            }
        } catch (_: IllegalArgumentException) {
            return WriteSetPreparation.InvalidPlan
        }
        val resolvedState = plan.intendedState.resolvePersistentReferences(plannedIds, plannedPages)
        // Launcher schema 33 has no persistent empty-page table. The DB
        // capture therefore reports only pages still referenced by desktop
        // rows, so the post-apply canonical state must use that same inventory.
        val referencedPages = rows.asSequence()
            .filter { it.containerCode.value == Favorites.CONTAINER_DESKTOP }
            .mapNotNull { it.screenId }
            .toSet()
        val materializedState = resolvedState.copy(
            pages = resolvedState.pages.filter { page ->
                (page.ref as? ApplicationPageRef.PersistentPage)?.pageId in referencedPages
            },
        )
        val intendedManifest = PersistenceManifest(
            capture.manifest.formatVersion,
            capture.manifest.schemaVersion,
            rows.size,
            rows.sortedBy { it.rowId },
            capture.manifest.resources,
            rows.maxOfOrNull { it.modified } ?: 0L,
        )
        return WriteSetPreparation.Ready(
            MaterializedWriteSet(
                plan.actions,
                materializedState,
                intendedManifest,
                RevisionCalculator.actionSetDigestOf(plan.actions),
                sourceRevision = capture.revision,
                identityMapping = MaterializationIdentityMapping(
                    items = plannedIds.mapValues { (_, id) -> ApplicationItemRef.PersistentItem(ItemId(id.toString())) },
                    pages = plannedPages.mapValues { (_, id) -> ApplicationPageRef.PersistentPage(PageId(id.toString())) },
                ),
            ),
        )
    }

    override fun prepareRecoveryWriteSet(
        targetManifest: PersistenceManifest,
        reviewedCurrent: CapturedSnapshot,
    ): WriteSetPreparation {
        val recovery = try {
            RecoveryWriteSetMaterializer.materialize(targetManifest, reviewedCurrent.manifest)
        } catch (_: IllegalArgumentException) {
            return WriteSetPreparation.ContextMismatch
        }
        return WriteSetPreparation.Ready(
            MaterializedWriteSet(
                emptyList(),
                reviewedCurrent.layoutState,
                recovery.targetManifest,
                recovery.digest(),
                recovery.actions,
                sourceRevision = reviewedCurrent.revision,
            ),
        )
    }

    override fun applyWriteSet(
        lease: LeaseHandle,
        writeSet: MaterializedWriteSet,
        pointId: RecoveryPointId?,
        faults: FaultInjector,
    ): ApplyTxOutcome {
        // A5 starts the transaction first; the authoritative reread and every exact
        // precondition check therefore observe the same locked state that A6 mutates.
        val tx = controller.newTransaction(lease.token)
        var commitCalled = false
        return try {
            val db = tx.db
            val before = capture()
            if (before.revision != writeSet.sourceRevision) {
                tx.close()
                return ApplyTxOutcome.PreconditionFailed(PreWriteRejection.STALE_REVISION)
            }
            if (!preconditionsHold(before, writeSet)) {
                tx.close()
                return ApplyTxOutcome.PreconditionFailed(PreWriteRejection.EXACT_PRECONDITION_FAILED)
            }
            if (writeSet.recoveryActions.isNotEmpty()) {
                writeSet.recoveryActions.forEachIndexed { index, action ->
                    faults.beforeLauncherWrite(index, pointId)
                    when (action) {
                        is RecoveryAction.PreserveRow, is RecoveryAction.PreserveResource -> Unit

                        is RecoveryAction.UpdateRow -> db.update(
                            Favorites.TABLE_NAME,
                            RowManifestCodec.values(action.intended),
                            "${Favorites._ID}=?",
                            arrayOf(action.expected.rowId.toString()),
                        )

                        is RecoveryAction.InsertRow -> db.insertOrThrow(
                            Favorites.TABLE_NAME,
                            null,
                            RowManifestCodec.values(action.intended),
                        )

                        is RecoveryAction.DeleteRow -> db.delete(
                            Favorites.TABLE_NAME,
                            "${Favorites._ID}=?",
                            arrayOf(action.expected.rowId.toString()),
                        )
                    }
                    faults.afterLauncherWrite(index, pointId)
                }
            } else {
                val oldIds = before.manifest.rows.map { it.rowId }.toHashSet()
                writeSet.intendedManifest.rows.forEachIndexed { index, row ->
                    faults.beforeLauncherWrite(index, pointId)
                    if (row.rowId in oldIds) {
                        db.update(
                            Favorites.TABLE_NAME,
                            RowManifestCodec.values(row),
                            "${Favorites._ID}=?",
                            arrayOf(row.rowId.toString()),
                        )
                    } else {
                        db.insertOrThrow(Favorites.TABLE_NAME, null, RowManifestCodec.values(row))
                    }
                    faults.afterLauncherWrite(index, pointId)
                }
            }
            when (faults.atTransactionClose(pointId)) {
                FaultInjector.TransactionCloseDirective.PROCEED -> Unit

                FaultInjector.TransactionCloseDirective.THROW_SQLITE_EXCEPTION ->
                    throw android.database.sqlite.SQLiteException("Injected transaction-close failure")

                FaultInjector.TransactionCloseDirective.SIMULATE_PROCESS_DEATH -> {
                    tx.close()
                    return ApplyTxOutcome.OutcomeUnknown
                }
            }
            tx.commit()
            commitCalled = true
            tx.close()
            controller.refreshMaxItemIdFromCommittedRows()
            ApplyTxOutcome.Committed
        } catch (t: Throwable) {
            runCatching { tx.close() }
            controller.refreshMaxItemIdFromCommittedRows()
            if (commitCalled) ApplyTxOutcome.OutcomeUnknown else ApplyTxOutcome.Failed(t)
        }
    }

    private fun preconditionsHold(snapshot: CapturedSnapshot, writeSet: MaterializedWriteSet): Boolean {
        if (writeSet.recoveryActions.isNotEmpty()) {
            val rows = snapshot.manifest.rows.associateBy { it.rowId }
            val resources = snapshot.manifest.resources.associateBy {
                Triple(it.kind, it.profileId, it.order)
            }
            return writeSet.recoveryActions.all { action ->
                when (action) {
                    is RecoveryAction.PreserveRow -> rows[action.expected.rowId] == action.expected

                    is RecoveryAction.UpdateRow -> rows[action.expected.rowId] == action.expected

                    is RecoveryAction.DeleteRow -> rows[action.expected.rowId] == action.expected

                    is RecoveryAction.InsertRow -> action.intended.rowId !in rows

                    is RecoveryAction.PreserveResource -> resources[
                        Triple(action.expected.kind, action.expected.profileId, action.expected.order),
                    ] == action.expected
                }
            }
        }
        val items = snapshot.layoutState.items.associateBy { it.ref }
        return writeSet.actions.all { action ->
            when (action) {
                is ApplyAction.Preserve -> items[action.ref] == action.expected
                is ApplyAction.Update -> items[action.ref] == action.expected
                is ApplyAction.Insert -> action.ref !in items
            }
        }
    }

    override fun requestCorrelatedReload(lease: LeaseHandle): ReloadResult = when (
        reload.requestAndWait(lease.token)
    ) {
        OrganizerModelReloadAdapter.Outcome.COMPLETED -> ReloadResult.Completed
        OrganizerModelReloadAdapter.Outcome.SUPERSEDED -> ReloadResult.Superseded
        OrganizerModelReloadAdapter.Outcome.TIMEOUT -> ReloadResult.Timeout
        OrganizerModelReloadAdapter.Outcome.FAILED -> ReloadResult.Failed
    }

    override fun classifyAuthoritativeState(
        preDigest: ByteArray,
        intendedPostDigest: ByteArray,
        recoveryTargetDigest: ByteArray?,
        reviewedCurrentDigest: ByteArray?,
    ): AuthoritativeClass {
        val digest = capture().digest
        return when {
            digest.contentEquals(preDigest) -> AuthoritativeClass.PRE_STATE
            digest.contentEquals(intendedPostDigest) -> AuthoritativeClass.INTENDED_POST_STATE
            recoveryTargetDigest != null && digest.contentEquals(recoveryTargetDigest) -> AuthoritativeClass.RECOVERY_TARGET
            reviewedCurrentDigest != null && digest.contentEquals(reviewedCurrentDigest) -> AuthoritativeClass.REVIEWED_CURRENT_STATE
            else -> AuthoritativeClass.NEITHER
        }
    }

    private fun capabilities(): DeviceCapabilities {
        val idp = InvariantDeviceProfile.INSTANCE.get(appContext)
        val orientation = if (appContext.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            DeviceOrientation.LANDSCAPE
        } else {
            DeviceOrientation.PORTRAIT
        }
        return DeviceCapabilities(
            columns = idp.numColumns,
            rows = idp.numRows,
            hotseatSlots = idp.numDatabaseHotseatIcons,
            folderMaxColumns = idp.numFolderColumns.maxOrNull()
                ?: error("Missing folder column capability"),
            folderMaxRows = idp.numFolderRows.maxOrNull()
                ?: error("Missing folder row capability"),
            orientation = orientation,
        )
    }

    private class CoordinatorLease(private val delegate: LayoutWriteCoordinator.Lease) : LeaseHandle {
        override val kind = delegate.kind().port()
        override val token = delegate.token()
        override fun close() = delegate.close()
    }
}

private fun WriterKind.bridge() = LayoutWriteCoordinator.OwnerKind.valueOf(name)
private fun LayoutWriteCoordinator.OwnerKind.port() = WriterKind.valueOf(name)

private fun LayoutState.resolvePersistentReferences(
    plannedIds: Map<ApplicationItemRef, Long>,
    plannedPages: Map<ApplicationPageRef.PlannedPage, Long>,
): LayoutState {
    fun itemRef(ref: ApplicationItemRef): ApplicationItemRef = when (ref) {
        is ApplicationItemRef.PersistentItem -> ref
        else -> ApplicationItemRef.PersistentItem(ItemId(requireNotNull(plannedIds[ref]).toString()))
    }

    fun pageRef(ref: ApplicationPageRef): ApplicationPageRef = when (ref) {
        is ApplicationPageRef.PersistentPage -> ref

        is ApplicationPageRef.PlannedPage -> ApplicationPageRef.PersistentPage(
            PageId(requireNotNull(plannedPages[ref]).toString()),
        )
    }

    fun placement(placement: PlacementState): PlacementState = when (placement) {
        is PlacementState.Workspace -> placement.copy(page = pageRef(placement.page))
        is PlacementState.FolderChild -> placement.copy(parent = itemRef(placement.parent))
        is PlacementState.AppPairChild -> placement.copy(parent = itemRef(placement.parent))
        is PlacementState.Dock, is PlacementState.UnsupportedContainer -> placement
    }

    fun structure(structure: StructureState): StructureState = when (structure) {
        StructureState.Plain -> structure

        is StructureState.FolderMembers -> structure.copy(
            members = structure.members.map { RankedMember(itemRef(it.item), it.rank) },
        )

        is StructureState.AppPairMembers -> structure.copy(
            first = itemRef(structure.first),
            second = itemRef(structure.second),
        )
    }

    fun targetKey(ref: ApplicationItemRef, target: TargetKey): TargetKey = when {
        ref is ApplicationItemRef.PlannedFolder && target is TargetKey.FolderKey ->
            TargetKey.FolderKey(FolderId(requireNotNull(plannedIds[ref]).toString()))

        else -> target
    }

    return copy(
        pages = pages.map { it.copy(ref = pageRef(it.ref)) },
        items = items.map { item ->
            val resolvedRef = itemRef(item.ref)
            item.copy(
                ref = resolvedRef,
                targetKey = targetKey(item.ref, item.targetKey),
                placement = placement(item.placement),
                structure = structure(item.structure),
            )
        },
    )
}

private fun rowFor(
    item: CanonicalItemState,
    base: PersistentRow?,
    plannedIds: Map<ApplicationItemRef, Long>,
    plannedPages: Map<ApplicationPageRef.PlannedPage, Long>,
): PersistentRow {
    val id = when (val ref = item.ref) {
        is ApplicationItemRef.PersistentItem -> ref.itemId.value.toLong()
        else -> requireNotNull(plannedIds[ref])
    }
    val placement = item.placement
    val container: Int
    val screen: PageId?
    val cell: GridCell?
    val span: GridSpan?
    val rank: Int
    when (placement) {
        is PlacementState.Workspace -> {
            container = Favorites.CONTAINER_DESKTOP
            screen = PageId(
                when (val page = placement.page) {
                    is ApplicationPageRef.PersistentPage -> page.pageId.value
                    is ApplicationPageRef.PlannedPage -> requireNotNull(plannedPages[page]).toString()
                },
            )
            cell = placement.cell
            span = placement.span
            rank = base?.rank ?: 0
        }

        is PlacementState.Dock -> {
            container = Favorites.CONTAINER_HOTSEAT
            screen = null
            cell = null
            span = null
            rank = placement.rank
        }

        is PlacementState.FolderChild -> {
            container = when (val parent = placement.parent) {
                is ApplicationItemRef.PersistentItem -> parent.itemId.value.toInt()
                else -> requireNotNull(plannedIds[parent]).toInt()
            }
            screen = null
            cell = null
            span = null
            rank = placement.rank
        }

        is PlacementState.AppPairChild -> {
            container = when (val parent = placement.parent) {
                is ApplicationItemRef.PersistentItem -> parent.itemId.value.toInt()
                else -> requireNotNull(plannedIds[parent]).toInt()
            }
            screen = null
            cell = null
            span = null
            rank = base?.rank ?: 0
        }

        is PlacementState.UnsupportedContainer -> {
            container = placement.code.value
            screen = base?.screenId
            cell = base?.rawCell
            span = base?.rawSpan
            rank = base?.rank ?: 0
        }
    }
    val widget = item.widget as? WidgetState.Widget
    return PersistentRow(
        id, ItemId(id.toString()), item.profile, ContainerCode(container), screen,
        cell?.x, cell?.y, span?.width, span?.height, rank,
        base?.itemType ?: KindCode(
            when (item.kind) {
                app.lawnchair.organizer.application.public.CanonicalItemKind.Application -> Favorites.ITEM_TYPE_APPLICATION
                app.lawnchair.organizer.application.public.CanonicalItemKind.ShortcutLegacy -> Favorites.ITEM_TYPE_SHORTCUT
                app.lawnchair.organizer.application.public.CanonicalItemKind.Folder -> Favorites.ITEM_TYPE_FOLDER
                app.lawnchair.organizer.application.public.CanonicalItemKind.AppWidget -> Favorites.ITEM_TYPE_APPWIDGET
                app.lawnchair.organizer.application.public.CanonicalItemKind.CustomAppWidget -> Favorites.ITEM_TYPE_CUSTOM_APPWIDGET
                app.lawnchair.organizer.application.public.CanonicalItemKind.DeepShortcut -> Favorites.ITEM_TYPE_DEEP_SHORTCUT
                app.lawnchair.organizer.application.public.CanonicalItemKind.AppPair -> Favorites.ITEM_TYPE_APP_PAIR
                is app.lawnchair.organizer.application.public.CanonicalItemKind.Unknown -> item.kind.code.value
            },
        ),
        widget?.appWidgetId, widget?.provider,
        (item.icon as? OptionalBytes.Present)?.value?.asByteArray(),
        (item.title as? OptionalText.Present)?.value, (item.intent as? OptionalText.Present)?.value,
        widget?.restored?.value ?: base?.restored ?: 0,
        widget?.options?.value ?: base?.options ?: 0,
        widget?.source?.value ?: base?.appWidgetSource ?: -1,
        item.modified.value, item.lockState,
        cell, span,
    )
}
