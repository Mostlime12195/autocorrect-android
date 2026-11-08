package dev.mostlime.autocorrect

import dev.mostlime.autocorrect.ime.KeyType
import dev.mostlime.autocorrect.ime.KeyboardLayout
import dev.mostlime.autocorrect.ime.LayoutOptions
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

    @Test fun longPressPopupsStartWithTheHint() {
        val k = { c: String -> KeyboardLayout.LETTERS.keys.first { it.text == c } }
        assertTrue(KeyboardLayout.alternates(k("e")) == listOf("3", "è", "é", "ê", "ë", "ē", "ė", "ę"))
        assertTrue(KeyboardLayout.alternates(k("a")) == listOf("@", "à", "á", "â", "ä", "æ", "ã", "å", "ā"))
        // every letter has a one-character hint (the default when released without sliding)
        for (c in 'a'..'z') assertTrue("$c", KeyboardLayout.alternates(k(c.toString())).firstOrNull()?.length == 1)
    }

    @Test fun everyLayoutHasAllLettersInsideTheKeyboard() {
        for (d in KeyboardLayout.LAYOUTS) for (o in listOf(LayoutOptions(d.id), LayoutOptions(d.id, numberRow = true, emojiKey = true, arrows = true))) {
            val l = KeyboardLayout.letters(o)
            val c = KeyboardLayout.centers(l)
            assertTrue("${d.id}: ${('a'..'z').filter { it !in c }}", ('a'..'z').all { it in c })
            assertTrue(d.id, l.keys.all { it.x >= 0f && it.x + it.w <= 10.001f })
            val bar = KeyboardLayout.spaceBar(l)
            assertTrue(bar[1] > bar[0] + 2)
        }
        // QWERTY keeps the trained geometry: q at (0.5, 0.7), a at (1.0, 2.1), z at (2.0, 3.5)
        val q = KeyboardLayout.centers(KeyboardLayout.LETTERS)
        fun near(c: Char, x: Double, y: Double) = kotlin.math.abs(q[c]!!.first - x) < 1e-9 && kotlin.math.abs(q[c]!!.second - y) < 1e-9
        assertTrue(near('q', 0.5, 0.7) && near('a', 1.0, 2.1) && near('z', 2.0, 3.5))
    }

    @Test fun customLongPressEntries() {
        val m = KeyboardLayout.parseExtra("e: € :) ✓\nnot a line\nh: haha")
        assertTrue(m["e"] == listOf("€", ":)", "✓") && m["h"] == listOf("haha"))
        KeyboardLayout.extra = m
        val e = KeyboardLayout.LETTERS.keys.first { it.text == "e" }
        assertTrue(KeyboardLayout.alternates(e).takeLast(3) == listOf("€", ":)", "✓"))
        KeyboardLayout.extra = emptyMap()
        assertTrue("/" in KeyboardLayout.alternates(KeyboardLayout.LETTERS.keys.first { it.text == "." }))
    }

    @Test fun numberRowKeepsLetterTouchesInModelUnits() {
        val l = KeyboardLayout.LETTERS_NUM
        val q = l.keys.first { it.text == "q" }
        assertTrue(q.row == 1 && l.rows == 5)
        // a touch in the middle of q (row 1 on screen) is row 0 (y = 0.7) for the spatial model
        val t = KeyboardLayout.modelTouch(0.5f, 1.5f, q, l.letterRowOffset)
        assertTrue(t.x == 0.5 && kotlin.math.abs(t.y - 0.7) < 1e-9)
        assertTrue(l.keys.count { it.text in listOf("1", "0") } == 2)
    }

    @Test fun letterGeometryMatchesTheModel() {
        // keyboard.py: row offsets 0 / 0.5 / 1.5, key width 1
        val k = KeyboardLayout.LETTERS.keys.associateBy { it.text }
        assertTrue(k["q"]!!.x == 0f && k["a"]!!.x == 0.5f && k["z"]!!.x == 1.5f && k["m"]!!.x == 7.5f)
    }
}
