package app.lawnchair.organizer.personalization

import app.lawnchair.organizer.personalization.exchange.ExchangeImportFailure
import app.lawnchair.organizer.personalization.exchange.ExchangeImportResult
import app.lawnchair.organizer.planning.Availability
import app.lawnchair.organizer.planning.CapturedItem
import app.lawnchair.organizer.planning.CapturedPlacement
import app.lawnchair.organizer.planning.ComponentKey
import app.lawnchair.organizer.planning.DeviceCapabilities
import app.lawnchair.organizer.planning.ExistingRole
import app.lawnchair.organizer.planning.ExistingTargetMembership
import app.lawnchair.organizer.planning.GridCell
import app.lawnchair.organizer.planning.GridSpan
import app.lawnchair.organizer.planning.ItemId
import app.lawnchair.organizer.planning.ItemKind
import app.lawnchair.organizer.planning.LayoutSnapshot
import app.lawnchair.organizer.planning.Orientation
import app.lawnchair.organizer.planning.Page
import app.lawnchair.organizer.planning.PageId
import app.lawnchair.organizer.planning.PageOrder
import app.lawnchair.organizer.planning.PageRef
import app.lawnchair.organizer.planning.ProfileId
import app.lawnchair.organizer.planning.RevisionId
import app.lawnchair.organizer.planning.TargetKey
import app.lawnchair.organizer.planning.TargetSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #204 AC-5/AC-13: every typed failure is fail-closed zero-write, and the
 * coverage partition invariant plus per-ref mobility semantics hold.
 */
class IntentValidatorTest {

    private val now = 1_000_000L
    private val ttl = ContextExportContract.SESSION_TTL_MS

    private fun device() = DeviceCapabilities(4, 6, 5, 3, 5, Orientation.PORTRAIT)

    private fun app(id: String, x: Int = 0, y: Int = 0, locked: Boolean = false) = CapturedItem(
        id = ItemId(id),
        profile = ProfileId("p0"),
        kind = ItemKind.APPLICATION,
        target = TargetKey.AppKey(ComponentKey("com.example.$id"), ProfileId("p0")),
        placement = CapturedPlacement.Workspace(PageRef(PageId("p0")), GridCell(x, y), GridSpan(1, 1)),
        locked = locked,
        availability = Availability.AVAILABLE,
    )

    private fun widget(id: String) = app(id).copy(kind = ItemKind.APPWIDGET)

    private fun docked(id: String) = app(id).copy(placement = CapturedPlacement.Dock(0))

    /**
     * Issue #337: the fixture advertises a catalog (one built-in plus one
     * user-defined entry) so category references resolve and the stale cases
     * can be exercised against a different catalog.
     */
    private fun catalog(
        userDefined: List<app.lawnchair.organizer.planning.UserDefinedCategory> = listOf(
            app.lawnchair.organizer.planning.UserDefinedCategory(
                app.lawnchair.organizer.planning.UserCategoryId(USER_CATEGORY_ID),
                "Commute",
            ),
        ),
    ): app.lawnchair.organizer.planning.ActiveCategoryCatalog = app.lawnchair.organizer.planning.ActiveCategoryCatalog(
        builtIn = app.lawnchair.organizer.planning.TaxonomyContract(
            app.lawnchair.organizer.planning.TaxonomyVersion("tv1"),
            listOf(app.lawnchair.organizer.planning.CategoryId("OTHER"), app.lawnchair.organizer.planning.CategoryId("GAMES")),
            app.lawnchair.organizer.planning.CategoryId("OTHER"),
        ),
        userDefined = userDefined,
    )

