package `in`.opentrack.india.alarm

import `in`.opentrack.india.core.RailTime
import `in`.opentrack.india.domain.PositionEngine
import `in`.opentrack.india.model.LiveStopFields
import `in`.opentrack.india.model.SegmentRow
import `in`.opentrack.india.model.SourceKind
import `in`.opentrack.india.model.StopRow
import `in`.opentrack.india.model.StopStatus
import `in`.opentrack.india.model.TrainPosition
import `in`.opentrack.india.model.TrainRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JourneyAlarmPolicyTest {
    private val day = 20_751L
    private val route = TrainRoute(
        trainNo = "12345", trainName = "TEST EXPRESS", srcCode = "MYS", srcName = "Mysuru",
        dstCode = "BNC", dstName = "Bengaluru Cantt", totalKm = 200, totalMin = 180,
        runDays = null, returnNo = null,
        stops = listOf(
            StopRow(1, "MYS", "Mysuru Jn", "KA", 12.3052, 76.6552,
                null, 600, 1, 0, true),
            StopRow(2, "KGI", "Kengeri", "KA", 12.9177, 77.4830,
                660, 665, 1, 100, true),
            StopRow(3, "SBC", "KSR Bengaluru", "KA", 12.9774, 77.5687,
                720, 725, 1, 150, true),
            StopRow(4, "BNC", "Bengaluru Cantt", "KA", 12.9941, 77.5998,
                740, null, 1, 200, true),
        ),
        segments = listOf(
            SegmentRow(1, "MYS", "KGI", 100, 60),
            SegmentRow(2, "KGI", "SBC", 50, 55),
            SegmentRow(3, "SBC", "BNC", 50, 15),
        ),
    )

    private fun config(
        trigger: AlertTrigger = AlertTrigger.PREVIOUS_STOP_REPORT,
        triggerCode: String = "KGI",
        triggerName: String = "Kengeri",
        triggerSeq: Int = 2,
        feedback: AlertFeedback = AlertFeedback.NOTIFICATION,
        distanceKm: Int = 0,
        createdMinute: Int = 650,
        baselineSeq: Int = 1,
        baselineMinute: Int? = null,
    ) = JourneyAlarmConfig(
        trainNo = "12345", trainName = "TEST EXPRESS", fromCode = "MYS",
        toCode = "SBC", toName = "KSR Bengaluru", toSeq = 3,
        trigger = trigger, triggerCode = triggerCode, triggerName = triggerName,
        triggerSeq = triggerSeq, distanceKm = distanceKm, feedback = feedback,
        intervalMinutes = 5, originEpochDay = day, baselineSeq = baselineSeq,
        baselineEventMinute = baselineMinute,
        createdAtMillis = RailTime.railMinuteToEpochMillis(day, createdMinute),
    )

    private fun answer(
        code: String = "KGI",
        seq: Int = 2,
        source: SourceKind = SourceKind.PROVIDER_NTES,
        actualArrival: Int? = null,
        actualDeparture: Int? = 665,
        fetchedAgeSeconds: Long = 30,
        feedAgeSeconds: Long? = 30,
        replyAtMillis: Long = now(670),
        feedStampOverride: Long? = null,
        held: Boolean = false,
    ): PositionEngine.Answer {
        val pos = TrainPosition(
            anchorSeq = seq, nextSeq = (seq + 1).coerceAtMost(4), fraction = 0f,
            kmAlong = 100f, status = StopStatus.DEPARTED, etaNextMinute = null,
            delayMinutes = 0, remainingMin = 50, provenance = source,
            confidence = TrainPosition.Confidence.HIGH, ageSeconds = fetchedAgeSeconds,
            feedAgeSeconds = feedAgeSeconds,
            feedStampMillis = feedStampOverride
                ?: feedAgeSeconds?.let { replyAtMillis - it * 1_000L },
        )
        return PositionEngine.Answer(
            position = pos, anchorStopCode = code, anchorStopName = code, note = null,
            perStop = if (actualArrival == null && actualDeparture == null) emptyMap() else mapOf(
                code to LiveStopFields(actualArrivalMin = actualArrival,
                    actualDepartureMin = actualDeparture)),
            held = held,
        )
    }

    private fun now(minute: Int) = RailTime.railMinuteToEpochMillis(day, minute)

    @Test fun freshNtessEventAtSelectedPrecedingStationCanTrigger() {
        val c = config()
        val decision = JourneyAlarmPolicy.stationEvent(c, route,
            answer(actualDeparture = 665), now(670))

        assertTrue(decision is JourneyAlarmPolicy.Decision.Fire)
        val fire = decision as JourneyAlarmPolicy.Decision.Fire
        assertTrue(fire.detail.contains("NTES running status reports departure at Kengeri"))
        assertTrue(fire.detail.contains("source-reported, not independently verified"))
        assertEquals(now(665), fire.evidenceAtMillis)
    }

    @Test fun genericProviderEvidenceCannotTriggerWithoutTheQualifiedWimtSourceType() {
        val decision = JourneyAlarmPolicy.stationEvent(config(), route,
            answer(source = SourceKind.PROVIDER, actualDeparture = 664), now(670),
            sourceDisplayName = "eRail running-status feed")

        assertTrue(decision is JourneyAlarmPolicy.Decision.Waiting)
        assertTrue((decision as JourneyAlarmPolicy.Decision.Waiting).detail
            .contains("NTES or WIMT"))
        assertEquals("Unidentified live provider",
            JourneyAlarmPolicy.sourceLabel(SourceKind.PROVIDER))
    }

    @Test fun strictWimtActualCanQualifyEvenWhenItsClockMatchesTheSchedule() {
        val c = config()
        val event = answer(source = SourceKind.PROVIDER, actualDeparture = 665)
        val genericProvider = JourneyAlarmPolicy.stationEvent(c, route, event, now(670))
        assertTrue(genericProvider is JourneyAlarmPolicy.Decision.Waiting)
        assertTrue((genericProvider as JourneyAlarmPolicy.Decision.Waiting).detail
            .contains("NTES or WIMT"))

        val wimt = JourneyAlarmPolicy.stationEvent(c, route, event, now(670),
            sourceDisplayName = "WIMT .in live-status feed",
            source = JourneyAlarmPolicy.AlertEvidenceSource.WIMT)
        assertTrue(wimt is JourneyAlarmPolicy.Decision.Fire)
        assertTrue((wimt as JourneyAlarmPolicy.Decision.Fire).detail
            .contains("WIMT .in live-status feed reports departure at Kengeri"))
    }

    @Test fun eventAtOrBeforeTheSetMinuteDoesNotTrigger() {
        val c = config(createdMinute = 665)
        val decision = JourneyAlarmPolicy.stationEvent(c, route,
            answer(actualDeparture = 665), now(670))

        assertTrue(decision is JourneyAlarmPolicy.Decision.Waiting)
        assertTrue((decision as JourneyAlarmPolicy.Decision.Waiting).detail.contains("at or before"))
    }

    @Test fun oldEventIsNotRebrandedAsFreshByARecentFetch() {
        val c = config()
        val decision = JourneyAlarmPolicy.stationEvent(c, route,
            answer(actualDeparture = 665, replyAtMillis = now(676)), now(676))

        assertTrue(decision is JourneyAlarmPolicy.Decision.Waiting)
        assertTrue((decision as JourneyAlarmPolicy.Decision.Waiting).detail.contains("11m old"))
    }

    @Test fun scheduleEchoAndNonRunningStatusSourcesCannotTrigger() {
        val c = config()
        val echo = JourneyAlarmPolicy.stationEvent(c, route,
            answer(source = SourceKind.PROVIDER, actualDeparture = 665), now(670))
        assertTrue(echo is JourneyAlarmPolicy.Decision.Waiting)
        assertTrue((echo as JourneyAlarmPolicy.Decision.Waiting).detail.contains("NTES or WIMT"))

        val cached = JourneyAlarmPolicy.stationEvent(c, route,
            answer(source = SourceKind.CACHED, actualDeparture = 664), now(670))
        assertTrue(cached is JourneyAlarmPolicy.Decision.Waiting)
        assertTrue((cached as JourneyAlarmPolicy.Decision.Waiting).detail.contains("route-matched"))

        val custom = JourneyAlarmPolicy.stationEvent(c, route,
            answer(source = SourceKind.PROVIDER_OVERRIDE, actualDeparture = 664), now(670))
        assertTrue(custom is JourneyAlarmPolicy.Decision.Waiting)
    }

    @Test fun outageQualificationRejectsStaleAndScheduleEchoDataButAllowsQualifiedLiveActuals() {
        val c = config()
        assertTrue(JourneyAlarmPolicy.hasUsableSourceData(c, route,
            answer(actualDeparture = 665), now(670)))
        assertTrue(!JourneyAlarmPolicy.hasUsableSourceData(c, route,
            answer(actualDeparture = 665, feedAgeSeconds = 26 * 60L), now(670)))
        val scheduleEcho = answer(source = SourceKind.PROVIDER, actualDeparture = 665)
        assertTrue(!JourneyAlarmPolicy.hasUsableSourceData(c, route, scheduleEcho, now(670)))
        assertTrue(JourneyAlarmPolicy.hasUsableSourceData(c, route, scheduleEcho, now(670),
            source = JourneyAlarmPolicy.AlertEvidenceSource.WIMT))
        assertTrue(!JourneyAlarmPolicy.hasUsableSourceData(c, route,
            answer(source = SourceKind.CACHED, actualDeparture = 665), now(670)))
    }

    @Test fun aFreshReplyCannotMakeAnOldSourceUpdateOrInconsistentEventFresh() {
        val c = config()
        val staleFeed = JourneyAlarmPolicy.stationEvent(c, route,
            answer(actualDeparture = 665, feedAgeSeconds = 26 * 60L), now(670))
        assertTrue(staleFeed is JourneyAlarmPolicy.Decision.Waiting)
        assertTrue((staleFeed as JourneyAlarmPolicy.Decision.Waiting).detail.contains("source update is old"))

        val stampBeforeEvent = JourneyAlarmPolicy.stationEvent(c, route,
            answer(actualDeparture = 665, feedStampOverride = now(664)), now(670))
        assertTrue(stampBeforeEvent is JourneyAlarmPolicy.Decision.Waiting)
        assertTrue((stampBeforeEvent as JourneyAlarmPolicy.Decision.Waiting).detail.contains("predates"))
        assertEquals(0, JourneyAlarmPolicy.baseline(c, route,
            answer(actualDeparture = 665, feedStampOverride = now(664)), now(670)).seq)

        val futureStamp = JourneyAlarmPolicy.stationEvent(c, route,
            answer(actualDeparture = 665, feedStampOverride = now(671)), now(670))
        assertTrue(futureStamp is JourneyAlarmPolicy.Decision.Waiting)
        assertTrue((futureStamp as JourneyAlarmPolicy.Decision.Waiting).detail.contains("in the future"))
    }

    @Test fun laterStationWithoutTriggerArrivalRemainsWaitingAndDoesNotClaimDelay() {
        val c = config()
        val after = answer(code = "SBC", seq = 3, actualArrival = 710,
            actualDeparture = null, replyAtMillis = now(715))
        val decision = JourneyAlarmPolicy.stationEvent(c, route, after, now(715))

        assertTrue(decision is JourneyAlarmPolicy.Decision.Waiting)
        val detail = (decision as JourneyAlarmPolicy.Decision.Waiting).detail
        assertTrue(detail.contains("does not confirm a qualifying station event at Kengeri"))
        assertTrue(detail.contains("Delay is unconfirmed; monitoring continues"))
        assertTrue(!detail.contains("No alarm was sent"))
    }

    @Test fun earlyDestinationArrivalFiresEvenWhenDepartureIsNotYetDue() {
        val c = config(AlertTrigger.DESTINATION_REPORT, "KGI", "Kengeri", 2,
            createdMinute = 650, baselineSeq = 1).copy(scheduledMinute = 660)
        val earlyArrival = answer(code = "KGI", seq = 2, actualArrival = 658,
            actualDeparture = 665, fetchedAgeSeconds = 1, feedAgeSeconds = 1,
            replyAtMillis = now(659))

        val decision = JourneyAlarmPolicy.stationEvent(c, route, earlyArrival, now(659))

        assertTrue("a qualifying early arrival must sound immediately: $decision",
            decision is JourneyAlarmPolicy.Decision.Fire)
        val fired = decision as JourneyAlarmPolicy.Decision.Fire
        assertTrue("early evidence should name the selected arrival: ${fired.detail}",
            fired.detail.contains("arrival at Kengeri"))
        assertEquals("the notice must be tied to the reported 10:58 arrival, before 11:00",
            now(658), fired.evidenceAtMillis)
    }

    @Test fun destinationDepartureWithoutArrivalIsUsableButCannotSoundArrivalAlert() {
        val c = config(AlertTrigger.DESTINATION_REPORT, "KGI", "Kengeri", 2,
            createdMinute = 650, baselineSeq = 1).copy(scheduledMinute = 660)
        val departureOnly = answer(code = "KGI", seq = 2, actualArrival = null,
            actualDeparture = 655, fetchedAgeSeconds = 1, feedAgeSeconds = 1,
            replyAtMillis = now(656))

        val decision = JourneyAlarmPolicy.stationEvent(c, route, departureOnly, now(656))

        assertTrue(decision is JourneyAlarmPolicy.Decision.Waiting)
        assertTrue((decision as JourneyAlarmPolicy.Decision.Waiting).detail
            .contains("No qualifying arrival report at Kengeri yet"))
        assertTrue("fresh live departure data is still a usable source answer",
            JourneyAlarmPolicy.hasUsableSourceData(c, route, departureOnly, now(656)))
    }

    @Test fun aFreshTargetEventStillFiresWhenTheCurrentAnchorHasPassedTheDestination() {
        val c = config(AlertTrigger.DESTINATION_REPORT, "SBC", "KSR Bengaluru", 3,
            createdMinute = 650, baselineSeq = 1)
        val laterAnchor = answer(code = "BNC", seq = 4, actualArrival = 728,
            actualDeparture = null, fetchedAgeSeconds = 1, feedAgeSeconds = 1,
            replyAtMillis = now(729)).copy(perStop = mapOf(
            "SBC" to LiveStopFields(actualArrivalMin = 725),
            "BNC" to LiveStopFields(actualArrivalMin = 728),
        ))

        val decision = JourneyAlarmPolicy.stationEvent(c, route, laterAnchor, now(729))

        assertTrue("a fresh target event must win over the later current anchor: $decision",
            decision is JourneyAlarmPolicy.Decision.Fire)
        assertEquals(now(725), (decision as JourneyAlarmPolicy.Decision.Fire).evidenceAtMillis)
    }

    @Test fun baselineAllowsAStationEventThatHappenedAfterTheUserSetTheAlert() {
        val c = config(createdMinute = 660)
        val baseline = JourneyAlarmPolicy.baseline(c, route, answer(actualDeparture = 665))
        assertEquals(1, baseline.seq)
        assertEquals(null, baseline.eventMinute)

        val decision = JourneyAlarmPolicy.stationEvent(c, route,
            answer(actualDeparture = 665), now(670))
        assertTrue(decision is JourneyAlarmPolicy.Decision.Fire)
    }

    @Test fun delayedButStillAheadStopIsEligibleAfterItsPublishedTimePassed() {
        val c = config(AlertTrigger.DESTINATION_REPORT, "SBC", "KSR Bengaluru", 3,
            createdMinute = 725).copy(scheduledMinute = 720)
        val current = answer(code = "KGI", seq = 2, actualDeparture = 728,
            fetchedAgeSeconds = 5, feedAgeSeconds = 5, replyAtMillis = now(731))

        val result = JourneyAlarmPolicy.setupEligibility(c, route, current, now(732),
            JourneyAlarmPolicy.AlertEvidenceSource.NTES)

        assertTrue("a past timetable clock cannot reject a fresh upcoming stop: $result",
            result is JourneyAlarmPolicy.SetupEligibility.Upcoming)
        assertEquals(2, (result as JourneyAlarmPolicy.SetupEligibility.Upcoming).baseline.seq)
        assertEquals("Kengeri", result.currentStation)
    }

    @Test fun currentPositionAtOrBeyondSelectedStopBlocksSetup() {
        val c = config(AlertTrigger.DESTINATION_REPORT, "SBC", "KSR Bengaluru", 3,
            createdMinute = 725).copy(scheduledMinute = 720)
        val atTarget = answer(code = "SBC", seq = 3, actualArrival = 730,
            actualDeparture = null, fetchedAgeSeconds = 5, feedAgeSeconds = 5,
            replyAtMillis = now(731))
        val pastTarget = answer(code = "BNC", seq = 4, actualArrival = 731,
            actualDeparture = null, fetchedAgeSeconds = 5, feedAgeSeconds = 5,
            replyAtMillis = now(732))

        val reached = JourneyAlarmPolicy.setupEligibility(c, route, atTarget, now(732),
            JourneyAlarmPolicy.AlertEvidenceSource.NTES)
        val passed = JourneyAlarmPolicy.setupEligibility(c, route, pastTarget, now(733),
            JourneyAlarmPolicy.AlertEvidenceSource.NTES)

        assertTrue(reached is JourneyAlarmPolicy.SetupEligibility.Reached)
        assertTrue(passed is JourneyAlarmPolicy.SetupEligibility.Reached)
    }

    @Test fun staleOrUnqualifiedPositionCannotAuthorizeAlertSetup() {
        val c = config(AlertTrigger.DESTINATION_REPORT, "SBC", "KSR Bengaluru", 3,
            createdMinute = 725).copy(scheduledMinute = 720)
        val stale = answer(code = "KGI", seq = 2, actualDeparture = 728,
            fetchedAgeSeconds = 700, feedAgeSeconds = 700, replyAtMillis = now(730))
        val genericProvider = answer(code = "KGI", seq = 2, source = SourceKind.PROVIDER,
            actualDeparture = 728, fetchedAgeSeconds = 5, feedAgeSeconds = 5,
            replyAtMillis = now(731))

        val staleResult = JourneyAlarmPolicy.setupEligibility(c, route, stale, now(732),
            JourneyAlarmPolicy.AlertEvidenceSource.NTES)
        val wrongSource = JourneyAlarmPolicy.setupEligibility(c, route, genericProvider, now(732),
            JourneyAlarmPolicy.AlertEvidenceSource.NTES)
        val wimtResult = JourneyAlarmPolicy.setupEligibility(c, route, genericProvider, now(732),
            JourneyAlarmPolicy.AlertEvidenceSource.WIMT)

        assertTrue(staleResult is JourneyAlarmPolicy.SetupEligibility.Unavailable)
        assertTrue(wrongSource is JourneyAlarmPolicy.SetupEligibility.Unavailable)
        assertTrue(wimtResult is JourneyAlarmPolicy.SetupEligibility.Upcoming)
    }

    @Test fun phoneDistanceNeedsFreshAccurateRouteMatchedMovement() {
        val c = config(AlertTrigger.PHONE_DISTANCE, "SBC", "KSR Bengaluru", 3,
            distanceKm = 5)
        val now = now(670)
        val fix = JourneyAlarmPolicy.PhoneFix(
            latitude = 12.9774, longitude = 77.5587, accuracyMeters = 25f,
            atMillis = now, speedKmh = 40f, movedMeters = 350f, matchesTrainRoute = true,
        )
        val fired = JourneyAlarmPolicy.distanceEvent(c, route, fix, now)
        assertTrue(fired is JourneyAlarmPolicy.Decision.Fire)
        assertTrue((fired as JourneyAlarmPolicy.Decision.Fire).detail.contains("not train GPS"))

        val stale = JourneyAlarmPolicy.distanceEvent(c, route, fix.copy(atMillis = now - 91_000), now)
        assertTrue(stale is JourneyAlarmPolicy.Decision.Waiting)
        val inaccurate = JourneyAlarmPolicy.distanceEvent(c, route,
            fix.copy(accuracyMeters = 500f), now)
        assertTrue(inaccurate is JourneyAlarmPolicy.Decision.Waiting)
        val stationary = JourneyAlarmPolicy.distanceEvent(c, route,
            fix.copy(speedKmh = 0f, movedMeters = 0f), now)
        assertTrue(stationary is JourneyAlarmPolicy.Decision.Waiting)
        val offRoute = JourneyAlarmPolicy.distanceEvent(c, route,
            fix.copy(matchesTrainRoute = false), now)
        assertTrue(offRoute is JourneyAlarmPolicy.Decision.Waiting)
    }

    @Test fun routeEndpointMismatchNeverTriggers() {
        val c = config()
        val mismatched = route.copy(stops = route.stops.filterNot { it.code == "SBC" })
        val decision = JourneyAlarmPolicy.stationEvent(c, mismatched,
            answer(actualDeparture = 665), now(670))
        assertTrue(decision is JourneyAlarmPolicy.Decision.Waiting)
    }
}
