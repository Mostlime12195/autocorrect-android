package dev.mostlime.autocorrect.ime

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import androidx.core.content.ContextCompat
import dev.mostlime.autocorrect.R
import dev.mostlime.autocorrect.engine.Touch
import kotlin.math.min

enum class Shift { OFF, ON, LOCK }

/** Colors: Material You (system dynamic colors) on Android 12+, a neutral light/dark palette before that. */
class Palette(ctx: Context) {
    val dark = (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    private val dyn = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    private val res = ctx
    private fun pick(darkId: Int, lightId: Int, darkFallback: Int, lightFallback: Int): Int =
        if (dyn) res.getColor(if (dark) darkId else lightId) else if (dark) darkFallback else lightFallback

    val bg = pick(android.R.color.system_neutral1_900, android.R.color.system_neutral1_100, 0xFF1B1B1F.toInt(), 0xFFE8EAED.toInt())
    val key = pick(android.R.color.system_neutral1_800, android.R.color.system_neutral1_10, 0xFF3C3C43.toInt(), 0xFFFFFFFF.toInt())
    val mod = pick(android.R.color.system_neutral2_700, android.R.color.system_neutral2_200, 0xFF2A2A30.toInt(), 0xFFCDD1D6.toInt())
    val pressed = pick(android.R.color.system_neutral2_600, android.R.color.system_neutral2_300, 0xFF5A5A63.toInt(), 0xFFB0B5BC.toInt())
    val fg = pick(android.R.color.system_neutral1_50, android.R.color.system_neutral1_900, 0xFFF1F1F1.toInt(), 0xFF202124.toInt())
    val dim = pick(android.R.color.system_neutral2_200, android.R.color.system_neutral2_600, 0xFF9AA0A6.toInt(), 0xFF5F6368.toInt())
    val accent = pick(android.R.color.system_accent1_200, android.R.color.system_accent1_600, 0xFF81C995.toInt(), 0xFF2E7D32.toInt())
    val onAccent = pick(android.R.color.system_accent1_800, android.R.color.system_accent1_0, 0xFF0B3D1B.toInt(), 0xFFFFFFFF.toInt())
    val accentSoft = pick(android.R.color.system_accent2_800, android.R.color.system_accent2_100, 0xFF33433A.toInt(), 0xFFDCEBDD.toInt())
    val popup = pick(android.R.color.system_neutral1_700, android.R.color.system_neutral1_0, 0xFF4A4A52.toInt(), 0xFFFFFFFF.toInt())
}

/**
 * Suggestion strip + keys on one canvas. Reports key presses with the touch in model units.
 * Micro-interactions: key preview bubble, press scale, long-press alternates (digits on the top row, accents, punctuation),
 * accelerating backspace repeat, strip fade-in, a flash + undo chip when a correction lands.
 */
@SuppressLint("ViewConstructor")
class KeyboardView(ctx: Context, private val listener: Listener) : View(ctx) {
    interface Listener {
        fun onKey(key: KeyDef, touch: Touch)
        fun onAlternate(text: String)
        fun onSuggestion(index: Int)
        fun onSwitchIme()
        fun onSettings()
        fun onClipboard() {}
        fun onEmoji() {}
        fun onPasteChip() {}
    }

    /** text of a recently copied clip, offered as a chip in an empty strip (null = none) */
    var clipChip: String? = null
        set(v) { field = v; invalidate() }
    val stripHeight get() = stripH
    val bottomInsetPx get() = bottomInset

    var layer: Layer = KeyboardLayout.LETTERS
        set(v) { field = v; invalidate() }
    var shift = Shift.OFF
        set(v) { field = v; invalidate() }
    /** editor action label from the IME (Go / Search / Send / Next / Done / Prev / ⏎) */
    var enterLabel = "⏎"
        set(v) { field = v; invalidate() }
    var haptics = true
    /** 0 light, 1 medium, 2 strong */
    var hapticStrength = 1

