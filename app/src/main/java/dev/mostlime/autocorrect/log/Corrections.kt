package dev.mostlime.autocorrect.log

import java.text.Normalizer
import java.time.Instant
import java.time.ZoneOffset

/**
 * Opt-in upload events (schema autocorrect-events/3, DATA.md in the autocorrect repo): one scrubbed, word-level event
 * per correction decision. Nothing here touches Android, so it's unit-tested on the JVM.
 *
 * [CorrectionLog] turns the keyboard's local events (KeyboardController.write) plus a few hooks into these events:
 * it waits for the outcome of a decision (kept / undone / picked / ignored ...), adds at most 2 words before and 1 after,
 * scrubs everything and hands the finished event to [sink]. The controller never calls it for sensitive fields.
 *
 * With the separate "Include sentence context" opt-in (and only then) an event also carries ctx_before / ctx_after:
 * the rest of the current sentence around the word, clipped and scrubbed by [Ctx].
 */
object Scrub {
    const val MAX_TOKEN = 20
    private const val EXTRA = "'’-."

    /** one word: 1..20 chars, a letter, only letters / combining marks / ' ’ - . (no digits, @, ://, spaces, symbols) */
    fun tokenOk(t: String): Boolean {
        if (t.isEmpty() || t.length > MAX_TOKEN || '@' in t || "://" in t || t.none { it.isLetter() }) return false
        return t.all { it.isLetter() || it in EXTRA || Character.getType(it).let { ty -> ty == Character.NON_SPACING_MARK.toInt() || ty == Character.COMBINING_SPACING_MARK.toInt() || ty == Character.ENCLOSING_MARK.toInt() } }
    }

    /** a word slot: 1..maxTokens allowed tokens joined by single spaces ("a lot") */
    fun wordOk(w: String?, maxTokens: Int): Boolean {
        if (w == null) return false
        val parts = w.split(' ')
        return parts.size in 1..maxTokens && parts.all(::tokenOk)
    }

    /** a raw context token -> trimmed, lowercased token, or null if it must be dropped */
    fun contextToken(raw: String): String? {
        if (raw.any { it.isDigit() } || '@' in raw || "://" in raw) return null
        val t = raw.trim { !it.isLetterOrDigit() }.lowercase()
        return t.takeIf(::tokenOk)
    }

    private val WS = Regex("\\s+")

    /** the 2 tokens right before the word (same line), each scrubbed; dropped ones aren't replaced by older words */
    fun before(text: String?): List<String> {
        if (text.isNullOrEmpty()) return emptyList()
        val line = text.substringAfterLast('\n')
        return line.split(WS).filter { it.isNotEmpty() }.takeLast(2).mapNotNull(::contextToken)
    }

    /** the token right after the word (same line), scrubbed */
    fun after(text: String?): List<String> {
        if (text.isNullOrEmpty()) return emptyList()
        val line = text.substringBefore('\n')
        return line.split(WS).firstOrNull { it.isNotEmpty() }?.let(::contextToken)?.let { listOf(it) } ?: emptyList()
    }
}

/**
 * Sentence context for the "Include sentence context" opt-in (DATA.md "Sentence context"). The same rules, in the same
 * order, as the reference in the autocorrect repo (collect/server.py ctx_token, collect/ctx_ref.py); CorrectionsTest
 * replays that reference's vectors (ctx_vectors.json).
 *
 * Context = the current sentence only, on the word's own line, at most 24 words before and 12 after. Every token that
 * could identify someone or is a number, code or key is replaced by a placeholder before anything is queued.
 */
object Ctx {
    const val MAX_BEFORE = 24
    const val MAX_AFTER = 12
    const val MAX_CTX_TOKEN = 32
    val PLACEHOLDERS = setOf("<num>", "<email>", "<url>", "<handle>", "<tok>")
    private const val PUNCT = "'\u2019\u2018\"\u201c\u201d-\u2013\u2014.,!?;:()\u2026&\u00bf\u00a1\u00ab\u00bb"
    private const val NEWLINES = "\n\r\u000b\u000c\u0085\u2028\u2029"
    private const val SENTENCE_END = ".!?\u2026\u3002\uff01\uff1f"
    private const val CLOSERS = "\"'\u201d\u2019)]}\u00bb"
    private const val VOWELS = "aeiouyAEIOUY"

