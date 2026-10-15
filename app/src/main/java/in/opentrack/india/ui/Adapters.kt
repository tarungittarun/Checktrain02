package `in`.opentrack.india.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.LinearLayout
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import `in`.opentrack.india.R
import `in`.opentrack.india.core.RailTime
import `in`.opentrack.india.domain.RouteBetween
import `in`.opentrack.india.domain.Suggestion
import `in`.opentrack.india.domain.TitleCase
import `in`.opentrack.india.domain.TrackModel
import `in`.opentrack.india.model.StopRow
import `in`.opentrack.india.model.StationHit
import `in`.opentrack.india.model.TrainHit
import `in`.opentrack.india.model.TrainSummary

/**
 * Search results. Two rows of information per hit and nothing else — the decision a traveller makes
 * here is "does this train work for me", which needs train identity, times, duration and whether it
 * actually runs on the date they picked.
 *
 * The right-hand tag is the one thing a printed timetable cannot tell you: how long until it goes. It
 * is computed from the same rail-minute arithmetic the search used, so it cannot disagree with the times
 * beside it.
 */
class TrainHitAdapter(private val onClick: (TrainHit) -> Unit) :
    ListAdapter<TrainHit, TrainHitAdapter.Holder>(DIFF) {

    /** Minute-of-IST-epoch for "now", carried by the ViewModel. UI never reads the wall clock itself. */
    var nowMinute: Long = 0L

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val dep: TextView = view.findViewById(R.id.hitDep)
        val arr: TextView = view.findViewById(R.id.hitArr)
        val no: TextView = view.findViewById(R.id.hitNo)
        val name: TextView = view.findViewById(R.id.hitName)
        val meta: TextView = view.findViewById(R.id.hitMeta)
        val tag: TextView = view.findViewById(R.id.hitTag)
        val go: TextView = view.findViewById(R.id.hitGo)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_train_hit, parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val hit = getItem(position)
        val ctx = holder.itemView.context
        holder.dep.text = RailTime.formatRailMinuteBare(hit.boardMinute)
        holder.arr.text = RailTime.formatRailMinute(hit.alightMinute)
        holder.no.text = buildString {
            append(hit.trainNo)
            hit.trainType?.let { append(" · ").append(RowText.type(it)) }
        }
        holder.name.text = RowText.of(hit.trainName, keep = setOf(hit.fromCode, hit.toCode))
        holder.meta.text = buildString {
            append(hit.fromCode).append(" → ").append(hit.toCode)
            if (hit.distanceKm > 0) append(" · ").append(hit.distanceKm).append(" km")
            append(" · ").append(RailTime.formatDuration(hit.durationMin))
            if (RailTime.dayOffsetOf(hit.alightMinute) > RailTime.dayOffsetOf(hit.boardMinute)) {
                append(" · arrives next day")
            }
            // The provenance suffix rides the meta line: a row must say whether its time is the
            // timetable's or a live source's (v2 slice 1 - "offline never looks live").
            hit.sourceTag?.let { append(" · ").append(it) }
            // Only spell the running days out when they are not every day: "daily" under a daily train
            // is noise, and "Mon/Tue/Thu" is the note that saves somebody a wasted journey.
            hit.runDays?.let { if (it != 0x7F) append(" · ").append(RowText.dayNames(it)) }
        }
        val delta = hit.boardEpochMinute - nowMinute
        holder.tag.text = TrackModel.formatGap(delta.toInt())
        holder.tag.setBackgroundResource(
            when {
                delta in -20L..0L -> R.drawable.bg_badge_problem
                delta > 0 && delta <= 180 -> R.drawable.bg_pill_accent
                else -> R.drawable.bg_pill
            }
        )
        holder.tag.setTextColor(ctx.getColor(
            when {
                delta in -20L..0L -> R.color.late
                delta in 1L..180L -> R.color.accent
                else -> R.color.text2
            }
        ))
        holder.itemView.setOnClickListener { onClick(hit) }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<TrainHit>() {
            override fun areItemsTheSame(a: TrainHit, b: TrainHit) =
                a.trainNo == b.trainNo && a.originDateEpochDay == b.originDateEpochDay &&
                    a.fromCode == b.fromCode && a.toCode == b.toCode

            override fun areContentsTheSame(a: TrainHit, b: TrainHit) = a == b
        }
    }
}

