package dev.mostlime.autocorrect

import android.app.Application
import android.os.Process
import android.os.SystemClock
import dev.mostlime.autocorrect.log.EventLog
import dev.mostlime.autocorrect.log.UploadWorker
import dev.mostlime.autocorrect.model.ModelStore

class App : Application() {
    lateinit var prefs: Prefs
        private set
    lateinit var store: ModelStore
        private set
    lateinit var events: EventLog
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        prefs = Prefs(this)
        store = ModelStore(this)
        events = EventLog(this)
        UploadWorker.schedule(this, prefs)
    }

    companion object {
        lateinit var instance: App
            private set

        /** elapsedRealtime when the process started (for the cold-load measurement) */
        val processStart: Long get() = Process.getStartElapsedRealtime()
        fun now() = SystemClock.elapsedRealtime()
    }
}
