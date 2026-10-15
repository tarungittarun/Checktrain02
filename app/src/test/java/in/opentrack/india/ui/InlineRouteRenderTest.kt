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
        assertTrue(holder.inlineLegCaption.text.contains("not GPS"))
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
        assertTrue(holder.inlineLegCaption.text.contains("NTES last reported Arasalu"))
        assertTrue(holder.inlineLegCaption.text.contains("between-stop position unknown"))
        assertEquals(12 * 60L, holder.inlineLeg.ageSeconds)
        assertEquals(0f, holder.inlineLeg.projection!!.fraction, .001f)
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
        assertTrue(holder.inlineLegCaption.text.contains("between-stop position unknown"))
        assertTrue(holder.inlineLeg.contentDescription.contains("no measured distance since"))
        assertEquals(0f, holder.inlineLeg.projection!!.fraction, .001f)
        assertEquals(9 * 60L, holder.inlineLeg.ageSeconds)
        adapter.legAgeSeconds = 70 * 60L
        adapter.onBindViewHolder(holder, 0)
        assertEquals(70 * 60L, holder.inlineLeg.ageSeconds)
        assertEquals(0f, holder.inlineLeg.projection!!.fraction, .001f)
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
        assertEquals(View.GONE, holder.intermediateGroup.visibility)
        holder.row.performClick()
        adapter.onBindViewHolder(holder, 0)
        assertEquals(View.VISIBLE, holder.intermediateGroup.visibility)
        val text = (holder.intermediateGroup.getChildAt(0) as TextView).text.toString()
        assertTrue(text.contains("Malleswaram"))
        assertEquals("• Malleswaram · 368 km", text)
        assertFalse(text.contains("Act "))
        holder.row.performClick()
        adapter.onBindViewHolder(holder, 0)
        assertEquals(View.GONE, holder.intermediateGroup.visibility)
    }
}
