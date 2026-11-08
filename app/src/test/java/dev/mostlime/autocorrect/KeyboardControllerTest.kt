package dev.mostlime.autocorrect

import android.text.InputType
import dev.mostlime.autocorrect.engine.CandidateGenerator
import dev.mostlime.autocorrect.engine.CandidateGeneratorV2
import dev.mostlime.autocorrect.engine.Corrector
import dev.mostlime.autocorrect.engine.DecodeParams
import dev.mostlime.autocorrect.engine.KeyGeom
import dev.mostlime.autocorrect.engine.Lexicon
import dev.mostlime.autocorrect.engine.LmScorer
import dev.mostlime.autocorrect.engine.Rewind
import dev.mostlime.autocorrect.engine.RewindChange
import dev.mostlime.autocorrect.engine.RewindTarget
import dev.mostlime.autocorrect.engine.SpatialModel
import dev.mostlime.autocorrect.engine.SwipeCand
import dev.mostlime.autocorrect.engine.SwipeResult
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
import dev.mostlime.autocorrect.ime.SwipeRequest
import dev.mostlime.autocorrect.ime.Timer
import dev.mostlime.autocorrect.ime.TypoMark
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
        /** what the user could see: the text after every edit outside a batch, and at the end of each batch */
        val visible = ArrayList<String>()
        private var depth = 0
        private fun shown() { if (depth == 0 && visible.lastOrNull() != text.toString()) visible.add(text.toString()) }
        val composingText get() = if (cs >= 0) text.substring(cs) else ""
        override fun textBefore(n: Int) = text.takeLast(n).toString()
        override fun hasSelection() = false
        override fun setComposing(text: String) {
            if (cs >= 0) this.text.setLength(cs) else cs = this.text.length
            this.text.append(text); shown()
        }
        override fun finishComposing() { cs = -1 }
        override fun commit(text: String) {
            if (cs >= 0) { this.text.setLength(cs); cs = -1 }
            this.text.append(text); shown()
        }
        override fun deleteBefore(n: Int) {
            this.text.setLength(maxOf(0, this.text.length - n))
            if (cs > this.text.length) cs = -1
            marks.keys.removeAll { it >= this.text.length }
            shown()
        }
        /** typo marks (SuggestionSpans in the app): start in the text -> suggestions */
        val marks = HashMap<Int, List<String>>()
        override fun replaceBefore(n: Int, text: String, mark: TypoMark?) {
            val start = this.text.length - n
            replaceBefore(n, text)
            if (mark != null) marks[start + mark.start] = mark.suggestions
        }
        override fun beginBatch() { depth++ }
        override fun endBatch() { depth--; shown() }
        override fun deleteKey(): Int { if (text.isEmpty()) return 0; text.setLength(text.length - 1); if (cs > text.length) cs = -1; return 1 }
        override fun enter() { cs = -1; text.append('\n') }
        /** what getCursorCapsMode would say at the cursor */
        var caps = false
        override fun capsMode() = caps
    }

    companion object {
        val corrector: Corrector by lazy {
            val f = listOf("src/main/assets/lexicon.tsv", "app/src/main/assets/lexicon.tsv").map(::File).first { it.exists() }
            val gen = CandidateGenerator(Lexicon(f.readText()), SpatialModel())
            Corrector(gen, CandidateGeneratorV2(gen, spaceBarX = doubleArrayOf(2.5, 7.5)))
        }
        val GOOD = setOf("the", "quick", "brown", "fox", "one", "more", "question", "that", "be", "only", "i", "have", "is", "it",
            "keyboard", "guess", "fixed", "a", "lot", "contractions", "are", "go", "home", "now")
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
        val rewinds = ArrayList<Pair<List<RewindTarget>, (List<RewindChange>) -> Unit>>()
        var rewindLm: LmScorer = lm
        override fun rewind(targets: List<RewindTarget>, done: (List<RewindChange>) -> Unit) { rewinds.add(targets to done) }
        fun deliverRewinds() {
            while (rewinds.isNotEmpty()) { val (t, d) = rewinds.removeAt(0); d(Rewind.check(t, rewindLm, 1.25)) }
        }
        val swipes = ArrayList<Pair<SwipeRequest, (SwipeResult?) -> Unit>>()
        override fun swipe(req: SwipeRequest, done: (SwipeResult?) -> Unit) { swipes.add(req to done) }
        /** deliver the oldest swipe: words best-first by geometry, `best` = the index the LM picked */
        fun deliverSwipe(words: List<String>, best: Int = 0) {
            val (req, done) = swipes.removeAt(0)
            val cands = words.mapIndexed { i, w -> SwipeCand(dev.mostlime.autocorrect.engine.applyCase(w, req.casePattern), i, -i.toDouble(), 0.0, 0.0, 0.0) }
            val order = (listOf(best) + cands.indices.filter { it != best }).toIntArray()
            val scores = DoubleArray(cands.size) { i -> -order.indexOf(i).toDouble() }
            done(SwipeResult(cands, null, scores, order, 1.0, 0.0, 10))
        }
        fun deliver(n: Int = queue.size) {
            repeat(n) {
                val (req, done) = queue.removeAt(0)
                done(corrector.correctWord(req.input, lm, P, req.prev, merge = req.merge))
            }
        }
    }

    /** Short delays (the fast hold) expire at once, i.e. the engine is slower than the hold, unless fastExpires = false. */
    class ManualTimer(var fastExpires: Boolean = true) : Timer {
        val tasks = LinkedHashMap<Any, () -> Unit>()
        override fun post(delayMs: Long, r: () -> Unit): Any {
            val t = Any()
            if (fastExpires && delayMs < 100) { r(); return t }
            tasks[t] = r; return t
        }
        override fun cancel(token: Any) { tasks.remove(token) }
        fun fireAll() { val t = tasks.values.toList(); tasks.clear(); t.forEach { it() } }
    }

    private val ed = FakeEditor()
    private val engine = SlowEngine()
    private val timer = ManualTimer()
    private val events = ArrayList<Map<String, Any?>>()
    private val ctl = KeyboardController({ ed }, engine, object : ControllerUi {
        override fun showStrip(words: List<String>, bold: Int, undo: Int) {}
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

    @Test fun lateResultsStillApplyWithoutDuplicates() {
        type("teh tha ")
        engine.deliver(2)
        // both land after later typing: each replaces exactly its own word
        assertEquals("the that ", ed.text.toString())
        assertEquals(2, ctl.stats.applied)
    }

    @Test fun wordFollowedByCommaThenMoreTyping() {
        // "keybiard," then " I gu" typed before the slow result arrives (the v0.1.13 miss)
        type("this is a usable keybiard, I gu")
        engine.deliver(engine.queue.size)
        assertTrue(ed.text.toString(), ed.text.endsWith("usable keyboard, I gu"))
        assertEquals("gu", ed.composingText)
        type("ess ")
        engine.deliver(engine.queue.size)
        assertTrue(ed.text.toString(), ed.text.endsWith("keyboard, I guess "))
    }

    @Test fun fastTypingAcrossSeveralWords() {
        type("contractions are fized a lot ")
        engine.deliver(engine.queue.size)
        assertEquals("contractions are fixed a lot ", ed.text.toString())
    }

    @Test fun backspaceAfterWordKeepsPendingCorrection() {
        type("teh  ")          // double space, then fix it before the result arrives
        type("\b")
        engine.deliver(engine.queue.size)
        assertEquals("the ", ed.text.toString())
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

    /** An engine that answers within the fast hold: the text goes straight from the typed word to the corrected one. */
    @Test fun quickCorrectionsAreOneVisibleEdit() {
        val ed2 = FakeEditor()
        val quick = object : CorrectionEngine {
            override fun correct(req: CorrectionRequest, done: (WordResult?) -> Unit) = done(corrector.correctWord(req.input, lm, P, req.prev, merge = req.merge))
            override fun suggest(typed: String, touches: List<Touch>, done: (List<String>) -> Unit) {}
            override fun cancelQueued() {}
        }
        val t2 = ManualTimer(fastExpires = false)
        val c2 = KeyboardController({ ed2 }, quick, object : ControllerUi {
            override fun showStrip(words: List<String>, bold: Int, undo: Int) {}
            override fun setShift(shift: Shift) {}
            override fun onResult(r: WordResult?) {}
        }, t2) {}.apply { startInput(ControllerConfig(FieldPolicy.of(InputType.TYPE_CLASS_TEXT, 0), true, "standard", { "t" }, "t")) }
        for (ch in "teh quick ") c2.key(keyFor(ch), KeyGeom.center(ch) ?: Touch.APOS)
        assertEquals("the quick ", ed2.text.toString())
        assertTrue(ed2.visible.toString(), "teh " !in ed2.visible)          // never shown uncorrected with its space
        assertTrue(ed2.visible.toString(), ed2.visible.contains("the "))
    }

    @Test fun standaloneIIsCapitalised() {
        type("this is a test i want ")
        engine.deliver(engine.queue.size)
        assertTrue(ed.text.toString(), ed.text.endsWith("test I want "))
    }

    @Test fun casualKeepsLowercaseI() {
        ctl.startInput(ControllerConfig(FieldPolicy.of(InputType.TYPE_CLASS_TEXT, 0), true, "casual", { "test" }, "t"))
        type("so i want ")
        engine.deliver(engine.queue.size)
        assertEquals("so i want ", ed.text.toString())
    }

    /** LM that only knows "to the" beats "too the" (the evidence comes after the word) */
    private val toThe = LmScorer { reqs -> DoubleArray(reqs.size) { i -> val c = reqs[i].cont; if ("to the" in c && "too the" !in c) -1.0 else if ("too the" in c) -15.0 else -5.0 } }

    @Test fun rewindFixesAConfusableWordFromRightContext() {
        type("i went too the store ")
        engine.deliver(engine.queue.size)
        engine.rewindLm = toThe
        engine.deliverRewinds()
        assertTrue(ed.text.toString(), ed.text.endsWith("went to the store "))
        assertTrue(events.any { it["type"] == "rewind" && it["from"] == "too" && it["to"] == "to" })
        // undo chip puts it back, and it is never rewound again
        assertEquals("too", ctl.shown[1])
        ctl.pickSuggestion(1)
        assertTrue(ed.text.toString(), ed.text.endsWith("went too the store "))
        type("now ")
        engine.deliver(engine.queue.size)
        engine.deliverRewinds()
        assertTrue(ed.text.toString(), ed.text.endsWith("went too the store now "))
    }

    @Test fun rewindNeedsAClearMarginAndUnchangedText() {
        type("i went too the store ")
        engine.deliver(engine.queue.size)
        // weak evidence: no change
        engine.rewindLm = LmScorer { reqs -> DoubleArray(reqs.size) { i -> if ("to the" in reqs[i].cont) -5.0 else -6.0 } }
        engine.deliverRewinds()
        assertTrue(ed.text.toString(), ed.text.endsWith("too the store "))
    }

    @Test fun rewindRespectsEditsMadeMeanwhile() {
        type("i went too the store ")
        engine.deliver(engine.queue.size)
        type("\b\b\b\b\b\b")                      // the user deleted "store " before the rewind landed
        engine.rewindLm = toThe
        engine.deliverRewinds()
        assertTrue(ed.text.toString(), ed.text.endsWith("too the "))
    }

    /** v0.1.35 emulator: "home nownow" -- an older result landed while the next word was still held (composing). */
    @Test fun lateResultDuringFastHoldDoesNotDuplicate() {
        timer.fastExpires = false
        type("go hoem ")
        timer.fireAll()                 // "hoem " committed as typed, its result still pending
        type("now ")                    // "now" held (composing) for the fast hold
        engine.deliver(2)               // "go", "hoem" results land during the hold
        timer.fireAll()                 // hold expires: "now " committed
        engine.deliver(engine.queue.size)
        assertEquals(ed.visible.joinToString(" | "), "go home now ", ed.text.toString())
    }

    @Test fun passwordFieldsNeverCorrectOrLog() {
        ctl.startInput(ControllerConfig(FieldPolicy.of(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD, 0), true, "standard", { "test" }, "t"))
        ed.text.setLength(0)
        type("teh ")
        assertEquals(0, engine.requests.size)
        assertEquals("teh ", ed.text.toString())
        assertTrue(events.isEmpty())
    }

    // ---------------------------------------------------------------- swipe typing
    private val path = listOf(Touch(1.5, 0.7), Touch(5.0, 0.7), Touch(8.5, 0.7))

    /** a swipe that starts on key c: the press types c, the view then reports the swipe */
    private fun swipe(start: Char) {
        ctl.key(keyFor(start), KeyGeom.center(start)!!)
        ctl.swipeBegin()
        ctl.swipe(path)
    }

    @Test fun swipeInsertsWordWithSpaceAndPunctuationTakesTheSpace() {
        ed.text.append("hello ")
        swipe('w')
        assertEquals("hello ", ed.text.toString())          // the press's letter was taken back
        val req = engine.swipes.first().first
        assertEquals("hello", req.left); assertEquals(" ", req.sep); assertEquals("", req.casePattern)
        engine.deliverSwipe(listOf("world", "word", "wild"))
        assertEquals("hello world ", ed.text.toString())
        assertEquals(listOf("world", "word", "wild"), ctl.shown)
        type(".")
        assertEquals("hello world. ", ed.text.toString())
    }

    @Test fun swipeAddsLeadingSpaceAndBackspaceRemovesWholeWord() {
        ed.text.append("ok.")
        swipe('y')
        assertEquals(". ", engine.swipes.first().first.sep)    // sep = everything between the previous word and this one
        engine.deliverSwipe(listOf("yes", "yea"))
        assertEquals("ok. yes ", ed.text.toString())
        type("\b")
        assertEquals("ok.", ed.text.toString())
    }

    @Test fun swipeAlternativeFromStripReplacesWordAndLmOverruleGetsUndoChip() {
        ed.text.append("the ")
        swipe('w')
        engine.deliverSwipe(listOf("word", "world", "wild"), best = 1)   // the LM picked the geometry runner-up
        assertEquals("the world ", ed.text.toString())
        assertEquals("world", ctl.shown[0])
        assertTrue(ctl.shown.contains("word"))
        ctl.pickSuggestion(ctl.shown.indexOf("word"))
        assertEquals("the word ", ed.text.toString())
        type("\b")                                          // still one backspace to remove the swiped word
        assertEquals("the ", ed.text.toString())
    }

    @Test fun swipeWithShiftCapitalisesAndHoldsKeysTypedMeanwhile() {
        ctl.shift = Shift.ON
        swipe('h')
        assertEquals(Shift.ON, ctl.shift)                    // the press's shift-off was taken back too
        assertEquals("A", engine.swipes.first().first.casePattern)
        type("ok")                                           // typed while the swipe decodes: held
        assertEquals("", ed.text.toString())
        engine.deliverSwipe(listOf("hello"))
        assertEquals("Hello ok", ed.text.toString())
    }

    @Test fun swipeFinishesTheWordBeingTyped() {
        timer.fastExpires = false
        type("teh")
        swipe('q')
        engine.deliver()                                     // "teh" corrected and committed first
        engine.deliverSwipe(listOf("quick"))
        assertEquals("the quick ", ed.text.toString())
    }
}
