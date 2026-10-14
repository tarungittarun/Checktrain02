package `in`.opentrack.india.domain

import `in`.opentrack.india.model.StopRow
import `in`.opentrack.india.model.TrainRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteBetweenTest {
    private val route = TrainRoute(
        trainNo = "20652", trainName = "Test", srcCode = "AAA", srcName = "AAA",
        dstCode = "DDD", dstName = "DDD", totalKm = 40, totalMin = null,
        runDays = 127, returnNo = null,
        stops = listOf(
            StopRow(1, "AAA", "Start", null, null, null, null, 600, 0, 0, true),
            StopRow(2, "BBB", "Passing", null, null, null, null, null, 0, 10, false),
            StopRow(3, "CCC", "Halt", null, null, null, 700, 705, 0, 20, true),
            StopRow(4, "DDD", "End", null, null, null, 800, null, 0, 40, true),
        ), segments = emptyList(),
    )

    @Test fun officialScheduleUpgradesOnlyTheHaltsItActuallyConfirms() {
        val official = route.copy(stops = route.stops.map { if (it.code == "BBB")
            it.copy(isHalt = true, arrivalMin = 645, departureMin = 646) else it })
        val reconciled = RouteBetween.withVerifiedHalts(route, official)!!
        assertTrue(reconciled.stops[1].isHalt)
        assertEquals(645, reconciled.stops[1].arrivalMin)
        assertEquals(listOf("AAA", "BBB", "CCC", "DDD"), reconciled.stops.map { it.code })
        assertEquals(null, RouteBetween.withVerifiedHalts(route, official.copy(trainNo = "20689")))
    }

    @Test fun arbitraryPairUsesTrainOrderWithoutReadingPosition() {
        assertEquals(listOf("AAA", "BBB", "CCC"),
            RouteBetween.inclusive(route, "CCC", "AAA").map { it.code })
        assertEquals(listOf("BBB", "CCC", "DDD"),
            RouteBetween.inclusive(route, "BBB", "DDD").map { it.code })
        assertTrue(RouteBetween.inclusive(route, "X", "AAA").isEmpty())
        val passing = RouteBetween.inclusive(route, "AAA", "CCC")[1]
        assertTrue(!passing.isHalt && passing.arrivalMin == null && passing.departureMin == null)
    }
}
