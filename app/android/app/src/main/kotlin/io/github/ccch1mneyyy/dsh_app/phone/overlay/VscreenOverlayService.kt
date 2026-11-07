package io.github.ccch1mneyyy.dsh_app.phone.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Base64
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import io.github.ccch1mneyyy.dsh_app.MainActivity
import io.github.ccch1mneyyy.dsh_app.R
import io.github.ccch1mneyyy.dsh_app.phone.background.BackgroundControl
import java.util.concurrent.Executors

/**
 * 悬浮窗查看虚拟屏: a system-level window (TYPE_APPLICATION_OVERLAY) showing the virtual display a
 * task is running, and forwarding touches on it back to that display.
 *
 * The window is a client of the read-only preview owner in `PrivilegedDisplayCore`: it never
 * creates, takes or releases the display, so opening or closing it cannot end a task. A frame is
 * pulled on its own thread every [FRAME_INTERVAL_MS] and decoded on the main thread; with nothing
 * running the window says so and keeps polling, so a later task appears by itself.
 *
 * This is not the accessibility overlay ([io.github.ccch1mneyyy.dsh_app.phone.a11y.ControlOverlay]):
 * it works without the accessibility service, needs 显示在其他应用上层, and only ever shows pixels.
 */
class VscreenOverlayService : Service() {

