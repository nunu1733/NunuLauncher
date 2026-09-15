package app.lawnchair.organizer.ui.exchange

import app.lawnchair.organizer.integration.exchange.ExchangeTransportFailure
import app.lawnchair.organizer.integration.exchange.ExchangeTransportResult
import app.lawnchair.organizer.personalization.ExportSession
import app.lawnchair.organizer.personalization.PrivacyTier
import app.lawnchair.organizer.planning.ItemId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #205 (PR review P1): the disclosure lifecycle — a disclosure binds its
 * own generating session, transport success makes it non-cancelable, and only
 * the pre-send cancel invalidates.
 */
class ExchangeDisclosureStateTest {

    private fun session(exportId: String): ExportSession = ExportSession(
        exportId = exportId,
        itemRefs = emptyMap(),
        tier = PrivacyTier.EXTERNAL_REDACTED,
        sourceContextDigest = "digest",
        signalProvenance = null,
        createdAtEpochMs = 0L,
        expiresAtEpochMs = 1L,
    )

    @Test
    fun aFreshDisclosureIsPreSendCancelable() {
        val state = ExchangeDisclosureState(session("e1"), "package", PrivacyTier.EXTERNAL_REDACTED)
        assertTrue(state.cancelable)
        assertFalse(state.sent)
    }

    @Test
    fun firstTransportSuccessMarksTheDisclosureSentAndNonCancelable() {
        val state = ExchangeDisclosureState(session("e1"), "package", PrivacyTier.EXTERNAL_REDACTED)
            .onTransportResult(ExchangeTransportResult.Success)
        assertTrue(state.sent)
        assertFalse(state.cancelable)
    }

    @Test
    fun transportFailureLeavesTheDisclosureCancelable() {
        val state = ExchangeDisclosureState(session("e1"), "package", PrivacyTier.EXTERNAL_REDACTED)
            .onTransportResult(
                ExchangeTransportResult.Failure(ExchangeTransportFailure.CLIPBOARD_UNAVAILABLE),
            )
        assertFalse(state.sent)
        assertTrue(state.cancelable)
    }

    @Test
    fun sentIsStickyAcrossFurtherTransportResults() {
        val state = ExchangeDisclosureState(session("e1"), "package", PrivacyTier.EXTERNAL_REDACTED)
            .onTransportResult(ExchangeTransportResult.Success)
            .onTransportResult(
                ExchangeTransportResult.Failure(ExchangeTransportFailure.SHARE_TARGET_ABSENT),
            )
        assertTrue(state.sent)
        assertFalse(state.cancelable)
    }

    @Test
    fun theBoundSessionTravelsWithTheState() {
        val bound = session("e1")
        val state = ExchangeDisclosureState(bound, "package", PrivacyTier.EXTERNAL_WITH_LABELS)
        assertEquals(bound, state.session)
        assertEquals(PrivacyTier.EXTERNAL_WITH_LABELS, state.tier)
        assertEquals("package", state.packageText)
    }
}