/**
 * The route table. Every string on a row comes from [TrackModel.Stop]; the adapter adds no facts, which
 * is what keeps the sentence at the top of the screen and the row it describes in agreement.
 *
 * Per row: scheduled arrival/departure in the dim column, live-or-expected in the bright one, and a tag
 * naming which of the two the bright column is. A projected minute and a confirmed minute have to be
 * distinguishable at a glance — that distinction is the whole reason a passenger opens this app instead
 * of a timetable PDF.
 */
class StopAdapter : ListAdapter<TrackModel.Stop, StopAdapter.Holder>(DIFF) {

    private var expandedCode: String? = null
    private var routePoints: Map<String, List<StopRow>> = emptyMap()
    private var projectedLeg: RouteBetween.LegProjection? = null
    var legAgeSeconds: Long? = null
        set(value) {
            if (field?.div(60) != value?.div(60)) {
                field = value
                if (projectedLeg != null) notifyDataSetChanged()
            } else field = value
        }

    fun setProjectedLeg(leg: RouteBetween.LegProjection?) {
        if (projectedLeg == leg) return
        projectedLeg = leg
        notifyDataSetChanged()
    }

    /** A group is attached to the preceding scheduled halt. No point becomes a timed halt. */
    fun setRoutePoints(groups: Map<String, List<StopRow>>) {
        if (routePoints == groups) return
        routePoints = groups
        if (expandedCode?.let { it !in groups } == true) expandedCode = null
        notifyDataSetChanged()
    }

    /** The halt the mirror's actuals say the train is at or making for - tinted, never silent. */
    var haltMarker: TableMarker.Halt? = null
        set(value) {
            if (field != value) {
                field = value
                notifyDataSetChanged()
            }
        }

    var highlightCode: String? = null
        set(value) {
            if (field != value) {
                field = value
                notifyDataSetChanged() // the timetable rows themselves may be unchanged
            }
        }