    /**
     * Key vibration through the Vibrator service. performHapticFeedback(KEYBOARD_TAP) follows the system's touch-feedback
     * / keyboard-vibration setting, which is off on many phones, so the app's own switch did nothing there.
     */
    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
        ctx.getSystemService(VibratorManager::class.java)?.defaultVibrator
    else @Suppress("DEPRECATION") ctx.getSystemService(Vibrator::class.java)

    private fun buzz(strong: Boolean = false) {
        if (!haptics) return
        val v = vibrator
        if (v == null || !v.hasVibrator()) {
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP, HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING)
            return
        }
        val level = if (strong) 2 else hapticStrength
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                v.vibrate(VibrationEffect.createPredefined(when (level) {
                    0 -> VibrationEffect.EFFECT_TICK
                    1 -> VibrationEffect.EFFECT_CLICK
                    else -> VibrationEffect.EFFECT_HEAVY_CLICK
                }))
            } else {
                v.vibrate(VibrationEffect.createOneShot(when (level) { 0 -> 8L; 1 -> 15L; else -> 25L }, VibrationEffect.DEFAULT_AMPLITUDE))
            }
        } catch (_: Exception) {
        }
    }

    private var suggestions: List<String> = emptyList()
    private var bold = -1
    private var undoIdx = -1
    private var stripChangedAt = 0L
    private var flashAt = 0L
    var status: String = ""
        set(v) { field = v; invalidate() }

    fun setSuggestions(s: List<String>, boldIndex: Int, undoIndex: Int = -1) {
        if (s != suggestions) stripChangedAt = SystemClock.uptimeMillis()
        suggestions = s.take(3); bold = boldIndex; undoIdx = undoIndex; invalidate()
    }

    /** a correction was applied: short flash of the strip */
    fun flash() { flashAt = SystemClock.uptimeMillis(); invalidate() }

    private val pal = Palette(ctx)
    private val dp = resources.displayMetrics.density
    private val stripH = 46 * dp
    private var keyW = 0f
    private var rowH = 0f
    private var bottomInset = 0

    private val keyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; color = pal.fg }
    private val stripPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; textSize = 17 * dp }
    private val statusPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.RIGHT; color = pal.dim; textSize = 10 * dp }
    private val rect = RectF()

    private fun icon(id: Int): Drawable = ContextCompat.getDrawable(context, id)!!.mutate()
    private val icShift = icon(R.drawable.ms_shift)
    private val icShiftOn = icon(R.drawable.ms_shift_on)
    private val icShiftLock = icon(R.drawable.ms_shift_lock)
    private val icBackspace = icon(R.drawable.ms_backspace)
    private val icEnter = icon(R.drawable.ms_enter)
    private val icSend = icon(R.drawable.ms_send)
    private val icSearch = icon(R.drawable.ms_search)
    private val icGo = icon(R.drawable.ms_go)
    private val icDone = icon(R.drawable.ms_done)
    private val icNext = icon(R.drawable.ms_next)
    private val icSettings = icon(R.drawable.ms_settings)
    private val icUndo = icon(R.drawable.ms_undo)
    private val icPaste = icon(R.drawable.ms_paste)
    private val icEmoji = icon(R.drawable.ms_emoji)
    private val chipRect = RectF()

    // touch state
    private val pressed = HashMap<Int, KeyDef>()     // pointer id -> key
    private val released = HashMap<KeyDef, Long>()   // key -> release time (scale-back animation)
    private val ui = Handler(Looper.getMainLooper())
    private var repeatKey: KeyDef? = null
    private var repeats = 0
    private val repeater = object : Runnable {
        override fun run() {
            val k = repeatKey ?: return
            listener.onKey(k, Touch.APOS)
            repeats++
            // accelerate: 70 ms -> 25 ms over the first ~15 repeats
            ui.postDelayed(this, maxOf(25L, 70L - 3L * repeats))
        }
    }
    private var symDownAt = 0L
    private var stripDown = -1f

    // long-press alternates
    private class Alts(val key: KeyDef, val pointer: Int, val options: List<String>, var sel: Int)
    private var alts: Alts? = null
    private val longPress = Runnable { openAlternates() }
    private var longPressPointer = -1

    init { setBackgroundColor(pal.bg) }

    // ------------------------------------------------------------------ insets / size
    /** Bottom system area the keys must stay clear of: 3-button bar, gesture handle, display cutout. */
    private fun bottomOf(ins: WindowInsets?): Int {
        if (ins == null) return 0
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            maxOf(ins.getInsets(WindowInsets.Type.navigationBars() or WindowInsets.Type.displayCutout()).bottom,
                ins.getInsets(WindowInsets.Type.tappableElement()).bottom)
        } else @Suppress("DEPRECATION") ins.systemWindowInsetBottom
    }

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        val b = bottomOf(insets)
        if (b != bottomInset) { bottomInset = b; requestLayout() }
        return insets
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        requestApplyInsets()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // edge-to-edge IME windows (Android 15+) can hand the insets to a parent: read the window's own insets too
        bottomInset = maxOf(bottomInset, bottomOf(rootWindowInsets))
        val w = View.MeasureSpec.getSize(widthMeasureSpec)
        keyW = w / 10f
        rowH = (keyW * 1.25f).coerceIn(46 * dp, 58 * dp)
        setMeasuredDimension(w, (stripH + 4 * rowH + 8 * dp).toInt() + bottomInset)
    }

    private fun keyRect(k: KeyDef, out: RectF) {
        val padX = 3 * dp
        val padY = 4 * dp
        out.set(k.x * keyW + padX, stripH + k.row * rowH + padY, (k.x + k.w) * keyW - padX, stripH + (k.row + 1) * rowH - padY)
    }

    // ------------------------------------------------------------------ drawing
    override fun onDraw(canvas: Canvas) {
        val now = SystemClock.uptimeMillis()
        var animating = drawStrip(canvas, now)
        val held = pressed.values.toSet()
        for (k in layer.keys) {
            keyRect(k, rect)
            // press scale: 0.92 while held, eases back over 90 ms after release
            val rel = released[k]
            val s = when {
                k in held -> 0.92f
                rel != null && now - rel < 90 -> { animating = true; 0.92f + 0.08f * ((now - rel) / 90f) }
                else -> { if (rel != null) released.remove(k); 1f }
            }
            if (s != 1f) {
                val cx = rect.centerX(); val cy = rect.centerY(); val hw = rect.width() / 2 * s; val hh = rect.height() / 2 * s
                rect.set(cx - hw, cy - hh, cx + hw, cy + hh)
            }
            val isEnter = k.type == KeyType.ENTER
            val lock = k.type == KeyType.SHIFT && shift == Shift.LOCK
            keyPaint.color = when {
                k in held -> pal.pressed
                isEnter || lock -> pal.accent
                k.type == KeyType.SHIFT && shift == Shift.ON -> pal.accentSoft
                k.type == KeyType.CHAR || k.type == KeyType.SPACE -> pal.key
                else -> pal.mod
            }
            canvas.drawRoundRect(rect, 9 * dp, 9 * dp, keyPaint)
            val ic: Drawable? = when (k.type) {
                KeyType.SHIFT -> when (shift) { Shift.OFF -> icShift; Shift.ON -> icShiftOn; Shift.LOCK -> icShiftLock }
                KeyType.BACKSPACE -> icBackspace
                KeyType.ENTER -> when (enterLabel) { "Send" -> icSend; "Search" -> icSearch; "Go" -> icGo; "Done" -> icDone; "Next" -> icNext; else -> icEnter }
                else -> null
            }
            if (ic != null) {
                val sz = (24 * dp).toInt()
                val l = (rect.centerX() - sz / 2).toInt(); val t = (rect.centerY() - sz / 2).toInt()
                ic.setBounds(l, t, l + sz, t + sz)
                ic.setTint(if (isEnter || lock) pal.onAccent else pal.fg)
                ic.draw(canvas)
                continue
            }
            textPaint.typeface = Typeface.DEFAULT
            if (k.type == KeyType.SPACE) {
                textPaint.color = pal.dim; textPaint.textSize = 12 * dp
                canvas.drawText("English", rect.centerX(), rect.centerY() + 4 * dp, textPaint)
                continue
            }
            val label = if (k.type == KeyType.CHAR && shift != Shift.OFF) k.label.uppercase() else k.label
            textPaint.color = pal.fg
            textPaint.textSize = if (label.length > 1) 14 * dp else 22 * dp
            canvas.drawText(label, rect.centerX(), rect.centerY() + textPaint.textSize / 3, textPaint)
            // hint for the long-press alternate on the top row (digits)
            if (layer === KeyboardLayout.LETTERS && k.row == 0) {
                KeyboardLayout.alternates(k).firstOrNull()?.let { alt ->
                    textPaint.color = pal.dim; textPaint.textSize = 9 * dp
                    canvas.drawText(alt, rect.right - 7 * dp, rect.top + 11 * dp, textPaint)
                }
            }
        }
        drawPopups(canvas)
        if (animating) postInvalidateOnAnimation()
    }

    /** -> still animating */
    private fun drawStrip(canvas: Canvas, now: Long): Boolean {
        var anim = false
        val fa = now - flashAt
        if (fa < 350) {
            keyPaint.color = pal.accentSoft
            keyPaint.alpha = (255 * (1f - fa / 350f)).toInt()
            canvas.drawRect(0f, 0f, width.toFloat(), stripH, keyPaint)
            keyPaint.alpha = 255
            anim = true
        }
        val cellW = width / 3f
        val order = intArrayOf(1, 0, 2)   // best suggestion in the middle
        val t = ((now - stripChangedAt) / 140f).coerceIn(0f, 1f)
        if (t < 1f) anim = true
        chipRect.setEmpty()
        if (suggestions.isEmpty()) {
            // toolbar: settings, clipboard, emoji
            val sz = (22 * dp).toInt()
            val tp = ((stripH - sz) / 2).toInt()
            for ((i, ic) in listOf(icSettings, icPaste, icEmoji).withIndex()) {
                val l = (14 * dp + i * TOOL_W * dp).toInt()
                ic.setBounds(l, tp, l + sz, tp + sz); ic.setTint(pal.dim); ic.alpha = 255; ic.draw(canvas)
            }
            val clip = clipChip
            if (clip != null) {
                // paste chip with the start of the copied text
                stripPaint.typeface = Typeface.DEFAULT; stripPaint.color = pal.fg; stripPaint.textSize = 14 * dp
                var label = clip.replace('\n', ' ').trim()
                val maxW = width - (14 + 3 * TOOL_W + 24) * dp - 40 * dp
                if (stripPaint.measureText(label) > maxW) { while (label.length > 3 && stripPaint.measureText("$label…") > maxW) label = label.dropLast(1); label += "…" }
                val tw = stripPaint.measureText(label) + 40 * dp
                val right = width - 10 * dp
                chipRect.set(right - tw, 8 * dp, right, stripH - 8 * dp)
                keyPaint.color = pal.accentSoft
                canvas.drawRoundRect(chipRect, chipRect.height() / 2, chipRect.height() / 2, keyPaint)
                val isz = (16 * dp).toInt()
                val l = (chipRect.left + 10 * dp).toInt(); val t2 = (chipRect.centerY() - isz / 2).toInt()
                icPaste.setBounds(l, t2, l + isz, t2 + isz); icPaste.setTint(pal.fg); icPaste.draw(canvas)
                stripPaint.textAlign = Paint.Align.LEFT
                canvas.drawText(label, chipRect.left + 30 * dp, stripH / 2 + 5 * dp, stripPaint)
                stripPaint.textAlign = Paint.Align.CENTER; stripPaint.textSize = 17 * dp
            }
        }
        for ((cell, si) in order.withIndex()) {
            val s = suggestions.getOrNull(si) ?: continue
            val cx = cellW * cell + cellW / 2
            val dy = (1f - t) * 6 * dp
            stripPaint.typeface = if (si == bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            stripPaint.color = pal.fg
            stripPaint.alpha = (255 * t).toInt()
            var label = s
            while (stripPaint.measureText(label) > cellW - 40 * dp && label.length > 4) label = label.dropLast(2)
            if (label != s) label += "…"
            if (si == undoIdx) {
                // undo chip: the word as typed, tap to put it back
                val tw = stripPaint.measureText(label) + 32 * dp
                rect.set(cx - tw / 2, 8 * dp + dy, cx + tw / 2, stripH - 8 * dp + dy)
                keyPaint.color = pal.accentSoft; keyPaint.alpha = (255 * t).toInt()
                canvas.drawRoundRect(rect, rect.height() / 2, rect.height() / 2, keyPaint)
                keyPaint.alpha = 255
                val sz = (16 * dp).toInt()
                val l = (rect.left + 8 * dp).toInt(); val tp = (rect.centerY() - sz / 2).toInt()
                icUndo.setBounds(l, tp, l + sz, tp + sz); icUndo.setTint(pal.fg); icUndo.alpha = (255 * t).toInt(); icUndo.draw(canvas)
                canvas.drawText(label, cx + 9 * dp, stripH / 2 + 6 * dp + dy, stripPaint)
            } else {
                canvas.drawText(label, cx, stripH / 2 + 6 * dp + dy, stripPaint)
            }
        }
        stripPaint.alpha = 255
        // hairline dividers between the cells
        if (suggestions.size > 1) {
            keyPaint.color = pal.mod
            for (c in 1..2) canvas.drawRect(cellW * c - dp / 2, 15 * dp, cellW * c + dp / 2, stripH - 15 * dp, keyPaint)
        }
        if (status.isNotEmpty()) canvas.drawText(status, width - 4 * dp, 11 * dp, statusPaint)
        return anim
    }

    private fun drawPopups(canvas: Canvas) {
        val a = alts
        if (a != null) {
            keyRect(a.key, rect)
            val cw = keyW * 0.95f
            val w = cw * a.options.size
            val left = (rect.centerX() - cw / 2).coerceIn(2 * dp, width - w - 2 * dp)
            val top = rect.top - rowH * 1.1f
            rect.set(left, top, left + w, top + rowH)
            keyPaint.color = pal.popup
            keyPaint.setShadowLayer(6 * dp, 0f, 2 * dp, 0x55000000)
            canvas.drawRoundRect(rect, 10 * dp, 10 * dp, keyPaint)
            keyPaint.clearShadowLayer()
            for ((i, o) in a.options.withIndex()) {
                val cl = left + i * cw
                if (i == a.sel) {
                    keyPaint.color = pal.accent
                    canvas.drawRoundRect(cl + 3 * dp, rect.top + 3 * dp, cl + cw - 3 * dp, rect.bottom - 3 * dp, 8 * dp, 8 * dp, keyPaint)
                }
                textPaint.color = if (i == a.sel) pal.onAccent else pal.fg
                textPaint.textSize = 20 * dp
                canvas.drawText(if (shift != Shift.OFF) o.uppercase() else o, cl + cw / 2, rect.centerY() + 7 * dp, textPaint)
            }
            return
        }
        // key preview bubbles for held character keys
        for (k in pressed.values) {
            if (k.type != KeyType.CHAR) continue
            keyRect(k, rect)
            val w = rect.width() * 1.2f
            val h = rowH * 1.05f
            val cx = rect.centerX().coerceIn(w / 2 + 2 * dp, width - w / 2 - 2 * dp)
            rect.set(cx - w / 2, rect.top - h - 4 * dp, cx + w / 2, rect.top - 4 * dp)
            keyPaint.color = pal.popup
            keyPaint.setShadowLayer(6 * dp, 0f, 2 * dp, 0x55000000)
            canvas.drawRoundRect(rect, 10 * dp, 10 * dp, keyPaint)
            keyPaint.clearShadowLayer()
            textPaint.color = pal.fg; textPaint.textSize = 28 * dp; textPaint.typeface = Typeface.DEFAULT
            val label = if (shift != Shift.OFF) k.label.uppercase() else k.label
            canvas.drawText(label, rect.centerX(), rect.centerY() + 10 * dp, textPaint)
        }
    }

    // ------------------------------------------------------------------ touch
    private fun openAlternates() {
        val k = pressed[longPressPointer] ?: return
        val opts = KeyboardLayout.alternates(k)
        if (opts.isEmpty()) return
        alts = Alts(k, longPressPointer, opts, 0)
        buzz(strong = true)
        invalidate()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                val x = e.getX(i)
                val y = e.getY(i)
                if (alts != null) return true   // one alternates popup at a time
                if (y < stripH) {
                    if (e.actionMasked == MotionEvent.ACTION_DOWN) stripDown = x
                    return true
                }
                val ux = x / keyW
                val uy = (y - stripH) / rowH
                val k = layer.keyAt(ux, min(uy, 3.99f)) ?: return true
                val id = e.getPointerId(i)
                pressed[id] = k
                buzz()
                // text keys fire on press (keeps order when presses overlap); ?123 fires on release so a long press
                // can open the keyboard switcher instead
                if (k.type == KeyType.TO_SYMBOLS) symDownAt = e.eventTime
                else listener.onKey(k, KeyboardLayout.modelTouch(ux, uy, k))
                if (k.type == KeyType.BACKSPACE) { repeatKey = k; repeats = 0; ui.postDelayed(repeater, REPEAT_START_MS) }
                if (k.type == KeyType.CHAR && KeyboardLayout.alternates(k).isNotEmpty()) {
                    ui.removeCallbacks(longPress); longPressPointer = id; ui.postDelayed(longPress, LONG_PRESS_ALT_MS)
                }
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                val a = alts ?: return true
                val pi = e.findPointerIndex(a.pointer)
                if (pi < 0) return true
                keyRect(a.key, rect)
                val cw = keyW * 0.95f
                val left = (rect.centerX() - cw / 2).coerceIn(2 * dp, width - cw * a.options.size - 2 * dp)
                val sel = ((e.getX(pi) - left) / cw).toInt().coerceIn(0, a.options.size - 1)
                if (sel != a.sel) { a.sel = sel; invalidate() }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                val i = e.actionIndex
                val id = e.getPointerId(i)
                val k = pressed.remove(id)
                if (k != null) released[k] = SystemClock.uptimeMillis()
                if (id == longPressPointer) { ui.removeCallbacks(longPress); longPressPointer = -1 }
                val a = alts
                if (a != null && a.pointer == id) {
                    if (e.actionMasked != MotionEvent.ACTION_CANCEL) listener.onAlternate(a.options[a.sel])
                    alts = null
                }
                if (k?.type == KeyType.BACKSPACE) { repeatKey = null; ui.removeCallbacks(repeater) }
                if (k?.type == KeyType.TO_SYMBOLS && e.actionMasked != MotionEvent.ACTION_CANCEL) {
                    if (e.eventTime - symDownAt > LONG_PRESS_MS) listener.onSwitchIme() else listener.onKey(k, Touch.APOS)
                }
                if (k == null && e.actionMasked == MotionEvent.ACTION_UP && e.getY(i) < stripH && stripDown >= 0) {
                    if (suggestions.isEmpty()) {
                        val x = e.getX(i)
                        when {
                            !chipRect.isEmpty && chipRect.contains(x, e.getY(i)) -> { buzz(); listener.onPasteChip() }
                            x < (14 + TOOL_W - 4) * dp -> listener.onSettings()
                            x < (14 + 2 * TOOL_W - 4) * dp -> listener.onClipboard()
                            x < (14 + 3 * TOOL_W - 4) * dp -> listener.onEmoji()
                        }
                    } else {
                        val cell = (e.getX(i) / (width / 3f)).toInt().coerceIn(0, 2)
                        val si = intArrayOf(1, 0, 2)[cell]
                        if (si < suggestions.size) {
                            buzz()
                            listener.onSuggestion(si)
                        }
                    }
                }
                if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) {
                    val t = SystemClock.uptimeMillis()
                    for (q in pressed.values) released[q] = t
                    pressed.clear(); stripDown = -1f; repeatKey = null
                    ui.removeCallbacks(repeater); ui.removeCallbacks(longPress); alts = null
                }
                invalidate()
            }
        }
        return true
    }

    companion object {
        const val REPEAT_START_MS = 400L
        const val LONG_PRESS_MS = 500L
        const val LONG_PRESS_ALT_MS = 380L
        /** strip toolbar slot width (dp) */
        const val TOOL_W = 44f
    }
}
