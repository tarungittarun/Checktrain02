package `in`.opentrack.india.ui

/** A one-line check receipt. Full evidence stays in TalkBack, Info and the diary.
 * Never use the page-check clock to redate a station event. */
object RefreshReceiptUi {
    fun short(source: String, checked: String, change: RefreshEvidence.Change,
              detail: String, cached: Boolean): String {
        val result = when {
            cached -> "cached reply · no new event"
            detail.contains("failed", true) || detail.contains("no usable", true) -> "check failed · last report retained"
            detail.contains("no validated", true) -> "no validated stop"
            change == RefreshEvidence.Change.NEW_STOP -> "new stop reported"
            change == RefreshEvidence.Change.REVISED_AT_SAME_STOP -> "stop time revised"
            else -> "no newer stop"
        }
        return "Checked $checked · $source · $result"
    }
}
