package dev.mostlime.autocorrect.log

import android.content.Context
import android.util.Log
import dev.mostlime.autocorrect.App
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.util.concurrent.Executors

/**
 * Local event log: app-private JSONL (filesDir/events/events.jsonl), one JSON object per line, schema in
 * docs/upload-schema.md. Writes happen on a background thread. The keyboard only calls this for non-sensitive fields
 * (FieldPolicy). When the file passes MAX_BYTES it is rotated to events.1.jsonl (the older rotation is dropped).
 */
class EventLog(private val ctx: Context) {
    val dir = File(ctx.filesDir, "events").apply { mkdirs() }
    val file = File(dir, "events.jsonl")
    private val old = File(dir, "events.1.jsonl")
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "ac-log") }

    fun append(e: JsonObject) {
        io.execute {
            try {
                if (file.length() > MAX_BYTES) rotate()
                file.appendText(e.toString() + "\n")
            } catch (t: Throwable) {
                Log.w(TAG, "log write failed: $t")
            }
        }
    }

    private fun rotate() {
        old.delete()
        file.renameTo(old)
        // the uploader's byte offset refers to events.jsonl; anything not uploaded before rotation is skipped
        App.instance.prefs.uploadOffset = 0
    }

    fun sizeBytes() = file.length() + old.length()

    fun count(): Int = listOf(old, file).filter { it.exists() }.sumOf { f -> f.useLines { it.count() } }

    /** Blocking: copy of all events (oldest first) into cacheDir/export, for the share sheet. */
    fun exportCopy(): File {
        val out = File(File(ctx.cacheDir, "export").apply { mkdirs() }, "autocorrect-events.jsonl")
        io.submit {
            out.outputStream().use { o -> listOf(old, file).filter { it.exists() }.forEach { f -> f.inputStream().use { it.copyTo(o) } } }
        }.get()
        return out
    }

    fun clear() {
        io.submit {
            file.delete(); old.delete()
            File(ctx.cacheDir, "export").deleteRecursively()
            App.instance.prefs.uploadOffset = 0
        }.get()
    }

    /** Lines from byte offset `from` of events.jsonl, up to maxLines / maxBytes. -> (lines, new offset) */
    fun readFrom(from: Long, maxLines: Int, maxBytes: Int): Pair<List<String>, Long> {
        return io.submit<Pair<List<String>, Long>> {
            if (!file.exists() || from >= file.length()) return@submit emptyList<String>() to minOf(from, file.length())
            val lines = ArrayList<String>()
            var off = from
            var bytes = 0
            file.inputStream().use { inp ->
                inp.skip(from)
                val r = inp.bufferedReader(Charsets.UTF_8)
                while (lines.size < maxLines && bytes < maxBytes) {
                    val l = r.readLine() ?: break
                    val n = l.toByteArray(Charsets.UTF_8).size + 1
                    lines.add(l); bytes += n; off += n
                }
            }
            lines to off
        }.get()
    }

    /** Offset of the end of the log (uploads start here when sharing is switched on: no backfill). */
    fun endOffset(): Long = io.submit<Long> { file.length() }.get()

    companion object {
        const val TAG = "AcLog"
        const val MAX_BYTES = 8L shl 20
        const val SCHEMA = 1

        /** event map (KeyboardController) -> JSON, as written to the log and uploaded */
        @Suppress("UNCHECKED_CAST")
        fun toJson(v: Any?): JsonElement = when (v) {
            null -> JsonNull
            is String -> JsonPrimitive(v)
            is Number -> JsonPrimitive(v)
            is Boolean -> JsonPrimitive(v)
            is Map<*, *> -> JsonObject((v as Map<String, Any?>).mapValues { toJson(it.value) })
            is List<*> -> JsonArray(v.map { toJson(it) })
            else -> JsonPrimitive(v.toString())
        }
    }
}
