package io.github.ccch1mneyyy.dsh_app.phone.overlay

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
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
import android.graphics.drawable.ColorDrawable
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
import android.text.TextUtils
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
import io.github.ccch1mneyyy.dsh_app.phone.a11y.OverlayPrefs
import io.github.ccch1mneyyy.dsh_app.phone.a11y.SurfacePainter
import io.github.ccch1mneyyy.dsh_app.phone.a11y.dp
import io.github.ccch1mneyyy.dsh_app.phone.a11y.dpf
import io.github.ccch1mneyyy.dsh_app.phone.a11y.softShadow
import io.github.ccch1mneyyy.dsh_app.phone.background.BackgroundControl
import java.util.Arrays
import java.util.concurrent.Executors
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 悬浮窗查看虚拟屏: a system-level window (TYPE_APPLICATION_OVERLAY) showing the virtual display a
 * task is running, and forwarding touches on it back to that display.
 *
 * The window is a client of the read-only preview owner in `PrivilegedDisplayCore`: it never
 * creates, takes or releases the display, so opening or closing it cannot end a task. A frame is
 * pulled on its own thread and decoded on the main thread; with nothing running the window keeps
 * the last picture as its ground and says why over it — one quiet fade, not a blink per poll — and
 * keeps polling, so a later task appears by itself.
 *
 * It looks like the app's own card: one rounded surface of `CapsuleMetrics.RADIUS_DP` dp drawn by
 * the control's own [SurfacePainter] (the palette's surface fall, top rim and hairline) over a soft
 * elevation shadow, a light bar with ≡ 虚拟屏 刷新 关闭, the picture in a rounded window and a short
 * handle at the foot. ≡ hands the window over to 悬浮球 ([VscreenBallService]) and folds it away;
 * the ball's 查看虚拟屏 brings it back. It is not the accessibility control: it works without that
 * service, needs 显示在其他应用上层, and only ever shows pixels.
 *
 * Three things follow what the person does with it rather than the display: it reopens where it was
 * left ([VscreenWinPrefs]), its sides snap to the screen edges, and the poll slows down while the
 * picture stays the same and stops while the window is dragged or away.
 */
class VscreenOverlayService : Service() {