    private fun Char.cat() = category.code[0]
    private fun Char.letter() = cat() == 'L'
    private fun Char.mark() = cat() == 'M'
    private fun Char.digit() = cat() == 'N'

    /** one whitespace-separated token -> the token itself, or the placeholder that is sent instead of it */
    fun token(t: String): String {
        if (t.isEmpty() || t.any { it.isWhitespace() }) return "<tok>"
        val core = t.trim { it in PUNCT }
        val at = core.indexOf('@')
        if (at > 0 && at < core.length - 1) return "<email>"
        if ("://" in t || core.lowercase().startsWith("www.")) return "<url>"
        val host = core.substringBefore('/')
        if ('.' in host) {
            val labels = host.split('.')
            val last = labels.last()
            if (labels.all { it.isNotEmpty() } && last.length >= 2 && last.all { it.letter() } &&
                labels.dropLast(1).all { l -> l.all { it.letter() || it.digit() || it == '-' || it == '_' } }) return "<url>"
        }
        val lead = t.trimStart { it in PUNCT }
        if (lead.length >= 2 && (lead[0] == '@' || lead[0] == '#') && (lead[1].letter() || lead[1].digit() || lead[1] == '_')) return "<handle>"
        val hasDigit = t.any { it.digit() }
        val hasLetter = t.any { it.letter() }
        if (hasDigit) return if (hasLetter) "<tok>" else "<num>"
        if (core.length > Scrub.MAX_TOKEN || t.length > MAX_CTX_TOKEN) return "<tok>"
        if (!t.all { it.letter() || it.mark() || it in PUNCT }) return "<tok>"
        if (!hasLetter) return if (t.length <= 3) t else "<tok>"   // punctuation on its own (a dash, an ellipsis)
        // letters that look like a code or key
        val letters = core.filter { it.letter() }
        var ups = 0
        for (i in 1 until letters.length) if (letters[i - 1].isLowerCase() && letters[i].isUpperCase()) ups++
        if (ups >= 3) return "<tok>"
        if (core.count { it == '-' } >= 2 && letters.none { it.isLowerCase() }) return "<tok>"
        if (letters.length >= 6 && letters.all { it.code < 128 } && letters.none { it in VOWELS }) return "<tok>"
        return t
    }

    private fun split(s: String): List<String> {
        val out = ArrayList<String>()
        var i = 0
        while (i < s.length) {
            while (i < s.length && s[i].isWhitespace()) i++
            val a = i
            while (i < s.length && !s[i].isWhitespace()) i++
            if (i > a) out.add(s.substring(a, i))
        }
        return out
    }

    fun endsSentence(tok: String): Boolean {
        val t = tok.trimEnd { it in CLOSERS }
        return t.isNotEmpty() && t.last() in SENTENCE_END
    }

    /** tokens -> what is sent ("" = nothing): placeholders in, runs of <num> collapsed, nothing unless a real word is left */
    fun scrub(toks: List<String>): String {
        val out = ArrayList<String>()
        for (t in toks) {
            val s = token(t)
            if (s == "<num>" && out.lastOrNull() == "<num>") continue
            out.add(s)
        }
        if (out.none { it !in PLACEHOLDERS && it.any { c -> c.letter() } }) return ""
        return out.joinToString(" ")
    }

