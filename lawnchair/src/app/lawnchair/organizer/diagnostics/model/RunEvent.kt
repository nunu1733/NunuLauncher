package app.lawnchair.organizer.diagnostics.model

import kotlin.ConsistentCopyVisibility
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.Serializable

/**
 * RunMode from the diagnostics contract §3: closed two-value set.
 */
@Serializable
enum class RunMode {
    @kotlinx.serialization.SerialName("FULL_ORGANIZATION")
    FULL_ORGANIZATION,

    @kotlinx.serialization.SerialName("INCREMENTAL_PLACEMENT")
    INCREMENTAL_PLACEMENT,
}

/**
 * Versions carried on RUN_STARTED events. Identifiers only, no content.
 *
 * Each version identifier must be a member of the source-owned
 * [APPROVED_VERSIONS] allowlist. The allowlist is the only construction
 * gate — the runtime type system makes arbitrary strings (including
 * package/component/profile identities) unrepresentable as version
 * identifiers. The regex-based charset restriction ([A-Za-z0-9_-]) is
 * subsumed by the allowlist.
 */
@Serializable
@ConsistentCopyVisibility
data class RunVersions private constructor(
    val ruleVersion: String = "",
    val taxonomyVersion: String = "",
    val recoveryFormatVersion: String = "",
    /** Spec 182: selected layout strategy identity (e.g. CANONICAL_PAGE_COMPACT_V1). */
    val strategyVersion: String = "",
) {
    companion object {
        /**
         * Source-owned allowlist of approved version identifiers.
         * Only identifiers in this set can be used as version fields.
         * Empty string (default) is always allowed.
         */
        val APPROVED_VERSIONS: Set<String> = setOf(
            "1",
            "v1_0",
            "t_2024",
            "rf-1",
            "a-b-c_d",
            "A",
            // Spec 182 accepted catalog identities (ADR-0012: an ID is an
            // immutable semantic identity; new strategies extend this set).
            "CANONICAL_PAGE_COMPACT_V1",
            "STABLE_PAGE_TIDY_V1",
            "BOTTOM_FIRST_V1",
            "GLOBAL_COMPACT_V1",
            "CATEGORY_CONTIGUOUS_V1",
        )

        /** Construct [RunVersions] with approved version identifiers. */
        fun create(
            ruleVersion: String = "",
            taxonomyVersion: String = "",
            recoveryFormatVersion: String = "",
            strategyVersion: String = "",
        ): RunVersions = RunVersions(ruleVersion, taxonomyVersion, recoveryFormatVersion, strategyVersion)
    }

    init {
        validateVersionIdentifier(ruleVersion, "ruleVersion")
        validateVersionIdentifier(taxonomyVersion, "taxonomyVersion")
        validateVersionIdentifier(recoveryFormatVersion, "recoveryFormatVersion")
        validateVersionIdentifier(strategyVersion, "strategyVersion")
    }

    private fun validateVersionIdentifier(value: String, fieldName: String) {
        if (value.isEmpty()) return
        // The charset check is still applied as a defense-in-depth
        // layer within the allowlist; the allowlist is the authoritative
        // approval gate.
        require(value in APPROVED_VERSIONS) {
            "$fieldName must be an approved version identifier, got '$value'"
        }
        require(value.matches(VERSION_ID_REGEX)) {
            "$fieldName must be 1-32 characters of [A-Za-z0-9_-], got '$value'"
        }
    }
}

/**
 * Closed orientation enum for DeviceProfileSummary.
 * Serializes to the contract-approved string values "PORTRAIT" and "LANDSCAPE".
 */
@Serializable
enum class Orientation {
    @kotlinx.serialization.SerialName("PORTRAIT")
    PORTRAIT,

    @kotlinx.serialization.SerialName("LANDSCAPE")
    LANDSCAPE,
}

/**
 * Device profile summary from the diagnostics contract §3.
 * Dimensions only; no coordinates. orientation is a closed enum.
 */
