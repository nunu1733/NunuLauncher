package app.lawnchair.organizer.planning

import java.security.MessageDigest

/**
 * Issue #228 (spec AC-15): deterministic planning-ID derivation for selected
 * missing-app candidates.
 *
 * - The ID is derived from the stable identity (`ComponentKey` +
 *   `ProfileId`) only — never from display labels, locale, or enumeration
 *   order. The same identity always yields the same ID.
 * - The `candidate-` prefix is a dedicated namespace that cannot collide with
 *   captured item IDs (numeric favorites-row strings) or planned-folder IDs
 *   (`planned-folder-*`).
 * - The hash is SHA-256 as 64 lowercase hex characters, never truncated, so
 *   two candidates share an ID only on a full hash collision.
 */
object CandidatePlanningIds {

    const val ID_PREFIX = "candidate-"

    fun planningId(target: CandidateTarget.AppKey): ItemId = ItemId(
        ID_PREFIX + sha256Hex("${target.component.value}:${target.profile.value}"),
    )

    fun isCandidateId(id: ItemId): Boolean = id.value.startsWith(ID_PREFIX)

    private fun sha256Hex(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }
}
