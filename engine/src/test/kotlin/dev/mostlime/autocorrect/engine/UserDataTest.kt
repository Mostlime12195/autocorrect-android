package dev.mostlime.autocorrect.engine

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UserDataTest {
    @Test fun dictionaryRulesAndFiles() {
        val dir = Files.createTempDirectory("ud").toFile()
        val u = UserData(File(dir, "d/words.txt"), File(dir, "d/learn.tsv"))
        assertTrue(u.add("McDonald"))
        assertTrue(u.add("e-bike"))
        assertTrue(u.add("rock'n'roll"))
        assertFalse(u.add("two words"))
        assertFalse(u.add("x".repeat(49)))
        assertFalse(u.add("!!"))
        assertTrue(u.has("mcdonald"))
        assertEquals(listOf("McDonald"), u.near("mcdonalf"))
        // the other process sees the file
        val v = UserData(File(dir, "d/words.txt"), File(dir, "d/learn.tsv"))
        assertEquals(setOf("McDonald", "e-bike", "rock'n'roll"), v.words().toSet())
        u.remove("e-bike")
        v.reloadIfChanged()
        assertFalse(v.has("e-bike"))
        u.clearAll()
        assertEquals(0, u.size)
        dir.deleteRecursively()
    }

    @Test fun dictionaryWordsAreSwipeTargets() {
        val u = UserData(null, null)
        val d = SwipeDecoder(Fixtures.gen.lex).also { it.user = u }
        val path = KeyGeom.keyTouches("zorbix")
        assertTrue(d.decode(path).none { it.word == "zorbix" })
        u.add("Zorbix")
        assertEquals("Zorbix", d.decode(path).first().word)
    }

    /** long contexts are clipped in chunks: the window start stays put while the text grows, so the KV cache is reused */
    @Test fun longContextClipsInChunks() {
        assertEquals(0, NeuralScorer.dropFor(900, 1000))
        assertEquals(1, NeuralScorer.dropFor(65, 64))                 // short windows: exact
        val starts = (1000..1400).map { NeuralScorer.dropFor(it, 1000) }.distinct()
        assertTrue(starts.size <= 3, "$starts")                       // a few jumps of 250, not one per token
        assertTrue((1000..1400).all { n -> n - NeuralScorer.dropFor(n, 1000) in 750..1000 })
    }

    @Test fun suppressionsExpire() {
        var t = 1_000_000L
        val u = UserData(null, null) { t }
        u.suppress("their", "there")
        assertEquals(UserData.SUPPRESS_NATS, u.penalty("Their", "There"))
        assertEquals(0.0, u.penalty("there", "their"))
        t += UserData.SUPPRESS_DAYS * 86_400_000L + 1
        assertEquals(0.0, u.penalty("their", "there"))
    }

    @Test fun autoCapOffNeverAddsSentenceCapitals() {
        val c = Corrector(Fixtures.gen, Fixtures.gen2)
        val lm = LmScorer { reqs -> DoubleArray(reqs.size) { i -> if (reqs[i].cont.any { it.isUpperCase() }) -1.0 else -6.0 } }
        val p = DecodeParams(1.25, 0.0, 0.0, 1.25)
        assertEquals("Hello", c.correctWord(WordInput("hello", null, "", ""), lm, p, null, formal = true, autoCap = true).out)
        assertEquals("hello", c.correctWord(WordInput("hello", null, "", ""), lm, p, null, formal = true, autoCap = false).out)
        // i -> I is not a sentence capital: it stays with the style
        assertEquals("I", c.correctWord(WordInput("i", null, "so", " "), lm, p, null, formal = true, autoCap = false).out)
    }

    /** formal step D used to put a full stop after "does" from one word of right context */
    @Test fun formalNeverEndsASentenceMidWay() {
        val c = Corrector(Fixtures.gen, Fixtures.gen2)
        // an LM that loves a sentence break after "does"
        val lm = LmScorer { reqs -> DoubleArray(reqs.size) { i -> val t = reqs[i].left + reqs[i].cont; if ("does." in t || "does!" in t || "does?" in t) -1.0 else -8.0 } }
        val p = DecodeParams(1.25, 0.0, 0.0, 1.25)
        val words = "it does my commas for me".split(' ')
        val outs = ArrayList<String>()
        var prev: PrevWord? = null
        for ((t, w) in words.withIndex()) {
            val left = outs.joinToString(" ")
            val r = c.correctWord(WordInput(w, null, left, if (t == 0) "" else " "), lm, p, prev, formal = true)
            if (r.prevText != null) outs[t - 1] = r.prevText!!
            outs.add(r.out)
            prev = PrevWord(r.slot, r.chosen, outs.dropLast(1).joinToString(" "), if (t == 0) "" else " ", r.lmScored, r.margin, r.scores)
        }
        val text = outs.joinToString(" ")
        assertFalse(text.contains('.') || text.contains('!') || text.contains('?'), text)
    }

    @Test fun dictionaryWordsAreKeptByTheCorrector() {
        val u = UserData(null, null)
        val c = Corrector(Fixtures.gen, Fixtures.gen2, user = u)
        val lm = LmScorer { reqs -> DoubleArray(reqs.size) { i -> if ("fixed" in reqs[i].cont) -2.0 else -14.0 } }
        val p = DecodeParams(1.25, 0.0, 0.0, 1.25)
        assertEquals("fixed", c.correctWord(WordInput("fized", null, "it is", " "), lm, p, null).out)
        u.add("fized")
        assertEquals("fized", c.correctWord(WordInput("fized", null, "it is", " "), lm, p, null).out)
        // rewinds skip dictionary words
        u.add("too")
        val t = RewindTarget(0, "too", "", "", "the store", 1)
        assertTrue(Rewind.check(listOf(t), LmScorer { r -> DoubleArray(r.size) { i -> if (r[i].cont.startsWith("to ")) 0.0 else -20.0 } }, 1.25, user = u).isEmpty())
    }
}