    /** Per-stop source annotations, including manual uncorroborated candidates. A source
     * selection can leave the row objects unchanged, so explicitly rebind visible rows. */
    var reports: Map<String, String> = emptyMap()
        set(value) {
            if (field != value) {
                field = value
                notifyDataSetChanged()
            }
        }

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val row: View = view.findViewById(R.id.stopRow)
        val intermediateGroup: LinearLayout = view.findViewById(R.id.intermediateGroup)
        val inlineLegGroup: LinearLayout = view.findViewById(R.id.inlineLegGroup)
        val inlineLeg: InlineLegView = view.findViewById(R.id.inlineLeg)
        val inlineLegCaption: TextView = view.findViewById(R.id.inlineLegCaption)
        val dot: View = view.findViewById(R.id.stopDot)
        val marker: TextView = view.findViewById(R.id.haltMarker)
        val name: TextView = view.findViewById(R.id.stopName)
        val meta: TextView = view.findViewById(R.id.stopMeta)
        val status: TextView = view.findViewById(R.id.stopStatus)
        val report: TextView = view.findViewById(R.id.stopReport)
        val schedArr: TextView = view.findViewById(R.id.schedArr)
        val schedDep: TextView = view.findViewById(R.id.schedDep)
        val liveArr: TextView = view.findViewById(R.id.liveArr)
        val liveDep: TextView = view.findViewById(R.id.liveDep)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_stop, parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val s = getItem(position)
        val ctx = holder.itemView.context
        holder.name.text = RowText.of(s.name)
        holder.name.setTextColor(ctx.getColor(colorFor(s)))
        holder.meta.text = buildString {
            append(s.code)
            if (s.showKm) append(" · Sch ").append(s.kmFromOrigin).append(" km")
            if (s.isBoarding) append(" · ").append(ctx.getString(R.string.tag_board))
            else if (s.isAlighting) append(" · ").append(ctx.getString(R.string.tag_alight))
            s.platform?.takeIf { it.isNotBlank() }?.let {
                append(if (s.source == TrackModel.MinuteSource.SITE_REPORTED) " · site pf "
                    else " · feed pf ").append(it)
            }
        }
        val marker = haltMarker?.takeIf { it.code.equals(s.code, true) }
        holder.marker.visibility = if (marker == null) View.GONE else View.VISIBLE
        holder.marker.text = marker?.let { "🚆 ${it.caption}" }.orEmpty()
        holder.marker.contentDescription = marker?.caption ?: ""
        holder.marker.setTextColor(ctx.getColor(if (marker?.website == true) R.color.warn else R.color.live))
        val verified = s.source == TrackModel.MinuteSource.REPORTED
        val website = s.source == TrackModel.MinuteSource.SITE_REPORTED
        val stationClaim = reports[s.code]?.startsWith("RailYatri root claims") == true
        holder.status.text = when {
            website -> "SITE REPORT · unverified"
            stationClaim -> "RAILYATRI STATION CLAIM · passage time unknown"
            verified && s.showDep != null -> "LEFT · departure reported"
            // A separate provider departed flag may be present without an ATD minute.
            // Do not contradict the headline, and NEVER fill Act with scheduled departure.
            verified && s.isAnchor && s.state == TrackModel.State.PASSED ->
                "LEFT · departure flagged; minute not supplied"
            verified && s.showArr != null -> "ARRIVED · departure not reported"
            s.state == TrackModel.State.PASSED -> "TIMETABLE PAST · actual unknown"
            s.state == TrackModel.State.HERE -> "TIMETABLE AT STOP · train unconfirmed"
            s.state == TrackModel.State.NEXT && s.source == TrackModel.MinuteSource.PROJECTED ->
                "NEXT · estimated, arrival unconfirmed"
            s.state == TrackModel.State.NEXT -> "NEXT ON TIMETABLE · not live"
            s.source == TrackModel.MinuteSource.PROJECTED -> "ESTIMATE · not reported"
            else -> "SCHEDULED · not reported"
        }
        holder.status.setTextColor(ctx.getColor(when {
            stationClaim || website -> R.color.warn
            verified -> R.color.live
            s.source == TrackModel.MinuteSource.PROJECTED -> R.color.warn
            else -> R.color.text3
        }))
        holder.report.text = reports[s.code].orEmpty()
        holder.report.visibility = if (holder.report.text.isNullOrBlank()) View.GONE else View.VISIBLE
        // The owner's arrangement (round 45): each time column stacks SCHEDULED above ACTUAL/EXPECTED,
        // every minute prefixed so the two kinds can never be confused. A late actual burns red - and it
        // sits BELOW the schedule, which is the whole point: the eye compares them at one glance.
        fun sch(minute: Int?): String = "Sch " + (minute?.let { RailTime.formatRailMinute(it) } ?: "—")
        holder.schedArr.text = sch(s.schedArr)
        holder.schedDep.text = sch(s.schedDep)
        val reported = s.source != TrackModel.MinuteSource.SCHEDULE
        val late: Boolean = (s.delay ?: 0) >= 3
        fun act(prefix: String, minute: Int?): String =
            minute?.let { "$prefix ${RailTime.formatRailMinute(it)}" } ?: "—"
        holder.liveArr.text = when {
            !reported -> "—"
            s.source == TrackModel.MinuteSource.PROJECTED -> act("Est", s.showArr)
            website -> act("Site", s.showArr)
            else -> act("Act", s.showArr)
        }
        holder.liveDep.text = when {
            !reported -> "—"
            s.source == TrackModel.MinuteSource.PROJECTED -> act("Est", s.showDep)
            website -> act("Site", s.showDep)
            else -> act("Act", s.showDep)
        }
        // Late = red (the colour a traveller scans for); on-time actual = green; estimate = amber.
        // A pure-timetable row stays grey in both - no number pretends to be news.
        val color = when {
            !reported -> R.color.text2
            s.source == TrackModel.MinuteSource.PROJECTED || website -> R.color.warn
            late -> R.color.late
            else -> R.color.live
        }
        holder.liveArr.setTextColor(ctx.getColor(color))
        holder.liveDep.setTextColor(ctx.getColor(color))
        holder.dot.setBackgroundResource(dotFor(s))
        holder.row.setPadding(holder.row.paddingLeft,
            if (s.state == TrackModel.State.NEXT && s.source == TrackModel.MinuteSource.PROJECTED)
                ctx.resources.getDimensionPixelSize(R.dimen.row_gap_running)
            else ctx.resources.getDimensionPixelSize(R.dimen.gap_s),
            holder.row.paddingRight, holder.row.paddingBottom)
        holder.row.setBackgroundColor(
            when {
                s.state == TrackModel.State.HERE && verified && s.showArr != null && s.showDep == null ->
                    0x30FFC107.toInt()
                s.code.equals(highlightCode, true) -> 0x30FFC107.toInt() // amber: last reported halt or an explicitly unverified source claim
                s.isAnchor -> 0x2437C978.toInt()
                s.isBoarding || s.isAlighting -> 0x0AFFFFFF
                else -> 0x00000000
            }
        )
        val points = routePoints[s.code].orEmpty()
        val leg = projectedLeg?.takeIf { it.fromCode.equals(s.code, true) }
        holder.inlineLegGroup.visibility = if (leg == null) View.GONE else View.VISIBLE
        holder.inlineLeg.projection = leg
        holder.inlineLeg.ageSeconds = if (leg?.markerKind != RouteBetween.MarkerKind.PROJECTED)
            legAgeSeconds else null
        if (leg != null) {
            val web = leg.markerKind == RouteBetween.MarkerKind.WEB_CACHE_CANDIDATE
            val site = leg.markerKind == RouteBetween.MarkerKind.SITE_INTERMEDIATE
            val siteHalt = leg.markerKind == RouteBetween.MarkerKind.SITE_LAST_STOP
            val caption = when {
                siteHalt -> "Website last reported ${leg.from}; between-stop position unknown · not GPS"
                site -> "Website reports reached ${leg.nearestPoint} · map position at station, not GPS"
                web -> "WIMT web cache · distance-scaled candidate · unverified, not phone-app GPS"
                else -> "Projected between ${leg.from} and ${leg.to} · not GPS"
            }
            holder.inlineLegCaption.text = caption
            holder.inlineLeg.contentDescription = caption + " · ${leg.from} to ${leg.to} " +
                "${(leg.fraction * 100).toInt()} percent of scheduled leg by " +
                (if (siteHalt) "last reported halt (no measured distance since)"
                    else if (site) "static map of the site-reported station"
                    else if (web) "web-cache km claim" else "estimated distance")
        }
        holder.row.setOnClickListener(if (points.isEmpty()) null else View.OnClickListener {
            val previous = expandedCode
            expandedCode = if (previous == s.code) null else s.code
            if (previous != null) currentList.indexOfFirst { it.code == previous }
                .takeIf { it >= 0 }?.let(::notifyItemChanged)
            currentList.indexOfFirst { it.code == s.code }
                .takeIf { it >= 0 }?.let(::notifyItemChanged)
        })
        holder.row.isClickable = points.isNotEmpty()
        holder.row.contentDescription = if (points.isNotEmpty())
            "${s.name}, ${points.size} route points to next scheduled halt, tap to " +
                (if (expandedCode == s.code) "collapse" else "expand") else s.name
        holder.status.text = holder.status.text.toString() + if (points.isNotEmpty())
            " · ${points.size} route points · tap to ${if (expandedCode == s.code) "hide" else "show"}" else ""
        holder.intermediateGroup.removeAllViews()
        holder.intermediateGroup.visibility = if (points.isNotEmpty() && expandedCode == s.code)
            View.VISIBLE else View.GONE
        if (holder.intermediateGroup.visibility == View.VISIBLE) {
            for (point in points) {
                holder.intermediateGroup.addView(TextView(ctx).apply {
                    // Static map points are not evidence that a train stopped or passed here.
                    text = "• ${RowText.of(point.name)}" +
                        (if (point.schedKm >= 0) " · ${point.schedKm} km" else " · distance unknown")
                    setTextColor(ctx.getColor(if (point.isHalt) R.color.text1 else R.color.text2))
                    textSize = 13f
                    val pad = (5 * ctx.resources.displayMetrics.density).toInt()
                    setPadding(0, pad, 0, pad)
                })
            }
        }
    }

    /**
     * One short word under the live column, naming what that column holds.
     *
     * The rule the user asked for is exactly this: a stop the train has covered is scheduled *and*
     * actual, a stop still ahead is scheduled *and* expected. Colour separates them in the columns
     * themselves, which is the fast read; the word is the slow read, and it is what makes a screenshot
     * or a colour-blind passenger unambiguous. "no report" and "timetable" are spelled out rather than
     * left blank because a passed stop with no actual minute, and a screen with no feed at all, are
     * statements about the data — and a dash reads like the app gave up.
     */
    private fun tagFor(s: TrackModel.Stop, ctx: android.content.Context): String = when {
        s.state == TrackModel.State.CANCELLED -> ctx.getString(R.string.tag_cancelled)
        s.state == TrackModel.State.SKIPPED -> ctx.getString(R.string.tag_skipped)
        s.state == TrackModel.State.HERE -> ctx.getString(R.string.tag_here)
        // The one row where counting down beats labelling, and the header repeats it anyway.
        s.state == TrackModel.State.NEXT ->
            s.minutesFromNow?.let { TrackModel.formatGap(it) } ?: ctx.getString(R.string.tag_due)
        s.showArr == null && s.showDep == null -> ctx.getString(R.string.tag_no_report)
        s.source == TrackModel.MinuteSource.PROJECTED -> ctx.getString(R.string.tag_expected)
        s.source == TrackModel.MinuteSource.SITE_REPORTED -> "Site report"
        s.source == TrackModel.MinuteSource.SCHEDULE -> ctx.getString(R.string.tag_timetable)
        else -> ctx.getString(R.string.tag_actual)
    }

    private fun tagColorFor(s: TrackModel.Stop): Int = when {
        s.state == TrackModel.State.HERE && s.source == TrackModel.MinuteSource.REPORTED &&
            s.showArr != null && s.showDep == null -> R.color.warn
        s.state == TrackModel.State.HERE && s.source == TrackModel.MinuteSource.REPORTED -> R.color.accent
        s.state == TrackModel.State.CANCELLED || s.state == TrackModel.State.SKIPPED -> R.color.late
        s.state == TrackModel.State.NEXT -> R.color.text1
        s.source == TrackModel.MinuteSource.PROJECTED ||
            s.source == TrackModel.MinuteSource.SITE_REPORTED -> R.color.warn
        s.source == TrackModel.MinuteSource.SCHEDULE -> R.color.text3
        s.showArr != null || s.showDep != null -> R.color.text2
        else -> R.color.text3
    }

    private fun dotFor(s: TrackModel.Stop): Int = when (s.state) {
        TrackModel.State.HERE -> if (s.source == TrackModel.MinuteSource.REPORTED &&
            !(s.showArr != null && s.showDep == null))
            R.drawable.dot_here else R.drawable.dot_later
        TrackModel.State.PASSED -> if (s.source == TrackModel.MinuteSource.REPORTED)
            R.drawable.dot_passed else R.drawable.dot_later
        TrackModel.State.CANCELLED, TrackModel.State.SKIPPED -> R.drawable.dot_problem
        else -> R.drawable.dot_later
    }

    private fun colorFor(s: TrackModel.Stop): Int = when {
        s.state == TrackModel.State.CANCELLED || s.state == TrackModel.State.SKIPPED -> R.color.late
        s.state == TrackModel.State.HERE && s.source == TrackModel.MinuteSource.REPORTED &&
            s.showArr != null && s.showDep == null -> R.color.warn
        s.state == TrackModel.State.HERE && s.source == TrackModel.MinuteSource.REPORTED -> R.color.accent
        s.source == TrackModel.MinuteSource.SCHEDULE -> R.color.text2
        s.state == TrackModel.State.LATER -> R.color.text2
        else -> R.color.text1
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<TrackModel.Stop>() {
            override fun areItemsTheSame(a: TrackModel.Stop, b: TrackModel.Stop) = a.seq == b.seq
            override fun areContentsTheSame(a: TrackModel.Stop, b: TrackModel.Stop) = a == b
        }
    }
}

