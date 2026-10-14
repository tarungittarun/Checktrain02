package `in`.opentrack.india.ui

import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import `in`.opentrack.india.R
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
