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
import android.widget.Toast
import dev.mostlime.autocorrect.App
import dev.mostlime.autocorrect.BuildConfig
import dev.mostlime.autocorrect.Prefs
import dev.mostlime.autocorrect.engine.DecodeParams
import dev.mostlime.autocorrect.engine.Rewind
import dev.mostlime.autocorrect.engine.RewindChange
import dev.mostlime.autocorrect.engine.RewindTarget
import dev.mostlime.autocorrect.engine.SentenceCheck
import dev.mostlime.autocorrect.engine.SentenceFix
import dev.mostlime.autocorrect.engine.SentenceReq
import kotlinx.coroutines.isActive
import dev.mostlime.autocorrect.engine.SwipeResult
import dev.mostlime.autocorrect.engine.Touch
import dev.mostlime.autocorrect.engine.WordResult
import dev.mostlime.autocorrect.log.EventLog
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
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

    /** the field (InputConnection) as the controller sees it; the cursor falls back to onUpdateSelection's */
    private val editor = IcEditor(this, { currentInputConnection }, { info }, { sendDownUpKeyEvents(it) }, { sendKeyChar(it) }) { lastSelStart }
    @Volatile private var lastSelStart = -1
    /** a new id per input session (field focus): undo learning across sessions, probation */
    private var inputSession = java.util.UUID.randomUUID().toString().take(8)

    private val engine = object : CorrectionEngine {
        override fun correct(req: CorrectionRequest, done: (WordResult?) -> Unit) {
            val job = EngineHolder.scope.launch {
                val r = try {
                    val corr = EngineHolder.ensureLexicon(app)
                    val sc = EngineHolder.scorer?.takeIf { !it.step.closed }
                    sc?.lastCtxTokens = 0
                    val res = corr.correctWord(req.input, sc, EngineHolder.params ?: DEFAULT_PARAMS, req.prev, merge = req.merge, formal = req.formal,
                        autoCap = req.autoCap)
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

        override fun rewind(targets: List<RewindTarget>, done: (List<RewindChange>) -> Unit) {
            EngineHolder.scope.launch {
                val sc = EngineHolder.scorer?.takeIf { !it.step.closed }
                val ch = if (sc == null) emptyList() else runCatching {
                    Rewind.check(targets, sc, (EngineHolder.params ?: DEFAULT_PARAMS).lamRe, Rewind.MARGIN, app.user)
                }.getOrDefault(emptyList())
                main.post { done(ch) }
            }
        }

        override fun swipe(req: SwipeRequest, done: (SwipeResult?) -> Unit) {
            EngineHolder.scope.launch {
                val r = try {
                    val dec = EngineHolder.ensureSwipe(app)
                    val sc = EngineHolder.scorer?.takeIf { !it.step.closed }
                    dec.decodeAndRerank(req.path, req.left, req.sep, req.casePattern, sc)
                } catch (e: Throwable) {
                    Log.e(TAG, "swipe failed", e); null
                }
                // timing only, never the text
                if (r != null) Log.i(TAG, "swipe -> decode ${"%.0f".format(r.decodeMs)}ms (${r.nSurvivors} templates) " +
                    (if (r.lm != null) "LM ${"%.0f".format(r.lmMs)}ms ${r.cands.size} cands" else "no LM") + " pts ${req.path.size}")
                main.post { done(r) }
            }
        }

        private var idleJob: Job? = null

        override fun cancelIdle() { idleJob?.cancel(); idleJob = null }

        /** ATOMIC: a cancelled job still runs (and stops at once), so done() is always called */
        override fun checkSentences(reqs: List<SentenceReq>, done: (List<List<SentenceFix>>?) -> Unit) {
            idleJob = EngineHolder.scope.launch(start = kotlinx.coroutines.CoroutineStart.ATOMIC) {
                val sc = EngineHolder.scorer?.takeIf { !it.step.closed }
                val corr = EngineHolder.corrector
                val out = if (sc == null || corr == null) null else runCatching {
                    val t0 = App.now()
                    val r = reqs.map { req -> if (!isActive) return@runCatching null
                        SentenceCheck.check(corr, req, sc, EngineHolder.params ?: DEFAULT_PARAMS, KeyboardController.FLAG_MARGIN) { !isActive } }
                    Log.i(TAG, "sentence check: ${reqs.size} sentences in ${App.now() - t0} ms")
                    PerfStats.pass(App.now() - t0, this@AutocorrectIME)
                    r
                }.getOrNull()
                val ok = isActive
                main.post { done(if (ok) out else null) }
            }
        }

        override fun rewindIdle(targets: List<RewindTarget>, done: (List<RewindChange>) -> Unit) {
            idleJob = EngineHolder.scope.launch(start = kotlinx.coroutines.CoroutineStart.ATOMIC) {
                val sc = EngineHolder.scorer?.takeIf { !it.step.closed }
                val p = EngineHolder.params ?: DEFAULT_PARAMS
                val t0 = App.now()
                val ch = if (sc == null || !isActive) emptyList() else runCatching {
                    Rewind.check(targets, sc, p.lamRe, Rewind.MARGIN, app.user, p.pen) { !isActive }
                }.getOrDefault(emptyList())
                if (sc != null) { Log.i(TAG, "idle rewind: ${targets.size} targets in ${App.now() - t0} ms, ${ch.size} changes"); PerfStats.pass(App.now() - t0, this@AutocorrectIME) }
                val ok = isActive
                main.post { done(if (ok) ch else emptyList()) }
            }
        }

        /** queued corrections that haven't started are superseded by the newer word */
        override fun cancelQueued() {
            synchronized(jobs) { jobs.toList() }.forEach { it.cancel() }
        }
    }

    /** personal dictionary + learned edits (files shared with the settings screen) */
    private val learning = object : Learning {
        override fun isUserWord(w: String) = app.user.has(w)
        override fun inVocab(w: String): Boolean {
            // unknown until the lexicon is loaded: count as in-vocab, so nothing is learned as spelling
            val lex = EngineHolder.corrector?.lex ?: return true
            return lex.index.containsKey(w.lowercase()) || app.user.has(w)
        }
        override fun addWord(w: String) = app.user.add(w)
        override fun removeWord(w: String) = app.user.remove(w)
        override fun suppress(typed: String, chosen: String) = app.user.suppress(typed, chosen)
        override fun penalty(typed: String, chosen: String) = app.user.penalty(typed, chosen)
        override fun noteSpellingUndo(typed: String) = if (app.prefs.learnUndo) app.user.noteSpellingUndo(typed) else 0
        override fun shouldLearn(typed: String, typoLike: Boolean) = app.prefs.learnUndo && app.user.shouldLearn(typed, inputSession, typoLike)
        override fun addLearned(w: String) = app.user.addProbation(w, inputSession)
        override fun seenTyped(w: String) { app.user.seenTyped(w, inputSession) }
        /** edit distance <= 2 from one of the 20k most frequent lexicon words (only asked on an undo) */
        override fun nearCommonWord(w: String): Boolean {
            val lex = EngineHolder.corrector?.lex ?: return true
            val t = w.lowercase()
            if (lex.index.containsKey(t)) return false
            val n = minOf(lex.size, 20_000)
            for (i in 0 until n) {
                val c = lex.words[i]
                if (kotlin.math.abs(c.length - t.length) <= 2 && dev.mostlime.autocorrect.engine.UserData.editDistance(c, t, 2) <= 2) return true
            }
            return false
        }
    }

    private val ui = object : ControllerUi {
        override fun showStrip(words: List<String>, bold: Int, undo: Int) { kv?.setSuggestions(words, bold, undo) }
        override fun showStrip(words: List<String>, bold: Int, undo: Int, hint: String, action: String?) { kv?.setSuggestions(words, bold, undo, hint, action) }
        override fun onDictAdded(word: String) { Toast.makeText(this@AutocorrectIME, "Added “$word” to dictionary", Toast.LENGTH_SHORT).show() }
        override fun onApplied() { kv?.flash() }
        override fun setShift(shift: Shift) { kv?.shift = shift }
        override fun onResult(r: WordResult?) {
            if (r != null && !r.cheap) PerfStats.word(r.lmMs, this@AutocorrectIME)
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
    private var root: KeyboardRoot? = null
    private lateinit var clips: ClipStore
    private val clipListener = android.content.ClipboardManager.OnPrimaryClipChangedListener { onClipChanged() }

    /** A new clip: keep it unless clipboard history is off, the clip is marked sensitive, or we're in a password /
     *  incognito field. */
    private fun onClipChanged() {
        if (!app.prefs.clipboard) return
        val pol = ctl.config.policy
        if (pol.password || (pol.sensitive && pol.autocorrect)) return   // password or incognito field
        val cm = getSystemService(android.content.ClipboardManager::class.java) ?: return
        val clip = runCatching { cm.primaryClip }.getOrNull() ?: return
        val ex = clip.description?.extras
        if (ex != null && ex.getBoolean(ClipStore.EXTRA_IS_SENSITIVE, false)) return
        val text = runCatching { clip.getItemAt(0)?.coerceToText(this)?.toString() }.getOrNull() ?: return
        clips.add(text)
        updateClipChip()
    }

    private fun updateClipChip() {
        kv?.clipChip = if (app.prefs.clipboard && !ctl.config.policy.password) clips.recentOffer()?.text else null
    }

    private val emojiRecents: MutableList<String> by lazy {
        (getSharedPreferences("emoji", MODE_PRIVATE).getString("recent", "") ?: "").split('\u0001').filter { it.isNotEmpty() }.toMutableList()
    }

    private fun showEmoji() {
        val r = root ?: return
        val data = EmojiData.get(this)
        r.showPanel(EmojiPanel(this, Palette(this), data, { emojiRecents.toList() }, { e ->
            ctl.insertText(e)
            emojiRecents.remove(e); emojiRecents.add(0, e)
            while (emojiRecents.size > 32) emojiRecents.removeAt(emojiRecents.size - 1)
            getSharedPreferences("emoji", MODE_PRIVATE).edit().putString("recent", emojiRecents.joinToString("\u0001")).apply()
        }, { ctl.key(KeyboardLayout.LETTERS.keys.first { it.type == KeyType.BACKSPACE }, Touch.APOS) }) { r.hidePanel() })
    }

    /** layers for the current layout options (rebuilt at input start) */
    private var lettersLayer: Layer = KeyboardLayout.LETTERS
    private var symbolsLayer: Layer = KeyboardLayout.SYMBOLS
    private var symbols2Layer: Layer = KeyboardLayout.SYMBOLS2
    private var layoutOpts: LayoutOptions? = null
    private fun letters() = lettersLayer
    /** colors the current view was built with (a change rebuilds the view) */
    private var themeSig = ""

    private fun showClipboard() {
        val r = root ?: return
        val p = ClipboardPanel(this, Palette(this), clips, { t -> ctl.insertText(t); clips.consumeOffer(); r.hidePanel(); updateClipChip() }) { r.hidePanel() }
        r.showPanel(p)
    }

    override fun onCreate() {
        super.onCreate()
        app = application as App
        // opt-in upload: scrubbed word-level events, queued only while sharing is on (DATA.md)
        val corrections = dev.mostlime.autocorrect.log.CorrectionLog(
            sink = { e -> if (app.prefs.share) app.corrections.append(toJson(e) as JsonObject) },
            inVocab = learning::inVocab,
            isProbation = { w -> app.user.onProbation(w) },
            // the second opt-in (Prefs.shareContext is false unless sharing itself is on)
            context = { app.prefs.shareContext },
            ctxWindow = KeyboardController.LOG_CONTEXT_CHARS,
        )
        ctl = KeyboardController({ currentInputConnection?.let { editor } }, engine, ui, timer, learning, corrections) { e ->
            app.events.append(toJson(e) as JsonObject)
        }
        EngineHolder.preload(this)
        clips = ClipStore(java.io.File(filesDir, "clipboard.json"))
        runCatching { getSystemService(android.content.ClipboardManager::class.java)?.addPrimaryClipChangedListener(clipListener) }
    }

    override fun onDestroy() {
        runCatching { getSystemService(android.content.ClipboardManager::class.java)?.removePrimaryClipChangedListener(clipListener) }
        super.onDestroy()
    }

    override fun onCreateInputView(): View {
        val k = KeyboardView(this, this)
        kv = k
        return KeyboardRoot(this, k).also { root = it }
    }

    override fun onStartInputView(editorInfo: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(editorInfo, restarting)
        app.prefs.reload()   // settings live in the app process
        info = editorInfo
        lastSelStart = editorInfo?.initialSelStart?.takeIf { it >= 0 && it == editorInfo.initialSelEnd } ?: -1
        if (!restarting) inputSession = java.util.UUID.randomUUID().toString().take(8)
        val policy = FieldPolicy.of(editorInfo)
        val o = app.prefs.layoutOptions()
        if (o != layoutOpts) {
            layoutOpts = o
            lettersLayer = KeyboardLayout.letters(o); symbolsLayer = KeyboardLayout.symbols(o); symbols2Layer = KeyboardLayout.symbols2(o)
        }
        EngineHolder.applyLayout(lettersLayer)
        KeyboardLayout.extra = KeyboardLayout.parseExtra(app.prefs.customLongPress)
        val sig = listOf(app.prefs.themeMode, app.prefs.themeColor, app.prefs.customColor,
            resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK).joinToString()
        if (sig != themeSig) {
            val first = themeSig.isEmpty()
            themeSig = sig
            if (!first || kv == null) setInputView(onCreateInputView())
        }
        val v = kv
        v?.keyGapDp = app.prefs.keyGap.toFloat()
        v?.keyRadiusDp = app.prefs.keyRadius.toFloat()
        v?.hintScale = if (!app.prefs.showHints) 0f else when (app.prefs.hintSize) { "small" -> 0.8f; "large" -> 1.25f; else -> 1f }
        v?.longPressMs = app.prefs.longPressMs.toLong()
        v?.keyAlpha = app.prefs.keyAlpha
        v?.setBackgroundImage(Prefs.bgFile(this).takeIf { app.prefs.bgStamp > 0 }, app.prefs.bgDim, app.prefs.bgBlur)
        v?.oneHanded = app.prefs.oneHanded
        // navigation_mode 2 = gesture navigation; 0 / 1 (3- / 2-button) have no swipe-up to keep away from
        v?.gestureNav = runCatching { android.provider.Settings.Secure.getInt(contentResolver, "navigation_mode") == 2 }.getOrDefault(false)
        v?.postDelayed({ writeDiag() }, 800)
        v?.split = app.prefs.splitLandscape && resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        v?.haptics = app.prefs.haptics
        v?.swipeEnabled = app.prefs.swipe && policy.autocorrect
        v?.hapticStrength = app.prefs.hapticStrength
        v?.heightScale = app.prefs.heightPercent / 100f
        v?.baseRows = letters().rows
        v?.layer = if (policy.numeric) symbolsLayer else letters()
        v?.enterLabel = enterLabel(editorInfo)
        ctl.lettersLayer = v?.layer?.isLetters == true
        app.user.reloadIfChanged()   // the settings screen may have edited the dictionary
        ctl.startInput(ControllerConfig(policy, app.prefs.autocorrect, app.prefs.style, { EngineHolder.spec?.id ?: "none" }, BuildConfig.VERSION_NAME,
            suggestOnly = app.prefs.suggestOnly, confidentOnly = app.prefs.confidentOnly, autoCap = app.prefs.autoCap,
            flagTypos = app.prefs.flagTypos, idlePauseMs = app.prefs.idlePauseMs, recheckEvery = app.prefs.recheckEvery,
            wholeField = app.prefs.wholeField, ctxChars = maxOf(KeyboardController.CTX_CHARS, app.prefs.ctxTokens * 5),
            fixPill = app.prefs.fixPill))
        root?.hidePanel()
        if (!app.prefs.clipboard) clips.clear()
        updateClipChip()
        // cheap when loaded; picks up a model / style change or a finished download
        if (!EngineHolder.loading) EngineHolder.preload(this)
        EngineHolder.applyPerf(app)
        updateStatus(null)
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        root?.hidePanel()
        ctl.finishInput()
        super.onFinishInputView(finishingInput)
    }

    override fun onFinishInput() {
        ctl.finishInput()
        super.onFinishInput()
    }

    override fun onUpdateSelection(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int, candidatesStart: Int, candidatesEnd: Int) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        lastSelStart = if (newSelStart == newSelEnd) newSelStart else -1
        ctl.selectionChanged()
        ctl.cursorMoved(newSelStart, newSelEnd)
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
            KeyType.TO_SYMBOLS -> kv?.layer = symbolsLayer
            KeyType.TO_SYMBOLS2 -> kv?.layer = symbols2Layer
            KeyType.EMOJI -> { showEmoji(); return }
            KeyType.TO_LETTERS -> kv?.layer = letters()
            else -> { ctl.key(key, touch); return }
        }
        ctl.lettersLayer = kv?.layer?.isLetters == true
        if (ctl.lettersLayer) ctl.autoShift() else ctl.shift = Shift.OFF
    }

    override fun onSuggestion(index: Int) = ctl.pickSuggestion(index)

    override fun onOneHandedSwap() {
        val side = if (app.prefs.oneHanded == "left") "right" else "left"
        app.prefs.oneHanded = side
        kv?.oneHanded = side
    }

    override fun onSuggestionLongPress(index: Int) = ctl.longPressSuggestion(index)

    override fun onStripAction() = ctl.stripAction()

    override fun onStripActionLongPress() = ctl.stripActionLongPress()

    override fun onSwipeStart() = ctl.swipeBegin()

    override fun onSwipe(path: List<Touch>) = ctl.swipe(path)

    /** popup choice: typed in place of the pressed key (which never typed) */
    override fun onAlternate(key: KeyDef, text: String, touch: Touch) = ctl.alternate(key, text, touch)

    override fun onClipboard() = showClipboard()

    override fun onEmoji() = showEmoji()

    override fun onCommaLongPress() = showEmoji()

    override fun onPasteChip() {
        val t = clips.recentOffer()?.text ?: return
        ctl.insertText(t)
        clips.consumeOffer()
        updateClipChip()
    }

    override fun onSettings() {
        startActivity(android.content.Intent(this, dev.mostlime.autocorrect.ui.SettingsActivity::class.java)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** the bottom-row numbers for settings -> About -> Copy diagnostics (the settings screen is another process) */
    private fun writeDiag() {
        val v = kv ?: return
        val nav = runCatching { android.provider.Settings.Secure.getInt(contentResolver, "navigation_mode") }.getOrDefault(-1)
        val text = "${v.diag()} navMode=$nav device=${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} sdk=${android.os.Build.VERSION.SDK_INT} " +
            "app=${BuildConfig.VERSION_NAME} model=${EngineHolder.spec?.id} threads=${EngineHolder.sessionThreads}"
        runCatching { java.io.File(filesDir, "diag.txt").writeText(text) }
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
            missing -> "model not downloaded: open Keylime"
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

        fun toJson(v: Any?): JsonElement = EventLog.toJson(v)
    }
}
