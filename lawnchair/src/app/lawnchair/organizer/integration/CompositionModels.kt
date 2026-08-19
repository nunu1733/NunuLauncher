package app.lawnchair.organizer.integration

import app.lawnchair.organizer.planning.CategoryId
import app.lawnchair.organizer.planning.ClassificationSignals
import app.lawnchair.organizer.planning.ItemId
import app.lawnchair.organizer.planning.OrganizationInput
import app.lawnchair.organizer.planning.PackageName
import app.lawnchair.organizer.planning.ProfileId
import app.lawnchair.organizer.planning.RevisionId
import app.lawnchair.organizer.planning.TargetSet
import app.lawnchair.organizer.rules.ClassificationPolicy
import app.lawnchair.organizer.rules.PolicyBundleIdentity
import app.lawnchair.organizer.rules.PolicyInputIdentity

data class InputProvenance(
    val revision: RevisionId,
    val rules: PolicyInputIdentity,
    val taxonomy: PolicyInputIdentity,
    val signals: PolicyInputIdentity,
    val targets: PolicyInputIdentity,
    val policyBundle: PolicyBundleIdentity,
)

sealed interface OrganizationInputComposition {
    data class Ready(
        val input: OrganizationInput,
        val provenance: InputProvenance,
    ) : OrganizationInputComposition

    data class NotReady(
        val reason: InputReadinessReason,
        val diagnostic: CompositionDiagnostic,
    ) : OrganizationInputComposition
}

sealed interface InputReadinessReason {
    data object SourceUnavailable : InputReadinessReason
    data object SourceUnreadable : InputReadinessReason
    data object UnsupportedVersion : InputReadinessReason
    data object IncompatiblePolicyBundle : InputReadinessReason
    data object InconsistentPolicyRead : InputReadinessReason
    data object ContradictorySource : InputReadinessReason
    data object InvalidCanonicalCapture : InputReadinessReason
}

/** Deliberately opaque: no package, profile, component, item, or layout identity. */
data class CompositionDiagnostic(
    val code: String,
    val policyVersionOrGeneration: String? = null,
    val digest: String? = null,
)

data class ClassificationEvidenceRequest(
    val item: ItemId,
    val packageName: PackageName,
    val profile: ProfileId,
)

data class PlatformClassificationEvidence(
    val s2: Map<ItemId, CategoryId>,
    val s5: Map<ItemId, CategoryId>,
    val identity: PolicyInputIdentity,
)

sealed interface PlatformEvidenceReadResult {
    data class Ready(val evidence: PlatformClassificationEvidence) : PlatformEvidenceReadResult
    data object Unreadable : PlatformEvidenceReadResult
}

/** Platform/Android implementation is injected; the composer never receives Android objects. */
interface ClassificationSignalSnapshotSource {
    fun read(
        requests: List<ClassificationEvidenceRequest>,
        policy: ClassificationPolicy,
    ): PlatformEvidenceReadResult
}

data class MaterializedTargetSet(
    val targets: TargetSet,
    val identity: PolicyInputIdentity,
)

data class MaterializedSignals(
    val signals: ClassificationSignals,
    val identity: PolicyInputIdentity,
)
