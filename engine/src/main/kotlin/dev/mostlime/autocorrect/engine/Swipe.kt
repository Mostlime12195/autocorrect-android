package dev.mostlime.autocorrect.engine

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Swipe (glide) typing: SHARK2-style template matching + LM rerank. Port of autocorrect/swipe/{geom,templates,decoder}.py
 * (see swipe/README.md there for the eval and the tuned numbers).
 *
 * decode(path):
 *  1. resample the touch path to N points by arc length (model units: key width 1, row pitch KeyGeom.ROW_H)
 *  2. prune: templates whose first / last key centre lies within R of the first / last touch point (R grows until
 *     >= minKeep survive), then a length check of the input path against the template's ideal length
 *  3. per surviving template: loc = sum_i alpha_i |u_i - t_i|^2 (endpoint-weighted), shape = mean_i |u^_i - t^_i| on
 *     centred, scale-normalised paths
 *  4. per word of the template: geo = -a shape^2 - b loc + wf log P(word); top-K words.
 * rerank: final = -ra shape^2 - rb loc + wf2 log P(word) + lam * LM log P(word | left context).
 */
data class SwipeParams(
    val r: Double = 1.6,
    val rStep: Double = 0.5,
    val rMax: Double = 3.5,
    val minKeep: Int = 200,
    val lenLo: Double = 0.45,
    val lenHi: Double = 1.7,
    val lenAbs: Double = 1.2,
    val a: Double = 16.0,
    val b: Double = 1.0,
    val wf: Double = 0.15,
    val beta: Double = 3.0,
    val floor: Double = 2.0,
    val k: Int = 20,
    // LM rerank weights, tuned on the synthetic dev set with m2 (swipe/out/results_m2.json: dev top-1 79.8% -> 92.1%)
    val ra: Double = 16.0,
    val rb: Double = 2.0,
    val wf2: Double = 0.0,
    val lam: Double = 0.35,
)

class SwipeCand(val word: String, val wordId: Int, val geo: Double, val shape: Double, val loc: Double, val logp: Double)

object SwipeGeom {
    /** Letters of the word (apostrophes etc. dropped), consecutive duplicates collapsed: "hello" -> "helo". */
    fun keySeq(word: String): String {
        val sb = StringBuilder(word.length)
        for (c0 in word) {
            val c = c0.lowercaseChar()
            if (c in 'a'..'z' && (sb.isEmpty() || sb[sb.length - 1] != c)) sb.append(c)
        }
        return sb.toString()
    }

    /** Resample the polyline (xs, ys) to n points equally spaced by arc length (endpoints kept) -> [x0, y0, x1, y1, ...]. */
    fun resample(xs: DoubleArray, ys: DoubleArray, n: Int): DoubleArray {
        val m = xs.size
        val out = DoubleArray(2 * n)
        if (m == 0) return out
        val cum = DoubleArray(m)
        for (i in 1 until m) {
            val dx = xs[i] - xs[i - 1]; val dy = ys[i] - ys[i - 1]
            cum[i] = cum[i - 1] + sqrt(dx * dx + dy * dy)
        }
        val len = cum[m - 1]
        if (m == 1 || len < 1e-9) {
            for (i in 0 until n) { out[2 * i] = xs[0]; out[2 * i + 1] = ys[0] }
            return out
        }
        var j = 0
        for (i in 0 until n) {
            val t = if (i == n - 1) len else len * i / (n - 1)
            while (j < m - 2 && cum[j + 1] <= t) j++
            val seg = cum[j + 1] - cum[j]
            val f = if (seg > 0) ((t - cum[j]) / seg).coerceIn(0.0, 1.0) else 1.0
            out[2 * i] = xs[j] + f * (xs[j + 1] - xs[j])
            out[2 * i + 1] = ys[j] + f * (ys[j + 1] - ys[j])
        }
        return out
    }

