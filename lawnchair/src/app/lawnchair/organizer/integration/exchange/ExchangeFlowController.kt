package app.lawnchair.organizer.integration.exchange

import app.lawnchair.organizer.personalization.ContextExportBuilder
import app.lawnchair.organizer.personalization.ContextExportCodec
import app.lawnchair.organizer.personalization.ExportSession
import app.lawnchair.organizer.personalization.ExportSessionStore
import app.lawnchair.organizer.personalization.IntentCodec
import app.lawnchair.organizer.personalization.IntentValidationFailure
import app.lawnchair.organizer.personalization.PendingImportedIntentStore
import app.lawnchair.organizer.personalization.PrivacyTier
import app.lawnchair.organizer.personalization.RandomIdAllocator
import app.lawnchair.organizer.personalization.ValidatedPersonalizedIntent
import app.lawnchair.organizer.personalization.exchange.ExchangeGenerationGate
import app.lawnchair.organizer.personalization.exchange.ExchangeGenerationGateOutcome
import app.lawnchair.organizer.personalization.exchange.ExchangeImportFailure
import app.lawnchair.organizer.personalization.exchange.ExchangeImportPipeline
import app.lawnchair.organizer.personalization.exchange.ExchangeImportResult
import app.lawnchair.organizer.personalization.exchange.ExchangeMutationGate
import app.lawnchair.organizer.personalization.exchange.IntentFramingResult
import app.lawnchair.organizer.personalization.exchange.IntentImportParser
import app.lawnchair.organizer.personalization.exchange.RecognizedImportInfo
import app.lawnchair.organizer.personalization.exchange.recognizedInfo
import app.lawnchair.organizer.personalization.exchange.withGateOrNull
import app.lawnchair.organizer.planning.CandidateTarget

/**
 * Issue #205: the exchange flow orchestrator (spec 205 data flow). Owns the
 * generation ordering contract —
 * gate → privacy mode → build → durable session save → package composition →
 * disclosure — plus disclosure cancel (explicit invalidation of the unsent
 * session) and the import orchestration (session lookup by the echoed
 * `exportId`, then the pure #205 pipeline).
 *
 * The controller writes nothing to the layout DB; every failure is typed and
 * zero-write. The generated package is an immutable value handed to the
 * disclosure UI; transport adapters receive the identical value (AC-12).
 */
