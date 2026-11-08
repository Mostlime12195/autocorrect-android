package dev.mostlime.autocorrect.ime

import dev.mostlime.autocorrect.engine.KeyGeom
import dev.mostlime.autocorrect.engine.Touch

enum class KeyType { CHAR, SHIFT, BACKSPACE, SPACE, ENTER, TO_SYMBOLS, TO_LETTERS, TO_SYMBOLS2, EMOJI, CURSOR }

/** x / w in key widths (10 per row), row 0..3 from the top of the key area. hint: the long-press default (top-right). */
class KeyDef(val label: String, val text: String, val type: KeyType, val x: Float, val w: Float, val row: Int, val hint: String? = null) {
    val cx get() = x + w / 2
}

class Layer(val keys: List<KeyDef>, val isLetters: Boolean = false, /** rows above the q row (number row) */ val letterRowOffset: Int = 0) {
    val rows = keys.maxOf { it.row } + 1

    /** The key under (ux, row units) -- nearest key in the row so gaps and edges still hit something. */
    fun keyAt(ux: Float, uy: Float): KeyDef? {
        val row = uy.toInt().coerceIn(0, rows - 1)
        val inRow = keys.filter { it.row == row }
        return inRow.firstOrNull { ux >= it.x && ux < it.x + it.w } ?: inRow.minByOrNull { kotlin.math.abs(it.cx - ux) }
    }
}

/** A letter layout: three rows of keys (letters, plus the odd punctuation key on Dvorak). */
class LayoutDef(val id: String, val label: String, val rows: List<String>)

/** Layout options from the settings (Appearance -> Layout). */
data class LayoutOptions(val layout: String = "qwerty", val numberRow: Boolean = false, val emojiKey: Boolean = false,
                         val commaKey: Boolean = true, val arrows: Boolean = false)

/**
 * The layouts, as small tables. QWERTY reproduces the geometry keyboard.py / keyboard.js model exactly (key width 1,
 * rows offset 0 / 0.5 / 1.5), so a touch maps to the model's units as x = px / keyWidth, y = (py / rowHeight) * ROW_H;
 * other layouts move the letter centres (KeyGeom.setCenters) so the spatial model and swipe templates follow them.
 * Bottom row: ?123 (1.5), [emoji (1)], [, (1)], space, . (1), enter (1.5); optional cursor-key row under it.
 */
