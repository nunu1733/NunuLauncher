package app.lawnchair.search.algorithms.engine.provider.web

/**
 * Typed outcome of the latest custom suggestion fetch attempt (spec Issue
 * #528). The fetch path never degrades to a silent empty result without an
 * assignable category; the settings UI renders each value as a localized
 * status line. In-memory only — nothing is persisted (zero-write).
 */
enum class SuggestionFetchOutcome {
    /** No fetch has run for the current suggestions URL template yet. */
    NOT_RUN,

    /** The fetch completed with a 2xx response. */
    SUCCESS,

    /** Short-circuited before any network call: statically-local template and `ACCESS_LOCAL_NETWORK` not granted. */
    BLOCKED_BY_PERMISSION,

    /** The platform blocked cleartext (HTTP) traffic to the endpoint. */
    CLEARTEXT_BLOCKED,

    /** TLS handshake / certificate (incl. Certificate Transparency) failure. */
    TLS_CT_FAILURE,

    /** The endpoint answered with a non-2xx status (only the code is kept). */
    HTTP_ERROR,

    /** Any other failure (DNS, connect, timeout, malformed request URL). */
    GENERIC_NETWORK_FAILURE,
}

/**
 * In-memory lifecycle contract for the typed outcome (Issue #528):
 * - changing the suggestions URL template clears the outcome to
 *   [SuggestionFetchOutcome.NOT_RUN], so a failure of an old URL is never
 *   displayed against a new configuration;
 * - a fetch result is published only while the template used at fetch start
 *   still equals the current template — a slow stale call never overwrites
 *   the outcome of a newer configuration.
 *
 * Pure and JVM-testable; [CustomWebSearchProvider] mirrors the value into a
 * Compose-observable state for the settings UI.
 */
class SuggestionFetchOutcomeState(initialTemplate: String = "") {

    var outcome: SuggestionFetchOutcome = SuggestionFetchOutcome.NOT_RUN
        private set

    var template: String = initialTemplate
        private set

    /** Clears the outcome when the template changed; a no-op for equal templates. */
    fun onTemplateChanged(newTemplate: String) {
        if (newTemplate == template) return
        template = newTemplate
        outcome = SuggestionFetchOutcome.NOT_RUN
    }

    /**
     * Publishes [newOutcome] only when [templateAtFetchStart] is still the
     * current template. Returns whether the outcome was published.
     */
    fun publish(templateAtFetchStart: String, newOutcome: SuggestionFetchOutcome): Boolean {
        if (templateAtFetchStart != template) return false
        outcome = newOutcome
        return true
    }
}

/**
 * Redacted log message builders for the custom suggestion fetch (Issue #528
 * log contract): logcat carries the reason category, permission state and
 * `SDK_INT` only — never the raw query, the full URL, userinfo, or a response
 * body. The functions are pure so the redaction contract is directly unit
 * testable.
 */
internal object SuggestionFetchLog {

    /** Typed log for the LAN fetch guard short-circuit (no host, no URL). */
    fun guardBlocked(permissionGranted: Boolean, sdkInt: Int): String = "suggestion fetch short-circuited: reason=lnp-statically-local " +
        "permissionGranted=$permissionGranted sdkInt=$sdkInt"

    /**
     * The exception message can contain the URL or the query (e.g.
     * UnknownHostException, MalformedURLException), so only the class name is
     * ever logged.
     */
    fun failure(throwable: Throwable): String = throwable.javaClass.name
}
