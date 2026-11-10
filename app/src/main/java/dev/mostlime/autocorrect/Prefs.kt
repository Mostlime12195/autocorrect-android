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
    /** the Lite (34M) model: default on for low-end phones (Device.lowEnd) */
    val batterySaver get() = sp.getBoolean(BATTERY_SAVER, Device.lowEnd(ctx))
    val style get() = sp.getString(STYLE, "standard") ?: "standard"
    val autocorrect get() = sp.getBoolean(AUTOCORRECT, true)
    /** sentence-start capitals (auto-shift + the corrector's sentence capitals) */
    val autoCap get() = sp.getBoolean(AUTO_CAP, true)
    /** "suggest" = never edit the text, only underline likely typos and suggest fixes */
    val suggestOnly get() = sp.getString(CORRECTION_MODE, "auto") == "suggest"
    val confidentOnly get() = sp.getBoolean(CONFIDENT_ONLY, false)
    /** "Flag likely typos": until the user sets it, on in suggest-only mode and off in auto-correct mode */
    /** suggest-only "teh → the" pill (the switch shows only when Keylime is the system spell checker) */
    val fixPill get() = sp.getBoolean(FIX_PILL, true)
    val flagTypos get() = if (sp.contains(FLAG_TYPOS)) sp.getBoolean(FLAG_TYPOS, true) else suggestOnly
    val swipe get() = sp.getBoolean(SWIPE, true)
    val clipboard get() = sp.getBoolean(CLIPBOARD, true)
    val numberRow get() = sp.getBoolean(NUMBER_ROW, false)
    /** keys a little taller than before 0.1.50 by default (more room for the corner hint) */
    val heightPercent get() = sp.getInt(HEIGHT, DEFAULT_HEIGHT).coerceIn(80, 130)
    // layout
    val layout get() = sp.getString(LAYOUT, "qwerty") ?: "qwerty"
    val emojiKey get() = sp.getBoolean(EMOJI_KEY, false)
    val commaKey get() = sp.getBoolean(COMMA_KEY, true)
    val arrowRow get() = sp.getBoolean(ARROW_ROW, false)
    /** "off", "left", "right" */
    var oneHanded: String
        get() = sp.getString(ONE_HANDED, "off") ?: "off"
        set(v) = sp.edit().putString(ONE_HANDED, v).apply()
    val splitLandscape get() = sp.getBoolean(SPLIT_LANDSCAPE, false)
    val keyRadius get() = sp.getInt(KEY_RADIUS, 9).coerceIn(0, 20)
    val showHints get() = sp.getBoolean(SHOW_HINTS, true)
    /** "key: entry entry" per line (KeyboardLayout.parseExtra) */
    val customLongPress get() = sp.getString(CUSTOM_LONGPRESS, "") ?: ""
    fun layoutOptions() = dev.mostlime.autocorrect.ime.LayoutOptions(layout, numberRow, emojiKey, commaKey, arrowRow)
    val debugOverlay get() = sp.getBoolean(DEBUG, false)
    val haptics get() = sp.getBoolean(HAPTICS, true)
    val hapticStrength get() = (sp.getString(HAPTIC_STRENGTH, "1") ?: "1").toIntOrNull() ?: 1
    var statsResetAt: Long
        get() = sp.getLong(STATS_RESET, 0L)
        set(v) = sp.edit().putLong(STATS_RESET, v).apply()
    val share get() = sp.getBoolean(SHARE, false)
    /** "Include sentence context": its own opt-in, off by default, and only ever on while sharing is on */
    val shareContext get() = share && sp.getBoolean(SHARE_CONTEXT, false)
    val wifiOnly get() = sp.getBoolean(WIFI_ONLY, true)
    /** the custom URL from settings, else the default (older builds saved the example.invalid placeholder: ignored) */
    val uploadUrl get() = (sp.getString(UPLOAD_URL, null) ?: "").trim().takeUnless { it.isBlank() || it.contains(".invalid") } ?: DEFAULT_UPLOAD_URL
    /** manifest mirrors, tried in order (a custom URL in settings replaces them) */
    val manifestUrls: List<String> get() = (sp.getString(MANIFEST_URL, null) ?: "").ifBlank { null }?.let { listOf(it) } ?: DEFAULT_MANIFEST_URLS

    // appearance
    val themeMode get() = sp.getString(THEME_MODE, "system") ?: "system"
    /** "dynamic" (Material You, default on Android 12+), "green" (default before), "custom" or a swatch "#RRGGBB" */
    val themeColor get() = sp.getString(THEME_COLOR, null) ?: if (android.os.Build.VERSION.SDK_INT >= 31) "dynamic" else "green"
    val customColor get() = sp.getString(CUSTOM_COLOR, "#1E88E5") ?: "#1E88E5"
    /** the accent as a color, or null for Material You / green */
    fun accentColor(): Int? = when (val c = themeColor) {
        "dynamic", "green" -> null
        "custom" -> parseColor(customColor)
        else -> parseColor(c)
    }
    var bgStamp: Long
        get() = sp.getLong(BG_STAMP, 0L)
        set(v) = sp.edit().putLong(BG_STAMP, v).apply()
    val bgDim get() = sp.getInt(BG_DIM, 30).coerceIn(0, 80)
    val bgBlur get() = sp.getInt(BG_BLUR, 0).coerceIn(0, 25)
    val keyAlpha get() = sp.getInt(KEY_ALPHA, 75).coerceIn(20, 100)
    val keyGap get() = sp.getInt(KEY_GAP, 3).coerceIn(1, 8)
    val hintSize get() = sp.getString(HINT_SIZE, "normal") ?: "normal"
    val longPressMs get() = sp.getInt(LONG_PRESS_MS, 380).coerceIn(200, 800)
    /** two undos of the same spelling fix add the word to the dictionary */
    val learnUndo get() = sp.getBoolean(LEARN_UNDO, true)

    // performance (settings -> Performance): a preset, or "custom" with every value set
    val perfPreset get() = sp.getString(PERF_PRESET, "balanced") ?: "balanced"
    private fun perf(key: String): Int =
        if (perfPreset == "custom" && sp.contains(key)) sp.getInt(key, 0) else (PRESETS[perfPreset] ?: PRESETS["balanced"]!!)[key] ?: 0
    /** left context for the model, tokens (64 .. 1024; the graphs clamp to MAX_POS - POS_MARGIN) */
    val ctxTokens get() = perf(CTX_TOKENS).coerceIn(64, 1024)
    /** pause in typing before the idle re-check (suggest-only sentence check / far rewind), ms */
    val idlePauseMs get() = perf(IDLE_PAUSE).coerceIn(200, 3000).toLong()
    /** also re-check every N finished words without waiting for a pause (0 = off) */
    val recheckEvery get() = perf(RECHECK_EVERY).coerceIn(0, 20)
    /** re-check the whole field (1) or the sentences near the cursor (0) */
    val wholeField get() = perf(RECHECK_FIELD) == 1
    /** ORT intra-op threads (0 = auto: the big cores) */
    val ortThreads get() = perf(ORT_THREADS).coerceIn(0, 8)
    /** candidates the model scores per word */
    val lmTopK get() = perf(LM_TOPK).coerceIn(2, 24)
    /** skip the model on confident, correctly spelled common words (1) or score every word (0) */
    val lmSkip get() = perf(LM_SKIP) == 1

    /** switch to a preset (its values) or to custom with the current values written out */
    fun setPerfPreset(p: String) {
        val e = sp.edit()
        if (p == "custom") {
            val cur = mapOf(CTX_TOKENS to ctxTokens, IDLE_PAUSE to idlePauseMs.toInt(), RECHECK_EVERY to recheckEvery,
                RECHECK_FIELD to (if (wholeField) 1 else 0), ORT_THREADS to ortThreads, LM_TOPK to lmTopK, LM_SKIP to (if (lmSkip) 1 else 0))
            for ((k, v) in cur) e.putInt(k, v)
        }
        e.putString(PERF_PRESET, p).commit()
    }

    /** in-app updates: last latest.json seen, auto-download (default on), last version notified */
    var latestJson: String?
        get() = sp.getString(LATEST_JSON, null)
        set(v) = sp.edit().putString(LATEST_JSON, v).apply()
    val autoUpdate get() = sp.getBoolean(AUTO_UPDATE, true)
    var updateNotified: Int
        get() = sp.getInt(UPDATE_NOTIFIED, 0)
        set(v) = sp.edit().putInt(UPDATE_NOTIFIED, v).apply()

    /** byte offset of the first not-yet-uploaded line of the upload queue (App.corrections) */
    var corrUploadOffset: Long
        get() = sp.getLong(CORR_UPLOAD_OFFSET, 0)
        set(v) = sp.edit().putLong(CORR_UPLOAD_OFFSET, v).apply()

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
        const val FLAG_TYPOS = "flag_typos"
        const val FIX_PILL = "fix_pill"
        const val SWIPE = "swipe_typing"
        const val CLIPBOARD = "clipboard"
        const val NUMBER_ROW = "number_row"
        const val LAYOUT = "kb_layout"
        const val EMOJI_KEY = "emoji_key"
        const val COMMA_KEY = "comma_key"
        const val ARROW_ROW = "arrow_row"
        const val ONE_HANDED = "one_handed"
        const val SPLIT_LANDSCAPE = "split_landscape"
        const val KEY_RADIUS = "key_radius"
        const val SHOW_HINTS = "show_hints"
        const val CUSTOM_LONGPRESS = "custom_longpress"
        const val HEIGHT = "kb_height"
        const val DEFAULT_HEIGHT = 108
        const val DEBUG = "debug_overlay"
        const val HAPTICS = "haptics"
        const val HAPTIC_STRENGTH = "haptic_strength"
        const val STATS_RESET = "stats_reset_at"
        const val SHARE = "share_data"
        const val SHARE_CONTEXT = "share_context"
        const val WIFI_ONLY = "upload_wifi_only"
        const val UPLOAD_URL = "upload_url"
        const val MANIFEST_URL = "manifest_url"
        /** the v1 uploader's cursor into the local log (removed at start) */
        const val UPLOAD_OFFSET = "upload_offset"
        const val CORR_UPLOAD_OFFSET = "corr_upload_offset"
        const val INSTALL_ID = "install_id"
        const val PENDING_DELETES = "pending_deletes"
        const val THEME_MODE = "theme_mode"
        const val THEME_COLOR = "theme_color"
        const val CUSTOM_COLOR = "custom_color"
        const val BG_STAMP = "kb_bg_stamp"
        const val BG_DIM = "bg_dim"
        const val BG_BLUR = "bg_blur"
        const val KEY_ALPHA = "key_alpha"
        const val KEY_GAP = "key_gap"
        const val HINT_SIZE = "hint_size"
        const val LONG_PRESS_MS = "longpress_ms"
        const val LEARN_UNDO = "learn_undo"
        const val LATEST_JSON = "update_latest_json"
        const val PERF_PRESET = "perf_preset"
        const val CTX_TOKENS = "ctx_tokens"
        const val IDLE_PAUSE = "idle_pause_ms"
        const val RECHECK_EVERY = "recheck_every"
        const val RECHECK_FIELD = "recheck_field"
        const val ORT_THREADS = "ort_threads"
        const val LM_TOPK = "lm_topk"
        const val LM_SKIP = "lm_skip"
        /** Balanced = the behaviour before these settings, except the context: 256 tokens (1024 measured ~4x slower per word) */
        const val BALANCED_CTX = 256
        val PRESETS: Map<String, Map<String, Int>> = mapOf(
            "battery" to mapOf(CTX_TOKENS to 64, IDLE_PAUSE to 1500, RECHECK_EVERY to 0, RECHECK_FIELD to 0, ORT_THREADS to 2, LM_TOPK to 4, LM_SKIP to 1),
            "balanced" to mapOf(CTX_TOKENS to BALANCED_CTX, IDLE_PAUSE to 400, RECHECK_EVERY to 0, RECHECK_FIELD to 0, ORT_THREADS to 0, LM_TOPK to 6, LM_SKIP to 1),
            "max" to mapOf(CTX_TOKENS to 1024, IDLE_PAUSE to 200, RECHECK_EVERY to 2, RECHECK_FIELD to 1, ORT_THREADS to 0, LM_TOPK to 12, LM_SKIP to 0),
        )
        const val AUTO_UPDATE = "auto_update"
        const val UPDATE_NOTIFIED = "update_notified"

        /** Collection endpoint (autocorrect repo collect/API.md); only used when sharing is switched on. */
        const val DEFAULT_UPLOAD_URL = "https://autocorrect.mostlime.hackclub.app/api/v1/upload"
        val DEFAULT_MANIFEST_URLS = listOf(
            "https://github.com/Mostlime12195/keylime-models/releases/download/models-v1/manifest.json",
            "https://autocorrect.mostlime.hackclub.app/autocorrect-android/manifest.json",
        )
        val STYLES = listOf("casual", "standard", "formal")
        val SWATCHES = listOf("Blue" to "#1E88E5", "Teal" to "#00897B", "Green" to "#43A047", "Lime" to "#7CB342", "Amber" to "#FFB300",
            "Orange" to "#FB8C00", "Red" to "#E53935", "Pink" to "#D81B60", "Purple" to "#8E24AA", "Indigo" to "#3949AB")
        fun parseColor(s: String): Int? = runCatching {
            val h = s.trim().removePrefix("#")
            if (h.length != 6) null else (0xFF000000 or h.toLong(16)).toInt()
        }.getOrNull()
        /** the background image (already cropped / scaled down when picked) */
        fun bgFile(ctx: Context) = java.io.File(ctx.filesDir, "kb_bg.jpg")
    }
}
