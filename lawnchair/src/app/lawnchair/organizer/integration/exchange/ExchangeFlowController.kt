package app.lawnchair.organizer.integration.exchange

import app.lawnchair.organizer.personalization.ContextExportBuilder
import app.lawnchair.organizer.personalization.ContextExportCodec
import app.lawnchair.organizer.personalization.ExportSession
import app.lawnchair.organizer.personalization.ExportSessionStore
import app.lawnchair.organizer.personalization.IntentCodec
import app.lawnchair.organizer.personalization.IntentValidationFailure
import app.lawnchair.organizer.personalization.PrivacyTier
import app.lawnchair.organizer.personalization.RandomIdAllocator
import app.lawnchair.organizer.personalization.ValidatedPersonalizedIntent
import app.lawnchair.organizer.personalization.exchange.ExchangeGenerationGate
import app.lawnchair.organizer.personalization.exchange.ExchangeGenerationGateOutcome
import app.lawnchair.organizer.personalization.exchange.ExchangeImportFailure
import app.lawnchair.organizer.personalization.exchange.ExchangeImportPipeline
import app.lawnchair.organizer.personalization.exchange.ExchangeImportResult
import app.lawnchair.organizer.personalization.exchange.IntentFramingResult
import app.lawnchair.organizer.personalization.exchange.IntentImportParser

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
) {

    constructor(
        adapter: ExchangeInputAdapter,
        store: ExportSessionStore,
        allocator: RandomIdAllocator,
        clock: () -> Long,
    ) : this(
        composeExportInputs = adapter::composeForExport,
        currentStructuralInputs = adapter::currentStructural,
        store = store,
        allocator = allocator,
        clock = clock,
    )

    /** The active (unexpired) session, if any — drives the replacement gate. */
    fun activeSession(): ExportSession? = store.active(clock())

    /** Gate decision for starting a new generation flow (spec 205 AC-13). */
    fun generationGate(userConfirmation: Boolean?): ExchangeGenerationGateOutcome = ExchangeGenerationGate.evaluate(activeSession() != null, userConfirmation)

    /**
     * Generates one exchange package in the chosen tier. Callers must have
     * passed the replacement gate first when an active session existed.
     */
    fun generate(tier: PrivacyTier): ExchangeGenerationResult {
        val inputs = when (val result = composeExportInputs(clock())) {
            is ExchangeInputResult.NotReady -> return ExchangeGenerationResult.InputNotReady(result.reason)
            is ExchangeInputResult.ExportReady -> result.inputs
        }
        val built = ContextExportBuilder.build(inputs, tier, allocator)
        if (!store.save(built.session)) {
            // Fail-closed: without a durable session the package can never be
            // imported after a process death, so nothing is disclosed.
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
        store.invalidate(session.exportId)
    }

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
                ),
            )
        if (session.isExpired(clock())) {
            return ExchangeImportOutcome.Pipeline(
                ExchangeImportResult.Failure(
                    ExchangeImportFailure.Contract(IntentValidationFailure.SessionExpired),
                ),
            )
        }
        val structural = when (val result = currentStructuralInputs()) {
            is app.lawnchair.organizer.integration.exchange.ExchangeStructuralResult.NotReady ->
                return ExchangeImportOutcome.InputNotReady(result.reason)

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

/** Import outcome surfaced to the import UI. */
sealed interface ExchangeImportOutcome {
    data class Pipeline(val result: ExchangeImportResult) : ExchangeImportOutcome

    data class InputNotReady(val reason: app.lawnchair.organizer.integration.InputReadinessReason) : ExchangeImportOutcome
}
