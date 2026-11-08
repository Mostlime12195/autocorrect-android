package dev.mostlime.autocorrect.engine

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * SentencePiece BPE (identity normalizer, byte fallback). Port of web/site/tokenizer.js, which reproduces
 * sentencepiece 0.2 encode/decode. The vocab is the vocab*.json written by web/export_vocab.py.
 */
class SPTokenizer(
    val pieces: Array<String>,
    val scores: FloatArray,
    val types: IntArray,
    val unkId: Int,
    val eosId: Int,
    val bosId: Int,
    private val addDummyPrefix: Boolean,
    private val removeExtraWhitespaces: Boolean,
    private val escapeWhitespaces: Boolean,
    private val byteFallback: Boolean,
    private val unkSurface: String,
) {
    private val normal = HashMap<String, Int>(pieces.size * 2)   // NORMAL / USER_DEFINED / UNUSED: BPE merges look here
    private val reserved = HashMap<String, Int>()                 // control / unknown / byte pieces
    private val byteId = IntArray(256) { -1 }
    private val user: List<String>

    init {
        val u = ArrayList<String>()
        for (i in pieces.indices) {
            val p = pieces[i]
            val t = types[i]
            if (t == T_NORMAL || t == T_USER || t == T_UNUSED) normal[p] = i else reserved[p] = i
            if (t == T_USER) u.add(p)
            if (t == T_BYTE) byteId[p.substring(3, 5).toInt(16)] = i
        }
        user = u.sortedByDescending { it.length }
    }

    val vocabSize get() = pieces.size

    fun pieceToId(p: String): Int = reserved[p] ?: normal[p] ?: unkId

    fun normalize(text0: String): String {
        var text = fixSurrogates(text0)
        if (removeExtraWhitespaces) text = text.replace(Regex(" +"), " ").replace(Regex("^ | $"), "")
        if (text.isEmpty()) return ""
        if (addDummyPrefix) text = " $text"
        return if (escapeWhitespaces) text.replace(" ", WS) else text
    }

    fun encodePieces(text: String): List<String> {
        val s = normalize(text)
        if (s.isEmpty()) return emptyList()
        val sym = ArrayList<String>()
        val frozen = ArrayList<Boolean>()
        var i = 0
        while (i < s.length) {
            var u: String? = null
            for (p in user) if (s.startsWith(p, i)) { u = p; break }
            if (u != null) { sym.add(u); frozen.add(true); i += u.length; continue }
            val n = Character.charCount(s.codePointAt(i))
            sym.add(s.substring(i, i + n)); frozen.add(false); i += n
        }
        // BPE: repeatedly merge the adjacent pair with the highest score (leftmost on ties)
        fun pairScore(k: Int): Float {
            if (frozen[k] || frozen[k + 1]) return Float.NEGATIVE_INFINITY
            val id = normal[sym[k] + sym[k + 1]] ?: return Float.NEGATIVE_INFINITY
            return scores[id]
        }
        val sc = ArrayList<Float>(sym.size)
        for (k in 0 until sym.size - 1) sc.add(pairScore(k))
        while (true) {
            var best = -1
            var bs = Float.NEGATIVE_INFINITY
            for (k in sc.indices) if (sc[k] > bs) { bs = sc[k]; best = k }
            if (best < 0) break
            sym[best] = sym[best] + sym[best + 1]
            sym.removeAt(best + 1); frozen.removeAt(best + 1); sc.removeAt(best)
            if (best > 0) sc[best - 1] = pairScore(best - 1)
            if (best < sym.size - 1) sc[best] = pairScore(best)
        }
        return sym
    }

    fun encode(text: String): IntArray {
        val ids = IntArrayList()
        for (p in encodePieces(text)) {
            val id = pieceToId(p)
            if (id == unkId && byteFallback) {
                for (b in p.toByteArray(Charsets.UTF_8)) ids.add(byteId[b.toInt() and 0xff])
            } else ids.add(id)
        }
        return ids.toArray()
    }

    fun isByte(id: Int) = types[id] == T_BYTE
    fun isControl(id: Int) = types[id] == T_CONTROL

    /** Surface string per id, like sentencepiece's DecodeIds (bytes grouped into UTF-8 characters). */
    fun decodeSurfaces(ids: IntArray): Array<String> {
        val out = Array(ids.size) { "" }
        var bosWs = true
        var any = false
        val bytes = ArrayList<Int>()
        var bstart = 0
        fun flush(end: Int) {
            var off = 0
            while (off < bytes.size) {
                val (cp, n) = utf8At(bytes, off)
                if (cp < 0) { out[bstart + off] = "�"; off += 1 }
                else { out[bstart + off + n - 1] = String(Character.toChars(cp)); off += n }
                any = true
            }
            bytes.clear(); bstart = end + 1
        }
        for (i in ids.indices) {
            val id = ids[i]
            val t = types[id]
            if (t == T_BYTE) {
                if (bytes.isEmpty()) bstart = i
                bytes.add(pieces[id].substring(3, 5).toInt(16)); continue
            }
            flush(i)
            if (any) bosWs = false
            if (t == T_CONTROL) continue
            if (t == T_UNKNOWN) { out[i] = unkSurface; bosWs = false; any = true; continue }
            var p = pieces[id]
            if (bosWs && (addDummyPrefix || removeExtraWhitespaces) && p.startsWith(WS)) p = p.substring(1)
            out[i] = p.replace(WS, " ")
            bosWs = false; any = true
        }
        flush(ids.size)
        return out
    }

    fun decode(ids: IntArray): String = decodeSurfaces(ids).joinToString("")

    companion object {
        const val WS = "▁"
        const val T_NORMAL = 1
        const val T_UNKNOWN = 2
        const val T_CONTROL = 3
        const val T_USER = 4
        const val T_UNUSED = 5
        const val T_BYTE = 6

        fun fromJson(text: String): SPTokenizer {
            val v = Json.parseToJsonElement(text).jsonObject
            return fromJson(v)
        }

        fun fromJson(v: JsonObject): SPTokenizer {
            val pieces = v["pieces"]!!.jsonArray.map { it.jsonPrimitive.content }.toTypedArray()
            val scores = v["scores"]!!.jsonArray.let { a -> FloatArray(a.size) { a[it].jsonPrimitive.float } }
            val types = v["types"]!!.jsonArray.let { a -> IntArray(a.size) { a[it].jsonPrimitive.int } }
            return SPTokenizer(
                pieces, scores, types,
                unkId = v["unk_id"]!!.jsonPrimitive.int,
                eosId = v["eos_id"]!!.jsonPrimitive.int,
                bosId = v["bos_id"]!!.jsonPrimitive.int,
                addDummyPrefix = v["add_dummy_prefix"]!!.jsonPrimitive.boolean,
                removeExtraWhitespaces = v["remove_extra_whitespaces"]!!.jsonPrimitive.boolean,
                escapeWhitespaces = v["escape_whitespaces"]!!.jsonPrimitive.boolean,
                byteFallback = v["byte_fallback"]!!.jsonPrimitive.boolean,
                unkSurface = v["unk_surface"]?.jsonPrimitive?.content ?: " ⁇ ",
            )
        }

        /** lone surrogates -> U+FFFD (what JS's well-formedness step does) */
        internal fun fixSurrogates(s: String): String {
            var bad = false
            for (i in s.indices) {
                val c = s[i]
                if (Character.isHighSurrogate(c) && !(i + 1 < s.length && Character.isLowSurrogate(s[i + 1]))) { bad = true; break }
                if (Character.isLowSurrogate(c) && !(i > 0 && Character.isHighSurrogate(s[i - 1]))) { bad = true; break }
            }
            if (!bad) return s
            val sb = StringBuilder(s.length)
            for (i in s.indices) {
                val c = s[i]
                val ok = when {
                    Character.isHighSurrogate(c) -> i + 1 < s.length && Character.isLowSurrogate(s[i + 1])
                    Character.isLowSurrogate(c) -> i > 0 && Character.isHighSurrogate(s[i - 1])
                    else -> true
                }
                sb.append(if (ok) c else '�')
            }
            return sb.toString()
        }

        /** strict UTF-8 decode of one character at b[o]: (codepoint, length) or (-1, 1) */
        private fun utf8At(b: List<Int>, o: Int): Pair<Int, Int> {
            val c = b[o]
            if (c < 0x80) return c to 1
            val n: Int
            var cp: Int
            val min: Int
            when {
                c in 0xc2..0xdf -> { n = 2; cp = c and 0x1f; min = 0x80 }
                (c and 0xf0) == 0xe0 -> { n = 3; cp = c and 0x0f; min = 0x800 }
                c in 0xf0..0xf4 -> { n = 4; cp = c and 0x07; min = 0x10000 }
                else -> return -1 to 1
            }
            if (o + n > b.size) return -1 to 1
            for (k in 1 until n) {
                val x = b[o + k]
                if ((x and 0xc0) != 0x80) return -1 to 1
                cp = (cp shl 6) or (x and 0x3f)
            }
            if (cp < min || cp > 0x10ffff || cp in 0xd800..0xdfff) return -1 to 1
            return cp to n
        }
    }
}

/** Minimal growable int list (avoids boxing in hot paths). */
class IntArrayList(cap: Int = 16) {
    private var a = IntArray(cap)
    var size = 0
        private set

    fun add(x: Int) {
        if (size == a.size) a = a.copyOf(maxOf(8, a.size * 2))
        a[size++] = x
    }

    operator fun get(i: Int) = a[i]
    fun toArray(): IntArray = a.copyOf(size)
}
