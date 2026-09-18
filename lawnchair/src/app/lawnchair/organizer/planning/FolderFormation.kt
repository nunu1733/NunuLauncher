package app.lawnchair.organizer.planning

/**
 * Issue #337 (spec 337 D-6): the closed formation key of one folder candidate —
 * either a resolved category identity (the pre-337 key) or a run-scoped
 * proposal key carrying the accepted intent's proposal label. A proposal key is
 * never a category identity, never joins the category ordering, and is never
 * persisted; it exists only for the run that authored it.
 *
 * Canonical total order: every existing-category key sorts before any proposal
 * key (so adding proposals never reorders existing groups), existing keys keep
 * the canonical [CategoryIdentity] order, and proposal keys order by UTF-8
 * byte order of their label.
 */
internal sealed interface FormationKey : Comparable<FormationKey> {
    data class Existing(val identity: CategoryIdentity) : FormationKey

    data class Proposed(val label: String) : FormationKey

    override fun compareTo(other: FormationKey): Int = when (this) {
        is Existing -> when (other) {
            is Existing -> identity.compareTo(other.identity)
            is Proposed -> -1
        }

        is Proposed -> when (other) {
            is Existing -> 1
            is Proposed -> compareUtf8Bytes(label, other.label)
        }
    }
}

/**
 * Canonical folder formation (spec 12 P-04/P-05): same-profile same-key
 * grouping with capacity partition, shared by the full-run strategy executor
 * and the incremental run. Issue #336: the grouping key is the closed
 * `(profile, CategoryIdentity)` pair, so user-defined groups participate
 * identically under the canonical identity order. Issue #337: the key is the
 * [FormationKey], so a run-scoped proposal forms (and names) its own group
 * through exactly the same mechanics.
 */
internal data class FolderCandidate(
    val item: ItemId,
    val profile: ProfileId,
    val key: FormationKey,
)

internal data class FolderGroup(
    val ordinal: NewFolderOrdinal,
    val profile: ProfileId,
    val key: FormationKey,
    val members: List<ItemId>,
)

internal fun formFolderGroups(
    candidates: List<FolderCandidate>,
    fallbackCategory: CategoryIdentity,
    capacity: Long,
    minGroupSize: Int,
): List<FolderGroup> {
    if (capacity < minGroupSize.toLong()) return emptyList()

    val fallbackKey = FormationKey.Existing(fallbackCategory)
    val groups = candidates
        .groupBy { it.profile to it.key }
        .toSortedMap(compareBy({ it.first }, { it.second }))
    val result = mutableListOf<FolderGroup>()
    var ordinal = 0
    for ((key, groupCandidates) in groups) {
        val (profile, formationKey) = key
        val members = groupCandidates.map { it.item }.sorted()
        if (formationKey == fallbackKey || members.size < minGroupSize) continue

        val effectiveCapacity = minOf(capacity, members.size.toLong()).toInt()
        for (folderMembers in partitionMembers(members, effectiveCapacity, minGroupSize)) {
            result += FolderGroup(NewFolderOrdinal(ordinal++), profile, formationKey, folderMembers)
        }
    }
    return result
}

private fun partitionMembers(
    members: List<ItemId>,
    effectiveCapacity: Int,
    minGroupSize: Int,
): List<List<ItemId>> {
    if (members.size <= effectiveCapacity) {
        return listOf(members)
    }

    val folders = mutableListOf<List<ItemId>>()
    var index = 0
    while (index + effectiveCapacity <= members.size) {
        folders += members.subList(index, index + effectiveCapacity).toList()
        index += effectiveCapacity
    }

    val remainder = members.size - index
    if (remainder == 0) {
        return folders
    }

    val remainderItems = members.subList(index, members.size).toList()

    if (remainder >= minGroupSize) {
        folders += remainderItems
        return folders
    }

    val needed = minGroupSize - remainder
    if (folders.isNotEmpty()) {
        val preceding = folders.last()
        val precedingNewSize = preceding.size - needed
        if (precedingNewSize >= minGroupSize) {
            folders[folders.lastIndex] = preceding.subList(0, precedingNewSize)
            folders += preceding.subList(precedingNewSize, preceding.size) + remainderItems
            return folders
        }
    }

    return folders
}
