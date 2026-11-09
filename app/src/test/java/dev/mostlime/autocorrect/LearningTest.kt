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
        var wordLm: LmScorer = lm
        var rewindLm: LmScorer = lm
        val pendingRewinds = ArrayList<Pair<List<dev.mostlime.autocorrect.engine.RewindTarget>, (List<dev.mostlime.autocorrect.engine.RewindChange>) -> Unit>>()
        override fun checkSentences(reqs: List<dev.mostlime.autocorrect.engine.SentenceReq>, done: (List<List<dev.mostlime.autocorrect.engine.SentenceFix>>?) -> Unit) =
            done(reqs.map { dev.mostlime.autocorrect.engine.SentenceCheck.check(corrector, it, rewindLm, P, KeyboardController.FLAG_MARGIN) })
        fun deliverRewinds() {
            while (pendingRewinds.isNotEmpty()) {
                val (t, d) = pendingRewinds.removeAt(0)
                d(dev.mostlime.autocorrect.engine.Rewind.check(t, rewindLm, 1.25, user = user))
            }
        }
        override fun correct(req: CorrectionRequest, done: (WordResult?) -> Unit) { queue.add(req to done) }
        var suggestions: ((String) -> List<String>)? = null
        override fun suggest(typed: String, touches: List<Touch>, done: (List<String>) -> Unit) { suggestions?.let { done(it(typed)) } }
        override fun cancelQueued() {}
        override fun rewind(targets: List<dev.mostlime.autocorrect.engine.RewindTarget>, done: (List<dev.mostlime.autocorrect.engine.RewindChange>) -> Unit) { rewinds++; pendingRewinds.add(targets to done) }
        fun deliver() {
            while (queue.isNotEmpty()) {
                val (req, done) = queue.removeAt(0)
                done(crafted[req.input.typed] ?: corrector.correctWord(req.input, wordLm, P, req.prev, merge = req.merge))
            }
        }
    }

    private val ed = KeyboardControllerTest.FakeEditor()
    private val timer = KeyboardControllerTest.ManualTimer()
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
    }, timer, learning) { events.add(it) }

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
        assertTrue(ed.marks.isEmpty())                     // nothing written while typing
        // one small chip at the side; the strip's word cells stay empty for candidates
        assertEquals(StripMode.FLAG, ctl.stripMode)
        assertEquals("teh → the", ctl.shownAction)
        assertTrue(ctl.shown.isEmpty())
        assertEquals(0, engine.rewinds)
        assertTrue(events.any { it["outcome"] == "flagged" && it["typed"] == "teh" })
        // a pause: the underline is attached
        timer.fireAll()
        val at = "so i want ".length
        assertEquals("the", ed.marks[at]?.first())
        assertEquals("so i want teh quick brown fox ", ed.text.toString())
        // tapping the chip is an explicit edit: allowed
        ctl.stripAction()
        assertEquals("so i want the quick brown fox ", ed.text.toString())
        assertTrue(!ed.marks.containsKey(at))
        assertTrue(events.any { it["outcome"] == "strip_pick" && it["final"] == "the" })
    }

    @Test fun suggestOnlyLeavesLateResultsAlone() {
        start(suggestOnly = true)
        type("teh qui")
        engine.deliver()                                   // lands while the next word is composing
        assertEquals("teh qui", ed.text.toString())
        assertEquals("qui", ed.composingText)
        assertTrue(ed.marks.isEmpty())
        assertEquals(StripMode.NONE, ctl.stripMode)        // the chip never takes the strip while a word is composed
        type("ck ")
        engine.deliver()
        assertEquals("teh quick ", ed.text.toString())
        assertEquals(StripMode.FLAG, ctl.stripMode)        // the flagged word comes back once nothing is composing
    }

    @Test fun notATypoClearsMarkAddsWordAndLogs() {
        start(suggestOnly = true)
        type("fized a lot ")
        engine.deliver()
        timer.fireAll()
        assertTrue(ed.marks.containsKey(0))
        ctl.stripActionLongPress()                         // long-press the "fized → fixed" chip: not a typo
        assertTrue(user.has("fized"))
        assertTrue(!ed.marks.containsKey(0))
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
        timer.fireAll()
        assertTrue(ed.marks.isNotEmpty())                  // their -> there flagged
        ctl.stripActionLongPress()                         // "ignore" for an in-vocab confusable
        assertFalse(user.has("their"))
        assertTrue(user.penalty("their", "there") > 0)
        assertTrue(events.any { it["type"] == "suppress" && it["source"] == "ignore" })
        assertTrue(events.none { it["type"] == "not_typo" })
    }

    /** after an auto-correction the strip has plain alternatives only: no undo pill, not the word as typed */
    @Test fun autoCorrectStripHasNoRevertChip() {
        start()
        type("fized ")
        engine.deliver()
        assertEquals("fixed ", ed.text.toString())
        assertTrue(ctl.shown.toString(), "fized" !in ctl.shown)
        assertEquals(null, ctl.shownAction)
        type("\b")                                        // backspace is the undo
        assertEquals("fized", ed.text.toString())
    }

    /** long-press the word as typed while typing: dictionary */
    @Test fun longPressTypedWordWhileTypingAddsToDictionary() {
        start()
        engine.suggestions = { t -> listOf(t, "fixed") }
        type("fized")
        val i = ctl.shown.indexOf("fized")
        assertTrue(ctl.shown.toString(), i >= 0)
        ctl.longPressSuggestion(i)
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
        assertTrue(ed.marks.isEmpty())                     // underlines only after a pause
        timer.fireAll()
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

    @Test fun autoAddShowsNoPill() {
        start()
        repeat(2) { type("fized "); engine.deliver(); type("\b") ; if (it == 0) type(" ") }
        assertTrue(user.has("fized"))
        assertEquals(null, ctl.shownAction)
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

    @Test fun autoCapitalizeFollowsTheSettingAndFieldFlags() {
        ed.caps = true
        val sentences = FieldPolicy.of(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES, 0)
        ctl.startInput(ControllerConfig(sentences, true, "standard", { "t" }, "t", autoCap = true))
        assertEquals(Shift.ON, ctl.shift)
        ctl.startInput(ControllerConfig(sentences, true, "standard", { "t" }, "t", autoCap = false))
        assertEquals(Shift.OFF, ctl.shift)
        // no CAP flag in the field: no auto-shift even with the setting on
        ctl.startInput(ControllerConfig(FieldPolicy.of(InputType.TYPE_CLASS_TEXT, 0), true, "standard", { "t" }, "t", autoCap = true))
        assertEquals(Shift.OFF, ctl.shift)
        // ALL CAPS field keeps the shift after a letter
        ctl.startInput(ControllerConfig(FieldPolicy.of(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS, 0), true, "standard", { "t" }, "t"))
        type("ab")
        assertEquals("AB", ed.composingText)
        // requests carry the setting to the corrector
        ctl.startInput(ControllerConfig(sentences, true, "formal", { "t" }, "t", autoCap = false))
        type("hello ")
        assertFalse(engine.queue.last().first.autoCap)
    }

    /** the evidence for "too" -> "to" only arrives 7 words later ("home") */
    private val farLm = LmScorer { reqs -> DoubleArray(reqs.size) { i ->
        val t = reqs[i].left + reqs[i].cont
        if ("home" !in t) -5.0 else if ("want to go to the shop and then home" in t) -1.0 else -10.0
    } }

    @Test fun idleRewindFixesAWordMoreThanThreeBack() {
        start()
        engine.wordLm = farLm; engine.rewindLm = farLm
        type("i want too go to the shop and then home ")
        engine.deliver(); engine.deliverRewinds()
        assertTrue(ed.text.toString(), ed.text.contains("want too go"))     // the 3-word window can't see "home"
        timer.fireAll()                                                     // typing paused: whole-sentence pass
        engine.deliverRewinds()
        assertTrue(ed.text.toString(), ed.text.endsWith(" want to go to the shop and then home "))
    }

    @Test fun idleSuggestMarksAWordMoreThanThreeBack() {
        start(suggestOnly = true)
        engine.wordLm = farLm; engine.rewindLm = farLm
        type("i want too go to the shop and then home ")
        engine.deliver()
        assertTrue(ed.marks.isEmpty())
        timer.fireAll()
        assertEquals("i want too go to the shop and then home ", ed.text.toString())
        assertEquals("to", ed.marks["i want ".length]?.first())
        assertEquals(1, ed.marks.size)
    }

    /** the flagged word is several words back: tapping into it (cursor on it) brings its fixes, and a pick fixes it */
    @Test fun cursorOnAFlaggedWordShowsItsFixes() {
        start(suggestOnly = true)
        type("so teh quick brown fox is great ")
        engine.deliver()
        assertEquals(StripMode.FLAG, ctl.stripMode)
        ctl.pickSuggestion(-1)                              // nothing
        // the user moves on; the strip shows something else
        type("a")
        assertEquals(StripMode.NONE, ctl.stripMode)
        type("\b")
        // the cursor lands inside "teh" (FakeEditor's cursor is at the end, so report the word's own offset)
        val at = ed.text.indexOf("teh")
        ctl.cursorMoved(at + 1, at + 1)
        assertEquals(StripMode.TYPO, ctl.stripMode)
        assertEquals("the", ctl.shown[0])
        ctl.pickSuggestion(0)
        assertEquals("so the quick brown fox is great ", ed.text.toString())
    }

    /** the chip is the only way a flag changes text: tapping the strip's cells or pressing space never applies a fix */
    @Test fun onlyTheChipAppliesAFix() {
        start(suggestOnly = true)
        type("so teh ")
        engine.deliver()
        assertEquals(StripMode.FLAG, ctl.stripMode)
        for (i in 0..2) ctl.pickSuggestion(i)
        type(" ")
        assertEquals("so teh  ", ed.text.toString())
        ctl.stripAction()
        assertTrue(ed.text.toString(), ed.text.startsWith("so the"))
    }

    /** a field that drops spans (Discord / React Native): fast typing with a flagged word ends exactly as typed */
    @Test fun spanDroppingFieldKeepsExactlyWhatWasTyped() {
        start(suggestOnly = true)
        ed.dropsSpans = true
        fun fast(t: String) {
            for ((i, c) in t.withIndex()) { type(c.toString()); if (i % 7 == 3) engine.deliver() }   // results land mid-typing
            engine.deliver()
        }
        val a = "so teh quick brown fox jumps over "
        val b = "teh lazy dog and then fized it "
        fast(a)
        timer.fireAll()                                     // a pause: marks are tried once, the field drops them
        val editsAfterPause = ed.edits
        fast(b)
        timer.fireAll()
        assertEquals(a + b, ed.text.toString())
        assertTrue(ed.marks.isEmpty())
        assertEquals("no more re-commits once the field dropped a span", editsAfterPause, ed.edits)
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
