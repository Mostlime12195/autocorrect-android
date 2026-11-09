package dev.mostlime.autocorrect.engine

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.LongBuffer

/** default left context (tokens, incl. EOS / style) */
const val MAX_CTX = 64
/** positions the step graphs have RoPE for (cos / sin tables of m2 and m1 are [1024, 32]) */
const val MAX_POS = 1024
/** room kept for what's scored after the context (candidate + next word, a sentence check's right context) */
const val POS_MARGIN = 48
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

class TreeOutput(val edgeLp: FloatArray, val bnd: FloatArray, val state: LmState?)

/** onnxruntime wrapper around one step graph (port of StepModel in web/site/models.js). */
class StepModel(
    private val env: OrtEnvironment,
    private val session: OrtSession,
    val meta: StepMeta,
    @Suppress("unused") private val modelBytes: ByteBuffer? = null,   // must outlive the session (bytes used in place)
) : AutoCloseable {
    @Volatile var closed = false
        private set

    /** timing counters (benchmarks / debugging): runs, wall ns in run(), ns inside session.run, rows x padded length */
    class Counters { var runs = 0L; var totalNs = 0L; var ortNs = 0L; var cells = 0L; var usedTokens = 0L; var headRows = 0L }
    val counters = Counters()

    /**
     * Tree graph (android_export/tree_graph.py): one row of tokens laid out as a token tree (context delta + every
     * candidate), attention / conv over ancestors, the vocab head only on the rows that need it. Older graphs take
     * padded batches (run()).
     */
    val isTree: Boolean = session.inputNames.contains("tree_mask")

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
        val tStart = System.nanoTime()
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
            val tOrt = System.nanoTime()
            val result = session.run(feeds, want)
            counters.ortNs += System.nanoTime() - tOrt
            counters.runs++; counters.cells += (b * n).toLong(); counters.usedTokens += rows.sumOf { it.size }.toLong()
            result.use { res ->
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
            counters.totalNs += System.nanoTime() - tStart
        }
    }

    /**
     * Tree run. toks/parent: nodes in an order where parents come first, parent -1 = the last past position.
     * Nodes 0 until trunk are a chain (the context delta); with trunk > 0 the state after the trunk is returned, with
     * last = full log-probs at head row lastNode (which must be node trunk - 1).
     * Returns edge log-probs log p(edgeTok[e] | path to headNodes[edgeNode[e]]) and the boundary log-prob per head row.
     */
    fun runTree(toks: IntArray, parent: IntArray, pos0: Int, state: LmState, headNodes: IntArray, edgeNode: IntArray,
                edgeTok: IntArray, trunk: Int, lastNode: Int): TreeOutput {
        check(!closed) { "session closed" }
        val tStart = System.nanoTime()
        val n = toks.size
        val k = meta.convKernel
        val depth = IntArray(n)
        val mask = ByteBuffer.allocateDirect(n * n).order(ByteOrder.nativeOrder())
        val cidx = LongArray(n * k)
        for (i in 0 until n) {
            val pi = parent[i]
            depth[i] = if (pi < 0) 0 else depth[pi] + 1
            var a = i
            while (a >= 0) { mask.put(i * n + a, 1); a = parent[a] }
            // conv window over the (past tail ++ nodes) buffer: node j at K-1+j, then ancestors, then tail K-2, K-3, ...
            var x = k - 1 + i
            for (q in k - 1 downTo 0) {
                cidx[i * k + q] = x.toLong()
                x = if (x >= k - 1) { val p = parent[x - (k - 1)]; if (p >= 0) k - 1 + p else k - 2 } else x - 1
            }
        }
        val tensors = ArrayList<OnnxTensor>()
        try {
            val feeds = HashMap<String, OnnxTensor>()
            fun put(name: String, t: OnnxTensor) { tensors.add(t); feeds[name] = t }
            fun longs(name: String, v: LongArray, shape: LongArray) = put(name, OnnxTensor.createTensor(env, LongBuffer.wrap(v), shape))
            fun ints(name: String, v: IntArray) = longs(name, LongArray(v.size) { v[it].toLong() }, longArrayOf(v.size.toLong()))
            longs("ids", LongArray(n) { toks[it].toLong() }, longArrayOf(1, n.toLong()))
            longs("pos", LongArray(n) { (pos0 + depth[it]).toLong() }, longArrayOf(n.toLong()))
            put("past_mask", OnnxTensor.createTensor(env, state.mask))
            for (nm in meta.stateNames) put("past_$nm", OnnxTensor.createTensor(env, FloatBuffer.wrap(state.data[nm]!!), state.shapes[nm]!!))
            put("tree_mask", OnnxTensor.createTensor(env, mask, longArrayOf(n.toLong(), n.toLong()), OnnxJavaType.BOOL))
            longs("conv_idx", cidx, longArrayOf(n.toLong(), k.toLong()))
            ints("head_nodes", headNodes)
            ints("edge_node", edgeNode)
            ints("edge_tok", edgeTok)
            longs("last_node", longArrayOf(lastNode.toLong()), longArrayOf(1))
            val keep = trunk > 0
            val want = LinkedHashSet<String>().apply {
                add("edge_lp"); add("bnd")
                if (keep) { add("last_lp"); meta.stateNames.forEach { add("present_$it") } }
            }
            val tOrt = System.nanoTime()
            val result = session.run(feeds, want)
            counters.ortNs += System.nanoTime() - tOrt
            counters.runs++; counters.cells += n.toLong(); counters.usedTokens += n.toLong(); counters.headRows += headNodes.size.toLong()
            result.use { res ->
                fun floats(name: String): FloatArray {
                    val fb = (res.get(name).get() as OnnxTensor).floatBuffer
                    return FloatArray(fb.remaining()).also { fb.get(it) }
                }
                val edgeLp = floats("edge_lp")
                val bnd = floats("bnd")
                var st: LmState? = null
                if (keep) {
                    // keep the trunk: K/V slots 0 until P + trunk, conv buffer slots trunk until trunk + K - 1
                    val d = HashMap<String, FloatArray>()
                    val s = HashMap<String, LongArray>()
                    val p = state.mask.size
                    for (nm in meta.stateNames) {
                        val t = res.get("present_$nm").get() as OnnxTensor
                        val fb = t.floatBuffer
                        if (nm[0] == 'k' || nm[0] == 'v') {
                            val hd = meta.headDim
                            val out = FloatArray(meta.nKvHeads * (p + trunk) * hd)
                            for (h in 0 until meta.nKvHeads) {
                                fb.position(h * (p + n) * hd)
                                fb.get(out, h * (p + trunk) * hd, (p + trunk) * hd)
                            }
                            d[nm] = out; s[nm] = longArrayOf(1, meta.nKvHeads.toLong(), (p + trunk).toLong(), hd.toLong())
                        } else {
                            val dm = meta.dModel
                            val out = FloatArray((k - 1) * dm)
                            fb.position(trunk * dm)
                            fb.get(out, 0, (k - 1) * dm)
                            d[nm] = out; s[nm] = longArrayOf(1, (k - 1).toLong(), dm.toLong())
                        }
                    }
                    st = LmState(d, s, state.mask + BooleanArray(trunk) { true }, floats("last_lp"))
                }
                return TreeOutput(edgeLp, bnd, st)
            }
        } finally {
            tensors.forEach { it.close() }
            counters.totalNs += System.nanoTime() - tStart
        }
    }

    override fun close() {
        closed = true
        session.close()
    }

    companion object {
        /**
         * Session for a pre-optimized ORT-format graph (android_export/to_ort.py): no runtime graph optimization (all
         * levels already applied at conversion, on arm64), initializers used straight from the (memory-mapped) model
         * buffer. threads: DeviceCpu.intraOpThreads() on Android.
         */
        fun open(env: OrtEnvironment, model: ByteBuffer, meta: StepMeta, threads: Int = 2, ortFormat: Boolean = true,
                 extraConfig: Map<String, String> = emptyMap(), spinning: Boolean = false): StepModel {
            val opts = OrtSession.SessionOptions()
            opts.setIntraOpNumThreads(threads)
            opts.setInterOpNumThreads(1)
            // worker threads block instead of spin-waiting: the keyboard always shares the CPU with the app in front
            // (and its own UI thread), and a spinning pool whose workers lose their core is slower, not faster
            // (2 threads on a loaded 2-core box: 113 ms/word without spinning vs 156 ms with)
            opts.addConfigEntry("session.intra_op.allow_spinning", if (spinning) "1" else "0")
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
    private var cacheSize: Int = 16,
) : LmScorer {
    /**
     * Left context in tokens (64 .. MAX_POS - POS_MARGIN). Longer contexts are clipped in chunks of maxCtx / 4 from the
     * front, so the window start only moves now and then and the cached prefix (KV state) keeps being extended instead
     * of recomputed for every word. The prefix cache shrinks with it (each entry holds the whole KV state).
     */
    var maxCtx: Int = MAX_CTX
        set(v) {
            val c = v.coerceIn(16, MAX_POS - POS_MARGIN)
            if (c != field) { field = c; cacheSize = (16 * MAX_CTX / c).coerceIn(4, 16); clearCache() }
        }
    private val cache = LinkedHashMap<String, Pair<IntArray, LmState>>(32, 0.75f, true)
    var tokensRun = 0L
        private set
    var calls = 0L
        private set
    /** longest context (tokens, incl. EOS / style; at most MAX_CTX) scored since the caller last reset it */
    var lastCtxTokens = 0

    private fun enc(s: String): IntArray {
        val body = if (s.isEmpty()) IntArray(0) else tok.encode(s)
        return intArrayOf(tok.eosId) + body
    }

    private fun dropFor(size: Int, keep: Int) = Companion.dropFor(size, keep)

    private fun clip(ctx: IntArray): IntArray {
        val sid = styleId ?: return dropFor(ctx.size, maxCtx).let { d -> if (d == 0) ctx else ctx.copyOfRange(d, ctx.size) }
        // [EOS, STYLE] + last tokens
        val body = ctx.copyOfRange(1, ctx.size)
        val d = dropFor(body.size, maxCtx - 2)
        val tail = if (d == 0) body else body.copyOfRange(d, body.size)
        return intArrayOf(tok.eosId, sid) + tail
    }

    private fun key(t: IntArray, n: Int = t.size): String {
        val sb = StringBuilder(n * 5)
        for (i in 0 until n) sb.append(t[i]).append(',')
        return sb.toString()
    }

    /** Longest cached prefix of `toks` (the exact entry if cached), or null. */
    private fun cached(toks: IntArray): Pair<IntArray, LmState>? {
        cache[key(toks)]?.let { return it }
        var best: Pair<IntArray, LmState>? = null
        for (e in cache.values) {
            val p = e.first
            if (p.size < toks.size && (best == null || p.size > best.first.size)) {
                var ok = true
                for (i in p.indices) if (p[i] != toks[i]) { ok = false; break }
                if (ok) best = e
            }
        }
        return best
    }

    private fun remember(toks: IntArray, st: LmState) {
        cache[key(toks)] = toks to st
        while (cache.size > cacheSize) cache.remove(cache.keys.first())
    }

    /** State after `toks`: exact cache hit, else extend the longest cached proper prefix, else a fresh run. */
    private fun prefix(toks: IntArray): LmState {
        val best = cached(toks)
        if (best != null && best.first.size == toks.size) return best.second
        val out: LmState
        if (step.isTree) {
            out = tree(toks, best, emptyList()).state!!
        } else if (best != null) {
            val delta = toks.copyOfRange(best.first.size, toks.size)
            out = step.run(listOf(delta), best.first.size, best.second, keepState = true).state!!
            tokensRun += delta.size
        } else {
            out = step.run(listOf(toks), 0, step.fresh(), keepState = true).state!!
            tokensRun += toks.size
        }
        remember(toks, out)
        return out
    }

    /** One scoring branch for the tree path: tokens after the shared context, which of them are targets, boundary or not. */
    private class Branch(val seq: IntArray, val tgtFrom: Int, val bnd: Boolean)

    private class TreeResult(val state: LmState?, val scores: DoubleArray)

    /**
     * Tree graph: one call for the context delta (toks beyond the cached prefix `base`) and every branch.
     * Score of a branch = sum of log p(seq[q]) for q >= tgtFrom (+ boundary after the last token).
     */
    private fun tree(toks: IntArray, base: Pair<IntArray, LmState>?, branches: List<Branch>): TreeResult {
        val b0 = base?.first?.size ?: 0
        val st0 = base?.second ?: step.fresh()
        val trunk = toks.size - b0
        val nodeTok = ArrayList<Int>(trunk + branches.size * 3)
        val nodePar = ArrayList<Int>(trunk + branches.size * 3)
        for (j in 0 until trunk) { nodeTok.add(toks[b0 + j]); nodePar.add(j - 1) }
        val root = trunk - 1                                   // -1: candidates hang off the cached state directly
        val child = HashMap<Long, Int>()
        val head = LinkedHashMap<Int, Int>()                   // node -> head row
        fun h(node: Int) = head.getOrPut(node) { head.size }
        val edgeNode = ArrayList<Int>(); val edgeTok = ArrayList<Int>()
        // per branch: edge indices (or -1 - tok for "from the cached last_lp") and the boundary head row (or -1)
        val bEdges = ArrayList<IntArray>(branches.size)
        val bBnd = IntArray(branches.size) { -1 }
        for ((bi, br) in branches.withIndex()) {
            var cur = root
            val e = IntArray(br.seq.size - br.tgtFrom)
            for ((q, t) in br.seq.withIndex()) {
                if (q >= br.tgtFrom) {
                    if (cur < 0) e[q - br.tgtFrom] = -1 - t
                    else { e[q - br.tgtFrom] = edgeNode.size; edgeNode.add(h(cur)); edgeTok.add(t) }
                }
                val key = (cur.toLong() shl 32) or (t.toLong() and 0xffffffffL)
                cur = child.getOrPut(key) { nodeTok.add(t); nodePar.add(cur); nodeTok.size - 1 }
            }
            bEdges.add(e)
            if (br.bnd) bBnd[bi] = h(cur)
        }
        val lastRow = if (trunk > 0) h(trunk - 1) else 0
        if (head.isEmpty()) h(nodeTok.size - 1)
        val out = step.runTree(nodeTok.toIntArray(), nodePar.toIntArray(), b0, st0, head.keys.toIntArray(),
            edgeNode.toIntArray(), edgeTok.toIntArray(), trunk, lastRow)
        tokensRun += nodeTok.size
        val scores = DoubleArray(branches.size) { bi ->
            var s = 0.0
            for (e in bEdges[bi]) s += if (e < 0) st0.last!![-1 - e].toDouble() else out.edgeLp[e].toDouble()
            if (bBnd[bi] >= 0) s += out.bnd[bBnd[bi]].toDouble()
            s
        }
        return TreeResult(out.state, scores)
    }

    /** Warm the cache with the context of `left` (e.g. right after a word is committed). */
    fun warm(left: String) {
        prefix(clip(enc(left)))
    }

    fun clearCache() = cache.clear()

    companion object {
        /** front tokens to drop so `size` fits `keep`: a multiple of the chunk (stable window start between jumps) */
        fun dropFor(size: Int, keep: Int): Int {
            if (size <= keep) return 0
            if (keep <= MAX_CTX) return size - keep   // short windows: the old exact clip (cheap to recompute)
            val chunk = maxOf(8, keep / 4)
            return ((size - keep + chunk - 1) / chunk) * chunk
        }
    }

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
        lastCtxTokens = maxOf(lastCtxTokens, keys.maxOf { it.ctx.size })
        val ctxs = keys.map { it.ctx }
        var p = 0
        while (ctxs.all { it.size > p } && ctxs.all { it[p] == ctxs[0][p] }) p++
        if (step.isTree) {
            // context delta + all candidates in one call; the state after the shared context is cached
            val shared = ctxs[0].copyOfRange(0, p)
            val base = cached(shared)
            val exact = base != null && base.first.size == shared.size
            val r = tree(shared, base, keys.map { kk ->
                Branch(kk.ctx.copyOfRange(p, kk.ctx.size) + kk.tgt, kk.ctx.size - p, !kk.eot)
            })
            if (!exact) remember(shared, r.state!!)
            return r.scores
        }
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