/**
 * Name casing and the small vocabularies the rows print.
 *
 * Named for what it does to a row rather than for the casing rule it delegates to: `TitleCase` is a
 * top-level object in `domain`, and a second type of that name in the same package as `Suggestion` once
 * made the pair unreadable (the checker flagged it the moment they coexisted).
 */
object RowText {
    fun of(raw: String, keep: Set<String> = emptySet()): String = TitleCase.of(raw, keep)

    fun rows(hits: List<StationHit>): List<Suggestion> = hits.map { Suggestion.ofStation(it) }
    fun trainRows(hits: List<TrainSummary>): List<Suggestion> = hits.map { Suggestion.ofTrain(it) }

    /** "SUPERFAST_EXPRESS" -> "Superfast Exp"; the abbreviations timetables use, kept short. */
    fun type(raw: String): String {
        val t = of(raw)
        return when {
            t.equals("Superfast Express", true) -> "SF Exp"
            t.equals("Mail/Express", true) -> "Exp"
            else -> if (t.length > 12) t.substringBefore(' ') else t
        }
    }

    fun dayNames(mask: Int): String {
        val names = arrayOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
        val bits = ArrayList<String>(7)
        for (i in 0..6) if (mask and (1 shl (6 - i)) != 0) bits += names[i]
        return when (bits.size) {
            7 -> "daily"
            0 -> "not scheduled"
            else -> bits.joinToString("/")
        }
    }
}
