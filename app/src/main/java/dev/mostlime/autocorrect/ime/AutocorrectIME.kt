package dev.mostlime.autocorrect.ime

import android.content.ComponentCallbacks2
import android.inputmethodservice.InputMethodService
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import dev.mostlime.autocorrect.App
import dev.mostlime.autocorrect.BuildConfig
import dev.mostlime.autocorrect.engine.DecodeParams
import dev.mostlime.autocorrect.engine.MergeInput
import dev.mostlime.autocorrect.engine.PrevWord
import dev.mostlime.autocorrect.engine.SEG
import dev.mostlime.autocorrect.engine.Slot
import dev.mostlime.autocorrect.engine.Touch
import dev.mostlime.autocorrect.engine.WordInput
import dev.mostlime.autocorrect.engine.WordResult
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import java.util.UUID
import kotlin.math.roundToInt

/**
 * The keyboard. Key presses are handled strictly in order on the main thread through one action queue; the only
 * suspending step is word end, where the corrector runs on the engine thread (EngineHolder). The LM runs only there
 * (space / punctuation / enter); while typing, the strip shows cheap spatial + unigram suggestions (debounced).
 */
class AutocorrectIME : InputMethodService(), KeyboardView.Listener {
    private lateinit var app: App
    private var kv: KeyboardView? = null
    private val main = MainScope()
    private val actions = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private var policy = FieldPolicy.NONE
    private var info: EditorInfo? = null

    // the word being typed (composing text) and its touches in model units
    private val composing = StringBuilder()
    private val touches = ArrayList<Touch>()
    private var keepTypedOnce = false

    /** The last finished word: for undo-by-backspace, strip replacement and the event outcome. */
    private class Last(
        val typed: String, val touches: List<Touch>, var out: String, val sepText: String,
        val result: WordResult?, var event: MutableMap<String, Any?>?, val autocorrected: Boolean,
        /** text to put back before the restored word on undo (a merge also swallowed the previous word) */
        val undoPrefix: String = "",
    )
    private var last: Last? = null

    /** The previous word as the corrector saw it, for the re-check pass. */
    private class Prev(val slot: Slot, var idx: Int, var out: String, val lmScored: Boolean, val typed: String, val touches: List<Touch>)
    private var prev: Prev? = null

    private enum class StripMode { NONE, TYPING, LAST_WORD }
    private var stripMode = StripMode.NONE
    private var shown: List<String> = emptyList()
    private var suggestJob: Job? = null
    private var lastShiftTap = 0L
    private var shift = Shift.OFF
        set(v) { field = v; kv?.shift = v }

    override fun onCreate() {
        super.onCreate()
        app = application as App
        EngineHolder.preload(this)
        main.launch {
            for (a in actions) {
                try { a() } catch (e: Throwable) { Log.e(TAG, "key action failed", e) }
            }
        }
    }

    override fun onDestroy() {
        actions.close()
        main.cancel()
        super.onDestroy()
    }

    override fun onCreateInputView(): View = KeyboardView(this, this).also { kv = it }

    override fun onStartInputView(editorInfo: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(editorInfo, restarting)
        info = editorInfo
        policy = FieldPolicy.of(editorInfo)
        resetWord()
        last = null; prev = null
        val v = kv ?: return
        v.haptics = app.prefs.haptics
        v.layer = if (policy.numeric) KeyboardLayout.SYMBOLS else KeyboardLayout.LETTERS
        v.enterLabel = enterLabel(editorInfo)
        shift = Shift.OFF
        autoShift()
        setStrip(emptyList(), -1, StripMode.NONE)
        if (EngineHolder.scorer == null) EngineHolder.preload(this)
        updateStatus(null)
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        flushLast()
        super.onFinishInputView(finishingInput)
    }

    override fun onFinishInput() {
        flushLast()
        resetWord()
        super.onFinishInput()
    }

    override fun onUpdateSelection(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int, candidatesStart: Int, candidatesEnd: Int) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        // the user moved the cursor away from the word being composed: keep it as typed. Updates without a composing
        // region (candidatesEnd < 0) are ignored: they can be stale ones from our own commit arriving after the next key.
        if (composing.isNotEmpty() && candidatesEnd >= 0 && (newSelStart != newSelEnd || newSelEnd != candidatesEnd)) {
            currentInputConnection?.finishComposingText()
            resetWord()
            setStrip(emptyList(), -1, StripMode.NONE)
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        @Suppress("DEPRECATION")
        if (level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL || level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE) EngineHolder.releaseAsync()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        EngineHolder.releaseAsync()
    }

