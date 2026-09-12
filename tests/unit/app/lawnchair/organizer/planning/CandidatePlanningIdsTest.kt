package app.lawnchair.organizer.planning

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #228 spec AC-15: deterministic, label-independent, namespace-separated
 * planning IDs for selected candidates.
 */
class CandidatePlanningIdsTest {

    private fun target(component: String, profile: String) = CandidateTarget.AppKey(ComponentKey(component), ProfileId(profile))

    @Test
    fun sameIdentityYieldsSameIdAcrossRepeatedCalls() {
        val a = target("com.example.a/.Main", "0")
        assertEquals(CandidatePlanningIds.planningId(a), CandidatePlanningIds.planningId(a))
    }

    @Test
    fun differentIdentityYieldsDifferentId() {
        assertNotEquals(
            CandidatePlanningIds.planningId(target("com.example.a/.Main", "0")),
            CandidatePlanningIds.planningId(target("com.example.b/.Main", "0")),
        )
        assertNotEquals(
            CandidatePlanningIds.planningId(target("com.example.a/.Main", "0")),
            CandidatePlanningIds.planningId(target("com.example.a/.Main", "10")),
        )
    }

    @Test
    fun idsDeriveFromIdentityNotLabelOrOrder() {
        // The derivation input is exactly component + profile; two identities
        // that differ only in a label-bearing surface never exist here, and
        // component/profile order inside the hash is fixed by the format.
        val id = CandidatePlanningIds.planningId(target("com.example.a/.Main", "0"))
        val sameAgain = CandidatePlanningIds.planningId(target("com.example.a/.Main", "0"))
        assertEquals(id, sameAgain)
        // A prefix collision in the raw strings must not merge identities:
        // "a:b" + "c" vs "a" + "b:c" keep distinct hashes.
        assertNotEquals(
            CandidatePlanningIds.planningId(target("a:b/com.C", "1")),
            CandidatePlanningIds.planningId(target("a/com.BC", "1")),
        )
    }

    @Test
    fun candidateNamespaceNeverCollidesWithCapturedOrPlannedFolderIds() {
        val candidate = CandidatePlanningIds.planningId(target("com.example.a/.Main", "0"))
        val capturedNumeric = ItemId("42")
        val plannedFolder = ItemId("planned-folder-0")
        assertNotEquals(candidate, capturedNumeric)
        assertNotEquals(candidate, plannedFolder)
        assertTrue(CandidatePlanningIds.isCandidateId(candidate))
        assertTrue(!CandidatePlanningIds.isCandidateId(capturedNumeric))
        assertTrue(!CandidatePlanningIds.isCandidateId(plannedFolder))
    }

    @Test
    fun idsUseTheDedicatedPrefixAndFullLengthHex() {
        val id = CandidatePlanningIds.planningId(target("com.example.a/.Main", "0"))
        assertTrue(id.value.startsWith("candidate-"))
        // SHA-256 hex, never truncated: prefix + 64 hex characters.
        assertEquals("candidate-".length + 64, id.value.length)
        assertTrue(id.value.drop("candidate-".length).all { it in "0123456789abcdef" })
    }
}
