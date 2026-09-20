package app.lawnchair.organizer.ui.exchange

import app.lawnchair.organizer.integration.InputReadinessReason
import app.lawnchair.organizer.integration.exchange.ExchangeImportOutcome
import app.lawnchair.organizer.personalization.ExportSession
import app.lawnchair.organizer.personalization.PrivacyTier
import app.lawnchair.organizer.personalization.exchange.ExchangeImportSummary
import app.lawnchair.organizer.personalization.exchange.RecognizedImportInfo
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #372 (accepted spec): the request-flow contract oracles that run on
 * the JVM — the system-Back mapping (EX-AC-11), the T-16 summary derivation
 * (EX-AC-04), the T-15 remaining-time display root (EX-AC-03), and the
 * EN/ja string contract for the new and revised copy (EX-AC-05/EX-AC-10:
 * name set parity, placeholder parity, the v4 free-text warning, and
 * `LOCAL_FULL` never appearing in UI vocabulary — D-14).
 */
class ExchangeRequestFlowContractTest {

    // region Back mapping (EX-AC-11)

    @Test
    fun backOnRequestCreationFacesClosesZeroWrite() {
        assertEquals(ExchangeBackAction.CLOSE, exchangeBackAction(ExchangeScreen.SelectingPrivacy(replacementConfirmationRequired = false)))
        assertEquals(ExchangeBackAction.CLOSE, exchangeBackAction(ExchangeScreen.SelectingPrivacy(replacementConfirmationRequired = true, activeRequestExpiresAtEpochMs = 5L)))
        assertEquals(ExchangeBackAction.CLOSE, exchangeBackAction(ExchangeScreen.ReplacementConfirm(PrivacyTier.EXTERNAL_REDACTED)))
    }

    @Test
    fun backOnUnsentDisclosureRequestsTheDiscardConfirmation() {
        val state = disclosureState(sent = false, inFlight = false, cancelling = false)
        assertEquals(ExchangeBackAction.REQUEST_DISCARD, exchangeBackAction(ExchangeScreen.Disclosing(state)))
    }

    @Test
    fun backOnBusyFacesIsBlockedNotDelegated() {
        // Generating and the in-flight/cancelling disclosure must CONSUME Back:
        // delegating would navigate away, dispose the composition, and cancel
        // the holder's rememberCoroutineScope operations (review round 2).
        assertEquals(ExchangeBackAction.BLOCKED, exchangeBackAction(ExchangeScreen.Generating))
        assertEquals(
            ExchangeBackAction.BLOCKED,
            exchangeBackAction(ExchangeScreen.Disclosing(disclosureState(sent = false, inFlight = true, cancelling = false))),
        )
        assertEquals(
            ExchangeBackAction.BLOCKED,
            exchangeBackAction(ExchangeScreen.Disclosing(disclosureState(sent = false, inFlight = false, cancelling = true))),
        )
    }

    @Test
    fun backOnSentDisclosureClosesWithTheRequestSurviving() {
        assertEquals(ExchangeBackAction.CLOSE, exchangeBackAction(ExchangeScreen.Disclosing(disclosureState(sent = true, inFlight = false, cancelling = false))))
    }

    @Test
    fun backOnImportFacesAndClosedStaysWithTheCurrentContracts() {
        // The import faces are #373/spec 328/332 territory; #372 must not
        // touch their Back behavior.
        assertEquals(ExchangeBackAction.NONE, exchangeBackAction(ExchangeScreen.Closed))
        assertEquals(ExchangeBackAction.NONE, exchangeBackAction(ExchangeScreen.Importing("")))
        assertEquals(
            ExchangeBackAction.NONE,
            exchangeBackAction(
                ExchangeScreen.ImportOutcomeScreen(
                    outcome = ExchangeImportOutcome.InputNotReady(
                        reason = InputReadinessReason.ReconciliationPending,
                        recognized = RecognizedImportInfo(framing = null, intentSchemaVersion = null, authoredEntryCount = null),
                    ),
                ),
            ),
        )
        assertEquals(
            ExchangeBackAction.NONE,
            exchangeBackAction(
                ExchangeScreen.ImportSuccess(
                    summary = emptySummary(),
                    entryKind = ExchangeImportEntryKind.IDLE,
                    attemptToken = 1L,
                ),
            ),
        )
    }

    // endregion

    // region T-16 summary derivation (EX-AC-04)

    @Test
    fun disclosureSummaryCountsExactlyTheExportedItemsOfTheBoundSession() {
        val session = exportSession(itemRefCount = 3)
        val state = ExchangeDisclosureState(session = session, packageText = "pkg", tier = PrivacyTier.EXTERNAL_WITH_LABELS)
        val summary = exchangeDisclosureSummary(state)
        assertEquals(3, summary.itemCount)
        assertEquals(PrivacyTier.EXTERNAL_WITH_LABELS, summary.tier)
    }

    // endregion

    // region T-15 remaining-time display root (EX-AC-03)

