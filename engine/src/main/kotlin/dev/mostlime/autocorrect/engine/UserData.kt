package dev.mostlime.autocorrect.engine

import java.io.File
import kotlin.math.abs
import kotlin.math.min

/**
 * What the keyboard learned from this user, kept on the phone only:
 *  - the personal dictionary (case-preserving words that are never corrected, flagged or rewound away, and are offered
 *    as candidates for near-miss typos)
 *  - suppressions: an edit (typed -> chosen) the user undid that isn't a spelling fix (punctuation, capitals, confusable
 *    swaps, rewinds, multi-word edits). It then needs SUPPRESS_NATS more score for SUPPRESS_DAYS.
 *  - spelling undo counts: the same spelling fix undone twice -> the typed word joins the dictionary.
 *
 * Files: dict = one word per line; learn = TSV "s<TAB>from<TAB>to<TAB>expiry_ms" / "u<TAB>word<TAB>count".
 * Thread-safe (the keyboard's main thread and the engine thread both read it). reloadIfChanged() picks up edits made
 * by the other process (settings screen / keyboard).
 */
class UserData(private val dictFile: File?, private val learnFile: File?, private val now: () -> Long = System::currentTimeMillis) {
    private val words = LinkedHashMap<String, String>()          // lowercase -> as added
    private val suppress = HashMap<String, Long>()               // "from\u0001to" (lowercase) -> expiry
    private val undos = HashMap<String, Int>()                   // lowercase typed -> spelling undo count
    private var dictStamp = -1L
    private var learnStamp = -1L

    init { reloadIfChanged() }

    @Synchronized fun reloadIfChanged() {
        val ds = stamp(dictFile)
        if (ds != dictStamp) {
            words.clear()
            dictFile?.takeIf { it.exists() }?.readLines()?.forEach { l -> val w = l.trim(); if (valid(w)) words[w.lowercase()] = w }
            dictStamp = ds
        }
        val ls = stamp(learnFile)
        if (ls != learnStamp) {
            suppress.clear(); undos.clear()
            learnFile?.takeIf { it.exists() }?.readLines()?.forEach { l ->
                val f = l.split('\t')
                when {
                    f.size == 4 && f[0] == "s" -> f[3].toLongOrNull()?.let { suppress[f[1] + SEP + f[2]] = it }
                    f.size == 3 && f[0] == "u" -> f[2].toIntOrNull()?.let { undos[f[1]] = it }
                }
            }
            learnStamp = ls
        }
    }

    private fun stamp(f: File?) = if (f == null || !f.exists()) 0L else f.lastModified() * 31 + f.length()

    // ------------------------------------------------------------------ dictionary
    @Synchronized fun has(word: String): Boolean = word.isNotEmpty() && words.containsKey(word.lowercase())
    @Synchronized fun words(): List<String> = words.values.toList()
    val size @Synchronized get() = words.size

    /** -> false if the word isn't valid (letters, digits, apostrophe, hyphen; at most MAX_LEN) */
    @Synchronized fun add(word: String): Boolean {
        val w = word.trim()
        if (!valid(w)) return false
        words[w.lowercase()] = w
        undos.remove(w.lowercase())
        saveDict(); saveLearn()
        return true
    }

    @Synchronized fun remove(word: String) { if (words.remove(word.trim().lowercase()) != null) saveDict() }

    @Synchronized fun addAll(ws: Collection<String>): Int {
        var n = 0
        for (w0 in ws) { val w = w0.trim(); if (valid(w) && !words.containsKey(w.lowercase())) { words[w.lowercase()] = w; n++ } }
        if (n > 0) saveDict()
        return n
    }

    /** dictionary + learned edits gone (delete my data / clear all) */
    @Synchronized fun clearAll() {
        words.clear(); suppress.clear(); undos.clear()
        dictFile?.delete(); learnFile?.delete()
        dictStamp = 0L; learnStamp = 0L
    }

    @Synchronized fun clearWords() { words.clear(); saveDict() }

    /**
     * Dictionary words near a typed word (same length +-2, edit distance <= 2), for the candidate list. Words with
     * characters the spatial model can't score (digits, hyphens) are only protected, never suggested.
     */
    @Synchronized fun near(typed: String): List<String> {
        if (words.isEmpty()) return emptyList()
        val t = typed.lowercase()
        return words.values.filter { w -> val l = w.lowercase(); l != t && abs(l.length - t.length) <= 2 && editDistance(l, t, 2) <= 2 }
    }

    // ------------------------------------------------------------------ learned edits
    /** extra score the edit typed -> chosen needs (0 unless the user undid it recently) */
    @Synchronized fun penalty(typed: String, chosen: String): Double {
        if (suppress.isEmpty()) return 0.0
        val exp = suppress[typed.lowercase() + SEP + chosen.lowercase()] ?: return 0.0
        return if (exp > now()) SUPPRESS_NATS else 0.0
    }

    @Synchronized fun suppress(typed: String, chosen: String) {
        val t = now()
        suppress.entries.removeAll { it.value <= t }
        suppress[typed.lowercase() + SEP + chosen.lowercase()] = t + SUPPRESS_DAYS * 86_400_000L
        saveLearn()
    }

    /** a spelling fix of `typed` was undone -> how many times so far (the caller adds the word at UNDOS_TO_ADD) */
    @Synchronized fun noteSpellingUndo(typed: String): Int {
        val k = typed.lowercase()
        val n = (undos[k] ?: 0) + 1
        undos[k] = n
        saveLearn()
        return n
    }

    private fun saveDict() {
        val f = dictFile ?: return
        f.parentFile?.mkdirs()
        val tmp = File(f.path + ".tmp")
        tmp.writeText(words.values.joinToString("\n", postfix = if (words.isEmpty()) "" else "\n"))
        tmp.renameTo(f)
        dictStamp = stamp(f)
    }

    private fun saveLearn() {
        val f = learnFile ?: return
        f.parentFile?.mkdirs()
        val t = now()
        val sb = StringBuilder()
        for ((k, v) in suppress) if (v > t) { val (a, b) = k.split(SEP); sb.append("s\t").append(a).append('\t').append(b).append('\t').append(v).append('\n') }
        for ((k, v) in undos) sb.append("u\t").append(k).append('\t').append(v).append('\n')
        val tmp = File(f.path + ".tmp")
        tmp.writeText(sb.toString())
        tmp.renameTo(f)
        learnStamp = stamp(f)
    }

    companion object {
        const val MAX_LEN = 48
        const val SUPPRESS_NATS = 3.0
        const val SUPPRESS_DAYS = 7L
        const val UNDOS_TO_ADD = 2
        /** unigram log-prior for dictionary words offered as candidates (a moderately rare lexicon word) */
        const val WORD_LOGP = -11.0
        private const val SEP = "\u0001"
        private val VALID = Regex("^[\\p{L}\\p{N}'’-]{1,$MAX_LEN}$")

        fun valid(w: String) = VALID.matches(w) && w.any { it.isLetterOrDigit() }

        /** Levenshtein distance, stopping early above `max` */
        fun editDistance(a: String, b: String, max: Int): Int {
            if (abs(a.length - b.length) > max) return max + 1
            var prev = IntArray(b.length + 1) { it }
            var cur = IntArray(b.length + 1)
            for (i in 1..a.length) {
                cur[0] = i
                var best = cur[0]
                for (j in 1..b.length) {
                    cur[j] = min(min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
                    best = min(best, cur[j])
                }
                if (best > max) return max + 1
                val t = prev; prev = cur; cur = t
            }
            return prev[b.length]
        }
    }
}
