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
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.FrameLayout
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
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 虚拟屏悬浮球: a system-level ball ([WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY]) that
 * keeps 虚拟屏 at hand while the picture window is closed. It is the accessibility control's ball
 * made for this overlay — one circle of solid surface ([SurfacePainter] over the palette's fall,
 * top rim and hairline) with a round-capped line mark of the screen — and it opens a small card
 * ([PanelView]) with the same corners, stroke and shadow as the capsule.
 *
 * Its 查看虚拟屏 row brings the picture window back ([VscreenOverlayService.ensure]) whether it was
 * only hidden or the system stopped it; the two windows are the same type, and the ball is put on
 * top again whenever the card opens.
 *
 * Left alone for the 闲置 seconds the ball slides half off its edge, leaving half a circle; any
 * touch brings it back (the touch that brings it back does not open the card). 设置 → 手机操控 →
 * 悬浮球 writes the size, the opacity, the idle half-hide and its delay into [VscreenBallPrefs];
 * a showing ball follows them at once.
 */
class VscreenBallService : Service() {

    private lateinit var windows: WindowManager
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "dsh-vscreen-ball").apply { isDaemon = true } }
    private val control by lazy { BackgroundControl.get(applicationContext) }

    private var palette: OverlayPalette = OverlayPalette.LIGHT
    private var prefs = VscreenBallPrefs()

    private var root: FrameLayout? = null
    private var ball: BallView? = null
    private var params: WindowManager.LayoutParams? = null

    private var panel: PanelView? = null
    private var panelParams: WindowManager.LayoutParams? = null
    private var viewRow: MenuRow? = null
    private var panelOpen = false

    /** The window's sizes in px: [diameter] is the ball, [size] the window (room for its shadow). */
    private var diameter = 0
    private var size = 0
    private var pad = 0
    private var margin = 0

    private var side = SIDE_RIGHT
    private var yFraction = DEFAULT_Y
    private var halfHidden = false
    private var swallowTap = false
    private var live = true

    /** Whether a 虚拟屏 runs now, for the 查看虚拟屏 row; null while nobody has looked. */
    private var vscreenAvailable: Boolean? = null

    private var dragStartX = 0f
    private var dragStartY = 0f
    private var originX = 0
    private var originY = 0
    private var dragging = false
    private var slop = 0

    private var glide: ValueAnimator? = null

    private val idle: Runnable = Runnable { if (!panelOpen) halfHide() }

    private val prefChange = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key != null && key.startsWith(VscreenBallPrefs.PREFIX)) main.post { applyPrefs(VscreenBallPrefs.read(this)) }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        live = true
        windows = getSystemService(WindowManager::class.java)
        slop = ViewConfiguration.get(this).scaledTouchSlop
        prefs = VscreenBallPrefs.read(this)
        side = if (VscreenBallPrefs.savedSide(this) == "left") SIDE_LEFT else SIDE_RIGHT
        yFraction = VscreenBallPrefs.savedY(this)
        VscreenBallPrefs.prefs(this).registerOnSharedPreferenceChangeListener(prefChange)
        showing = true
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
            // A vendor policy refused the foreground start: without it the ball must not stay.
            Log.w(TAG, "vscreen ball could not go foreground", e)
            stopSelf()
            return START_NOT_STICKY
        }
        if (root == null) show()
        refreshAvailable()
        scheduleIdle()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        live = false
        showing = false
        main.removeCallbacks(idle)
        glide?.cancel()
        glide = null
        if (panelOpen) closePanel(animated = false)
        runCatching { VscreenBallPrefs.prefs(this).unregisterOnSharedPreferenceChangeListener(prefChange) }
        io.shutdownNow()
        root?.animate()?.cancel()
        root?.let { runCatching { windows.removeViewImmediate(it) } }
        panel?.let { runCatching { windows.removeViewImmediate(it) } }
        root = null
        ball = null
        panel = null
        viewRow = null
        params = null
        panelParams = null
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Sizes are screen fractions again; the colours stay the ones the ball opened with.
        if (root == null) return
        closePanel(animated = false)
        val p = params ?: return
        p.x = if (halfHidden) halfX() else dockedX()
        p.y = dockedY()
        update()
    }

    // ---- the ball ----------------------------------------------------------

    private fun show() {
        palette = if (isDark()) OverlayPalette.DARK else OverlayPalette.LIGHT
        diameter = dp(prefs.sizeDp)
        pad = dp(PAD_DP)
        size = diameter + 2 * pad
        margin = dp(EDGE_DP) - pad

        val view = BallView(this)
        view.setOnTouchListener(ballTouch)
        view.setOnClickListener { togglePanel() }
        val frame = FrameLayout(this).apply {
            setPadding(pad, pad, pad, pad)
            clipToPadding = false
            clipChildren = false
            addView(view, FrameLayout.LayoutParams(diameter, diameter))
        }
        ball = view
        root = frame
        frame.alpha = prefs.opacity
        halfHidden = false

        val p = WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            title = "悬浮球"
            if (Build.VERSION.SDK_INT >= 30) setFitInsetsTypes(0)
        }
        p.x = dockedX()
        p.y = dockedY()
        params = p
        try {
            windows.addView(frame, p)
        } catch (e: Exception) {
            // The vendor's own floating-window switch can refuse after canDrawOverlays said yes.
            Log.w(TAG, "vscreen ball window refused", e)
            root = null
            ball = null
            params = null
            stopSelf()
        }
    }

    /** Takes the ball away and puts it back (a new size), staying on the same side and height. */
    private fun rebuild() {
        val wasHalf = halfHidden
        closePanel(animated = false)
        main.removeCallbacks(idle)
        glide?.cancel()
        root?.let { runCatching { windows.removeViewImmediate(it) } }
        root = null
        ball = null
        show()
        halfHidden = false
        if (wasHalf) {
            halfHidden = true
            params?.let { it.x = halfX() }
            update()
        }
        scheduleIdle()
    }

    private fun update() {
        val view = root ?: return
        val p = params ?: return
        runCatching { if (view.isAttachedToWindow) windows.updateViewLayout(view, p) }
    }

    private fun screenW(): Int = resources.displayMetrics.widthPixels
    private fun screenH(): Int = resources.displayMetrics.heightPixels

    /** The ball's gap to the screen side, as a window margin: [EDGE_DP] dp to its edge. */
    private fun dockedX(): Int = if (side == SIDE_LEFT) margin else screenW() - size - margin

    private fun dockedY(): Int {
        val free = (screenH() - size).coerceAtLeast(0)
        return (yFraction * free).roundToInt().coerceIn(0, free)
    }

    /** Half a circle off screen: the window's [HALF_OUT_DP] dp beyond the edge, less half of it. */
    private fun halfX(): Int =
        if (side == SIDE_LEFT) dp(HALF_OUT_DP) - size / 2 else screenW() - size / 2 - dp(HALF_OUT_DP)

    private fun saveDock() {
        VscreenBallPrefs.saveDock(this, if (side == SIDE_LEFT) "left" else "right", yFraction)
    }

    // ---- idle half-hide ----------------------------------------------------

    private fun scheduleIdle() {
        main.removeCallbacks(idle)
        if (!live || !prefs.idleHide || panelOpen || root == null) return
        main.postDelayed(idle, prefs.idleMs.toLong())
    }

    private fun halfHide() {
        if (panelOpen || halfHidden || root == null) return
        halfHidden = true
        animateX(halfX(), HALF_MS)
    }

    private fun animateX(to: Int, ms: Long) {
        val p = params ?: return
        glide?.cancel()
        glide = null
        val from = p.x
        if (from == to) return
        if (!OverlayMotion.on()) {
            p.x = to
            update()
            return
        }
        glide = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = ms
            interpolator = OverlayMotion.emphasized
            addUpdateListener { a ->
                p.x = (from + (to - from) * (a.animatedValue as Float)).roundToInt()
                update()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (glide === animation) glide = null
                }
            })
            start()
        }
    }

    // ---- touch: drag, snap, tap -------------------------------------------

    private val ballTouch = View.OnTouchListener { view, event ->
        val p = params ?: return@OnTouchListener false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                glide?.cancel()
                glide = null
                main.removeCallbacks(idle)
                dragStartX = event.rawX
                dragStartY = event.rawY
                originX = p.x
                originY = p.y
                dragging = false
                swallowTap = halfHidden
                press(true)
                // 半隐: the touch that brings the ball back only brings it back.
                if (halfHidden) {
                    halfHidden = false
                    animateX(dockedX(), HALF_MS)
                }
                true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - dragStartX
                val dy = event.rawY - dragStartY
                if (!dragging && (abs(dx) > slop || abs(dy) > slop)) {
                    dragging = true
                    press(false)
                }
                if (dragging) {
                    val free = (screenH() - size).coerceAtLeast(0)
                    p.x = (originX + dx).roundToInt().coerceIn(0, (screenW() - size).coerceAtLeast(0))
                    p.y = (originY + dy).roundToInt().coerceIn(0, free)
                    update()
                }
                true
            }
            MotionEvent.ACTION_UP -> {
                press(false)
                if (dragging) settle() else if (!swallowTap) view.performClick()
                swallowTap = false
                scheduleIdle()
                true
            }
            MotionEvent.ACTION_CANCEL -> {
                press(false)
                if (dragging) settle()
                swallowTap = false
                scheduleIdle()
                true
            }
            else -> false
        }
    }

    /** Dropped: the nearer side wins, the x glides to the [EDGE_DP] margin in [SNAP_MS]. */
    private fun settle() {
        dragging = false
        val p = params ?: return
        side = if (p.x + size / 2 < screenW() / 2) SIDE_LEFT else SIDE_RIGHT
        val free = (screenH() - size).coerceAtLeast(0)
        yFraction = if (free > 0) p.y.toFloat() / free else 0f
        saveDock()
        animateX(dockedX(), SNAP_MS)
    }

    private fun press(down: Boolean) {
        val view = ball ?: return
        view.isPressed = down
        val scale = if (down) PRESS_SCALE else 1f
        if (!OverlayMotion.on()) {
            view.scaleX = scale
            view.scaleY = scale
            return
        }
        view.animate().scaleX(scale).scaleY(scale).setDuration(if (down) 90 else 200)
            .setInterpolator(OverlayMotion.glide).start()
    }

    // ---- the menu ----------------------------------------------------------

    private fun togglePanel() {
        if (panelOpen) closePanel(animated = true) else openPanel()
    }

    private fun openPanel() {
        if (panelOpen || root == null) return
        panelOpen = true
        main.removeCallbacks(idle)
        if (halfHidden) {
            halfHidden = false
            animateX(dockedX(), HALF_MS)
        }
        val view = buildPanel()
        view.measure(
            View.MeasureSpec.makeMeasureSpec(dp(PANEL_W_DP), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val w = dp(PANEL_W_DP)
        val h = view.measuredHeight.coerceAtLeast(dp(ROW_DP))
        val cy = (params?.y ?: 0) + size / 2
        val x = if (side == SIDE_LEFT) dp(PANEL_EDGE_DP) else screenW() - w - dp(PANEL_EDGE_DP)
        val y = (cy - h / 2).coerceIn(dp(PANEL_EDGE_DP), (screenH() - h - dp(PANEL_EDGE_DP)).coerceAtLeast(dp(PANEL_EDGE_DP)))
        val p = WindowManager.LayoutParams(
            w,
            h,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            title = "悬浮球菜单"
            this.x = x
            this.y = y
            if (Build.VERSION.SDK_INT >= 30) setFitInsetsTypes(0)
        }
        panel = view
        panelParams = p
        try {
            windows.addView(view, p)
        } catch (e: Exception) {
            Log.w(TAG, "vscreen ball menu refused", e)
            panel = null
            panelParams = null
            panelOpen = false
            scheduleIdle()
            return
        }
        bringBallToFront()
        view.isFocusable = true
        view.isFocusableInTouchMode = true
        view.requestFocus()
        appear(view)
        refreshAvailable()
    }

    /**
     * The ball stays above its menu: the two windows are the same type, so the one added last
     * is on top — the ball is taken away and put back at the same place after the card.
     */
    private fun bringBallToFront() {
        val view = root ?: return
        val p = params ?: return
        view.animate().cancel()
        runCatching { windows.removeViewImmediate(view) }
        runCatching { windows.addView(view, p) }.onFailure { Log.w(TAG, "vscreen ball could not stay on top", it) }
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
        view.scaleX = 0.94f
        view.scaleY = 0.94f
        view.animate().alpha(1f).scaleX(1f).scaleY(1f)
            .setDuration(MENU_MS).setInterpolator(OverlayMotion.emphasized).start()
    }

    private fun closePanel(animated: Boolean = true) {
        val view = panel ?: return
        panelOpen = false
        viewRow = null
        view.animate().cancel()
        val remove = {
            if (panel === view) {
                panel = null
                panelParams = null
            }
            runCatching { windows.removeViewImmediate(view) }
        }
        if (!animated || !OverlayMotion.on()) {
            remove()
            scheduleIdle()
            return
        }
        view.animate().alpha(0f).scaleX(0.96f).scaleY(0.96f)
            .setDuration(QUICK_MS).setInterpolator(OverlayMotion.emphasized)
            .withEndAction { remove() }.start()
        scheduleIdle()
    }

    private fun buildPanel(): PanelView {
        val view = PanelView(this, palette)
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val rowParams = { LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(ROW_DP)) }
        viewRow = menuRow(MenuGlyph.SCREEN, "查看虚拟屏") { openVscreen() }
        column.addView(viewRow, rowParams())
        column.addView(menuRow(MenuGlyph.SLIDERS, "虚拟屏设置") { openApp(LINK_VSCREEN) }, rowParams())
        column.addView(menuRow(MenuGlyph.BALL, "悬浮球设置") { openApp(LINK_BALL) }, rowParams())
        column.addView(menuRow(MenuGlyph.POWER, "关闭悬浮球") { closePanel(false); stopSelf() }, rowParams())
        view.addView(column, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        view.onOutside = { closePanel() }
        bindViewRow()
        return view
    }

    private fun menuRow(glyph: MenuGlyph, title: String, action: () -> Unit): MenuRow =
        MenuRow(this, palette, glyph, title).apply {
            isClickable = true
            isFocusable = true
            setOnClickListener { action() }
        }

    /** 查看虚拟屏 greys out while no 虚拟屏 runs, and says so at its end. */
    private fun bindViewRow() {
        val enabled = vscreenAvailable != false
        viewRow?.bind(enabled, if (vscreenAvailable == false) NOT_RUNNING else null)
    }

    private fun openVscreen() {
        closePanel(animated = false)
        VscreenOverlayService.ensure(this, true)
    }

    /** 虚拟屏设置 / 悬浮球设置: the app, on the page a `dshgo://open/…` link names. */
    private fun openApp(link: String) {
        closePanel(animated = false)
        val intent = Intent(this, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse(link)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        runCatching { startActivity(intent) }.onFailure { Log.w(TAG, "vscreen ball could not open $link", it) }
    }

    /**
     * Whether a 虚拟屏 runs, for the menu: the picture window's own last poll while it is up,
     * otherwise one ask of the read-only preview owner (never on the main thread).
     */
    private fun refreshAvailable() {
        VscreenOverlayService.available?.let {
            vscreenAvailable = it
            bindViewRow()
            return
        }
        if (!live) return
        runCatching {
            io.execute {
                val state = runCatching { control.vscreenState() }.getOrNull()
                val now = state?.get("available") == true
                main.post {
                    if (!live) return@post
                    vscreenAvailable = now
                    bindViewRow()
                }
            }
        }
    }

    // ---- preferences -------------------------------------------------------

    private fun applyPrefs(next: VscreenBallPrefs) {
        val old = prefs
        prefs = next
        if (next.sizeDp != old.sizeDp) {
            rebuild()
            return
        }
        root?.alpha = next.opacity
        scheduleIdle()
    }

    private fun isDark(): Boolean =
        resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

    // ---- foreground --------------------------------------------------------

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm?.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "虚拟屏悬浮球", NotificationManager.IMPORTANCE_MIN).apply { setShowBadge(false) },
        )
        val open = PendingIntent.getActivity(
            this, NOTIFICATION_ID,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_dsh)
            .setContentTitle("悬浮球")
            .setContentText("点一下可查看虚拟屏")
            .setOngoing(true)
            .setContentIntent(open)
            .build()
    }

    // ---- the ball's own views ---------------------------------------------

    /** The ball: the capsule's surface as a circle and the screen mark, with the 0.92 press. */
    private inner class BallView(context: Context) : View(context) {
        private val surface = SurfacePainter(context)
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rect = RectF()
        private val path = Path()

        init {
            isClickable = true
            isFocusable = true
            contentDescription = "悬浮球"
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) = outline.setOval(0, 0, view.width, view.height)
            }
            softShadow(palette, 5f)
            foreground = RippleDrawable(
                ColorStateList.valueOf(palette.ripple), null,
                InsetDrawable(GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) }, context.dp(4)),
            )
        }

        override fun getAccessibilityClassName(): CharSequence = android.widget.Button::class.java.name

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            rect.set(0f, 0f, width.toFloat(), height.toFloat())
            surface.draw(canvas, rect, width / 2f, palette)
            paint.color = palette.accent
            paint.alpha = (255 * 0.9f).roundToInt()
            drawScreen(canvas, width / 2f, height / 2f, context.dpf(GLYPH_DP.toFloat()), paint, path, rect)
        }

        private companion object {
            /** The screen mark on the glyphs' 24-unit grid. */
            const val GLYPH_DP = 22
        }
    }

    /** The card: the capsule's corners, fall, rim and hairline over the same soft shadow. */
    private class PanelView(context: Context, private val c: OverlayPalette) : FrameLayout(context) {
        private val painter = SurfacePainter(context)
        private val radius = context.dpf(CapsuleMetrics.RADIUS_DP.toFloat())
        private val rect = RectF()

        var onOutside: () -> Unit = {}

        init {
            setWillNotDraw(false)
            clipChildren = true
            clipToOutline = true
            setPadding(context.dp(INNER_PAD_DP), context.dp(INNER_PAD_DP), context.dp(INNER_PAD_DP), context.dp(INNER_PAD_DP))
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, radius)
                }
            }
            softShadow(c, 6f)
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

        /** A tap anywhere else, or the back gesture: the card goes. */
        override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
            if (ev.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                onOutside()
                return true
            }
            return super.dispatchTouchEvent(ev)
        }

        private companion object {
            /** The card's own margin round its rows, inside the surface. */
            const val INNER_PAD_DP = 6
        }

        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                if (event.action == KeyEvent.ACTION_UP) onOutside()
                return true
            }
            return super.dispatchKeyEvent(event)
        }
    }

    /** One line of the card: its glyph, the 12 sp words, and an optional note at the end. */
    private class MenuRow(context: Context, private val c: OverlayPalette, glyph: MenuGlyph, title: String) : LinearLayout(context) {
        private val icon = MenuGlyphView(context, glyph, c)
        private val label = TextView(context).apply {
            text = title
            textSize = 12f
            setTextColor(c.ink)
            includeFontPadding = false
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        private val note = TextView(context).apply {
            textSize = 11f
            setTextColor(c.faint)
            includeFontPadding = false
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            visibility = View.GONE
        }

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = context.dp(HEIGHT_DP)
            setPadding(context.dp(10), 0, context.dp(10), 0)
            addView(icon, LayoutParams(context.dp(16), context.dp(16)))
            addView(label, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = context.dp(10) })
            addView(note, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginStart = context.dp(8) })
            foreground = ripple()
        }

        private fun ripple(): RippleDrawable = RippleDrawable(
            ColorStateList.valueOf(c.ripple), null,
            GradientDrawable().apply { cornerRadius = context.dpf(INNER_RADIUS_DP); setColor(Color.WHITE) },
        )

        override fun getAccessibilityClassName(): CharSequence = android.widget.Button::class.java.name

        /** Greyed and unclickable while the thing it opens isn't there; [noteText] says why. */
        fun bind(enabled: Boolean, noteText: String?) {
            isEnabled = enabled
            isClickable = enabled
            label.setTextColor(if (enabled) c.ink else c.faint)
            icon.color = if (enabled) c.muted else c.faint
            note.text = noteText.orEmpty()
            note.visibility = if (noteText.isNullOrEmpty()) View.GONE else View.VISIBLE
            alpha = if (enabled) 1f else 0.85f
            foreground = if (enabled) ripple() else null
        }

        private companion object {
            const val HEIGHT_DP = 44
            const val INNER_RADIUS_DP = 14f
        }
    }

    private class MenuGlyphView(context: Context, private val glyph: MenuGlyph, private val c: OverlayPalette) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val path = Path()
        private val rect = RectF()

        var color: Int = c.muted
            set(v) { if (field != v) { field = v; invalidate() } }

        init {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            paint.color = color
            drawMenuGlyph(canvas, glyph, width / 2f, height / 2f, context.dpf(16f), paint, path, rect)
        }
    }

    companion object {
        private const val TAG = "DshVscreen"
        private const val CHANNEL_ID = "dsh-vscreen-ball"
        private const val NOTIFICATION_ID = 3092

        /** `reasonCode` of opening the ball without 显示在其他应用上层. */
        const val NO_PERMISSION = "noOverlayPermission"

        /** Whether the ball is up (the app's 悬浮球 switch and `ballPrefs.showing`). */
        @Volatile
        var showing = false
            private set

        /**
         * Opens or closes the ball. Without [Settings.canDrawOverlays] nothing is started: the
         * caller says what to turn on ([NO_PERMISSION]), unlike the picture window, which opens
         * the system switch itself.
         */
        fun ensure(context: Context, open: Boolean): Map<String, Any?> {
            val app = context.applicationContext
            if (!open) {
                runCatching { app.stopService(Intent(app, VscreenBallService::class.java)) }
                return mapOf("ok" to true)
            }
            if (!Settings.canDrawOverlays(app)) {
                return mapOf("ok" to false, "reasonCode" to NO_PERMISSION, "reason" to "请允许显示在其他应用上层")
            }
            return try {
                app.startForegroundService(Intent(app, VscreenBallService::class.java))
                mapOf("ok" to true)
            } catch (e: Exception) {
                Log.w(TAG, "vscreen ball could not start", e)
                mapOf("ok" to false, "reason" to "悬浮球启动失败，请稍后再试")
            }
        }

        /** `dshgo://open/…` targets of the card's two 设置 rows. */
        const val LINK_VSCREEN = "dshgo://open/vscreen"
        const val LINK_BALL = "dshgo://open/ball"

        private const val SIDE_LEFT = 0
        private const val SIDE_RIGHT = 1

        /** The ball's gap to the screen side, as the app's floating controls (page-design §7). */
        private const val EDGE_DP = 8

        /** Room round the ball, inside the window, for its shadow. */
        private const val PAD_DP = 10

        /** Half-hidden: the window sits this far past the edge, so its circle is half off. */
        private const val HALF_OUT_DP = 4

        private const val PANEL_W_DP = 200
        private const val PANEL_EDGE_DP = 8
        private const val ROW_DP = 44

        /** First drop: the right side, a third of the way down. */
        const val DEFAULT_Y = 0.3f

        private const val SNAP_MS = 180L
        private const val HALF_MS = 200L
        private const val MENU_MS = 150L
        private const val QUICK_MS = 120L
        private const val PRESS_SCALE = 0.92f

        // l10n:keep agent-vs-screen wording (same line BackgroundControl gives the models)
        private const val NOT_RUNNING = "虚拟屏未运行"
    }
}

