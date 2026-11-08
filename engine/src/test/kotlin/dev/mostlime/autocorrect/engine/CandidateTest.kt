package dev.mostlime.autocorrect.engine

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CandidateTest {
    private fun sameScores(exp: List<Double>, got: List<Double>, what: String, tol: Double = 2e-3) {
        assertEquals(exp.size, got.size, what)
        for (i in exp.indices) assertTrue(abs(exp[i] - got[i]) <= tol, "$what [$i]: ${exp[i]} vs ${got[i]}")
    }

    /** Python v1 candidate lists on key-centre touches (typing37k-style), 50 messages. */
    @Test fun keyCentreSlotsMatchPython() {
        val ref = Fixtures.json("pipeline_ref.json.gz").jsonObject
        val texts = ref["texts"]!!.jsonArray.map { it.jsonPrimitive.content }
        val cands = ref["cands"]!!.jsonArray
        var n = 0
        var ties = 0
        for ((m, t) in texts.withIndex()) {
            val words = segment(t).second
            val exp = cands[m].jsonArray
            assertEquals(exp.size, words.size)
            for ((i, w) in words.withIndex()) {
                val e = exp[i].jsonObject
                val slot = Slot.of(w, Fixtures.gen.generate(KeyGeom.keyTouches(w).ifEmpty { KeyGeom.keyTouches("a") }, w))
                // numpy's argsort is not stable: exact ties (same spatial score and same count) may come in either order,
                // so positions must hold the same score and the same set of words
                val ec = e["cands"]!!.jsonArray.map { it.jsonPrimitive.content }
                if (ec != slot.cands) { assertEquals(ec.toSet(), slot.cands.toSet(), "cands of '$w'"); ties++ }
                sameScores(e["sp"]!!.jsonArray.map { it.jsonPrimitive.double }, slot.sp.toList(), "sp of '$w'")
                assertEquals(e["typed_idx"]!!.jsonPrimitive.int, slot.typedIdx, "typed_idx of '$w'")
                n++
            }
        }
        println("key-centre slots: $n words, $ties with exact ties in a different order")
        assertTrue(ties * 50 < n)
    }

    private fun touches(a: JsonArray) = a.map { p -> p.jsonArray.let { Touch(it[0].jsonPrimitive.double, it[1].jsonPrimitive.double) } }

    /** Real-valued (jittered) touches -> v1 and v2 lists, same order and spatial scores as Python. */
    @Test fun realTouchesMatchPython() {
        val f = Fixtures.json("cands_touch.json").jsonObject
        var n = 0
        for (c in f["cases"]!!.jsonArray) {
            val o = c.jsonObject
            val typed = o["typed"]!!.jsonPrimitive.content
            val tc = touches(o["touches"]!!.jsonArray)
            val v1 = Fixtures.gen.generate(tc, typed)
            val e1 = o["v1"]!!.jsonArray.map { it.jsonArray }
            assertEquals(e1.map { it[0].jsonPrimitive.content }, v1.map { it.text }, "v1 cands of '$typed'")
            sameScores(e1.map { it[1].jsonPrimitive.double }, v1.map { it.sp }, "v1 sp of '$typed'", 1e-6)
            val v2 = Fixtures.gen2.generate(tc, typed)
            val e2 = o["v2"]!!.jsonArray.map { it.jsonArray }
            assertEquals(e2.map { it[0].jsonPrimitive.content }, v2.map { it.text }, "v2 cands of '$typed'")
            assertEquals(e2.map { it[2].jsonPrimitive.content }, v2.map { it.kind }, "v2 kinds of '$typed'")
            sameScores(e2.map { it[1].jsonPrimitive.double }, v2.map { it.sp }, "v2 sp of '$typed'", 1e-6)
            n++
        }
        for (m in f["merges"]!!.jsonArray) {
            val o = m.jsonObject
            val a = o["a"]!!.jsonPrimitive.content
            val b = o["b"]!!.jsonPrimitive.content
            assertEquals(o["ok"]!!.jsonPrimitive.boolean, Fixtures.gen2.mergeOk(a, b), "mergeOk $a $b")
            val got = Fixtures.gen2.mergeCandidates(KeyGeom.keyTouches(a), a, KeyGeom.keyTouches(b), b)
            val exp = o["cands"]!!.jsonArray.map { it.jsonArray }
            assertEquals(exp.map { it[0].jsonPrimitive.content }, got.map { it.text }, "merge $a $b")
            sameScores(exp.map { it[1].jsonPrimitive.double }, got.map { it.sp }, "merge sp $a $b", 1e-6)
        }
        println("real-touch cases: $n identical")
    }

    @Test fun spatialPrefersNearKeys() {
        val sp = SpatialModel()
        val t = KeyGeom.keyTouches("the")
        val e = sp.emitTable(t)
        val the = sp.score(e, sp.wordCode("the")!!)
        val tge = sp.score(e, sp.wordCode("tge")!!)
        val xyz = sp.score(e, sp.wordCode("xyz")!!)
        assertTrue(the > tge && tge > xyz)
        // a perfect hit scores (about) the per-letter optimum
        assertTrue(abs(the - 3 * sp.perfectLetter) < 0.05, "$the vs ${3 * sp.perfectLetter}")
        // the typed string is always a candidate
        val c = Fixtures.gen.generate(KeyGeom.keyTouches("qzx"), "qzx")
        assertTrue(c.any { it.text == "qzx" })
    }

    @Test fun applyCaseRules() {
        assertEquals("The", applyCase("the", "Teh"))
        assertEquals("THE", applyCase("the", "TEH"))
        assertEquals("the", applyCase("the", "teh"))
        assertEquals("I'm", applyCase("i'm", "Im"))
    }
}
