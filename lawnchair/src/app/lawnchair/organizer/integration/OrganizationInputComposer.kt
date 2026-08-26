package app.lawnchair.organizer.integration

import app.lawnchair.organizer.application.protocol.CaptureId
import app.lawnchair.organizer.application.protocol.CapturedSnapshot
import app.lawnchair.organizer.application.protocol.LayoutWriterPort
import app.lawnchair.organizer.application.public.ApplicationItemRef
import app.lawnchair.organizer.application.public.ApplicationPageRef
import app.lawnchair.organizer.application.public.CanonicalItemKind
import app.lawnchair.organizer.application.public.ItemAvailability
import app.lawnchair.organizer.application.public.LayoutState
import app.lawnchair.organizer.application.public.OptionalSnapPosition
import app.lawnchair.organizer.application.public.OrganizerLockState
import app.lawnchair.organizer.application.public.PlacementState
import app.lawnchair.organizer.application.public.ProfileAvailability
import app.lawnchair.organizer.application.public.StructureState
import app.lawnchair.organizer.planning.AppPairId
import app.lawnchair.organizer.planning.AppPairMember
import app.lawnchair.organizer.planning.AppPairMetadata
import app.lawnchair.organizer.planning.AppPairRef
import app.lawnchair.organizer.planning.Availability
import app.lawnchair.organizer.planning.CapturedItem
import app.lawnchair.organizer.planning.CapturedPlacement
import app.lawnchair.organizer.planning.ClassificationSignal
import app.lawnchair.organizer.planning.ClassificationSignals
import app.lawnchair.organizer.planning.ComponentKey
import app.lawnchair.organizer.planning.DeviceCapabilities
import app.lawnchair.organizer.planning.FolderId
import app.lawnchair.organizer.planning.FolderRef
import app.lawnchair.organizer.planning.ItemId
import app.lawnchair.organizer.planning.ItemKind
import app.lawnchair.organizer.planning.LayoutSnapshot
import app.lawnchair.organizer.planning.OrganizationInput
import app.lawnchair.organizer.planning.PackageName
import app.lawnchair.organizer.planning.Page
import app.lawnchair.organizer.planning.PageRef
import app.lawnchair.organizer.planning.ProfileId
import app.lawnchair.organizer.planning.RunMode
import app.lawnchair.organizer.planning.SignalSource
import app.lawnchair.organizer.planning.TargetKey
import app.lawnchair.organizer.rules.BundleReadResult
import app.lawnchair.organizer.rules.CategoryOverrideKey
import app.lawnchair.organizer.rules.CategoryOverrideSnapshot
import app.lawnchair.organizer.rules.CategoryOverrideSnapshotSource
import app.lawnchair.organizer.rules.FullOrganizationTargetPolicy
import app.lawnchair.organizer.rules.OrganizerPolicyBundle
import app.lawnchair.organizer.rules.OrganizerPolicyBundleSource
import app.lawnchair.organizer.rules.OverrideSnapshotReadResult
import app.lawnchair.organizer.rules.PolicyInputIdentity
import app.lawnchair.organizer.rules.PolicySourceKind
import app.lawnchair.organizer.rules.sha256Canonical

interface OrganizationInputComposer {
    fun composeFullOrganization(): OrganizationInputComposition
}

/** Production capture adapter; the composer itself never reaches SQLite or Android state. */
class LayoutWriterCanonicalCaptureSource(private val writer: LayoutWriterPort) : CanonicalCaptureSource {
    override fun capture(): CanonicalCaptureReadResult = try {
        CanonicalCaptureReadResult.Ready(writer.captureCurrent(CaptureId("organization-input")))
    } catch (_: RuntimeException) {
        CanonicalCaptureReadResult.Invalid
    }
}

fun interface CanonicalCaptureSource {
    fun capture(): CanonicalCaptureReadResult
}

sealed interface CanonicalCaptureReadResult {
    data class Ready(val snapshot: CapturedSnapshot) : CanonicalCaptureReadResult
    data object Invalid : CanonicalCaptureReadResult
}