    /** Length of an interleaved polyline [x0, y0, x1, y1, ...]. */
    fun pathLength(p: DoubleArray): Double {
        var s = 0.0
        for (i in 1 until p.size / 2) {
            val dx = p[2 * i] - p[2 * i - 2]; val dy = p[2 * i + 1] - p[2 * i - 1]
            s += sqrt(dx * dx + dy * dy)
        }
        return s
    }
}

/**
 * Precomputed templates, built from the lexicon at load time (same algorithm and quantisation as templates.py, so
 * nothing extra ships): one template per key sequence (dont / don't share one), ideal key-centre path resampled to
 * N points, quantised to u8 in 1/Q key widths, sorted by (first key, last key) so each start/end pair is one range.
 * Memory at N = 50 and ~94k templates: ~9.4 MB of points + ~2 MB of index arrays.
 */
class SwipeTemplates private constructor(
    val n: Int,
    val count: Int,
    /** start of bucket (first * 26 + last) in template order; bucket b = [bucket[b], bucket[b + 1]) */
    val bucket: IntArray,
    /** quantised points [count][n][2] */
    val pts: ByteArray,
    /** ideal path length, key widths (u16 / 100 like the Python file) */
    val lens: FloatArray,
    /** CSR: words of template t = twIds[twOff[t] until twOff[t + 1]] (lexicon indices) */
    val twOff: IntArray,
    val twIds: IntArray,
    /** per template: shape-normalisation centroid x, y and 1 / side (precomputed, see SwipeDecoder.normalize) */
    val norm: FloatArray,
    val normFloor: Double,
) {
    companion object {
        const val Q = 25

        fun build(lex: Lexicon, n: Int = 50, floor: Double = SwipeParams().floor): SwipeTemplates {
            val seqs = HashMap<String, MutableList<Int>>(lex.size * 2)
            for (i in 0 until lex.size) {
                val s = SwipeGeom.keySeq(lex.words[i])
                if (s.isNotEmpty()) seqs.getOrPut(s) { ArrayList(1) }.add(i)
            }
            fun li(c: Char) = c - 'a'
            val keys = seqs.keys.sortedWith(compareBy<String>({ li(it[0]) * 26 + li(it[it.length - 1]) }, { it }))
            val t = keys.size
            val pts = ByteArray(t * n * 2)
            val lens = FloatArray(t)
            val bucket = IntArray(26 * 26 + 1)
            val twOff = IntArray(t + 1)
            val twIds = IntArray(seqs.values.sumOf { it.size })
            val norm = FloatArray(t * 3)
            var nw = 0
            for ((ti, s) in keys.withIndex()) {
                val xs = DoubleArray(s.length) { KeyGeom.CX[li(s[it])] }
                val ys = DoubleArray(s.length) { KeyGeom.CY[li(s[it])] }
                val r = SwipeGeom.resample(xs, ys, n)
                for (j in 0 until 2 * n) pts[ti * 2 * n + j] = Math.rint(r[j] * Q).toInt().coerceIn(0, 255).toByte()
                var plen = 0.0
                for (j in 1 until s.length) plen += sqrt((xs[j] - xs[j - 1]).let { it * it } + (ys[j] - ys[j - 1]).let { it * it })
                lens[ti] = (Math.rint(plen * 100) / 100.0).toFloat()
                bucket[li(s[0]) * 26 + li(s[s.length - 1]) + 1]++
                for (w in seqs[s]!!) twIds[nw++] = w
                twOff[ti + 1] = nw
                // normalisation of the dequantised template
                var cx = 0.0; var cy = 0.0
                var x0 = Double.MAX_VALUE; var x1 = -Double.MAX_VALUE; var y0 = Double.MAX_VALUE; var y1 = -Double.MAX_VALUE
                for (j in 0 until n) {
                    val x = (pts[ti * 2 * n + 2 * j].toInt() and 0xff) / Q.toDouble()
                    val y = (pts[ti * 2 * n + 2 * j + 1].toInt() and 0xff) / Q.toDouble()
                    cx += x; cy += y
                    x0 = min(x0, x); x1 = max(x1, x); y0 = min(y0, y); y1 = max(y1, y)
                }
                norm[3 * ti] = (cx / n).toFloat(); norm[3 * ti + 1] = (cy / n).toFloat()
                norm[3 * ti + 2] = (1.0 / max(max(x1 - x0, y1 - y0), floor)).toFloat()
            }
            for (b in 1 until bucket.size) bucket[b] += bucket[b - 1]
            return SwipeTemplates(n, t, bucket, pts, lens, twOff, twIds, norm, floor)
        }
    }
}

