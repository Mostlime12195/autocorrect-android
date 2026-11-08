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
import dev.mostlime.autocorrect.App
import dev.mostlime.autocorrect.R
import dev.mostlime.autocorrect.engine.KeyGeom
import dev.mostlime.autocorrect.engine.Touch
import kotlin.math.min

enum class Shift { OFF, ON, LOCK }

/** Colors: Material You (system dynamic colors) on Android 12+, a neutral light/dark palette before that. */
/**
 * Keyboard colors. Mode: system / light / dark (setting). Accent (setting "Theme color"): Material You (wallpaper colors,
 * Android 12+, the default there), the original green (the default elsewhere), a swatch or a custom color; text on
 * the accent is black or white, whichever reads better.
 */
class Palette(ctx: Context) {
    private val prefs = runCatching { App.instance.prefs }.getOrNull()
    val dark = when (prefs?.themeMode) {
        "light" -> false
        "dark" -> true
        else -> (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    }
    private val dyn = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    private val res = ctx
    private fun pick(darkId: Int, lightId: Int, darkFallback: Int, lightFallback: Int): Int =
        if (dyn) res.getColor(if (dark) darkId else lightId) else if (dark) darkFallback else lightFallback
    private fun fixed(darkC: Int, lightC: Int) = if (dark) darkC else lightC

    val bg = pick(android.R.color.system_neutral1_900, android.R.color.system_neutral1_100, 0xFF1B1B1F.toInt(), 0xFFE8EAED.toInt())
    val key = pick(android.R.color.system_neutral1_800, android.R.color.system_neutral1_10, 0xFF3C3C43.toInt(), 0xFFFFFFFF.toInt())
    val mod = pick(android.R.color.system_neutral2_700, android.R.color.system_neutral2_200, 0xFF2A2A30.toInt(), 0xFFCDD1D6.toInt())
    val pressed = pick(android.R.color.system_neutral2_600, android.R.color.system_neutral2_300, 0xFF5A5A63.toInt(), 0xFFB0B5BC.toInt())
    val fg = pick(android.R.color.system_neutral1_50, android.R.color.system_neutral1_900, 0xFFF1F1F1.toInt(), 0xFF202124.toInt())
    val dim = pick(android.R.color.system_neutral2_200, android.R.color.system_neutral2_600, 0xFF9AA0A6.toInt(), 0xFF5F6368.toInt())
    val popup = pick(android.R.color.system_neutral1_700, android.R.color.system_neutral1_0, 0xFF4A4A52.toInt(), 0xFFFFFFFF.toInt())

    /** the chosen accent color, or null for Material You / green */
    private val custom: Int? = prefs?.accentColor()
    private val useDyn = custom == null && dyn && (prefs?.themeColor ?: "dynamic") == "dynamic"
    val accent = custom ?: if (useDyn) res.getColor(if (dark) android.R.color.system_accent1_200 else android.R.color.system_accent1_600)
        else fixed(0xFF81C995.toInt(), 0xFF2E7D32.toInt())
    val onAccent = if (custom != null) onColor(custom) else if (useDyn) res.getColor(if (dark) android.R.color.system_accent1_800 else android.R.color.system_accent1_0)
        else fixed(0xFF0B3D1B.toInt(), 0xFFFFFFFF.toInt())
    val accentSoft = if (custom != null) blend(custom, bg, if (dark) 0.35f else 0.25f)
        else if (useDyn) res.getColor(if (dark) android.R.color.system_accent2_800 else android.R.color.system_accent2_100)
        else fixed(0xFF33433A.toInt(), 0xFFDCEBDD.toInt())

    companion object {
        /** black or white text on c, by relative luminance */
        fun onColor(c: Int): Int {
            fun ch(v: Int): Double { val x = v / 255.0; return if (x <= 0.03928) x / 12.92 else Math.pow((x + 0.055) / 1.055, 2.4) }
            val l = 0.2126 * ch((c shr 16) and 0xff) + 0.7152 * ch((c shr 8) and 0xff) + 0.0722 * ch(c and 0xff)
            return if (l > 0.4) 0xFF1B1B1F.toInt() else 0xFFFFFFFF.toInt()
        }
        /** a * f + b * (1 - f), opaque */
        fun blend(a: Int, b: Int, f: Float): Int {
            fun mix(sh: Int) = ((((a shr sh) and 0xff) * f + ((b shr sh) and 0xff) * (1 - f)).toInt() and 0xff) shl sh
            return (0xFF shl 24) or mix(16) or mix(8) or mix(0)
        }
    }
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
        fun onSuggestionLongPress(index: Int) {}
        /** the strip's action chip ("not a typo", "ignore", "undo") */
        fun onStripAction() {}
        fun onSwitchIme()
        fun onSettings()
        fun onClipboard() {}
        fun onEmoji() {}
        fun onPasteChip() {}
        /** long-press on comma: emoji (the comma the press typed is taken back) */
        fun onCommaLongPress() {}
        /** one-handed mode: the empty side was tapped */
        fun onOneHandedSwap() {}
        /** the touch that started on a letter became a swipe (the letter its press typed must be taken back) */
        fun onSwipeStart() {}
        /** the swipe ended: path in model units (key widths, rows x ROW_H), letter area only */
        fun onSwipe(path: List<Touch>) {}
    }

