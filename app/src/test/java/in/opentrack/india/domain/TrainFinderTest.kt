package `in`.opentrack.india.domain

import `in`.opentrack.india.core.RailTime
import `in`.opentrack.india.model.PairStop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import java.time.LocalDate

/**
 * The ±24 h search rule, isolated from SQLite.
 *
 * This is the arithmetic that decides whether a traveller sees the train they need or "no trains
 * found", and it is where a naive `WHERE date = :day` query silently loses the two cases Indians
 * actually depend on: the overnight run that boarded yesterday evening, and the run that leaves just
 * after midnight for a search made before midnight.
 */
class TrainFinderTest {

    /** 2024-08-14 is a Wednesday; every case below is written against that date. */
    private val wed = LocalDate.of(2024, 8, 14).toEpochDay()

    private fun pair(
        no: String, board: Int, alight: Int, days: Int? = null,
        fromKm: Int = 0, toKm: Int = 500,
    ) = PairStop(
        trainNo = no, trainName = "$no TEST EXPRESS", trainType = "SUPERFAST", runDays = days,
        srcCode = "SRC", dstCode = "DST", fromSeq = 4, boardMinute = board, toSeq = 9,
        alightMinute = alight, fromKm = fromKm, toKm = toKm,
    )

    private fun find(vararg pairs: PairStop, day: Long = wed, minute: Int, limit: Int = 400) =
        TrainFinder.runsBetween(pairs.toList(), day, minute, "SRC", "DST", limit)

    @Test
    fun aRunThatBoardedLateYesterdayIsStillAnAnswerThisMorning() {
        val (hits, skips) = find(pair("12951", board = 1430, alight = 1900), minute = 10)
        assertEquals(1, hits.size)
        val h = hits[0]
        // Origin is *yesterday*, which is what the live view needs to key the correct run.
        assertEquals(wed - 1, h.originDateEpochDay)
        assertEquals(1430, h.boardMinute)
        assertEquals("23:50 → 07:40 (+1d)", h.prettyTimes())
        assertEquals(470, h.durationMin)
        // Yesterday's 23:50 is 20 min away; today's 23:50 is 23 h 40 m away. The closest wins, and
        // `break`-on-first-candidate-day is what the naive version gets wrong.
        assertEquals(1, skips.outOfWindow)   // the +24 h candidate, correctly rejected
    }

    @Test
    fun theRunThatLeftAMinuteAgoBeatsTheOneLeavingTomorrow() {
        // The −24 h half of the window exists for exactly this: someone searching at midnight for the
        // 23:59 train is asking about the one that is already running, and dropping it (or answering with
        // tomorrow's) is the failure mode the requirement was written to prevent.
        val (hits, _) = find(pair("12002", board = 1439, alight = 1900), day = wed + 1, minute = 0)
        assertEquals(1, hits.size)
        assertEquals(wed, hits[0].originDateEpochDay)
    }

    @Test
    fun anExactTieIsResolvedTowardsTheRunStillToCome() {
        // A 12:00 train, searched at midnight: yesterday's departure is 720 min behind, today's is 720
        // min ahead. Equidistant, so the tie-break decides — and it must land on the boardable one,
        // because the live screen keys its whole day calculation off this origin date.
        val (hits, _) = find(pair("12002", board = 720, alight = 900), minute = 0)
        assertEquals(1, hits.size)
        assertEquals(wed, hits[0].originDateEpochDay)
    }

    @Test
    fun trainsThatDoNotRunOnAnyCandidateDayAreCountedNotHidden() {
        // Monday-only (bit 1, since bit 0 is Sunday) with Tue/Wed/Thu candidates: no hit, but the
        // reason is reported, which is what turns "no trains" into "12951 runs only on Mon".
        val (hits, skips) = find(pair("12951", board = 600, alight = 900, days = 2), minute = 600)
        assertTrue(hits.isEmpty())
        assertEquals(3, skips.wrongDate)
        assertEquals(listOf("12951"), skips.wrongDateTrains)
    }

    @Test
    fun aTrainRunningOnAnAdjacentCandidateDayIsKeptWithThatDate() {
        // Runs Tue/Wed only: for a Wednesday 23:00 search the Tuesday-evening run is a legal answer,
        // and the origin must be Tuesday so the live screen does not show Wednesday's non-run.
        val (hits, _) = find(pair("12621", board = 1380, alight = 1900, days = 0b0000_0110), minute = 1380)
        assertEquals(1, hits.size)
        assertEquals(wed - 1, hits[0].originDateEpochDay)
    }

    @Test
    fun rowsWhereTheAlightingStopComesFirstAreDroppedSilently() {
        // Either the reverse direction or a corrupt schedule row; neither belongs in the result list,
        // and neither should be counted as "outside the window", which would make the hint lie.
        val (hits, skips) = find(pair("99999", board = 600, alight = 590), minute = 600)
        assertTrue(hits.isEmpty())
        assertEquals(0, skips.outOfWindow)
        assertEquals(0, skips.wrongDate)
    }

    @Test
    fun orderedByClosenessToTheSearchedInstantThenByBoardingTime() {
        val (hits, _) = find(
            pair("11111", board = 480, alight = 900),      // 08:00
            pair("22222", board = 540, alight = 900),      // 09:00
            pair("33333", board = 1200, alight = 1500),    // 20:00
            minute = 600,                                   // search for 10:00
        )
        assertEquals(listOf("22222", "11111", "33333"), hits.map { it.trainNo })
    }

    @Test
    fun limitTrimsTheTailNotTheMiddle() {
        val pairs = (1..5).map { pair("1000$it", board = 300 + it * 60, alight = 1200) }
        val (hits, _) = find(*pairs.toTypedArray(), minute = 300, limit = 2)
        assertEquals(2, hits.size)
        assertEquals(listOf("10001", "10002"), hits.map { it.trainNo })
    }

    @Test
    fun distanceIsTheSegmentDeltaNotTheWholeRun() {
        val (hits, _) = find(pair("12951", board = 600, alight = 900, fromKm = 400, toKm = 900), minute = 600)
        assertEquals(500, hits[0].distanceKm)
        assertEquals(4, hits[0].fromSeq)
        assertEquals(9, hits[0].toSeq)
    }

    @Test
    fun availabilityChipsUseTheSameRunRuleAsTheResults() {
        // The date chips must not promise a day the search then fails to honour: both call sites use
        // the noon probe, so a Monday-only train reads "1" on Monday and "0" everywhere else.
        val mondayOnly = pair("12951", board = 600, alight = 900, days = 2)
        val days = TrainFinder.availability(listOf(mondayOnly), wed, back = 2, forward = 2)
        assertEquals(5, days.size)
        val byEpoch = days.associate { it.epochDay to it.trains }
        assertEquals(0, byEpoch[wed])       // Wednesday
        assertEquals(0, byEpoch[wed + 1])
        assertEquals(1, byEpoch[wed - 2])   // Monday
        assertEquals(
            RailTime.istWeekdayOfEpochDay(wed - 2),
            RailTime.istWeekdayOfEpochDay(LocalDate.of(2024, 8, 12).toEpochDay())
        )
    }

    @Test
    fun nullRunDaysNeverFiltersATrainOut() {
        // 21 trains in the shipped DB have no calendar at all; hiding them would be worse than showing
        // a train that might not run today, so `null` means "we do not know, assume it runs".
        val (hits, skips) = find(pair("12345", board = 600, alight = 900, days = null), minute = 600)
        assertEquals(1, hits.size)
        assertEquals(0, skips.wrongDate)
    }
}
