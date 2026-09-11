package app.lawnchair.organizer.application.protocol

import app.lawnchair.organizer.application.public.ItemAvailability
import app.lawnchair.organizer.application.public.OptionalBytes
import app.lawnchair.organizer.planning.CandidateTarget

/**
 * Issue #228: application-owned port that resolves everything needed to build
 * a selected candidate's canonical application item at composition/plan time
 * (spec §6). The platform implementation is an adapter; the UI and the
 * composer never resolve applications themselves.
 *
 * Responsibility split (plan §3): this port resolves for *construction*;
 * apply-time re-verification of component availability is the separate
 * [CandidateAvailabilityPort], never this port.
 */
interface CandidateApplicationResolver {
    /** Resolved once per materialization; typed failure, no exceptions upward. */
    fun resolve(target: CandidateTarget.AppKey): CandidateApplicationResolution
}

sealed interface CandidateApplicationResolution {
    /**
     * [title]/[intentText]/[icon] feed the intended `CanonicalItemState`
     * exactly as a captured application row would carry them; `intentText`
     * is the canonical serialized launch intent (`ACTION_MAIN` +
     * `CATEGORY_LAUNCHER` + component, `toUri(0)`) the launcher itself uses
     * for app icons.
     */
    data class Ready(
        val title: String,
        val intentText: String,
        val icon: OptionalBytes,
        val itemAvailability: ItemAvailability,
    ) : CandidateApplicationResolution

    /** Typed failure: one unresolvable candidate fails the whole composition (no partial adoption). */
    data class Unavailable(val reason: CandidateResolutionFailure) : CandidateApplicationResolution
}

enum class CandidateResolutionFailure {
    /** The component is no longer present in the launcher-authorized enumeration. */
    COMPONENT_NOT_FOUND,

    /** The label/title could not be resolved to a non-blank value. */
    LABEL_UNAVAILABLE,

    /** The platform read failed in a way that is not proof of absence. */
    PLATFORM_READ_FAILED,
}

/**
 * Issue #228: application-owned apply-time availability re-verification
 * (spec §6). Called immediately before the apply transaction, on the same
 * boundary as the stale-revision recheck; any non-`AllAvailable` outcome is a
 * commit-before rejection — never an optimistic success.
 */
interface CandidateAvailabilityPort {
    fun verifyLaunchable(candidates: List<CandidateTarget.AppKey>): AvailabilityVerification
}

sealed interface AvailabilityVerification {
    data object AllAvailable : AvailabilityVerification

    /** The listed identities are no longer launchable (disabled/suspended/uninstalled/hidden). */
    data class Unavailable(val identities: List<CandidateTarget.AppKey>) : AvailabilityVerification

    /** The verification itself failed; treated exactly like [Unavailable] (fail-closed). */
    data class Unknown(val reason: String) : AvailabilityVerification
}
