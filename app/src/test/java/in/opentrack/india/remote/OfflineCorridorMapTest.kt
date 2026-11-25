package `in`.opentrack.india.remote

import `in`.opentrack.india.domain.RouteBetween
import `in`.opentrack.india.model.StopRow
import `in`.opentrack.india.model.TrainRoute
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OfflineCorridorMapTest {
    private val json by lazy {
        RuntimeEnvironment.getApplication().assets.open(OfflineCorridorMap.ASSET)
            .bufferedReader().use { it.readText() }
    }
    // A 29-halt fixture along the old infrastructure, NOT a claimed transcript of
    // today's NTES schedule. The real dated timetable is passed at runtime instead.
    private val codes = listOf("MYS", "NHY", "PANP", "Y", "MYA", "MAD", "CPT", "RMGM",
        "KGI", "SBC", "MWM", "YPR", "TK", "TTR", "ASK", "DRU", "RRB", "TKE",
        "BDVT", "SME", "SMET", "HNHL", "ARU", "ANF", "SRF", "TLGP")
    private fun official(stations: List<String> = codes): TrainRoute {
        val km = JSONObject(json).getJSONArray("points")
        val byCode = (0 until km.length()).associate { i ->
            val row = km.getJSONArray(i)
            row.getString(0) to (row.getInt(2) * 510 / 512)
        }
        val stops = stations.mapIndexed { i, c ->
            StopRow(i + 1, c, c, null, null, null, 900 + i * 8, 901 + i * 8,
                1, byCode[c] ?: i * 15, true)
        }
        return TrainRoute("16227", "Current official timetable", "MYS", "Mysuru", "TLGP",
            "Talguppa", 510, 500, null, null, stops, emptyList())
    }

    @Test fun bundledCorridorHasSourceAndGivesCurrentHaltsTheirOwnSchedule() {
        val result = OfflineCorridorMap.parse(json, official())
        assertNotNull("bundled corridor must be inside the packaged APK", result)
        val map = result!!.route
        val groups = RouteBetween.groupedPoints(map, official())
        assertTrue("YPR–TK must work even when live map services fail", groups["YPR"].orEmpty().size >= 8)
        assertEquals(listOf("HEI", "MLSA", "GBB", "NTR", "SPGR", "AMSA", "BSN",
            "ARGP", "RDI", "BNK"), groups["TK"].orEmpty().map { it.code })
        assertEquals(listOf("SPV", "KRNH"), groups["RRB"].orEmpty().map { it.code })
        assertEquals(emptyList<String>(), groups["MWM"].orEmpty().map { it.code })
        assertTrue("a mapped empty leg must not open an unavailable dialog",
            "MWM" in result.mappedLegStarts)
        assertEquals(codes, map.stops.filter { it.isHalt }.map { it.code })
        assertTrue(map.stops.filterNot { it.isHalt }.all {
            it.actualArrivalMin == null && it.actualDepartureMin == null &&
                it.arrivalMin == null && it.departureMin == null
        })
        assertTrue(groups["TK"].orEmpty().zipWithNext().all { (a, b) -> a.schedKm < b.schedKm })
    }

    private fun official20652(): TrainRoute {
        val points = JSONObject(json).getJSONArray("points")
        val sourceKm = (0 until points.length()).associate { i ->
            val row = points.getJSONArray(i)
            row.getString(0) to row.getInt(2)
        }
        val codes20652 = listOf("TLGP", "SRF", "ANF", "ARU", "KMSI", "SMET", "SME",
            "BDVT", "TKE", "RRB", "DRU", "ASK", "TTR", "TK", "YPR", "SBC")
        fun km(code: String): Int? = sourceKm[code]?.let { 512 - it }
        val kmByCode = codes20652.map { code -> code to km(code) }
            .toMap().toMutableMap().apply {
                // The historical map has no KMSI point. The fixture places the dated
                // halt between its route neighbours; the parser must leave those two
                // legs unmapped instead of pretending KMSI was in the old corridor.
                put("KMSI", ((km("ARU")!! + km("SMET")!!) / 2))
            }
        val stops = codes20652.mapIndexed { index, code ->
            val routeKm = (kmByCode.getValue(code)!! * 372.0 / 369.0).toInt()
            StopRow(index + 1, code, code, null, null, null, null, null,
                0, routeKm, true)
        }
        return TrainRoute("20652", "TLGP SBC EXP", "TLGP", "Talguppa", "SBC",
            "Bengaluru", 372, 360, null, null, stops, emptyList())
    }

    @Test fun reverse20652RouteMapsOnlyConsecutiveHaltLegsThatMatchTheArchive() {
        val route = official20652()
        val result = OfflineCorridorMap.parse(json, route)
        assertNotNull("the exact reversed TLGP–SBC corridor should be reusable", result)
        val map = result!!.route
        assertNotNull("the expanded route must still match every dated scheduled halt",
            RouteBetween.withVerifiedHalts(map, route))
        val groups = RouteBetween.groupedPoints(map, route)
        assertEquals("Kadur–Arsikere's archived pass-through sequence",
            listOf("BLKR", "VNR", "BVR"), groups["DRU"].orEmpty().map { it.code })
        assertEquals(listOf("BALLEKERE HALT", "DEVANUR", "BANAVAR"),
            groups["DRU"].orEmpty().map { it.name })
        assertTrue("the formerly missing leg must be marked as mapped",
            "DRU" in result.mappedLegStarts)
        assertFalse("the archived map lacks KMSI; don't claim either adjoining leg is mapped",
            "ARU" in result.mappedLegStarts || "KMSI" in result.mappedLegStarts)
        assertTrue("historical map points must not invent clocks or actuals",
            groups["DRU"].orEmpty().all { point ->
                point.arrivalMin == null && point.departureMin == null &&
                    point.actualArrivalMin == null && point.actualDepartureMin == null &&
                    !point.isHalt
            })
    }

    @Test fun aChangedJunctionDoesNotSilentlyAdoptAnOldPath() {
        val changed = official(codes.toMutableList().apply { add(indexOf("TK") + 1, "NEWJ") })
        val result = OfflineCorridorMap.parse(json, changed)!!
        assertFalse("changed leg must remain explicitly unmapped", "TK" in result.mappedLegStarts)
        assertFalse("unknown station must not become invented pass-through data",
            "NEWJ" in result.mappedLegStarts)
        assertTrue(RouteBetween.groupedPoints(result.route, changed)["TK"].isNullOrEmpty())
        assertTrue("other independently matched legs must still work", "RRB" in result.mappedLegStarts)
        assertNull(OfflineCorridorMap.parse(json, official().copy(dstCode = "SMET")))
        assertNull(OfflineCorridorMap.parse(json, official().copy(trainNo = "16228")))
        assertNull(OfflineCorridorMap.parse("<html>maintenance</html>", official()))
    }
}