    private lateinit var windows: WindowManager
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "dsh-vscreen-frame").apply { isDaemon = true } }
    private val control by lazy { BackgroundControl.get(applicationContext) }

    private var params: WindowManager.LayoutParams? = null
    private var root: WindowFrame? = null
    private var card: View? = null
    private var body: FrameLayout? = null
    private var picture: ImageView? = null
    private var hint: View? = null
    private var hintText: TextView? = null
    private var feedback: RippleDot? = null
    private var shown: Bitmap? = null

    /** The width the person asked for (or the default); [cardW] is that after the limits. */
    private var wantedW = 0

    /** The picture height the person dragged to; 0 means "follow the frame's shape". */
    private var wantedH = 0
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

    // ---- the poll: its pace, and when it is allowed to run ------------------

    /** The wait before the next frame: quick while something moves, slow while nothing does. */
    private var pollMs = FAST_MS

    /** The last frame's rough signature, [NO_FRAME] when none was there: two alike slow the poll. */
    private var lastSig = NO_FRAME

    /** A frame has arrived at least once: no channel yet is 通道未开启, a lost one 通道已断开. */
    private var everLive = false

    /** A drag or a resize is under way: no frames, and the window follows the finger alone. */
    private var dragging = false

    /** The system can hide an overlay (the screen went off): the poll stops with it. */
    private var windowVisible = true

    /** The glide to the screen edge after a drag was dropped near one. */
    private var glide: ValueAnimator? = null

    @Suppress("DEPRECATION")
    private val wakeReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
            if (intent?.action == ACTION_WAKE && PowerSaver.enabled) resumePoll()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        live = true
        PowerSaver.refresh(this)
        windows = getSystemService(WindowManager::class.java)
        @Suppress("DEPRECATION")
        runCatching { registerReceiver(wakeReceiver, android.content.IntentFilter(ACTION_WAKE)) }
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
        resumePoll()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        live = false
        runCatching { unregisterReceiver(wakeReceiver) }
        available = null
        showing = false
        saveWin()
        main.removeCallbacks(tick)
        glide?.cancel()
        glide = null
        io.shutdownNow()
        root?.animate()?.cancel()
        root?.let { runCatching { windows.removeViewImmediate(it) } }
        root = null
        card = null
        body = null
        picture = null
        hint = null
        hintText = null
        feedback = null
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
        val saved = VscreenWinPrefs.read(this)
        // A window coming back (关闭 hid it) keeps the size and place it had; the first one takes
        // them from the last time it was open, else is sized to the screen.
        if (wantedW == 0) {
            wantedW = if (saved.widthDp > 0) dp(saved.widthDp)
            else (min(metrics.widthPixels, metrics.heightPixels) * WIDTH_FRACTION).toInt()
        }
        // Portrait by default: a tall window that shows a landscape picture letterboxed, which is
        // what the person expects from a phone overlay. Only a height the person dragged wins.
        if (wantedH == 0 && saved.heightDp > 0) wantedH = dp(saved.heightDp)
        val wasAt = params?.let { it.x to it.y } ?: saved.at
        closing = false

        val bodyView = FrameLayout(this).apply {
            clipChildren = true
            // The theme's field under everything: with no picture the area is the app's own
            // placeholder, never a black hole, and a letterboxed picture sits on it. The corners
            // are the outline's — one curve, no border drawn round the pixels.
            background = ColorDrawable(c.field)
            clipToOutline = true
            outlineProvider = roundOutline(dpf(PICTURE_RADIUS_DP.toFloat()))
        }
        val pic = ImageView(this).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            setOnTouchListener(frameTouch)
            visibility = View.INVISIBLE
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val ripple = RippleDot(this, c)
        val emptyView = EmptyState(this, c)
        val grip = ResizeGrip(this, c).apply { setOnTouchListener(resizeTouch) }
        val gripLeft = ResizeGrip(this, c).apply {
            setOnTouchListener(resizeTouchLeft)
            scaleX = -1f
        }
        bodyView.addView(pic, matchParent())
        bodyView.addView(ripple, matchParent())
        bodyView.addView(emptyView, matchParent())
        bodyView.addView(grip, FrameLayout.LayoutParams(dp(GRIP_DP), dp(GRIP_DP), Gravity.BOTTOM or Gravity.END).apply {
            setMargins(0, 0, dp(2), dp(2))
        })
        // The other bottom corner: the same grip mirrored, growing towards the left so the
        // right edge stays put.
        bodyView.addView(gripLeft, FrameLayout.LayoutParams(dp(GRIP_DP), dp(GRIP_DP), Gravity.BOTTOM or Gravity.START).apply {
            setMargins(dp(2), 0, 0, dp(2))
        })

        // The foot: a short round bar, the handle that drags the window (its strip is the target,
        // so a finger never has to find four dp).
        val handle = FrameLayout(this).apply {
            contentDescription = "拖动悬浮窗"
            isFocusable = true
            setOnTouchListener(dragTouch)
            addView(
                HandleBar(this@VscreenOverlayService, c),
                FrameLayout.LayoutParams(dp(HANDLE_W_DP), dp(HANDLE_H_DP), Gravity.CENTER),
            )
        }

        val cardView = CardSurface(this, c).apply {
            addView(toolbar(c), FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(TOOLBAR_DP), Gravity.TOP))
            addView(bodyView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(MIN_PICTURE_DP)))
            addView(handle, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(HANDLE_DP), Gravity.BOTTOM))
        }
        val frame = WindowFrame(this).apply {
            clipChildren = false
            clipToPadding = false
            addView(cardView)
        }
        frame.onVisible = { visible -> onWindowVisible(visible) }

        card = cardView
        body = bodyView
        picture = pic
        hint = emptyView
        hintText = emptyView.line
        feedback = ripple
        hintText?.text = REASON_WAIT
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
        p.x = wasAt?.first ?: ((metrics.widthPixels - cardW) / 2 - dp(PAD_X_DP))
        p.y = wasAt?.second ?: ((metrics.heightPixels * 0.18f).toInt() - dp(PAD_TOP_DP))
        // A place saved on a wider (or turned) screen: the whole card comes back in view.
        clamp(p)
        try {
            windows.addView(frame, p)
            root = frame
            windowVisible = true
            showing = true
            // The ball stays above the picture: the two are the same kind of window.
            VscreenBallService.bump()
        } catch (e: Exception) {
            // The vendor's own floating-window switch can refuse after canDrawOverlays said yes.
            Log.w(TAG, "vscreen overlay window refused", e)
            showing = false
            stopSelf()
            return
        }
        appear(frame)
    }

    /**
     * The bar: ≡ (hands over to the ball), the title, 刷新 and 关闭. The title is centred over the
     * window rather than pushed around by the keys, and the bar itself is a handle for dragging.
     */
    private fun toolbar(c: OverlayPalette): View {
        val bar = FrameLayout(this).apply {
            setPadding(dp(4), 0, dp(2), 0)
            setOnTouchListener(dragTouch)
        }
        bar.addView(TextView(this).apply {
            text = TITLE
            textSize = 12f
            setTextColor(c.muted)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            includeFontPadding = false
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, matchParent())
        val keys = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        keys.addView(GlyphKey(this@VscreenOverlayService, KeyGlyph.MENU, c).apply {
            contentDescription = "收起成悬浮球"
            setOnClickListener { collapseToBall(openMenu = true) }
        }, LinearLayout.LayoutParams(dp(KEY_DP), ViewGroup.LayoutParams.MATCH_PARENT))
        keys.addView(View(this), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        keys.addView(GlyphKey(this@VscreenOverlayService, KeyGlyph.REFRESH, c).apply {
            contentDescription = "刷新画面"
            setOnClickListener { refresh() }
        }, LinearLayout.LayoutParams(dp(KEY_DP), ViewGroup.LayoutParams.MATCH_PARENT))
        keys.addView(GlyphKey(this@VscreenOverlayService, KeyGlyph.CLOSE, c).apply {
            contentDescription = "关闭悬浮窗"
            setOnClickListener { collapseToBall(openMenu = false) }
        }, LinearLayout.LayoutParams(dp(KEY_DP), ViewGroup.LayoutParams.MATCH_PARENT))
        bar.addView(keys, matchParent())
        return bar
    }

    private fun matchParent(): FrameLayout.LayoutParams =
        FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

    private fun roundOutline(radius: Float): ViewOutlineProvider = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            outline.setRoundRect(0, 0, view.width, view.height, radius)
        }
    }

    /** The card's size after the limits: the person's wish and the screen itself. */
    private fun layout(requested: Int) {
        val metrics = resources.displayMetrics
        val screenW = metrics.widthPixels
        val screenH = metrics.heightPixels
        val shortSide = min(screenW, screenH)
        val barH = dp(TOOLBAR_DP)
        val handleH = dp(HANDLE_DP)
        val inset = dp(INSET_DP)

        // The picture's own share of the screen: the bar, the foot, its inset and the window's
        // shadow room are all inside the window as well.
        val maxPicH = ((screenH * MAX_HEIGHT_FRACTION).toInt() - barH - handleH - inset - dp(PAD_TOP_DP) - dp(PAD_BOTTOM_DP))
            .coerceAtLeast(dp(MIN_PICTURE_DP))
        val minW = dp(MIN_WIDTH_DP)
        val maxW = (shortSide * MAX_WIDTH_FRACTION).toInt()
        val w = requested.coerceIn(minW, maxW.coerceAtLeast(minW))
        val picW = (w - 2 * inset).coerceAtLeast(dp(48))
        // 默认竖版 (a tall card, height ≈ width × 1.9): the picture is FIT_CENTER, so a landscape
        // frame letterboxes instead of stretching the card wide. A dragged height always wins, and
        // dragged width and height stay independent (自由缩放).
        val h = if (wantedH > 0) wantedH.coerceIn(dp(MIN_PICTURE_DP), maxPicH)
                else (picW * PORTRAIT_RATIO).toInt().coerceIn(dp(MIN_PICTURE_DP), maxPicH)

        wantedW = requested
        cardW = w
        picH = h
        cardH = barH + inset + h + handleH

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
        clamp(p)
        root?.let { runCatching { windows.updateViewLayout(it, p) } }
    }

    /** Keeps the whole card on the screen as it grows (only its shadow room may hang off). */
    private fun clamp(p: WindowManager.LayoutParams) {
        val metrics = resources.displayMetrics
        val minX = -dp(PAD_X_DP)
        val minY = -dp(PAD_TOP_DP)
        p.x = p.x.coerceIn(minX, (metrics.widthPixels - cardW - dp(PAD_X_DP)).coerceAtLeast(minX))
        p.y = p.y.coerceIn(minY, (metrics.heightPixels - cardH - dp(PAD_TOP_DP)).coerceAtLeast(minY))
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
        if (view == null) {
            then()
            return
        }
        // Always animate here: the transition is how the window leaves, not a flourish, and a
        // ROM with animators off should still see it collapse rather than vanish.
        view.animate().cancel()
        view.animate().alpha(0f).scaleX(APPEAR_SCALE).scaleY(APPEAR_SCALE)
            .setDuration(ANIM_MS).setInterpolator(OverlayMotion.emphasized)
            .withEndAction { then() }.start()
    }

    /**
     * ≡ and 关闭: takes the picture away, but the service stays — the ball's 查看虚拟屏 brings the
     * window straight back, and a task that starts later appears by itself once it is back.
     */
    private fun hideWindow() {
        saveWin()
        showing = false
        pausePoll()
        val view = root
        root = null
        card = null
        body = null
        picture = null
        hint = null
        hintText = null
        feedback = null
        hintOn = true
        shown?.recycle()
        shown = null
        frameWidth = 0
        frameHeight = 0
        lastSig = NO_FRAME
        pollMs = FAST_MS
        closing = false
        if (view != null) runCatching { windows.removeViewImmediate(view) }
        // Nothing polls now: the ball asks the channel itself for 查看虚拟屏's own line.
        available = null
        // 关闭/≡ already brought the ball up; only a window closed some other way needs it.
        if (!VscreenBallService.showing) VscreenBallService.ensure(this, true)
    }

    /**
     * ✕ and ≡: the ball is what stays, so it comes up and starts fading in first. Only once it
     * is there does the picture fold away, and the window is removed after its own fade — never
     * before the ball exists. A ball that cannot come must not hold the window hostage.
     */
    private fun collapseToBall(openMenu: Boolean) {
        if (closing) return
        // The picture is on its way out: the app's 悬浮窗 switch must not wait for the fade.
        showing = false
        if (openMenu) VscreenBallService.openMenu(this)
        else if (!VscreenBallService.showing) VscreenBallService.ensure(this, true)
        awaitBallAndFold(0L)
    }

    private fun awaitBallAndFold(waited: Long) {
        if (!live || closing) return
        if (VscreenBallService.showing || waited >= BALL_WAIT_MS) {
            dismiss { hideWindow() }
            return
        }
        main.postDelayed({ awaitBallAndFold(waited + BALL_POLL_MS) }, BALL_POLL_MS)
    }

    private fun isDark(): Boolean =
        resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

    private fun onWindowVisible(visible: Boolean) {
        if (windowVisible == visible) return
        windowVisible = visible
        if (visible) resumePoll() else pausePoll()
    }

    // ---- dragging the window ------------------------------------------------

    private var dragStartX = 0f
    private var dragStartY = 0f
    private var dragOriginX = 0
    private var dragOriginY = 0

    private val dragTouch = View.OnTouchListener { _, event ->
        val p = params ?: return@OnTouchListener false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                glide?.cancel()
                glide = null
                dragStartX = event.rawX
                dragStartY = event.rawY
                dragOriginX = p.x
                dragOriginY = p.y
                dragging = true
                // The window is what moves: no frame is worth pulling while a finger owns it.
                pausePoll()
                true
            }
            MotionEvent.ACTION_MOVE -> {
                // Straight to the window's place — no layout pass, so the card stays under the
                // finger at every event; the card's own sizes do not change while it travels.
                val metrics = resources.displayMetrics
                p.x = (dragOriginX + (event.rawX - dragStartX).toInt())
                    .coerceIn(-dp(PAD_X_DP), (metrics.widthPixels - cardW - dp(PAD_X_DP)).coerceAtLeast(-dp(PAD_X_DP)))
                p.y = (dragOriginY + (event.rawY - dragStartY).toInt())
                    .coerceIn(-dp(PAD_TOP_DP), (metrics.heightPixels - cardH - dp(PAD_TOP_DP)).coerceAtLeast(-dp(PAD_TOP_DP)))
                root?.let { runCatching { windows.updateViewLayout(it, p) } }
                true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                endDrag(snap = true)
                true
            }
            else -> false
        }
    }

    /**
     * Dropped: a card left near a side glides to [SNAP_EDGE_DP] dp from it (the nearer side wins,
     * [SNAP_MS]); anywhere else it just stays. Either way the place is remembered and the poll
     * resumes.
     */
    private fun endDrag(snap: Boolean) {
        dragging = false
        val p = params
        val target = if (snap && p != null) edgeX(p.x) else null
        if (p != null && target != null && target != p.x) {
            glideTo(target) {
                saveWin()
                resumePoll()
            }
            return
        }
        saveWin()
        resumePoll()
    }

    /** The window's x with the card's nearer side [SNAP_EDGE_DP] dp in, when it is within reach. */
    private fun edgeX(x: Int): Int? {
        val screenW = resources.displayMetrics.widthPixels
        val pad = dp(PAD_X_DP)
        val left = x + pad
        val right = screenW - (x + pad + cardW)
        val reach = dp(SNAP_REACH_DP)
        val edge = dp(SNAP_EDGE_DP)
        return when {
            left <= reach -> edge - pad
            right <= reach -> screenW - edge - cardW - pad
            else -> null
        }
    }

    /** The [SNAP_MS] glide of the window's x to [to], then [then] (never both ends of a frame). */
    private fun glideTo(to: Int, then: () -> Unit) {
        val p = params ?: return then()
        val from = p.x
        if (from == to || !OverlayMotion.on()) {
            p.x = to
            root?.let { runCatching { windows.updateViewLayout(it, p) } }
            return then()
        }
        glide?.cancel()
        glide = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = SNAP_MS
            interpolator = OverlayMotion.emphasized
            addUpdateListener { a ->
                p.x = (from + (to - from) * (a.animatedValue as Float)).roundToInt()
                root?.let { runCatching { windows.updateViewLayout(it, p) } }
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (glide === animation) glide = null
                    then()
                }
            })
            start()
        }
    }

    /** The window's place and size, kept for the next opening (`vscreenWin_` keys). */
    private fun saveWin() {
        val p = params ?: return
        val density = resources.displayMetrics.density
        val widthDp = if (density > 0f) (wantedW / density).roundToInt() else 0
        // 0 means "no dragged height": the next opening falls back to the portrait default.
        val heightDp = if (density > 0f) (wantedH / density).roundToInt() else 0
        VscreenWinPrefs.write(this, p.x, p.y, widthDp, heightDp)
    }

    // ---- the corner grip: resizing from the bottom-right --------------------

    private var resizeStartX = 0f
    private var resizeStartY = 0f
    private var resizeStartWidth = 0
    private var resizeStartHeight = 0

    private val resizeTouch = View.OnTouchListener { _, event ->
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                glide?.cancel()
                glide = null
                resizeStartX = event.rawX
                resizeStartY = event.rawY
                resizeStartWidth = wantedW
                resizeStartHeight = picH
                dragging = true
                pausePoll()
                true
            }
            MotionEvent.ACTION_MOVE -> {
                // The window's top-left is the anchor: the corner follows the finger. Both axes
                // count — the height follows the width by the frame's shape, so pulling down is
                // the same wish as pulling right, by that many width-pixels. The two add up, which
                // is what a corner grip should feel like.
                val byX = event.rawX - resizeStartX
                val byY = event.rawY - resizeStartY
                // Both axes are the person's own wish now: width from the horizontal pull, height
                // from the vertical one. The picture never distorts, it letterboxes.
                wantedH = (resizeStartHeight + byY).toInt()
                layout((resizeStartWidth + byX).toInt())
                true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                endDrag(snap = false)
                true
            }
            else -> false
        }
    }

    private var leftStartRawX = 0f
    private var leftStartRawY = 0f
    private var leftStartWidth = 0
    private var leftStartHeight = 0
    private var leftStartX = 0
    private var leftStartCardW = 0

    private val resizeTouchLeft = View.OnTouchListener { _, event ->
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                glide?.cancel()
                glide = null
                leftStartRawX = event.rawX
                leftStartRawY = event.rawY
                leftStartWidth = wantedW
                leftStartHeight = picH
                leftStartX = params?.x ?: 0
                leftStartCardW = cardW
                dragging = true
                pausePoll()
                true
            }
            MotionEvent.ACTION_MOVE -> {
                // Dragging left grows the card; the window slides left by the same amount so the
                // right edge does not move.
                val byX = leftStartRawX - event.rawX
                val byY = event.rawY - leftStartRawY
                wantedH = (leftStartHeight + byY).toInt()
                layout((leftStartWidth + byX).toInt())
                params?.let { p ->
                    p.x = leftStartX - (cardW - leftStartCardW)
                    root?.let { runCatching { windows.updateViewLayout(it, p) } }
                }
                true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                endDrag(snap = false)
                true
            }
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
                    // Sent already: the dot says so at the spot the finger left, faint enough to
                    // read as the window's answer rather than as content.
                    feedback?.show(downX, downY)
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
            if (!live || root == null || dragging || !windowVisible) return
            fetch()
            main.postDelayed(this, pollMs)
        }
    }

    /** ⟳: one frame now, and the poll's clock restarts from here. */
    private fun refresh() {
        if (root == null || dragging) return
        main.removeCallbacks(tick)
        fetch()
        if (live) main.postDelayed(tick, pollMs)
    }

    private fun pausePoll() {
        main.removeCallbacks(tick)
    }

    private fun resumePoll() {
        main.removeCallbacks(tick)
        if (live && root != null && !dragging && windowVisible) main.post(tick)
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
        val bytes = png?.let { runCatching { Base64.decode(it, Base64.DEFAULT) }.getOrNull() }
        val decoded = bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
        if (decoded == null) {
            noteFrame(NO_FRAME)
            noTarget(reasonText(frame))
            // 全局省电: nothing to show means no reason to keep waking the channel. The ⟳ button,
            // reopening the window, or a task starting ([wake]) resumes the poll.
            if (PowerSaver.enabled) pausePoll()
            return
        }
        noteFrame(signature(bytes))
        val w = (frame?.get("width") as? Int) ?: decoded.width
        val h = (frame?.get("height") as? Int) ?: decoded.height
        val sameShape = w == frameWidth && h == frameHeight
        frameWidth = w
        frameHeight = h
        val previous = shown
        shown = decoded
        available = true
        everLive = true
        picture?.setImageBitmap(decoded)
        showPicture()
        fadeHint(false)
        if (!sameShape) layout(wantedW)
        if (previous !== decoded) previous?.recycle()
    }

    /**
     * 没有画面: the last picture stays as the card's ground, so the poll does not blink, and one
     * line says why the frame did not come ([reasonText]) — faded in over it once.
     */
    private fun noTarget(reason: String) {
        // Touch forwarding must not aim at a size this window no longer shows.
        available = false
        frameWidth = 0
        frameHeight = 0
        if (hintText?.text?.toString() != reason) hintText?.text = reason
        fadeHint(true)
    }

    /**
     * Why there is no picture, in the person's words. [BackgroundControl.frame] answers
     * `reasonCode` `noDisplay` when the privileged channel is not there at all (or the ask never
     * got an answer), `displayMissing` when the channel is up but no task owns a display, and
     * `frameNotReady` when the display has no frame yet — the three cases a person can tell apart.
     */
    private fun reasonText(frame: Map<String, Any?>?): String = when (frame?.get("reasonCode") as? String) {
        // No channel: one that never delivered a frame is 未开启, one that did 已断开.
        "noDisplay" -> if (everLive) REASON_CHANNEL_GONE else REASON_CHANNEL_OFF
        "channelDisabled", "permissionRequired", "identityChanged" -> REASON_CHANNEL_GONE
        // The channel is there, the display is not: nothing has been started yet.
        "displayMissing" -> REASON_NO_DISPLAY
        // The display is there, its picture is not (yet).
        "frameNotReady", "timeout" -> REASON_TIMEOUT
        else -> REASON_UNKNOWN
    }

    /**
     * The poll's pace: two frames alike in a row (its length and a cheap hash of its bytes) mean
     * nothing moves, so the wait goes to [SLOW_MS]; anything new brings it back to [FAST_MS].
     */
    private fun noteFrame(sig: Long) {
        pollMs = if (sig == lastSig) SLOW_MS else FAST_MS
        lastSig = sig
    }

    /** The frame's rough signature; [NO_FRAME] stands for "no picture", which repeats too. */
    private fun signature(bytes: ByteArray?): Long {
        if (bytes == null) return NO_FRAME
        return (bytes.size.toLong() shl 32) or (Arrays.hashCode(bytes).toLong() and 0xFFFFFFFFL)
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

    /** The window's own view: it reports when the system hides it, so the poll stops with it. */
    private class WindowFrame(context: Context) : FrameLayout(context) {
        var onVisible: (Boolean) -> Unit = {}

        override fun onWindowVisibilityChanged(visibility: Int) {
            super.onWindowVisibilityChanged(visibility)
            onVisible(visibility == View.VISIBLE)
        }
    }

    companion object {
        private const val TAG = "DshVscreen"
        private const val CHANNEL_ID = "dsh-vscreen"
        private const val NOTIFICATION_ID = 3091

        /** One frame while something moves; [SLOW_MS] while two frames in a row are the same. */
        private const val FAST_MS = 500L
        private const val SLOW_MS = 1_500L

        /** No picture at all — a signature that repeats like any other. */
        private const val NO_FRAME = -1L

        private const val TAP_TIMEOUT_MS = 300

        /** The card's default width: 42 % of the screen's short side; never past the screen. */
        private const val WIDTH_FRACTION = 0.42f
        private const val MAX_WIDTH_FRACTION = 1.0f

        /** 默认竖版: the picture's height is this many times its width; the picture letterboxes. */
        private const val PORTRAIT_RATIO = 1.9f

        /** The picture never takes the whole screen: room for the bar, the foot and the shadow. */
        private const val MAX_HEIGHT_FRACTION = 1.0f
        private const val MIN_WIDTH_DP = 160
        private const val MIN_PICTURE_DP = 64

        private const val TOOLBAR_DP = 36
        private const val KEY_DP = 36
        private const val GRIP_DP = 32

        /** The foot: a strip tall enough to catch a finger, the bar inside it 4 dp. */
        private const val HANDLE_DP = 18
        private const val HANDLE_W_DP = 40
        private const val HANDLE_H_DP = 4

        /** The picture's margin inside the card, all round. */
        private const val INSET_DP = 6

        /** Room round the card, inside the window, for its shadow (as the capsule has). */
        private const val PAD_X_DP = 12
        private const val PAD_TOP_DP = 10
        private const val PAD_BOTTOM_DP = 16

        /** The picture's corner: the card's less its inset, so the two curves stay concentric. */
        private const val PICTURE_RADIUS_DP = CapsuleMetrics.RADIUS_DP - INSET_DP

        /** Dropped within this of a side, the card glides to [SNAP_EDGE_DP] from it. */
        private const val SNAP_REACH_DP = 24
        private const val SNAP_EDGE_DP = 8
        private const val SNAP_MS = 180L

        private const val ANIM_MS = 150L
        private const val APPEAR_SCALE = 0.92f

        /** The ball's own appearance is 150 ms; the picture waits until the ball has been added. */
        private const val BALL_POLL_MS = 40L
        private const val BALL_WAIT_MS = 500L
        private const val HINT_MS = 180L
        private const val PICTURE_MS = 160L

        /** A task started or the app changed state: resume a poll paused by 全局省电. */
        const val ACTION_WAKE = "io.github.ccch1mneyyy.dsh_app.VSCREEN_WAKE"

        fun wake(context: android.content.Context) {
            runCatching {
                context.sendBroadcast(android.content.Intent(ACTION_WAKE).setPackage(context.packageName))
            }
        }

        private const val TITLE = "虚拟屏"

        // l10n:keep 悬浮窗自己的文案（原生窗口，没有 Flutter 的 l10n 上下文）
        /** Before the first frame: neither a fault nor a blank. */
        private const val REASON_WAIT = "正在读取画面"
        private const val REASON_CHANNEL_OFF = "通道未开启"
        private const val REASON_CHANNEL_GONE = "通道已断开"
        private const val REASON_NO_DISPLAY = "没有可捕获的显示"
        private const val REASON_TIMEOUT = "抓帧超时"
        private const val REASON_UNKNOWN = "未知"

        /** `reasonCode` of opening the overlay without 显示在其他应用上层. */
        const val NO_PERMISSION = "noOverlayPermission"

        /**
         * Whether the window's last poll found a 虚拟屏: the ball's 查看虚拟屏 row says so without a
         * channel round-trip. Null while the window isn't polling (hidden, or not running).
         */
        @Volatile
        var available: Boolean? = null
            private set

        /**
         * Whether the picture window is actually on screen now (the app's 悬浮窗 switch reads it
         * through `vscreenState.showing`). True from the moment the window is added until it is
         * hidden or the service goes away.
         */
        @Volatile
        var showing = false
            private set

        /**
         * Opens or closes the window. Without [Settings.canDrawOverlays] it opens the system
         * switch instead of starting a service that cannot add its view, and reports
         * [NO_PERMISSION] so the app can say why.
         */
        fun ensure(context: Context, open: Boolean): Map<String, Any?> {
            val app = context.applicationContext
            if (!open) {
                // The switch reads this through `vscreenState.showing`: say so at once, even
                // though the system takes a moment to run the service's onDestroy.
                showing = false
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

/**
 * Where the window was and what size (`vscreenWin_x` / `vscreenWin_y` / `vscreenWin_widthDp` /
 * `vscreenWin_heightDp` in the overlay's own preferences file, [OverlayPrefs.FILE]): the next
 * opening starts there instead of in the middle of the screen, with the size the person dragged
 * (else the portrait default). A place saved on a wider screen is brought back in view by the caller.
 */
private object VscreenWinPrefs {
    const val PREFIX = "vscreenWin_"
    private const val K_X = PREFIX + "x"
    private const val K_Y = PREFIX + "y"
    private const val K_WIDTH_DP = PREFIX + "widthDp"
    private const val K_HEIGHT_DP = PREFIX + "heightDp"

    class Saved(val widthDp: Int, val heightDp: Int, val at: Pair<Int, Int>?)

    fun read(context: Context): Saved {
        val p = shared(context)
        val at = if (p.contains(K_X) && p.contains(K_Y)) p.getInt(K_X, 0) to p.getInt(K_Y, 0) else null
        return Saved(p.getInt(K_WIDTH_DP, 0), p.getInt(K_HEIGHT_DP, 0), at)
    }

    fun write(context: Context, x: Int, y: Int, widthDp: Int, heightDp: Int) {
        runCatching {
            shared(context).edit()
                .putInt(K_X, x)
                .putInt(K_Y, y)
                .putInt(K_WIDTH_DP, widthDp.coerceAtLeast(0))
                .putInt(K_HEIGHT_DP, heightDp.coerceAtLeast(0))
                .apply()
        }
    }

    private fun shared(context: Context): SharedPreferences =
        context.getSharedPreferences(OverlayPrefs.FILE, Context.MODE_PRIVATE)
}

/**
 * The card: one rounded rect of solid surface (`CapsuleMetrics.RADIUS_DP` dp) drawn by the
 * control's own [SurfacePainter] — the faint top-to-bottom fall of the surface colour, the top rim
 * of light and the hairline — over a soft elevation shadow, with every child cut to that outline
 * (so the bar's ends, the picture's corners and the foot's handle follow the card).
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

/** What the bar's three keys draw. */
private enum class KeyGlyph { MENU, REFRESH, CLOSE }

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
        KeyGlyph.MENU -> {
            // ≡: three lines, the app's own grip, on the grid's two-thirds.
            for (i in -1..1) {
                val y = 12f + i * 3.5f
                path.moveTo(6.5f, y)
                path.lineTo(17.5f, y)
            }
        }
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
 * ≡ / 刷新 / 关闭: a round ripple over the key's disc, the glyph inside and the 0.92 press the
 * control's keys have. The whole 36 dp square is the target.
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

/** The foot's handle: a short round bar, the card's own grip; the strip round it is what drags. */
private class HandleBar(context: Context, private val c: OverlayPalette) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        paint.color = c.muted
        paint.alpha = 0x59
        val r = height / 2f
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), r, r, paint)
    }
}

