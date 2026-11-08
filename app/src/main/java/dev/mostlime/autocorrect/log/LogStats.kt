package dev.mostlime.autocorrect.log

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/** Settings "Stats": computed from the keyboard's local log (the keyboard runs in another process). */
object LogStats {
    fun compute(f: File, maxLines: Int = 5000): String {
        if (!f.exists()) return "No words typed yet"
        val lines = f.useLines { it.toList().takeLast(maxLines) }
        var words = 0; var skipped = 0; var cheap = 0; var lm = 0; var lmMs = 0.0; var ctx = 0.0; var ctxN = 0
        var changed = 0; var undone = 0; var picks = 0; var dropped = 0
        var session: String? = null
        for (l in lines) {
            val o = runCatching { Json.parseToJsonElement(l).jsonObject }.getOrNull() ?: continue
            val type = o["type"]?.jsonPrimitive?.contentOrNull
            if (type == "session") {
                session = "Last model load: ${o["model"]?.jsonPrimitive?.contentOrNull} session ${o["session_ms"]?.jsonPrimitive?.contentOrNull} ms, " +
                    "cold ${o["cold_load_ms"]?.jsonPrimitive?.contentOrNull} ms, first inference ${o["first_inference_ms"]?.jsonPrimitive?.doubleOrNull?.toInt()} ms"
                continue
            }
            if (type != "perf" && type != "autocorrect") continue
            words++
            if (o["skipped"]?.jsonPrimitive?.booleanOrNull == true) skipped++
            if (o["cheap"]?.jsonPrimitive?.booleanOrNull == true) cheap++
            if (o["skipped"]?.jsonPrimitive?.boolean == false && o["cheap"]?.jsonPrimitive?.boolean == false) {
                lm++; lmMs += o["lm_ms"]?.jsonPrimitive?.double ?: 0.0
                o["ctx_tokens"]?.jsonPrimitive?.doubleOrNull?.takeIf { it >= 0 }?.let { ctx += it; ctxN++ }
            }
            when (o["outcome"]?.jsonPrimitive?.contentOrNull) {
                "accepted" -> changed++
                "undone" -> { changed++; undone++ }
                "strip_pick" -> picks++
                "dropped_stale" -> dropped++
            }
        }
        if (words == 0) return session ?: "No words typed yet"
        return buildString {
            append("$words words: LM skipped ${100 * skipped / words}%, no model ${100 * cheap / words}%")
            if (lm > 0) append(", avg LM ${(lmMs / lm).toInt()} ms")
            if (ctxN > 0) append(", avg context ${(ctx / ctxN).toInt()} tokens")
            append("\n$changed corrections ($undone undone), $picks strip picks, $dropped dropped as stale")
            session?.let { append("\n").append(it) }
        }
    }
}
