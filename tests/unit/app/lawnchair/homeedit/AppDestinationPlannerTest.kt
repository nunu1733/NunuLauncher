/*
 * Issue #497: destination-policy planner semantics (ADR-0015 Decisions 3,
 * 4, 6, 8) and the persisted-snapshot classification (Decision 10, spec 497
 * Open questions 9). The planner's closed result deliberately carries no
 * coordinates; the classifier never reads the current policy.
 */
package app.lawnchair.homeedit

import com.android.launcher3.model.DirectEditContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun row(
    id: Int,
    container: Int = HomeEditContainers.DESKTOP,
    screenId: Int = 0,
    cellX: Int = 0,
    cellY: Int = 0,
    itemType: Int = HomeEditItemTypes.APPLICATION,
    rank: Int = 0,
    userSerial: Long = 10L,
) = DirectEditContract.Row(id, container, screenId, cellX, cellY, 1, 1, itemType, rank, userSerial)

private fun snapshot(vararg rows: DirectEditContract.Row) = DirectEditContract.Snapshot(4, 6, intArrayOf(0, 1, 2), rows as Array<DirectEditContract.Row>, 4)

private fun policy(
    kind: DestinationPolicySnapshot.Kind = DestinationPolicySnapshot.Kind.FOLDER,
    folderId: Int = 42,
    userSerial: Long = 10L,
    packageName: String = "com.test.app",
) = DestinationPolicySnapshot(kind, folderId, userSerial, packageName)

class AppDestinationPlannerTest {

    private val incoming = IncomingInstall(userSerial = 10L, packageName = "com.test.app")

    // --- classifier ---

    @Test
    fun `missing snapshot classifies as MISSING without reading current policy`() {
        val classification = AppDestinationClassifier.classify(
            policy = null,
            snapshotPersisted = false,
            baseUserSerial = 10L,
            basePackageName = "com.test.app",
        )
        assertEquals(DestinationSnapshotValidity.MISSING, classification.validity)
        assertNull(classification.policy)
    }

    @Test
    fun `undecodable snapshot classifies as INVALID`() {
        val classification = AppDestinationClassifier.classify(
            policy = null,
            snapshotPersisted = true,
            baseUserSerial = 10L,
            basePackageName = "com.test.app",
        )
        assertEquals(DestinationSnapshotValidity.INVALID, classification.validity)
    }

    @Test
    fun `identity mismatch classifies as IDENTITY_MISMATCH`() {
        val classification = AppDestinationClassifier.classify(
            policy = policy(userSerial = 11L),
            snapshotPersisted = true,
            baseUserSerial = 10L,
            basePackageName = "com.test.app",
        )
        assertEquals(DestinationSnapshotValidity.IDENTITY_MISMATCH, classification.validity)
    }

    @Test
    fun `matching identity classifies as VALID`() {
        val classification = AppDestinationClassifier.classify(
            policy = policy(),
            snapshotPersisted = true,
            baseUserSerial = 10L,
            basePackageName = "com.test.app",
        )
        assertEquals(DestinationSnapshotValidity.VALID, classification.validity)
    }

    // --- wire format (DirectEditContract serialization round-trip) ---

    @Test
    fun `snapshot wire format round-trips and rejects corrupt input`() {
        val raw = DirectEditContract.serializeDestinationSnapshot(
            DirectEditContract.DEST_SNAPSHOT_KIND_FOLDER,
            42,
            10L,
            "com.test.app",
        )
        assertEquals("folder|42|10|com.test.app", raw)
        val parts = DirectEditContract.parseDestinationSnapshot(raw)
        assertEquals("folder", parts!![0])
        assertEquals("42", parts[1])
        assertEquals("10", parts[2])
        assertEquals("com.test.app", parts[3])

        assertTrue(DirectEditContract.isUpstreamSnapshot("upstream|0|10|com.test.app"))
        assertTrue(!DirectEditContract.isUpstreamSnapshot(raw))
        assertNull(DirectEditContract.parseDestinationSnapshot(null))
        assertNull(DirectEditContract.parseDestinationSnapshot(""))
        assertNull(DirectEditContract.parseDestinationSnapshot("folder|x|10|com.test.app"))
        assertNull(DirectEditContract.parseDestinationSnapshot("bogus|42|10|com.test.app"))
    }

