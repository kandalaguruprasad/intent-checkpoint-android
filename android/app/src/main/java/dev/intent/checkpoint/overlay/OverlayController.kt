package dev.intent.checkpoint.overlay

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import dev.intent.checkpoint.BuildConfig
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
 * At most two windows: one focusable full-screen "checkpoint" window whose content is swapped
 * (pause → intention, time's up → extension, completion), and one non-focusable reminder pill.
 *
 * Never reads or inspects the app underneath; it only draws on top of it.
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
    private var waitDotsRunnable: Runnable? = null

    private var pillRoot: FrameLayout? = null
    private var pillParams: WindowManager.LayoutParams? = null
    private var pillSession: Session? = null
    private var pillWarnings = true
    private var pillMode = PillMode.COMPACT
    private var pillTicker: Runnable? = null

    /** AssistiveTouch-style states for the floating reminder. */
    private enum class PillMode { COMPACT, EXPANDED, EDGE }

    // --- 1. pause + intention checkpoint -----------------------------------------------------

    fun showPause(session: Session, pauseMs: Long) = onMain {
        if (checkpointSessionId == session.id &&
            (checkpointSurface == Surface.PAUSE || checkpointSurface == Surface.FORM)
        ) {
            return@onMain // duplicate detection while already showing: never a second checkpoint
        }
        val p = OverlayPalette.LIGHT
        val v = OverlayViews(ctx, p)
        val content = v.column()
        content.addWrap(v.appIcon(session.packageName, 56), ctx.dp(96))
        content.addFull(v.text(session.appName, 15f, p.muted), ctx.dp(12))
        val waitTitle = v.title("Wait.", 34f)
        content.addFull(waitTitle, ctx.dp(32))
        val dot = View(ctx).apply {
            background = rounded(p.primary, ctx.dpf(12))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        content.addView(dot, LinearLayout.LayoutParams(ctx.dp(24), ctx.dp(24)).apply { topMargin = ctx.dp(40) })
        content.addFull(v.text("One second.", 15f, p.muted), ctx.dp(32))

        if (!setCheckpoint(session.id, Surface.PAUSE, p, content, footer = null) { callbacks.onNotNow(session.id) }) {
            return@onMain
        }
        val reduced = ctx.reducedMotion()
        if (reduced) {
            content.alpha = 0f
            content.animate().alpha(1f).setDuration(REDUCED_MOTION_PAUSE_MS).start()
        } else {
            pauseAnimator = ObjectAnimator.ofFloat(dot, View.SCALE_X, 1f, 1.6f).apply {
                duration = 900
                repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE
                addUpdateListener { dot.scaleY = dot.scaleX }
                start()
            }
            startWaitDots(waitTitle)
            // Gentle continuous drift upward for the whole pause: by the time the intention form
            // cross-fades in, the pause content is already mid-motion instead of static-then-cut.
            content.animate().translationY(-ctx.dpf(16)).setDuration(maxOf(pauseMs, 900L)).start()
        }
        val wait = if (reduced) REDUCED_MOTION_PAUSE_MS else pauseMs
        pauseRunnable = Runnable { callbacks.onPauseElapsed(session.id) }.also { main.postDelayed(it, wait) }
    }

    /** Cycles the "Wait" title through Wait. → Wait.. → Wait... so the pause reads as active, not stuck. */
    private fun startWaitDots(title: TextView) {
        waitDotsRunnable?.let(main::removeCallbacks)
        var dots = 1
        val tick = object : Runnable {
            override fun run() {
                dots = if (dots >= 3) 1 else dots + 1
                title.text = "Wait" + ".".repeat(dots)
                main.postDelayed(this, 450)
            }
        }
        waitDotsRunnable = tick
        main.postDelayed(tick, 450)
    }

    fun showIntentionForm(session: Session, rule: AppRule, recentIntentions: List<String>) = onMain {
        if (checkpointSessionId == session.id && checkpointSurface == Surface.FORM) return@onMain // keep typed text
        // The pause naturally leads into this form: continue that motion instead of a hard cut.
        val crossfadeFromPause = checkpointSessionId == session.id && checkpointSurface == Surface.PAUSE
        val p = OverlayPalette.LIGHT
        val v = OverlayViews(ctx, p)
        val content = v.column()
        content.addWrap(v.appIcon(session.packageName, 48), ctx.dp(24))
        content.addFull(v.text(session.appName, 15f, p.muted), ctx.dp(8))
        content.addFull(v.title("Why are you here?"), ctx.dp(16))

        val (inputBox, input) = v.input("What do you want to do?", MAX_INTENTION_CHARS, withClear = true)
        content.addFull(inputBox, ctx.dp(24))

        fun fill(text: String) {
            input.setText(text)
            input.setSelection(text.length)
        }

        if (recentIntentions.isNotEmpty()) {
            content.addFull(v.label("Recent"), ctx.dp(24))
            val recent = v.flow()
            for (r in recentIntentions.take(3)) recent.addView(v.chip(r, "Recent: $r") { fill(r) })
            content.addFull(recent, ctx.dp(8))
        }

        content.addFull(v.label("Quick picks"), ctx.dp(if (recentIntentions.isEmpty()) 24 else 16))
        val picks = v.flow()
        for (s in SUGGESTIONS) {
            picks.addView(
                v.chip(s) {
                    if (s == OTHER) {
                        input.setText("")
                        input.requestFocus()
                        showKeyboard(input)
                    } else {
                        fill(s)
                    }
                },
            )
        }
        content.addFull(picks, ctx.dp(8))

        var selectedSeconds: Long? = rule.defaultTimerSeconds
        var customSelected = false
        val (customBox, custom) = v.numberInput("Minutes")
        customBox.visibility = View.GONE
        if (rule.timerEnabled) {
            content.addFull(v.label("How long do you plan to be here?"), ctx.dp(24))
            val durations = v.flow()
            val chips = mutableListOf<Button>()
            fun select(chip: Button, seconds: Long?, isCustom: Boolean) {
                chips.forEach { v.setChipSelected(it, it === chip) }
                selectedSeconds = seconds
                customSelected = isCustom
                customBox.visibility = if (isCustom) View.VISIBLE else View.GONE
                if (isCustom) {
                    custom.requestFocus()
                    showKeyboard(custom)
                }
            }
            val options = if (BuildConfig.DEBUG) listOf("2m" to 120L) + DURATIONS else DURATIONS
            var preselected = false
            for ((label, seconds) in options) {
                val chip = v.chip(label, if (seconds == null) "No timer" else "$label timer") { select(it, seconds, false) }
                chips += chip
                durations.addView(chip)
                if (seconds == rule.defaultTimerSeconds && !preselected) {
                    v.setChipSelected(chip, true)
                    preselected = true
                }
            }
            if (rule.customTimerAllowed) {
                val chip = v.chip("Custom", "Custom duration") { select(it, null, true) }
                chips += chip
                durations.addView(chip)
                if (!preselected) {
                    // Default isn't one of the chips (edited rule): show it as the custom value.
                    select(chip, null, true)
                    custom.setText((rule.defaultTimerSeconds / 60).toString())
                    customBox.visibility = View.VISIBLE
                    input.requestFocus()
                }
            }
            content.addFull(durations, ctx.dp(8))
            content.addFull(customBox, ctx.dp(8))
        } else {
            selectedSeconds = null
        }

        val submit = {
            val seconds = if (customSelected) {
                custom.text.toString().toLongOrNull()?.takeIf { it in 1..720 }?.times(60)
            } else {
                selectedSeconds
            }
            if (customSelected && seconds == null) {
                custom.error = "Enter 1–720 minutes"
            } else {
                hideKeyboard(input)
                callbacks.onSubmitIntention(session.id, input.text.toString(), seconds)
            }
        }
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                hideKeyboard(input)
                input.clearFocus()
                true
            } else {
                false
            }
        }

        // Pinned footer: Continue stays above the keyboard (the window pads for the IME inset).
        val footer = v.column()
        footer.addFull(v.primary("Continue") { submit() })
        footer.addFull(v.textButton("Not now") { callbacks.onNotNow(session.id) }, ctx.dp(4))

        setCheckpoint(session.id, Surface.FORM, p, content, footer, crossfade = crossfadeFromPause) {
            callbacks.onNotNow(session.id)
        }
    }

    // --- 3. time's up + extension ------------------------------------------------------------

    fun showExpired(session: Session, extensionAllowed: Boolean) = onMain {
        hidePillNow()
        if (session.state == SessionState.EXTENSION_REQUEST) {
            showExtensionForm(session)
            return@onMain
        }
        if (checkpointSessionId == session.id && checkpointSurface == Surface.EXPIRED) return@onMain
        val p = OverlayPalette.LIGHT
        val v = OverlayViews(ctx, p)
        val content = v.column()
        content.addWrap(v.appIcon(session.packageName, 48), ctx.dp(72))
        content.addFull(v.text(session.appName, 15f, p.muted), ctx.dp(8))
        content.addFull(v.title("Time's up", 34f), ctx.dp(24))
        content.addFull(v.text("“${session.intention}”", 22f, bold = true), ctx.dp(16))
        val planned = session.plannedDurationSeconds
        if (planned != null) {
            val line = if (session.extensionCount > 0) {
                val here = session.elapsedMs(System.currentTimeMillis()) / 1000
                "You planned ${formatMinutes(planned)}. You've been here for ${formatMinutes(here)}."
            } else {
                "You planned ${formatMinutes(planned)}."
            }
            content.addFull(v.text(line, 16f, p.muted), ctx.dp(24))
        }
        content.addFull(v.text("Still doing what you came here to do?", 16f), ctx.dp(8))

        val footer = v.column()
        footer.addFull(v.primary("I'm done") { callbacks.onDone(session.id) })
        if (extensionAllowed) {
            footer.addFull(v.outlined("Need more time") { callbacks.onBeginExtension(session.id) }, ctx.dp(12))
        }
        footer.addFull(v.textButton("Go home") { callbacks.onEnd(session.id) }, ctx.dp(4))
        // Back = "need more time, no request": short snooze, never a silent reset (PRD §15.8).
        setCheckpoint(session.id, Surface.EXPIRED, p, content, footer) { callbacks.onSnoozeExpired(session.id) }
    }

    private fun showExtensionForm(session: Session) {
        if (checkpointSessionId == session.id && checkpointSurface == Surface.EXTENSION) return
        val p = OverlayPalette.LIGHT
        val v = OverlayViews(ctx, p)
        val content = v.column()
        content.addWrap(v.appIcon(session.packageName, 48), ctx.dp(72))
        content.addFull(v.title("Need more time?", 30f), ctx.dp(24))
        content.addFull(v.text("You came here to: “${session.intention}”", 16f, p.muted), ctx.dp(12))
        val (reasonBox, reason) = v.input("What's left? (optional)", MAX_INTENTION_CHARS, withClear = true)
        content.addFull(reasonBox, ctx.dp(32))
        content.addFull(v.label("How much longer?"), ctx.dp(24))
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
        content.addFull(row, ctx.dp(8))

        val footer = v.column()
        footer.addFull(
            v.primary("Continue") {
                hideKeyboard(reason)
                callbacks.onRequestExtension(session.id, reason.text.toString(), extra)
            },
        )
        footer.addFull(v.textButton("Back") { callbacks.onCancelExtension(session.id) }, ctx.dp(4))
        setCheckpoint(session.id, Surface.EXTENSION, p, content, footer) { callbacks.onCancelExtension(session.id) }
    }

    // --- 4. session complete -----------------------------------------------------------------

    fun showCompletion(session: Session) = onMain {
        hidePillNow()
        val p = OverlayPalette.LIGHT
        val v = OverlayViews(ctx, p)
        val content = v.column()
        content.addWrap(v.appIcon(session.packageName, 48), ctx.dp(72))
        content.addFull(v.title("Done for now?", 30f), ctx.dp(24))
        content.addFull(v.text("“${session.intention}”", 20f), ctx.dp(12))

        val actual = session.foregroundMs / 1000
        val planned = session.plannedDurationSeconds
        val summary = if (planned != null) {
            "${shortMinutes(actual)} actual · ${shortMinutes(planned)} planned"
        } else {
            "${shortMinutes(actual)} actual · no timer"
        }
        content.addFull(v.text(summary, 16f), ctx.dp(24))
        if (planned != null && planned > 0) {
            val scale = maxOf(actual, planned).toFloat()
            content.addFull(bar(v, "Actual time", actual, actual / scale, p.primary), ctx.dp(24))
            content.addFull(bar(v, "Planned time", planned, planned / scale, p.muted), ctx.dp(12))
        }

        val footer = v.column()
        footer.addFull(v.primary("Go home") { goHome() })
        // Ends this opening: no pill, no new checkpoint until the user leaves and opens it again.
        footer.addFull(v.outlined("Stay without a session") { hideCheckpointNow() }, ctx.dp(12))
        setCheckpoint(session.id, Surface.COMPLETION, p, content, footer) { hideCheckpointNow() }
    }

    private fun bar(v: OverlayViews, label: String, seconds: Long, fraction: Float, color: Int): View {
        val row = v.row()
        row.contentDescription = "$label ${formatMinutes(seconds)}"
        row.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        val name = v.text(label, 14f, v.p.muted, center = false)
        name.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        row.addView(name, LinearLayout.LayoutParams(ctx.dp(104), ViewGroup.LayoutParams.WRAP_CONTENT))
        val track = FrameLayout(ctx).apply { background = rounded(v.p.border, ctx.dpf(5)) }
        val fill = View(ctx).apply { background = rounded(color, ctx.dpf(5)) }
        track.addView(fill, FrameLayout.LayoutParams(0, ctx.dp(10)))
        track.addOnLayoutChangeListener { t, _, _, _, _, _, _, _, _ ->
            val w = (t.width * fraction.coerceIn(0.02f, 1f)).toInt()
            if (fill.layoutParams.width != w) {
                fill.layoutParams = FrameLayout.LayoutParams(w, ctx.dp(10))
            }
        }
        row.addView(track, LinearLayout.LayoutParams(0, ctx.dp(10), 1f))
        val value = v.text(shortMinutes(seconds), 14f, v.p.text, center = false)
        value.gravity = Gravity.END
        value.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        row.addView(value, LinearLayout.LayoutParams(ctx.dp(64), ViewGroup.LayoutParams.WRAP_CONTENT))
        return row
    }

    fun hideCheckpoint() = onMain { hideCheckpointNow() }

    // --- 2. floating reminder ----------------------------------------------------------------

    fun showReminder(session: Session, warningsEnabled: Boolean) = onMain {
        if (!PermissionChecker.canDrawOverlays(ctx)) return@onMain
        pillSession = session
        pillWarnings = warningsEnabled
        if (pillRoot == null) {
            pillMode = PillMode.COMPACT
            val root = FrameLayout(ctx)
            val params = WindowManager.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                x = 0
                y = ctx.dp(72)
                title = "IntentReminder"
            }
            root.setOnTouchListener(PillTouchHandler(params))
            if (!addWindow(root, params)) return@onMain
            pillRoot = root
            pillParams = params
        }
        renderPill()
        startPillTicker()
    }

    fun hideReminder() = onMain { hidePillNow() }

    fun hideAll() = onMain {
        hideCheckpointNow()
        hidePillNow()
    }

    // Refs into the rendered pill, so the 1 s tick only updates text (never rebuilds under a finger).
    private var pillTimeView: TextView? = null
    private var pillCard: View? = null
    private var pillRenderedWarning = false

    /**
     * Rebuilds the pill for the current state. One state at a time: edge-collapsed, compact, or
     * expanded. Called on show, on tap, and when the warning threshold is crossed; the ticker
     * handles the rest.
     */
    private fun renderPill() {
        val root = pillRoot ?: return
        val session = pillSession ?: return
        val now = System.currentTimeMillis()
        val p = OverlayPalette.LIGHT
        val v = OverlayViews(ctx, p)
        val warning = isWarning(session, now)
        pillRenderedWarning = warning

        root.removeAllViews()

        if (pillMode == PillMode.EDGE) {
            val badge = FrameLayout(ctx).apply {
                background = rounded(if (warning) OverlayPalette.PILL_WARNING_BG else p.background, ctx.dpf(20), p.border, ctx.dp(1))
                elevation = ctx.dpf(2)
                setPadding(ctx.dp(14), ctx.dp(9), ctx.dp(14), ctx.dp(9))
            }
            val timeView = TextView(ctx).apply {
                setTextColor(if (warning) p.amber else p.text)
                textSize = 15f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            }
            badge.addView(timeView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
            root.addView(
                badge,
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    setMargins(ctx.dp(4), ctx.dp(4), ctx.dp(4), ctx.dp(4))
                },
            )
            pillCard = badge
            pillTimeView = timeView
            updatePillTime(now)
            pillParams?.let { params -> runCatching { wm.updateViewLayout(root, params) } }
            return
        }

        val expanded = pillMode == PillMode.EXPANDED
        val card = v.column(Gravity.START).apply {
            background = rounded(
                if (warning) OverlayPalette.PILL_WARNING_BG else p.background,
                ctx.dpf(if (expanded) 20 else 26),
                p.border,
                ctx.dp(1),
            )
            elevation = ctx.dpf(2)
            setPadding(ctx.dp(10), ctx.dp(10), ctx.dp(10), ctx.dp(10))
            minimumHeight = ctx.dp(48)
        }

        val header = v.row()
        header.addView(v.circularAppIcon(session.packageName, 32))
        val texts = v.column(Gravity.START)
        val intention = TextView(ctx).apply {
            setTextColor(p.text)
            textSize = 15f
            if (expanded) maxLines = 3 else setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
            maxWidth = ctx.dp(if (expanded) 220 else 170)
            text = session.intention
        }
        val timeView = TextView(ctx).apply {
            setTextColor(if (warning) p.amber else p.text)
            textSize = if (expanded) 13f else 15f
        }
        if (expanded) {
            texts.addView(intention)
            texts.addView(timeView)
        } else {
            val one = v.row()
            one.addView(intention)
            one.addView(TextView(ctx).apply { text = " · "; setTextColor(p.muted); textSize = 15f })
            one.addView(timeView)
            texts.addView(one)
        }
        if (warning) {
            texts.addView(TextView(ctx).apply { text = "Time's almost up"; setTextColor(p.amber); textSize = 12f })
        }
        header.addView(
            texts,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = ctx.dp(10)
                marginEnd = ctx.dp(4)
            },
        )
        header.addView(ChevronView(ctx, p.muted, up = expanded), LinearLayout.LayoutParams(ctx.dp(28), ctx.dp(28)))
        card.addView(header)

        if (expanded) {
            val actions = v.row()
            actions.addView(
                v.compactPrimary("Done") { callbacks.onDone(session.id) },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            actions.addView(
                v.compactOutlined("End") { callbacks.onEnd(session.id) },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = ctx.dp(8) },
            )
            card.addView(
                actions,
                LinearLayout.LayoutParams(ctx.dp(240), ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = ctx.dp(10) },
            )
        }

        root.addView(
            card,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(ctx.dp(4), ctx.dp(4), ctx.dp(4), ctx.dp(4)) // room for the thin shadow
            },
        )
        pillCard = card
        pillTimeView = timeView
        updatePillTime(now)
        pillParams?.let { params -> runCatching { wm.updateViewLayout(root, params) } }
    }

    private fun isWarning(session: Session, now: Long): Boolean =
        pillWarnings && session.warningLevel(now) != WarningLevel.NONE

    private fun updatePillTime(now: Long) {
        val session = pillSession ?: return
        val remaining = session.remainingMs(now)
        val time = if (remaining != null) formatClock(remaining) else "+" + formatElapsed(session.elapsedMs(now))
        pillTimeView?.text = when (pillMode) {
            PillMode.EDGE, PillMode.COMPACT -> time
            PillMode.EXPANDED -> buildString {
                append(if (remaining != null) "$time remaining" else "${time.drop(1)} elapsed")
                session.plannedDurationSeconds?.let { append(" · ${it / 60} min planned") }
            }
        }
        val spoken = if (remaining != null) "${formatClock(remaining)} remaining" else "${formatElapsed(session.elapsedMs(now))} elapsed"
        pillCard?.contentDescription = "Intention: ${session.intention}. $spoken. " + when (pillMode) {
            PillMode.EDGE -> "Double tap to expand."
            PillMode.EXPANDED -> "Double tap to collapse."
            PillMode.COMPACT -> "Double tap for options. Drag to an edge to minimize."
        }
        // Gentle 30 s pulse via alpha only; skipped with reduced motion.
        pillCard?.alpha = if (pillWarnings && session.warningLevel(now) == WarningLevel.THIRTY_SECONDS &&
            !ctx.reducedMotion() && (now / 1000) % 2 == 0L
        ) {
            0.88f
        } else {
            1f
        }
    }

    private fun startPillTicker() {
        pillTicker?.let(main::removeCallbacks)
        val tick = object : Runnable {
            override fun run() {
                val session = pillSession ?: return
                val now = System.currentTimeMillis()
                if (isWarning(session, now) != pillRenderedWarning) renderPill() else updatePillTime(now)
                main.postDelayed(this, 1_000)
            }
        }
        pillTicker = tick
        main.postDelayed(tick, 1_000)
    }

    // --- navigation --------------------------------------------------------------------------

    /**
     * "Go home" as far as Android allows a third-party app: bring the launcher to front. We never
     * claim the target app was closed (PRD §15.10). Background-activity-start is permitted because
     * we hold SYSTEM_ALERT_WINDOW.
     */
    fun goHome() = onMain {
        hideCheckpointNow()
        hidePillNow()
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

    /**
     * Full-screen checkpoint window: scrollable [content] with an optional pinned [footer].
     * Returns false if the window could not be shown; the engine keeps its state either way.
     */
    private fun setCheckpoint(
        sessionId: String,
        surface: Surface,
        p: OverlayPalette,
        content: LinearLayout,
        footer: LinearLayout?,
        crossfade: Boolean = false,
        onBack: () -> Unit,
    ): Boolean {
        if (!PermissionChecker.canDrawOverlays(ctx)) return false
        // Never draw over the lock screen / credential UI (PRD §39).
        if (ctx.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true) return false

        val reduced = ctx.reducedMotion()
        val fadingOutRoot = if (crossfade && !reduced) checkpointRoot else null
        if (fadingOutRoot != null) {
            // Stop the pause's own animations/timers but leave its window up to fade out.
            pauseRunnable?.let(main::removeCallbacks)
            pauseRunnable = null
            pauseAnimator?.cancel()
            pauseAnimator = null
            waitDotsRunnable?.let(main::removeCallbacks)
            waitDotsRunnable = null
            checkpointRoot = null
            checkpointSessionId = null
            checkpointSurface = null
        } else {
            hideCheckpointNow()
        }
        val root = BackAwareFrame(ctx, onBack).apply {
            setBackgroundColor(p.background)
            // Tapjacking guard: ignore taps while another app's window obscures ours.
            filterTouchesWhenObscured = true
            isFocusableInTouchMode = true
        }
        val maxWidth = minOf(ctx.resources.displayMetrics.widthPixels, ctx.dp(520))
        val column = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        val scroll = ScrollView(ctx).apply {
            isFillViewport = true
            clipToPadding = false
        }
        content.setPadding(ctx.dp(20), ctx.dp(8), ctx.dp(20), ctx.dp(24))
        scroll.addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        column.addView(scroll, LinearLayout.LayoutParams(maxWidth, 0, 1f))
        if (footer != null) {
            footer.setPadding(ctx.dp(20), ctx.dp(8), ctx.dp(20), ctx.dp(16))
            column.addView(footer, LinearLayout.LayoutParams(maxWidth, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        root.addView(column, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        applySystemInsets(root, column)

        val params = WindowManager.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // Focusable (no FLAG_NOT_FOCUSABLE) so the text field gets the IME and back reaches us.
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            // TRANSLUCENT (not OPAQUE) so the cross-fade below can animate real alpha; the content
            // paints a full-bleed background itself, so this looks identical when alpha is 1.
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
        if (fadingOutRoot != null) {
            // New content starts faded/offset, old content still visible behind it.
            root.alpha = 0f
            column.translationY = ctx.dpf(14)
        }
        if (!addWindow(root, params)) {
            if (fadingOutRoot != null) removeWindow(fadingOutRoot) // don't strand the old window
            return false
        }
        root.requestFocus()
        checkpointRoot = root
        checkpointSessionId = sessionId
        checkpointSurface = surface
        if (fadingOutRoot != null) {
            root.animate().alpha(1f).setDuration(CROSSFADE_MS).start()
            column.animate().translationY(0f).setDuration(CROSSFADE_MS).start()
            fadingOutRoot.animate()
                .alpha(0f)
                .translationY(-ctx.dpf(14))
                .setDuration(CROSSFADE_MS)
                .withEndAction { removeWindow(fadingOutRoot) }
                .start()
        }
        return true
    }

    /**
     * Pads for status/nav bars, cutouts and the IME so the pinned footer is never covered by the
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
        } catch (_: IllegalArgumentException) {
            // Already detached (e.g. system removed it when the permission was revoked).
        }
    }

    private fun hideCheckpointNow() {
        pauseRunnable?.let(main::removeCallbacks)
        pauseRunnable = null
        pauseAnimator?.cancel()
        pauseAnimator = null
        waitDotsRunnable?.let(main::removeCallbacks)
        waitDotsRunnable = null
        checkpointRoot?.let(::hideKeyboard)
        removeWindow(checkpointRoot)
        checkpointRoot = null
        checkpointSessionId = null
        checkpointSurface = null
    }

    private fun hidePillNow() {
        pillTicker?.let(main::removeCallbacks)
        pillTicker = null
        removeWindow(pillRoot)
        pillRoot = null
        pillParams = null
        pillSession = null
        pillMode = PillMode.COMPACT
        pillTimeView = null
        pillCard = null
    }

    private fun showKeyboard(view: View) {
        view.post { ctx.getSystemService(InputMethodManager::class.java)?.showSoftInput(view, 0) }
    }

    private fun hideKeyboard(view: View) {
        ctx.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(view.windowToken, 0)
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    /**
     * Drag the pill anywhere; drag it to a side edge and it docks there, collapsed into a small
     * AssistiveTouch-style time badge (never dismissed — the session keeps running, PRD §15.4).
     * A tap with no movement expands the edge badge, or toggles compact/expanded otherwise.
     */
    private inner class PillTouchHandler(private val params: WindowManager.LayoutParams) : View.OnTouchListener {
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
                        runCatching { wm.updateViewLayout(v, params) }
                    }
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragging) {
                        v.performClick()
                        if (pillMode == PillMode.EDGE) {
                            expandFromEdge()
                        } else {
                            pillMode = if (pillMode == PillMode.EXPANDED) PillMode.COMPACT else PillMode.EXPANDED
                            renderPill()
                        }
                        return true
                    }
                    // Gravity is CENTER_HORIZONTAL, so x is the offset from screen centre.
                    val half = ctx.resources.displayMetrics.widthPixels / 2
                    if (abs(params.x) > half - v.width / 3) {
                        collapseToEdge(dockRight = params.x >= 0)
                    }
                    return true
                }
                MotionEvent.ACTION_CANCEL -> return true
            }
            return false
        }
    }

    /** Docks the pill against whichever edge it was dragged toward, collapsed to a time badge. */
    private fun collapseToEdge(dockRight: Boolean) {
        val root = pillRoot ?: return
        val params = pillParams ?: return
        pillMode = PillMode.EDGE
        renderPill()
        val half = ctx.resources.displayMetrics.widthPixels / 2
        params.x = if (dockRight) half - ctx.dp(28) else -(half - ctx.dp(28))
        runCatching { wm.updateViewLayout(root, params) }
    }

    /** Tapping the collapsed edge badge smoothly brings back the full popup with its actions. */
    private fun expandFromEdge() {
        val root = pillRoot ?: return
        val params = pillParams ?: return
        pillMode = PillMode.EXPANDED
        renderPill()
        if (ctx.reducedMotion()) {
            params.x = 0
            runCatching { wm.updateViewLayout(root, params) }
            return
        }
        val startX = params.x
        ValueAnimator.ofInt(startX, 0).apply {
            duration = CROSSFADE_MS
            addUpdateListener {
                params.x = it.animatedValue as Int
                runCatching { wm.updateViewLayout(root, params) }
            }
            start()
        }
    }

    private companion object {
        const val REDUCED_MOTION_PAUSE_MS = 300L
        const val CROSSFADE_MS = 260L
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

        val DURATIONS: List<Pair<String, Long?>> = listOf(
            "5m" to 300L,
            "10m" to 600L,
            "15m" to 900L,
            "30m" to 1_800L,
            "No timer" to null,
        )

        val EXTENSIONS: List<Pair<String, Long>> = listOf("5m" to 300L, "10m" to 600L, "15m" to 900L)
    }
}