/** Decoded swipe: candidates best-first by geometry, the final order (after the LM, if it ran) and timings. */
class SwipeResult(
    val cands: List<SwipeCand>,
    /** LM log-prob per candidate (null: no model / LM failed) */
    val lm: DoubleArray?,
    /** final score per candidate */
    val scores: DoubleArray,
    /** candidate indices, best first */
    val order: IntArray,
    val decodeMs: Double,
    val lmMs: Double,
    val nSurvivors: Int,
) {
    val best: String? get() = order.firstOrNull()?.let { cands[it].word }
    /** geometry-only best (the LM may have overruled it) */
    val geoBest: String? get() = cands.firstOrNull()?.word
    fun ranked(n: Int = 3): List<String> {
        val out = LinkedHashSet<String>()
        for (i in order) { out.add(cands[i].word); if (out.size == n) break }
        return out.toList()
    }
}

class SwipeDecoder(val lex: Lexicon, val p: SwipeParams = SwipeParams(), n: Int = 50) {
    /** personal dictionary: its words are swipe targets too (templates made on the fly, prior UserData.WORD_LOGP) */
    var user: UserData? = null
    val tp: SwipeTemplates = SwipeTemplates.build(lex, n, p.floor)
    private val nPts = tp.n
    private val logp = lex.logp
    private val alpha = DoubleArray(nPts).also { a ->
        val h = (nPts - 1) / 2.0
        var s = 0.0
        for (i in 0 until nPts) { a[i] = 1 + p.beta * abs(i - h) / h; s += a[i] }
        for (i in 0 until nPts) a[i] /= s
    }
    var lastSurvivors = 0
        private set

    /** Template indices that survive the start / end / length pruning. */
    internal fun prune(u: DoubleArray, lIn: Double): IntArray {
        val ds = DoubleArray(26); val de = DoubleArray(26)
        val ux0 = u[0]; val uy0 = u[1]; val ux1 = u[2 * nPts - 2]; val uy1 = u[2 * nPts - 1]
        for (c in 0 until 26) {
            ds[c] = sqrt((KeyGeom.CX[c] - ux0).let { it * it } + (KeyGeom.CY[c] - uy0).let { it * it })
            de[c] = sqrt((KeyGeom.CX[c] - ux1).let { it * it } + (KeyGeom.CY[c] - uy1).let { it * it })
        }
        var r = p.r
        while (true) {
            val out = IntArrayList(1024)
            for (f in 0 until 26) {
                if (ds[f] >= r) continue
                for (l in 0 until 26) {
                    if (de[l] >= r) continue
                    val b = f * 26 + l
                    for (t in tp.bucket[b] until tp.bucket[b + 1]) {
                        val lt = tp.lens[t]
                        if (lIn >= p.lenLo * lt - p.lenAbs && lIn <= p.lenHi * lt + p.lenAbs) out.add(t)
                    }
                }
            }
            if (out.size >= p.minKeep || r >= p.rMax) return out.toArray()
            r += p.rStep
        }
    }

