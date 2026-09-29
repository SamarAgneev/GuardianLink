// parent-app/src/main/java/com/guardianlink/parent/GuardianLinkParentApp.kt
package com.guardianlink.parent

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.google.firebase.FirebaseApp
import com.guardianlink.common.constants.NotificationChannels
import com.guardianlink.common.security.EncryptionUtils
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber

@HiltAndroidApp
class GuardianLinkParentApp : Application() {

    override fun onCreate() {
        super.onCreate()
        FirebaseApp.initializeApp(this)
        if (BuildConfig.DEBUG) Timber.plant(Timber.DebugTree())
        EncryptionUtils.generateKeyIfAbsent()
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannels(listOf(
            NotificationChannel(
                NotificationChannels.ALERT_CHANNEL, "Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply { description = "Real-time alerts from monitored devices" },
            NotificationChannel(
                NotificationChannels.GENERAL, "General",
                NotificationManager.IMPORTANCE_DEFAULT
            )
        ))
    }
}
