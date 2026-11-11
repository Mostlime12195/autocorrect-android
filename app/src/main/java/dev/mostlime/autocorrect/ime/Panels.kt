package dev.mostlime.autocorrect.ime

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.mostlime.autocorrect.R

/** The input view: the keyboard plus panels (clipboard, emoji) that cover the key area, under the strip. */
@SuppressLint("ViewConstructor")
class KeyboardRoot(ctx: Context, val keys: KeyboardView) : FrameLayout(ctx) {
    private var panel: View? = null

    init { addView(keys, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)) }

    fun showPanel(v: View) {
        hidePanel()
        panel = v
        val lp = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        lp.topMargin = keys.stripHeight.toInt()
        lp.bottomMargin = keys.bottomInsetPx
        addView(v, lp)
        v.alpha = 0f
        v.animate().alpha(1f).setDuration(120).start()
    }

    /**
     * The keys alone set the height. A panel only fills the key area: measured as a plain FrameLayout child, a
     * scrolling panel (emoji) asked for the whole screen, the input window grew, the field restarted input and the
     * panel was closed again at once (a flash).
     */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        measureChildWithMargins(keys, widthMeasureSpec, 0, heightMeasureSpec, 0)
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = keys.measuredHeight
        setMeasuredDimension(w, h)
        panel?.let {
            val lp = it.layoutParams as LayoutParams
            it.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(maxOf(0, h - lp.topMargin - lp.bottomMargin), MeasureSpec.EXACTLY))
        }
    }

    fun hidePanel() { panel?.let { removeView(it) }; panel = null }
    val panelShown get() = panel != null
    /** the panel on screen, if any */
    val current: View? get() = panel
}

/** Small helpers for programmatic panel UI in the keyboard's palette. */
internal object Ui {
    fun dp(v: View, x: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, x, v.resources.displayMetrics)

    fun iconButton(ctx: Context, icon: Int, tint: Int, desc: String, onClick: () -> Unit) = ImageButton(ctx).apply {
        setImageResource(icon); setColorFilter(tint); contentDescription = desc
        background = null
        val p = dp(this, 10f).toInt(); setPadding(p, p, p, p)
        setOnClickListener { onClick() }
    }

    fun header(ctx: Context, pal: Palette, title: String, onBack: () -> Unit, extra: View? = null): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(iconButton(ctx, R.drawable.ms_abc, pal.fg, "Back to the keyboard", onBack))
        addView(TextView(ctx).apply {
            text = title; setTextColor(pal.fg); setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (extra != null) addView(extra)
    }
}

/** Clipboard history: tap to paste, pin, delete. */
@SuppressLint("ViewConstructor")
class ClipboardPanel(ctx: Context, private val pal: Palette, private val store: ClipStore,
                     private val onPaste: (String) -> Unit, private val onBack: () -> Unit) : LinearLayout(ctx) {
    private val list = LinearLayout(ctx).apply { orientation = VERTICAL }

    init {
        orientation = VERTICAL
        setBackgroundColor(pal.bg)
        addView(Ui.header(ctx, pal, "Clipboard", onBack, TextView(ctx).apply {
            text = "Clear"; setTextColor(pal.accent); setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            val p = Ui.dp(this, 12f).toInt(); setPadding(p, p, p, p)
            setOnClickListener { store.clear(); refresh() }
        }))
        addView(ScrollView(ctx).apply { addView(list) }, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        refresh()
    }

    fun refresh() {
        list.removeAllViews()
        if (store.items.isEmpty()) {
            list.addView(TextView(context).apply {
                text = "Copied text shows up here. Nothing is kept from password or sensitive fields."
                setTextColor(pal.dim); gravity = Gravity.CENTER
                val p = Ui.dp(this, 24f).toInt(); setPadding(p, p, p, p)
            })
            return
        }
        for (item in store.items) {
            val row = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = GradientDrawable().apply { setColor(pal.key); cornerRadius = Ui.dp(this@ClipboardPanel, 12f) }
                setOnClickListener { onPaste(item.text) }
            }
            row.addView(TextView(context).apply {
                text = item.text.replace('\n', ' '); maxLines = 2; ellipsize = TextUtils.TruncateAt.END
                setTextColor(pal.fg); setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                val p = Ui.dp(this, 12f).toInt(); setPadding(p, p, 0, p)
            }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            row.addView(Ui.iconButton(context, if (item.pinned) R.drawable.ms_pin_on else R.drawable.ms_pin,
                if (item.pinned) pal.accent else pal.dim, if (item.pinned) "Unpin" else "Pin") { store.togglePin(item); refresh() })
            row.addView(Ui.iconButton(context, R.drawable.ms_delete, pal.dim, "Delete") { store.delete(item); refresh() })
            val lp = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            val m = Ui.dp(this, 6f).toInt(); lp.setMargins(m * 2, m / 2, m * 2, m / 2)
            list.addView(row, lp)
        }
    }
}

/** Emoji from assets/emoji.tsv (Unicode emoji-test.txt), only those the system font can draw. */
object EmojiData {
    val TABS = listOf("smileys" to "😀", "people" to "👋", "nature" to "🐶", "food" to "🍔", "travel" to "✈️",
        "activities" to "⚽", "objects" to "💡", "symbols" to "❤️", "flags" to "🏁")
    @Volatile private var data: Map<String, List<String>>? = null

    fun get(ctx: Context): Map<String, List<String>> {
        data?.let { return it }
        val paint = android.graphics.Paint()
        val m = LinkedHashMap<String, MutableList<String>>()
        ctx.assets.open("emoji.tsv").bufferedReader().useLines { lines ->
            for (l in lines) {
                val p = l.split('\t')
                if (p.size < 2) continue
                if (!paint.hasGlyph(p[1])) continue
                m.getOrPut(p[0]) { ArrayList() }.add(p[1])
            }
        }
        data = m
        return m
    }
}

/** Grid of emoji drawn on one canvas (thousands of TextViews would be slow). */
@SuppressLint("ViewConstructor")
class EmojiGrid(ctx: Context, pal: Palette, private val onPick: (String) -> Unit) : View(ctx) {
    var items: List<String> = emptyList()
        set(v) { field = v; requestLayout(); invalidate() }
    private val cell = Ui.dp(this, 46f)
    private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        textSize = Ui.dp(this@EmojiGrid, 28f); textAlign = android.graphics.Paint.Align.CENTER; color = pal.fg
    }
    private var cols = 8
    private var downAt = -1

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        cols = maxOf(1, (w / cell).toInt())
        val rows = (items.size + cols - 1) / cols
        setMeasuredDimension(w, (rows * cell).toInt() + Ui.dp(this, 8f).toInt())
    }

