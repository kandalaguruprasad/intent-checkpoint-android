package dev.intent.checkpoint.overlay

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
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
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import dev.intent.checkpoint.monitoring.AppIcons

/**
 * Design tokens for the native overlays. Mirrors src/theme/tokens.ts so RN screens and overlays
 * read as one product. Solid surfaces only: no blur, no heavy elevation (design brief).
 */
internal class OverlayPalette private constructor(val dark: Boolean) {
    val background = if (dark) 0xFF151B19.toInt() else 0xFFF7F7F4.toInt()
    val surface = if (dark) 0xFF1F2724.toInt() else 0xFFFFFFFF.toInt()
    val text = if (dark) 0xFFF2F5F3.toInt() else 0xFF1B2420.toInt()
    val muted = if (dark) 0xFFA7B3AD.toInt() else 0xFF5E6B64.toInt()
    val border = if (dark) 0xFF34403A.toInt() else 0xFFDDE3DD.toInt()
    val primary = if (dark) 0xFF8DB09C.toInt() else 0xFF507764.toInt()
    val onPrimary = if (dark) 0xFF10201A.toInt() else Color.WHITE
    val amber = if (dark) 0xFFE0A560.toInt() else 0xFFB67932.toInt()

    companion object {
        /** Follows the system setting: the intention checkpoint. */
        fun system(context: Context) = OverlayPalette(
            (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES,
        )

        /** Always dark: Time's up, extension, completion, and the reminder pill (reads over any app). */
        val DARK = OverlayPalette(true)

        /** Amber-tinted dark surface for the pill's timer warnings. */
        const val PILL_WARNING_BG = 0xFF2B2217.toInt()
    }
}

internal fun Context.dp(v: Int): Int =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

internal fun Context.dpf(v: Int): Float = dp(v).toFloat()

/** OS "remove animations" setting; the pause collapses to a short fade (PRD §21). */
internal fun Context.reducedMotion(): Boolean =
    Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

internal fun rounded(color: Int, radiusPx: Float, strokeColor: Int? = null, strokePx: Int = 0) =
    GradientDrawable().apply {
        setColor(color)
        cornerRadius = radiusPx
        if (strokeColor != null) setStroke(strokePx, strokeColor)
    }

/** Solid background with a pressed ripple; keeps touch feedback without extra animation. */
internal fun pressable(background: Drawable, rippleColor: Int): Drawable =
    RippleDrawable(ColorStateList.valueOf(rippleColor), background, null)

/** Root for focusable overlay windows: routes the system back key to [onBack]. */
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
            child.layout(paddingLeft + x, paddingTop + y, paddingLeft + x + child.measuredWidth, paddingTop + y + child.measuredHeight)
            x += child.measuredWidth + gapPx
            rowHeight = maxOf(rowHeight, child.measuredHeight)
        }
    }
}

/** Small chevron drawn in code (no icon font or vector resources in the overlay). */
internal class ChevronView(context: Context, color: Int, var up: Boolean) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.STROKE
        strokeWidth = context.dpf(2)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val dx = w * 0.28f
        val dy = h * 0.14f
        path.reset()
        if (up) {
            path.moveTo(w / 2 - dx, h / 2 + dy)
            path.lineTo(w / 2, h / 2 - dy)
            path.lineTo(w / 2 + dx, h / 2 + dy)
        } else {
            path.moveTo(w / 2 - dx, h / 2 - dy)
            path.lineTo(w / 2, h / 2 + dy)
            path.lineTo(w / 2 + dx, h / 2 - dy)
        }
        canvas.drawPath(path, paint)
    }
}

/** View factory: 8 dp grid, ≥48 dp targets, sp text (font scaling), TalkBack labels. */
internal class OverlayViews(private val context: Context, val p: OverlayPalette) {
    private val minTouch = context.dp(48)

