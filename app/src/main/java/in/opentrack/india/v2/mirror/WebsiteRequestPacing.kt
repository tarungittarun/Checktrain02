package `in`.opentrack.india.v2.mirror

import android.content.Context

/** Host-wide, persisted courtesy pause after a website HTTP refusal. Never bypass or relabel a
 * cached page as new. A new screen or train number does not reset the refused host's pause. */
class WebsiteRequestPacing(
    context: Context,
    preferencesName: String = "org_website_request_pause",
) {
    private val prefs = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)

    fun paused(now: Long): PublicPageChecks.Result? {
        if (now < 0L) return null
        val until = prefs.getLong("until", 0L)
        val code = prefs.getInt("code", 0)
        if (code !in listOf(403, 429) || until <= now) return null
        val remaining = until - now // both non-negative and until > now, so cannot overflow
        if (remaining > PAUSE_MS) return null // discard corrupt or stale preference values
        val minutes = (remaining + 59_999L) / 60_000L
        return PublicPageChecks.Result(
            "Website request paused after HTTP $code; no new request. Retry in $minutes min.",
            now, false)
    }

    fun observe(result: PublicPageChecks.Result, now: Long) {
        if (result.detail.startsWith("Website request paused")) return
        if (result.pageAnswered) {
            observeHttpStatus(null, true, now)
            return
        }
        val code = when {
            result.detail.contains("HTTP 403") -> 403
            result.detail.contains("HTTP 429") -> 429
            else -> null
        }
        observeHttpStatus(code, false, now)
    }

    /** Transport-aware entry point for JSON sources; never infer an HTTP status from body text. */
    fun observeHttpStatus(status: Int?, responseReceived: Boolean, now: Long) {
        if (now < 0L) return
        if (status == 403 || status == 429) {
            val until = if (now > Long.MAX_VALUE - PAUSE_MS) Long.MAX_VALUE else now + PAUSE_MS
            prefs.edit().putLong("until", until).putInt("code", status).apply()
        } else if (responseReceived) {
            prefs.edit().remove("until").remove("code").apply()
        }
    }

    companion object { const val PAUSE_MS = 15 * 60_000L }
}
