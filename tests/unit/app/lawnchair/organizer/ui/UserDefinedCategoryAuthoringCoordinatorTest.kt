package app.lawnchair.organizer.ui

import app.lawnchair.organizer.planning.CategoryId
import app.lawnchair.organizer.planning.CategoryIdentity
import app.lawnchair.organizer.planning.PackageName
import app.lawnchair.organizer.planning.ProfileId
import app.lawnchair.organizer.planning.UserCategoryId
import app.lawnchair.organizer.planning.UserDefinedCategory
import app.lawnchair.organizer.rules.CategoryOverrideKey
import app.lawnchair.organizer.rules.CategoryOverrideMutation
import app.lawnchair.organizer.rules.CategoryOverrideSnapshot
import app.lawnchair.organizer.rules.CategoryOverrideStore
import app.lawnchair.organizer.rules.CategoryOverrideStoredIdentity
import app.lawnchair.organizer.rules.CategoryOverrideStoredReadResult
import app.lawnchair.organizer.rules.CategoryOverrideStoredSnapshot
import app.lawnchair.organizer.rules.CategoryOverrideWriteResult
import app.lawnchair.organizer.rules.OverrideSnapshotReadResult
import app.lawnchair.organizer.rules.PolicyInputIdentity
import app.lawnchair.organizer.rules.PolicySourceKind
import app.lawnchair.organizer.rules.UserDefinedCategoryCatalogIdentity
import app.lawnchair.organizer.rules.UserDefinedCategoryCatalogReadResult
import app.lawnchair.organizer.rules.UserDefinedCategoryCatalogSnapshot
import app.lawnchair.organizer.rules.UserDefinedCategoryMutation
import app.lawnchair.organizer.rules.UserDefinedCategoryStore
import app.lawnchair.organizer.rules.UserDefinedCategoryStoredReadResult
import app.lawnchair.organizer.rules.UserDefinedCategoryWriteResult
import app.lawnchair.organizer.rules.sha256Canonical
import app.lawnchair.organizer.rules.storedSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #336 authoring coordinator (accepted plan "Coordinator tests"): lease
 * mutual exclusion with runs/recovery in both directions, the overrides-first
 * two-store delete protocol (success, step-2 failure, retry), typed conflict
 * and no-op handling, and the catalog name-validation projection.
 */
class UserDefinedCategoryAuthoringCoordinatorTest {

    private val userId = UserCategoryId("3f2b8c4e-1234-4abc-9de0-1234567890ab")
    private val otherUserId = UserCategoryId("a1b2c3d4-5678-4abc-8de0-abcdefabcdef")

    @Test
    fun createRenameDeleteFollowTheTypedLifecycleWithStableIdentity() {
        val catalog = FakeCatalogStore()
        val coordinator = coordinator(catalog, FakeOverrideStore())

        val created = coordinator.create("  Commute  ") as UserDefinedCategoryAuthoringResult.Created
        assertEquals("Commute", created.entry.displayName)
        assertEquals(0, created.entry.assignedCount)

        val mintedId = created.entry.id
        val renamed = coordinator.rename(mintedId, "Commute tools") as UserDefinedCategoryAuthoringResult.Renamed
        assertEquals("the display name changes, the stable ID does not", listOf(mintedId), renamed.entries.map { it.id })
        assertEquals("Commute tools", renamed.entries.single().displayName)

        val deleted = coordinator.delete(mintedId) as UserDefinedCategoryAuthoringResult.Deleted
        assertTrue(deleted.entries.isEmpty())
    }

    @Test
    fun nameValidationProjectsTypedFailuresWithoutWrites() {
        val catalog = FakeCatalogStore().apply {
            seed(listOf(UserDefinedCategory(userId, "Existing"), UserDefinedCategory(otherUserId, "Games")))
        }
        val coordinator = coordinator(catalog, FakeOverrideStore())
        val generationBefore = catalog.generation

        assertEquals(UserDefinedCategoryAuthoringResult.InvalidName, coordinator.create("has|pipe"))
        assertEquals(UserDefinedCategoryAuthoringResult.InvalidName, coordinator.create("   "))
        assertEquals(UserDefinedCategoryAuthoringResult.DuplicateName, coordinator.create("Existing"))
        assertEquals(UserDefinedCategoryAuthoringResult.DuplicateName, coordinator.rename(userId, "Games"))
        assertEquals(UserDefinedCategoryAuthoringResult.InvalidName, coordinator.rename(userId, ""))
        assertEquals("no rejected request may write", generationBefore, catalog.generation)
    }

