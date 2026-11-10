package dev.mostlime.autocorrect

import dev.mostlime.autocorrect.log.CorrectionLog
import dev.mostlime.autocorrect.log.Ctx
import dev.mostlime.autocorrect.log.EventLog
import dev.mostlime.autocorrect.log.UploadClient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import dev.mostlime.autocorrect.log.Kinds
import dev.mostlime.autocorrect.log.Scrub
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/** Upload events (autocorrect-events/3): scrubbing, sentence context and outcome classification, fed with the keyboard's local events. */
class CorrectionsTest {
    // ------------------------------------------------------------------ scrubber
    @Test fun tokensWithDigitsEmailsUrlsOrLengthAreDropped() {
        assertTrue(Scrub.tokenOk("hello"))
        assertTrue(Scrub.tokenOk("don't"))
        assertTrue(Scrub.tokenOk("café"))
        assertTrue(Scrub.tokenOk("well-known"))
        assertFalse(Scrub.tokenOk("h3llo"))
        assertFalse(Scrub.tokenOk("2nd"))
        assertFalse(Scrub.tokenOk("me@example"))
        assertFalse(Scrub.tokenOk("x://y"))
        assertFalse(Scrub.tokenOk("a".repeat(21)))
        assertTrue(Scrub.tokenOk("a".repeat(20)))
        assertFalse(Scrub.tokenOk("a/b"))
        assertFalse(Scrub.tokenOk("#tag"))
        assertFalse(Scrub.tokenOk(""))
        assertFalse(Scrub.tokenOk("..."))
        assertTrue(Scrub.wordOk("a lot", 2))
        assertFalse(Scrub.wordOk("a lot", 1))
        assertFalse(Scrub.wordOk("call 911", 2))
    }

    @Test fun contextIsTwoBeforeOneAfterLowercasedAndScrubbed() {
        assertEquals(listOf("you", "at"), Scrub.before("See You At "))
        assertEquals(listOf("hello"), Scrub.before("Hello, "))
        assertEquals(listOf("soon"), Scrub.after(" Soon! and more"))
        // dropped tokens are not replaced by words further back
        assertEquals(listOf("call"), Scrub.before("please call 5551234 "))
        assertEquals(listOf("mail"), Scrub.before("hi mail me@x.com "))
        assertEquals(listOf("see"), Scrub.before("see https://example.com/a "))
        assertEquals(listOf("visit"), Scrub.before("visit www.example.com:8080 "))
        assertEquals(emptyList<String>(), Scrub.before("supercalifragilisticexpialidocious 12:30 "))
        assertEquals(emptyList<String>(), Scrub.after(" user@mail.com later"))
        assertEquals(emptyList<String>(), Scrub.after(" 42"))
        // same line only
        assertEquals(listOf("new"), Scrub.before("old words\nnew "))
        assertEquals(emptyList<String>(), Scrub.after("\nnext line"))
    }

    @Test fun kinds() {
        val vocab = setOf("form", "from", "their", "there")
        assertEquals("spelling", Kinds.classify("teh", "the", false) { it in vocab })
        assertEquals("real_word", Kinds.classify("form", "from", false) { it in vocab })
        assertEquals("punctuation", Kinds.classify("dont", "don't", false) { it in vocab })
        assertEquals("punctuation", Kinds.classify("im", "I'm", false) { it in vocab })
        assertEquals("punctuation", Kinds.classify("cafe", "café", false) { it in vocab })
        assertEquals("grammar", Kinds.classify("their", "there", true) { it in vocab })
        assertEquals("0-1", Kinds.marginBucket(0.4))
        assertEquals("4-8", Kinds.marginBucket(5.0))
        assertEquals("8+", Kinds.marginBucket(Double.POSITIVE_INFINITY))
    }

    // ------------------------------------------------------------------ outcomes (local events in, upload events out)
    private val out = ArrayList<Map<String, Any?>>()
    private val probation = HashSet<String>()
    private var ctxOn = false
    private val log = CorrectionLog({ out.add(it) }, { it in setOf("form", "from", "the") }, { it in probation }, { 1_762_650_000_000L },
        context = { ctxOn })

