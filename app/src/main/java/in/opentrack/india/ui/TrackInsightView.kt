package `in`.opentrack.india.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import `in`.opentrack.india.core.RailTime
import `in`.opentrack.india.model.StopRow
import `in`.opentrack.india.model.TrainPosition
import kotlin.math.max
import `in`.opentrack.india.model.SourceKind

/**
 * The screen's main object: a time–distance plot of one run, with the schedule and the live position
 * drawn against each other.
 *
 * Why a hand-drawn view instead of a map: the traveller's actual question is "is it late, where is it
 * relative to where it should be, and how long until my station" — that is a time/distance relation,
 * not a geography question. A map costs a 3 MB SDK, a tile provider account, network use in a tunnel,
 * and still cannot show "14 min late at RTM". A `Canvas` costs nothing, works offline, and shows the
 * comparison literally: the gap between the two lines *is* the delay.
 *
 * Everything it draws comes from [stops] + [position]; it holds no state of its own, so the view is
 * trivially re-renderable on each refresh and the "held/backwards" rules in PositionEngine are the
 * single source of truth for movement.
 *
 * Coordinates: x = minutes (of the visible window), y = km along the route, origin top-left with the
 * boarding station at the top, so reading the chart top-to-bottom matches reading the journey forward.
 */
class TrackInsightView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0,
) : View(context, attrs, defStyle) {

    /** Visible window in rail-minutes; auto-fitted when unset. */
    private var windowStartMin: Int? = null
    private var windowEndMin: Int? = null
    private var stops: List<StopRow> = emptyList()
    private var position: TrainPosition? = null
    private var totalKm: Int = 1
    private var boardingSeq: Int = 0
    private var alightingSeq: Int = Int.MAX_VALUE
    private var showDelayShade = true
    /** "now" on the same rail-minute axis as the stops; supplied by the caller, never read here. */
    private var nowRailMinute: Int? = null

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1f; color = COLOR_GRID
    }
    private val schedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 3f; color = COLOR_SCHEDULE
    }
    private val livePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 5f; color = COLOR_LIVE; strokeCap = Paint.Cap.ROUND
    }
    private val futurePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 4f; color = COLOR_LIVE; alpha = 140
        pathEffect = DashPathEffect(floatArrayOf(9f, 7f), 0f)
    }
    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = COLOR_LIVE }
    private val markerHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 3f; color = COLOR_LIVE; alpha = 110
    }
    private val stopPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = COLOR_STOP; strokeWidth = 2f }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_TEXT; textSize = 22f
    }
    private val smallTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_TEXT_DIM; textSize = 18f
    }
    private val shadePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = COLOR_SHADE }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL; color = COLOR_LIVE; alpha = 26
    }
    private val path = Path()
    private val rect = RectF()

    fun setJourney(
        stops: List<StopRow>, position: TrainPosition?, totalKm: Int,
        boardingSeq: Int, alightingSeq: Int, nowRailMinute: Int? = null,
    ) {
        this.nowRailMinute = nowRailMinute
        this.stops = stops.filter { it.seq in boardingSeq..alightingSeq }
        this.position = position
        this.totalKm = max(1, totalKm)
        this.boardingSeq = boardingSeq
        this.alightingSeq = alightingSeq
        this.showDelayShade = position?.delayMinutes?.let { kotlin.math.abs(it) >= 3 } ?: false
        invalidate()
    }

    /** Exposed so the stop list can highlight the row that matches the drawn marker. */
    var currentSeq: Int = -1

    /** Manual zoom: a 40-hour cross-country run is unreadable unless the window can be narrowed. */
    fun zoomTo(startMin: Int?, endMin: Int?) {
        windowStartMin = startMin
        windowEndMin = endMin
        invalidate()
    }

    fun fitToJourney() {
        windowStartMin = null
        windowEndMin = null
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val mode = MeasureSpec.getMode(heightMeasureSpec)
        val desired = (w * 0.72f).toInt().coerceIn(MIN_HEIGHT, MAX_HEIGHT)
        val h = when (mode) {
            MeasureSpec.EXACTLY -> MeasureSpec.getSize(heightMeasureSpec)
            MeasureSpec.AT_MOST -> desired.coerceAtMost(MeasureSpec.getSize(heightMeasureSpec))
            else -> desired
        }
        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        val visible = stops
        if (visible.size < 2) {
            textPaint.textAlign = Paint.Align.CENTER
            canvas.drawText("no stops to plot", width / 2f, height / 2f, textPaint)
            return
        }
        val padL = dp(46f); val padR = dp(12f); val padT = dp(16f); val padB = dp(26f)
        val plot = RectF(padL, padT, width - padR, height - padB)
        rect.set(plot)
        // Fixed dark card: the chart's colour semantics (grey = schedule, green = live) must not
        // depend on the system theme, or "late" can become invisible in light mode.
        canvas.drawColor(COLOR_BG)

        val first = visible.first()
        val last = visible.last()
        val t0 = windowStartMin ?: (first.arrivalMin ?: first.departureMin ?: 0)
        val t1Auto = last.departureMin ?: last.arrivalMin ?: (t0 + 60)
        val liveMinute = position?.etaNextMinute
        // `maxOf`, not `max`: `kotlin.math.max` is the binary function, and three candidates — the
        // window end, the live marker, and a floor so a one-stop train still has a plot width.
        val t1 = windowEndMin ?: maxOf(t1Auto, liveMinute ?: 0, t0 + 30)
        val kMin = first.schedKm.toFloat()
        val kMax = max(kMin + 1f, last.schedKm.toFloat())

        fun xOf(minute: Int): Float =
            plot.left + (minute - t0).toFloat() / max(1, t1 - t0) * plot.width()

        fun yOf(km: Float): Float =
            plot.top + (km - kMin) / (kMax - kMin) * plot.height()

        // --- grid: hourly ticks (or coarser for long runs), 25% distance lines
        val minuteStep = when {
            t1 - t0 > 30 * 60 -> 6 * 60
            t1 - t0 > 12 * 60 -> 3 * 60
            t1 - t0 > 5 * 60 -> 60
            else -> 30
        }
        var tick = (t0 / minuteStep) * minuteStep
        textPaint.textAlign = Paint.Align.CENTER
        smallTextPaint.textAlign = Paint.Align.CENTER
        while (tick <= t1 + minuteStep) {
            val x = xOf(tick)
            if (x in plot.left..plot.right) {
                canvas.drawLine(x, plot.top, x, plot.bottom, gridPaint)
                canvas.drawText(
                    RailTime.formatRailMinuteBare(tick).substringBefore(':') + "h",
                    x, plot.bottom + dp(17f), smallTextPaint
                )
            }
            tick += minuteStep
        }
        for (q in 0..4) {
            val km = kMin + (kMax - kMin) * q / 4f
            val y = yOf(km)
            canvas.drawLine(plot.left, y, plot.right, y, gridPaint)
            textPaint.textAlign = Paint.Align.RIGHT
            canvas.drawText("%.0f".format(km), plot.left - dp(5f), y + dp(5f), textPaint)
            textPaint.textAlign = Paint.Align.CENTER
        }

        // --- schedule line
        path.reset()
        var started = false
        for (s in visible) {
            val t = s.departureMin ?: s.arrivalMin ?: continue
            val x = xOf(t); val y = yOf(s.schedKm.toFloat())
            if (!started) { path.moveTo(x, y); started = true } else path.lineTo(x, y)
        }
        canvas.drawPath(path, schedPaint)

        // --- delay band: the visual gap between "planned" and "actual"
        if (showDelayShade) {
            val delay = (position?.delayMinutes ?: 0).toFloat()
            path.reset()
            started = false
            val forward = ArrayList<Pair<Float, Float>>(visible.size)
            val back = ArrayList<Pair<Float, Float>>(visible.size)
            for (s in visible) {
                val t = (s.departureMin ?: s.arrivalMin ?: continue)
                val actual = (s.actualDepartureMin ?: (t + delay.toInt())).toFloat()
                forward.add(xOf(actual.toInt()) to yOf(s.schedKm.toFloat()))
                back.add(xOf(t) to yOf(s.schedKm.toFloat()))
            }
            if (forward.isNotEmpty()) {
                forward.forEachIndexed { i, (x, y) ->
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                for (i in back.indices.reversed()) {
                    val (x, y) = back[i]
                    path.lineTo(x, y)
                }
                path.close()
                canvas.drawPath(path, shadePaint)
            }
        }

        // --- live line: confirmed actuals up to the current position
        val pos = position
        path.reset()
        started = false
        if (pos != null) {
            for (s in visible) {
                if (s.seq > pos.anchorSeq) break
                val t = s.actualDepartureMin ?: s.actualArrivalMin ?: (s.departureMin ?: s.arrivalMin)
                    ?: continue
                val x = xOf(t); val y = yOf(s.schedKm.toFloat())
                if (!started) { path.moveTo(x, y); started = true } else path.lineTo(x, y)
            }
            val liveX = xOf(pos.etaNextMinute ?: (t0 + ((pos.fraction) * (t1 - t0)).toInt()))
            val liveY = yOf(pos.kmAlong.coerceIn(kMin, kMax))
            if (started) path.lineTo(liveX, liveY) else { path.moveTo(liveX, liveY); started = true }
            canvas.drawPath(path, livePaint)

            // projection to the next stop, dashed: the honest "expected", not a claim
            val next = visible.firstOrNull { it.seq > pos.anchorSeq }
            if (next != null) {
                val eta = pos.etaNextMinute
                if (eta != null) {
                    path.reset()
                    path.moveTo(liveX, liveY)
                    path.lineTo(xOf(eta), yOf(next.schedKm.toFloat()))
                    canvas.drawPath(path, futurePaint)
                    canvas.drawCircle(xOf(eta), yOf(next.schedKm.toFloat()), dp(4f), markerHaloPaint)
                }
            }

            // fill under the live line, so the chart reads as a progress area at a glance
            if (started) {
                val area = Path(path)
                area.lineTo(liveX, plot.bottom)
                area.lineTo(plot.left, plot.bottom)
                area.close()
                canvas.drawPath(area, fillPaint)
            }

            // --- the train itself. Hollow + dim when the data is stale (rule C5).
            val stale = pos.provenance == SourceKind.OFFLINE_SCHEDULE ||
                pos.provenance == SourceKind.NONE ||
                pos.ageSeconds > 600
            if (stale) {
                canvas.drawCircle(liveX, liveY, dp(9f), markerHaloPaint)
            } else {
                markerPaint.alpha = 235
                canvas.drawCircle(liveX, liveY, dp(7.5f), markerPaint)
                canvas.drawCircle(liveX, liveY, dp(12f), markerHaloPaint)
            }
            // "now" line: the horizontal gap from this to the schedule line *is* the delay, which is
            // the one thing the chart must make legible at a glance.
            nowRailMinute?.let { nm ->
                val nowX = xOf(nm)
                if (nowX in plot.left..plot.right) {
                    val p2 = Paint(gridPaint)
                    p2.color = COLOR_NOW
                    p2.strokeWidth = 2f
                    canvas.drawLine(nowX, plot.top, nowX, plot.bottom, p2)
                    smallTextPaint.textAlign = Paint.Align.CENTER
                    canvas.drawText("now", nowX, plot.top + dp(11f), smallTextPaint)
                }
            }
        }

        // --- stop ticks + labels with a simple collision guard
        smallTextPaint.textAlign = Paint.Align.LEFT
        var lastLabelBottom = -Float.MAX_VALUE
        for (s in visible) {
            val t = s.departureMin ?: s.arrivalMin ?: continue
            val x = xOf(t); val y = yOf(s.schedKm.toFloat())
            canvas.drawLine(plot.left, y, plot.right, y, gridPaint)
            if (s.isHalt) canvas.drawCircle(x, y, dp(3.4f), stopPaint)
            val isEdge = s.seq == boardingSeq || s.seq == alightingSeq
            val isCurrent = pos != null && (s.seq == pos.anchorSeq || s.seq == pos.nextSeq)
            if (!isEdge && !isCurrent && !s.isHalt) continue
            val labelY = y - dp(6f)
            if (labelY < lastLabelBottom + dp(15f)) continue
            val label = s.code + " " + RailTime.formatRailMinute(
                (s.actualDepartureMin ?: s.departureMin ?: t)
            )
            canvas.drawText(label, x + dp(4f), labelY, if (isCurrent || isEdge) textPaint else smallTextPaint)
            lastLabelBottom = labelY
        }

        // axis captions
        smallTextPaint.textAlign = Paint.Align.LEFT
        canvas.drawText("km", dp(4f), plot.top - dp(2f), smallTextPaint)
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    companion object {
        private const val MIN_HEIGHT = 240
        private const val MAX_HEIGHT = 720
        private val COLOR_GRID = Color.parseColor("#22FFFFFF")
        private val COLOR_SCHEDULE = Color.parseColor("#90A4AE")
        private val COLOR_LIVE = Color.parseColor("#4CAF50")
        private val COLOR_STOP = Color.parseColor("#B0BEC5")
        private val COLOR_TEXT = Color.parseColor("#ECEFF1")
        private val COLOR_TEXT_DIM = Color.parseColor("#90A4AE")
        private val COLOR_SHADE = Color.parseColor("#2E7D32")
        private val COLOR_NOW = Color.parseColor("#FFB74D")
        private val COLOR_BG = Color.parseColor("#16202A")
    }
}
