package io.github.ccch1mneyyy.dsh_app.runtime

import android.content.Context

/**
 * 全局省电: one flag the runtime and the overlays read. It only ever slows things down
 * (frames, logs, watchdog ticks); it never touches the foreground service, the wake lock or
 * the motion settings, because a task must keep running while the screen is off.
 */
object PowerSaver {
    @Volatile
    var enabled: Boolean = false
        private set

    fun refresh(context: Context) {
        enabled = KeepAliveGuard.globalPowerSaver(context)
    }
}
