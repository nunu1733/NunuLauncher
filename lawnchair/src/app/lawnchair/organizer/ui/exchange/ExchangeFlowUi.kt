package app.lawnchair.organizer.ui.exchange

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.lawnchair.organizer.integration.exchange.ExchangeFlowController
import app.lawnchair.organizer.integration.exchange.ExchangeGenerationResult
import app.lawnchair.organizer.integration.exchange.ExchangeImportOutcome
import app.lawnchair.organizer.integration.exchange.ExchangeTransportFailure
import app.lawnchair.organizer.integration.exchange.ExchangeTransportResult
import app.lawnchair.organizer.integration.exchange.FileExchangeRead
import app.lawnchair.organizer.integration.exchange.FileExchangeTransport
import app.lawnchair.organizer.personalization.IntentValidationFailure
import app.lawnchair.organizer.personalization.PrivacyTier
import app.lawnchair.organizer.personalization.exchange.ExchangeEnvelopeFailure
import app.lawnchair.organizer.personalization.exchange.ExchangeImportFailure
import app.lawnchair.organizer.personalization.exchange.ExchangeImportResult
import app.lawnchair.organizer.ui.ManualOrganizationRun
import com.android.launcher3.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Issue #205: the External Agent Exchange surface as LazyColumn items (spec
 * 205 behavior scenarios). The flow is user-driven end to end: privacy mode →
 * (explicit replacement confirmation when a live session exists) → generation
 * → pre-send disclosure of the generated immutable package → explicit send via
 * clipboard/share/file, or import of the agent's marked reply → validated
 * intent starts a fresh run through the normal preview/confirm path. Nothing
 * here writes to the layout DB.
 */
sealed interface ExchangeScreen {
    data object Closed : ExchangeScreen

    data class SelectingPrivacy(val replacementConfirmationRequired: Boolean) : ExchangeScreen

    /** Explicit pre-generation confirmation (spec 205 AC-13). */
    data class ReplacementConfirm(val tier: PrivacyTier) : ExchangeScreen

    data object Generating : ExchangeScreen

    data class Disclosing(val packageText: String, val tier: PrivacyTier) : ExchangeScreen

    data class Importing(val replyText: String) : ExchangeScreen

    data class ImportOutcomeScreen(val outcome: ExchangeImportOutcome) : ExchangeScreen
}

