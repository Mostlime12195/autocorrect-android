package dev.mostlime.autocorrect

import android.text.InputType
import dev.mostlime.autocorrect.KeyboardControllerTest.Companion.GOOD
import dev.mostlime.autocorrect.engine.CandidateGenerator
import dev.mostlime.autocorrect.engine.CandidateGeneratorV2
import dev.mostlime.autocorrect.engine.Corrector
import dev.mostlime.autocorrect.engine.DecodeParams
import dev.mostlime.autocorrect.engine.KeyGeom
import dev.mostlime.autocorrect.engine.Lexicon
import dev.mostlime.autocorrect.engine.LmScorer
import dev.mostlime.autocorrect.engine.Slot
import dev.mostlime.autocorrect.engine.SpatialModel
import dev.mostlime.autocorrect.engine.Touch
import dev.mostlime.autocorrect.engine.UserData
import dev.mostlime.autocorrect.engine.WordResult
import dev.mostlime.autocorrect.ime.ControllerConfig
import dev.mostlime.autocorrect.ime.ControllerUi
import dev.mostlime.autocorrect.ime.CorrectionEngine
import dev.mostlime.autocorrect.ime.CorrectionRequest
import dev.mostlime.autocorrect.ime.FieldPolicy
import dev.mostlime.autocorrect.ime.KeyDef
import dev.mostlime.autocorrect.ime.KeyType
import dev.mostlime.autocorrect.ime.KeyboardController
import dev.mostlime.autocorrect.ime.KeyboardController.StripMode
import dev.mostlime.autocorrect.ime.KeyboardLayout
import dev.mostlime.autocorrect.ime.Learning
import dev.mostlime.autocorrect.ime.Shift
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Suggest-only mode, "only auto-correct when confident", the personal dictionary and the grammar guard. */
class LearningTest {
    companion object {
        val lexicon: Lexicon by lazy {
            val f = listOf("src/main/assets/lexicon.tsv", "app/src/main/assets/lexicon.tsv").map(::File).first { it.exists() }
            Lexicon(f.readText())
        }
        val P = DecodeParams(lam = 1.25, bias = 0.0, margin = 0.0, lamR = 1.25)
        val WORDS = GOOD + setOf("there", "store", "went", "want", "so")
        val lm = LmScorer { reqs -> DoubleArray(reqs.size) { i -> reqs[i].cont.trim().split(Regex("\\s+")).sumOf { w -> if (w.lowercase() in WORDS) -2.0 else -14.0 } } }
    }

    private val user = UserData(null, null)
    private val corrector = CandidateGenerator(lexicon, SpatialModel()).let { Corrector(it, CandidateGeneratorV2(it, spaceBarX = doubleArrayOf(2.5, 7.5)), user = user) }

    /** results computed at once, delivered on deliver(); `crafted` replaces the corrector for a typed word */
    inner class Engine : CorrectionEngine {
        val queue = ArrayList<Pair<CorrectionRequest, (WordResult?) -> Unit>>()
        val crafted = HashMap<String, WordResult>()
        var rewinds = 0
        override fun correct(req: CorrectionRequest, done: (WordResult?) -> Unit) { queue.add(req to done) }
        override fun suggest(typed: String, touches: List<Touch>, done: (List<String>) -> Unit) {}
        override fun cancelQueued() {}
        override fun rewind(targets: List<dev.mostlime.autocorrect.engine.RewindTarget>, done: (List<dev.mostlime.autocorrect.engine.RewindChange>) -> Unit) { rewinds++ }
        fun deliver() {
            while (queue.isNotEmpty()) {
                val (req, done) = queue.removeAt(0)
                done(crafted[req.input.typed] ?: corrector.correctWord(req.input, lm, P, req.prev, merge = req.merge))
            }
        }
    }

    private val ed = KeyboardControllerTest.FakeEditor()
    private val engine = Engine()
    private val events = ArrayList<Map<String, Any?>>()
    private val added = ArrayList<String>()
    private val learning = object : Learning {
        override fun isUserWord(w: String) = user.has(w)
        override fun inVocab(w: String) = lexicon.index.containsKey(w.lowercase()) || user.has(w)
        override fun addWord(w: String) = user.add(w)
        override fun removeWord(w: String) = user.remove(w)
        override fun suppress(typed: String, chosen: String) = user.suppress(typed, chosen)
        override fun penalty(typed: String, chosen: String) = user.penalty(typed, chosen)
        override fun noteSpellingUndo(typed: String) = user.noteSpellingUndo(typed)
    }
    private val ctl = KeyboardController({ ed }, engine, object : ControllerUi {
        override fun showStrip(words: List<String>, bold: Int, undo: Int) {}
        override fun setShift(shift: Shift) {}
        override fun onResult(r: WordResult?) {}
        override fun onDictAdded(word: String) { added.add(word) }
    }, KeyboardControllerTest.ManualTimer(), learning) { events.add(it) }

