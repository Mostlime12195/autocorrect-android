package dev.mostlime.autocorrect.ime

import dev.mostlime.autocorrect.engine.MergeInput
import dev.mostlime.autocorrect.engine.PrevWord
import dev.mostlime.autocorrect.engine.Rewind
import dev.mostlime.autocorrect.engine.RewindChange
import dev.mostlime.autocorrect.engine.RewindTarget
import dev.mostlime.autocorrect.engine.SEG
import dev.mostlime.autocorrect.engine.Slot
import dev.mostlime.autocorrect.engine.Touch
import dev.mostlime.autocorrect.engine.WordInput
import dev.mostlime.autocorrect.engine.WordResult
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
    /** confusion-set rewind (needs the model; done(empty) without one) */
    fun rewind(targets: List<RewindTarget>, done: (List<RewindChange>) -> Unit) = done(emptyList())
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
}

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

    private class Last(
        val typed: String, val touches: List<Touch>, val out: String, val sepText: String, val result: WordResult?,
        var event: MutableMap<String, Any?>?, val undoPrefix: String,
    )
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

    enum class StripMode { NONE, TYPING, LAST_WORD, REWIND, TYPO, DICT_ADDED }

    /** the most recent word marked as a likely typo (start = its position in the counter) */
    private class Typo(val start: Long, val typed: String, val fixes: List<String>, val spelling: Boolean,
                       val event: MutableMap<String, Any?>?, val leftContext: String)
    private var typo: Typo? = null
    /** word just added to the dictionary by repeated undos (strip offers to take it back) */
    private var dictAdded: String? = null

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

    var stats = Stats()
    class Stats { var applied = 0; var dropped = 0; var unchanged = 0 }

    // ------------------------------------------------------------------ input lifecycle
    fun startInput(cfg: ControllerConfig) {
        flushLast()
        config = cfg
        resetWord(); last = null; prev = null; pendings.clear(); compLen = 0; rewound.clear(); rewindUndo = null; deferred.clear()
        typo = null; dictAdded = null
        releaseHold()
        shift = Shift.OFF
        autoShift()
        setStrip(emptyList(), -1, StripMode.NONE)
    }

    fun finishInput() {
        flushLast()
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
            resetWord(); last = null; prev = null; typo = null
            setStrip(emptyList(), -1, StripMode.NONE)
        }
    }

    // ------------------------------------------------------------------ keys
    fun key(k: KeyDef, touch: Touch) {
        if (holding) { held.add { key(k, touch) }; return }
        val ed = ed() ?: return
        when (k.type) {
            KeyType.CHAR -> {
                var c = k.text
                if (shift != Shift.OFF) c = c.uppercase()
                val ch = c[0]
                val letter = ch in 'a'..'z' || ch in 'A'..'Z' || (Character.isLetter(ch) && composing.isNotEmpty())
                if (letter || (ch == '\'' && composing.isNotEmpty())) {
                    flushLast()
                    if (stripMode != StripMode.NONE && stripMode != StripMode.TYPING) setStrip(emptyList(), -1, StripMode.NONE)
                    composing.append(c); touches.add(touch)
                    ed.setComposing(composing.toString())
                    // ALL-CAPS fields (CAP_CHARACTERS) keep the shift on after each letter
                    if (shift == Shift.ON && !(config.autoCap && config.policy.capChars)) shift = Shift.OFF
                    scheduleSuggest()
                } else if (composing.isNotEmpty()) {
                    finishWord(ed, c, trigger = ch in WORD_END_PUNCT)
                } else {
                    flushLast()
                    ed.commit(c)
                    autoShift()
                }
            }
            KeyType.SPACE -> if (composing.isNotEmpty()) finishWord(ed, " ", trigger = true) else { flushLast(); ed.commit(" "); autoShift() }
            KeyType.ENTER -> {
                if (composing.isNotEmpty()) finishWord(ed, "", trigger = true)
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
            KeyType.TO_SYMBOLS, KeyType.TO_SYMBOLS2, KeyType.TO_LETTERS -> {}
        }
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
            if (composing.isEmpty()) { ed.commit(""); setStrip(emptyList(), -1, StripMode.NONE) } else { ed.setComposing(composing.toString()); scheduleSuggest() }
            return
        }
        val l = last
        if (l != null) {
            val tail = l.out + l.sepText
            if (ed.textBefore(tail.length) == tail && !ed.hasSelection()) {
                // undo: back to exactly what was typed, kept as typed at the next word end
                ed.beginBatch()
                ed.deleteBefore(tail.length)
                if (l.undoPrefix.isNotEmpty()) ed.commit(l.undoPrefix)
                composing.append(l.typed); touches.addAll(l.touches)
                ed.setComposing(composing.toString())
                ed.endBatch()
                keepTypedOnce = true
                l.event?.let { it["outcome"] = "undone"; it["final"] = l.typed; write(it) }
                last = null; prev = null
                setStrip(emptyList(), -1, StripMode.NONE)
                learnFromUndo(l.undoPrefix + l.typed, l.out, l.event)
                return
            }
        }
        flushLast()
        ed.deleteKey()
        setStrip(emptyList(), -1, StripMode.NONE)
        autoShift()
    }

    // ------------------------------------------------------------------ word end
    private fun finishWord(ed: Editor, sepText: String, trigger: Boolean) {
        flushLast()
        cancelSuggest()
        val typed = composing.toString()
        val tch = ArrayList(touches)
        val cfg = config
        val canCorrect = trigger && cfg.policy.autocorrect && cfg.autocorrect && WORD_RE.matches(typed) && !keepTypedOnce
        // the text before the word, from the live editor (the composing word is the last thing before the cursor)
        val raw = if (canCorrect) ed.textBefore(CTX_CHARS + typed.length) else ""
        val before = if (raw.endsWith(typed)) raw.dropLast(typed.length) else raw
        if (!canCorrect || !raw.endsWith(typed)) {
            ed.commit(typed + sepText)
            resetWord()
            prev = null; autoShift(); setStrip(emptyList(), -1, StripMode.NONE); showTypo(); return
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
        if (deferred.isEmpty() || pendings.any { it.uncommitted }) return
        val todo = ArrayList(deferred); deferred.clear()
        for ((p, r) in todo) onCorrection(p, r)
    }

    /** results that arrived while a newer word was held (its text is still composing): applied once it's committed */
    private val deferred = ArrayList<Pair<Pending, WordResult?>>()

    private fun onCorrection(pd: Pending, r: WordResult?) {
        if (pd.done) return
        if (!pd.uncommitted && pendings.any { it.uncommitted }) { deferred.add(pd to r); return }
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
        if (changes.isEmpty() || holding || pendings.isNotEmpty()) return
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
        if (cur.isEmpty()) setStrip(listOf(c.to, c.from), 0, StripMode.REWIND, undo = 1)
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
        if (config.suggestOnly || unsure) {
            if (flag(pd, r, ev, if (config.suggestOnly) "suggest" else "unsure")) return
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
        ui.onApplied()
        if (rc != null) ev?.set("recheck", mapOf("from" to rc.from, "to" to rc.to))
        if (prevNew != null && rc?.to != prevNew) ev?.set("punct", mapOf("from" to pd.prevOut, "to" to prevNew))
        if (mg != null) ev?.let { it["merge"] = mapOf("from" to pd.prevOut + pd.prevSep + pd.typed, "to" to mg.to); it["final"] = mg.to }
        val undoPrefix = if (mg != null) pd.prevOut + pd.prevSep else ""
        val newest = pendings.none { it.anchor > pd.anchor }
        if (xi == 0) last = Last(pd.typed, pd.touches, out, pd.sepText, r, ev, undoPrefix) else ev?.let { write(it) }
        prev = if (mg != null || !newest || xi != cur.length) null else Prev(r.slot, r.chosen, out, r.lmScored, pd.typed, pd.touches, if (r.lmScored) r.margin else 0.0, if (r.lmScored) r.scores else null)
        if (xi == 0 && out != pd.typed) {
            val ranked = r.ranked(3).toMutableList()
            if (pd.typed !in ranked) { if (ranked.size >= 3) ranked[2] = pd.typed else ranked.add(pd.typed) }
            setStrip(ranked, ranked.indexOf(out), StripMode.LAST_WORD, undo = ranked.indexOf(pd.typed))
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
                e?.let { it["outcome"] = if (pick == l.typed) "undone" else "strip_pick"; it["final"] = pick; write(it) }
                last = null
                if (pick == l.typed && l.result != null) learnFromUndo(l.undoPrefix + l.typed, l.out, l.event)
                prev?.let { pv -> val k = pv.slot.cands.indexOf(pick); if (k >= 0) { pv.idx = k; pv.out = pick } else prev = null }
                setStrip(emptyList(), -1, StripMode.NONE)
            }
            StripMode.REWIND -> {
                val u = rewindUndo ?: return
                if (pick == u.from) revertRewind(ed) else setStrip(emptyList(), -1, StripMode.NONE)
            }
            StripMode.TYPO -> {
                // an explicit pick: the only way the text changes in suggest-only mode
                val t = typo ?: return
                typo = null
                if (replaceAt(ed, t.start, t.typed, pick)) {
                    t.event?.let { val e = LinkedHashMap(it); e["outcome"] = "strip_pick"; e["final"] = pick; e["ts"] = System.currentTimeMillis(); write(e) }
                }
                setStrip(emptyList(), -1, StripMode.NONE)
            }
            StripMode.DICT_ADDED, StripMode.NONE -> {}
        }
    }

    /** The strip's chip: "not a typo" / "ignore" for a flagged word, "undo" after an automatic dictionary add. */
    fun stripAction() {
        if (holding) { held.add { stripAction() }; return }
        val ed = ed() ?: return
        when (stripMode) {
            StripMode.TYPO -> notTypo(ed)
            StripMode.DICT_ADDED -> {
                dictAdded?.let { learning.removeWord(it) }
                dictAdded = null
                setStrip(emptyList(), -1, StripMode.NONE)
            }
            else -> {}
        }
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
        val t = typo ?: return
        typo = null
        replaceAt(ed, t.start, t.typed, t.typed)   // same text, without the mark
        if (t.spelling) {
            learning.addWord(t.typed)
            if (!config.policy.sensitive) write(linkedMapOf("v" to 1, "id" to UUID.randomUUID().toString(), "ts" to System.currentTimeMillis(),
                "type" to "not_typo", "typed" to t.typed, "left_context" to t.leftContext, "candidates" to t.fixes.take(3).map { mapOf("w" to it) },
                "style" to config.style, "model" to config.modelId(), "app_version" to config.appVersion))
        } else {
            t.fixes.firstOrNull()?.let { suppressEdit(t.typed, it, "ignore", t.event) }
        }
        setStrip(emptyList(), -1, StripMode.NONE)
    }

    /**
     * Mark a finished word as a likely typo instead of correcting it: chosen != typed and the fix beats the typed word
     * by FLAG_MARGIN. The text stays exactly as typed (only a SuggestionSpan is added). -> false if nothing was marked.
     */
    private fun flag(pd: Pending, r: WordResult, ev: MutableMap<String, Any?>?, why: String): Boolean {
        val ti = r.slot.typedIdx
        val gap = if (ti < 0) Double.POSITIVE_INFINITY else r.scores[r.chosen] - r.scores[ti]
        val fixes = r.ranked(5).filter { it.lowercase() != pd.typed.lowercase() }.take(3)
        if (r.out == pd.typed || gap < FLAG_MARGIN || fixes.isEmpty() || learning.isUserWord(pd.typed)) return false
        val ed = ed() ?: return false
        val cur = composing.toString()
        val core = pd.typed + pd.sepText
        val x = pos - pd.anchor
        if (x < cur.length || x > MAX_AFTER) return false
        val xi = x.toInt()
        val live = ed.textBefore(core.length + xi + 1)
        val L = live.length
        if (L < core.length + xi || live.substring(L - xi - core.length, L - xi) != core || ed.hasSelection() || !live.endsWith(cur)) return false
        val after = live.substring(L - xi)
        ed.beginBatch()
        if (cur.isNotEmpty()) ed.finishComposing()
        ed.replaceBefore(core.length + xi, core + after.dropLast(cur.length), TypoMark(0, pd.typed.length, fixes))
        if (cur.isNotEmpty()) ed.setComposing(cur)
        ed.endBatch()
        val spelling = isSpellingEdit(pd.typed, r.out)
        ev?.let { it["outcome"] = "flagged"; it["final"] = pd.typed; it["flag"] = why; it["flag_kind"] = if (spelling) "spelling" else "other"; write(it) }
        typo = Typo(pd.anchor - core.length, pd.typed, fixes, spelling, ev, pd.before.takeLast(LOG_CONTEXT_CHARS))
        prev = null
        showTypo()
        return true
    }

    /** strip for the most recent flagged word, while it's still close to the cursor */
    private fun showTypo() {
        val t = typo ?: return
        if (composing.isNotEmpty()) return
        if (pos - (t.start + t.typed.length) > MAX_AFTER) { typo = null; return }
        setStrip(t.fixes, 0, StripMode.TYPO, hint = "typo?", action = if (t.spelling) "not a typo" else "ignore")
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
    private fun learnFromUndo(typed: String, out: String, ev: Map<String, Any?>?) {
        if (learning.isUserWord(typed) || typed == out) return
        if (isSpellingEdit(typed, out)) {
            if (learning.noteSpellingUndo(typed) >= UNDOS_TO_ADD && learning.addWord(typed)) {
                dictAdded = typed
                ui.onDictAdded(typed)
                setStrip(listOf(typed), -1, StripMode.DICT_ADDED, hint = "added to dictionary", action = "undo")
            }
        } else suppressEdit(typed, out, "undo", ev)
    }

    private fun suppressEdit(typed: String, chosen: String, source: String, ev: Map<String, Any?>?) {
        learning.suppress(typed, chosen)
        if (!config.policy.sensitive) write(linkedMapOf("v" to 1, "id" to UUID.randomUUID().toString(), "ts" to System.currentTimeMillis(),
            "type" to "suppress", "typed" to typed, "chosen" to chosen, "source" to source,
            "left_context" to (ev?.get("left_context") as? String), "style" to config.style, "model" to config.modelId(), "app_version" to config.appVersion))
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
        flushLast()
        cancelSuggest()
        if (composing.isNotEmpty()) { ed.commit(composing.toString()); resetWord() }
        ed.commit(t)
        prev = null
        setStrip(emptyList(), -1, StripMode.NONE)
        autoShift()
    }

    /** Long-press alternate: replaces the character the press already typed (é for e, 1 for q, ? for .). */
    fun replaceLastChar(alt0: String) {
        if (holding) { held.add { replaceLastChar(alt0) }; return }
        val ed = ed() ?: return
        if (composing.isNotEmpty()) {
            val alt = if (composing.last().isUpperCase()) alt0.uppercase() else alt0
            composing.setLength(composing.length - 1); composing.append(alt)
            ed.setComposing(composing.toString())
            scheduleSuggest()
            return
        }
        // the character ended a word: swap the separator of that word's pending correction too
        val pd = pendings.lastOrNull()
        if (pd != null && pd.uncommitted && pd.sepText.length == 1) { pd.sepText = alt0; return }
        if (ed.textBefore(1).isEmpty()) return
        ed.beginBatch(); ed.deleteBefore(1); ed.commit(alt0); ed.endBatch()
        if (pd != null && pd.valid && pd.anchor == pos && pd.sepText.length == 1) pd.sepText = alt0
        autoShift()
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

    private fun setStrip(s: List<String>, bold: Int, mode: StripMode, undo: Int = -1, hint: String = "", action: String? = null) {
        shown = s; stripMode = mode
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
    }

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
        const val LOG_CONTEXT_CHARS = 120
        const val LOG_CANDS = 8
        const val ENTER_HOLD_MS = 400L
        const val FAST_HOLD_MS = 60L
        const val MAX_PENDING = 6
        const val MAX_AFTER = 120L
        const val SUGGEST_DEBOUNCE_MS = 60L
        const val DOUBLE_TAP_MS = 350L
        val WORD_RE = Regex("[A-Za-z]+(?:'[A-Za-z]+)*'?")
        const val WORD_END_PUNCT = ".,!?;:)\""
        val I_WORDS = setOf("i", "i'm", "i'll", "i'd", "i've")
        /** a flagged typo: the fix must beat the typed word by this much (score units, nats) */
        const val FLAG_MARGIN = 1.5
        /** "only auto-correct when confident": the choice must beat every other candidate by this much (WordResult.margin) */
        const val CONFIDENT_MARGIN = 3.0
        const val UNDOS_TO_ADD = 2

        fun r3(x: Double) = Math.round(x * 1000.0) / 1000.0

        /** -> (left context = text up to the end of the previous word, separator before the current word) */
        fun splitLeft(before: String): Pair<String, String> {
            val m = SEG.findAll(before).lastOrNull() ?: return "" to before
            return before.substring(0, m.range.last + 1) to before.substring(m.range.last + 1)
        }
    }
}
