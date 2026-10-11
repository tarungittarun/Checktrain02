package `in`.opentrack.india.e2e

import android.content.Context
import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import java.io.File

/**
 * The one way this suite touches the app: pixels, resource ids and the accessibility tree — what a person
 * sees and what a screen reader reads. No `ActivityScenario`, no reflection, no `App.getInstance()`.
 *
 * That is a deliberate restriction with a reason. The JVM tests in this repo already reach inside; the
 * device suite exists to answer "does the built APK, driven by hand, do what the traveller expects", and
 * the moment it imports a class from `app/src/main` it stops testing the app and starts testing the build.
 * It also means these tests survive a refactor the way a user does: they break when the *screen* changes.
 *
 * Timeouts are generous in one place only — cold start, which on a first run unpacks a 14 MB SQLite file
 * before anything is clickable. Everywhere else a short wait is a real failure, because a spinner that
 * needs 20 s is a bug on a phone, not a flake.
 */
object E2E {

    const val PKG = "in.opentrack.india"

    /** First launch after `pm clear`: extraction of the offline timetable, on a slow CI disk. */
    const val COLD_START_MS = 120_000L
    /** Anything that only reads the device database. */
    const val LOCAL_MS = 10_000L
    /** Anything that may wait on the network before the screen settles. */
    const val NETWORK_MS = 45_000L