    private fun buildState(
        items: List<CapturedItem>,
        activeCatalog: app.lawnchair.organizer.planning.ActiveCategoryCatalog = catalog(),
        resolved: Map<ItemId, app.lawnchair.organizer.planning.CategoryIdentity?> = emptyMap(),
    ): Pair<BuiltExport, CanonicalStructuralInputs> {
        val snapshot = LayoutSnapshot(RevisionId("rev"), device(), listOf(Page(PageId("p0"), PageOrder(0))), items)
        val targets = TargetSet(items.map { ExistingTargetMembership(it.id, ExistingRole.Movable) }, emptyList())
        val structural = CanonicalStructuralInputs(snapshot, targets, resolved, activeCatalog)
        val inputs = ExportInputs(
            snapshot = snapshot,
            targets = targets,
            resolvedIdentities = resolved,
            catalog = activeCatalog,
            nowEpochMs = now,
        )
        return ContextExportBuilder.build(inputs, PrivacyTier.LOCAL_FULL, SequentialIdAllocator()) to structural
    }

    /** Issue #337: the advertised ref of the fixture's user-defined category. */
    private fun userCategoryRef(built: BuiltExport): String = built.session.categoryRefs.entries
        .single { (_, identity) -> identity is app.lawnchair.organizer.planning.CategoryIdentity.UserDefined }
        .key

    private fun renameUserCategory(): app.lawnchair.organizer.planning.ActiveCategoryCatalog = catalog(
        listOf(
            app.lawnchair.organizer.planning.UserDefinedCategory(
                app.lawnchair.organizer.planning.UserCategoryId(USER_CATEGORY_ID),
                "Transport",
            ),
        ),
    )

    private fun deleteUserCategory(): app.lawnchair.organizer.planning.ActiveCategoryCatalog = catalog(emptyList())

    private fun validate(
        built: BuiltExport,
        structural: CanonicalStructuralInputs,
        intent: PersonalizedIntentV1,
        currentDigest: String = SourceContextIdentity.digest(structural),
        at: Long = now + 1,
    ): IntentValidation = IntentValidator.validate(
        intent = intent,
        export = built.export,
        session = built.session,
        nowEpochMs = at,
        currentStructuralDigest = currentDigest,
    )

    private fun refsOf(built: BuiltExport): Map<String, String> = built.session.itemRefs.entries.associate { (ref, id) -> id.value to ref }

    @Test
    fun aFullyCoveredIntentIsAcceptedWithAnIdentity() {
        val (built, structural) = buildState(listOf(app("a"), app("b", x = 1)))
        val refs = refsOf(built).values
        val intent = PersonalizedIntentV1(
            exportId = built.export.exportId,
            itemIntents = refs.map { ItemIntent(ref = it, importance = Importance.NORMAL) },
        )
        val validation = validate(built, structural, intent)
        assertTrue(validation is IntentValidation.Validated)
        val validated = (validation as IntentValidation.Validated).validated
        assertEquals(ContextExportContract.INTENT_SCHEMA_VERSION, validated.identity.schemaVersion)
        assertEquals(64, validated.identity.digest.length)
    }

    @Test
    fun unknownRefIsRejectedFailClosed() {
        val (built, structural) = buildState(listOf(app("a")))
        val ref = built.export.items.first().ref
        val intent = PersonalizedIntentV1(
            exportId = built.export.exportId,
            itemIntents = listOf(ItemIntent(ref = ref, importance = Importance.HIGH)),
            unresolvedRefs = listOf("ghost"),
        )
        assertEquals(
            IntentValidationFailure.UnknownRef("ghost"),
            (validate(built, structural, intent) as IntentValidation.Failure).failure,
        )
    }

    @Test
    fun duplicateRefsAreRejectedFailClosed() {
        val (built, structural) = buildState(listOf(app("a"), app("b", x = 1)))
        val refs = refsOf(built).values.toList()
        val intent = PersonalizedIntentV1(
            exportId = built.export.exportId,
            itemIntents = listOf(ItemIntent(ref = refs[0]), ItemIntent(ref = refs[0])),
        )
        assertEquals(
            IntentValidationFailure.DuplicateRef,
            (validate(built, structural, intent) as IntentValidation.Failure).failure,
        )
    }

