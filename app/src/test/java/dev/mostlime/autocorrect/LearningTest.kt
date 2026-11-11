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
        var checks = 0
        override fun checkSentences(reqs: List<dev.mostlime.autocorrect.engine.SentenceReq>, done: (List<List<dev.mostlime.autocorrect.engine.SentenceFix>>?) -> Unit) {
            checks++
            done(reqs.map { dev.mostlime.autocorrect.engine.SentenceCheck.check(corrector, it, rewindLm, P, KeyboardController.FLAG_MARGIN) })
        }
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
        override fun shouldLearn(typed: String, typoLike: Boolean) = user.shouldLearn(typed, session, typoLike)
        override fun addLearned(w: String) = user.addProbation(w, session)
        override fun seenTyped(w: String) { user.seenTyped(w, session) }
        override fun nearCommonWord(w: String) = nearCommon
    }
    private var session = "s1"
    private var nearCommon = false
    /** upload events (autocorrect-events/3) */
    private val uploads = ArrayList<Map<String, Any?>>()
    /** the "Palantir Mode" opt-in */
    private var ctxOn = false
    private val corrections = dev.mostlime.autocorrect.log.CorrectionLog({ uploads.add(it) }, learning::inVocab, { user.onProbation(it) },
        context = { ctxOn }, ctxWindow = KeyboardController.LOG_CONTEXT_CHARS)
    private val ctl = KeyboardController({ ed }, engine, object : ControllerUi {
        override fun showStrip(words: List<String>, bold: Int, undo: Int) {}
        override fun setShift(shift: Shift) {}
        override fun onResult(r: WordResult?) {}
        override fun onDictAdded(word: String) { added.add(word) }
    }, timer, learning, corrections) { events.add(it) }

    private fun start(suggestOnly: Boolean = false, confidentOnly: Boolean = false, style: String = "standard") =
        ctl.startInput(ControllerConfig(FieldPolicy.of(InputType.TYPE_CLASS_TEXT, 0), true, style, { "test" }, "t",
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
        type("so i want teh quick brown ")
        engine.deliver()
        assertEquals("so I want teh quick brown ", ed.text.toString())   // only the fixed rules (i -> I) edit in suggest-only
        assertTrue(ed.marks.isEmpty())                     // nothing written while typing
        // one small chip at the side; the strip's word cells stay empty for candidates
        assertEquals(StripMode.FLAG, ctl.stripMode)
        assertEquals("teh → the", ctl.shownAction)
        assertTrue(ctl.shown.isEmpty())
        assertEquals(0, engine.rewinds)
        assertTrue(events.any { it["outcome"] == "flagged" && it["typed"] == "teh" })
        // a pause: the underline is attached
        timer.fireAll()
        val at = "so I want ".length
        assertEquals("the", ed.marks[at]?.first())
        assertEquals("so I want teh quick brown ", ed.text.toString())
        // tapping the chip is an explicit edit: allowed
        ctl.stripAction()
        assertEquals("so I want the quick brown ", ed.text.toString())
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

    // ------------------------------------------------------------------ fixed rules (FixedFixes): lone i, missing apostrophes
    private fun fixedCase(mode: String, sug: Boolean, typed: String, want: String) {
        ed.text.setLength(0); engine.queue.clear()
        start(suggestOnly = sug, style = mode)
        type(typed)
        engine.deliver()
        assertEquals("$mode suggestOnly=$sug", want, ed.text.toString())
    }
    @Test fun dbgStdIm() = fixedCase("standard", false, "im ", "I'm ")
    @Test fun dbgStdSo() = fixedCase("standard", false, "i so want ", "I so want ")
    @Test fun dbgStdThe() = fixedCase("standard", false, "the one i dont be ", "the one I don't be ")
    @Test fun dbgStdAll() = fixedCase("standard", false, "i so want im the one i dont be ", "I so want I'm the one I don't be ")
    @Test fun dbgSugAll() = fixedCase("standard", true, "i so want im the one i dont be ", "I so want I'm the one I don't be ")
    @Test fun dbgFormalAll() = fixedCase("formal", false, "i so want im the one i dont be ", "I so want I'm the one I don't be ")
    @Test fun dbgFormalSugAll() = fixedCase("formal", true, "i so want im the one i dont be ", "I so want I'm the one I don't be ")

    @Test fun loneIAndContractionsAreFixedInEveryModeButCasual() {
        // "only auto-correct when confident": a deterministic fix is never unsure
        ed.text.setLength(0); engine.queue.clear()
        start(confidentOnly = true)
        engine.crafted["dont"] = result("dont", listOf("dont", "don't"), doubleArrayOf(0.0, 0.5))
        type("dont ")
        engine.deliver()
        assertEquals("don't ", ed.text.toString())
        // casual keeps the text as typed
        ed.text.setLength(0); engine.queue.clear()
        start(style = "casual")
        type("i dont ")
        engine.deliver()
        assertEquals("i dont ", ed.text.toString())
    }

    @Test fun contractionFixKeepsTheFirstLettersCase() {
        start()
        ctl.shift = Shift.ON
        type("dont ")
        engine.deliver()
        assertEquals("Don't ", ed.text.toString())
        type("one more ")
        engine.deliver()
        assertEquals("Don't one more ", ed.text.toString())
    }

    @Test fun undoneFixedFixesStayUndone() {
        start()
        engine.crafted["dont"] = result("dont", listOf("dont", "don't"), doubleArrayOf(1.0, 0.0))   // the model alone would keep it
        engine.crafted["i"] = result("i", listOf("i", "I"), doubleArrayOf(1.0, 0.0))
        type("i dont ")
        engine.deliver()
        assertEquals("I don't ", ed.text.toString())
        type("\b"); type(" ")                               // undo don't, keep it as typed
        assertEquals("I dont ", ed.text.toString())
        assertTrue(user.penalty("dont", "don't") > 0)
        assertTrue(events.any { it["type"] == "suppress" && it["typed"] == "dont" && it["chosen"] == "don't" })
        type("dont ")
        engine.deliver()
        assertEquals("I dont dont ", ed.text.toString())     // never again (also with a capital: the memory ignores case)
        // the lone i: same rule
        type("i ")
        engine.deliver()
        assertEquals("I dont dont I ", ed.text.toString())
        type("\b"); type(" ")
        assertTrue(user.penalty("i", "I") > 0)
        type("i ")
        engine.deliver()
        assertEquals("I dont dont i i ", ed.text.toString())
    }

    @Test fun autoAddShowsNoPill() {
        start()
        repeat(2) { type("fized "); engine.deliver(); type("\b"); type(" ") }
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
        assertEquals(StripMode.NONE, ctl.stripMode)         // 5 words back: no chip
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

    /** "re-check every N words": runs as results come in, no pause needed; marks still wait for a quiet moment */
    @Test fun periodicRecheckRunsWithoutAPause() {
        ctl.startInput(ControllerConfig(FieldPolicy.of(InputType.TYPE_CLASS_TEXT, 0), true, "standard", { "test" }, "t",
            suggestOnly = true, recheckEvery = 2))
        type("so teh quick ")
        engine.deliver()
        assertTrue("checks ${engine.checks}", engine.checks >= 1)
        assertTrue(ed.marks.isEmpty())
        assertEquals("so teh quick ", ed.text.toString())
        timer.fireAll()
        assertTrue(ed.marks.isNotEmpty())
    }

    /** undo then retyping the fix is an accidental backspace: nothing is learned from it */
    @Test fun accidentalBackspaceIsNotARejection() {
        start()
        repeat(3) {
            type("fized "); engine.deliver()
            type("\b")                                      // reverted to "fized" (composing)
            type("\b\b\b\b\bfixed ")                         // ...and the fix typed again right away
        }
        assertFalse(user.has("fized"))
        // a real undo (the word as typed stays) still counts
        repeat(2) { type("fized "); engine.deliver(); type("\b"); type(" ") }
        assertTrue(user.has("fized"))
    }

    /** a typo of a common word the model was sure about needs undos in two sessions, and then starts on probation */
    @Test fun typoLookingWordsNeedTwoSessionsAndProbation() {
        nearCommon = true
        engine.crafted["fized"] = result("fized", listOf("fized", "fixed"), doubleArrayOf(0.0, 9.0))   // strong preference
        start()
        repeat(3) { type("fized "); engine.deliver(); type("\b"); type(" ") }
        assertFalse("same session: not learned", user.has("fized"))
        session = "s2"; start()
        type("fized "); engine.deliver(); type("\b"); type(" ")
        assertTrue(user.has("fized"))
        assertEquals(listOf("fized"), user.probationWords())     // on probation: protected, not suggested yet
        assertTrue(user.near("fizrd").isEmpty())
        session = "s3"; start()
        type("fized ")                                           // used in another session: graduates
        assertTrue(user.words().contains("fized"))
        assertTrue(user.probationWords().isEmpty())
    }

    /** a re-check (pause, or every N words) never touches the word at the cursor */
    @Test fun recheckNeverChangesTheWordAtTheCursor() {
        ctl.startInput(ControllerConfig(FieldPolicy.of(InputType.TYPE_CLASS_TEXT, 0), true, "standard", { "test" }, "t", recheckEvery = 1))
        engine.wordLm = farLm; engine.rewindLm = farLm
        type("i want too go to the shop and then home now too")      // "too" (the last one) is still being typed
        engine.deliver()
        timer.fireAll()
        engine.deliverRewinds()
        assertTrue(ed.text.toString(), ed.text.endsWith(" too"))
        assertEquals("too", ed.composingText)
    }

    /** a short out-of-vocabulary word needing two letters changed ("wruff" -> "stuff") is underlined, not replaced */
    @Test fun riskyShortWordIsUnderlinedNotReplaced() {
        start()
        engine.crafted["wruff"] = result("wruff", listOf("wruff", "stuff"), doubleArrayOf(0.0, 6.0))
        type("that wruff ")
        engine.deliver()
        assertEquals("that wruff ", ed.text.toString())
        timer.fireAll()
        assertEquals("stuff", ed.marks["that ".length]?.first())
    }

    /** tapping a word shows its alternatives; a tap swaps one in */
    @Test fun tappingAWordOffersItsAlternatives() {
        start()
        engine.suggestions = { t -> if (t == "fixed") listOf("fixed", "fixes", "mixed") else emptyList() }
        type("fized it ")
        engine.deliver()
        assertEquals("fixed it ", ed.text.toString())
        // the cursor goes into "fixed" (FakeEditor keeps it at the end, so put "fixed" last)
        ed.text.setLength(0); ed.text.append("so fixed")
        ctl.cursorMoved(ed.text.length, ed.text.length)
        assertEquals(StripMode.WORD_ALTS, ctl.stripMode)
        assertEquals("fized", ctl.shown[0])                  // the word as typed comes first after a correction
        ctl.pickSuggestion(ctl.shown.indexOf("mixed"))
        assertEquals("so mixed", ed.text.toString())
    }

    /** one tap on the pill fixes; it's only for the word just finished or up to 2 words back; the switch turns it off */
    @Test fun fixPillOnlyForRecentWordsAndCanBeTurnedOff() {
        start(suggestOnly = true)
        type("so teh quick ")
        engine.deliver()
        assertEquals("teh → the", ctl.shownAction)
        ctl.stripAction()
        assertEquals("so the quick ", ed.text.toString())
        assertEquals(null, ctl.shownAction)                 // no flip-back pill
        type("a fized it ")
        engine.deliver()
        assertEquals("fized → fixed", ctl.shownAction)
        type("is a lot ")                                    // three words later the pill is gone
        engine.deliver()
        assertEquals(null, ctl.shownAction)
        ctl.startInput(ControllerConfig(FieldPolicy.of(InputType.TYPE_CLASS_TEXT, 0), true, "standard", { "test" }, "t",
            suggestOnly = true, fixPill = false))
        type("so teh ")
        engine.deliver()
        assertEquals(null, ctl.shownAction)
        assertEquals(StripMode.NONE, ctl.stripMode)
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

    // ------------------------------------------------------------------ upload events through the real controller
    private fun upload(typed: String) = uploads.single { it["typed"] == typed }

    @Test fun uploadKeptAndUndone() {
        start()
        type("so i want teh quick brown fox ")
        engine.deliver()
        assertTrue(ed.text.toString(), ed.text.endsWith("want the quick brown fox "))
        ctl.finishInput()
        val e = upload("teh")
        assertEquals("auto_applied_kept", e["outcome"]); assertEquals("the", e["suggestion"])
        assertEquals(listOf("i", "want"), e["before"]); assertEquals(listOf("quick"), e["after"])
        assertEquals("spelling", e["kind"])

        uploads.clear(); ed.text.setLength(0); start()
        type("fized ")
        engine.deliver()
        type("\b")                                        // backspace undo
        type(" a lot ")                                    // finished as typed: a real undo
        engine.deliver()
        ctl.finishInput()
        assertEquals("auto_applied_undone", upload("fized")["outcome"])
        assertEquals(listOf("a"), upload("fized")["after"])
    }

    @Test fun uploadManualFixAndAccidentalBackspace() {
        start()
        type("fized ")
        engine.deliver()
        assertEquals("fixed ", ed.text.toString())
        type("\b")                                        // undo -> "fized" composing
        type("\b\b\bes ")                                  // retyped the slot as "fies"
        engine.deliver()
        ctl.finishInput()
        val e = upload("fized")
        assertEquals("manual_fix", e["outcome"]); assertEquals("fies", e["final"])

        uploads.clear(); ed.text.setLength(0); start()
        type("fized ")
        engine.deliver()
        type("\b")                                        // undo...
        type("\b\b\b\b\bfixed ")                           // ...and typed the same fix again: accidental, nothing sent
        engine.deliver()
        ctl.finishInput()
        assertTrue(uploads.toString(), uploads.none { it["typed"] == "fized" })
    }

    @Test fun uploadSuggestOnlyPickNotATypoAndIgnore() {
        start(suggestOnly = true)
        type("so i want teh quick brown ")
        engine.deliver()
        ctl.stripAction()                                  // the "teh -> the" chip
        ctl.finishInput()
        val e = upload("teh")
        assertEquals("suggestion_picked", e["outcome"]); assertEquals(0, e["picked_rank"])

        uploads.clear(); ed.text.setLength(0); start(suggestOnly = true)
        type("fized a lot ")
        engine.deliver(); timer.fireAll()
        ctl.stripActionLongPress()                         // not a typo
        ctl.finishInput()
        assertEquals("not_a_typo", upload("fized")["outcome"])

        uploads.clear(); ed.text.setLength(0); start(suggestOnly = true)
        type("teh quick ")
        engine.deliver()
        ctl.finishInput()                                  // left alone until the field closed
        assertEquals("suggestion_ignored", upload("teh")["outcome"])
    }

    @Test fun uploadSentenceContextOnlyWithItsOwnOptIn() {
        // off (the default): word-level events only, whatever was typed around the word
        start()
        type("so i want teh quick brown fox. ok ")
        engine.deliver()
        ctl.finishInput()
        var e = upload("teh")
        assertFalse(e.containsKey("ctx_before")); assertFalse(e.containsKey("ctx_after"))
        assertFalse(uploads.toString(), uploads.toString().contains("brown"))

        // on: the rest of the sentence, scrubbed; nothing from the sentence before or after it
        uploads.clear(); ed.text.setLength(0); ctxOn = true; start()
        type("first bit. call me at 5551234 or teh quick brown fox. next one ")
        engine.deliver()
        ctl.finishInput()
        e = upload("teh")
        assertEquals("auto_applied_kept", e["outcome"])
        assertEquals("call me at <num> or", e["ctx_before"])
        assertEquals("quick brown fox.", e["ctx_after"])
        assertEquals(3, e["v"])
        assertFalse(uploads.toString(), uploads.toString().contains("555") || uploads.toString().contains("first") || uploads.toString().contains("next"))
    }

    @Test fun uploadNoContextFromNameFieldsAndNothingFromExcludedApps() {
        ctxOn = true
        // a name field: the correction event is fine, the sentence around it is not taken
        ctl.startInput(ControllerConfig(FieldPolicy.of(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PERSON_NAME, 0), true, "standard", { "test" }, "t"))
        type("so i want teh quick brown ")
        engine.deliver()
        ctl.finishInput()
        val e = upload("teh")
        assertFalse(e.containsKey("ctx_before")); assertFalse(e.containsKey("ctx_after"))

        // a password manager / terminal: no event at all, even from a plain text field
        uploads.clear(); ed.text.setLength(0)
        for (pkg in listOf("com.x8bit.bitwarden", "com.termux")) {
            ctl.startInput(ControllerConfig(FieldPolicy.of(InputType.TYPE_CLASS_TEXT, 0, pkg), true, "standard", { "test" }, "t"))
            type("so i want teh quick brown ")
            engine.deliver()
            ctl.finishInput()
            ed.text.setLength(0)
        }
        // incognito (IME_FLAG_NO_PERSONALIZED_LEARNING), number, phone, URL, date fields
        for ((t, o) in listOf(InputType.TYPE_CLASS_TEXT to android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING,
            InputType.TYPE_CLASS_NUMBER to 0, InputType.TYPE_CLASS_PHONE to 0, InputType.TYPE_CLASS_DATETIME to 0,
            (InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI) to 0,
            (InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD) to 0)) {
            ctl.startInput(ControllerConfig(FieldPolicy.of(t, o), true, "standard", { "test" }, "t"))
            type("so i want teh quick brown ")
            engine.deliver()
            ctl.finishInput()
            ed.text.setLength(0)
        }
        assertTrue(uploads.toString(), uploads.isEmpty())
    }

    @Test fun uploadNothingFromPasswordFieldsOrProbationWords() {
        ctl.startInput(ControllerConfig(FieldPolicy.of(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD, 0), true, "standard", { "test" }, "t"))
        type("teh quick fized ")
        engine.deliver()
        ctl.finishInput()
        ctl.startInput(ControllerConfig(FieldPolicy.of(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, 0), true, "standard", { "test" }, "t"))
        type("teh quick ")
        engine.deliver()
        ctl.finishInput()
        assertTrue(uploads.toString(), uploads.isEmpty())

        user.addProbation("Fized", "s0")
        start(suggestOnly = true)
        type("fized a lot ")
        engine.deliver()
        ctl.finishInput()
        assertTrue(uploads.toString(), uploads.none { it["typed"] == "fized" })
    }
}
