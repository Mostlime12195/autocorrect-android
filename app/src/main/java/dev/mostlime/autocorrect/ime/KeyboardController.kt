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
    fun beginBatch()
    fun endBatch()
    /** backspace when there's nothing we know about before the cursor (selection or empty field) */
    fun deleteKey()
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
    fun showStrip(words: List<String>, bold: Int)
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

    private class Prev(val slot: Slot, var idx: Int, var out: String, val lmScored: Boolean, val typed: String, val touches: List<Touch>)
    private var prev: Prev? = null

    /** A correction in flight. */
    private class Pending(
        val id: Int, val typed: String, val touches: List<Touch>, val sepText: String, val before: String, val sep: String,
        val prevOut: String?, val prevSep: String, val prevTyped: Prev?,
    ) { var done = false }
    private var pending: Pending? = null
    private var nextId = 1

    private var holding = false
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
        resetWord(); last = null; prev = null; pending = null
        releaseHold()
        shift = Shift.OFF
        autoShift()
        setStrip(emptyList(), -1, StripMode.NONE)
    }

    fun finishInput() {
        flushLast()
        releaseHold()
        pending = null
        resetWord()
    }

    /** The editor reported a selection change: make sure the composing word is still right before the cursor. */
    fun selectionChanged() {
        if (holding || composing.isEmpty()) return
        val ed = editor() ?: return
        if (ed.hasSelection() || !ed.textBefore(composing.length + 1).endsWith(composing)) {
            ed.finishComposing()
            resetWord(); last = null; prev = null
            setStrip(emptyList(), -1, StripMode.NONE)
        }
    }

    // ------------------------------------------------------------------ keys
    fun key(k: KeyDef, touch: Touch) {
        if (holding) { held.add { key(k, touch) }; return }
        val ed = editor() ?: return
        when (k.type) {
            KeyType.CHAR -> {
                var c = k.text
                if (shift != Shift.OFF) c = c.uppercase()
                val ch = c[0]
                val letter = ch in 'a'..'z' || ch in 'A'..'Z'
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
                val p = pending
                if (p != null && !p.done) {
                    // hold enter (and later keys) until the correction lands or the hold times out
                    holding = true
                    holdToken = timer.post(ENTER_HOLD_MS) { releaseHold() }
                    held.add { flushLast(); editor()?.enter(); autoShift() }
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
        // commit the word as typed right away; the correction (if any) replaces it when it's ready
        ed.commit(typed + sepText)
        resetWord()
        prev = prev.takeIf { canCorrect }
        if (!canCorrect || !raw.endsWith(typed)) { prev = null; autoShift(); setStrip(emptyList(), -1, StripMode.NONE); return }

        val (left, sep) = splitLeft(before)
        val p = prev
        var prevOut: String? = null
        var prevSep = ""
        val prevWord = if (p != null && SEG.findAll(left).lastOrNull()?.let { it.value == p.out && it.range.last == left.length - 1 } == true) {
            val (leftp, sepp) = splitLeft(left.dropLast(p.out.length))
            prevOut = p.out; prevSep = sep
            PrevWord(p.slot, p.idx, leftp, sepp, p.lmScored)
        } else null
        val id = nextId++
        val pd = Pending(id, typed, tch, sepText, before, sep, prevOut, prevSep, p.takeIf { prevWord != null })
        pending = pd
        engine.cancelQueued()
        val req = CorrectionRequest(id, WordInput(typed, tch, left, sep), prevWord, if (prevWord != null && p != null) MergeInput(p.typed, p.touches) else null,
            formal = cfg.style == "formal")
        engine.correct(req) { r -> onCorrection(pd, r) }
        prev = null   // set again when this word's result is applied
        autoShift()
        setStrip(emptyList(), -1, StripMode.NONE)
    }

    private fun onCorrection(pd: Pending, r: WordResult?) {
        if (pd.done) return
        pd.done = true
        val latest = pending === pd
        if (latest) pending = null
        try {
            // a newer word end already happened: this result is stale by definition
            if (latest) apply(pd, r) else { stats.dropped++; ui.onResult(r) }
        } finally {
            if (holding && pending == null) releaseHold()
        }
    }

    private fun apply(pd: Pending, r: WordResult?) {
        ui.onResult(r)
        if (r == null) return
        val ed = editor() ?: return
        val mg = r.merge?.takeIf { pd.prevOut != null }
        val rc = r.recheck?.takeIf { mg == null && pd.prevOut != null }
        // new text for the previous word: re-check flip and/or formal punctuation
        val prevNew = r.prevText?.takeIf { mg == null && pd.prevOut != null && it != pd.prevOut }
        val out = mg?.to ?: r.out
        val cur = composing.toString()
        val changed = out != pd.typed || prevNew != null
        val ev = if (!config.policy.sensitive) buildEvent(pd, r) else null
        if (!changed) {
            stats.unchanged++
            if (ev != null) writePerf(r)
            // the word stands as typed: it becomes the previous word for the next re-check
            prev = Prev(r.slot, r.chosen, pd.typed, r.lmScored, pd.typed, pd.touches)
            return
        }
        val prefixOld = if (mg != null || prevNew != null) pd.prevOut + pd.prevSep else ""
        val oldSpan = prefixOld + pd.typed + pd.sepText + cur
        val live = ed.textBefore(oldSpan.length + 1)
        if (!live.endsWith(oldSpan) || ed.hasSelection()) {
            stats.dropped++
            ev?.let { it["outcome"] = "dropped_stale"; write(it) }
            return
        }
        val newPrefix = if (prevNew != null) prevNew + pd.prevSep else ""
        val newSpan = newPrefix + out + pd.sepText
        ed.beginBatch()
        if (cur.isNotEmpty()) ed.finishComposing()
        ed.deleteBefore(oldSpan.length)
        ed.commit(newSpan)
        if (cur.isNotEmpty()) ed.setComposing(cur)
        ed.endBatch()
        stats.applied++
        if (rc != null) ev?.set("recheck", mapOf("from" to rc.from, "to" to rc.to))
        if (prevNew != null && rc?.to != prevNew) ev?.set("punct", mapOf("from" to pd.prevOut, "to" to prevNew))
        if (mg != null) ev?.let { it["merge"] = mapOf("from" to pd.prevOut + pd.prevSep + pd.typed, "to" to mg.to); it["final"] = mg.to }
        val undoPrefix = if (mg != null) pd.prevOut + pd.prevSep else ""
        last = Last(pd.typed, pd.touches, out, pd.sepText, r, ev, undoPrefix)
        prev = if (mg != null) null else Prev(r.slot, r.chosen, out, r.lmScored, pd.typed, pd.touches)
        if (cur.isEmpty() && out != pd.typed) {
            val ranked = r.ranked(3).toMutableList()
            if (pd.typed !in ranked) { if (ranked.size >= 3) ranked[2] = pd.typed else ranked.add(pd.typed) }
            setStrip(ranked, ranked.indexOf(out), StripMode.LAST_WORD)
        }
    }

    fun pickSuggestion(i: Int) {
        if (holding) { held.add { pickSuggestion(i) }; return }
        val ed = editor() ?: return
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
                e?.let { it["outcome"] = "strip_pick"; it["final"] = pick; write(it) }
                last = null
                prev?.let { pv -> val k = pv.slot.cands.indexOf(pick); if (k >= 0) { pv.idx = k; pv.out = pick } else prev = null }
                setStrip(emptyList(), -1, StripMode.NONE)
            }
            StripMode.NONE -> {}
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

    private fun setStrip(s: List<String>, bold: Int, mode: StripMode) {
        shown = s; stripMode = mode
        ui.showStrip(s, bold)
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
            "lm_ms" to r3(r.lmMs), "n_scored" to r.nScored, "n_cands" to r.nCands, "skipped" to r.skipped, "cheap" to r.cheap,
            "ctx_tokens" to r.ctxTokens))
    }

    companion object {
        const val CTX_CHARS = 400
        const val LOG_CONTEXT_CHARS = 120
        const val LOG_CANDS = 8
        const val ENTER_HOLD_MS = 400L
        const val SUGGEST_DEBOUNCE_MS = 60L
        const val DOUBLE_TAP_MS = 350L
        val WORD_RE = Regex("[A-Za-z]+(?:'[A-Za-z]+)*'?")
        const val WORD_END_PUNCT = ".,!?;:)\""

        fun r3(x: Double) = Math.round(x * 1000.0) / 1000.0

        /** -> (left context = text up to the end of the previous word, separator before the current word) */
        fun splitLeft(before: String): Pair<String, String> {
            val m = SEG.findAll(before).lastOrNull() ?: return "" to before
            return before.substring(0, m.range.last + 1) to before.substring(m.range.last + 1)
        }
    }
}