    @Test
    fun aPartialResponseIsAcceptedAsCanonicalUnresolvedOmission() {
        // Issue #330 (v3, spec 330 D-1): a ref missing from both lists is no
        // longer a coverage violation — it completes to canonical unresolved.
        val (built, structural) = buildState(listOf(app("a"), app("b", x = 1)))
        val refs = refsOf(built).values.toList()
        val missing = PersonalizedIntentV1(
            exportId = built.export.exportId,
            itemIntents = listOf(ItemIntent(ref = refs[0])),
        )
        val validated = (validate(built, structural, missing) as IntentValidation.Validated).validated
        assertEquals(
            RefDecision.UnresolvedByOmission,
            validated.completed.decisions.getValue(refs[1]),
        )
        // A full-coverage document stays valid (v3 is a superset of v2).
        val full = PersonalizedIntentV1(
            exportId = built.export.exportId,
            itemIntents = emptyList(),
            unresolvedRefs = refs,
        )
        assertTrue(validate(built, structural, full) is IntentValidation.Validated)
    }

    @Test
    fun coverageSplitViolationsAreStillRejected() {
        val (built, structural) = buildState(listOf(app("a"), app("b", x = 1)))
        val refs = refsOf(built).values.toList()
        // Overlap: the same ref in both collections.
        val overlapping = PersonalizedIntentV1(
            exportId = built.export.exportId,
            itemIntents = listOf(ItemIntent(ref = refs[0])),
            unresolvedRefs = listOf(refs[0]),
        )
        assertEquals(
            IntentValidationFailure.IncompleteCoverage,
            (validate(built, structural, overlapping) as IntentValidation.Failure).failure,
        )
        // Duplicate inside unresolvedRefs.
        val duplicatedUnresolved = PersonalizedIntentV1(
            exportId = built.export.exportId,
            itemIntents = emptyList(),
            unresolvedRefs = listOf(refs[0], refs[0]),
        )
        assertEquals(
            IntentValidationFailure.IncompleteCoverage,
            (validate(built, structural, duplicatedUnresolved) as IntentValidation.Failure).failure,
        )
    }

    @Test
    fun bareEntryWithAnUnknownRefIsStillUnknownRef() {
        // Issue #330 (spec 330 D-6): a bare entry validates like any authored
        // entry — normalization to unresolved happens after validation, so a
        // fabricated ref is never accepted through the bare form.
        val (built, structural) = buildState(listOf(app("a")))
        val intent = PersonalizedIntentV1(
            exportId = built.export.exportId,
            itemIntents = listOf(ItemIntent(ref = "ghost")),
        )
        assertEquals(
            IntentValidationFailure.UnknownRef("ghost"),
            (validate(built, structural, intent) as IntentValidation.Failure).failure,
        )
    }

    @Test
    fun aFullyOmittedIntentIsAccepted() {
        // Issue #330 (v3, spec 330 contract detail 8): an empty intent is
        // legal; the planner effect equals an all-unresolved explicit intent.
        val (built, structural) = buildState(listOf(app("a")))
        val intent = PersonalizedIntentV1(exportId = built.export.exportId, itemIntents = emptyList())
        val validated = (validate(built, structural, intent) as IntentValidation.Validated).validated
        assertEquals(
            RefDecision.UnresolvedByOmission,
            validated.completed.decisions.getValue(refsOf(built).values.first()),
        )
    }

    @Test
    fun unmatchedExportIdIsAnExportMismatch() {
        val (built, structural) = buildState(listOf(app("a")))
        val refs = refsOf(built).values
        val intent = PersonalizedIntentV1(
            exportId = "other-export",
            itemIntents = refs.map { ItemIntent(ref = it) },
        )
        assertEquals(
            IntentValidationFailure.ExportMismatch,
            (validate(built, structural, intent) as IntentValidation.Failure).failure,
        )
    }

    @Test
    fun expiredSessionsAreRejectedWithSessionExpired() {
        val (built, structural) = buildState(listOf(app("a")))
        val refs = refsOf(built).values
        val intent = PersonalizedIntentV1(
            exportId = built.export.exportId,
            itemIntents = refs.map { ItemIntent(ref = it) },
        )
        assertEquals(
            IntentValidationFailure.SessionExpired,
            (validate(built, structural, intent, at = now + ttl + 1) as IntentValidation.Failure).failure,
        )
    }