    override fun onEvaluateFullscreenMode() = false

    // ---------------------------------------------------------------- KeyboardView.Listener (queued, in order)
    override fun onKey(key: KeyDef, touch: Touch) {
        actions.trySend { handleKey(key, touch) }
    }

    override fun onSuggestion(index: Int) {
        actions.trySend { pickSuggestion(index) }
    }

    override fun onSwitchIme() {
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
    }

    private suspend fun handleKey(key: KeyDef, touch: Touch) {
        val ic = currentInputConnection ?: return
        when (key.type) {
            KeyType.CHAR -> {
                var c = key.text
                if (shift != Shift.OFF) c = c.uppercase()
                val ch = c[0]
                val letter = ch in 'a'..'z' || ch in 'A'..'Z'
                if (letter || (ch == '\'' && composing.isNotEmpty())) {
                    flushLast()
                    if (stripMode == StripMode.LAST_WORD) setStrip(emptyList(), -1, StripMode.NONE)
                    composing.append(c); touches.add(touch)
                    ic.setComposingText(composing, 1)
                    if (shift == Shift.ON) shift = Shift.OFF
                    scheduleSuggest()
                } else if (composing.isNotEmpty()) {
                    finishWord(c, trigger = ch in WORD_END_PUNCT)
                } else {
                    flushLast()
                    ic.commitText(c, 1)
                    autoShift()
                }
            }
            KeyType.SPACE -> if (composing.isNotEmpty()) finishWord(" ", trigger = true) else { flushLast(); ic.commitText(" ", 1); autoShift() }
            KeyType.ENTER -> {
                if (composing.isNotEmpty()) finishWord("", trigger = true)
                flushLast()
                sendKeyChar('\n')
                autoShift()
            }
            KeyType.BACKSPACE -> backspace(ic)
            KeyType.SHIFT -> {
                val now = SystemClock.uptimeMillis()
                shift = when {
                    shift == Shift.LOCK -> Shift.OFF
                    shift == Shift.ON && now - lastShiftTap < DOUBLE_TAP_MS -> Shift.LOCK
                    shift == Shift.ON -> Shift.OFF
                    else -> Shift.ON
                }
                lastShiftTap = now
            }
            KeyType.TO_SYMBOLS -> kv?.layer = KeyboardLayout.SYMBOLS
            KeyType.TO_SYMBOLS2 -> kv?.layer = KeyboardLayout.SYMBOLS2
            KeyType.TO_LETTERS -> kv?.layer = KeyboardLayout.LETTERS
        }
    }

    private fun backspace(ic: InputConnection) {
        if (composing.isNotEmpty()) {
            composing.deleteCharAt(composing.length - 1)
            if (touches.isNotEmpty()) touches.removeAt(touches.size - 1)
            if (composing.isEmpty()) { ic.commitText("", 1); setStrip(emptyList(), -1, StripMode.NONE) } else { ic.setComposingText(composing, 1); scheduleSuggest() }
            return
        }
        val l = last
        if (l != null && l.autocorrected) {
            val tail = l.out + l.sepText
            val before = ic.getTextBeforeCursor(tail.length, 0)?.toString()
            if (before == tail) {
                // undo: back to exactly what was typed, kept as typed at the next word end
                ic.beginBatchEdit()
                ic.deleteSurroundingText(tail.length, 0)
                if (l.undoPrefix.isNotEmpty()) ic.commitText(l.undoPrefix, 1)
                composing.append(l.typed); touches.addAll(l.touches)
                ic.setComposingText(composing, 1)
                ic.endBatchEdit()
                keepTypedOnce = true
                l.event?.let { it["outcome"] = "undone"; it["final"] = l.typed; writeEvent(it) }
                last = null; prev = null
                setStrip(emptyList(), -1, StripMode.NONE)
                return
            }
        }
        flushLast()
        val sel = ic.getSelectedText(0)
        if (!sel.isNullOrEmpty()) ic.commitText("", 1)
        else if (ic.getTextBeforeCursor(1, 0).isNullOrEmpty()) sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
        else ic.deleteSurroundingTextInCodePoints(1, 0)
        setStrip(emptyList(), -1, StripMode.NONE)
        autoShift()
    }

    // ---------------------------------------------------------------- word end
    /** -> (left context = text up to the end of the previous word, separator before the current word) */
    private fun splitLeft(before: String): Pair<String, String> {
        val m = SEG.findAll(before).lastOrNull() ?: return "" to before
        return before.substring(0, m.range.last + 1) to before.substring(m.range.last + 1)
    }

