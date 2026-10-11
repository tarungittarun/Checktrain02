package in.opentrack.india

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.TextView

/**
 * CI-1 baseline screen: the app must never print a value without a provenance label
 * (brief 3.2), and it must never invent one (brief 3.4). There is no data yet, so the
 * honest state is OFFLINE_UNVERIFIED with the reason stated in passenger words.
 *
 * One [label] function renders provenance here exactly as it will everywhere else;
 * screens never compose their own label (brief 3.2).
 */
class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val view = TextView(this).apply {
            text = buildString {
                append("OpenTrack India\n\n")
                append(label(State.OFFLINE_UNVERIFIED)).append("\n\n")
                append("No train data is on this phone yet. Nothing shown here is live, ")
                append("and nothing has been guessed. That changes with the next milestone.")
            }
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
        }
        setContentView(view)
    }

    /** The screen-state vocabulary (brief 6.6). CI-1 ships the one state that is true today. */
    enum class State {
        OFFLINE_UNVERIFIED,
    }

    /** Single provenance renderer for the whole app (brief 3.2). */
    fun label(state: State): String = when (state) {
        State.OFFLINE_UNVERIFIED ->
            "Offline - could not look yet. No data source has answered from this phone."
    }
}
