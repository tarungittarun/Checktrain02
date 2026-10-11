package `in`.opentrack.india.ui

import `in`.opentrack.india.v2.contract.Provenance
import `in`.opentrack.india.v2.contract.SourceKind
import `in`.opentrack.india.v2.contract.TrainIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The details screen's live-identity overlay, as pure functions (the Activity computes nothing, 8.2):
 * when an erail identity reached the device, the label must say what the live source says the number
 * runs - and when it matches the device's timetable, say THAT too, because a confirmation is also a
 * fact. These cases are the three shapes the screen can meet, decided here so no screen arithmetic
 * can invent a fourth.
 */
class IdentityOverlayTest {

    private fun id(from: String, to: String) = TrainIdentity(
        number = "16221",
        name = "TLGP-MYS KUVEMPU EXPRESS",
        fromCode = from,
        toCode = to,
        provenance = Provenance(SourceKind.LIVE_PAIR, "erail", 1_000L),
    )

    @Test
    fun no_live_identity_leaves_the_timetable_label_alone() {
        assertNull(LiveViewModel.identityOverlay("SMET", "MYS", null))
    }

    @Test
    fun matching_identity_is_stated_as_a_confirmation_not_silence() {
        val overlay = LiveViewModel.identityOverlay("TLGP", "MYS", id("tlgp", "MYS"))
        assertNotNull(overlay)
        assertTrue("got: $overlay", overlay!!.contains("confirmed"))
        assertTrue("got: $overlay", overlay.contains("erail"))
    }

    @Test
    fun mismatching_identity_carries_both_readings_and_names_the_table_as_the_timetable_s() {
        val overlay = LiveViewModel.identityOverlay("SMET", "MYS", id("TLGP", "MYS"))
        assertNotNull(overlay)
        assertTrue("got: $overlay", overlay!!.contains("TLGP → MYS"))
        assertTrue("got: $overlay", overlay.contains("SMET"))
        assertTrue("got: $overlay", overlay.contains("timetable"))
    }
}
