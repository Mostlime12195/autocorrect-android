package dev.mostlime.autocorrect.log

import android.content.Context
import android.util.Log
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.util.concurrent.Executors

/**
 * App-private JSONL file, one JSON object per line. Writes happen on a background thread. When the file passes maxBytes
 * it is rotated to <name>.1.jsonl (the older rotation is dropped) and resetOffset() is called.
 *
 * Two of them: the local event log (filesDir/events/events.jsonl, docs/upload-schema.md; stays on the phone) and the
 * upload queue (filesDir/upload/corrections.jsonl: scrubbed correction events, only written while sharing is on,
 * docs/upload-schema.md). The keyboard only writes either for non-sensitive fields (FieldPolicy).
 */
class EventLog(
    private val ctx: Context,
    dirName: String = "events",
    name: String = "events",
    private val maxBytes: Long = MAX_BYTES,
    private val resetOffset: () -> Unit = {},
) {
    val dir = File(ctx.filesDir, dirName).apply { mkdirs() }
    val file = File(dir, "$name.jsonl")
    private val old = File(dir, "$name.1.jsonl")
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "ac-log") }

    fun append(e: JsonObject) {
        io.execute {
            try {
                if (file.length() > maxBytes) rotate()
                file.appendText(e.toString() + "\n")
            } catch (t: Throwable) {
                Log.w(TAG, "log write failed: $t")
            }
        }
    }

    private fun rotate() {
        old.delete()
        file.renameTo(old)
        // the uploader's byte offset refers to the current file; anything not uploaded before rotation is skipped
        resetOffset()
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
            resetOffset()
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

    /** Blocking: the last n lines (oldest first), for the upload preview. */
    fun tail(n: Int): List<String> = io.submit<List<String>> {
        val out = ArrayDeque<String>()
        for (f in listOf(old, file)) if (f.exists()) f.useLines { ls -> ls.forEach { out.addLast(it); if (out.size > n) out.removeFirst() } }
        out.toList()
    }.get()

    /**
     * Blocking: rewrite every queued line with [f] (used to drop sentence context when that opt-in is switched off).
     * offset = a byte offset into the current file; returns where that same line starts after the rewrite.
     */
    fun rewrite(offset: Long, f: (String) -> String): Long = io.submit<Long> {
        if (old.exists()) rewriteFile(old, 0, f)
        if (file.exists()) rewriteFile(file, offset, f) else 0L
    }.get()

    /** Offset of the end of the log (uploads start here when sharing is switched on: no backfill). */
    fun endOffset(): Long = io.submit<Long> { file.length() }.get()

    companion object {
        const val TAG = "AcLog"
        const val MAX_BYTES = 8L shl 20
        /** the upload queue holds only recent events: uploaded ones go when it rotates */
        const val QUEUE_BYTES = 256L shl 10
        /** upload schema (DATA.md in the autocorrect repo); the local log's own events are v1 and never uploaded */
        const val SCHEMA = 3

        /** rewrite one JSONL file line by line -> the new byte offset of the line that started at [offset] (or the new end) */
        fun rewriteFile(src: File, offset: Long, f: (String) -> String): Long {
            val tmp = File(src.parentFile, src.name + ".tmp")
            var pos = 0L; var out = 0L; var newOffset = -1L
            tmp.bufferedWriter(Charsets.UTF_8).use { w ->
                src.useLines { ls ->
                    for (l in ls) {
                        if (newOffset < 0 && pos >= offset) newOffset = out
                        val n = f(l)
                        w.write(n); w.write("\n")
                        pos += l.toByteArray(Charsets.UTF_8).size + 1
                        out += n.toByteArray(Charsets.UTF_8).size + 1
                    }
                }
            }
            if (!tmp.renameTo(src)) { src.delete(); tmp.renameTo(src) }
            return if (newOffset < 0) out else newOffset
        }

        /** an upload event without its sentence context (what is sent while "Include sentence context" is off) */
        fun withoutContext(line: String): String {
            if ("\"ctx_" !in line) return line
            val o = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(line) as? JsonObject }.getOrNull() ?: return line
            return JsonObject(o.filterKeys { it != "ctx_before" && it != "ctx_after" }).toString()
        }

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
