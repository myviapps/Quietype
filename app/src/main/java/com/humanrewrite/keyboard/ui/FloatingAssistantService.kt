package com.humanrewrite.keyboard.ui

import android.accessibilityservice.AccessibilityService
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.humanrewrite.keyboard.core.AppMode
import com.humanrewrite.keyboard.core.EntitlementStore
import com.humanrewrite.keyboard.core.LocalModelRewriteGateway
import com.humanrewrite.keyboard.core.ModelStore
import com.humanrewrite.keyboard.core.RewriteCoordinator
import com.humanrewrite.keyboard.core.RewriteResult
import com.humanrewrite.keyboard.core.Palette
import com.humanrewrite.keyboard.core.SettingsStore
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * A single floating "QT" handle that rewrites the text box the user is typing in, in any app,
 * without the keyboard. It docks to whichever screen edge it's dragged to, slides mostly out of
 * sight after a few idle seconds (a curved sliver stays visible), and sliding it back in opens
 * the menu — one continuous shape throughout, never a separate handle-plus-menu pair. It's an
 * accessibility service because that is the only Android API that can read and replace another
 * app's text; it never reads anything until the user opens the menu and taps Rewrite.
 */
class FloatingAssistantService : AccessibilityService() {
    private lateinit var windowManager: WindowManager
    private lateinit var settingsStore: SettingsStore
    private lateinit var rewriteCoordinator: RewriteCoordinator
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    // The overlay window itself is added to WindowManager exactly once (in showOverlay()) and
    // never removed/re-added for state changes — only its single child view and position change.
    // Swapping the whole window in and out on every tap was the "two bubbles" flicker.
    private var overlayRoot: FrameLayout? = null
    private var params: WindowManager.LayoutParams? = null
    private var modeListener: android.content.SharedPreferences.OnSharedPreferenceChangeListener? = null

    private var state = BubbleState.CIRCLE
    private var dockedRight = true
    private var busy = false
    private var status: String? = null
    private var animator: ValueAnimator? = null

    private val idleRunnable = Runnable { animateTo(BubbleState.PEEK) }
    private val clearStatus = Runnable {
        status = null
        renderContent()
    }

    override fun onServiceConnected() {
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        settingsStore = SettingsStore(this)
        rewriteCoordinator = RewriteCoordinator(
            EntitlementStore(this),
            settingsStore,
            LocalModelRewriteGateway(settingsStore, ModelStore(this))
        )
        applyMode(settingsStore.appMode)
        // The Accessibility permission can only be granted or revoked by the user in Settings —
        // this listener is what lets the "Keyboard | Shortcut | Both" slider hide or show the
        // bubble immediately, without the user having to toggle the permission itself.
        modeListener = settingsStore.addAppModeListener { applyMode(it) }
    }

