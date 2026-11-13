package io.github.ccch1mneyyy.dsh_app.runtime

import android.content.Context
import android.util.Log
import io.github.ccch1mneyyy.dsh_app.phone.background.BackgroundControl
import java.io.File
import java.util.concurrent.Executors

/**
 * 息屏保活's privileged half: the system exemptions and a shell-uid watchdog that outlives this
 * app's process.
 *
 * 特权通道 and 手机操控 are separate switches: this only needs the channel
 * ([BackgroundControl.status] mode != off and ready), never 手机操控. Turning 手机操控 off must
 * not stop it; turning the channel off must.
 *
 * The watchdog runs as the channel's uid (shell/root), so ColorOS freezing the app's uid does not
 * freeze it. It starts DshService again when the engine process (`dsh web`) is gone. Its stop flag
 * lives in the app's external files dir, which shell can read even when the app is frozen; that is
 * the fallback when the channel is already off and `kill` cannot be delivered.
 */
object KeepAliveGuard {
    private const val TAG = "DshKeepAlive"
    private const val PREFS = "dsh_keepalive"
    private const val K_ENABLED = "keepAliveEnabled"
    private const val K_ECONOMY = "keepAliveEconomy"
    private const val K_GLOBAL_SAVER = "globalPowerSaver"
    const val PKG = "io.github.mosota.dshgo"
    private const val SERVICE = "$PKG/io.github.ccch1mneyyy.dsh_app.runtime.DshService"

    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "dsh-keepalive").apply { isDaemon = true } }
    @Volatile private var lastInterval = -1
    @Volatile private var lastAttempt = 0L
    @Volatile private var lastExempt = 0L

    fun keepAliveEnabled(context: Context): Boolean = pref(context).getBoolean(K_ENABLED, true)
    fun economyEnabled(context: Context): Boolean = pref(context).getBoolean(K_ECONOMY, true)
    fun globalPowerSaver(context: Context): Boolean = pref(context).getBoolean(K_GLOBAL_SAVER, false)

    /** The watchdog's probe interval: global saver wins, then economy, then the normal 60 s. */
    fun watchSeconds(context: Context): Int = when {
        globalPowerSaver(context) -> 300
        economyEnabled(context) -> 300
        else -> 60
    }

    fun prefs(context: Context): Map<String, Any?> = mapOf(
        "ok" to true,
        "keepAliveEnabled" to keepAliveEnabled(context),
        "keepAliveEconomy" to economyEnabled(context),
        "globalPowerSaver" to globalPowerSaver(context),
        "watchSeconds" to watchSeconds(context),
        "watchdogRunning" to (readPid(context) != null),
    )

    /** Dart's `keepAlivePrefs` / `setKeepAlivePrefs`: store, then apply or undo at once. */
    fun writePrefs(context: Context, args: Map<*, *>): Map<String, Any?> {
        val app = context.applicationContext
        val edit = pref(app).edit()
        (args[K_ENABLED] as? Boolean)?.let { edit.putBoolean(K_ENABLED, it) }
        (args[K_ECONOMY] as? Boolean)?.let { edit.putBoolean(K_ECONOMY, it) }
        (args[K_GLOBAL_SAVER] as? Boolean)?.let { edit.putBoolean(K_GLOBAL_SAVER, it) }
        edit.apply()
        PowerSaver.refresh(app)
        DshService.refreshKeepAlive(app)
        if (keepAliveEnabled(app)) startAsync(app, DshService.isRunning) else stopAsync(app, revoke = true)
        return prefs(app)
    }

    /** Called on service create/start and when the app comes back to the foreground. */
    fun startAsync(context: Context, engineRunning: Boolean) {
        val app = context.applicationContext
        if (!keepAliveEnabled(app)) {
            stopAsync(app, revoke = true)
            return
        }
        worker.execute { runCatching { startNow(app, engineRunning) }.onFailure { Log.i(TAG, "start skipped: ${it.javaClass.simpleName}") } }
    }

    /** A status change (channel ready / gone). Channel gone must also stop the watchdog. */
    fun onChannelChanged(context: Context) {
        val app = context.applicationContext
        PowerSaver.refresh(app)
        // Status changes can arrive in bursts; one probe per two seconds is plenty.
        val now = System.currentTimeMillis()
        if (now - lastAttempt < 2_000) return
        lastAttempt = now
        if (!keepAliveEnabled(app)) stopAsync(app, revoke = true) else startAsync(app, DshService.isRunning)
    }

    /** Explicit stop (user turned 保持后台运行 off, or stopped the engine): kill + revoke. */
    fun stopAsync(context: Context, revoke: Boolean = true) {
        val app = context.applicationContext
        worker.execute { runCatching { stopNow(app, revoke) } }
    }

    /** The engine was stopped on purpose: its watchdog must not start it again. */
    fun onEngineStopped(context: Context) {
        stopAsync(context, revoke = false)
    }

    /** The service itself is going away; the watchdog must survive it. */
    fun onServiceDestroy() = Unit

    private fun startNow(app: Context, engineRunning: Boolean) {
        val bg = BackgroundControl.get(app)
        val status = bg.status()
        val mode = status["mode"] as? String
        val ready = status["ready"] == true
        if (mode == null || mode == "off" || !ready) {
            // Channel gone: try to undo the exemptions with the last usable channel; the flag
            // makes the watchdog exit on its next loop even when `kill` cannot be delivered.
            stopNow(app, revoke = true)
            return
        }
        runCatching {
            flag(app).parentFile?.mkdirs()
            flag(app).writeText("1")
        }
        val now = System.currentTimeMillis()
        if (now - lastExempt > 10 * 60_000L) {
            applyExemptions(app, bg)
            lastExempt = now
        }
        if (!engineRunning) {
            stopWatchdog(app, bg)
            return
        }
        val interval = watchSeconds(app)
        if (readPid(app) != null && pidAlive(app, bg) && lastInterval == interval) return
        stopWatchdog(app, bg)
        startWatchdog(app, bg, interval)
    }

    private fun stopNow(app: Context, revoke: Boolean) {
        flag(app).delete()
        val bg = BackgroundControl.get(app)
        stopWatchdog(app, bg)
        if (revoke) revokeExemptions(app, bg)
        lastInterval = -1
    }

    private fun startWatchdog(app: Context, bg: BackgroundControl, interval: Int) {
        val body = "while true; do " +
            "[ -f \"${flag(app).absolutePath}\" ] || exit 0; " +
            "if ! pgrep -f \"dsh web\" >/dev/null 2>&1; then " +
            "am start-foreground-service -n $SERVICE >/dev/null 2>&1; fi; " +
            "sleep $interval; done # dsh-keepalive"
        val quoted = "sh -c '$body'"
        // setsid detaches from the channel's process group; nohup is the fallback when toybox lacks it.
        val pid = parsePid(run(bg, "setsid $quoted >/dev/null 2>&1 < /dev/null & echo \$!", 8_000))
            ?: parsePid(run(bg, "nohup $quoted >/dev/null 2>&1 < /dev/null & echo \$!", 8_000))
        if (pid == null) {
            Log.i(TAG, "watchdog did not start")
            return
        }
        runCatching { pidFile(app).writeText(pid.toString()) }
        lastInterval = interval
        Log.i(TAG, "watchdog pid=$pid interval=${interval}s")
    }

    private fun stopWatchdog(app: Context, bg: BackgroundControl) {
        val pid = readPid(app)
        if (pid != null) run(bg, "kill $pid 2>/dev/null; pkill -f dsh-keepalive 2>/dev/null", 5_000)
        pidFile(app).delete()
        lastInterval = -1
    }

    private fun pidAlive(app: Context, bg: BackgroundControl): Boolean {
        val pid = readPid(app) ?: return false
        val result = run(bg, "kill -0 $pid 2>/dev/null", 5_000)
        return result["ok"] == true && (result["rc"] as? Number)?.toInt() == 0
    }

    /** Each exemption is best effort: a ROM that refuses it must not break the engine. */
    private fun applyExemptions(app: Context, bg: BackgroundControl) {
        val commands = mutableListOf(
            "cmd deviceidle whitelist +$PKG",
            "cmd appops set $PKG RUN_ANY_IN_BACKGROUND allow",
            "cmd appops set $PKG RUN_IN_BACKGROUND allow",
            "am set-standby-bucket $PKG active",
        )
        // 省电时 explicitly does not ask for a performance mode.
        if (!economyEnabled(app) && !globalPowerSaver(app)) {
            commands += "cmd power set-fixed-performance-mode-enabled true"
        }
        for (cmd in commands) okTwice(bg, cmd)
    }

    private fun revokeExemptions(app: Context, bg: BackgroundControl) {
        for (cmd in listOf(
            "cmd deviceidle whitelist -$PKG",
            "cmd appops set $PKG RUN_ANY_IN_BACKGROUND default",
            "cmd appops set $PKG RUN_IN_BACKGROUND default",
            "am set-standby-bucket $PKG rare",
        )) okTwice(bg, cmd)
    }

    /** Once, and once again if it failed: a channel that just came up may refuse the first call. */
    private fun okTwice(bg: BackgroundControl, cmd: String) {
        repeat(2) {
            val result = run(bg, cmd, 8_000)
            val rc = (result["rc"] as? Number)?.toInt() ?: -1
            if (result["ok"] == true && rc == 0) return
        }
    }

    private fun run(bg: BackgroundControl, cmd: String, timeoutMs: Int): Map<String, Any?> =
        runCatching { bg.exec(cmd, timeoutMs, null) }.getOrNull() ?: emptyMap()

    private fun parsePid(result: Map<String, Any?>): Int? {
        val out = result["stdout"] as? String ?: return null
        return out.lineSequence().mapNotNull { it.trim().toIntOrNull() }.lastOrNull()
    }

    private fun pref(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun dir(context: Context): File = File(context.filesDir, "runtime").apply { mkdirs() }
    private fun pidFile(context: Context) = File(dir(context), "keepalive.pid")
    private fun readPid(context: Context): Int? = runCatching { pidFile(context).readText().trim().toInt() }.getOrNull()

    /** Readable by the shell-uid watchdog; its absence tells it to exit. */
    private fun flag(context: Context): File =
        File(runCatching { context.getExternalFilesDir(null) }.getOrNull() ?: context.filesDir, "keepalive.on")
}

/**
 * 全局省电 as seen by the services: no animation decision is made here (性能 settings own that),
 * only frame polling and log verbosity read this.
 */
object PowerSaver {
    @Volatile var enabled: Boolean = false
        private set

    fun refresh(context: Context) {
        enabled = runCatching { KeepAliveGuard.globalPowerSaver(context) }.getOrDefault(false)
    }
}
