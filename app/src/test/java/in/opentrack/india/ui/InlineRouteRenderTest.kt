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
        assertTrue(holder.inlineLegCaption.text.contains("separate static schedule"))
        assertTrue(holder.report.text.contains("13:13"))
        assertEquals("Act 13:12", holder.liveArr.text.toString())
        adapter.setProjectedLeg(null)
        adapter.onBindViewHolder(holder, 0)
        assertEquals(View.GONE, holder.inlineLegGroup.visibility)
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
        assertTrue(text.contains("halt unconfirmed"))
        assertFalse(text.contains("Act "))
        holder.row.performClick()
        adapter.onBindViewHolder(holder, 0)
        assertEquals(View.GONE, holder.intermediateGroup.visibility)
    }
}
