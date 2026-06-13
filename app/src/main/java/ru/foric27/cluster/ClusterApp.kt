package ru.foric27.cluster
import ru.foric27.cluster.config.*
import ru.foric27.cluster.di.appModule
import ru.foric27.cluster.di.uiModule
import ru.foric27.cluster.di.updateModule
import ru.foric27.cluster.di.videoModule
import ru.foric27.cluster.util.*

import android.app.Application
import android.content.Context
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin
import timber.log.Timber

/**
 * Точка входа приложения.
 *
 * Инициализирует [RuntimeConfig], ставит на место [AppTimberTree]
 * и устанавливает глобальный обработчик крашей [ProcessRecoveryManager].
 */
class ClusterApp : Application() {

    override fun onCreate() {
        super.onCreate()
        appContext = applicationContext
        RuntimeConfig.init(applicationContext)
        Timber.plant(AppTimberTree())
        CrashAnalytics.init(applicationContext)
        ProcessRecoveryManager.install(applicationContext)

        startKoin {
            androidLogger()
            androidContext(this@ClusterApp)
            modules(appModule, videoModule, updateModule, uiModule)
        }
    }

    companion object {
        lateinit var appContext: Context
            private set
    }
}
