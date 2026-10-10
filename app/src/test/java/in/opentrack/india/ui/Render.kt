package `in`.opentrack.india.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import `in`.opentrack.india.R
import `in`.opentrack.india.domain.TrackModel
import `in`.opentrack.india.model.TrainPosition
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import org.robolectric.RuntimeEnvironment

/**
 * Renders a screen the way a device would, on the JVM, and writes the result out as an image.
 *
 * There is no emulator available for this project (no KVM, no system images) and no way to look at the app
 * from CI, which left the one thing a reviewer can judge only by looking completely unverified. Robolectric
 * runs the real framework: the actual layout XML is inflated against the actual theme, measured, laid out
 * and painted by the same drawing code a phone runs.
 *
 * `prepare` deliberately runs *between two* measure passes. The custom views here compute geometry from
 * their own `width` when they are handed data (`TrackInsightView.setJourney`), so binding before the first
 * layout would paint into a zero-size canvas — an artefact of the harness, not of the app.
 */
object Render {

    const val WIDTH_DP = 360f
    const val HEIGHT_DP = 760f

    /** Ceiling for a captured screen, so a 140-row route table cannot ask for a 30 000 px bitmap. */
    const val MAX_CONTENT_DP = 2400f

    class Shot(val root: View, val bitmap: Bitmap, val context: Context) {

        fun text(id: Int): String = (root.findViewById<TextView>(id))?.text?.toString().orEmpty()

        fun bounds(id: Int): Rect = Rect().also { r ->
            root.findViewById<View>(id)?.let { v ->
                val off = IntArray(2)
                v.getLocationInWindow(off)
                r.set(off[0], off[1], off[0] + v.width, off[1] + v.height)
            }
        }

        /** Distinct colours painted inside `area`; a screen or chart that drew nothing scores 1. */
        fun distinct(area: Rect = Rect(0, 0, bitmap.width, bitmap.height)): Int {
            val seen = HashSet<Int>(4096)
            var y = maxOf(0, area.top)
            while (y < minOf(bitmap.height, area.bottom)) {
                var x = maxOf(0, area.left)
                while (x < minOf(bitmap.width, area.right)) {
                    seen.add(bitmap.getPixel(x, y) and 0x00FFFFFF)
                    x += 2
                }
                y += 2
            }
            return seen.size
        }

        /** How many sampled pixels are exactly `color`; the count, so a failure message can carry it. */
        fun countWith(color: Int, area: Rect = Rect(0, 0, bitmap.width, bitmap.height)): Int {
            val target = color and 0x00FFFFFF
            var n = 0
            var y = maxOf(0, area.top)
            while (y < minOf(bitmap.height, area.bottom)) {
                var x = maxOf(0, area.left)
                while (x < minOf(bitmap.width, area.right)) {
                    if (bitmap.getPixel(x, y) and 0x00FFFFFF == target) n++
                    x += 2
                }
                y += 2
            }
            return n
        }

        fun paintedWith(color: Int, area: Rect = Rect(0, 0, bitmap.width, bitmap.height)): Boolean {
            val target = color and 0x00FFFFFF
            var y = maxOf(0, area.top)
            while (y < minOf(bitmap.height, area.bottom)) {
                var x = maxOf(0, area.left)
                while (x < minOf(bitmap.width, area.right)) {
                    if (bitmap.getPixel(x, y) and 0x00FFFFFF == target) return true
                    x += 2
                }
                y += 2
            }
            return false
        }
    }

