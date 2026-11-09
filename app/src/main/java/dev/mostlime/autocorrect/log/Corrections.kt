package dev.mostlime.autocorrect.log

import java.text.Normalizer
import java.time.Instant
import java.time.ZoneOffset

/**
 * Opt-in upload events (schema autocorrect-events/2, DATA.md in the autocorrect repo): one scrubbed, word-level event
 * per correction decision. Nothing here touches Android, so it's unit-tested on the JVM.
 *
 * [CorrectionLog] turns the keyboard's local events (KeyboardController.write) plus a few hooks into these events:
 * it waits for the outcome of a decision (kept / undone / picked / ignored ...), adds at most 2 words before and 1 after,
 * scrubs everything and hands the finished event to [sink]. The controller never calls it for sensitive fields.
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
) {
    /** a decision waiting for its outcome and/or the word after it */
    private class Item(
        val id: String, val typed: String, val suggestion: String, val alternatives: List<String>, val kind: String,
        val margin: String?, val before: List<String>, val model: String, val appVersion: String,
        /** word counter of this word (null = unknown, then no "after" word) */
        var seq: Long?, var after: List<String>?,
        /** used when the session ends with no outcome (null = no event) */
        val fallback: String?,
    ) {
        var outcome: String? = null
        var pickedRank: Int? = null
        var final: String? = null
        var day: String = ""
    }

    private val items = LinkedHashMap<String, Item>()

    /** recent finished words of this field, by counter (their current text) */
    private val ring = LinkedHashMap<Long, String>()
    private var nextSeq = 0L

    // ------------------------------------------------------------------ hooks from the controller

    /** a word was finished (typed, picked or swiped) */
    fun word(w: String) {
        ring[nextSeq++] = w
        while (ring.size > RING) ring.remove(ring.keys.first())
        emitReady(false)
    }

    /** the last finished word was reopened (backspace undo): the next word() is the same slot */
    fun reopen() {
        val last = ring.keys.lastOrNull() ?: return
        ring.remove(last)
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
        ring.clear()
        nextSeq++   // the next word is never counted as "right after" a word from before the jump
    }

    /** the field lost focus: everything gets its fallback outcome and goes out */
    fun flush() {
        for (it in items.values.toList()) if (it.outcome == null) { if (it.fallback != null) resolve(it, it.fallback, emit = false) else items.remove(it.id) }
        emitReady(true)
        items.clear()
        ring.clear()
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
            after = Scrub.after(e["right_context"] as? String))
    }

    // ------------------------------------------------------------------ building

    private fun add(id: String, e: Map<String, Any?>, typed: String, suggestion: String, alts: List<String>, margin: String?,
                    seq: Long?, fallback: String?, rewind: Boolean = false, after: List<String>? = null): Item? {
        // scrub: the word itself (and its top fix) must be clean, or there's no event at all
        if (!Scrub.wordOk(typed, 1) || !Scrub.wordOk(suggestion, 2) || suggestion == typed) return null
        if (isProbation(typed)) return null   // quality guard: auto-learned words still on probation
        val left = e["left_context"] as? String
        val before = if (left != null) Scrub.before(left) else seq?.let { s -> listOfNotNull(ring[s - 2], ring[s - 1]) }?.mapNotNull(Scrub::contextToken) ?: emptyList()
        val it = Item(id, typed, suggestion, alts.take(MAX_ALTS), Kinds.classify(typed, suggestion, rewind, inVocab), margin, before,
            (e["model"] as? String).orEmpty(), (e["app_version"] as? String).orEmpty(), seq, after, fallback)
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
            emitItem(it)
            items.remove(it.id)
        }
    }

    private fun emitItem(it: Item) {
        val outcome = it.outcome ?: return
        if (it.final != null && (!Scrub.wordOk(it.final, 2) || isProbation(it.final!!))) return
        val after = it.after ?: it.seq?.let { s -> ring[s + 1] }?.let(Scrub::contextToken)?.let { listOf(it) } ?: emptyList()
        build(it, outcome, after)?.let(sink)
    }

    private fun build(it: Item, outcome: String, after: List<String>): Map<String, Any?>? {
        // ranks follow the app's list; a scrubbed alternative is left out but keeps its rank
        val alts = it.alternatives.mapIndexedNotNull { i, w -> if (Scrub.wordOk(w, 2) && w != it.suggestion) mapOf("w" to w, "rank" to i + 1) else null }
        if (outcome == "suggestion_picked") {
            val r = it.pickedRank ?: return null
            if (r != 0 && alts.none { a -> a["rank"] == r }) return null
        }
        val e = linkedMapOf<String, Any?>(
            "v" to 2, "id" to it.id.replace("-", ""), "day" to it.day, "type" to "correction", "platform" to platform,
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
        val DAY: java.time.format.DateTimeFormatter = java.time.format.DateTimeFormatter.ISO_LOCAL_DATE.withZone(ZoneOffset.UTC)
        val VERSION = Regex("^[0-9A-Za-z][0-9A-Za-z.+_-]{0,31}$")
        val MODEL = Regex("^[a-z0-9][a-z0-9_.-]{0,31}$")
    }
}
