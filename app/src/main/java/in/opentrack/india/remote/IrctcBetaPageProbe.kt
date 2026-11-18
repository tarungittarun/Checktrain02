package `in`.opentrack.india.remote

import `in`.opentrack.india.core.BrowserFetch
import `in`.opentrack.india.core.Diary
import `in`.opentrack.india.core.Http
import org.json.JSONObject

/**
 * A first-party probe of IRCTC's public Track your train page. The page itself builds and submits its
 * undocumented `ntesData` POST; we deliberately do not guess that request body. It returns the page text
 * plus the observed request body and response for protocol investigation, and is not yet a LiveProvider
 * or a source of actuals.
 */
object IrctcBetaPageProbe {
    const val PAGE_URL = "https://www.irctc.co.in/eticket/booking/live-train"
    private const val INPUT_LABEL = "Train Number/Train Name"

    /**
     * Load the public beta page in the app's same-origin WebView, enter one train number, tap its Search
     * control and read the resulting visible page. The page makes its own POST with its own session and
     * headers. For `ntesData`, the capture includes method, body, header names, status and response body;
     * cookie values and request-header values are never collected.
     */
    fun search(trainNo: String): Http.Result {
        if (!trainNo.matches(Regex("[0-9]{5}"))) {
            return Http.Result.Failed(Http.Result.Kind.PARSE, "IRCTC beta search needs a five-digit train number")
        }
        val result = BrowserFetch.pageInteract(
            url = PAGE_URL,
            settleMillis = 2_500,
            taps = listOf("Search"),
            postTapWaitMillis = 9_000,
            totalBudgetMillis = 35_000,
            inputHint = INPUT_LABEL,
            inputValue = trainNo,
            capturePathSuffix = "/eticketing/protected/mapps1/ntesData",
        )
        if (result is Http.Result.Ok) {
            val root = runCatching { JSONObject(result.body) }.getOrNull()
            val capture = root?.optJSONObject("capturedRequest")
            val visible = root?.optString("visibleText").orEmpty()
            Diary.get()?.log("IRCTC_BETA") {
                if (capture == null) {
                    "train=$trainNo no ntesData capture; visibleText=${visible.length}B; " +
                        visible.take(180).replace('\n', ' ')
                } else {
                    "train=$trainNo ${capture.optString("method")} ${capture.optString("url").substringAfter("irctc.co.in").take(100)} " +
                        "HTTP ${capture.optInt("status", -1)} request=${capture.optString("requestBody").length}B " +
                        "response=${capture.optString("responseBody").length}B " +
                        "headerNames=${capture.optJSONArray("requestHeaderNames")?.length() ?: 0} " +
                        "page=${visible.length}B"
                }
            }
        }
        return result
    }
}
