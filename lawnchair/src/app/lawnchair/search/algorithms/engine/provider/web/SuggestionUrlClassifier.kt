package app.lawnchair.search.algorithms.engine.provider.web

import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI

/**
 * Static classification of a suggestion URL template (spec Issue #528).
 *
 * The values are the sets that can be decided from the address string alone,
 * deliberately NOT a full reproduction of the platform local-network
 * definition (directly-connected / stub networks are route-dependent and
 * cannot be decided from a URL). Everything not decidable is
 * [INDETERMINATE], which the fetch guard never treats as public.
 */
enum class SuggestionUrlCategory {
    /** Address string alone proves a local destination (Android 17 LNP scope). */
    STATICALLY_LOCAL,

    /** Address string alone proves a non-local IPv4 unicast destination. */
    STATICALLY_PUBLIC,

    /** Not decidable from the address string (hostnames, route-dependent IPv6). */
    INDETERMINATE,

    /** Unparseable, unsupported scheme, or placeholder contract violation. */
    INVALID,
}

/**
 * Pure classifier for custom suggestion URL **templates** (Issue #528).
 *
 * Canonicalization contract, shared verbatim by the fetch guard and the
 * settings UI: the literal `%s` placeholder is replaced exactly once with a
 * safe dummy value, then the result is parsed as [URI]. Both callers go
 * through [classify]/[schemeOf] so the valid/invalid judgment can never
 * diverge. The interface is platform-free (pure `java.net`/`kotlin` types) so
 * the same contract is asserted by JVM unit tests.
 *
 * Scheme rule (documented choice): only `http`/`https` templates are valid
 * for suggestions; any other explicit scheme (e.g. `ftp://`) is [SuggestionUrlCategory.INVALID].
 * A plain `http://` scheme is valid and its host is classified normally —
 * whether cleartext succeeds is a separate platform decision that surfaces as
 * the typed `cleartext-blocked` outcome, not as a classifier verdict.
 */
object SuggestionUrlClassifier {

    const val PLACEHOLDER: String = "%s"

    /** URL-safe literal substituted for [PLACEHOLDER] during canonicalization. */
    const val PLACEHOLDER_SUBSTITUTE: String = "lawnchair"

    /**
     * Returns the template with the `%s` placeholder replaced exactly once,
     * or `null` when the template violates the placeholder contract (blank or
     * missing `%s`).
     */
    fun canonicalize(template: String): String? {
        if (!template.contains(PLACEHOLDER)) return null
        return template.replaceFirst(PLACEHOLDER, PLACEHOLDER_SUBSTITUTE)
    }

    /**
     * Lowercase scheme of the canonicalized template, `null` when the template
     * has no placeholder, does not parse, or declares no scheme.
     */
    fun schemeOf(template: String): String? {
        val canonical = canonicalize(template) ?: return null
        return try {
            URI(canonical).scheme?.lowercase()
        } catch (e: Exception) {
            null
        }
    }

    /** Classifies the template into one of the four static categories. */
    fun classify(template: String): SuggestionUrlCategory {
        val canonical = canonicalize(template) ?: return SuggestionUrlCategory.INVALID
        val uri = try {
            URI(canonical)
        } catch (e: Exception) {
            // Includes templates with a second literal %s: the remaining
            // "%s" is an illegal escape pair for RFC 2396 parsing.
            return SuggestionUrlCategory.INVALID
        }
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return SuggestionUrlCategory.INVALID
        // getHost() is null for registry-based / unparseable authorities
        // (e.g. underscore hosts, partial IPv4 like "1.2.3") or a missing host.
        val host = uri.host
        if (host.isNullOrEmpty()) return SuggestionUrlCategory.INVALID

        if (host.startsWith("[") && host.endsWith("]")) {
            return classifyIpv6Literal(host.substring(1, host.length - 1))
        }
        if (host.endsWith(".local", ignoreCase = true)) {
            // mDNS names resolve to link-local destinations on the local network.
            return SuggestionUrlCategory.STATICALLY_LOCAL
        }
        if (host.contains(":")) {
            // Bare (unbracketed) IPv6 literal; brackets are handled above.
            return classifyIpv6Literal(host)
        }
        if (host.any { it == '.' } && host.all { it.isDigit() || it == '.' }) {
            return classifyDottedQuad(host)
        }
        // Every remaining hostname (including "localhost" and numeric-only
        // forms such as "2130706433", whose IPv4 meaning is ambiguous):
        // the address string alone cannot prove local or non-local.
        return SuggestionUrlCategory.INDETERMINATE
    }

