package dev.mostlime.autocorrect.log

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.net.HttpURLConnection
import java.net.URI

/**
 * HTTP side of the opt-in upload (no Android APIs, so it's unit-testable): the request body, the delete URL and what
 * to do with each response. Server contract: collect/API.md in the autocorrect repo.
 */
object UploadClient {
    class Response(val code: Int, val retryAfterSec: Long?, val body: String)

    enum class Action { ADVANCE, RETRY, SPLIT, SKIP }

    /** 2xx advances; 408 / 429 / 5xx / network error (-1) retry later; 413 halves the batch; any other 4xx skips it. */
    fun classify(code: Int): Action = when {
        code in 200..299 -> Action.ADVANCE
        code == 413 -> Action.SPLIT
        code == 408 || code == 429 || code >= 500 || code < 0 -> Action.RETRY
        code in 400..499 -> Action.SKIP
        else -> Action.RETRY   // 1xx / 3xx: shouldn't happen (redirects are followed for GET only); try again later
    }

    /**
     * Upload envelope (schema autocorrect-events/2: exactly schema, install_id, app_version, events). The events are
     * the queue's JSON lines as they are (each one already scrubbed and checked by CorrectionLog).
     */
    fun envelope(installId: String, appVersion: String, lines: List<String>): String =
        JsonObject(mapOf(
            "schema" to JsonPrimitive("autocorrect-events/${EventLog.SCHEMA}"),
            "install_id" to JsonPrimitive(installId),
            "app_version" to JsonPrimitive(appVersion),
            "events" to JsonArray(lines.mapNotNull { runCatching { Json.parseToJsonElement(it) }.getOrNull() }),
        )).toString()

    class Drained(val sent: Int, val skipped: Int, /** set when it stopped on a retryable error */ val retry: Response?)

    /**
     * Upload everything from `start`: read(from, maxLines) -> (lines, next offset), send(lines) -> response,
     * commit(next) after each batch that is done with (sent, or skipped for good). Stops at the end of the log or on
     * the first retryable error (the offset stays before that batch).
     */
    fun drain(start: Long, maxEvents: Int, read: (Long, Int) -> Pair<List<String>, Long>, send: (List<String>) -> Response,
              commit: (Long) -> Unit, log: (String) -> Unit = {}): Drained {
        var off = start
        var batch = maxEvents
        var sent = 0
        var skipped = 0
        while (true) {
            val (lines, next) = read(off, batch)
            if (lines.isEmpty()) return Drained(sent, skipped, null)
            val r = send(lines)
            when (classify(r.code)) {
                Action.ADVANCE -> sent += lines.size   // keeps a halved batch size for the rest of this run
                Action.SPLIT -> {
                    if (lines.size > 1) { batch = lines.size / 2; continue }
                    log("413 for a single event, skipping it"); skipped++
                }
                // permanent 4xx: the same bytes can never succeed, so don't let them wedge the log
                Action.SKIP -> { log("rejected (${r.code}), skipping ${lines.size} events: ${r.body.take(200)}"); skipped += lines.size }
                Action.RETRY -> return Drained(sent, skipped, r)
            }
            off = next
            commit(next)
        }
    }

    /** .../api/v1/upload -> .../api/v1/install/{id} */
    fun deleteUrl(uploadUrl: String, installId: String): String =
        uploadUrl.trimEnd('/').substringBeforeLast('/') + "/install/" + installId

    fun post(url: String, body: String): Response = request("POST", url, body.toByteArray(Charsets.UTF_8))

    fun delete(url: String): Response = request("DELETE", url, null)

    /** Network errors come back as code -1. */
    fun request(method: String, url: String, body: ByteArray?): Response {
        val c = try {
            URI(url).toURL().openConnection() as HttpURLConnection
        } catch (e: Exception) {
            return Response(-1, null, e.toString())
        }
        return try {
            c.requestMethod = method
            c.connectTimeout = 15_000; c.readTimeout = 30_000
            c.instanceFollowRedirects = false
            c.setRequestProperty("Accept", "application/json")
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                c.setFixedLengthStreamingMode(body.size)   // Content-Length, no chunking (the server answers 411 otherwise)
                c.outputStream.use { it.write(body) }
            }
            val code = c.responseCode
            val text = try {
                (if (code >= 400) c.errorStream else c.inputStream)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
            } catch (_: Exception) { "" }
            Response(code, c.getHeaderField("Retry-After")?.trim()?.toLongOrNull()?.coerceAtLeast(0), text.take(2000))
        } catch (e: Exception) {
            Response(-1, null, e.toString())
        } finally {
            c.disconnect()
        }
    }
}