    @Test
    fun structuralChangeIsContextStale() {
        val (built, structural) = buildState(listOf(app("a")))
        val refs = refsOf(built).values
        val movedState = structural.copy(
            snapshot = structural.snapshot.copy(
                items = structural.snapshot.items.map { it.copy(placement = (it.placement as CapturedPlacement.Workspace).copy(cell = GridCell(3, 3))) },
            ),
        )
        val intent = PersonalizedIntentV1(
            exportId = built.export.exportId,
            itemIntents = refs.map { ItemIntent(ref = it) },
        )
        assertEquals(
            IntentValidationFailure.ContextStale,
            (validate(built, structural, intent, currentDigest = SourceContextIdentity.digest(movedState)) as IntentValidation.Failure).failure,
        )
    }

    @Test
    fun signalOnlyChangesNeverRejectTheImport() {
        // Simulate the #205 round trip: export, usage signal rebuild, import.
        val (built, structural) = buildState(listOf(app("a")))
        val refs = refsOf(built).values
        val intent = PersonalizedIntentV1(
            exportId = built.export.exportId,
            itemIntents = refs.map { ItemIntent(ref = it) },
        )
        // The current structural digest is unchanged; the #203 snapshot the AI
        // saw at export time is recorded as provenance only, never matched.
        assertTrue(validate(built, structural, intent) is IntentValidation.Validated)
    }

    @Test
    fun fixedRefContradictionsAreMobilityContradictions() {
        val (built, structural) = buildState(listOf(app("a", locked = true), app("b", x = 1)))
        val refs = refsOf(built)
        val intent = PersonalizedIntentV1(
            exportId = built.export.exportId,
            itemIntents = listOf(
                ItemIntent(ref = refs.getValue("a"), pageAffinity = 0),
                ItemIntent(ref = refs.getValue("b")),
            ),
        )
        assertEquals(
            IntentValidationFailure.MobilityContradiction(refs.getValue("a")),
            (validate(built, structural, intent) as IntentValidation.Failure).failure,
        )
    }

    @Test
    fun lockedItemMovementPreferenceIsNotForbiddenContent() {
        // 3rd review: locked ref + semantic field is MOBILITY_CONTRADICTION
        // (schema-permitted field), never FORBIDDEN_CONTENT.
        val (built, structural) = buildState(listOf(app("a", locked = true)))
        val lockedRef = refsOf(built).getValue("a")
        val intent = PersonalizedIntentV1(
            exportId = built.export.exportId,
            itemIntents = listOf(ItemIntent(ref = lockedRef, importance = Importance.HIGH)),
        )
        val failure = (validate(built, structural, intent) as IntentValidation.Failure).failure
        assertEquals(IntentValidationFailure.MobilityContradiction(lockedRef), failure)
    }

    @Test
    fun widgetGroupMembershipIsAMobilityContradiction() {
        val (built, structural) = buildState(listOf(widget("w1"), app("a", x = 1)))
        val refs = refsOf(built)
        val intent = PersonalizedIntentV1(
            exportId = built.export.exportId,
            itemIntents = listOf(
                ItemIntent(ref = refs.getValue("w1"), desiredGroupRefs = listOf(refs.getValue("a"))),
                ItemIntent(ref = refs.getValue("a")),
            ),
        )
        assertEquals(
            IntentValidationFailure.MobilityContradiction(refs.getValue("w1")),
            (validate(built, structural, intent) as IntentValidation.Failure).failure,
        )
    }

    @Test
    fun fixedRefPreserveOnlyIntentsAreAccepted() {
        val (built, structural) = buildState(listOf(docked("d"), app("a", x = 1)))
        val refs = refsOf(built)
        val intent = PersonalizedIntentV1(
            exportId = built.export.exportId,
            itemIntents = listOf(
                ItemIntent(ref = refs.getValue("d"), preserve = true),
                ItemIntent(ref = refs.getValue("a"), pageAffinity = 0),
            ),
        )
        assertTrue(validate(built, structural, intent) is IntentValidation.Validated)
    }

