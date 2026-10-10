package in.opentrack.india.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import in.opentrack.india.App
import in.opentrack.india.R
import in.opentrack.india.core.RailTime
import in.opentrack.india.databinding.ActivityMainBinding
import in.opentrack.india.domain.TrainFinder
import in.opentrack.india.model.StationHit
import in.opentrack.india.model.TrainHit

/**
 * Screen 1 of 3 — find a train: a station pair (or a train number/name), a date, and every run inside
 * the ±24 h window, from the on-device timetable.
 *
 * There is deliberately no navigation drawer, home carousel or tab bar: the app has one job, so the
 * launch screen *is* the search. Every extra surface is pixels to inflate and code to keep working
 * across Android versions, in an app whose main selling point is that it keeps working.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var vm: MainViewModel
    private lateinit var adapter: TrainHitAdapter
    private val suggestions = ArrayList<StationHit>()
    private lateinit var suggestionAdapter: ArrayAdapter<String>

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

        suggestionAdapter = ArrayAdapter(this, R.layout.item_suggestion, ArrayList())
        binding.from.setAdapter(suggestionAdapter)
        binding.to.setAdapter(suggestionAdapter)

        selectMode(MainViewModel.Mode.STATIONS)
        binding.tabStations.setOnClickListener { selectMode(MainViewModel.Mode.STATIONS) }
        binding.tabTrain.setOnClickListener { selectMode(MainViewModel.Mode.TRAIN) }
        binding.search.setOnClickListener { runSearch() }
        binding.dayBack.setOnClickListener { shiftDay(-1) }
        binding.dayFwd.setOnClickListener { shiftDay(1) }
        binding.dayNow.setOnClickListener {
            vm.setDate(RailTime.todayEpochDay(), RailTime.istMinuteOfDay(System.currentTimeMillis()))
            renderDate()
        }
        binding.settings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        val onSearch = android.widget.TextView.OnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_SEARCH || action == EditorInfo.IME_ACTION_DONE) {
                runSearch(); true
            } else false
        }
        binding.from.setOnEditorActionListener(onSearch)
        binding.to.setOnEditorActionListener(onSearch)
        binding.trainInput.setOnEditorActionListener(onSearch)

        // Selecting from the dropdown replaces the free text with the exact code, so the search that
        // follows cannot mis-resolve "Chennai" to the second-best match.
        listOf(binding.from, binding.to).forEach { field ->
            field.setOnItemClickListener { _, _, position, _ ->
                suggestions.getOrNull(position)?.let { hit ->
                    // The dropdown shows "Name · CODE"; a tap must leave the *code* in the field, since
                    // an exact code resolves unambiguously while a name can match several stations.
                    field.setText(hit.code, false)
                    field.hint = hit.name
                }
            }
            field.addTextChangedListener(
                SimpleWatcher { text -> onQuery(field, text) }
            )
        }
        binding.trainInput.addTextChangedListener(SimpleWatcher { text ->
            vm.setTrainQuery(text)
            if (vm.mode == MainViewModel.Mode.TRAIN && text.length >= 3) runSearch()
        })

        vm.phase.observe(this) { renderPhase(it) }
        vm.results.observe(this) { it?.let { r -> renderReport(r) } }
        renderDate()
        restoreLast()
        vm.prepare()
    }

    /** Reopens the last search: the common case is "same pair, again tomorrow". */
    private fun restoreLast() {
        val entry = (application as App).prefsRecent().firstOrNull() ?: return
        val parts = entry.split('|')
        if (parts.size < 2) return
        if (binding.from.text.isNullOrEmpty()) binding.from.setText(parts[1], false)
        if (parts.size > 2 && binding.to.text.isNullOrEmpty()) binding.to.setText(parts[2], false)
    }

    private fun onQuery(field: android.widget.AutoCompleteTextView, text: String) {
        if (text.trim().length < 2) {
            suggestions.clear()
            suggestionAdapter.clear()
            suggestionAdapter.notifyDataSetChanged()
            return
        }
        vm.suggestStations(text.trim()) { hits ->
            suggestions.clear()
            suggestions.addAll(hits)
            suggestionAdapter.clear()
            suggestionAdapter.addAll(hits.map { "${it.name} · ${it.code}" })
            suggestionAdapter.notifyDataSetChanged()
            field.showDropDown()
        }
    }

    private fun selectMode(mode: MainViewModel.Mode) {
        vm.setMode(mode)
        val stations = mode == MainViewModel.Mode.STATIONS
        binding.pairBlock.visibility = if (stations) View.VISIBLE else View.GONE
        binding.trainBlock.visibility = if (stations) View.GONE else View.VISIBLE
        binding.tabStations.isSelected = stations
        binding.tabTrain.isSelected = !stations
        binding.tabStations.alpha = if (stations) 1f else 0.55f
        binding.tabTrain.alpha = if (stations) 1f else 0.55f
        if (!stations) runSearch()
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
        binding.dateLabel.text = "$label · ${RailTime.formatRailMinute(vm.targetMinuteOfDay)}"
        binding.dateSub.text = getString(R.string.window_hint)
    }

    private fun runSearch() {
        val from = binding.from.text?.toString()?.trim().orEmpty()
        val to = binding.to.text?.toString()?.trim().orEmpty()
        val train = binding.trainInput.text?.toString()?.trim().orEmpty()
        if (vm.mode == MainViewModel.Mode.STATIONS && (from.length < 2 || to.length < 2)) {
            binding.status.setText(R.string.err_need_two_stations)
            return
        }
        if (vm.mode == MainViewModel.Mode.TRAIN && train.length < 3) {
            binding.status.setText(R.string.err_need_train_query)
            return
        }
        vm.search(from, if (vm.mode == MainViewModel.Mode.TRAIN) train else to)
    }

    private fun renderPhase(phase: MainViewModel.Phase) {
        when (phase) {
            is MainViewModel.Phase.Preparing -> {
                binding.progress.visibility = View.VISIBLE
                val pct = if (phase.total > 0) (phase.done * 100 / phase.total).toInt() else 0
                binding.progress.text = getString(R.string.installing_data, pct)
            }
            is MainViewModel.Phase.Ready -> {
                binding.progress.visibility = View.GONE
                binding.dataChip.visibility = View.VISIBLE
                binding.dataChip.text = phase.dataVersion
                binding.search.isEnabled = true
            }
            is MainViewModel.Phase.Broken -> {
                binding.progress.visibility = View.GONE
                binding.search.isEnabled = false
                binding.status.text = phase.message
            }
            MainViewModel.Phase.Searching -> binding.status.setText(R.string.searching)
        }
    }

    private fun renderReport(report: TrainFinder.Report) {
        adapter.submitList(report.hits)
        binding.status.text = when (report.reason) {
            TrainFinder.Reason.OK -> {
                val head = "${report.hits.size} train(s)"
                val pair = listOfNotNull(report.from?.name, report.to?.name)
                    .takeIf { it.size == 2 }?.joinToString(" → ")
                val skip = report.notRunningOnDate
                    .takeIf { it > 0 }?.let { " · $it do not run on this date" } ?: ""
                listOfNotNull(head, pair).joinToString(" · ") + skip +
                    (report.notRunningHint?.let { " · $it" } ?: "")
            }
            TrainFinder.Reason.STATION_NOT_FOUND ->
                getString(R.string.err_station_unknown)
            TrainFinder.Reason.NO_DIRECT_TRAINS ->
                getString(R.string.err_no_direct) +
                    (report.notRunningHint?.let { " — $it" } ?: "")
            TrainFinder.Reason.WRONG_DATE ->
                report.notRunningHint ?: getString(R.string.err_wrong_date)
            TrainFinder.Reason.SAME_STATION -> getString(R.string.err_same_station)
        }
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

/** Minimal afterTextChanged-only watcher; `androidx.core.widget.doAfterTextChanged` would do, and this avoids reaching for it in five places. */
class SimpleWatcher(private val onText: (String) -> Unit) : android.text.TextWatcher {
    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
    override fun afterTextChanged(s: android.text.Editable?) { onText(s?.toString().orEmpty()) }
}
