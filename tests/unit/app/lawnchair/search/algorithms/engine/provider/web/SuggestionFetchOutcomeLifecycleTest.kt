package app.lawnchair.search.algorithms.engine.provider.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract tests for the typed fetch outcome lifecycle (Issue #528):
 * a template change clears the outcome to NOT_RUN, and a result publishes
 * only while the template used at fetch start is still current. The
 * redaction helpers keep URLs, queries and exception messages out of logcat.
 */
class SuggestionFetchOutcomeLifecycleTest {

    @Test
    fun `fresh state starts as not-run`() {
        assertEquals(SuggestionFetchOutcome.NOT_RUN, SuggestionFetchOutcomeState().outcome)
    }

    @Test
    fun `changing the template clears the outcome to not-run`() {
        val state = SuggestionFetchOutcomeState("https://nas.local/s?q=%s")
        state.publish("https://nas.local/s?q=%s", SuggestionFetchOutcome.BLOCKED_BY_PERMISSION)
        assertEquals(SuggestionFetchOutcome.BLOCKED_BY_PERMISSION, state.outcome)

        state.onTemplateChanged("https://example.com/s?q=%s")
        assertEquals(SuggestionFetchOutcome.NOT_RUN, state.outcome)
    }

    @Test
    fun `same template re-observation keeps the outcome`() {
        val template = "https://nas.local/s?q=%s"
        val state = SuggestionFetchOutcomeState(template)
        state.publish(template, SuggestionFetchOutcome.SUCCESS)

        state.onTemplateChanged(template)
        assertEquals(SuggestionFetchOutcome.SUCCESS, state.outcome)
    }

    @Test
    fun `stale fetch result never overwrites the current outcome`() {
        val oldTemplate = "https://nas.local/s?q=%s"
        val state = SuggestionFetchOutcomeState(oldTemplate)
        state.publish(oldTemplate, SuggestionFetchOutcome.GENERIC_NETWORK_FAILURE)

        state.onTemplateChanged("https://example.com/s?q=%s")
        state.publish("https://example.com/s?q=%s", SuggestionFetchOutcome.SUCCESS)
        assertEquals(SuggestionFetchOutcome.SUCCESS, state.outcome)

        // A slow call for the old configuration must not publish.
        assertFalse(state.publish(oldTemplate, SuggestionFetchOutcome.TLS_CT_FAILURE))
        assertEquals(SuggestionFetchOutcome.SUCCESS, state.outcome)
    }

    @Test
    fun `current fetch result publishes and replaces the outcome`() {
        val template = "https://nas.local/s?q=%s"
        val state = SuggestionFetchOutcomeState(template)

        assertTrue(state.publish(template, SuggestionFetchOutcome.HTTP_ERROR))
        assertEquals(SuggestionFetchOutcome.HTTP_ERROR, state.outcome)
    }

    @Test
    fun `guard log carries only category, permission state and sdk int`() {
        val message = SuggestionFetchLog.guardBlocked(permissionGranted = false, sdkInt = 37)
        assertTrue(message.contains("reason=lnp-statically-local"))
        assertTrue(message.contains("permissionGranted=false"))
        assertTrue(message.contains("sdkInt=37"))
        // The redaction contract: no URL, host, path or query material.
        listOf("https", "%s", "query", "10.0.2.2", "nas.local").forEach { forbidden ->
            assertFalse("must not contain $forbidden", message.contains(forbidden, ignoreCase = true))
        }
    }

    @Test
    fun `failure log drops exception messages that can carry the url or query`() {
        val exception = java.net.UnknownHostException("https://10.0.2.2/suggest?q=secret%20query")
        val logged = SuggestionFetchLog.failure(exception)
        assertEquals("java.net.UnknownHostException", logged)
        assertFalse(logged.contains("10.0.2.2"))
        assertFalse(logged.contains("secret"))
    }
}
