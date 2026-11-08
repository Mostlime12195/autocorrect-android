package dev.mostlime.autocorrect.ime

import dev.mostlime.autocorrect.engine.KeyGeom
import dev.mostlime.autocorrect.engine.Touch

enum class KeyType { CHAR, SHIFT, BACKSPACE, SPACE, ENTER, TO_SYMBOLS, TO_LETTERS, TO_SYMBOLS2 }

/** x / w in key widths (10 per row), row 0..3 from the top of the key area. */
class KeyDef(val label: String, val text: String, val type: KeyType, val x: Float, val w: Float, val row: Int) {
    val cx get() = x + w / 2
}

class Layer(val keys: List<KeyDef>) {
    /** The key under (ux, row units) -- nearest key in the row so gaps and edges still hit something. */
    fun keyAt(ux: Float, uy: Float): KeyDef? {
        val row = uy.toInt().coerceIn(0, 3)
        val inRow = keys.filter { it.row == row }
        return inRow.firstOrNull { ux >= it.x && ux < it.x + it.w } ?: inRow.minByOrNull { kotlin.math.abs(it.cx - ux) }
    }
}

/**
 * The layouts. The letter layer reproduces the geometry keyboard.py / keyboard.js model exactly (key width 1, rows
 * offset 0 / 0.5 / 1.5), so a touch maps to the model's units as x = px / keyWidth, y = (py / rowHeight) * ROW_H.
 * Bottom row: ?123 (1.5) , (1) space (2.5 .. 7.5) . (1) enter (1.5).
 */
object KeyboardLayout {
    private fun row(chars: String, r: Int, x0: Float) = chars.mapIndexed { i, c -> KeyDef(c.toString(), c.toString(), KeyType.CHAR, x0 + i, 1f, r) }

    private fun bottom(left: KeyDef) = listOf(
        left,
        KeyDef(",", ",", KeyType.CHAR, 1.5f, 1f, 3),
        KeyDef("space", " ", KeyType.SPACE, 2.5f, 5f, 3),
        KeyDef(".", ".", KeyType.CHAR, 7.5f, 1f, 3),
        KeyDef("⏎", "\n", KeyType.ENTER, 8.5f, 1.5f, 3),
    )

    val LETTERS = Layer(
        row("qwertyuiop", 0, 0f) + row("asdfghjkl", 1, 0.5f) +
            listOf(KeyDef("⇧", "", KeyType.SHIFT, 0f, 1.5f, 2)) + row("zxcvbnm", 2, 1.5f) +
            listOf(KeyDef("⌫", "", KeyType.BACKSPACE, 8.5f, 1.5f, 2)) +
            bottom(KeyDef("?123", "", KeyType.TO_SYMBOLS, 0f, 1.5f, 3))
    )

    val SYMBOLS = Layer(
        row("1234567890", 0, 0f) + row("@#\$_&-+()/", 1, 0f) +
            listOf(KeyDef("=\\<", "", KeyType.TO_SYMBOLS2, 0f, 1.5f, 2)) + row("*\"':;!?", 2, 1.5f) +
            listOf(KeyDef("⌫", "", KeyType.BACKSPACE, 8.5f, 1.5f, 2)) +
            bottom(KeyDef("ABC", "", KeyType.TO_LETTERS, 0f, 1.5f, 3))
    )

    val SYMBOLS2 = Layer(
        row("~`|•√π÷×¶∆", 0, 0f) + row("£¢€¥^°={}\\", 1, 0f) +
            listOf(KeyDef("?123", "", KeyType.TO_SYMBOLS, 0f, 1.5f, 2)) + row("%©®™✓[]", 2, 1.5f) +
            listOf(KeyDef("⌫", "", KeyType.BACKSPACE, 8.5f, 1.5f, 2)) +
            bottom(KeyDef("ABC", "", KeyType.TO_LETTERS, 0f, 1.5f, 3))
    )

    /** Touch in key-area units (ux key widths, uy rows) -> model units. Letters only; others give the APOS sentinel. */
    fun modelTouch(ux: Float, uy: Float, key: KeyDef): Touch =
        if (key.type == KeyType.CHAR && key.text.length == 1 && key.text[0].lowercaseChar() in 'a'..'z')
            Touch(ux.toDouble(), uy.toDouble() * KeyGeom.ROW_H)
        else Touch.APOS
}
