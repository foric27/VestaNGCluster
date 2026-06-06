package ru.foric27.cluster

import android.app.Application
import android.content.Context
import timber.log.Timber

class ClusterApp : Application() {

    override fun onCreate() {
        super.onCreate()
        appContext = applicationContext
        RuntimeConfig.init(applicationContext)
        Timber.plant(AppTimberTree())
        ProcessRecoveryManager.install(applicationContext)
    }

    companion object {
        lateinit var appContext: Context
            private set
    }
}
