package ru.foric27.cluster

import android.app.Application
import android.content.Context
import timber.log.Timber

class ClusterApp : Application() {

    override fun onCreate() {
        super.onCreate()
        appContext = applicationContext
        RuntimeConfig.init(applicationContext)
        persistentLogWriter = PersistentLogWriter(PersistentLogWriter.defaultDirectory(applicationContext))
        Timber.plant(AppTimberTree(persistentLogWriter))
        ProcessRecoveryManager.install(applicationContext)
    }

    companion object {
        lateinit var appContext: Context
            private set

        @Volatile
        private var persistentLogWriter: PersistentLogWriter? = null

        internal fun persistedLogWriter(): PersistentLogWriter? = persistentLogWriter
    }
}
