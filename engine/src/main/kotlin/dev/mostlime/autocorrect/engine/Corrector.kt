package dev.mostlime.autocorrect.engine

import kotlin.math.max

/** Battery / latency knobs (see README "Battery"). */
data class CorrectorConfig(
    /** LM-score only this many cheap-ranked lexicon candidates (+ typed string + confusion/contraction/split extras). */
    val lmTopK: Int = 6,
    /** Skip the LM when the typed word's cheap score beats every other candidate by at least this (nats). */
    val skipMargin: Double = SKIP_MARGIN,
    /** ... and its spatial fit is within this many nats per letter of a perfect key-centre hit. */
    val skipFitPerLetter: Double = SKIP_FIT_PER_LETTER,
    /** Cheap path (no LM): the typed string gets this bonus so only clear typos get changed. */
    val cheapKeepBias: Double = 0.5,
    /** Only words this frequent (lexicon rank) can skip the LM: a rare word one edit from a common one ("oen" / "eon")
     *  still gets scored. */
    val skipMaxRank: Int = SKIP_MAX_RANK,
    /** Re-check the previous word only when its own decision went through the LM. */
    val recheckOnlyIfScored: Boolean = true,
    /** Extra cost on the typed string when it's a known misspelling (lexicon_clean_removed: thier, alot, ...). 0 = off. */
    val misspellingPenalty: Double = MISSPELLING_PENALTY,
    /** Early exit: when the cheap ranking's leader beats the runner-up by this much, LM-score only the top 2 (+ typed). */
    val adaptiveMargin: Double = ADAPTIVE_MARGIN,
    /** Early exit: skip the re-check when the previous word won its first pass by this much (and isn't a confusion word). */
    val recheckSkipMargin: Double = RECHECK_SKIP_MARGIN,
) {
    companion object {
        const val SKIP_MARGIN = 3.0
        const val SKIP_FIT_PER_LETTER = 0.6
        const val SKIP_MAX_RANK = 20000
        const val ADAPTIVE_MARGIN = 4.0
        const val MISSPELLING_PENALTY = 0.0
        const val RECHECK_SKIP_MARGIN = 6.0
    }
}

/** What the IME knows about the word that was just finished. */
class WordInput(
    /** as typed, with case */
    val typed: String,
    /** real touches in key units, one per typed character; null -> key centres */
    val touches: List<Touch>?,
    /** text before the word up to the end of the previous word (the LM's left context) */
    val left: String,
    /** separator between the previous word and this one (" ", ", ", "" at the start, ...) */
    val sep: String,
)

/** The previous word, for the re-check pass. */
class PrevWord(val slot: Slot, val idx: Int, val left: String, val sep: String, val lmScored: Boolean,
               /** first-pass score margin of the previous word's choice over its runner-up (0 = unknown) */
               val margin: Double = 0.0)

class Recheck(val from: String, val to: String, val idx: Int)

/** The previous word as typed, for merge candidates ("some thing" -> "something"). */
class MergeInput(val prevTyped: String, val prevTouches: List<Touch>?)

/** The previous word + this one read as one word (decode_v2 step C). */
class Merge(val to: String, val best: Double, val keep: Double)