    private suspend fun finishWord(sepText: String, trigger: Boolean) {
        val ic = currentInputConnection ?: return
        flushLast()
        suggestJob?.cancel()
        val typed = composing.toString()
        val tch = ArrayList(touches)
        val canCorrect = trigger && policy.autocorrect && app.prefs.autocorrect && WORD_RE.matches(typed) && !keepTypedOnce
        var result: WordResult? = null
        var recheckApplied = false
        var before = ""
        var prevOut: String? = null
        var sep = ""
        if (canCorrect) {
            val raw = ic.getTextBeforeCursor(CTX_CHARS, 0)?.toString() ?: ""
            before = if (raw.endsWith(typed)) raw.dropLast(typed.length) else raw
            val (left, s) = splitLeft(before)
            sep = s
            // re-check the previous word only if the editor still shows exactly what we committed
            val p = prev
            val prevWord = if (p != null && left.endsWith(p.out) && SEG.findAll(left).lastOrNull()?.value == p.out) {
                val (leftp, sepp) = splitLeft(left.dropLast(p.out.length))
                prevOut = p.out
                PrevWord(p.slot, p.idx, leftp, sepp, p.lmScored)
            } else null
            val scorer = EngineHolder.scorer
            val params = EngineHolder.params ?: DEFAULT_PARAMS
            if (scorer == null && !EngineHolder.loading) EngineHolder.preload(this)
            result = withTimeoutOrNull(WORD_TIMEOUT_MS) {
                withContext(EngineHolder.dispatcher) {
                    val corr = EngineHolder.ensureLexicon(app)
                    val sc = scorer?.takeIf { !it.step.closed }
                    corr.correctWord(WordInput(typed, tch, left, sep), sc, params, prevWord,
                        merge = if (prevWord != null && p != null) MergeInput(p.typed, p.touches) else null)
                }
            }
        }
        val mg = result?.merge?.takeIf { prevOut != null }
        val out = mg?.to ?: result?.out ?: typed
        val rc = result?.recheck.takeIf { mg == null }
        var undoPrefix = ""
        ic.beginBatchEdit()
        if (mg != null) {
            // "some thing" -> "something": replace the previous word, the space and this word
            ic.commitText(typed, 1)
            ic.deleteSurroundingText(prevOut!!.length + sep.length + typed.length, 0)
            ic.commitText(mg.to + sepText, 1)
            undoPrefix = prevOut + sep
        } else if (rc != null && prevOut != null) {
            ic.commitText(out, 1)
            ic.deleteSurroundingText(prevOut.length + sep.length + out.length, 0)
            ic.commitText(rc.to + sep + out + sepText, 1)
            recheckApplied = true
        } else {
            ic.commitText(out + sepText, 1)
        }
        ic.endBatchEdit()
        resetWord()

        if (result != null && result.lmScored && EngineHolder.firstInferenceMs < 0) {
            EngineHolder.firstInferenceMs = result.lmMs
            logSession()
        }
        if (result != null) { EngineHolder.lastInferenceMs = result.lmMs; EngineHolder.lastScored = result.nScored }
        val ev = if (result != null && !policy.sensitive) buildEvent(typed, tch, before, sep, result, if (recheckApplied) rc!!.from to rc.to else null) else null
        if (mg != null) ev?.let { it["merge"] = mapOf("from" to prevOut + sep + typed, "to" to mg.to); it["final"] = mg.to }
        val corrected = out != typed
        last = Last(typed, tch, out, sepText, result, if (corrected || recheckApplied) ev else null, corrected, undoPrefix)
        if (ev != null && !corrected && !recheckApplied) writePerf(result!!)
        prev = if (mg != null) null else result?.let { r -> Prev(r.slot, r.chosen, out, r.lmScored, typed, tch) }
        if (result != null && corrected) {
            val ranked = result.ranked(3).toMutableList()
            if (typed !in ranked) { if (ranked.size >= 3) ranked[2] = typed else ranked.add(typed) }
            setStrip(ranked, ranked.indexOf(out), StripMode.LAST_WORD)
        } else setStrip(emptyList(), -1, StripMode.NONE)
        updateStatus(result)
        autoShift()
    }