    /** text of a recently copied clip, offered as a chip in an empty strip (null = none) */
    var clipChip: String? = null
        set(v) { field = v; invalidate() }
    val stripHeight get() = stripH
    val bottomInsetPx get() = bottomInset

    var layer: Layer = KeyboardLayout.LETTERS
        set(v) { field = v; invalidate() }
    /** rows the keyboard height is sized for (the letters layer, 5 with the number row); other layers stretch to fit */
    var baseRows = 4
        set(v) { if (v != field) { field = v; requestLayout() } }
    /** row height of the current layer */
    private val rh get() = rowH * baseRows / layer.rows
    /** keyboard height setting: 0.8 .. 1.3 */
    var heightScale = 1f
        set(v) { if (v != field) { field = v; requestLayout() } }
    var shift = Shift.OFF
        set(v) { field = v; invalidate() }
    /** editor action label from the IME (Go / Search / Send / Next / Done / Prev / ⏎) */
    var enterLabel = "⏎"
        set(v) { field = v; invalidate() }
    /** swipe typing on the letters layer (setting + field policy) */
    var swipeEnabled = true
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

    private var stripHint = ""
    private var stripAction: String? = null
    private val actionRect = RectF()

    fun setSuggestions(s: List<String>, boldIndex: Int, undoIndex: Int = -1, hint: String = "", action: String? = null) {
        if (s != suggestions || action != stripAction) stripChangedAt = SystemClock.uptimeMillis()
        suggestions = s.take(3); bold = boldIndex; undoIdx = undoIndex; stripHint = hint; stripAction = action; invalidate()
    }

    /** width of the strip's word cells (the action chip, if any, takes the right end) */
    private fun cellsW(): Float = if (stripAction != null && suggestions.isNotEmpty()) actionLeft() else width.toFloat()
    private fun actionLeft(): Float {
        val a = stripAction ?: return width.toFloat()
        stripPaint.textSize = 14 * dp
        val w = stripPaint.measureText(a) + 28 * dp
        stripPaint.textSize = 17 * dp
        return width - w - 8 * dp
    }

    /** a correction was applied: short flash of the strip */
    fun flash() { flashAt = SystemClock.uptimeMillis(); invalidate() }

    private val pal = Palette(ctx)
    private val dp = resources.displayMetrics.density
    private val stripH = 46 * dp
    private var keyW = 0f
    /** left edge of the key area (one-handed right) */
    private var areaX0 = 0f
    /** "off", "left", "right" */
    var oneHanded = "off"
        set(v) { if (v != field) { field = v; requestLayout(); invalidate() } }
    /** split letters (landscape) */
    var split = false
        set(v) { if (v != field) { field = v; requestLayout(); invalidate() } }
    /** key units (0..10) -> px, and back */
    private fun pxOf(u: Float): Float = if (split) (if (u < 5f) u * keyW else width - (10f - u) * keyW) else areaX0 + u * keyW
    private fun uOf(px: Float): Float = if (split) (if (px < width / 2f) px / keyW else 10f - (width - px) / keyW) else (px - areaX0) / keyW
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
    private val icBack = icon(R.drawable.ms_back)
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
    /** long-press on a strip word (add to dictionary / not a typo): the release then does nothing */
    private var stripLongDone = false
    private val stripLongPress = Runnable {
        val x = stripDown
        if (x < 0 || suggestions.isEmpty() || (!actionRect.isEmpty && x >= actionRect.left - 4 * dp)) return@Runnable
        val si = intArrayOf(1, 0, 2)[(x / (cellsW() / 3f)).toInt().coerceIn(0, 2)]
        if (si < suggestions.size) { stripLongDone = true; buzz(strong = true); listener.onSuggestionLongPress(si) }
    }