class WordResult(
    /** candidates that were considered (the LM-scored subset when the LM ran) */
    val slot: Slot,
    /** final score per slot candidate */
    val scores: DoubleArray,
    /** LM log-prob per slot candidate (null when the LM didn't run) */
    val lm: DoubleArray?,
    val chosen: Int,
    val out: String,
    /** the LM was skipped because the typed word was a confident exact hit */
    val skipped: Boolean,
    /** no model: spatial + unigram prior only */
    val cheap: Boolean,
    val nScored: Int,
    val nCands: Int,
    val lmMs: Double,
    val recheck: Recheck?,
    val merge: Merge? = null,
    /** new text for the previous word (re-check flip and/or formal punctuation), null = unchanged */
    val prevText: String? = null,
) {
    /** chosen score minus the best other score (first pass) */
    val margin: Double get() {
        var best = Double.NEGATIVE_INFINITY
        for (i in scores.indices) if (i != chosen && scores[i] > best) best = scores[i]
        return if (best == Double.NEGATIVE_INFINITY) 0.0 else scores[chosen] - best
    }
    /** left-context tokens the LM saw (set by the caller from NeuralScorer.lastCtxTokens; -1 = no LM) */
    var ctxTokens: Int = -1
    val changed get() = out != slot.typed
    val lmScored get() = !skipped && !cheap

    /** Suggestions for the strip, best first (no duplicates). */
    fun ranked(n: Int = 3): List<String> {
        val order = slot.cands.indices.sortedByDescending { scores[it] }
        val out = LinkedHashSet<String>()
        for (i in order) { out.add(slot.cands[i]); if (out.size == n) break }
        return out.toList()
    }
}

class Stats {
    var words = 0L
    var lmSkipped = 0L
    var cheapOnly = 0L
    var lmRuns = 0L
    var lmMsTotal = 0.0
}

/**
 * Word-at-a-time corrector for the keyboard. Same scoring as Decoder (corrector.decode), with the battery rules:
 * the LM runs only at word end, only on the top-K cheap candidates, and not at all for confident exact hits.
 */
