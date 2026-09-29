// child-app/src/main/java/com/guardianlink/child/GuardianLinkChildApp.kt
package com.guardianlink.child

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.google.firebase.FirebaseApp
import com.google.firebase.database.FirebaseDatabase
import com.guardianlink.common.constants.NotificationChannels
import com.guardianlink.common.security.EncryptionUtils
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber

@HiltAndroidApp
class GuardianLinkChildApp : Application() {

    override fun onCreate() {
        super.onCreate()

        // Initialize Firebase
        FirebaseApp.initializeApp(this)
        try {
            FirebaseDatabase.getInstance().setPersistenceEnabled(true)
        } catch (e: Exception) {
            Timber.w(e, "Realtime Database persistence was already configured")
        }

        // Set up Timber logging (debug builds only)
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }

        // Pre-generate AES key in Android Keystore
        EncryptionUtils.generateKeyIfAbsent()

        // Create notification channels (required for Android 8+)
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val manager = getSystemService(NotificationManager::class.java)

        manager.createNotificationChannels(
            listOf(
                NotificationChannel(
                    NotificationChannels.MONITORING_SERVICE,
                    "GuardianLink Monitoring",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Keeps GuardianLink running in the background"
                    setShowBadge(false)
                },
                NotificationChannel(
                    NotificationChannels.ALERT_CHANNEL,
                    "GuardianLink Alerts",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Critical alerts from the monitoring service"
                },
                NotificationChannel(
                    NotificationChannels.STREAM_CHANNEL,
                    "Live Streaming",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Active camera/screen/audio streams"
                    setShowBadge(false)
                },
                NotificationChannel(
                    NotificationChannels.GENERAL,
                    "General",
                    NotificationManager.IMPORTANCE_DEFAULT
                )
            )
        )
    }
}
