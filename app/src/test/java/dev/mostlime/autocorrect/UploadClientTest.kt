package dev.mostlime.autocorrect

import android.text.InputType
import dev.mostlime.autocorrect.engine.KeyGeom
import dev.mostlime.autocorrect.engine.Touch
import dev.mostlime.autocorrect.engine.WordResult
import dev.mostlime.autocorrect.ime.ControllerConfig
import dev.mostlime.autocorrect.ime.ControllerUi
import dev.mostlime.autocorrect.ime.FieldPolicy
import dev.mostlime.autocorrect.ime.KeyType
import dev.mostlime.autocorrect.ime.KeyboardController
import dev.mostlime.autocorrect.ime.KeyboardLayout
import dev.mostlime.autocorrect.ime.Shift
import dev.mostlime.autocorrect.log.EventLog
import dev.mostlime.autocorrect.log.UploadClient
import dev.mostlime.autocorrect.log.UploadClient.Action
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.UUID

class UploadClientTest {
    @Test fun responseHandling() {
        assertEquals(Action.ADVANCE, UploadClient.classify(200))
        assertEquals(Action.ADVANCE, UploadClient.classify(204))
        assertEquals(Action.SPLIT, UploadClient.classify(413))
        for (c in listOf(-1, 408, 429, 500, 502, 503, 504)) assertEquals("$c", Action.RETRY, UploadClient.classify(c))
        for (c in listOf(400, 401, 403, 404, 411, 415, 422)) assertEquals("$c", Action.SKIP, UploadClient.classify(c))
    }

    @Test fun urls() {
        assertEquals("https://autocorrect.mostlime.hackclub.app/api/v1/install/abc",
            UploadClient.deleteUrl(Prefs.DEFAULT_UPLOAD_URL, "abc"))
        assertEquals("https://autocorrect.mostlime.hackclub.app/api/v1/upload", Prefs.DEFAULT_UPLOAD_URL)
    }

    @Test fun envelopeIsValidJson() {
        val lines = listOf(EventLog.toJson(mapOf("v" to 2, "id" to "abcdefgh", "typed" to "te\"h", "x" to listOf(1.5, null))).toString())
        val o = Json.parseToJsonElement(UploadClient.envelope("id", "0.1.1", lines)).jsonObject
        assertEquals("autocorrect-events/3", o["schema"]!!.jsonPrimitive.content)
        assertEquals(setOf("schema", "install_id", "app_version", "events"), o.keys)   // no sent_at since v2
        assertEquals(1, o["events"]!!.jsonArray.size)
    }

    /** a log of n lines; offsets are line numbers */
    private fun reader(n: Int) = { from: Long, max: Int -> (from until minOf(n.toLong(), from + max)).map { "{\"i\":$it}" } to minOf(n.toLong(), from + max) }

    @Test fun drainHalvesOn413AndSkipsPermanentErrors() {
        val sizes = ArrayList<Int>()
        var off = 0L
        // the server takes at most 120 events; the batch holding event 250 (248..309 once halved to 62) is rejected for good
        val d = UploadClient.drain(0, 500, reader(1000), { lines ->
            sizes.add(lines.size)
            when {
                lines.size > 120 -> UploadClient.Response(413, null, "")
                "{\"i\":250}" in lines -> UploadClient.Response(400, null, "{\"error\":\"invalid_json\"}")
                else -> UploadClient.Response(200, null, "")
            }
        }, { off = it })
        assertEquals(1000L, off)
        assertEquals(null, d.retry)
        assertEquals(62, d.skipped)
        assertEquals(938, d.sent)
        assertEquals(listOf(500, 250, 125, 62), sizes.take(4))
    }

    @Test fun drainStopsOnRetryableWithoutAdvancing() {
        var off = 0L
        var calls = 0
        val d = UploadClient.drain(0, 500, reader(1200), { calls++; UploadClient.Response(if (calls == 1) 200 else 429, 60, "") }, { off = it })
        assertEquals(500L, off)
        assertEquals(429, d.retry!!.code)
        assertEquals(60L, d.retry!!.retryAfterSec)
    }

