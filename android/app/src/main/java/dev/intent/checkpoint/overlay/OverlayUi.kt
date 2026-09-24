package dev.intent.checkpoint.overlay

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Calm, non-judgmental palette (PRD §21): no red alarms, no padlocks/shields. Follows the system
 * light/dark setting.
 */
internal class OverlayPalette(context: Context) {
    private val dark = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
        Configuration.UI_MODE_NIGHT_YES

    val scrim = if (dark) 0xF01A1917.toInt() else 0xF0F7F5F2.toInt()
    val surface = if (dark) 0xFF262422.toInt() else 0xFFFFFFFF.toInt()
    val text = if (dark) 0xFFECEAE6.toInt() else 0xFF2B2A28.toInt()
    val muted = if (dark) 0xFFA8A39C.toInt() else 0xFF6F6B66.toInt()
    val accent = if (dark) 0xFF8FB3A0.toInt() else 0xFF5B7A6A.toInt()
    val onAccent = if (dark) 0xFF10201A.toInt() else Color.WHITE
    val chip = if (dark) 0xFF34312E.toInt() else 0xFFECE8E2.toInt()
    val chipSelected = accent
    /** Soft amber for the timer warnings — attention without alarm. */
    val warn = if (dark) 0xFFD9B27A.toInt() else 0xFFB7894A.toInt()
}

internal fun Context.dp(v: Int): Int =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

/** OS "remove animations" setting; the pause collapses to a short fade (PRD §21). */
internal fun Context.reducedMotion(): Boolean =
    Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

internal fun rounded(color: Int, radiusPx: Float) = GradientDrawable().apply {
    setColor(color)
    cornerRadius = radiusPx
}

/** Root for focusable checkpoint windows: routes the system back key to [onBack]. */
internal class BackAwareFrame(context: Context, private val onBack: () -> Unit) : FrameLayout(context) {
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) onBack()
            return true
        }
        return super.dispatchKeyEvent(event)
    }
}

/** Minimal wrapping row for chips; avoids pulling a layout library into the overlay. */
internal class FlowLayout(context: Context, private val gapPx: Int) : ViewGroup(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val maxWidth = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
        var x = 0
        var y = 0
        var rowHeight = 0
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == GONE) continue
            measureChild(child, MeasureSpec.makeMeasureSpec(maxWidth, MeasureSpec.AT_MOST), heightMeasureSpec)
            if (x > 0 && x + child.measuredWidth > maxWidth) {
                x = 0
                y += rowHeight + gapPx
                rowHeight = 0
            }
            x += child.measuredWidth + gapPx
            rowHeight = maxOf(rowHeight, child.measuredHeight)
        }
        setMeasuredDimension(
            MeasureSpec.getSize(widthMeasureSpec),
            resolveSize(y + rowHeight + paddingTop + paddingBottom, heightMeasureSpec),
        )
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val maxWidth = r - l - paddingLeft - paddingRight
        var x = 0
        var y = 0
        var rowHeight = 0
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == GONE) continue
            if (x > 0 && x + child.measuredWidth > maxWidth) {
                x = 0
                y += rowHeight + gapPx
                rowHeight = 0
            }
            child.layout(
                paddingLeft + x,
                paddingTop + y,
                paddingLeft + x + child.measuredWidth,
                paddingTop + y + child.measuredHeight,
            )
            x += child.measuredWidth + gapPx
            rowHeight = maxOf(rowHeight, child.measuredHeight)
        }
    }
}

/** Small factory so every overlay element gets 48dp targets and TalkBack labels (PRD §21–22). */
internal class OverlayViews(private val context: Context, val palette: OverlayPalette) {
    private val minTouch = context.dp(48)

    fun column(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
    }

    fun title(text: String): TextView = TextView(context).apply {
        this.text = text
        setTextColor(palette.text)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 34f)
        typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        gravity = Gravity.CENTER
        isFocusable = true // TalkBack reads the heading first
        accessibilityHeading()
    }

    fun body(text: String, sizeSp: Float = 18f, color: Int = palette.text): TextView = TextView(context).apply {
        this.text = text
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        gravity = Gravity.CENTER
    }

    fun label(text: String): TextView = body(text, 14f, palette.muted)

    fun input(hint: String, maxChars: Int): EditText = EditText(context).apply {
        this.hint = hint
        setHintTextColor(palette.muted)
        setTextColor(palette.text)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        isSingleLine = true
        imeOptions = EditorInfo.IME_ACTION_DONE
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        // Practical cap, not a validation rule (PRD §15.2).
        filters = arrayOf(android.text.InputFilter.LengthFilter(maxChars))
        minHeight = minTouch
        background = rounded(palette.surface, context.dp(14).toFloat())
        setPadding(context.dp(16), context.dp(12), context.dp(16), context.dp(12))
        contentDescription = hint
    }

    fun numberInput(hint: String): EditText = input(hint, 4).apply {
        inputType = InputType.TYPE_CLASS_NUMBER
    }

    fun chip(text: String, description: String = text, onClick: (Button) -> Unit): Button = Button(context).apply {
        this.text = text
        isAllCaps = false
        setTextColor(palette.text)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        minHeight = minTouch
        minimumHeight = minTouch
        minWidth = minTouch
        minimumWidth = minTouch
        stateListAnimator = null
        background = rounded(palette.chip, context.dp(24).toFloat())
        setPadding(context.dp(16), 0, context.dp(16), 0)
        contentDescription = description
        setOnClickListener { onClick(this) }
    }

    fun setChipSelected(chip: Button, selected: Boolean) {
        chip.isSelected = selected
        chip.background = rounded(if (selected) palette.chipSelected else palette.chip, context.dp(24).toFloat())
        chip.setTextColor(if (selected) palette.onAccent else palette.text)
    }

    fun primary(text: String, onClick: () -> Unit): Button = Button(context).apply {
        this.text = text
        isAllCaps = false
        setTextColor(palette.onAccent)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
        minHeight = context.dp(56)
        minimumHeight = context.dp(56)
        stateListAnimator = null
        background = rounded(palette.accent, context.dp(28).toFloat())
        setOnClickListener { onClick() }
    }

    fun secondary(text: String, onClick: () -> Unit): Button = Button(context).apply {
        this.text = text
        isAllCaps = false
        setTextColor(palette.text)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
        minHeight = context.dp(56)
        minimumHeight = context.dp(56)
        stateListAnimator = null
        background = rounded(palette.chip, context.dp(28).toFloat())
        setOnClickListener { onClick() }
    }

    fun textButton(text: String, onClick: () -> Unit): Button = Button(context).apply {
        this.text = text
        isAllCaps = false
        setTextColor(palette.muted)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        minHeight = minTouch
        minimumHeight = minTouch
        stateListAnimator = null
        setBackgroundColor(Color.TRANSPARENT)
        setOnClickListener { onClick() }
    }

    fun flow(): FlowLayout = FlowLayout(context, context.dp(8))

    fun spacer(heightDp: Int): View = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(1, context.dp(heightDp))
    }

    private fun TextView.accessibilityHeading() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) isAccessibilityHeading = true
    }
}

internal fun LinearLayout.addFullWidth(view: View, topMarginPx: Int = 0) {
    addView(
        view,
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = topMarginPx
        },
    )
}

internal fun formatClock(ms: Long): String {
    val total = (ms + 999) / 1000 // round up so "00:00" only shows at true expiry
    return "%02d:%02d".format(total / 60, total % 60)
}

internal fun formatMinutes(seconds: Long): String {
    val m = (seconds + 59) / 60
    return if (m == 1L) "1 minute" else "$m minutes"
}
