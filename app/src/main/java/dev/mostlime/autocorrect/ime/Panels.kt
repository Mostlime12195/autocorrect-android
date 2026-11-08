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

    fun hidePanel() { panel?.let { removeView(it) }; panel = null }
    val panelShown get() = panel != null
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