    /**
     * Against the live collection server (AC_LIVE_UPLOAD=1): v3 events made by the real KeyboardController +
     * CorrectionLog, serialized like the queue, in the worker's envelope -> all accepted by the server's strict
     * validation, deduped on retry, the server's 4xx codes map to the right action, and DELETE removes everything.
     */
    @Test fun liveServer() {
        assumeTrue(System.getenv("AC_LIVE_UPLOAD") == "1")
        val ed = KeyboardControllerTest.FakeEditor()
        val engine = KeyboardControllerTest.SlowEngine()
        val events = ArrayList<Map<String, Any?>>()
        // with the sentence-context opt-in on, so the server's v3 checks see what the real scrubber makes
        val corrections = dev.mostlime.autocorrect.log.CorrectionLog({ events.add(it) }, { false }, context = { true })
        val ctl = KeyboardController({ ed }, engine, object : ControllerUi {
            override fun showStrip(words: List<String>, bold: Int, undo: Int) {}
            override fun setShift(shift: Shift) {}
            override fun onResult(r: WordResult?) {}
        }, KeyboardControllerTest.ManualTimer(), corrections = corrections) {}
        ctl.startInput(ControllerConfig(FieldPolicy.of(InputType.TYPE_CLASS_TEXT, 0), true, "standard", { "m2s_int4" }, "0.0.0-test"))
        for (c in "call me at 5551234 or teh quick brown fox is it fized ") {
            val k = if (c == ' ') KeyboardLayout.LETTERS.keys.first { it.type == KeyType.SPACE } else KeyboardLayout.LETTERS.keys.firstOrNull { it.text == c.toString() }
                ?: dev.mostlime.autocorrect.ime.KeyDef(c.toString(), c.toString(), KeyType.CHAR, 0f, 1f, 0)   // digits
            ctl.key(k, KeyGeom.center(c) ?: Touch.APOS)
            ctl.selectionChanged()
            engine.deliver(engine.queue.size)
        }
        ctl.finishInput()
        val lines = events.map { EventLog.toJson(it).toString() }
        println("events: ${lines.size}\n${lines.joinToString("\n")}")
        assertTrue(lines.any { "\"type\":\"correction\"" in it })
        assertTrue(lines.any { "\"ctx_before\"" in it })
        assertTrue(lines.none { "555" in it })

        val id = UUID.randomUUID().toString()
        println("install_id $id")
        val url = Prefs.DEFAULT_UPLOAD_URL
        fun up(lines: List<String>, install: String = id) = UploadClient.post(url, UploadClient.envelope(install, "0.0.0-test", lines, context = true))
        try {
            val r1 = up(lines)
            println("upload: ${r1.code} ${r1.body}")
            assertEquals(Action.ADVANCE, UploadClient.classify(r1.code))
            val o1 = Json.parseToJsonElement(r1.body).jsonObject
            assertEquals(r1.body, lines.size, o1["stored"]!!.jsonPrimitive.int)
            val r2 = up(lines)
            println("retry: ${r2.code} ${r2.body}")
            assertEquals(lines.size, Json.parseToJsonElement(r2.body).jsonObject["duplicates"]!!.jsonPrimitive.int)
            val r3 = up(lines, install = "not-a-uuid")
            println("bad id: ${r3.code} ${r3.body}")
            assertEquals(Action.SKIP, UploadClient.classify(r3.code))
            val r4 = up(List(501) { lines.last() })
            println("501 events: ${r4.code} ${r4.body}")
            assertEquals(Action.SPLIT, UploadClient.classify(r4.code))
        } finally {
            val d = UploadClient.delete(UploadClient.deleteUrl(url, id))
            println("delete: ${d.code} ${d.body}")
            assertEquals(Action.ADVANCE, UploadClient.classify(d.code))
            val d2 = UploadClient.delete(UploadClient.deleteUrl(url, id))
            println("delete again: ${d2.code} ${d2.body}")
            assertTrue(d2.body.contains("\"existed\":false") || d2.body.contains("\"existed\": false"))
        }
    }
}
