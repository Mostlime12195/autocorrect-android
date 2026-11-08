package dev.mostlime.autocorrect.ui

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
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
import dev.mostlime.autocorrect.ime.EngineHolder
import dev.mostlime.autocorrect.log.UploadWorker
import dev.mostlime.autocorrect.model.DownloadWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        val root = findViewById<View>(R.id.root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(b.left, 0, b.right, b.bottom); insets
        }
        if (savedInstanceState == null) supportFragmentManager.beginTransaction().replace(R.id.prefs, Fragment()).commit()
    }

    class Fragment : PreferenceFragmentCompat() {
        private val app get() = App.instance

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.prefs, rootKey)
            val m = app.store.manifest()
            findPreference<ListPreference>(Prefs.MODEL)!!.apply {
                entries = m.models.map { "${it.label} (${it.downloadBytes / 1_000_000} MB)" }.toTypedArray()
                entryValues = m.models.map { it.id }.toTypedArray()
                if (value == null) value = m.default
                setOnPreferenceChangeListener { _, _ -> view?.post { onModelChanged() }; true }
            }
            findPreference<Preference>(Prefs.BATTERY_SAVER)!!.setOnPreferenceChangeListener { _, _ -> view?.post { onModelChanged() }; true }
            findPreference<Preference>(Prefs.STYLE)!!.setOnPreferenceChangeListener { _, _ -> view?.post { EngineHolder.reloadIfNeeded(app) }; true }
            findPreference<Preference>("enable_keyboard")!!.setOnPreferenceClickListener {
                startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)); true
            }
            findPreference<Preference>("switch_keyboard")!!.setOnPreferenceClickListener {
                (requireContext().getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker(); true
            }
            findPreference<Preference>("model_status")!!.setOnPreferenceClickListener {
                DownloadWorker.start(requireContext(), app.store.activeSpec().id); true
            }
            findPreference<Preference>("export")!!.setOnPreferenceClickListener { export(); true }
            findPreference<Preference>("clear")!!.setOnPreferenceClickListener {
                AlertDialog.Builder(requireContext()).setTitle("Clear local data?")
                    .setMessage("Deletes the correction log stored on this phone. Already uploaded data is not affected.")
                    .setPositiveButton("Clear") { _, _ ->
                        lifecycleScope.launch { withContext(Dispatchers.IO) { app.events.clear() }; refresh() }
                    }.setNegativeButton("Cancel", null).show()
                true
            }
            findPreference<Preference>(Prefs.SHARE)!!.setOnPreferenceChangeListener { _, on ->
                // opt-in applies to new events only: start uploading from the current end of the log
                if (on == true) lifecycleScope.launch { app.prefs.uploadOffset = withContext(Dispatchers.IO) { app.events.endOffset() } }
                view?.post { UploadWorker.schedule(requireContext(), app.prefs) }
                true
            }
            findPreference<Preference>(Prefs.WIFI_ONLY)!!.setOnPreferenceChangeListener { _, _ -> view?.post { UploadWorker.schedule(requireContext(), app.prefs) }; true }
            findPreference<androidx.preference.EditTextPreference>(Prefs.UPLOAD_URL)!!.apply {
                if (text.isNullOrBlank()) text = Prefs.DEFAULT_UPLOAD_URL
                summaryProvider = Preference.SummaryProvider<androidx.preference.EditTextPreference> { p ->
                    val t = p.text.orEmpty()
                    if (t.contains(".invalid")) "$t (placeholder, nothing is sent)" else t
                }
            }
            findPreference<Preference>("version")!!.summary = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"

            WorkManager.getInstance(requireContext()).getWorkInfosForUniqueWorkLiveData(DownloadWorker.NAME).observe(this) { infos ->
                showDownload(infos.firstOrNull())
            }
            // first run: fetch the manifest and start the default model download
            lifecycleScope.launch {
                withContext(Dispatchers.IO) { app.store.refreshManifest(app.prefs.manifestUrl) }
                val spec = app.store.activeSpec()
                if (!app.store.isInstalled(spec)) DownloadWorker.start(requireContext(), spec.id)
                refresh()
            }
        }

        override fun onResume() {
            super.onResume()
            refresh()
        }

        private fun onModelChanged() {
            val spec = app.store.activeSpec()
            if (!app.store.isInstalled(spec)) DownloadWorker.start(requireContext(), spec.id) else EngineHolder.reloadIfNeeded(app)
            refresh()
        }

        private fun showDownload(w: WorkInfo?) {
            val p = findPreference<Preference>("model_status") ?: return
            val spec = app.store.activeSpec()
            when (w?.state) {
                WorkInfo.State.RUNNING, WorkInfo.State.ENQUEUED -> {
                    val done = w.progress.getLong(DownloadWorker.KEY_DONE, 0)
                    val total = w.progress.getLong(DownloadWorker.KEY_TOTAL, spec.downloadBytes)
                    p.summary = if (done > 0) "Downloading ${spec.label}: ${100 * done / maxOf(total, 1)}% (${done / 1_000_000} / ${total / 1_000_000} MB)"
                    else "Waiting for network to download ${spec.label}…"
                }
                WorkInfo.State.FAILED -> p.summary = "Download failed: ${w.outputData.getString(DownloadWorker.KEY_ERROR)}. Tap to retry."
                else -> refresh()
            }
        }

        private fun refresh() {
            val ctx = context ?: return
            val imm = ctx.getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            val enabled = imm.enabledInputMethodList.any { it.packageName == ctx.packageName }
            val current = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)?.startsWith(ctx.packageName) == true
            findPreference<Preference>("enable_keyboard")?.summary = if (enabled) "Enabled ✓" else "Tap, then turn on \"Autocorrect keyboard\""
            findPreference<Preference>("switch_keyboard")?.summary = if (current) "In use ✓" else "Tap and pick \"Autocorrect keyboard\""
            val spec = app.store.activeSpec()
            val st = findPreference<Preference>("model_status")
            if (app.store.isInstalled(spec)) {
                st?.summary = "${spec.label}: installed (${spec.downloadBytes / 1_000_000} MB)" +
                    (if (EngineHolder.spec?.id == spec.id) ", loaded" else "") + (EngineHolder.lastError?.let { " · error: $it" } ?: "")
            } else if (st?.summary?.startsWith("Downloading") != true) {
                st?.summary = "${spec.label}: not downloaded (${spec.downloadBytes / 1_000_000} MB). Tap to download."
            }
            val c = EngineHolder.corrector?.stats
            findPreference<Preference>("stats")?.summary = buildString {
                if (c != null && c.words > 0) {
                    append("${c.words} words this session · LM skipped ${100 * c.lmSkipped / c.words}% · cheap path ${c.cheapOnly}")
                    if (c.lmRuns > 0) append(" · avg LM ${(c.lmMsTotal / c.lmRuns).toInt()} ms")
                    append("\n")
                }
                append("Session load ${EngineHolder.sessionMs} ms · cold load ${EngineHolder.coldLoadMs} ms · first inference ${EngineHolder.firstInferenceMs.toInt()} ms")
            }
            lifecycleScope.launch {
                val (n, bytes) = withContext(Dispatchers.IO) { app.events.count() to app.events.sizeBytes() }
                findPreference<Preference>("log_info")?.summary = "$n events, ${bytes / 1024} KB, stored only on this phone" +
                    if (app.prefs.share) " (new events are uploaded)" else ""
            }
        }

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