    private fun word(typed: String, chosen: String, outcome: String, id: String = UUID.randomUUID().toString(),
                     left: String? = "see you at ", extra: Map<String, Any?> = emptyMap()): Map<String, Any?> =
        linkedMapOf<String, Any?>("v" to 1, "id" to id, "ts" to 1L, "type" to "autocorrect", "typed" to typed,
            "touches" to listOf(listOf(1.0, 2.0)), "left_context" to left, "sep" to " ",
            "candidates" to listOf(mapOf("w" to chosen, "score" to -1.0), mapOf("w" to "then", "score" to -3.0),
                mapOf("w" to typed, "score" to -6.5), mapOf("w" to "tea", "score" to -7.0), mapOf("w" to "t3a", "score" to -8.0),
                mapOf("w" to "ten", "score" to -9.0)),
            "chosen" to chosen, "final" to chosen, "outcome" to outcome, "style" to "standard", "model" to "m2s_int4",
            "app_version" to "0.1.90", "lm_ms" to 5.0) + extra

    private fun finish(vararg words: String) { for (w in words) log.word(w) }

    @Test fun keptWithContextAndNoForbiddenFields() {
        finish("see", "you", "at", "teh")
        log.onEvent(word("teh", "the", "accepted"))
        assertTrue(out.isEmpty())                    // waits for the next word (and its fix) to settle
        finish("party", "tonight")
        val e = out.single()
        assertEquals("auto_applied_kept", e["outcome"])
        assertEquals("teh", e["typed"]); assertEquals("the", e["suggestion"])
        assertEquals(listOf("you", "at"), e["before"]); assertEquals(listOf("party"), e["after"])
        assertEquals("spelling", e["kind"]); assertEquals("4-8", e["margin"])
        assertEquals("2025-11-09", e["day"]); assertEquals("android", e["platform"]); assertEquals(3, e["v"])
        // the word as typed is never an alternative; t3a (rank 3) was scrubbed and left out
        assertEquals(listOf(mapOf("w" to "then", "rank" to 1), mapOf("w" to "tea", "rank" to 2)), e["alternatives"])
        val allowed = setOf("v", "id", "day", "type", "platform", "app_version", "model", "kind", "typed", "suggestion",
            "alternatives", "outcome", "picked_rank", "final", "margin", "before", "after")
        assertTrue(e.keys.toString(), allowed.containsAll(e.keys))
        assertFalse(e.toString().contains("see you at"))
    }

    @Test fun undoVerdicts() {
        // backspace undo, then the word finished as typed: undone
        finish("teh"); val a = word("teh", "the", "undone"); log.onEvent(a); log.reopen()
        log.undoResolved(a["id"] as String, "teh"); finish("teh", "x", "y")
        assertEquals("auto_applied_undone", out.last()["outcome"])
        // backspace undo, then retyped as something else: manual fix with what it became
        out.clear(); finish("form"); val b = word("form", "from", "undone"); log.onEvent(b); log.reopen()
        log.undoResolved(b["id"] as String, "for"); finish("for", "x", "y")
        assertEquals("manual_fix", out.single()["outcome"]); assertEquals("for", out.single()["final"])
        assertEquals("real_word", out.single()["kind"])
        // backspace undo, then the same fix typed again: accidental backspace, no event
        out.clear(); finish("teh"); val c = word("teh", "the", "undone"); log.onEvent(c); log.reopen()
        log.undoResolved(c["id"] as String, "the"); finish("the", "x", "y"); log.flush()
        assertTrue(out.isEmpty())
        // the cursor moved before the word was finished: no verdict, no event
        finish("teh"); val d = word("teh", "the", "undone"); log.onEvent(d); log.reopen()
        log.undoResolved(d["id"] as String, null); log.flush()
        assertTrue(out.isEmpty())
        // picking the word as typed from the strip: undone right away
        finish("teh"); log.onEvent(word("teh", "the", "undone", extra = mapOf("via" to "pick"))); log.flush()
        assertEquals("auto_applied_undone", out.single()["outcome"])
    }

