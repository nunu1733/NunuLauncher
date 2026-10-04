package app.lawnchair.organizer.personalization

import app.lawnchair.organizer.planning.ItemId

/**
 * Issue #204 (spec 204 "Planner接続" / Q1): the pure adapter from a validated
 * intent to the planner-internal semantic projection. The projection is a
 * pure function of the accepted intent and the export session's ref↔`ItemId`
 * map; it carries ordering/preference bias ONLY.
 *
 * The planner consumes [PersonalizedIntentProjection] as additive hints:
 * preservation decisions (`determinePreservation`), constraints, run modes,
 * and `TargetSet` semantics are never weakened by intent content. Widget
 * affinity is consumed only in runs with a widget-capable strategy, decided by
 * the planner itself.
 */
object IntentPlannerAdapter {

    fun project(validated: ValidatedPersonalizedIntent): PersonalizedIntentProjection {
        val refToItem = validated.session.itemRefs
        val roleByRef = validated.export.items.associate { it.ref to it.role }
        val itemPreferences = validated.intent.itemIntents.map { item ->
            ItemPreference(
                item = refToItem.getValue(item.ref),
                role = roleByRef.getValue(item.ref),
                importance = item.importance,
                desiredGroup = item.desiredGroupRefs?.map(refToItem::getValue),
                groupSemantic = item.groupSemantic,
                pageAffinity = item.pageAffinity,
                regionAffinity = item.regionAffinity,
                preserve = item.preserve,
            )
        }
        return PersonalizedIntentProjection(
            identity = validated.identity,
            itemPreferences = itemPreferences,
            globalMinimizeMovement = validated.intent.globalPreference?.minimizeMovement ?: false,
        )
    }
}

/**
 * The planner-facing semantic projection of one accepted intent. Immutable and
 * deterministic in the accepted intent identity (NFR-003 contract: the same
 * accepted intent + the same canonical planning inputs produce the same plan).
 */
data class PersonalizedIntentProjection(
    val identity: IntentIdentity,
    val itemPreferences: List<ItemPreference>,
    val globalMinimizeMovement: Boolean,
) {
    init {
        require(itemPreferences.map { it.item }.toSet().size == itemPreferences.size)
    }
}

data class ItemPreference(
    val item: ItemId,
    val role: ExportItemRole,
    val importance: Importance?,
    val desiredGroup: List<ItemId>?,
    val groupSemantic: GroupSemantic?,
    val pageAffinity: Int?,
    val regionAffinity: ExportRegionKind?,
    val preserve: Boolean?,
)