class Corrector(
    val gen: CandidateGenerator,
    var gen2: CandidateGeneratorV2? = null,
    var config: CorrectorConfig = CorrectorConfig(),
    /** known misspellings (lowercase) -> their correction */
    var misspellings: Map<String, String> = emptyMap(),
) {
    private fun typedBias(s: Slot, bias: Double) =
        if (config.misspellingPenalty != 0.0 && s.typed.lowercase() in misspellings) bias - config.misspellingPenalty else bias

    val lex get() = gen.lex
    val stats = Stats()

    /** Full candidate list for a typed word. */
    fun candidates(typed: String, touches: List<Touch>?): Slot {
        var t = touches?.takeIf { it.size == typed.length } ?: KeyGeom.keyTouches(typed)
        if (t.isEmpty()) t = KeyGeom.keyTouches("a")
        val c = gen2?.generate(t, typed) ?: gen.generate(t, typed)
        return Slot.of(typed, c)
    }

    private fun cheapScore(s: Slot, i: Int): Double {
        val lp = s.logp?.get(i)?.takeUnless { it.isNaN() } ?: OOV_LOGP
        return s.sp[i] + gen.priorW * lp
    }

    /** Cheap suggestions while typing: spatial + unigram prior, typed string kept on near-ties. */
    fun suggest(typed: String, touches: List<Touch>?, n: Int = 3): List<String> {
        val s = candidates(typed, touches)
        val sc = DoubleArray(s.cands.size) { cheapScore(s, it) + if (it == s.typedIdx) config.cheapKeepBias else 0.0 }
        return s.cands.indices.sortedByDescending { sc[it] }.map { s.cands[it] }.distinct().take(n)
    }

    /** Indices to LM-score: top-K cheap-ranked lexicon words + typed + every non-word kind / confusion extra. */
    fun lmSubset(s: Slot): IntArray {
        var k = config.lmTopK
        if (k <= 0 || k >= s.cands.size) return s.cands.indices.toList().toIntArray()
        val ranked = s.cands.indices.filter { s.kind(it) == Kind.WORD && !(s.logp?.get(it)?.isNaN() ?: true) && it != s.typedIdx }
            .sortedByDescending { cheapScore(s, it) }
        // early exit: a clear cheap leader only needs a runner-up for comparison
        if (ranked.size >= 2 && cheapScore(s, ranked[0]) - cheapScore(s, ranked[1]) >= config.adaptiveMargin) k = minOf(k, 2)
        val words = ranked.take(k)
        val keep = HashSet(words)
        if (s.typedIdx >= 0) keep.add(s.typedIdx)
        val tl = s.typed.lowercase()
        val conf = CONFUSIONS[tl] ?: emptySet()
        for (i in s.cands.indices) {
            if (s.kind(i) != Kind.WORD || s.cands[i].lowercase() in conf) keep.add(i)
        }
        return keep.sorted().toIntArray()   // original order -> same argmax tie-breaks as the full slot
    }

    /** True when the LM can't change the outcome much: confident exact lexicon hit, no alternatives worth a look. */
    fun canSkip(s: Slot): Boolean {
        val ti = s.typedIdx
        if (ti < 0) return false
        val tl = s.typed.lowercase()
        val rank = lex.index[tl] ?: return false
        if (rank >= config.skipMaxRank) return false
        if (CONFUSIONS.containsKey(tl)) return false
        if (s.cands.indices.any { s.kind(it) != Kind.WORD }) return false        // contraction / g-drop / split alternatives
        val letters = tl.count { it != '\'' }
        if (letters == 0) return false
        // spatial fit vs. a perfect hit on every key centre
        val perfect = letters * gen.sp.perfectLetter
        if (perfect - s.sp[ti] > config.skipFitPerLetter * letters) return false
        val mine = cheapScore(s, ti)
        for (i in s.cands.indices) if (i != ti && cheapScore(s, i) > mine - config.skipMargin) return false
        return true
    }

    /**
     * Decide one finished word. scorer == null -> cheap path. prev -> re-check pass for the previous word.
     */
    fun correctWord(w: WordInput, scorer: LmScorer?, p: DecodeParams, prev: PrevWord?, allowChange: Boolean = true,
                    merge: MergeInput? = null, formal: Boolean = false): WordResult {
        val t0 = System.nanoTime()
        val mi = if (merge != null) MergeCtx(merge, w.typed, w.touches) else null
        return decideSlot(candidates(w.typed, w.touches), w.left, w.sep, scorer, p, prev, allowChange, t0, mi, formal)
    }

    class MergeCtx(val input: MergeInput, val typed: String, val touches: List<Touch>?)

    private fun touchesOf(typed: String, t: List<Touch>?) = t?.takeIf { it.size == typed.length } ?: KeyGeom.keyTouches(typed)

    fun decideSlot(full: Slot, left: String, sep: String, scorer: LmScorer?, p: DecodeParams, prev: PrevWord?,
                   allowChange: Boolean = true, t0: Long = System.nanoTime(), merge: MergeCtx? = null,
                   formal: Boolean = false): WordResult {
        stats.words++
        if (!allowChange || full.typedIdx < 0 && full.cands.isEmpty()) {
            return keep(full, t0)
        }
        if (scorer == null) {
            stats.cheapOnly++
            val sc = DoubleArray(full.cands.size) { cheapScore(full, it) + if (it == full.typedIdx) typedBias(full, config.cheapKeepBias) else 0.0 }
            var k = 0
            for (i in 1 until sc.size) if (sc[i] > sc[k]) k = i
            return WordResult(full, sc, null, k, full.cands[k], skipped = false, cheap = true, nScored = 0, nCands = full.cands.size,
                lmMs = ms(t0), recheck = null)
        }
        val mergePossible = merge != null && prev != null && sep == " " && gen2?.mergeOk(merge.input.prevTyped, merge.typed) == true
        // formal style needs the LM at every word (capitals, punctuation after the previous word)
        if (!formal && !mergePossible && canSkip(full)) {
            stats.lmSkipped++
            val sc = DoubleArray(full.cands.size) { cheapScore(full, it) }
            return WordResult(full, sc, null, full.typedIdx, full.cands[full.typedIdx], skipped = true, cheap = false, nScored = 0,
                nCands = full.cands.size, lmMs = ms(t0), recheck = null)
        }
        val s = full.subset(lmSubset(full))
        val lp = scorer.score(s.cands.map { LmReq(left, sep + it) })
        val (k, sc) = choose(s, lp, p.lam, typedBias(s, p.bias), p.pen)
        var recheck: Recheck? = null
        var mergeOut: Merge? = null
        val prevConfident = prev != null && prev.margin >= config.recheckSkipMargin &&
            !CONFUSIONS.containsKey(prev.slot.cands[prev.idx].lowercase()) && !CONFUSIONS.containsKey(prev.slot.typed.lowercase())
        if (prev != null && p.margin != null && !prevConfident && (prev.lmScored || !config.recheckOnlyIfScored || mergePossible || formal)) {
            val nxt = sep + s.cands[k]
            val lp2 = scorer.score(prev.slot.cands.map { LmReq(prev.left, prev.sep + it + nxt) })
            val (k2, sc2) = choose(prev.slot, lp2, p.lamRe, p.bias, p.pen)
            if (k2 != prev.idx && sc2[k2] - sc2[prev.idx] > p.margin) recheck = Recheck(prev.slot.cands[prev.idx], prev.slot.cands[k2], k2)
            // C. merge of the previous token and this one (only across a single space)
            val g2 = gen2
            if (merge != null && g2 != null && sep == " " && g2.mergeOk(merge.input.prevTyped, merge.typed)) {
                val mc = g2.mergeCandidates(touchesOf(merge.input.prevTyped, merge.input.prevTouches), merge.input.prevTyped,
                    touchesOf(merge.typed, merge.touches), merge.typed)
                if (mc.isNotEmpty()) {
                    val ms = mc.map { applyCase(it.text, merge.input.prevTyped) }
                    val lpm = scorer.score(ms.map { LmReq(prev.left, prev.sep + it) })
                    val cur = recheck?.idx ?: prev.idx
                    val kd = s.kind(k)
                    val keep = sc2[cur] + s.sp[k] + (if (k == s.typedIdx) p.bias else 0.0) - (if (kd != Kind.WORD) p.pen[kd] ?: 0.0 else 0.0)
                    var best = Double.NEGATIVE_INFINITY
                    var bj = -1
                    for (j in ms.indices) {
                        val v = round3(mc[j].sp) + p.lamRe * lpm[j] - (p.pen["merge"] ?: 0.0)
                        if (v > best) { best = v; bj = j }
                    }
                    if (best > keep) mergeOut = Merge(ms[bj], best, keep)
                }
            }
        }
        var out = s.cands[k]
        var prevText: String? = recheck?.to
        if (formal) {
            // A2. capitals: sentence start, i -> I (pipeline.js decodeV2 with style)
            if (isLower(out.take(1)) && (sentStart(left + sep) || out.split(' ')[0] in I_WORDS)) {
                val lc = scorer.score(listOf(LmReq(left, sep + cap1(out))))[0]
                val kd = s.kind(k)
                val v = s.sp[k] + p.lam * lc + (if (k == s.typedIdx) p.bias else 0.0) - (if (kd != Kind.WORD) p.pen[kd] ?: 0.0 else 0.0) - (p.pen["caps"] ?: 0.0)
                if (v > sc[k]) out = cap1(out)
            }
            // D. punctuation after the previous word
            if (prev != null && mergeOut == null && sep.isNotEmpty() && sep.isBlank()) {
                val po = prevText ?: prev.slot.cands[prev.idx]
                if (po.isNotEmpty() && po.last().let { it in 'a'..'z' || it in 'A'..'Z' }) {
                    val pre = prev.sep + po
                    val reqs = listOf(LmReq(prev.left, pre + sep + out)) +
                        PUNCT.map { (pc, _) -> LmReq(prev.left, pre + pc + sep + (if (pc in SENT_END) cap1(out) else out)) }
                    val lpp = scorer.score(reqs)
                    var best = p.lamRe * lpp[0]
                    var bj = -1
                    PUNCT.forEachIndexed { j, (_, kind) -> val v = p.lamRe * lpp[1 + j] - (p.pen[kind] ?: 0.0); if (v > best) { best = v; bj = j } }
                    if (bj >= 0) {
                        val pc = PUNCT[bj].first
                        prevText = po + pc
                        if (pc in SENT_END && isLower(out.take(1))) out = cap1(out)
                    }
                }
            }
        }
        stats.lmRuns++
        val dt = ms(t0)
        stats.lmMsTotal += dt
        return WordResult(s, sc, lp, k, out, skipped = false, cheap = false, nScored = s.cands.size, nCands = full.cands.size,
            lmMs = dt, recheck = recheck, merge = mergeOut, prevText = prevText)
    }

    private fun keep(full: Slot, t0: Long): WordResult {
        val ti = max(full.typedIdx, 0)
        val sc = DoubleArray(full.cands.size) { if (it == ti) 0.0 else -1e9 }
        return WordResult(full, sc, null, ti, full.cands.getOrElse(ti) { full.typed }, skipped = true, cheap = true, nScored = 0,
            nCands = full.cands.size, lmMs = ms(t0), recheck = null)
    }

    private fun ms(t0: Long) = (System.nanoTime() - t0) / 1e6

    companion object {
        private const val SENT_END = ".?!"
        private val I_WORDS = setOf("i", "i'm", "i'll", "i'd", "i've")
        private val PUNCT = listOf("." to "punct", "?" to "punct", "!" to "punct", "," to "comma")
        private fun cap1(s: String) = s.take(1).uppercase() + s.drop(1)
        private fun isLower(c: String) = c.isNotEmpty() && c != c.uppercase() && c == c.lowercase()
        private fun sentStart(text: String): Boolean {
            val t = text.trimEnd(' ', '\t')
            return t.isEmpty() || t.last() in SENT_END || t.last() == '\n'
        }

        /** log prior for a typed string that isn't in the lexicon (cheap path) */
        const val OOV_LOGP = -20.0
    }
}

