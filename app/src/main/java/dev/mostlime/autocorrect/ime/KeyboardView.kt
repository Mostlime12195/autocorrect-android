package dev.mostlime.autocorrect.ime

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import dev.mostlime.autocorrect.engine.Touch
import kotlin.math.min

enum class Shift { OFF, ON, LOCK }

/** Suggestion strip + QWERTY keys, drawn on one canvas. Reports key presses with the touch in model units. */
@SuppressLint("ViewConstructor")
class KeyboardView(ctx: Context, private val listener: Listener) : View(ctx) {
    interface Listener {
        fun onKey(key: KeyDef, touch: Touch)
        fun onSuggestion(index: Int)
        fun onSwitchIme()
    }

    var layer: Layer = KeyboardLayout.LETTERS
        set(v) { field = v; invalidate() }
    var shift = Shift.OFF
        set(v) { field = v; invalidate() }
    var enterLabel = "⏎"
        set(v) { field = v; invalidate() }
    var haptics = true

    /** up to 3 suggestions; `bold` = index drawn emphasised */
    private var suggestions: List<String> = emptyList()
    private var bold = -1
    var status: String = ""
        set(v) { field = v; invalidate() }

    fun setSuggestions(s: List<String>, boldIndex: Int) {
        suggestions = s.take(3); bold = boldIndex; invalidate()
    }

    private val dp = resources.displayMetrics.density
    private val stripH = 44 * dp
    private var keyW = 0f
    private var rowH = 0f
    private var bottomInset = 0
    private val dark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    private val bg = if (dark) 0xFF1B1B1F.toInt() else 0xFFE8EAED.toInt()
    private val keyBg = if (dark) 0xFF3C3C43.toInt() else 0xFFFFFFFF.toInt()
    private val modBg = if (dark) 0xFF2A2A30.toInt() else 0xFFCDD1D6.toInt()
    private val pressedBg = if (dark) 0xFF5A5A63.toInt() else 0xFFB0B5BC.toInt()
    private val accent = 0xFF2E7D32.toInt()
    private val fg = if (dark) 0xFFF1F1F1.toInt() else 0xFF202124.toInt()
    private val dim = if (dark) 0xFF9AA0A6.toInt() else 0xFF5F6368.toInt()

    private val keyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; color = fg }
    private val stripPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; color = fg; textSize = 17 * dp }
    private val statusPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.RIGHT; color = dim; textSize = 10 * dp }
    private val rect = RectF()

    private val pressed = HashMap<Int, KeyDef>()   // pointer id -> key
    private val repeatHandler = Handler(Looper.getMainLooper())
    private var repeatKey: KeyDef? = null
    private val repeater = object : Runnable {
        override fun run() {
            val k = repeatKey ?: return
            listener.onKey(k, Touch.APOS)
            repeatHandler.postDelayed(this, REPEAT_MS)
        }
    }
    private var symDownAt = 0L

    init {
        setBackgroundColor(bg)
        setOnApplyWindowInsetsListener { _, insets ->
            val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) insets.getInsets(WindowInsets.Type.navigationBars()).bottom
            else @Suppress("DEPRECATION") insets.systemWindowInsetBottom
            if (b != bottomInset) { bottomInset = b; requestLayout() }
            insets
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = View.MeasureSpec.getSize(widthMeasureSpec)
        keyW = w / 10f
        rowH = (keyW * 1.25f).coerceIn(46 * dp, 58 * dp)
        setMeasuredDimension(w, (stripH + 4 * rowH + 6 * dp).toInt() + bottomInset)
    }

    private fun keyRect(k: KeyDef, out: RectF) {
        val pad = 3 * dp
        out.set(k.x * keyW + pad, stripH + k.row * rowH + pad, (k.x + k.w) * keyW - pad, stripH + (k.row + 1) * rowH - pad)
    }

    override fun onDraw(canvas: Canvas) {
        // strip
        val cellW = width / 3f
        val order = intArrayOf(1, 0, 2)   // best suggestion in the middle
        for ((cell, si) in order.withIndex()) {
            val s = suggestions.getOrNull(si) ?: continue
            stripPaint.typeface = if (si == bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            val label = if (stripPaint.measureText(s) > cellW - 8 * dp) s.take(14) + "…" else s
            canvas.drawText(label, cellW * cell + cellW / 2, stripH / 2 + 6 * dp, stripPaint)
        }
        if (status.isNotEmpty()) canvas.drawText(status, width - 4 * dp, 11 * dp, statusPaint)
        // keys
        val held = pressed.values.toSet()
        for (k in layer.keys) {
            keyRect(k, rect)
            keyPaint.color = when {
                k in held -> pressedBg
                k.type == KeyType.ENTER -> accent
                k.type == KeyType.SHIFT && shift != Shift.OFF -> if (shift == Shift.LOCK) accent else pressedBg
                k.type == KeyType.CHAR || k.type == KeyType.SPACE -> keyBg
                else -> modBg
            }
            canvas.drawRoundRect(rect, 6 * dp, 6 * dp, keyPaint)
            val label = when {
                k.type == KeyType.ENTER -> enterLabel
                k.type == KeyType.SPACE -> ""
                k.type == KeyType.CHAR && shift != Shift.OFF -> k.label.uppercase()
                else -> k.label
            }
            textPaint.color = if (k.type == KeyType.ENTER) 0xFFFFFFFF.toInt() else fg
            textPaint.textSize = if (label.length > 1) 14 * dp else 21 * dp
            canvas.drawText(label, rect.centerX(), rect.centerY() + textPaint.textSize / 3, textPaint)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                val x = e.getX(i)
                val y = e.getY(i)
                if (y < stripH) {
                    if (e.actionMasked == MotionEvent.ACTION_DOWN) stripDown = x
                    return true
                }
                val ux = x / keyW
                val uy = (y - stripH) / rowH
                val k = layer.keyAt(ux, min(uy, 3.99f)) ?: return true
                pressed[e.getPointerId(i)] = k
                if (haptics) performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                // text keys fire on press (keeps order when presses overlap); ?123 fires on release so a long press
                // can open the keyboard switcher instead
                if (k.type == KeyType.TO_SYMBOLS) symDownAt = e.eventTime
                else listener.onKey(k, KeyboardLayout.modelTouch(ux, uy, k))
                if (k.type == KeyType.BACKSPACE) { repeatKey = k; repeatHandler.postDelayed(repeater, REPEAT_START_MS) }
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                val i = e.actionIndex
                val k = pressed.remove(e.getPointerId(i))
                if (k?.type == KeyType.BACKSPACE) { repeatKey = null; repeatHandler.removeCallbacks(repeater) }
                if (k?.type == KeyType.TO_SYMBOLS && e.actionMasked != MotionEvent.ACTION_CANCEL) {
                    if (e.eventTime - symDownAt > LONG_PRESS_MS) listener.onSwitchIme() else listener.onKey(k, Touch.APOS)
                }
                if (k == null && e.actionMasked == MotionEvent.ACTION_UP && e.getY(i) < stripH && stripDown >= 0) {
                    val cell = (e.getX(i) / (width / 3f)).toInt().coerceIn(0, 2)
                    val si = intArrayOf(1, 0, 2)[cell]
                    if (si < suggestions.size) listener.onSuggestion(si)
                }
                if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) {
                    pressed.clear(); stripDown = -1f; repeatKey = null; repeatHandler.removeCallbacks(repeater)
                }
                invalidate()
            }
        }
        return true
    }

    private var stripDown = -1f

    companion object {
        const val REPEAT_START_MS = 400L
        const val REPEAT_MS = 50L
        const val LONG_PRESS_MS = 500L
    }
}
