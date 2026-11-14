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
            result.orgReport != null && !result.orgReport.originInferred &&
                result.orgRunDateMatched -> "DATE_ROUTE_MATCHED"
            result.orgReport?.originInferred == true -> "INFERRED"
            detached?.runDateMatched == true -> "LIVE_ROUTE_MATCH_DATE_VERIFIED"
            detached != null -> "LIVE_ROUTE_MATCH_RUN_DATE_UNVERIFIED"
            result.orgSiteCards.isNotEmpty() -> "INFERRED_FOR_PAGE_DISPLAY_ONLY"
            else -> "UNVERIFIED"
        }
        val siteClaim = result.orgReport?.let { it.anchorCode + ":" + it.eventMinute }
            ?: detached?.event?.let { it.stationCode + ":" + it.minute } ?: "none"
        val siteClaimKind = result.orgReport?.let { if (it.departed) "DEPARTURE" else "ARRIVAL" }
            ?: detached?.event?.kind?.name ?: "none"
        val siteRoute = detached?.let { it.websiteFromCode + ">" + it.websiteToCode }
            ?: result.orgPageEndpoints?.let { endpoints ->
                if (endpoints.fromCode != null && endpoints.toCode != null)
                    endpoints.fromCode + ">" + endpoints.toCode else "none"
            } ?: "none"
        val selectedRoute = detached?.let { it.selectedRouteFromCode + ">" + it.selectedRouteToCode }
            ?: result.orgSelectedRouteFromCode?.let { from -> result.orgSelectedRouteToCode
                ?.let { to -> from + ">" + to } } ?: "none"
        val pageEndpoints = result.orgPageEndpoints?.let {
            "${it.fromCode ?: "unknown"}>${it.toCode ?: "unknown"}"
        } ?: "unknown"
        fun labelToken(value: String?): String = value?.replace(
            Regex("[^\\p{L}\\p{N}]+"), "_")?.trim('_')?.take(48)?.ifBlank { "unknown" }
            ?: "unknown"
        val pageEndpointLabels = result.orgPageEndpoints?.let {
            "${labelToken(it.fromLabel)}>${labelToken(it.toLabel)}"
        } ?: "unknown"
        val siteClaimAt = detached?.event?.let {
            RailTime.railMinuteToEpochMillis(detached.originDay, it.minute)
        }
        val claimTimeBasis = when {
            detached?.runDateMatched == true -> "selected_origin_date_verified;route_conflict"
            detached != null -> "page_clock_aligned;selected_origin_day_unverified"
            result.orgRunDateMatched -> "selected_origin_date_verified"
            result.orgReport != null -> "selected_origin_date_unverified"
            else -> "none"
        }
        val shape = result.orgPageShapeReceipt?.take(1800)?.ifBlank { "none" } ?: "none"
        return "source=$source train=$train originDay=$originDay " +
            "transport=$transport bytes=${result.responseBytes} sha256_16=${result.responseDigest ?: "none"} " +
            "parsed=$parsed orgContentParsed=${result.orgContentParsed} runIdentity=$identity " +
            "primaryActuals=0 primaryMarker=none " +
            "siteRows=${result.orgReport?.rows?.size ?: 0} detachedSiteClaims=${if (detached?.event != null) 1 else 0} " +
            "siteCards=${result.orgSiteCards.size} siteDisplayStops=${result.orgSiteClocks.size} " +
            "siteClaimStation=${result.orgReport?.anchorCode ?: detached?.event?.stationCode ?: "unknown"} " +
            "siteClaim=$siteClaim siteClaimKind=$siteClaimKind " +
            "siteRoute=$siteRoute sitePageEndpoints=$pageEndpoints " +
            "sitePageLabels=$pageEndpointLabels selectedRoute=$selectedRoute " +
            "siteCardContainers=${result.orgParsedCardContainers} siteDateMatched=${result.orgRunDateMatched} " +
            "sitePageRunDate=${result.orgPageRunDate ?: "unknown"} " +
            "siteShape=$shape liveIdentityRouteMatch=${detached != null} " +
            "siteClaimTimeBasis=$claimTimeBasis " +
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
