package dev.mostlime.autocorrect.log

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.mostlime.autocorrect.App
import dev.mostlime.autocorrect.BuildConfig
import dev.mostlime.autocorrect.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * Opt-in upload of the local event log ("Share typing data", off by default). Batched POST of new events since the
 * last successful upload, JSON body per docs/upload-schema.md. Runs via WorkManager every few hours, Wi-Fi only by
 * default. The default URL is a placeholder (example.invalid): nothing is sent until a real endpoint is configured.
 */
class UploadWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val app = App.instance
        val prefs = app.prefs
        if (!prefs.share) return@withContext Result.success()
        val url = prefs.uploadUrl
        if (URI(url).host?.endsWith(".invalid") != false) {
            Log.i(TAG, "upload endpoint is the placeholder ($url); skipping")
            return@withContext Result.success()
        }
        var sent = 0
        while (true) {
            val (lines, next) = app.events.readFrom(prefs.uploadOffset, BATCH_EVENTS, BATCH_BYTES)
            if (lines.isEmpty()) break
            val body = buildString {
                append("{\"schema\":\"autocorrect-events/").append(EventLog.SCHEMA).append("\",")
                append("\"install_id\":\"").append(prefs.installId).append("\",")
                append("\"app_version\":\"").append(BuildConfig.VERSION_NAME).append("\",")
                append("\"sent_at\":").append(System.currentTimeMillis()).append(",")
                append("\"events\":[").append(lines.joinToString(",")).append("]}")
            }
            val code = try { post(url, body) } catch (e: Exception) { Log.w(TAG, "upload failed: $e"); -1 }
            if (code !in 200..299) return@withContext if (runAttemptCount < 5) Result.retry() else Result.failure()
            prefs.uploadOffset = next
            sent += lines.size
        }
        Log.i(TAG, "uploaded $sent events")
        Result.success()
    }

    private fun post(url: String, body: String): Int {
        val c = (URI(url).toURL().openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; doOutput = true; connectTimeout = 15_000; readTimeout = 30_000
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }
        try {
            c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            return c.responseCode
        } finally {
            c.disconnect()
        }
    }

    companion object {
        const val TAG = "AcUpload"
        const val NAME = "event-upload"
        const val BATCH_EVENTS = 500
        const val BATCH_BYTES = 512 * 1024

        /** (Re)schedule or cancel according to the settings. */
        fun schedule(ctx: Context, prefs: Prefs) {
            val wm = WorkManager.getInstance(ctx)
            if (!prefs.share) { wm.cancelUniqueWork(NAME); return }
            val net = if (prefs.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED
            val req = PeriodicWorkRequestBuilder<UploadWorker>(6, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(net).setRequiresBatteryNotLow(true).build())
                .build()
            wm.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.UPDATE, req)
        }
    }
}