/** Observable holder for the exchange sub-flow hosted by the preferences screen. */
class ExchangeFlowStateHolder(
    val controller: ExchangeFlowController,
    private val run: ManualOrganizationRun,
    private val scope: CoroutineScope,
) {
    var screen: ExchangeScreen by mutableStateOf(ExchangeScreen.Closed)
        private set

    var status: ExchangeStatus? by mutableStateOf(null)
        private set

    fun openFlow() {
        status = null
        screen = ExchangeScreen.SelectingPrivacy(replacementConfirmationRequired = controller.activeSession() != null)
    }

    fun openImport() {
        status = null
        screen = ExchangeScreen.Importing("")
    }

    fun close() {
        status = null
        screen = ExchangeScreen.Closed
    }

    fun requestGeneration(replacementConfirmationRequired: Boolean, tier: PrivacyTier) {
        if (replacementConfirmationRequired) {
            screen = ExchangeScreen.ReplacementConfirm(tier)
        } else {
            generate(tier)
        }
    }

    /** The user confirmed discarding the existing exchange (spec 205 AC-13). */
    fun confirmReplacementAndGenerate(tier: PrivacyTier) {
        generate(tier)
    }

    /** The user declined; the existing session stays untouched and importable. */
    fun declineReplacement() {
        close()
    }

    fun generate(tier: PrivacyTier) {
        screen = ExchangeScreen.Generating
        scope.launch(Dispatchers.IO) {
            val result = controller.generate(tier)
            withContext(Dispatchers.Main) {
                when (result) {
                    is ExchangeGenerationResult.Generated ->
                        screen = ExchangeScreen.Disclosing(result.packageText, tier)

                    is ExchangeGenerationResult.InputNotReady -> {
                        status = ExchangeStatus(ExchangeStatus.Kind.GENERATION_INPUT_NOT_READY)
                        screen = ExchangeScreen.SelectingPrivacy(false)
                    }

                    ExchangeGenerationResult.SessionStoreFailure -> {
                        status = ExchangeStatus(ExchangeStatus.Kind.GENERATION_STORE_FAILURE)
                        screen = ExchangeScreen.SelectingPrivacy(false)
                    }

                    is ExchangeGenerationResult.EncodeFailure -> {
                        status = ExchangeStatus(ExchangeStatus.Kind.GENERATION_OVERSIZE)
                        screen = ExchangeScreen.SelectingPrivacy(false)
                    }
                }
            }
        }
    }

    fun cancelDisclosure(screen: ExchangeScreen.Disclosing) {
        // Spec 205: cancel explicitly invalidates the unsent session only.
        scope.launch(Dispatchers.IO) {
            controller.activeSession()?.let { controller.cancelDisclosure(it) }
            withContext(Dispatchers.Main) { close() }
        }
    }

    fun onTransportResult(result: ExchangeTransportResult) {
        status = when (result) {
            ExchangeTransportResult.Success -> ExchangeStatus(ExchangeStatus.Kind.TRANSPORT_SUCCESS)

            is ExchangeTransportResult.Failure ->
                ExchangeStatus(ExchangeStatus.transportFailure(result.kind))
        }
    }

    fun importFromFile(context: Context, fileTransport: FileExchangeTransport, uri: Uri) {
        scope.launch(Dispatchers.IO) {
            val read = fileTransport.read(uri)
            withContext(Dispatchers.Main) {
                when (read) {
                    is FileExchangeRead.Text -> screen = ExchangeScreen.Importing(read.text)
                    FileExchangeRead.Oversize -> status = ExchangeStatus(ExchangeStatus.Kind.INPUT_OVERSIZE)
                    FileExchangeRead.Failure -> status = ExchangeStatus(ExchangeStatus.Kind.FILE_READ_FAILED)
                }
            }
        }
    }

    fun writeFile(fileTransport: FileExchangeTransport, packageText: String, uri: Uri) {
        scope.launch(Dispatchers.IO) {
            val result = fileTransport.write(packageText, uri)
            withContext(Dispatchers.Main) { onTransportResult(result) }
        }
    }

    fun onImportTextChange(text: String) {
        screen = ExchangeScreen.Importing(text)
    }

    fun import(replyText: String) {
        scope.launch(Dispatchers.IO) {
            val outcome = controller.importReply(replyText)
            val pipeline = (outcome as? ExchangeImportOutcome.Pipeline)?.result
            if (pipeline is ExchangeImportResult.Validated) {
                // The fresh-run start performs capture/composition/planning
                // synchronously; every production entry runs it on IO (audit
                // P2-1), matching the plain start row's execute{} wrapper.
                when (run.start(intent = pipeline.validated)) {
                    is ManualOrganizationRun.StartOutcome.Started -> withContext(Dispatchers.Main) {
                        status = ExchangeStatus(ExchangeStatus.Kind.IMPORT_ACCEPTED)
                        screen = ExchangeScreen.Closed
                    }

                    ManualOrganizationRun.StartOutcome.Busy -> withContext(Dispatchers.Main) {
                        // Single-active-operation gate rejected the fresh run:
                        // typed guidance, zero-write, intent dropped.
                        status = ExchangeStatus(ExchangeStatus.Kind.RUN_BUSY)
                        screen = ExchangeScreen.Importing(replyText)
                    }
                }
            } else {
                withContext(Dispatchers.Main) {
                    screen = ExchangeScreen.ImportOutcomeScreen(outcome)
                }
            }
        }
    }
}

/** Inline typed status line content. */
data class ExchangeStatus(val kind: Kind) {
    enum class Kind {
        TRANSPORT_SUCCESS,
        TRANSPORT_CLIPBOARD_FAILED,
        TRANSPORT_SHARE_ABSENT,
        TRANSPORT_FILE_FAILED,
        FILE_READ_FAILED,
        GENERATION_INPUT_NOT_READY,
        GENERATION_STORE_FAILURE,
        GENERATION_OVERSIZE,
        INPUT_OVERSIZE,
        IMPORT_ACCEPTED,
        RUN_BUSY,
    }

    companion object {
        fun transportFailure(kind: ExchangeTransportFailure): Kind = when (kind) {
            ExchangeTransportFailure.CLIPBOARD_UNAVAILABLE -> Kind.TRANSPORT_CLIPBOARD_FAILED
            ExchangeTransportFailure.SHARE_TARGET_ABSENT -> Kind.TRANSPORT_SHARE_ABSENT
            ExchangeTransportFailure.FILE_WRITE_FAILED -> Kind.TRANSPORT_FILE_FAILED
        }
    }
}

