package dev.mostlime.autocorrect.ime

import android.content.Context
import android.util.Log
import ai.onnxruntime.OrtEnvironment
import dev.mostlime.autocorrect.App
import dev.mostlime.autocorrect.engine.CandidateGenerator
import dev.mostlime.autocorrect.engine.CandidateGeneratorV2
import dev.mostlime.autocorrect.engine.Corrector
import dev.mostlime.autocorrect.engine.DecodeParams
import dev.mostlime.autocorrect.engine.Lexicon
import dev.mostlime.autocorrect.engine.DeviceCpu
import dev.mostlime.autocorrect.engine.NeuralScorer
import dev.mostlime.autocorrect.engine.SPTokenizer
import dev.mostlime.autocorrect.engine.SpatialModel
import dev.mostlime.autocorrect.engine.StepModel
import dev.mostlime.autocorrect.engine.SwipeDecoder
import dev.mostlime.autocorrect.model.ModelSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.util.concurrent.Executors

/**
 * Process-wide engine: lexicon + candidate generators (loaded once), and the ORT session for the active model.
 * Everything runs on one background thread ("ac-engine"), so the engine needs no locking.
 *
 * Session policy: preload when the IME is created / shown, keep it for the life of the process, release only on
 * critical memory pressure (onTrimMemory RUNNING_CRITICAL / COMPLETE, onLowMemory) and reload lazily after that.
 * Until the session is ready, word-end correction uses the cheap spatial + unigram path.
 */
object EngineHolder {
    private const val TAG = "AcEngine"
    val dispatcher = Executors.newSingleThreadExecutor { r -> Thread(r, "ac-engine").apply { priority = Thread.NORM_PRIORITY } }
        .asCoroutineDispatcher()
    val scope = CoroutineScope(SupervisorJob() + dispatcher)

    /** Space bar extent of the Android layout in key units (KeyboardLayout: x 2.5 .. 7.5). */
    val SPACE_BAR_X = doubleArrayOf(2.5, 7.5)
    @Volatile private var spaceBar = SPACE_BAR_X
    private var layoutKey = "qwerty|2.5"

    /**
     * Keyboard layout change (main thread, at input start): letter centres for the spatial model, the space bar for the
     * space-miss candidates, and the swipe templates (rebuilt lazily from the new centres).
     */
    fun applyLayout(l: Layer) {
        val centers = KeyboardLayout.centers(l)
        val bar = KeyboardLayout.spaceBar(l)
        val key = centers.entries.sortedBy { it.key }.joinToString { "${it.key}${it.value}" } + "|" + bar.joinToString()
        if (key == layoutKey) return
        layoutKey = key
        scope.launch {
            dev.mostlime.autocorrect.engine.KeyGeom.setCenters(centers)
            spaceBar = bar
            corrector?.gen2?.spaceBarX = bar
            swipe = null
        }
    }

    /** ORT intra-op threads: the big cores (up to 4), or 4 on all-little SoCs (DeviceCpu) */
    val THREADS = DeviceCpu.intraOpThreads()

    @Volatile var corrector: Corrector? = null
        private set
    @Volatile var scorer: NeuralScorer? = null
        private set
    /** swipe templates (~12 MB), built from the lexicon on the engine thread the first time swipe typing is used */
    @Volatile var swipe: SwipeDecoder? = null
        private set
    @Volatile var swipeBuildMs = -1L
    @Volatile var spec: ModelSpec? = null
        private set
    @Volatile var params: DecodeParams? = null
        private set
    @Volatile var loading = false
        private set
    @Volatile var lastError: String? = null
        private set

    // timings (ms) for the debug overlay / log
    @Volatile var lexiconMs = -1L
    @Volatile var sessionMs = -1L
    @Volatile var coldLoadMs = -1L          // process start -> session ready (first load in this process)
    @Volatile var firstInferenceMs = -1.0
    @Volatile var lastInferenceMs = -1.0
    @Volatile var lastScored = 0
    private var firstLoadDone = false

    /** Session creation runs here, not on the engine thread, so the cheap path keeps working while a model loads. */
    private val loaderScope = CoroutineScope(SupervisorJob() + Executors.newSingleThreadExecutor { r -> Thread(r, "ac-loader") }.asCoroutineDispatcher())

    /** Async, idempotent: lexicon (engine thread), then the active model's session (loader thread). */
    fun preload(ctx: Context) {
        val app = ctx.applicationContext as App
        scope.launch {
            val c = ensureLexicon(app)
            // templates take ~1-2 s to build: on the loader thread, so word-end corrections don't queue behind it
            if (app.prefs.swipe && swipe == null) loaderScope.launch { buildSwipe(c) }
        }
        loaderScope.launch { ensureLoaded(app) }
    }