    @Test fun picksIgnoresAndNotATypo() {
        // an alternative picked after an auto fix
        finish("teh"); log.onEvent(word("teh", "the", "strip_pick", extra = mapOf("final" to "tea"))); log.flush()
        assertEquals("suggestion_picked", out.last()["outcome"]); assertEquals(2, out.last()["picked_rank"])
        // flagged (suggest-only), then the chip tapped (same id)
        out.clear(); val id = UUID.randomUUID().toString()
        finish("teh"); log.onEvent(word("teh", "the", "flagged", id))
        log.onEvent(word("teh", "the", "strip_pick", id, extra = mapOf("final" to "the"))); log.flush()
        assertEquals("suggestion_picked", out.single()["outcome"]); assertEquals(0, out.single()["picked_rank"])
        // flagged, then not a typo (refers to the flag's id)
        out.clear(); val id2 = UUID.randomUUID().toString()
        finish("yeet"); log.onEvent(word("yeet", "yet", "flagged", id2))
        log.onEvent(mapOf("v" to 1, "id" to "n1", "type" to "not_typo", "typed" to "yeet", "ref" to id2)); log.flush()
        assertEquals("not_a_typo", out.single()["outcome"])
        // flagged and left alone until the field closed: ignored
        out.clear(); finish("teh"); log.onEvent(word("teh", "the", "flagged")); log.flush()
        assertEquals("suggestion_ignored", out.single()["outcome"])
        // a typing-strip completion isn't a correction; a real fix is
        out.clear()
        log.word("hello")
        log.onEvent(mapOf("v" to 1, "id" to "s1", "type" to "strip_pick", "typed" to "hel", "candidates" to listOf(mapOf("w" to "hello")),
            "chosen" to "hello", "final" to "hello", "outcome" to "strip_pick", "model" to "m2s_int4", "app_version" to "0.1.90"))
        log.word("relieve")   // the controller counts the picked word
        log.onEvent(mapOf("v" to 1, "id" to "s2", "type" to "strip_pick", "typed" to "recieve",
            "candidates" to listOf(mapOf("w" to "recieve"), mapOf("w" to "receive"), mapOf("w" to "relieve")),
            "chosen" to "relieve", "final" to "relieve", "outcome" to "strip_pick", "model" to "m2s_int4", "app_version" to "0.1.90"))
        log.flush()
        val e = out.single()
        assertEquals("recieve", e["typed"]); assertEquals("receive", e["suggestion"]); assertEquals(1, e["picked_rank"])
        assertEquals(listOf("hello"), e["before"])
    }

    @Test fun rewindsAreGrammarAndKeptUnlessUndone() {
        log.onEvent(mapOf("v" to 1, "id" to "r1", "type" to "rewind", "from" to "too", "to" to "to", "margin" to 7.1, "words_back" to 1,
            "left_context" to "I want", "right_context" to "Go home now", "outcome" to "accepted", "model" to "m2s_int4", "app_version" to "0.1.90"))
        log.onEvent(mapOf("v" to 1, "id" to "r2", "type" to "rewind", "from" to "their", "to" to "there", "margin" to 9.0, "words_back" to 1,
            "left_context" to "over", "right_context" to "is", "outcome" to "accepted", "model" to "m2s_int4", "app_version" to "0.1.90"))
        log.onEvent(mapOf("v" to 1, "id" to "r2", "type" to "rewind", "from" to "their", "to" to "there", "outcome" to "undone"))
        log.flush()
        val (a, b) = out.sortedBy { it["typed"] as String }.let { it[1] to it[0] }
        assertEquals("grammar", a["kind"]); assertEquals("auto_applied_kept", a["outcome"]); assertEquals(listOf("go"), a["after"])
        assertEquals(listOf("i", "want"), a["before"]); assertEquals("4-8", a["margin"])
        assertEquals("auto_applied_undone", b["outcome"])
    }