class ExchangeFlowController(
    private val composeExportInputs: (Long) -> ExchangeInputResult,
    private val currentStructuralInputs: () -> ExchangeStructuralResult,
    private val store: ExportSessionStore,
    private val allocator: RandomIdAllocator,
    private val clock: () -> Long,
    private val encodeExport: (app.lawnchair.organizer.personalization.PersonalizationContextExportV1) -> app.lawnchair.organizer.personalization.ContextExportResult = app.lawnchair.organizer.personalization.ContextExportCodec::encode,
    /**
     * Issue #331: the run-in (scope-composed) entry onto the same canonical
     * composition seam — the confirmed selection with candidate display
     * labels. The idle entry covers the empty-scope case. Test fixtures that
     * construct the controller with lambdas and never exercise the run-in
     * entry may rely on the default (typed NotReady; fail-closed).
     */
    private val composeScopedExportInputs: (Long, List<CandidateTarget.AppKey>, Map<CandidateTarget.AppKey, String>) -> ExchangeInputResult =
        { _, _, _ ->
            ExchangeInputResult.NotReady(app.lawnchair.organizer.integration.InputReadinessReason.ReconciliationPending)
        },

    /**
     * Issue #374 (spec 374 DI-AC-03): the durable pending imported intent
     * store, wired for the replacement invalidation — right after the NEW
     * session's durable save succeeds, the previous imported proposal's
     * record is deleted (the fixed write order 「新session保存 → 旧pending無効化」).
     * The read-time reconcile stays the master correctness defense, so a
     * process death between the two writes is caught by the exportId check.
     * Null (the default) skips the delete — fixtures that never exercise the
     * replacement contract keep the pre-#374 behavior.
     */
    private val pendingImportStore: PendingImportedIntentStore? = null,
    /**
     * Issue #375 (spec "exchange mutation gate"): the process-wide
     * serialization point. When injected, every durable-record / active-
     * session mutation this controller performs (the replacement commit's
     * new-session save + old-record invalidation, and the pre-send
     * invalidation) runs inside one gate hold, so it can never interleave
     * with the rebind admission anchor's fresh verification. Null (the
     * default) keeps legacy fixtures running un-gated.
     */
    private val exchangeMutationGate: ExchangeMutationGate? = null,
) {

    constructor(
        adapter: ExchangeInputAdapter,
        store: ExportSessionStore,
        allocator: RandomIdAllocator,
        clock: () -> Long,
        pendingImportStore: PendingImportedIntentStore? = null,
        exchangeMutationGate: ExchangeMutationGate? = null,
    ) : this(
        composeExportInputs = adapter::composeForExport,
        currentStructuralInputs = adapter::currentStructural,
        store = store,
        allocator = allocator,
        clock = clock,
        composeScopedExportInputs = adapter::composeForExport,
        pendingImportStore = pendingImportStore,
        exchangeMutationGate = exchangeMutationGate,
    )

    /** The active (unexpired) session, if any — drives the replacement gate. */
    fun activeSession(): ExportSession? = store.active(clock())

    /**
     * Issue #372: the same clock the generation and store reads use, exposed
     * for the T-15 pre-display's expiry-scheduled re-read so the display and
     * the gate never read two different time sources.
     */
    fun nowEpochMs(): Long = clock()

    /** Gate decision for starting a new generation flow (spec 205 AC-13). */
    fun generationGate(userConfirmation: Boolean?): ExchangeGenerationGateOutcome = ExchangeGenerationGate.evaluate(activeSession() != null, userConfirmation)

    /**
     * Generates one exchange package in the chosen tier. Callers must have
     * passed the replacement gate first when an active session existed.
     */
    fun generate(tier: PrivacyTier): ExchangeGenerationResult = generate(tier, composeExportInputs(clock()))

    /**
     * Issue #331: run-in (scope-composed) generation — the export scope is the
     * run's fixed selection composed by the same canonical seam the planner
     * consumes. Same ordering contract as [generate]: gate → build → save →
     * compose → disclose.
     *
     * Issue #417: LEGACY-COMPAT generation path only — the run-in scoped
     * creation entry moved to the atomic commit seam ([prepareScopedGeneration]
     * inside the run-owned transaction, spec 417); this method keeps the
     * pre-#417 behavior for existing fixtures and readers. Its sessions carry
     * no explicit origin, so the durable provenance decodes through the
     * legacy rule (absent origin + non-empty scope = RUN_IN).
     */
    fun generateForSelection(
        tier: PrivacyTier,
        selection: List<CandidateTarget.AppKey>,
        candidateLabels: Map<CandidateTarget.AppKey, String>,
    ): ExchangeGenerationResult = generate(tier, composeScopedExportInputs(clock(), selection, candidateLabels))

    /**
     * Issue #417 (spec 417 "生成seamの責務分割"): the LOCK-FREE prepare half
     * of the scoped generation — canonical composition (capture) and the pure
     * export build, with the session tagged ONCE with its durable entry
     * origin ([ExportEntryOrigin.RUN_IN]; a scoped request is only authored
     * from a confirmed scope). No store access, no gate, no run state: the
     * caller claims the generation epoch first and runs the returned
     * preparation through [commitPreparedSession] inside the run-owned
     * transaction's gate hold.
     */
    fun prepareScopedGeneration(
        tier: PrivacyTier,
        selection: List<CandidateTarget.AppKey>,
        candidateLabels: Map<CandidateTarget.AppKey, String>,
    ): ExchangeGenerationPreparation {
        val composed = composeScopedExportInputs(clock(), selection, candidateLabels)
        val inputs = when (composed) {
            is ExchangeInputResult.NotReady -> return ExchangeGenerationPreparation.InputNotReady(composed.reason)
            is ExchangeInputResult.ExportReady -> composed.inputs
        }
        val built = ContextExportBuilder.build(inputs, tier, allocator)
        return ExchangeGenerationPreparation.Prepared(
            export = built.export,
            session = built.session.copy(entryOrigin = app.lawnchair.organizer.personalization.ExportEntryOrigin.RUN_IN),
        )
    }

    /**
     * Issue #417 (spec 417 "生成seamの責務分割"): the DURABLE mutation half of
     * the scoped generation — the new session save (with its immutable origin)
     * followed by the #374 previous-pending invalidation. MUST run inside the
     * run-owned transaction's gate hold ([commitGeneratedSession]); the
     * typed outcome is what the run's gate-held bind callback consumes (a
     * write failure never binds). The post-commit encode stays the caller's
     * next step (spec 205 generation order unchanged).
     */
    fun commitPreparedSession(prepared: ExchangeGenerationPreparation.Prepared): SessionPersistOutcome {
        if (!store.save(prepared.session)) {
            // Fail-closed: without a durable session the package can never be
            // imported after a process death, so nothing may be disclosed.
            return SessionPersistOutcome.WriteFailed
        }
        // Issue #374 (spec 374 DI-AC-03): the replacement write order is
        // fixed — the NEW session is durable FIRST, then the previous
        // imported proposal's record is invalidated (best-effort delete; the
        // read-time reconcile is the master). E2's session and binding are
        // never rolled back on this path.
        pendingImportStore?.delete()
        return SessionPersistOutcome.Committed
    }

    /**
     * Issue #417: the post-commit encode + package composition of a committed
     * preparation (spec 205 order: save → encode → return). On failure the
     * caller retires the session through the run-owned cleanup seam
     * ([cleanupBoundExport] with [invalidateSessionIf]) instead of a bare
     * invalidate, so the binding clear stays gate-held and binding-conditional.
     */
    fun encodePrepared(prepared: ExchangeGenerationPreparation.Prepared): ExchangePreparedEncodeResult = when (val encoded = encodeExport(prepared.export)) {
        is app.lawnchair.organizer.personalization.ContextExportResult.Failure ->
            ExchangePreparedEncodeResult.Failure(encoded.problem)

        is app.lawnchair.organizer.personalization.ContextExportResult.Success ->
            ExchangePreparedEncodeResult.Encoded(
                packageText = app.lawnchair.organizer.personalization.exchange.ExchangePackageComposer.compose(
                    encoded.bytes.decodeToString(),
                ),
            )
    }

    /**
     * Issue #417: the failure-aware conditional session invalidation
     * (`invalidateIf`) the run-owned capability callbacks run INSIDE the gate
     * hold of [cleanupBoundExport] / [discardScopeBoundRequest]. The gate is
     * already held by the capability when this is invoked (the monitor is
     * re-entrant, and the run's default capability runs the mutation
     * un-gated), so this body itself takes no gate.
     */
    fun invalidateSessionIf(expectedExportId: String): app.lawnchair.organizer.personalization.ExportInvalidationResult = store.invalidateIf(expectedExportId)

    private fun generate(tier: PrivacyTier, composedInputs: ExchangeInputResult): ExchangeGenerationResult {
        val inputs = when (composedInputs) {
            is ExchangeInputResult.NotReady -> return ExchangeGenerationResult.InputNotReady(composedInputs.reason)
            is ExchangeInputResult.ExportReady -> composedInputs.inputs
        }
        val built = ContextExportBuilder.build(inputs, tier, allocator)
        // Issue #375: the replacement commit (new session save + old record
        // invalidation) is a session-and-record mutation — inside one exchange
        // gate hold when the process-wide gate is injected, so it can never
        // interleave with the rebind admission anchor's fresh verification.
        val replacementCommitted = exchangeMutationGate.withGateOrNull {
            if (!store.save(built.session)) {
                // Fail-closed: without a durable session the package can never
                // be imported after a process death, so nothing is disclosed.
                return@withGateOrNull false
            }
            // Issue #374 (spec 374 DI-AC-03): the replacement write order is
            // fixed — the NEW session is durable FIRST, then the previous
            // imported proposal's record is invalidated (a plain best-effort
            // delete: the read-time reconcile is the master, so no tombstone
            // is needed here). Placed before the encode/compose so a
            // replacement can never disclose a package while the old
            // proposal's record still reads as active.
            pendingImportStore?.delete()
            true
        }
        if (!replacementCommitted) {
            return ExchangeGenerationResult.SessionStoreFailure
        }
        val exportJson = when (val encoded = encodeExport(built.export)) {
            is app.lawnchair.organizer.personalization.ContextExportResult.Failure -> {
                // Review P2: a generation attempt that never produced a package
                // must not leave a ghost active session — the durable active
                // session and the disclosed package stay 1:1 (spec 205).
                store.invalidate(built.session.exportId)
                return ExchangeGenerationResult.EncodeFailure(encoded.problem)
            }

            is app.lawnchair.organizer.personalization.ContextExportResult.Success ->
                encoded.bytes.decodeToString()
        }
        return ExchangeGenerationResult.Generated(
            packageText = app.lawnchair.organizer.personalization.exchange.ExchangePackageComposer.compose(exportJson),
            session = built.session,
        )
    }

    /**
     * Disclosure cancel (spec 205): explicitly invalidates the session of a
     * generated-but-unsent package. The package never left the device through
     * this flow, so nothing else is affected.
     */
    fun cancelDisclosure(session: ExportSession) {
        // Issue #375: a session invalidation is a gate-held mutation (short;
        // no suspension inside).
        exchangeMutationGate.withGateOrNull {
            store.invalidate(session.exportId)
        }
    }

    /**
     * Issue #417: the exportId-addressed legacy invalidation used by the
     * holder when the run-owned cleanup seam reports a stale binding
     * (`Superseded`) — the exact-exportId-conditional delete inside the gate,
     * so it never touches a replacement session.
     */
    fun invalidateSession(expectedExportId: String) {
        exchangeMutationGate.withGateOrNull {
            store.invalidate(expectedExportId)
        }
    }

    /** Issue #375: the current structural inputs for the rebind's pre-admission re-verification. */
    fun currentStructural(): app.lawnchair.organizer.integration.exchange.ExchangeStructuralResult = currentStructuralInputs()

    /**
     * Imports an agent reply (spec 205 data flow ordering): the untrusted reply
     * is bounded, framed, and decoded FIRST, then the session is resolved by
     * the echoed `exportId` (invalidated/unknown → `EXPORT_MISMATCH`, expired
     * matching record → `SESSION_EXPIRED`) — only after the reply is bound to
     * a live session does the canonical structural composition run, so an
     * unbound/expired reply never pays for a capture (review P2).
     */
    fun importReply(replyText: String): ExchangeImportOutcome {
        val prepared = when (val result = ExchangeImportPipeline.prepare(replyText)) {
            is ExchangeImportResult.Failure -> return ExchangeImportOutcome.Pipeline(result)
            is ExchangeImportPipeline.Prepared -> result
            is ExchangeImportResult.Validated -> error("unreachable")
        }
        val session = store.load(prepared.intent.exportId)
            ?: return ExchangeImportOutcome.Pipeline(
                ExchangeImportResult.Failure(
                    ExchangeImportFailure.Contract(IntentValidationFailure.ExportMismatch),
                    prepared.recognizedInfo(),
                ),
            )
        if (session.isExpired(clock())) {
            return ExchangeImportOutcome.Pipeline(
                ExchangeImportResult.Failure(
                    ExchangeImportFailure.Contract(IntentValidationFailure.SessionExpired),
                    prepared.recognizedInfo(),
                ),
            )
        }
        val structural = when (val result = currentStructuralInputs()) {
            is app.lawnchair.organizer.integration.exchange.ExchangeStructuralResult.NotReady ->
                // Issue #332 (spec D-6): the reply is already decoded, so the
                // recognition facts survive the environmental failure.
                return ExchangeImportOutcome.InputNotReady(result.reason, prepared.recognizedInfo())

            is app.lawnchair.organizer.integration.exchange.ExchangeStructuralResult.Ready -> result.structural
        }
        return ExchangeImportOutcome.Pipeline(
            ExchangeImportPipeline.validate(prepared, session, structural, clock()),
        )
    }
}

