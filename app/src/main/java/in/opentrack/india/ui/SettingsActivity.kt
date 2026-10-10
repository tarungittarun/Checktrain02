package `in`.opentrack.india.ui

import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import `in`.opentrack.india.App
import `in`.opentrack.india.data.Prefs
import `in`.opentrack.india.databinding.ActivitySettingsBinding
import kotlinx.coroutines.launch
import `in`.opentrack.india.BuildConfig
import `in`.opentrack.india.data.RefDb

/**
 * Screen 3 of 3 — the controls that keep this app alive when something upstream changes.
 *
 * This is not a preferences screen for its own sake. Every field here is a response to a documented
 * failure mode: an endpoint that starts requiring a key (`overrideUrl`), a feed whose column order
 * shifts ([in.opentrack.india.remote.ErailWire] hint), and a data snapshot that ages out (refresh).
 * A user with no build tools can fix all three from this screen, which is the difference between a
 * side project that dies and one that survives.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var prefs: Prefs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = Prefs(this)

        binding.overrideUrl.setText(prefs.overrideUrl)
        binding.columnHint.setText(prefs.columnHint)
        // Own layout rather than `simple_list_item_1`: the framework row brings a system text size and
        // padding, which would be the only type on this screen not drawn from the project's ladder.
        binding.refreshChoices.adapter = ArrayAdapter(
            this, R.layout.item_spinner_choice, REFRESH_LABELS
        )
        binding.refreshChoices.setSelection(refreshIndex(prefs.autoRefreshSeconds))

        binding.save.setOnClickListener {
            prefs.overrideUrl = binding.overrideUrl.text.toString().trim()
            prefs.columnHint = binding.columnHint.text.toString().trim()
            prefs.autoRefreshSeconds = REFRESH_SECONDS[binding.refreshChoices.selectedItemPosition]
            Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
            finish()
        }
        binding.clear.setOnClickListener {
            lifecycleScope.launch {
                (application as App).repository()?.clearCaches()
                Toast.makeText(this@SettingsActivity, "Live cache cleared", Toast.LENGTH_SHORT).show()
            }
        }
        binding.back.setOnClickListener { finish() }
        // Both labels need the repository, which is a suspend lookup (it may still be extracting on a
        // first run), so they are set together from one coroutine rather than reached for inline.
        lifecycleScope.launch {
            val version = runCatching {
                (application as App).repository()?.dataVersion()
            }.getOrNull() ?: "not installed"
            binding.about.text = buildString {
                append("OpenTrack India ").append(BuildConfig.VERSION_NAME)
                append(" · timetable: ").append(version).append("\n\n")
                append("No accounts, no API keys, no ads, no analytics. Live status is fetched ")
                append("directly from a public Indian Railways enquiry endpoint; everything else — ")
                append("station search, the ±24 h train list and every timetable — is answered here.")
            }
            binding.state.text = when {
                RefDb.isUsable(this@SettingsActivity) ->
                    "Built-in timetable installed · offline search ready"
                else -> "Built-in timetable not installed yet — it is extracted on first search"
            }
        }
    }

    companion object {
        private val REFRESH_SECONDS = intArrayOf(0, 30, 60, 300, 900)
        private val REFRESH_LABELS = arrayOf(
            "Manual refresh only", "Every 30 seconds", "Every minute", "Every 5 minutes",
            "Every 15 minutes"
        )

        private fun refreshIndex(seconds: Int): Int =
            REFRESH_SECONDS.indexOf(seconds).takeIf { it >= 0 } ?: 2
    }
}
