package dev.mostlime.autocorrect.ui

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.work.WorkInfo
import androidx.work.WorkManager
import dev.mostlime.autocorrect.App
import dev.mostlime.autocorrect.BuildConfig
import dev.mostlime.autocorrect.Prefs
import dev.mostlime.autocorrect.R
import dev.mostlime.autocorrect.log.LogStats
import dev.mostlime.autocorrect.log.UploadWorker
import dev.mostlime.autocorrect.model.DownloadWorker
import dev.mostlime.autocorrect.update.Updater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsActivity : AppCompatActivity(), PreferenceFragmentCompat.OnPreferenceStartFragmentCallback {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        val root = findViewById<View>(R.id.root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(b.left, b.top, b.right, b.bottom); insets
        }
        setSupportActionBar(findViewById(R.id.toolbar))
        if (savedInstanceState == null) supportFragmentManager.beginTransaction().replace(R.id.prefs, Fragment()).commit()
        supportFragmentManager.addOnBackStackChangedListener {
            supportActionBar?.setDisplayHomeAsUpEnabled(supportFragmentManager.backStackEntryCount > 0)
            if (supportFragmentManager.backStackEntryCount == 0) supportActionBar?.title = getString(R.string.app_name)
        }
    }

    override fun onSupportNavigateUp(): Boolean { onBackPressedDispatcher.onBackPressed(); return true }

    /** a sub-screen (Typing, Correction, ...): the same fragment class with another XML */
    override fun onPreferenceStartFragment(caller: PreferenceFragmentCompat, pref: Preference): Boolean {
        val f = Fragment().apply { arguments = Bundle().apply { putString(Fragment.SCREEN, pref.key); putString(Fragment.TITLE, pref.title?.toString()) } }
        supportFragmentManager.beginTransaction().replace(R.id.prefs, f).addToBackStack(pref.key).commit()
        supportActionBar?.title = pref.title
        return true
    }

    class Fragment : PreferenceFragmentCompat() {
        private val app get() = App.instance

        private val screen get() = arguments?.getString(SCREEN)

        companion object {
            const val SCREEN = "screen"
            const val TITLE = "title"
        }

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(when (screen) {
                "screen_typing" -> R.xml.prefs_typing
                "screen_correction" -> R.xml.prefs_correction
                "screen_dictionary" -> R.xml.prefs_dictionary
                "screen_appearance" -> R.xml.prefs_appearance
                "screen_model" -> R.xml.prefs_model
                "screen_privacy" -> R.xml.prefs_privacy
                "screen_updates" -> R.xml.prefs_updates
                "screen_about" -> R.xml.prefs_about
                "screen_performance" -> R.xml.prefs_performance
                else -> R.xml.prefs
            }, rootKey)
            setupAppearance()
            setupPerformance()
            val m = app.store.manifest()
            findPreference<ListPreference>(Prefs.MODEL)?.apply {
                entries = m.models.map { "${it.label} (${it.downloadBytes / 1_000_000} MB)" }.toTypedArray()
                entryValues = m.models.map { it.id }.toTypedArray()
                if (value == null || m.model(value) == null) value = m.default
                setOnPreferenceChangeListener { _, _ -> view?.post { onModelChanged() }; true }
            }
            findPreference<androidx.preference.SwitchPreferenceCompat>(Prefs.BATTERY_SAVER)?.apply {
                isChecked = app.prefs.batterySaver
                val lite = app.store.manifest().let { m -> m.model(m.batterySaver) }
                isVisible = lite != null
                val why = dev.mostlime.autocorrect.Device.reason(requireContext())
                summary = "Smaller and faster (m1 34M), a bit less accurate." +
                    if (why != null) " On by default because $why." else ""
                setOnPreferenceChangeListener { _, _ -> view?.post { onModelChanged() }; true }
            }
            findPreference<ModelCard>("model_card")?.onAction = { DownloadWorker.start(requireContext(), app.store.activeSpec().id) }
            findPreference<Preference>("export")?.setOnPreferenceClickListener { export(); true }
            findPreference<Preference>("stats_reset")?.setOnPreferenceClickListener {
                app.prefs.statsResetAt = System.currentTimeMillis(); refresh(); true
            }
            findPreference<Preference>("clear")?.setOnPreferenceClickListener {
                MaterialAlertDialogBuilder(requireContext()).setTitle("Clear local data?")
                    .setMessage("Deletes the correction log stored on this phone. Already uploaded data is not affected.")
                    .setPositiveButton("Clear") { _, _ ->
                        lifecycleScope.launch { withContext(Dispatchers.IO) { app.events.clear() }; refresh() }
                    }.setNegativeButton("Cancel", null).show()
                true
            }
            findPreference<Preference>(Prefs.SHARE)?.setOnPreferenceChangeListener { _, on ->
                // opt-in applies to new corrections only; switching it off drops whatever is still queued
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        if (on != true) app.corrections.clear()
                        app.prefs.corrUploadOffset = app.corrections.endOffset()
                    }
                    refresh()
                }
                // sentence context is its own opt-in: it never comes back on by itself when sharing is switched on again
                if (on != true) findPreference<androidx.preference.SwitchPreferenceCompat>(Prefs.SHARE_CONTEXT)?.isChecked = false
                view?.post { UploadWorker.schedule(requireContext(), app.prefs) }
                true
            }
            findPreference<Preference>(Prefs.SHARE_CONTEXT)?.setOnPreferenceChangeListener { _, on ->
                // switching it off takes the sentences out of everything still queued (the uploader strips them too)
                if (on != true) lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        app.prefs.corrUploadOffset = app.corrections.rewrite(app.prefs.corrUploadOffset, dev.mostlime.autocorrect.log.EventLog::withoutContext)
                    }
                    refresh()
                }
                true
            }
            findPreference<Preference>("upload_preview")?.setOnPreferenceClickListener { showUploadPreview(); true }
            val confident = findPreference<Preference>(Prefs.CONFIDENT_ONLY)
            confident?.isEnabled = !app.prefs.suggestOnly
            // not persisted by the widget: the default follows the mode until the user sets it
            val flagSw = findPreference<androidx.preference.SwitchPreferenceCompat>(Prefs.FLAG_TYPOS)?.apply {
                isChecked = app.prefs.flagTypos
                summary = "Underline words that look mistyped (after a pause in typing) and offer a \"teh → the\" chip at the side of the strip"
                setOnPreferenceChangeListener { _, v -> app.prefs.sp.edit().putBoolean(Prefs.FLAG_TYPOS, v as Boolean).apply(); true }
            }
            findPreference<Preference>(Prefs.CORRECTION_MODE)?.setOnPreferenceChangeListener { _, v ->
                confident?.isEnabled = v != "suggest"
                if (!app.prefs.sp.contains(Prefs.FLAG_TYPOS)) flagSw?.isChecked = v == "suggest"
                true
            }
            findPreference<Preference>("spell_checker")?.setOnPreferenceClickListener { openSpellCheckerSettings(); true }
            findPreference<Preference>("copy_diag")?.setOnPreferenceClickListener {
                val f = java.io.File(requireContext().filesDir, "diag.txt")
                val text = (if (f.exists()) f.readText() else "kb: (open the keyboard once first)") +
                    "\nsettings: app=${BuildConfig.VERSION_NAME} device=${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} sdk=${android.os.Build.VERSION.SDK_INT}"
                val cm = requireContext().getSystemService(android.content.ClipboardManager::class.java)
                cm.setPrimaryClip(android.content.ClipData.newPlainText("Keylime diagnostics", text))
                Toast.makeText(requireContext(), "Diagnostics copied", Toast.LENGTH_SHORT).show()
                true
            }
            findPreference<Preference>("clear_learned")?.setOnPreferenceClickListener {
                app.user.clearLearned(); Toast.makeText(requireContext(), "Undone edits forgotten", Toast.LENGTH_SHORT).show(); true
            }
            findPreference<Preference>("refresh_models")?.setOnPreferenceClickListener {
                lifecycleScope.launch {
                    val ok = withContext(Dispatchers.IO) { runCatching { app.store.refreshManifest(app.prefs.manifestUrls) }.getOrDefault(false) }
                    Toast.makeText(requireContext(), if (ok) "Model list updated" else "Couldn't fetch the model list", Toast.LENGTH_SHORT).show()
                    refresh()
                }
                true
            }
            findPreference<Preference>("dictionary")?.setOnPreferenceClickListener {
                startActivity(Intent(requireContext(), DictionaryActivity::class.java)); true
            }
            findPreference<Preference>("delete_uploaded")?.setOnPreferenceClickListener {
                MaterialAlertDialogBuilder(requireContext()).setTitle("Delete your uploaded data?")
                    .setMessage("Deletes everything this phone has uploaded from the server and clears the log and personal dictionary on this phone. " +
                        "If sharing stays on, future uploads use a new random ID that isn't linked to the old data.")
                    .setPositiveButton("Delete") { _, _ ->
                        lifecycleScope.launch {
                            val ok = withContext(Dispatchers.IO) { UploadWorker.deleteMyData(app) }
                            context?.let {
                                Toast.makeText(it, if (ok) "Your uploaded data was deleted"
                                    else "Couldn't reach the server. The delete will be retried automatically.", Toast.LENGTH_LONG).show()
                            }
                            refresh()
                        }
                    }.setNegativeButton("Cancel", null).show()
                true
            }
            findPreference<Preference>(Prefs.WIFI_ONLY)?.setOnPreferenceChangeListener { _, _ -> view?.post { UploadWorker.schedule(requireContext(), app.prefs) }; true }
            findPreference<androidx.preference.EditTextPreference>(Prefs.UPLOAD_URL)?.apply {
                // older builds saved the example.invalid placeholder here
                if (text.isNullOrBlank() || text!!.contains(".invalid")) text = Prefs.DEFAULT_UPLOAD_URL
                summaryProvider = Preference.SummaryProvider<androidx.preference.EditTextPreference> { app.prefs.uploadUrl }
            }
            findPreference<Preference>("version")?.summary = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
            findPreference<Preference>("check_updates")?.setOnPreferenceClickListener { checkUpdates(manual = true); true }
            findPreference<Preference>("update_banner")?.setOnPreferenceClickListener { Updater.pending()?.let { installUpdate(it) }; true }
            showUpdate()
            if (screen == null || screen == "screen_updates") checkUpdates(manual = false)
            // notifications for updates found in the background (Android 13+ asks once)
            if (android.os.Build.VERSION.SDK_INT >= 33 && savedInstanceState == null &&
                requireContext().checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                notifPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)

            WorkManager.getInstance(requireContext()).getWorkInfosForUniqueWorkLiveData(DownloadWorker.NAME).observe(this) { infos ->
                showDownload(infos.firstOrNull())
            }
            // first run: fetch the manifest and start the default model download
            if (screen == null) lifecycleScope.launch {
                withContext(Dispatchers.IO) { app.store.refreshManifest(app.prefs.manifestUrls) }
                val spec = app.store.activeSpec()
                if (!app.store.isInstalled(spec)) DownloadWorker.start(requireContext(), spec.id)
                refresh()
            }
        }

        override fun onResume() {
            super.onResume()
            if (screen == "screen_performance") { loadPerf(); view?.postDelayed(meterTick, 2000) }
            arguments?.getString(TITLE)?.let { (activity as? AppCompatActivity)?.supportActionBar?.apply { title = it; setDisplayHomeAsUpEnabled(true) } }
            refresh()
        }

        private fun onModelChanged() {
            val spec = app.store.activeSpec()
            if (!app.store.isInstalled(spec)) DownloadWorker.start(requireContext(), spec.id)
            refresh()
        }

        private var download: WorkInfo? = null

        private fun showDownload(w: WorkInfo?) { download = w; refresh() }

        /** the system spell checker screen (no public action: the Settings activity, else Languages & input) */
        private fun openSpellCheckerSettings() {
            val tries = listOf(
                Intent().setClassName("com.android.settings", "com.android.settings.Settings\$SpellCheckersSettingsActivity"),
                Intent(Settings.ACTION_INPUT_METHOD_SETTINGS),
                Intent(Settings.ACTION_SETTINGS),
            )
            for (i in tries) if (runCatching { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) return
        }

        private fun keylimeIsSpellChecker(): Boolean {
            val sel = runCatching { Settings.Secure.getString(requireContext().contentResolver, "selected_spell_checker") }.getOrNull().orEmpty()
            return sel.startsWith(requireContext().packageName + "/")
        }

        private fun spellCheckerSummary(): String {
            val ours = keylimeIsSpellChecker()
            return (if (ours) "On: Keylime is the spell checker. " else "") +
                "Red underlines with a fix menu in apps' text fields. Pick Keylime in Settings → System → Languages → Spell checker."
        }

        // ------------------------------------------------------------------ performance
        /** widgets of the Performance screen: not persisted by themselves; a change switches the preset to custom */
        private fun setupPerformance() {
            val preset = findPreference<ListPreference>(Prefs.PERF_PRESET) ?: return
            val p = app.prefs
            preset.value = p.perfPreset
            preset.setOnPreferenceChangeListener { _, v -> p.setPerfPreset(v as String); view?.post { loadPerf() }; true }
            fun custom(key: String, v: Int) {
                if (p.perfPreset != "custom") p.setPerfPreset("custom")
                p.sp.edit().putInt(key, v).commit()
                preset.value = "custom"
                view?.post { loadPerf() }
            }
            for (k in listOf(Prefs.CTX_TOKENS, Prefs.LM_TOPK, Prefs.IDLE_PAUSE, Prefs.RECHECK_EVERY))
                findPreference<androidx.preference.SeekBarPreference>(k)?.setOnPreferenceChangeListener { _, v -> custom(k, v as Int); true }
            for (k in listOf(Prefs.LM_SKIP, Prefs.RECHECK_FIELD))
                findPreference<androidx.preference.SwitchPreferenceCompat>(k)?.setOnPreferenceChangeListener { _, v -> custom(k, if (v as Boolean) 1 else 0); true }
            findPreference<ListPreference>(Prefs.ORT_THREADS)?.setOnPreferenceChangeListener { _, v -> custom(Prefs.ORT_THREADS, (v as String).toInt()); true }
            findPreference<Preference>("perf_meter_reset")?.setOnPreferenceClickListener {
                dev.mostlime.autocorrect.ime.PerfStats.file(requireContext()).delete(); showMeter(); true
            }
            loadPerf()
        }

        private fun loadPerf() {
            val p = app.prefs
            findPreference<androidx.preference.SeekBarPreference>(Prefs.CTX_TOKENS)?.value = p.ctxTokens
            findPreference<androidx.preference.SeekBarPreference>(Prefs.LM_TOPK)?.value = p.lmTopK
            findPreference<androidx.preference.SeekBarPreference>(Prefs.IDLE_PAUSE)?.value = p.idlePauseMs.toInt()
            findPreference<androidx.preference.SeekBarPreference>(Prefs.RECHECK_EVERY)?.value = p.recheckEvery
            findPreference<androidx.preference.SwitchPreferenceCompat>(Prefs.LM_SKIP)?.isChecked = p.lmSkip
            findPreference<androidx.preference.SwitchPreferenceCompat>(Prefs.RECHECK_FIELD)?.isChecked = p.wholeField
            findPreference<ListPreference>(Prefs.ORT_THREADS)?.value = p.ortThreads.toString()
            findPreference<ListPreference>(Prefs.PERF_PRESET)?.value = p.perfPreset
            showMeter()
        }

        /** the keyboard process writes perf_stats.json every few seconds while it works */
        private fun showMeter() {
            val pref = findPreference<Preference>("perf_meter") ?: return
            val f = dev.mostlime.autocorrect.ime.PerfStats.file(requireContext())
            val o = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(f.readText()) as kotlinx.serialization.json.JsonObject }.getOrNull()
            if (o == null) { pref.summary = "No numbers yet: type something with the keyboard."; return }
            fun d(k: String) = (o[k] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toDoubleOrNull() ?: 0.0
            val interval = app.prefs.idlePauseMs
            val sb = StringBuilder()
            sb.append("Per word: %.0f ms average, %.0f ms p95 (%d words)".format(d("word_avg"), d("word_p95"), d("words").toInt()))
            if (d("passes") > 0) sb.append("\nRe-check: %.0f ms per pass (p95 %.0f), %d in the last minute".format(d("pass_avg"), d("pass_p95"), d("passes_per_min").toInt()))
            if (d("cpu") >= 0) sb.append("\nKeyboard CPU: %.0f%% of one core".format(d("cpu")))
            if (d("pass_avg") > interval) sb.append("\n⚠ Falling behind: a re-check takes longer than the %d ms pause. Try a shorter context or a longer pause.".format(interval))
            val age = (System.currentTimeMillis() - d("time").toLong()) / 1000
            if (age > 30) sb.append("\n(%d s ago)".format(age))
            pref.summary = sb.toString()
        }

        private val meterTick = object : Runnable {
            override fun run() { if (isResumed && screen == "screen_performance") { showMeter(); view?.postDelayed(this, 2000) } }
        }

        // ------------------------------------------------------------------ appearance
        private val pickImage = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri == null) return@registerForActivityResult
            // read now, in this activity: the picker's URI grant is ours only while we hold it
            val ctx = requireContext().applicationContext
            val width = resources.displayMetrics.widthPixels.coerceAtMost(1440)
            lifecycleScope.launch {
                val err = withContext(Dispatchers.IO) { BackgroundImage.save(ctx, uri, Prefs.bgFile(ctx), width) }
                if (err == null) app.prefs.bgStamp = System.currentTimeMillis()
                Toast.makeText(ctx, if (err == null) "Background set" else "Couldn't open that image ($err)", Toast.LENGTH_LONG).show()
                refreshAppearance()
            }
        }

        private fun setupAppearance() {
            findPreference<ListPreference>(Prefs.THEME_COLOR)?.apply {
                val dyn = android.os.Build.VERSION.SDK_INT >= 31
                val names = (if (dyn) listOf("Material You (wallpaper colors)") else emptyList()) + listOf("Green (original)") +
                    Prefs.SWATCHES.map { it.first } + listOf("Custom")
                val values = (if (dyn) listOf("dynamic") else emptyList()) + listOf("green") + Prefs.SWATCHES.map { it.second } + listOf("custom")
                entries = names.toTypedArray(); entryValues = values.toTypedArray()
                if (value == null) value = app.prefs.themeColor
                setOnPreferenceChangeListener { _, _ -> view?.post { refreshAppearance() }; true }
            }
            findPreference<Preference>(Prefs.CUSTOM_COLOR)?.setOnPreferenceClickListener { colorDialog(); true }
            findPreference<Preference>("kb_bg")?.setOnPreferenceClickListener {
                pickImage.launch(androidx.activity.result.PickVisualMediaRequest(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly)); true
            }
            findPreference<Preference>("kb_bg_remove")?.setOnPreferenceClickListener {
                Prefs.bgFile(requireContext()).delete(); app.prefs.bgStamp = 0L; refreshAppearance(); true
            }
            findPreference<Preference>(Prefs.BG_BLUR)?.isVisible = android.os.Build.VERSION.SDK_INT >= 31
            findPreference<androidx.preference.EditTextPreference>(Prefs.CUSTOM_LONGPRESS)?.apply {
                setOnBindEditTextListener { it.isSingleLine = false; it.minLines = 3; it.setHorizontallyScrolling(false) }
                summaryProvider = Preference.SummaryProvider<androidx.preference.EditTextPreference> { p ->
                    val n = dev.mostlime.autocorrect.ime.KeyboardLayout.parseExtra(p.text.orEmpty()).size
                    if (n == 0) "Add your own characters or strings to any key's popup" else "Entries on $n key${if (n == 1) "" else "s"}"
                }
            }
            refreshAppearance()
        }

        private fun refreshAppearance() {
            val hasBg = app.prefs.bgStamp > 0 && Prefs.bgFile(requireContext()).exists()
            findPreference<Preference>("kb_bg")?.summary = if (hasBg) "Set. Tap to pick another image." else "Pick an image (no storage permission needed)"
            findPreference<Preference>("kb_bg_remove")?.isVisible = hasBg
            for (k in listOf(Prefs.BG_DIM, Prefs.BG_BLUR, Prefs.KEY_ALPHA)) findPreference<Preference>(k)?.isEnabled = hasBg
            findPreference<Preference>(Prefs.CUSTOM_COLOR)?.apply {
                isVisible = app.prefs.themeColor == "custom"
                summary = app.prefs.customColor.uppercase()
            }
        }

        /** hex field + hue / saturation / brightness sliders with a preview swatch */
        private fun colorDialog() {
            val ctx = requireContext()
            val dp = resources.displayMetrics.density
            val hsv = FloatArray(3)
            android.graphics.Color.colorToHSV(Prefs.parseColor(app.prefs.customColor) ?: 0xFF1E88E5.toInt(), hsv)
            val box = android.widget.LinearLayout(ctx).apply { orientation = android.widget.LinearLayout.VERTICAL; setPadding((24 * dp).toInt(), (8 * dp).toInt(), (24 * dp).toInt(), 0) }
            val swatch = View(ctx)
            box.addView(swatch, android.widget.LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, (48 * dp).toInt()))
            val hex = android.widget.EditText(ctx).apply { isSingleLine = true; hint = "#RRGGBB" }
            box.addView(hex)
            var updating = false
            fun color() = android.graphics.Color.HSVToColor(hsv)
            val bars = listOf("Hue" to 360, "Saturation" to 100, "Brightness" to 100).mapIndexed { i, (label, max) ->
                box.addView(android.widget.TextView(ctx).apply { text = label })
                android.widget.SeekBar(ctx).apply { this.max = max; progress = if (i == 0) hsv[0].toInt() else (hsv[i] * 100).toInt() }.also { box.addView(it) }
            }
            fun show() {
                updating = true
                swatch.setBackgroundColor(color())
                val h = "#%06X".format(color() and 0xFFFFFF)
                if (hex.text.toString().uppercase() != h) hex.setText(h)
                updating = false
            }
            bars.forEachIndexed { i, b ->
                b.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: android.widget.SeekBar?, p: Int, fromUser: Boolean) {
                        if (!fromUser) return
                        hsv[i] = if (i == 0) p.toFloat() else p / 100f; show()
                    }
                    override fun onStartTrackingTouch(s: android.widget.SeekBar?) {}
                    override fun onStopTrackingTouch(s: android.widget.SeekBar?) {}
                })
            }
            hex.addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(e: android.text.Editable?) {
                    if (updating) return
                    val c = Prefs.parseColor(e.toString()) ?: return
                    android.graphics.Color.colorToHSV(c, hsv)
                    bars[0].progress = hsv[0].toInt(); bars[1].progress = (hsv[1] * 100).toInt(); bars[2].progress = (hsv[2] * 100).toInt()
                    swatch.setBackgroundColor(c)
                }
            })
            show()
            MaterialAlertDialogBuilder(ctx).setTitle("Custom color").setView(box)
                .setPositiveButton("Use") { _, _ ->
                    app.prefs.sp.edit().putString(Prefs.CUSTOM_COLOR, "#%06X".format(color() and 0xFFFFFF)).putString(Prefs.THEME_COLOR, "custom").apply()
                    findPreference<ListPreference>(Prefs.THEME_COLOR)?.value = "custom"
                    refreshAppearance()
                }.setNegativeButton("Cancel", null).show()
        }

        private val notifPermission = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) {}

        private fun showUpdate(status: String? = null) {
            val u = Updater.pending()
            findPreference<Preference>("update_banner")?.apply {
                isVisible = u != null
                if (u != null) {
                    title = "Update to ${u.version}"
                    summary = status ?: ((if (Updater.isDownloaded(requireContext(), u)) "Downloaded. Tap to install." else "Tap to download and install.") +
                        if (u.notes.isNotBlank()) "\n${u.notes}" else "")
                }
            }
        }

        private fun checkUpdates(manual: Boolean) {
            lifecycleScope.launch {
                if (manual) findPreference<Preference>("check_updates")?.summary = "Checking…"
                val u = withContext(Dispatchers.IO) { Updater.check(requireContext().applicationContext) }
                findPreference<Preference>("check_updates")?.summary = when {
                    u == null -> if (manual) "Couldn't reach the update server" else null
                    u.newer -> "${u.version} is available"
                    else -> "Up to date (${BuildConfig.VERSION_NAME})"
                }
                showUpdate()
                if (u != null && u.newer && app.prefs.autoUpdate && !Updater.isDownloaded(requireContext(), u)) {
                    val cm = requireContext().getSystemService(android.net.ConnectivityManager::class.java)
                    if (!cm.isActiveNetworkMetered) withContext(Dispatchers.IO) { Updater.download(requireContext().applicationContext, u) }
                    showUpdate()
                }
            }
        }

        private fun installUpdate(u: dev.mostlime.autocorrect.update.UpdateInfo) {
            val ctx = requireContext()
            if (!Updater.canInstall(ctx)) {
                MaterialAlertDialogBuilder(ctx).setTitle("Allow updates from this app")
                    .setMessage("Android needs your OK once: turn on \"Allow from this source\" for Keylime, then come back and tap the update again.")
                    .setPositiveButton("Open settings") { _, _ -> startActivity(Updater.allowInstallsIntent(ctx)) }
                    .setNegativeButton("Cancel", null).show()
                return
            }
            lifecycleScope.launch {
                showUpdate("Downloading…")
                val f = withContext(Dispatchers.IO) {
                    Updater.download(ctx.applicationContext, u) { done, total ->
                        if (total > 0) view?.post { showUpdate("Downloading ${100 * done / total}%") }
                    }
                }
                if (f == null) { showUpdate("Download failed (or the file didn't match). Tap to try again."); return@launch }
                showUpdate("Installing…")
                withContext(Dispatchers.IO) { runCatching { Updater.install(ctx.applicationContext, f) } }
                    .onFailure { showUpdate("Install failed: ${it.message}") }
            }
        }

        private fun mb(b: Long) = "${(b + 500_000) / 1_000_000} MB"

        private fun refresh() {
            val ctx = context ?: return
            val imm = ctx.getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            val enabled = imm.enabledInputMethodList.any { it.packageName == ctx.packageName }
            val current = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)?.startsWith(ctx.packageName) == true
            val spec = app.store.activeSpec()
            val installed = app.store.isInstalled(spec)
            val w = download
            val running = w?.state == WorkInfo.State.RUNNING || w?.state == WorkInfo.State.ENQUEUED
            findPreference<SetupCard>("setup_card")?.isVisible = !(enabled && current && installed)
            findPreference<SetupCard>("setup_card")?.steps = listOf(
                SetupCard.Step("Enable the keyboard", if (enabled) "Enabled" else "Turn on \"Keylime\" in system settings", enabled, "Enable") {
                    startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
                },
                SetupCard.Step("Switch to it", if (current) "In use" else "Pick \"Keylime\" as your keyboard", current, "Switch") {
                    imm.showInputMethodPicker()
                },
                SetupCard.Step("Download the model", if (installed) "${spec.label} is ready" else if (running) "Downloading…" else "${mb(spec.downloadBytes)}, Wi-Fi recommended",
                    installed, if (running) null else "Download") { DownloadWorker.start(ctx, spec.id) },
            )
            findPreference<ModelCard>("model_card")?.apply {
                name = spec.label
                size = mb(spec.downloadBytes)
                when {
                    installed -> { status = "Installed · runs on this phone, nothing leaves it"; progress = -1; action = null }
                    running -> {
                        val done = w!!.progress.getLong(DownloadWorker.KEY_DONE, 0)
                        val total = w.progress.getLong(DownloadWorker.KEY_TOTAL, spec.downloadBytes)
                        status = if (done > 0) "Downloading ${mb(done)} of ${mb(total)}" else "Waiting for network…"
                        progress = if (done > 0) (100 * done / maxOf(total, 1)).toInt() else -2
                        action = null
                    }
                    w?.state == WorkInfo.State.FAILED -> {
                        status = "Download failed: ${w.outputData.getString(DownloadWorker.KEY_ERROR)}"; progress = -1; action = "Retry"
                    }
                    else -> { status = "Not downloaded yet"; progress = -1; action = "Download" }
                }
                update()
            }
            lifecycleScope.launch {
                val st = withContext(Dispatchers.IO) { LogStats.compute(app.events.file, BuildConfig.VERSION_NAME, app.prefs.statsResetAt) }
                findPreference<Preference>("stats")?.summary = st
            }
            findPreference<Preference>("spell_checker")?.summary = spellCheckerSummary()
            findPreference<Preference>(Prefs.FIX_PILL)?.isVisible = keylimeIsSpellChecker()
            app.user.reloadIfChanged()
            findPreference<Preference>("dictionary")?.summary = when (val n = app.user.size) {
                0 -> "Words that are never corrected or flagged"
                1 -> "1 word"
                else -> "$n words"
            }
            lifecycleScope.launch {
                val (n, bytes) = withContext(Dispatchers.IO) { app.events.count() to app.events.sizeBytes() }
                findPreference<Preference>("log_info")?.summary = "$n events, ${bytes / 1024} KB, stored on this phone (never uploaded)" +
                    (if (app.prefs.pendingDeletes.isNotEmpty()) ". Server delete pending, retried automatically." else "")
            }
            lifecycleScope.launch {
                val waiting = withContext(Dispatchers.IO) { app.corrections.readFrom(app.prefs.corrUploadOffset, 10_000, 4 shl 20).first.size }
                findPreference<Preference>("upload_preview")?.summary =
                    if (!app.prefs.share) "Sharing is off: nothing is queued" else "$waiting waiting to upload. Tap to see the last 20 queued events, exactly as they will be sent" +
                        (if (app.prefs.shareContext) " (with sentence context)" else "")
            }
        }

        /** the last ~20 queued upload events, verbatim (one JSON object each) */
        private fun showUploadPreview() {
            lifecycleScope.launch {
                // exactly what the uploader sends: without sentence context unless that opt-in is on right now
                val ctx = app.prefs.shareContext
                val lines = withContext(Dispatchers.IO) { app.corrections.tail(20) }.map { dev.mostlime.autocorrect.log.UploadClient.outgoing(it, ctx) }
                val text = if (lines.isEmpty()) {
                    if (app.prefs.share) "Nothing queued yet. Each correction you make while sharing is on shows up here before it's sent."
                    else "Sharing is off, so nothing is queued or sent."
                } else lines.asReversed().joinToString("\n\n") { l ->
                    runCatching { prettyJson.encodeToString(kotlinx.serialization.json.JsonElement.serializer(), kotlinx.serialization.json.Json.parseToJsonElement(l)) }.getOrDefault(l)
                }
                val tv = android.widget.TextView(requireContext()).apply {
                    setText(text); setTextIsSelectable(true); typeface = android.graphics.Typeface.MONOSPACE; textSize = 12f
                    val p = (16 * resources.displayMetrics.density).toInt(); setPadding(p, p / 2, p, p / 2)
                }
                MaterialAlertDialogBuilder(requireContext()).setTitle("Upload preview (newest first)")
                    .setView(android.widget.ScrollView(requireContext()).apply { addView(tv) })
                    .setPositiveButton("Close", null).show()
            }
        }

        private val prettyJson = kotlinx.serialization.json.Json { prettyPrint = true }

        private fun export() {
            lifecycleScope.launch {
                val f = withContext(Dispatchers.IO) { app.events.exportCopy() }
                if (f.length() == 0L) { Toast.makeText(requireContext(), "The log is empty", Toast.LENGTH_SHORT).show(); return@launch }
                val uri = FileProvider.getUriForFile(requireContext(), "${requireContext().packageName}.files", f)
                val send = Intent(Intent.ACTION_SEND).setType("application/jsonl").putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                startActivity(Intent.createChooser(send, "Export autocorrect data"))
            }
        }
    }
}