/** A word some way back, re-checked with the text typed after it as right context. start = offset in the snapshot. */
class RewindTarget(val start: Int, val word: String, val left: String, val sep: String, val right: String, val wordsBack: Int)

class RewindChange(val start: Int, val from: String, val to: String, val margin: Double, val wordsBack: Int)

/**
 * Confusion-set rewind: when the model is very sure, fix a real-word error up to a few words back using the words typed
 * since (to/too/two, there/their/they're, ...). Only within these fixed sets, only above a high LM margin.
 */
object Rewind {
    val SETS: Map<String, List<String>> = run {
        val groups = listOf(
            listOf("to", "too", "two"), listOf("there", "their", "they're"), listOf("your", "you're"), listOf("its", "it's"),
            listOf("then", "than"), listOf("lose", "loose"), listOf("were", "where", "we're"), listOf("of", "off"), listOf("a", "an"),
        )
        val m = HashMap<String, List<String>>()
        for (g in groups) for (w in g) m[w] = g
        m
    }
    /** LM log-prob margin (x lam_r) the alternative must win by. Conservative by default. */
    const val MARGIN = 6.0
    const val MAX_WORDS_BACK = 3

    fun isTarget(word: String) = word.lowercase() in SETS

    fun check(targets: List<RewindTarget>, scorer: LmScorer, lam: Double, margin: Double = MARGIN): List<RewindChange> {
        val out = ArrayList<RewindChange>()
        for (t in targets) {
            val group = SETS[t.word.lowercase()] ?: continue
            val cands = group.map { applyCase(it, t.word) }
            val cur = group.indexOf(t.word.lowercase())
            val lp = scorer.score(cands.map { LmReq(t.left, t.sep + it + t.right) })
            var best = cur
            for (i in lp.indices) if (lp[i] > lp[best]) best = i
            val m = lam * (lp[best] - lp[cur])
            if (best != cur && m >= margin) out.add(RewindChange(t.start, t.word, cands[best], m, t.wordsBack))
        }
        return out
    }
}