/** Generation outcome surfaced to the disclosure UI. */
sealed interface ExchangeGenerationResult {
    data class Generated(val packageText: String, val session: ExportSession) : ExchangeGenerationResult

    data class InputNotReady(val reason: app.lawnchair.organizer.integration.InputReadinessReason) : ExchangeGenerationResult

    data object SessionStoreFailure : ExchangeGenerationResult

    data class EncodeFailure(val problem: app.lawnchair.organizer.personalization.ExportEncodeProblem) : ExchangeGenerationResult
}

/**
 * Issue #417 (spec 417 "生成seamの責務分割"): the prepared (lock-free) half of
 * a scoped generation — everything the run-owned durable commit needs. The
 * session already carries its immutable durable entry origin.
 */
sealed interface ExchangeGenerationPreparation {
    data class Prepared(
        val export: app.lawnchair.organizer.personalization.PersonalizationContextExportV1,
        val session: ExportSession,
    ) : ExchangeGenerationPreparation

    data class InputNotReady(val reason: app.lawnchair.organizer.integration.InputReadinessReason) : ExchangeGenerationPreparation
}

/**
 * Issue #417: the typed persist outcome of [ExchangeFlowController.commitPreparedSession]
 * — the controller-local mirror of the run's `PersistOutcome` (the holder maps
 * it inside the run's transaction lambda; the controller stays decoupled from
 * the run type).
 */
enum class SessionPersistOutcome { Committed, WriteFailed }

/** Issue #417: the post-commit encode outcome of a committed preparation. */
sealed interface ExchangePreparedEncodeResult {
    data class Encoded(val packageText: String) : ExchangePreparedEncodeResult

    data class Failure(val problem: app.lawnchair.organizer.personalization.ExportEncodeProblem) : ExchangePreparedEncodeResult
}

/** Import outcome surfaced to the import UI. */
sealed interface ExchangeImportOutcome {
    data class Pipeline(val result: ExchangeImportResult) : ExchangeImportOutcome

    /**
     * Issue #332 (spec D-6): a post-decode environmental failure — the reply
     * was already framed and decoded, so the parse-stage recognition facts
     * travel with the outcome for the parse-first display.
     */
    data class InputNotReady(
        val reason: app.lawnchair.organizer.integration.InputReadinessReason,
        val recognized: RecognizedImportInfo? = null,
    ) : ExchangeImportOutcome
}
