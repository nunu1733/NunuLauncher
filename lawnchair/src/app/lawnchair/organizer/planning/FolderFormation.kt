package app.lawnchair.organizer.planning

/**
 * Canonical folder formation (spec 12 P-04/P-05): same-profile same-category
 * grouping with capacity partition, shared by the full-run strategy executor
 * and the incremental run.
 */
internal data class FolderCandidate(
    val item: ItemId,
    val profile: ProfileId,
    val category: CategoryId,
)

internal data class FolderGroup(
    val ordinal: NewFolderOrdinal,
    val profile: ProfileId,
    val category: CategoryId,
    val members: List<ItemId>,
)

internal fun formFolderGroups(
    candidates: List<FolderCandidate>,
    fallbackCategory: CategoryId,
    capacity: Long,
    minGroupSize: Int,
): List<FolderGroup> {
    if (capacity < minGroupSize.toLong()) return emptyList()

    val groups = candidates
        .groupBy { it.profile to it.category }
        .toSortedMap(compareBy({ it.first }, { it.second }))
    val result = mutableListOf<FolderGroup>()
    var ordinal = 0
    for ((key, groupCandidates) in groups) {
        val (profile, category) = key
        val members = groupCandidates.map { it.item }.sorted()
        if (category == fallbackCategory || members.size < minGroupSize) continue

        val effectiveCapacity = minOf(capacity, members.size.toLong()).toInt()
        for (folderMembers in partitionMembers(members, effectiveCapacity, minGroupSize)) {
            result += FolderGroup(NewFolderOrdinal(ordinal++), profile, category, folderMembers)
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
