package app.lawnchair.organizer.application.adapter

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Process
import android.os.UserManager
import app.lawnchair.organizer.PreferenceWorkspaceOverlapToleranceSource
import app.lawnchair.organizer.application.actions.IntendedStateResolution
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
import app.lawnchair.organizer.application.public.CanonicalItemKind
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
import app.lawnchair.organizer.application.public.RecoveryPointId
import app.lawnchair.organizer.application.public.ValidatedLayoutPlan
import app.lawnchair.organizer.application.public.WidgetState
import app.lawnchair.organizer.application.revision.RevisionCalculator
import app.lawnchair.organizer.planning.AppWidgetId
import app.lawnchair.organizer.planning.ComponentKey
import app.lawnchair.organizer.planning.ContainerCode
import app.lawnchair.organizer.planning.GridCell
import app.lawnchair.organizer.planning.GridSpan
import app.lawnchair.organizer.planning.ItemId
import app.lawnchair.organizer.planning.KindCode
import app.lawnchair.organizer.planning.PageId
import app.lawnchair.organizer.planning.ProfileId
import app.lawnchair.organizer.planning.ReservationOverlapAcceptance
import app.lawnchair.organizer.planning.ReservedWorkspaceRegion
import app.lawnchair.organizer.planning.WorkspaceOverlapToleranceSource
import com.android.launcher3.InvariantDeviceProfile
import com.android.launcher3.LauncherModel
import com.android.launcher3.LauncherSettings.Favorites
import com.android.launcher3.OrganizerModelReloadAdapter
import com.android.launcher3.WorkspaceLayoutManager.FIRST_SCREEN_ID
import com.android.launcher3.config.FeatureFlags
import com.android.launcher3.model.LayoutWriteCoordinator
import com.android.launcher3.model.ModelDbController
import com.android.launcher3.pm.UserCache

