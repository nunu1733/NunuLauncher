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
        // Issue #337 (spec 337 D-5/D-6): resolve an advertised category ref to
        // its identity through the session mapping here, at the single adapter
        // seam, so the planner never receives a raw string category. Validation
        // guarantees every authored ref is advertised (the reconstructed view
        // then carries it), so a missing mapping is a contract violation.
        val identityByRef = validated.session.categoryRefs
        // Issue #330 (spec 330 D-4): project the completed representation's
        // authored decisions only. Unresolved states (explicit, bare, or by
        // omission) generate no preference, so an omitted ref has exactly the
        // planner effect of an explicitly unresolved one.
        val itemPreferences = validated.completed.decisions.mapNotNull { (ref, decision) ->
            val authored = (decision as? RefDecision.Authored)?.intent ?: return@mapNotNull null
            val semantic = authored.groupSemantic
            ItemPreference(
                item = refToItem.getValue(ref),
                role = roleByRef.getValue(ref),
                importance = authored.importance,
                desiredGroup = authored.desiredGroupRefs?.map(refToItem::getValue),
                groupCategory = semantic?.categoryRef?.let { categoryRef ->
                    identityByRef[categoryRef] ?: error("validated category ref has no session identity")
                },
                groupProposalLabel = semantic?.proposalLabel,
                pageAffinity = authored.pageAffinity,
                regionAffinity = authored.regionAffinity,
                preserve = authored.preserve,
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
    /**
     * Issue #337: the resolved identity of an existing-category reference
     * (never a raw string), or null when the item authored no reference.
     */
    val groupCategory: app.lawnchair.organizer.planning.CategoryIdentity?,
    /**
     * Issue #337: the run-scoped proposal label, or null. A run-scoped
     * formation key; never a category identity.
     */
    val groupProposalLabel: String?,
    val pageAffinity: Int?,
    val regionAffinity: ExportRegionKind?,
    val preserve: Boolean?,
)
