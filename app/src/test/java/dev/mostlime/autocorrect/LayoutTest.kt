package dev.mostlime.autocorrect

import dev.mostlime.autocorrect.ime.KeyType
import dev.mostlime.autocorrect.ime.KeyboardLayout
import org.junit.Assert.assertTrue
import org.junit.Test

class LayoutTest {
    /** Every printable ASCII character can be typed: a key on some layer, shift for capitals, or a long-press alternate. */
    @Test fun everyPrintableAsciiIsReachable() {
        val layers = listOf(KeyboardLayout.LETTERS, KeyboardLayout.SYMBOLS, KeyboardLayout.SYMBOLS2)
        val reach = HashSet<String>()
        for (l in layers) for (k in l.keys) {
            if (k.type == KeyType.SPACE) reach.add(" ")
            if (k.type != KeyType.CHAR) continue
            reach.add(k.text); reach.add(k.text.uppercase())
            reach.addAll(KeyboardLayout.alternates(k))
        }
        val missing = (0x20..0x7e).map { it.toChar().toString() }.filter { it !in reach }
        assertTrue("unreachable: $missing", missing.isEmpty())
    }

    @Test fun letterGeometryMatchesTheModel() {
        // keyboard.py: row offsets 0 / 0.5 / 1.5, key width 1
        val k = KeyboardLayout.LETTERS.keys.associateBy { it.text }
        assertTrue(k["q"]!!.x == 0f && k["a"]!!.x == 0.5f && k["z"]!!.x == 1.5f && k["m"]!!.x == 7.5f)
    }
}
