package `in`.opentrack.india.ui

import `in`.opentrack.india.core.RailTime
import `in`.opentrack.india.v2.mirror.PublicPageChecks

/** HTTP, cache and admitted movement are separate diagnostic statements.
 * No body, URL query, cookie or device identity is written to the diary. */
object SourceEvidenceReceipt {
    fun publicPage(source: String, train: String, originDay: Long,
        result: PublicPageChecks.Result): String {
        val transport = when {
            result.fromHttpCache -> "CACHED_BODY"
            result.responseDigest != null -> "NETWORK_ANSWER"
            else -> "FAILED_OR_UNUSABLE"
        }
        val detached = result.orgDetachedPageClaim
        val parsed = when {
            result.orgReport != null -> "SITE_COMPARISON"
            detached?.event != null -> "DETACHED_SITE_EVENT_CLAIM"
            detached != null -> "DETACHED_SITE_CARDS"
            result.orgSiteCards.isNotEmpty() -> "SITE_CARDS_ONLY"
            else -> "NO_DATED_EVENT"
        }
        val identity = when {
            result.orgReport?.originInferred == true -> "INFERRED"
            detached != null -> "LIVE_ROUTE_MATCH_RUN_DATE_UNVERIFIED"
            result.orgSiteCards.isNotEmpty() -> "INFERRED_FOR_PAGE_DISPLAY_ONLY"
            else -> "UNVERIFIED"
        }
        val siteClaim = result.orgReport?.let { it.anchorCode + ":" + it.eventMinute }
            ?: detached?.event?.let { it.stationCode + ":" + it.minute } ?: "none"
        val siteClaimKind = result.orgReport?.let { if (it.departed) "DEPARTURE" else "ARRIVAL" }
            ?: detached?.event?.kind?.name ?: "none"
        val siteRoute = detached?.let { it.websiteFromCode + ">" + it.websiteToCode } ?: "none"
        val selectedRoute = detached?.let { it.selectedRouteFromCode + ">" + it.selectedRouteToCode }
            ?: "none"
        val siteClaimAt = detached?.event?.let {
            RailTime.railMinuteToEpochMillis(detached.originDay, it.minute)
        }
        return "source=$source train=$train originDay=$originDay " +
            "transport=$transport bytes=${result.responseBytes} sha256_16=${result.responseDigest ?: "none"} " +
            "parsed=$parsed orgContentParsed=${result.orgContentParsed} runIdentity=$identity " +
            "primaryActuals=0 primaryMarker=none " +
            "siteRows=${result.orgReport?.rows?.size ?: 0} detachedSiteClaims=${if (detached?.event != null) 1 else 0} " +
            "siteCards=${result.orgSiteCards.size} siteDisplayStops=${result.orgSiteClocks.size} " +
            "siteClaimStation=${result.orgReport?.anchorCode ?: detached?.event?.stationCode ?: "unknown"} " +
            "siteClaim=$siteClaim siteClaimKind=$siteClaimKind " +
            "siteRoute=$siteRoute selectedRoute=$selectedRoute " +
            "liveIdentityRouteMatch=${detached != null} siteClaimTimeBasis=" +
            "${if (detached != null) "page_clock_aligned;selected_origin_day_unverified" else "selected_origin_timetable"} " +
            "siteClaimAt=${siteClaimAt ?: "unknown"} " +
            "pagePublishedAt=${result.orgReport?.pageUpdatedAt ?: detached?.pageUpdatedAt ?: result.orgSitePageUpdatedAt ?: "unknown"} " +
            "pageClockHasSeconds=${result.orgSitePageClockHasSeconds || detached?.pageClockHasSeconds == true} " +
            "checkedAt=${result.checkedAt}"
    }

    fun official(train: String, originDay: Long, receipt: OfficialRequestReceipt,
        actualStops: Int, lastStop: String?, eventMinute: Int?, feedStamp: Long?): String {
        val admitted = receipt.kind == OfficialRequestReceipt.Kind.ANSWERED_WITH_ACTUALS
        val event = if (admitted) "${lastStop ?: "unknown"}:${eventMinute ?: "unknown"}" else "none"
        return "source=NTES train=$train originDay=$originDay " +
            "transport=${receipt.kind} parsedActualStops=$actualStops " +
            "primaryActuals=${if (admitted) actualStops else 0} lastEvent=$event " +
            "feedPublishedAt=${feedStamp ?: "unknown"} note=${receipt.reason.take(100)}"
    }
}