/** Production Launcher DB implementation of the Issue #14 application writer port. */
internal class LauncherLayoutAdapter(
    context: Context,
    private val controller: ModelDbController,
    private val model: LauncherModel,
    // Issue #185 / ADR-0010: re-read inside every A5/recovery transaction so a
    // policy flip between compose and apply cannot let an intended state the
    // current loader would reject reach the DB.
    private val overlapToleranceSource: WorkspaceOverlapToleranceSource = PreferenceWorkspaceOverlapToleranceSource(context),
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
        val context = captureWorkspaceContext()
        val pages = dbDesktopPageIds(context.qsbEnabled)
        val userCache = UserCache.INSTANCE.get(appContext)
        val userManager = requireNotNull(appContext.getSystemService(UserManager::class.java)) {
            "UserManager unavailable; canonical profile availability cannot be captured"
        }
        val profiles = (userCache.userProfiles + Process.myUserHandle()).distinct().map { user ->
            val profileId = requireNotNull(canonicalProfileId(userCache, user)) {
                "User profile has no stable serial"
            }
            ProfileState(
                profileId,
                if (userManager.isUserUnlocked(user) && !userManager.isQuietModeEnabled(user)) {
                    ProfileAvailability.AVAILABLE
                } else {
                    ProfileAvailability.UNAVAILABLE
                },
            )
        }.sortedBy { it.id }
        val captured = RowManifestCodec.capture(
            db = controller.db,
            capabilities = context.capabilities,
            orderedPages = pages,
            profileInventory = profiles,
            reservedWorkspaceRegions = context.reservations,
        )
        val revision = RevisionCalculator.revisionOf(captured.state)
        return CapturedSnapshot(
            captured.state,
            captured.manifest,
            revision,
            RevisionCalculator.classificationDigestOf(captured.state),
        )
    }

    /**
     * Issue #155: normalize the canonical page authority using the same first-screen
     * fallback as Launcher model collection. The QSB feature value is captured before
     * reading DB rows and is never read again within this capture attempt.
     */
    private fun dbDesktopPageIds(qsbEnabled: Boolean): List<PageId> {
        val sql = "SELECT DISTINCT ${Favorites.SCREEN} FROM ${Favorites.TABLE_NAME} " +
            "WHERE ${Favorites.CONTAINER} = ? ORDER BY ${Favorites.SCREEN}"
        val pages = controller.db.rawQuery(sql, arrayOf(Favorites.CONTAINER_DESKTOP.toString())).use { c ->
            buildList {
                while (c.moveToNext()) add(PageId(c.getLong(0).toString()))
            }
        }.toMutableList()
        val firstScreen = PageId(FIRST_SCREEN_ID.toString())
        // SQL preserves Launcher DB's numeric screen ordering. First screen is
        // platform-authoritative and must lead whenever it is logical but rowless.
        return buildList {
            if (qsbEnabled || pages.isEmpty()) add(firstScreen)
            addAll(pages.filterNot { it == firstScreen })
        }.distinct()
    }

    /** One immutable platform context shared by LayoutState and PersistenceManifest. */
    private fun captureWorkspaceContext(): WorkspaceCaptureContext {
        val idp = InvariantDeviceProfile.INSTANCE.get(appContext)
        val capabilities = capabilities(idp)
        val qsbEnabled = FeatureFlags.topQsbOnFirstScreenEnabled(appContext)
        val reservations = if (qsbEnabled) {
            listOf(
                ReservedWorkspaceRegion(
                    page = app.lawnchair.organizer.planning.PageRef(PageId(FIRST_SCREEN_ID.toString())),
                    cell = GridCell(0, 0),
                    span = GridSpan(idp.numSearchContainerColumns, 1),
                ),
            )
        } else {
            emptyList()
        }
        return WorkspaceCaptureContext(capabilities, qsbEnabled, reservations)
    }

    private data class WorkspaceCaptureContext(
        val capabilities: DeviceCapabilities,
        val qsbEnabled: Boolean,
        val reservations: List<ReservedWorkspaceRegion>,
    )

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
        // Hotseat rows also carry their slot in screenId since Issue #136. Page
        // identities must be unique across both row-backed pages and logical
        // rowless pages such as Launcher’s FIRST_SCREEN_ID (Issue #155).
        val occupiedPageIds = capture.layoutState.pages.mapNotNull { page ->
            (page.ref as? ApplicationPageRef.PersistentPage)?.pageId?.value?.toLongOrNull()
        }.toMutableSet()
        var nextPageId = (occupiedPageIds.maxOrNull() ?: -1L)
        for (ref in plan.intendedState.pages.map { it.ref }.filterIsInstance<ApplicationPageRef.PlannedPage>()
            .sortedBy { it.ordinal.value }) {
            do {
                if (nextPageId == Long.MAX_VALUE) return WriteSetPreparation.IdentityExhausted
                nextPageId += 1L
            } while (nextPageId in occupiedPageIds)
            plannedPages[ref] = nextPageId
            occupiedPageIds += nextPageId
        }
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
        val resolvedState = IntendedStateResolution.resolveAndFinalize(
            plan.intendedState,
            plannedIds,
            plannedPages,
        ) ?: return WriteSetPreparation.InvalidPlan
        // Issue #155: the post-write capture retains only row-backed pages plus
        // the logical first screen. Apply the identical page normalization before
        // deriving either intended canonical state or its opaque context resource.
        val materializedState = normalizeMaterializedPages(resolvedState, rows)
        val materializedPages = materializedState.pages.map { page ->
            (page.ref as? ApplicationPageRef.PersistentPage)?.pageId
                ?: error("Persistent references must be resolved before manifest creation")
        }
        val intendedManifest = PersistenceManifest(
            capture.manifest.formatVersion,
            capture.manifest.schemaVersion,
            rows.size,
            rows.sortedBy { it.rowId },
            ContextResourceCodec.encode(
                profiles = materializedState.profiles,
                capabilities = materializedState.deviceCapabilities,
                pages = materializedPages,
                reservedWorkspaceRegions = materializedState.reservedWorkspaceRegions,
            ),
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
            if (
                !overlapAcceptanceHolds(
                    // Audit F1 (PR #186): evaluate the state this write would
                    // produce — the intended manifest for a normal apply and the
                    // recovery target for a recovery write set — never the
                    // current state, or a recovery could resurrect an overlapped
                    // row the loader already deleted.
                    writeSet.intendedManifest.rows,
                    before.layoutState.reservedWorkspaceRegions,
                    overlapToleranceSource.isOverlapTolerated(),
                )
            ) {
                tx.close()
                return ApplyTxOutcome.PreconditionFailed(PreWriteRejection.OVERLAP_POLICY_REJECTED)
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

    override fun requestCorrelatedReload(lease: LeaseHandle): ReloadResult {
        val result = reload.requestAndWaitWithSnapshot(lease.token)
        return when (result.outcome) {
            // Issue #152: a COMPLETED outcome without a capturable snapshot
            // fails closed inside the adapter, so it never reaches this mapping.
            OrganizerModelReloadAdapter.Outcome.COMPLETED -> ReloadResult.Completed(
                requireNotNull(result.snapshot) { "Completed reload without model snapshot" },
            )

            OrganizerModelReloadAdapter.Outcome.SUPERSEDED -> ReloadResult.Superseded

            OrganizerModelReloadAdapter.Outcome.TIMEOUT -> ReloadResult.Timeout

            OrganizerModelReloadAdapter.Outcome.FAILED -> ReloadResult.Failed
        }
    }

    /**
     * Issue #152: canonical launch identity of a legacy shortcut row — the
     * persisted DB intent re-serialized canonically; the model-side codec
     * derives the same form from the in-memory `WorkspaceItemInfo` intent, so
     * a transformed launch target can no longer compare equal.
     */
    override fun legacyLaunchIdentityOf(item: CanonicalItemState): String? {
        val kind = item.kind
        if (kind !is CanonicalItemKind.ShortcutLegacy && kind !is CanonicalItemKind.Unknown) return null
        val intentText = (item.intent as? OptionalText.Present)?.value ?: return null
        return try {
            canonicalLegacyLaunchUri(Intent.parseUri(intentText, 0))
        } catch (_: Exception) {
            // A row whose persisted intent no longer parses cannot have been
            // loaded with a usable in-memory intent either; both legs then
            // carry no comparable identity and the DB leg keeps full coverage.
            null
        }
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

    private fun capabilities(idp: InvariantDeviceProfile): DeviceCapabilities {
        // Spec #130: two-panel-ness must come from the same constructed
        // DeviceProfile authority the launcher UI uses; re-deriving it from a
        // raw flag would let capture diverge from the actual device state.
        val orientation = canonicalOrientation(
            isTwoPanels = idp.getDeviceProfile(appContext).isTwoPanels,
            configurationOrientation = appContext.resources.configuration.orientation,
        )
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

/**
 * Issue #185 / ADR-0010: the intended state (normal write set or recovery
 * target) must be acceptable under the currently active platform overlap
 * policy. An authoritative-reservation overlap that exists in the intended
 * state is only accepted when the loader keeps such items; otherwise the write
 * would be followed by a correlated reload that deletes the row and breaks
 * A7/recovery verification. Evaluated fresh inside every A5 transaction.
 */
internal fun overlapAcceptanceHolds(
    intendedRows: List<PersistentRow>,
    reservations: List<ReservedWorkspaceRegion>,
    overlapTolerated: Boolean,
): Boolean {
    if (reservations.isEmpty()) return true
    val intendedOverlaps = intendedRows.any { row ->
        row.containerCode.value == Favorites.CONTAINER_DESKTOP &&
            row.screenId != null && row.rawCell != null && row.rawSpan != null &&
            ReservationOverlapAcceptance.overlaps(row.screenId, row.rawCell, row.rawSpan, reservations)
    }
    return !intendedOverlaps || overlapTolerated
}

internal fun canonicalOrientation(
    isTwoPanels: Boolean,
    configurationOrientation: Int,
): DeviceOrientation = when {
    isTwoPanels && configurationOrientation == Configuration.ORIENTATION_LANDSCAPE -> {
        DeviceOrientation.TWO_PANEL_LANDSCAPE
    }

    isTwoPanels -> DeviceOrientation.TWO_PANEL_PORTRAIT

    configurationOrientation == Configuration.ORIENTATION_LANDSCAPE -> DeviceOrientation.LANDSCAPE

    else -> DeviceOrientation.PORTRAIT
}

private fun normalizeMaterializedPages(
    state: LayoutState,
    rows: List<PersistentRow>,
): LayoutState {
    val referencedPages = rows.asSequence()
        .filter { it.containerCode.value == Favorites.CONTAINER_DESKTOP }
        .mapNotNull { it.screenId }
        .toSet()
    val reservationPages = state.reservedWorkspaceRegions.map { it.page.pageId }.toSet()
    return state.copy(
        pages = state.pages.filter { page ->
            val persistent = page.ref as? ApplicationPageRef.PersistentPage
            persistent != null && (
                persistent.pageId in referencedPages ||
                    persistent.pageId.value == FIRST_SCREEN_ID.toString() ||
                    persistent.pageId in reservationPages
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
            // The slot lives in SCREEN. A preserved row captured with a
            // schema-NULL slot (read as slot 0 by the loader) must round-trip
            // as NULL instead of an invented 0; anything else carries its
            // explicit slot. RANK stays untouched so preserved rows are exact.
            screen = if (base?.screenId == null && placement.rank == 0) {
                null
            } else {
                PageId(placement.rank.toString())
            }
            cell = null
            span = null
            rank = base?.rank ?: 0
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
