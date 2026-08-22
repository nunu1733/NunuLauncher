package app.lawnchair.organizer.ui

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class OrganizationOperationLeaseTest {
    @Test
    fun everyAdmissionKindBlocksEveryOtherKindUntilItsLeaseIsClosed() {
        OrganizationOperationLease.Kind.entries.forEach { heldKind ->
            val held = OrganizationOperationLease.tryAcquire(heldKind)
            assertNotNull("$heldKind should be admitted when idle", held)
            try {
                OrganizationOperationLease.Kind.entries.forEach { contenderKind ->
                    assertNull(
                        "$heldKind should block $contenderKind",
                        OrganizationOperationLease.tryAcquire(contenderKind),
                    )
                }
            } finally {
                held?.close()
            }
        }
    }
}