    @Test
    fun identicalRenameIsATypedNoOpThatPreservesGeneration() {
        val catalog = FakeCatalogStore().apply { seed(listOf(UserDefinedCategory(userId, "Same"))) }
        val coordinator = coordinator(catalog, FakeOverrideStore())

        val result = coordinator.rename(userId, "Same")

        assertTrue(result is UserDefinedCategoryAuthoringResult.NoChange)
        assertEquals(0L, catalog.generation)
    }

    @Test
    fun deleteRemovesAssignmentsFirstThroughExactlyOneAtomicPublication() {
        val catalog = FakeCatalogStore().apply { seed(listOf(UserDefinedCategory(userId, "Commute"), UserDefinedCategory(otherUserId, "Games"))) }
        val overrides = FakeOverrideStore().apply {
            seed(
                mapOf(
                    key("com.a") to CategoryIdentity.UserDefined(userId),
                    key("com.b") to CategoryIdentity.UserDefined(userId),
                    key("com.c") to CategoryIdentity.BuiltIn(CategoryId("NEWS")),
                    key("com.d") to CategoryIdentity.UserDefined(otherUserId),
                ),
            )
        }
        val coordinator = coordinator(catalog, overrides)

        val deleted = coordinator.delete(userId) as UserDefinedCategoryAuthoringResult.Deleted

        assertEquals("other user category keeps its assignment", 1, deleted.entries.single { it.id == otherUserId }.assignedCount)
        assertEquals("one atomic publication, not one per key", 1, overrides.commitCount)
        assertEquals(
            "only the two explicit Removes for the deleted category",
            listOf(
                CategoryOverrideMutation.Remove::class,
                CategoryOverrideMutation.Remove::class,
            ),
            overrides.recordedRequests.map { it::class },
        )
        assertFalse(key("com.a").packageName.value, overrides.assignments.keys.any { it == key("com.a") })
        assertNull(overrides.assignments[key("com.b")])
        assertEquals("built-in assignment is untouched", CategoryIdentity.BuiltIn(CategoryId("NEWS")), overrides.assignments[key("com.c")])
        assertEquals("other user category keeps its assignment", CategoryIdentity.UserDefined(otherUserId), overrides.assignments[key("com.d")])
    }

    @Test
    fun deleteWithNoAssignmentsSkipsTheOverridePublication() {
        val catalog = FakeCatalogStore().apply { seed(listOf(UserDefinedCategory(userId, "Commute"))) }
        val overrides = FakeOverrideStore().apply { seed(emptyMap()) }
        val coordinator = coordinator(catalog, overrides)

        assertTrue(coordinator.delete(userId) is UserDefinedCategoryAuthoringResult.Deleted)

        assertEquals(0, overrides.commitCount)
    }

    @Test
    fun deleteStep2FailureRendersTheTruthfulPartialStateAndRetryCompletes() {
        val catalog = FakeCatalogStore().apply { seed(listOf(UserDefinedCategory(userId, "Commute"))) }
        val overrides = FakeOverrideStore().apply {
            seed(mapOf(key("com.a") to CategoryIdentity.UserDefined(userId)))
        }
        val coordinator = coordinator(catalog, overrides)
        catalog.injectNextWriteResult = UserDefinedCategoryWriteResult.VerificationFailed

        val partial = coordinator.delete(userId) as UserDefinedCategoryAuthoringResult.PartialDelete

        assertEquals("the truthful count of durably removed assignments", 1, partial.removedAssignments)
        assertEquals("the empty category remains", listOf(userId), partial.entries.map { it.id })
        assertEquals("step 1 is durable: the assignment stays removed", 0, overrides.assignments.size)
        assertEquals("step 2 failed: the category stays present", listOf(userId), catalog.categories.map { it.id })

        // A retry completes the delete of the now empty category.
        val completed = coordinator.delete(userId) as UserDefinedCategoryAuthoringResult.Deleted
        assertTrue(completed.entries.isEmpty())
        assertEquals("retry must not publish further override removals", 1, overrides.commitCount)
    }

    @Test
    fun deleteStep2FailureWithoutRemovedAssignmentsIsThePlainTypedFailure() {
        val catalog = FakeCatalogStore().apply { seed(listOf(UserDefinedCategory(userId, "Commute"))) }
        val coordinator = coordinator(catalog, FakeOverrideStore().apply { seed(emptyMap()) })
        catalog.injectNextWriteResult = UserDefinedCategoryWriteResult.WriteFailed

        assertEquals(UserDefinedCategoryAuthoringResult.WriteFailed, coordinator.delete(userId))
        assertEquals("no partial state to report", listOf(userId), catalog.categories.map { it.id })
    }

