package io.github.ccch1mneyyy.dsh_app.phone.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.RippleDrawable
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
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import io.github.ccch1mneyyy.dsh_app.MainActivity
import io.github.ccch1mneyyy.dsh_app.R
import io.github.ccch1mneyyy.dsh_app.phone.a11y.CapsuleMetrics
import io.github.ccch1mneyyy.dsh_app.phone.a11y.OverlayMotion
import io.github.ccch1mneyyy.dsh_app.phone.a11y.OverlayPalette
import io.github.ccch1mneyyy.dsh_app.phone.a11y.SurfacePainter
import io.github.ccch1mneyyy.dsh_app.phone.a11y.dp
import io.github.ccch1mneyyy.dsh_app.phone.a11y.dpf
import io.github.ccch1mneyyy.dsh_app.phone.a11y.softShadow
import io.github.ccch1mneyyy.dsh_app.phone.background.BackgroundControl
import java.util.concurrent.Executors
import kotlin.math.min

/**
 * 悬浮窗查看虚拟屏: a system-level window (TYPE_APPLICATION_OVERLAY) showing the virtual display a
 * task is running, and forwarding touches on it back to that display.
 *
 * The window is a client of the read-only preview owner in `PrivilegedDisplayCore`: it never
 * creates, takes or releases the display, so opening or closing it cannot end a task. A frame is
 * pulled on its own thread every [FRAME_INTERVAL_MS] and decoded on the main thread; with nothing
 * running the window keeps the last picture as its ground and says so over it — one quiet fade,
 * not a blink per poll — and keeps polling, so a later task appears by itself.
 *
 * Its looks are the accessibility control's ([io.github.ccch1mneyyy.dsh_app.phone.a11y.ControlOverlay]):
 * one rounded surface of `CapsuleMetrics.RADIUS_DP` dp drawn by the control's own [SurfacePainter]
 * (the palette's surface fall, top rim and hairline) over a soft elevation shadow, in the theme's
 * ink, ripple and field colours, with round-capped 1.75-unit glyphs on the same 24-unit grid — so
 * it reads as the same object as the capsule and the ball. It is not that control: it works without
 * the accessibility service, needs 显示在其他应用上层, and only ever shows pixels.
 */
class VscreenOverlayService : Service() {

