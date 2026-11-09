package dev.mostlime.autocorrect.ui

import android.os.Bundle
import android.provider.UserDictionary
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import dev.mostlime.autocorrect.App
import dev.mostlime.autocorrect.R
import dev.mostlime.autocorrect.engine.UserData

/**
 * Personal dictionary: words the keyboard never corrects, flags or rewinds, and offers for near-miss typos. Search
 * (the same box adds), tap a word to delete it, clear all, and import from Android's personal dictionary where the
 * system allows it (only the current keyboard may read it).
 */
class DictionaryActivity : AppCompatActivity() {
    private val user get() = App.instance.user
    private lateinit var adapter: ArrayAdapter<String>
    private lateinit var box: TextInputEditText
    private lateinit var empty: TextView
    /** words behind the list rows (rows of probation words carry a "learning" note) */
    private var shownWords: List<String> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dp = resources.displayMetrics.density
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val bar = MaterialToolbar(this).apply {
            title = "Personal dictionary"
            setNavigationIcon(R.drawable.ms_back)
            navigationIcon?.setTint(com.google.android.material.color.MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface))
            setNavigationOnClickListener { finish() }
            menu.add("Import from Android").setOnMenuItemClickListener { importSystem(); true }
            menu.add("Clear all").setOnMenuItemClickListener { clearAll(); true }
        }
        root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding((16 * dp).toInt(), 0, (16 * dp).toInt(), (8 * dp).toInt()) }
        val til = TextInputLayout(this, null, com.google.android.material.R.attr.textInputOutlinedStyle).apply { hint = "Search or add a word" }
        box = TextInputEditText(til.context).apply { isSingleLine = true }
        til.addView(box)
        row.addView(til, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val add = MaterialButton(this).apply { text = "Add"; setOnClickListener { addTyped() } }
        row.addView(add, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginStart = (8 * dp).toInt(); gravity = android.view.Gravity.CENTER_VERTICAL
        })
        root.addView(row)
        empty = TextView(this).apply { setPadding((24 * dp).toInt(), (16 * dp).toInt(), (24 * dp).toInt(), 0) }
        root.addView(empty)
        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, ArrayList())
        val list = ListView(this).apply {
            this.adapter = this@DictionaryActivity.adapter
            setOnItemClickListener { _, _, pos, _ -> confirmDelete(shownWords.getOrNull(pos) ?: return@setOnItemClickListener) }
        }
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(b.left, b.top, b.right, b.bottom); insets
        }
        box.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) = refresh()
        })
        box.setOnEditorActionListener { _, _, _ -> addTyped(); true }
    }

    override fun onResume() {
        super.onResume()
        user.reloadIfChanged()
        refresh()
    }

    private fun refresh() {
        val q = box.text?.toString()?.trim().orEmpty().lowercase()
        val learning = user.probationWords().toSet()
        val all = (user.words() + learning).sortedBy { it.lowercase() }
        val shown = if (q.isEmpty()) all else all.filter { q in it.lowercase() }
        shownWords = shown
        adapter.clear(); adapter.addAll(shown.map { if (it in learning) "$it  ·  learning (from your undos)" else it })
        empty.text = when {
            all.isEmpty() -> "No words yet. Add one above, tap \"not a typo\" on an underlined word, or long-press the word you typed in the suggestion strip."
            shown.isEmpty() -> "No match. Tap Add to add it."
            else -> "${all.size} word${if (all.size == 1) "" else "s"} · tap one to delete it"
        }
        empty.visibility = View.VISIBLE
    }

    private fun addTyped() {
        val w = box.text?.toString()?.trim().orEmpty()
        if (w.isEmpty()) return
        if (!UserData.valid(w)) {
            Toast.makeText(this, "Use letters, digits, ' or - (up to ${UserData.MAX_LEN} characters)", Toast.LENGTH_SHORT).show(); return
        }
        user.add(w)
        box.setText("")
        refresh()
    }

    private fun confirmDelete(w: String) {
        MaterialAlertDialogBuilder(this).setTitle("Delete “$w”?")
            .setPositiveButton("Delete") { _, _ -> user.remove(w); refresh() }
            .setNegativeButton("Cancel", null).show()
    }

    private fun clearAll() {
        MaterialAlertDialogBuilder(this).setTitle("Clear the dictionary?")
            .setMessage("Deletes every word in your personal dictionary.")
            .setPositiveButton("Clear") { _, _ -> user.clearWords(); refresh() }
            .setNegativeButton("Cancel", null).show()
    }

    /** Android's own personal dictionary: readable only by the current keyboard (API 23+), and empty on many phones. */
    private fun importSystem() {
        val words = try {
            contentResolver.query(UserDictionary.Words.CONTENT_URI, arrayOf(UserDictionary.Words.WORD), null, null, null)?.use { c ->
                val out = ArrayList<String>()
                while (c.moveToNext()) c.getString(0)?.let { out.add(it) }
                out
            }
        } catch (_: Exception) { null }
        val msg = when {
            words == null -> "Android's personal dictionary isn't available. Make this the current keyboard and try again."
            words.isEmpty() -> "Android's personal dictionary is empty."
            else -> { val n = user.addAll(words); "Imported $n new word${if (n == 1) "" else "s"}." }
        }
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        refresh()
    }
}
