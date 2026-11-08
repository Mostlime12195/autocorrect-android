package dev.mostlime.autocorrect

import android.text.InputType
import dev.mostlime.autocorrect.engine.CandidateGenerator
import dev.mostlime.autocorrect.engine.CandidateGeneratorV2
import dev.mostlime.autocorrect.engine.Corrector
import dev.mostlime.autocorrect.engine.DecodeParams
import dev.mostlime.autocorrect.engine.KeyGeom
import dev.mostlime.autocorrect.engine.Lexicon
import dev.mostlime.autocorrect.engine.LmScorer
import dev.mostlime.autocorrect.engine.SpatialModel
import dev.mostlime.autocorrect.engine.Touch
import dev.mostlime.autocorrect.engine.WordResult
import dev.mostlime.autocorrect.ime.ControllerConfig
import dev.mostlime.autocorrect.ime.ControllerUi
import dev.mostlime.autocorrect.ime.CorrectionEngine
import dev.mostlime.autocorrect.ime.CorrectionRequest
import dev.mostlime.autocorrect.ime.Editor
import dev.mostlime.autocorrect.ime.FieldPolicy
import dev.mostlime.autocorrect.ime.KeyDef
import dev.mostlime.autocorrect.ime.KeyType
import dev.mostlime.autocorrect.ime.KeyboardController
import dev.mostlime.autocorrect.ime.KeyboardLayout
import dev.mostlime.autocorrect.ime.Shift
import dev.mostlime.autocorrect.ime.Timer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The keyboard's text state machine with a fake text field and an engine whose results arrive whenever the test says:
 * fast typing while inference is slow, stale results, selection-change spam, undo, enter while a correction runs.
 */
class KeyboardControllerTest {
    /** Text field with the cursor at the end and an optional composing region [cs, end). */
    class FakeEditor : Editor {
        val text = StringBuilder()
        var cs = -1
        val composingText get() = if (cs >= 0) text.substring(cs) else ""
        override fun textBefore(n: Int) = text.takeLast(n).toString()
        override fun hasSelection() = false
        override fun setComposing(text: String) {
            if (cs >= 0) this.text.setLength(cs) else cs = this.text.length
            this.text.append(text)
        }
        override fun finishComposing() { cs = -1 }
        override fun commit(text: String) {
            if (cs >= 0) { this.text.setLength(cs); cs = -1 }
            this.text.append(text)
        }
        override fun deleteBefore(n: Int) {
            this.text.setLength(maxOf(0, this.text.length - n))
            if (cs > this.text.length) cs = -1
        }
        override fun beginBatch() {}
        override fun endBatch() {}
        override fun deleteKey() { if (text.isNotEmpty()) text.setLength(text.length - 1); if (cs > text.length) cs = -1 }
        override fun enter() { cs = -1; text.append('\n') }
        override fun capsMode() = false
    }

    companion object {
        val corrector: Corrector by lazy {
            val f = listOf("src/main/assets/lexicon.tsv", "app/src/main/assets/lexicon.tsv").map(::File).first { it.exists() }
            val gen = CandidateGenerator(Lexicon(f.readText()), SpatialModel())
            Corrector(gen, CandidateGeneratorV2(gen, spaceBarX = doubleArrayOf(2.5, 7.5)))
        }
        val GOOD = setOf("the", "quick", "brown", "fox", "one", "more", "question", "that", "be", "only", "i", "have", "is", "it")
        /** LM that likes common words in GOOD and dislikes everything else (summed over the continuation's words) */
        val lm = LmScorer { reqs -> DoubleArray(reqs.size) { i -> reqs[i].cont.trim().split(Regex("\\s+")).sumOf { w -> if (w.lowercase() in GOOD) -2.0 else -14.0 } } }
        val P = DecodeParams(lam = 1.25, bias = 0.0, margin = 0.0, lamR = 1.25)
    }

    /** Results are computed right away but delivered only on deliver(). */
    class SlowEngine : CorrectionEngine {
        val queue = ArrayList<Pair<CorrectionRequest, (WordResult?) -> Unit>>()
        val requests = ArrayList<CorrectionRequest>()
        override fun correct(req: CorrectionRequest, done: (WordResult?) -> Unit) { requests.add(req); queue.add(req to done) }
        override fun suggest(typed: String, touches: List<Touch>, done: (List<String>) -> Unit) {}
        override fun cancelQueued() {}
        fun deliver(n: Int = queue.size) {
            repeat(n) {
                val (req, done) = queue.removeAt(0)
                done(corrector.correctWord(req.input, lm, P, req.prev, merge = req.merge))
            }
        }
    }