    // --- planner ---

    @Test
    fun `folder target appends at tail rank`() {
        val s = snapshot(
            row(42, itemType = HomeEditItemTypes.FOLDER, cellX = 0, cellY = 0),
            row(1, container = 42, rank = 0),
            row(2, container = 42, rank = 1),
        )
        val plan = AppDestinationPlanner.plan(
            HomeEditSnapshotMapper.map(s),
            policy(),
            incoming,
        )
        assertEquals(AppDestinationPlan.FolderTarget(42, 2), plan)
    }

    @Test
    fun `missing folder falls back with FOLDER_MISSING`() {
        val s = snapshot(row(1, itemType = HomeEditItemTypes.APPLICATION))
        val plan = AppDestinationPlanner.plan(
            HomeEditSnapshotMapper.map(s),
            policy(folderId = 42),
            incoming,
        )
        assertEquals(AppDestinationPlan.UpstreamDefault(AppDestinationFallbackReason.FOLDER_MISSING), plan)
    }

    @Test
    fun `folder in another profile falls back with PROFILE_MISMATCH`() {
        val s = snapshot(row(42, itemType = HomeEditItemTypes.FOLDER, userSerial = 11L))
        val plan = AppDestinationPlanner.plan(
            HomeEditSnapshotMapper.map(s),
            policy(),
            incoming,
        )
        assertEquals(AppDestinationPlan.UpstreamDefault(AppDestinationFallbackReason.PROFILE_MISMATCH), plan)
    }

    @Test
    fun `folder on the dock falls back with DOCK_FOLDER`() {
        val s = snapshot(
            row(42, container = HomeEditContainers.HOTSEAT, itemType = HomeEditItemTypes.FOLDER),
        )
        val plan = AppDestinationPlanner.plan(
            HomeEditSnapshotMapper.map(s),
            policy(),
            incoming,
        )
        assertEquals(AppDestinationPlan.UpstreamDefault(AppDestinationFallbackReason.DOCK_FOLDER), plan)
    }

    @Test
    fun `folder outside the grid falls back with CONSTRAINT_VIOLATION`() {
        val s = snapshot(row(42, itemType = HomeEditItemTypes.FOLDER, cellX = 9, cellY = 0))
        val plan = AppDestinationPlanner.plan(
            HomeEditSnapshotMapper.map(s),
            policy(),
            incoming,
        )
        assertEquals(
            AppDestinationPlan.UpstreamDefault(AppDestinationFallbackReason.CONSTRAINT_VIOLATION),
            plan,
        )
    }

    @Test
    fun `upstream policy kind defensively falls back as SNAPSHOT_INVALID`() {
        val s = snapshot(row(42, itemType = HomeEditItemTypes.FOLDER))
        val plan = AppDestinationPlanner.plan(
            HomeEditSnapshotMapper.map(s),
            policy(kind = DestinationPolicySnapshot.Kind.UPSTREAM),
            incoming,
        )
        assertEquals(
            AppDestinationPlan.UpstreamDefault(AppDestinationFallbackReason.SNAPSHOT_INVALID),
            plan,
        )
    }

    @Test
    fun `plan is deterministic and idempotent for identical inputs`() {
        val s = snapshot(
            row(42, itemType = HomeEditItemTypes.FOLDER),
            row(1, container = 42, rank = 0),
        )
        val first = AppDestinationPlanner.plan(HomeEditSnapshotMapper.map(s), policy(), incoming)
        val second = AppDestinationPlanner.plan(HomeEditSnapshotMapper.map(s), policy(), incoming)
        assertEquals(first, second)
        // Re-planning on the output state (folder now has 2 children) moves
        // only the tail rank — appending twice is rank-stable per append.
        val grown = snapshot(
            row(42, itemType = HomeEditItemTypes.FOLDER),
            row(1, container = 42, rank = 0),
            row(2, container = 42, rank = 1),
        )
        val replan = AppDestinationPlanner.plan(HomeEditSnapshotMapper.map(grown), policy(), incoming)
        assertEquals(AppDestinationPlan.FolderTarget(42, 2), replan)
    }
}