@Serializable
data class DeviceProfileSummary(
    val columns: Int = 0,
    val rows: Int = 0,
    val hotseatSlots: Int = 0,
    val orientation: Orientation? = null,
)

/**
 * Recovery context from the diagnostics contract §4.3.
 * pointId and pointOriginRunId must be canonical 32 lowercase hex or null.
 */
@Serializable
data class RecoveryContext(
    val pointId: String,
    val pointOriginRunId: String? = null,
) {
    init {
        validateCorrelationId(pointId, "RecoveryContext.pointId")
        pointOriginRunId?.let { validateCorrelationId(it, "RecoveryContext.pointOriginRunId") }
    }
}

/**
 * RecoveryLifecycle — the set of lifecycle states used in reconciliation
 * context (diagnostics contract §11). Mirrors the application
 * LifecycleState values as a serializable closed enum.
 */
@Serializable
enum class RecoveryLifecycle {
    CREATING,
    READY,
    APPLYING,
    COMMITTED_UNVERIFIED,
    VERIFIED,
    RESTORING,
    RESTORED,
    CORRUPT,
    EXPIRED,
    INCOMPATIBLE,
}

/**
 * Reconciliation classification from the diagnostics contract §11.
 */
@Serializable
enum class ReconciliationClassification {
    @kotlinx.serialization.SerialName("PRE_STATE")
    PRE_STATE,

    @kotlinx.serialization.SerialName("INTENDED_POST_STATE")
    INTENDED_POST_STATE,

    @kotlinx.serialization.SerialName("RECOVERY_TARGET_STATE")
    RECOVERY_TARGET_STATE,

    @kotlinx.serialization.SerialName("NEITHER_RECOGNIZED")
    NEITHER_RECOGNIZED,
}

/**
 * Reconciliation context from the diagnostics contract §11.
 * subjectRunId must be canonical 32 lowercase hex.
 */
@Serializable
data class ReconciliationContext(
    val subjectRunId: String,
    val priorLifecycle: RecoveryLifecycle,
    val classification: ReconciliationClassification,
    val resultingLifecycle: RecoveryLifecycle,
) {
    init {
        validateCorrelationId(subjectRunId, "ReconciliationContext.subjectRunId")
    }
}

/**
 * Top-level RunEvent — the single diagnostic event type.
 *
 * All fields are nullable or have defaults so that only populated
 * fields appear in serialized output (encodeDefaults = false).
 *
 * Contract §3 defines the full field set. Correlation fields
 * (runId, trigger, runMode, pointId) are at the top level for
 * JSON flatness matching the D-01–D-08 fixtures.
 *
 * runId and pointId must be canonical 32 lowercase hex or null.
 */
@Serializable
data class RunEvent(
    @EncodeDefault
    val schemaVersion: Int = SCHEMA_VERSION,
    val journalSequence: Long,
    @EncodeDefault
    val recordedAtWallMillis: Long = 0L,
    val runId: String? = null,
    val trigger: Trigger? = null,
    val runMode: RunMode? = null,
    val pointId: String? = null,
    val phase: PhaseCode,
    val applyStage: ApplyStage? = null,
    val error: ErrorEntry? = null,
    val planSummary: PlanSummary? = null,
    val applySummary: ApplySummary? = null,
    val recovery: RecoveryContext? = null,
    val reconciliation: ReconciliationContext? = null,
    val versions: RunVersions? = null,
    val deviceProfile: DeviceProfileSummary? = null,
) {
    companion object {
        /** The only accepted journal schema version per contract §3. */
        const val SCHEMA_VERSION: Int = 1
    }

    init {
        require(schemaVersion == SCHEMA_VERSION) {
            "schemaVersion must be $SCHEMA_VERSION; got $schemaVersion"
        }
        runId?.let { validateCorrelationId(it, "RunEvent.runId") }
        pointId?.let { validateCorrelationId(it, "RunEvent.pointId") }
    }
}
