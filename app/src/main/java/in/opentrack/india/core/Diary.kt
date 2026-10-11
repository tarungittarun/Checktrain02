package `in`.opentrack.india.core

import android.content.Context
import android.os.Build
import android.provider.Settings
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * An opt-in diagnostic log, written to the device and shared by the user.
 *
 * Why this exists rather than more guessing: two field reports had to be transcribed by hand into a chat
 * message, and one of them turned out to have been produced by an older build - which is only visible if the
 * report states the build that wrote it. A file the user chooses to collect answers that class of confusion
 * outright, and it can hold what an eight-line copy summary cannot: every request attempt, the provider
 * chain's reasoning, the DNS and TCP picture at the moment the toggle was flipped.
 *
 * Three properties, in order of how much they matter:
 *
 *  1. **Collects only if enabled.** [log] returns before touching the ring buffer or the disk when
 *     [enabled] is false. There is no background capture that "just isn't flushed yet", and nothing is
 *     written to a new file until the user turns it on. Disabling appends a footer and stops.
 *  2. **Never leaves the device on its own.** There is no upload path here: the only way the contents get
 *     out is the user pressing Share or Save. The screen that enables it says so.
 *  3. **Records what the app already knows, and nothing that identifies a person.** No location, no
 *     account, no search history beyond the train number being tracked - and a redaction pass that drops
 *     anything shaped like a credential (see [sanitize]), because the live feed's envelope and a keyed
 *     provider's token both pass near this code. Server IP addresses are logged; a Private DNS hostname is
 *     reduced to its last two labels, since those hostnames routinely embed an account id.
 *
 * Threading: [log] is called from the socket thread, so appending is an in-memory operation and the disk
 * write happens on a single daemon executor. A crash loses at most the last few lines; the ring keeps them
 * available for Share anyway.
 */
class Diary(private val file: File) {

    @Volatile
    var enabled: Boolean = false
        private set

    /** Internal so the "collects only if enabled" rule is testable without an Android Context. */
    internal fun setEnabled(on: Boolean) {
        enabled = on
    }