    @Test
    fun validatorIsTotalOverArbitraryInputs() {
        val (built, structural) = buildState(listOf(app("a")))
        val digest = SourceContextIdentity.digest(structural)
        val arbitrary = listOf(
            ByteArray(0),
            "not json".encodeToByteArray(),
            "{}".encodeToByteArray(),
            """{"schemaVersion":"personalized-intent-v3","exportId":"${built.export.exportId}","itemIntents":[{"ref":"ghost"}]}"""
                .encodeToByteArray(),
        )
        for (bytes in arbitrary) {
            val validation = when (val decoded = IntentCodec.decode(bytes)) {
                is IntentDecodeResult.Failure -> IntentValidation.Failure(decoded.failure)

                is IntentDecodeResult.Success -> IntentValidator.validate(
                    decoded.intent,
                    built.export,
                    built.session,
                    now + 1,
                    digest,
                )
            }
            assertTrue(
                "validator must resolve every input: ${bytes.decodeToString()}",
                validation is IntentValidation.Validated || validation is IntentValidation.Failure,
            )
        }
    }

    // ---- Issue #337 (spec 337 D-5, AC-7): category reference resolution ----

    /**
     * The single failure class per situation (spec 337 D-5 table). The digest
     * gate runs in the pipeline before validation, so an assignment-bearing
     * delete settles as `CONTEXT_STALE`; everything the validator sees is a
     * digest-fresh document, where an unresolvable ref is `UNKNOWN_CATEGORY_REF`.
     */
    @Test
    fun advertisedCategoryRefResolvesToItsIdentity() {
        val (built, structural) = buildState(listOf(app("a")))
        val ref = built.export.items.first().ref
        val validation = validate(
            built,
            structural,
            PersonalizedIntentV1(
                exportId = built.export.exportId,
                itemIntents = listOf(
                    ItemIntent(ref = ref, groupSemantic = GroupSemantic(categoryRef = userCategoryRef(built), proposalLabel = null)),
                ),
            ),
        )
        assertTrue(validation is IntentValidation.Validated)
        // The adapter resolves it to the identity through the session mapping.
        val projection = IntentPlannerAdapter.project((validation as IntentValidation.Validated).validated)
        val preference = projection.itemPreferences.single()
        assertEquals(
            app.lawnchair.organizer.planning.CategoryIdentity.UserDefined(
                app.lawnchair.organizer.planning.UserCategoryId(USER_CATEGORY_ID),
            ),
            preference.groupCategory,
        )
        assertEquals(null, preference.groupProposalLabel)
    }

    @Test
    fun unadvertisedCategoryRefIsRejectedFailClosed() {
        val (built, structural) = buildState(listOf(app("a")))
        for (ref in listOf("Commute", "u:$USER_CATEGORY_ID", "zzz")) {
            val validation = validate(
                built,
                structural,
                PersonalizedIntentV1(
                    exportId = built.export.exportId,
                    itemIntents = listOf(
                        ItemIntent(ref = built.export.items.first().ref, groupSemantic = GroupSemantic(categoryRef = ref, proposalLabel = null)),
                    ),
                ),
            )
            assertEquals(
                "ref: $ref",
                IntentValidationFailure.UnknownCategoryRef(ref),
                (validation as IntentValidation.Failure).failure,
            )
        }
    }

