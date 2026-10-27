package `in`.opentrack.india.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import `in`.opentrack.india.data.Prefs
import `in`.opentrack.india.domain.TrackModel
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

    @Test fun batteryRowKeepsScheduleActualProvenanceAndWebsiteGapWithoutAnimatedArrow() {
        Prefs(ctx).batteryMode = true
        val adapter = StopAdapter().apply { batteryMode = true }
        val stop = TrackModel.Stop(1, "ASK", "Arsikere Jn", 1543, 1545, 1538, null, null,
            TrackModel.MinuteSource.REPORTED, "2", TrackModel.State.HERE,
            true, 304, 0, 200, false, false, false, null)
        adapter.haltMarker = TableMarker.Halt("ASK", "Last NTES arrival at ASK", false)
        adapter.submitList(listOf(stop))
        val holder = adapter.onCreateViewHolder(FrameLayout(ctx), 0)
        adapter.onBindViewHolder(holder, 0)
        assertEquals("A 01:43", holder.schedArr.text.toString())
        assertEquals("A 01:38", holder.liveArr.text.toString())
        assertEquals("D END", holder.liveDep.text.toString())
        assertTrue(holder.liveArr.contentDescription.contains("Reported arrival"))
        assertEquals(Color.WHITE, holder.liveArr.currentTextColor)
        assertEquals(View.GONE, holder.trainMarker.visibility)
        assertEquals(View.VISIBLE, holder.marker.visibility)
        assertTrue(holder.marker.text.toString().contains("NTES"))
        assertEquals(null, holder.liveArr.compoundDrawables[0])
    }

    private fun pixel(v: View, x: Int, y: Int): Int {
        val bitmap = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(bitmap))
        return bitmap.getPixel(x, y).also { bitmap.recycle() }
    }
}
