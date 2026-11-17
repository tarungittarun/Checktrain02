package `in`.opentrack.india.ui

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackingSourceFanoutTest {
    @Test fun independentSourcesStartBeforeSlowOfficialRequestCompletes() = runTest {
        val order = mutableListOf<String>()
        val officialResponse = CompletableDeferred<String>()
        val load = async {
            TrackingSourceFanout.startBeforeOfficial(
                startIndependentSources = { order += "independent sources started" },
                fetchOfficial = {
                    order += "official request started"
                    officialResponse.await()
                },
            )
        }

        runCurrent()
        assertEquals(listOf("independent sources started", "official request started"), order)
        assertTrue("a slow official request must not hold the independent survey", !load.isCompleted)

        officialResponse.complete("NTES answer")
        assertEquals("NTES answer", load.await())
    }
}