    fun column(gravity: Int = Gravity.CENTER_HORIZONTAL): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        this.gravity = gravity
    }

    fun row(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    fun appIcon(packageName: String, sizeDp: Int): View {
        val drawable = AppIcons.load(context, packageName)
        return ImageView(context).apply {
            if (drawable != null) setImageDrawable(drawable) else setImageDrawable(rounded(p.border, context.dpf(sizeDp / 4)))
            scaleType = ImageView.ScaleType.FIT_CENTER
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            layoutParams = LinearLayout.LayoutParams(context.dp(sizeDp), context.dp(sizeDp))
        }
    }

    fun title(text: String, sizeSp: Float = 26f, color: Int = p.text): TextView = TextView(context).apply {
        this.text = text
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        gravity = Gravity.CENTER
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) isAccessibilityHeading = true
    }

    fun text(text: String, sizeSp: Float = 16f, color: Int = p.text, center: Boolean = true, bold: Boolean = false): TextView =
        TextView(context).apply {
            this.text = text
            setTextColor(color)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
            if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = if (center) Gravity.CENTER else Gravity.START
        }

    fun label(text: String): TextView = text(text, 14f, p.muted, center = false, bold = true)

    fun input(hint: String, maxChars: Int, withClear: Boolean): Pair<View, EditText> {
        val field = EditText(context).apply {
            this.hint = hint
            setHintTextColor(p.muted)
            setTextColor(p.text)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_DONE
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            // Practical cap, not a validation rule (PRD §15.2).
            filters = arrayOf(android.text.InputFilter.LengthFilter(maxChars))
            background = null
            minHeight = minTouch
            setPadding(context.dp(16), 0, context.dp(8), 0)
            contentDescription = hint
        }
        val box = row().apply {
            background = rounded(p.surface, context.dpf(14), p.border, context.dp(1))
            minimumHeight = context.dp(52)
            addView(field, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        if (withClear) {
            val clear = TextView(context).apply {
                text = "✕"
                setTextColor(p.muted)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                gravity = Gravity.CENTER
                contentDescription = "Clear"
                visibility = View.GONE
                setOnClickListener { field.setText("") }
            }
            box.addView(clear, LinearLayout.LayoutParams(minTouch, minTouch))
            field.addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun afterTextChanged(s: android.text.Editable?) {
                    clear.visibility = if (s.isNullOrEmpty()) View.GONE else View.VISIBLE
                }
            })
        }
        return box to field
    }

    fun numberInput(hint: String): Pair<View, EditText> = input(hint, 3, withClear = false).also {
        it.second.inputType = InputType.TYPE_CLASS_NUMBER
    }

    fun chip(text: String, description: String = text, onClick: (Button) -> Unit): Button = Button(context).apply {
        this.text = text
        isAllCaps = false
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        minHeight = minTouch
        minimumHeight = minTouch
        minWidth = context.dp(64)
        minimumWidth = context.dp(64)
        stateListAnimator = null
        setPadding(context.dp(16), 0, context.dp(16), 0)
        contentDescription = description
        setChipSelected(this, false)
        setOnClickListener { onClick(this) }
    }

    fun setChipSelected(chip: Button, selected: Boolean) {
        chip.isSelected = selected
        val bg = if (selected) rounded(p.primary, context.dpf(24)) else rounded(p.surface, context.dpf(24), p.border, context.dp(1))
        chip.background = pressable(bg, p.border)
        chip.setTextColor(if (selected) p.onPrimary else p.text)
    }

    fun primary(text: String, onClick: () -> Unit): Button = button(text, filled = true, outlined = false, onClick)

    fun outlined(text: String, onClick: () -> Unit): Button = button(text, filled = false, outlined = true, onClick)

    fun textButton(text: String, onClick: () -> Unit): Button = button(text, filled = false, outlined = false, onClick)

    private fun button(text: String, filled: Boolean, outlined: Boolean, onClick: () -> Unit): Button =
        Button(context).apply {
            this.text = text
            isAllCaps = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            val h = if (filled || outlined) context.dp(52) else minTouch
            minHeight = h
            minimumHeight = h
            stateListAnimator = null
            val bg = when {
                filled -> rounded(p.primary, context.dpf(26))
                outlined -> rounded(Color.TRANSPARENT, context.dpf(26), p.text, context.dp(1))
                else -> rounded(Color.TRANSPARENT, context.dpf(26))
            }
            background = pressable(bg, p.border)
            setTextColor(if (filled) p.onPrimary else p.text)
            setOnClickListener { onClick() }
        }

    fun flow(): FlowLayout = FlowLayout(context, context.dp(8))
}

internal fun LinearLayout.addFull(view: View, topMarginPx: Int = 0) {
    addView(
        view,
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = topMarginPx
        },
    )
}

internal fun LinearLayout.addWrap(view: View, topMarginPx: Int = 0) {
    val lp = (view.layoutParams as? LinearLayout.LayoutParams)
        ?: LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    lp.topMargin = topMarginPx
    addView(view, lp)
}

internal fun formatClock(ms: Long): String {
    val total = (ms + 999) / 1000 // round up so "00:00" only shows at true expiry
    return "%02d:%02d".format(total / 60, total % 60)
}

internal fun formatElapsed(ms: Long): String {
    val total = ms / 1000
    return "%02d:%02d".format(total / 60, total % 60)
}

internal fun formatMinutes(seconds: Long): String {
    val m = (seconds + 59) / 60
    return if (m == 1L) "1 minute" else "$m minutes"
}

/** Whole minutes for the completion card; under a minute is shown as "<1". */
internal fun shortMinutes(seconds: Long): String = if (seconds < 60) "<1 min" else "${seconds / 60} min"
