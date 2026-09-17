package app.lawnchair.organizer.ui

import android.content.Context
import app.lawnchair.organizer.planning.CategoryIdentity
import app.lawnchair.organizer.planning.UserCategoryId
import app.lawnchair.organizer.rules.CategoryOverrideMutation
import app.lawnchair.organizer.rules.CategoryOverrideStore
import app.lawnchair.organizer.rules.CategoryOverrideStoreModule
import app.lawnchair.organizer.rules.CategoryOverrideStoredReadResult
import app.lawnchair.organizer.rules.CategoryOverrideWriteResult
import app.lawnchair.organizer.rules.UserDefinedCategoryCatalogReadResult
import app.lawnchair.organizer.rules.UserDefinedCategoryMutation
import app.lawnchair.organizer.rules.UserDefinedCategoryStore
import app.lawnchair.organizer.rules.UserDefinedCategoryStoreModule
import app.lawnchair.organizer.rules.UserDefinedCategoryStoredReadResult
import app.lawnchair.organizer.rules.UserDefinedCategoryWriteResult

/**
 * UI-safe projection of one user-defined catalog entry. The stable ID stays
 * internal — user surfaces render [displayName] only, never the raw ID, and
 * [assignedCount] feeds the delete confirmation ("N apps return to automatic
 * classification").
 */
internal data class UserDefinedCategoryEntry(
    val id: UserCategoryId,
    val displayName: String,
    val assignedCount: Int,
)

internal sealed interface UserDefinedCategoryAuthoringResult {
    data class Loaded(val entries: List<UserDefinedCategoryEntry>) : UserDefinedCategoryAuthoringResult

    /** [entry] is the minted Create result with its zero assignment count. */
    data class Created(val entry: UserDefinedCategoryEntry) : UserDefinedCategoryAuthoringResult

    data class Renamed(val entries: List<UserDefinedCategoryEntry>) : UserDefinedCategoryAuthoringResult

    data class NoChange(val entries: List<UserDefinedCategoryEntry>) : UserDefinedCategoryAuthoringResult

    /** Both publications verified: the category and its assignments are gone. */
    data class Deleted(val entries: List<UserDefinedCategoryEntry>) : UserDefinedCategoryAuthoringResult

    /**
     * The overrides-first delete committed step 1 but step 2 failed. The
     * truthful durable state — assignments removed, the (now empty) category
     * remains — is rendered as-is, and a retry completes the delete.
     */
    data class PartialDelete(val entries: List<UserDefinedCategoryEntry>, val removedAssignments: Int) : UserDefinedCategoryAuthoringResult

    data object InvalidName : UserDefinedCategoryAuthoringResult
    data object DuplicateName : UserDefinedCategoryAuthoringResult
    data object CapacityExceeded : UserDefinedCategoryAuthoringResult
    data object OrganizationRunActive : UserDefinedCategoryAuthoringResult
    data object CatalogUnreadable : UserDefinedCategoryAuthoringResult
    data object UnsupportedSchema : UserDefinedCategoryAuthoringResult
    data object OverrideStoreUnavailable : UserDefinedCategoryAuthoringResult
    data object Conflict : UserDefinedCategoryAuthoringResult
    data object WriteFailed : UserDefinedCategoryAuthoringResult
    data object VerificationFailed : UserDefinedCategoryAuthoringResult
}

/**
 * Coordinator for user-defined category authoring (Issue #336). It mirrors the
 * `CategoryOverrideAuthoring` pattern: the [OrganizationOperationLease]'s
 * single admission domain is held for every mutation (runs, recovery, and all
 * authoring reject each other), Rule Management's typed store results are
 * projected as-is onto the typed UI results, and the UI layer never touches a
 * store directly.
 *
 * Delete is the spec's explicit two-store, overrides-first protocol: (1) every
 * override assignment referencing the category is removed through exactly ONE
 * atomic override-store publication of explicit per-key `Remove` mutations;
 * (2) the catalog snapshot without the category is published. The operation is
 * committed only when both publications verify. It is deliberately NOT a
 * cross-store transaction: a step-2 failure leaves the durable, benign partial
 * state (assignments removed, empty category remains) which [UserDefinedCategoryAuthoringResult.PartialDelete]
 * renders truthfully and a retry completes.
 */
