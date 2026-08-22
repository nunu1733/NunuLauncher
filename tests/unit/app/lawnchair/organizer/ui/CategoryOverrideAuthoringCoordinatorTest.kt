package app.lawnchair.organizer.ui

import app.lawnchair.organizer.planning.CategoryId
import app.lawnchair.organizer.planning.PackageName
import app.lawnchair.organizer.planning.ProfileId
import app.lawnchair.organizer.rules.BuiltInOrganizerPolicyBundleSource
import app.lawnchair.organizer.rules.CategoryOverrideKey
import app.lawnchair.organizer.rules.CategoryOverrideMutation
import app.lawnchair.organizer.rules.CategoryOverrideSnapshot
import app.lawnchair.organizer.rules.CategoryOverrideSnapshotSource
import app.lawnchair.organizer.rules.CategoryOverrideStore
import app.lawnchair.organizer.rules.CategoryOverrideStoredIdentity
import app.lawnchair.organizer.rules.CategoryOverrideStoredReadResult
import app.lawnchair.organizer.rules.CategoryOverrideStoredSnapshot
import app.lawnchair.organizer.rules.CategoryOverrideWriteResult
import app.lawnchair.organizer.rules.OverrideSnapshotReadResult
import app.lawnchair.organizer.rules.PolicyInputIdentity
import app.lawnchair.organizer.rules.PolicySourceKind
import app.lawnchair.organizer.rules.sha256Canonical
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CategoryOverrideAuthoringCoordinatorTest {
    @Test
    fun setChangeAndRemoveKeepSamePackageProfilesIsolatedAndAllowExplicitOther() {
        val personal = app("com.example.same", "0", CategoryOverrideProfile.PERSONAL)
        val work = app("com.example.same", "10", CategoryOverrideProfile.WORK)
        val store = InMemoryStore()
        val coordinator = coordinator(store) { listOf(personal, work) }

        assertEquals(CategoryOverrideAuthoringResult.Saved::class, coordinator.save(personal, CategoryId("OTHER"))::class)
        assertEquals(CategoryId("OTHER"), store.assignments[personal.key])
        assertFalse(work.key in store.assignments)

        assertEquals(CategoryOverrideAuthoringResult.Saved::class, coordinator.save(personal, CategoryId("GAME"))::class)
        assertEquals(CategoryId("GAME"), store.assignments[personal.key])
        assertFalse(work.key in store.assignments)

        assertEquals(CategoryOverrideAuthoringResult.Saved::class, coordinator.save(personal, null)::class)
        assertFalse(personal.key in store.assignments)
        assertFalse(work.key in store.assignments)
        assertEquals(
            listOf(CategoryOverrideMutation.Set::class, CategoryOverrideMutation.Set::class, CategoryOverrideMutation.Remove::class),
            store.requests.map { it::class },
        )
    }

    @Test
    fun unavailableTargetDoesNotInvokeStoreMutationOrAdvanceGeneration() {
        val target = app("com.example.removed", "0", CategoryOverrideProfile.PERSONAL)
        val store = InMemoryStore()
        val coordinator = coordinator(store) { emptyList() }

        assertEquals(CategoryOverrideAuthoringResult.TargetUnavailable, coordinator.save(target, CategoryId("SOCIAL")))
        assertTrue(store.requests.isEmpty())
        assertEquals(0L, store.snapshot.identity.generation)
    }

    @Test
    fun loadRetainsCanonicalProfileIdAndListsSamePackageSeparately() {
        val personal = app("com.example.same", "0", CategoryOverrideProfile.PERSONAL)
        val work = app("com.example.same", "10", CategoryOverrideProfile.WORK)
        val loaded = coordinator(InMemoryStore()) { listOf(personal, work) }.load() as CategoryOverrideAuthoringResult.Loaded

        assertEquals(setOf(ProfileId("0"), ProfileId("10")), loaded.apps.mapTo(linkedSetOf()) { it.key.profile })
        assertEquals(setOf(personal.key, work.key), loaded.apps.mapTo(linkedSetOf()) { it.key })
    }

    private fun coordinator(
        store: InMemoryStore,
        inventory: () -> List<CategoryOverrideApp>,
    ) = CategoryOverrideAuthoringCoordinator(
        store,
        BuiltInOrganizerPolicyBundleSource,
        CategoryOverrideAppInventory { inventory() },
    )

    private fun app(packageName: String, profile: String, kind: CategoryOverrideProfile) = CategoryOverrideApp(
        key = CategoryOverrideKey(PackageName(packageName), ProfileId(profile)),
        label = "Example",
        profile = kind,
        icon = null,
        assignedCategory = null,
    )

    private class InMemoryStore : CategoryOverrideStore {
        var assignments: Map<CategoryOverrideKey, CategoryId> = emptyMap()
        var snapshot = nextSnapshot(0L, assignments)
        val requests = mutableListOf<CategoryOverrideMutation>()

        override fun readStored(): CategoryOverrideStoredReadResult = CategoryOverrideStoredReadResult.Ready(snapshot)

        override fun read(capturedProfiles: Set<ProfileId>): OverrideSnapshotReadResult {
            val visible = assignments.filterKeys { it.profile in capturedProfiles }
            return OverrideSnapshotReadResult.Ready(
                CategoryOverrideSnapshot(
                    schemaVersion = 1,
                    generation = snapshot.identity.generation,
                    assignments = visible,
                    identity = PolicyInputIdentity(
                        PolicySourceKind.CATEGORY_OVERRIDE_SNAPSHOT,
                        "schema-1-generation-${snapshot.identity.generation}",
                        sha256Canonical(
                            visible.entries.sortedBy { it.key.profile.value }.joinToString("\n") {
                                "${it.key.packageName.value}|${it.key.profile.value}|${it.value.value}"
                            },
                        ),
                    ),
                ),
            )
        }

        override fun mutate(
            request: CategoryOverrideMutation,
            expected: CategoryOverrideStoredIdentity,
            verificationProfiles: Set<ProfileId>,
        ): CategoryOverrideWriteResult {
            requests += request
            val next = assignments.toMutableMap()
            when (request) {
                is CategoryOverrideMutation.Set -> next[request.key] = request.category
                is CategoryOverrideMutation.Remove -> next.remove(request.key)
            }
            assignments = next
            snapshot = nextSnapshot(snapshot.identity.generation + 1L, assignments)
            return CategoryOverrideWriteResult.Committed(
                snapshot.identity,
                (read(verificationProfiles) as OverrideSnapshotReadResult.Ready).snapshot.identity,
            )
        }

        private fun nextSnapshot(
            generation: Long,
            entries: Map<CategoryOverrideKey, CategoryId>,
        ): CategoryOverrideStoredSnapshot {
            val canonical = entries.entries.sortedWith(
                compareBy<Map.Entry<CategoryOverrideKey, CategoryId>> { it.key.profile.value }
                    .thenBy { it.key.packageName.value },
            ).joinToString("\n") { "${it.key.packageName.value}|${it.key.profile.value}|${it.value.value}" }
            return CategoryOverrideStoredSnapshot(
                CategoryOverrideStoredIdentity(1, generation, sha256Canonical(canonical)),
                entries,
            )
        }
    }
}