// ---- drawing ---------------------------------------------------------------

/** The card's four marks, on the glyphs' 24-unit grid (1.75-unit round-capped stroke). */
private enum class MenuGlyph { SCREEN, SLIDERS, BALL, POWER }

/** Draws [glyph] [size] px square centred on ([cx], [cy]) with [paint]'s colour. */
private fun drawMenuGlyph(canvas: Canvas, glyph: MenuGlyph, cx: Float, cy: Float, size: Float, paint: Paint, path: Path, rect: RectF) {
    val s = size / 24f
    paint.style = Paint.Style.STROKE
    paint.strokeWidth = 1.75f * s
    paint.strokeCap = Paint.Cap.ROUND
    paint.strokeJoin = Paint.Join.ROUND
    path.reset()
    when (glyph) {
        MenuGlyph.SCREEN -> {
            rect.set(4.5f, 5.5f, 19.5f, 16f)
            path.addRoundRect(rect, 2.6f, 2.6f, Path.Direction.CW)
            path.moveTo(12f, 16f)
            path.lineTo(12f, 20f)
            path.moveTo(8.5f, 20.2f)
            path.lineTo(15.5f, 20.2f)
        }
        MenuGlyph.SLIDERS -> {
            path.moveTo(4.5f, 9f)
            path.lineTo(19.5f, 9f)
            path.addCircle(9.5f, 9f, 2.1f, Path.Direction.CW)
            path.moveTo(4.5f, 15f)
            path.lineTo(19.5f, 15f)
            path.addCircle(14.5f, 15f, 2.1f, Path.Direction.CW)
        }
        MenuGlyph.BALL -> path.addCircle(12f, 12f, 6.5f, Path.Direction.CW)
        MenuGlyph.POWER -> {
            rect.set(5.5f, 5.5f, 18.5f, 18.5f)
            path.addArc(rect, -60f, 300f)
            path.moveTo(12f, 3.5f)
            path.lineTo(12f, 11.5f)
        }
    }
    canvas.save()
    canvas.translate(cx - size / 2f, cy - size / 2f)
    canvas.scale(s, s)
    canvas.drawPath(path, paint)
    canvas.restore()
}

