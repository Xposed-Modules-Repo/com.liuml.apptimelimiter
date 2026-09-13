package com.liuml.apptimelimiter

import android.app.Application
import android.content.Context
import com.liuml.apptimelimiter.migration.MigrationCoordinator
import com.liuml.apptimelimiter.ads.RewardedAdStateRepository
import com.liuml.apptimelimiter.ads.TopOnSdkBootstrap
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
        if (RewardedAdStateRepository(this).isPrivacyConsentGranted()) {
            TopOnSdkBootstrap.initialize(
                context = this,
                appId = BuildConfig.TOPON_APP_ID,
                appKey = BuildConfig.TOPON_APP_KEY,
                placementId = BuildConfig.TOPON_PLACEMENT_ID,
                enabled = !BuildConfig.TOPON_TEST_MODE,
            )
        }
    }
}