    private suspend fun pickSuggestion(i: Int) {
        val ic = currentInputConnection ?: return
        val pick = shown.getOrNull(i) ?: return
        when (stripMode) {
            StripMode.TYPING -> {
                if (composing.isEmpty()) return
                val typed = composing.toString()
                val tch = ArrayList(touches)
                ic.commitText("$pick ", 1)
                resetWord()
                flushLast()
                if (!policy.sensitive) {
                    val e = baseEvent("strip_pick", typed, tch)
                    e["candidates"] = shown.map { mapOf("w" to it) }
                    e["chosen"] = pick; e["final"] = pick; e["outcome"] = "strip_pick"; e["while_typing"] = true
                    writeEvent(e)
                }
                last = Last(typed, tch, pick, " ", null, null, pick != typed)
                prev = null
                setStrip(emptyList(), -1, StripMode.NONE)
                autoShift()
            }
            StripMode.LAST_WORD -> {
                val l = last ?: return
                val tail = l.out + l.sepText
                if (ic.getTextBeforeCursor(tail.length, 0)?.toString() != tail) return
                ic.beginBatchEdit()
                ic.deleteSurroundingText(tail.length, 0)
                ic.commitText(pick + l.sepText, 1)
                ic.endBatchEdit()
                val e = l.event ?: if (!policy.sensitive && l.result != null) buildEvent(l.typed, l.touches, "", "", l.result, null) else null
                e?.let { it["outcome"] = "strip_pick"; it["final"] = pick; writeEvent(it) }
                last = null
                prev?.let { p -> val k = p.slot.cands.indexOf(pick); if (k >= 0) { p.idx = k; p.out = pick } else prev = null }
                setStrip(emptyList(), -1, StripMode.NONE)
            }
            StripMode.NONE -> {}
        }
    }

    // ---------------------------------------------------------------- strip while typing (cheap path only)
    private fun scheduleSuggest() {
        suggestJob?.cancel()
        if (!policy.suggestions || !app.prefs.autocorrect) return
        val typed = composing.toString()
        if (!WORD_RE.matches(typed)) return
        val tch = ArrayList(touches)
        suggestJob = main.launch {
            delay(SUGGEST_DEBOUNCE_MS)
            val s = withContext(EngineHolder.dispatcher) { EngineHolder.ensureLexicon(app).suggest(typed, tch, 3) }
            if (composing.toString() == typed) setStrip(s, 0, StripMode.TYPING)
        }
    }

    private fun setStrip(s: List<String>, bold: Int, mode: StripMode) {
        shown = s; stripMode = mode
        kv?.setSuggestions(s, bold)
    }

    // ---------------------------------------------------------------- helpers
    private fun resetWord() {
        composing.setLength(0); touches.clear(); keepTypedOnce = false
    }

    private fun autoShift() {
        if (shift == Shift.LOCK || kv?.layer != KeyboardLayout.LETTERS) return
        val ei = info ?: return
        val caps = policy.capSentences && (currentInputConnection?.getCursorCapsMode(ei.inputType) ?: 0) != 0
        shift = if (caps) Shift.ON else Shift.OFF
    }