    @Test
    fun remainingDisplayBucketsWholeHoursAndTheUnderHourTail() {
        assertEquals(RequestRemaining.Hours(23), requestRemainingDisplay(expiresAtEpochMs = 1_000L + 23 * MILLIS_PER_HOUR, nowMs = 1_000L))
        assertEquals(RequestRemaining.Hours(1), requestRemainingDisplay(expiresAtEpochMs = 1_000L + MILLIS_PER_HOUR, nowMs = 1_000L))
        assertEquals(RequestRemaining.UnderOneHour, requestRemainingDisplay(expiresAtEpochMs = 1_000L + MILLIS_PER_HOUR - 1, nowMs = 1_000L))
        // Past the boundary the display is stale-safe: it shows the short
        // tail until the scheduled re-read clears the whole row.
        assertEquals(RequestRemaining.UnderOneHour, requestRemainingDisplay(expiresAtEpochMs = 1_000L, nowMs = 2_000L))
    }

    // endregion

    // region String contract (EX-AC-05/EX-AC-10)

    @Test
    fun newAndRevisedRequestCopyExistsInBothLocales() {
        val names = listOf(
            "exchange_method_consult",
            "exchange_request_title",
            "exchange_request_active_line",
            "exchange_request_remaining_under_hour",
            "exchange_expectation_fixed_home",
            "exchange_disclosure_summary_items",
            "exchange_disclosure_summary_limit",
            "exchange_disclosure_expand",
            "exchange_disclosure_collapse",
            "exchange_discard",
            "exchange_discard_confirm_title",
            "exchange_discard_confirm_body",
            "exchange_discard_confirm_confirm",
            "exchange_privacy_labels_warning",
        )
        for (localeDir in listOf("values", "values-ja")) {
            val xml = lawnchairStringsXml(localeDir).readText()
            for (name in names) {
                assertTrue("$name must exist in $localeDir", xml.contains("name=\"$name\""))
            }
        }
    }

    @Test
    fun summaryItemsPlaceholderMatchesAcrossLocales() {
        // Spec 123 AC-4/AC-5: format resources keep the placeholder set across
        // locales; Kotlin-side concatenation never builds the sentence.
        for (localeDir in listOf("values", "values-ja")) {
            val value = lawnchairStringsXml(localeDir)
                .readText()
                .substringAfter("name=\"exchange_disclosure_summary_items\"")
                .substringBefore("</string>")
            assertTrue("$localeDir summary items must keep the count placeholder", value.contains("%1\$d"))
        }
    }

    @Test
    fun remainingHoursPluralExistsInBothLocales() {
        for (localeDir in listOf("values", "values-ja")) {
            val xml = lawnchairStringsXml(localeDir).readText()
            val plurals = xml.substringAfter("name=\"exchange_request_remaining_hours\"").substringBefore("</plurals>")
            assertTrue("$localeDir must declare the hours plural with the count placeholder", plurals.contains("%1\$d"))
        }
    }

    @Test
    fun localFullNeverAppearsInExchangeUiVocabulary() {
        // D-14: LOCAL_FULL stays an internal contract value and never leaks
        // into the user-facing strings (strings scan, EX-AC-05).
        for (localeDir in listOf("values", "values-ja")) {
            val xml = lawnchairStringsXml(localeDir).readText()
            for (line in xml.lineSequence()) {
                if (line.contains("name=\"exchange_")) {
                    assertFalse("$localeDir leaks LOCAL_FULL: $line", line.contains("LOCAL_FULL"))
                }
            }
        }
    }

    @Test
    fun removedIdleEntryStringsAreGoneFromBothResources() {
        // Issue #372 (D-04): the idle entry row was removed; its strings must
        // not linger (reference grep contract, EX-AC-10).
        for (localeDir in listOf("values", "values-ja")) {
            val xml = lawnchairStringsXml(localeDir).readText()
            assertFalse(xml.contains("name=\"exchange_entry_title\""))
            assertFalse(xml.contains("name=\"exchange_entry_open\""))
        }
    }

    // endregion

    // region helpers

    private fun disclosureState(sent: Boolean, inFlight: Boolean, cancelling: Boolean): ExchangeDisclosureState = ExchangeDisclosureState(
        session = exportSession(itemRefCount = 1),
        packageText = "pkg",
        tier = PrivacyTier.EXTERNAL_REDACTED,
        sent = sent,
        transportInFlight = inFlight,
        cancelling = cancelling,
    )

    private fun exportSession(itemRefCount: Int): ExportSession = ExportSession(
        exportId = "export-under-test",
        itemRefs = (0 until itemRefCount).associate { "ref-$it" to app.lawnchair.organizer.planning.ItemId("id-$it") },
        tier = PrivacyTier.EXTERNAL_REDACTED,
        sourceContextDigest = "digest",
        signalProvenance = null,
        createdAtEpochMs = 1_000L,
        expiresAtEpochMs = 1_000L + 24 * MILLIS_PER_HOUR,
    )

    private fun emptySummary() = ExchangeImportSummary(
        recognizedCount = 0,
        noJudgmentCount = 0,
        priorityCount = 0,
        groupCount = 0,
        keepCount = 0,
        builtInCategoryCount = 0,
        userCategoryCount = 0,
        proposedGroupCount = 0,
        placementCount = 0,
        minimizeMovement = false,
        scopeCandidateCount = 0,
    )

    private fun lawnchairStringsXml(localeDir: String): File {
        var dir: File? = File(System.getProperty("user.dir"))
        repeat(4) {
            val candidate = File(dir, "lawnchair/res/$localeDir/strings.xml")
            if (candidate.exists()) return candidate
            dir = dir?.parentFile
        }
        error("lawnchair strings.xml not found for $localeDir from ${System.getProperty("user.dir")}")
    }

    // endregion
}
