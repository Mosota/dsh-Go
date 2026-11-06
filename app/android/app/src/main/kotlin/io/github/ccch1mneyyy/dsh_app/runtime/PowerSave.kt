package io.github.ccch1mneyyy.dsh_app.runtime

import android.content.Context

/**
 * 省电模式: while the engine would otherwise hold the phone awake, let the system
 * sleep — but only while nothing needs us.
 *
 * 已空闲，允许系统休眠: the foreground notification and the wake lock are released, so
 * Doze can take over. 有任务或状态待确认，继续保活: a phone task is running or a
 * confirmation is waiting, so keep-alive comes straight back.
 *
 * The service lives in this process, so the switch is a direct call rather than
 * a start-service intent (which would itself push the service back to the
 * foreground on Android 8+).
 */
object PowerSave {
    private const val PREFS = "dsh_power_save"
    private const val K_ENABLED = "enabled"

    /** Something needs the phone right now (set by phone control). */
    @Volatile var busy: Boolean = false
        private set

    private var held: DshService? = null

    fun register(service: DshService?) {
        held = service
    }

    fun enabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(K_ENABLED, false)

    fun setEnabled(context: Context, on: Boolean): Boolean {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(K_ENABLED, on).apply()
        apply()
        return true
    }

    /** A task started / a confirmation appeared ([busy] true) or the last one ended. */
    fun setBusy(value: Boolean) {
        if (busy == value) return
        busy = value
        apply()
    }

    /** Whether keep-alive should be held right now. */
    fun keepAlive(context: Context): Boolean = !enabled(context) || busy

    /** Re-evaluates the running service's foreground state, on the main thread. */
    fun apply() {
        val svc = held ?: return
        svc.mainHandler.post { svc.applyKeepAlive() }
    }
}
