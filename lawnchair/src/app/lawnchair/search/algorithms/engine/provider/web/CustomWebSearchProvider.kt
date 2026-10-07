package app.lawnchair.search.algorithms.engine.provider.web

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import app.lawnchair.preferences2.PreferenceManager2
import com.android.launcher3.R
import com.patrykmichalik.opto.core.firstBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray

/**
 * A WebSearchProvider that uses user-defined URLs for searching and suggestions.
 */
object CustomWebSearchProvider : WebSearchProvider {
    private const val TAG = "CustomWebSearchProvider"

    override val id: String = "custom"

    /**
     * The label for this search provider.
     *
     * This label is dynamic. If the user provides a name for the custom search provider,
     * that name will be used. Otherwise, a default label will be used.
     *
     * Note: This property returns a raw [String], not a `@StringRes`. The UI layer
     * is responsible for handling this and displaying the appropriate string resource
     * if the user-provided name is not available.
     *
     * For simplicity in the [WebSearchProvider] interface, this property points to a
     * generic string resource ([R.string.search_provider_custom]) as a fallback.
     */
    override val label: Int = R.string.search_provider_custom

    override val iconRes: Int = R.drawable.ic_search

    private var searchUrlTemplate: String = ""
    private var suggestionsUrlTemplate: String = ""
    private var displayName: String = ""
    private var appContext: Context? = null
    private val okHttpClient = OkHttpClient()

    /**
     * The template whose fetch is (or was last) in flight, set at fetch start
     * (Issue #528 review round 1). The outer timeout hook
     * [onFetchTimeout] publishes through the publish-if-template-current
     * lifecycle against THIS template, so a timeout that arrives after the
     * user already changed the template is refused as stale instead of
     * marking the new configuration as failed.
     */
    @Volatile
    private var lastFetchStartTemplate: String? = null

    /**
     * In-memory typed outcome for the current suggestions URL template
     * (Issue #528). Memory-only (zero-write); cleared to
     * [SuggestionFetchOutcome.NOT_RUN] when the template changes, and a result
     * is published only while the template used at fetch start is still
     * current, so a slow stale call never overwrites a newer state.
     */
    private val outcomeState = SuggestionFetchOutcomeState()

    /**
     * Compose-observable mirror of [outcomeState] read by the settings UI;
     * always copied from [outcomeState] after each mutation.
     */
    var lastOutcome: SuggestionFetchOutcome by mutableStateOf(SuggestionFetchOutcome.NOT_RUN)
        private set

    fun getDisplayName(): String = displayName

    /**
     * Template-change hook shared by [configure] and the settings UI: updates
     * the active template and clears the typed outcome when it changed
     * (zero-write; memory only).
     */
    fun onSuggestionsTemplateChanged(template: String) {
        if (template == suggestionsUrlTemplate) return
        suggestionsUrlTemplate = template
        outcomeState.onTemplateChanged(template)
        lastOutcome = outcomeState.outcome
    }

    /**
     * Timeout hook for the outer suggestion wrapper (Issue #528 review round
     * 1, F2): `WebSuggestionProvider.search()` applies `.timeout()` OUTSIDE
     * this provider's flow, so a timeout cancels the flow and the in-flow
     * typed publishes never run — without this hook the timeout path would
     * bypass the typed-outcome contract (empty suggestions in the search
     * surface while settings still shows the previous outcome or `not-run`).
     *
     * Publishes `GENERIC_NETWORK_FAILURE` for [lastFetchStartTemplate] through
     * the existing publish-if-template-current lifecycle: refused as stale
     * when the template changed since fetch start. A late completing fetch
     * cannot overwrite this state because its cancelled coroutine fails the
     * [SuggestionFetchOutcomeState.publishIfActive] gate.
     *
     * Runs in the wrapper's catch block (still-active context) and never
     * performs network work. Log redaction: class name and fixed strings
     * only — no URL, no query.
     */
    fun onFetchTimeout() {
        val template = lastFetchStartTemplate ?: return
        outcomeState.publish(template, SuggestionFetchOutcome.GENERIC_NETWORK_FAILURE)
        lastOutcome = outcomeState.outcome
    }

    override fun configure(context: Context): WebSearchProvider {
        val prefs = PreferenceManager2.getInstance(context)
        appContext = context.applicationContext
        searchUrlTemplate = prefs.webSuggestionProviderUrl.firstBlocking()
        displayName = prefs.webSuggestionProviderName.firstBlocking()
        onSuggestionsTemplateChanged(prefs.webSuggestionProviderSuggestionsUrl.firstBlocking())
        return this
    }

