package app.lawnchair.organizer.integration

import app.lawnchair.organizer.rules.BundleReadResult
import app.lawnchair.organizer.rules.CategoryOverrideSnapshotSource
import app.lawnchair.organizer.rules.OrganizerPolicyBundleSource
import app.lawnchair.organizer.rules.OverrideSnapshotReadResult
import org.junit.Assert.assertEquals
import org.junit.Test

class OrganizationInputComposerTest {
    @Test
    fun invalidCanonicalCaptureFailsClosedBeforeReadingPolicySources() {
        val composer = DefaultOrganizationInputComposer(
            captureSource = CanonicalCaptureSource { CanonicalCaptureReadResult.Invalid },
            bundleSource = object : OrganizerPolicyBundleSource {
                override fun readActive(): BundleReadResult = error("bundle must not be read")
            },
            overrides = object : CategoryOverrideSnapshotSource {
                override fun read(capturedProfiles: Set<app.lawnchair.organizer.planning.ProfileId>): OverrideSnapshotReadResult =
                    error("overrides must not be read")
            },
            platformEvidence = object : ClassificationSignalSnapshotSource {
                override fun read(
                    requests: List<ClassificationEvidenceRequest>,
                    policy: app.lawnchair.organizer.rules.ClassificationPolicy,
                ): PlatformEvidenceReadResult = error("evidence must not be read")
            },
        )

        val result = composer.composeFullOrganization() as OrganizationInputComposition.NotReady
        assertEquals(InputReadinessReason.InvalidCanonicalCapture, result.reason)
        assertEquals("capture-invalid", result.diagnostic.code)
    }
}
