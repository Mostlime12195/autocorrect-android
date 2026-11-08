package dev.mostlime.autocorrect.engine

import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.ln1p

/**
 * Keyboard geometry in the units keyboard.py / keyboard.js use: one key width = 1.0, row pitch = ROW_H (1.4 key
 * widths), rows offset 0 / 0.5 / 1.5. The Android layout maps real touches into these units (see ime/KeyGeometry).
 */
object KeyGeom {
    val ROWS = arrayOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
    val ROW_OFF = doubleArrayOf(0.0, 0.5, 1.5)
    const val ROW_H = 1.4
    const val AREA = 10 * 3 * ROW_H
    const val NEG = -1e9

    /** letters a..z in sorted order -> index */
    val L2I = IntArray(128) { -1 }
    val CX = DoubleArray(26)
    val CY = DoubleArray(26)

    init {
        ROWS.forEachIndexed { r, row ->
            row.forEachIndexed { i, ch ->
                val li = ch - 'a'
                L2I[ch.code] = li
                CX[li] = ROW_OFF[r] + i + 0.5
                CY[li] = r * ROW_H + ROW_H / 2
            }
        }
    }

    fun center(c: Char): Touch? {
        val lc = c.lowercaseChar()
        if (lc !in 'a'..'z') return null
        val i = lc - 'a'
        return Touch(CX[i], CY[i])
    }

    /** Key-centre touches for a typed string (apostrophe -> APOS sentinel), like keyboard.key_touches. */
    fun keyTouches(s: String): List<Touch> {
        val out = ArrayList<Touch>(s.length)
        for (c in s) {
            if (c == '\'') out.add(Touch.APOS)
            else center(c)?.let { out.add(it) }
        }
        return out
    }
}

/** One touch in key units. APOS (x < -50) marks an apostrophe typed from a non-letter key. */
data class Touch(val x: Double, val y: Double) {
    val isApos get() = x < -50
    companion object { val APOS = Touch(-100.0, -100.0) }
}

val CONFUSIONS: Map<String, Set<String>> = run {
    val groups = listOf(
        listOf("there", "their", "they're"), listOf("your", "you're"), listOf("its", "it's"), listOf("then", "than"),
        listOf("to", "too"), listOf("from", "form"), listOf("lose", "loose"), listOf("of", "off"),
        listOf("were", "where", "we're"), listOf("affect", "effect"), listOf("quite", "quiet"), listOf("whose", "who's"),
        listOf("accept", "except"), listOf("weather", "whether"), listOf("advice", "advise"), listOf("breath", "breathe"),
        listOf("past", "passed"), listOf("peace", "piece"), listOf("principal", "principle"), listOf("know", "now"),
        listOf("new", "knew"), listOf("right", "write"), listOf("hear", "here"), listOf("brake", "break"),
        listOf("sight", "site"), listOf("wear", "where"), listOf("buy", "by"), listOf("though", "through", "thought"),
        listOf("and", "an"), listOf("in", "on"), listOf("is", "it"), listOf("if", "of"), listOf("are", "our"),
        listOf("we", "were"), listOf("the", "they"), listOf("want", "went"), listOf("dose", "does"), listOf("lead", "led"),
        listOf("who", "how"), listOf("or", "of"), listOf("loose", "lose"), listOf("lightning", "lightening"),
        listOf("definitely", "defiantly"), listOf("angel", "angle"), listOf("desert", "dessert"), listOf("council", "counsel"),
    )
    val m = LinkedHashMap<String, LinkedHashSet<String>>()
    for (g in groups) for (w in g) {
        val s = m.getOrPut(w) { LinkedHashSet() }
        for (x in g) if (x != w) s.add(x)
    }
    m
}

/** numpy's logaddexp */
internal fun lae(x: Double, y: Double): Double {
    if (x == y) return x + LN2
    val t = x - y
    return if (t > 0) x + ln1p(exp(-t)) else y + ln1p(exp(t))
}

private val LN2 = ln(2.0)

fun applyCase(word: String, pattern: String): String {
    val letters = pattern.filter { it in 'A'..'Z' || it in 'a'..'z' }
    if (letters.length > 1 && letters.all { it.isUpperCase() }) return word.uppercase()
    if (letters.isNotEmpty() && letters[0].isUpperCase()) return word.take(1).uppercase() + word.drop(1)
    return word
}