    override fun getSuggestions(query: String): Flow<List<String>> = flow {
        if (query.isBlank() || suggestionsUrlTemplate.isBlank()) {
            emit(emptyList())
            return@flow
        }

        // Issue #528: the classifier verdict is decided ONCE per fetch, before
        // any network activity, and shared by both guards below (review round
        // 1, F3) so the fetch path can never execute a template the settings
        // UI classifies differently.
        val templateAtFetchStart = suggestionsUrlTemplate
        val category = SuggestionUrlClassifier.classify(templateAtFetchStart)

        // Review round 1, F3: an INVALID template (e.g. two `%s` placeholders
        // or an unsupported scheme) short-circuits BEFORE any network call and
        // without a LAN permission prompt. The settings UI's typed invalid
        // message owns that surface, so no outcome is published (stays
        // NOT_RUN). Redaction: reason category and SDK_INT only.
        if (category == SuggestionUrlCategory.INVALID) {
            Log.i(TAG, SuggestionFetchLog.guardInvalidTemplate(sdkInt = Build.VERSION.SDK_INT))
            emit(emptyList())
            return@flow
        }

        // Issue #528 fetch guard: on SDK 37+ a statically-local (LAN) template
        // requires ACCESS_LOCAL_NETWORK. Short-circuit BEFORE any network call
        // so the user never waits for the LNP timeout. `indeterminate` is
        // never short-circuited here — it fetches and flows into the typed
        // outcome contract. `statically-public`, granted permissions and
        // API 36 and below keep the unchanged fetch path.
        if (
            Build.VERSION.SDK_INT >= LanNetworkContract.SDK_CINNAMON_BUN &&
            category == SuggestionUrlCategory.STATICALLY_LOCAL &&
            !isLocalNetworkPermissionGranted()
        ) {
            publishOutcome(templateAtFetchStart, SuggestionFetchOutcome.BLOCKED_BY_PERMISSION)
            Log.i(TAG, SuggestionFetchLog.guardBlocked(permissionGranted = false, sdkInt = Build.VERSION.SDK_INT))
            emit(emptyList())
            return@flow
        }

        lastFetchStartTemplate = templateAtFetchStart

        try {
            val encodedQuery = Uri.encode(query)
            val url = templateAtFetchStart.replace("%s", encodedQuery)

            val request = Request.Builder().url(url).build()
            okHttpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val responseBody = response.body.string()
                    publishOutcome(templateAtFetchStart, SuggestionFetchOutcome.SUCCESS)
                    // We assume a standard OpenSearch format, as it's the most common.
                    // A malformed body keeps the SUCCESS outcome: the fetch itself
                    // succeeded; the parse failure is logged separately.
                    val suggestions = parseOpenSearchResponse(responseBody)
                    emit(suggestions)
                } else {
                    // Redaction: the status code is the only payload detail ever
                    // logged (no URL, query, or body).
                    Log.w(TAG, "Failed to retrieve suggestions: ${response.code}")
                    publishOutcome(templateAtFetchStart, SuggestionFetchOutcome.HTTP_ERROR)
                    emit(emptyList())
                }
            }
        } catch (e: Exception) {
            // Issue #528 log contract: exception messages can contain the URL
            // or the query, so only the exception class name is logged.
            Log.e(TAG, "Error during suggestion retrieval: ${SuggestionFetchLog.failure(e)}")
            publishOutcome(templateAtFetchStart, mapFailureToOutcome(e))
            emit(emptyList())
        }
    }.flowOn(Dispatchers.IO)

    override fun getSearchUrl(query: String): String {
        if (searchUrlTemplate.isBlank()) {
            // Fallback to a default search engine if the user's template is invalid.
            return GoogleWebSearchProvider.getSearchUrl(query)
        }
        val encodedQuery = Uri.encode(query)
        return searchUrlTemplate.replace("%s", encodedQuery)
    }

    private fun isLocalNetworkPermissionGranted(): Boolean {
        val context = appContext ?: return false
        return ContextCompat.checkSelfPermission(context, LanNetworkContract.PERMISSION_ACCESS_LOCAL_NETWORK) ==
            PackageManager.PERMISSION_GRANTED
    }

    /**
     * Publishes a fetch result through the publish-if-template-current
     * lifecycle, additionally gated on the fetching coroutine still being
     * active (Issue #528 review round 1, F2): when the outer `.timeout()` has
     * cancelled this flow, a blocking call that completes afterwards must not
     * flip the user-visible timeout state (published by [onFetchTimeout]) to
     * SUCCESS. Cancellation is cooperative, so the check is required here.
     */
    private suspend fun publishOutcome(templateAtFetchStart: String, outcome: SuggestionFetchOutcome) {
        val published = outcomeState.publishIfActive(templateAtFetchStart, currentCoroutineContext().isActive, outcome)
        if (published) lastOutcome = outcomeState.outcome
    }

    private fun parseOpenSearchResponse(responseBody: String): List<String> {
        return try {
            val jsonArray = JSONArray(responseBody)
            val suggestionsArray = jsonArray.getJSONArray(1)
            (0 until suggestionsArray.length()).map { suggestionsArray.getString(it) }
        } catch (e: Exception) {
            // Redaction: the JSONException message can quote response body
            // fragments, so only the class name is logged.
            Log.e(TAG, "Failed to parse custom provider response: ${SuggestionFetchLog.failure(e)}")
            emptyList()
        }
    }

    override fun toString(): String = id
}
