package dev.intent.checkpoint.overlay

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import dev.intent.checkpoint.engine.EngineLog
import dev.intent.checkpoint.permissions.PermissionChecker
import dev.intent.core.AppRule
import dev.intent.core.Session
import dev.intent.core.SessionState
import dev.intent.core.WarningLevel
import kotlin.math.abs

/** User actions on overlay surfaces. Implemented by the engine host; called on the main thread. */
interface OverlayCallbacks {
    fun onPauseElapsed(sessionId: String)
    fun onSubmitIntention(sessionId: String, intention: String, plannedSeconds: Long?)
    fun onNotNow(sessionId: String)
    fun onDone(sessionId: String)
    fun onEnd(sessionId: String)
    fun onBeginExtension(sessionId: String)
    fun onCancelExtension(sessionId: String)
    fun onRequestExtension(sessionId: String, reason: String?, extraSeconds: Long)
    fun onSnoozeExpired(sessionId: String)
}

/**
 * Native overlay host (PRD §28, ADR-002): plain Views in `TYPE_APPLICATION_OVERLAY` windows.
 * Two windows at most: one focusable, full-screen "checkpoint" window whose content is swapped
 * (pause -> intention form, expiry -> extension, completion) and one non-focusable reminder pill.
 *
 * Thread-safe entry points: every public method may be called from any thread and hops to main.
 */
class OverlayController(context: Context, private val callbacks: OverlayCallbacks) {
    private enum class Surface { PAUSE, FORM, EXPIRED, EXTENSION, COMPLETION }

    private val ctx = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val wm = ctx.getSystemService(WindowManager::class.java)

    // Main-thread state.
    private var checkpointRoot: BackAwareFrame? = null
    private var checkpointSessionId: String? = null
    private var checkpointSurface: Surface? = null
    private var pauseRunnable: Runnable? = null
    private var pauseAnimator: ValueAnimator? = null

    private var reminderRoot: View? = null
    private var reminderSessionId: String? = null
    private var reminderTicker: Runnable? = null
    /** Session whose pill the user dragged away; it stays hidden until they leave and return. */
    private var reminderDismissedFor: String? = null

    // --- checkpoint surfaces -----------------------------------------------------------------