    // long-press alternates
    private class Alts(val key: KeyDef, val pointer: Int, val options: List<String>, var sel: Int)
    private var alts: Alts? = null
    private val longPress = Runnable { openAlternates() }
    private val commaLongPress = Runnable { buzz(strong = true); commaDone = true; listener.onCommaLongPress() }
    private var commaDone = false
    private var longPressPointer = -1

    // swipe typing: a drag that starts on a letter and has been over 2+ letter keys (and moved far enough) is a swipe
    private class SwipeTrack(val pointer: Int, val startX: Float, val startY: Float) {
        val pts = ArrayList<Touch>(128)
        var lastKey: KeyDef? = null
        var keys = 0
        var started = false
        /** slid off its first key: it may become a swipe, so no preview bubble for it */
        var moving = false
    }
    private var track: SwipeTrack? = null
    /** fading trail: x, y (px) and time per point */
    private var trailX = FloatArray(256)
    private var trailY = FloatArray(256)
    private var trailT = LongArray(256)
    private var trailN = 0
    private var trailBreak = -1   // index where a new gesture's trail starts (no segment across it)
    private val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; color = pal.accent
    }

    init { setBackgroundColor(pal.bg) }

    // ------------------------------------------------------------------ look (settings -> Appearance)
    var keyGapDp = 4f
        set(v) { field = v; invalidate() }
    var keyRadiusDp = 9f
        set(v) { field = v; invalidate() }
    /** hint label size (1 = normal, 0 = hidden) */
    var hintScale = 1f
        set(v) { field = v; invalidate() }
    var longPressMs = LONG_PRESS_ALT_MS
    /** key fill opacity over a background image, % */
    var keyAlpha = 75

    /** background image: the picked file, darkened by dimPct, blurred by blurDp (Android 12+); one bitmap at view size */
    private var bgFile: java.io.File? = null
    private var bgDim = 0
    private var bgBlur = 0
    private var bgBitmap: android.graphics.Bitmap? = null
    private var bgNode: Any? = null

    fun setBackgroundImage(file: java.io.File?, dimPct: Int, blurDp: Int) {
        if (file == bgFile && dimPct == bgDim && blurDp == bgBlur && (file == null || bgBitmap != null)) return
        bgFile = file?.takeIf { it.exists() }; bgDim = dimPct; bgBlur = blurDp
        bgBitmap?.recycle(); bgBitmap = null; bgNode = null
        if (width > 0 && height > 0) loadBackground(width, height)
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (bgFile != null && (w != oldw || h != oldh)) { bgBitmap?.recycle(); bgBitmap = null; bgNode = null; loadBackground(w, h) }
    }

    /** decode with subsampling, centre-crop to w x h (re-done when the height setting changes the view size) */
    private fun loadBackground(w: Int, h: Int) {
        val f = bgFile ?: return
        try {
            val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeFile(f.path, o)
            if (o.outWidth <= 0 || o.outHeight <= 0) return
            var sample = 1
            while (o.outWidth / (sample * 2) >= w && o.outHeight / (sample * 2) >= h) sample *= 2
            val src = android.graphics.BitmapFactory.decodeFile(f.path, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }) ?: return
            val scale = maxOf(w / src.width.toFloat(), h / src.height.toFloat())
            val sw = w / scale; val sh = h / scale
            val sx = (src.width - sw) / 2; val sy = (src.height - sh) / 2
            val out = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
            android.graphics.Canvas(out).drawBitmap(src, android.graphics.Rect(sx.toInt(), sy.toInt(), (sx + sw).toInt(), (sy + sh).toInt()),
                android.graphics.Rect(0, 0, w, h), android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
            src.recycle()
            bgBitmap = out
            if (bgBlur > 0 && Build.VERSION.SDK_INT >= 31) {
                bgNode = android.graphics.RenderNode("kb-bg").apply {
                    setPosition(0, 0, w, h)
                    beginRecording().also { it.drawBitmap(out, 0f, 0f, null) }
                    endRecording()
                    setRenderEffect(android.graphics.RenderEffect.createBlurEffect(bgBlur * dp, bgBlur * dp, android.graphics.Shader.TileMode.CLAMP))
                }
            }
        } catch (e: Throwable) {
            android.util.Log.w("AcView", "background image: $e")
        }
    }

    private fun drawBackground(canvas: Canvas) {
        val b = bgBitmap ?: return
        val node = bgNode
        if (node != null && Build.VERSION.SDK_INT >= 31 && canvas.isHardwareAccelerated) canvas.drawRenderNode(node as android.graphics.RenderNode)
        else canvas.drawBitmap(b, 0f, 0f, null)
        if (bgDim > 0) canvas.drawColor((bgDim * 255 / 100) shl 24)
    }

    // ------------------------------------------------------------------ insets / size
    /** Bottom system area the keys must stay clear of: 3-button bar, gesture handle, display cutout. */
    private fun bottomOf(ins: WindowInsets?): Int {
        if (ins == null) return 0
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            maxOf(ins.getInsets(WindowInsets.Type.navigationBars() or WindowInsets.Type.displayCutout()).bottom,
                ins.getInsets(WindowInsets.Type.tappableElement()).bottom)
        } else @Suppress("DEPRECATION") ins.systemWindowInsetBottom
    }

    /** bottom band where the system's own gestures start (gesture navigation's swipe up): touches starting there are ignored */
    private var gestureBottom = 0

    private fun gestureBottomOf(ins: WindowInsets?): Int {
        if (ins == null) return 0
        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> ins.getInsets(WindowInsets.Type.mandatorySystemGestures()).bottom
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> @Suppress("DEPRECATION") ins.mandatorySystemGestureInsets.bottom
            else -> 0
        }
    }

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        gestureBottom = gestureBottomOf(insets)
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
        gestureBottom = maxOf(gestureBottom, gestureBottomOf(rootWindowInsets))
        val w = View.MeasureSpec.getSize(widthMeasureSpec)
        // one-handed: keys in 80% of the width on one side; split (landscape): two halves of 40% with a gap
        keyW = if (oneHanded != "off" || split) w * 0.8f / 10f else w / 10f
        areaX0 = if (oneHanded == "right" && !split) w * 0.2f else 0f
        rowH = (w / 10f * 1.25f).coerceIn(46 * dp, 58 * dp)
        rowH *= heightScale
        setMeasuredDimension(w, (stripH + baseRows * rowH + 8 * dp).toInt() + bottomInset)
    }

    private fun keyRect(k: KeyDef, out: RectF) {
        val padX = keyGapDp * dp
        val padY = (keyGapDp + 1) * dp
        out.set(pxOf(k.x) + padX, stripH + k.row * rh + padY, pxOf(k.x + k.w) - padX, stripH + (k.row + 1) * rh - padY)
    }

    // ------------------------------------------------------------------ drawing
    override fun onDraw(canvas: Canvas) {
        val now = SystemClock.uptimeMillis()
        drawBackground(canvas)
        if (oneHanded != "off" && !split) {
            // the empty side: a chevron that moves the keys there
            val sz = (28 * dp).toInt()
            val cx = if (areaX0 > 0) areaX0 / 2 else (areaX0 + 10 * keyW + width) / 2
            val cy = stripH + (height - stripH - bottomInset) / 2f
            val ic = if (areaX0 > 0) icBack else icNext
            ic.setBounds((cx - sz / 2).toInt(), (cy - sz / 2).toInt(), (cx + sz / 2).toInt(), (cy + sz / 2).toInt())
            ic.setTint(pal.dim); ic.alpha = 255; ic.draw(canvas)
        }
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
            if (bgBitmap != null) keyPaint.alpha = keyAlpha * 255 / 100
            canvas.drawRoundRect(rect, keyRadiusDp * dp, keyRadiusDp * dp, keyPaint)
            keyPaint.alpha = 255
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
            // smaller labels on short keys (height setting 80%) so the corner hint keeps its room
            textPaint.textSize = if (label.length > 1) 14 * dp else minOf(22 * dp, rect.height() * 0.52f)
            // letters sit a touch below centre when there's a hint, so the two never crowd each other
            val lift = if (k.hint != null && hintScale > 0f && label.length == 1) 1.5f * dp else 0f
            canvas.drawText(label, rect.centerX(), rect.centerY() + textPaint.textSize / 3 + lift, textPaint)
            // hint: the long-press default (digit / symbol), inset from the top-right corner
            val hint = k.hint
            if (hint != null && hintScale > 0f) {
                textPaint.color = pal.dim; textPaint.textSize = 8.5f * dp * hintScale
                // inner padding: tucked into the top-right corner, clear of the centred letter
                canvas.drawText(hint, rect.right - (3.5f + 3f * hintScale) * dp, rect.top + (2.5f + 7.5f * hintScale) * dp, textPaint)
            }
        }
        drawPopups(canvas)
        if (drawTrail(canvas, now)) animating = true
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
        val cellW = cellsW() / 3f
        val order = intArrayOf(1, 0, 2)   // best suggestion in the middle
        actionRect.setEmpty()
        val action = stripAction
        if (action != null && suggestions.isNotEmpty()) {
            // action chip at the right end ("not a typo", "ignore", "undo")
            val l = actionLeft()
            actionRect.set(l, 9 * dp, width - 8 * dp, stripH - 9 * dp)
            keyPaint.color = pal.accentSoft
            canvas.drawRoundRect(actionRect, actionRect.height() / 2, actionRect.height() / 2, keyPaint)
            stripPaint.typeface = Typeface.DEFAULT; stripPaint.color = pal.fg; stripPaint.textSize = 14 * dp
            canvas.drawText(action, actionRect.centerX(), actionRect.centerY() + 5 * dp, stripPaint)
            stripPaint.textSize = 17 * dp
        }
        if (stripHint.isNotEmpty() && suggestions.isNotEmpty()) {
            // small label in the top-left corner ("typo?")
            statusPaint.textAlign = Paint.Align.LEFT; statusPaint.color = pal.accent
            canvas.drawText(stripHint, 6 * dp, 12 * dp, statusPaint)
            statusPaint.textAlign = Paint.Align.RIGHT; statusPaint.color = pal.dim
        }
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
            if (!actionRect.isEmpty) canvas.drawRect(cellW * 3 - dp / 2, 15 * dp, cellW * 3 + dp / 2, stripH - 15 * dp, keyPaint)
        }
        if (status.isNotEmpty()) canvas.drawText(status, width - 4 * dp, 11 * dp, statusPaint)
        return anim
    }

    /** -> still animating. Segments fade out TRAIL_MS after their point was drawn. */
    private fun drawTrail(canvas: Canvas, now: Long): Boolean {
        var first = 0
        while (first < trailN && now - trailT[first] > TRAIL_MS) first++
        if (first > 0) {
            System.arraycopy(trailX, first, trailX, 0, trailN - first)
            System.arraycopy(trailY, first, trailY, 0, trailN - first)
            System.arraycopy(trailT, first, trailT, 0, trailN - first)
            trailN -= first
            trailBreak = if (trailBreak >= first) trailBreak - first else -1
        }
        if (trailN == 0) return false
        val w = 7 * dp
        for (i in 1 until trailN) {
            if (i == trailBreak) continue
            val f = 1f - (now - trailT[i]) / TRAIL_MS.toFloat()
            if (f <= 0f) continue
            trailPaint.alpha = (220 * f).toInt()
            trailPaint.strokeWidth = w * (0.4f + 0.6f * f)
            canvas.drawLine(trailX[i - 1], trailY[i - 1], trailX[i], trailY[i], trailPaint)
        }
        return true
    }

    private fun trailAdd(x: Float, y: Float, t: Long) {
        if (trailN == trailX.size) {
            trailX = trailX.copyOf(trailN * 2); trailY = trailY.copyOf(trailN * 2); trailT = trailT.copyOf(trailN * 2)
        }
        trailX[trailN] = x; trailY[trailN] = y; trailT[trailN] = t; trailN++
    }

    /** px -> model units of the letter area (same mapping as KeyboardLayout.modelTouch), clamped to the letter rows */
    private fun swipeTouch(x: Float, y: Float): Touch {
        val ux = uOf(x).toDouble().coerceIn(0.0, 10.0)
        val uy = ((y - stripH) / rh - layer.letterRowOffset).toDouble() * KeyGeom.ROW_H
        return Touch(ux, uy.coerceIn(0.0, 3 * KeyGeom.ROW_H))
    }

    private fun isLetterKey(k: KeyDef?) = k != null && k.type == KeyType.CHAR && k.text.length == 1 && k.text[0] in 'a'..'z'

    /** Track the swipe pointer's movement (historical samples too); start the swipe once it qualifies. */
    private fun trackMove(e: MotionEvent, t: SwipeTrack) {
        val pi = e.findPointerIndex(t.pointer)
        if (pi < 0) return
        fun add(x: Float, y: Float, time: Long) {
            t.pts.add(swipeTouch(x, y))
            if (t.started) trailAdd(x, y, time)
            if (!t.started) {
                val k = layer.keyAt(uOf(x), min((y - stripH) / rh, layer.rows - 0.01f))
                if (isLetterKey(k) && k != t.lastKey) { t.lastKey = k; t.keys++ }
                val moved = kotlin.math.hypot((x - t.startX) / keyW, (y - t.startY) / rh)
                if (moved > 0.35 && !t.moving) { t.moving = true; invalidate() }
                if (t.keys >= 2 && moved >= SWIPE_MIN_KEYS) startSwipe(t)
            }
        }
        for (h in 0 until e.historySize) add(e.getHistoricalX(pi, h), e.getHistoricalY(pi, h), e.getHistoricalEventTime(h))
        add(e.getX(pi), e.getY(pi), e.eventTime)
    }

    private fun startSwipe(t: SwipeTrack) {
        t.started = true
        // the press is no longer a key press: no preview bubble, no long-press popup
        pressed.remove(t.pointer)?.let { released[it] = SystemClock.uptimeMillis() }
        ui.removeCallbacks(longPress); longPressPointer = -1
        listener.onSwipeStart()
        // trail from the start of the gesture
        trailBreak = trailN
        val now = SystemClock.uptimeMillis()
        for (p in t.pts) {
            val yRow = p.y / KeyGeom.ROW_H + layer.letterRowOffset
            trailAdd(pxOf(p.x.toFloat()), (stripH + yRow * rh).toFloat(), now)
        }
        invalidate()
    }

    private fun drawPopups(canvas: Canvas) {
        val a = alts
        if (a != null) {
            keyRect(a.key, rect)
            val cw = keyW * 0.95f
            val w = cw * a.options.size
            val left = (rect.centerX() - cw / 2).coerceIn(2 * dp, width - w - 2 * dp)
            val top = rect.top - rh * 1.1f
            rect.set(left, top, left + w, top + rh)
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
        val tr = track
        for ((pid, k) in pressed) {
            if (k.type != KeyType.CHAR) continue
            if (tr != null && tr.pointer == pid && (tr.moving || tr.started)) continue   // turning into a swipe
            keyRect(k, rect)
            val w = rect.width() * 1.2f
            val h = rh * 1.05f
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
                // the system's swipe-up (gesture navigation) starts here: never a key press
                if (gestureBottom > 0 && y >= height - gestureBottom) return true
                if (track?.started == true) return true   // other fingers are ignored during a swipe
                track = null                              // a second finger: fast typing, not a swipe
                if (y < stripH) {
                    if (e.actionMasked == MotionEvent.ACTION_DOWN) {
                        stripDown = x; stripLongDone = false
                        ui.removeCallbacks(stripLongPress); ui.postDelayed(stripLongPress, LONG_PRESS_MS)
                    }
                    return true
                }
                // one-handed: a tap in the empty side moves the keys there
                if (oneHanded != "off" && !split && (x < areaX0 || x > areaX0 + 10 * keyW)) {
                    if (e.actionMasked == MotionEvent.ACTION_DOWN) listener.onOneHandedSwap()
                    return true
                }
                val ux = uOf(x)
                val uy = (y - stripH) / rh
                val k = layer.keyAt(ux, min(uy, layer.rows - 0.01f)) ?: return true
                val id = e.getPointerId(i)
                if (swipeEnabled && layer.isLetters && pressed.isEmpty() && e.actionMasked == MotionEvent.ACTION_DOWN && isLetterKey(k)) {
                    track = SwipeTrack(id, x, y).also { it.pts.add(swipeTouch(x, y)); it.lastKey = k; it.keys = 1 }
                }
                pressed[id] = k
                buzz()
                // text keys fire on press (keeps order when presses overlap); ?123 fires on release so a long press
                // can open the keyboard switcher instead
                // space fires on release inside the key, so a touch that turns into a system swipe never types
                if (k.type == KeyType.TO_SYMBOLS) symDownAt = e.eventTime
                else if (k.type != KeyType.SPACE) listener.onKey(k, KeyboardLayout.modelTouch(ux, uy, k, layer.letterRowOffset))
                if (k.type == KeyType.BACKSPACE) { repeatKey = k; repeats = 0; ui.postDelayed(repeater, REPEAT_START_MS) }
                if (k.type == KeyType.CHAR && k.text == "," && layer.isLetters) {
                    commaDone = false; ui.removeCallbacks(commaLongPress); ui.postDelayed(commaLongPress, longPressMs)
                }
                if (k.type == KeyType.CHAR && KeyboardLayout.alternates(k).isNotEmpty()) {
                    ui.removeCallbacks(longPress); longPressPointer = id; ui.postDelayed(longPress, longPressMs)
                }
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                // a press that slides up off the keyboard (a navigation swipe) is cancelled
                for (pi in 0 until e.pointerCount) {
                    val pid = e.getPointerId(pi)
                    if (e.getY(pi) < 0 && pressed[pid]?.type == KeyType.SPACE) { pressed.remove(pid); invalidate() }
                }
                val tr = track
                if (tr != null && alts == null) {
                    trackMove(e, tr)
                    if (tr.started) { invalidate(); return true }
                }
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
                val tr = track
                if (tr != null && tr.pointer == id) {
                    track = null
                    if (tr.started) {
                        if (e.actionMasked != MotionEvent.ACTION_CANCEL) {
                            tr.pts.add(swipeTouch(e.getX(i), e.getY(i)))
                            listener.onSwipe(tr.pts)
                        }
                        if (e.actionMasked != MotionEvent.ACTION_POINTER_UP) {
                            pressed.clear(); stripDown = -1f; repeatKey = null
                            ui.removeCallbacks(repeater); ui.removeCallbacks(longPress); ui.removeCallbacks(commaLongPress); alts = null
                        }
                        invalidate()
                        return true
                    }
                }
                val k = pressed.remove(id)
                if (k != null) released[k] = SystemClock.uptimeMillis()
                if (id == longPressPointer) { ui.removeCallbacks(longPress); longPressPointer = -1 }
                if (k?.text == ",") ui.removeCallbacks(commaLongPress)
                val a = alts
                if (a != null && a.pointer == id) {
                    if (e.actionMasked != MotionEvent.ACTION_CANCEL) listener.onAlternate(a.options[a.sel])
                    alts = null
                }
                if (k?.type == KeyType.BACKSPACE) { repeatKey = null; ui.removeCallbacks(repeater) }
                if (k?.type == KeyType.SPACE && e.actionMasked != MotionEvent.ACTION_CANCEL) {
                    keyRect(k, rect)
                    val ux = e.getX(i); val uy = e.getY(i)
                    if (ux >= rect.left - 4 * dp && ux <= rect.right + 4 * dp && uy >= rect.top - 8 * dp && uy <= height - gestureBottom.toFloat())
                        listener.onKey(k, Touch.APOS)
                }
                if (k?.type == KeyType.TO_SYMBOLS && e.actionMasked != MotionEvent.ACTION_CANCEL) {
                    if (e.eventTime - symDownAt > LONG_PRESS_MS) listener.onSwitchIme() else listener.onKey(k, Touch.APOS)
                }
                ui.removeCallbacks(stripLongPress)
                if (k == null && e.actionMasked == MotionEvent.ACTION_UP && e.getY(i) < stripH && stripDown >= 0 && !stripLongDone) {
                    if (!actionRect.isEmpty && e.getX(i) >= actionRect.left - 4 * dp) {
                        buzz(); listener.onStripAction()
                    } else if (suggestions.isEmpty()) {
                        val x = e.getX(i)
                        when {
                            !chipRect.isEmpty && chipRect.contains(x, e.getY(i)) -> { buzz(); listener.onPasteChip() }
                            x < (14 + TOOL_W - 4) * dp -> listener.onSettings()
                            x < (14 + 2 * TOOL_W - 4) * dp -> listener.onClipboard()
                            x < (14 + 3 * TOOL_W - 4) * dp -> listener.onEmoji()
                        }
                    } else {
                        val cell = (e.getX(i) / (cellsW() / 3f)).toInt().coerceIn(0, 2)
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
                    ui.removeCallbacks(repeater); ui.removeCallbacks(longPress); ui.removeCallbacks(commaLongPress); alts = null
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
        /** a swipe must also have moved this far from its start (key widths; rows count as key heights) */
        const val SWIPE_MIN_KEYS = 0.75f
        /** trail fade-out (ms) */
        const val TRAIL_MS = 260L
        /** strip toolbar slot width (dp) */
        const val TOOL_W = 44f
    }
}
