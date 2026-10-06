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

    @Test
    fun `okhttp cleartext rejection is classified as cleartext-blocked`() {
        // OkHttp 5.5.0 throws UnknownServiceException for a cleartext-policy
        // rejection; the message names the policy, not the old platform wording.
        val exception = java.net.UnknownServiceException(
            "CLEARTEXT communication to 10.0.2.2 not permitted by network security policy",
        )
        assertEquals(SuggestionFetchOutcome.CLEARTEXT_BLOCKED, mapFailureToOutcome(exception))
    }

    @Test
    fun `unknown service exception is cleartext-blocked regardless of message wording`() {
        // Type-based detection must not depend on the message text, which
        // varies across library versions.
        assertEquals(
            SuggestionFetchOutcome.CLEARTEXT_BLOCKED,
            mapFailureToOutcome(java.net.UnknownServiceException("protocol not permitted")),
        )
    }

    @Test
    fun `cleartext message category fallback keeps non-ssl rejections classified`() {
        // Robustness fallback: any exception whose message names the cleartext
        // policy is cleartext-blocked even if a wrapper changes the type.
        val exception = java.net.SocketException("CLEARTEXT communication to host not permitted by network security policy")
        assertEquals(SuggestionFetchOutcome.CLEARTEXT_BLOCKED, mapFailureToOutcome(exception))
    }

    @Test
    fun `ssl failures stay tls-ct-failure`() {
        assertEquals(SuggestionFetchOutcome.TLS_CT_FAILURE, mapFailureToOutcome(javax.net.ssl.SSLException("certificate unknown")))
        assertEquals(
            SuggestionFetchOutcome.TLS_CT_FAILURE,
            mapFailureToOutcome(javax.net.ssl.SSLHandshakeException("PKIX path validation failed")),
        )
    }

    @Test
    fun `other network failures stay generic`() {
        assertEquals(SuggestionFetchOutcome.GENERIC_NETWORK_FAILURE, mapFailureToOutcome(java.net.UnknownHostException("nas.local")))
        assertEquals(SuggestionFetchOutcome.GENERIC_NETWORK_FAILURE, mapFailureToOutcome(java.io.IOException("timeout")))
    }
}