    val ui: UiDevice by lazy { UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()) }

    private val args: android.os.Bundle
        get() = InstrumentationRegistry.getArguments()

    /** `-e offline true`, `-e location denied`, `-e rotation 90`: set by CI, read by the tests. */
    fun arg(key: String, default: String = ""): String = args.getString(key) ?: default
    fun flag(key: String): Boolean = arg(key).equals("true", ignoreCase = true)

    fun res(name: String): BySelector = By.res(PKG, "id/$name")
    fun byText(text: String): BySelector = By.text(text)
    fun find(name: String, timeoutMs: Long = LOCAL_MS): UiObject2? =
        ui.wait(Until.findObject(res(name)), timeoutMs)

    fun text(name: String): String = find(name)?.text.orEmpty().trim()

    fun visible(name: String, timeoutMs: Long = LOCAL_MS): Boolean = find(name, timeoutMs) != null

    /** A tap on a control's centre. Works whether or not the node reports itself clickable. */
    fun tap(name: String, timeoutMs: Long = LOCAL_MS): Boolean {
        val o = find(name, timeoutMs) ?: return false
        o.click()
        ui.waitForIdle()
        return true
    }

    /** Types into a field the way a hardware keyboard does, after focusing it the way a finger does. */
    fun type(name: String, value: String, timeoutMs: Long = LOCAL_MS): Boolean {
        val o = find(name, timeoutMs) ?: return false
        o.click()
        o.setText(value)
        ui.waitForIdle()
        return true
    }

    fun clearField(name: String) {
        val o = find(name) ?: return
        o.click()
        // Select-all + delete rather than `clearText()`, because the field may hold
        // a resolved station name (long) rather than what the last keystroke produced.
        var n = o.text?.length ?: 0
        while (n-- > 0) ui.pressDelete()
    }

    fun count(where: BySelector): Int = try {
        ui.findObjects(where).size
    } catch (e: IllegalStateException) {
        0
    }

    fun all(where: BySelector): List<UiObject2> = try {
        ui.findObjects(where)
    } catch (e: IllegalStateException) {
        emptyList()
    }

    /**
     * Launch (or relaunch) the app on its own task, waiting for its window rather than sleeping.
     *
     * `cold` is asserted, not performed: `pm clear` has to come from the harness, because UiAutomator
     * exposes no shell to a test process. The CI job therefore runs `adb shell pm clear` before the cold
     * row, which is what makes the first-run extraction observable at all — and the test fails if the app
     * somehow starts with an already-installed database, so the harness cannot silently skip the case.
     */
    fun launch(cold: Boolean = false) {
        val ctx = context()
        if (cold && flag("requireCleared")) {
            // The harness promised `pm clear` before this launch. If the timetable database is already
            // there, the "first run" this row is reporting on is a warm run, and every cold-start
            // assertion below it would be measuring the wrong thing.
            check(installedDbMissing()) {
                "cold row launched against an app that already has its timetable installed"
            }
        }
        val intent = ctx.packageManager.getLaunchIntentForPackage(PKG)
            ?: error("no launcher activity in $PKG — the APK is not the one this suite was built for")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        ctx.startActivity(intent)
        check(ui.wait(Until.hasObject(By.pkg(PKG).depth(0)), COLD_START_MS)) {
            "the app window never appeared"
        }
    }

    fun context(): Context = InstrumentationRegistry.getInstrumentation().targetContext

    /** Waits for the search screen to be interactive: the box exists AND the install progress is gone. */
    fun awaitSearchScreen() {
        check(visible("fromBox", COLD_START_MS)) { "no board-at field on the search screen" }
        val deadline = System.currentTimeMillis() + COLD_START_MS
        while (System.currentTimeMillis() < deadline) {
            val status = find("statusLine", 1_000)?.text.orEmpty()
            if (!status.contains("Installing", true) && !status.contains("%")) return
            Thread.sleep(500)
        }
        error("the offline timetable never finished installing within ${COLD_START_MS} ms")
    }

    fun openTrackFromFirstResult(): Boolean {
        val go = ui.findObjects(By.res(PKG, "id/hitGo")).firstOrNull() ?: return false
        go.click()
        return visible("headline", NETWORK_MS) || visible("routeLabel", NETWORK_MS)
    }

    /** Screenshots and hierarchies are the evidence a red device run needs; nobody reads a stack trace. */
    fun shot(tag: String) {
        val dir = evidenceDir() ?: return
        // Both calls take the destination File: `UiDevice` has no bitmap-returning screenshot, and the
        // hierarchy writer wants a stream only in the sense that it writes the file itself.
        runCatching { ui.takeScreenshot(File(dir, "$tag.png")) }
        runCatching { ui.dumpWindowHierarchy(File(dir, "$tag.xml")) }
    }

    private fun evidenceDir(): File? {
        val ctx = InstrumentationRegistry.getInstrumentation().context
        val dir = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "evidence")
        return if (dir.mkdirs() || dir.isDirectory) dir else null
    }

    /**
     * Display metrics for the geometry assertions: dp is the unit the layouts are written in.
     *
     * Taken from the target app's own `Resources` rather than from `UiDevice`, because the density and size
     * a layout is actually measured against is the one its `Context` reports - which is also the thing a
     * `wm density` override changes, which is what the large-screen runner row is doing.
     */
    fun densityDpi(): Int = context().resources.displayMetrics.densityDpi
    fun widthDp(): Int = dp(context().resources.displayMetrics.widthPixels)
    fun heightDp(): Int = dp(context().resources.displayMetrics.heightPixels)
    private fun dp(px: Int): Int = (px * 160f / densityDpi()).toInt()

    // `RefDb` keeps the extracted timetable at `filesDir/refdb.db`. The name is hardcoded here on
    // purpose: this suite does not compile against the app's classes, so a path rename is a change the
    // cold-start flow should notice rather than absorb.
    private fun installedDbMissing(): Boolean = try {
        !File(context().filesDir, "refdb.db").exists()
    } catch (e: Throwable) {
        true // an unreadable data dir is not evidence that the app is warm
    }

    // ---------------------------------------------------------------- the two suggest boxes
    //
    // Read *inside* the named box rather than by index of `suggestInput`: only the fields the current
    // search mode shows are in the tree at all, so a positional assumption would make a mode switch look
    // like a broken test.

    fun boxField(box: String): UiObject2? =
        ui.findObject(res(box))?.findObject(By.res(PKG, "id/suggestInput"))

    fun boxText(box: String): String = boxField(box)?.text.orEmpty().trim()

    fun typeInBox(box: String, value: String): Boolean {
        val o = boxField(box) ?: return false
        o.click()
        o.setText(value)
        ui.waitForIdle()
        return true
    }

    /** The "Selected  SBC" chip a picked suggestion leaves behind — the resolved code, from the UI. */
    fun boxChip(box: String): String =
        ui.findObject(res(box))?.findObject(By.res(PKG, "id/suggestChip"))?.text.orEmpty().trim()

    fun suggestionRows(): List<UiObject2> = all(By.res(PKG, "id/sugPrimary"))

    /** Taps the nth suggestion row inside a box's own panel, so the two boxes cannot cross wires. */
    fun tapSuggestion(box: String, index: Int = 0): Boolean {
        val panel = ui.findObject(res(box))?.findObject(res("suggestPanel")) ?: return false
        val rows = panel.findObjects(By.res(PKG, "id/sugPrimary"))
        val row = rows.getOrNull(index) ?: return false
        row.click()
        ui.waitForIdle()
        return true
    }

    fun resultRows(): Int = all(By.res(PKG, "id/hitNo")).size

    /**
     * The system dialogs that mean the app died. A crash on a device often surfaces as a dialog rather
     * than a failed assertion, and a suite that keeps tapping behind a "keeps stopping" sheet is worse than
     * useless — it reports passes. Checked after each flow.
     */
    fun assertNoSystemDialog(what: String) {
        val dialog = ui.findObject(By.textContains("keeps stopping"))
            ?: ui.findObject(By.textContains("won't respond"))
            ?: ui.findObject(By.textContains("isn't responding"))
            ?: ui.findObject(By.res("android", "id/aerr_text"))
        if (dialog != null) {
            shot("crash-$what")
            error("$what: the system says the app died (${dialog.text})")
        }
    }
}
