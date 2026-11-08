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

class SettingsActivity : AppCompatActivity() {
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
            findPreference<ModelCard>("model_card")!!.onAction = { DownloadWorker.start(requireContext(), app.store.activeSpec().id) }
            findPreference<Preference>("export")!!.setOnPreferenceClickListener { export(); true }
            findPreference<Preference>("stats_reset")!!.setOnPreferenceClickListener {
                app.prefs.statsResetAt = System.currentTimeMillis(); refresh(); true
            }
            findPreference<Preference>("clear")!!.setOnPreferenceClickListener {
                MaterialAlertDialogBuilder(requireContext()).setTitle("Clear local data?")
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
            val confident = findPreference<Preference>(Prefs.CONFIDENT_ONLY)!!
            confident.isEnabled = !app.prefs.suggestOnly
            findPreference<Preference>(Prefs.CORRECTION_MODE)!!.setOnPreferenceChangeListener { _, v -> confident.isEnabled = v != "suggest"; true }
            findPreference<Preference>("dictionary")!!.setOnPreferenceClickListener {
                startActivity(Intent(requireContext(), DictionaryActivity::class.java)); true
            }
            findPreference<Preference>("delete_uploaded")!!.setOnPreferenceClickListener {
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
            findPreference<Preference>(Prefs.WIFI_ONLY)!!.setOnPreferenceChangeListener { _, _ -> view?.post { UploadWorker.schedule(requireContext(), app.prefs) }; true }
            findPreference<androidx.preference.EditTextPreference>(Prefs.UPLOAD_URL)!!.apply {
                // older builds saved the example.invalid placeholder here
                if (text.isNullOrBlank() || text!!.contains(".invalid")) text = Prefs.DEFAULT_UPLOAD_URL
                summaryProvider = Preference.SummaryProvider<androidx.preference.EditTextPreference> { app.prefs.uploadUrl }
            }
            findPreference<Preference>("version")!!.summary = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
            findPreference<Preference>("check_updates")!!.setOnPreferenceClickListener { checkUpdates(manual = true); true }
            findPreference<Preference>("update_banner")!!.setOnPreferenceClickListener { Updater.pending()?.let { installUpdate(it) }; true }
            showUpdate()
            checkUpdates(manual = false)
            // notifications for updates found in the background (Android 13+ asks once)
            if (android.os.Build.VERSION.SDK_INT >= 33 && savedInstanceState == null &&
                requireContext().checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                notifPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)

            WorkManager.getInstance(requireContext()).getWorkInfosForUniqueWorkLiveData(DownloadWorker.NAME).observe(this) { infos ->
                showDownload(infos.firstOrNull())
            }
            // first run: fetch the manifest and start the default model download
            lifecycleScope.launch {
                withContext(Dispatchers.IO) { app.store.refreshManifest(app.prefs.manifestUrls) }
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
            if (!app.store.isInstalled(spec)) DownloadWorker.start(requireContext(), spec.id)
            refresh()
        }

        private var download: WorkInfo? = null

        private fun showDownload(w: WorkInfo?) { download = w; refresh() }

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
                    .setMessage("Android needs your OK once: turn on \"Allow from this source\" for Autocorrect, then come back and tap the update again.")
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
            findPreference<SetupCard>("setup_card")?.steps = listOf(
                SetupCard.Step("Enable the keyboard", if (enabled) "Enabled" else "Turn on \"Autocorrect keyboard\" in system settings", enabled, "Enable") {
                    startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
                },
                SetupCard.Step("Switch to it", if (current) "In use" else "Pick \"Autocorrect keyboard\" as your keyboard", current, "Switch") {
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
            app.user.reloadIfChanged()
            findPreference<Preference>("dictionary")?.summary = when (val n = app.user.size) {
                0 -> "Words that are never corrected or flagged"
                1 -> "1 word"
                else -> "$n words"
            }
            lifecycleScope.launch {
                val (n, bytes) = withContext(Dispatchers.IO) { app.events.count() to app.events.sizeBytes() }
                findPreference<Preference>("log_info")?.summary = "$n events, ${bytes / 1024} KB, stored on this phone" +
                    (if (app.prefs.share) " (new events are uploaded)" else "") +
                    (if (app.prefs.pendingDeletes.isNotEmpty()) ". Server delete pending, retried automatically." else "")
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