/** The exchange items. Hosted only while the run is not active (Idle/Cancelled). */
fun LazyListScope.exchangeFlowItems(
    holder: ExchangeFlowStateHolder,
    clipboardTransport: (Context, String) -> ExchangeTransportResult,
    shareTransport: (Context, String) -> ExchangeTransportResult,
    fileTransport: FileExchangeTransport,
) {
    when (val current = holder.screen) {
        ExchangeScreen.Closed -> {
            item(key = "exchange-entry") {
                ExchangeEntryRow(
                    onOpenFlow = holder::openFlow,
                    onOpenImport = holder::openImport,
                )
            }
        }

        is ExchangeScreen.SelectingPrivacy -> {
            item(key = "exchange-privacy") {
                ExchangePrivacySelection(
                    requiresConfirmation = current.replacementConfirmationRequired,
                    onGenerate = { tier -> holder.requestGeneration(current.replacementConfirmationRequired, tier) },
                    onCancel = holder::close,
                )
            }
        }

        is ExchangeScreen.ReplacementConfirm -> {
            item(key = "exchange-replacement-confirm") {
                ExchangeReplacementConfirm(
                    onConfirm = { holder.confirmReplacementAndGenerate(current.tier) },
                    onDecline = holder::declineReplacement,
                )
            }
        }

        ExchangeScreen.Generating -> {
            item(key = "exchange-generating") {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.heightIn(max = 24.dp))
                    Text(stringResource(R.string.exchange_generating))
                }
            }
        }

        is ExchangeScreen.Disclosing -> {
            item(key = "exchange-disclosure") {
                ExchangeDisclosure(
                    holder = holder,
                    screen = current,
                    clipboardTransport = clipboardTransport,
                    shareTransport = shareTransport,
                    fileTransport = fileTransport,
                )
            }
        }

        is ExchangeScreen.Importing -> {
            item(key = "exchange-import") {
                ExchangeImportField(
                    replyText = current.replyText,
                    holder = holder,
                    fileTransport = fileTransport,
                )
            }
        }

        is ExchangeScreen.ImportOutcomeScreen -> {
            item(key = "exchange-import-outcome") {
                ExchangeImportOutcome(current.outcome, holder)
            }
        }
    }
    holder.status?.let { status ->
        item(key = "exchange-status") {
            Text(
                text = exchangeStatusText(status.kind),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite }
                    .testTag("exchange-status"),
            )
        }
    }
}

@Composable
private fun ExchangeEntryRow(onOpenFlow: () -> Unit, onOpenImport: () -> Unit) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            text = stringResource(R.string.exchange_entry_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.testTag("exchange-entry-title"),
        )
        Text(
            text = stringResource(R.string.exchange_entry_subtitle),
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(onClick = onOpenFlow, modifier = Modifier.testTag("exchange-entry-open")) {
                Text(stringResource(R.string.exchange_entry_open))
            }
            OutlinedButton(onClick = onOpenImport, modifier = Modifier.testTag("exchange-entry-import")) {
                Text(stringResource(R.string.exchange_entry_import))
            }
        }
    }
}

@Composable
private fun ExchangePrivacySelection(
    requiresConfirmation: Boolean,
    onGenerate: (PrivacyTier) -> Unit,
    onCancel: () -> Unit,
) {
    var labelInclusive by remember { mutableStateOf(false) }
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            text = stringResource(R.string.exchange_privacy_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.testTag("exchange-privacy-title"),
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
        ) {
            RadioButton(selected = !labelInclusive, onClick = { labelInclusive = false })
            Text(stringResource(R.string.exchange_privacy_redacted))
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            RadioButton(selected = labelInclusive, onClick = { labelInclusive = true })
            Text(stringResource(R.string.exchange_privacy_labels))
        }
        if (labelInclusive) {
            Text(
                text = stringResource(R.string.exchange_privacy_labels_warning),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        if (requiresConfirmation) {
            Text(
                text = stringResource(R.string.exchange_replacement_notice),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .padding(top = 8.dp)
                    .semantics { liveRegion = LiveRegionMode.Assertive },
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = {
                    onGenerate(if (labelInclusive) PrivacyTier.EXTERNAL_WITH_LABELS else PrivacyTier.EXTERNAL_REDACTED)
                },
                modifier = Modifier.testTag("exchange-generate"),
            ) {
                Text(stringResource(R.string.exchange_generate))
            }
            OutlinedButton(onClick = onCancel) {
                Text(stringResource(R.string.exchange_cancel))
            }
        }
    }
}

@Composable
private fun ExchangeReplacementConfirm(
    onConfirm: () -> Unit,
    onDecline: () -> Unit,
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            text = stringResource(R.string.exchange_replacement_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.testTag("exchange-replacement-title"),
        )
        Text(
            text = stringResource(R.string.exchange_replacement_warning),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(onClick = onConfirm, modifier = Modifier.testTag("exchange-replacement-confirm")) {
                Text(stringResource(R.string.exchange_replacement_confirm))
            }
            OutlinedButton(onClick = onDecline, modifier = Modifier.testTag("exchange-replacement-decline")) {
                Text(stringResource(R.string.exchange_replacement_decline))
            }
        }
    }
}

