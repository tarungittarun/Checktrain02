package `in`.opentrack.india.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RefreshPlacementTest {
    @Test fun `default centre sits seven eighths across and one fifth above bottom`() {
        val point = RefreshPlacement.at(400, 800, 48, 48, 12)
        assertEquals(350f, point.x + 24f, 0.1f)
        assertEquals(640f, point.y + 24f, 0.1f)
    }

    @Test fun `drag is confined to lower half and kept off all edges`() {
        val above = RefreshPlacement.drag(400, 800, 48, 48, 12, -900f, -900f)
        assertEquals(12f, above.x, 0.1f)
        assertEquals(400f, above.y, 0.1f)
        val corner = RefreshPlacement.drag(400, 800, 48, 48, 12, 900f, 900f)
        assertEquals(340f, corner.x, 0.1f)
        assertEquals(740f, corner.y, 0.1f)
        val moved = RefreshPlacement.drag(400, 800, 48, 48, 12, 260f, 430f)
        assertEquals(260f, moved.x, 0.1f)
        assertEquals(430f, moved.y, 0.1f)
        assertTrue(moved.y >= 400f)
    }

    @Test fun `saved relative position remains in allowed half after rotation`() {
        val rotated = RefreshPlacement.at(800, 400, 48, 48, 12, 0.75f, 0.68f)
        assertEquals(576f, rotated.x, 0.1f)
        assertEquals(248f, rotated.y, 0.1f)
    }
}
