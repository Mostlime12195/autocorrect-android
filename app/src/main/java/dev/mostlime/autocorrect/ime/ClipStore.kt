package dev.mostlime.autocorrect.ime

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.io.File

/**
 * Clipboard history for the strip chip and the panel: the last MAX_UNPINNED copied texts plus pinned ones, kept only
 * in app-private storage of the keyboard process. Never fed with sensitive clips or text copied in password/incognito
 * fields (the caller checks).
 */
class ClipStore(private val file: File?) {
    class Item(val text: String, val at: Long, var pinned: Boolean)

    val items = ArrayList<Item>()   // newest first
    /** the clip the strip chip offers (cleared once pasted / dismissed / too old) */
    var offer: Item? = null
        private set

    init { load() }

    fun add(text: String, now: Long = System.currentTimeMillis()) {
        val t = text.take(MAX_CHARS)
        if (t.isBlank()) return
        val old = items.firstOrNull { it.text == t }
        items.removeAll { it.text == t }
        val item = Item(t, now, old?.pinned ?: false)
        items.add(0, item)
        trim()
        offer = item
        save()
    }

    fun recentOffer(now: Long = System.currentTimeMillis()): Item? = offer?.takeIf { now - it.at < OFFER_MS }

    fun consumeOffer() { offer = null }

    fun togglePin(item: Item) { item.pinned = !item.pinned; trim(); save() }

    fun delete(item: Item) { items.remove(item); if (offer === item) offer = null; save() }

    fun clear() { items.removeAll { !it.pinned }; offer = null; save() }

    private fun trim() {
        var unpinned = 0
        val keep = ArrayList<Item>()
        for (i in items) if (i.pinned || unpinned++ < MAX_UNPINNED) keep.add(i)
        items.clear(); items.addAll(keep)
    }

    private fun load() {
        val f = file ?: return
        if (!f.exists()) return
        runCatching {
            Json.parseToJsonElement(f.readText()).jsonArray.forEach { e ->
                val o = e.jsonObject
                items.add(Item(o["t"]!!.jsonPrimitive.content, o["at"]!!.jsonPrimitive.long, o["p"]!!.jsonPrimitive.boolean))
            }
        }
    }

    private fun save() {
        val f = file ?: return
        runCatching {
            val arr = JsonArray(items.map { JsonObject(mapOf("t" to JsonPrimitive(it.text), "at" to JsonPrimitive(it.at), "p" to JsonPrimitive(it.pinned))) })
            File(f.path + ".tmp").apply { writeText(arr.toString()); renameTo(f) }
        }
    }

    companion object {
        const val MAX_UNPINNED = 10
        const val MAX_CHARS = 5000
        /** the strip offers a clip for this long after it was copied */
        const val OFFER_MS = 90_000L
        /** ClipDescription.EXTRA_IS_SENSITIVE (API 33), also set by older apps that follow the convention */
        const val EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE"
    }
}
