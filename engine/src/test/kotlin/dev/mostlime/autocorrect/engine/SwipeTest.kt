package dev.mostlime.autocorrect.engine

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs

/**
 * Swipe decoder vs the Python reference (autocorrect/swipe/export_fixtures.py: synthetic dev swipes, decoder.py top-5
 * with the tuned params). Templates are built from the lexicon the app ships, like the Python ones.
 */
class SwipeTest {
    private val lex by lazy { Lexicon(File("../app/src/main/assets/lexicon.tsv").readText()) }
    private val dec by lazy { SwipeDecoder(lex) }

    private class Case(val gold: String, val path: List<Touch>, val top: List<String>, val geo: List<Double>, val nSurv: Int)

    private val cases by lazy {
        Fixtures.json("swipe_ref.json").jsonObject["cases"]!!.jsonArray.map { e ->
            val o = e.jsonObject
            Case(
                o["gold"]!!.jsonPrimitive.content,
                o["path"]!!.jsonArray.map { p -> Touch(p.jsonArray[0].jsonPrimitive.double, p.jsonArray[1].jsonPrimitive.double) },
                o["top"]!!.jsonArray.map { it.jsonPrimitive.content },
                o["geo"]!!.jsonArray.map { it.jsonPrimitive.double },
                o["n_surv"]!!.jsonPrimitive.int,
            )
        }
    }

    @Test
    fun keySeqCollapsesDoublesAndDropsApostrophes() {
        assertEquals("helo", SwipeGeom.keySeq("hello"))
        assertEquals("dont", SwipeGeom.keySeq("don't"))
        assertEquals("i", SwipeGeom.keySeq("I"))
    }

    @Test
    fun resampleKeepsEndpointsAndSpacing() {
        val r = SwipeGeom.resample(doubleArrayOf(0.0, 3.0, 3.0), doubleArrayOf(0.0, 0.0, 4.0), 8)
        assertEquals(0.0, r[0], 1e-12); assertEquals(0.0, r[1], 1e-12)
        assertEquals(3.0, r[14], 1e-12); assertEquals(4.0, r[15], 1e-12)
        assertEquals(7.0, SwipeGeom.pathLength(r), 1e-9)
    }

    @Test
    fun templatesMatchPythonTable() {
        // swipe_templates_n50.bin header: 93956 templates for the shipped lexicon (100000 words)
        assertEquals(100000, lex.size)
        assertEquals(93956, dec.tp.count)
        assertEquals(dec.tp.count, dec.tp.bucket.last())
    }

    @Test
    fun top1MatchesPythonReference() {
        var same = 0; var gold = 0; var surv = 0
        for (c in cases) {
            val top = dec.decode(c.path, 5)
            if (dec.lastSurvivors == c.nSurv) surv++
            if (top[0].word == c.top[0]) {
                same++
                assertEquals("geo score of ${c.top[0]}", c.geo[0], top[0].geo, 0.05 + 0.01 * abs(c.geo[0]))
            }
            if (top[0].word.equals(c.gold, ignoreCase = true)) gold++
            assertTrue("${c.gold}: kotlin ${top.map { it.word }} python ${c.top}", top[0].word in c.top.take(3))
        }
        println("swipe parity: top-1 same ${same}/${cases.size}, survivors same $surv, top-1 = gold $gold")
        assertTrue("top-1 agreement $same/${cases.size}", same >= cases.size - 1)
        assertTrue("gold top-1 $gold/${cases.size}", gold >= cases.size * 8 / 10)
    }

    @Test
    fun rerankUsesLmToBreakGeometryTies() {
        // a fake LM that prefers one candidate strongly: the rerank must follow it when it's in the top-K
        val c = cases.first()
        val geo = dec.decode(c.path)
        val target = geo[minOf(3, geo.size - 1)].word
        val lm = LmScorer { reqs -> DoubleArray(reqs.size) { if (reqs[it].cont.trim() == target) 0.0 else -30.0 } }
        val r = dec.decodeAndRerank(c.path, "so", " ", "", lm)
        assertEquals(target, r.best)
        assertEquals(geo[0].word, r.geoBest)
        val noLm = dec.decodeAndRerank(c.path, "so", " ", "", null)
        assertEquals(geo[0].word, noLm.best)
        // shift on: candidates come back capitalised
        val cap = dec.decodeAndRerank(c.path, "", "", "A", null)
        assertEquals(geo[0].word.replaceFirstChar { it.uppercaseChar() }, cap.best)
    }
}
