package app.lawnchair.organizer.application.protocol

import app.lawnchair.organizer.application.actions.IntendedStateResolution
import app.lawnchair.organizer.application.public.ApplicationItemRef
import app.lawnchair.organizer.application.public.ApplicationPageRef
import app.lawnchair.organizer.application.public.CanonicalItemState
import app.lawnchair.organizer.application.public.LayoutState
import app.lawnchair.organizer.application.public.PageState
import app.lawnchair.organizer.application.public.PlacementState
import app.lawnchair.organizer.application.public.RankedMember
import app.lawnchair.organizer.application.public.StructureState
import app.lawnchair.organizer.application.public.ValidatedLayoutPlan
import app.lawnchair.organizer.planning.FolderId
import app.lawnchair.organizer.planning.TargetKey

/**
 * Protocol-owned validation of a writer's materialized intended state.
 *
 * A Launcher writer may allocate persistent identities for planned items and
 * pages, and the schema may omit unreferenced empty pages. Issue #164 adds
 * exactly one further permitted transformation: the resolved items are
 * presented in the shared canonical `ItemId` byte order (the order the DB
 * capture produces), so the writer's intended state and an independent
 * recapture compare equal at A7. The ordering comes from the same
 * `CanonicalItemOrder` authority the capture uses — never from the writer.
 * No other state transformation is permitted. Keeping the mapping explicit
 * here prevents a writer-provided copy of the original plan from becoming the
 * source of truth for this invariant.
 */
internal object MaterializedStateValidator {

    fun matches(
        plan: ValidatedLayoutPlan,
        writeSet: MaterializedWriteSet,
    ): Boolean {
        val mapping = writeSet.identityMapping
        if (!mappingMatchesPlan(plan, mapping)) return false
        val resolved = try {
            resolve(plan.intendedState, mapping)
        } catch (_: RuntimeException) {
            return false
        }
        val canonicalResolved = IntendedStateResolution.finalizeCanonicalOrder(resolved) ?: return false
        return writeSet.intendedState == canonicalResolved ||
            writeSet.intendedState == normalizePages(canonicalResolved)
    }

    private fun mappingMatchesPlan(
        plan: app.lawnchair.organizer.application.public.ValidatedLayoutPlan,
        mapping: MaterializationIdentityMapping,
    ): Boolean {
        val plannedItems = plan.intendedState.items
            .map { it.ref }
            .filter { it !is ApplicationItemRef.PersistentItem }
            .toSet()
        if (mapping.items.keys != plannedItems) return false
        if (mapping.items.values.toSet().size != mapping.items.size) return false
        if (mapping.items.values.any { value ->
                plan.intendedState.items.any { it.ref == value }
            }
        ) {
            return false
        }

        val plannedPages = plan.intendedState.pages
            .map { it.ref }
            .filterIsInstance<ApplicationPageRef.PlannedPage>()
            .toSet()
        if (mapping.pages.keys != plannedPages) return false
        if (mapping.pages.values.toSet().size != mapping.pages.size) return false
        if (mapping.pages.values.any { value ->
                plan.intendedState.pages.any { it.ref == value }
            }
        ) {
            return false
        }
        return true
    }

    private fun resolve(
        state: LayoutState,
        mapping: MaterializationIdentityMapping,
    ): LayoutState {
        fun itemRef(ref: ApplicationItemRef): ApplicationItemRef = when (ref) {
            is ApplicationItemRef.PersistentItem -> ref

            is ApplicationItemRef.PlannedCandidate,
            is ApplicationItemRef.PlannedFolder,
            -> checkNotNull(mapping.items[ref]) { "Missing planned item identity for $ref" }
        }

        fun pageRef(ref: ApplicationPageRef): ApplicationPageRef = when (ref) {
            is ApplicationPageRef.PersistentPage -> ref

            is ApplicationPageRef.PlannedPage -> checkNotNull(mapping.pages[ref]) {
                "Missing planned page identity for $ref"
            }
        }

        fun placement(placement: PlacementState): PlacementState = when (placement) {
            is PlacementState.Workspace -> placement.copy(page = pageRef(placement.page))

            is PlacementState.Dock,
            is PlacementState.UnsupportedContainer,
            -> placement

            is PlacementState.FolderChild -> placement.copy(parent = itemRef(placement.parent))

            is PlacementState.AppPairChild -> placement.copy(parent = itemRef(placement.parent))
        }

        fun structure(structure: StructureState): StructureState = when (structure) {
            StructureState.Plain -> structure

            is StructureState.FolderMembers -> structure.copy(
                members = structure.members.map { RankedMember(itemRef(it.item), it.rank) },
            )

            is StructureState.AppPairMembers -> structure.copy(
                members = structure.members.map { it.copy(item = itemRef(it.item)) },
            )
        }

        fun targetKey(ref: ApplicationItemRef, target: TargetKey): TargetKey = when {
            ref is ApplicationItemRef.PlannedFolder && target is TargetKey.FolderKey -> {
                val persistent = checkNotNull(mapping.items[ref])
                target.copy(folderId = FolderId(persistent.itemId.value))
            }

            else -> target
        }

        return LayoutState(
            pages = state.pages.map { PageState(pageRef(it.ref), it.order) },
            profiles = state.profiles,
            deviceCapabilities = state.deviceCapabilities,
            items = state.items.map { item: CanonicalItemState ->
                item.copy(
                    ref = itemRef(item.ref),
                    targetKey = targetKey(item.ref, item.targetKey),
                    placement = placement(item.placement),
                    structure = structure(item.structure),
                )
            },
            // Platform-owned reservations are immutable, non-item context.
            // Identity materialization may only resolve planned references;
            // it must not erase reservation constraints before A5/A7.
            reservedWorkspaceRegions = state.reservedWorkspaceRegions,
        )
    }

    private fun normalizePages(state: LayoutState): LayoutState {
        val referencedPages = state.items.mapNotNull { item ->
            (item.placement as? PlacementState.Workspace)?.page
        }.toSet()
        val reservationPages = state.reservedWorkspaceRegions.map { reservation ->
            ApplicationPageRef.PersistentPage(reservation.page.pageId)
        }.toSet()
        return state.copy(
            pages = state.pages.filter { page ->
                page.ref in referencedPages || page.ref in reservationPages
            },
        )
    }
}