    private lateinit var windows: WindowManager
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "dsh-vscreen-frame").apply { isDaemon = true } }
    private val control by lazy { BackgroundControl.get(applicationContext) }

    private var params: WindowManager.LayoutParams? = null
    private var root: View? = null
    private var card: View? = null
    private var body: FrameLayout? = null
    private var picture: ImageView? = null
    private var hint: View? = null
    private var shown: Bitmap? = null

    /** The width the person asked for (or the default); [cardW] is that after the limits. */
    private var wantedW = 0
    private var cardW = 0
    private var cardH = 0
    private var picH = 0

    private var hintOn = true
    private var closing = false

    /** The frame's pixels; touches are the view's, and are scaled by these. */
    @Volatile private var frameWidth = 0
    @Volatile private var frameHeight = 0
    @Volatile private var live = true
    @Volatile private var fetching = false

    /** The last picture's height/width: the card keeps its shape while no frame arrives. */
    private var lastAspect = EMPTY_ASPECT

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
        root?.animate()?.cancel()
        root?.let { runCatching { windows.removeViewImmediate(it) } }
        root = null
        card = null
        body = null
        picture = null
        hint = null
        shown?.recycle()
        shown = null
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Sizes are screen fractions, so they are taken again here; the colours stay the ones the
        // window opened with (nothing re-themes mid-window, as with the control's capsule).
        if (root != null) layout(wantedW)
    }

    // ---- the window --------------------------------------------------------

    private fun show() {
        val c = if (isDark()) OverlayPalette.DARK else OverlayPalette.LIGHT
        val metrics = resources.displayMetrics
        wantedW = (min(metrics.widthPixels, metrics.heightPixels) * WIDTH_FRACTION).toInt()

        val bodyView = FrameLayout(this).apply {
            clipChildren = true
            // The theme's field under everything: with no picture the area is the app's own
            // placeholder, never a black hole, and a letterboxed picture sits on it.
            background = rounded(c.field, dpf(PICTURE_RADIUS_DP.toFloat()))
        }
        val pic = ImageView(this).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            background = rounded(c.field, dpf(PICTURE_RADIUS_DP.toFloat()))
            setOnTouchListener(frameTouch)
            visibility = View.INVISIBLE
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val emptyView = emptyState(c)
        val grip = ResizeGrip(this, c).apply { setOnTouchListener(resizeTouch) }
        bodyView.addView(pic, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        bodyView.addView(emptyView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        bodyView.addView(grip, FrameLayout.LayoutParams(dp(GRIP_DP), dp(GRIP_DP), Gravity.BOTTOM or Gravity.END).apply {
            setMargins(0, 0, dp(2), dp(2))
        })

        val cardView = CardSurface(this, c).apply {
            addView(toolbar(c), FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(TOOLBAR_DP), Gravity.TOP))
            addView(bodyView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(MIN_PICTURE_DP)))
        }
        val frame = FrameLayout(this).apply {
            clipChildren = false
            clipToPadding = false
            addView(cardView)
        }

        card = cardView
        body = bodyView
        picture = pic
        hint = emptyView
        hintOn = true
        emptyView.alpha = 1f
        emptyView.visibility = View.VISIBLE

        val p = WindowManager.LayoutParams(
            (metrics.widthPixels * 0.5f).toInt(),
            (metrics.heightPixels * 0.5f).toInt(),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= 30) setFitInsetsTypes(0)
        }
        params = p
        // Sizes first: the window is not attached yet, so this only fills in the params.
        layout(wantedW)
        p.x = (metrics.widthPixels - cardW) / 2 - dp(PAD_X_DP)
        p.y = (metrics.heightPixels * 0.18f).toInt() - dp(PAD_TOP_DP)
        try {
            windows.addView(frame, p)
            root = frame
        } catch (e: Exception) {
            // The vendor's own floating-window switch can refuse after canDrawOverlays said yes.
            Log.w(TAG, "vscreen overlay window refused", e)
            stopSelf()
            return
        }
        appear(frame)
    }

    /** The toolbar: the grip, the title, 刷新 and 关闭. The bar itself is the window's handle. */
    private fun toolbar(c: OverlayPalette): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(6), 0, dp(4), 0)
        setOnTouchListener(dragTouch)
        addView(GripDots(this@VscreenOverlayService, c), LinearLayout.LayoutParams(dp(HANDLE_DP), ViewGroup.LayoutParams.MATCH_PARENT))
        addView(TextView(this@VscreenOverlayService).apply {
            text = TITLE
            textSize = 12f
            setTextColor(c.muted)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            includeFontPadding = false
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(7), 0, dp(6), 0)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(GlyphKey(this@VscreenOverlayService, KeyGlyph.REFRESH, c).apply {
            contentDescription = "刷新画面"
            setOnClickListener { fetch() }
        }, LinearLayout.LayoutParams(dp(KEY_DP), ViewGroup.LayoutParams.MATCH_PARENT))
        addView(GlyphKey(this@VscreenOverlayService, KeyGlyph.CLOSE, c).apply {
            contentDescription = "关闭悬浮窗"
            setOnClickListener { dismiss { stopSelf() } }
        }, LinearLayout.LayoutParams(dp(KEY_DP), ViewGroup.LayoutParams.MATCH_PARENT))
    }

    /**
     * 虚拟屏未运行: the app's empty state — a small mark over a line of faint ink, on a scrim of the
     * surface so it reads over a kept picture. Never a warning colour.
     */
    private fun emptyState(c: OverlayPalette): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        background = rounded(withAlpha(c.surfaceBottom, SCRIM_ALPHA), dpf(PICTURE_RADIUS_DP.toFloat()))
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        addView(ScreenMark(this@VscreenOverlayService, c.faint), LinearLayout.LayoutParams(dp(24), dp(24)))
        addView(TextView(this@VscreenOverlayService).apply {
            text = NOT_RUNNING
            textSize = 12f
            setTextColor(c.faint)
            includeFontPadding = false
            gravity = Gravity.CENTER
            setPadding(0, dp(7), 0, 0)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        })
    }

    /** The card's width after the limits: the person's wish, the screen's 60 %, the frame's height. */
    private fun layout(requested: Int) {
        val metrics = resources.displayMetrics
        val screenW = metrics.widthPixels
        val screenH = metrics.heightPixels
        val shortSide = min(screenW, screenH)
        val aspect = lastAspect.coerceIn(MIN_ASPECT, MAX_ASPECT)
        val barH = dp(TOOLBAR_DP)
        val inset = dp(INSET_DP)

        val maxPicH = ((screenH * MAX_HEIGHT_FRACTION).toInt() - barH - 2 * inset).coerceAtLeast(dp(MIN_PICTURE_DP))
        val minW = dp(MIN_WIDTH_DP)
        val maxW = min((shortSide * MAX_WIDTH_FRACTION).toInt(), (maxPicH / aspect).toInt() + 2 * inset)
        val w = requested.coerceIn(minW, maxW.coerceAtLeast(minW))
        val picW = (w - 2 * inset).coerceAtLeast(dp(48))
        val h = (picW * aspect).toInt().coerceIn(dp(MIN_PICTURE_DP), maxPicH)

        wantedW = requested
        cardW = w
        picH = h
        cardH = barH + 2 * inset + h

        card?.layoutParams = FrameLayout.LayoutParams(cardW, cardH, Gravity.TOP or Gravity.START).apply {
            leftMargin = dp(PAD_X_DP)
            topMargin = dp(PAD_TOP_DP)
        }
        body?.layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, picH).apply {
            setMargins(inset, barH + inset, inset, 0)
        }
        val p = params ?: return
        p.width = cardW + 2 * dp(PAD_X_DP)
        p.height = cardH + dp(PAD_TOP_DP) + dp(PAD_BOTTOM_DP)
        val view = root ?: return
        // Keep the whole card on the screen as it grows (only its shadow room may hang off).
        val minX = -dp(PAD_X_DP)
        val minY = -dp(PAD_TOP_DP)
        p.x = p.x.coerceIn(minX, (screenW - cardW - dp(PAD_X_DP)).coerceAtLeast(minX))
        p.y = p.y.coerceIn(minY, (screenH - cardH - dp(PAD_TOP_DP)).coerceAtLeast(minY))
        runCatching { windows.updateViewLayout(view, p) }
    }

    private fun appear(view: View) {
        view.animate().cancel()
        if (!OverlayMotion.on()) {
            view.alpha = 1f
            view.scaleX = 1f
            view.scaleY = 1f
            return
        }
        view.alpha = 0f
        view.scaleX = APPEAR_SCALE
        view.scaleY = APPEAR_SCALE
        view.animate().alpha(1f).scaleX(1f).scaleY(1f)
            .setDuration(ANIM_MS).setInterpolator(OverlayMotion.emphasized).start()
    }

    /** The reverse of [appear], then [then]: the window shrinks and fades before it goes. */
    private fun dismiss(then: () -> Unit) {
        if (closing) return
        closing = true
        val view = root
        if (view == null || !OverlayMotion.on()) {
            then()
            return
        }
        view.animate().cancel()
        view.animate().alpha(0f).scaleX(APPEAR_SCALE).scaleY(APPEAR_SCALE)
            .setDuration(ANIM_MS).setInterpolator(OverlayMotion.emphasized)
            .withEndAction { then() }.start()
    }

    private fun isDark(): Boolean =
        resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

    // ---- dragging the window ------------------------------------------------

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

    // ---- the corner grip: resizing from the bottom-right --------------------

    private var resizeStartX = 0f
    private var resizeStartWidth = 0

    private val resizeTouch = View.OnTouchListener { _, event ->
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                resizeStartX = event.rawX
                resizeStartWidth = wantedW
                true
            }
            MotionEvent.ACTION_MOVE -> {
                // The window's top-left is the anchor: the corner follows the finger.
                layout((resizeStartWidth + (event.rawX - resizeStartX)).toInt())
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
            val fw = frameWidth
            val fh = frameHeight
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    downAt = SystemClock.uptimeMillis()
                    if (slop == 0) slop = ViewConfiguration.get(this@VscreenOverlayService).scaledTouchSlop
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    val map = frameMap(v.width, v.height, fw, fh) ?: return true
                    val x1 = toFrame(downX, map, fw)
                    val y1 = toFrame(downY, map, fh)
                    val x2 = toFrame(event.x, map, fw)
                    val y2 = toFrame(event.y, map, fh)
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

    /**
     * Where the frame is drawn inside the view: the picture is FIT_CENTER, so a picture whose
     * height hit the screen's limit is letterboxed — these offsets keep touches on the pixels
     * they look at. When the view and the frame share an aspect the offsets are 0, i.e. the plain
     * `view / frame` scale.
     */
    private class FrameMap(val ox: Float, val oy: Float, val dw: Float, val dh: Float)

    private fun frameMap(viewW: Int, viewH: Int, fw: Int, fh: Int): FrameMap? {
        if (viewW <= 0 || viewH <= 0 || fw <= 0 || fh <= 0) return null
        val scale = min(viewW.toFloat() / fw, viewH.toFloat() / fh)
        val dw = fw * scale
        val dh = fh * scale
        return FrameMap((viewW - dw) / 2f, (viewH - dh) / 2f, dw, dh)
    }

    private fun toFrame(position: Float, map: FrameMap, frameSize: Int): Int =
        ((position - map.ox) * frameSize / map.dw).toInt().coerceIn(0, frameSize - 1)

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
            noTarget()
            return
        }
        val bytes = runCatching { Base64.decode(png, Base64.DEFAULT) }.getOrNull()
        val decoded = bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
        if (decoded == null) {
            noTarget()
            return
        }
        val w = (frame["width"] as? Int) ?: decoded.width
        val h = (frame["height"] as? Int) ?: decoded.height
        val sameShape = w == frameWidth && h == frameHeight
        frameWidth = w
        frameHeight = h
        if (w > 0 && h > 0) lastAspect = h.toFloat() / w
        val previous = shown
        shown = decoded
        picture?.setImageBitmap(decoded)
        showPicture()
        fadeHint(false)
        if (!sameShape) layout(wantedW)
        if (previous !== decoded) previous?.recycle()
    }

    /**
     * Nothing to show: the last picture stays as the card's ground, so the poll every
     * [FRAME_INTERVAL_MS] does not blink, and the 未运行 line fades in over it once.
     */
    private fun noTarget() {
        // Touch forwarding must not aim at a size this window no longer shows.
        frameWidth = 0
        frameHeight = 0
        fadeHint(true)
    }

    private fun fadeHint(on: Boolean) {
        if (hintOn == on) return
        hintOn = on
        val view = hint ?: return
        view.animate().cancel()
        if (!OverlayMotion.on()) {
            view.alpha = if (on) 1f else 0f
            view.visibility = if (on) View.VISIBLE else View.INVISIBLE
            return
        }
        if (on) {
            view.visibility = View.VISIBLE
            view.animate().alpha(1f).setDuration(HINT_MS).setInterpolator(OverlayMotion.emphasized).start()
        } else {
            view.animate().alpha(0f).setDuration(HINT_MS).setInterpolator(OverlayMotion.emphasized)
                .withEndAction { if (!hintOn) view.visibility = View.INVISIBLE }.start()
        }
    }

    private fun showPicture() {
        val view = picture ?: return
        if (view.visibility == View.VISIBLE) return
        view.visibility = View.VISIBLE
        view.animate().cancel()
        if (!OverlayMotion.on()) {
            view.alpha = 1f
            return
        }
        view.alpha = 0f
        view.animate().alpha(1f).setDuration(PICTURE_MS).setInterpolator(OverlayMotion.emphasized).start()
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

    companion object {
        private const val TAG = "DshVscreen"
        private const val CHANNEL_ID = "dsh-vscreen"
        private const val NOTIFICATION_ID = 3091
        private const val FRAME_INTERVAL_MS = 600L
        private const val TAP_TIMEOUT_MS = 300

        /** The card's default width: 42 % of the screen's short side; never past 60 %. */
        private const val WIDTH_FRACTION = 0.42f
        private const val MAX_WIDTH_FRACTION = 0.60f

        /** …nor past 72 % of its height, which the frame's aspect may reach first. */
        private const val MAX_HEIGHT_FRACTION = 0.72f
        private const val MIN_WIDTH_DP = 160
        private const val MIN_PICTURE_DP = 64

        private const val TOOLBAR_DP = 36
        private const val KEY_DP = 36
        private const val HANDLE_DP = 16
        private const val GRIP_DP = 32

        /** The picture's margin inside the card, all round. */
        private const val INSET_DP = 6

        /** Room round the card, inside the window, for its shadow (as the capsule has). */
        private const val PAD_X_DP = 12
        private const val PAD_TOP_DP = 10
        private const val PAD_BOTTOM_DP = 16

        /** The picture's corner: the card's less its inset, so the two curves stay concentric. */
        private const val PICTURE_RADIUS_DP = CapsuleMetrics.RADIUS_DP - INSET_DP

        private const val ANIM_MS = 150L
        private const val APPEAR_SCALE = 0.92f
        private const val HINT_MS = 180L
        private const val PICTURE_MS = 160L
        private const val SCRIM_ALPHA = 0xA6

        /** No picture yet: a small landscape card, 16 : 9. */
        private const val EMPTY_ASPECT = 9f / 16f
        private const val MIN_ASPECT = 0.3f
        private const val MAX_ASPECT = 3f

        private const val TITLE = "虚拟屏"

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

// ---- the card and what sits in it ------------------------------------------

/** [color] with [alpha] (0–255) as its own. */
private fun withAlpha(color: Int, alpha: Int): Int = (color and 0x00FFFFFF) or (alpha shl 24)

private fun rounded(color: Int, radius: Float): GradientDrawable = GradientDrawable().apply {
    setColor(color)
    cornerRadius = radius
}

/**
 * The card: one rounded rect of solid surface (`CapsuleMetrics.RADIUS_DP` dp) drawn by the
 * control's own [SurfacePainter] — the faint top-to-bottom fall of the surface colour, the top rim
 * of light and the hairline — over a soft elevation shadow, with every child cut to that outline
 * (so the toolbar's ends and the picture's corners follow the card).
 */
private class CardSurface(context: Context, private val c: OverlayPalette) : FrameLayout(context) {
    private val painter = SurfacePainter(context)
    private val radius = context.dpf(CapsuleMetrics.RADIUS_DP.toFloat())
    private val rect = RectF()

    init {
        setWillNotDraw(false)
        clipChildren = true
        clipToOutline = true
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, radius)
            }
        }
        softShadow(c, ELEVATION_DP)
        alpha = SURFACE_ALPHA
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rect.set(0f, 0f, w.toFloat(), h.toFloat())
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (rect.isEmpty) rect.set(0f, 0f, width.toFloat(), height.toFloat())
        painter.draw(canvas, rect, radius, c)
    }

    private companion object {
        /** The control's own elevation (outline + ambient shadow, softly). */
        const val ELEVATION_DP = 6f

        /** The surface is the theme's, a breath translucent: the page reads through the chrome. */
        const val SURFACE_ALPHA = 0.97f
    }
}

