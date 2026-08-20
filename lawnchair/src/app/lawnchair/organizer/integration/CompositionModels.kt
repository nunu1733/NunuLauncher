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
import app.lawnchair.organizer.rules.PolicySourceKind

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

/**
 * Typed, non-write handoff consumed by #52. Source/identity semantics must be
 * taken from this type, never reconstructed by parsing [CompositionDiagnostic].
 */
sealed interface InputReadinessReason {
    data object ReconciliationPending : InputReadinessReason
    data object ReconciliationFailed : InputReadinessReason
    data class SourceUnavailable(val source: PolicySourceKind) : InputReadinessReason
    data class SourceUnreadable(val source: PolicySourceKind) : InputReadinessReason
    data class UnsupportedVersion(
        val source: PolicySourceKind,
        val actual: PolicyInputIdentity?,
    ) : InputReadinessReason
    data class IncompatiblePolicyBundle(
        val rules: PolicyInputIdentity,
        val taxonomy: PolicyInputIdentity,
        val signals: PolicyInputIdentity,
        val targets: PolicyInputIdentity,
        val policyBundle: PolicyBundleIdentity,
    ) : InputReadinessReason
    data class InconsistentPolicyRead(
        val expected: PolicyBundleIdentity,
        val observed: PolicyBundleIdentity,
    ) : InputReadinessReason
    data class ContradictorySource(val source: PolicySourceKind) : InputReadinessReason
    data class InvalidCanonicalCapture(val category: CaptureFailureCategory) : InputReadinessReason
}

enum class CaptureFailureCategory {
    CAPTURE_UNAVAILABLE,
    UNREPRESENTABLE_LAYOUT,
    UNKNOWN_LOCK,
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