    @Test fun scrubbedWordsAndGuardDropTheWholeEvent() {
        finish("h3llo"); log.onEvent(word("h3llo", "hello", "accepted"))
        finish("x"); log.onEvent(word("xx", "me@x.com", "accepted"))
        log.onEvent(word("a".repeat(21), "b", "accepted"))
        probation.add("yeet"); log.onEvent(word("yeet", "yet", "accepted"))
        // merged words / stale results aren't word-level decisions
        log.onEvent(word("teh", "the", "accepted", extra = mapOf("merge" to mapOf("from" to "a teh", "to" to "athe"))))
        log.onEvent(word("teh", "the", "dropped_stale"))
        // a manual fix into something with digits
        finish("teh"); val m = word("teh", "the", "undone"); log.onEvent(m); log.reopen(); log.undoResolved(m["id"] as String, "t3h")
        log.flush()
        assertTrue(out.toString(), out.isEmpty())
    }

    // ------------------------------------------------------------------ sentence context (the second opt-in)
    @Test fun ctxScrubberMatchesTheReferenceVectors() {
        val vec = Json.parseToJsonElement(javaClass.getResource("/ctx_vectors.json")!!.readText()).jsonArray
        assertTrue(vec.size > 30)
        for (v in vec) {
            val o = v.jsonObject
            val left = o["left"]!!.jsonPrimitive.content; val right = o["right"]!!.jsonPrimitive.content
            val b = Ctx.before(left, o["left_truncated"]!!.jsonPrimitive.boolean)
            val a = Ctx.after(right, o["right_truncated"]!!.jsonPrimitive.boolean)
            assertEquals("before of [$left]", o["before"]!!.jsonPrimitive.content, b)
            assertEquals("after of [$right]", o["after"]!!.jsonPrimitive.content, a)
            if (b.isNotEmpty()) assertTrue(b, Ctx.ok(b, Ctx.MAX_BEFORE))
            if (a.isNotEmpty()) assertTrue(a, Ctx.ok(a, Ctx.MAX_AFTER))
        }
    }

    @Test fun ctxTokensThatCouldIdentifyAreReplaced() {
        for ((t, want) in listOf("hello" to "hello", "Hello," to "Hello,", "don't" to "don't", "(ok)" to "(ok)", "café" to "café",
            "555-1234" to "<num>", "12:30" to "<num>", "\$5.00" to "<num>", "50%" to "<num>", "+1" to "<num>",
            "bob@example.com" to "<email>", "<bob@example.com>," to "<email>",
            "https://x.y/z" to "<url>", "www.x" to "<url>", "example.com" to "<url>", "example.com/a/b" to "<url>",
            "@lime" to "<handle>", "#general" to "<handle>", "(@lime)" to "<handle>",
            "abc123" to "<tok>", "2nd" to "<tok>", "a".repeat(21) to "<tok>", "a_b" to "<tok>", "c/d" to "<tok>", "x=y" to "<tok>",
            "\uD83D\uDE00" to "<tok>", "dGhpcyBpcyBh" to "<tok>", "ABCD-EFGH-IJKL" to "<tok>", "xkcdqwrt" to "<tok>",
            "sk_live_abcdef" to "<tok>", "me@" to "<tok>", "" to "<tok>")) assertEquals(t, want, Ctx.token(t))
        assertEquals("call me at <num> or", Ctx.before("call me at 555 123 4567 or "))        // a number run is one placeholder
        assertEquals("send <num> to <email> about", Ctx.before("send \$5.00 to bob@example.com about "))
        assertEquals("at <url> ok", Ctx.after(" at https://example.com/x?y=1 ok"))
        assertEquals("", Ctx.before("1 2 3 "))                                                 // placeholders alone aren't sent
        assertEquals("", Ctx.after(" @lime #x 42"))
        // what the server would refuse never passes the last check
        for (bad in listOf("call 555 now", "mail me@x.com", "see example.com", "hi @lime", "a\nb", "", "<num> <num> word", "<email>"))
            assertFalse(bad, Ctx.ok(bad, Ctx.MAX_BEFORE))
    }

