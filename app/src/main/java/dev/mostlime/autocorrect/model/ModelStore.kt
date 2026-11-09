package dev.mostlime.autocorrect.model

import android.content.Context
import android.util.Log
import dev.mostlime.autocorrect.App
import dev.mostlime.autocorrect.engine.DecodeParams
import dev.mostlime.autocorrect.engine.StepMeta
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

class RemoteFile(val name: String, val size: Long, val sha256: String)

class ModelSpec(
    val id: String,
    val label: String,
    val graph: RemoteFile,
    val vocab: RemoteFile,
    /** "ort" (pre-optimized ORT format) or "onnx" */
    val format: String,
    val sessionConfig: Map<String, String>,
    val meta: StepMeta,
    val params: DecodeParams,
    /** trained with style control tokens (web/STYLE_TOKENS.md); false -> untagged context whatever the picker says */
    val supportsStyle: Boolean,
    /** style -> control token id (style.py: casual 249, standard 250, formal 251) */
    val styleTokens: Map<String, Int?>,
    /** optional per-style decode params (tuned per style once the style evals land) */
    val styleParams: Map<String, DecodeParams>,
    /** cand-gen v2 kind penalties for the formal style (adds caps / punct / comma); params.pen is the default style */
    val penFormal: Map<String, Double> = emptyMap(),
) {
    fun styleToken(style: String): Int? = if (supportsStyle) styleTokens[style] else null
    fun paramsFor(style: String): DecodeParams {
        val base = if (supportsStyle) styleParams[style] ?: params else params
        return if (style == "formal" && penFormal.isNotEmpty()) base.copy(pen = penFormal) else base
    }

    val files get() = listOf(graph, vocab)
    val downloadBytes get() = files.sumOf { it.size }
}

class Manifest(val schema: Int, val updated: String, val baseUrls: List<String>, val default: String, val batterySaver: String, val models: List<ModelSpec>) {
    fun model(id: String) = models.firstOrNull { it.id == id }

    companion object {
        fun parse(text: String): Manifest {
            val o = Json.parseToJsonElement(text).jsonObject
            fun file(j: JsonObject) = RemoteFile(j["file"]!!.jsonPrimitive.content, j["size"]!!.jsonPrimitive.long, j["sha256"]!!.jsonPrimitive.content)
            val models = o["models"]!!.jsonArray.map { e ->
                val m = e.jsonObject
                val meta = m["meta"]!!.jsonObject
                fun dp(p: JsonObject) = DecodeParams(
                    lam = p["lam"]!!.jsonPrimitive.double,
                    bias = p["bias"]!!.jsonPrimitive.double,
                    margin = p["margin"]?.jsonPrimitive?.doubleOrNull,
                    lamR = p["lam_r"]?.jsonPrimitive?.doubleOrNull,
                    pen = p["pen"]?.jsonObject?.mapValues { it.value.jsonPrimitive.double } ?: emptyMap(),
                )
                ModelSpec(
                    id = m["id"]!!.jsonPrimitive.content,
                    label = m["label"]!!.jsonPrimitive.content,
                    graph = file(m["graph"]!!.jsonObject),
                    vocab = file(m["vocab"]!!.jsonObject),
                    format = m["format"]?.jsonPrimitive?.content ?: "ort",
                    sessionConfig = m["session_config"]?.jsonObject?.mapValues { it.value.jsonPrimitive.content } ?: emptyMap(),
                    meta = StepMeta(
                        nKvHeads = meta["n_kv_heads"]!!.jsonPrimitive.int,
                        headDim = meta["head_dim"]!!.jsonPrimitive.int,
                        convKernel = meta["conv_kernel"]!!.jsonPrimitive.int,
                        dModel = meta["d_model"]!!.jsonPrimitive.int,
                        stateNames = meta["state_names"]!!.jsonArray.map { it.jsonPrimitive.content },
                    ),
                    params = dp(m["params"]!!.jsonObject),
                    supportsStyle = m["supports_style"]?.jsonPrimitive?.booleanOrNull ?: false,
                    styleTokens = m["style_tokens"]?.jsonObject?.mapValues { it.value.jsonPrimitive.intOrNull } ?: emptyMap(),
                    styleParams = m["style_params"]?.jsonObject?.mapValues { dp(it.value.jsonObject) } ?: emptyMap(),
                    penFormal = m["params"]!!.jsonObject["pen_formal"]?.jsonObject?.mapValues { it.value.jsonPrimitive.double } ?: emptyMap(),
                )
            }
            return Manifest(
                schema = o["schema"]!!.jsonPrimitive.int,
                updated = o["updated"]?.jsonPrimitive?.content ?: "",
                // mirrors, tried in order (e.g. GitHub release assets, then the demo host)
                baseUrls = o["base_urls"]?.jsonArray?.map { it.jsonPrimitive.content } ?: listOf(o["base_url"]!!.jsonPrimitive.content),
                default = o["default"]!!.jsonPrimitive.content,
                batterySaver = o["battery_saver"]?.jsonPrimitive?.content ?: o["default"]!!.jsonPrimitive.content,
                models = models,
            )
        }
    }
}

/**
 * Model files live in app-private storage (filesDir/models). A file counts as installed when it has a matching
 * "<file>.sha256" marker, written only after the download's SHA-256 was verified.
 *
 * The manifest comes from the demo host (Prefs.manifestUrl) and is cached; the copy bundled in the APK
 * (assets/manifest.json) is the fallback, so a fresh install works offline-first and model updates don't need a new APK.
 */