/** What the toolbar's two keys draw. */
private enum class KeyGlyph { REFRESH, CLOSE }

/**
 * The keys' glyphs, on the control's 24-unit grid with its round-capped 1.75-unit stroke
 * ([io.github.ccch1mneyyy.dsh_app.phone.a11y.Glyph]).
 */
private fun drawKeyGlyph(canvas: Canvas, glyph: KeyGlyph, cx: Float, cy: Float, size: Float, paint: Paint, path: Path, oval: RectF) {
    val s = size / 24f
    paint.style = Paint.Style.STROKE
    paint.strokeWidth = 1.75f * s
    paint.strokeCap = Paint.Cap.ROUND
    paint.strokeJoin = Paint.Join.ROUND
    path.reset()
    when (glyph) {
        KeyGlyph.REFRESH -> {
            // An arc that all but closes, with the head of the arrow in its gap at the top.
            oval.set(4.5f, 4.5f, 19.5f, 19.5f)
            path.addArc(oval, -55f, 295f)
            path.moveTo(6.49f, 5.01f)
            path.lineTo(9.29f, 4.91f)
            path.lineTo(7.81f, 7.28f)
        }
        KeyGlyph.CLOSE -> {
            path.moveTo(7.5f, 7.5f)
            path.lineTo(16.5f, 16.5f)
            path.moveTo(16.5f, 7.5f)
            path.lineTo(7.5f, 16.5f)
        }
    }
    canvas.save()
    canvas.translate(cx - size / 2f, cy - size / 2f)
    canvas.scale(s, s)
    canvas.drawPath(path, paint)
    canvas.restore()
}

