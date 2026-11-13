package `in`.opentrack.india.ui

import `in`.opentrack.india.v2.mirror.PublicPageChecks

/** HTTP, cache and admitted movement are separate diagnostic statements.
 * No body, URL query, cookie or device identity is written to the diary. */
object SourceEvidenceReceipt {
    fun publicPage(source: String, train: String, originDay: Long,
        result: PublicPageChecks.Result): String {
        val transport = when {
            result.fromHttpCache -> "CACHED_BODY"
            result.pageAnswered -> "NETWORK_ANSWER"
            else -> "FAILED_OR_UNUSABLE"
        }
        val parsed = when {
            result.orgReport != null -> "SITE_COMPARISON"
            result.orgSiteCards.isNotEmpty() -> "SITE_CARDS_ONLY"
            else -> "NO_DATED_EVENT"
        }
        val identity = when {
            result.orgReport?.originInferred == true -> "INFERRED"
            result.orgSiteCards.isNotEmpty() -> "INFERRED_FOR_PAGE_DISPLAY_ONLY"
            else -> "UNVERIFIED"
        }
        val siteClaim = result.orgReport?.let { it.anchorCode + ":" + it.eventMinute } ?: "none"
        return "source=$source train=$train originDay=$originDay " +
            "transport=$transport bytes=${result.responseBytes} sha256_16=${result.responseDigest ?: "none"} " +
            "parsed=$parsed runIdentity=$identity primaryActuals=0 primaryMarker=none " +
            "siteRows=${result.orgReport?.rows?.size ?: 0} siteCards=${result.orgSiteCards.size} " +
            "siteDisplayStops=${result.orgSiteClocks.size} siteClaim=$siteClaim " +
            "pagePublishedAt=${result.orgReport?.pageUpdatedAt ?: result.orgSitePageUpdatedAt ?: "unknown"} " +
            "pageClockHasSeconds=${result.orgSitePageClockHasSeconds} checkedAt=${result.checkedAt}"
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