    @Test
    fun step2ConflictSurfacesAsThePartialStateWithRetryPath() {
        val catalog = FakeCatalogStore().apply { seed(listOf(UserDefinedCategory(userId, "Commute"))) }
        val overrides = FakeOverrideStore().apply { seed(mapOf(key("com.a") to CategoryIdentity.UserDefined(userId))) }
        val coordinator = coordinator(catalog, overrides)
        catalog.injectNextWriteResult = UserDefinedCategoryWriteResult.Conflict

        val partial = coordinator.delete(userId) as UserDefinedCategoryAuthoringResult.PartialDelete

        assertEquals("step 1 stayed durable through the step-2 conflict", 1, partial.removedAssignments)
        assertEquals(listOf(userId), partial.entries.map { it.id })
    }

    @Test
    fun catalogConflictProjectsTypedWithoutAnyWrite() {
        val catalog = FakeCatalogStore().apply { seed(listOf(UserDefinedCategory(userId, "Commute"))) }
        val coordinator = coordinator(catalog, FakeOverrideStore())
        val generation = catalog.generation

        catalog.injectNextWriteResult = UserDefinedCategoryWriteResult.Conflict
        assertEquals(UserDefinedCategoryAuthoringResult.Conflict, coordinator.create("New"))
        catalog.injectNextWriteResult = UserDefinedCategoryWriteResult.Conflict
        assertEquals(UserDefinedCategoryAuthoringResult.Conflict, coordinator.rename(userId, "New name"))
        assertEquals("no rejected request may write", generation, catalog.generation)
    }

    @Test
    fun authoringIsRejectedWhileARunOrRecoveryHoldsTheLease() {
        val coordinator = coordinator(FakeCatalogStore(), FakeOverrideStore())
        for (kind in listOf(OrganizationOperationLease.Kind.RUN, OrganizationOperationLease.Kind.RECOVERY, OrganizationOperationLease.Kind.AUTHORING)) {
            val lease = OrganizationOperationLease.tryAcquire(kind)!!
            try {
                assertEquals(UserDefinedCategoryAuthoringResult.OrganizationRunActive, coordinator.create("X"))
                assertEquals(UserDefinedCategoryAuthoringResult.OrganizationRunActive, coordinator.rename(userId, "Y"))
                assertEquals(UserDefinedCategoryAuthoringResult.OrganizationRunActive, coordinator.delete(userId))
            } finally {
                lease.close()
            }
        }
        // After the operation terminates, authoring is admitted again.
        assertTrue(coordinator.create("After") is UserDefinedCategoryAuthoringResult.Created)
    }

    @Test
    fun authoringHoldsTheLeaseForItsWholeMutation() {
        var leaseFreeDuringMutation = true
        val catalog = FakeCatalogStore()
        catalog.onMutate = {
            leaseFreeDuringMutation = OrganizationOperationLease.tryAcquire(OrganizationOperationLease.Kind.AUTHORING) == null
        }
        val coordinator = coordinator(catalog, FakeOverrideStore())

        assertTrue(coordinator.create("Exclusive") is UserDefinedCategoryAuthoringResult.Created)
        assertTrue("runs/recovery/other authoring must be rejected during the mutation", leaseFreeDuringMutation)
    }

    @Test
    fun loadProjectsEntriesInCanonicalOrderWithAssignmentCounts() {
        // Canonical order: built-in byte order first, then user IDs in byte
        // order — "a1b2…" sorts before "3f2b…"? No: byte order puts "3" (0x33)
        // before "a" (0x61), so the 3f… entry leads.
        val catalog = FakeCatalogStore().apply {
            seed(listOf(UserDefinedCategory(otherUserId, "Games"), UserDefinedCategory(userId, "Commute")))
        }
        val overrides = FakeOverrideStore().apply {
            seed(
                mapOf(
                    key("com.a") to CategoryIdentity.UserDefined(userId),
                    key("com.b") to CategoryIdentity.UserDefined(userId),
                    key("com.c") to CategoryIdentity.UserDefined(otherUserId),
                ),
            )
        }

        val loaded = coordinator(catalog, overrides).load() as UserDefinedCategoryAuthoringResult.Loaded

        assertEquals(listOf(userId, otherUserId), loaded.entries.map { it.id })
        assertEquals(listOf(2, 1), loaded.entries.map { it.assignedCount })
        assertEquals(listOf("Commute", "Games"), loaded.entries.map { it.displayName })
    }