    override fun onDraw(c: android.graphics.Canvas) {
        val cw = width.toFloat() / cols
        for ((i, e) in items.withIndex()) {
            val x = (i % cols) * cw + cw / 2
            val y = (i / cols) * cell + cell / 2 + paint.textSize / 3
            c.drawText(e, x, y, paint)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: android.view.MotionEvent): Boolean {
        val cw = width.toFloat() / cols
        val i = (e.y / cell).toInt() * cols + (e.x / cw).toInt().coerceIn(0, cols - 1)
        when (e.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> downAt = i
            android.view.MotionEvent.ACTION_UP -> {
                if (i == downAt && i in items.indices) { performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP); onPick(items[i]) }
                downAt = -1
            }
            android.view.MotionEvent.ACTION_CANCEL -> downAt = -1
        }
        return true
    }
}

/** Emoji panel: recents + Unicode categories, backspace, back to the keys. */
@SuppressLint("ViewConstructor")
class EmojiPanel(ctx: Context, private val pal: Palette, private val data: Map<String, List<String>>,
                 private val recents: () -> List<String>, onPick: (String) -> Unit, onBackspace: () -> Unit, onBack: () -> Unit) : LinearLayout(ctx) {
    private val grid = EmojiGrid(ctx, pal) { e -> onPick(e) }
    private val scroll = ScrollView(ctx).apply { addView(grid) }
    private val tabViews = ArrayList<TextView>()

    init {
        orientation = VERTICAL
        setBackgroundColor(pal.bg)
        val bar = LinearLayout(ctx).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        bar.addView(Ui.iconButton(ctx, R.drawable.ms_abc, pal.fg, "Back to the keyboard", onBack))
        val tabs = LinearLayout(ctx).apply { orientation = HORIZONTAL }
        val all = listOf("recent" to "🕘") + EmojiData.TABS
        for ((key, label) in all) {
            val tv = TextView(ctx).apply {
                text = label; setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f); gravity = Gravity.CENTER
                val p = Ui.dp(this, 8f).toInt(); setPadding(p, p, p, p)
                setOnClickListener { select(key) }
                tag = key
            }
            tabViews.add(tv); tabs.addView(tv)
        }
        bar.addView(android.widget.HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; addView(tabs) },
            LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(Ui.iconButton(ctx, R.drawable.ms_backspace, pal.fg, "Delete", onBackspace))
        addView(bar)
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        select(if (recents().isNotEmpty()) "recent" else "smileys")
    }

    private fun select(key: String) {
        grid.items = if (key == "recent") recents() else data[key].orEmpty()
        scroll.scrollTo(0, 0)
        for (t in tabViews) t.background = if (t.tag == key) GradientDrawable().apply {
            setColor(pal.accentSoft); cornerRadius = Ui.dp(this@EmojiPanel, 12f)
        } else null
    }
}
