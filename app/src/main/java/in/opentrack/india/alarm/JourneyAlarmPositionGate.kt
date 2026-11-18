package `in`.opentrack.india.alarm

import `in`.opentrack.india.data.RailRepository
import `in`.opentrack.india.model.TrainRoute
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/** Fresh, direct-source gate shared by new-alert setup and station changes in Alerts. */
object JourneyAlarmPositionGate {
    suspend fun verifyUpcoming(
        repo: RailRepository,
        config: JourneyAlarmConfig,
        route: TrainRoute,
    ): JourneyAlarmPolicy.SetupEligibility = coroutineScope {
        val ntes = async {
            probe("NTES") {
                repo.probeNtesStatus(config.trainNo, route, config.originEpochDay,
                    config.fromCode, config.toCode)
            }
        }
        val wimt = async {
            probe("WIMT") {
                repo.probeWimtStatus(config.trainNo, route, config.originEpochDay)
            }
        }
        val results = listOf(
            JourneyAlarmPolicy.AlertEvidenceSource.NTES to ntes.await(),
            JourneyAlarmPolicy.AlertEvidenceSource.WIMT to wimt.await(),
        )
        val evaluated = results.mapNotNull { (source, probe) ->
            val live = probe.live ?: return@mapNotNull null
            source to JourneyAlarmPolicy.setupEligibility(config, live.route, live.answer,
                System.currentTimeMillis(), source)
        }
        evaluated.firstOrNull { it.second is JourneyAlarmPolicy.SetupEligibility.Reached }
            ?.let { return@coroutineScope it.second }

        val conflict = evaluated.firstOrNull {
            val result = it.second as? JourneyAlarmPolicy.SetupEligibility.Unavailable
            result?.detail?.contains("inconsistent event", ignoreCase = true) == true
        }
        if (conflict != null) return@coroutineScope conflict.second

        val upcoming = evaluated.mapNotNull { it.second as? JourneyAlarmPolicy.SetupEligibility.Upcoming }
            .maxWithOrNull(compareBy<JourneyAlarmPolicy.SetupEligibility.Upcoming> {
                it.baseline.seq
            }.thenBy { it.baseline.eventMinute ?: Int.MIN_VALUE }
                .thenBy { if (it.source == JourneyAlarmPolicy.AlertEvidenceSource.NTES) 1 else 0 })
        if (upcoming != null) return@coroutineScope upcoming

        val reasons = buildList {
            results.forEach { (_, result) -> result.failure?.let(::add) }
            evaluated.mapNotNull { (_, eligibility) ->
                (eligibility as? JourneyAlarmPolicy.SetupEligibility.Unavailable)?.detail
            }.forEach(::add)
        }.distinct().take(3)
        JourneyAlarmPolicy.SetupEligibility.Unavailable(
            reasons.joinToString(" ").ifBlank {
                "Neither NTES nor WIMT returned a fresh, route-matched current station."
            }.take(420),
        )
    }

    private suspend fun probe(
        source: String,
        request: suspend () -> RailRepository.SourceProbe,
    ): RailRepository.SourceProbe = try {
        request()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        RailRepository.SourceProbe(null,
            "$source position check failed (${failure.javaClass.simpleName})")
    }
}