    /**
     * Strict dotted-quad classification. Anything dot-numeric but not a clean
     * quad (partial quads, leading zeros, values > 255) cannot be interpreted
     * unambiguously and stays [SuggestionUrlCategory.INDETERMINATE].
     */
    private fun classifyDottedQuad(host: String): SuggestionUrlCategory {
        val parts = host.split(".").map { part ->
            if (part.isEmpty() || part.length > 3 || part.any { !it.isDigit() }) return SuggestionUrlCategory.INDETERMINATE
            // Leading zeros are ambiguous (octal vs decimal); reject the
            // literal instead of guessing an interpretation.
            if (part.length > 1 && part.startsWith("0")) return SuggestionUrlCategory.INDETERMINATE
            part.toInt()
        }
        if (parts.any { it > 255 }) return SuggestionUrlCategory.INDETERMINATE
        val (b0, b1, b2, b3) = parts
        return when {
            // RFC1918 private ranges.
            b0 == 10 -> SuggestionUrlCategory.STATICALLY_LOCAL

            b0 == 172 && b1 in 16..31 -> SuggestionUrlCategory.STATICALLY_LOCAL

            b0 == 192 && b1 == 168 -> SuggestionUrlCategory.STATICALLY_LOCAL

            // CGNAT 100.64/10.
            b0 == 100 && b1 in 64..127 -> SuggestionUrlCategory.STATICALLY_LOCAL

            // IPv4 link-local.
            b0 == 169 && b1 == 254 -> SuggestionUrlCategory.STATICALLY_LOCAL

            // IPv4 multicast 224.0.0.0/4 and the limited broadcast address;
            // both are inside the platform local-network definition.
            b0 in 224..239 -> SuggestionUrlCategory.STATICALLY_LOCAL

            b0 == 255 && b1 == 255 && b2 == 255 && b3 == 255 -> SuggestionUrlCategory.STATICALLY_LOCAL

            // Loopback is deliberately NOT statically-local: the Android 17
            // local-network definition does not include it (documented choice).
            b0 == 127 -> SuggestionUrlCategory.STATICALLY_PUBLIC

            // Unspecified address: a connection target would effectively be
            // this device, so it cannot be asserted non-local.
            b0 == 0 -> SuggestionUrlCategory.INDETERMINATE

            // Reserved class E (240/4) is not provably non-local either.
            b0 >= 240 -> SuggestionUrlCategory.INDETERMINATE

            // Everything else is a provably non-local IPv4 unicast.
            else -> SuggestionUrlCategory.STATICALLY_PUBLIC
        }
    }

    /**
     * IPv6 literal classification. The zone id (e.g. `fe80::1%eth0`, arriving
     * percent-encoded as `%25`) is stripped: the zone names a local interface,
     * so classification is decided by the address part alone.
     */
    private fun classifyIpv6Literal(literalWithMaybeZone: String): SuggestionUrlCategory {
        val percentEncoded = literalWithMaybeZone.indexOf("%25")
        val literal = if (percentEncoded >= 0) {
            literalWithMaybeZone.substring(0, percentEncoded)
        } else {
            val plainZone = literalWithMaybeZone.indexOf('%')
            if (plainZone >= 0) literalWithMaybeZone.substring(0, plainZone) else literalWithMaybeZone
        }
        val address = try {
            // A string containing ':' is always treated as an IP literal; no
            // name resolution happens here.
            InetAddress.getByName(literal)
        } catch (e: Exception) {
            return SuggestionUrlCategory.INDETERMINATE
        }
        if (address is Inet6Address) {
            val bytes = address.address
            return when {
                // ff00::/8 multicast and fe80::/10 link-local are inside the
                // platform local-network definition.
                address.isMulticastAddress || address.isLinkLocalAddress -> SuggestionUrlCategory.STATICALLY_LOCAL

                // ::1 loopback is deliberately NOT statically-local: the
                // Android 17 local-network definition does not include it.
                address.isLoopbackAddress -> SuggestionUrlCategory.STATICALLY_PUBLIC

                // :: unspecified cannot be asserted non-local.
                address.isAnyLocalAddress -> SuggestionUrlCategory.INDETERMINATE

                // IPv4-mapped literals follow the embedded IPv4 rules.
                isIpv4Mapped(bytes) -> classifyIpv4Mapped(bytes)

                // ULA fc00::/7, global unicast and every other unicast form are
                // route-dependent on Android 17 (directly-connected / stub / VPN
                // exclusions) and must not be asserted public or local.
                else -> SuggestionUrlCategory.INDETERMINATE
            }
        }
        // IPv4 literal embedded without brackets (e.g. "::ffff:10.0.0.1" style
        // handled by the JVM as Inet4Address) falls back to IPv4 rules.
        return classifyDottedQuad(address.hostAddress ?: return SuggestionUrlCategory.INDETERMINATE)
    }

    private fun isIpv4Mapped(bytes: ByteArray): Boolean {
        for (i in 0 until 10) if (bytes[i].toInt() != 0) return false
        return bytes[10].toInt() == 0xff && bytes[11].toInt() == 0xff
    }

    private fun classifyIpv4Mapped(bytes: ByteArray): SuggestionUrlCategory = classifyDottedQuad("${bytes[12].toInt() and 0xff}.${bytes[13].toInt() and 0xff}.${bytes[14].toInt() and 0xff}.${bytes[15].toInt() and 0xff}")
}
