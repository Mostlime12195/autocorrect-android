package dev.mostlime.autocorrect

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import java.util.UUID

/** Typed access to the settings (keys match res/xml/prefs.xml). */
class Prefs(private val ctx: Context) {
    var sp: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(ctx)
        private set

    /**
     * The keyboard runs in its own process (":ime") and settings are changed in the app process: re-read the file.
     * (MODE_MULTI_PROCESS is deprecated but still reloads a changed file on the next getSharedPreferences.)
     */
    @Suppress("DEPRECATION")
    fun reload() {
        sp = ctx.getSharedPreferences(ctx.packageName + "_preferences", Context.MODE_MULTI_PROCESS)
    }

    var model: String
        get() = sp.getString(MODEL, "") ?: ""
        set(v) = sp.edit().putString(MODEL, v).apply()
    val batterySaver get() = sp.getBoolean(BATTERY_SAVER, false)
    val style get() = sp.getString(STYLE, "standard") ?: "standard"
    val autocorrect get() = sp.getBoolean(AUTOCORRECT, true)
    val rewind get() = sp.getBoolean(REWIND, true)
    val clipboard get() = sp.getBoolean(CLIPBOARD, true)
    val debugOverlay get() = sp.getBoolean(DEBUG, false)
    val haptics get() = sp.getBoolean(HAPTICS, true)
    val hapticStrength get() = (sp.getString(HAPTIC_STRENGTH, "1") ?: "1").toIntOrNull() ?: 1
    var statsResetAt: Long
        get() = sp.getLong(STATS_RESET, 0L)
        set(v) = sp.edit().putLong(STATS_RESET, v).apply()
    val share get() = sp.getBoolean(SHARE, false)
    val wifiOnly get() = sp.getBoolean(WIFI_ONLY, true)
    val uploadUrl get() = (sp.getString(UPLOAD_URL, null) ?: "").ifBlank { DEFAULT_UPLOAD_URL }
    /** manifest mirrors, tried in order (a custom URL in settings replaces them) */
    val manifestUrls: List<String> get() = (sp.getString(MANIFEST_URL, null) ?: "").ifBlank { null }?.let { listOf(it) } ?: DEFAULT_MANIFEST_URLS

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
        const val REWIND = "rewind"
        const val CLIPBOARD = "clipboard"
        const val DEBUG = "debug_overlay"
        const val HAPTICS = "haptics"
        const val HAPTIC_STRENGTH = "haptic_strength"
        const val STATS_RESET = "stats_reset_at"
        const val SHARE = "share_data"
        const val WIFI_ONLY = "upload_wifi_only"
        const val UPLOAD_URL = "upload_url"
        const val MANIFEST_URL = "manifest_url"
        const val UPLOAD_OFFSET = "upload_offset"
        const val INSTALL_ID = "install_id"

        /** Placeholder: the collection endpoint doesn't exist yet (docs/upload-schema.md). Uploads to it are skipped. */
        const val DEFAULT_UPLOAD_URL = "https://example.invalid/autocorrect/v1/events"
        val DEFAULT_MANIFEST_URLS = listOf(
            "https://github.com/Mostlime12195/autocorrect-models/releases/download/models-v1/manifest.json",
            "https://smitten.mostlime.hackclub.app/autocorrect-android/manifest.json",
        )
        val STYLES = listOf("casual", "standard", "formal")
    }
}