    @Test
    fun unreadableOrUnsupportedSourcesFailClosed() {
        val readableCatalog = FakeCatalogStore().apply { seed(listOf(UserDefinedCategory(userId, "Commute"))) }
        val brokenCatalog = FakeCatalogStore().apply { unreadable = true }
        val unsupportedCatalog = FakeCatalogStore().apply { unsupportedSchema = true }
        val brokenOverrides = FakeOverrideStore().apply { unreadable = true }

        assertEquals(UserDefinedCategoryAuthoringResult.CatalogUnreadable, coordinator(brokenCatalog, FakeOverrideStore()).load())
        assertEquals(UserDefinedCategoryAuthoringResult.UnsupportedSchema, coordinator(unsupportedCatalog, FakeOverrideStore()).load())
        assertEquals(UserDefinedCategoryAuthoringResult.OverrideStoreUnavailable, coordinator(readableCatalog, brokenOverrides).load())
        assertEquals(UserDefinedCategoryAuthoringResult.CatalogUnreadable, coordinator(brokenCatalog, FakeOverrideStore()).create("X"))
    }

    // ---- fixtures ----------------------------------------------------------

    private fun coordinator(catalog: FakeCatalogStore, overrides: FakeOverrideStore) = UserDefinedCategoryAuthoringCoordinator(catalog, overrides)

    private fun key(packageName: String) = CategoryOverrideKey(PackageName(packageName), ProfileId("0"))

    private class FakeCatalogStore : UserDefinedCategoryStore {
        var snapshot = storedSnapshot(0L, emptyList())
        var unreadable = false
        var unsupportedSchema = false
        var injectNextWriteResult: UserDefinedCategoryWriteResult? = null
        var onMutate: (() -> Unit)? = null

        val generation: Long get() = snapshot.identity.generation
        val categories: List<UserDefinedCategory> get() = snapshot.categories

        fun seed(entries: List<UserDefinedCategory>) {
            snapshot = storedSnapshot(0L, entries)
        }

        override fun read(): UserDefinedCategoryCatalogReadResult = when {
            unreadable -> UserDefinedCategoryCatalogReadResult.Unreadable
            unsupportedSchema -> UserDefinedCategoryCatalogReadResult.UnsupportedSchema
            else -> UserDefinedCategoryCatalogReadResult.Ready(visible())
        }

        override fun readStored(): UserDefinedCategoryStoredReadResult = when {
            unreadable -> UserDefinedCategoryStoredReadResult.Unreadable
            unsupportedSchema -> UserDefinedCategoryStoredReadResult.UnsupportedSchema
            else -> UserDefinedCategoryStoredReadResult.Ready(snapshot)
        }

        override fun mutate(
            request: UserDefinedCategoryMutation,
            expected: app.lawnchair.organizer.rules.UserDefinedCategoryStoredIdentity,
        ): UserDefinedCategoryWriteResult {
            onMutate?.invoke()
            injectNextWriteResult?.let {
                injectNextWriteResult = null
                return it
            }
            if (snapshot.identity != expected) return UserDefinedCategoryWriteResult.Conflict
            val previousIds = snapshot.categories.map { it.id }.toSet()
            val next = when (request) {
                is UserDefinedCategoryMutation.Create -> {
                    // The same normalization + validation rules as the store.
                    val normalized = app.lawnchair.organizer.rules.UserDefinedCategoryNameRules.normalize(request.displayName)
                    if (!app.lawnchair.organizer.rules.UserDefinedCategoryNameRules.isValid(normalized)) return UserDefinedCategoryWriteResult.InvalidName
                    if (snapshot.categories.any { it.displayName == normalized }) return UserDefinedCategoryWriteResult.DuplicateName
                    // The fixture IDs never collide with the seeded UUIDs.
                    val minted = UserCategoryId("00000000-0000-4000-8000-000000000001")
                    snapshot.categories + UserDefinedCategory(minted, normalized)
                }

                is UserDefinedCategoryMutation.Rename -> {
                    if (snapshot.categories.none { it.id == request.id }) return UserDefinedCategoryWriteResult.UnknownId
                    val normalized = app.lawnchair.organizer.rules.UserDefinedCategoryNameRules.normalize(request.displayName)
                    if (!app.lawnchair.organizer.rules.UserDefinedCategoryNameRules.isValid(normalized)) return UserDefinedCategoryWriteResult.InvalidName
                    if (snapshot.categories.any { it.id != request.id && it.displayName == normalized }) {
                        return UserDefinedCategoryWriteResult.DuplicateName
                    }
                    snapshot.categories.map { if (it.id == request.id) it.copy(displayName = normalized) else it }
                }

                is UserDefinedCategoryMutation.Delete -> {
                    if (snapshot.categories.none { it.id == request.id }) return UserDefinedCategoryWriteResult.UnknownId
                    snapshot.categories.filterNot { it.id == request.id }
                }
            }
            if (next == snapshot.categories) return UserDefinedCategoryWriteResult.NoChange(snapshot.identity, visible().identity)
            snapshot = storedSnapshot(snapshot.identity.generation + 1L, next)
            val created = snapshot.categories.firstOrNull { it.id !in previousIds }
            return UserDefinedCategoryWriteResult.Committed(snapshot.identity, visible().identity, created)
        }

