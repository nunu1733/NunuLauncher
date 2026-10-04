package app.lawnchair.organizer.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Issue #418: single owner of the organizer run's UI-facing state holders
 * (display state, preparation phase, operation-active projection).
 *
 * The run reads through the accessors and writes only through the
 * `publishXxx` methods, which record the writing thread for the confinement
 * oracle. Because the holders are private here, a run-side write cannot
 * bypass the publication seam — the oracle's "every UI state write happens on
 * the publication thread" is verifiable by construction (spec 418 AC-1).
 */
internal class ManualOrganizationRunStateBus(
    initialState: ManualOrganizationRun.State,
    private val writeThreadTracker: ((String, Thread) -> Unit)? = null,
) {
    private val stateHolder = MutableStateFlow(initialState)
    private val operationActiveHolder = MutableStateFlow(false)
    private val preparationPhaseHolder =
        MutableStateFlow(ManualOrganizationRun.PreparationPhase.DETECTION)

    val stateFlow: StateFlow<ManualOrganizationRun.State> = stateHolder.asStateFlow()
    val state: ManualOrganizationRun.State get() = stateHolder.value
    val operationActive: StateFlow<Boolean> = operationActiveHolder.asStateFlow()
    val preparationPhase: StateFlow<ManualOrganizationRun.PreparationPhase> =
        preparationPhaseHolder.asStateFlow()

    fun publishState(next: ManualOrganizationRun.State) {
        track("state")
        stateHolder.value = next
    }

    fun publishOperationActive(active: Boolean) {
        track("operationActive")
        operationActiveHolder.value = active
    }

    fun publishPreparationPhase(phase: ManualOrganizationRun.PreparationPhase) {
        track("preparationPhase")
        preparationPhaseHolder.value = phase
    }

    private fun track(field: String) {
        writeThreadTracker?.invoke(field, Thread.currentThread())
    }
}