    fun theme(): Context = ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Theme_OpenTrack)

    fun dp(ctx: Context, v: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, ctx.resources.displayMetrics)

    fun render(layoutRes: Int, heightDp: Float = 0f, prepare: (View) -> Unit = {}): Shot {
        val ctx = theme()
        val w = dp(ctx, WIDTH_DP).toInt()
        val device = dp(ctx, if (heightDp > 0f) heightDp else HEIGHT_DP).toInt()
        val cap = dp(ctx, MAX_CONTENT_DP).toInt()
        val host = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        host.layoutParams = ViewGroup.LayoutParams(w, device)
        host.addView(LayoutInflater.from(ctx).inflate(layoutRes, host, false))

        fun measure(heightPx: Int, mode: Int) = host.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(heightPx, mode),
        )

        // Pass 1, at the device height: this is what gives the custom views a real width to compute their
        // geometry from, which is why `prepare` cannot run before it.
        measure(device, View.MeasureSpec.EXACTLY)
        host.layout(0, 0, w, device)
        prepare(host)

        // Pass 2, unbounded, to learn what the content actually wants. Skipping this is how the first
        // version of this harness "found" a route table with one row in it: a RecyclerView lays out only as
        // many rows as it is *given*, so measuring at 760dp clipped the list to what fit on the fold and
        // the assertions would have been written against a half-painted screen. Screens are therefore
        // captured whole — which is also the only way to review spacing in a table.
        measure(0, View.MeasureSpec.UNSPECIFIED)
        val h = host.measuredHeight.coerceIn(device, cap)

        measure(h, View.MeasureSpec.EXACTLY)
        host.layout(0, 0, w, h)

        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(ctx.getColor(R.color.bg))
        host.draw(canvas)
        return Shot(host, bmp, ctx)
    }

    /**
     * The tracking screen's bindings, in one place so both the layout test and the shipped-data test paint
     * the same screen. These are the same ten assignments [TrainActivity] makes; the *values* come from
     * [TrackModel], which is what keeps the two from telling different stories.
     */
    fun track(root: View, m: TrackModel.View, pos: TrainPosition?, now: Int, note: String? = null) {
        text(root, R.id.trainNo, m.trainNo)
        text(root, R.id.routeLabel, m.routeLabel)
        text(root, R.id.runDate, m.runDateLabel)
        text(root, R.id.headline, m.headline)
        text(root, R.id.provenance, m.provenanceLabel)
        text(root, R.id.age, m.ageLabel)
        text(root, R.id.delay, m.delayLabel)
        text(root, R.id.next, m.nextLabel)
        text(root, R.id.progressLabel, m.progressLabel)
        text(root, R.id.chartLegend, if (m.isLive) {
            "green = reported · grey = timetable"
        } else {
            "timetable only — nothing reported for this run"
        })
        root.findViewById<View>(R.id.note)?.let { v ->
            v.visibility = if (note == null && m.note == null) View.GONE else View.VISIBLE
            (note ?: m.note)?.let { (v as TextView).text = it }
        }
        root.findViewById<JourneyProgressView>(R.id.progressBar)?.apply {
            pct = m.progressPct
            schedulePct = m.schedulePct
            live = m.isLive
        }
        root.findViewById<TrackInsightView>(R.id.chart)?.setJourney(m, pos, now, 0 until m.rows.size)
        root.findViewById<RecyclerView>(R.id.stops)?.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = StopAdapter().apply { submitList(m.rows) }
            isNestedScrollingEnabled = false
            Render.settle(this, m.rows.size)
        }
    }

    /**
     * Let a `ListAdapter`'s DiffUtil finish. `submitList` computes the update on its own handler thread,
     * which Robolectric does not own, so the list is empty until that thread hands the result back to the
     * main looper. This idles the main looper until `expected` rows are in the adapter — and if they never
     * arrive it returns anyway, leaving the caller's assertion to fail with the count that is visible.
     *
     * There is no synchronous setter to use instead: recyclerview 1.3 exposes `getCurrentList()` only, and
     * `currentList =` is not assignable (the getter is `@NonNull`, so Kotlin sees a val).
     */
    fun settle(list: RecyclerView?, expected: Int) {
        val main = org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
        var waited = 0
        while (list?.adapter != null && (list.adapter?.itemCount ?: -1) != expected && waited < 2_000) {
            main.idle()
            Thread.sleep(20)
            waited += 20
        }
        main.idle()
    }

    fun text(root: View, id: Int, value: String?) {
        (root.findViewById<TextView>(id))?.text = value.orEmpty()
    }

    fun read(root: View, id: Int): String = (root.findViewById<TextView>(id))?.text?.toString().orEmpty()

    /** Row views a RecyclerView laid out are its children; a collapsed list is the classic bug. */
    fun rowCount(root: View): Int = (root.findViewById<RecyclerView>(R.id.stops))?.childCount ?: -1

    fun save(name: String, bmp: Bitmap) {
        val dir = File(System.getProperty("preview.dir") ?: "app/build/reports/previews")
        dir.mkdirs()
        FileOutputStream(File(dir, "$name.png")).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        // The full PNG is for the artifact a human downloads. The small JPEG is what CI can put in a
        // commit comment as base64 (65 536 characters), which is the only way these pixels get reviewed.
        val scale = (System.getProperty("preview.scale") ?: "55").toInt()
        val quality = (System.getProperty("preview.quality") ?: "62").toInt()
        val small = Bitmap.createScaledBitmap(bmp, bmp.width * scale / 100, bmp.height * scale / 100, true)
        val out = ByteArrayOutputStream(1 shl 15)
        small.compress(Bitmap.CompressFormat.JPEG, quality, out)
        File(dir, "$name.jpg").writeBytes(out.toByteArray())
        small.recycle()
    }
}
