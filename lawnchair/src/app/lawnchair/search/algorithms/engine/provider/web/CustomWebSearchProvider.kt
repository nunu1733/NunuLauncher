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
import javax.net.ssl.SSLException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
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

        // Issue #528 fetch guard: on SDK 37+ a statically-local (LAN) template
        // requires ACCESS_LOCAL_NETWORK. Short-circuit BEFORE any network call
        // so the user never waits for the LNP timeout. `indeterminate` is
        // never short-circuited here — it fetches and flows into the typed
        // outcome contract. `statically-public`, granted permissions and
        // API 36 and below keep the unchanged fetch path.
        val templateAtFetchStart = suggestionsUrlTemplate
        if (
            Build.VERSION.SDK_INT >= LanNetworkContract.SDK_CINNAMON_BUN &&
            SuggestionUrlClassifier.classify(templateAtFetchStart) == SuggestionUrlCategory.STATICALLY_LOCAL &&
            !isLocalNetworkPermissionGranted()
        ) {
            publishOutcome(templateAtFetchStart, SuggestionFetchOutcome.BLOCKED_BY_PERMISSION)
            Log.i(TAG, SuggestionFetchLog.guardBlocked(permissionGranted = false, sdkInt = Build.VERSION.SDK_INT))
            emit(emptyList())
            return@flow
        }

        try {
            val encodedQuery = Uri.encode(query)
            val url = suggestionsUrlTemplate.replace("%s", encodedQuery)

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
     * Maps a fetch exception to the typed outcome. Detection is by exception
     * type / message category; none of it carries URL or query text.
     */
    private fun mapFailureToOutcome(e: Exception): SuggestionFetchOutcome = when {
        // Android's cleartext rejection surfaces as a SocketException whose
        // message names the policy ("Cleartext HTTP traffic to <host> not
        // permitted"); matching the category avoids logging the host.
        e.message?.contains("Cleartext HTTP traffic", ignoreCase = true) == true -> SuggestionFetchOutcome.CLEARTEXT_BLOCKED

        // SSLHandshakeException and SSLPeerUnverifiedException are both
        // SSLException subclasses: certificate, trust and CT failures.
        e is SSLException -> SuggestionFetchOutcome.TLS_CT_FAILURE

        else -> SuggestionFetchOutcome.GENERIC_NETWORK_FAILURE
    }

    private fun publishOutcome(templateAtFetchStart: String, outcome: SuggestionFetchOutcome) {
        outcomeState.publish(templateAtFetchStart, outcome)
        lastOutcome = outcomeState.outcome
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