    private fun enterLabel(ei: EditorInfo?): String {
        if (ei == null || (ei.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0) return "⏎"
        return when (ei.imeOptions and EditorInfo.IME_MASK_ACTION) {
            EditorInfo.IME_ACTION_GO -> "Go"
            EditorInfo.IME_ACTION_SEARCH -> "Search"
            EditorInfo.IME_ACTION_SEND -> "Send"
            EditorInfo.IME_ACTION_NEXT -> "Next"
            EditorInfo.IME_ACTION_DONE -> "Done"
            EditorInfo.IME_ACTION_PREVIOUS -> "Prev"
            else -> "⏎"
        }
    }

    private fun updateStatus(r: WordResult?) {
        val v = kv ?: return
        val spec = runCatching { app.store.activeSpec() }.getOrNull()
        val missing = spec != null && !app.store.isInstalled(spec)
        v.status = when {
            !policy.autocorrect -> ""
            missing -> "model not downloaded: open Autocorrect"
            !app.prefs.debugOverlay -> if (EngineHolder.loading) "loading model…" else ""
            else -> buildString {
                append(EngineHolder.spec?.id ?: if (EngineHolder.loading) "loading…" else "cheap")
                if (r != null) {
                    append(" · ")
                    append(when { r.cheap -> "cheap"; r.skipped -> "skip"; else -> "LM ${r.lmMs.roundToInt()}ms ${r.nScored}/${r.nCands}" })
                    r.recheck?.let { append(" · re-check") }
                }
                val st = EngineHolder.corrector?.stats
                if (st != null && st.words > 0) append(" · skip ${(100 * st.lmSkipped / st.words)}%")
                if (EngineHolder.coldLoadMs >= 0) append(" · cold ${EngineHolder.coldLoadMs}ms")
                if (EngineHolder.firstInferenceMs >= 0) append(" · 1st ${EngineHolder.firstInferenceMs.roundToInt()}ms")
            }
        }
    }

    // ---------------------------------------------------------------- logging (non-sensitive fields only)
    private fun baseEvent(type: String, typed: String, tch: List<Touch>): MutableMap<String, Any?> = linkedMapOf(
        "v" to 1, "id" to UUID.randomUUID().toString(), "ts" to System.currentTimeMillis(), "type" to type,
        "typed" to typed, "touches" to tch.map { if (it.isApos) null else listOf(r3(it.x), r3(it.y)) },
        "style" to app.prefs.style, "model" to (EngineHolder.spec?.id ?: "none"), "app_version" to BuildConfig.VERSION_NAME,
    )

    private fun buildEvent(typed: String, tch: List<Touch>, before: String, sep: String, r: WordResult, recheck: Pair<String, String>?): MutableMap<String, Any?> {
        val e = baseEvent("autocorrect", typed, tch)
        e["left_context"] = before.takeLast(LOG_CONTEXT_CHARS)
        e["sep"] = sep
        val order = r.slot.cands.indices.sortedByDescending { r.scores[it] }.take(LOG_CANDS)
        e["candidates"] = order.map { i ->
            mapOf("w" to r.slot.cands[i], "kind" to r.slot.kind(i), "sp" to r3(r.slot.sp[i]), "lm" to r.lm?.get(i)?.let(::r3), "score" to r3(r.scores[i]))
        }
        e["chosen"] = r.out
        e["final"] = r.out
        e["outcome"] = "accepted"
        e["lm_ms"] = r3(r.lmMs); e["n_scored"] = r.nScored; e["n_cands"] = r.nCands
        e["skipped"] = r.skipped; e["cheap"] = r.cheap
        recheck?.let { e["recheck"] = mapOf("from" to it.first, "to" to it.second) }
        return e
    }

    /** No text: timing only, for the words that weren't changed. */
    private fun writePerf(r: WordResult) {
        writeEvent(linkedMapOf("v" to 1, "ts" to System.currentTimeMillis(), "type" to "perf", "model" to (EngineHolder.spec?.id ?: "none"),
            "lm_ms" to r3(r.lmMs), "n_scored" to r.nScored, "n_cands" to r.nCands, "skipped" to r.skipped, "cheap" to r.cheap))
    }

    private fun logSession() {
        if (policy.sensitive) return
        writeEvent(linkedMapOf("v" to 1, "ts" to System.currentTimeMillis(), "type" to "session", "model" to (EngineHolder.spec?.id ?: "none"),
            "session_ms" to EngineHolder.sessionMs, "cold_load_ms" to EngineHolder.coldLoadMs, "lexicon_ms" to EngineHolder.lexiconMs,
            "first_inference_ms" to r3(EngineHolder.firstInferenceMs), "app_version" to BuildConfig.VERSION_NAME))
    }

    /** The pending outcome of the last word is final ("accepted") once anything other than undo / a strip pick happens. */
    private fun flushLast() {
        last?.event?.let { writeEvent(it) }
        last = null
    }

    private fun writeEvent(e: Map<String, Any?>) {
        if (policy.sensitive) return
        app.events.append(toJson(e) as JsonObject)
    }

    companion object {
        const val TAG = "AcIME"
        const val CTX_CHARS = 400
        const val LOG_CONTEXT_CHARS = 120
        const val LOG_CANDS = 8
        const val WORD_TIMEOUT_MS = 1500L
        const val SUGGEST_DEBOUNCE_MS = 60L
        const val DOUBLE_TAP_MS = 350L
        val WORD_RE = Regex("[A-Za-z]+(?:'[A-Za-z]+)*'?")
        const val WORD_END_PUNCT = ".,!?;:)\""
        val DEFAULT_PARAMS = DecodeParams(lam = 1.25, bias = 0.0, margin = 0.0, lamR = 1.25)

        private fun r3(x: Double) = Math.round(x * 1000.0) / 1000.0

        @Suppress("UNCHECKED_CAST")
        fun toJson(v: Any?): kotlinx.serialization.json.JsonElement = when (v) {
            null -> kotlinx.serialization.json.JsonNull
            is String -> kotlinx.serialization.json.JsonPrimitive(v)
            is Number -> kotlinx.serialization.json.JsonPrimitive(v)
            is Boolean -> kotlinx.serialization.json.JsonPrimitive(v)
            is Map<*, *> -> JsonObject((v as Map<String, Any?>).mapValues { toJson(it.value) })
            is List<*> -> kotlinx.serialization.json.JsonArray(v.map { toJson(it) })
            else -> kotlinx.serialization.json.JsonPrimitive(v.toString())
        }
    }
}
