package `in`.opentrack.india.alarm

import `in`.opentrack.india.model.SegmentRow
import `in`.opentrack.india.model.StopRow
import `in`.opentrack.india.model.TrainRoute
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test

class JourneyAlarmRouteSelectionTest {
    @Test fun theVisibleJourneyRouteWinsOverAnotherValidTrainNumberRoute() {
        val visible = route("12627", listOf("SBC", "TK", "SMET"))
        val bundled = route("12627", listOf("SBC", "ASK", "TK", "SMET"))

        assertSame(visible, JourneyAlarmRouteSelection.firstMatching(
            "12627", "sbc", "TK", visible, bundled))
    }

    @Test fun fallbackSkipsWrongTrainMissingEndpointsAndReversedJourneys() {
        val wrongTrain = route("12628", listOf("SBC", "TK", "SMET"))
        val missingBoarding = route("12627", listOf("ASK", "TK", "SMET"))
        val reversed = route("12627", listOf("SMET", "TK", "SBC"))
        val selected = route("12627", listOf("SBC", "TK", "SMET"))

        assertSame(selected, JourneyAlarmRouteSelection.firstMatching(
            "12627", "SBC", "SMET", wrongTrain, missingBoarding, reversed, selected))
        assertFalse(JourneyAlarmRouteSelection.matches(selected, "12628", "SBC", "SMET"))
        assertFalse(JourneyAlarmRouteSelection.matches(selected, "12627", "", "SMET"))
        assertFalse(JourneyAlarmRouteSelection.matches(null, "12627", "SBC", "SMET"))
    }

    private fun route(trainNo: String, codes: List<String>): TrainRoute {
        val stops = codes.mapIndexed { index, code ->
            StopRow(
                seq = index + 1,
                code = code,
                name = code,
                state = null,
                lat = null,
                lon = null,
                arrivalMin = index * 60,
                departureMin = index * 60 + 2,
                journeyDay = 1,
                schedKm = index * 100,
                isHalt = true,
            )
        }
        return TrainRoute(
            trainNo = trainNo,
            trainName = "Test train",
            srcCode = codes.first(),
            srcName = codes.first(),
            dstCode = codes.last(),
            dstName = codes.last(),
            totalKm = (codes.size - 1) * 100,
            totalMin = null,
            runDays = null,
            returnNo = null,
            stops = stops,
            segments = codes.zipWithNext().mapIndexed { index, (from, to) ->
                SegmentRow(index + 1, from, to, 100, 60)
            },
        )
    }
}
