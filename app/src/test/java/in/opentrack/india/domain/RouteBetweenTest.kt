package `in`.opentrack.india.domain

import `in`.opentrack.india.model.SourceKind
import `in`.opentrack.india.model.StopRow
import `in`.opentrack.india.model.StopStatus
import `in`.opentrack.india.model.TrainPosition
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

    @Test fun tapOnHaltRevealsOnlyNextLegAndLeavesPointUntimed() {
        val halts = route.copy(stops = route.stops.filter { it.isHalt }
            .mapIndexed { i, s -> s.copy(seq = i + 1) })
        val groups = RouteBetween.groupedPoints(route, halts)
        assertEquals(listOf("BBB"), groups["AAA"]?.map { it.code })
        assertTrue(groups["CCC"].isNullOrEmpty())
        assertEquals(null, groups["AAA"]?.first()?.arrivalMin)
        assertEquals(emptyMap<String, List<StopRow>>(),
            RouteBetween.groupedPoints(route.copy(trainNo = "99999"), halts))
    }

    @Test fun aPointWithImpossibleMapKmKeepsItsNameButLosesItsNumber() {
        val halts = route.copy(stops = route.stops.filter { it.isHalt }
            .mapIndexed { i, s -> s.copy(seq = i + 1) })
        val bad = route.copy(stops = route.stops.map { if (it.code == "BBB")
            it.copy(schedKm = 29) else it })
        assertEquals(-1, RouteBetween.groupedPoints(bad, halts)["AAA"]!!.single().schedKm)
    }

    @Test fun trainIconIsAProjectionNotAReportedIntermediateArrival() {
        val halts = route.copy(stops = route.stops.filter { it.isHalt }
            .mapIndexed { i, s -> s.copy(seq = i + 1) })
        fun position(source: SourceKind, age: Long = 120) = TrainPosition(
            anchorSeq = 1, nextSeq = 2, fraction = .5f, kmAlong = 10f,
            status = StopStatus.DEPARTED, etaNextMinute = null, delayMinutes = 0,
            remainingMin = null, provenance = source, confidence = TrainPosition.Confidence.LOW,
            ageSeconds = age)
        val projection = RouteBetween.projectLeg(halts, route, position(SourceKind.PROVIDER_NTES))!!
        assertEquals("Passing", projection.nearestPoint)
        assertEquals(10, projection.estimateKm)
        assertEquals("AAA", projection.fromCode)
        assertEquals("CCC", projection.toCode)
        assertEquals(.5f, projection.points.single().fraction, .001f)
        assertEquals(null, RouteBetween.projectLeg(halts, route,
            position(SourceKind.OFFLINE_SCHEDULE)))
        assertEquals(null, RouteBetween.projectLeg(halts, route,
            position(SourceKind.PROVIDER_NTES, 601)))
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
