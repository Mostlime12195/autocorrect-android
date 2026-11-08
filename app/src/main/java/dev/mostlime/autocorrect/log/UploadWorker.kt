package dev.mostlime.autocorrect.log

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.mostlime.autocorrect.App
import dev.mostlime.autocorrect.BuildConfig
import dev.mostlime.autocorrect.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * Opt-in upload of the local event log ("Share typing data", off by default). Batched POST of new events since the
 * last successful upload, JSON body per docs/upload-schema.md. Runs via WorkManager every 6 hours, Wi-Fi only by
 * default. Pending server-side deletes ("Delete my uploaded data" that couldn't reach the server) go first, before any
 * upload, and also run when sharing is off.
 */
class UploadWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val app = App.instance
        val prefs = app.prefs
        val url = prefs.uploadUrl

        for (id in prefs.pendingDeletes) {
            val r = UploadClient.delete(UploadClient.deleteUrl(url, id))
            when (UploadClient.classify(r.code)) {
                UploadClient.Action.ADVANCE, UploadClient.Action.SKIP, UploadClient.Action.SPLIT -> {
                    // 200 = gone; a 4xx (e.g. a malformed id) will never succeed: drop it rather than block uploads forever
                    Log.i(TAG, "delete ${r.code}: ${r.body.take(200)}")
                    prefs.pendingDeletes = prefs.pendingDeletes - id
                }
                UploadClient.Action.RETRY -> return@withContext retryLater(r, "delete")
            }
        }

        if (!prefs.share) return@withContext Result.success()
        if (URI(url).host?.endsWith(".invalid") != false) return@withContext Result.success()
        val d = UploadClient.drain(prefs.uploadOffset, BATCH_EVENTS,
            read = { from, n -> app.events.readFrom(from, n, BATCH_BYTES) },
            send = { lines -> UploadClient.post(url, UploadClient.envelope(prefs.installId, BuildConfig.VERSION_NAME, System.currentTimeMillis(), lines)) },
            commit = { prefs.uploadOffset = it },
            log = { Log.w(TAG, it) })
        Log.i(TAG, "uploaded ${d.sent} events, skipped ${d.skipped}")
        d.retry?.let { return@withContext retryLater(it, "upload") }
        Result.success()
    }

    /** 429 / 5xx / network: honor Retry-After with a one-off delayed run, else WorkManager's backoff. */
    private fun retryLater(r: UploadClient.Response, what: String): Result {
        Log.w(TAG, "$what failed (${r.code}) ${r.body.take(200)}")
        val after = r.retryAfterSec
        if (after != null) {
            // APPEND: runs after this run ends (REPLACE would cancel the run that is enqueueing it)
            enqueueOnce(applicationContext, App.instance.prefs, after.coerceIn(30L, 24L * 3600), ExistingWorkPolicy.APPEND_OR_REPLACE)
            return Result.success()
        }
        return if (runAttemptCount < 5) Result.retry() else Result.failure()
    }

    companion object {
        const val TAG = "AcUpload"
        const val NAME = "event-upload"
        const val BATCH_EVENTS = 500
        const val BATCH_BYTES = 512 * 1024

        const val NAME_ONCE = "event-upload-once"

        private fun constraints(prefs: Prefs) = Constraints.Builder()
            // a pending delete goes out on any network; uploads respect the Wi-Fi setting
            .setRequiredNetworkType(if (prefs.wifiOnly && prefs.pendingDeletes.isEmpty()) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(prefs.pendingDeletes.isEmpty())
            .build()

        /** (Re)schedule or cancel according to the settings. */
        fun schedule(ctx: Context, prefs: Prefs) {
            val wm = WorkManager.getInstance(ctx)
            if (prefs.pendingDeletes.isNotEmpty()) enqueueOnce(ctx, prefs, 0)
            if (!prefs.share) { wm.cancelUniqueWork(NAME); return }
            val req = PeriodicWorkRequestBuilder<UploadWorker>(6, TimeUnit.HOURS)
                .setConstraints(constraints(prefs))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
                .build()
            wm.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.UPDATE, req)
        }

        /**
         * "Delete my uploaded data" (blocking, call off the main thread): queue the current install ID for deletion,
         * switch to a new one, clear the local log (nothing old is uploaded again), then try the server delete now.
         * true = the server confirmed; false = it stays pending and the worker retries it before any upload.
         */
        fun deleteMyData(app: App): Boolean {
            val prefs = app.prefs
            val old = prefs.installId
            prefs.pendingDeletes = prefs.pendingDeletes + old
            prefs.rotateInstallId()
            app.events.clear()
            app.user.clearAll()   // personal dictionary + learned edits are local data too
            Prefs.bgFile(app).delete(); prefs.bgStamp = 0L   // and the keyboard background image
            prefs.uploadOffset = app.events.endOffset()
            val r = UploadClient.delete(UploadClient.deleteUrl(prefs.uploadUrl, old))
            Log.i(TAG, "delete my data: ${r.code} ${r.body.take(200)}")
            return if (UploadClient.classify(r.code) == UploadClient.Action.ADVANCE) {
                prefs.pendingDeletes = prefs.pendingDeletes - old
                true
            } else {
                enqueueOnce(app, prefs, r.retryAfterSec?.coerceIn(30L, 24L * 3600) ?: 0L)
                false
            }
        }

        /** One run after `delaySec` (Retry-After, or a delete that has to go through even with sharing off). */
        fun enqueueOnce(ctx: Context, prefs: Prefs, delaySec: Long, policy: ExistingWorkPolicy = ExistingWorkPolicy.REPLACE) {
            val req = OneTimeWorkRequestBuilder<UploadWorker>()
                .setConstraints(constraints(prefs))
                .setInitialDelay(delaySec, TimeUnit.SECONDS)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(ctx).enqueueUniqueWork(NAME_ONCE, policy, req)
        }
    }
}
