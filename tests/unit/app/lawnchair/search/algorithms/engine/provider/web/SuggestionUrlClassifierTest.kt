package app.lawnchair.search.algorithms.engine.provider.web

import app.lawnchair.search.algorithms.engine.provider.web.SuggestionUrlCategory.INDETERMINATE
import app.lawnchair.search.algorithms.engine.provider.web.SuggestionUrlCategory.INVALID
import app.lawnchair.search.algorithms.engine.provider.web.SuggestionUrlCategory.STATICALLY_LOCAL
import app.lawnchair.search.algorithms.engine.provider.web.SuggestionUrlCategory.STATICALLY_PUBLIC
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Contract tests for the pure suggestion URL template classifier (Issue #528).
 * The canonicalization (single `%s` substitution before parsing) is shared by
 * the fetch guard and the settings UI, so the verdicts here bind both.
 */
class SuggestionUrlClassifierTest {

    private fun classify(template: String) = SuggestionUrlClassifier.classify(template)

    @Test
    fun `template canonicalization replaces the placeholder exactly once before parsing`() {
        // 10.0.2.2 is the emulator host loopback: RFC1918 -> statically-local.
        assertEquals(STATICALLY_LOCAL, classify("https://10.0.2.2/suggest?q=%s"))
        assertEquals("https://10.0.2.2/suggest?q=lawnchair", SuggestionUrlClassifier.canonicalize("https://10.0.2.2/suggest?q=%s"))
    }

    @Test
    fun `ipv4 rfc1918 ranges are statically-local`() {
        assertEquals(STATICALLY_LOCAL, classify("https://10.1.2.3/s?q=%s"))
        assertEquals(STATICALLY_LOCAL, classify("https://172.16.0.1/s?q=%s"))
        assertEquals(STATICALLY_LOCAL, classify("https://172.31.255.255/s?q=%s"))
        assertEquals(STATICALLY_LOCAL, classify("https://192.168.1.1/s?q=%s"))
    }

    @Test
    fun `ipv4 rfc1918 boundaries split local from public`() {
        assertEquals(STATICALLY_PUBLIC, classify("https://172.15.255.255/s?q=%s"))
        assertEquals(STATICALLY_PUBLIC, classify("https://172.32.0.0/s?q=%s"))
        assertEquals(STATICALLY_PUBLIC, classify("https://9.255.255.255/s?q=%s"))
        assertEquals(STATICALLY_PUBLIC, classify("https://11.0.0.0/s?q=%s"))
    }

    @Test
    fun `ipv4 cgnat and link-local are statically-local`() {
        assertEquals(STATICALLY_LOCAL, classify("https://100.64.0.1/s?q=%s"))
        assertEquals(STATICALLY_LOCAL, classify("https://100.127.255.254/s?q=%s"))
        assertEquals(STATICALLY_LOCAL, classify("https://169.254.1.1/s?q=%s"))
    }

    @Test
    fun `ipv4 cgnat boundaries split local from public`() {
        assertEquals(STATICALLY_PUBLIC, classify("https://100.63.255.255/s?q=%s"))
        assertEquals(STATICALLY_PUBLIC, classify("https://100.128.0.0/s?q=%s"))
    }

    @Test
    fun `ipv4 multicast and broadcast are statically-local`() {
        assertEquals(STATICALLY_LOCAL, classify("https://224.0.0.1/s?q=%s"))
        assertEquals(STATICALLY_LOCAL, classify("https://239.255.255.250/s?q=%s"))
        assertEquals(STATICALLY_LOCAL, classify("https://255.255.255.255/s?q=%s"))
    }

    @Test
    fun `ipv4 public unicast is statically-public`() {
        assertEquals(STATICALLY_PUBLIC, classify("https://8.8.8.8/s?q=%s"))
        assertEquals(STATICALLY_PUBLIC, classify("https://1.1.1.1/s?q=%s"))
        assertEquals(STATICALLY_PUBLIC, classify("https://198.51.100.7/s?q=%s"))
    }

    @Test
    fun `ipv4 loopback is statically-public not local`() {
        // Documented choice: loopback is not part of the Android 17
        // local-network definition.
        assertEquals(STATICALLY_PUBLIC, classify("https://127.0.0.1/s?q=%s"))
        assertEquals(STATICALLY_PUBLIC, classify("https://127.8.8.8/s?q=%s"))
    }

    @Test
    fun `ipv4 unspecified and class-e stay indeterminate`() {
        // 0.0.0.0 and reserved 240/4 are not provably non-local address strings.
        assertEquals(INDETERMINATE, classify("https://0.0.0.0/s?q=%s"))
        assertEquals(INDETERMINATE, classify("https://240.1.2.3/s?q=%s"))
    }

    @Test
    fun `ipv6 link-local and multicast literals are statically-local`() {
        assertEquals(STATICALLY_LOCAL, classify("https://[fe80::1]/s?q=%s"))
        assertEquals(STATICALLY_LOCAL, classify("https://[feb0::1]/s?q=%s"))
        assertEquals(STATICALLY_LOCAL, classify("https://[ff02::1]/s?q=%s"))
    }

    @Test
    fun `ipv6 zone id is stripped before classification`() {
        // RFC 6874 zone id arrives percent-encoded as %25.
        assertEquals(STATICALLY_LOCAL, classify("https://[fe80::1%25eth0]/s?q=%s"))
    }

    @Test
    fun `ipv6 ula and global unicast literals are indeterminate`() {
        // Route-dependent on Android 17; never asserted public.
        assertEquals(INDETERMINATE, classify("https://[fd00::1]/s?q=%s"))
        assertEquals(INDETERMINATE, classify("https://[fc00::1]/s?q=%s"))
        assertEquals(INDETERMINATE, classify("https://[2001:db8::1]/s?q=%s"))
    }

    @Test
    fun `ipv6 loopback is statically-public not local`() {
        assertEquals(STATICALLY_PUBLIC, classify("https://[::1]/s?q=%s"))
    }

    @Test
    fun `dot-local hostnames are statically-local`() {
        assertEquals(STATICALLY_LOCAL, classify("https://nas.local/s?q=%s"))
        assertEquals(STATICALLY_LOCAL, classify("https://NAS.LOCAL/s?q=%s"))
    }

    @Test
    fun `plain hostnames are indeterminate`() {
        assertEquals(INDETERMINATE, classify("https://example.com/s?q=%s"))
        assertEquals(INDETERMINATE, classify("https://localhost/s?q=%s"))
        assertEquals(INDETERMINATE, classify("https://my-nas/s?q=%s"))
    }

    @Test
    fun `plain http is a valid scheme and the host is classified`() {
        // Cleartext policy is a separate typed outcome, not a classifier verdict.
        assertEquals(STATICALLY_LOCAL, classify("http://192.168.1.1/s?q=%s"))
        assertEquals(INDETERMINATE, classify("http://example.com/s?q=%s"))
    }

    @Test
    fun `non-http schemes are invalid`() {
        assertEquals(INVALID, classify("ftp://10.0.2.2/s?q=%s"))
        assertEquals(INVALID, classify("file:///tmp/s?q=%s"))
        // schemeOf still reports the scheme so the settings UI can show the
        // dedicated non-HTTPS message instead of the generic invalid one.
        assertEquals("ftp", SuggestionUrlClassifier.schemeOf("ftp://10.0.2.2/s?q=%s"))
    }

    @Test
    fun `missing scheme is invalid`() {
        assertEquals(INVALID, classify("example.com/s?q=%s"))
        assertEquals(INVALID, classify("10.0.2.2/s?q=%s"))
    }

    @Test
    fun `missing placeholder is invalid`() {
        assertEquals(INVALID, classify("https://example.com/suggest?q=query"))
        assertEquals(INVALID, classify(""))
        assertNull(SuggestionUrlClassifier.canonicalize("https://example.com/suggest?q=query"))
    }

    @Test
    fun `unparseable templates are invalid`() {
        assertEquals(INVALID, classify("https://exa mple.com/s?q=%s"))
        assertEquals(INVALID, classify("https:///path?q=%s"))
        // A second literal %s survives the single substitution and breaks URI parsing.
        assertEquals(INVALID, classify("https://example.com/s?q=%s&x=%s"))
    }

    @Test
    fun `schemeOf reports lowercase scheme for valid templates`() {
        assertEquals("https", SuggestionUrlClassifier.schemeOf("HTTPS://example.com/s?q=%s"))
        assertEquals("http", SuggestionUrlClassifier.schemeOf("http://192.168.1.1/s?q=%s"))
        // schemeOf reflects the parse result alone (host-less URL still has a
        // scheme); the invalid verdict itself comes from classify().
        assertEquals("https", SuggestionUrlClassifier.schemeOf("https:///path?q=%s"))
    }
}