    /**
     * ctx_before from the text right before the word. truncated = [left] is a window that may start in the middle of a
     * word (its first token is then left out unless a sentence or line start is found after it).
     */
    fun before(left: String?, truncated: Boolean = false): String {
        if (left.isNullOrEmpty()) return ""
        val cut = left.indexOfLast { it in NEWLINES }
        var line = left.substring(cut + 1)
        if (line.isNotEmpty() && !line.last().isWhitespace()) {   // stuck to the word (an opening quote, "well-"): not context
            var i = line.length
            while (i > 0 && !line[i - 1].isWhitespace()) i--
            line = line.substring(0, i)
        }
        val toks = split(line)
        var start = 0
        for ((i, t) in toks.withIndex()) if (endsSentence(t)) start = i + 1
        if (start == 0 && truncated && cut < 0) start = 1
        return scrub(toks.drop(start).takeLast(MAX_BEFORE))
    }

    /** the tokens of ctx_after and whether the sentence (or line) ended in [right], so waiting for more text is pointless */
    private fun afterTokens(right: String, truncated: Boolean): Pair<List<String>, Boolean> {
        val end = right.indexOfFirst { it in NEWLINES }
        var line = if (end >= 0) right.substring(0, end) else right
        if (line.isNotEmpty() && !line[0].isWhitespace()) {   // stuck to the word ("," "." "'s")
            var i = 0
            while (i < line.length && !line[i].isWhitespace()) i++
            if (endsSentence(line.substring(0, i))) return emptyList<String>() to true
            line = line.substring(i)
        }
        val out = ArrayList<String>()
        var done = false
        for (t in split(line)) {
            out.add(t)
            if (endsSentence(t)) { done = true; break }
        }
        if (!done && truncated && end < 0 && out.isNotEmpty()) out.removeAt(out.size - 1)
        return out.take(MAX_AFTER) to (done || end >= 0 || out.size >= MAX_AFTER)
    }

    /** ctx_after from the text right after the word (truncated: a window that may end in the middle of a word) */
    fun after(right: String?, truncated: Boolean = false): String =
        if (right.isNullOrEmpty()) "" else scrub(afterTokens(right, truncated).first)

    /** nothing typed later can add to ctx_after: the sentence or line ended, or 12 words are there */
    fun afterDone(right: String): Boolean = afterTokens(right, false).second

    /** a ctx value as the server accepts it (the last check before an event is queued) */
    fun ok(v: String, max: Int): Boolean {
        if (v.isEmpty() || '\n' in v) return false
        val toks = v.split(' ')
        if (toks.size > max) return false
        var prev: String? = null
        var words = 0
        for (t in toks) {
            if (t in PLACEHOLDERS) { if (t == "<num>" && prev == "<num>") return false }
            else if (token(t) != t) return false
            else if (t.any { it.letter() }) words++
            prev = t
        }
        return words > 0
    }
}

object Kinds {
    /** letters only, lowercase, accents removed: what's left when punctuation / capitals / accents are ignored */
    fun fold(s: String): String = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).filter { it.isLetter() }

    /** grammar (rewind confusables), punctuation (same letters), real_word (typed is a known word), else spelling */
    fun classify(typed: String, fix: String, rewind: Boolean, inVocab: (String) -> Boolean): String = when {
        rewind -> "grammar"
        fold(typed) == fold(fix) -> "punctuation"
        inVocab(typed) -> "real_word"
        else -> "spelling"
    }

    fun marginBucket(m: Double): String = when {
        m.isNaN() -> "0-1"
        m < 1 -> "0-1"
        m < 2 -> "1-2"
        m < 4 -> "2-4"
        m < 8 -> "4-8"
        else -> "8+"
    }
}