    fun showPause(session: Session, pauseMs: Long) = onMain {
        if (checkpointSessionId == session.id &&
            (checkpointSurface == Surface.PAUSE || checkpointSurface == Surface.FORM)
        ) {
            return@onMain // duplicate detection while already showing: no second checkpoint
        }
        val v = views()
        val content = v.column()
        content.addFullWidth(v.label(session.appName))
        content.addFullWidth(v.title("Wait."), ctx.dp(16))
        val dot = View(ctx).apply {
            background = rounded(v.palette.accent, ctx.dp(12).toFloat())
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        content.addView(dot, LinearLayout.LayoutParams(ctx.dp(24), ctx.dp(24)).apply { topMargin = ctx.dp(32) })
        content.addFullWidth(v.label("One second."), ctx.dp(24))

        if (!setCheckpoint(session.id, Surface.PAUSE, content) { callbacks.onNotNow(session.id) }) return@onMain

        val reduced = ctx.reducedMotion()
        val duration = if (reduced) REDUCED_MOTION_PAUSE_MS else pauseMs
        if (!reduced) {
            pauseAnimator = ObjectAnimator.ofFloat(dot, View.SCALE_X, 1f, 1.6f).apply {
                this.duration = 900
                repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE
                addUpdateListener { dot.scaleY = dot.scaleX }
                start()
            }
        } else {
            content.alpha = 0f
            content.animate().alpha(1f).setDuration(REDUCED_MOTION_PAUSE_MS).start()
        }
        pauseRunnable = Runnable { callbacks.onPauseElapsed(session.id) }.also { main.postDelayed(it, duration) }
    }

    fun showIntentionForm(session: Session, rule: AppRule) = onMain {
        if (checkpointSessionId == session.id && checkpointSurface == Surface.FORM) return@onMain // keep typed text
        val v = views()
        val content = v.column()
        content.addFullWidth(v.label(session.appName))
        content.addFullWidth(v.title("Wait."), ctx.dp(8))
        content.addFullWidth(v.body("Why are you opening ${session.appName}?"), ctx.dp(24))

        val input = v.input("What are you here to do?", MAX_INTENTION_CHARS)
        content.addFullWidth(input, ctx.dp(16))

        val suggestions = v.flow()
        for (s in SUGGESTIONS) {
            suggestions.addView(
                v.chip(s) {
                    if (s == OTHER) {
                        input.setText("")
                        input.requestFocus()
                    } else {
                        input.setText(s)
                        input.setSelection(s.length)
                    }
                },
            )
        }
        content.addFullWidth(suggestions, ctx.dp(12))

        var selectedSeconds: Long? = rule.defaultTimerSeconds
        var customSelected = false
        val custom = v.numberInput("Minutes").apply { visibility = View.GONE }
        if (rule.timerEnabled) {
            content.addFullWidth(v.body("How long do you need?", 16f), ctx.dp(24))
            val durations = v.flow()
            val chips = mutableListOf<Button>()
            fun select(chip: Button, seconds: Long?, isCustom: Boolean) {
                chips.forEach { v.setChipSelected(it, it === chip) }
                selectedSeconds = seconds
                customSelected = isCustom
                custom.visibility = if (isCustom) View.VISIBLE else View.GONE
                if (isCustom) custom.requestFocus()
            }
            for ((label, seconds) in DURATIONS) {
                val chip = v.chip(label, if (seconds == null) "No timer" else "$label timer") {
                    select(it, seconds, isCustom = false)
                }
                chips += chip
                durations.addView(chip)
                if (seconds == rule.defaultTimerSeconds) v.setChipSelected(chip, true)
            }
            if (rule.customTimerAllowed) {
                val chip = v.chip("Custom", "Custom duration") { select(it, null, isCustom = true) }
                chips += chip
                durations.addView(chip)
            }
            content.addFullWidth(durations, ctx.dp(12))
            content.addFullWidth(custom, ctx.dp(8))
        } else {
            selectedSeconds = null
        }

        val submit = {
            val seconds = if (customSelected) custom.text.toString().toLongOrNull()?.takeIf { it > 0 }?.times(60) else selectedSeconds
            if (customSelected && seconds == null) {
                custom.error = "Enter minutes"
            } else {
                callbacks.onSubmitIntention(session.id, input.text.toString(), seconds)
            }
        }
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                input.clearFocus()
                hideKeyboard(input)
                true
            } else {
                false
            }
        }
        content.addFullWidth(v.primary("Continue") { submit() }, ctx.dp(28))
        content.addFullWidth(v.textButton("Not now") { callbacks.onNotNow(session.id) }, ctx.dp(4))

