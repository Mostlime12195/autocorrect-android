package dev.mostlime.autocorrect.ime

import android.content.ComponentCallbacks2
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import dev.mostlime.autocorrect.App
import dev.mostlime.autocorrect.BuildConfig
import dev.mostlime.autocorrect.engine.DecodeParams
import dev.mostlime.autocorrect.engine.Touch
import dev.mostlime.autocorrect.engine.WordResult
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.roundToInt

/**
 * The keyboard service: glue between Android (InputConnection, the view, the engine thread) and KeyboardController,
 * which owns the text state machine. Everything here runs on the main thread except the engine jobs, whose results
 * come back through the main Handler. Nothing on the main thread waits for the model.
 */
class AutocorrectIME : InputMethodService(), KeyboardView.Listener {
    private lateinit var app: App
    private var kv: KeyboardView? = null
    private val main = Handler(Looper.getMainLooper())
    private var info: EditorInfo? = null
    private val jobs = ArrayList<Job>()

    private val editor = object : Editor {
        private val ic get() = currentInputConnection
        override fun textBefore(n: Int) = ic?.getTextBeforeCursor(n, 0)?.toString() ?: ""
        override fun hasSelection() = !ic?.getSelectedText(0).isNullOrEmpty()
        override fun setComposing(text: String) { ic?.setComposingText(text, 1) }
        override fun finishComposing() { ic?.finishComposingText() }
        override fun commit(text: String) { ic?.commitText(text, 1) }
        override fun deleteBefore(n: Int) { ic?.deleteSurroundingText(n, 0) }
        /** one replace (composing region over the span + commit) instead of a visible delete then insert */
        override fun replaceBefore(n: Int, text: String) {
            val c = ic ?: return
            val et = c.getExtractedText(android.view.inputmethod.ExtractedTextRequest(), 0)
            if (et != null && et.selectionStart == et.selectionEnd && et.selectionStart >= 0) {
                val end = et.startOffset + et.selectionStart
                if (end - n >= 0 && c.setComposingRegion(end - n, end)) { c.commitText(text, 1); return }
            }
            c.deleteSurroundingText(n, 0); c.commitText(text, 1)
        }
        override fun beginBatch() { ic?.beginBatchEdit() }
        override fun endBatch() { ic?.endBatchEdit() }
        override fun deleteKey(): Int {
            val c = ic ?: return -1
            if (!c.getSelectedText(0).isNullOrEmpty()) { c.commitText("", 1); return -1 }
            val b = c.getTextBeforeCursor(2, 0)?.toString() ?: ""
            if (b.isEmpty()) { sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL); return -1 }
            c.deleteSurroundingTextInCodePoints(1, 0)
            return if (b.length == 2 && Character.isSurrogatePair(b[0], b[1])) 2 else 1
        }
        override fun enter() { sendKeyChar('\n') }
        override fun capsMode(): Boolean {
            val ei = info ?: return false
            return (ic?.getCursorCapsMode(ei.inputType) ?: 0) != 0
        }
    }

    private val engine = object : CorrectionEngine {
        override fun correct(req: CorrectionRequest, done: (WordResult?) -> Unit) {
            val job = EngineHolder.scope.launch {
                val r = try {
                    val corr = EngineHolder.ensureLexicon(app)
                    val sc = EngineHolder.scorer?.takeIf { !it.step.closed }
                    sc?.lastCtxTokens = 0
                    val res = corr.correctWord(req.input, sc, EngineHolder.params ?: DEFAULT_PARAMS, req.prev, merge = req.merge, formal = req.formal)
                    if (sc != null && res.lmScored) res.ctxTokens = sc.lastCtxTokens
                    res
                } catch (e: Throwable) {
                    Log.e(TAG, "correction failed", e); null
                }
                main.post { done(r) }
            }
            synchronized(jobs) { jobs.add(job) }
            job.invokeOnCompletion { synchronized(jobs) { jobs.remove(job) } }
        }

        override fun suggest(typed: String, touches: List<Touch>, done: (List<String>) -> Unit) {
            EngineHolder.scope.launch {
                val s = runCatching { EngineHolder.ensureLexicon(app).suggest(typed, touches, 3) }.getOrDefault(emptyList())
                main.post { done(s) }
            }
        }

        /** queued corrections that haven't started are superseded by the newer word */
        override fun cancelQueued() {
            synchronized(jobs) { jobs.toList() }.forEach { it.cancel() }
        }
    }

    private val ui = object : ControllerUi {
        override fun showStrip(words: List<String>, bold: Int, undo: Int) { kv?.setSuggestions(words, bold, undo) }
        override fun onApplied() { kv?.flash() }
        override fun setShift(shift: Shift) { kv?.shift = shift }
        override fun onResult(r: WordResult?) {
            // timing only, never the text
            if (r != null) Log.i(TAG, "word -> " +
                (if (r.cheap) "cheap" else if (r.skipped) "skip" else "LM") + " ${"%.0f".format(r.lmMs)}ms scored ${r.nScored}/${r.nCands} " +
                "ctx ${r.ctxTokens}t recheck=${r.recheck != null} model=${EngineHolder.spec?.id} threads=${EngineHolder.THREADS}")
            if (r != null && r.lmScored) {
                if (EngineHolder.firstInferenceMs < 0) { EngineHolder.firstInferenceMs = r.lmMs; logSession() }
                EngineHolder.lastInferenceMs = r.lmMs; EngineHolder.lastScored = r.nScored
            }
            updateStatus(r)
        }
    }

    private val timer = object : Timer {
        override fun post(delayMs: Long, r: () -> Unit): Any { val run = Runnable { r() }; main.postDelayed(run, delayMs); return run }
        override fun cancel(token: Any) { main.removeCallbacks(token as Runnable) }
    }

    private lateinit var ctl: KeyboardController

    override fun onCreate() {
        super.onCreate()
        app = application as App
        ctl = KeyboardController({ currentInputConnection?.let { editor } }, engine, ui, timer) { e ->
            app.events.append(toJson(e) as JsonObject)
        }
        EngineHolder.preload(this)
    }

    override fun onCreateInputView(): View = KeyboardView(this, this).also { kv = it }

    override fun onStartInputView(editorInfo: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(editorInfo, restarting)
        app.prefs.reload()   // settings live in the app process
        info = editorInfo
        val policy = FieldPolicy.of(editorInfo)
        val v = kv
        v?.haptics = app.prefs.haptics
        v?.hapticStrength = app.prefs.hapticStrength
        v?.layer = if (policy.numeric) KeyboardLayout.SYMBOLS else KeyboardLayout.LETTERS
        v?.enterLabel = enterLabel(editorInfo)
        ctl.lettersLayer = v?.layer == KeyboardLayout.LETTERS
        ctl.startInput(ControllerConfig(policy, app.prefs.autocorrect, app.prefs.style, { EngineHolder.spec?.id ?: "none" }, BuildConfig.VERSION_NAME))
        // cheap when loaded; picks up a model / style change or a finished download
        if (!EngineHolder.loading) EngineHolder.preload(this)
        updateStatus(null)
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        ctl.finishInput()
        super.onFinishInputView(finishingInput)
    }

    override fun onFinishInput() {
        ctl.finishInput()
        super.onFinishInput()
    }

    override fun onUpdateSelection(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int, candidatesStart: Int, candidatesEnd: Int) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        ctl.selectionChanged()
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

    // ---------------------------------------------------------------- KeyboardView.Listener
    override fun onKey(key: KeyDef, touch: Touch) {
        when (key.type) {
            KeyType.TO_SYMBOLS -> kv?.layer = KeyboardLayout.SYMBOLS
            KeyType.TO_SYMBOLS2 -> kv?.layer = KeyboardLayout.SYMBOLS2
            KeyType.TO_LETTERS -> kv?.layer = KeyboardLayout.LETTERS
            else -> { ctl.key(key, touch); return }
        }
        ctl.lettersLayer = kv?.layer == KeyboardLayout.LETTERS
        if (ctl.lettersLayer) ctl.autoShift() else ctl.shift = Shift.OFF
    }

    override fun onSuggestion(index: Int) = ctl.pickSuggestion(index)

    override fun onAlternate(text: String) = ctl.replaceLastChar(text)

    override fun onSettings() {
        startActivity(android.content.Intent(this, dev.mostlime.autocorrect.ui.SettingsActivity::class.java)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    override fun onSwitchIme() {
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
    }

    // ---------------------------------------------------------------- helpers
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
        val policy = ctl.config.policy
        val spec = runCatching { app.store.activeSpec() }.getOrNull()
        val missing = spec != null && !app.store.isInstalled(spec)
        v.status = when {
            !policy.autocorrect -> ""
            missing -> "model not downloaded: open Autocorrect"
            !app.prefs.debugOverlay -> if (EngineHolder.loading) "loading model…" else if (EngineHolder.lastError != null) "model error" else ""
            else -> buildString {
                append(EngineHolder.spec?.id ?: if (EngineHolder.loading) "loading…" else "cheap")
                if (r != null) {
                    append(" · ")
                    append(when { r.cheap -> "cheap"; r.skipped -> "skip"; else -> "LM ${r.lmMs.roundToInt()}ms ${r.nScored}/${r.nCands} ctx ${r.ctxTokens}t" })
                    r.recheck?.let { append(" · re-check") }
                    r.merge?.let { append(" · merge") }
                    // top candidates with final scores, for screenshots of misses
                    append(" · ").append(r.slot.cands.indices.sortedByDescending { r.scores[it] }.take(3)
                        .joinToString("  ") { "${r.slot.cands[it]} ${"%.1f".format(r.scores[it])}" })
                }
                val st = EngineHolder.corrector?.stats
                if (st != null && st.words > 0) append(" · skip ${(100 * st.lmSkipped / st.words)}%")
                if (ctl.stats.dropped > 0) append(" · stale ${ctl.stats.dropped}")
                if (EngineHolder.coldLoadMs >= 0) append(" · cold ${EngineHolder.coldLoadMs}ms")
                if (EngineHolder.firstInferenceMs >= 0) append(" · 1st ${EngineHolder.firstInferenceMs.roundToInt()}ms")
            }
        }
    }

    private fun logSession() {
        if (ctl.config.policy.sensitive) return
        app.events.append(toJson(linkedMapOf("v" to 1, "ts" to System.currentTimeMillis(), "type" to "session",
            "model" to (EngineHolder.spec?.id ?: "none"), "session_ms" to EngineHolder.sessionMs, "cold_load_ms" to EngineHolder.coldLoadMs,
            "lexicon_ms" to EngineHolder.lexiconMs, "first_inference_ms" to KeyboardController.r3(EngineHolder.firstInferenceMs),
            "app_version" to BuildConfig.VERSION_NAME)) as JsonObject)
    }

    companion object {
        const val TAG = "AcIME"
        /** web/site/params_v2.json (default style) */
        val DEFAULT_PARAMS = DecodeParams(lam = 1.25, bias = 0.0, margin = 0.0, lamR = 1.25,
            pen = mapOf("split" to 2.0, "space" to 3.0, "contr" to 1.0, "contr_rw" to 0.0, "gdrop" to 1.0, "merge" to 4.0))

        @Suppress("UNCHECKED_CAST")
        fun toJson(v: Any?): JsonElement = when (v) {
            null -> JsonNull
            is String -> JsonPrimitive(v)
            is Number -> JsonPrimitive(v)
            is Boolean -> JsonPrimitive(v)
            is Map<*, *> -> JsonObject((v as Map<String, Any?>).mapValues { toJson(it.value) })
            is List<*> -> JsonArray(v.map { toJson(it) })
            else -> JsonPrimitive(v.toString())
        }
    }
}
