package `in`.opentrack.india.ui

import `in`.opentrack.india.alarm.AlertState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JourneyAlertPresentationTest {
    @Test fun noActiveAlertKeepsTheSetupAction() {
        val presentation = journeyAlertPresentation(listOf(AlertState.FIRED, AlertState.DISMISSED))
        assertEquals("Alert", presentation.label)
        assertEquals("Configure an arrival alert for this journey", presentation.contentDescription)
        assertFalse(presentation.openAlerts)
    }

    @Test fun armedAlertsCanStillOfferAddAnotherOrCentralManagement() {
        val one = journeyAlertPresentation(listOf(AlertState.ARMED))
        assertEquals("Alert on", one.label)
        assertTrue(one.contentDescription.contains("tap to add another"))
        assertFalse(one.openAlerts)

        val several = journeyAlertPresentation(listOf(AlertState.ARMED, AlertState.ARMED))
        assertEquals("Alerts · 2", several.label)
        assertTrue(several.contentDescription.contains("2 alerts are monitoring"))
        assertFalse(several.openAlerts)
    }

    @Test fun ringingButtonRoutesDirectlyToPerAlertStopControls() {
        val one = journeyAlertPresentation(listOf(AlertState.RINGING))
        assertEquals("Ringing", one.label)
        assertTrue(one.contentDescription.contains("tap to open Alerts and stop each one independently"))
        assertTrue(one.openAlerts)

        val several = journeyAlertPresentation(listOf(
            AlertState.RINGING, AlertState.ARMED, AlertState.RINGING))
        assertEquals("Ringing · 2", several.label)
        assertTrue(several.contentDescription.contains("2 arrival alarms are ringing"))
        assertTrue(several.openAlerts)
    }
}