internal class UserDefinedCategoryAuthoringCoordinator internal constructor(
    private val store: UserDefinedCategoryStore,
    private val overrides: CategoryOverrideStore,
) {
    constructor(context: Context) : this(
        store = UserDefinedCategoryStoreModule.get(context),
        overrides = CategoryOverrideStoreModule.get(context),
    )

    /** Canonical-order entries with their current assignment counts. */
    fun load(): UserDefinedCategoryAuthoringResult = when (val result = readEntries()) {
        is EntriesRead.Ready -> UserDefinedCategoryAuthoringResult.Loaded(result.entries)
        is EntriesRead.Failed -> result.typed
    }

    fun create(displayName: String): UserDefinedCategoryAuthoringResult {
        val lease = OrganizationOperationLease.tryAcquire(OrganizationOperationLease.Kind.AUTHORING)
            ?: return UserDefinedCategoryAuthoringResult.OrganizationRunActive
        return try {
            val expected = when (val stored = readStoredForMutation()) {
                is StoredRead.Ready -> stored.identity
                is StoredRead.Failed -> return stored.typed
            }
            when (val result = store.mutate(UserDefinedCategoryMutation.Create(displayName), expected)) {
                is UserDefinedCategoryWriteResult.Committed -> {
                    val minted = requireNotNull(result.created) { "verified Create must report the minted entry" }
                    UserDefinedCategoryAuthoringResult.Created(UserDefinedCategoryEntry(minted.id, minted.displayName, 0))
                }

                is UserDefinedCategoryWriteResult.NoChange -> when (val entries = readEntries()) {
                    is EntriesRead.Ready -> UserDefinedCategoryAuthoringResult.NoChange(entries.entries)
                    is EntriesRead.Failed -> entries.typed
                }

                UserDefinedCategoryWriteResult.InvalidName -> UserDefinedCategoryAuthoringResult.InvalidName

                UserDefinedCategoryWriteResult.DuplicateName -> UserDefinedCategoryAuthoringResult.DuplicateName

                UserDefinedCategoryWriteResult.CapacityExceeded -> UserDefinedCategoryAuthoringResult.CapacityExceeded

                UserDefinedCategoryWriteResult.StoreUnreadable -> UserDefinedCategoryAuthoringResult.CatalogUnreadable

                UserDefinedCategoryWriteResult.UnsupportedSchema -> UserDefinedCategoryAuthoringResult.UnsupportedSchema

                UserDefinedCategoryWriteResult.Conflict -> UserDefinedCategoryAuthoringResult.Conflict

                UserDefinedCategoryWriteResult.WriteFailed -> UserDefinedCategoryAuthoringResult.WriteFailed

                UserDefinedCategoryWriteResult.VerificationFailed -> UserDefinedCategoryAuthoringResult.VerificationFailed

                UserDefinedCategoryWriteResult.UnknownId -> UserDefinedCategoryAuthoringResult.CatalogUnreadable
            }
        } finally {
            lease.close()
        }
    }

    fun rename(id: UserCategoryId, displayName: String): UserDefinedCategoryAuthoringResult {
        val lease = OrganizationOperationLease.tryAcquire(OrganizationOperationLease.Kind.AUTHORING)
            ?: return UserDefinedCategoryAuthoringResult.OrganizationRunActive
        return try {
            val expected = when (val stored = readStoredForMutation()) {
                is StoredRead.Ready -> stored.identity
                is StoredRead.Failed -> return stored.typed
            }
            when (val result = store.mutate(UserDefinedCategoryMutation.Rename(id, displayName), expected)) {
                is UserDefinedCategoryWriteResult.Committed -> when (val entries = readEntries()) {
                    is EntriesRead.Ready -> UserDefinedCategoryAuthoringResult.Renamed(entries.entries)
                    is EntriesRead.Failed -> entries.typed
                }

                is UserDefinedCategoryWriteResult.NoChange -> when (val entries = readEntries()) {
                    is EntriesRead.Ready -> UserDefinedCategoryAuthoringResult.NoChange(entries.entries)
                    is EntriesRead.Failed -> entries.typed
                }

                UserDefinedCategoryWriteResult.InvalidName -> UserDefinedCategoryAuthoringResult.InvalidName

                UserDefinedCategoryWriteResult.DuplicateName -> UserDefinedCategoryAuthoringResult.DuplicateName

                UserDefinedCategoryWriteResult.UnknownId -> UserDefinedCategoryAuthoringResult.CatalogUnreadable

                UserDefinedCategoryWriteResult.CapacityExceeded -> UserDefinedCategoryAuthoringResult.CatalogUnreadable

                UserDefinedCategoryWriteResult.StoreUnreadable -> UserDefinedCategoryAuthoringResult.CatalogUnreadable

                UserDefinedCategoryWriteResult.UnsupportedSchema -> UserDefinedCategoryAuthoringResult.UnsupportedSchema

                UserDefinedCategoryWriteResult.Conflict -> UserDefinedCategoryAuthoringResult.Conflict

                UserDefinedCategoryWriteResult.WriteFailed -> UserDefinedCategoryAuthoringResult.WriteFailed

                UserDefinedCategoryWriteResult.VerificationFailed -> UserDefinedCategoryAuthoringResult.VerificationFailed
            }
        } finally {
            lease.close()
        }
    }

    /**
     * The overrides-first two-store delete protocol (spec "Delete semantics").
     * Step-2 failures surface as the truthful [UserDefinedCategoryAuthoringResult.PartialDelete];
     * a retry re-runs the whole protocol and completes the delete of the now
     * empty category.
     */
    fun delete(id: UserCategoryId): UserDefinedCategoryAuthoringResult {
        val lease = OrganizationOperationLease.tryAcquire(OrganizationOperationLease.Kind.AUTHORING)
            ?: return UserDefinedCategoryAuthoringResult.OrganizationRunActive
        return try {
            // Step 1: remove every override assignment referencing the category
            // through ONE atomic publication of explicit per-key Removes.
            val overrideSnapshot = when (val stored = overrides.readStored()) {
                is CategoryOverrideStoredReadResult.Ready -> stored.snapshot

                CategoryOverrideStoredReadResult.Unreadable,
                CategoryOverrideStoredReadResult.MigrationBarrierUncertain,
                -> return UserDefinedCategoryAuthoringResult.OverrideStoreUnavailable

                CategoryOverrideStoredReadResult.UnsupportedSchema -> return UserDefinedCategoryAuthoringResult.UnsupportedSchema
            }
            val referencing = overrideSnapshot.assignments
                .filterValues { value -> value is CategoryIdentity.UserDefined && value.id == id }
                .keys
            var removedAssignments = 0
            if (referencing.isNotEmpty()) {
                val verificationProfiles = referencing.mapTo(linkedSetOf()) { it.profile }
                when (
                    val write = overrides.mutateAll(
                        referencing.map { key -> CategoryOverrideMutation.Remove(key) },
                        overrideSnapshot.identity,
                        verificationProfiles,
                    )
                ) {
                    is CategoryOverrideWriteResult.Committed,
                    is CategoryOverrideWriteResult.NoChange,
                    -> removedAssignments = referencing.size

                    CategoryOverrideWriteResult.Conflict -> return UserDefinedCategoryAuthoringResult.Conflict

                    CategoryOverrideWriteResult.StoreUnreadable,
                    CategoryOverrideWriteResult.MigrationBarrierUncertain,
                    CategoryOverrideWriteResult.TaxonomyUnavailable,
                    -> return UserDefinedCategoryAuthoringResult.OverrideStoreUnavailable

                    CategoryOverrideWriteResult.UnsupportedSchema -> return UserDefinedCategoryAuthoringResult.UnsupportedSchema

                    CategoryOverrideWriteResult.WriteFailed -> return UserDefinedCategoryAuthoringResult.WriteFailed

                    CategoryOverrideWriteResult.VerificationFailed -> return UserDefinedCategoryAuthoringResult.VerificationFailed

                    CategoryOverrideWriteResult.InvalidCategory -> return UserDefinedCategoryAuthoringResult.OverrideStoreUnavailable
                }
            }

            // Step 2: publish the catalog snapshot without the category. Any
            // failure here is the defined partial state — never presented as an
            // undone operation.
            val expected = when (val stored = readStoredForMutation()) {
                is StoredRead.Ready -> stored.identity
                is StoredRead.Failed -> return partialOrTyped(stored.typed, removedAssignments)
            }
            when (val result = store.mutate(UserDefinedCategoryMutation.Delete(id), expected)) {
                is UserDefinedCategoryWriteResult.Committed,
                // Already absent (e.g. a concurrent delete): the operation is
                // idempotently complete.
                is UserDefinedCategoryWriteResult.NoChange,
                UserDefinedCategoryWriteResult.UnknownId,
                -> when (val entries = readEntries()) {
                    is EntriesRead.Ready -> UserDefinedCategoryAuthoringResult.Deleted(entries.entries)
                    is EntriesRead.Failed -> entries.typed
                }

                else -> return partialOrTyped(mapStep2Failure(result), removedAssignments)
            }
        } finally {
            lease.close()
        }
    }

    /** Maps a typed step-2 failure; the caller turns it into the partial state. */
    private fun mapStep2Failure(result: UserDefinedCategoryWriteResult): UserDefinedCategoryAuthoringResult = when (result) {
        UserDefinedCategoryWriteResult.Conflict -> UserDefinedCategoryAuthoringResult.Conflict
        UserDefinedCategoryWriteResult.WriteFailed -> UserDefinedCategoryAuthoringResult.WriteFailed
        UserDefinedCategoryWriteResult.VerificationFailed -> UserDefinedCategoryAuthoringResult.VerificationFailed
        UserDefinedCategoryWriteResult.StoreUnreadable -> UserDefinedCategoryAuthoringResult.CatalogUnreadable
        UserDefinedCategoryWriteResult.UnsupportedSchema -> UserDefinedCategoryAuthoringResult.UnsupportedSchema
        else -> UserDefinedCategoryAuthoringResult.CatalogUnreadable
    }

    /**
     * Step 2 failed after step 1 removed assignments: the committed state is
     * "assignments removed, category remains" and the result renders it
     * truthfully. With no assignments removed the step-2 failure is the plain
     * typed failure.
     */
    private fun partialOrTyped(typed: UserDefinedCategoryAuthoringResult, removedAssignments: Int): UserDefinedCategoryAuthoringResult {
        if (removedAssignments == 0) return typed
        return when (val entries = readEntries()) {
            is EntriesRead.Ready -> UserDefinedCategoryAuthoringResult.PartialDelete(entries.entries, removedAssignments)
            is EntriesRead.Failed -> UserDefinedCategoryAuthoringResult.PartialDelete(emptyList(), removedAssignments)
        }
    }

    private sealed interface EntriesRead {
        data class Ready(val entries: List<UserDefinedCategoryEntry>) : EntriesRead
        data class Failed(val typed: UserDefinedCategoryAuthoringResult) : EntriesRead
    }

    private fun readEntries(): EntriesRead {
        val catalog = when (val read = store.read()) {
            is UserDefinedCategoryCatalogReadResult.Ready -> read.snapshot
            UserDefinedCategoryCatalogReadResult.Unreadable -> return EntriesRead.Failed(UserDefinedCategoryAuthoringResult.CatalogUnreadable)
            UserDefinedCategoryCatalogReadResult.UnsupportedSchema -> return EntriesRead.Failed(UserDefinedCategoryAuthoringResult.UnsupportedSchema)
        }
        val counts = when (val stored = overrides.readStored()) {
            is CategoryOverrideStoredReadResult.Ready ->
                stored.snapshot.assignments.values
                    .filterIsInstance<CategoryIdentity.UserDefined>()
                    .groupingBy { it.id }
                    .eachCount()

            CategoryOverrideStoredReadResult.Unreadable,
            CategoryOverrideStoredReadResult.MigrationBarrierUncertain,
            -> return EntriesRead.Failed(UserDefinedCategoryAuthoringResult.OverrideStoreUnavailable)

            CategoryOverrideStoredReadResult.UnsupportedSchema -> return EntriesRead.Failed(UserDefinedCategoryAuthoringResult.UnsupportedSchema)
        }
        return EntriesRead.Ready(
            catalog.categories.map { entry -> UserDefinedCategoryEntry(entry.id, entry.displayName, counts[entry.id] ?: 0) },
        )
    }

    private sealed interface StoredRead {
        data class Ready(val identity: app.lawnchair.organizer.rules.UserDefinedCategoryStoredIdentity) : StoredRead
        data class Failed(val typed: UserDefinedCategoryAuthoringResult) : StoredRead
    }

    private fun readStoredForMutation(): StoredRead = when (val stored = store.readStored()) {
        is UserDefinedCategoryStoredReadResult.Ready -> StoredRead.Ready(stored.snapshot.identity)
        UserDefinedCategoryStoredReadResult.Unreadable -> StoredRead.Failed(UserDefinedCategoryAuthoringResult.CatalogUnreadable)
        UserDefinedCategoryStoredReadResult.UnsupportedSchema -> StoredRead.Failed(UserDefinedCategoryAuthoringResult.UnsupportedSchema)
    }
}