        private fun visible(): UserDefinedCategoryCatalogSnapshot = UserDefinedCategoryCatalogSnapshot(
            schemaVersion = snapshot.identity.schemaVersion,
            generation = snapshot.identity.generation,
            categories = snapshot.categories,
            identity = UserDefinedCategoryCatalogIdentity.identityOf(
                snapshot.identity.schemaVersion,
                snapshot.identity.generation,
                snapshot.categories,
            ),
        )
    }

    private class FakeOverrideStore : CategoryOverrideStore {
        var assignments: Map<CategoryOverrideKey, CategoryIdentity> = emptyMap()
        var snapshot = CategoryOverrideStoredSnapshot(storedIdentity(0L, emptyMap()), emptyMap())
        var unreadable = false
        var commitCount = 0
        val recordedRequests = mutableListOf<CategoryOverrideMutation>()

        fun seed(entries: Map<CategoryOverrideKey, CategoryIdentity>) {
            assignments = entries
            snapshot = CategoryOverrideStoredSnapshot(storedIdentity(0L, entries), entries)
        }

        override fun readStored(): CategoryOverrideStoredReadResult = if (unreadable) CategoryOverrideStoredReadResult.Unreadable else CategoryOverrideStoredReadResult.Ready(snapshot)

        override fun read(capturedProfiles: Set<ProfileId>): OverrideSnapshotReadResult {
            if (unreadable) return OverrideSnapshotReadResult.Unreadable
            val visible = snapshot.assignments.filterKeys { it.profile in capturedProfiles }
            return OverrideSnapshotReadResult.Ready(
                CategoryOverrideSnapshot(
                    schemaVersion = 2,
                    generation = snapshot.identity.generation,
                    assignments = visible,
                    identity = visibleIdentity(snapshot.identity.generation),
                ),
            )
        }

        override fun mutate(
            request: CategoryOverrideMutation,
            expected: CategoryOverrideStoredIdentity,
            verificationProfiles: Set<ProfileId>,
        ): CategoryOverrideWriteResult = mutateAll(listOf(request), expected, verificationProfiles)

        override fun mutateAll(
            requests: List<CategoryOverrideMutation>,
            expected: CategoryOverrideStoredIdentity,
            verificationProfiles: Set<ProfileId>,
        ): CategoryOverrideWriteResult {
            recordedRequests += requests
            val next = snapshot.assignments.toMutableMap()
            var changed = false
            for (request in requests) {
                when (request) {
                    is CategoryOverrideMutation.Set -> if (next[request.key] != request.category) {
                        next[request.key] = request.category
                        changed = true
                    }

                    is CategoryOverrideMutation.Remove -> changed = next.remove(request.key) != null || changed
                }
            }
            if (!changed) return CategoryOverrideWriteResult.NoChange(snapshot.identity, visibleIdentity(snapshot.identity.generation, next))
            assignments = next
            snapshot = CategoryOverrideStoredSnapshot(storedIdentity(snapshot.identity.generation + 1L, next), next)
            commitCount += 1
            return CategoryOverrideWriteResult.Committed(snapshot.identity, visibleIdentity(snapshot.identity.generation, next))
        }

        private fun storedIdentity(generation: Long, entries: Map<CategoryOverrideKey, CategoryIdentity>) = CategoryOverrideStoredIdentity(
            2,
            generation,
            sha256Canonical(canonicalAssignments(entries)),
        )

        private fun canonicalAssignments(entries: Map<CategoryOverrideKey, CategoryIdentity>): String = entries.entries
            .sortedWith(compareBy({ it.key.profile.value }, { it.key.packageName.value }))
            .joinToString("\n") { "${it.key.packageName.value}|${it.key.profile.value}|${it.value.canonicalValue}" }

        private fun visibleIdentity(generation: Long, entries: Map<CategoryOverrideKey, CategoryIdentity> = snapshot.assignments) = PolicyInputIdentity(
            PolicySourceKind.CATEGORY_OVERRIDE_SNAPSHOT,
            "schema-2-generation-$generation",
            sha256Canonical(canonicalAssignments(entries)),
        )
    }
}
