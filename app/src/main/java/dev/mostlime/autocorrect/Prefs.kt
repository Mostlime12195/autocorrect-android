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
    /** sentence-start capitals (auto-shift + the corrector's sentence capitals) */
    val autoCap get() = sp.getBoolean(AUTO_CAP, true)
    /** "suggest" = never edit the text, only underline likely typos and suggest fixes */
    val suggestOnly get() = sp.getString(CORRECTION_MODE, "auto") == "suggest"
    val confidentOnly get() = sp.getBoolean(CONFIDENT_ONLY, false)
    val clipboard get() = sp.getBoolean(CLIPBOARD, true)
    val numberRow get() = sp.getBoolean(NUMBER_ROW, false)
    val heightPercent get() = sp.getInt(HEIGHT, 100).coerceIn(80, 130)
    val debugOverlay get() = sp.getBoolean(DEBUG, false)
    val haptics get() = sp.getBoolean(HAPTICS, true)
    val hapticStrength get() = (sp.getString(HAPTIC_STRENGTH, "1") ?: "1").toIntOrNull() ?: 1
    var statsResetAt: Long
        get() = sp.getLong(STATS_RESET, 0L)
        set(v) = sp.edit().putLong(STATS_RESET, v).apply()
    val share get() = sp.getBoolean(SHARE, false)
    val wifiOnly get() = sp.getBoolean(WIFI_ONLY, true)
    /** the custom URL from settings, else the default (older builds saved the example.invalid placeholder: ignored) */
    val uploadUrl get() = (sp.getString(UPLOAD_URL, null) ?: "").trim().takeUnless { it.isBlank() || it.contains(".invalid") } ?: DEFAULT_UPLOAD_URL
    /** manifest mirrors, tried in order (a custom URL in settings replaces them) */
    val manifestUrls: List<String> get() = (sp.getString(MANIFEST_URL, null) ?: "").ifBlank { null }?.let { listOf(it) } ?: DEFAULT_MANIFEST_URLS

    var uploadOffset: Long
        get() = sp.getLong(UPLOAD_OFFSET, 0)
        set(v) = sp.edit().putLong(UPLOAD_OFFSET, v).apply()

    val installId: String
        get() = sp.getString(INSTALL_ID, null) ?: UUID.randomUUID().toString().also { sp.edit().putString(INSTALL_ID, it).commit() }

    /** New random install ID (after "delete my data", so later uploads aren't linked to the wiped one). */
    fun rotateInstallId(): String = UUID.randomUUID().toString().also { sp.edit().putString(INSTALL_ID, it).commit() }

    /** install IDs whose server-side delete hasn't gone through yet (retried by UploadWorker before any upload) */
    var pendingDeletes: List<String>
        get() = (sp.getString(PENDING_DELETES, null) ?: "").split(',').filter { it.isNotBlank() }
        set(v) { sp.edit().putString(PENDING_DELETES, v.distinct().joinToString(",")).commit() }

    companion object {
        const val MODEL = "model"
        const val BATTERY_SAVER = "battery_saver"
        const val STYLE = "style"
        const val AUTOCORRECT = "autocorrect"
        /** removed setting (rewind is always on now): dropped from old installs at start */
        const val REWIND = "rewind"
        const val AUTO_CAP = "auto_cap"
        const val CORRECTION_MODE = "correction_mode"
        const val CONFIDENT_ONLY = "confident_only"
        const val CLIPBOARD = "clipboard"
        const val NUMBER_ROW = "number_row"
        const val HEIGHT = "kb_height"
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
        const val PENDING_DELETES = "pending_deletes"

        /** Collection endpoint (autocorrect repo collect/API.md); only used when sharing is switched on. */
        const val DEFAULT_UPLOAD_URL = "https://autocorrect.mostlime.hackclub.app/api/v1/upload"
        val DEFAULT_MANIFEST_URLS = listOf(
            "https://github.com/Mostlime12195/autocorrect-models/releases/download/models-v1/manifest.json",
            "https://autocorrect.mostlime.hackclub.app/autocorrect-android/manifest.json",
        )
        val STYLES = listOf("casual", "standard", "formal")
    }
}
