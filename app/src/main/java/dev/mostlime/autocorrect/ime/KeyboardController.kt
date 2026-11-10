package dev.mostlime.autocorrect.ime

import dev.mostlime.autocorrect.engine.MergeInput
import dev.mostlime.autocorrect.engine.PrevWord
import dev.mostlime.autocorrect.engine.Rewind
import dev.mostlime.autocorrect.engine.RewindChange
import dev.mostlime.autocorrect.engine.RewindTarget
import dev.mostlime.autocorrect.engine.SEG
import dev.mostlime.autocorrect.engine.SentenceCheck
import dev.mostlime.autocorrect.engine.SentenceFix
import dev.mostlime.autocorrect.engine.SentenceReq
import dev.mostlime.autocorrect.engine.Slot
import dev.mostlime.autocorrect.engine.SwipeResult
import dev.mostlime.autocorrect.engine.Touch
import dev.mostlime.autocorrect.engine.WordInput
import dev.mostlime.autocorrect.engine.WordResult
import dev.mostlime.autocorrect.log.CorrectionLog
import java.util.UUID

/** The text field, as the controller sees it (InputConnection in the app, a fake in tests). All calls are synchronous. */
interface Editor {
    fun textBefore(n: Int): String
    fun hasSelection(): Boolean
    fun setComposing(text: String)
    fun finishComposing()
    fun commit(text: String)
    fun deleteBefore(n: Int)
    /** replace the n chars before the cursor with text, as one edit where the editor allows it */
    fun replaceBefore(n: Int, text: String) { deleteBefore(n); commit(text) }
    /** same, with a likely-typo mark (SuggestionSpan in the app: red underline + the app's tap-to-fix menu) on a word of text */
    fun replaceBefore(n: Int, text: String, mark: TypoMark?) = replaceBefore(n, text)
    /** cursor keys: "L", "R", "U", "D" */
    fun moveCursor(dir: String) {}
    /** cursor position in the field (-1 = unknown) */
    fun cursorPos(): Int = -1
    /** does the field keep our SuggestionSpans (read back with styles)? null = can't tell */
    fun keepsSpans(): Boolean? = null
    /** replace len chars starting offset chars from the cursor (negative = before it); the cursor keeps its place in the text */
    fun replaceRange(offset: Int, len: Int, text: String) {}
    /** text after the cursor (suggest-only idle pass) */
    fun textAfter(n: Int): String = ""
    /** re-commit `text` (unchanged) starting `offset` chars from the cursor (negative = before it) with exactly these typo marks */
    fun remark(offset: Int, text: String, marks: List<TypoMark>) {}
    fun beginBatch()
    fun endBatch()
    /** backspace with no composing word: -> chars removed before the cursor, or -1 if unknown (selection, key event) */
    fun deleteKey(): Int
    /** enter: editor action or newline */
    fun enter()
    fun capsMode(): Boolean
}

/** Off-main-thread work. Callbacks must come back on the main thread (the controller is single-threaded). */
interface CorrectionEngine {
    fun correct(req: CorrectionRequest, done: (WordResult?) -> Unit)
    fun suggest(typed: String, touches: List<Touch>, done: (List<String>) -> Unit)
    /** drop queued (not yet started) work */
    fun cancelQueued()
    /** suggest-only idle pass: typo fixes per sentence (null = no model / cancelled) */
    fun checkSentences(reqs: List<SentenceReq>, done: (List<List<SentenceFix>>?) -> Unit) = done(null)
    /** drop the idle work (a key was pressed); its callback then gets null / empty */
    fun cancelIdle() {}
    /** the idle (whole-sentence) rewind: cancelled by cancelIdle */
    fun rewindIdle(targets: List<RewindTarget>, done: (List<RewindChange>) -> Unit) = rewind(targets, done)
    /** confusion-set rewind (needs the model; done(empty) without one) */
    fun rewind(targets: List<RewindTarget>, done: (List<RewindChange>) -> Unit) = done(emptyList())
    /** swipe: geometry decode + LM rerank of the gesture (done(null) when unavailable) */
    fun swipe(req: SwipeRequest, done: (SwipeResult?) -> Unit) = done(null)
}

/** a word of the committed text marked as a likely typo: [start, start + len) of the text, best fixes first */
class TypoMark(val start: Int, val len: Int, val suggestions: List<String>)

/** Personal dictionary + learned edits, as the controller needs them (UserData in the app). */
interface Learning {
    fun isUserWord(w: String): Boolean = false
    /** in the lexicon the candidate generator uses (or the personal dictionary) */
    fun inVocab(w: String): Boolean = true
    fun addWord(w: String): Boolean = false
    fun removeWord(w: String) {}
    fun suppress(typed: String, chosen: String) {}
    fun penalty(typed: String, chosen: String): Double = 0.0
    /** -> spelling undos of this typed word so far */
    fun noteSpellingUndo(typed: String): Int = 0
    /** quality guard: count this undo; true = learn the word now (see UserData.shouldLearn) */
    fun shouldLearn(typed: String, typoLike: Boolean): Boolean = noteSpellingUndo(typed) >= 2 && !typoLike
    /** a word learned from undos: on probation (protected, not suggested yet) */
    fun addLearned(w: String): Boolean = addWord(w)
    /** within edit distance 2 of a common lexicon word */
    fun nearCommonWord(w: String): Boolean = false
    /** a dictionary / probation word was typed and kept (probation words graduate when used in another session) */
    fun seenTyped(w: String) {}
}

/** A finished swipe gesture: path in model units, the LM context, and the case pattern for the word ("A" shift, "AA" caps lock). */
class SwipeRequest(val path: List<Touch>, val left: String, val sep: String, val casePattern: String)

interface ControllerUi {
    /** undo = index of the word as typed after a correction (drawn as an undo chip), -1 if none */
    fun showStrip(words: List<String>, bold: Int, undo: Int = -1)
    /** hint = small label ("typo?"), action = a chip at the end of the strip ("not a typo", "undo") */
    fun showStrip(words: List<String>, bold: Int, undo: Int, hint: String, action: String?) = showStrip(words, bold, undo)
    /** a word joined the personal dictionary (toast) */
    fun onDictAdded(word: String) {}
    /** a correction just changed the text (micro-interaction hook) */
    fun onApplied() {}
    fun setShift(shift: Shift)
    fun onResult(r: WordResult?)
}

interface Timer {
    fun post(delayMs: Long, r: () -> Unit): Any
    fun cancel(token: Any)
}

class CorrectionRequest(val id: Int, val input: WordInput, val prev: PrevWord?, val merge: MergeInput?, val formal: Boolean = false,
                        val autoCap: Boolean = true)

/** Snapshot of the settings the controller needs (re-read at every input start). */
class ControllerConfig(
    val policy: FieldPolicy,
    val autocorrect: Boolean,
    val style: String,
    val modelId: () -> String,
    val appVersion: String,
    /** confusion-set rewind on/off */
    val rewind: Boolean = true,
    /** never edit the text: likely typos are underlined and offered in the strip */
    val suggestOnly: Boolean = false,
    /** auto-correct mode: unsure corrections (small margin) are underlined + suggested instead of applied */
    val confidentOnly: Boolean = false,
    /** sentence-start capitals: auto-shift (following the field's CAP flags) and the corrector's sentence capitals */
    val autoCap: Boolean = true,
    /** flag likely typos (marks + the side chip); off = suggest-only / unsure words just stay as typed */
    val flagTypos: Boolean = true,
    /** pause in typing before the idle re-check (sentence check / far rewind) */
    val idlePauseMs: Long = KeyboardController.IDLE_PAUSE_MS,
    /** also re-check every N finished words, without a pause (0 = off); the next key still cancels it */
    val recheckEvery: Int = 0,
    /** re-check the whole field (sentence-cached) instead of the sentences near the cursor */
    val wholeField: Boolean = false,
    /** left context handed to the model, chars (the scorer clips to its token budget) */
    val ctxChars: Int = KeyboardController.CTX_CHARS,
    /** suggest-only: the "teh → the" pill in the strip */
    val fixPill: Boolean = true,
)

/**
 * The keyboard's text state machine, independent of Android so it can be tested with a fake editor and a slow engine.
 *
 * Rules that keep the text consistent when inference is slow:
 *  - Keys are handled immediately on the main thread; nothing waits for the model.
 *  - At word end the typed word is committed as typed, and a correction request goes to the engine thread.
 *  - When the result comes back it is applied only if the text before the cursor is still exactly
 *    [previous word + separator] + typed + separator + (the word being composed now); the replacement is one batch
 *    edit (finish composing, delete that span, commit the corrected span, restore the composing word). Otherwise the
 *    result is dropped.
 *  - Enter with a correction still running holds the enter (and any keys typed meanwhile) until the result arrives or
 *    ENTER_HOLD_MS passes, so "Send" sends the corrected text without blocking the main thread.
 *  - The composing word is re-validated against the live text (text before the cursor must end with it) whenever the
 *    editor reports a selection change; if the user moved the cursor, the word is finished as is.
 */
