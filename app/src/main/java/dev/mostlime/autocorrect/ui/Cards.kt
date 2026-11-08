package dev.mostlime.autocorrect.ui

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.color.MaterialColors
import com.google.android.material.progressindicator.LinearProgressIndicator
import dev.mostlime.autocorrect.R

/** "Get started" card: enable the keyboard, switch to it, download the model. */
class SetupCard(ctx: Context, attrs: AttributeSet?) : Preference(ctx, attrs) {
    class Step(val title: String, val summary: String, val done: Boolean, val action: String?, val onClick: (() -> Unit)?)
    var steps: List<Step> = emptyList()
        set(v) { field = v; notifyChanged() }

    init { layoutResource = R.layout.pref_setup_card; isSelectable = false }

    override fun onBindViewHolder(h: PreferenceViewHolder) {
        super.onBindViewHolder(h)
        val all = steps.isNotEmpty() && steps.all { it.done }
        (h.findViewById(R.id.setup_title) as TextView).text = if (all) "You're all set" else "Get started"
        val ids = listOf(R.id.step1, R.id.step2, R.id.step3)
        for ((i, id) in ids.withIndex()) {
            val row = h.findViewById(id) ?: continue
            val s = steps.getOrNull(i)
            row.visibility = if (s == null) View.GONE else View.VISIBLE
            if (s == null) continue
            val icon = row.findViewById<ImageView>(R.id.icon)
            icon.setImageResource(if (s.done) R.drawable.ms_check_circle else R.drawable.ms_circle)
            icon.setColorFilter(MaterialColors.getColor(row, if (s.done) androidx.appcompat.R.attr.colorPrimary
                else com.google.android.material.R.attr.colorOutline))
            row.findViewById<TextView>(R.id.title).text = s.title
            row.findViewById<TextView>(R.id.summary).text = s.summary
            val b = row.findViewById<MaterialButton>(R.id.action)
            b.visibility = if (s.action == null || s.done) View.GONE else View.VISIBLE
            b.text = s.action
            b.setOnClickListener { s.onClick?.invoke() }
        }
    }
}

/** The active model: name, size, status, download progress and action. */
class ModelCard(ctx: Context, attrs: AttributeSet?) : Preference(ctx, attrs) {
    var name = ""; var size = ""; var status = ""
    /** -1 hidden, -2 indeterminate, else 0..100 */
    var progress = -1
    var action: String? = null
    var onAction: (() -> Unit)? = null

    init { layoutResource = R.layout.pref_model_card; isSelectable = false }

    fun update() = notifyChanged()

    override fun onBindViewHolder(h: PreferenceViewHolder) {
        super.onBindViewHolder(h)
        (h.findViewById(R.id.model_name) as TextView).text = name
        (h.findViewById(R.id.model_status) as TextView).text = status
        (h.findViewById(R.id.model_size) as Chip).text = size
        val icon = h.findViewById(R.id.model_icon) as ImageView
        icon.setColorFilter(MaterialColors.getColor(icon, androidx.appcompat.R.attr.colorPrimary))
        val p = h.findViewById(R.id.model_progress) as LinearProgressIndicator
        p.visibility = if (progress == -1) View.GONE else View.VISIBLE
        p.isIndeterminate = progress == -2
        if (progress >= 0) p.setProgressCompat(progress, true)
        val b = h.findViewById(R.id.model_action) as MaterialButton
        b.visibility = if (action == null) View.GONE else View.VISIBLE
        b.text = action
        b.setOnClickListener { onAction?.invoke() }
    }
}