/** Python round(x, 3) */
fun round3(x: Double): Double = BigDecimal(x).setScale(3, RoundingMode.HALF_EVEN).toDouble()

class Lexicon(tsv: String) {
    val words: Array<String>
    val logp: DoubleArray
    val nletters: IntArray
    val bag: FloatArray
    val codes: Array<ByteArray>
    val index: HashMap<String, Int>

    init {
        val ws = ArrayList<String>(100_000)
        val cs = ArrayList<Long>(100_000)
        for (line in tsv.lineSequence()) {
            if (line.isEmpty()) continue
            val t = line.indexOf('\t')
            ws.add(line.substring(0, t)); cs.add(line.substring(t + 1).trim().toLong())
        }
        val n = ws.size
        words = ws.toTypedArray()
        var tot = 0.0
        for (c in cs) tot += c.toDouble()
        logp = DoubleArray(n) { ln(cs[it].toDouble() / tot) }
        nletters = IntArray(n)
        bag = FloatArray(n * 26)
        codes = Array(n) { i ->
            val w = words[i]
            val code = ByteArray(w.length)
            var nl = 0
            for (j in w.indices) {
                if (w[j] == '\'') { code[j] = -1; continue }
                val li = KeyGeom.L2I[w[j].code]
                code[j] = li.toByte(); bag[i * 26 + li] += 1f; nl++
            }
            nletters[i] = nl
            code
        }
        index = HashMap(n * 2)
        for (i in 0 until n) index.putIfAbsent(words[i], i)
    }

    val size get() = words.size
}

class SpatialModel(
    val sigma: Double = 0.3,
    pOmit: Double = 0.01,
    pDouble: Double = 0.006,
    pIns: Double = 0.002,
    pTranspose: Double = 0.006,
    pAposDrop: Double = 0.4,
    val eps: Double = 0.01,
) {
    val lpHit = ln(1 - pOmit - pDouble - pIns)
    val lpOm = ln(pOmit)
    val lpDbl = ln(pDouble)
    val lpIns = ln(pIns)
    val lpTr = ln(pTranspose)
    val lpAposKeep = ln(1 - pAposDrop)
    val lpAposDrop = ln(pAposDrop)
    val lpRand = ln(1.0 / KeyGeom.AREA)

    /** Best possible per-letter emission (touch exactly on the key centre) incl. the hit probability. */
    val perfectLetter: Double = run {
        val s2 = sigma * sigma
        ln((1 - eps) / (2 * PI * s2) + eps / KeyGeom.AREA) + lpHit
    }

    class Emit(val e: Array<DoubleArray>, val isApos: BooleanArray) {
        val n get() = e.size
        fun slice(a: Int, b: Int) = Emit(e.copyOfRange(a, b), isApos.copyOfRange(a, b))
    }

    fun emitTable(touches: List<Touch>): Emit {
        val s2 = sigma * sigma
        val e = Array(touches.size) { i ->
            val t = touches[i]
            if (t.isApos) DoubleArray(26) { KeyGeom.NEG }
            else DoubleArray(26) { j ->
                val dx = t.x - KeyGeom.CX[j]
                val dy = t.y - KeyGeom.CY[j]
                val g = exp(-(dx * dx + dy * dy) / (2 * s2)) / (2 * PI * s2)
                ln((1 - eps) * g + eps / KeyGeom.AREA)
            }
        }
        return Emit(e, BooleanArray(touches.size) { touches[it].isApos })
    }

    /** Forward algorithm over alignments of the touches to one word (code: letter ids, -1 = apostrophe). */
    fun score(em0: Emit, code: ByteArray): Double {
        val E = em0.e
        val isApos = em0.isApos
        val n = E.size
        val m = code.size
        val NEG = KeyGeom.NEG
        val om = DoubleArray(m) { if (code[it] < 0) lpAposDrop else lpOm }
        val em = DoubleArray(n * m)
        val dbl = DoubleArray(n * m)
        val ins = DoubleArray(n)
        for (i in 0 until n) {
            for (j in 0 until m) {
                val ap = code[j] < 0
                val e = E[i][if (ap) 0 else code[j].toInt()]
                em[i * m + j] = if (ap) (if (isApos[i]) lpAposKeep else NEG) else e + lpHit
                dbl[i * m + j] = if (ap) NEG else e + lpDbl
            }
            ins[i] = (if (isApos[i]) NEG else lpRand) + lpIns
        }
        val w = m + 1
        val F = DoubleArray((n + 1) * w) { NEG }
        F[0] = 0.0
        for (j in 1..m) F[j] = F[j - 1] + om[j - 1]
        for (i in 1..n) {
            F[i * w] = F[(i - 1) * w] + ins[i - 1]
            for (j in 1..m) {
                var a = F[(i - 1) * w + j - 1] + em[(i - 1) * m + j - 1]
                a = lae(a, F[i * w + j - 1] + om[j - 1])
                a = lae(a, F[(i - 1) * w + j] + ins[i - 1])
                a = lae(a, F[(i - 1) * w + j] + dbl[(i - 1) * m + j - 1])
                if (i >= 2 && j >= 2) a = lae(a, F[(i - 2) * w + j - 2] + lpTr + em[(i - 2) * m + j - 1] + em[(i - 1) * m + j - 2])
                F[i * w + j] = a
            }
        }
        return F[n * w + m]
    }

    /** null when the word has a character the keyboard model doesn't know (digits, accents, ...). */
    fun wordCode(w: String): ByteArray? {
        val lw = w.lowercase()
        val out = ByteArray(lw.length)
        for (j in lw.indices) {
            val c = lw[j]
            out[j] = when {
                c == '\'' -> -1
                c.code < 128 && KeyGeom.L2I[c.code] >= 0 -> KeyGeom.L2I[c.code].toByte()
                else -> return null
            }
        }
        return out
    }
}