class KeyboardController(
    private val editor: () -> Editor?,
    private val engine: CorrectionEngine,
    private val ui: ControllerUi,
    private val timer: Timer,
    private val learning: Learning = object : Learning {},
    /** opt-in upload events (word-level, scrubbed): fed from write() plus the hooks below; never for sensitive fields */
    private val corrections: CorrectionLog? = null,
    private val events: (Map<String, Any?>) -> Unit,
) {
    var config = ControllerConfig(FieldPolicy.NONE, false, "standard", { "none" }, "")

    val composing = StringBuilder()
    private val touches = ArrayList<Touch>()
    private var keepTypedOnce = false

    var shift = Shift.OFF
        set(v) { field = v; ui.setShift(v) }
    private var lastShiftTap = 0L
    var lettersLayer = true

    /** The last word put in by a correction, a strip pick or a swipe (typed == "" for a swipe; lead = its auto space). */
    private class Last(
        val typed: String, val touches: List<Touch>, val out: String, val sepText: String, val result: WordResult?,
        var event: MutableMap<String, Any?>?, val undoPrefix: String, val lead: String = "",
    ) { val swiped get() = typed.isEmpty() }
    private var last: Last? = null

    private class Prev(val slot: Slot, var idx: Int, var out: String, val lmScored: Boolean, val typed: String, val touches: List<Touch>,
                       val margin: Double = 0.0, val scores: DoubleArray? = null)
    private var prev: Prev? = null

    /** A correction in flight. anchor = our cursor position counter right after the word + separator were committed. */
    private class Pending(
        val id: Int, val typed: String, val touches: List<Touch>, var sepText: String, val before: String, val sep: String,
        val prevOut: String?, val prevSep: String, var anchor: Long,
    ) { var done = false; var valid = true; var uncommitted = false }
    private val pendings = ArrayList<Pending>()
    private var nextId = 1

    /**
     * Cursor position counter: every edit goes through [tracked], so for each pending correction we know how many
     * characters were typed after its word (they're kept and re-inserted when the correction lands late).
     * Edits we can't size (enter, deleting a selection, the user moving the cursor) invalidate the pending ones.
     */
    private var pos = 0L
    private var compLen = 0
    private fun invalidatePendings() { for (p in pendings) p.valid = false }

    private inner class Tracked(val e: Editor) : Editor {
        override fun textBefore(n: Int) = e.textBefore(n)
        override fun hasSelection() = e.hasSelection()
        override fun setComposing(text: String) { e.setComposing(text); pos += text.length - compLen; compLen = text.length }
        override fun finishComposing() { e.finishComposing(); compLen = 0 }
        override fun commit(text: String) { e.commit(text); pos += text.length - compLen; compLen = 0 }
        override fun deleteBefore(n: Int) { e.deleteBefore(n); pos -= n }
        override fun replaceBefore(n: Int, text: String) { e.replaceBefore(n, text); pos += text.length - n; compLen = 0 }
        override fun replaceBefore(n: Int, text: String, mark: TypoMark?) { e.replaceBefore(n, text, mark); pos += text.length - n; compLen = 0 }
        override fun textAfter(n: Int) = e.textAfter(n)
        override fun moveCursor(dir: String) { e.moveCursor(dir); invalidatePendings() }
        override fun cursorPos() = e.cursorPos()
        override fun keepsSpans() = e.keepsSpans()
        override fun replaceRange(offset: Int, len: Int, text: String) {
            e.replaceRange(offset, len, text)
            if (offset + len <= 0) pos += text.length - len else invalidatePendings()
        }
        override fun remark(offset: Int, text: String, marks: List<TypoMark>) = e.remark(offset, text, marks)
        override fun beginBatch() = e.beginBatch()
        override fun endBatch() = e.endBatch()
        override fun deleteKey(): Int { val n = e.deleteKey(); if (n < 0) invalidatePendings() else pos -= n; return n }
        override fun enter() { e.enter(); invalidatePendings() }
        override fun capsMode() = e.capsMode()
    }

    private fun ed(): Editor? = editor()?.let { Tracked(it) }

    private var holding = false
    /** what the current hold waits for (fast hold: this word's result; enter: every pending correction) */
    private var holdDone: () -> Boolean = { true }
    private val held = ArrayList<() -> Unit>()
    private var holdToken: Any? = null

    enum class StripMode { NONE, TYPING, LAST_WORD, REWIND, TYPO, DICT_ADDED, FLAG, WORD_ALTS }

    /** the most recent word marked as a likely typo (start = its position in the counter) */
    private class Typo(val start: Long, val typed: String, val fixes: List<String>, val spelling: Boolean,
                       val event: MutableMap<String, Any?>?, val leftContext: String)
    private var typo: Typo? = null

    /**
     * Flagged words anywhere in the field (suggest-only / unsure), by their offset in the field. Offsets go stale when
     * text before them changes: a flag is checked against the text (and looked for nearby) whenever it's used.
     */
    private class Flag(var start: Int, val word: String, val fixes: List<String>, val spelling: Boolean,
                       val event: MutableMap<String, Any?>?, val leftContext: String)
    private val flags = ArrayList<Flag>()
    /** the flag the cursor is on (its fixes are in the strip) */
    private var cursorFlag: Flag? = null
    /** the flag in the side chip */
    private var chipFlag: Flag? = null
    /** this field drops our SuggestionSpans (seen once): stop attaching them */
    private var spansDropped = false
    /** flags whose underline has been attached */
    private val marked = HashSet<Flag>()

    private fun addFlag(f: Flag) {
        flags.removeAll { it.start < f.start + f.word.length && f.start < it.start + it.word.length }
        flags.add(f)
        while (flags.size > MAX_FLAGS) flags.removeAt(0)
    }
    /** word just added to the dictionary by repeated undos (strip offers to take it back) */
    private var dictAdded: String? = null

    /**
     * An auto-correction was just reverted with backspace. What counts is what the user does next: finishing the word
     * as typed is a real undo (learned from); deleting it and typing the fix again is an accidental backspace (noise,
     * nothing learned); anything else isn't a verdict on the fix either way.
     */
    private class PendingUndo(val word: String, val full: String, val out: String, val gap: Double, val ev: Map<String, Any?>?)
    private var pendingUndo: PendingUndo? = null

    /** words already rewound (or reverted by the user) by their start in the position counter: never touched again */
    private val rewound = HashSet<Long>()
    private var rewindBusy = false
    private class RewindUndo(val start: Long, val from: String, val to: String, val event: MutableMap<String, Any?>?)
    private var rewindUndo: RewindUndo? = null
    var stripMode = StripMode.NONE
        private set
    var shown: List<String> = emptyList()
        private set
    private var suggestToken: Any? = null
    private var suggestSeq = 0

    // swipe typing
    /** shift state when the current swipe started */
    private var swipeShift: Shift? = null
    /** id of the swipe being decoded (0 = none); a result for another id is dropped */
    private var swipePending = 0
    private var nextSwipeId = 1
    /** the text ends with the auto space after a swiped word: punctuation typed next goes before it */
    private var swipeSpace = false

    var stats = Stats()
    class Stats { var applied = 0; var dropped = 0; var unchanged = 0 }

    // ------------------------------------------------------------------ input lifecycle
    fun startInput(cfg: ControllerConfig) {
        flushLast()
        corrections?.flush()
        config = cfg
        corrections?.fieldContext = !cfg.policy.noContext
        resetWord(); last = null; prev = null; pendings.clear(); compLen = 0; rewound.clear(); rewindUndo = null; deferred.clear()
        typo = null; dictAdded = null; pendingUndo = null; punctTried.clear(); sentenceCache.clear(); flags.clear(); cursorFlag = null
        chipFlag = null; spansDropped = false; marked.clear()
        idleToken?.let { timer.cancel(it) }; idleToken = null
        markToken?.let { timer.cancel(it) }; markToken = null
        pendingRemarks.clear(); wordsSinceCheck = 0; periodicDue = false
        swipeShift = null; swipePending = 0; swipeSpace = false
        releaseHold()
        shift = Shift.OFF
        autoShift()
        setStrip(emptyList(), -1, StripMode.NONE)
    }

    fun finishInput() {
        flushLast()
        corrections?.flush()
        pendings.filter { it.uncommitted }.forEach { commitHeld(it) }
        releaseHold()
        pendings.clear()
        resetWord(); compLen = 0
    }

    /** The editor reported a selection change: make sure the composing word is still right before the cursor. */
    fun selectionChanged() {
        if (holding || composing.isEmpty()) return
        val ed = ed() ?: return
        if (ed.hasSelection() || !ed.textBefore(composing.length + 1).endsWith(composing)) {
            ed.finishComposing()
            invalidatePendings()
            corr()?.let { c -> pendingUndo?.let { c.undoResolved(it.ev?.get("id") as? String, null) }; c.breakChain() }
            resetWord(); last = null; prev = null; typo = null; pendingUndo = null
            setStrip(emptyList(), -1, StripMode.NONE)
        }
    }

    // ------------------------------------------------------------------ keys
    /** A key press. [base]: the key whose long-press popup [k] came from (it composes like that key would). */
    fun key(k: KeyDef, touch: Touch, base: KeyDef? = null) {
        idleTouch()
        if (holding) { held.add { key(k, touch, base) }; return }
        val ed = ed() ?: return
        val afterSwipe = swipeSpace
        swipeSpace = false
        when (k.type) {
            KeyType.CHAR -> {
                var c = k.text
                if (shift != Shift.OFF) c = c.uppercase()
                val ch = c[0]
                fun composes(x: Char) = x in 'a'..'z' || x in 'A'..'Z' || ((Character.isLetter(x) || x == '\'') && composing.isNotEmpty())
                // a popup choice from a letter key goes into the word like that letter would (é, or q's 1)
                if (composes(ch) || (base != null && base.text.isNotEmpty() && composes(base.text[0]))) {
                    flushLast()
                    if (stripMode != StripMode.NONE && stripMode != StripMode.TYPING) setStrip(emptyList(), -1, StripMode.NONE)
                    composing.append(c); touches.add(touch)
                    ed.setComposing(composing.toString())
                    // ALL-CAPS fields (CAP_CHARACTERS) keep the shift on after each letter
                    if (shift == Shift.ON && !(config.autoCap && config.policy.capChars)) shift = Shift.OFF
                    scheduleSuggest()
                } else if (composing.isNotEmpty()) {
                    finishWord(ed, c, trigger = ch in WORD_END_PUNCT)
                } else if (afterSwipe && ch in SWIPE_PUNCT && ed.textBefore(1) == " " && !ed.hasSelection()) {
                    // "word " + "." -> "word. " (the swiped word's auto space moves after the punctuation)
                    flushLast()
                    ed.beginBatch(); ed.deleteBefore(1); ed.commit("$c "); ed.endBatch()
                    corr()?.between("\b$c ")
                    swipeSpace = true
                    setStrip(emptyList(), -1, StripMode.NONE)
                    autoShift()
                } else {
                    flushLast()
                    ed.commit(c)
                    corr()?.between(c)
                    autoShift()
                }
            }
            KeyType.SPACE -> if (composing.isNotEmpty()) finishWord(ed, " ", trigger = true) else { flushLast(); ed.commit(" "); corr()?.between(" "); autoShift() }
            KeyType.ENTER -> {
                if (composing.isNotEmpty()) finishWord(ed, "", trigger = true)
                corr()?.breakChain()
                if (pendings.any { it.valid }) {
                    // hold enter (and later keys) until the corrections land or the hold times out
                    holding = true
                    holdDone = { pendings.none { it.valid } }
                    holdToken = timer.post(ENTER_HOLD_MS) { releaseHold() }
                    held.add { flushLast(); ed()?.enter(); autoShift() }
                } else { flushLast(); ed.enter(); autoShift() }
            }
            KeyType.BACKSPACE -> backspace(ed)
            KeyType.SHIFT -> {
                val now = System.currentTimeMillis()
                shift = when {
                    shift == Shift.LOCK -> Shift.OFF
                    shift == Shift.ON && now - lastShiftTap < DOUBLE_TAP_MS -> Shift.LOCK
                    shift == Shift.ON -> Shift.OFF
                    else -> Shift.ON
                }
                lastShiftTap = now
            }
            KeyType.CURSOR -> cursor(ed, k.text)
            KeyType.TO_SYMBOLS, KeyType.TO_SYMBOLS2, KeyType.TO_LETTERS, KeyType.EMOJI -> {}
        }
    }

    /** cursor keys (optional row): the word being typed stays as typed, then the cursor moves */
    private fun cursor(ed: Editor, dir: String) {
        flushLast(); cancelSuggest()
        if (composing.isNotEmpty()) { ed.commit(composing.toString()); resetWord() }
        invalidatePendings(); prev = null; last = null; typo = null
        corr()?.breakChain()
        ed.moveCursor(dir)
        setStrip(emptyList(), -1, StripMode.NONE)
        autoShift()
    }

    private fun releaseHold() {
        holdToken?.let { timer.cancel(it) }
        holdToken = null
        if (!holding) return
        holding = false
        val todo = ArrayList(held); held.clear()
        for (a in todo) a()
    }

    private fun backspace(ed: Editor) {
        if (composing.isNotEmpty()) {
            composing.deleteCharAt(composing.length - 1)
            if (touches.isNotEmpty()) touches.removeAt(touches.size - 1)
            if (composing.isEmpty()) { ed.commit(""); setStrip(emptyList(), -1, StripMode.NONE); flushDeferred() } else { ed.setComposing(composing.toString()); scheduleSuggest() }
            return
        }
        val l = last
        if (l != null) {
            val tail = l.out + l.sepText
            if (l.swiped && ed.textBefore(l.lead.length + tail.length) == l.lead + tail && !ed.hasSelection()) {
                // backspace right after a swipe removes the whole word (and its auto space)
                ed.deleteBefore(l.lead.length + tail.length)
                last = null; prev = null
                setStrip(emptyList(), -1, StripMode.NONE)
                autoShift()
                return
            }
            if (!l.swiped && ed.textBefore(tail.length) == tail && !ed.hasSelection()) {
                // undo: back to exactly what was typed, kept as typed at the next word end
                ed.beginBatch()
                ed.deleteBefore(tail.length)
                if (l.undoPrefix.isNotEmpty()) ed.commit(l.undoPrefix)
                composing.append(l.typed); touches.addAll(l.touches)
                ed.setComposing(composing.toString())
                ed.endBatch()
                keepTypedOnce = true
                l.event?.let { it["outcome"] = "undone"; it["final"] = l.typed; write(it) }
                corr()?.reopen()   // the same word slot is finished again (its verdict: undoResolved)
                last = null; prev = null
                setStrip(emptyList(), -1, StripMode.NONE)
                // learned from only if the word as typed then stays (see PendingUndo)
                pendingUndo = PendingUndo(l.typed, l.undoPrefix + l.typed, l.out, gapOf(l.result), l.event)
                return
            }
        }
        flushLast()
        ed.deleteKey()
        corr()?.breakChain()
        setStrip(emptyList(), -1, StripMode.NONE)
        autoShift()
    }

    // ------------------------------------------------------------------ word end
    private fun finishWord(ed: Editor, sepText: String, trigger: Boolean) {
        flushLast()
        pendingUndo?.let { pu ->
            pendingUndo = null
            val now = composing.toString()
            corr()?.undoResolved(pu.ev?.get("id") as? String, now)
            if (now == pu.word) learnFromUndo(pu.full, pu.out, pu.ev, pu.gap)
            // now == pu.out (retyped the fix) or something else: not a rejection, nothing learned
        }
        composing.toString().takeIf { it.isNotEmpty() && learning.isUserWord(it) }?.let { learning.seenTyped(it) }
        cancelSuggest()
        val typed = composing.toString()
        if (typed.isNotEmpty()) corr()?.word(typed, sepText)
        val tch = ArrayList(touches)
        val cfg = config
        val canCorrect = trigger && cfg.policy.autocorrect && cfg.autocorrect && WORD_RE.matches(typed) && !keepTypedOnce
        // the text before the word, from the live editor (the composing word is the last thing before the cursor)
        val raw = if (canCorrect) leftWindow(ed, typed.length) else ""
        val before = if (raw.endsWith(typed)) raw.dropLast(typed.length) else raw
        if (!canCorrect || !raw.endsWith(typed)) {
            ed.commit(typed + sepText)
            resetWord()
            prev = null; autoShift(); setStrip(emptyList(), -1, StripMode.NONE); showTypo(); flushDeferred(); return
        }
        // the word stays as typed (composing) for up to FAST_HOLD_MS: a quick result (cheap path, skip, contractions,
        // a fast model) is then committed directly, in one edit, so the text never shows typed -> corrected.
        // A slower result is applied later over the committed word.
        resetWord()

        val (left, sep) = splitLeft(before)
        val p = prev
        var prevOut: String? = null
        var prevSep = ""
        val prevWord = if (p != null && SEG.findAll(left).lastOrNull()?.let { it.value == p.out && it.range.last == left.length - 1 } == true) {
            val (leftp, sepp) = splitLeft(left.dropLast(p.out.length))
            prevOut = p.out; prevSep = sep
            PrevWord(p.slot, p.idx, leftp, sepp, p.lmScored, p.margin, p.scores)
        } else null
        val id = nextId++
        val pd = Pending(id, typed, tch, sepText, before, sep, prevOut, prevSep, -1).apply { uncommitted = true }
        pendings.add(pd)
        // keep at most MAX_PENDING corrections in flight (slow model + fast typing): the oldest gives way
        while (pendings.size > MAX_PENDING) { val old = pendings.removeAt(0); old.done = true; stats.dropped++ }
        val req = CorrectionRequest(id, WordInput(typed, tch, left, sep), prevWord, if (prevWord != null && p != null) MergeInput(p.typed, p.touches) else null,
            formal = cfg.style == "formal" && !cfg.suggestOnly, autoCap = cfg.autoCap)
        if (cfg.recheckEvery > 0 && ++wordsSinceCheck >= cfg.recheckEvery) periodicDue = true
        prev = null   // set again when this word's result is applied
        setStrip(emptyList(), -1, StripMode.NONE)
        showTypo()
        engine.correct(req) { r -> onCorrection(pd, r) }
        if (pd.uncommitted) {
            holding = true
            holdDone = { !pd.uncommitted }
            holdToken = timer.post(FAST_HOLD_MS) { commitHeld(pd); flushDeferred(); releaseHold() }
        }
    }

    /** Commit a held word as typed (its correction, if any, lands later). */
    private fun commitHeld(pd: Pending) {
        if (!pd.uncommitted) return
        pd.uncommitted = false
        val ed = ed() ?: return
        ed.commit(pd.typed + pd.sepText)
        pd.anchor = pos
        autoShift()
    }

    /** results that waited for a held word: run them now (never from inside another edit) */
    private fun flushDeferred() {
        if (deferred.isEmpty() || pendings.any { it.uncommitted } || composing.isNotEmpty()) return
        val todo = ArrayList(deferred); deferred.clear()
        for ((p, r) in todo) onCorrection(p, r)
    }

    /** results that arrived while a newer word was held (its text is still composing): applied once it's committed */
    private val deferred = ArrayList<Pair<Pending, WordResult?>>()

    private fun onCorrection(pd: Pending, r: WordResult?) {
        if (pd.done) return
        // a late result for an earlier word waits while a word is being typed (composing or held): it's applied at the
        // next separator / pause, re-validated against the text, so the word at the cursor is never split or uncomposed
        if (!pd.uncommitted && (pendings.any { it.uncommitted } || composing.isNotEmpty())) { deferred.add(pd to r); return }
        pd.done = true
        pendings.remove(pd)
        try {
            if (pd.uncommitted) {
                // still held: commit and correct in one batch -> a single visible change
                val ed = ed()
                ed?.beginBatch()
                try { commitHeld(pd); if (pd.valid) apply(pd, r) else ui.onResult(r) } finally { ed?.endBatch() }
                autoShift()
            } else if (pd.valid) apply(pd, r) else { stats.dropped++; ui.onResult(r) }
        } finally {
            flushDeferred()
            if (holding && holdDone()) releaseHold()
        }
        if (r != null && !r.cheap) scheduleRewind()   // the model is loaded (skipped words count too)
        idleTouch()
        // re-check every N words: as soon as this word's result is in (the next key cancels it, like a pause would)
        if (periodicDue && pendings.isEmpty() && !holding) onIdle()
    }

    // ------------------------------------------------------------------ idle passes (no word window)
    private var idleToken: Any? = null
    /** suggest-only: fixes per sentence already marked, by left context + sentence */
    private val sentenceCache = object : LinkedHashMap<String, List<SentenceFix>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<SentenceFix>>?) = size > 64
    }
    /** auto mode: punctuation already proposed after a word (by its start in the counter) */
    private val punctTried = HashSet<Long>()

    /** marks (underlines) wait for this long without typing: they're a same-text rewrite of the field */
    private var markToken: Any? = null
    /** sentence marks found by a re-check, attached at the next quiet moment (markIdle) */
    private class Remark(val before: String, val after: String, val offset: Int, val text: String, val marks: List<TypoMark>)
    private val pendingRemarks = ArrayList<Remark>()
    private var wordsSinceCheck = 0
    private var periodicDue = false

    /** typing (or a result) happened: restart the idle timers, drop idle work in flight */
    private fun idleTouch() {
        idleToken?.let { timer.cancel(it) }
        markToken?.let { timer.cancel(it) }
        engine.cancelIdle()
        idleToken = timer.post(config.idlePauseMs) { idleToken = null; onIdle() }
        markToken = timer.post(MARK_MS) { markToken = null; onQuiet() }
        if (stripMode == StripMode.FLAG && composing.isNotEmpty()) setStrip(emptyList(), -1, StripMode.NONE)
    }

    /** the re-check pass (pause, or every N words): text is read, nothing is written while typing */
    private fun onIdle() {
        if (holding || pendings.isNotEmpty() || rewindBusy) return
        if (!config.policy.autocorrect || !config.autocorrect) return
        wordsSinceCheck = 0; periodicDue = false
        if (config.suggestOnly) { if (config.flagTypos) idleSuggest() } else idleRewind()
    }

    /** 1.5 s without typing: attach underlines (word flags + what the re-check found) */
    private fun onQuiet() {
        flushDeferred()
        if (holding || pendings.isNotEmpty() || composing.isNotEmpty()) return
        if (!config.policy.autocorrect || !config.autocorrect) return
        markIdle()
        applyRemarks()
    }

    /** the model's left context: up to ctxChars, its start moved in quarter-window chunks so the model's KV cache stays valid */
    private fun leftWindow(ed: Editor, extra: Int): String {
        val want = config.ctxChars + extra
        if (config.ctxChars <= CTX_CHARS) return ed.textBefore(want)
        val cp = ed.cursorPos()
        if (cp < 0) return ed.textBefore(want)
        val chunk = maxOf(64, config.ctxChars / 4)
        val start = maxOf(0, ((cp - want + chunk - 1) / chunk) * chunk)
        return ed.textBefore(cp - start)
    }

    private fun applyRemarks() {
        if (pendingRemarks.isEmpty()) return
        val ed = ed() ?: return
        val todo = ArrayList(pendingRemarks); pendingRemarks.clear()
        if (spansDropped || composing.isNotEmpty() || ed.hasSelection()) return
        for (r in todo) {
            val chars = r.before.length
            if (ed.textBefore(chars) != r.before || ed.textAfter(r.after.length) != r.after) continue   // text moved on
            ed.remark(r.offset, r.text, r.marks)
        }
        checkSpans(ed)
    }

    /**
     * Typing has paused: attach underlines to flagged words that don't have one yet (one small same-text replace per
     * word, cursor put back). Never while typing, never on cursor moves, and not at all in a field that drops spans.
     */
    private fun markIdle() {
        if (spansDropped || flags.isEmpty()) return
        val ed = ed() ?: return
        if (ed.hasSelection()) return
        val cur = ed.cursorPos().takeIf { it >= 0 } ?: return
        var did = false
        for (f in flags.filter { it !in marked }.toList()) {
            if (!locate(ed, f, cur)) continue
            ed.remark(f.start - cur, f.word, listOf(TypoMark(0, f.word.length, f.fixes)))
            marked.add(f); did = true
        }
        if (did) checkSpans(ed)
    }

    /** after attaching marks: if the field shows none of them, it drops spans; stop re-committing text there */
    private fun checkSpans(ed: Editor) {
        if (ed.keepsSpans() == false && flags.isNotEmpty()) spansDropped = true
    }

    /**
     * Suggest-only: re-check every sentence of the readable text (before and after the cursor), all words, with the rest
     * of the sentence as right context; marks are added or removed per changed sentence. Text is never changed.
     */
    private fun idleSuggest() {
        if (config.policy.sensitive && config.policy.password) return
        val ed = ed() ?: return
        if (ed.hasSelection()) return
        val chars = if (config.wholeField) FIELD_CHARS else IDLE_CHARS
        // the word being typed is left out (a periodic re-check can run mid-word)
        val beforeAll = ed.textBefore(chars + composing.length)
        if (!beforeAll.endsWith(composing)) return
        val before = beforeAll.dropLast(composing.length)
        val after = if (composing.isEmpty()) ed.textAfter(chars) else ""
        val text = before + after
        val todo = ArrayList<Triple<IntRange, String, String>>()   // range, cache key, left
        for (r in SentenceCheck.split(text)) {
            val left = text.substring(0, r.first).takeLast(IDLE_LEFT)
            val key = left + "\u0001" + text.substring(r)
            if (!sentenceCache.containsKey(key)) todo.add(Triple(r, key, left))
        }
        if (todo.isEmpty()) return
        // nearest to the cursor first
        todo.sortBy { (r, _, _) -> if (r.last < before.length) before.length - r.last else r.first - before.length }
        val batch = todo.take(IDLE_SENTENCES)
        val snapPos = pos
        val snapComp = composing.toString()
        engine.checkSentences(batch.map { (r, _, left) -> SentenceReq(left, text.substring(r)) }) { res ->
            if (res == null || pos != snapPos || composing.toString() != snapComp || holding) return@checkSentences
            val e2 = ed() ?: return@checkSentences
            if (e2.textBefore(chars + snapComp.length) != beforeAll || (snapComp.isEmpty() && e2.textAfter(chars) != after) || e2.hasSelection()) return@checkSentences
            val cp = e2.cursorPos().let { if (it >= 0) it - snapComp.length else it }
            for ((j, fixes) in res.withIndex()) {
                val (r, key, left) = batch[j]
                sentenceCache[key] = fixes
                if (cp >= 0) {
                    val s0 = cp - before.length + r.first
                    val old = flags.filter { it.start >= s0 && it.start < s0 + (r.last - r.first + 1) }
                    flags.removeAll(old.toSet())
                    // a word flagged as it was typed keeps its event (so a later fix / "not a typo" is logged against it)
                    for (fx in fixes) addFlag(Flag(s0 + fx.start, fx.from, fx.fixes, isSpellingEdit(fx.from, fx.to),
                        old.firstOrNull { it.start == s0 + fx.start && it.word == fx.from }?.event,
                        (left + text.substring(r.first, r.first + fx.start)).takeLast(LOG_CONTEXT_CHARS)))
                }
                // underlines wait for a quiet moment (onQuiet): a re-check can run while the user is still typing
                if (!spansDropped && snapComp.isEmpty()) pendingRemarks.add(Remark(before, after, r.first - before.length, text.substring(r),
                    fixes.map { TypoMark(it.start, it.from.length, it.fixes) }))
                // the strip's flagged word: drop it if this pass found it fine
                val t = typo
                if (t != null) {
                    val tStart = before.length - (pos - t.start).toInt()
                    if (tStart in r && fixes.none { it.start == tStart - r.first }) {
                        typo = null
                        if (stripMode == StripMode.TYPO) setStrip(emptyList(), -1, StripMode.NONE)
                    }
                }
            }
            if (markToken == null) applyRemarks()   // already quiet long enough
            if (stripMode == StripMode.NONE || stripMode == StripMode.FLAG) showFlagChip()
            // more sentences: the next round right away (whole field) or after the next pause
            if (todo.size > batch.size) { if (config.wholeField) timer.post(1) { onIdle() } else idleTouch() }
        }
    }

    /**
     * Auto mode, idle: confusable swaps across the whole current sentence (up to 20 words back; the margin grows with
     * distance) and, in the formal style, commas and sentence ends scored on everything typed after them.
     */
    private fun idleRewind() {
        if (rewindBusy) return
        val ed = ed() ?: return
        if (ed.hasSelection()) return
        val cur = composing.toString()
        val raw = ed.textBefore(CTX_CHARS + cur.length)
        if (!raw.endsWith(cur)) return
        val snap = raw.dropLast(cur.length)
        val end = pos - cur.length
        // the current sentence: back to the last . ! ? or newline (formal: the whole tail, so a missing full stop can land)
        val formal = config.style == "formal"
        val cut = if (formal) maxOf(snap.lastIndexOf('\n'), 0) else (snap.indexOfLast { it == '.' || it == '!' || it == '?' || it == '\n' } + 1)
        val words = SEG.findAll(snap).filter { it.range.first >= cut }.toList().takeLast(Rewind.MAX_SENTENCE_WORDS)
        if (words.size < 2) return
        val n = words.size
        val targets = ArrayList<RewindTarget>()
        for (j in 0 until n - 1) {
            val w = words[j]
            val abs = end - (snap.length - w.range.first)
            val back = n - 1 - j
            val (left, sep) = splitLeft(snap.substring(0, w.range.first))
            if (Rewind.isTarget(w.value) && abs !in rewound && !learning.isUserWord(w.value))
                targets.add(RewindTarget(w.range.first, w.value, left, sep, snap.substring(w.range.last + 1).trimEnd(), back))
            if (!formal || abs in punctTried) continue
            val gap = snap.substring(w.range.last + 1, words[j + 1].range.first)
            if (gap != " ") continue                         // already punctuated (or a newline)
            punctTried.add(abs)
            val rest = snap.substring(words[j + 1].range.last + 1).trimEnd()
            targets.add(RewindTarget(w.range.first, w.value, left, sep, gap + words[j + 1].value + rest, back, RewindTarget.COMMA))
            // a sentence end only with at least 3 words of right context
            if (n - 1 - (j + 1) >= 2)
                targets.add(RewindTarget(w.range.first, w.value + gap + words[j + 1].value, left, sep, rest, back, RewindTarget.STOP, config.autoCap))
        }
        if (targets.isEmpty()) return
        rewindBusy = true
        engine.rewindIdle(targets) { changes ->
            rewindBusy = false
            // one change per region: overlapping ones (a full stop over a swapped word) keep the best margin
            val keep = ArrayList<RewindChange>()
            for (c in changes.sortedByDescending { it.margin }) {
                if (keep.none { k -> c.start < k.start + k.from.length && k.start < c.start + c.from.length }) keep.add(c)
            }
            applyRewind(snap, end, keep)
        }
    }

    // ------------------------------------------------------------------ confusion-set rewind
    /**
     * After a word's result lands (on time or late), re-check confusable words up to MAX_WORDS_BACK words back with the
     * text typed since as right context. Runs only when no correction is in flight, on an unchanged snapshot.
     */
    private fun scheduleRewind() {
        if (!config.rewind || config.suggestOnly || rewindBusy || holding || pendings.isNotEmpty()) return
        val ed = ed() ?: return
        val cur = composing.toString()
        val raw = ed.textBefore(CTX_CHARS + cur.length)
        if (!raw.endsWith(cur) || ed.hasSelection()) return
        val snap = raw.dropLast(cur.length)
        val end = pos - cur.length            // position counter at the end of snap
        val words = SEG.findAll(snap).toList()
        if (words.size < 2) return
        val targets = ArrayList<RewindTarget>()
        val n = words.size
        for (j in maxOf(0, n - 1 - Rewind.MAX_WORDS_BACK) until n - 1) {
            val w = words[j]
            val abs = end - (snap.length - w.range.first)
            if (!Rewind.isTarget(w.value) || abs in rewound || learning.isUserWord(w.value)) continue
            val (left, sep) = splitLeft(snap.substring(0, w.range.first))
            targets.add(RewindTarget(w.range.first, w.value, left, sep, snap.substring(w.range.last + 1).trimEnd(), n - 1 - j))
        }
        if (targets.isEmpty()) return
        rewindBusy = true
        engine.rewind(targets) { changes -> rewindBusy = false; applyRewind(snap, end, changes) }
    }

    private fun applyRewind(snap: String, end: Long, changes: List<RewindChange>) {
        // never while a word is being typed (tried again after the next word's result)
        if (changes.isEmpty() || holding || pendings.isNotEmpty() || composing.isNotEmpty()) return
        val ed = ed() ?: return
        val cur = composing.toString()
        val first = changes.minOf { it.start }
        val oldTail = snap.substring(first)
        val x = pos - end                      // typed since the snapshot (incl. the composing word)
        if (x < cur.length || x > MAX_AFTER) return
        val xi = x.toInt()
        val live = ed.textBefore(oldTail.length + xi + 1)
        val L = live.length
        if (L < oldTail.length + xi || live.substring(L - xi - oldTail.length, L - xi) != oldTail || ed.hasSelection() || !live.endsWith(cur)) return
        val after = live.substring(L - xi)
        val sb = StringBuilder(oldTail)
        for (c in changes.sortedByDescending { it.start }) sb.replace(c.start - first, c.start - first + c.from.length, c.to)
        val newTail = sb.toString()
        val before = pos
        ed.beginBatch()
        if (cur.isNotEmpty()) ed.finishComposing()
        ed.replaceBefore(oldTail.length + xi, newTail + after.dropLast(cur.length))
        if (cur.isNotEmpty()) ed.setComposing(cur)
        ed.endBatch()
        val delta = pos - before
        val regionStart = end - oldTail.length
        for (o in pendings) if (o.anchor > regionStart) o.anchor += delta
        prev = null; last = null
        ui.onApplied()
        // the last change gets the undo chip; all are logged
        var lastEv: MutableMap<String, Any?>? = null
        for (c in changes) {
            val abs = end - (snap.length - c.start)
            rewound.add(abs)
            if (!config.policy.sensitive) {
                val e = linkedMapOf<String, Any?>("v" to 1, "id" to UUID.randomUUID().toString(), "ts" to System.currentTimeMillis(),
                    "type" to "rewind", "from" to c.from, "to" to c.to, "margin" to r3(c.margin), "words_back" to c.wordsBack,
                    "left_context" to snap.substring(0, c.start).takeLast(LOG_CONTEXT_CHARS), "right_context" to snap.substring(c.start + c.from.length),
                    "outcome" to "accepted", "style" to config.style, "model" to config.modelId(), "app_version" to config.appVersion)
                write(e); lastEv = e
            }
        }
        val c = changes.maxBy { it.start }
        // start of the changed word in the counter, after this edit (changes before it shifted it)
        val shift = changes.filter { it.start < c.start }.sumOf { it.to.length - it.from.length }
        rewindUndo = RewindUndo(end - (snap.length - c.start) + shift, c.from, c.to, lastEv)
        // no revert chip in the strip (keep the UI simple); a rewind is a normal edit from here on
    }

    /** Undo chip of a rewind: put the original word back, if the text there is unchanged. */
    private fun revertRewind(ed: Editor) {
        val u = rewindUndo ?: return
        rewindUndo = null
        val cur = composing.toString()
        val x = pos - (u.start + u.to.length)
        if (x < cur.length || x > MAX_AFTER) return
        val xi = x.toInt()
        val live = ed.textBefore(u.to.length + xi + 1)
        val L = live.length
        if (L < u.to.length + xi || live.substring(L - xi - u.to.length, L - xi) != u.to || !live.endsWith(cur)) return
        val after = live.substring(L - xi)
        val before = pos
        ed.beginBatch()
        if (cur.isNotEmpty()) ed.finishComposing()
        ed.replaceBefore(u.to.length + xi, u.from + after.dropLast(cur.length))
        if (cur.isNotEmpty()) ed.setComposing(cur)
        ed.endBatch()
        val delta = pos - before
        for (o in pendings) if (o.anchor > u.start) o.anchor += delta
        prev = null
        rewound.add(u.start)
        u.event?.let { val e = LinkedHashMap(it); e["outcome"] = "undone"; e["ts"] = System.currentTimeMillis(); write(e) }
        // a rewind is never a spelling fix: undoing it only makes the same swap harder for a while
        suppressEdit(u.from, u.to, "rewind", u.event)
        setStrip(emptyList(), -1, StripMode.NONE)
    }

    private fun apply(pd: Pending, r: WordResult?) {
        ui.onResult(r)
        if (r == null) return
        val ed = ed() ?: return
        val mg = r.merge?.takeIf { pd.prevOut != null }
        val rc = r.recheck?.takeIf { mg == null && pd.prevOut != null }
        // new text for the previous word: re-check flip and/or formal punctuation
        val prevNew = r.prevText?.let(::capI)?.takeIf { mg == null && pd.prevOut != null && it != pd.prevOut }
        val out = capI(mg?.to ?: r.out)
        val cur = composing.toString()
        val changed = out != pd.typed || prevNew != null
        val ev = if (!config.policy.sensitive) buildEvent(pd, r) else null
        // suggest-only, or an unsure correction with "only auto-correct when confident": mark, never edit
        val unsure = config.confidentOnly && r.out != pd.typed && mg == null && r.margin < CONFIDENT_MARGIN
        // a short word that needs 2+ letters changed ("wruff" -> "stuff") is often slang: underline, don't replace,
        // unless the model is very sure
        // (transpositions count as one: "teh" / "hte" are ordinary typos; real words are left to the other rules)
        val risky = !config.suggestOnly && r.out != pd.typed && mg == null && pd.typed.length <= 5 && !learning.inVocab(pd.typed) &&
            osaDistance(pd.typed.lowercase(), r.out.lowercase()) >= 2 && gapOf(r) < RISKY_APPLY_GAP
        if (config.suggestOnly || unsure || risky) {
            if (flag(pd, r, ev, if (config.suggestOnly) "suggest" else if (risky) "risky" else "unsure", force = risky)) return
            stats.unchanged++
            if (ev != null) writePerf(r)
            return
        }
        if (!changed) {
            stats.unchanged++
            if (ev != null) writePerf(r)
            // the word stands as typed: it becomes the previous word for the next re-check
            if (pendings.none { it.anchor > pd.anchor }) prev = Prev(r.slot, r.chosen, pd.typed, r.lmScored, pd.typed, pd.touches, if (r.lmScored) r.margin else 0.0, if (r.lmScored) r.scores else null)
            return
        }
        val prefixOld = if (mg != null || prevNew != null) pd.prevOut + pd.prevSep else ""
        val oldCore = prefixOld + pd.typed + pd.sepText
        // text typed since this word (kept): X chars before the cursor, ending with the composing word
        val x = pos - pd.anchor
        if (x < cur.length || x > MAX_AFTER) { stats.dropped++; ev?.let { it["outcome"] = "dropped_stale"; write(it) }; return }
        val xi = x.toInt()
        val live = ed.textBefore(oldCore.length + xi + 1)
        val L = live.length
        if (L < oldCore.length + xi || live.substring(L - xi - oldCore.length, L - xi) != oldCore || ed.hasSelection() || !live.endsWith(cur)) {
            stats.dropped++
            ev?.let { it["outcome"] = "dropped_stale"; write(it) }
            return
        }
        val after = live.substring(L - xi)
        val newPrefix = if (prevNew != null) prevNew + pd.prevSep else ""
        val newCore = newPrefix + out + pd.sepText
        val before = pos
        ed.beginBatch()
        if (cur.isNotEmpty()) ed.finishComposing()
        ed.replaceBefore(oldCore.length + xi, newCore + after.dropLast(cur.length))
        if (cur.isNotEmpty()) ed.setComposing(cur)
        ed.endBatch()
        // later words moved by the length change
        val delta = pos - before
        for (o in pendings) if (o.anchor > pd.anchor) o.anchor += delta
        stats.applied++
        if (out != pd.typed) { recentFixes.addLast(pd.typed to out); while (recentFixes.size > 20) recentFixes.removeFirst() }
        ui.onApplied()
        if (rc != null) ev?.set("recheck", mapOf("from" to rc.from, "to" to rc.to))
        if (prevNew != null && rc?.to != prevNew) ev?.set("punct", mapOf("from" to pd.prevOut, "to" to prevNew))
        if (mg != null) ev?.let { it["merge"] = mapOf("from" to pd.prevOut + pd.prevSep + pd.typed, "to" to mg.to); it["final"] = mg.to }
        val undoPrefix = if (mg != null) pd.prevOut + pd.prevSep else ""
        val newest = pendings.none { it.anchor > pd.anchor }
        if (xi == 0) last = Last(pd.typed, pd.touches, out, pd.sepText, r, ev, undoPrefix) else ev?.let { write(it) }
        prev = if (mg != null || !newest || xi != cur.length) null else Prev(r.slot, r.chosen, out, r.lmScored, pd.typed, pd.touches, if (r.lmScored) r.margin else 0.0, if (r.lmScored) r.scores else null)
        if (xi == 0 && out != pd.typed) {
            // Gboard-like: plain alternatives, no "undo" pill and not the word as typed (backspace right after undoes)
            val ranked = r.ranked(4).filter { it != pd.typed }.take(3)
            setStrip(ranked, ranked.indexOf(out), StripMode.LAST_WORD)
        }
    }

    fun pickSuggestion(i: Int) {
        if (holding) { held.add { pickSuggestion(i) }; return }
        val ed = ed() ?: return
        val pick = shown.getOrNull(i) ?: return
        when (stripMode) {
            StripMode.TYPING -> {
                if (composing.isEmpty()) return
                val typed = composing.toString()
                val tch = ArrayList(touches)
                flushLast()
                ed.commit("$pick ")
                resetWord()
                corr()?.word(pick)
                if (!config.policy.sensitive) {
                    val e = baseEvent("strip_pick", typed, tch)
                    e["candidates"] = shown.map { mapOf("w" to it) }
                    e["chosen"] = pick; e["final"] = pick; e["outcome"] = "strip_pick"; e["while_typing"] = true
                    write(e)
                }
                last = Last(typed, tch, pick, " ", null, null, "")
                prev = null
                setStrip(emptyList(), -1, StripMode.NONE)
                autoShift()
            }
            StripMode.LAST_WORD -> {
                val l = last ?: return
                val tail = l.out + l.sepText
                if (ed.textBefore(tail.length) != tail) return
                ed.beginBatch()
                ed.deleteBefore(tail.length)
                ed.commit(pick + l.sepText)
                ed.endBatch()
                val e = l.event ?: if (!config.policy.sensitive && l.result != null) baseEvent("autocorrect", l.typed, l.touches) else null
                e?.let { it["outcome"] = if (pick == l.typed) "undone" else "strip_pick"; if (pick == l.typed) it["via"] = "pick"; it["final"] = pick; write(it) }
                // a swiped word stays removable with one backspace after a pick from its alternates
                last = if (l.swiped) Last("", emptyList(), pick, l.sepText, null, null, "", l.lead) else null
                if (pick == l.typed && l.result != null && !l.swiped) learnFromUndo(l.undoPrefix + l.typed, l.out, l.event)
                prev?.let { pv -> val k = pv.slot.cands.indexOf(pick); if (k >= 0) { pv.idx = k; pv.out = pick } else prev = null }
                if (l.swiped) { swipeSpace = l.sepText == " "; autoShift() }
                setStrip(emptyList(), -1, StripMode.NONE)
            }
            StripMode.REWIND -> {
                val u = rewindUndo ?: return
                if (pick == u.from) revertRewind(ed) else setStrip(emptyList(), -1, StripMode.NONE)
            }
            StripMode.TYPO -> {
                // an explicit pick: the only way the text changes in suggest-only mode
                cursorFlag?.let { f ->
                    cursorFlag = null; typo = null
                    if (replaceFlag(ed, f, pick)) {
                        f.event?.let { val e = LinkedHashMap(it); e["outcome"] = "strip_pick"; e["final"] = pick; e["ts"] = System.currentTimeMillis(); write(e) }
                    }
                    setStrip(emptyList(), -1, StripMode.NONE)
                    return
                }
                val t = typo ?: return
                typo = null
                if (replaceAt(ed, t.start, t.typed, pick)) {
                    t.event?.let { val e = LinkedHashMap(it); e["outcome"] = "strip_pick"; e["final"] = pick; e["ts"] = System.currentTimeMillis(); write(e) }
                }
                setStrip(emptyList(), -1, StripMode.NONE)
            }
            StripMode.WORD_ALTS -> {
                // swap the tapped word, if it's still there around the cursor
                val tw = tapWord ?: return
                tapWord = null
                val before = ed.textBefore(tw.back)
                val after = ed.textAfter(tw.word.length - tw.back)
                if (before + after == tw.word && !ed.hasSelection()) ed.replaceRange(-tw.back, tw.word.length, pick)
                setStrip(emptyList(), -1, StripMode.NONE)
            }
            StripMode.DICT_ADDED, StripMode.NONE, StripMode.FLAG -> {}
        }
    }

    /** The strip's chip: "not a typo" / "ignore" for a flagged word, "undo" after an automatic dictionary add. */
    fun stripAction() {
        if (holding) { held.add { stripAction() }; return }
        val ed = ed() ?: return
        when (stripMode) {
            StripMode.TYPO -> notTypo(ed)
            StripMode.FLAG -> {
                // an explicit tap on the chip: the only way a flagged word changes
                val f = chipFlag ?: return
                chipFlag = null
                val to = f.fixes.first()
                typo?.let { t -> if (t.typed == f.word) typo = null }
                if (replaceFlag(ed, f, to)) {
                    f.event?.let { val e = LinkedHashMap(it); e["outcome"] = "strip_pick"; e["final"] = to; e["ts"] = System.currentTimeMillis(); write(e) }
                }
                setStrip(emptyList(), -1, StripMode.NONE)
                showFlagChip()
            }
            StripMode.DICT_ADDED -> {
                dictAdded?.let { learning.removeWord(it) }
                dictAdded = null
                setStrip(emptyList(), -1, StripMode.NONE)
            }
            else -> {}
        }
    }

    /** long-press on the side chip: "not a typo" (spelling) / "ignore" for that word */
    fun stripActionLongPress() {
        if (holding) { held.add { stripActionLongPress() }; return }
        val ed = ed() ?: return
        if (stripMode != StripMode.FLAG) return
        cursorFlag = chipFlag ?: return
        chipFlag = null
        notTypo(ed)
        showFlagChip()
    }

    /**
     * Long-press on a strip word: on a flagged word's fixes = "not a typo" / "ignore"; on the literal typed word
     * (while typing, or the undo chip after a correction) = add it to the dictionary.
     */
    fun longPressSuggestion(i: Int) {
        if (holding) { held.add { longPressSuggestion(i) }; return }
        val ed = ed() ?: return
        val w = shown.getOrNull(i) ?: return
        when (stripMode) {
            StripMode.TYPO -> notTypo(ed)
            StripMode.TYPING -> if (w == composing.toString() && learning.addWord(w)) ui.onDictAdded(w)
            StripMode.LAST_WORD -> {
                val l = last ?: return
                if (w != l.typed || !learning.addWord(w)) return
                ui.onDictAdded(w)
                pickSuggestion(i)   // back to the word as typed (a dictionary word now, so no undo learning)
            }
            else -> {}
        }
    }

    /** "not a typo" (spelling flag): squiggle off + dictionary; "ignore" (other flags): squiggle off + suppression */
    private fun notTypo(ed: Editor) {
        cursorFlag?.let { f ->
            cursorFlag = null
            // same text, without the mark (nothing to rewrite where marks never stuck or weren't attached)
            val ok = if (spansDropped) { flags.remove(f); true } else replaceFlag(ed, f, f.word)
            if (ok) {
                // the same word may also be the typing-time flag
                typo?.let { t -> if (t.typed == f.word) typo = null }
                learnNotTypo(f.word, f.spelling, f.fixes, f.leftContext, f.event)
            }
            setStrip(emptyList(), -1, StripMode.NONE)
            return
        }
        val t = typo ?: return
        typo = null
        replaceAt(ed, t.start, t.typed, t.typed)   // same text, without the mark
        flags.removeAll { it.word == t.typed && it.event === t.event }
        learnNotTypo(t.typed, t.spelling, t.fixes, t.leftContext, t.event)
        setStrip(emptyList(), -1, StripMode.NONE)
    }

    private fun learnNotTypo(typed: String, spelling: Boolean, fixes: List<String>, left: String, ev: Map<String, Any?>?) {
        if (spelling) {
            learning.addWord(typed)
            if (!config.policy.sensitive) write(linkedMapOf("v" to 1, "id" to UUID.randomUUID().toString(), "ts" to System.currentTimeMillis(),
                "type" to "not_typo", "typed" to typed, "left_context" to left, "candidates" to fixes.take(3).map { mapOf("w" to it) }, "ref" to ev?.get("id"),
                "style" to config.style, "model" to config.modelId(), "app_version" to config.appVersion))
        } else {
            fixes.firstOrNull()?.let { suppressEdit(typed, it, "ignore", ev) }
        }
    }

    /**
     * The cursor moved (tap / arrows): on or right next to a flagged word, show its fixes in the strip (works in every
     * app, also those that ignore SuggestionSpans).
     */
    fun cursorMoved(selStart: Int, selEnd: Int) {
        if (!config.suggestOnly || flags.isEmpty() || !onFlag(selStart, selEnd)) wordAlternatives(selStart, selEnd)
    }

    /** words changed recently (typed -> out): tapping one offers the word as typed first */
    private val recentFixes = ArrayDeque<Pair<String, String>>()
    private class TapWord(val word: String, val back: Int)
    private var tapWord: TapWord? = null

    /**
     * The cursor was put on (or right next to) a word, typed or corrected: its alternatives go in the strip, a tap swaps
     * them in (like Gboard). Works in every app; nothing changes until the user picks.
     */
    private fun wordAlternatives(selStart: Int, selEnd: Int) {
        fun clear() { tapWord = null; if (stripMode == StripMode.WORD_ALTS) setStrip(emptyList(), -1, StripMode.NONE) }
        if (holding || composing.isNotEmpty() || selStart != selEnd || !config.policy.suggestions || !config.autocorrect) { clear(); return }
        val ed = ed() ?: return
        val before = ed.textBefore(48); val after = ed.textAfter(48)
        val wb = Regex("[A-Za-z']+$").find(before)?.value.orEmpty()
        val wa = Regex("^[A-Za-z']+").find(after)?.value.orEmpty()
        val word = (wb + wa).trim('\'')
        if (word.length < 2 || !WORD_RE.matches(word) || (wb.isEmpty() && wa.isEmpty())) { clear(); return }
        if (tapWord?.word == word && tapWord?.back == wb.length && stripMode == StripMode.WORD_ALTS) return
        val tw = TapWord(word, wb.length)
        tapWord = tw
        val typed = recentFixes.lastOrNull { it.second == word }?.first
        engine.suggest(word, dev.mostlime.autocorrect.engine.KeyGeom.keyTouches(word)) { s ->
            if (tapWord !== tw || composing.isNotEmpty()) return@suggest
            val list = (listOfNotNull(typed) + s).filter { it != word }.distinct().take(3)
            if (list.isNotEmpty()) setStrip(list, 0, StripMode.WORD_ALTS)
        }
    }

    /** suggest-only: the cursor on a flagged word -> its fixes (true = handled) */
    private fun onFlag(selStart: Int, selEnd: Int): Boolean {
        if (holding || composing.isNotEmpty() || selStart != selEnd || selStart < 0 || flags.isEmpty()) {
            if (cursorFlag != null && selStart != selEnd) { cursorFlag = null; if (stripMode == StripMode.TYPO) setStrip(emptyList(), -1, StripMode.NONE) }
            return false
        }
        val ed = ed() ?: return false
        val f = flags.lastOrNull { selStart >= it.start && selStart <= it.start + it.word.length }
        if (f == null || !locate(ed, f, ed.cursorPos().takeIf { it >= 0 } ?: selStart)) {
            if (cursorFlag != null) { cursorFlag = null; if (stripMode == StripMode.TYPO) setStrip(emptyList(), -1, StripMode.NONE) }
            return false
        }
        cursorFlag = f
        setStrip(f.fixes, 0, StripMode.TYPO, hint = "typo?", action = "not a typo")
        return true
    }

    /** is f.word still at f.start (cursor at cur)? else look for it nearby and move the flag; false = gone */
    private fun locate(ed: Editor, f: Flag, cur: Int): Boolean {
        val back = (cur - f.start + LOCATE_SLACK).coerceAtLeast(0)
        val fwd = (f.start + f.word.length - cur + LOCATE_SLACK).coerceAtLeast(0)
        val win = ed.textBefore(back) + ed.textAfter(fwd)
        val base = cur - minOf(back, ed.textBefore(back).length)
        var best = -1
        var i = win.indexOf(f.word)
        while (i >= 0) {
            val abs = base + i
            val wordStart = i == 0 || !win[i - 1].isLetterOrDigit()
            val wordEnd = i + f.word.length >= win.length || !win[i + f.word.length].isLetterOrDigit()
            if (wordStart && wordEnd && (best < 0 || kotlin.math.abs(abs - f.start) < kotlin.math.abs(best - f.start))) best = abs
            i = win.indexOf(f.word, i + 1)
        }
        if (best < 0) { flags.remove(f); return false }
        f.start = best
        return true
    }

    private fun replaceFlag(ed: Editor, f: Flag, text: String): Boolean {
        val cur = ed.cursorPos()
        if (cur < 0 || !locate(ed, f, cur)) return false
        ed.replaceRange(f.start - cur, f.word.length, text)
        flags.remove(f)
        val d = text.length - f.word.length
        if (d != 0) for (o in flags) if (o.start > f.start) o.start += d
        prev = null; last = null
        return true
    }

    /**
     * Mark a finished word as a likely typo instead of correcting it: chosen != typed and the fix beats the typed word
     * by FLAG_MARGIN. The text stays exactly as typed; the flag goes in the side chip now and gets its underline after a
     * pause (markIdle). -> false if nothing was flagged.
     */
    private fun flag(pd: Pending, r: WordResult, ev: MutableMap<String, Any?>?, why: String, force: Boolean = false): Boolean {
        val ti = r.slot.typedIdx
        val gap = if (ti < 0) Double.POSITIVE_INFINITY else r.scores[r.chosen] - r.scores[ti]
        val fixes = r.ranked(5).filter { it.lowercase() != pd.typed.lowercase() }.take(3)
        if ((!config.flagTypos && !force) || r.out == pd.typed || gap < FLAG_MARGIN || fixes.isEmpty() || learning.isUserWord(pd.typed)) return false
        val ed = ed() ?: return false
        val cur = composing.toString()
        val core = pd.typed + pd.sepText
        val x = pos - pd.anchor
        if (x < cur.length || x > MAX_AFTER) return false
        val xi = x.toInt()
        val live = ed.textBefore(core.length + xi + 1)
        val L = live.length
        if (L < core.length + xi || live.substring(L - xi - core.length, L - xi) != core || ed.hasSelection() || !live.endsWith(cur)) return false
        // nothing is written now: the underline is attached after a pause in typing (markIdle), never mid-typing
        val spelling = isSpellingEdit(pd.typed, r.out)
        ev?.let { it["outcome"] = "flagged"; it["final"] = pd.typed; it["flag"] = why; it["flag_kind"] = if (spelling) "spelling" else "other"; write(it) }
        typo = Typo(pd.anchor - core.length, pd.typed, fixes, spelling, ev, pd.before.takeLast(LOG_CONTEXT_CHARS))
        val cp = ed.cursorPos()
        if (cp >= 0) addFlag(Flag(cp - (pos - typo!!.start).toInt(), pd.typed, fixes, spelling, ev, pd.before.takeLast(LOG_CONTEXT_CHARS)))
        prev = null
        showTypo()
        return true
    }

    /**
     * The flagged-word chip: one small "teh → the" chip at the side of an otherwise empty strip (never in place of the
     * word's candidates or anything else), for the latest flagged word behind the cursor. Tap = fix it in place,
     * long-press = not a typo. Works in every app, also those that drop SuggestionSpans.
     */
    private fun showTypo() = showFlagChip()

    private fun showFlagChip() {
        if (!config.suggestOnly) return   // auto-correct mode: no chips, unsure words just get their underline
        if (!config.fixPill) return       // turned off (the system spell checker's underlines cover it)
        if (composing.isNotEmpty() || flags.isEmpty() || (stripMode != StripMode.NONE && stripMode != StripMode.FLAG)) return
        val ed = ed() ?: return
        val cur = ed.cursorPos()
        if (cur < 0) return
        fun short(w: String) = if (w.length > 14) w.take(13) + "…" else w
        // only the word just finished or up to 2 words back, and only if it's still there (edited words drop out)
        val f = flags.filter { it.start <= cur }.sortedByDescending { it.start }
            .firstOrNull { recent(ed, it, cur) && locate(ed, it, cur) } ?: run { if (stripMode == StripMode.FLAG) setStrip(emptyList(), -1, StripMode.NONE); return }
        chipFlag = f
        setStrip(emptyList(), -1, StripMode.FLAG, action = "${short(f.word)} → ${short(f.fixes.first())}")
    }

    /** the flagged word is the last word before the cursor or at most CHIP_WORDS_BACK words back */
    private fun recent(ed: Editor, f: Flag, cur: Int): Boolean {
        val back = cur - f.start
        if (back < f.word.length || back > MAX_AFTER + f.word.length) return false
        val since = ed.textBefore(back).drop(f.word.length)
        return SEG.findAll(since).count() <= CHIP_WORDS_BACK
    }



    /**
     * A spelling fix changes one word that isn't in the vocabulary into a different word. Capitals-only, punctuation or
     * apostrophe-only changes, confusable swaps between two real words and multi-word edits are not spelling.
     */
    fun isSpellingEdit(typed: String, out: String): Boolean {
        if (' ' in typed || ' ' in out) return false
        val a = typed.lowercase(); val b = out.lowercase()
        if (a == b) return false
        if (a.filter { it.isLetterOrDigit() } == b.filter { it.isLetterOrDigit() }) return false
        return !learning.inVocab(typed)
    }

    /**
     * An auto edit was undone. Spelling: count it; the second undo of the same typed word adds it to the dictionary.
     * Anything else (grammar guard): never the dictionary, only a suppression of this edit for a while.
     */
    private fun learnFromUndo(typed: String, out: String, ev: Map<String, Any?>?, gap: Double = 0.0) {
        if (learning.isUserWord(typed) || typed == out) return
        if (isSpellingEdit(typed, out)) {
            // looks like a typo of a common word that the model was sure about: needs undos in two sessions
            val typoLike = gap >= STRONG_GAP && learning.nearCommonWord(typed)
            if (learning.shouldLearn(typed, typoLike) && learning.addLearned(typed)) {
                dictAdded = typed
                ui.onDictAdded(typed)
                // a toast says so; the word can be removed in settings (no extra pill in the strip)
            }
        } else suppressEdit(typed, out, "undo", ev)
    }

    /** how much the model preferred its fix over the word as typed (score units; big = sure) */
    private fun gapOf(r: WordResult?): Double {
        r ?: return 0.0
        val ti = r.slot.typedIdx
        return if (ti < 0) Double.POSITIVE_INFINITY else r.scores[r.chosen] - r.scores[ti]
    }

    private fun suppressEdit(typed: String, chosen: String, source: String, ev: Map<String, Any?>?) {
        learning.suppress(typed, chosen)
        if (!config.policy.sensitive) write(linkedMapOf("v" to 1, "id" to UUID.randomUUID().toString(), "ts" to System.currentTimeMillis(),
            "type" to "suppress", "typed" to typed, "chosen" to chosen, "source" to source,
            "left_context" to (ev?.get("left_context") as? String), "ref" to ev?.get("id"), "style" to config.style, "model" to config.modelId(), "app_version" to config.appVersion))
    }

    /** Replace a word that ends `pos - (start + old.length)` chars before the cursor, if the text there is unchanged. */
    private fun replaceAt(ed: Editor, start: Long, old: String, new: String, mark: TypoMark? = null): Boolean {
        val cur = composing.toString()
        val x = pos - (start + old.length)
        if (x < cur.length || x > MAX_AFTER) return false
        val xi = x.toInt()
        val live = ed.textBefore(old.length + xi + 1)
        val L = live.length
        if (L < old.length + xi || live.substring(L - xi - old.length, L - xi) != old || ed.hasSelection() || !live.endsWith(cur)) return false
        val after = live.substring(L - xi)
        val before = pos
        ed.beginBatch()
        if (cur.isNotEmpty()) ed.finishComposing()
        ed.replaceBefore(old.length + xi, new + after.dropLast(cur.length), mark)
        if (cur.isNotEmpty()) ed.setComposing(cur)
        ed.endBatch()
        val delta = pos - before
        for (o in pendings) if (o.anchor > start) o.anchor += delta
        if (delta != 0L) { prev = null; last = null }
        return true
    }

    /** Standalone i / i'm / i'll / i'd / i've -> I, I'm, ... (every style except casual, which keeps text as typed). */
    private fun capI(text: String): String {
        if (config.style == "casual" || config.suggestOnly) return text
        return text.split(' ').joinToString(" ") { w -> if (w in I_WORDS && learning.penalty(w, "I" + w.substring(1)) == 0.0) "I" + w.substring(1) else w }
    }

    /** Paste / emoji: the word being typed stays as typed, then the text goes in at the cursor. */
    fun insertText(t: String) {
        if (holding) { held.add { insertText(t) }; return }
        val ed = ed() ?: return
        swipeSpace = false
        flushLast()
        cancelSuggest()
        if (composing.isNotEmpty()) { ed.commit(composing.toString()); resetWord() }
        ed.commit(t)
        corr()?.breakChain()   // pasted / inserted text isn't a typed word: nothing after it is "the next word"
        prev = null
        setStrip(emptyList(), -1, StripMode.NONE)
        autoShift()
    }

    /**
     * Long-press popup choice (é for e, 1 for q, ? for .), typed in place of the pressed key, which never went in:
     * one character is typed like that key would be, a longer entry goes in as text.
     */
    fun alternate(base: KeyDef, alt: String, touch: Touch) {
        if (alt.isEmpty()) return
        if (alt.codePointCount(0, alt.length) > 1) { insertText(alt); return }
        key(KeyDef(alt, alt, KeyType.CHAR, base.x, base.w, base.row), touch, base)
    }

    // ------------------------------------------------------------------ swipe typing
    /**
     * The view decided the current touch is a swipe. Its first key never typed (character keys wait for the gesture to
     * be decided), so there is nothing to take back: remember the shift state for the swiped word's case.
     */
    fun swipeBegin() {
        if (holding) { held.add { swipeBegin() }; return }
        swipeShift = shift
    }

    /**
     * A finished swipe (path in model units). A word being typed is finished first (normal autocorrect); the swiped word
     * goes in with an auto space before it (unless the text already ends in a space / opening bracket) and a space after
     * it. Keys typed while it decodes are held and replayed after it, like the fast hold at word end.
     */
    fun swipe(path: List<Touch>) {
        idleTouch()
        if (holding) { held.add { swipe(path) }; return }
        val ed = ed() ?: return
        swipeSpace = false
        flushLast()
        cancelSuggest()
        if (composing.isNotEmpty()) {
            finishWord(ed, " ", trigger = true)
            if (holding) { held.add { swipe(path) }; return }
        }
        val raw = ed.textBefore(CTX_CHARS)
        val lead = if (raw.isNotEmpty() && !raw.last().isWhitespace() && raw.last() !in NO_SPACE_AFTER) " " else ""
        val (left, sep) = splitLeft(raw + lead)
        val sh = swipeShift ?: shift
        swipeShift = null
        val case = when (sh) { Shift.LOCK -> "AA"; Shift.ON -> "A"; Shift.OFF -> "" }
        val id = nextSwipeId++
        swipePending = id
        setStrip(emptyList(), -1, StripMode.NONE)
        holding = true
        holdDone = { swipePending == 0 }
        holdToken = timer.post(SWIPE_HOLD_MS) { if (swipePending == id) swipePending = 0; releaseHold() }
        engine.swipe(SwipeRequest(path, left, sep, case)) { r -> onSwipe(id, lead, r) }
    }

    private fun onSwipe(id: Int, lead: String, r: SwipeResult?) {
        if (id != swipePending) return    // timed out (its hold was released) or superseded
        swipePending = 0
        try {
            val word = r?.best ?: return
            val ed = ed() ?: return
            if (ed.hasSelection()) return
            val out = capI(word)
            ed.commit(lead + out + " ")
            corr()?.word(out)
            val geo = r.geoBest?.let(::capI)
            val strip = r.ranked(3).map(::capI).distinct().toMutableList()
            // the geometry-only word, when the LM overruled it, is the undo chip (like the word as typed after a correction)
            var undo = -1
            if (geo != null && geo != out) {
                if (geo !in strip) { if (strip.size >= 3) strip[2] = geo else strip.add(geo) }
                undo = strip.indexOf(geo)
            }
            last = Last("", emptyList(), out, " ", null, null, "", lead)
            prev = null
            swipeSpace = true
            autoShift()
            setStrip(strip, strip.indexOf(out), StripMode.LAST_WORD, undo = undo)
        } finally {
            releaseHold()
        }
    }

    // ------------------------------------------------------------------ strip while typing (cheap path, debounced)
    private fun scheduleSuggest() {
        cancelSuggest()
        if (!config.policy.suggestions || !config.autocorrect) return
        val typed = composing.toString()
        if (!WORD_RE.matches(typed)) return
        val tch = ArrayList(touches)
        val seq = ++suggestSeq
        suggestToken = timer.post(SUGGEST_DEBOUNCE_MS) {
            engine.suggest(typed, tch) { s -> if (seq == suggestSeq && composing.toString() == typed) setStrip(s, 0, StripMode.TYPING) }
        }
    }

    private fun cancelSuggest() {
        suggestSeq++
        suggestToken?.let { timer.cancel(it) }
        suggestToken = null
    }

    /** the strip's side chip label (tests / accessibility) */
    var shownAction: String? = null
        private set

    private fun setStrip(s: List<String>, bold: Int, mode: StripMode, undo: Int = -1, hint: String = "", action: String? = null) {
        shown = s; stripMode = mode; shownAction = action
        ui.showStrip(s, bold, undo, hint, action)
    }

    // ------------------------------------------------------------------ helpers
    private fun resetWord() {
        composing.setLength(0); touches.clear(); keepTypedOnce = false
    }

    fun autoShift() {
        if (shift == Shift.LOCK || !lettersLayer) return
        val caps = config.autoCap && config.policy.anyCaps && (editor()?.capsMode() ?: false)
        shift = if (caps) Shift.ON else Shift.OFF
    }

    private fun flushLast() {
        last?.event?.let { write(it) }
        last = null
    }

    private fun write(e: Map<String, Any?>) {
        if (config.policy.sensitive) return
        events(e)
        corr()?.onEvent(e)
    }

    /** the upload log, unless this field is sensitive (password / email / URL / number / incognito ...) or in an excluded app */
    private fun corr(): CorrectionLog? = corrections?.takeIf { !config.policy.sensitive && !config.policy.noUpload }

    private fun baseEvent(type: String, typed: String, tch: List<Touch>): MutableMap<String, Any?> = linkedMapOf(
        "v" to 1, "id" to UUID.randomUUID().toString(), "ts" to System.currentTimeMillis(), "type" to type,
        "typed" to typed, "touches" to tch.map { if (it.isApos) null else listOf(r3(it.x), r3(it.y)) },
        "style" to config.style, "model" to config.modelId(), "app_version" to config.appVersion,
    )

    private fun buildEvent(pd: Pending, r: WordResult): MutableMap<String, Any?> {
        val e = baseEvent("autocorrect", pd.typed, pd.touches)
        e["left_context"] = pd.before.takeLast(LOG_CONTEXT_CHARS)
        e["sep"] = pd.sep
        val order = r.slot.cands.indices.sortedByDescending { r.scores[it] }.take(LOG_CANDS)
        e["candidates"] = order.map { i ->
            mapOf("w" to r.slot.cands[i], "kind" to r.slot.kind(i), "sp" to r3(r.slot.sp[i]), "lm" to r.lm?.get(i)?.let(::r3), "score" to r3(r.scores[i]))
        }
        e["chosen"] = r.out; e["final"] = r.out; e["outcome"] = "accepted"
        e["lm_ms"] = r3(r.lmMs); e["n_scored"] = r.nScored; e["n_cands"] = r.nCands
        e["skipped"] = r.skipped; e["cheap"] = r.cheap; e["ctx_tokens"] = r.ctxTokens
        return e
    }

    /** No text: timing only, for words that weren't changed. */
    private fun writePerf(r: WordResult) {
        write(linkedMapOf("v" to 1, "ts" to System.currentTimeMillis(), "type" to "perf", "model" to config.modelId(),
            "app_version" to config.appVersion,
            "lm_ms" to r3(r.lmMs), "n_scored" to r.nScored, "n_cands" to r.nCands, "skipped" to r.skipped, "cheap" to r.cheap,
            "ctx_tokens" to r.ctxTokens))
    }

    companion object {
        const val CTX_CHARS = 400
        /** text before a word kept in a local event (also what sentence context is cut from: 24 words fit) */
        const val LOG_CONTEXT_CHARS = 240
        const val LOG_CANDS = 8
        const val ENTER_HOLD_MS = 400L
        const val FAST_HOLD_MS = 60L
        const val MAX_PENDING = 6
        const val MAX_AFTER = 120L
        const val SUGGEST_DEBOUNCE_MS = 60L
        const val DOUBLE_TAP_MS = 350L
        /** a swipe whose decode takes longer than this is dropped and held keys go through */
        const val SWIPE_HOLD_MS = 1500L
        /** punctuation that takes the swiped word's auto space: "word ." -> "word. " */
        const val SWIPE_PUNCT = ".,!?;:"
        /** no auto space before a swiped word after these */
        const val NO_SPACE_AFTER = "([{/@#\"'-_\n"
        val WORD_RE = Regex("[A-Za-z]+(?:'[A-Za-z]+)*'?")
        const val WORD_END_PUNCT = ".,!?;:)\""
        val I_WORDS = setOf("i", "i'm", "i'll", "i'd", "i've")
        /** idle passes start after this pause in typing */
        /** idle passes (marks, sentence re-check, far rewind) only after this long without typing */
        /** marks (underlines) only after this long without typing */
        const val MARK_MS = 1500L
        /** default pause before the idle re-check */
        const val IDLE_PAUSE_MS = 400L
        /** "whole field" scope: everything readable (sentence-cached) */
        const val FIELD_CHARS = 200_000
        const val IDLE_CHARS = 2000
        const val IDLE_LEFT = 200
        const val IDLE_SENTENCES = 6
        const val MAX_FLAGS = 64
        /** the flag chip is for the word just finished or at most this many words back */
        const val CHIP_WORDS_BACK = 2
        /** how far a flag is looked for when its offset went stale */
        const val LOCATE_SLACK = 48
        /** a flagged typo: the fix must beat the typed word by this much (score units, nats) */
        const val FLAG_MARGIN = 1.5
        /** "only auto-correct when confident": the choice must beat every other candidate by this much (WordResult.margin) */
        const val CONFIDENT_MARGIN = 3.0
        const val UNDOS_TO_ADD = 2
        /** a short word needing 2+ letters changed is applied only above this gap over the word as typed */
        const val RISKY_APPLY_GAP = 9.0
        /** the model "strongly prefers" its fix above this gap (score units) */
        const val STRONG_GAP = 4.0

        fun r3(x: Double) = Math.round(x * 1000.0) / 1000.0

        /** edit distance where swapping two neighbouring letters counts as one edit (optimal string alignment) */
        fun osaDistance(a: String, b: String): Int {
            val d = Array(a.length + 1) { IntArray(b.length + 1) }
            for (i in 0..a.length) d[i][0] = i
            for (j in 0..b.length) d[0][j] = j
            for (i in 1..a.length) for (j in 1..b.length) {
                val c = if (a[i - 1] == b[j - 1]) 0 else 1
                d[i][j] = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + c)
                if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) d[i][j] = minOf(d[i][j], d[i - 2][j - 2] + 1)
            }
            return d[a.length][b.length]
        }

        /** -> (left context = text up to the end of the previous word, separator before the current word) */
        fun splitLeft(before: String): Pair<String, String> {
            val m = SEG.findAll(before).lastOrNull() ?: return "" to before
            return before.substring(0, m.range.last + 1) to before.substring(m.range.last + 1)
        }
    }
}