    /**
     * A rename keeps the identity and the ORIGINAL export ref: the same session
     * still resolves it, and the reconstructed view shows the current name
     * (spec 337 D-5: names are presentation, never authority).
     */
    @Test
    fun renamedCategoryKeepsTheOriginalExportRefResolvable() {
        val (built, structural) = buildState(listOf(app("a")))
        val originalRef = userCategoryRef(built)
        assertEquals("Commute", built.export.categories.single { it.ref == originalRef }.displayName?.value)

        // Same export/session, import-time catalog carries the renamed entry.
        val renamedView = structural.copy(catalog = renameUserCategory())
        assertEquals(
            "a rename does not disturb the structural digest",
            SourceContextIdentity.digest(structural),
            SourceContextIdentity.digest(renamedView),
        )
        val itemRef = built.export.items.first().ref
        val reply = "```json\n" + buildString {
            append("{\"schemaVersion\":\"${ContextExportContract.INTENT_SCHEMA_VERSION}\",")
            append("\"exportId\":\"${built.export.exportId}\",")
            append("\"itemIntents\":[{\"ref\":\"$itemRef\",\"groupSemantic\":{\"categoryRef\":\"$originalRef\"}}]}")
        } + "\n```"
        val result = app.lawnchair.organizer.personalization.exchange.ExchangeImportPipeline.import(
            reply,
            built.session,
            renamedView,
            now + 1,
        )
        assertTrue("the original ref still resolves after a rename", result is ExchangeImportResult.Validated)
        // The validation view (and therefore preview / folder titles) shows the
        // CURRENT name from the composition snapshot, not the export-time one.
        val view = (
            app.lawnchair.organizer.personalization.exchange.SessionExportReconstructor.rebuild(
                built.session,
                renamedView,
            ) as app.lawnchair.organizer.personalization.exchange.ReconstructionResult.Rebuilt
            ).export
        assertEquals("Transport", view.categories.single { it.ref == originalRef }.displayName?.value)
    }

    /**
     * Spec 337 D-5 table: a category delete WITH an assignment changes the
     * resolved identity, so the pipeline's digest gate settles it as
     * `CONTEXT_STALE` before the validator ever sees a category ref.
     */
    @Test
    fun assignedCategoryDeleteSettlesAsContextStale() {
        val identity = app.lawnchair.organizer.planning.CategoryIdentity.UserDefined(
            app.lawnchair.organizer.planning.UserCategoryId(USER_CATEGORY_ID),
        )
        val (built, structural) = buildState(
            listOf(app("a")),
            // The item is assigned to the user-defined category.
            resolved = mapOf(ItemId("a") to identity),
        )
        val staleRef = userCategoryRef(built)
        val itemRef = built.export.items.first().ref
        val deletedAssigned = structural.copy(
            resolvedIdentities = mapOf(ItemId("a") to app.lawnchair.organizer.planning.CategoryIdentity.BuiltIn(app.lawnchair.organizer.planning.CategoryId("OTHER"))),
            catalog = deleteUserCategory(),
        )
        val reply = "```json\n" + buildString {
            append("{\"schemaVersion\":\"${ContextExportContract.INTENT_SCHEMA_VERSION}\",")
            append("\"exportId\":\"${built.export.exportId}\",")
            append("\"itemIntents\":[{\"ref\":\"$itemRef\",\"groupSemantic\":{\"categoryRef\":\"$staleRef\"}}]}")
        } + "\n```"
        val result = app.lawnchair.organizer.personalization.exchange.ExchangeImportPipeline.import(
            reply,
            built.session,
            deletedAssigned,
            now + 1,
        )
        assertEquals(
            ExchangeImportFailure.Contract(IntentValidationFailure.ContextStale),
            (result as ExchangeImportResult.Failure).failure,
        )
    }

    @Test
    fun categoryDeletedWithoutAssignmentIsRejectedAsUnknownRef() {
        val (built, structural) = buildState(listOf(app("a")))
        val staleView = structural.copy(catalog = deleteUserCategory())
        assertEquals(
            "an unassigned delete does not disturb the digest",
            SourceContextIdentity.digest(structural),
            SourceContextIdentity.digest(staleView),
        )
        val staleRef = userCategoryRef(built)
        val itemRef = built.export.items.first().ref
        val reply = "```json\n" + buildString {
            append("{\"schemaVersion\":\"${ContextExportContract.INTENT_SCHEMA_VERSION}\",")
            append("\"exportId\":\"${built.export.exportId}\",")
            append("\"itemIntents\":[{\"ref\":\"$itemRef\",\"groupSemantic\":{\"categoryRef\":\"$staleRef\"}}]}")
        } + "\n```"
        val result = app.lawnchair.organizer.personalization.exchange.ExchangeImportPipeline.import(
            reply,
            built.session,
            staleView,
            now + 1,
        )
        assertEquals(
            ExchangeImportFailure.Contract(IntentValidationFailure.UnknownCategoryRef(staleRef)),
            (result as ExchangeImportResult.Failure).failure,
        )
    }

