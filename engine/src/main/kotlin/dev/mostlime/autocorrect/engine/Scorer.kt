package dev.mostlime.autocorrect.engine

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.ByteBuffer
import java.nio.FloatBuffer
import java.nio.LongBuffer

const val MAX_CTX = 64
const val EOT = "\u0003"   // continuation suffix = end of message: score </s> instead of the word boundary

/** One LM request: log P(cont + word boundary | left). */
data class LmReq(val left: String, val cont: String)

fun interface LmScorer {
    fun score(reqs: List<LmReq>): DoubleArray
}

/** Step-graph layout (web/export_step.py), from models.json / the Android manifest. */
data class StepMeta(
    val nKvHeads: Int,
    val headDim: Int,
    val convKernel: Int,
    val dModel: Int,
    val stateNames: List<String>,
)

/** Recurrent state after a token prefix: per-name float data + shape, the attention mask, and last-token log-probs. */
class LmState(val data: Map<String, FloatArray>, val shapes: Map<String, LongArray>, val mask: BooleanArray, val last: FloatArray?)

class StepOutput(val tokLp: FloatArray, val bnd: FloatArray, val n: Int, val state: LmState?)

/** onnxruntime wrapper around one step graph (port of StepModel in web/site/models.js). */
class StepModel(
    private val env: OrtEnvironment,
    private val session: OrtSession,
    val meta: StepMeta,
    @Suppress("unused") private val modelBytes: ByteBuffer? = null,   // must outlive the session (bytes used in place)
) : AutoCloseable {
    @Volatile var closed = false
        private set

    fun fresh(): LmState {
        val d = HashMap<String, FloatArray>()
        val s = HashMap<String, LongArray>()
        for (n in meta.stateNames) {
            if (n[0] == 'k' || n[0] == 'v') {
                d[n] = FloatArray(meta.nKvHeads * meta.headDim); s[n] = longArrayOf(1, meta.nKvHeads.toLong(), 1, meta.headDim.toLong())
            } else {
                d[n] = FloatArray((meta.convKernel - 1) * meta.dModel); s[n] = longArrayOf(1, (meta.convKernel - 1).toLong(), meta.dModel.toLong())
            }
        }
        return LmState(d, s, booleanArrayOf(false), null)
    }

    /**
     * rows: token lists (right-padded with 0). keepState: return the present state + last-token log-probs
     * (only valid for a single row; used for the shared left context).
     */
    fun run(rows: List<IntArray>, pos0: Int, state: LmState, keepState: Boolean): StepOutput {
        check(!closed) { "session closed" }
        val b = rows.size
        val n = rows.maxOf { it.size }
        val ids = LongArray(b * n)
        val nxt = LongArray(b * n)
        rows.forEachIndexed { r, row ->
            for (j in row.indices) {
                ids[r * n + j] = row[j].toLong()
                if (j > 0) nxt[r * n + j - 1] = row[j].toLong()
            }
        }
        val shape = longArrayOf(b.toLong(), n.toLong())
        val tensors = ArrayList<OnnxTensor>()
        try {
            val feeds = HashMap<String, OnnxTensor>()
            fun put(name: String, t: OnnxTensor) { tensors.add(t); feeds[name] = t }
            put("ids", OnnxTensor.createTensor(env, LongBuffer.wrap(ids), shape))
            put("next_ids", OnnxTensor.createTensor(env, LongBuffer.wrap(nxt), shape))
            put("pos", OnnxTensor.createTensor(env, LongBuffer.wrap(LongArray(n) { (pos0 + it).toLong() }), longArrayOf(n.toLong())))
            put("past_mask", OnnxTensor.createTensor(env, state.mask))
            for (nm in meta.stateNames) put("past_$nm", OnnxTensor.createTensor(env, FloatBuffer.wrap(state.data[nm]!!), state.shapes[nm]!!))
            val want = LinkedHashSet<String>().apply {
                add("tok_lp"); add("bnd")
                if (keepState) { add("last_lp"); meta.stateNames.forEach { add("present_$it") } }
            }
            session.run(feeds, want).use { res ->
                fun floats(name: String): FloatArray {
                    val t = res.get(name).get() as OnnxTensor
                    val fb = t.floatBuffer
                    return FloatArray(fb.remaining()).also { fb.get(it) }
                }
                val tokLp = floats("tok_lp")
                val bnd = floats("bnd")
                var st: LmState? = null
                if (keepState) {
                    val d = HashMap<String, FloatArray>()
                    val s = HashMap<String, LongArray>()
                    for (nm in meta.stateNames) {
                        val t = res.get("present_$nm").get() as OnnxTensor
                        s[nm] = t.info.shape
                        val fb = t.floatBuffer
                        d[nm] = FloatArray(fb.remaining()).also { fb.get(it) }
                    }
                    val last = floats("last_lp")   // [B, V]; row 0
                    val v = last.size / b
                    st = LmState(d, s, state.mask + BooleanArray(n) { true }, last.copyOf(v))
                }
                return StepOutput(tokLp, bnd, n, st)
            }
        } finally {
            tensors.forEach { it.close() }
        }
    }

    override fun close() {
        closed = true
        session.close()
    }

    companion object {
        /**
         * Session for a pre-optimized ORT-format graph (android_export/to_ort.py): no runtime graph optimization,
         * initializers used straight from the (memory-mapped) model buffer.
         */
        fun open(env: OrtEnvironment, model: ByteBuffer, meta: StepMeta, threads: Int = 2, ortFormat: Boolean = true,
                 extraConfig: Map<String, String> = emptyMap()): StepModel {
            val opts = OrtSession.SessionOptions()
            opts.setIntraOpNumThreads(threads)
            opts.setInterOpNumThreads(1)
            if (ortFormat) {
                opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.NO_OPT)
                opts.addConfigEntry("session.load_model_format", "ORT")
                if (model.isDirect) {
                    opts.addConfigEntry("session.use_ort_model_bytes_directly", "1")
                    opts.addConfigEntry("session.use_ort_model_bytes_for_initializers", "1")
                }
            } else {
                opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }
            for ((k, v) in extraConfig) opts.addConfigEntry(k, v)
            val s = env.createSession(model, opts)
            return StepModel(env, s, meta, model)
        }
    }
}