/**
 * 刷新 / 关闭: a round ripple over the key's disc, the glyph inside and the 0.92 press the control's
 * keys have. The whole 36 dp square is the target.
 */
private class GlyphKey(context: Context, private val glyph: KeyGlyph, private val c: OverlayPalette) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val oval = RectF()

    init {
        isClickable = true
        isFocusable = true
        foreground = RippleDrawable(
            ColorStateList.valueOf(c.ripple), null,
            InsetDrawable(GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) }, context.dp(3)),
        )
    }

    override fun getAccessibilityClassName(): CharSequence = android.widget.Button::class.java.name

    override fun setPressed(pressed: Boolean) {
        super.setPressed(pressed)
        val s = if (pressed) 0.92f else 1f
        if (OverlayMotion.on()) {
            animate().scaleX(s).scaleY(s).setDuration(if (pressed) 90 else 200).setInterpolator(OverlayMotion.glide).start()
        } else {
            scaleX = s
            scaleY = s
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        paint.color = c.muted
        drawKeyGlyph(canvas, glyph, width / 2f, height / 2f, context.dpf(GLYPH_DP.toFloat()), paint, path, oval)
    }

    private companion object {
        const val GLYPH_DP = 18
    }
}

/** The bar's start: three small dots, the app's grip; the bar round them is what drags. */
private class GripDots(context: Context, private val c: OverlayPalette) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        paint.color = c.faint
        paint.alpha = 0xB0
        val cx = width / 2f
        val cy = height / 2f
        val r = context.dpf(1.3f)
        val step = context.dpf(3.8f)
        for (i in -1..1) canvas.drawCircle(cx, cy + i * step, r, paint)
    }
}

