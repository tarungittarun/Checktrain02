package `in`.opentrack.india.ui

import `in`.opentrack.india.R
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import `in`.opentrack.india.core.Diary
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

    /**
     * "Save to file" hands the log to the system's document picker, which answers with a content URI. The
     * pending target is remembered here rather than written to a bundle: the export is a one-shot gesture that
     * loses its meaning if the screen is recreated mid-picker, and a stale path would be worse than a lost tap.
     */
    private var pendingExport: Uri? = null

    private val exportDocument = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val uri = pendingExport
        pendingExport = null
        val bytes = Diary.get()?.text()?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)
        // The null check has to be the branch condition itself: assigning it to a `cancelled` boolean reads
        // better and compiles not at all, because the compiler stops tracking `uri` through the indirection.
        when {
            bytes.isEmpty() -> toast(R.string.diag_nothing)
            uri == null || result.resultCode != RESULT_OK -> toast("Not saved - the picker was closed")
            else -> {
                val outcome = runCatching { contentResolver.openOutputStream(uri)?.use { it.write(bytes) } }
                if (outcome.isSuccess) toast(R.string.diag_saved) else toast("Could not write that file")
            }
        }
    }

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
        wireDiagnostics()
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

    /**
     * The collector's controls. Everything on this card is deliberately inert until the switch is on: no
     * buffer is filled, no file is created, and the state line tells the user how many bytes exist so that
     * "I turned it on and nothing happened" is answerable from the screen rather than from a bug report.
     */
    private fun wireDiagnostics() {
        val diary = Diary.get() ?: Diary.install(this)
        renderDiary(diary)
        binding.diagToggle.setOnClickListener {
            if (diary.enabled) {
                diary.disable()
                prefs.diaryEnabled = false
                toast("Collection stopped — nothing more is written")
            } else {
                diary.enable(this)
                prefs.diaryEnabled = true
                toast("Collection on: reproduce the problem, then Share or Save to file here")
            }
            renderDiary(diary)
        }
        binding.diagShare.setOnClickListener {
            val body = diary.text()
            val collected = diary.file()?.let { it.length() > 0L } == true
            if (body.isBlank() || !collected) {
                toast(R.string.diag_empty_hint)
            } else {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "OpenTrack India diagnostic log")
                    putExtra(Intent.EXTRA_TEXT, body)
                }
                runCatching { startActivity(Intent.createChooser(send, "Share diagnostic log")) }
                    .onFailure { toast("No app can accept plain text on this device") }
            }
        }
        binding.diagSave.setOnClickListener {
            val saved = diary.file()?.let { it.length() > 0L } == true
            if (!saved) {
                toast(R.string.diag_empty_hint)
                return@setOnClickListener
            }
            val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "text/plain"
                putExtra(Intent.EXTRA_TITLE, diary.exportName())
            }
            runCatching { exportDocument.launch(intent) }.onFailure { toast("This device has no file picker") }
        }
        binding.diagClear.setOnClickListener {
            diary.clear()
            renderDiary(diary)
            toast("Log deleted from this device")
        }
    }

    private fun renderDiary(diary: Diary) {
        binding.diagToggle.setText(
            if (diary.enabled) R.string.settings_diag_off else R.string.settings_diag_on,
        )
        val file = diary.file()
        binding.diagState.text = if (file == null || file.length() == 0L) {
            getString(R.string.diag_empty_hint)
        } else {
            val when0 = runCatching {
                java.text.DateFormat.getDateTimeInstance().format(java.util.Date(file.lastModified()))
            }.getOrNull() ?: "?"
            (if (diary.enabled) "Collecting" else "Stopped") + " · " + file.length() + " bytes · last entry " +
                when0 + " · file: " + file.name
        }
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    private fun toast(res: Int) = Toast.makeText(this, res, Toast.LENGTH_LONG).show()

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