/** The ball's own mark: a small screen, drawn like the app's line icons (1.75-unit stroke). */
private fun drawScreen(canvas: Canvas, cx: Float, cy: Float, size: Float, paint: Paint, path: Path, rect: RectF) {
    val s = size / 24f
    paint.style = Paint.Style.STROKE
    paint.strokeWidth = 1.75f * s
    paint.strokeCap = Paint.Cap.ROUND
    paint.strokeJoin = Paint.Join.ROUND
    path.reset()
    rect.set(4.5f, 5.5f, 19.5f, 16f)
    path.addRoundRect(rect, 2.6f, 2.6f, Path.Direction.CW)
    path.moveTo(12f, 16f)
    path.lineTo(12f, 20f)
    path.moveTo(8.5f, 20.2f)
    path.lineTo(15.5f, 20.2f)
    canvas.save()
    canvas.translate(cx - size / 2f, cy - size / 2f)
    canvas.scale(s, s)
    canvas.drawPath(path, paint)
    canvas.restore()
}

/**
 * 悬浮球's settings, in the overlay's own preferences file ([OverlayPrefs.FILE]) under
 * `vscreenBall_` keys: 40–72 dp across, 60–100 % opaque, and whether an untouched ball slides
 * half off its edge after 1–10 s. Written through `dsh/phone` (`setBallPrefs`); a showing ball
 * follows at once through [SharedPreferences.OnSharedPreferenceChangeListener].
 */
