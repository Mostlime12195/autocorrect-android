package dev.mostlime.autocorrect.engine

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CorrectorTest {
    private val p = DecodeParams(lam = 1.25, bias = 0.0, margin = 0.0, lamR = 1.25)
    private fun corr(cfg: CorrectorConfig = CorrectorConfig()) = Corrector(Fixtures.gen, Fixtures.gen2, cfg)

    /** LM that likes exactly the given continuations. */
    private fun lm(vararg liked: String) = LmScorer { reqs -> DoubleArray(reqs.size) { if (liked.any { l -> reqs[it].cont.endsWith(l) }) -1.0 else -12.0 } }

    @Test fun lmOnlyScoresTopK() {
        val c = corr(CorrectorConfig(lmTopK = 6))
        var seen = 0
        val r = c.correctWord(WordInput("thnaks", KeyGeom.keyTouches("thnaks"), "hey", " "), { reqs -> seen = reqs.size; DoubleArray(reqs.size) { -5.0 } }, p, null)
        assertTrue(seen in 7..12, "scored $seen")
        assertEquals(seen, r.nScored)
        assertTrue(r.slot.cands.contains("thnaks"), "typed string always scored")
    }

    @Test fun confidentExactHitSkipsLm() {
        val c = corr()
        val r = c.correctWord(WordInput("because", KeyGeom.keyTouches("because"), "", ""), { error("LM should be skipped") }, p, null)
        assertTrue(r.skipped); assertEquals("because", r.out)
        // a confusion-set word never skips
        val r2 = c.correctWord(WordInput("there", KeyGeom.keyTouches("there"), "over", " "), lm(" there"), p, null)
        assertFalse(r2.skipped)
        assertEquals(1L, c.stats.lmSkipped)
    }

    @Test fun cheapPathWithoutModel() {
        // "thw" with the last touch near the w/e border: a clear typo for "the"
        val t = KeyGeom.keyTouches("th") + Touch(2.1, 0.7)
        val r = corr().correctWord(WordInput("thw", t, "", ""), null, p, null)
        assertTrue(r.cheap); assertEquals("the", r.out)
        val keep = corr().correctWord(WordInput("hello", KeyGeom.keyTouches("hello"), "", ""), null, p, null)
        assertEquals("hello", keep.out)
    }

    @Test fun recheckAndMerge() {
        val c = corr()
        val prevSlot = c.candidates("some", KeyGeom.keyTouches("some"))
        val prev = PrevWord(prevSlot, prevSlot.typedIdx, "i need", " ", lmScored = true)
        val r = c.correctWord(WordInput("thing", KeyGeom.keyTouches("thing"), "i need some", " "), lm(" something"), p, prev,
            merge = MergeInput("some", KeyGeom.keyTouches("some")))
        assertNotNull(r.merge); assertEquals("something", r.merge!!.to)
        // no merge when the LM prefers the two words
        val r2 = c.correctWord(WordInput("thing", KeyGeom.keyTouches("thing"), "i need some", " "), lm(" some thing", " thing"), p, prev,
            merge = MergeInput("some", KeyGeom.keyTouches("some")))
        assertNull(r2.merge)
    }

    @Test fun v2SplitsAndContractions() {
        val c = corr()
        val s = c.candidates("inaminute", KeyGeom.keyTouches("inaminute"))
        assertTrue(s.cands.contains("in a minute"), s.cands.toString())
        val im = c.candidates("im", KeyGeom.keyTouches("im"))
        assertTrue(im.cands.contains("i'm"))
        assertFalse(c.canSkip(im))
    }
}
