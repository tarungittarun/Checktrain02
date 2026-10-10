package `in`.opentrack.india

import android.app.Application
import `in`.opentrack.india.data.RailRepository
import `in`.opentrack.india.data.RefDb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import `in`.opentrack.india.data.Prefs

/**
 * Process-wide holder for the one heavyweight resource: the read-only timetable handle.
 *
 * Opening it is not free (first launch extracts a 14 MB SQLite file out of the APK), so it happens
 * once, lazily, behind a mutex, and every screen suspends on the same result instead of each
 * Activity re-implementing "make sure the data is ready".
 */
class App : Application() {

    private val mutex = Mutex()

    @Volatile
    private var repository: RailRepository? = null

    suspend fun repository(onProgress: (Long, Long) -> Unit = { _, _ -> }): RailRepository? {
        repository?.let { return it }
        return mutex.withLock {
            repository ?: run {
                if (RefDb.prepare(this) != RefDb.State.READY) return@run null
                val ref = runCatching { RefDb.openOrCreate(this) }.getOrNull() ?: return@run null
                RailRepository.get(this, ref).also { repository = it }
            }
        }
    }

    /** Recent searches, for reopening "same train, tomorrow" without typing. */
    fun prefsRecent(): List<String> =
        Prefs(this).recentTrains()

    /** Progress-bearing variant used by the first-launch screen. */
    suspend fun prepare(onProgress: (Long, Long) -> Unit): RailRepository? =
        withContext(Dispatchers.IO) { repository(onProgress) }
}