    private fun start(suggestOnly: Boolean = false, confidentOnly: Boolean = false) =
        ctl.startInput(ControllerConfig(FieldPolicy.of(InputType.TYPE_CLASS_TEXT, 0), true, "standard", { "test" }, "t",
            suggestOnly = suggestOnly, confidentOnly = confidentOnly))

    private fun keyFor(c: Char): KeyDef = when (c) {
        ' ' -> KeyboardLayout.LETTERS.keys.first { it.type == KeyType.SPACE }
        '\b' -> KeyboardLayout.LETTERS.keys.first { it.type == KeyType.BACKSPACE }
        else -> KeyboardLayout.LETTERS.keys.firstOrNull { it.text == c.toString() } ?: KeyDef(c.toString(), c.toString(), KeyType.CHAR, 0f, 1f, 0)
    }

    private fun type(s: String) { for (c in s) { ctl.key(keyFor(c), KeyGeom.center(c) ?: Touch.APOS); ctl.selectionChanged() } }

    // ------------------------------------------------------------------ suggest-only
    @Test fun suggestOnlyNeverEditsAndMarksTypos() {
        start(suggestOnly = true)
        type("so i want teh quick brown fox ")
        engine.deliver()
        assertEquals("so i want teh quick brown fox ", ed.text.toString())   // not even i -> I
        val at = "so i want ".length
        assertEquals("the", ed.marks[at]?.first())
        assertEquals(StripMode.TYPO, ctl.stripMode)
        assertEquals("the", ctl.shown[0])
        assertEquals(0, engine.rewinds)
        assertTrue(events.any { it["outcome"] == "flagged" && it["typed"] == "teh" })
        // tapping a fix is an explicit edit: allowed
        ctl.pickSuggestion(0)
        assertEquals("so i want the quick brown fox ", ed.text.toString())
        assertTrue(ed.marks.isEmpty())
        assertTrue(events.any { it["outcome"] == "strip_pick" && it["final"] == "the" })
    }

    @Test fun suggestOnlyLeavesLateResultsAlone() {
        start(suggestOnly = true)
        type("teh qui")
        engine.deliver()                                   // lands while the next word is composing
        assertEquals("teh qui", ed.text.toString())
        assertEquals("qui", ed.composingText)
        assertEquals("the", ed.marks[0]?.first())
        type("ck ")
        engine.deliver()
        assertEquals("teh quick ", ed.text.toString())
        assertEquals(StripMode.TYPO, ctl.stripMode)        // the flagged word comes back once nothing is composing
    }

    @Test fun notATypoClearsMarkAddsWordAndLogs() {
        start(suggestOnly = true)
        type("fized a lot ")
        engine.deliver()
        assertTrue(ed.marks.containsKey(0))
        ctl.stripAction()                                  // "not a typo"
        assertTrue(user.has("fized"))
        assertTrue(ed.marks.isEmpty())
        assertEquals("fized a lot ", ed.text.toString())
        val e = events.single { it["type"] == "not_typo" }
        assertEquals("fized", e["typed"])
        assertTrue((e["candidates"] as List<*>).isNotEmpty())
        // never flagged again
        type("fized ")
        engine.deliver()
        assertTrue(ed.marks.isEmpty())
    }

    @Test fun grammarFlagsOfferIgnoreNotDictionary() {
        start(suggestOnly = true)
        engine.crafted["their"] = result("their", listOf("their", "there"), doubleArrayOf(0.0, 5.0))
        type("i went their ")
        engine.deliver()
        assertTrue(ed.marks.isNotEmpty())                  // their -> there flagged
        ctl.stripAction()                                  // "ignore" for an in-vocab confusable
        assertFalse(user.has("their"))
        assertTrue(user.penalty("their", "there") > 0)
        assertTrue(events.any { it["type"] == "suppress" && it["source"] == "ignore" })
        assertTrue(events.none { it["type"] == "not_typo" })
    }

    @Test fun longPressTypedWordAddsToDictionary() {
        start()
        type("fized ")
        engine.deliver()
        assertEquals("fixed ", ed.text.toString())
        val i = ctl.shown.indexOf("fized")
        ctl.longPressSuggestion(i)
        assertEquals("fized ", ed.text.toString())
        assertTrue(user.has("fized"))
        assertEquals(listOf("fized"), added)
    }

