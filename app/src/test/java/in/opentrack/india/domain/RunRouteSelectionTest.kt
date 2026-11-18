package `in`.opentrack.india.domain

import `in`.opentrack.india.model.StopRow
import `in`.opentrack.india.model.TrainRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RunRouteSelectionTest {
    private fun route(trainNo: String, vararg codes: String): TrainRoute {
        val stops = codes.mapIndexed { index, code ->
            StopRow(
                seq = index + 1,
                code = code,
                name = code,
                state = null,
                lat = null,
                lon = null,
                arrivalMin = null,
                departureMin = null,
                journeyDay = 0,
                schedKm = index * 10,
                isHalt = true,
            )
        }
        return TrainRoute(
            trainNo = trainNo,
            trainName = trainNo,
            srcCode = codes.first(),
            srcName = codes.first(),
            dstCode = codes.last(),
            dstName = codes.last(),
            totalKm = (codes.size - 1) * 10,
            totalMin = null,
            runDays = null,
            returnNo = null,
            stops = stops,
            segments = emptyList(),
        )
    }

    @Test fun staleNumberMatchCannotHideTheSelectedRouteOrItsLiveIdentityScaffold() {
        val bundled = route("16227", "SBC", "SMET")
        val liveIdentityScaffold = route("16227", "MYS", "SRF", "TLGP")

        assertFalse(RunRouteSelection.covers(bundled, "MYS", "TLGP"))
        assertSame(liveIdentityScaffold, RunRouteSelection.firstCovering(
            listOf(bundled, liveIdentityScaffold), "MYS", "TLGP"))
        assertNull(RunRouteSelection.firstCovering(listOf(bundled), "MYS", "TLGP"))
    }

    @Test fun onlyOrderedSelectedSegmentsQualifyAndSourcePriorityIsPreserved() {
        val full = route("16227", "MYS", "SRF", "TLGP")
        assertTrue(RunRouteSelection.covers(full, "MYS", "TLGP"))
        assertTrue(RunRouteSelection.covers(full, "SRF", "TLGP"))
        assertFalse(RunRouteSelection.covers(full, "TLGP", "MYS"))
        assertEquals(full, RunRouteSelection.firstCovering(
            listOf(full, route("16227", "MYS", "TLGP", "SRF")), "MYS", "TLGP"))
    }
}
