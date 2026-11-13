package `in`.opentrack.india.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import `in`.opentrack.india.data.Prefs
import `in`.opentrack.india.R
import `in`.opentrack.india.domain.TrackModel
import `in`.opentrack.india.domain.RouteBetween
import `in`.opentrack.india.model.LiveStopFields
import `in`.opentrack.india.model.StopRow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BatteryModeTest {
    private val ctx get() = RuntimeEnvironment.getApplication()

    private fun textViews(view: View): List<TextView> = when (view) {
        is TextView -> listOf(view)
        is ViewGroup -> (0 until view.childCount).flatMap { textViews(view.getChildAt(it)) }
        else -> emptyList()
    }

    @After fun restore() { Prefs(ctx).batteryMode = false }

    @Test fun preferenceIsIndependentOfAppearanceAndOffByDefault() {
        ctx.getSharedPreferences("opentrack", 0).edit().remove("batteryMode").commit()
        val prefs = Prefs(ctx)
        assertFalse(prefs.batteryMode)
        val appearance = prefs.appearance
        prefs.batteryMode = true
        assertTrue(Prefs(ctx).batteryMode)
        assertEquals(appearance, Prefs(ctx).appearance)
        prefs.batteryMode = false
        assertEquals(appearance, Prefs(ctx).appearance)
    }

    @Test fun onlyEnabledModeBlacksSurfacesAndKeepsTextLegible() {
        val prefs = Prefs(ctx)
        val root = FrameLayout(ctx).apply { setBackgroundColor(Color.RED) }
        val text = TextView(ctx).apply {
            text = "Delay · next · NTES"
            setTextColor(Color.BLUE)
            layoutParams = FrameLayout.LayoutParams(190, 40)
        }
        root.addView(text)
        root.measure(View.MeasureSpec.makeMeasureSpec(200, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 200, 100)
        prefs.batteryMode = false
        BatteryMode.paint(root)
        assertEquals(Color.RED, pixel(root, 190, 80))
        assertEquals(Color.BLUE, text.currentTextColor)
        prefs.batteryMode = true
        BatteryMode.paint(root)
        assertEquals(Color.BLACK, pixel(root, 190, 80))
        assertEquals(Color.WHITE, text.currentTextColor)
        assertEquals("Delay · next · NTES", text.text.toString())
    }

    @Test fun batteryRefreshIsAStaticWhiteActionOnTheBlackSurface() {
        Prefs(ctx).batteryMode = true
        val density = ctx.resources.displayMetrics.density
        val button = FrameLayout(ctx)
        val label = TextView(ctx).apply { text = "Refresh" }
        button.addView(label, FrameLayout.LayoutParams(-1, -1))
        // Match the activity order: general OLED paint, then the dedicated action treatment.
        BatteryMode.paint(button)
        BatteryMode.emphasizeRefresh(button, label)
        val side = (48f * density + 0.5f).toInt()
        button.measure(View.MeasureSpec.makeMeasureSpec(side, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(side, View.MeasureSpec.EXACTLY))
        button.layout(0, 0, side, side)
        val bitmap = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        button.draw(Canvas(bitmap))
        val surfaceSample = (8f * density).toInt()
        assertEquals("the Battery refresh is an inverse high-contrast action",
            Color.WHITE, bitmap.getPixel(surfaceSample, surfaceSample))
        assertEquals(Color.BLACK, label.currentTextColor)
        assertTrue(label.typeface.isBold)
        assertTrue(label.textSize >= 12f * ctx.resources.displayMetrics.scaledDensity)
        assertEquals("Refresh selected source now; drag to move in lower half", button.contentDescription)
        assertEquals("Battery repainting keeps the label black and the control white",
            Color.WHITE, pixel(button, surfaceSample, surfaceSample))
        BatteryMode.paint(button)
        BatteryMode.emphasizeRefresh(button, label)
        assertEquals(Color.BLACK, label.currentTextColor)
        bitmap.recycle()
    }

    @Test fun homeSettingsAndTrackingLayoutsAreBlackWithReadablePrimaryText() {
        Prefs(ctx).batteryMode = true
        for ((layout, labelId) in listOf(
            R.layout.activity_main to R.id.dataChip,
            R.layout.activity_settings to R.id.batteryMode,
            R.layout.activity_train to R.id.delay,
        )) {
            val shot = Render.render(layout, prepare = { BatteryMode.paint(it) })
            // The extreme corner can be anti-aliased by the permitted outline.
            assertEquals("$layout left an illuminated surface", Color.BLACK,
                shot.bitmap.getPixel(15, 15))
            assertEquals("$layout lost its text contrast", Color.WHITE,
                shot.root.findViewById<TextView>(labelId).currentTextColor)
        }
    }

    @Test fun batteryRowKeepsScheduleActualProvenanceAndWebsiteGapWithoutAnimatedArrow() {
        Prefs(ctx).batteryMode = true
        val adapter = StopAdapter().apply { batteryMode = true }
        val stop = TrackModel.Stop(1, "ASK", "Arsikere Jn", 1543, 1545, 1538, null, null,
            TrackModel.MinuteSource.REPORTED, "2", TrackModel.State.HERE,
            true, 304, 0, 200, false, false, false, null)
        adapter.haltMarker = TableMarker.Halt("ASK", "Last NTES arrival at ASK", false)
        adapter.legAgeSeconds = 9 * 60L
        adapter.submitList(listOf(stop))
        val holder = adapter.onCreateViewHolder(FrameLayout(ctx), 0)
        adapter.onBindViewHolder(holder, 0)
        assertEquals("A 01:43", holder.schedArr.text.toString())
        assertEquals("A 01:38", holder.liveArr.text.toString())
        // This halt has a scheduled departure, so missing actual stays blank, not END.
        assertEquals("D —", holder.liveDep.text.toString())
        assertTrue(holder.liveArr.contentDescription.contains("Reported arrival"))
        assertEquals(Color.WHITE, holder.liveArr.currentTextColor)
        assertEquals(View.VISIBLE, holder.trainMarker.visibility)
        assertTrue(holder.trainMarker.batteryOutline)
        assertEquals(View.GONE, holder.marker.visibility)
        assertTrue(holder.eventCaption.text.toString().contains("At Arsikere Jn · as of 01:38"))
        assertTrue(holder.eventCaption.text.toString().contains("9m ago"))
        assertEquals("9m ago", holder.markerAge.text.toString())
        assertTrue(holder.trainMarker.contentDescription.contains("present position unknown"))
        assertEquals(null, holder.liveArr.compoundDrawables[0])
    }

    @Test fun lastWebsiteIntermediateHasOneWhiteSketchAndAnAgedSiteCaption() {
        Prefs(ctx).batteryMode = true
        val adapter = StopAdapter().apply { batteryMode = true }
        val kadur = TrackModel.Stop(1, "DRU", "Kadur", 501, 503, 685, 690, null,
            TrackModel.MinuteSource.SITE_REPORTED, "1", TrackModel.State.PASSED,
            false, 167, 0, 39, false, false, false, null)
        val arsikere = kadur.copy(seq = 2, code = "ASK", name = "Arsikere Jn",
            schedArr = 530, schedDep = 535, showArr = null, showDep = null,
            source = TrackModel.MinuteSource.SCHEDULE, isAnchor = false,
            kmFromOrigin = 206)
        adapter.legAgeSeconds = 9 * 60L
        adapter.legWebsiteEventMinute = 707 // 11:47 is a site card, not a page-update clock
        val mappedPoints = (1..6).map { index ->
            RouteBetween.LegPoint("P$index", "Mapped point $index", 185 + index,
                index / 7f, passageMinute = 540 + index)
        }
        adapter.setProjectedLeg(RouteBetween.LegProjection("Kadur", "Arsikere Jn",
            0.5f, "Banavar", 186, "DRU", "ASK", mappedPoints,
            RouteBetween.MarkerKind.SITE_INTERMEDIATE))
        adapter.submitList(listOf(kadur, arsikere))
        val holder = adapter.onCreateViewHolder(FrameLayout(ctx), 0)
        adapter.onBindViewHolder(holder, 0)
        assertEquals(View.VISIBLE, holder.inlineLegGroup.visibility)
        assertEquals(View.VISIBLE, holder.inlineLeg.visibility)
        assertTrue(holder.inlineLeg.batteryOutline)
        assertEquals(View.GONE, holder.trainMarker.visibility) // one source event, one logo
        assertTrue(holder.inlineLeg.contentDescription.contains("Banavar"))
        assertTrue(holder.inlineLeg.contentDescription.contains("11:47"))
        assertTrue(holder.inlineLeg.contentDescription.contains("9 min ago"))
        assertTrue(holder.inlineLeg.contentDescription.contains("present position unknown"))
        assertTrue(holder.inlineLegGroup.performClick())
        assertTrue("the disclosed battery cards do not need a tall hidden map canvas",
            holder.inlineLeg.showLabels)
        val width = (360f * ctx.resources.displayMetrics.density).toInt()
        holder.inlineLeg.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        val expectedHeight = (LegGeometry.BATTERY_EVENT_HEIGHT_DP *
            ctx.resources.displayMetrics.density + 0.5f).toInt()
        assertEquals("Battery-mode event marker should stay compact with many mapped points",
            expectedHeight, holder.inlineLeg.measuredHeight)
        holder.inlineLeg.layout(0, 0, width, holder.inlineLeg.measuredHeight)
        val bitmap = Bitmap.createBitmap(width, holder.inlineLeg.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.BLACK)
        holder.inlineLeg.paintForTest(canvas)
        var white = 0
        for (x in 0 until bitmap.width step 2) for (y in 0 until bitmap.height step 2)
            if (bitmap.getPixel(x, y) == Color.WHITE) white++
        assertTrue("white point-pen train and label were not painted", white > 35)
        bitmap.recycle()
        adapter.setProjectedLeg(null)
        adapter.onBindViewHolder(holder, 0)
        assertEquals(View.GONE, holder.inlineLeg.visibility)
        assertFalse(holder.inlineLeg.batteryOutline)
    }

    @Test fun batteryIntermediateDisclosureIsExplicitStaticAndKeepsTheStationRowsStable() {
        Prefs(ctx).batteryMode = true
        val adapter = StopAdapter().apply {
            batteryMode = true
            mapSource = "RailGeo"
            mappedAnchorKm = mapOf("AAA" to 0, "MID1" to 20, "BBB" to 40,
                "MID2" to 60, "CCC" to 80)
            setRoutePoints(mapOf(
                "AAA" to listOf(StopRow(2, "MID1", "Mapped One", null, null, null,
                    610, null, 1, 20, false)),
                "BBB" to listOf(StopRow(4, "MID2", "Mapped Two", null, null, null,
                    null, null, 1, 60, false)),
            ))
            websiteIntermediateReports = mapOf("MID1" to LiveStopFields(actualArrivalMin = 611))
        }
        val first = TrackModel.Stop(1, "AAA", "Alpha", 600, 602, 598, 600, null,
            TrackModel.MinuteSource.REPORTED, "2", TrackModel.State.PASSED,
            true, 0, 0, 80, false, false, false, null)
        val second = TrackModel.Stop(2, "BBB", "Beta", 620, 622, null, null, null,
            TrackModel.MinuteSource.SCHEDULE, null, TrackModel.State.NEXT,
            false, 40, 40, 40, false, false, false, null)
        val third = TrackModel.Stop(3, "CCC", "Gamma", 640, null, null, null, null,
            TrackModel.MinuteSource.SCHEDULE, null, TrackModel.State.NEXT,
            false, 80, 80, 0, false, false, false, null)
        adapter.submitList(listOf(first, second, third))
        val attachedList = RecyclerView(ctx)
        attachedList.adapter = adapter
        BatteryMode.paint(attachedList)
        assertEquals("Battery disclosures must not trigger item animations", null,
            attachedList.itemAnimator)
        Render.settle(attachedList, 3)
        val firstRow = adapter.onCreateViewHolder(FrameLayout(ctx), 0)
        val secondRow = adapter.onCreateViewHolder(FrameLayout(ctx), 0)
        adapter.onBindViewHolder(firstRow, 0)
        adapter.onBindViewHolder(secondRow, 1)
        val clocksBefore = listOf(firstRow.schedArr.text, firstRow.schedDep.text,
            firstRow.liveArr.text, firstRow.liveDep.text).map { it.toString() }

        assertEquals(View.VISIBLE, firstRow.inlineLegGroup.visibility)
        assertEquals("Show 1 intermediate stations ▾", firstRow.inlineLegCaption.text.toString())
        assertEquals(View.GONE, firstRow.batteryStations.visibility)
        assertEquals(View.GONE, firstRow.inlineLeg.visibility) // no animated route canvas
        assertTrue(firstRow.row.contentDescription.contains("tap to show 1 intermediate stations"))

        // The visible caption/leg is a full tap target. Text updates immediately, before
        // the next bind, and carries route/time/source distinctions.
        firstRow.inlineLegGroup.performClick()
        assertEquals(View.VISIBLE, firstRow.batteryStations.visibility)
        assertEquals("Hide intermediate stations ▴", firstRow.inlineLegCaption.text.toString())
        val firstDetails = textViews(firstRow.batteryStations).joinToString("\n") {
            it.text.toString()
        }
        val firstStationCards = (0 until firstRow.batteryStations.childCount)
            .map { firstRow.batteryStations.getChildAt(it) }.filterIsInstance<LinearLayout>()
        assertEquals("one distinct card for each intermediate station", 1, firstStationCards.size)
        assertTrue(firstDetails.contains("Intermediate stations · RailGeo"))
        assertTrue(firstDetails.contains("Map only · present position unknown."))
        assertTrue(firstDetails.contains("Timetable · listed 10:10"))
        assertTrue(firstDetails.contains("whereismytrain.org.in · claim · run inferred · unverified"))
        assertTrue(firstDetails.contains("Arrival 10:11 · departure —"))
        assertFalse(firstDetails.contains("Not independently verified."))
        assertFalse(firstDetails.contains("percent"))
        assertTrue(firstDetails.contains("present position unknown"))
        assertEquals(Color.WHITE, textViews(firstRow.batteryStations).first().currentTextColor)
        assertEquals(Color.WHITE, textViews(firstRow.batteryStations).last().currentTextColor)
        adapter.onBindViewHolder(firstRow, 0)
        assertEquals(clocksBefore, listOf(firstRow.schedArr.text, firstRow.schedDep.text,
            firstRow.liveArr.text, firstRow.liveDep.text).map { it.toString() })
        assertEquals(View.GONE, firstRow.inlineLeg.visibility)

        // Opening another leg folds the previous one; only its requested static details remain open.
        secondRow.inlineLegGroup.performClick()
        adapter.onBindViewHolder(firstRow, 0)
        adapter.onBindViewHolder(secondRow, 1)
        assertEquals(View.GONE, firstRow.batteryStations.visibility)
        assertEquals(View.VISIBLE, secondRow.batteryStations.visibility)
        val secondDetails = textViews(secondRow.batteryStations).joinToString("\n") {
            it.text.toString()
        }
        assertTrue(secondDetails.contains("Timetable · ~10:31"))
        assertTrue(secondDetails.contains("60 km map"))
        assertTrue(secondDetails.contains("present position unknown"))
        assertTrue(secondRow.row.contentDescription.contains("tap to hide 1 intermediate stations"))
        secondRow.inlineLegGroup.performClick()
        assertEquals(View.GONE, secondRow.batteryStations.visibility)
        assertEquals(0, secondRow.batteryStations.childCount)
        assertEquals("Show 1 intermediate stations ▾", secondRow.inlineLegCaption.text.toString())
    }

    @Test fun officialDepartureCueStaysAtLastTimedStopOnlyInBatteryMode() {
        Prefs(ctx).batteryMode = true
        val departed = TrackModel.Stop(1, "SMET", "Shimoga Town", 425, 430, 580, 588, null,
            TrackModel.MinuteSource.REPORTED, null, TrackModel.State.PASSED,
            true, 98, 0, 20, false, false, false, null)
        val upcoming = departed.copy(seq = 2, code = "SME", name = "Shimoga",
            showArr = null, showDep = null, source = TrackModel.MinuteSource.SCHEDULE,
            schedArr = 435, schedDep = 436, isAnchor = false)
        val leg = RouteBetween.LegProjection("Shimoga Town", "Shimoga", 0.2f,
            "Shimoga", 98, "SMET", "SME", emptyList(),
            RouteBetween.MarkerKind.REPORTED_DEPARTURE_CUE)
        val adapter = StopAdapter().apply { batteryMode = true }
        adapter.setProjectedLeg(leg)
        adapter.legAgeSeconds = 9 * 60L
        adapter.submitList(listOf(departed, upcoming))
        val holder = adapter.onCreateViewHolder(FrameLayout(ctx), 0)
        adapter.onBindViewHolder(holder, 0)
        assertEquals(View.VISIBLE, holder.trainMarker.visibility)
        assertEquals(View.GONE, holder.inlineLeg.visibility)
        assertTrue(holder.eventCaption.text.toString().contains("as of 09:48"))
        assertTrue(holder.eventCaption.text.toString().contains("NTES departure"))
        assertTrue(holder.trainMarker.contentDescription.contains("present position unknown"))
        Prefs(ctx).batteryMode = false
        val normal = StopAdapter().apply {
            batteryMode = false
            setProjectedLeg(leg)
            submitList(listOf(departed, upcoming))
        }
        val standard = normal.onCreateViewHolder(FrameLayout(ctx), 0)
        normal.onBindViewHolder(standard, 0)
        assertEquals(View.GONE, standard.trainMarker.visibility)
        assertEquals(View.VISIBLE, standard.inlineLeg.visibility)
        assertFalse(standard.inlineLeg.batteryOutline)
    }

    @Test fun aTimetableOrUnagedClaimNeverGetsABatteryLocomotive() {
        Prefs(ctx).batteryMode = true
        val adapter = StopAdapter().apply { batteryMode = true }
        val stop = TrackModel.Stop(1, "ASK", "Arsikere Jn", 530, 535, null, null, null,
            TrackModel.MinuteSource.SCHEDULE, null, TrackModel.State.NEXT,
            false, 206, 0, 50, false, false, false, null)
        adapter.haltMarker = TableMarker.Halt("ASK", "No accepted event", true)
        adapter.submitList(listOf(stop))
        val holder = adapter.onCreateViewHolder(FrameLayout(ctx), 0)
        adapter.onBindViewHolder(holder, 0)
        assertEquals(View.GONE, holder.trainMarker.visibility)
        assertEquals(View.GONE, holder.inlineLeg.visibility)
        assertEquals(View.GONE, holder.marker.visibility)
    }

    private fun pixel(v: View, x: Int, y: Int): Int {
        val bitmap = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(bitmap))
        return bitmap.getPixel(x, y).also { bitmap.recycle() }
    }
}