class CorrectionLog(
    private val sink: (Map<String, Any?>) -> Unit,
    private val inVocab: (String) -> Boolean = { true },
    private val isProbation: (String) -> Boolean = { false },
    private val now: () -> Long = System::currentTimeMillis,
    private val platform: String = "android",
    /** the "Include sentence context" opt-in (only true while sharing is on too); read for every event */
    private val context: () -> Boolean = { false },
    /** size of the left_context window in the keyboard's local events (a full window may start mid-word) */
    private val ctxWindow: Int = 240,
) {
    /**
     * Set by the controller for each field: false in fields where even an opted-in user's sentence is never taken
     * (FieldPolicy.noContext: names, addresses, no-suggestion fields ...). Events there stay word-level.
     */
    var fieldContext = true

    /** a decision waiting for its outcome and/or the word after it */
    private class Item(
        val id: String, val typed: String, val suggestion: String, val alternatives: List<String>, val kind: String,
        val margin: String?, val before: List<String>, val model: String, val appVersion: String,
        /** word counter of this word (null = unknown, then no "after" word) */
        var seq: Long?, var after: List<String>?,
        /** used when the session ends with no outcome (null = no event) */
        val fallback: String?,
        /** sentence context taken when the decision was made (null = not opted in / not allowed in this field) */
        val ctxBefore: String?,
        /** ctx_after when the text after the word was known at decision time; else it comes from the words typed next */
        val ctxAfter: String?,
        val wantCtx: Boolean,
    ) {
        var outcome: String? = null
        var pickedRank: Int? = null
        var final: String? = null
        var day: String = ""
    }

    private val items = LinkedHashMap<String, Item>()

    /** recent finished words of this field, by counter (their current text) */
    private val ring = LinkedHashMap<Long, String>()
    /** what was typed right after each of those words (" ", ". ", "\n" ...): only used for sentence context */
    private val seps = HashMap<Long, String>()
    private var nextSeq = 0L

    // ------------------------------------------------------------------ hooks from the controller

    /** a word was finished (typed, picked or swiped) */
    fun word(w: String, sep: String = " ") {
        seps[nextSeq] = sep
        ring[nextSeq++] = w
        while (ring.size > RING) ring.keys.first().let { ring.remove(it); seps.remove(it) }
        emitReady(false)
    }

    /** text typed outside a word (a space after a full stop, digits, symbols): part of what follows the last word */
    fun between(text: String) {
        val last = ring.keys.lastOrNull() ?: return
        val cur = seps[last] ?: ""
        // "\b" first = the space before it was replaced (a swiped word's auto space moving behind the punctuation)
        if (cur.length < MAX_SEP) seps[last] = if (text.startsWith('\b')) cur.trimEnd(' ') + text.drop(1) else cur + text
    }

    /** the last finished word was reopened (backspace undo): the next word() is the same slot */
    fun reopen() {
        val last = ring.keys.lastOrNull() ?: return
        ring.remove(last); seps.remove(last)
        nextSeq = last
    }

    /** an undone auto-fix's verdict: what the user finished the reopened word as (null = unknown, e.g. cursor moved) */
    fun undoResolved(id: String?, now: String?) {
        val it = items[id ?: return] ?: return
        if (it.outcome != null) return
        when {
            now == null -> items.remove(id)
            now == it.typed -> resolve(it, "auto_applied_undone")
            // backspace, then the same fix typed again: an accidental backspace (quality guard), no event
            now == it.suggestion -> items.remove(id)
            else -> { it.final = now; resolve(it, "manual_fix") }
        }
    }

    /** the cursor jumped / the text changed under us: words after this point aren't "the next word" */
    fun breakChain() {
        emitReady(true)
        ring.clear(); seps.clear()
        nextSeq++   // the next word is never counted as "right after" a word from before the jump
    }

    /** the field lost focus: everything gets its fallback outcome and goes out */
    fun flush() {
        for (it in items.values.toList()) if (it.outcome == null) { if (it.fallback != null) resolve(it, it.fallback, emit = false) else items.remove(it.id) }
        emitReady(true)
        items.clear()
        ring.clear(); seps.clear()
    }

    // ------------------------------------------------------------------ the keyboard's local events

    /** a local (v1) event from KeyboardController.write */
    fun onEvent(e: Map<String, Any?>) {
        val id = e["id"] as? String ?: return
        when (e["type"]) {
            "autocorrect" -> onWord(id, e)
            "strip_pick" -> onTypingPick(id, e)
            "rewind" -> onRewind(id, e)
            "not_typo" -> (e["ref"] as? String)?.let { ref -> items[ref]?.let { if (it.outcome == null) resolve(it, "not_a_typo") } }
            "suppress" -> {
                val ref = e["ref"] as? String ?: return
                val it = items[ref] ?: return
                if (it.outcome != null) return
                when (e["source"]) {
                    "ignore" -> resolve(it, "suggestion_ignored")
                    "rewind" -> resolve(it, "auto_applied_undone")
                }
            }
        }
    }

    private fun onWord(id: String, e: Map<String, Any?>) {
        val outcome = e["outcome"] as? String ?: return
        if (e["merge"] != null) return   // two words joined: not a word-level decision
        val existing = items[id]
        if (existing != null) {
            if (existing.outcome != null) return
            when (outcome) {
                "strip_pick" -> pick(existing, e["final"] as? String)
                "undone" -> if (e["via"] == "pick") resolve(existing, "auto_applied_undone")   // else: wait for undoResolved
                "accepted" -> resolve(existing, "auto_applied_kept")
                "dropped_stale" -> items.remove(id)
            }
            return
        }
        val typed = e["typed"] as? String ?: return
        val cands = (e["candidates"] as? List<*>)?.mapNotNull { (it as? Map<*, *>) } ?: return
        val chosen = e["chosen"] as? String ?: return
        val ranked = cands.mapNotNull { it["w"] as? String }
        val alts = ranked.filter { it != chosen && it.lowercase() != typed.lowercase() }.distinct().take(MAX_ALTS)
        val margin = margin(cands, typed, chosen)
        val seq = findSeq(typed)
        when (outcome) {
            "accepted" -> add(id, e, typed, chosen, alts, margin, seq, null)?.let { resolve(it, "auto_applied_kept") }
            "undone" -> {
                val it = add(id, e, typed, chosen, alts, margin, seq, null) ?: return
                if (e["via"] == "pick") resolve(it, "auto_applied_undone")
            }
            "strip_pick" -> add(id, e, typed, chosen, alts, margin, seq, null)?.let { pick(it, e["final"] as? String) }
            "flagged" -> {
                // suggest-only / unsure: the fixes shown are the ranked list without the word as typed
                val fixes = ranked.filter { it.lowercase() != typed.lowercase() }.distinct()
                val top = fixes.firstOrNull() ?: return
                add(id, e, typed, top, fixes.drop(1).take(MAX_ALTS), margin, seq, "suggestion_ignored")
            }
        }
    }

    /** a suggestion tapped while typing: a correction only if it isn't a completion of what was typed */
    private fun onTypingPick(id: String, e: Map<String, Any?>) {
        val typed = e["typed"] as? String ?: return
        val pick = e["final"] as? String ?: return
        val shown = (e["candidates"] as? List<*>)?.mapNotNull { (it as? Map<*, *>)?.get("w") as? String } ?: return
        if (pick.lowercase() == typed.lowercase() || pick.lowercase().startsWith(typed.lowercase())) return
        val fixes = shown.filter { it.lowercase() != typed.lowercase() }.distinct()
        val top = fixes.firstOrNull() ?: return
        val it = add(id, e, typed, top, fixes.drop(1).take(MAX_ALTS), null, findSeq(pick), null) ?: return
        pick(it, pick)
    }

    private fun onRewind(id: String, e: Map<String, Any?>) {
        val existing = items[id]
        if (existing != null) {
            if (existing.outcome == null && e["outcome"] == "undone") resolve(existing, "auto_applied_undone")
            return
        }
        if (e["outcome"] != "accepted") return
        val from = e["from"] as? String ?: return
        val to = e["to"] as? String ?: return
        val m = (e["margin"] as? Number)?.toDouble()
        // the undo chip of a rewind can still put it back until the field closes: kept unless that happens
        add(id, e, from, to, emptyList(), m?.let(Kinds::marginBucket), null, "auto_applied_kept", rewind = true,
            after = Scrub.after(e["right_context"] as? String), right = e["right_context"] as? String)
    }

    // ------------------------------------------------------------------ building

    private fun add(id: String, e: Map<String, Any?>, typed: String, suggestion: String, alts: List<String>, margin: String?,
                    seq: Long?, fallback: String?, rewind: Boolean = false, after: List<String>? = null, right: String? = null): Item? {
        // scrub: the word itself (and its top fix) must be clean, or there's no event at all
        if (!Scrub.wordOk(typed, 1) || !Scrub.wordOk(suggestion, 2) || suggestion == typed) return null
        if (isProbation(typed)) return null   // quality guard: auto-learned words still on probation
        val left = e["left_context"] as? String
        val before = if (left != null) Scrub.before(left) else seq?.let { s -> listOfNotNull(ring[s - 2], ring[s - 1]) }?.mapNotNull(Scrub::contextToken) ?: emptyList()
        // sentence context: only with the second opt-in, and never in a field that rules it out
        val wantCtx = fieldContext && context()
        val ctxBefore = if (!wantCtx) null else if (left != null) Ctx.before(left, truncated = left.length >= ctxWindow) else seq?.let { s -> Ctx.before(ringText(s)) }
        val ctxAfter = if (!wantCtx || right == null) null else Ctx.after(right, truncated = true)
        val it = Item(id, typed, suggestion, alts.take(MAX_ALTS), Kinds.classify(typed, suggestion, rewind, inVocab), margin, before,
            (e["model"] as? String).orEmpty(), (e["app_version"] as? String).orEmpty(), seq, after, fallback, ctxBefore, ctxAfter, wantCtx)
        items[id] = it
        while (items.size > MAX_ITEMS) {
            val old = items.values.first()
            if (old.outcome == null && old.fallback != null) resolve(old, old.fallback, emit = false)
            emitItem(old)
            items.remove(old.id)
        }
        return it
    }

    private fun pick(it: Item, w: String?) {
        val list = listOf(it.suggestion) + it.alternatives
        val k = list.indexOf(w)
        if (k < 0) { items.remove(it.id); return }   // a word we didn't offer (or scrubbed): no clean rank, no event
        it.pickedRank = k
        resolve(it, "suggestion_picked")
    }

    private fun resolve(it: Item, outcome: String, emit: Boolean = true) {
        it.outcome = outcome
        it.day = DAY.format(Instant.ofEpochMilli(now()))
        // the word's current text, as context for the words around it
        it.seq?.let { s ->
            if (ring.containsKey(s)) ring[s] = when (outcome) {
                "auto_applied_kept" -> it.suggestion
                "suggestion_picked" -> (listOf(it.suggestion) + it.alternatives)[it.pickedRank ?: 0]
                "manual_fix" -> it.final ?: it.typed
                else -> it.typed
            }
        }
        if (emit) emitReady(false)
    }

    /** resolved items go out once the word after them is known and settled (2 words later), or when forced */
    private fun emitReady(force: Boolean) {
        val last = ring.keys.lastOrNull()
        for (it in items.values.toList()) {
            if (it.outcome == null) continue
            val s = it.seq
            if (!force && it.after == null && s != null && (last == null || last < s + 2)) continue
            // with sentence context: until the rest of the sentence is typed (or the field is left / the cursor jumps)
            if (!force && it.wantCtx && it.ctxAfter == null && s != null && ring.containsKey(s) && !Ctx.afterDone(textAfter(s))) continue
            emitItem(it)
            items.remove(it.id)
        }
    }

    private fun emitItem(it: Item) {
        val outcome = it.outcome ?: return
        if (it.final != null && (!Scrub.wordOk(it.final, 2) || isProbation(it.final!!))) return
        val after = it.after ?: it.seq?.let { s -> ring[s + 1] }?.let(Scrub::contextToken)?.let { listOf(it) } ?: emptyList()
        val e = build(it, outcome, after) ?: return
        // the opt-in is checked again here: switched off (or the field changed) since the decision -> no context
        if (it.wantCtx && fieldContext && context()) {
            val cb = it.ctxBefore.orEmpty()
            val ca = it.ctxAfter ?: it.seq?.takeIf { s -> ring.containsKey(s) }?.let { s -> Ctx.after(textAfter(s)) }.orEmpty()
            if (Ctx.ok(cb, Ctx.MAX_BEFORE)) e["ctx_before"] = cb
            if (Ctx.ok(ca, Ctx.MAX_AFTER)) e["ctx_after"] = ca
        }
        sink(e)
    }

    /** the field text before word [s], rebuilt from the words finished since the last cursor jump */
    private fun ringText(s: Long): String {
        val sb = StringBuilder()
        var k = s - 1
        while (ring.containsKey(k)) k--
        for (i in k + 1 until s) sb.append(ring[i]).append(seps[i] ?: " ")
        if (sb.isNotEmpty() && !sb.last().isWhitespace()) sb.append(' ')   // word [s] is its own token
        return sb.toString()
    }

    /** the field text after word [s], rebuilt from the words finished after it */
    private fun textAfter(s: Long): String {
        val sb = StringBuilder(seps[s] ?: " ")
        var k = s + 1
        while (ring.containsKey(k)) { sb.append(ring[k]).append(seps[k] ?: " "); k++ }
        return sb.toString()
    }

    private fun build(it: Item, outcome: String, after: List<String>): MutableMap<String, Any?>? {
        // ranks follow the app's list; a scrubbed alternative is left out but keeps its rank
        val alts = it.alternatives.mapIndexedNotNull { i, w -> if (Scrub.wordOk(w, 2) && w != it.suggestion) mapOf("w" to w, "rank" to i + 1) else null }
        if (outcome == "suggestion_picked") {
            val r = it.pickedRank ?: return null
            if (r != 0 && alts.none { a -> a["rank"] == r }) return null
        }
        val e = linkedMapOf<String, Any?>(
            "v" to EventLog.SCHEMA, "id" to it.id.replace("-", ""), "day" to it.day, "type" to "correction", "platform" to platform,
            "app_version" to it.appVersion.takeIf { v -> VERSION.matches(v) }, "model" to it.model.lowercase().takeIf { m -> MODEL.matches(m) },
            "kind" to it.kind, "typed" to it.typed, "suggestion" to it.suggestion,
        )
        if (e["app_version"] == null || e["model"] == null) return null
        if (alts.isNotEmpty()) e["alternatives"] = alts
        e["outcome"] = outcome
        if (outcome == "suggestion_picked") e["picked_rank"] = it.pickedRank
        if (outcome == "manual_fix") e["final"] = it.final ?: return null
        it.margin?.let { m -> e["margin"] = m }
        if (it.before.isNotEmpty()) e["before"] = it.before
        if (after.isNotEmpty()) e["after"] = after
        return e
    }

    /** how much the model preferred the fix over the word as typed (score units), bucketed */
    private fun margin(cands: List<Map<*, *>>, typed: String, chosen: String): String? {
        val sc = cands.firstOrNull { it["w"] == chosen }?.get("score") as? Number ?: return null
        val ts = cands.firstOrNull { (it["w"] as? String)?.lowercase() == typed.lowercase() }?.get("score") as? Number
        return Kinds.marginBucket(if (ts == null) Double.POSITIVE_INFINITY else sc.toDouble() - ts.toDouble())
    }

    /** the counter of the most recent finished word with this text */
    private fun findSeq(w: String): Long? = ring.entries.lastOrNull { it.value == w }?.key

    companion object {
        const val MAX_ALTS = 3
        const val RING = 16
        const val MAX_ITEMS = 128
        const val MAX_SEP = 256
        val DAY: java.time.format.DateTimeFormatter = java.time.format.DateTimeFormatter.ISO_LOCAL_DATE.withZone(ZoneOffset.UTC)
        val VERSION = Regex("^[0-9A-Za-z][0-9A-Za-z.+_-]{0,31}$")
        val MODEL = Regex("^[a-z0-9][a-z0-9_.-]{0,31}$")
    }
}
