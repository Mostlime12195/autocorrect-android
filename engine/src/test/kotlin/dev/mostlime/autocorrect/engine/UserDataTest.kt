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

    @Test fun suppressionsExpire() {
        var t = 1_000_000L
        val u = UserData(null, null) { t }
        u.suppress("their", "there")
        assertEquals(UserData.SUPPRESS_NATS, u.penalty("Their", "There"))
        assertEquals(0.0, u.penalty("there", "their"))
        t += UserData.SUPPRESS_DAYS * 86_400_000L + 1
        assertEquals(0.0, u.penalty("their", "there"))
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
