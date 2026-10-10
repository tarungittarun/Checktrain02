package `in`.opentrack.india.ui

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import `in`.opentrack.india.App
import `in`.opentrack.india.R
import `in`.opentrack.india.core.RailTime
import `in`.opentrack.india.databinding.ActivityTrainBinding
import `in`.opentrack.india.domain.PositionEngine
import `in`.opentrack.india.model.SourceKind

/**
 * Screen 2 of 3 — the live view: where the train is against where the timetable says it should be,
 * plus the stop-by-stop comparison underneath.
 *
 * The header states the provenance in the same visual weight as the position itself. That is not
 * decoration: a tracker that shows a stale marker without saying so is the specific failure this
 * project exists to avoid.
 */
class TrainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTrainBinding
    private lateinit var vm: LiveViewModel
    private lateinit var stopAdapter: StopAdapter
    private var fullRoute = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val trainNo = intent.getStringExtra(EXTRA_TRAIN_NO).orEmpty()
        if (trainNo.length < 4) { finish(); return }
        binding = ActivityTrainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val app = application as App
        val from = intent.getStringExtra(EXTRA_FROM).orEmpty()
        val to = intent.getStringExtra(EXTRA_TO).orEmpty()
        val originDay = intent.getLongExtra(EXTRA_ORIGIN_EPOCH_DAY, RailTime.todayEpochDay())
        vm = ViewModelProvider(
            this,
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
                    LiveViewModel(app, trainNo, from, to, originDay) as T
            }
        )[LiveViewModel::class.java]

        binding.title.text = trainNo
        binding.subtitle.text = if (from.isNotEmpty() && to.isNotEmpty()) "$from → $to" else "full route"
        binding.date.text = "run of ${RailTime.formatDate(originDay)}"

        stopAdapter = StopAdapter(
            currentSeq = { binding.track.currentSeq },
            highlight = { fullRoute || (it.seq >= vm.fromSeq() && it.seq <= vm.toSeq()) },
        )
        binding.stops.layoutManager = LinearLayoutManager(this)
        binding.stops.adapter = stopAdapter
        binding.stops.isNestedScrollingEnabled = false

        binding.refresh.setOnClickListener { vm.refresh() }
        binding.toggle.setOnClickListener {
            fullRoute = !fullRoute
            binding.toggle.setText(if (fullRoute) R.string.show_my_segment else R.string.show_full_route)
            vm.ui.value?.let { bind(it) }
        }
        binding.toggle.setText(R.string.show_full_route)
        binding.back.setOnClickListener { finish() }

        vm.ui.observe(this) { it?.let { u -> bind(u) } }
        vm.error.observe(this) { msg ->
            if (msg != null) {
                binding.status.text = msg
                binding.status.visibility = View.VISIBLE
            }
        }
    }

    private fun bind(ui: LiveViewModel.Ui) {
        val pos = ui.answer.position
        val route = ui.route
        binding.headline.text = PositionEngine.describe(pos, route)
        binding.provenance.text = ui.answer.note ?: pos.provenance.label
        binding.provenance.setBackgroundColor(
            when {
                pos.provenance.live -> 0x1F4CAF50
                pos.provenance == SourceKind.NONE -> 0x1FFF5252
                else -> 0x1FFFC107
            }
        )
        binding.age.text = when {
            pos.ageSeconds <= 0 -> "live"
            pos.ageSeconds < 60 -> "updated ${pos.ageSeconds}s ago"
            pos.ageSeconds < 3600 -> "updated ${pos.ageSeconds / 60}m ago"
            else -> "updated ${pos.ageSeconds / 3600}h ago"
        }
        val delay = pos.delayMinutes
        binding.delay.text = when {
            delay == null -> "delay unknown"
            else -> RailTime.formatDelay(delay)
        }
        binding.eta.text = pos.etaNextMinute?.let {
            "next stop at ${RailTime.formatRailMinute(it)}"
        } ?: "next-stop time not published"
        binding.remaining.text = pos.remainingMin?.let {
            " ${RailTime.formatDuration(it)} to ${route.dstName}"
        } ?: ""
        binding.progressValue.text = if (pos.kmAlong > 0f && route.totalKm > 0) {
            "%.0f / %d km".format(pos.kmAlong, route.totalKm)
        } else {
            "—"
        }

        val visible = if (fullRoute) ui.stops else ui.stops.filter {
            it.seq >= (ui.boarding?.seq ?: 0) && it.seq <= (ui.alighting?.seq ?: Int.MAX_VALUE)
        }
        stopAdapter.submitList(visible)
        binding.track.setJourney(
            stops = ui.stops, position = pos, totalKm = route.totalKm,
            boardingSeq = ui.boarding?.seq ?: route.stops.firstOrNull()?.seq ?: 0,
            alightingSeq = ui.alighting?.seq ?: route.stops.lastOrNull()?.seq ?: Int.MAX_VALUE,
            nowRailMinute = ui.nowRailMinute,
        )
        binding.track.currentSeq = pos.anchorSeq
        // Which provider answered, in plain words. Debuggable provenance (rule C5) without a log.
        if (ui.tried.isEmpty()) {
            binding.status.visibility = View.GONE
        } else {
            binding.status.visibility = View.VISIBLE
            binding.status.text = ui.tried.joinToString("  ") {
                it.replace("_", " ").lowercase().replace("=hit", " ok").replace("=miss", " –")
            }
        }
    }

    override fun onStart() {
        super.onStart()
        vm.start()
    }

    override fun onStop() {
        vm.stop()
        super.onStop()
    }

    companion object {
        const val EXTRA_TRAIN_NO = "trainNo"
        const val EXTRA_FROM = "fromCode"
        const val EXTRA_TO = "toCode"
        const val EXTRA_ORIGIN_EPOCH_DAY = "originEpochDay"
    }
}