    private val ring = ArrayDeque<String>(RING_CAP)
    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "opentrack-diary").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
    }
    @Volatile
    private var appContextForDns: Context? = null

    private val clockFmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private val stampFmt = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)

    /** Turns collection on, opening the file and recording the environment it is being read in. */
    fun enable(context: Context) {
        if (enabled) return
        setEnabled(true)
        append("DIARY", "=== collection enabled ===")
        // On the writer thread, never the caller's. The first field report proved why: enabling happens in a
        // click listener, so every DNS probe ran on the main thread and came back
        // "NetworkOnMainThreadException: null". A log that cannot look up a hostname is worse than no log, 
        // because it reads like a result.
        runCatching {
            io.execute {
                runCatching { snapshot(context) }.onFailure { err ->
                    append("ENV", "snapshot failed on the writer: " + err.javaClass.simpleName)
                }
            }
        }
    }

    /** Re-arms collection after a process restart when the switch was left on, without a fresh snapshot. */
    fun resume(context: Context) {
        if (enabled) return
        setEnabled(true)
        append("DIARY", "=== collection resumed: the switch was left on ===")
        runCatching {
            val pi = context.applicationContext.packageManager.getPackageInfo(context.packageName, 0)
            append("ENV", "app=" + pi.versionName + " diagSchema=" + SCHEMA + " (resumed)")
        }
    }

    fun disable() {
        if (!enabled) return
        append("DIARY", "=== collection disabled; nothing is recorded until it is turned on again ===")
        setEnabled(false)
    }

    /**
     * One line of log. `tag` groups the source (HTTP, NTES, PROBE, CACHE, UI), `message` is the finding.
     * Cheap enough to call on a hot path: disabled means an early return, enabled means a string concat, an
     * ArrayDeque add and a queued append.
     */
    fun log(tag: String, message: String) {
        if (!enabled) return
        append(tag, message)
    }

    fun log(tag: String, message: () -> String) {
        if (!enabled) return
        append(tag, message())
    }

    private fun append(tag: String, message: String) {
        val line = "${clockFmt.format(Date())} [$tag] ${sanitize(message)}"
        synchronized(ring) {
            if (ring.size >= RING_CAP) ring.removeFirst()
            ring.addLast(line)
        }
        // The first line is written on the calling thread. Everything after it is queued: a screen that
        // reports "nothing collected yet" a millisecond after the user enabled collection teaches them that
        // the switch does nothing, and that is the failure mode this whole card exists to avoid.
        val write = { runCatching { file.appendText(line + "\n"); rotate() } }
        if (!file.exists() || file.length() == 0L) runCatching { file.parentFile?.mkdirs() }.also { write() }
        else runCatching { io.execute { write() } }
    }

    /**
     * Keeps the file bounded without losing the recent past: on overflow the older half is dropped and the
     * cut is recorded, so a reader can tell truncation from a device that simply hasn't done much.
     */
    private fun rotate() {
        val bytes = runCatching { if (file.length() > MAX_BYTES) file.readBytes() else null }.getOrNull()
            ?: return
        val plan = rotationPlan(bytes, MAX_BYTES) ?: return
        runCatching { file.writeText(plan.first); file.appendBytes(plan.second) }
    }

    /**
     * What to keep when the file outgrows its cap: the newer half, preceded by a marker naming the cut.
     * Split out because the property that matters - rotation must never silently eat the tail, which is
     * where the repro is - is a pure function, and a rotating log with untested rotation is a log that loses
     * the one run the user finally captured.
     */
    internal fun rotationPlan(bytes: ByteArray, maxBytes: Long): Pair<String, ByteArray>? {
        if (bytes.size.toLong() <= maxBytes) return null
        val keep = bytes.copyOfRange(bytes.size / 2, bytes.size)
        val marker = "--- older half of this log dropped at rotation " +
            "(" + bytes.size + "B -> " + keep.size + "B; the tail is intact) ---\n"
        return marker to keep
    }

    /**
     * Blocks until everything logged so far has reached the disk, or the wait runs out.
     *
     * The writer is a single FIFO thread, so a marker task queued now runs after every pending append - which
     * makes "durable up to this instant" a property the class can offer instead of something each caller races
     * against. The export paths use it, and so does the test that once failed by reading the file a write
     * early: a log you cannot guarantee the end of is a log that loses the last few lines of a repro.
     */
    fun awaitIdle(timeoutMillis: Long = 4_000L): Boolean {
        val done = java.util.concurrent.CountDownLatch(1)
        if (runCatching { io.execute { done.countDown() } }.isFailure) return false
        return done.await(timeoutMillis, java.util.concurrent.TimeUnit.MILLISECONDS)
    }

    /**
     * The environment, captured once when collection starts.
     *
     * This is the part a report from a phone can tell and no CI run can: whether a Private DNS profile is
     * intercepting name resolution, whether the active network is a VPN, and whether the hosts the app needs
     * resolve at all. Each line is written so that its *absence* is also information.
     */
    private fun snapshot(context: Context) {
        val app = context.applicationContext
        appContextForDns = app
        append("ENV", "model=${Build.MANUFACTURER} ${Build.MODEL} android=${Build.VERSION.RELEASE} (sdk ${Build.VERSION.SDK_INT})")
        runCatching {
            val pi = app.packageManager.getPackageInfo(app.packageName, 0)
            append(
                "ENV",
                "app=" + pi.versionName + " versionCode=" + pi.versionCode +
                    " installed=" + pi.firstInstallTime + " diagSchema=" + SCHEMA,
            )
        }
        runCatching {
            val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            val net = cm.activeNetwork
            val caps = net?.let { cm.getNetworkCapabilities(it) }
            val kinds = buildList {
                if (caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true) add("wifi")
                if (caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) == true) add("cellular")
                if (caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET) == true) add("ethernet")
            }
            val vpn = caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) == true
            val validated = caps?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
            append(
                "ENV",
                "network=${kinds.ifEmpty { listOf("none") }.joinToString("+")} vpn=$vpn validated=$validated " +
                    "metered=${caps?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED) != true}",
            )
            val links = if (vpn) net?.let { n -> cm.getLinkProperties(n)?.let { "active link present" } } ?: "n/a" else "n/a"
            append("ENV", "vpnLinks=$links")
        }.onFailure { append("ENV", "network unreadable: ${it.javaClass.simpleName}") }
        runCatching {
            // Both keys are readable without a permission. `opportunistic`/`off` is the normal state; a
            // hostname here means every lookup on the device goes via that resolver, which is a common cause
            // of a domain answering NXDOMAIN for one app and a browser.
            val mode = Settings.Global.getString(app.contentResolver, "private_dns_mode") ?: "off"
            val spec = Settings.Global.getString(app.contentResolver, "private_dns_specifier").orEmpty()
            append("ENV", "privateDns=$mode host=${tailLabels(spec, 2)}")
        }.onFailure { append("ENV", "privateDns unreadable: ${it.javaClass.simpleName}") }
        append("ENV", "utcNow=${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())} " +
            "deviceZone=${java.util.TimeZone.getDefault().id}")
        for (host in DNS_HOSES) {
            runCatching {
                val t0 = System.currentTimeMillis()
                val addrs = InetAddress.getAllByName(host)
                // Both families, tried separately. Reporting only "the first address" hides the exact case this
                // exists to catch: a host with a healthy A record and a AAAA record that blackholes, where the
                // stack picks the v6 answer and every request dies while a browser - which retries the other
                // family - loads the page in front of the same user.
                val v4 = addrs.filterIsInstance<java.net.Inet4Address>().firstOrNull()
                val v6 = addrs.filterIsInstance<java.net.Inet6Address>().firstOrNull()
                append(
                "DNS",
                host + ": " + addrs.size + " addr(s) A=" + (v4?.hostAddress ?: "none") +
                " AAAA=" + (v6?.hostAddress?.substringBefore("%") ?: "none"),
                )
                append(
                "TCP",
                host + " 443: v4=" + reach(v4) + " v6=" + reach(v6) +
                " in " + (System.currentTimeMillis() - t0) + "ms",
                )
                }.onFailure {
                append("DNS", host + " -> UNRESOLVABLE " + it.javaClass.simpleName + ": " + it.message?.take(120))
                }
                append("NET", "dnsServers=" + dnsServers())
                append("NET", "networkHandle=" + networkHandle())
                }
    }

    /**
     * The configured nameservers, reduced to [redacted] when they are on-device (a phone's own hotspot or a
     * VPN's tunnel hands out an address that is the user's business, not ours) and otherwise kept as-is
     * because a public resolver's address is public infrastructure. This exists for one question: is every
     * lookup on this device really going through the private-DNS profile the settings line names? "off" with a
     * resolver that is not the carrier's means a security app is intercepting DNS at the network layer, which
     * is a different conversation from "the railway refused us" - and the field report that prompted this had a
     * filtering resolver configured, five hosts failing to resolve within 70 ms of each other, and every one of
     * them having resolved fine a minute earlier.
     */
    private fun dnsServers(): String {
        val ctx = appContextForDns ?: return "n/a"
        return runCatching {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            val props = cm.activeNetwork?.let { cm.getLinkProperties(it) } ?: return "n/a"
            val addrs = props.dnsServers
            if (addrs.isEmpty()) return "none"
            addrs.joinToString(",") { a ->
                val s = a.hostAddress.orEmpty()
                if (isLocalAddress(s)) "[redacted]" else s
            }
        }.getOrElse { "unreadable " + it.javaClass.simpleName }
    }

    /**
     * Which network the socket is actually bound to. `Vpn` here with `vpn=false` above is the fingerprint of a
     * security app that takes traffic per-application rather than at the tunnel level; a name like
     * `WIFI(100)` is the plain case. No identifiers are included - this is the kind, not the network.
     */
    private fun networkHandle(): String {
        val ctx = appContextForDns ?: return "n/a"
        return runCatching {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            val n = cm.activeNetwork ?: return "no active network"
            n.networkCapabilities?.transportTypes?.joinToString("+") { android.net.ConnectivityManager
                .getNetworkTypeName(it) } ?: "?"
        }.getOrElse { "unreadable " + it.javaClass.simpleName }
    }

    private fun isLocalAddress(host: String): Boolean =
        host.startsWith("127.") || host.startsWith("10.") || host.startsWith("192.168.") ||
            host.startsWith("172.16.") || host.startsWith("172.17.") || host.startsWith("fe80") ||
            host == "::1"

    /** One address, one TCP attempt, one word back. "absent" is a fact about DNS, not about the network. */
    private fun reach(addr: InetAddress?): String =
        if (addr == null) "absent" else runCatching {
            Socket().use { s ->
                s.connect(InetSocketAddress(addr, 443), TCP_PROBE_MS)
                "ok"
            }
        }.getOrElse { "fail:" + it.javaClass.simpleName }


    /** Last `n` labels of a dotted name - enough to identify a resolver, not enough to identify an account. */
    internal fun tailLabels(host: String, n: Int): String =
        if (host.isBlank()) "-" else host.trimEnd('.').split('.').takeLast(n).joinToString(".")

    /**
     * Drops anything that looks like a credential before it can be written.
     *
     * The live feed's encrypted envelope and any future keyed provider both travel near this code, and a log
     * is by design something the user pastes into a public place. A name-based scrub is cheap insurance and
     * is tested, so that adding a new field cannot quietly start logging one.
     */
    internal fun sanitize(message: String): String {
        var out = REDACTED.replaceFirstIn(message)
        for (key in SECRET_KEYS) {
            out = out.replace(Regex("(?i)([?&\"]?$key[\"']?\\s*[:=]\\s*\")[^\"]*(\")"), "$1<redacted>$2")
            out = out.replace(Regex("(?i)([?&]?)$key\\s*[:=]\\s*([^&,\\s;]+)"), "$1$key=<redacted>")
        }
        if (out.length > MAX_LINE) out = out.substring(0, MAX_LINE) + "…(+${out.length - MAX_LINE}B)"
        return out.replace('\n', ' ')
    }

    private object REDACTED {
        /** A bare 32-hex blob (an MD5 digest, an AES key in the wild) has no business in a shared log. */
        private val bare = Regex("\\b[0-9a-fA-F]{32}\\b")
        fun replaceFirstIn(message: String): String = bare.replace(message) { "<redacted-digest>" }
    }

    /** Everything collected so far, as one text block: what Share hands to the OS. */
    fun text(limitBytes: Int = SHARE_BYTES): String {
        val header = "# OpenTrack India diagnostic log\n# build-reported entries below; collected only while " +
            "the switch on the Data source screen was on.\n"
        val all = synchronized(ring) { ring.joinToString("\n") }
        val body = if (all.length <= limitBytes) all else all.takeLast(limitBytes)
        val truncated = if (all.length > limitBytes) "[older ${all.length - limitBytes}B omitted]\n" else ""
        return header + truncated + body + "\n"
    }

    /** The file to save or attach; null when nothing has been collected. */
    fun file(): File? = if (file.exists() && file.length() > 0) file else null

    fun exportName(): String = "opentrack-diary-${stampFmt.format(Date())}.txt"

    fun clear() {
        // Nothing is written afterwards on purpose. The alternative - a "log cleared" line - recreates the
        // file the user just deleted, so the card would still claim to hold content seconds after they cleared it.
        runCatching { file.delete() }
        synchronized(ring) { ring.clear() }
    }

    companion object {
        const val SCHEMA = 1
        private const val RING_CAP = 2_000
        private const val MAX_BYTES = 2_000_000L
        private const val MAX_LINE = 400
        private const val SHARE_BYTES = 220_000
        private const val TCP_PROBE_MS = 2_500
        private val SECRET_KEYS = arrayOf(
            "app_key", "appkey", "api_key", "apikey", "token", "secret", "password", "sckey", "jsonKey",
        )
        private val DNS_HOSES = arrayOf(
            "enquiry.indianrail.gov.in", "erail.in", "api.railradar.in", "play.googleapis.com",
        )

        @Volatile
        private var instance: Diary? = null

        fun install(context: Context): Diary {
            val dir = File(context.applicationContext.filesDir, "diag").apply { mkdirs() }
            val d = Diary(File(dir, "log.txt"))
            instance = d
            return d
        }

        /** Null before [install]; every call site treats a missing diary as "collection is off". */
        fun get(): Diary? = instance
    }
}