/**
 * Stage-2 validator wiring (ADR-0015 required-test rows 2 and 3, decision
 * level): the validator reads ONLY the persisted raw snapshot — the
 * "current policy" is not an input at all — and a mismatching snapshot
 * rejects without a write.
 */
class AppDestinationStage2ValidatorTest {

    private val incoming = IncomingInstall(userSerial = 10L, packageName = "com.test.app")
    private val folderState = snapshot(
        row(42, itemType = HomeEditItemTypes.FOLDER, cellX = 0, cellY = 0),
        row(1, container = 42, rank = 0),
    )

    @Test
    fun `persisted snapshot A decides even when a different policy would be current now`() {
        // Snapshot A (folder 42) persisted at enqueue; the "current policy"
        // would point elsewhere — the validator has no way to see it, which is
        // the contract (ADR-0015 Decision 10).
        val validator = AppDestinationStage2Validator(
            DirectEditContract.serializeDestinationSnapshot(
                DirectEditContract.DEST_SNAPSHOT_KIND_FOLDER,
                42,
                10L,
                "com.test.app",
            ),
            incoming,
        )
        val decision = validator.validate(folderState)
        assertEquals(DirectEditContract.DEST_ACTION_FOLDER, decision.action)
        assertEquals(42, decision.folderId)
    }

    @Test
    fun `missing snapshot replans to upstream default with SNAPSHOT_INVALID`() {
        val validator = AppDestinationStage2Validator(null, incoming)
        val decision = validator.validate(folderState)
        assertEquals(DirectEditContract.DEST_ACTION_DEFAULT, decision.action)
        assertEquals(DirectEditContract.DEST_SNAPSHOT_INVALID, decision.reason)
    }

    @Test
    fun `corrupt snapshot replans to upstream default with SNAPSHOT_INVALID`() {
        val validator = AppDestinationStage2Validator("folder|notanumber|10|com.test.app", incoming)
        val decision = validator.validate(folderState)
        assertEquals(DirectEditContract.DEST_ACTION_DEFAULT, decision.action)
        assertEquals(DirectEditContract.DEST_SNAPSHOT_INVALID, decision.reason)
    }

    @Test
    fun `identity mismatch rejects without a write`() {
        val validator = AppDestinationStage2Validator(
            DirectEditContract.serializeDestinationSnapshot(
                DirectEditContract.DEST_SNAPSHOT_KIND_FOLDER,
                42,
                99L,
                "com.other.app",
            ),
            incoming,
        )
        val decision = validator.validate(folderState)
        assertEquals(DirectEditContract.DEST_ACTION_REJECT, decision.action)
        assertEquals(DirectEditContract.DEST_SNAPSHOT_INVALID, decision.reason)
    }

    @Test
    fun `stale folder replans to upstream default with its typed reason`() {
        val validator = AppDestinationStage2Validator(
            DirectEditContract.serializeDestinationSnapshot(
                DirectEditContract.DEST_SNAPSHOT_KIND_FOLDER,
                42,
                10L,
                "com.test.app",
            ),
            incoming,
        )
        // The folder vanished between stage 1 and admission: the re-plan is a
        // valid upstream-default plan, not a validation failure.
        val decision = validator.validate(snapshot(row(1, itemType = HomeEditItemTypes.APPLICATION)))
        assertEquals(DirectEditContract.DEST_ACTION_DEFAULT, decision.action)
        assertEquals(DirectEditContract.DEST_FOLDER_MISSING, decision.reason)
    }
}
