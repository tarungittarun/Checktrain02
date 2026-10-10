package `in`.opentrack.india.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import `in`.opentrack.india.App
import `in`.opentrack.india.R
import `in`.opentrack.india.core.RailTime
import `in`.opentrack.india.databinding.ActivityMainBinding
import `in`.opentrack.india.domain.Suggestion
import `in`.opentrack.india.domain.TrainFinder
import `in`.opentrack.india.model.TrainHit

/**
 * Screen 1 of 3 — find a train: a station pair (or a train number/name), a date, and every run inside
 * the ±24 h window, from the on-device timetable.
 *
 * There is deliberately no navigation drawer, home carousel or tab bar: the app has one job, so the
 * launch screen *is* the search. Every extra surface is pixels to inflate and code to keep working
 * across Android versions, in an app whose main selling point is that it keeps working.
 *
 * The two input boxes are [SuggestBox]es, so a search can be carried by tapping a real station: typing
 * "Chenna" and pressing Find resolves to whatever the fuzzy matcher preferred, while a tap carries the
 * code the user actually looked at.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var vm: MainViewModel
    private lateinit var adapter: TrainHitAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val app = application as App
        vm = ViewModelProvider(
            this,
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
                    MainViewModel(app) as T
            }
        )[MainViewModel::class.java]

        adapter = TrainHitAdapter { hit ->
            vm.remember(hit)
            open(hit)
        }
        binding.results.layoutManager = LinearLayoutManager(this)
        binding.results.adapter = adapter
        binding.results.setHasFixedSize(true)

        wireBoxes()
        binding.find.setOnClickListener { runSearch() }
        binding.swap.setOnClickListener { swap() }
        binding.dayBack.setOnClickListener { shiftDay(-1) }
        binding.dayFwd.setOnClickListener { shiftDay(1) }
        binding.dayNow.setOnClickListener {
            vm.setDate(RailTime.todayEpochDay(), RailTime.istMinuteOfDay(System.currentTimeMillis()))
            renderDate()
        }
        binding.settings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        binding.modeStations.setOnClickListener { selectMode(MainViewModel.Mode.STATIONS) }
        binding.modeTrain.setOnClickListener { selectMode(MainViewModel.Mode.TRAIN) }

        vm.phase.observe(this) { renderPhase(it) }
        vm.results.observe(this) { it?.let { r -> renderReport(r) } }
        selectMode(MainViewModel.Mode.STATIONS)
        renderDate()
        restoreLast()
        vm.prepare()
    }

    /**
     * Suggestions for both fields. The station boxes share one source; the train box searches the
     * offline train master by number or name. `onPick` fires a search when the *other* end is already
     * filled, which is the common case on a second visit to the app.
     */
    private fun wireBoxes() {
        val stationSuggester = object : SuggestBox.Suggester {
            override fun suggest(query: String, then: (List<Suggestion>) -> Unit) =
                vm.suggestStations(query, then)
        }
        val trainSuggester = object : SuggestBox.Suggester {
            override fun suggest(query: String, then: (List<Suggestion>) -> Unit) =
                vm.suggestTrains(query, then)
        }
        listOf(binding.fromBox, binding.toBox).forEach { box ->
            box.suggester = stationSuggester
            box.onPick = { if (otherEndFilled(box)) runSearch() }
            box.onSearch = { runSearch() }
        }
        binding.trainBox.suggester = trainSuggester
        binding.trainBox.onPick = { runSearch() }
        binding.trainBox.onSearch = { runSearch() }
        binding.fromBox.hint = getString(R.string.hint_from)
        binding.toBox.hint = getString(R.string.hint_to)
        binding.trainBox.hint = getString(R.string.hint_train)
    }

    private fun otherEndFilled(box: SuggestBox): Boolean {
        val other = if (box === binding.fromBox) binding.toBox else binding.fromBox
        return other.valueOrText().length >= 3
    }

    /** Swap keeps the *resolved* codes with the names; a swap that lost the resolution would re-fuzzy. */
    private fun swap() {
        val (a, b) = binding.fromBox.text to binding.toBox.text
        val (ac, bc) = binding.fromBox.resolvedCode to binding.toBox.resolvedCode
        binding.fromBox.setTextShown(b); binding.fromBox.resolvedCode = bc
        binding.toBox.setTextShown(a); binding.toBox.resolvedCode = ac
    }

    /** Reopens the last search: the common case is "same pair, again tomorrow". */
    private fun restoreLast() {
        val entry = (application as App).prefsRecent().firstOrNull() ?: return
        val parts = entry.split('|')
        if (parts.size < 3) return
        if (binding.fromBox.text.isEmpty()) binding.fromBox.setTextShown(parts[1].uppercase())
        if (binding.toBox.text.isEmpty()) binding.toBox.setTextShown(parts[2].uppercase())
    }

    private fun selectMode(mode: MainViewModel.Mode) {
        vm.setMode(mode)
        val stations = mode == MainViewModel.Mode.STATIONS
        binding.fromBox.visibility = if (stations) View.VISIBLE else View.GONE
        binding.toBox.visibility = if (stations) View.VISIBLE else View.GONE
        binding.swap.visibility = if (stations) View.VISIBLE else View.GONE
        binding.trainBox.visibility = if (stations) View.GONE else View.VISIBLE
        binding.trainLabel.visibility = if (stations) View.GONE else View.VISIBLE
        binding.resultsHead.visibility = View.GONE
        binding.modeStations.setBackgroundResource(if (stations) R.drawable.bg_segment_on else 0)
        binding.modeTrain.setBackgroundResource(if (stations) 0 else R.drawable.bg_segment_on)
        binding.modeStations.setTextColor(getColor(if (stations) R.color.text1 else R.color.text3))
        binding.modeTrain.setTextColor(getColor(if (stations) R.color.text3 else R.color.text1))
        if (!stations && binding.trainBox.text.length >= 3) runSearch()
    }

    private fun shiftDay(delta: Int) {
        vm.setDate(vm.targetEpochDay + delta, vm.targetMinuteOfDay)
        renderDate()
    }

    private fun renderDate() {
        val label = when (vm.targetEpochDay - RailTime.todayEpochDay()) {
            0L -> "Today"
            1L -> "Tomorrow"
            -1L -> "Yesterday"
            else -> RailTime.formatDate(vm.targetEpochDay)
        }
        binding.whenValue.text = "$label · ${RailTime.formatRailMinute(vm.targetMinuteOfDay)}"
        binding.dayNow.setBackgroundResource(
            if (vm.targetEpochDay == RailTime.todayEpochDay()) R.drawable.bg_segment_on else 0
        )
        binding.dayBack.setBackgroundResource(
            if (vm.targetEpochDay < RailTime.todayEpochDay()) R.drawable.bg_segment_on else 0
        )
        binding.dayFwd.setBackgroundResource(
            if (vm.targetEpochDay > RailTime.todayEpochDay()) R.drawable.bg_segment_on else 0
        )
    }

    private fun runSearch() {
        val from = binding.fromBox.valueOrText()
        val to = binding.toBox.valueOrText()
        val train = binding.trainBox.valueOrText()
        if (vm.mode == MainViewModel.Mode.STATIONS && (from.length < 2 || to.length < 2)) {
            binding.statusLine.visibility = View.VISIBLE
            binding.statusLine.setText(R.string.err_need_two_stations)
            return
        }
        if (vm.mode == MainViewModel.Mode.TRAIN && train.length < 3) {
            binding.statusLine.visibility = View.VISIBLE
            binding.statusLine.setText(R.string.err_need_train_query)
            return
        }
        vm.search(from, if (vm.mode == MainViewModel.Mode.TRAIN) train else to)
    }

    private fun renderPhase(phase: MainViewModel.Phase) {
        when (phase) {
            is MainViewModel.Phase.Preparing -> {
                binding.statusLine.visibility = View.VISIBLE
                val pct = if (phase.total > 0) (phase.done * 100 / phase.total).toInt() else 0
                binding.statusLine.text = if (phase.total == 0L) {
                    "Unpacking the offline timetable…"
                } else {
                    getString(R.string.installing_data, pct)
                }
            }
            is MainViewModel.Phase.Ready -> {
                binding.statusLine.visibility = View.GONE
                binding.dataChip.visibility = View.VISIBLE
                binding.dataChip.text = phase.dataVersion
                binding.find.isEnabled = true
            }
            is MainViewModel.Phase.Broken -> {
                binding.statusLine.visibility = View.VISIBLE
                binding.find.isEnabled = false
                binding.statusLine.text = phase.message
            }
            MainViewModel.Phase.Searching -> {
                binding.statusLine.visibility = View.VISIBLE
                binding.statusLine.setText(R.string.searching)
            }
        }
    }

    private fun renderReport(report: TrainFinder.Report) {
        binding.resultsHead.visibility = if (report.hits.isEmpty()) View.GONE else View.VISIBLE
        binding.resultsSub.text = if (report.queryMillis > 0) {
            "${getString(R.string.sorted_by)} · ${report.queryMillis} ms on device"
        } else getString(R.string.sorted_by)
        adapter.nowMinute = vm.nowEpochMinute()
        adapter.submitList(report.hits)
        val head = when (report.reason) {
            TrainFinder.Reason.OK -> {
                val pair = listOfNotNull(report.from?.code, report.to?.code).joinToString(" → ")
                getString(R.string.trains_between, report.hits.size,
                    report.from?.name ?: pair, report.to?.name ?: pair) +
                    (report.notRunningOnDate.takeIf { it > 0 }
                        ?.let { " · $it do not run on that date" } ?: "")
            }
            TrainFinder.Reason.STATION_NOT_FOUND -> getString(R.string.err_station_unknown)
            TrainFinder.Reason.NO_DIRECT_TRAINS ->
                getString(R.string.err_no_direct) + (report.notRunningHint?.let { " — $it" } ?: "")
            TrainFinder.Reason.WRONG_DATE ->
                report.notRunningHint ?: getString(R.string.err_wrong_date)
            TrainFinder.Reason.SAME_STATION -> getString(R.string.err_same_station)
        }
        binding.statusLine.visibility = View.VISIBLE
        binding.statusLine.text = head
    }

    private fun open(hit: TrainHit) {
        startActivity(
            Intent(this, TrainActivity::class.java).apply {
                putExtra(TrainActivity.EXTRA_TRAIN_NO, hit.trainNo)
                putExtra(TrainActivity.EXTRA_FROM, hit.fromCode)
                putExtra(TrainActivity.EXTRA_TO, hit.toCode)
                putExtra(TrainActivity.EXTRA_ORIGIN_EPOCH_DAY, hit.originDateEpochDay)
            }
        )
    }
}
