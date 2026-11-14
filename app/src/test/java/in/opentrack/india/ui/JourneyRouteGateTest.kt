package `in`.opentrack.india.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JourneyRouteGateTest {
    @Test fun aReusedNumberCannotPaintAnUnrelatedDeviceRun() {
        assertFalse(JourneyRouteGate.covers(listOf("MYS", "CPT", "MAS"), "TLGP", "MYS"))
        assertFalse(JourneyRouteGate.covers(listOf("TLGP", "MYS", "MAS"), "MYS", "TLGP"))
        assertTrue(JourneyRouteGate.covers(listOf("TLGP", "SPV", "MYS"), "TLGP", "MYS"))
        assertTrue(JourneyRouteGate.covers(listOf("TLGP", "SPV", "MYS"), "SPV", "MYS"))
        assertTrue(JourneyRouteGate.covers(listOf("TLGP", "SPV", "MYS"), "", "MYS"))
    }

    @Test fun aPassengerSearchSegmentInsideTheFullRunIsNotAnIdentityConflict() {
        val full16228 = listOf(
            "TLGP", "SRF", "ANF", "ARU", "KMSI", "SMET", "SME", "BDVT", "TKE", "RRB",
            "ASK", "TTR", "TK", "YPR", "MWM", "SBC", "NYH", "KGI", "BID", "RMGM",
            "CPT", "MAD", "MYA", "Y", "PANP", "NHY", "MYS",
        )
        assertTrue(JourneyRouteGate.covers(full16228, "SMET", "SBC"))
        assertNull(JourneyRouteGate.missingJourneyNote("16228", full16228, "SMET", "SBC"))
    }

    @Test fun onlyAnUncoveredOrReversedPassengerSegmentGetsAnIncompleteRouteWarning() {
        val note = JourneyRouteGate.missingJourneyNote(
            "16228", listOf("TLGP", "SMET", "SBC", "MYS"), "MYS", "SMET")
        assertTrue(note!!.contains("Selected search segment MYS → SMET"))
        assertTrue(note.contains("timetable shown for train 16228"))
        assertTrue(note.contains("may be incomplete"))
        assertFalse(note.contains("wrong train", ignoreCase = true))
        assertNull(JourneyRouteGate.missingJourneyNote(
            "16228", listOf("TLGP", "SMET", "SBC", "MYS"), "", "SBC"))
    }
}
