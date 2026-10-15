package app.lawnchair.organizer.planning

/**
 * Issue #507: the single grouping authority shared by the planner surplus
 * rule and the edit-surface duplicate confirmation. A duplicate group is the
 * set of items sharing the same launch target — [TargetKey.AppKey]
 * (component + profile) or [TargetKey.ShortcutKey] (package + shortcut id +
 * profile), by data-class value equality; sets of size 1 are not duplicates.
 * Participation (the two kinds [ItemKind.APPLICATION] / [ItemKind.DEEP_SHORTCUT]
 * and the two target key shapes above) and grouping (same target value,
 * size >= 2) live here only; callers project their own item vocabulary onto
 * [ItemKind] / [TargetKey] and return null for items that never participate.
 * Internal on purpose: the edit surface lives in the same Gradle module.
 */
internal fun <T> duplicateGroups(
    items: List<T>,
    kindOf: (T) -> ItemKind?,
    targetOf: (T) -> TargetKey?,
): Map<TargetKey, List<T>> = items
    .asSequence()
    .mapNotNull { item ->
        val kind = kindOf(item) ?: return@mapNotNull null
        val target = targetOf(item) ?: return@mapNotNull null
        if ((kind == ItemKind.APPLICATION || kind == ItemKind.DEEP_SHORTCUT) &&
            (target is TargetKey.AppKey || target is TargetKey.ShortcutKey)
        ) {
            target to item
        } else {
            null
        }
    }
    .groupBy({ it.first }, { it.second })
    .filterValues { it.size >= 2 }

/**
 * Issue #451 (spec 451 N-1): pure duplicate-launch-target surplus over the
 * captured item set, delegated to [duplicateGroups] (the shared grouping
 * authority, #507). The surplus of a set is every item beyond its
 * representative, where the representative is the first [ItemId] in canonical
 * order (UTF-8 byte order, `Identity.kt`). Detection covers every captured
 * APPLICATION/DEEP_SHORTCUT item regardless of preservation state or existing
 * folder membership (the intentional #451 contract extension), so duplicates
 * can never flow into a newly formed folder no matter where they currently sit.
 * Other kinds (legacy shortcut, widget, folder, app pair) never participate:
 * their target keys are out of the #451 definition's scope.
 *
 * The result depends on the input only — no strategy, classification, locale,
 * thread, or enumeration order — so materialize-and-replan runs (spec 12 P-10)
 * pick the same representative and keep the same surplus items preserved with
 * the same reason (`ItemId`s are stable across moves and folder joins).
 */
internal fun duplicateSurplusIds(items: List<CapturedItem>): Set<ItemId> = duplicateGroups(
    items,
    kindOf = { it.kind },
    targetOf = { it.target },
).values
    .asSequence()
    .flatMap { group -> group.map { it.id }.sorted().drop(1) }
    .toSet()
