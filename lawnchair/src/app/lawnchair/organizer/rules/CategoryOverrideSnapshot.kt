package app.lawnchair.organizer.rules

import android.content.SharedPreferences
import app.lawnchair.organizer.planning.CategoryId
import app.lawnchair.organizer.planning.PackageName
import app.lawnchair.organizer.planning.ProfileId

/** Read-side contract only. Override authoring and migration writers are outside Issue #83. */
interface CategoryOverrideSnapshotSource {
    fun read(capturedProfiles: Set<ProfileId>): OverrideSnapshotReadResult
}

data class CategoryOverrideKey(val packageName: PackageName, val profile: ProfileId)
data class CategoryOverrideSnapshot(
    val schemaVersion: Int,
    val generation: Long,
    val assignments: Map<CategoryOverrideKey, CategoryId>,
    val identity: PolicyInputIdentity,
)

sealed interface OverrideSnapshotReadResult {
    data class Ready(val snapshot: CategoryOverrideSnapshot) : OverrideSnapshotReadResult
    data object Unreadable : OverrideSnapshotReadResult
    data object UnsupportedSchema : OverrideSnapshotReadResult
}

/**
 * App-private preferences representation. Its physical absence is the accepted
 * schema-v1, generation-0 empty source. Raw package/profile values never leave
 * this source; callers receive only typed values or a redacted failure.
 */
class SharedPreferencesCategoryOverrideSnapshotSource(
    private val preferences: SharedPreferences,
) : CategoryOverrideSnapshotSource {
    override fun read(capturedProfiles: Set<ProfileId>): OverrideSnapshotReadResult = try {
        if (!preferences.contains(SCHEMA_KEY)) return OverrideSnapshotReadResult.Ready(emptySnapshot())
        if (preferences.getInt(SCHEMA_KEY, -1) != SCHEMA_V1) return OverrideSnapshotReadResult.UnsupportedSchema
        val generation = preferences.getLong(GENERATION_KEY, -1L)
        if (generation < 0L) return OverrideSnapshotReadResult.Unreadable
        val entries = preferences.getString(ENTRIES_KEY, "") ?: return OverrideSnapshotReadResult.Unreadable
        val parsed = linkedMapOf<CategoryOverrideKey, CategoryId>()
        if (entries.isNotBlank()) {
            for (encoded in entries.lineSequence()) {
                val parts = encoded.split("|", limit = 3)
                if (parts.size != 3 || parts.any { it.isBlank() }) return OverrideSnapshotReadResult.Unreadable
                val key = CategoryOverrideKey(PackageName(parts[0]), ProfileId(parts[1]))
                val old = parsed.put(key, CategoryId(parts[2]))
                if (old != null) return OverrideSnapshotReadResult.Unreadable
            }
        }
        val visible = parsed.filterKeys { it.profile in capturedProfiles }
        val canonical = visible.entries.sortedWith(
            compareBy<Map.Entry<CategoryOverrideKey, CategoryId>> { it.key.profile.value }
                .thenBy { it.key.packageName.value },
        ).joinToString("\n") { "${it.key.packageName.value}|${it.key.profile.value}|${it.value.value}" }
        OverrideSnapshotReadResult.Ready(
            CategoryOverrideSnapshot(
                schemaVersion = SCHEMA_V1,
                generation = generation,
                assignments = visible,
                identity = PolicyInputIdentity(
                    PolicySourceKind.CATEGORY_OVERRIDE_SNAPSHOT,
                    "schema-$SCHEMA_V1-generation-$generation",
                    sha256Canonical(canonical),
                ),
            ),
        )
    } catch (_: RuntimeException) {
        OverrideSnapshotReadResult.Unreadable
    }

    private fun emptySnapshot() = CategoryOverrideSnapshot(
        schemaVersion = SCHEMA_V1,
        generation = 0L,
        assignments = emptyMap(),
        identity = PolicyInputIdentity(
            PolicySourceKind.CATEGORY_OVERRIDE_SNAPSHOT,
            "schema-$SCHEMA_V1-generation-0",
            sha256Canonical(""),
        ),
    )

    private companion object {
        const val SCHEMA_V1 = 1
        const val SCHEMA_KEY = "schema"
        const val GENERATION_KEY = "generation"
        const val ENTRIES_KEY = "entries"
    }
}
