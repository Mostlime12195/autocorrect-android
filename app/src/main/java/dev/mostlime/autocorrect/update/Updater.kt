package dev.mostlime.autocorrect.update

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.mostlime.autocorrect.App
import dev.mostlime.autocorrect.BuildConfig
import dev.mostlime.autocorrect.R
import dev.mostlime.autocorrect.ui.SettingsActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** latest.json on the Nest mirror (the GitHub repo is private, so its API doesn't work for users). */
class UpdateInfo(val version: String, val code: Int, val url: String, val sha256: String, val notes: String) {
    val newer get() = code > BuildConfig.VERSION_CODE

    companion object {
        fun parse(text: String): UpdateInfo {
            val o = Json.parseToJsonElement(text).jsonObject
            return UpdateInfo(o["version"]!!.jsonPrimitive.content, o["code"]!!.jsonPrimitive.int, o["url"]!!.jsonPrimitive.content,
                o["sha256"]!!.jsonPrimitive.content.lowercase(), o["notes"]?.jsonPrimitive?.content.orEmpty())
        }
    }
}

/**
 * In-app updates: check latest.json (settings launch + every 6 h), notify, download the APK to the cache (auto-download
 * setting, on by default), verify its SHA-256 and install it with a PackageInstaller session (Android asks the user to
 * confirm, and the first time to allow installs from this app).
 */
object Updater {
    const val TAG = "AcUpdate"
    const val LATEST_URL = "https://autocorrect.mostlime.hackclub.app/autocorrect-android/latest.json"
    private const val CHANNEL = "updates"
    private const val NOTIFY_ID = 47
    const val ACTION_INSTALL_STATUS = "dev.mostlime.autocorrect.INSTALL_STATUS"

    private fun dir(ctx: Context) = File(ctx.cacheDir, "updates").apply { mkdirs() }
    fun apkFile(ctx: Context, u: UpdateInfo) = File(dir(ctx), "autocorrect-${u.version}.apk")

    /** Blocking. null = couldn't check. Remembers the result for the settings banner. */
    fun check(ctx: Context): UpdateInfo? {
        return try {
            val c = (URI(LATEST_URL).toURL().openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000; readTimeout = 15_000; setRequestProperty("Cache-Control", "no-cache")
            }
            val text = try { c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) } } finally { c.disconnect() }
            UpdateInfo.parse(text).also { App.instance.prefs.latestJson = text }
        } catch (e: Exception) {
            Log.w(TAG, "check failed: $e"); null
        }
    }

    /** the last checked info, if it is newer than this build */
    fun pending(): UpdateInfo? = App.instance.prefs.latestJson?.let { runCatching { UpdateInfo.parse(it) }.getOrNull() }?.takeIf { it.newer }

    fun isDownloaded(ctx: Context, u: UpdateInfo): Boolean {
        val f = apkFile(ctx, u)
        return f.exists() && sha256(f) == u.sha256
    }

    /** Blocking download to the cache, verified. -> the file, or null (bad hash / network). Old APKs are removed. */
    fun download(ctx: Context, u: UpdateInfo, progress: (Long, Long) -> Unit = { _, _ -> }): File? {
        val f = apkFile(ctx, u)
        if (isDownloaded(ctx, u)) return f
        dir(ctx).listFiles()?.forEach { if (it != f) it.delete() }
        val tmp = File(f.path + ".part")
        return try {
            val c = (URI(u.url).toURL().openConnection() as HttpURLConnection).apply { connectTimeout = 15_000; readTimeout = 30_000 }
            try {
                val total = c.contentLengthLong
                c.inputStream.use { inp ->
                    tmp.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        while (true) {
                            val n = inp.read(buf); if (n < 0) break
                            out.write(buf, 0, n); done += n; progress(done, total)
                        }
                    }
                }
            } finally { c.disconnect() }
            if (sha256(tmp) != u.sha256) { Log.w(TAG, "sha256 mismatch"); tmp.delete(); return null }
            tmp.renameTo(f); f
        } catch (e: Exception) {
            Log.w(TAG, "download failed: $e"); tmp.delete(); null
        }
    }

    fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { inp -> val b = ByteArray(64 * 1024); while (true) { val n = inp.read(b); if (n < 0) break; md.update(b, 0, n) } }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** Installs from this app allowed? If not, the caller sends the user to the system screen (canRequestInstalls). */
    fun canInstall(ctx: Context) = Build.VERSION.SDK_INT < 26 || ctx.packageManager.canRequestPackageInstalls()

    fun allowInstallsIntent(ctx: Context) =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** PackageInstaller session; Android shows its confirmation (InstallReceiver starts it). */
    fun install(ctx: Context, apk: File) {
        val pi = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(ctx.packageName)
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = pi.createSession(params)
        pi.openSession(id).use { s ->
            s.openWrite("base.apk", 0, apk.length()).use { out -> apk.inputStream().use { it.copyTo(out) }; s.fsync(out) }
            val intent = Intent(ctx, InstallReceiver::class.java).setAction(ACTION_INSTALL_STATUS)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            s.commit(PendingIntent.getBroadcast(ctx, id, intent, flags).intentSender)
        }
    }

    fun notify(ctx: Context, u: UpdateInfo, ready: Boolean) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(CHANNEL, "App updates", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ms_download)
            .setContentTitle("Autocorrect ${u.version} is available")
            .setContentText(if (ready) "Downloaded. Tap to install." else "Tap to update.")
            .setStyle(NotificationCompat.BigTextStyle().bigText(u.notes.ifBlank { if (ready) "Downloaded. Tap to install." else "Tap to update." }))
            .setContentIntent(open).setAutoCancel(true).build()
        nm.notify(NOTIFY_ID, n)
    }

    fun schedule(ctx: Context) {
        val req = PeriodicWorkRequestBuilder<UpdateWorker>(6, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("update-check", ExistingPeriodicWorkPolicy.KEEP, req)
    }
}

class UpdateWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val u = Updater.check(applicationContext) ?: return@withContext Result.success()
        if (!u.newer) return@withContext Result.success()
        // auto-download only on unmetered networks; Android still asks before installing
        val cm = applicationContext.getSystemService(android.net.ConnectivityManager::class.java)
        val ready = App.instance.prefs.autoUpdate && !cm.isActiveNetworkMetered && Updater.download(applicationContext, u) != null
        val seen = App.instance.prefs.updateNotified
        if (seen != u.code || ready) { Updater.notify(applicationContext, u, ready); App.instance.prefs.updateNotified = u.code }
        Result.success()
    }
}

/** PackageInstaller status: start the confirmation screen when Android asks for it. */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (val st = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = (if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                    else intent.getParcelableExtra(Intent.EXTRA_INTENT)) ?: return
                ctx.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            PackageInstaller.STATUS_SUCCESS -> Log.i(Updater.TAG, "installed")
            else -> {
                Log.w(Updater.TAG, "install failed: $st ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)}")
                android.widget.Toast.makeText(ctx, "Update not installed: ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: st}",
                    android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }
}
