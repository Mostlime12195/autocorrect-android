package dev.mostlime.autocorrect.engine

/** A likely typo found by the sentence pass: [start, start + from.length) of the sentence, best fixes first. */
class SentenceFix(val start: Int, val from: String, val to: String, val gap: Double, val fixes: List<String>)

/** One sentence of the field text with the text before it (LM context). */
class SentenceReq(val left: String, val sentence: String)

/**
 * Suggest-only mode's idle pass: every word of a sentence re-scored with the rest of the sentence as right context (no
 * 3-word window), so a fix that needs later words still shows up. Nothing is edited; the caller marks the fixes.
 */
object SentenceCheck {
    /** right context per word (words): enough for grammar, bounded cost */
    const val RIGHT_WORDS = 8
    const val MAX_WORDS = 40

    /** sentence ranges of text: up to and including . ! ? (and following quotes/brackets) or a newline */
    fun split(text: String): List<IntRange> {
        val out = ArrayList<IntRange>()
        var s = 0
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '\n' || c == '.' || c == '!' || c == '?') {
                var e = i
                while (e + 1 < text.length && text[e + 1] in ".!?\"')]") e++
                out.add(s..e); s = e + 1; i = e
            }
            i++
        }
        if (s < text.length) out.add(s until text.length)
        return out.filter { r -> text.substring(r).any { it.isLetter() } }
    }

    /** -> fixes for the words of req.sentence whose best candidate beats the word as typed by minGap */
    fun check(c: Corrector, req: SentenceReq, scorer: LmScorer, p: DecodeParams, minGap: Double,
              cancelled: () -> Boolean = { false }): List<SentenceFix> {
        val words = SEG.findAll(req.sentence).take(MAX_WORDS).toList()
        val out = ArrayList<SentenceFix>()
        for (i in words.indices) {
            if (cancelled()) break
            checkWord(c, req, words, i, scorer, p, minGap)?.let { out.add(it) }
        }
        return out
    }

    /** one word of the sentence (words = SEG matches of req.sentence); a caller can run words as separate jobs */
    fun checkWord(c: Corrector, req: SentenceReq, words: List<MatchResult>, i: Int, scorer: LmScorer, p: DecodeParams, minGap: Double): SentenceFix? {
        val w = words[i]
        val user = c.user
        if (user != null && user.has(w.value)) return null
        val full = c.candidates(w.value, null)
        if (full.typedIdx < 0 || c.canSkip(full)) return null
        val s = full.subset(c.lmSubset(full))
        val ti = s.typedIdx
        if (ti < 0 || s.cands.size < 2) return null
        val before = req.left + req.sentence.substring(0, w.range.first)
        val lastWord = SEG.findAll(before).lastOrNull()
        val left = if (lastWord == null) "" else before.substring(0, lastWord.range.last + 1)
        val sep = before.substring(left.length)
        val endRight = if (i + RIGHT_WORDS < words.size) words[i + RIGHT_WORDS].range.first else req.sentence.length
        val right = req.sentence.substring(w.range.last + 1, endRight).trimEnd()
        val lp = scorer.score(s.cands.map { LmReq(left, sep + it + right) })
        val (k0, sc) = choose(s, lp, p.lamRe, p.bias, p.pen)
        var k = k0
        if (user != null && k != ti) { val pen = user.penalty(s.typed, s.cands[k]); if (pen > 0) { sc[k] -= pen; k = sc.indices.maxBy { sc[it] } } }
        val gap = sc[k] - sc[ti]
        if (k == ti || gap < minGap) return null
        val fixes = s.cands.indices.sortedByDescending { sc[it] }.map { s.cands[it] }.filter { it.lowercase() != w.value.lowercase() }.distinct().take(3)
        return if (fixes.isEmpty()) null else SentenceFix(w.range.first, w.value, s.cands[k], gap, fixes)
    }
}