object KeyboardLayout {
    val LAYOUTS = listOf(
        LayoutDef("qwerty", "QWERTY", listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")),
        LayoutDef("qwertz", "QWERTZ", listOf("qwertzuiop", "asdfghjkl", "yxcvbnm")),
        LayoutDef("azerty", "AZERTY", listOf("azertyuiop", "qsdfghjklm", "wxcvbn")),
        LayoutDef("dvorak", "Dvorak", listOf("',.pyfgcrl", "aoeuidhtns", "qjkxbmwvz")),
        LayoutDef("colemak", "Colemak", listOf("qwfpgjluy", "arstdhneio", "zxcvbkm")),
    )
    fun layoutDef(id: String) = LAYOUTS.firstOrNull { it.id == id } ?: LAYOUTS[0]

    /** long-press hints by position: digits on the top row, Gboard's symbols on the two rows under it */
    private val HINTS = listOf("1234567890", "@#\$_&-+()/", "*\"':;!?")

    private fun row(chars: String, r: Int, x0: Float, w: Float = 1f, hints: String? = null) =
        chars.mapIndexed { i, c -> KeyDef(c.toString(), c.toString(), KeyType.CHAR, x0 + i * w, w, r, hints?.getOrNull(i)?.toString()) }

    private fun bottom(left: KeyDef, o: LayoutOptions, r: Int = 3): List<KeyDef> {
        val out = arrayListOf(left)
        var x = 1.5f
        if (o.emojiKey) { out.add(KeyDef("☺", "", KeyType.EMOJI, x, 1f, r)); x += 1f }
        if (o.commaKey) { out.add(KeyDef(",", ",", KeyType.CHAR, x, 1f, r)); x += 1f }
        out.add(KeyDef("space", " ", KeyType.SPACE, x, 7.5f - x, r))
        out.add(KeyDef(".", ".", KeyType.CHAR, 7.5f, 1f, r))
        out.add(KeyDef("⏎", "\n", KeyType.ENTER, 8.5f, 1.5f, r))
        if (o.arrows) out.addAll(listOf("◀" to "L", "▲" to "U", "▼" to "D", "▶" to "R").mapIndexed { i, (l, t) ->
            KeyDef(l, t, KeyType.CURSOR, i * 2.5f, 2.5f, r + 1) })
        return out
    }

    /** a row of n keys centred in 10 key widths */
    private fun centred(chars: String, r: Int, hints: String?) = row(chars, r, (10 - chars.length) / 2f, 1f, hints)

    fun letters(o: LayoutOptions): Layer {
        val d = layoutDef(o.layout)
        val off = if (o.numberRow) 1 else 0
        val r2 = d.rows[2]
        // the third row sits between shift (0 .. 1.5) and backspace (8.5 .. 10): narrower keys when more than 7
        val w2 = if (r2.length <= 7) 1f else 7f / r2.length
        val x2 = 1.5f + (7f - r2.length * w2) / 2
        val keys = ArrayList<KeyDef>()
        if (o.numberRow) keys.addAll(row("1234567890", 0, 0f))
        keys.addAll(centred(d.rows[0], off, if (o.numberRow) null else HINTS[0]))
        keys.addAll(centred(d.rows[1], off + 1, HINTS[1]))
        keys.add(KeyDef("⇧", "", KeyType.SHIFT, 0f, 1.5f, off + 2))
        keys.addAll(row(r2, off + 2, x2, w2, HINTS[2]))
        keys.add(KeyDef("⌫", "", KeyType.BACKSPACE, 8.5f, 1.5f, off + 2))
        keys.addAll(bottom(KeyDef("?123", "", KeyType.TO_SYMBOLS, 0f, 1.5f, off + 3), o, off + 3))
        return Layer(keys, isLetters = true, letterRowOffset = off)
    }

    /** letter centres in model units (KeyGeom.setCenters) for a letters layer */
    fun centers(l: Layer): Map<Char, Pair<Double, Double>> = l.keys.filter { it.type == KeyType.CHAR && it.text.length == 1 && it.text[0] in 'a'..'z' }
        .associate { it.text[0] to (it.cx.toDouble() to ((it.row - l.letterRowOffset) * KeyGeom.ROW_H + KeyGeom.ROW_H / 2)) }

    /** the space bar's x extent (key units), for the space-miss candidates */
    fun spaceBar(l: Layer): DoubleArray = l.keys.first { it.type == KeyType.SPACE }.let { doubleArrayOf(it.x.toDouble(), (it.x + it.w).toDouble()) }

    val LETTERS = letters(LayoutOptions())
    /** Letters with the optional number row on top (the letter rows keep the model geometry, one row lower). */
    val LETTERS_NUM = letters(LayoutOptions(numberRow = true))
    fun letters(numberRow: Boolean) = if (numberRow) LETTERS_NUM else LETTERS

    fun symbols(o: LayoutOptions = LayoutOptions()) = Layer(
        row("1234567890", 0, 0f) + row("@#\$_&-+()/", 1, 0f) +
            listOf(KeyDef("=\\<", "", KeyType.TO_SYMBOLS2, 0f, 1.5f, 2)) + row("*\"':;!?", 2, 1.5f) +
            listOf(KeyDef("⌫", "", KeyType.BACKSPACE, 8.5f, 1.5f, 2)) +
            bottom(KeyDef("ABC", "", KeyType.TO_LETTERS, 0f, 1.5f, 3), o)
    )

    fun symbols2(o: LayoutOptions = LayoutOptions()) = Layer(
        row("~`|•√π÷×¶∆", 0, 0f) + row("£¢€¥^°={}\\", 1, 0f) +
            listOf(KeyDef("?123", "", KeyType.TO_SYMBOLS, 0f, 1.5f, 2)) + row("%<>©®[]", 2, 1.5f) +
            listOf(KeyDef("⌫", "", KeyType.BACKSPACE, 8.5f, 1.5f, 2)) +
            bottom(KeyDef("ABC", "", KeyType.TO_LETTERS, 0f, 1.5f, 3), o)
    )

    val SYMBOLS = symbols()
    val SYMBOLS2 = symbols2()

    /** Accented / alternate forms after the hint in the long-press popup (Gboard-like). */
    private val ALTS = mapOf(
        "w" to "ŵ", "e" to "è é ê ë ē ė ę", "r" to "ř", "t" to "ţ ť þ", "y" to "ý ÿ",
        "u" to "ù ú û ü ū", "i" to "ì í î ï ī į", "o" to "ò ó ô ö õ ø ō œ",
        "a" to "à á â ä æ ã å ā", "s" to "ß ś š ş", "d" to "ď ð", "g" to "ğ", "l" to "ł ľ",
        "z" to "ž ź ż", "c" to "ç ć č", "n" to "ñ ń ň",
        "." to "? ! … - / @ #",
    ).mapValues { it.value.split(' ') }

    /** user entries per key (settings: custom long-press), appended to the popups */
    @Volatile var extra: Map<String, List<String>> = emptyMap()

    /** Long-press popup for a key: its hint first (the default on release), then accents, then the user's own entries. */
    fun alternates(k: KeyDef): List<String> = if (k.type != KeyType.CHAR) emptyList() else
        (listOfNotNull(k.hint) + ALTS[k.text].orEmpty() + extra[k.text.lowercase()].orEmpty()).distinct()

    /**
     * Custom long-press text: one key per line, "key: entry entry ..." (entries are separated by spaces and can be
     * several characters, like "e: € :) ✓"). -> key -> entries.
     */
    fun parseExtra(text: String): Map<String, List<String>> = text.lines().mapNotNull { line ->
        val i = line.indexOf(':')
        if (i < 1) return@mapNotNull null
        val key = line.substring(0, i).trim().lowercase()
        val items = line.substring(i + 1).trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.take(12)
        if (key.length != 1 || items.isEmpty()) null else key to items
    }.toMap()

    /** Touch in key-area units (ux key widths, uy rows) -> model units. Letters only; others give the APOS sentinel. */
    fun modelTouch(ux: Float, uy: Float, key: KeyDef, rowOffset: Int = 0): Touch =
        if (key.type == KeyType.CHAR && key.text.length == 1 && key.text[0].lowercaseChar() in 'a'..'z')
            Touch(ux.toDouble(), (uy - rowOffset).toDouble() * KeyGeom.ROW_H)
        else Touch.APOS
}
