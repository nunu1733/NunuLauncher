package app.lawnchair.organizer.integration

import android.content.Context
import app.lawnchair.organizer.PreferenceWorkspaceOverlapToleranceSource
import app.lawnchair.organizer.application.protocol.LayoutWriterPort
import app.lawnchair.organizer.rules.BuiltInOrganizerPolicyBundleSource
import app.lawnchair.organizer.rules.CategoryOverrideStoreModule
import app.lawnchair.organizer.rules.LayoutStrategySelectionModule

/**
 * #83 production entry point. Callers provide only the existing canonical capture
 * seam; no UI preference, Flowerpot, or planner policy construction is allowed.
 */
class ProductionOrganizationInputComposer(
    appContext: Context,
    layoutWriter: LayoutWriterPort,
    captureFailureObserver: CaptureFailureObserver = NoopCaptureFailureObserver,
) : OrganizationInputComposer by DefaultOrganizationInputComposer(
    captureSource = LayoutWriterCanonicalCaptureSource(layoutWriter, captureFailureObserver),
    bundleSource = BuiltInOrganizerPolicyBundleSource,
    overrides = CategoryOverrideStoreModule.source(appContext),
    // Spec 182: the persisted strategy selection joins the stable cut as the
    // fifth policy input.
    layoutStrategySelections = LayoutStrategySelectionModule.source(appContext),
    platformEvidence = AndroidClassificationSignalSnapshotSource(appContext),
    // Issue #185 / ADR-0010: the reservation-overlap gate reads the same
    // platform policy the loader consults, freshly at every compose.
    overlapTolerance = PreferenceWorkspaceOverlapToleranceSource(appContext),
)
