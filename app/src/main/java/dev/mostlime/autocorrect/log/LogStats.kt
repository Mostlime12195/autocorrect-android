package dev.mostlime.autocorrect.log

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File

/**
 * Settings "Stats", computed from the keyboard's local log (the keyboard runs in another process): this app version,
 * the most recent words, and everything since the last reset. Old versions were slower, so the all-time average lags.
 */
object LogStats {
    class Agg {
        var words = 0; var skipped = 0; var cheap = 0; var lm = 0; var lmMs = 0.0; var ctx = 0.0; var ctxN = 0
        var changed = 0; var undone = 0; var picks = 0; var dropped = 0; var rewinds = 0
        fun add(o: JsonObject, type: String?) {
            if (type == "rewind") { rewinds++; return }
            words++
            val sk = o["skipped"]?.jsonPrimitive?.booleanOrNull == true
            val ch = o["cheap"]?.jsonPrimitive?.booleanOrNull == true
            if (sk) skipped++
            if (ch) cheap++
            if (!sk && !ch) {
                lm++; lmMs += o["lm_ms"]?.jsonPrimitive?.doubleOrNull ?: 0.0
                o["ctx_tokens"]?.jsonPrimitive?.doubleOrNull?.takeIf { it >= 0 }?.let { ctx += it; ctxN++ }
            }
            when (o["outcome"]?.jsonPrimitive?.contentOrNull) {
                "accepted" -> changed++
                "undone" -> { changed++; undone++ }
                "strip_pick" -> picks++
                "dropped_stale" -> dropped++
            }
        }
        fun line(name: String): String {
            if (words == 0) return "$name: no words yet"
            val sb = StringBuilder("$name: $words words, LM ${if (lm > 0) "${(lmMs / lm).toInt()} ms avg" else "not used"}")
            sb.append(", skipped ${100 * skipped / words}%")
            if (cheap > 0) sb.append(", no model ${100 * cheap / words}%")
            if (ctxN > 0) sb.append(", context ${(ctx / ctxN).toInt()} tokens")
            sb.append(", $changed fixes ($undone undone)")
            if (rewinds > 0) sb.append(", $rewinds rewinds")
            return sb.toString()
        }
    }

    fun compute(f: File, version: String, since: Long, recentN: Int = 200, maxLines: Int = 20000): String {
        if (!f.exists()) return "No words typed yet"
        val lines = f.useLines { it.toList().takeLast(maxLines) }
        val all = Agg(); val mine = Agg()
        val recent = ArrayList<Pair<JsonObject, String?>>()
        var session: String? = null
        for (l in lines) {
            val o = runCatching { Json.parseToJsonElement(l).jsonObject }.getOrNull() ?: continue
            if ((o["ts"]?.jsonPrimitive?.longOrNull ?: 0L) < since) continue
            val type = o["type"]?.jsonPrimitive?.contentOrNull
            if (type == "session") {
                session = "Last model load: ${o["model"]?.jsonPrimitive?.contentOrNull}, session ${o["session_ms"]?.jsonPrimitive?.contentOrNull} ms, " +
                    "first inference ${o["first_inference_ms"]?.jsonPrimitive?.doubleOrNull?.toInt()} ms"
                continue
            }
            if (type != "perf" && type != "autocorrect" && type != "rewind") continue
            all.add(o, type)
            if (o["app_version"]?.jsonPrimitive?.contentOrNull == version) mine.add(o, type)
            recent.add(o to type)
        }
        val rec = Agg()
        recent.takeLast(recentN).forEach { (o, t) -> rec.add(o, t) }
        return listOfNotNull(mine.line("This version ($version)"), rec.line("Last $recentN words"), all.line("All time"), session).joinToString("\n")
    }
}