@Composable
private fun ExchangeDisclosure(
    holder: ExchangeFlowStateHolder,
    screen: ExchangeScreen.Disclosing,
    clipboardTransport: (Context, String) -> ExchangeTransportResult,
    shareTransport: (Context, String) -> ExchangeTransportResult,
    fileTransport: FileExchangeTransport,
) {
    val context = LocalContext.current
    val fileSaver = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri: Uri? ->
        if (uri != null) holder.writeFile(fileTransport, screen.packageText, uri)
    }
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            text = stringResource(R.string.exchange_disclosure_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.testTag("exchange-disclosure-title"),
        )
        Text(
            text = if (screen.tier == PrivacyTier.EXTERNAL_WITH_LABELS) {
                stringResource(R.string.exchange_disclosure_labels_included)
            } else {
                stringResource(R.string.exchange_disclosure_redacted)
            },
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        Text(
            text = stringResource(R.string.exchange_disclosure_hint),
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            text = screen.packageText,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
                .heightIn(max = 240.dp)
                .verticalScroll(rememberScrollState())
                .testTag("exchange-disclosure-package"),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = { holder.onTransportResult(clipboardTransport(context, screen.packageText)) },
                modifier = Modifier.testTag("exchange-send-clipboard"),
            ) {
                Text(stringResource(R.string.exchange_copy))
            }
            FilledTonalButton(
                onClick = { holder.onTransportResult(shareTransport(context, screen.packageText)) },
                modifier = Modifier.testTag("exchange-send-share"),
            ) {
                Text(stringResource(R.string.exchange_share))
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = { fileSaver.launch("nunu-launcher-exchange.txt") },
                modifier = Modifier.testTag("exchange-send-file"),
            ) {
                Text(stringResource(R.string.exchange_save_file))
            }
            OutlinedButton(
                onClick = { holder.cancelDisclosure(screen) },
                modifier = Modifier.testTag("exchange-cancel"),
            ) {
                Text(stringResource(R.string.exchange_cancel))
            }
        }
    }
}

@Composable
private fun ExchangeImportField(
    replyText: String,
    holder: ExchangeFlowStateHolder,
    fileTransport: FileExchangeTransport,
) {
    val context = LocalContext.current
    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri != null) holder.importFromFile(context, fileTransport, uri)
    }
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            text = stringResource(R.string.exchange_import_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.testTag("exchange-import-title"),
        )
        OutlinedTextField(
            value = replyText,
            onValueChange = holder::onImportTextChange,
            label = { Text(stringResource(R.string.exchange_import_hint)) },
            minLines = 4,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
                .testTag("exchange-import-field"),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = { holder.import(replyText) },
                enabled = replyText.isNotBlank(),
                modifier = Modifier.testTag("exchange-import-action"),
            ) {
                Text(stringResource(R.string.exchange_import_action))
            }
            OutlinedButton(
                onClick = { filePicker.launch(arrayOf("text/plain")) },
                modifier = Modifier.testTag("exchange-import-file"),
            ) {
                Text(stringResource(R.string.exchange_import_from_file))
            }
            OutlinedButton(onClick = holder::close) {
                Text(stringResource(R.string.exchange_cancel))
            }
        }
    }
}