    /** Geometry decode of a touch path (model units). Returns up to k words, best first. */
    fun decode(path: List<Touch>, k: Int = p.k): List<SwipeCand> {
        val pts = path.filter { !it.isApos }
        if (pts.isEmpty()) return emptyList()
        val u = SwipeGeom.resample(DoubleArray(pts.size) { pts[it].x }, DoubleArray(pts.size) { pts[it].y }, nPts)
        val lIn = SwipeGeom.pathLength(u)
        val surv = prune(u, lIn)
        lastSurvivors = surv.size
        // input normalisation
        var cx = 0.0; var cy = 0.0
        var x0 = Double.MAX_VALUE; var x1 = -Double.MAX_VALUE; var y0 = Double.MAX_VALUE; var y1 = -Double.MAX_VALUE
        for (i in 0 until nPts) {
            val x = u[2 * i]; val y = u[2 * i + 1]
            cx += x; cy += y; x0 = min(x0, x); x1 = max(x1, x); y0 = min(y0, y); y1 = max(y1, y)
        }
        cx /= nPts; cy /= nPts
        val inv = 1.0 / max(max(x1 - x0, y1 - y0), p.floor)
        val un = DoubleArray(2 * nPts) { j -> (u[j] - if (j % 2 == 0) cx else cy) * inv }

        // per-word scores; keep the best k with a small min-heap
        val heapIdx = IntArray(k); val heapSc = DoubleArray(k)
        val heapSh = DoubleArray(k); val heapLo = DoubleArray(k)
        var hn = 0
        val q = 1.0 / SwipeTemplates.Q
        val stride = 2 * nPts
        for (t in surv) {
            val base = t * stride
            val tcx = tp.norm[3 * t].toDouble(); val tcy = tp.norm[3 * t + 1].toDouble(); val tinv = tp.norm[3 * t + 2].toDouble()
            var loc = 0.0; var shape = 0.0
            for (i in 0 until nPts) {
                val tx = (tp.pts[base + 2 * i].toInt() and 0xff) * q
                val ty = (tp.pts[base + 2 * i + 1].toInt() and 0xff) * q
                val dx = tx - u[2 * i]; val dy = ty - u[2 * i + 1]
                loc += alpha[i] * (dx * dx + dy * dy)
                val nx = (tx - tcx) * tinv - un[2 * i]; val ny = (ty - tcy) * tinv - un[2 * i + 1]
                shape += sqrt(nx * nx + ny * ny)
            }
            shape /= nPts
            val g0 = -p.a * shape * shape - p.b * loc
            for (wi in tp.twOff[t] until tp.twOff[t + 1]) {
                val w = tp.twIds[wi]
                val sc = g0 + p.wf * logp[w]
                if (hn < k) {
                    heapIdx[hn] = w; heapSc[hn] = sc; heapSh[hn] = shape; heapLo[hn] = loc; hn++
                    siftUp(heapIdx, heapSc, heapSh, heapLo, hn - 1)
                } else if (sc > heapSc[0]) {
                    heapIdx[0] = w; heapSc[0] = sc; heapSh[0] = shape; heapLo[0] = loc
                    siftDown(heapIdx, heapSc, heapSh, heapLo, hn)
                }
            }
        }
        val order = (0 until hn).sortedByDescending { heapSc[it] }
        val out = order.map { j -> SwipeCand(lex.words[heapIdx[j]], heapIdx[j], heapSc[j], heapSh[j], heapLo[j], logp[heapIdx[j]]) }
        val extra = userCands(u, un)
        if (extra.isEmpty()) return out
        val have = out.mapTo(HashSet()) { it.word.lowercase() }
        return (out + extra.filter { it.word.lowercase() !in have }).sortedByDescending { it.geo }.take(k)
    }