    /** The same reference resolves while the category still exists. */
    @Test
    fun categoryReferenceResolvesThroughTheImportPipeline() {
        val (built, structural) = buildState(listOf(app("a")))
        val itemRef = built.export.items.first().ref
        val advertised = userCategoryRef(built)
        val reply = "```json\n" + buildString {
            append("{\"schemaVersion\":\"${ContextExportContract.INTENT_SCHEMA_VERSION}\",")
            append("\"exportId\":\"${built.export.exportId}\",")
            append("\"itemIntents\":[{\"ref\":\"$itemRef\",\"groupSemantic\":{\"categoryRef\":\"$advertised\"}}]}")
        } + "\n```"
        val result = app.lawnchair.organizer.personalization.exchange.ExchangeImportPipeline.import(
            reply,
            built.session,
            structural,
            now + 1,
        )
        assertTrue(result is ExchangeImportResult.Validated)
    }

    @Test
    fun proposalLabelNeedsNoCategoryAndNeverResolvesToIdentity() {
        val (built, structural) = buildState(listOf(app("a")))
        val validation = validate(
            built,
            structural,
            PersonalizedIntentV1(
                exportId = built.export.exportId,
                itemIntents = listOf(
                    ItemIntent(
                        ref = built.export.items.first().ref,
                        groupSemantic = GroupSemantic(categoryRef = null, proposalLabel = "Commute"),
                    ),
                ),
            ),
        )
        assertTrue(validation is IntentValidation.Validated)
        val preference = IntentPlannerAdapter.project((validation as IntentValidation.Validated).validated).itemPreferences.single()
        assertEquals(null, preference.groupCategory)
        assertEquals("Commute", preference.groupProposalLabel)
    }

    // ---- Issue #337 (spec 337 D-4/D-5, AC-8): semantic unit corpus ----

    /**
     * Differing semantics inside one `desiredGroup` component are NOT a
     * failure: each declaration is well-defined on its own and the formation
     * key splits the component into separate groups (spec 337 D-4: the unit of
     * semantic authority is the resolved formation key, not the relation
     * graph). The validator accepts all three shapes.
     */
    @Test
    fun conflictingSemanticsInsideOneComponentAreAcceptedAndSplitByKey() {
        val (built, structural) = buildState(listOf(app("a"), app("b", x = 1)))
        val refs = built.session.itemRefs.entries.sortedBy { it.value.value }.map { it.key }
        val categoryRef = userCategoryRef(built)
        val cases = listOf(
            // same component, an existing-category reference vs a proposal
            listOf(
                ItemIntent(ref = refs[0], desiredGroupRefs = listOf(refs[1]), groupSemantic = GroupSemantic(categoryRef, null)),
                ItemIntent(ref = refs[1], groupSemantic = GroupSemantic(categoryRef = null, proposalLabel = "Morning")),
            ),
            // same component, two different proposal labels
            listOf(
                ItemIntent(ref = refs[0], desiredGroupRefs = listOf(refs[1]), groupSemantic = GroupSemantic(null, "Morning")),
                ItemIntent(ref = refs[1], groupSemantic = GroupSemantic(null, "Evening")),
            ),
            // same label, no relation at all
            listOf(
                ItemIntent(ref = refs[0], groupSemantic = GroupSemantic(null, "Morning")),
                ItemIntent(ref = refs[1], groupSemantic = GroupSemantic(null, "Morning")),
            ),
        )
        for (itemIntents in cases) {
            val validation = validate(
                built,
                structural,
                PersonalizedIntentV1(exportId = built.export.exportId, itemIntents = itemIntents),
            )
            assertTrue("component semantics are not a reject: $itemIntents", validation is IntentValidation.Validated)
        }
    }

    private companion object {
        /** Canonical lowercase UUID v4 fixture; a session/digest input only. */
        const val USER_CATEGORY_ID = "3f2b8c4e-1234-4abc-9de0-1234567890ab"
    }
}