class VscreenBallPrefs(
    val sizeDp: Int = DEFAULT_SIZE_DP,
    val opacity: Float = DEFAULT_OPACITY,
    val idleHide: Boolean = true,
    val idleMs: Int = DEFAULT_IDLE_MS,
) {
    fun toMap(showing: Boolean): Map<String, Any?> = linkedMapOf(
        "sizeDp" to sizeDp,
        "opacity" to opacity.toDouble(),
        "idleHide" to idleHide,
        "idleMs" to idleMs,
        "showing" to showing,
    )

    companion object {
        const val PREFIX = "vscreenBall_"
        const val K_SIZE = PREFIX + "sizeDp"
        const val K_OPACITY = PREFIX + "opacity"
        const val K_IDLE_HIDE = PREFIX + "idleHide"
        const val K_IDLE_MS = PREFIX + "idleMs"
        const val K_SIDE = PREFIX + "side"
        const val K_Y = PREFIX + "y"

        const val DEFAULT_SIZE_DP = 56
        const val MIN_SIZE_DP = 40
        const val MAX_SIZE_DP = 72

        /** A ball keeps its surface opaque enough to read the mark on any page. */
        const val DEFAULT_OPACITY = 1f
        const val MIN_OPACITY = 0.6f
        const val MAX_OPACITY = 1f

        const val DEFAULT_IDLE_MS = 3_000
        const val MIN_IDLE_MS = 1_000
        const val MAX_IDLE_MS = 10_000

        fun clampSize(v: Int): Int = v.coerceIn(MIN_SIZE_DP, MAX_SIZE_DP)

        fun clampOpacity(v: Float): Float =
            if (v.isNaN()) DEFAULT_OPACITY else ((v.coerceIn(MIN_OPACITY, MAX_OPACITY) * 100).roundToInt() / 100f)

        /** Whole seconds, 1–10. */
        fun clampIdle(v: Int): Int = ((v.coerceIn(MIN_IDLE_MS, MAX_IDLE_MS) + 500) / 1000 * 1000).coerceIn(MIN_IDLE_MS, MAX_IDLE_MS)

        fun prefs(context: Context): SharedPreferences = context.getSharedPreferences(OverlayPrefs.FILE, Context.MODE_PRIVATE)

        fun read(context: Context): VscreenBallPrefs = from(prefs(context))

        fun from(p: SharedPreferences): VscreenBallPrefs = VscreenBallPrefs(
            clampSize(p.getInt(K_SIZE, DEFAULT_SIZE_DP)),
            clampOpacity(p.getFloat(K_OPACITY, DEFAULT_OPACITY)),
            p.getBoolean(K_IDLE_HIDE, true),
            clampIdle(p.getInt(K_IDLE_MS, DEFAULT_IDLE_MS)),
        )

        /** Writes what [map] carries (`sizeDp`, `opacity`, `idleHide`, `idleMs`); the rest stays. */
        fun write(context: Context, map: Map<*, *>?): VscreenBallPrefs {
            val p = prefs(context)
            val edit = p.edit()
            (map?.get("sizeDp") as? Number)?.let { edit.putInt(K_SIZE, clampSize(it.toInt())) }
            (map?.get("opacity") as? Number)?.let { edit.putFloat(K_OPACITY, clampOpacity(it.toFloat())) }
            (map?.get("idleHide") as? Boolean)?.let { edit.putBoolean(K_IDLE_HIDE, it) }
            (map?.get("idleMs") as? Number)?.let { edit.putInt(K_IDLE_MS, clampIdle(it.toInt())) }
            edit.apply()
            return from(p)
        }

        /** Where the ball rests; kept across restarts like the accessibility ball's dock. */
        fun savedSide(context: Context): String =
            if (prefs(context).getString(K_SIDE, null) == "left") "left" else "right"

        fun savedY(context: Context): Float = prefs(context).getFloat(K_Y, VscreenBallService.DEFAULT_Y).coerceIn(0f, 1f)

        fun saveDock(context: Context, side: String, yFraction: Float) {
            prefs(context).edit().putString(K_SIDE, side).putFloat(K_Y, yFraction.coerceIn(0f, 1f)).apply()
        }
    }
}