class DefaultOrganizationInputComposer(
    private val captureSource: CanonicalCaptureSource,
    private val bundleSource: OrganizerPolicyBundleSource,
    private val overrides: CategoryOverrideSnapshotSource,
    private val platformEvidence: ClassificationSignalSnapshotSource,
    private val targetMaterializer: FullTargetSetMaterializer = FullTargetSetMaterializer(),
) : OrganizationInputComposer {
    override fun composeFullOrganization(): OrganizationInputComposition {
        val capture = (captureSource.capture() as? CanonicalCaptureReadResult.Ready)?.snapshot
            ?: return notReady(
                InputReadinessReason.InvalidCanonicalCapture(CaptureFailureCategory.CAPTURE_UNAVAILABLE),
                "capture-invalid",
            )
        if (capture.layoutState.items.any { it.lockState == OrganizerLockState.UNKNOWN }) {
            return notReady(
                InputReadinessReason.InvalidCanonicalCapture(CaptureFailureCategory.UNKNOWN_LOCK),
                "capture-unknown-lock",
            )
        }
        val mapped = mapLayout(capture.layoutState, capture.revision)
            ?: return notReady(
                InputReadinessReason.InvalidCanonicalCapture(CaptureFailureCategory.UNREPRESENTABLE_LAYOUT),
                "capture-unrepresentable",
            )
        val bundle = when (val read = bundleSource.readActive()) {
            is BundleReadResult.Ready -> read.bundle

            BundleReadResult.Missing -> return notReady(
                InputReadinessReason.SourceUnavailable(PolicySourceKind.ORGANIZER_POLICY_BUNDLE),
                "bundle-missing",
            )

            BundleReadResult.Corrupt -> return notReady(
                InputReadinessReason.SourceUnreadable(PolicySourceKind.ORGANIZER_POLICY_BUNDLE),
                "bundle-corrupt",
            )

            is BundleReadResult.UnsupportedVersion -> return notReady(
                InputReadinessReason.UnsupportedVersion(
                    PolicySourceKind.ORGANIZER_POLICY_BUNDLE,
                    read.identity?.let { policyIdentity(PolicySourceKind.ORGANIZER_POLICY_BUNDLE, it.semanticVersion, it.sha256) },
                ),
                "bundle-unsupported",
                read.identity?.sha256,
            )
        }
        bundle.validate()?.let {
            return notReady(incompatibleBundleReason(bundle), "bundle-invalid", bundle.identity.sha256)
        }
        val requests = mapped.items.mapNotNull(::evidenceRequest)
            .sortedWith(compareBy({ it.profile.value }, { it.packageName.value }, { it.item.value }))
        var expectedCut: app.lawnchair.organizer.rules.PolicyBundleIdentity? = null
        var observedCut: app.lawnchair.organizer.rules.PolicyBundleIdentity? = null
        repeat(MAX_DYNAMIC_ATTEMPTS) {
            val firstOverrides = when (val read = overrides.read(mapped.profiles)) {
                is OverrideSnapshotReadResult.Ready -> read.snapshot

                OverrideSnapshotReadResult.Unreadable -> return notReady(
                    InputReadinessReason.SourceUnreadable(PolicySourceKind.CATEGORY_OVERRIDE_SNAPSHOT),
                    "override-unreadable",
                )

                OverrideSnapshotReadResult.UnsupportedSchema -> return notReady(
                    InputReadinessReason.UnsupportedVersion(PolicySourceKind.CATEGORY_OVERRIDE_SNAPSHOT, null),
                    "override-unsupported-schema",
                )
            }
            val firstEvidence = when (val read = platformEvidence.read(requests, bundle.classification)) {
                is PlatformEvidenceReadResult.Ready -> read.evidence

                PlatformEvidenceReadResult.Unreadable -> return notReady(
                    InputReadinessReason.SourceUnreadable(PolicySourceKind.PLATFORM_CLASSIFICATION_EVIDENCE),
                    "evidence-unreadable",
                )
            }
            val secondOverrides = when (val read = overrides.read(mapped.profiles)) {
                is OverrideSnapshotReadResult.Ready -> read.snapshot

                OverrideSnapshotReadResult.Unreadable -> return notReady(
                    InputReadinessReason.SourceUnreadable(PolicySourceKind.CATEGORY_OVERRIDE_SNAPSHOT),
                    "override-unreadable",
                )

                OverrideSnapshotReadResult.UnsupportedSchema -> return notReady(
                    InputReadinessReason.UnsupportedVersion(PolicySourceKind.CATEGORY_OVERRIDE_SNAPSHOT, null),
                    "override-unsupported-schema",
                )
            }
            val secondEvidence = when (val read = platformEvidence.read(requests, bundle.classification)) {
                is PlatformEvidenceReadResult.Ready -> read.evidence

                PlatformEvidenceReadResult.Unreadable -> return notReady(
                    InputReadinessReason.SourceUnreadable(PolicySourceKind.PLATFORM_CLASSIFICATION_EVIDENCE),
                    "evidence-unreadable",
                )
            }
            val firstCut = dynamicCutIdentity(bundle, firstOverrides.identity, firstEvidence.identity)
            val secondCut = dynamicCutIdentity(bundle, secondOverrides.identity, secondEvidence.identity)
            if (firstCut != secondCut) {
                expectedCut = firstCut
                observedCut = secondCut
                return@repeat
            }
            if (firstOverrides.assignments.values.any { it !in bundle.taxonomy.allowedCategories }) {
                return notReady(
                    InputReadinessReason.ContradictorySource(PolicySourceKind.CATEGORY_OVERRIDE_SNAPSHOT),
                    "override-category-invalid",
                    bundle.identity.sha256,
                )
            }
            val signals = materializeSignals(mapped.items, bundle, firstOverrides, firstEvidence)
                ?: return notReady(
                    InputReadinessReason.ContradictorySource(PolicySourceKind.MATERIALIZED_CLASSIFICATION_SIGNALS),
                    "signal-contradiction",
                    bundle.identity.sha256,
                )
            val targets = (targetMaterializer.materialize(mapped.items, bundle.fullOrganizationTargets) as? TargetMaterializationResult.Ready)?.value
                ?: return notReady(
                    InputReadinessReason.ContradictorySource(PolicySourceKind.MATERIALIZED_FULL_TARGET_SET),
                    "target-partition",
                    bundle.identity.sha256,
                )
            val rulesIdentity = policyIdentity(PolicySourceKind.ORGANIZER_POLICY_BUNDLE, bundle.rules.version.value, bundle.identity.sha256)
            val taxonomyIdentity = policyIdentity(PolicySourceKind.ORGANIZER_POLICY_BUNDLE, bundle.taxonomy.version.value, bundle.identity.sha256)
            return OrganizationInputComposition.Ready(
                OrganizationInput(mapped.snapshot, bundle.rules, bundle.taxonomy, signals.signals, targets.targets, RunMode.FullOrganization),
                InputProvenance(capture.revision, rulesIdentity, taxonomyIdentity, signals.identity, targets.identity, bundle.identity),
            )
        }
        return notReady(
            InputReadinessReason.InconsistentPolicyRead(checkNotNull(expectedCut), checkNotNull(observedCut)),
            "dynamic-cut-unstable",
            observedCut?.sha256,
        )
    }

    private fun materializeSignals(
        items: List<CapturedItem>,
        bundle: OrganizerPolicyBundle,
        overrideSnapshot: CategoryOverrideSnapshot,
        evidence: PlatformClassificationEvidence,
    ): MaterializedSignals? {
        val signals = mutableListOf<ClassificationSignal>()
        for (item in items) {
            val request = evidenceRequest(item) ?: continue
            val candidate = overrideSnapshot.assignments[CategoryOverrideKey(request.packageName, request.profile)]
                ?.let { SignalSource.S1 to it }
                ?: evidence.s2[item.id]?.let { SignalSource.S2 to it }
                ?: evidence.s5[item.id]?.let { SignalSource.S5 to it }
                ?: continue
            if (candidate.second !in bundle.taxonomy.allowedCategories) return null
            signals += ClassificationSignal(item.id, candidate.first, candidate.second)
        }
        val ordered = signals.sortedWith(compareBy({ it.item.value }, { it.source.ordinal }, { it.candidate.value }))
        val canonical = ordered.joinToString("\n") { "${it.item.value}:${it.source.name}:${it.candidate.value}" }
        return MaterializedSignals(
            ClassificationSignals(ordered),
            policyIdentity(
                PolicySourceKind.MATERIALIZED_CLASSIFICATION_SIGNALS,
                "${bundle.classification.version}:${overrideSnapshot.identity.versionOrGeneration}:${evidence.identity.versionOrGeneration}",
                sha256Canonical("${bundle.identity.sha256}\n${overrideSnapshot.identity.sha256}\n${evidence.identity.sha256}\n$canonical"),
            ),
        )
    }

    private fun evidenceRequest(item: CapturedItem): ClassificationEvidenceRequest? {
        if (item.availability != Availability.AVAILABLE) return null
        val packageName = when (val target = item.target) {
            is TargetKey.AppKey -> target.component.value.substringBefore('/').takeIf { it.isNotBlank() }?.let(::PackageName)
            is TargetKey.ShortcutKey -> target.packageName
            else -> null
        } ?: return null
        return ClassificationEvidenceRequest(item.id, packageName, item.profile)
    }

    private fun mapLayout(state: LayoutState, revision: app.lawnchair.organizer.planning.RevisionId): MappedLayout? {
        val profiles = state.profiles.associate { it.id to it.availability }
        val pages = state.pages.map {
            val ref = it.ref as? ApplicationPageRef.PersistentPage ?: return null
            Page(ref.pageId, it.order)
        }
        val items = state.items.map { item -> mapItem(item, profiles[item.profile] ?: return null) ?: return null }
        return MappedLayout(
            LayoutSnapshot(revision, state.deviceCapabilities.toPlanner(), pages, items),
            items,
            profiles.keys,
        )
    }

    private fun mapItem(
        item: app.lawnchair.organizer.application.public.CanonicalItemState,
        profileAvailability: ProfileAvailability,
    ): CapturedItem? {
        val id = (item.ref as? ApplicationItemRef.PersistentItem)?.itemId ?: return null
        if (item.lockState == OrganizerLockState.UNKNOWN) return null
        val placement = when (val value = item.placement) {
            is PlacementState.Workspace -> {
                val page = (value.page as? ApplicationPageRef.PersistentPage)?.pageId ?: return null
                CapturedPlacement.Workspace(PageRef(page), value.cell, value.span)
            }

            is PlacementState.Dock -> CapturedPlacement.Dock(value.rank)

            is PlacementState.FolderChild -> {
                val folder = (value.parent as? ApplicationItemRef.PersistentItem)?.itemId ?: return null
                CapturedPlacement.FolderMember(FolderRef(FolderId(folder.value)), value.rank)
            }

            is PlacementState.AppPairChild -> {
                val pair = (value.parent as? ApplicationItemRef.PersistentItem)?.itemId ?: return null
                CapturedPlacement.AppPairMember(AppPairRef(AppPairId(pair.value)))
            }

            is PlacementState.UnsupportedContainer -> return null
        }
        val kind = when (val value = item.kind) {
            CanonicalItemKind.Application -> ItemKind.APPLICATION
            CanonicalItemKind.DeepShortcut -> ItemKind.DEEP_SHORTCUT
            CanonicalItemKind.ShortcutLegacy -> ItemKind.SHORTCUT_LEGACY
            CanonicalItemKind.Folder -> ItemKind.FOLDER
            CanonicalItemKind.AppWidget -> ItemKind.APPWIDGET
            CanonicalItemKind.CustomAppWidget -> ItemKind.CUSTOM_APPWIDGET
            CanonicalItemKind.AppPair -> ItemKind.APP_PAIR
            is CanonicalItemKind.Unknown -> return null
        }
        val availability = if (profileAvailability == ProfileAvailability.UNAVAILABLE) {
            Availability.UNAVAILABLE
        } else {
            when (item.itemAvailability) {
                ItemAvailability.AVAILABLE -> Availability.AVAILABLE
                ItemAvailability.DISABLED -> Availability.DISABLED
                ItemAvailability.QUIET -> Availability.QUIET
                ItemAvailability.LOCKED_PRIVATE_SPACE -> Availability.LOCKED_PRIVATE_SPACE
                ItemAvailability.UNAVAILABLE -> Availability.UNAVAILABLE
            }
        }
        val structure = item.structure
        // Spec #10: CapturedItem.members holds folder child ids iff kind = FOLDER;
        // app-pair membership lives only in AppPairMetadata plus each member's
        // AppPairMember placement.
        val members = when (structure) {
            StructureState.Plain -> emptyList()
            is StructureState.FolderMembers -> structure.members.map { (it.item as? ApplicationItemRef.PersistentItem)?.itemId ?: return null }
            is StructureState.AppPairMembers -> emptyList()
        }
        val pairMetadata = (structure as? StructureState.AppPairMembers)?.let {
            val first = (it.first as? ApplicationItemRef.PersistentItem)?.itemId ?: return null
            val second = (it.second as? ApplicationItemRef.PersistentItem)?.itemId ?: return null
            // Issue #141: capture decodes the persisted member-rank encoding into
            // one shared snap token; Absent projects to null so undecodable pairs
            // keep failing typed MALFORMED_APP_PAIR validation instead of being
            // repaired with an invented value.
            val snapPosition = (it.snapPosition as? OptionalSnapPosition.Present)?.token
            AppPairMetadata(
                listOf(
                    AppPairMember(first, it.firstStage, snapPosition),
                    AppPairMember(second, it.secondStage, snapPosition),
                ),
            )
        }
        // Planner contract: the container item itself owns its identity
        // (folderId/appPairId). Member items stay linked only through their
        // placement and the parent's member list; setting folderId on a member
        // would fail KIND_TARGET_MISMATCH validation.
        return CapturedItem(
            id, item.profile, kind, item.targetKey, placement,
            locked = item.lockState == OrganizerLockState.LOCKED,
            availability = availability,
            folderId = if (kind == ItemKind.FOLDER) FolderId(id.value) else null,
            appPairId = if (kind == ItemKind.APP_PAIR) AppPairId(id.value) else null,
            members = members,
            appPair = pairMetadata,
        )
    }

    private fun app.lawnchair.organizer.application.public.DeviceCapabilities.toPlanner() = DeviceCapabilities(
        columns,
        rows,
        hotseatSlots,
        folderMaxColumns,
        folderMaxRows,
        when (orientation) {
            app.lawnchair.organizer.application.public.DeviceOrientation.PORTRAIT -> app.lawnchair.organizer.planning.Orientation.PORTRAIT
            app.lawnchair.organizer.application.public.DeviceOrientation.LANDSCAPE -> app.lawnchair.organizer.planning.Orientation.LANDSCAPE
            app.lawnchair.organizer.application.public.DeviceOrientation.TWO_PANEL_PORTRAIT -> app.lawnchair.organizer.planning.Orientation.TWO_PANEL_PORTRAIT
            app.lawnchair.organizer.application.public.DeviceOrientation.TWO_PANEL_LANDSCAPE -> app.lawnchair.organizer.planning.Orientation.TWO_PANEL_LANDSCAPE
        },
    )

    private fun policyIdentity(source: PolicySourceKind, version: String, digest: String) = PolicyInputIdentity(source, version, digest)

    private fun incompatibleBundleReason(bundle: OrganizerPolicyBundle) = InputReadinessReason.IncompatiblePolicyBundle(
        rules = policyIdentity(PolicySourceKind.ORGANIZER_POLICY_BUNDLE, bundle.rules.version.value, bundle.identity.sha256),
        taxonomy = policyIdentity(PolicySourceKind.ORGANIZER_POLICY_BUNDLE, bundle.taxonomy.version.value, bundle.identity.sha256),
        signals = policyIdentity(PolicySourceKind.MATERIALIZED_CLASSIFICATION_SIGNALS, bundle.classification.version, bundle.identity.sha256),
        targets = policyIdentity(PolicySourceKind.MATERIALIZED_FULL_TARGET_SET, bundle.fullOrganizationTargets.version, bundle.identity.sha256),
        policyBundle = bundle.identity,
    )

    private fun dynamicCutIdentity(
        bundle: OrganizerPolicyBundle,
        overrides: PolicyInputIdentity,
        evidence: PolicyInputIdentity,
    ) = app.lawnchair.organizer.rules.PolicyBundleIdentity(
        bundle.identity.semanticVersion,
        sha256Canonical("${bundle.identity.sha256}\n${overrides.sha256}\n${evidence.sha256}"),
    )

    private fun notReady(reason: InputReadinessReason, code: String, digest: String? = null) = OrganizationInputComposition.NotReady(
        reason,
        CompositionDiagnostic(code, digest = digest),
    )

    private data class MappedLayout(
        val snapshot: LayoutSnapshot,
        val items: List<CapturedItem>,
        val profiles: Set<ProfileId>,
    )

    private companion object {
        const val MAX_DYNAMIC_ATTEMPTS = 2
    }
}
