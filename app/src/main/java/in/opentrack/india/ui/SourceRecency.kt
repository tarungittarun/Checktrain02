package `in`.opentrack.india.ui

/** A site update is not a passage event. Only a newly past-marked stop farther
 * along the same dated official route earns a comparison hint, never a location
 * change or an automatic promotion to railway-confirmed actuals. */
object SourceRecency {
    fun showComparison(officialIndex: Int, siteIndex: Int, officialAgeMs: Long,
                       pageAgeMs: Long, siteEventAgeMs: Long): Boolean =
        officialIndex >= 0 && siteIndex > officialIndex && officialAgeMs >= 300_000L &&
            pageAgeMs in 0L..300_000L && siteEventAgeMs in 0L..300_000L

    /** Compare passage/event clocks, never either source's page/feed update stamp. */
    fun officialIsNewer(officialEventAgeMs: Long?, siteEventAgeMs: Long?): Boolean =
        officialEventAgeMs != null && siteEventAgeMs != null &&
            officialEventAgeMs >= 0L && siteEventAgeMs >= 0L &&
            officialEventAgeMs + 120_000L < siteEventAgeMs
}
