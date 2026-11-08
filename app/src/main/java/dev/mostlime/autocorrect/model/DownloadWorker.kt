package dev.mostlime.autocorrect.model

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dev.mostlime.autocorrect.App
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Downloads (and verifies) the files of one model. Progress: "done" / "total" bytes. */
class DownloadWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val app = App.instance
        app.store.refreshManifest(app.prefs.manifestUrls)
        val id = inputData.getString(KEY_MODEL)
        val spec = id?.let { app.store.manifest().model(it) } ?: app.store.activeSpec()
        try {
            var lastPost = 0L
            app.store.download(spec) { done, total ->
                val now = System.currentTimeMillis()
                if (now - lastPost > 300 || done == total) {
                    lastPost = now
                    setProgressAsync(workDataOf(KEY_DONE to done, KEY_TOTAL to total, KEY_MODEL to spec.id))
                }
            }
            app.store.prune()
            // the keyboard process picks the new model up at its next onStartInputView
            Result.success(workDataOf(KEY_MODEL to spec.id))
        } catch (e: Exception) {
            if (runAttemptCount < 3) Result.retry() else Result.failure(workDataOf(KEY_ERROR to (e.message ?: e.toString())))
        }
    }

    companion object {
        const val NAME = "model-download"
        const val KEY_MODEL = "model"
        const val KEY_DONE = "done"
        const val KEY_TOTAL = "total"
        const val KEY_ERROR = "error"

        fun start(ctx: Context, modelId: String?) {
            val req = OneTimeWorkRequestBuilder<DownloadWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInputData(workDataOf(KEY_MODEL to modelId))
                .build()
            WorkManager.getInstance(ctx).enqueueUniqueWork(NAME, ExistingWorkPolicy.REPLACE, req)
        }
    }
}