    @Test fun ctxIsTheCurrentSentenceOnTheSameLineAndCapped() {
        assertEquals("And then I went to", Ctx.before("First one. And then I went to "))
        assertEquals("store.", Ctx.after(" store. Another one follows."))
        assertEquals("", Ctx.before("Done! "))
        assertEquals("", Ctx.after(". Next sentence"))
        assertEquals("Because", Ctx.before("He asked why? Because "))
        assertEquals("second line with", Ctx.before("line one\nsecond line with "))
        assertEquals("words", Ctx.after(" words\nthird line"))
        assertEquals("", Ctx.after("\nnext line"))
        val words = (0 until 40).map { "w" + ('a' + it % 26) + ('a' + it / 26) }
        assertEquals(words.takeLast(24).joinToString(" "), Ctx.before(words.joinToString(" ") + " "))
        assertEquals(words.take(12).joinToString(" "), Ctx.after(" " + words.joinToString(" ")))
        // a window that was cut never gives half a word
        assertEquals("word then", Ctx.before("rtial word then ", truncated = true))
        assertEquals("and the tail is", Ctx.after(" and the tail is cu", truncated = true))
    }

    private val CTX_LEFT = "Old sentence here. I think we should see you at "

    @Test fun noSentenceContextWithoutTheSecondOptIn() {
        finish("see", "you", "at", "teh")
        log.onEvent(word("teh", "the", "accepted", left = CTX_LEFT))
        finish("party", "tonight"); log.flush()
        val e = out.single()
        assertFalse(e.containsKey("ctx_before")); assertFalse(e.containsKey("ctx_after"))
        assertFalse(e.toString().contains("think"))
    }

    @Test fun sentenceContextWithTheOptIn() {
        ctxOn = true
        finish("see", "you", "at"); log.word("teh", " ")
        log.onEvent(word("teh", "the", "accepted", left = CTX_LEFT))
        log.word("party", " "); log.word("tonight", " ")
        assertTrue(out.isEmpty())                       // the sentence isn't finished yet
        log.word("ok", "."); log.between(" "); log.word("Next", " ")
        val e = out.single()
        assertEquals("I think we should see you at", e["ctx_before"])
        assertEquals("party tonight ok.", e["ctx_after"])
        assertEquals(listOf("you", "at"), e["before"]); assertEquals(listOf("party"), e["after"])   // v2 fields unchanged
        assertEquals(3, e["v"])
    }

    @Test fun sentenceContextFromTypedWordsIsScrubbedAndStopsAtTheSentenceEnd() {
        ctxOn = true
        // no left_context in the event (a strip pick): rebuilt from the words typed in this field
        log.word("Done", "."); log.between(" ")
        log.word("mail", " "); log.word("bob", "@"); log.word("example", "."); log.word("com", " ")
        log.word("or", " "); log.between("555 1234 ")
        log.word("relieve", " ")
        log.onEvent(mapOf("v" to 1, "id" to "s2", "type" to "strip_pick", "typed" to "recieve",
            "candidates" to listOf(mapOf("w" to "recieve"), mapOf("w" to "receive"), mapOf("w" to "relieve")),
            "chosen" to "relieve", "final" to "relieve", "outcome" to "strip_pick", "model" to "m2s_int4", "app_version" to "0.1.90"))
        log.word("it", " "); log.word("at", " "); log.between("12:30"); log.word("pm", "!"); log.between(" "); log.word("Bye", " ")
        val e = out.single()
        assertEquals("mail <email> or <num>", e["ctx_before"])
        assertEquals("it at <tok>", e["ctx_after"])    // "12:30pm!" is one token: letters mixed with digits
    }