    /** Engine thread: the lexicon + candidate generators (needed by the cheap path too). */
    fun ensureLexicon(app: App): Corrector {
        corrector?.let { return it }
        run {
            val t0 = App.now()
            val lex = Lexicon(app.assets.open("lexicon.tsv").bufferedReader().use { it.readText() })
            val gen = CandidateGenerator(lex, SpatialModel(sigma = 0.3))
            val miss = runCatching {
                app.assets.open("misspellings.tsv").bufferedReader().readLines().mapNotNull { l -> l.split('\t').takeIf { it.size >= 2 }?.let { it[0] to it[1] } }.toMap()
            }.getOrDefault(emptyMap())
            corrector = Corrector(gen, CandidateGeneratorV2(gen, spaceBarX = spaceBar), misspellings = miss, user = app.user)
            lexiconMs = App.now() - t0
            Log.i(TAG, "lexicon ${lex.size} words in $lexiconMs ms")
        }
        return corrector!!
    }

    private val swipeLock = Any()

    /** Engine thread: the swipe decoder (templates from the same lexicon as the corrector); waits for a build in progress. */
    fun ensureSwipe(app: App): SwipeDecoder = swipe ?: buildSwipe(ensureLexicon(app))

    /** Any thread (the lexicon is immutable once built). */
    private fun buildSwipe(c: Corrector): SwipeDecoder = synchronized(swipeLock) {
        swipe?.let { return it }
        val t0 = App.now()
        val d = SwipeDecoder(c.gen.lex).also { it.user = c.user }
        swipeBuildMs = App.now() - t0
        Log.i(TAG, "swipe templates ${d.tp.count} in $swipeBuildMs ms")
        swipe = d
        d
    }

    /** Loader thread: the active model's session (no-op when it is already loaded). Swapped in on the engine thread. */
    private suspend fun ensureLoaded(app: App) {
        val want = runCatching { app.store.activeSpec() }.getOrNull() ?: return
        val cur = spec
        if (cur != null && cur.id == want.id && cur.graph.sha256 == want.graph.sha256 && scorer?.step?.closed == false) {
            withContext(dispatcher) { applyStyle(app) }; return
        }
        if (!app.store.isInstalled(want)) return
        loading = true
        try {
            val t0 = App.now()
            val tok = SPTokenizer.fromJson(app.store.file(want.vocab).readText())
            val f = app.store.file(want.graph)
            val buf = RandomAccessFile(f, "r").use { it.channel.map(FileChannel.MapMode.READ_ONLY, 0, it.length()) }
            val env = OrtEnvironment.getEnvironment()
            val step = StepModel.open(env, buf, want.meta, threads = THREADS,
                ortFormat = want.format == "ort", extraConfig = want.sessionConfig)
            withContext(dispatcher) {
                release()
                scorer = NeuralScorer(tok, step)
                spec = want
                sessionMs = App.now() - t0
                if (!firstLoadDone) { coldLoadMs = App.now() - App.processStart; firstLoadDone = true }
                firstInferenceMs = -1.0
                lastError = null
                applyStyle(app)
            }
            Log.i(TAG, "session ${want.id} ready in $sessionMs ms (cold load $coldLoadMs ms since process start), tree=${step.isTree}, threads $THREADS [${DeviceCpu.describe()}]")
        } catch (e: Throwable) {
            lastError = e.toString()
            Log.e(TAG, "session load failed", e)
        } finally {
            loading = false
        }
    }

    /** Style picker -> control token (only for models with supports_style) + that style's decode params. */
    private fun applyStyle(app: App) {
        val s = scorer ?: return
        val sp = spec ?: return
        val style = app.prefs.style
        val id = sp.styleToken(style)
        if (s.styleId != id) { s.styleId = id; s.clearCache() }
        params = sp.paramsFor(style)
    }

    /** After a download or a settings change. */
    fun reloadIfNeeded(ctx: Context) = preload(ctx)

    fun release() {
        scorer?.step?.close()
        scorer = null
        spec = null
    }

    /** onTrimMemory(RUNNING_CRITICAL / COMPLETE) / onLowMemory: drop the session, keep the (small) lexicon. */
    fun releaseAsync() {
        scope.launch { release(); Log.i(TAG, "session released (memory pressure)") }
    }
}
