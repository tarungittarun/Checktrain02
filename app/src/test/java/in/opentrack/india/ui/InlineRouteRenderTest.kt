package `in`.opentrack.india.ui

import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import `in`.opentrack.india.R
import `in`.opentrack.india.domain.RouteBetween
import `in`.opentrack.india.domain.TrackModel
import `in`.opentrack.india.model.StopRow
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class InlineRouteRenderTest {
    @Test fun projectedTrainIsInlineBetweenItsHaltRowsAndCandidateIsVisibleWithoutReplacingAct() {
        val ctx = RuntimeEnvironment.getApplication()
        val adapter = StopAdapter()
        adapter.setProjectedLeg(RouteBetween.LegProjection("Birur", "Tarikere", .25f,
            null, 212, "RRB", "TKE", listOf(RouteBetween.LegPoint("SPV", "Sivapur", 216, .4f))))
        adapter.reports = mapOf("RRB" to "RRB arrival 13:13 · WIMT web-cache candidate, NOT verified Act")
        val row = TrackModel.Stop(1, "RRB", "Birur Jn", 722, 724,
            792, 794, null, TrackModel.MinuteSource.REPORTED, "2",
            TrackModel.State.PASSED, true, 206, 0, 24, false, false, false, -5)
        adapter.submitList(listOf(row))
        val holder = adapter.onCreateViewHolder(FrameLayout(ctx), 0)
        adapter.onBindViewHolder(holder, 0)
        assertEquals(View.VISIBLE, holder.inlineLegGroup.visibility)
        assertTrue(holder.inlineLeg.contentDescription.contains("not GPS"))
        assertTrue(holder.inlineLegCaption.text.contains("Between halts"))
        assertTrue(holder.report.text.contains("13:13"))
        assertEquals("Act 13:12", holder.liveArr.text.toString())
        adapter.setProjectedLeg(null)
        adapter.onBindViewHolder(holder, 0)
        assertEquals(View.GONE, holder.inlineLegGroup.visibility)
    }

    @Test fun officialStopHasVisibleBluePinnedLocomotiveNotAnInterpolatedPosition() {
        val ctx = RuntimeEnvironment.getApplication()
        val adapter = StopAdapter()
        adapter.sourceNameForMarker = "NTES"
        adapter.setProjectedLeg(RouteBetween.LegProjection("Arasalu", "Kumsi", 0f,
            "Arasalu", 57, "ARU", "KMSI", emptyList(), RouteBetween.MarkerKind.REPORTED_HALT))
        adapter.legAgeSeconds = 12 * 60L
        val row = TrackModel.Stop(1, "ARU", "Arasalu", 960, 961,
            961, null, null, TrackModel.MinuteSource.REPORTED, "1",
            TrackModel.State.PASSED, true, 57, 0, 15, false, false, false, -12)
        adapter.submitList(listOf(row))
        val holder = adapter.onCreateViewHolder(FrameLayout(ctx), 0)
        adapter.onBindViewHolder(holder, 0)
        assertEquals(View.VISIBLE, holder.inlineLegGroup.visibility)
        assertTrue(holder.inlineLegCaption.text.contains("Last stop"))
        assertTrue(holder.inlineLeg.contentDescription.contains("no measured distance since"))
        assertEquals("12 min ago", holder.inlineLegAge.text.toString())
        assertEquals(12 * 60L, holder.inlineLeg.ageSeconds)
        assertEquals(0f, holder.inlineLeg.projection!!.fraction, .001f)
    }

    @Test fun stationClaimMarkerNeverPretendsToHaveAReportedMinute() {
        val ctx = RuntimeEnvironment.getApplication()
        val adapter = StopAdapter()
        adapter.setProjectedLeg(RouteBetween.LegProjection("Arasalu", "Kumsi", 0f,
            "Arasalu", 57, "ARU", "KMSI", emptyList(), RouteBetween.MarkerKind.STATION_CLAIM))
        val row = TrackModel.Stop(1, "ARU", "Arasalu", 960, 961,
            960, 961, null, TrackModel.MinuteSource.SCHEDULE, null,
            TrackModel.State.PASSED, true, 57, 0, 15, false, false, false, -12)
        adapter.submitList(listOf(row))
        val holder = adapter.onCreateViewHolder(FrameLayout(ctx), 0)
        adapter.onBindViewHolder(holder, 0)
        assertTrue(holder.inlineLegCaption.text.contains("Station claim"))
        assertTrue(holder.inlineLeg.contentDescription.contains("passage time"))
        assertEquals(null, holder.inlineLeg.ageSeconds)
        assertEquals(0f, holder.inlineLeg.projection!!.fraction, .001f)
        assertEquals("—", holder.liveArr.text.toString())
    }

    @Test fun websiteLastStopIsPinnedAtHaltAndAgesWithoutMovingAlongLeg() {
        val ctx = RuntimeEnvironment.getApplication()
        val adapter = StopAdapter()
        val pinned = RouteBetween.LegProjection("Arasalu", "Kumsi", 0f,
            "Arasalu", 57, "ARU", "KMSI", listOf(
                RouteBetween.LegPoint("ABC", "Static point", 60, .2f)),
            RouteBetween.MarkerKind.SITE_LAST_STOP)
        adapter.setProjectedLeg(pinned)
        adapter.legAgeSeconds = 9 * 60L
        val row = TrackModel.Stop(1, "ARU", "Arasalu", 960, 961,
            961, null, null, TrackModel.MinuteSource.SITE_REPORTED, "1",
            TrackModel.State.PASSED, true, 57, 0, 15, false, false, false, -9)
        adapter.submitList(listOf(row))
        val holder = adapter.onCreateViewHolder(FrameLayout(ctx), 0)
        adapter.onBindViewHolder(holder, 0)
        assertEquals(View.VISIBLE, holder.inlineLegGroup.visibility)
        assertTrue(holder.inlineLegCaption.text.contains("Last stop"))
        assertEquals("9 min ago", holder.inlineLegAge.text.toString())
        assertTrue(holder.inlineLeg.contentDescription.contains("no measured distance since"))
        assertEquals(0f, holder.inlineLeg.projection!!.fraction, .001f)
        assertEquals(9 * 60L, holder.inlineLeg.ageSeconds)
        adapter.legAgeSeconds = 70 * 60L
        adapter.onBindViewHolder(holder, 0)
        assertEquals(70 * 60L, holder.inlineLeg.ageSeconds)
        assertEquals("1 h 10 min ago", holder.inlineLegAge.text.toString())
        assertEquals(0f, holder.inlineLeg.projection!!.fraction, .001f)
    }

    @Test fun denseLegRetainsAllOrderedStaticPointsInAccessibilityAndExpandedMap() {
        val ctx = RuntimeEnvironment.getApplication()
        val adapter = StopAdapter()
        val points = (1..11).map { i ->
            RouteBetween.LegPoint("P$i", "Static point $i", 100 + i * i,
                (i * i).toFloat() / (12 * 12))
        }
        adapter.setProjectedLeg(RouteBetween.LegProjection("A", "B", 0f,
            "A", 100, "AAA", "BBB", points, RouteBetween.MarkerKind.REPORTED_HALT))
        val row = TrackModel.Stop(1, "AAA", "A", 500, 505, 507,
            null, null, TrackModel.MinuteSource.REPORTED, null,
            TrackModel.State.PASSED, true, 100, 0, 44, false, false, false, -10)
        adapter.submitList(listOf(row))
        val holder = adapter.onCreateViewHolder(FrameLayout(ctx), 0)
        adapter.onBindViewHolder(holder, 0)
        assertEquals(View.VISIBLE, holder.inlineLegGroup.visibility)
        val description = holder.inlineLeg.contentDescription.toString()
        assertTrue(description.contains("P1 → P2 → P3"))
        assertTrue(description.contains("P9 → P10 → P11"))
        assertEquals(points, holder.inlineLeg.projection!!.points)
        val ruler = holder.inlineLeg
        val width = View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY)
        val height = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        ruler.measure(width, height)
        val compact = ruler.measuredHeight
        assertEquals(0f, ruler.projection!!.fraction, .001f)
        ruler.showLabels = true
        ruler.measure(width, height)
        assertEquals("opening map points stretched the distance ruler",
            compact, ruler.measuredHeight)
        assertEquals("disclosing map points moved the last-reported train", 0f,
            ruler.projection!!.fraction, .001f)
        ruler.showLabels = false
        ruler.measure(width, height)
        assertEquals("closing map points did not restore the compact leg",
            compact, ruler.measuredHeight)
    }

    @Test fun verticalDistanceRulerScalesByScheduledKmWithoutOutgrowingTheViewport() {
        assertEquals(104, LegGeometry.heightDp(2))
        assertTrue(LegGeometry.heightDp(20) > LegGeometry.heightDp(6))
        assertEquals(210, LegGeometry.heightDp(200))
        assertEquals("many map points must not stretch the ruler", 210, LegGeometry.heightDp(200))
    }

    @Test fun sourceSwitchKeepsTheSameVerticalLegGeometry() {
        val ctx = RuntimeEnvironment.getApplication()
        val from = TrackModel.Stop(1, "AAA", "From", 500, 505, 505,
            null, 505, TrackModel.MinuteSource.REPORTED, null,
            TrackModel.State.PASSED, true, 100, 0, 20, false, false, false, -5)
        val to = from.copy(seq = 2, code = "BBB", name = "To", kmFromOrigin = 120,
            isAnchor = false, source = TrackModel.MinuteSource.SCHEDULE,
            showArr = null, showDep = null)
        val adapter = StopAdapter()
        adapter.submitList(listOf(from, to))
        val holder = adapter.onCreateViewHolder(FrameLayout(ctx), 0)
        val spec = View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY)
        val open = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        fun height(kind: RouteBetween.MarkerKind): Int {
            adapter.setProjectedLeg(RouteBetween.LegProjection("From", "To", 0f,
                "From", 100, "AAA", "BBB", emptyList(), kind))
            adapter.onBindViewHolder(holder, 0)
            assertEquals(20, holder.inlineLeg.distanceKm)
            holder.inlineLeg.measure(spec, open)
            return holder.inlineLeg.measuredHeight
        }
        assertEquals("source choice changed the train ruler's footprint",
            height(RouteBetween.MarkerKind.REPORTED_HALT),
            height(RouteBetween.MarkerKind.SITE_LAST_STOP))
    }

    @Test fun officialAndWebsitePaintTheSameRulerAndLocomotive() {
        val ctx = RuntimeEnvironment.getApplication()
        fun paint(kind: RouteBetween.MarkerKind): android.graphics.Bitmap {
            val ruler = InlineLegView(ctx)
            ruler.distanceKm = 20
            ruler.projection = RouteBetween.LegProjection("From", "To", 0f,
                "From", 100, "AAA", "BBB", listOf(
                    RouteBetween.LegPoint("MAP", "Untimed map point", 110, .5f)), kind)
            ruler.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            ruler.layout(0, 0, ruler.measuredWidth, ruler.measuredHeight)
            return android.graphics.Bitmap.createBitmap(ruler.width, ruler.height,
                android.graphics.Bitmap.Config.ARGB_8888).also {
                ruler.draw(android.graphics.Canvas(it))
            }
        }
        assertTrue("switching from NTES to the website changed the route skin",
            paint(RouteBetween.MarkerKind.REPORTED_HALT).sameAs(
                paint(RouteBetween.MarkerKind.SITE_LAST_STOP)))
    }

    @Test fun pastAndArrivedStopDotsKeepTheSameSkinAcrossSources() {
        val ctx = RuntimeEnvironment.getApplication()
        data class RowPaint(val dot: android.graphics.Bitmap, val nameColor: Int,
                            val timeType: String, val dotStyle: Int)
        fun row(source: TrackModel.MinuteSource, state: TrackModel.State,
                departed: Boolean): RowPaint {
            val adapter = StopAdapter()
            val stop = TrackModel.Stop(1, "AAA", "From", 500, 505, 507,
                if (departed) 509 else null, null, source, null,
                state, true, 100, 0, 20, false, false, false, -5)
            adapter.submitList(listOf(stop))
            val holder = adapter.onCreateViewHolder(FrameLayout(ctx), 0)
            adapter.onBindViewHolder(holder, 0)
            val background = holder.dot.background
            background.setBounds(0, 0, 24, 24)
            val bitmap = android.graphics.Bitmap.createBitmap(24, 24,
                android.graphics.Bitmap.Config.ARGB_8888)
            background.draw(android.graphics.Canvas(bitmap))
            return RowPaint(bitmap, holder.name.currentTextColor,
                holder.timeType.text.toString(), adapter.dotFor(stop))
        }
        for ((state, departed) in listOf(TrackModel.State.PASSED to true,
                TrackModel.State.HERE to false)) {
            val official = row(TrackModel.MinuteSource.REPORTED, state, departed)
            val website = row(TrackModel.MinuteSource.SITE_REPORTED, state, departed)
            assertTrue("switching sources recoloured the $state stop dot",
                official.dot.sameAs(website.dot))
            assertEquals("switching sources recoloured the $state stop name",
                official.nameColor, website.nameColor)
            assertTrue(official.timeType.startsWith("ACTUAL"))
            assertEquals("SITE CLAIM", website.timeType)
        }
        // Robolectric's GradientDrawable raster shadow stretches these small
        // XML circles to the same forced bounds. Check the distinct resource
        // chosen by the real row instead of mistaking its bitmap stub for UI.
        assertEquals("arrived-at-station ring", R.drawable.dot_here,
            row(TrackModel.MinuteSource.REPORTED, TrackModel.State.HERE, false).dotStyle)
        assertEquals("passed-stop dot", R.drawable.dot_passed,
            row(TrackModel.MinuteSource.REPORTED, TrackModel.State.PASSED, true).dotStyle)
    }

    @Test fun tappingHaltShowsUntimedPointsThenCollapsesWithoutClaimingArrival() {
        val ctx = RuntimeEnvironment.getApplication()
        val adapter = StopAdapter()
        adapter.setRoutePoints(mapOf("YPR" to listOf(
            StopRow(2, "MWM", "Malleswaram", null, null, null,
                null, null, 0, 368, false))))
        val row = TrackModel.Stop(1, "YPR", "Yesvantpur Jn", 670, 672,
            670, 672, null, TrackModel.MinuteSource.REPORTED, null,
            TrackModel.State.PASSED, true, 366, 0, 6, true, true, false, -20)
        adapter.submitList(listOf(row))
        val holder = adapter.onCreateViewHolder(FrameLayout(ctx), 0)
        adapter.onBindViewHolder(holder, 0)
        assertEquals(View.GONE, holder.intermediateScroll.visibility)
        holder.row.performClick()
        adapter.onBindViewHolder(holder, 0)
        assertEquals(View.VISIBLE, holder.intermediateScroll.visibility)
        assertEquals("MAP POINTS · NO STOP TIMES",
            (holder.intermediateGroup.getChildAt(0) as TextView).text.toString())
        val text = (holder.intermediateGroup.getChildAt(1) as TextView).text.toString()
        assertTrue(text.contains("Malleswaram"))
        assertEquals("• Malleswaram · 368 km", text)
        assertFalse(text.contains("Act "))
        holder.row.performClick()
        adapter.onBindViewHolder(holder, 0)
        assertEquals(View.GONE, holder.intermediateScroll.visibility)
    }
}
