package com.liuml.apptimelimiter

import android.app.Application
import android.content.Context
import com.liuml.apptimelimiter.migration.MigrationCoordinator
import com.liuml.apptimelimiter.xposedstatus.ScopeSyncCoordinator
import com.liuml.apptimelimiter.xposedstatus.XposedStatusRepository

class TimeStopApplication : Application() {
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
    }

    override fun onCreate() {
        super.onCreate()
        val migration = MigrationCoordinator.get(this)
        migration.initialize()
        if (migration.canInitializeRepositories() && BuildConfig.MODERN_XPOSED_ENABLED) {
            // Register only after legacy data has been imported. Binding earlier could replace
            // the framework's remote preferences with an empty private mirror.
            XposedStatusRepository.instance.initialize(this)
            ScopeSyncCoordinator.get(this).initialize()
        }
    }
}