/**
 * scorers.NeuralScorer.score() on the step graph (port of Scorer in web/site/models.js): the shared left context runs
 * once (batch 1) and all candidates of the slot run in one batch on top of it.
 *
 * Differences from the browser: the context cache also extends a cached shorter prefix (between words the new context
 * is the old one plus the committed word, so only those tokens run), and an optional style control token goes right
 * after the leading EOS (style.py contract: context = [EOS, STYLE] + encode(left); truncation keeps both in front).
 */
class NeuralScorer(
    val tok: SPTokenizer,
    val step: StepModel,
    var styleId: Int? = null,
    private val cacheSize: Int = 16,
) : LmScorer {
    private val cache = LinkedHashMap<String, Pair<IntArray, LmState>>(32, 0.75f, true)
    var tokensRun = 0L
        private set
    var calls = 0L
        private set

    private fun enc(s: String): IntArray {
        val body = if (s.isEmpty()) IntArray(0) else tok.encode(s)
        return intArrayOf(tok.eosId) + body
    }

    private fun clip(ctx: IntArray): IntArray {
        val sid = styleId ?: return if (ctx.size > MAX_CTX) ctx.copyOfRange(ctx.size - MAX_CTX, ctx.size) else ctx
        // [EOS, STYLE] + last tokens
        val body = ctx.copyOfRange(1, ctx.size)
        val keep = MAX_CTX - 2
        val tail = if (body.size > keep) body.copyOfRange(body.size - keep, body.size) else body
        return intArrayOf(tok.eosId, sid) + tail
    }

    private fun key(t: IntArray, n: Int = t.size): String {
        val sb = StringBuilder(n * 5)
        for (i in 0 until n) sb.append(t[i]).append(',')
        return sb.toString()
    }

    /** State after `toks`: exact cache hit, else extend the longest cached proper prefix, else a fresh run. */
    private fun prefix(toks: IntArray): LmState {
        val k = key(toks)
        cache[k]?.let { return it.second }
        var best: Pair<IntArray, LmState>? = null
        for (e in cache.values) {
            val p = e.first
            if (p.size < toks.size && (best == null || p.size > best.first.size)) {
                var ok = true
                for (i in p.indices) if (p[i] != toks[i]) { ok = false; break }
                if (ok) best = e
            }
        }
        val out: LmState
        if (best != null) {
            val delta = toks.copyOfRange(best.first.size, toks.size)
            out = step.run(listOf(delta), best.first.size, best.second, keepState = true).state!!
            tokensRun += delta.size
        } else {
            out = step.run(listOf(toks), 0, step.fresh(), keepState = true).state!!
            tokensRun += toks.size
        }
        cache[k] = toks to out
        while (cache.size > cacheSize) cache.remove(cache.keys.first())
        return out
    }

    /** Warm the cache with the context of `left` (e.g. right after a word is committed). */
    fun warm(left: String) {
        prefix(clip(enc(left)))
    }

    fun clearCache() = cache.clear()

    override fun score(reqs: List<LmReq>): DoubleArray {
        calls++
        class K(val ctx: IntArray, val tgt: IntArray, val eot: Boolean)
        val keys = reqs.map { r ->
            var cont = r.cont
            val eot = cont.endsWith(EOT)
            if (eot) cont = cont.dropLast(1)
            val lt = enc(r.left)
            val ft = enc(r.left + cont)
            var k = 0
            val n = minOf(lt.size, ft.size - 1)
            while (k < n && lt[k] == ft[k]) k++
            val ctx = clip(ft.copyOfRange(0, k))
            val tgt0 = ft.copyOfRange(k, ft.size)
            K(ctx, if (eot) tgt0 + tok.eosId else tgt0, eot)
        }
        val out = DoubleArray(reqs.size)
        if (keys.isEmpty()) return out
        val ctxs = keys.map { it.ctx }
        var p = 0
        while (ctxs.all { it.size > p } && ctxs.all { it[p] == ctxs[0][p] }) p++
        val pre: LmState = if (p > 0) prefix(ctxs[0].copyOfRange(0, p)) else step.fresh()
        val rems = keys.map { it.ctx.copyOfRange(p, it.ctx.size) + it.tgt }
        val o = step.run(rems, p, pre, keepState = false)
        tokensRun += rems.sumOf { it.size }
        keys.forEachIndexed { b, kk ->
            var s = 0.0
            for (q in kk.tgt.indices) {
                val j = kk.ctx.size - p + q
                s += if (j == 0) pre.last!![kk.tgt[q]].toDouble() else o.tokLp[b * o.n + j - 1].toDouble()
            }
            if (!kk.eot) s += o.bnd[b * o.n + rems[b].size - 1].toDouble()
            out[b] = s
        }
        return out
    }
}
