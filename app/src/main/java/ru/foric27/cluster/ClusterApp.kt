package ru.foric27.cluster

import android.app.Application
import android.content.Context

class ClusterApp : Application() {

    override fun onCreate() {
        super.onCreate()
        appContext = applicationContext
        RuntimeConfig.init(applicationContext)
        ProcessRecoveryManager.install(applicationContext)
    }

    companion object {
        lateinit var appContext: Context
            private set
    }
}
