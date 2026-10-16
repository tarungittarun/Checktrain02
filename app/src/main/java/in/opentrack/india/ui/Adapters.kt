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
    private val detailOverrides = mutableMapOf<String, Boolean>()
    private var routePoints: Map<String, List<StopRow>> = emptyMap()
    private var projectedLeg: RouteBetween.LegProjection? = null
    var sourceNameForMarker: String = "NTES"
        set(value) {
            if (field != value) { field = value; if (projectedLeg != null) notifyDataSetChanged() }
        }
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
        val intermediateScroll: android.widget.ScrollView = view.findViewById(R.id.intermediateScroll)
        val inlineLegGroup: LinearLayout = view.findViewById(R.id.inlineLegGroup)
        val inlineLeg: InlineLegView = view.findViewById(R.id.inlineLeg)
        val inlineLegCaption: TextView = view.findViewById(R.id.inlineLegCaption)
        val inlineLegAge: TextView = view.findViewById(R.id.inlineLegAge)
        val routeToggle: TextView = view.findViewById(R.id.routeToggle)
        val details: LinearLayout = view.findViewById(R.id.stopDetails)
        val primaryTime: TextView = view.findViewById(R.id.stopPrimaryTime)
        val timeType: TextView = view.findViewById(R.id.stopTimeType)
        val stopToggle: TextView = view.findViewById(R.id.stopToggle)
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
            if (s.showKm) append(" · ").append(s.kmFromOrigin).append(" km")
            if (s.isBoarding) append(" · ").append(ctx.getString(R.string.tag_board))
            else if (s.isAlighting) append(" · ").append(ctx.getString(R.string.tag_alight))
            s.platform?.takeIf { it.isNotBlank() }?.let {
                append(if (s.source == TrackModel.MinuteSource.SITE_REPORTED) " · site pf "
                    else " · feed pf ").append(it)
            }
        }
        val marker = haltMarker?.takeIf { it.code.equals(s.code, true) }
        holder.marker.visibility = if (marker == null) View.GONE else View.VISIBLE
        holder.marker.text = marker?.let {
            if (projectedLeg?.fromCode.equals(s.code, true)) "LAST REPORTED STOP"
            else "🚆  ${it.caption}"
        }.orEmpty()
        holder.marker.contentDescription = marker?.caption ?: ""
        holder.marker.setTextColor(ctx.getColor(R.color.live))
        val verified = s.source == TrackModel.MinuteSource.REPORTED
        val website = s.source == TrackModel.MinuteSource.SITE_REPORTED
        val stationClaim = reports[s.code]?.startsWith("RailYatri root claims") == true
        holder.status.text = when {
            website && s.showDep != null -> "Departed station · unverified"
            website -> "Arrival claim · unverified"
            stationClaim -> "At station claimed · time unknown"
            verified && s.isAnchor && s.showDep != null -> "Departed station · later position unknown"
            verified && s.showDep != null -> "Departed · reported"
            verified && s.isAnchor && s.state == TrackModel.State.PASSED -> "Departed · time unknown"
            verified && s.showArr != null -> "Arrived at station · departure unknown"
            s.state == TrackModel.State.PASSED -> "Past on schedule · actual unknown"
            s.state == TrackModel.State.HERE -> "At stop on schedule · unconfirmed"
            s.state == TrackModel.State.NEXT && s.source == TrackModel.MinuteSource.PROJECTED ->
                "Next · estimated, not confirmed"
            s.state == TrackModel.State.NEXT -> "Next · schedule only"
            s.source == TrackModel.MinuteSource.PROJECTED -> "Estimated · not reported"
            else -> "Scheduled"
        }
        holder.status.visibility = if (s.isAnchor || s.state == TrackModel.State.NEXT ||
            s.state == TrackModel.State.CANCELLED || s.state == TrackModel.State.SKIPPED ||
            stationClaim || website || (detailOverrides[s.code] == true)) View.VISIBLE else View.GONE
        holder.status.setTextColor(ctx.getColor(when {
            stationClaim || website || verified -> R.color.live
            s.source == TrackModel.MinuteSource.PROJECTED -> R.color.warn
            else -> R.color.text3
        }))
        holder.report.text = reports[s.code].orEmpty()
        holder.report.visibility = if (!holder.report.text.isNullOrBlank() &&
            (detailOverrides[s.code] ?: s.isAnchor)) View.VISIBLE else View.GONE
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
            s.source == TrackModel.MinuteSource.PROJECTED -> R.color.warn
            else -> R.color.live
        }
        holder.liveArr.setTextColor(ctx.getColor(color))
        holder.liveDep.setTextColor(ctx.getColor(color))
        // One time is legible at a glance; opening a stop reveals both fully labelled
        // arrival/departure comparisons. A projected or site minute is never an Act.
        val hasShownTime = s.source != TrackModel.MinuteSource.SCHEDULE &&
            (s.showArr != null || s.showDep != null)
        val primary = if (hasShownTime) s.showDep ?: s.showArr
            else s.schedArr ?: s.schedDep
        holder.primaryTime.text = primary?.let(RailTime::formatRailMinute) ?: "—"
        holder.timeType.text = when {
            !hasShownTime -> "SCHEDULE"
            s.source == TrackModel.MinuteSource.PROJECTED -> "ESTIMATE"
            website -> "SITE CLAIM"
            s.showDep != null -> "ACTUAL DEP"
            else -> "ACTUAL ARR"
        }
        holder.primaryTime.setTextColor(ctx.getColor(if (hasShownTime) color else R.color.text2))
        holder.details.visibility = if (detailOverrides[s.code] ?: s.isAnchor) View.VISIBLE else View.GONE
        holder.stopToggle.text = if (routePoints[s.code].orEmpty().isNotEmpty())
            (if (expandedCode == s.code) "Less  ▴" else "Explore  ▾")
        else if (holder.details.visibility == View.VISIBLE) "Less  ▴" else "Details  ▾"
        holder.dot.setBackgroundResource(dotFor(s))
        // Keep the next stop directly after the compact leg. The former 34dp
        // projected-row spacer looked like missing content on a phone.
        val nextKm = currentList.getOrNull(position + 1)?.kmFromOrigin
        val betweenKm = nextKm?.takeIf { it >= 0 && s.kmFromOrigin >= 0 &&
            it >= s.kmFromOrigin }?.minus(s.kmFromOrigin)
        val screenDp = ctx.resources.displayMetrics.heightPixels / ctx.resources.displayMetrics.density
        val hasTrainLeg = projectedLeg?.fromCode.equals(s.code, true)
        val gapPx = betweenKm?.let {
            (LegGeometry.stopGapDp(it, screenDp) * ctx.resources.displayMetrics.density).toInt()
        } ?: 0
        holder.row.setPadding(holder.row.paddingLeft,
            ctx.resources.getDimensionPixelSize(R.dimen.gap_s),
            holder.row.paddingRight,
            ctx.resources.getDimensionPixelSize(R.dimen.gap_s) + if (hasTrainLeg || nextKm == null) 0 else gapPx)
        // The stop dot and its label already mark the reported halt. Full-width
        // row bands made this look like a data table, not a continuous route.
        holder.row.setBackgroundColor(0x00000000)
        val points = routePoints[s.code].orEmpty()
        val leg = projectedLeg?.takeIf { it.fromCode.equals(s.code, true) }
        holder.inlineLegGroup.visibility = if (leg == null) View.GONE else View.VISIBLE
        holder.inlineLeg.projection = leg
        // The disclosed point list already prints names and distances. Drawing the same
        // labels on the ruler duplicates content and squeezes the train's age badge.
        holder.inlineLeg.showLabels = false
        holder.inlineLeg.distanceKm = leg?.let { projection ->
            currentList.firstOrNull { it.code.equals(projection.toCode, true) }
                ?.let { (it.kmFromOrigin - s.kmFromOrigin).coerceAtLeast(0) }
        } ?: 0
        holder.inlineLeg.ageSeconds = if (leg?.markerKind != RouteBetween.MarkerKind.PROJECTED)
            legAgeSeconds else null
        if (leg != null) {
            val web = leg.markerKind == RouteBetween.MarkerKind.WEB_CACHE_CANDIDATE
            val site = leg.markerKind == RouteBetween.MarkerKind.SITE_INTERMEDIATE
            val siteHalt = leg.markerKind == RouteBetween.MarkerKind.SITE_LAST_STOP
            val reportedHalt = leg.markerKind == RouteBetween.MarkerKind.REPORTED_HALT
            val stationClaim = leg.markerKind == RouteBetween.MarkerKind.STATION_CLAIM
            val caption = when {
                stationClaim -> "Station claim · time unknown"
                leg.fraction > 0f -> "Between halts · motion unknown"
                else -> "Last stop · now unknown"
            }
            holder.inlineLegCaption.text = caption
            holder.inlineLegCaption.setTextColor(ctx.getColor(R.color.text2))
            holder.inlineLegAge.text = holder.inlineLeg.ageSeconds?.let { seconds ->
                if (seconds < 60) "Just now" else TableMarker.elapsedLabel(seconds * 1_000L) + " ago"
            }.orEmpty()
            // The age belongs beside the locomotive, not on a separate line near stop names.
            holder.inlineLegAge.visibility = View.GONE
            holder.inlineLegAge.contentDescription = if (holder.inlineLegAge.text.isEmpty()) ""
                else "Last reported event ${holder.inlineLegAge.text}"
            holder.routeToggle.text = if (expandedCode == s.code) "Hide  ▴" else
                "${leg.points.size} points  ▾"
            holder.routeToggle.contentDescription = if (expandedCode == s.code)
                "Hide intermediate map points" else "Show ${leg.points.size} intermediate map points"
            holder.routeToggle.visibility = if (leg.points.isEmpty()) View.GONE else View.VISIBLE
            holder.routeToggle.setOnClickListener {
                val previous = expandedCode
                expandedCode = if (previous == s.code) null else s.code
                if (previous != null) currentList.indexOfFirst { it.code == previous }
                    .takeIf { it >= 0 }?.let(::notifyItemChanged)
                currentList.indexOfFirst { it.code == s.code }
                    .takeIf { it >= 0 }?.let(::notifyItemChanged)
            }
            holder.inlineLeg.contentDescription = caption +
                (holder.inlineLeg.ageSeconds?.let { " · reported event ${LegGeometry.ageLabel(it)}" }
                    ?: " · event time unknown") +
                " · not GPS · ${leg.from} to ${leg.to} " +
                "${(leg.fraction * 100).toInt()} percent of scheduled leg by " +
                (if (stationClaim) "unverified station claim (no passage time or measured distance)"
                    else if (reportedHalt || siteHalt) "last reported halt (no measured distance since)"
                    else if (site) "static map of the site-reported station"
                    else if (web) "web-cache km claim" else "estimated distance") +
                (if (leg.points.isEmpty()) "" else " · static points in order: " +
                    leg.points.joinToString(" → ") { it.code })
        }
        holder.row.setOnClickListener {
            val opening = if (points.isNotEmpty()) expandedCode != s.code
                else holder.details.visibility != View.VISIBLE
            detailOverrides[s.code] = opening
            // A stop is also the disclosure control for its map points. Closing
            // it removes both the long point list and the detailed time sheet;
            // the next scheduled halt remains immediately below the mini-leg.
            if (points.isNotEmpty()) expandedCode = if (opening) s.code else null
            notifyItemChanged(holder.bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION }
                ?: position)
        }
        holder.row.isClickable = true
        holder.row.contentDescription = "${s.name}, ${holder.status.text}, ${holder.timeType.text} " +
            "${holder.primaryTime.text}; tap to " +
            (if (points.isNotEmpty())
                (if (expandedCode == s.code) "hide" else "show") + " arrival, departure and map points"
            else (if (holder.details.visibility == View.VISIBLE) "hide" else "show") +
                " arrival and departure times")
        holder.intermediateGroup.removeAllViews()
        holder.intermediateScroll.visibility = if (points.isNotEmpty() && expandedCode == s.code)
            View.VISIBLE else View.GONE
        holder.intermediateScroll.layoutParams = holder.intermediateScroll.layoutParams.apply {
            height = if (points.size > 5) (136 * ctx.resources.displayMetrics.density).toInt()
                else android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        }
        if (holder.intermediateScroll.visibility == View.VISIBLE) {
            holder.intermediateGroup.addView(TextView(ctx).apply {
                text = "MAP POINTS · NO STOP TIMES"
                setTextColor(ctx.getColor(R.color.text3))
                textSize = 10f
                setPadding(0, 6, 0, 6)
            })
            for (point in points) {
                holder.intermediateGroup.addView(TextView(ctx).apply {
                    // Static map points are not evidence that a train stopped or passed here.
                    text = "• ${RowText.of(point.name)}" +
                        (if (point.schedKm >= 0) " · ${point.schedKm} km" else " · km unknown")
                    setTextColor(ctx.getColor(R.color.text2))
                    textSize = 11f
                    val pad = (3 * ctx.resources.displayMetrics.density).toInt()
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

    /** A source does not recolour the route. The row's Site/Act wording and the
     * hero convey confidence; identical reported stop states get identical dots. */
    private fun hasStopEvent(s: TrackModel.Stop): Boolean =
        s.source == TrackModel.MinuteSource.REPORTED ||
            s.source == TrackModel.MinuteSource.SITE_REPORTED

    internal fun dotFor(s: TrackModel.Stop): Int = when (s.state) {
        // An arrival without departure is an at-station report, not a passed
        // halt. Give it the ring used for HERE on both NTES and the website.
        TrackModel.State.HERE -> if (hasStopEvent(s))
            R.drawable.dot_here else R.drawable.dot_later
        TrackModel.State.PASSED -> if (hasStopEvent(s))
            R.drawable.dot_passed else R.drawable.dot_later
        TrackModel.State.CANCELLED, TrackModel.State.SKIPPED -> R.drawable.dot_problem
        else -> R.drawable.dot_later
    }

    private fun colorFor(s: TrackModel.Stop): Int = when {
        s.state == TrackModel.State.CANCELLED || s.state == TrackModel.State.SKIPPED -> R.color.late
        s.state == TrackModel.State.HERE && hasStopEvent(s) &&
            s.showArr != null && s.showDep == null -> R.color.warn
        s.state == TrackModel.State.HERE && hasStopEvent(s) -> R.color.accent
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
