package app.lawnchair.organizer.integration

import android.content.Context
import app.lawnchair.organizer.application.protocol.LayoutWriterPort
import app.lawnchair.organizer.rules.BuiltInOrganizerPolicyBundleSource
import app.lawnchair.organizer.rules.CategoryOverrideStoreModule

/**
 * #83 production entry point. Callers provide only the existing canonical capture
 * seam; no UI preference, Flowerpot, or planner policy construction is allowed.
 */
class ProductionOrganizationInputComposer(
    appContext: Context,
    layoutWriter: LayoutWriterPort,
) : OrganizationInputComposer by DefaultOrganizationInputComposer(
    captureSource = LayoutWriterCanonicalCaptureSource(layoutWriter),
    bundleSource = BuiltInOrganizerPolicyBundleSource,
    overrides = CategoryOverrideStoreModule.source(appContext),
    platformEvidence = AndroidClassificationSignalSnapshotSource(appContext),
)