@Composable
private fun ExchangeImportOutcome(outcome: ExchangeImportOutcome, holder: ExchangeFlowStateHolder) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            text = stringResource(R.string.exchange_import_result_title),
            style = MaterialTheme.typography.titleMedium,
        )
        val pipeline = (outcome as? ExchangeImportOutcome.Pipeline)?.result
        val message = when {
            pipeline is ExchangeImportResult.Failure -> exchangeFailureText(pipeline.failure)

            outcome is ExchangeImportOutcome.InputNotReady ->
                stringResource(R.string.exchange_generation_input_not_ready)

            else -> stringResource(R.string.exchange_import_result_unknown)
        }
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier
                .padding(vertical = 8.dp)
                .semantics { liveRegion = LiveRegionMode.Polite }
                .testTag("exchange-import-outcome-message"),
        )
        Text(
            text = stringResource(R.string.exchange_import_retry_hint),
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedButton(onClick = holder::openImport, modifier = Modifier.padding(top = 8.dp)) {
            Text(stringResource(R.string.exchange_import_retry))
        }
    }
}

@Composable
private fun exchangeStatusText(kind: ExchangeStatus.Kind): String = when (kind) {
    ExchangeStatus.Kind.TRANSPORT_SUCCESS -> stringResource(R.string.exchange_transport_success)
    ExchangeStatus.Kind.TRANSPORT_CLIPBOARD_FAILED -> stringResource(R.string.exchange_transport_clipboard_failed)
    ExchangeStatus.Kind.TRANSPORT_SHARE_ABSENT -> stringResource(R.string.exchange_transport_share_absent)
    ExchangeStatus.Kind.TRANSPORT_FILE_FAILED -> stringResource(R.string.exchange_transport_file_failed)
    ExchangeStatus.Kind.FILE_READ_FAILED -> stringResource(R.string.exchange_transport_file_failed)
    ExchangeStatus.Kind.GENERATION_INPUT_NOT_READY -> stringResource(R.string.exchange_generation_input_not_ready)
    ExchangeStatus.Kind.GENERATION_STORE_FAILURE -> stringResource(R.string.exchange_generation_store_failure)
    ExchangeStatus.Kind.GENERATION_OVERSIZE -> stringResource(R.string.exchange_generation_oversize)
    ExchangeStatus.Kind.INPUT_OVERSIZE -> stringResource(R.string.exchange_failure_input_oversize)
    ExchangeStatus.Kind.IMPORT_ACCEPTED -> stringResource(R.string.exchange_import_accepted)
    ExchangeStatus.Kind.RUN_BUSY -> stringResource(R.string.exchange_run_busy)
}

@Composable
private fun exchangeFailureText(failure: ExchangeImportFailure): String = when (failure) {
    is ExchangeImportFailure.Envelope -> when (failure.failure) {
        ExchangeEnvelopeFailure.InputOversize -> stringResource(R.string.exchange_failure_input_oversize)
        ExchangeEnvelopeFailure.FramingMissing -> stringResource(R.string.exchange_failure_framing_missing)
        ExchangeEnvelopeFailure.FramingAmbiguous -> stringResource(R.string.exchange_failure_framing_ambiguous)
        ExchangeEnvelopeFailure.FramingEmpty -> stringResource(R.string.exchange_failure_framing_empty)
    }

    is ExchangeImportFailure.Contract -> when (val f = failure.failure) {
        IntentValidationFailure.SchemaMismatch -> stringResource(R.string.exchange_failure_schema_mismatch)
        IntentValidationFailure.ExportMismatch -> stringResource(R.string.exchange_failure_export_mismatch)
        IntentValidationFailure.SessionExpired -> stringResource(R.string.exchange_failure_session_expired)
        IntentValidationFailure.ContextStale -> stringResource(R.string.exchange_failure_context_stale)
        IntentValidationFailure.Oversize -> stringResource(R.string.exchange_failure_oversize)
        is IntentValidationFailure.UnknownRef -> stringResource(R.string.exchange_failure_unknown_ref)
        IntentValidationFailure.DuplicateRef -> stringResource(R.string.exchange_failure_duplicate_ref)
        IntentValidationFailure.IncompleteCoverage -> stringResource(R.string.exchange_failure_incomplete_coverage)
        IntentValidationFailure.InvalidEnum -> stringResource(R.string.exchange_failure_invalid_enum)
        IntentValidationFailure.ForbiddenContent -> stringResource(R.string.exchange_failure_forbidden_content)
        is IntentValidationFailure.MobilityContradiction -> stringResource(R.string.exchange_failure_mobility_contradiction)
        IntentValidationFailure.CapabilityUnsupported -> stringResource(R.string.exchange_failure_capability_unsupported)
    }
}
