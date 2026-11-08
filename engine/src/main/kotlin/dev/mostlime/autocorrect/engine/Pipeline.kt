package dev.mostlime.autocorrect.engine

/**
 * Decode parameters (web/dist/params.json "e"): score(c) = spatial(c) + lam * LM(sep + c | left) + bias * [c typed]
 * - pen[kind]; the re-check of the previous word uses lam_r and flips when it beats the current choice by > margin.
 */
data class DecodeParams(
    val lam: Double,
    val bias: Double,
    val margin: Double? = 0.0,
    val lamR: Double? = null,
    val pen: Map<String, Double> = emptyMap(),
) {
    val lamRe get() = lamR ?: lam
}

class Message(val seps: List<String>, val words: List<Slot>)

/** -> (k, scores): np.argmax (first max) of sp + lam * lm + bias at the typed index - kind penalties */
fun choose(w: Slot, lm: DoubleArray, lam: Double, bias: Double, pen: Map<String, Double> = emptyMap()): Pair<Int, DoubleArray> {
    val sc = DoubleArray(w.cands.size) { w.sp[it] + lam * lm[it] }
    if (w.typedIdx >= 0) sc[w.typedIdx] += bias
    for (j in sc.indices) { val k = w.kind(j); if (k != Kind.WORD) sc[j] -= pen[k] ?: 0.0 }
    var k = 0
    for (i in 1 until sc.size) if (sc[i] > sc[k]) k = i
    return k to sc
}

/** Port of corrector.decode() / pipeline.js decode() for one message (system e: first pass + re-check). */
object Decoder {
    class Result(val outs: List<String>, val idx: IntArray)

    private fun render(seps: List<String>, outs: Array<String?>, t: Int): String {
        val sb = StringBuilder()
        for (i in 0 until t) sb.append(seps[i]).append(outs[i])
        return sb.toString()
    }

    fun decode(msg: Message, scorer: LmScorer, p: DecodeParams): Result {
        val T = msg.words.size
        val outs = arrayOfNulls<String>(T)
        val idx = IntArray(T)
        for (t in 0 until T) {
            val w = msg.words[t]
            val left = render(msg.seps, outs, t)
            val lp = scorer.score(w.cands.map { LmReq(left, msg.seps[t] + it) })
            val (k, _) = choose(w, lp, p.lam, p.bias, p.pen)
            idx[t] = k; outs[t] = w.cands[k]
            if (p.margin != null && t > 0) {
                val wp = msg.words[t - 1]
                val leftp = render(msg.seps, outs, t - 1)
                val nxt = msg.seps[t] + outs[t]
                val lp2 = scorer.score(wp.cands.map { LmReq(leftp, msg.seps[t - 1] + it + nxt) })
                val (k2, sc2) = choose(wp, lp2, p.lamRe, p.bias, p.pen)
                val cur = idx[t - 1]
                if (k2 != cur && sc2[k2] - sc2[cur] > p.margin) { idx[t - 1] = k2; outs[t - 1] = wp.cands[k2] }
            }
        }
        return Result(outs.map { it!! }, idx)
    }
}