    // ------------------------------------------------------------------ confident only
    private fun result(typed: String, cands: List<String>, scores: DoubleArray): WordResult {
        val s = Slot(typed, cands, DoubleArray(cands.size), cands.indexOf(typed))
        var k = 0
        for (i in scores.indices) if (scores[i] > scores[k]) k = i
        return WordResult(s, scores, scores, k, cands[k], skipped = false, cheap = false, nScored = cands.size, nCands = cands.size, lmMs = 1.0, recheck = null)
    }

    @Test fun confidentOnlyUnderlinesUnsureCorrections() {
        start(confidentOnly = true)
        engine.crafted["tha"] = result("tha", listOf("tha", "the", "that"), doubleArrayOf(0.0, 2.5, 1.5))   // margin 1: unsure
        engine.crafted["hte"] = result("hte", listOf("hte", "the"), doubleArrayOf(0.0, 6.0))                 // margin 6: sure
        type("tha hte ")
        engine.deliver()
        assertEquals("tha the ", ed.text.toString())
        assertEquals(listOf("the", "that"), ed.marks[0])
        start(confidentOnly = false)
        ed.text.setLength(0); ed.marks.clear()
        type("tha ")
        engine.deliver()
        assertEquals("the ", ed.text.toString())
    }

    // ------------------------------------------------------------------ dictionary + grammar guard
    @Test fun dictionaryWordsAreNeverCorrected() {
        user.add("Fized")
        start()
        type("fized a lot ")
        engine.deliver()
        assertEquals("fized a lot ", ed.text.toString())
        // offered for near misses
        assertTrue(corrector.candidates("fizrd", null).cands.contains("Fized"))
    }

    @Test fun twoSpellingUndosAddTheWord() {
        start()
        repeat(2) {
            type("fized ")
            engine.deliver()
            assertTrue(ed.text.toString(), ed.text.endsWith("fixed "))
            type("\b")                                     // undo: back to "fized"
            type(" ")
        }
        assertTrue(user.has("fized"))
        assertEquals(listOf("fized"), added)
        type("fized ")
        engine.deliver()
        assertTrue(ed.text.toString(), ed.text.endsWith("fized fized fized "))
    }

    @Test fun autoAddCanBeTakenBack() {
        start()
        repeat(2) { type("fized "); engine.deliver(); type("\b") ; if (it == 0) type(" ") }
        assertEquals(StripMode.DICT_ADDED, ctl.stripMode)
        ctl.stripAction()
        assertFalse(user.has("fized"))
    }

    @Test fun grammarUndosNeverTouchTheDictionary() {
        start()
        engine.crafted["their"] = result("their", listOf("their", "there"), doubleArrayOf(0.0, 5.0))
        repeat(3) {
            type("i went their ")
            engine.deliver()
            assertTrue(ed.text.toString(), ed.text.endsWith("went there "))
            type("\b")                                     // undo the confusable swap
            type(" ")
            ed.text.setLength(0); ctl.startInput(ctl.config)
        }
        assertFalse(user.has("their"))
        assertEquals(0, user.size)
        assertTrue(user.penalty("their", "there") > 0)
        assertTrue(events.any { it["type"] == "suppress" && it["typed"] == "their" && it["chosen"] == "there" })
        // capitals-only (i -> I) is not spelling either
        type("so i want ")
        engine.deliver()
        assertTrue(ed.text.toString(), ed.text.endsWith("I want "))
    }

    @Test fun spellingClassification() {
        start()
        assertTrue(ctl.isSpellingEdit("fized", "fixed"))
        assertFalse(ctl.isSpellingEdit("hello", "hello,"))      // punctuation
        assertFalse(ctl.isSpellingEdit("dont", "don't"))        // apostrophe only
        assertFalse(ctl.isSpellingEdit("hello", "Hello"))       // capitals only
        assertFalse(ctl.isSpellingEdit("their", "there"))       // both real words
        assertFalse(ctl.isSpellingEdit("inaminute", "in a minute"))   // multi-token
    }

    @Test fun suppressionRaisesTheMarginInTheEngine() {
        val s = corrector.correctWord(dev.mostlime.autocorrect.engine.WordInput("fized", null, "it is", " "), lm, P, null)
        assertEquals("fixed", s.out)
        repeat(3) { user.suppress("fized", "fixed") }
        val r = corrector.correctWord(dev.mostlime.autocorrect.engine.WordInput("fized", null, "it is", " "), lm, P, null)
        val gap = s.scores[s.chosen] - s.scores[s.slot.typedIdx]
        if (gap < UserData.SUPPRESS_NATS) assertEquals("fized", r.out) else assertEquals("fixed", r.out)
    }
}