/**
 * 没有画面: the app's empty state — a small mark over one line of faint ink saying why, on a scrim
 * of the surface so it reads over a kept picture. Never a warning colour, never a blank.
 */
private class EmptyState(context: Context, c: OverlayPalette) : LinearLayout(context) {
    /** The one line that says why; [VscreenOverlayService] rewrites it as the reason changes. */
    val line: TextView = TextView(context).apply {
        text = "正在读取画面"
        textSize = 12f
        setTextColor(c.faint)
        includeFontPadding = false
        gravity = Gravity.CENTER
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
        setPadding(context.dp(10), context.dp(7), context.dp(10), 0)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    init {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        background = GradientDrawable().apply {
            setColor(withAlpha(c.surfaceBottom, SCRIM_ALPHA))
            cornerRadius = context.dpf(RADIUS_DP.toFloat())
        }
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        addView(ScreenMark(context, c.faint), LinearLayout.LayoutParams(context.dp(24), context.dp(24)))
        addView(line)
    }

    private companion object {
        const val SCRIM_ALPHA = 0xA6

        /** The picture's corner: the card's less its inset, so the two curves stay concentric. */
        const val RADIUS_DP = CapsuleMetrics.RADIUS_DP - 6
    }
}

/**
 * 点击反馈: a very light dot where a touch was sent to the display, out in [MS] ms. Drawn, not
 * wondered at — the window says the tap went somewhere without drawing anything over the picture
 * it belongs to. Never takes a touch: it is not clickable, so the picture below keeps them.
 */
private class RippleDot(context: Context, private val c: OverlayPalette) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var cx = 0f
    private var cy = 0f
    private var t = 1f
    private var fade: ValueAnimator? = null

    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun show(x: Float, y: Float) {
        cx = x
        cy = y
        fade?.cancel()
        if (!OverlayMotion.on()) {
            t = 1f
            invalidate()
            return
        }
        t = 0f
        fade = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = MS
            addUpdateListener {
                t = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (t >= 1f) return
        paint.color = c.accent
        paint.alpha = ((1f - t) * 0x3D).toInt()
        canvas.drawCircle(cx, cy, context.dpf(4f) + context.dpf(9f) * t, paint)
    }

    private companion object {
        const val MS = 120L
    }
}

/** The 没有画面 mark: a small display, drawn like the control's line glyphs. */
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
 * from its corner; the service keeps it in bounds. Kept faint: it is a hint, not a button.
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
        paint.color = withAlpha(c.surfaceBottom, 0xB3)
        paint.strokeWidth = context.dpf(3f)
        elbow(canvas, near, far)
        paint.color = withAlpha(c.muted, 0xB0)
        paint.strokeWidth = context.dpf(1.2f)
        elbow(canvas, near, far)
    }

    private fun elbow(canvas: Canvas, near: Float, far: Float) {
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawLine(w - near, h - far, w - near, h - near, paint)
        canvas.drawLine(w - far, h - near, w - near, h - near, paint)
    }
}
