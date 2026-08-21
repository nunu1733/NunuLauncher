package app.lawnchair.organizer.ui

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class OrganizationOperationLeaseTest {
    @Test
    fun authoringAndRunShareOneAdmissionDomainUntilTheLeaseIsClosed() {
        val authoring = OrganizationOperationLease.tryAcquire(OrganizationOperationLease.Kind.AUTHORING)
        assertNotNull(authoring)

        assertNull(OrganizationOperationLease.tryAcquire(OrganizationOperationLease.Kind.RUN))
        authoring?.close()

        val run = OrganizationOperationLease.tryAcquire(OrganizationOperationLease.Kind.RUN)
        assertNotNull(run)
        assertNull(OrganizationOperationLease.tryAcquire(OrganizationOperationLease.Kind.RECOVERY))
        run?.close()
    }
}