/** Candidate kinds (v2 adds split / space / contr / contr_rw / gdrop; merges are separate). */
object Kind {
    const val WORD = "word"
    const val SPLIT = "split"
    const val SPACE = "space"
    const val CONTR = "contr"
    const val CONTR_RW = "contr_rw"
    const val GDROP = "gdrop"
}

/** A candidate: lowercase text, spatial log-likelihood, kind, and its lexicon prior (NaN if not a lexicon word). */
data class Cand(val text: String, val sp: Double, val kind: String = Kind.WORD, val logp: Double = Double.NaN)

/** numpy float32 pairwise sum of |bag - soft| over the 26 letters (8 accumulators, then the tail). */
private fun bagDist(bag: FloatArray, off: Int, soft: FloatArray): Float {
    val r = FloatArray(8)
    for (j in 0 until 8) r[j] = abs(bag[off + j] - soft[j])
    var i = 8
    while (i < 24) {
        for (j in 0 until 8) r[j] = r[j] + abs(bag[off + i + j] - soft[i + j])
        i += 8
    }
    var res = ((r[0] + r[1]) + (r[2] + r[3])) + ((r[4] + r[5]) + (r[6] + r[7]))
    for (k in 24 until 26) res += abs(bag[off + k] - soft[k])
    return res
}

/** Generates candidates for one typed word. Port of keyboard.CandidateGenerator (k=16, prefilter=1500). */
class CandidateGenerator(val lex: Lexicon, val sp: SpatialModel, val k: Int = 16, val prefilter: Int = 1500, val priorW: Double = 0.5) {
    private val cheap = DoubleArray(lex.size)

