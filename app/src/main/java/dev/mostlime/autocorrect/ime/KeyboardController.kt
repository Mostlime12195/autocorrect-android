package dev.mostlime.autocorrect.ime

import dev.mostlime.autocorrect.engine.MergeInput
import dev.mostlime.autocorrect.engine.PrevWord
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
}

interface ControllerUi {
    /** undo = index of the word as typed after a correction (drawn as an undo chip), -1 if none */
    fun showStrip(words: List<String>, bold: Int, undo: Int = -1)
    /** a correction just changed the text (micro-interaction hook) */
    fun onApplied() {}
    fun setShift(shift: Shift)
    fun onResult(r: WordResult?)
}

interface Timer {
    fun post(delayMs: Long, r: () -> Unit): Any
    fun cancel(token: Any)
}

class CorrectionRequest(val id: Int, val input: WordInput, val prev: PrevWord?, val merge: MergeInput?, val formal: Boolean = false)

/** Snapshot of the settings the controller needs (re-read at every input start). */
class ControllerConfig(
    val policy: FieldPolicy,
    val autocorrect: Boolean,
    val style: String,
    val modelId: () -> String,
    val appVersion: String,
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
                       val margin: Double = 0.0)
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

    enum class StripMode { NONE, TYPING, LAST_WORD }
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
        resetWord(); last = null; prev = null; pendings.clear(); compLen = 0
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
            resetWord(); last = null; prev = null
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
                    if (stripMode == StripMode.LAST_WORD) setStrip(emptyList(), -1, StripMode.NONE)
                    composing.append(c); touches.add(touch)
                    ed.setComposing(composing.toString())
                    if (shift == Shift.ON) shift = Shift.OFF
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
            prev = null; autoShift(); setStrip(emptyList(), -1, StripMode.NONE); return
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
            PrevWord(p.slot, p.idx, leftp, sepp, p.lmScored, p.margin)
        } else null
        val id = nextId++
        val pd = Pending(id, typed, tch, sepText, before, sep, prevOut, prevSep, -1).apply { uncommitted = true }
        pendings.add(pd)
        // keep at most MAX_PENDING corrections in flight (slow model + fast typing): the oldest gives way
        while (pendings.size > MAX_PENDING) { val old = pendings.removeAt(0); old.done = true; stats.dropped++ }
        val req = CorrectionRequest(id, WordInput(typed, tch, left, sep), prevWord, if (prevWord != null && p != null) MergeInput(p.typed, p.touches) else null,
            formal = cfg.style == "formal")
        prev = null   // set again when this word's result is applied
        setStrip(emptyList(), -1, StripMode.NONE)
        engine.correct(req) { r -> onCorrection(pd, r) }
        if (pd.uncommitted) {
            holding = true
            holdDone = { !pd.uncommitted }
            holdToken = timer.post(FAST_HOLD_MS) { commitHeld(pd); releaseHold() }
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

    private fun onCorrection(pd: Pending, r: WordResult?) {
        if (pd.done) return
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
            if (holding && holdDone()) releaseHold()
        }
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
        if (!changed) {
            stats.unchanged++
            if (ev != null) writePerf(r)
            // the word stands as typed: it becomes the previous word for the next re-check
            if (pendings.none { it.anchor > pd.anchor }) prev = Prev(r.slot, r.chosen, pd.typed, r.lmScored, pd.typed, pd.touches, if (r.lmScored) r.margin else 0.0)
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
        prev = if (mg != null || !newest || xi != cur.length) null else Prev(r.slot, r.chosen, out, r.lmScored, pd.typed, pd.touches, if (r.lmScored) r.margin else 0.0)
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
                prev?.let { pv -> val k = pv.slot.cands.indexOf(pick); if (k >= 0) { pv.idx = k; pv.out = pick } else prev = null }
                setStrip(emptyList(), -1, StripMode.NONE)
            }
            StripMode.NONE -> {}
        }
    }

    /** Standalone i / i'm / i'll / i'd / i've -> I, I'm, ... (every style except casual, which keeps text as typed). */
    private fun capI(text: String): String {
        if (config.style == "casual") return text
        return text.split(' ').joinToString(" ") { w -> if (w in I_WORDS) "I" + w.substring(1) else w }
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

    private fun setStrip(s: List<String>, bold: Int, mode: StripMode, undo: Int = -1) {
        shown = s; stripMode = mode
        ui.showStrip(s, bold, undo)
    }

    // ------------------------------------------------------------------ helpers
    private fun resetWord() {
        composing.setLength(0); touches.clear(); keepTypedOnce = false
    }

    fun autoShift() {
        if (shift == Shift.LOCK || !lettersLayer) return
        val caps = config.policy.capSentences && (editor()?.capsMode() ?: false)
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

        fun r3(x: Double) = Math.round(x * 1000.0) / 1000.0

        /** -> (left context = text up to the end of the previous word, separator before the current word) */
        fun splitLeft(before: String): Pair<String, String> {
            val m = SEG.findAll(before).lastOrNull() ?: return "" to before
            return before.substring(0, m.range.last + 1) to before.substring(m.range.last + 1)
        }
    }
}