class ModelStore(private val ctx: Context) {
    val dir = File(ctx.filesDir, "models").apply { mkdirs() }
    private val cached = File(dir, "manifest.json")
    @Volatile private var manifest: Manifest? = null
    @Volatile private var loadedStamp = -1L

    /** Cached manifest; re-read when the other process (app / keyboard) refreshed the file. */
    fun manifest(): Manifest {
        val stamp = if (cached.exists()) cached.lastModified() else 0L
        if (stamp != loadedStamp) { manifest = null; loadedStamp = stamp }
        manifest?.let { return it }
        val m = runCatching { if (cached.exists()) Manifest.parse(cached.readText()) else null }.getOrNull()
            ?: Manifest.parse(ctx.assets.open("manifest.json").bufferedReader().use { it.readText() })
        manifest = m
        return m
    }

    /** Fetch the remote manifest from the first mirror that answers (blocking). Keeps the current one on any error. */
    fun refreshManifest(urls: List<String>): Boolean {
        for (u in urls) if (refreshManifest(u)) return true
        return false
    }

    fun refreshManifest(url: String): Boolean = try {
        val text = httpGet(url)
        val m = Manifest.parse(text)
        val cur = manifest()
        if (m.schema >= cur.schema) {
            File(dir, "manifest.json.tmp").apply { writeText(text); renameTo(cached) }
            manifest = m
            true
        } else false
    } catch (e: Exception) {
        Log.w(TAG, "manifest refresh failed: $e")
        false
    }

    fun file(f: RemoteFile) = File(dir, f.name)
    private fun marker(f: RemoteFile) = File(dir, f.name + ".sha256")

    fun isInstalled(f: RemoteFile): Boolean {
        val m = marker(f)
        return m.exists() && file(f).exists() && m.readText().trim() == f.sha256 && file(f).length() == f.size
    }

    fun isInstalled(spec: ModelSpec) = spec.files.all { isInstalled(it) }

    fun installedBytes(spec: ModelSpec) = spec.files.filter { isInstalled(it) }.sumOf { it.size }

    /** Download + verify every missing file of the model. progress(doneBytes, totalBytes). Throws on failure. */
    fun download(spec: ModelSpec, progress: (Long, Long) -> Unit) {
        val bases = manifest().baseUrls
        val total = spec.downloadBytes
        var done = installedBytes(spec)
        progress(done, total)
        for (f in spec.files) {
            if (isInstalled(f)) continue
            var err: Exception? = null
            for (base in bases) {
                try { fetch(base, f, done, total, progress); err = null; break } catch (e: IOException) { err = e; Log.w(TAG, "$base${f.name}: $e") }
            }
            err?.let { throw it }
            done += f.size
            progress(done, total)
        }
    }

    private fun fetch(base: String, f: RemoteFile, done: Long, total: Long, progress: (Long, Long) -> Unit) {
        run {
            val tmp = File(dir, f.name + ".part")
            val md = MessageDigest.getInstance("SHA-256")
            val conn = (URL(base + f.name).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000; readTimeout = 30_000
                setRequestProperty("Cache-Control", "no-cache")
            }
            try {
                if (conn.responseCode != 200) throw IOException("${f.name}: HTTP ${conn.responseCode}")
                var got = 0L
                conn.inputStream.use { inp ->
                    tmp.outputStream().use { out ->
                        val buf = ByteArray(1 shl 16)
                        var last = 0L
                        while (true) {
                            if (Thread.currentThread().isInterrupted) throw IOException("cancelled")
                            val n = inp.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n); md.update(buf, 0, n); got += n
                            if (got - last > 1 shl 20) { last = got; progress(done + got, total) }
                        }
                    }
                }
                val sha = md.digest().joinToString("") { "%02x".format(it) }
                if (got != f.size || sha != f.sha256) {
                    tmp.delete()
                    throw IOException("${f.name}: verification failed (size $got/${f.size}, sha256 ${sha.take(12)}… expected ${f.sha256.take(12)}…)")
                }
                marker(f).delete()
                if (!tmp.renameTo(file(f))) throw IOException("rename failed for ${f.name}")
                marker(f).writeText(f.sha256)
            } finally {
                conn.disconnect()
            }
        }
    }

    /** Delete files no model in the manifest references any more. */
    fun prune() {
        val keep = manifest().models.flatMap { it.files }.flatMap { listOf(it.name, it.name + ".sha256") }.toSet() + "manifest.json"
        dir.listFiles()?.forEach { if (it.name !in keep) it.delete() }
    }

    /** The model the keyboard should use now (battery saver wins over the explicit choice). */
    fun activeSpec(): ModelSpec {
        val m = manifest()
        val prefs = App.instance.prefs
        // a saved pick the manifest no longer has (m2_int4 / int8 ...) is cleared: the default takes over
        if (prefs.model.isNotEmpty() && m.model(prefs.model) == null) prefs.model = ""
        val id = if (prefs.batterySaver) m.batterySaver else prefs.model.ifEmpty { m.default }
        return m.model(id) ?: m.model(m.default)!!
    }

    companion object {
        const val TAG = "ModelStore"

        fun httpGet(url: String): String {
            val c = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000; readTimeout = 15_000; setRequestProperty("Cache-Control", "no-cache")
            }
            try {
                if (c.responseCode != 200) throw IOException("$url: HTTP ${c.responseCode}")
                return c.inputStream.bufferedReader().use { it.readText() }
            } finally {
                c.disconnect()
            }
        }
    }
}