    /** The v1 list: top-k lexicon words by spatial + prior_w * log prior, then the typed string and its confusions. */
    fun generate(touches: List<Touch>, typed: String): List<Cand> {
        val emit = sp.emitTable(touches)
        val softD = DoubleArray(26)
        var nL = 0
        for (i in 0 until emit.n) {
            if (emit.isApos[i]) continue
            nL++
            val row = emit.e[i]
            var l = row[0]
            for (j in 1 until 26) l = lae(l, row[j])
            for (j in 0 until 26) softD[j] += exp(row[j] - l)
        }
        val soft = FloatArray(26) { softD[it].toFloat() }
        val n = lex.size
        var nOk = 0
        for (i in 0 until n) {
            val dl = abs(lex.nletters[i] - nL)
            if (dl > 2) { cheap[i] = -1e9; continue }
            cheap[i] = -(bagDist(lex.bag, i * 26, soft).toDouble() + 1.5 * dl) + 0.15 * lex.logp[i]
            nOk++
        }
        val top = topByCheap(nOk)
        val scored = DoubleArray(top.size)
        for (q in top.indices) scored[q] = sp.score(emit, lex.codes[top[q]])
        val order = top.indices.sortedWith { a, b ->
            val ra = scored[a] + priorW * lex.logp[top[a]]
            val rb = scored[b] + priorW * lex.logp[top[b]]
            if (ra != rb) rb.compareTo(ra) else top[a].compareTo(top[b])
        }
        val cands = ArrayList<Cand>(k + 4)
        val names = HashSet<String>()
        for (q in order.take(k)) {
            val w = top[q]
            cands.add(Cand(lex.words[w], scored[q], Kind.WORD, lex.logp[w])); names.add(lex.words[w])
        }
        val tl = typed.lowercase()
        val extra = ArrayList<String>()
        extra.add(tl)
        CONFUSIONS[tl]?.sorted()?.let { extra.addAll(it) }
        for (w in extra) {
            if (w in names) continue
            val code = sp.wordCode(w) ?: continue
            names.add(w)
            cands.add(Cand(w, sp.score(emit, code), Kind.WORD, lex.index[w]?.let { lex.logp[it] } ?: Double.NaN))
        }
        return cands
    }

    /** Indices of the top `prefilter` words by cheap score (ties: lexicon order) -- same set as a full sort. */
    private fun topByCheap(nOk: Int): IntArray {
        val n = lex.size
        val want = minOf(prefilter, nOk)
        if (want == 0) return IntArray(0)
        // threshold = want-th largest cheap value among eligible words
        val vals = DoubleArray(nOk)
        var q = 0
        for (i in 0 until n) if (cheap[i] > -1e8) vals[q++] = cheap[i]
        vals.sort()
        val thr = vals[nOk - want]
        var above = 0
        for (v in vals) if (v > thr) above++
        var tiesLeft = want - above
        val out = IntArray(want)
        var o = 0
        for (i in 0 until n) {
            val c = cheap[i]
            if (c < -1e8) continue
            if (c > thr) out[o++] = i
            else if (c == thr && tiesLeft > 0) { out[o++] = i; tiesLeft-- }
        }
        return out
    }
}

// --- message structure (corrector.segment + typing37k-style word slots)
val SEG = Regex("[A-Za-z]+(?:'[A-Za-z]+)*")

/** -> (seps (n+1), words (n)) */
fun segment(text: String): Pair<List<String>, List<String>> {
    val seps = ArrayList<String>()
    val words = ArrayList<String>()
    var last = 0
    for (m in SEG.findAll(text)) {
        seps.add(text.substring(last, m.range.first)); words.add(m.value); last = m.range.last + 1
    }
    seps.add(text.substring(last))
    return seps to words
}

/** Merge alternatives for a slot (this token read together with the previous one). */
class MergeSlot(val cands: List<String>, val sp: DoubleArray)

/** One word slot of a message: cased candidates, rounded spatial scores, kinds, typed index. */
class Slot(
    val typed: String,
    val cands: List<String>,
    val sp: DoubleArray,
    val typedIdx: Int,
    val kinds: List<String>? = null,
    val logp: DoubleArray? = null,
    var merge: MergeSlot? = null,
) {
    fun kind(i: Int) = kinds?.get(i) ?: Kind.WORD

    /** Sub-slot with only the given candidate indices (typed index remapped; -1 if the typed string was dropped). */
    fun subset(idx: IntArray): Slot = Slot(
        typed, idx.map { cands[it] }, DoubleArray(idx.size) { sp[idx[it]] }, idx.indexOf(typedIdx),
        kinds?.let { k -> idx.map { k[it] } }, logp?.let { l -> DoubleArray(idx.size) { l[idx[it]] } }, merge,
    )

    companion object {
        fun of(typed: String, cands: List<Cand>): Slot {
            val tl = typed.lowercase()
            return Slot(
                typed, cands.map { applyCase(it.text, typed) }, DoubleArray(cands.size) { round3(cands[it].sp) },
                cands.indexOfFirst { it.text == tl }, cands.map { it.kind }, DoubleArray(cands.size) { cands[it].logp },
            )
        }
    }
}
