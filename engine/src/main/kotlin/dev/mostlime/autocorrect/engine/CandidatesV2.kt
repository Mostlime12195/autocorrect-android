package dev.mostlime.autocorrect.engine

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.ln


/**
 * Candidate generation v2: port of keyboard.CandidateGeneratorV2 / web/site/keyboard.js (same iteration orders and
 * tie-breaks so the lists match the Python eval).
 *   split / space : 2-3 lexicon words for one typed token (space omitted / c v b n m touch read as the space)
 *   contr / contr_rw / gdrop : apostrophe and g-dropping expansions ("im" -> "i'm", "fuckin" -> "fucking")
 *   merge candidates: two typed tokens read as one word (decided in the re-check step)
 *
 * spaceBarX: the space bar's x extent in key units. The eval layout uses [3.5, 8.5]; the Android layout passes its own.
 */
class CandidateGeneratorV2(
    val gen: CandidateGenerator,
    splitVocab: Int = 30000,
    shortVocab: Int = 10000,
    common: Int = 3000,
    val segK: Int = 3,
    val maxSplits: Int = 4,
    val minSplit: Int = 3,
    val maxSplit: Int = 24,
    val maxSplit3: Int = 14,
    val spaceBarX: DoubleArray = doubleArrayOf(3.5, 8.5),
) {
    val lex = gen.lex
    val sp = gen.sp
    val V = minOf(splitVocab, lex.size)
    val common: Set<String> = lex.words.take(common).toHashSet()
    private val index = HashMap<String, IntArrayList>()
    private val expand = HashMap<String, MutableList<String>>()
    private val shortIds1: IntArray
    private val shortIds2: IntArray

    init {
        val short = IntArrayList()
        val slen = HashMap<Int, Int>()
        for (i in 0 until V) {
            val w = lex.words[i]
            val s = w.replace("'", "")
            if (i < shortVocab && s.length <= 3 && (s.length > 1 || s in SPLIT_SINGLE)) { short.add(i); slen[i] = s.length }
            if (s.length >= 2) {
                val keys = ArrayList<String>()
                keys.add(s)
                if (s.length >= 3) for (k in s.indices) keys.add(s.substring(0, k) + s.substring(k + 1))
                for (key in keys) {
                    val l = index.getOrPut(key) { IntArrayList(2) }
                    if (l.size == 0 || l[l.size - 1] != i) l.add(i)
                }
            }
            if ('\'' in w) expand.getOrPut(s) { ArrayList(1) }.add(w)
        }
        val sh = short.toArray()
        shortIds1 = sh.filter { slen[it]!! <= 2 }.toIntArray()
        shortIds2 = sh.filter { slen[it]!! <= 3 }.toIntArray()
    }

    private class Seg(val rank: Double, val sp: Double, val id: Int)

    private fun spaceEmit(t: Touch): Double {
        val dx = maxOf(spaceBarX[0] - t.x, 0.0, t.x - spaceBarX[1])
        val dy = t.y - SPACE_BAR_Y
        val s2 = sp.sigma * sp.sigma
        val g = exp(-(dx * dx + dy * dy) / (2 * s2)) / (2 * PI * s2)
        return ln((1 - sp.eps) * g + sp.eps / KeyGeom.AREA)
    }

    private fun lookup(s: String, into: MutableSet<Int>) {
        index[s]?.let { l -> for (q in 0 until l.size) into.add(l[q]) }
    }

    private fun segTop(em: SpatialModel.Emit, typed: String, a: Int, b: Int, memo: HashMap<Int, List<Seg>>): List<Seg> {
        val key = a * 1000 + b
        memo[key]?.let { return it }
        val L = b - a
        val s = typed.substring(a, b)
        val ids: IntArray = when {
            L <= 1 -> shortIds1
            L == 2 -> shortIds2
            else -> {
                val got = HashSet<Int>()
                lookup(s, got)
                for (j in 0 until L) lookup(s.substring(0, j) + s.substring(j + 1), got)
                got.sorted().toIntArray()
            }
        }
        // JS: shortIds[L] for L <= 2 (L = 1 -> ids of length <= 2, L = 2 -> length <= 3)
        val es = em.slice(a, b)
        val pw = gen.priorW
        val out = ids.map { i -> val sc = sp.score(es, lex.codes[i]); Seg(sc + pw * lex.logp[i], sc, i) }
            .sortedWith { x, y -> if (x.rank != y.rank) y.rank.compareTo(x.rank) else x.id.compareTo(y.id) }
            .take(segK)
        memo[key] = out
        return out
    }

    private class Bound(val a: Int, val b: Int, val lp: Double, val space: Boolean)

    /** -> 2-3 word splits of one typed token as Cand(text, sp, kind) */
    fun splits(touches: List<Touch>, typed: String): List<Cand> {
        val tl = typed.lowercase()
        val n = tl.length
        if (n < minSplit || n > maxSplit || !isAlpha(tl) || tl in common || touches.size != n) return emptyList()
        val em = sp.emitTable(touches)
        val bounds = ArrayList<Bound>()
        for (p in 1 until n) bounds.add(Bound(p, p, LP_NOSPACE, false))
        for (k in 1 until n - 1) if (tl[k] in SPACE_NEAR) bounds.add(Bound(k, k + 1, spaceEmit(touches[k]) + sp.lpHit, true))
        val memo = HashMap<Int, List<Seg>>()
        val res = LinkedHashMap<String, Triple<Double, Double, String>>()
        val W = lex.words
        fun add(words: IntArray, rank: Double, spv: Double, space: Boolean) {
            val text = words.joinToString(" ") { W[it] }
            val r = res[text]
            if (r == null || rank > r.first) res[text] = Triple(rank, spv, if (space) Kind.SPACE else Kind.SPLIT)
        }
        for (b1 in bounds) {
            val A = segTop(em, tl, 0, b1.a, memo)
            val B = segTop(em, tl, b1.b, n, memo)
            for (x in A) for (y in B) add(intArrayOf(x.id, y.id), x.rank + b1.lp + y.rank, x.sp + b1.lp + y.sp, b1.space)
            if (n > maxSplit3) continue
            for (b2 in bounds) {
                if (b2.a <= b1.b) continue
                val M = segTop(em, tl, b1.b, b2.a, memo)
                if (M.isEmpty()) continue
                val C = segTop(em, tl, b2.b, n, memo)
                for (x in A) for (m in M) for (c in C)
                    add(intArrayOf(x.id, m.id, c.id), x.rank + b1.lp + m.rank + b2.lp + c.rank, x.sp + b1.lp + m.sp + b2.lp + c.sp, b1.space || b2.space)
            }
        }
        return res.entries.sortedWith { x, y -> if (x.value.first != y.value.first) y.value.first.compareTo(x.value.first) else x.key.compareTo(y.key) }
            .take(maxSplits).map { Cand(it.key, it.value.second, it.value.third) }
    }

    /** the v1 list (labelled) + v2 extras */
    fun generate(touches: List<Touch>, typed: String): List<Cand> {
        val cands = gen.generate(touches, typed)
        val tl = typed.lowercase()
        val exp = if ('\'' in tl) emptyList() else expand[tl].orEmpty()
        val gi = lex.index[tl + "g"]
        val gd = if (tl.endsWith("in") && tl.length >= 4 && gi != null && gi < V) tl + "g" else null
        val ckind = if (lex.index.containsKey(tl)) Kind.CONTR_RW else Kind.CONTR
        val out = ArrayList<Cand>(cands.size + 6)
        for (c in cands) out.add(c.copy(kind = if (c.text in exp) ckind else if (c.text == gd) Kind.GDROP else Kind.WORD))
        val have = cands.map { it.text }.toHashSet()
        val extra = exp.filter { it !in have } + listOfNotNull(gd?.takeIf { it !in have })
        if (extra.isNotEmpty()) {
            val em = sp.emitTable(touches)
            for (w in extra) {
                if (w in have) continue
                val code = sp.wordCode(w) ?: continue
                out.add(Cand(w, sp.score(em, code), if (w == gd) Kind.GDROP else ckind, lex.index[w]?.let { lex.logp[it] } ?: Double.NaN))
                have.add(w)
            }
        }
        for (c in splits(touches, typed)) if (c.text !in have) { out.add(c); have.add(c.text) }
        return out
    }

    fun mergeOk(typed1: String, typed2: String): Boolean {
        val t1 = typed1.lowercase()
        val t2 = typed2.lowercase()
        if (!isAlpha(t1) || !isAlpha(t2) || t1.length + t2.length < 3 || t1.length + t2.length > maxSplit) return false
        return t1 !in common || t2 !in common || lex.index.containsKey(t1 + t2)
    }

    /** two typed tokens read as one word (spurious space): -> [(word, sp)] */
    fun mergeCandidates(touches1: List<Touch>, typed1: String, touches2: List<Touch>, typed2: String, top: Int = 3, slack: Double = 12.0): List<Cand> {
        val t1 = typed1.lowercase()
        val t2 = typed2.lowercase()
        if (!mergeOk(t1, t2)) return emptyList()
        val s = t1 + t2
        val got = HashSet<Int>()
        lookup(s, got)
        for (j in s.indices) lookup(s.substring(0, j) + s.substring(j + 1), got)
        if (got.isEmpty()) return emptyList()
        fun sc1(tc: List<Touch>, w: String) = sp.score(sp.emitTable(tc), sp.wordCode(w)!!)
        val ref = sc1(touches1, t1) + sc1(touches2, t2)
        val em = sp.emitTable(touches1 + touches2)
        val out = ArrayList<Triple<String, Double, Double>>()
        for (i in got.sorted()) {
            val x = sp.score(em, lex.codes[i]) + LP_EXTRA_SPACE
            if (x >= ref - slack) out.add(Triple(lex.words[i], x, x + gen.priorW * lex.logp[i]))
        }
        return out.sortedWith { a, b -> if (a.third != b.third) b.third.compareTo(a.third) else a.first.compareTo(b.first) }
            .take(top).map { Cand(it.first, it.second, Kind.WORD) }
    }

    companion object {
        val SPACE_BAR_Y = 3 * KeyGeom.ROW_H + KeyGeom.ROW_H / 2
        val SPACE_NEAR = setOf('c', 'v', 'b', 'n', 'm')
        val LP_NOSPACE = ln(0.01)
        val LP_EXTRA_SPACE = ln(0.01)
        val SPLIT_SINGLE = setOf("a", "i")
        fun isAlpha(s: String) = s.isNotEmpty() && s.all { it in 'a'..'z' }

    }
}
