package app.lawnchair.organizer.planning

/**
 * Issue #451 (spec 451 N-1): pure duplicate-launch-target detection over the
 * captured item set. A duplicate set is the set of captured items sharing the
 * same launch target — [TargetKey.AppKey] (component + profile) or
 * [TargetKey.ShortcutKey] (package + shortcut id + profile), by data-class
 * value equality. Sets of size 1 are not duplicates.
 *
 * The [duplicateSurplusIds] of a set is every item beyond its representative,
 * where the representative is the first [ItemId] in canonical order (UTF-8
 * byte order, `Identity.kt`). Detection covers every captured
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
internal fun duplicateSurplusIds(items: List<CapturedItem>): Set<ItemId> = items
    .asSequence()
    .filter { it.kind == ItemKind.APPLICATION || it.kind == ItemKind.DEEP_SHORTCUT }
    .filter { it.target is TargetKey.AppKey || it.target is TargetKey.ShortcutKey }
    .groupBy { it.target }
    .values
    .asSequence()
    .filter { it.size >= 2 }
    .flatMap { group -> group.map { it.id }.sorted().drop(1) }
    .toSet()