    @Test fun rewindContextComesFromTheTextAroundTheWord() {
        ctxOn = true
        log.onEvent(mapOf("v" to 1, "id" to "r1", "type" to "rewind", "from" to "too", "to" to "to", "margin" to 7.1, "words_back" to 2,
            "left_context" to "Hi there. I want ", "right_context" to " go home at 5 now. Then sleep", "outcome" to "accepted",
            "model" to "m2s_int4", "app_version" to "0.1.90"))
        log.flush()
        val e = out.single()
        assertEquals("I want", e["ctx_before"]); assertEquals("go home at <num> now.", e["ctx_after"])
    }

    @Test fun contextIsDroppedWhenSwitchedOffOrTheFieldRulesItOut() {
        ctxOn = true
        finish("teh"); log.onEvent(word("teh", "the", "accepted", left = CTX_LEFT))
        ctxOn = false                                   // switched off before the event went out
        log.flush()
        assertFalse(out.single().containsKey("ctx_before"))
        out.clear(); ctxOn = true
        log.fieldContext = false                        // a name / address / no-suggestions field
        finish("teh"); log.onEvent(word("teh", "the", "accepted", left = CTX_LEFT)); log.flush()
        assertFalse(out.single().containsKey("ctx_before")); assertFalse(out.single().containsKey("ctx_after"))
        out.clear(); log.fieldContext = true
        finish("teh"); log.onEvent(word("teh", "the", "accepted", left = CTX_LEFT)); log.flush()
        assertEquals("I think we should see you at", out.single()["ctx_before"])
    }

    @Test fun queuedContextIsStrippedFromLinesTheQueueAndTheUpload() {
        val with = "{\"v\":3,\"id\":\"abcdefgh\",\"typed\":\"teh\",\"ctx_before\":\"I think we\",\"ctx_after\":\"ok.\",\"outcome\":\"auto_applied_kept\"}"
        val plain = "{\"v\":3,\"id\":\"abcdefgi\",\"typed\":\"teh\",\"outcome\":\"not_a_typo\"}"
        val stripped = EventLog.withoutContext(with)
        assertEquals("{\"v\":3,\"id\":\"abcdefgh\",\"typed\":\"teh\",\"outcome\":\"auto_applied_kept\"}", stripped)
        assertEquals(plain, EventLog.withoutContext(plain))
        // the upload (and the preview, which shows UploadClient.outgoing) only carries context while the opt-in is on
        assertEquals(with, UploadClient.outgoing(with, true)); assertEquals(stripped, UploadClient.outgoing(with, false))
        assertFalse(UploadClient.envelope("id", "0.1.1", listOf(with, plain)).contains("ctx_"))
        assertFalse(UploadClient.envelope("id", "0.1.1", listOf(with, plain), context = false).contains("think"))
        assertTrue(UploadClient.envelope("id", "0.1.1", listOf(with, plain), context = true).contains("\"ctx_before\":\"I think we\""))
        // switching the opt-in off rewrites the queue file; the upload cursor still points at the same line
        val f = java.io.File.createTempFile("corr", ".jsonl")
        try {
            f.writeText("$plain\n$with\n$with\n$plain\n")
            val third = (plain.length + 1 + with.length + 1).toLong()
            val off = EventLog.rewriteFile(f, third, EventLog::withoutContext)
            assertEquals("$plain\n$stripped\n$stripped\n$plain\n", f.readText())
            assertEquals((plain.length + 1 + stripped.length + 1).toLong(), off)
            assertEquals(f.length(), EventLog.rewriteFile(f, f.length() + 10, EventLog::withoutContext))   // a cursor at the end stays at the end
            assertFalse(java.io.File(f.path + ".tmp").exists())
        } finally { f.delete() }
    }

    @Test fun contextNeverCrossesACursorJump() {
        finish("teh"); log.onEvent(word("teh", "the", "accepted", left = ""))
        log.breakChain()
        finish("elsewhere", "entirely")
        assertNull(out.single()["after"])
    }
}