    // Text is only read when the user taps Rewrite, so events are not needed.
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        modeListener?.let { settingsStore.removeListener(it) }
        animator?.cancel()
        mainHandler.removeCallbacks(idleRunnable)
        overlayRoot?.let { windowManager.removeView(it) }
        overlayRoot = null
        executor.shutdown()
        super.onDestroy()
    }

    private fun applyMode(mode: AppMode) {
        if (mode.needsShortcut) {
            if (overlayRoot == null) showOverlay()
        } else if (overlayRoot != null) {
            animator?.cancel()
            mainHandler.removeCallbacks(idleRunnable)
            windowManager.removeView(overlayRoot)
            overlayRoot = null
        }
    }

    private fun showOverlay() {
        // Android clamps TYPE_ACCESSIBILITY_OVERLAY windows to stay fully on-screen — asking for
        // an x position that would hang the window off the edge gets silently overridden back to
        // the nearest valid position (confirmed via dumpsys window: mAttrs held our requested x,
        // but the computed Frame did not). So "hiding" the bubble can't move the window off-screen;
        // instead the PEEK state shrinks the window's own width, clipping the still full-size
        // circle inside it down to a sliver — the window itself always stays validly on-screen.
        val layoutParams = WindowManager.LayoutParams(
            CIRCLE_SIZE_DP.dp,
            CIRCLE_SIZE_DP.dp,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = screenWidth() - CIRCLE_SIZE_DP.dp - EDGE_MARGIN_DP.dp
            y = dp(220) // initial vertical position, matches the old default
        }
        val root = FrameLayout(this)
        params = layoutParams
        overlayRoot = root
        windowManager.addView(root, layoutParams)
        renderContent()
        attachTouchHandling(root)
        scheduleIdle()
    }

    // ---- content rendering: swaps the single child view in place, never the window itself ----

    private fun renderContent() {
        val root = overlayRoot ?: return
        root.removeAllViews()
        root.addView(
            when (state) {
                BubbleState.PEEK, BubbleState.CIRCLE -> circleView()
                BubbleState.MENU -> menuView()
            }
        )
    }

    private fun circleView(): TextView = TextView(this).apply {
        text = status ?: if (busy) "…" else "QT"
        textSize = if (status != null) 11f else 14f
        gravity = Gravity.CENTER
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(Color.WHITE)
        setPadding(dp(6), dp(6), dp(6), dp(6))
        layoutParams = FrameLayout.LayoutParams(CIRCLE_SIZE_DP.dp, CIRCLE_SIZE_DP.dp)
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(ACCENT)
        }
        elevation = dp(6).toFloat()
    }

    private fun menuView(): LinearLayout = LinearLayout(this).apply {
        Palette.apply(this@FloatingAssistantService, settingsStore.themeMode)
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            setColor(Palette.surface)
            cornerRadius = dp(18).toFloat()
        }
        elevation = dp(8).toFloat()
        setPadding(dp(10), dp(10), dp(10), dp(10))
        layoutParams = FrameLayout.LayoutParams(MENU_WIDTH_DP.dp, ViewGroup.LayoutParams.WRAP_CONTENT)
        addView(pill(if (busy) "Rewriting…" else "Rewrite", ACCENT, Color.WHITE) { rewriteFocusedText() })
        addView(pill(settingsStore.rewriteMode.title, ACCENT_SOFT, ACCENT) {
            settingsStore.rewriteMode = settingsStore.rewriteMode.next()
            renderContent()
        })
        addView(pill("Hide", ACCENT_SOFT, ACCENT) {
            animateTo(BubbleState.CIRCLE)
        })
        // Requires opening the menu first (not reachable from a single tap on the collapsed
        // bubble), so it can't be triggered by one accidental tap the way it could sitting
        // directly on the collapsed circle.
        addView(pill("Turn off", Color.TRANSPARENT, MUTED) { disableSelf() })
    }

    private fun pill(label: String, fill: Int, textColor: Int, onClick: () -> Unit): TextView = TextView(this).apply {
        text = label
        textSize = 14f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(textColor)
        gravity = Gravity.CENTER
        minHeight = dp(44)
        setPadding(dp(16), 0, dp(16), 0)
        background = RippleDrawable(
            ColorStateList.valueOf(Color.argb(40, 0, 0, 0)),
            GradientDrawable().apply { setColor(fill); cornerRadius = dp(22).toFloat() },
            null
        )
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) }
        setOnClickListener { onClick() }
    }

    // ---- state transitions ----

    private enum class BubbleState { PEEK, CIRCLE, MENU }

    private fun scheduleIdle() {
        mainHandler.removeCallbacks(idleRunnable)
        if (state == BubbleState.CIRCLE) mainHandler.postDelayed(idleRunnable, IDLE_MS)
    }

    private fun animateTo(target: BubbleState) {
        val p = params ?: return
        val root = overlayRoot ?: return
        mainHandler.removeCallbacks(idleRunnable)
        animator?.cancel()

        val fromState = state
        state = target
        val shapeChange = target == BubbleState.MENU || fromState == BubbleState.MENU
        if (shapeChange) {
            // Content shape changes (circle <-> menu): swap immediately, no slide — only the
            // circle-to-peek-and-back transition is a slide, since it's the same shape clipping
            // narrower or wider, not a different view.
            renderContent()
        }

        val targetWidth = widthFor(target)
        if (shapeChange || p.width == targetWidth) {
            p.width = targetWidth
            p.x = xForWidth(targetWidth)
            windowManager.updateViewLayout(root, p)
            if (target == BubbleState.CIRCLE) scheduleIdle()
            return
        }

        animator = ValueAnimator.ofInt(p.width, targetWidth).apply {
            duration = 220
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                val w = it.animatedValue as Int
                p.width = w
                p.x = xForWidth(w)
                runCatching { windowManager.updateViewLayout(root, p) }
            }
            start()
        }
        if (target == BubbleState.CIRCLE) mainHandler.postDelayed({ scheduleIdle() }, 240)
    }

    private fun widthFor(state: BubbleState): Int = when (state) {
        BubbleState.PEEK -> PEEK_VISIBLE_DP.dp
        BubbleState.CIRCLE -> CIRCLE_SIZE_DP.dp
        BubbleState.MENU -> MENU_WIDTH_DP.dp
    }

    // Right edge (or left edge, if docked left) always stays flush with the screen edge — only
    // the far edge moves as width changes between states. This is what keeps every requested
    // position valid and un-clamped (see the note in showOverlay()).
    private fun xForWidth(width: Int): Int =
        if (dockedRight) screenWidth() - width - EDGE_MARGIN_DP.dp else EDGE_MARGIN_DP.dp

    // ---- touch handling: drag to reposition + snap to nearest edge, or tap to act ----

    private fun attachTouchHandling(view: View) {
        var startRawX = 0f
        var startRawY = 0f
        var startParamsX = 0
        var startParamsY = 0
        var dragged = false

        view.setOnTouchListener { _, event ->
            val p = params ?: return@setOnTouchListener false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    animator?.cancel()
                    mainHandler.removeCallbacks(idleRunnable)
                    startRawX = event.rawX
                    startRawY = event.rawY
                    startParamsX = p.x
                    startParamsY = p.y
                    dragged = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    // Only the fully-visible circle can be dragged — the menu doesn't reposition,
                    // and the peek sliver only reveals on tap (its width differs from the
                    // dragging math below, which assumes a full-size circle).
                    if (state != BubbleState.CIRCLE) return@setOnTouchListener false
                    val dx = (event.rawX - startRawX)
                    val dy = (event.rawY - startRawY)
                    if (!dragged && (abs(dx) > TOUCH_SLOP || abs(dy) > TOUCH_SLOP)) dragged = true
                    if (dragged) {
                        // Android clamps this window to stay fully on-screen regardless, so only
                        // valid (fully on-screen) positions are worth requesting.
                        p.x = (startParamsX + dx).toInt().coerceIn(0, screenWidth() - CIRCLE_SIZE_DP.dp)
                        p.y = (startParamsY + dy).toInt().coerceIn(0, screenHeight() - CIRCLE_SIZE_DP.dp)
                        runCatching { windowManager.updateViewLayout(view, p) }
                    }
                    dragged
                }
                MotionEvent.ACTION_UP -> {
                    if (dragged) {
                        dockedRight = (p.x + CIRCLE_SIZE_DP.dp / 2) > screenWidth() / 2
                        animateTo(BubbleState.CIRCLE)
                    } else {
                        onTap()
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun onTap() {
        when (state) {
            // Tapping the hidden sliver slides it fully into view and straight on into the menu —
            // the slide itself is what "opens" it.
            BubbleState.PEEK -> {
                state = BubbleState.CIRCLE
                animateThenOpenMenu()
            }
            BubbleState.CIRCLE -> animateTo(BubbleState.MENU)
            BubbleState.MENU -> Unit
        }
    }

    private fun animateThenOpenMenu() {
        val p = params ?: return
        val root = overlayRoot ?: return
        val targetWidth = widthFor(BubbleState.CIRCLE)
        animator?.cancel()
        animator = ValueAnimator.ofInt(p.width, targetWidth).apply {
            duration = 220
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                val w = it.animatedValue as Int
                p.width = w
                p.x = xForWidth(w)
                runCatching { windowManager.updateViewLayout(root, p) }
            }
            doOnEndCompat { animateTo(BubbleState.MENU) }
            start()
        }
    }

    private fun ValueAnimator.doOnEndCompat(action: () -> Unit) {
        addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) = action()
        })
    }

    // ---- rewrite action (unchanged behavior) ----

    private fun rewriteFocusedText() {
        if (busy) return
        val node = focusedTextBox() ?: return toast("Tap into a text box first (password fields are not supported), then tap Rewrite")
        val text = node.text?.toString().orEmpty()
        if (text.isBlank()) {
            node.recycle()
            return toast("Nothing to rewrite")
        }
        val start = node.textSelectionStart
        val end = node.textSelectionEnd
        val hasSelection = start in 0 until end && end <= text.length
        val target = if (hasSelection) text.substring(start, end) else text.trim()
        node.recycle()

        busy = true
        renderContent()
        executor.execute {
            val result = rewriteCoordinator.rewrite(target)
            mainHandler.post {
                busy = false
                when (result) {
                    is RewriteResult.Success -> {
                        val replacement = if (hasSelection) text.replaceRange(start, end, result.text) else result.text
                        // The field may have changed while the model ran, so look it up again before
                        // writing, and refuse to overwrite anything the user typed in the meantime.
                        val current = focusedTextBox()
                        if (current == null) {
                            toast("The text box went away. Copy: ${result.text}")
                        } else if (current.text?.toString().orEmpty() != text) {
                            current.recycle()
                            toast("The text changed while rewriting. Tap Rewrite again.")
                        } else {
                            val args = Bundle().apply {
                                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, replacement)
                            }
                            val ok = current.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                            current.recycle()
                            toast(if (ok) "Rewritten" else "This app doesn't allow text replacement")
                        }
                    }
                    is RewriteResult.Locked -> toast(result.reason)
                    is RewriteResult.NeedsCloudConsent -> toast(result.reason)
                    is RewriteResult.Failed -> toast(result.reason)
                }
                animateTo(BubbleState.CIRCLE)
            }
        }
    }

    private fun focusedTextBox(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        val node = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        root.recycle()
        if (node == null) return null
        // Empty fields report their hint as text; treat that as empty.
        // Never read or replace password fields.
        if (!node.isEditable || node.isShowingHintText || node.isPassword) {
            node.recycle()
            return null
        }
        return node
    }

    // Android drops toasts from background services, so messages are shown on the bubble itself
    // for a few seconds instead.
    private fun toast(message: String) {
        status = message
        state = BubbleState.CIRCLE
        renderContent()
        val root = overlayRoot
        val p = params
        if (root != null && p != null) {
            p.width = widthFor(BubbleState.CIRCLE)
            p.x = xForWidth(p.width)
            runCatching { windowManager.updateViewLayout(root, p) }
        }
        mainHandler.removeCallbacks(clearStatus)
        mainHandler.postDelayed(clearStatus, 3500)
    }

    private fun screenWidth(): Int = resources.displayMetrics.widthPixels
    private fun screenHeight(): Int = resources.displayMetrics.heightPixels
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private val Int.dp: Int get() = dp(this)

    companion object {
        private val ACCENT get() = Palette.accent
        private val ACCENT_SOFT get() = Palette.accentSoft
        private val MUTED get() = Palette.muted
        private const val CIRCLE_SIZE_DP = 52
        private const val PEEK_VISIBLE_DP = 16
        private const val MENU_WIDTH_DP = 150
        private const val EDGE_MARGIN_DP = 0
        private const val TOUCH_SLOP = 12
        private const val IDLE_MS = 2500L

        fun isEnabled(context: Context): Boolean {
            val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            return enabled?.split(':')?.any { it.startsWith(context.packageName + "/") } == true
        }
    }
}
