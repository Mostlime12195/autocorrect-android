package dev.mostlime.autocorrect

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import java.util.UUID

/** Typed access to the settings (keys match res/xml/prefs.xml). */
class Prefs(ctx: Context) {
    val sp: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(ctx)

    var model: String
        get() = sp.getString(MODEL, "") ?: ""
        set(v) = sp.edit().putString(MODEL, v).apply()
    val batterySaver get() = sp.getBoolean(BATTERY_SAVER, false)
    val style get() = sp.getString(STYLE, "standard") ?: "standard"
    val autocorrect get() = sp.getBoolean(AUTOCORRECT, true)
    val debugOverlay get() = sp.getBoolean(DEBUG, false)
    val haptics get() = sp.getBoolean(HAPTICS, true)
    val share get() = sp.getBoolean(SHARE, false)
    val wifiOnly get() = sp.getBoolean(WIFI_ONLY, true)
    val uploadUrl get() = (sp.getString(UPLOAD_URL, null) ?: "").ifBlank { DEFAULT_UPLOAD_URL }
    val manifestUrl get() = (sp.getString(MANIFEST_URL, null) ?: "").ifBlank { DEFAULT_MANIFEST_URL }

    var uploadOffset: Long
        get() = sp.getLong(UPLOAD_OFFSET, 0)
        set(v) = sp.edit().putLong(UPLOAD_OFFSET, v).apply()

    val installId: String
        get() = sp.getString(INSTALL_ID, null) ?: UUID.randomUUID().toString().also { sp.edit().putString(INSTALL_ID, it).apply() }

    companion object {
        const val MODEL = "model"
        const val BATTERY_SAVER = "battery_saver"
        const val STYLE = "style"
        const val AUTOCORRECT = "autocorrect"
        const val DEBUG = "debug_overlay"
        const val HAPTICS = "haptics"
        const val SHARE = "share_data"
        const val WIFI_ONLY = "upload_wifi_only"
        const val UPLOAD_URL = "upload_url"
        const val MANIFEST_URL = "manifest_url"
        const val UPLOAD_OFFSET = "upload_offset"
        const val INSTALL_ID = "install_id"

        /** Placeholder: the collection endpoint doesn't exist yet (docs/upload-schema.md). Uploads to it are skipped. */
        const val DEFAULT_UPLOAD_URL = "https://example.invalid/autocorrect/v1/events"
        const val DEFAULT_MANIFEST_URL = "https://smitten.mostlime.hackclub.app/autocorrect-android/manifest.json"
        val STYLES = listOf("casual", "standard", "formal")
    }
}