        setCheckpoint(session.id, Surface.FORM, content) { callbacks.onNotNow(session.id) }
    }

    fun showExpired(session: Session, extensionAllowed: Boolean) = onMain {
        hideReminderNow()
        if (session.state == SessionState.EXTENSION_REQUEST) {
            showExtensionForm(session)
            return@onMain
        }
        if (checkpointSessionId == session.id && checkpointSurface == Surface.EXPIRED) return@onMain
        val v = views()
        val content = v.column()
        content.addFullWidth(v.title("Time's up"))
        content.addFullWidth(v.label("You opened ${session.appName} to:"), ctx.dp(24))
        content.addFullWidth(v.body("“${session.intention}”", 22f), ctx.dp(8))
        val planned = session.plannedDurationSeconds
        if (planned != null) {
            val line = if (session.extensionCount > 0 && session.startedAt != null) {
                val here = (System.currentTimeMillis() - session.startedAt!!) / 1000
                "You planned ${formatMinutes(planned)}. You've been here for ${formatMinutes(here)}."
            } else {
                "You planned ${formatMinutes(planned)}."
            }
            content.addFullWidth(v.label(line), ctx.dp(16))
        }
        content.addFullWidth(v.body("Still doing what you came here to do?", 16f), ctx.dp(24))
        content.addFullWidth(v.primary("Yes — I'm done") { callbacks.onDone(session.id) }, ctx.dp(24))
        if (extensionAllowed) {
            content.addFullWidth(v.secondary("I need more time") { callbacks.onBeginExtension(session.id) }, ctx.dp(12))
        }
        content.addFullWidth(v.textButton("Leave ${session.appName}") { callbacks.onEnd(session.id) }, ctx.dp(8))
        // Back = "need more time, no request": short snooze, never a silent reset (PRD §15.8).
        setCheckpoint(session.id, Surface.EXPIRED, content) { callbacks.onSnoozeExpired(session.id) }
    }

    private fun showExtensionForm(session: Session) {
        if (checkpointSessionId == session.id && checkpointSurface == Surface.EXTENSION) return
        val v = views()
        val content = v.column()
        content.addFullWidth(v.title("Need more time?"))
        content.addFullWidth(v.label("You came here to: “${session.intention}”"), ctx.dp(16))
        val reason = v.input("What's left? (optional)", MAX_INTENTION_CHARS)
        content.addFullWidth(reason, ctx.dp(24))
        val chips = mutableListOf<Button>()
        var extra = EXTENSIONS.first().second
        val row = v.flow()
        for ((label, seconds) in EXTENSIONS) {
            val chip = v.chip(label, "$label more") { c ->
                chips.forEach { v.setChipSelected(it, it === c) }
                extra = seconds
            }
            chips += chip
            row.addView(chip)
        }
        v.setChipSelected(chips.first(), true)
        content.addFullWidth(row, ctx.dp(16))
        content.addFullWidth(
            v.primary("Continue") { callbacks.onRequestExtension(session.id, reason.text.toString(), extra) },
            ctx.dp(28),
        )
        setCheckpoint(session.id, Surface.EXTENSION, content) { callbacks.onCancelExtension(session.id) }
    }

    fun showCompletion(session: Session) = onMain {
        hideReminderNow()
        val v = views()
        val content = v.column()
        content.addFullWidth(v.title("Done for now?"))
        content.addFullWidth(v.body("“${session.intention}”", 20f), ctx.dp(16))
        session.wallClockSeconds?.let {
            content.addFullWidth(v.label("You were here for ${formatMinutes(it)}."), ctx.dp(8))
        }
        content.addFullWidth(v.primary("Leave ${session.appName}") { goHome() }, ctx.dp(32))
        content.addFullWidth(v.secondary("Stay without a session") { hideCheckpointNow() }, ctx.dp(12))
        setCheckpoint(session.id, Surface.COMPLETION, content) { hideCheckpointNow() }
    }

    fun hideCheckpoint() = onMain { hideCheckpointNow() }

    // --- reminder pill -----------------------------------------------------------------------

    fun showReminder(session: Session, warningsEnabled: Boolean) = onMain {
        if (reminderDismissedFor == session.id && reminderSessionId == null) return@onMain
        hideReminderNow()
        if (!PermissionChecker.canDrawOverlays(ctx)) return@onMain
        val v = views()
        val p = v.palette
        val pill = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(p.surface, ctx.dp(22).toFloat())
            elevation = ctx.dp(6).toFloat()
            setPadding(ctx.dp(16), ctx.dp(10), ctx.dp(16), ctx.dp(10))
            minimumHeight = ctx.dp(48)
        }
        val line = TextView(ctx).apply {
            setTextColor(p.text)
            textSize = 15f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            maxWidth = ctx.dp(260)
        }
        val actions = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            visibility = View.GONE
        }
        actions.addView(v.chip("Done") { callbacks.onDone(session.id) })
        actions.addView(
            v.chip("End") { callbacks.onEnd(session.id) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = ctx.dp(8)
            },
        )
        pill.addView(line)
        pill.addView(actions)

        val params = WindowManager.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = ctx.dp(16)
            y = ctx.dp(96)
        }

        pill.setOnTouchListener(DragToDismiss(params, onTap = {
            actions.visibility = if (actions.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }, onDismiss = {
            reminderDismissedFor = session.id
            hideReminderNow()
        }))

        if (!addWindow(pill, params)) return@onMain
        reminderRoot = pill
        reminderSessionId = session.id
        reminderDismissedFor = null

        val tick = object : Runnable {
            override fun run() {
                val now = System.currentTimeMillis()
                val remaining = session.remainingMs(now)
                line.text = if (remaining == null) session.intention else "${session.intention} · ${formatClock(remaining)}"
                pill.contentDescription = if (remaining == null) {
                    "Intention: ${session.intention}. Tap for options."
                } else {
                    "Intention: ${session.intention}. ${formatClock(remaining)} remaining. Tap for options."
                }
                val level = if (warningsEnabled) session.warningLevel(now) else WarningLevel.NONE
                line.setTextColor(if (level == WarningLevel.NONE) p.text else p.warn)
                if (level == WarningLevel.THIRTY_SECONDS && !ctx.reducedMotion()) {
                    pill.alpha = if (pill.alpha < 1f) 1f else 0.85f // gentle pulse, no popup
                } else {
                    pill.alpha = 1f
                }
                main.postDelayed(this, 1_000)
            }
        }
        reminderTicker = tick
        tick.run()
    }

    fun hideReminder() = onMain {
        // Leaving the app resets the "dragged away" choice: the pill comes back on return.
        reminderDismissedFor = null
        hideReminderNow()
    }

    fun hideAll() = onMain {
        hideCheckpointNow()
        reminderDismissedFor = null
        hideReminderNow()
    }

    /**
     * "Leave app" as far as Android allows a third-party app: bring the launcher to front. We never
     * claim the target app was closed (PRD §15.10). Background-activity-start is permitted because
     * we hold SYSTEM_ALERT_WINDOW.
     */
    fun goHome() = onMain {
        hideCheckpointNow()
        hideReminderNow()
        try {
            ctx.startActivity(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            Toast.makeText(ctx, "Taking you home.", Toast.LENGTH_SHORT).show()
        } catch (e: RuntimeException) {
            EngineLog.w("could not start launcher", e)
        }
    }

    // --- window plumbing ---------------------------------------------------------------------

    /** Returns false if the window could not be shown; the engine keeps its state either way. */
    private fun setCheckpoint(sessionId: String, surface: Surface, content: LinearLayout, onBack: () -> Unit): Boolean {
        if (!PermissionChecker.canDrawOverlays(ctx)) return false
        // Never draw over the lock screen / credential UI (PRD §39).
        if (ctx.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true) return false

        hideCheckpointNow()
        val palette = OverlayPalette(ctx)
        val root = BackAwareFrame(ctx, onBack).apply {
            setBackgroundColor(palette.scrim)
            // Tapjacking guard: ignore taps while another app's window obscures ours.
            filterTouchesWhenObscured = true
            isFocusableInTouchMode = true
        }
        val scroll = ScrollView(ctx).apply {
            isFillViewport = true
            clipToPadding = false
        }
        val wrapper = FrameLayout(ctx)
        content.setPadding(ctx.dp(24), ctx.dp(24), ctx.dp(24), ctx.dp(24))
        wrapper.addView(
            content,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            ).apply { width = minOf(ctx.resources.displayMetrics.widthPixels, ctx.dp(520)) },
        )
        scroll.addView(wrapper, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(scroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        applySystemInsets(root, scroll)

        val params = WindowManager.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // Focusable (no FLAG_NOT_FOCUSABLE) so the text field gets the IME and back reaches us.
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            // Deprecated on 30+, where the IME inset listener below does the work; still needed <30.
            @Suppress("DEPRECATION")
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            title = "IntentCheckpoint"
        }
        if (!addWindow(root, params)) return false
        root.requestFocus()
        checkpointRoot = root
        checkpointSessionId = sessionId
        checkpointSurface = surface
        return true
    }

    /**
     * Pads for status/nav bars, cutouts and the IME so the text field is never covered by the
     * keyboard (PRD §28), including on API 30+ where ADJUST_RESIZE alone is unreliable for
     * overlay windows.
     */
    private fun applySystemInsets(root: View, target: View) {
        root.setOnApplyWindowInsetsListener { _, insets ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bars = insets.getInsets(
                    WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime(),
                )
                target.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            } else {
                @Suppress("DEPRECATION")
                target.setPadding(
                    insets.systemWindowInsetLeft,
                    insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight,
                    insets.systemWindowInsetBottom,
                )
            }
            insets
        }
    }

    private fun addWindow(view: View, params: WindowManager.LayoutParams): Boolean = try {
        wm.addView(view, params)
        true
    } catch (e: RuntimeException) {
        // BadTokenException / SecurityException on some OEM builds or a just-revoked permission:
        // skip this surface rather than crash the service (PRD §41).
        EngineLog.w("overlay addView failed", e)
        false
    }

    private fun removeWindow(view: View?) {
        if (view == null) return
        try {
            wm.removeViewImmediate(view)
        } catch (e: IllegalArgumentException) {
            // Already detached (e.g. system removed it when the permission was revoked).
        }
    }

    private fun hideCheckpointNow() {
        pauseRunnable?.let(main::removeCallbacks)
        pauseRunnable = null
        pauseAnimator?.cancel()
        pauseAnimator = null
        checkpointRoot?.let(::hideKeyboard)
        removeWindow(checkpointRoot)
        checkpointRoot = null
        checkpointSessionId = null
        checkpointSurface = null
    }

    private fun hideReminderNow() {
        reminderTicker?.let(main::removeCallbacks)
        reminderTicker = null
        removeWindow(reminderRoot)
        reminderRoot = null
        reminderSessionId = null
    }

    private fun hideKeyboard(view: View) {
        ctx.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
            ?.hideSoftInputFromWindow(view.windowToken, 0)
    }

    private fun views() = OverlayViews(ctx, OverlayPalette(ctx))

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    /**
     * Drag the pill anywhere; fling it past a screen edge to hide it for this visit. Hiding never
     * ends the session (PRD §15.4). A short press with no movement toggles the actions.
     */
    private inner class DragToDismiss(
        private val params: WindowManager.LayoutParams,
        private val onTap: () -> Unit,
        private val onDismiss: () -> Unit,
    ) : View.OnTouchListener {
        private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
        private var downRawX = 0f
        private var downRawY = 0f
        private var startX = 0
        private var startY = 0
        private var dragging = false

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = e.rawX
                    downRawY = e.rawY
                    startX = params.x
                    startY = params.y
                    dragging = false
                    // Children (Done/End) get first dibs in dispatch, so claiming here is safe.
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downRawX
                    val dy = e.rawY - downRawY
                    if (!dragging && (abs(dx) > slop || abs(dy) > slop)) dragging = true
                    if (dragging) {
                        params.x = startX + dx.toInt()
                        params.y = (startY + dy.toInt()).coerceAtLeast(0)
                        try {
                            wm.updateViewLayout(v, params)
                        } catch (_: IllegalArgumentException) {
                        }
                    }
                    return dragging
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragging) {
                        v.performClick()
                        onTap()
                        return true
                    }
                    val screenW = ctx.resources.displayMetrics.widthPixels
                    val offLeft = params.x < -v.width / 2
                    val offRight = params.x + v.width / 2 > screenW
                    if (offLeft || offRight) onDismiss()
                    return true
                }
            }
            return false
        }
    }

    private companion object {
        const val REDUCED_MOTION_PAUSE_MS = 300L
        const val MAX_INTENTION_CHARS = 200

        const val OTHER = "Other"

        val SUGGESTIONS = listOf(
            "Reply to someone",
            "Post something",
            "Check a notification",
            "Search for something",
            "Entertainment",
            OTHER,
        )

        /** "2m" is the Phase 0 test duration (P0-010); drop it before Phase 3 ships. */
        val DURATIONS: List<Pair<String, Long?>> = listOf(
            "2m" to 120L,
            "5m" to 300L,
            "10m" to 600L,
            "15m" to 900L,
            "30m" to 1_800L,
            "No timer" to null,
        )

        val EXTENSIONS: List<Pair<String, Long>> = listOf("5m" to 300L, "10m" to 600L, "15m" to 900L)
    }
}