    /** Dictionary words scored like templates (same resampling, normalisation and weights; no quantisation). */
    private fun userCands(u: DoubleArray, un: DoubleArray): List<SwipeCand> {
        val words = user?.words().orEmpty()
        if (words.isEmpty()) return emptyList()
        val out = ArrayList<SwipeCand>()
        for (w in words) {
            val seq = SwipeGeom.keySeq(w)
            if (seq.isEmpty()) continue
            val xs = DoubleArray(seq.length) { KeyGeom.CX[seq[it] - 'a'] }
            val ys = DoubleArray(seq.length) { KeyGeom.CY[seq[it] - 'a'] }
            val t = SwipeGeom.resample(xs, ys, nPts)
            // start / end keys must be near the path's ends, like the template pruning
            val ds = sqrt((t[0] - u[0]).let { it * it } + (t[1] - u[1]).let { it * it })
            val de = sqrt((t[2 * nPts - 2] - u[2 * nPts - 2]).let { it * it } + (t[2 * nPts - 1] - u[2 * nPts - 1]).let { it * it })
            if (ds >= p.rMax || de >= p.rMax) continue
            var cx = 0.0; var cy = 0.0
            var x0 = Double.MAX_VALUE; var x1 = -Double.MAX_VALUE; var y0 = Double.MAX_VALUE; var y1 = -Double.MAX_VALUE
            for (i in 0 until nPts) {
                val x = t[2 * i]; val y = t[2 * i + 1]
                cx += x; cy += y; x0 = min(x0, x); x1 = max(x1, x); y0 = min(y0, y); y1 = max(y1, y)
            }
            cx /= nPts; cy /= nPts
            val tinv = 1.0 / max(max(x1 - x0, y1 - y0), p.floor)
            var loc = 0.0; var shape = 0.0
            for (i in 0 until nPts) {
                val dx = t[2 * i] - u[2 * i]; val dy = t[2 * i + 1] - u[2 * i + 1]
                loc += alpha[i] * (dx * dx + dy * dy)
                val nx = (t[2 * i] - cx) * tinv - un[2 * i]; val ny = (t[2 * i + 1] - cy) * tinv - un[2 * i + 1]
                shape += sqrt(nx * nx + ny * ny)
            }
            shape /= nPts
            out.add(SwipeCand(w, -1, -p.a * shape * shape - p.b * loc + p.wf * UserData.WORD_LOGP, shape, loc, UserData.WORD_LOGP))
        }
        return out
    }

    /**
     * Decode + LM rerank. left / sep: the LM context as in WordInput (text up to the end of the previous word, and the
     * separator before the swiped word). casePattern: applied to every candidate (applyCase), e.g. "A" with shift on.
     */
    fun decodeAndRerank(path: List<Touch>, left: String, sep: String, casePattern: String, scorer: LmScorer?): SwipeResult {
        val t0 = System.nanoTime()
        val cands0 = decode(path)
        val cands = cands0.map { c -> SwipeCand(applyCase(c.word, casePattern), c.wordId, c.geo, c.shape, c.loc, c.logp) }
        val t1 = System.nanoTime()
        var lm: DoubleArray? = null
        if (scorer != null && cands.size > 1) {
            lm = try { scorer.score(cands.map { LmReq(left, sep + it.word) }) } catch (e: Exception) { null }
        }
        val t2 = System.nanoTime()
        val scores = DoubleArray(cands.size) { i ->
            val c = cands[i]
            if (lm == null) c.geo else -p.ra * c.shape * c.shape - p.rb * c.loc + p.wf2 * c.logp + p.lam * lm[i]
        }
        val order = cands.indices.sortedByDescending { scores[it] }.toIntArray()
        return SwipeResult(cands, lm, scores, order, (t1 - t0) / 1e6, (t2 - t1) / 1e6, lastSurvivors)
    }

    private fun swap(i: IntArray, s: DoubleArray, a: DoubleArray, b: DoubleArray, x: Int, y: Int) {
        val ti = i[x]; i[x] = i[y]; i[y] = ti
        val ts = s[x]; s[x] = s[y]; s[y] = ts
        val ta = a[x]; a[x] = a[y]; a[y] = ta
        val tb = b[x]; b[x] = b[y]; b[y] = tb
    }

    private fun siftUp(i: IntArray, s: DoubleArray, a: DoubleArray, b: DoubleArray, j0: Int) {
        var j = j0
        while (j > 0) {
            val pj = (j - 1) / 2
            if (s[pj] <= s[j]) break
            swap(i, s, a, b, pj, j); j = pj
        }
    }

    private fun siftDown(i: IntArray, s: DoubleArray, a: DoubleArray, b: DoubleArray, n: Int) {
        var j = 0
        while (true) {
            val l = 2 * j + 1; val r = l + 1
            var m = j
            if (l < n && s[l] < s[m]) m = l
            if (r < n && s[r] < s[m]) m = r
            if (m == j) return
            swap(i, s, a, b, m, j); j = m
        }
    }
}