/** The 未运行 mark: a small display, drawn like the control's line glyphs. */
private class ScreenMark(context: Context, ink: Int) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = ink
    }
    private val path = Path()
    private val screen = RectF()

    init {
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val s = min(width, height) / 24f
        paint.strokeWidth = 1.6f * s
        path.reset()
        screen.set(4.5f, 6f, 19.5f, 16.5f)
        path.addRoundRect(screen, 2.4f, 2.4f, Path.Direction.CW)
        path.moveTo(12f, 16.5f)
        path.lineTo(12f, 20f)
        path.moveTo(8.5f, 20.3f)
        path.lineTo(15.5f, 20.3f)
        canvas.save()
        canvas.translate((width - 24f * s) / 2f, (height - 24f * s) / 2f)
        canvas.scale(s, s)
        canvas.drawPath(path, paint)
        canvas.restore()
    }
}

/**
 * The corner grip: a small L, drawn twice — a halo of the surface's colour under the ink, so the
 * mark reads over whatever picture is under it, without a plaque. Dragged, it resizes the window
 * from its bottom-right; the service keeps it in bounds.
 */
private class ResizeGrip(context: Context, private val c: OverlayPalette) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    init {
        contentDescription = "拖动缩放悬浮窗"
        isFocusable = true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val near = context.dpf(6.5f)
        val far = context.dpf(11.5f)
        paint.color = withAlpha(c.surfaceBottom, 0xCC)
        paint.strokeWidth = context.dpf(4f)
        elbow(canvas, near, far)
        paint.color = c.muted
        paint.strokeWidth = context.dpf(1.5f)
        elbow(canvas, near, far)
    }

    private fun elbow(canvas: Canvas, near: Float, far: Float) {
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawLine(w - near, h - far, w - near, h - near, paint)
        canvas.drawLine(w - far, h - near, w - near, h - near, paint)
    }
}
