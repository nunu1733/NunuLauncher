package app.lawnchair.organizer.diagnostics.projection

import app.lawnchair.organizer.diagnostics.model.ErrorEntry
import app.lawnchair.organizer.diagnostics.model.ErrorFamily
import app.lawnchair.organizer.integration.InputCompositionCode
import app.lawnchair.organizer.integration.OrganizationInputComposition

/**
 * Issue #172 projection from a `NotReady` composition into the terminal
 * `INPUT_NOT_READY` journal record's [ErrorEntry]. The code set is the closed
 * [InputCompositionCode] the composer already carries in
 * `CompositionDiagnostic.code`, so this projection is a 1:1 rename with no
 * classification logic of its own. Unknown external values would fail
 * `ErrorEntry` validation, which the contract's `UNMAPPED` fallback covers for
 * future code sources.
 */
object InputReadinessProjection {

    @JvmStatic
    fun projectError(composition: OrganizationInputComposition.NotReady): ErrorEntry = errorFor(composition.diagnostic.code)

    @JvmStatic
    fun errorFor(code: InputCompositionCode): ErrorEntry = ErrorEntry(ErrorFamily.INPUT_READINESS, code.name)
}
