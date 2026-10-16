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
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
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
        assertEquals("13:12", holder.liveArr.text.toString())
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
        assertTrue(description.contains("Static point 1 (P1)") &&
            description.contains("Static point 3 (P3)"))
        assertTrue(description.contains("Static point 9 (P9)") &&
            description.contains("Static point 11 (P11)"))
        assertEquals(points, holder.inlineLeg.projection!!.points)
        val ruler = holder.inlineLeg
        val width = View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY)
        val height = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        ruler.measure(width, height)
        val compact = ruler.measuredHeight
        assertEquals(0f, ruler.projection!!.fraction, .001f)
        ruler.showLabels = true
        ruler.measure(width, height)
        assertTrue("station dots need room on the same line",
            ruler.measuredHeight > compact)
        assertEquals("disclosing map points moved the last-reported train", 0f,
            ruler.projection!!.fraction, .001f)
        ruler.showLabels = false
        ruler.measure(width, height)
        assertEquals("closing stations did not restore the compact leg",
            compact, ruler.measuredHeight)
    }

    @Test fun routeSpineContinuesThroughTheFullStopItemIncludingItsSpacing() {
        val ctx = RuntimeEnvironment.getApplication()
        val root = android.view.LayoutInflater.from(ctx)
            .inflate(R.layout.item_stop, FrameLayout(ctx), false) as RouteStopGroup
        root.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        root.layout(0, 0, root.measuredWidth, root.measuredHeight)
        val bitmap = android.graphics.Bitmap.createBitmap(root.width, root.height,
            android.graphics.Bitmap.Config.ARGB_8888)
        root.paintForTest(android.graphics.Canvas(bitmap))
        val x = (RouteRailGeometry.X_DP * ctx.resources.displayMetrics.density).toInt()
        assertEquals("route spine must reach the item boundary, not stop at the text",
            ctx.getColor(R.color.stroke), bitmap.getPixel(x, root.height - 2))
    }

    @Test fun verticalDistanceRulerScalesByScheduledKmWithoutOutgrowingTheViewport() {
        assertEquals(73, LegGeometry.heightDp(2))
        assertTrue(LegGeometry.heightDp(20) > LegGeometry.heightDp(6))
        assertEquals(103, LegGeometry.heightDp(200))
        assertEquals("small screens cap the leg and leave space for three halt rows",
            90, LegGeometry.heightDp(200, 480f))
        assertTrue(LegGeometry.heightDp(65) + 3 * 82 < 360)
        assertEquals(64, LegGeometry.compactMappedHeightDp(65))
        assertTrue("short mapped legs must not all look identical",
            LegGeometry.compactMappedHeightDp(3) < LegGeometry.compactMappedHeightDp(18))
        assertTrue("3 km, 7 km and 18 km need distinct stop separations",
            LegGeometry.stopGapDp(3) < LegGeometry.stopGapDp(7) &&
                LegGeometry.stopGapDp(7) < LegGeometry.stopGapDp(18))
        assertTrue("distance must still change the long-leg layout",
            LegGeometry.stopGapDp(60) > LegGeometry.stopGapDp(18))
        assertTrue("small displays retain a bounded leg", LegGeometry.stopGapDp(200, 480f) <= 34)
        assertEquals("1 min ago", LegGeometry.ageLabel(60))
        assertEquals("1 hour ago", LegGeometry.ageLabel(3600))
        assertEquals("1 hour 2 min ago", LegGeometry.ageLabel(3720))
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

    @Test fun disclosedStationNamesAndDotsShareTheLocomotiveRail() {
        val ctx = RuntimeEnvironment.getApplication()
        val points = listOf(
            RouteBetween.LegPoint("SAR", "Sree Saradanagar", 142, .04f, 1011, true),
            RouteBetween.LegPoint("HNV", "Honnavalli Road", 152, .44f, 1020, true),
            RouteBetween.LegPoint("ADH", "Adihalli", 157, .64f, 1025, true))
        val ruler = InlineLegView(ctx).apply {
            projection = RouteBetween.LegProjection("Tiptur", "Arsikere", 0f, null,
                141, "TTR", "ASK", points, RouteBetween.MarkerKind.REPORTED_HALT)
            distanceKm = 25
            showLabels = true
            ageSeconds = 180
        }
        ruler.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        ruler.layout(0, 0, ruler.measuredWidth, ruler.measuredHeight)
        val bmp = android.graphics.Bitmap.createBitmap(ruler.width, ruler.height,
            android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        canvas.drawColor(ctx.getColor(R.color.surface))
        ruler.paintForTest(canvas)
        Render.save("10-inline-stations-and-train", bmp)
        val d = ctx.resources.displayMetrics.density
        val positions = LegGeometry.pointPositionsDp(points, 25)
        assertTrue(positions[1] - positions[0] > positions[0] - 24f)
        for (y in positions) assertEquals("intermediate dot is not on the train's rail",
            ctx.getColor(R.color.text2), bmp.getPixel((RouteRailGeometry.X_DP * d).toInt(), (y * d).toInt()))
        val surface = ctx.getColor(R.color.surface)
        val timeY = (positions[0] * d).toInt()
        assertTrue("the planned arrival clock is not painted beside its dot",
            ((bmp.width - (82 * d).toInt()) until bmp.width).any { x ->
                ((timeY - (7 * d).toInt())..(timeY + (7 * d).toInt())).any { y ->
                    bmp.getPixel(x, y) != surface
                }
            })
        assertEquals("the locomotive has moved to a point instead of staying at its stop",
            ctx.getColor(R.color.live), bmp.getPixel(((RouteRailGeometry.X_DP - 16f) * d).toInt(), (24 * d).toInt()))
    }

    @Test fun onlySelectedWebsiteIntermediatePaintsItsOwnPublishedEventBesideMarker() {
        val ctx = RuntimeEnvironment.getApplication()
        val ruler = InlineLegView(ctx).apply {
            projection = RouteBetween.LegProjection("Arsikere", "Birur", .5f, "Devanur",
                327, "ASK", "RRB", listOf(RouteBetween.LegPoint("DRU", "Devanur", 327, .5f)),
                RouteBetween.MarkerKind.SITE_INTERMEDIATE)
            distanceKm = 45
            ageSeconds = null
        }
        ruler.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        ruler.layout(0, 0, ruler.measuredWidth, ruler.measuredHeight)
        fun painted(minute: Int?): android.graphics.Bitmap {
            ruler.siteEventMinute = minute
            return android.graphics.Bitmap.createBitmap(ruler.width, ruler.height,
                android.graphics.Bitmap.Config.ARGB_8888).also { bitmap ->
                ruler.paintForTest(android.graphics.Canvas(bitmap))
            }
        }
        val noSiteEvent = painted(null)
        val siteEvent = painted(1576)
        val d = ctx.resources.displayMetrics.density
        val fromX = ((RouteRailGeometry.X_DP + 23f) * d).toInt()
        assertTrue("a qualified website minute must change pixels beside the non-halt marker",
            (fromX until siteEvent.width).any { x ->
                (0 until siteEvent.height).any { y ->
                    siteEvent.getPixel(x, y) != noSiteEvent.getPixel(x, y)
                }
            })
    }

    @Test fun onlyARecentSourceEventPaintsTheGreenCheckOnTheLocomotive() {
        val ctx = RuntimeEnvironment.getApplication()
        val ruler = InlineLegView(ctx).apply {
            distanceKm = 20
            projection = RouteBetween.LegProjection("From", "To", 0f, "From", 100,
                "AAA", "BBB", emptyList(), RouteBetween.MarkerKind.REPORTED_HALT)
        }
        ruler.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        ruler.layout(0, 0, ruler.measuredWidth, ruler.measuredHeight)
        fun pixelAtCheck(age: Long?): Int {
            ruler.ageSeconds = age
            val bmp = android.graphics.Bitmap.createBitmap(ruler.width, ruler.height,
                android.graphics.Bitmap.Config.ARGB_8888)
            // A bare renderer may skip painting before attachment in Robolectric.
            // Exercise the painter directly on this software canvas instead.
            ruler.paintForTest(android.graphics.Canvas(bmp))
            val d = ctx.resources.displayMetrics.density
            return bmp.getPixel(((RouteRailGeometry.X_DP + 15 + 5) * d).toInt(), ((24 - 14) * d).toInt())
        }
        assertEquals("<1 min must draw green at the icon's upper-right",
            ctx.getColor(R.color.accent), pixelAtCheck(30))
        assertTrue("a minute-old event must not display a freshness check",
            pixelAtCheck(60) != ctx.getColor(R.color.accent))
        assertTrue("unknown event time must not display a freshness check",
            pixelAtCheck(null) != ctx.getColor(R.color.accent))
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
                ruler.paintForTest(android.graphics.Canvas(it))
            }
        }
        val official = paint(RouteBetween.MarkerKind.REPORTED_HALT)
        val d = ctx.resources.displayMetrics.density
        assertEquals("source-neutral train body must actually be painted",
            ctx.getColor(R.color.live), official.getPixel(((RouteRailGeometry.X_DP - 16f) * d).toInt(), (24 * d).toInt()))
        assertTrue("switching from NTES to the website changed the route skin",
            official.sameAs(paint(RouteBetween.MarkerKind.SITE_LAST_STOP)))
    }

    @Test fun tappingAnOrdinaryStopShowsProportionalStationDotsWithoutATimeSheet() {
        val ctx = RuntimeEnvironment.getApplication()
        val adapter = StopAdapter()
        adapter.setRoutePoints(mapOf("YPR" to listOf(
            StopRow(2, "MWM", "Malleswaram", null, null, null,
                null, null, 0, 367, false),
            StopRow(3, "ABC", "Another station", null, null, null,
                null, null, 0, 370, false))))
        val from = TrackModel.Stop(1, "YPR", "Yesvantpur Jn", 670, 672,
            670, 672, null, TrackModel.MinuteSource.REPORTED, null,
            TrackModel.State.PASSED, true, 366, 0, 6, true, true, false, -20)
        val next = TrackModel.Stop(2, "AAA", "Next stop", 684, 686,
            684, 686, null, TrackModel.MinuteSource.SCHEDULE, null,
            TrackModel.State.NEXT, true, 372, 6, 8, false, false, false, 4)
        adapter.submitList(listOf(from, next))
        val holder = adapter.onCreateViewHolder(FrameLayout(ctx), 0)
        adapter.onBindViewHolder(holder, 0)
        assertEquals(View.VISIBLE, holder.inlineLegGroup.visibility)
        assertFalse(holder.inlineLeg.showLabels)
        assertEquals("Stations ▾", holder.routeToggle.text.toString())
        assertTrue(holder.inlineLeg.compactStatic)
        holder.inlineLeg.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        assertTrue(holder.inlineLeg.measuredHeight / ctx.resources.displayMetrics.density <= 58f)
        assertEquals(View.GONE, holder.details.visibility)
        assertEquals(View.VISIBLE, holder.schedArr.visibility)
        assertEquals(View.VISIBLE, holder.liveArr.visibility)
        assertEquals("11:10", holder.schedArr.text.toString())
        holder.inlineLeg.performClick() // full folded rail must open, not just the name
        assertEquals("Hide ▴", holder.routeToggle.text.toString())
        assertTrue(holder.inlineLeg.showLabels) // visible before RecyclerView's next bind
        adapter.onBindViewHolder(holder, 0)
        assertEquals(View.VISIBLE, holder.inlineLegGroup.visibility)
        assertTrue(holder.inlineLeg.showLabels)
        assertFalse(holder.inlineLeg.showTrain)
        assertEquals(2, holder.inlineLeg.projection!!.points.size)
        assertEquals(367, holder.inlineLeg.projection!!.points[0].mapKm)
        assertEquals("intermediate clocks must use bounding schedule and km, never live progress",
            listOf(674, 680), holder.inlineLeg.projection!!.points.map { it.passageMinute })
        assertTrue(holder.inlineLeg.projection!!.points[0].approximate)
        assertTrue(holder.inlineLeg.contentDescription.contains("Malleswaram (MWM), approximately"))
        assertTrue(holder.inlineLeg.contentDescription.contains("no train-position claim"))
        assertEquals(View.GONE, holder.details.visibility)
        val positions = LegGeometry.pointPositionsDp(holder.inlineLeg.projection!!.points, 6)
        assertTrue("1 km and 3 km must create different vertical gaps",
            positions[0] - 24f < positions[1] - positions[0])
        holder.inlineLeg.performClick()
        adapter.onBindViewHolder(holder, 0)
        assertEquals(View.VISIBLE, holder.inlineLegGroup.visibility)
        assertFalse(holder.inlineLeg.showLabels)
    }

    @Test fun emptyStaticMapExplainsWhyStationsCannotOpenAndOffersRetry() {
        val ctx = RuntimeEnvironment.getApplication()
        val adapter = StopAdapter()
        var explanations = 0
        adapter.onMapUnavailable = { _ -> explanations++ }
        adapter.setProjectedLeg(RouteBetween.LegProjection("Yesvantpur", "Tumkur", 0f,
            "Yesvantpur", 144, "YPR", "TK", emptyList(), RouteBetween.MarkerKind.REPORTED_HALT))
        adapter.haltMarker = TableMarker.Halt("YPR", "NTES YPR departure 23:42", true)
        adapter.legAgeSeconds = 53 * 60L
        val from = TrackModel.Stop(1, "YPR", "Yesvantpur", 1420, 1422,
            1420, 1422, null, TrackModel.MinuteSource.REPORTED, null,
            TrackModel.State.PASSED, true, 144, 0, 55, false, false, false, -53)
        val to = from.copy(seq = 2, code = "TK", name = "Tumkur", kmFromOrigin = 199,
            isAnchor = false, source = TrackModel.MinuteSource.SCHEDULE)
        val third = to.copy(seq = 3, code = "TTR", name = "Tiptur", kmFromOrigin = 279)
        adapter.submitList(listOf(from, to, third))
        val holder = adapter.onCreateViewHolder(FrameLayout(ctx), 0)
        adapter.onBindViewHolder(holder, 0)
        assertEquals(View.VISIBLE, holder.inlineLegGroup.visibility)
        assertEquals("the caption states the reported-stop limit without map-status prose",
            "Last stop · now unknown", holder.inlineLegCaption.text.toString())
        assertEquals("an empty map cannot offer an empty Stations disclosure", View.GONE,
            holder.routeToggle.visibility)
        assertTrue("freshness must be beside the pinned locomotive",
            holder.marker.text.toString().startsWith("53 min ago · LAST REPORTED STOP"))
        assertEquals(53 * 60L, holder.trainMarker.ageSeconds)
        holder.inlineLeg.performClick()
        assertEquals("the source-supported train leg still explains its unavailable map on tap",
            1, explanations)
        adapter.onBindViewHolder(holder, 1)
        holder.row.performClick() // even a plain, unmapped station gap explains itself
        assertEquals(2, explanations)
        adapter.onBindViewHolder(holder, 2)
        holder.row.performClick() // last stop has no following leg; no false promise
        assertEquals(2, explanations)
        adapter.onBindViewHolder(holder, 0)
        assertFalse(holder.inlineLeg.showLabels)
        assertEquals(0, holder.inlineLeg.projection!!.points.size)
    }

    @Test fun birurMarkerIsOnItsStationAndWholeLegTogglesExactlyTwoMappedPoints() {
        val ctx = RuntimeEnvironment.getApplication()
        val adapter = StopAdapter()
        val points = listOf(
            StopRow(2, "SPV", "Sivapur", null, null, null,
                null, null, 0, 215, false),
            StopRow(3, "KRNH", "Koranahalli", null, null, null,
                null, null, 0, 220, false))
        adapter.setRoutePoints(mapOf("RRB" to points))
        adapter.setProjectedLeg(RouteBetween.LegProjection("Birur Jn", "Tarikere Jn", 0f,
            "Birur Jn", 211, "RRB", "TKE", emptyList(), RouteBetween.MarkerKind.SITE_LAST_STOP))
        adapter.haltMarker = TableMarker.Halt("RRB", "Site's last past stop · 2m ago", true)
        adapter.legAgeSeconds = 120
        val birur = TrackModel.Stop(1, "RRB", "Birur Jn", 1205, 1206, 1230,
            null, null, TrackModel.MinuteSource.SITE_REPORTED, "2",
            TrackModel.State.PASSED, true, 211, 0, 24, false, false, false, -2)
        val tarikere = birur.copy(seq = 2, code = "TKE", name = "Tarikere Jn",
            schedArr = 1230, schedDep = 1231, showArr = null, showDep = null,
            source = TrackModel.MinuteSource.SCHEDULE, isAnchor = false, kmFromOrigin = 235)
        adapter.submitList(listOf(birur, tarikere))
        val holder = adapter.onCreateViewHolder(FrameLayout(ctx), 0)
        adapter.onBindViewHolder(holder, 0)
        assertEquals(View.VISIBLE, holder.trainMarker.visibility)
        assertEquals(View.GONE, holder.dot.visibility)
        assertFalse("second locomotive cannot occupy the leg below Birur", holder.inlineLeg.showTrain)
        assertEquals("the train belongs to Birur, not the next dot", "RRB",
            adapter.haltMarker?.code)
        assertFalse(holder.inlineLeg.showLabels)
        holder.inlineLegGroup.performClick() // any blank part of the leg, not a button
        adapter.onBindViewHolder(holder, 0)
        assertTrue(holder.inlineLeg.showLabels)
        assertEquals(listOf("SPV", "KRNH"), holder.inlineLeg.projection!!.points.map { it.code })
        assertEquals(listOf(215, 220), holder.inlineLeg.projection!!.points.map { it.mapKm })
        assertTrue(holder.inlineLeg.contentDescription.contains("215 km"))
        assertTrue(holder.inlineLeg.contentDescription.contains("220 km"))
        val d = ctx.resources.displayMetrics.density
        val content = holder.itemView
        content.measure(View.MeasureSpec.makeMeasureSpec((360 * d).toInt(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        content.layout(0, 0, content.measuredWidth, content.measuredHeight)
        val frame = holder.trainMarker.parent as View
        val locomotiveX = frame.left + holder.trainMarker.left + holder.trainMarker.width / 2
        assertEquals("the locomotive must be centred on the x=124dp station rail",
            (RouteRailGeometry.X_DP * d).toInt(), locomotiveX)
        // Keep both time pairs on their OWN sides of that same rail, including at
        // narrow widths; a coordinate assertion catches a cosmetic-only reorder.
        val scheduleBounds = android.graphics.Rect(0, 0, holder.schedArr.width, holder.schedArr.height)
        val reportBounds = android.graphics.Rect(0, 0, holder.liveArr.width, holder.liveArr.height)
        val group = content as android.view.ViewGroup
        group.offsetDescendantRectToMyCoords(holder.schedArr, scheduleBounds)
        group.offsetDescendantRectToMyCoords(holder.liveArr, reportBounds)
        assertTrue("scheduled arrival overlaps the rail: $scheduleBounds",
            scheduleBounds.right < locomotiveX)
        assertTrue("reported arrival crosses to the schedule side: $reportBounds",
            reportBounds.left > locomotiveX)
        assertTrue("the icon slipped below the Birur station name",
            kotlin.math.abs(frame.top + holder.trainMarker.top + holder.trainMarker.height / 2 -
                (holder.name.top + holder.name.height / 2)) < (25 * d).toInt())
        val stationBitmap = android.graphics.Bitmap.createBitmap(content.width, content.height,
            android.graphics.Bitmap.Config.ARGB_8888)
        val stationCanvas = android.graphics.Canvas(stationBitmap)
        stationCanvas.drawColor(ctx.getColor(R.color.surface))
        content.draw(stationCanvas)
        Render.save("12-birur-train-at-stop-with-two-map-points", stationBitmap)
        holder.inlineLeg.performClick() // on the rail itself closes it again
        adapter.onBindViewHolder(holder, 0)
        assertFalse(holder.inlineLeg.showLabels)
        assertEquals(View.VISIBLE, holder.trainMarker.visibility)
        val ruler = holder.inlineLeg
        // A second tap must really close the stations; no anonymous small dots
        // may remain on the otherwise plain route rail.
        ruler.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        ruler.layout(0, 0, ruler.measuredWidth, ruler.measuredHeight)
        val closed = android.graphics.Bitmap.createBitmap(ruler.width, ruler.height,
            android.graphics.Bitmap.Config.ARGB_8888)
        val closedCanvas = android.graphics.Canvas(closed)
        closedCanvas.drawColor(ctx.getColor(R.color.surface))
        ruler.paintForTest(closedCanvas)
        val pointFraction = ruler.projection!!.points[0].fraction
        val collapsedY = 24f + (ruler.height / d - 48f) * pointFraction
        assertEquals("hidden intermediate point still leaves an unexplained dot",
            ctx.getColor(R.color.stroke),
            closed.getPixel((RouteRailGeometry.X_DP * d).toInt(), (collapsedY * d).toInt()))
        ruler.showLabels = true
        ruler.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        ruler.layout(0, 0, ruler.measuredWidth, ruler.measuredHeight)
        val bmp = android.graphics.Bitmap.createBitmap(ruler.width, ruler.height,
            android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        canvas.drawColor(ctx.getColor(R.color.surface))
        ruler.paintForTest(canvas)
        Render.save("11-birur-to-tarikere-two-points", bmp)
    }

    @Test fun historicalCorridorOpensStationsButMappedEmptyLegNeverShowsFailure() {
        val ctx = RuntimeEnvironment.getApplication()
        val adapter = StopAdapter()
        adapter.mapSource = `in`.opentrack.india.remote.OfflineCorridorMap.SOURCE
        adapter.mappedLegStarts = setOf("TK", "MWM")
        var unavailable = 0
        adapter.onMapUnavailable = { _ -> unavailable++ }
        adapter.setRoutePoints(mapOf("TK" to listOf(
            StopRow(0, "HEI", "Heggere", null, null, null,
                null, null, 0, 218, false),
            StopRow(0, "MLSA", "Mallasandra", null, null, null,
                null, null, 0, 222, false))))
        val from = TrackModel.Stop(1, "TK", "Tumkur", 1450, 1452,
            1470, 1472, null, TrackModel.MinuteSource.REPORTED, null,
            TrackModel.State.PASSED, true, 213, 0, 70, false, false, false, -44)
        val to = from.copy(seq = 2, code = "TTR", name = "Tiptur",
            kmFromOrigin = 284, source = TrackModel.MinuteSource.SCHEDULE,
            showArr = null, showDep = null, isAnchor = false)
        adapter.submitList(listOf(from, to))
        val holder = adapter.onCreateViewHolder(FrameLayout(ctx), 0)
        adapter.onBindViewHolder(holder, 0)
        assertEquals("Stations ▾", holder.routeToggle.text.toString())
        holder.inlineLeg.performClick()
        assertTrue(holder.inlineLeg.showLabels)
        assertEquals(listOf("HEI", "MLSA"), holder.inlineLeg.projection!!.points.map { it.code })
        assertTrue(holder.inlineLeg.projection!!.points.all { it.passageMinute == null })
        assertTrue(holder.inlineLeg.historicalMap)
        assertEquals(0, unavailable)
        val malleshwaram = from.copy(code = "MWM", name = "Malleswaram", kmFromOrigin = 143)
        val emptyLeg = StopAdapter()
        emptyLeg.mapSource = adapter.mapSource
        emptyLeg.mappedLegStarts = adapter.mappedLegStarts
        emptyLeg.onMapUnavailable = { _ -> unavailable++ }
        emptyLeg.submitList(listOf(malleshwaram, to.copy(code = "YPR", kmFromOrigin = 146)))
        val emptyHolder = emptyLeg.onCreateViewHolder(FrameLayout(ctx), 0)
        emptyLeg.onBindViewHolder(emptyHolder, 0)
        assertEquals("no-point leg must not repeat map status prose", View.GONE,
            emptyHolder.inlineLegGroup.visibility)
        assertEquals(View.GONE, emptyHolder.routeToggle.visibility)
        assertFalse("empty historical leg should not pop a toast or map dialog",
            emptyHolder.row.isClickable)
        assertEquals(0, unavailable)
    }

    @Test fun arrowsHaveOppositeDiagonalTipsAndSourceLightIsStronger() {
        val ctx = RuntimeEnvironment.getApplication()
        val d = ctx.resources.displayMetrics.density
        fun draw(arrive: Boolean, report: Boolean): android.graphics.Bitmap {
            val arrow = DirectionArrowDrawable(ctx, arrive, report)
            arrow.phaseForTest = .6f
            val bitmap = android.graphics.Bitmap.createBitmap((22*d).toInt(), (20*d).toInt(),
                android.graphics.Bitmap.Config.ARGB_8888)
            arrow.setBounds(0, 0, bitmap.width, bitmap.height)
            arrow.draw(android.graphics.Canvas(bitmap))
            return bitmap
        }
        val arrival = draw(true, true)
        val departure = draw(false, true)
        fun alpha(bitmap: android.graphics.Bitmap, x: Float, y: Float) =
            android.graphics.Color.alpha(bitmap.getPixel((x*d).toInt(), (y*d).toInt()))
        assertTrue("arrival must glow at lower-right, not upper-left",
            alpha(arrival, 18f, 17f) > alpha(arrival, 3f, 17f))
        assertTrue("departure must point lower-left, not upper-left",
            alpha(departure, 3f, 17f) > alpha(departure, 3f, 3f))
        // The reported highlight moves along the shaft, not a stationary halo at its tip.
        val early = DirectionArrowDrawable(ctx, true, true).apply { phaseForTest = .1f }
        val late = DirectionArrowDrawable(ctx, true, true).apply { phaseForTest = .85f }
        fun frame(arrow: DirectionArrowDrawable): android.graphics.Bitmap {
            val bitmap = android.graphics.Bitmap.createBitmap((22*d).toInt(), (20*d).toInt(),
                android.graphics.Bitmap.Config.ARGB_8888)
            arrow.setBounds(0, 0, bitmap.width, bitmap.height)
            arrow.draw(android.graphics.Canvas(bitmap))
            return bitmap
        }
        val before = frame(early)
        val after = frame(late)
        assertTrue("light must leave the tail", alpha(before, 5f, 5f) > alpha(after, 5f, 5f))
        assertTrue("light must reach the head", alpha(after, 16f, 15f) > alpha(before, 16f, 15f))
    }
}