    private lateinit var windows: WindowManager
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "dsh-vscreen-frame").apply { isDaemon = true } }
    private val control by lazy { BackgroundControl.get(applicationContext) }

    private var params: WindowManager.LayoutParams? = null
    private var root: View? = null
    private var image: ImageView? = null
    private var empty: TextView? = null
    private var shown: Bitmap? = null

    /** The frame's pixels; touches are the view's, and are scaled by these. */
    @Volatile private var frameWidth = 0
    @Volatile private var frameHeight = 0
    @Volatile private var live = true
    @Volatile private var fetching = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        live = true
        windows = getSystemService(WindowManager::class.java)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            val n = notification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, n)
            }
        } catch (e: Exception) {
            // A vendor policy refused the foreground start: without it the window must not stay.
            Log.w(TAG, "vscreen overlay could not go foreground", e)
            stopSelf()
            return START_NOT_STICKY
        }
        if (root == null) show()
        main.removeCallbacks(tick)
        main.post(tick)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        live = false
        main.removeCallbacks(tick)
        io.shutdownNow()
        root?.let { runCatching { windows.removeViewImmediate(it) } }
        root = null
        image = null
        empty = null
        shown?.recycle()
        shown = null
        super.onDestroy()
    }

    // ---- the window --------------------------------------------------------

    private fun show() {
        val density = resources.displayMetrics.density
        val width = (resources.displayMetrics.widthPixels * WIDTH_FRACTION).toInt()
            .coerceAtLeast((MIN_WIDTH_DP * density).toInt())

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(3), dp(2), dp(3))
            setBackgroundColor(BAR_COLOR)
            // The bar is the handle: dragging it moves the whole window, never the display.
            setOnTouchListener(dragTouch)
        }
        bar.addView(TextView(this).apply {
            text = "虚拟屏"
            setTextColor(Color.WHITE)
            textSize = 12f
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(TextView(this).apply {
            text = "关闭"
            setTextColor(TITLE_COLOR)
            textSize = 12f
            setPadding(dp(10), dp(2), dp(10), dp(2))
            setOnClickListener { stopSelf() }
        })

        val picture = ImageView(this).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.BLACK)
            setOnTouchListener(frameTouch)
        }
        val placeholder = TextView(this).apply {
            text = NOT_RUNNING
            setTextColor(Color.WHITE)
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(18), dp(8), dp(18))
            setBackgroundColor(BAR_COLOR)
        }

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }
        column.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        column.addView(picture, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        column.addView(placeholder, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        image = picture
        empty = placeholder

        val p = WindowManager.LayoutParams(
            width,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(12)
            y = (resources.displayMetrics.heightPixels * 0.2f).toInt()
        }
        params = p
        try {
            windows.addView(column, p)
            root = column
        } catch (e: Exception) {
            // The vendor's own floating-window switch can refuse after canDrawOverlays said yes.
            Log.w(TAG, "vscreen overlay window refused", e)
            stopSelf()
        }
    }

    private var dragStartX = 0f
    private var dragStartY = 0f
    private var dragOriginX = 0
    private var dragOriginY = 0

    private val dragTouch = View.OnTouchListener { _, event ->
        val p = params ?: return@OnTouchListener false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragStartX = event.rawX
                dragStartY = event.rawY
                dragOriginX = p.x
                dragOriginY = p.y
                true
            }
            MotionEvent.ACTION_MOVE -> {
                p.x = dragOriginX + (event.rawX - dragStartX).toInt()
                p.y = dragOriginY + (event.rawY - dragStartY).toInt()
                root?.let { runCatching { windows.updateViewLayout(it, p) } }
                true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> true
            else -> false
        }
    }

    // ---- touches on the picture → virtual-screen pixels --------------------

    private val frameTouch = object : View.OnTouchListener {
        private var downX = 0f
        private var downY = 0f
        private var downAt = 0L
        private var slop = 0

        override fun onTouch(v: View, event: MotionEvent): Boolean {
            val viewWidth = v.width
            val viewHeight = v.height
            val frameW = frameWidth
            val frameH = frameHeight
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    downAt = SystemClock.uptimeMillis()
                    if (slop == 0) slop = ViewConfiguration.get(this@VscreenOverlayService).scaledTouchSlop
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    if (frameW <= 0 || frameH <= 0 || viewWidth <= 0 || viewHeight <= 0) return true
                    val x1 = toFrame(downX, viewWidth, frameW)
                    val y1 = toFrame(downY, viewHeight, frameH)
                    val x2 = toFrame(event.x, viewWidth, frameW)
                    val y2 = toFrame(event.y, viewHeight, frameH)
                    val held = (SystemClock.uptimeMillis() - downAt).toInt()
                    val movedX = event.x - downX
                    val movedY = event.y - downY
                    val travelled = movedX * movedX + movedY * movedY
                    val tap = travelled < (slop * slop).toFloat() && held < TAP_TIMEOUT_MS
                    // Decided on release, so a tap that degenerates into a swipe still scrolls
                    // the display instead of clicking wherever the finger started.
                    onFrameThread {
                        if (tap) control.vscreenTap(x1, y1)
                        else control.vscreenSwipe(x1, y1, x2, y2, held.coerceIn(80, 1_200))
                    }
                    return true
                }
                else -> return true
            }
        }
    }

    /** The picture is drawn at the frame's aspect ratio (adjustViewBounds): one linear scale. */
    private fun toFrame(position: Float, viewSize: Int, frameSize: Int): Int =
        (position * frameSize / viewSize).toInt().coerceIn(0, frameSize - 1)

    // ---- frames ------------------------------------------------------------

    private val tick = object : Runnable {
        override fun run() {
            if (!live) return
            fetch()
            main.postDelayed(this, FRAME_INTERVAL_MS)
        }
    }

    private fun fetch() {
        if (fetching) return
        fetching = true
        // The channel call blocks (and may wait for a task's own call): never the main thread.
        onFrameThread {
            val frame = runCatching { control.frame() }.getOrNull()
            main.post {
                fetching = false
                if (live) apply(frame)
            }
        }
    }

    /** Runs [block] on the frame thread; a service already going away drops it. */
    private fun onFrameThread(block: () -> Unit) {
        if (!live) return
        runCatching { io.execute { runCatching(block) } }
    }

    private fun apply(frame: Map<String, Any?>?) {
        val png = frame?.get("png") as? String
        if (frame == null || frame["ok"] != true || png == null) {
            showPlaceholder()
            return
        }
        val bytes = runCatching { Base64.decode(png, Base64.DEFAULT) }.getOrNull()
        val decoded = bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
        if (decoded == null) {
            showPlaceholder()
            return
        }
        val previous = shown
        shown = decoded
        frameWidth = (frame["width"] as? Int) ?: decoded.width
        frameHeight = (frame["height"] as? Int) ?: decoded.height
        image?.setImageBitmap(decoded)
        image?.visibility = View.VISIBLE
        empty?.visibility = View.GONE
        if (previous !== decoded) previous?.recycle()
    }

    private fun showPlaceholder() {
        // No display: touch forwarding must not aim at a size this window no longer shows.
        frameWidth = 0
        frameHeight = 0
        image?.visibility = View.GONE
        empty?.visibility = View.VISIBLE
    }

    // ---- foreground --------------------------------------------------------

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm?.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "虚拟屏悬浮窗", NotificationManager.IMPORTANCE_MIN).apply { setShowBadge(false) },
        )
        val open = PendingIntent.getActivity(
            this, NOTIFICATION_ID,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_dsh)
            .setContentTitle("虚拟屏")
            .setContentText("悬浮窗正在显示虚拟屏画面")
            .setOngoing(true)
            .setContentIntent(open)
            .build()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val TAG = "DshVscreen"
        private const val CHANNEL_ID = "dsh-vscreen"
        private const val NOTIFICATION_ID = 3091
        private const val FRAME_INTERVAL_MS = 600L
        private const val TAP_TIMEOUT_MS = 300
        private const val WIDTH_FRACTION = 0.42f
        private const val MIN_WIDTH_DP = 140
        private const val BAR_COLOR = 0xCC202124.toInt()
        private const val TITLE_COLOR = 0xFF8AB4F8.toInt()

        // l10n:keep agent-vs-screen wording (same line BackgroundControl gives the models)
        private const val NOT_RUNNING = "虚拟屏未运行"

        /** `reasonCode` of opening the overlay without 显示在其他应用上层. */
        const val NO_PERMISSION = "noOverlayPermission"

        /**
         * Opens or closes the window. Without [Settings.canDrawOverlays] it opens the system
         * switch instead of starting a service that cannot add its view, and reports
         * [NO_PERMISSION] so the app can say why.
         */
        fun ensure(context: Context, open: Boolean): Map<String, Any?> {
            val app = context.applicationContext
            if (!open) {
                runCatching { app.stopService(Intent(app, VscreenOverlayService::class.java)) }
                return mapOf("ok" to true)
            }
            if (!Settings.canDrawOverlays(app)) {
                runCatching { app.startActivity(permissionIntent(app)) }
                return mapOf("ok" to false, "reasonCode" to NO_PERMISSION, "reason" to "请允许显示在其他应用上层")
            }
            return try {
                app.startForegroundService(Intent(app, VscreenOverlayService::class.java))
                mapOf("ok" to true)
            } catch (e: Exception) {
                Log.w(TAG, "vscreen overlay could not start", e)
                mapOf("ok" to false, "reason" to "悬浮窗启动失败，请稍后再试")
            }
        }

        private fun permissionIntent(context: Context): Intent =
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