    class ManualTimer : Timer {
        val tasks = LinkedHashMap<Any, () -> Unit>()
        override fun post(delayMs: Long, r: () -> Unit): Any { val t = Any(); tasks[t] = r; return t }
        override fun cancel(token: Any) { tasks.remove(token) }
        fun fireAll() { val t = tasks.values.toList(); tasks.clear(); t.forEach { it() } }
    }

    private val ed = FakeEditor()
    private val engine = SlowEngine()
    private val timer = ManualTimer()
    private val events = ArrayList<Map<String, Any?>>()
    private val ctl = KeyboardController({ ed }, engine, object : ControllerUi {
        override fun showStrip(words: List<String>, bold: Int) {}
        override fun setShift(shift: Shift) {}
        override fun onResult(r: WordResult?) {}
    }, timer) { events.add(it) }.apply {
        startInput(ControllerConfig(FieldPolicy.of(InputType.TYPE_CLASS_TEXT, 0), true, "standard", { "test" }, "t"))
    }

    private fun keyFor(c: Char): KeyDef = when (c) {
        ' ' -> KeyboardLayout.LETTERS.keys.first { it.type == KeyType.SPACE }
        '\n' -> KeyboardLayout.LETTERS.keys.first { it.type == KeyType.ENTER }
        '\b' -> KeyboardLayout.LETTERS.keys.first { it.type == KeyType.BACKSPACE }
        else -> KeyboardLayout.LETTERS.keys.firstOrNull { it.text == c.toString() } ?: KeyDef(c.toString(), c.toString(), KeyType.CHAR, 0f, 1f, 0)
    }

    /** type each char; the editor reports a (possibly stale) selection change after every key */
    private fun type(s: String) {
        for (c in s) {
            ctl.key(keyFor(c), KeyGeom.center(c) ?: Touch.APOS)
            ctl.selectionChanged()
        }
    }

    @Test fun slowResultLandsWhileNextWordIsBeingTyped() {
        type("teh qui")
        assertEquals("teh qui", ed.text.toString())
        engine.deliver()
        assertEquals("the qui", ed.text.toString())
        assertEquals("qui", ed.composingText)            // the whole word stays composing (underlined)
        type("ck ")
        engine.deliver()
        assertEquals("the quick ", ed.text.toString())
    }

    @Test fun staleResultsAreDroppedNotDuplicated() {
        type("teh tha ")
        engine.deliver(2)
        // "teh" was superseded by the next word end: dropped, never written twice; "tha" -> "that" applies
        val t = ed.text.toString()
        assertTrue(t, t == "teh that " || t == "the that ")
        assertEquals(1, Regex("th").findAll(t.substringAfter(' ')).count())
        assertEquals(ctl.stats.dropped + ctl.stats.applied + ctl.stats.unchanged, 2)
    }

    @Test fun composingSurvivesSelectionSpam() {
        type("only")
        assertEquals("only", ed.composingText)
        type(" ")
        engine.deliver()
        assertEquals("only ", ed.text.toString())
        assertEquals("", ed.composingText)
    }

    @Test fun backspaceUndoesCorrectionAndKeepsTyped() {
        type("teh ")
        engine.deliver()
        assertEquals("the ", ed.text.toString())
        type("\b")
        assertEquals("teh", ed.text.toString())
        assertEquals("teh", ed.composingText)
        type(" ")
        engine.deliver()
        assertEquals("teh ", ed.text.toString())
        assertTrue(events.any { it["outcome"] == "undone" })
    }

    @Test fun enterWaitsForTheCorrection() {
        type("teh")
        type("\n")
        assertEquals("teh", ed.text.toString())           // held: nothing sent yet
        type("x")                                          // keys typed meanwhile are held too
        engine.deliver()
        assertEquals("the\nx", ed.text.toString())
    }

    @Test fun enterHoldTimesOut() {
        type("teh\n")
        timer.fireAll()                                    // no result within ENTER_HOLD_MS
        assertEquals("teh\n", ed.text.toString())
        engine.deliver()                                   // late result: text moved on, dropped
        assertEquals("teh\n", ed.text.toString())
    }

    @Test fun lmSeesTheWholeLeftWindow() {
        val sentence = "i have been thinking about this for a long time and i have oen"
        type("$sentence ")
        val req = engine.requests.last()
        assertEquals("oen", req.input.typed)
        assertEquals(sentence.removeSuffix(" oen"), req.input.left)
        assertEquals(" ", req.input.sep)
        engine.deliver(engine.queue.size)
        assertTrue(ed.text.toString(), ed.text.endsWith("have one "))
    }

    @Test fun passwordFieldsNeverCorrectOrLog() {
        ctl.startInput(ControllerConfig(FieldPolicy.of(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD, 0), true, "standard", { "test" }, "t"))
        ed.text.setLength(0)
        type("teh ")
        assertEquals(0, engine.requests.size)
        assertEquals("teh ", ed.text.toString())
        assertTrue(events.isEmpty())
    }
}
