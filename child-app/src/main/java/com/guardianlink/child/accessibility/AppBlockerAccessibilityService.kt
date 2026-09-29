// child-app/src/main/java/com/guardianlink/child/accessibility/AppBlockerAccessibilityService.kt
package com.guardianlink.child.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.usage.UsageStatsManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Button
import android.widget.TextView
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestore
import com.guardianlink.child.R
import com.guardianlink.common.constants.FirebasePaths
import com.guardianlink.common.model.AlertType
import com.guardianlink.common.model.ParentalSettings
import com.guardianlink.common.security.SecurePreferences
import timber.log.Timber

class AppBlockerAccessibilityService : AccessibilityService() {

    private val db = FirebaseFirestore.getInstance()
    private var overlayView: View? = null
    private var windowManager: WindowManager? = null
    private var currentSettings: ParentalSettings = ParentalSettings()

    // Dynamic launcher detection: previously this service relied entirely on a
    // hardcoded list of known launcher package names (AOSP, Pixel, MIUI,
    // Huawei, and a Samsung *Always-On-Display service* package that is not
    // actually Samsung's launcher). That list omitted Samsung's real launcher
    // (com.sec.android.app.launcher) and every OnePlus/Oppo/Vivo/LG/Motorola
    // launcher, so on those devices bedtime/daily-limit enforcement could
    // fire against the home screen itself every time it became foreground.
    // This queries the OS for every package that can actually handle
    // ACTION_MAIN/CATEGORY_HOME (i.e. is installed as a launcher on this
    // specific device), which is correct regardless of OEM, and is cached
    // with a TTL since PackageManager queries are too expensive to run on
    // every single accessibility event.
    private var cachedLauncherPackages: Set<String> = emptySet()
    private var launcherPackagesCachedAt: Long = 0L
    private val lastBlockedAlertAt = mutableMapOf<String, Long>()

    companion object {
        private const val LAUNCHER_CACHE_TTL_MS = 60 * 60_000L // re-query hourly (user may change default launcher)
        private const val BLOCKED_ALERT_COOLDOWN_MS = 5 * 60_000L

        /**
         * System packages that are not launchers (so [isLauncherPackage] won't
         * catch them) but which must never be blocked/enforced against, since
         * doing so would break basic device operation rather than provide any
         * meaningful parental control. Kept as a small, explicit allow-list
         * rather than a launcher-name guess.
         */
        private val NON_LAUNCHER_SYSTEM_PACKAGES = setOf(
            "android",
            "com.android.systemui",
            "com.samsung.android.app.aodservice"
        )
    }

    override fun onServiceConnected() {
        super.onServiceConnected()

        serviceInfo = serviceInfo.apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOWS_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            notificationTimeout = 100
        }

        windowManager = getSystemService(WindowManager::class.java)
        startListeningForSettings()
        Timber.i("AppBlockerAccessibilityService connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        if (!canEnforceScreenTime()) return

        val packageName = event.packageName?.toString()?.trim() ?: return
        if (packageName.isBlank() || shouldIgnorePackage(packageName)) return

        checkAndBlock(packageName)
    }

    override fun onInterrupt() {
        Timber.w("AppBlockerAccessibilityService interrupted")
    }

    private fun checkAndBlock(packageName: String) {
        val normalized = packageName.lowercase()
        if (normalized == this.packageName.lowercase()) return

        when {
            currentSettings.isBlockedApp(normalized) -> {
                Timber.i("Blocking app: $normalized")
                showBlockedOverlay(normalized)
                goHome()
                reportBlockedAttempt(normalized)
            }
            isScreenTimeLimitExceeded() -> {
                Timber.i("Screen time limit exceeded — blocking $normalized")
                showScreenTimeLimitOverlay()
                goHome()
            }
            currentSettings.screenTimeSettings.isBedtimeActive() -> {
                Timber.i("Bedtime window active — blocking $normalized")
                showBedtimeOverlay()
                lockDevice()
            }
        }
    }

    private fun isScreenTimeLimitExceeded(): Boolean {
        val dailyLimitMinutes = currentSettings.screenTimeSettings.dailyLimitMinutes
        if (dailyLimitMinutes <= 0) return false
        if (!hasUsageAccess()) {
            Timber.w("Usage access denied; cannot enforce daily limit")
            return false
        }

        val usm = getSystemService(UsageStatsManager::class.java) ?: return false
        val endTime = System.currentTimeMillis()
        val startOfDay = getTodayStartMs()

        val totalScreenTimeMs = usm.queryUsageStats(
            UsageStatsManager.INTERVAL_DAILY,
            startOfDay,
            endTime
        )
            .filter { !it.packageName.isNullOrBlank() && it.packageName != this.packageName }
            .sumOf { it.totalTimeInForeground }

        return currentSettings.screenTimeSettings.isDailyLimitReached(totalScreenTimeMs)
    }

    private fun startListeningForSettings() {
        val prefs = SecurePreferences(this)
        val deviceId = prefs.getString(SecurePreferences.KEY_DEVICE_ID)
        if (deviceId.isBlank()) return

        db.collection(FirebasePaths.COLLECTION_SETTINGS)
            .document(deviceId)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Timber.e(error, "Settings listener error")
                    return@addSnapshotListener
                }

                val settings = snapshot?.toObject(ParentalSettings::class.java)
                    ?: ParentalSettings(deviceId = deviceId)

                currentSettings = settings
                Timber.d("Parental settings synced: blockedApps=${settings.blockedApps}, dailyLimit=${settings.screenTimeSettings.dailyLimitMinutes}, bedtimeEnabled=${settings.screenTimeSettings.bedtimeEnabled}")
            }
    }

    private fun canEnforceScreenTime(): Boolean {
        if (!isAccessibilityServiceEnabled()) {
            Timber.w("Accessibility service unavailable; skipping app enforcement")
            return false
        }
        if (!hasUsageAccess()) {
            Timber.w("Usage access not granted; skipping app enforcement")
            return false
        }
        return true
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val serviceName = "$packageName/${AppBlockerAccessibilityService::class.java.name}"
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabledServices.split(":").any { it == serviceName }
    }

    private fun hasUsageAccess(): Boolean {
        val permission = packageManager.checkPermission(
            android.Manifest.permission.PACKAGE_USAGE_STATS,
            packageName
        )
        return permission == PackageManager.PERMISSION_GRANTED
    }

    private fun shouldIgnorePackage(packageName: String): Boolean {
        val normalized = packageName.lowercase()
        if (normalized == this.packageName.lowercase()) return true
        if (normalized in NON_LAUNCHER_SYSTEM_PACKAGES) return true
        return isLauncherPackage(normalized)
    }

    /**
     * Returns true if [packageName] is currently resolvable as a home-screen
     * launcher on this device, per the OS itself (any package that can
     * handle `ACTION_MAIN`/`CATEGORY_HOME`), rather than a hardcoded list of
     * launcher package names for specific OEMs. This is correct for every
     * OEM automatically, including ones not explicitly known about here, and
     * correctly follows the user switching their default launcher.
     */
    private fun isLauncherPackage(normalizedPackageName: String): Boolean {
        val now = System.currentTimeMillis()
        if (now - launcherPackagesCachedAt > LAUNCHER_CACHE_TTL_MS) {
            cachedLauncherPackages = try {
                val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                @Suppress("DEPRECATION")
                packageManager.queryIntentActivities(homeIntent, PackageManager.MATCH_ALL)
                    .mapNotNull { it.activityInfo?.packageName?.lowercase() }
                    .toSet()
            } catch (e: Exception) {
                Timber.w(e, "Failed to enumerate launcher packages; falling back to previous/static list")
                cachedLauncherPackages
            }
            launcherPackagesCachedAt = now
        }
        return normalizedPackageName in cachedLauncherPackages
    }

    private fun goHome() {
        val homeIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(homeIntent)
    }

    private fun lockDevice() {
        val dpm = getSystemService(android.app.admin.DevicePolicyManager::class.java)
        dpm?.lockNow()
    }

    private fun showBlockedOverlay(packageName: String) {
        removeOverlay()

        val appName = try {
            packageManager.getApplicationLabel(
                packageManager.getApplicationInfo(packageName, 0)
            ).toString()
        } catch (_: Exception) {
            packageName
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.CENTER }

        val view = LayoutInflater.from(this)
            .inflate(R.layout.overlay_blocked_app, null).apply {
                findViewById<TextView>(R.id.tv_app_name).text =
                    getString(R.string.app_blocked_message, appName)
                findViewById<Button>(R.id.btn_ok).setOnClickListener { removeOverlay() }
            }

        overlayView = view
        windowManager?.addView(view, params)
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ removeOverlay() }, 3_000)
    }

    private fun showScreenTimeLimitOverlay() {
        removeOverlay()
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.CENTER }

        val view = LayoutInflater.from(this)
            .inflate(R.layout.overlay_blocked_app, null).apply {
                findViewById<TextView>(R.id.tv_app_name).text =
                    getString(R.string.screen_time_limit_reached)
                findViewById<Button>(R.id.btn_ok).setOnClickListener { removeOverlay() }
            }

        overlayView = view
        windowManager?.addView(view, params)
    }

    private fun showBedtimeOverlay() {
        removeOverlay()
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.CENTER }

        val view = LayoutInflater.from(this)
            .inflate(R.layout.overlay_blocked_app, null).apply {
                findViewById<TextView>(R.id.tv_app_name).text =
                    getString(R.string.bedtime_locked_message)
                findViewById<Button>(R.id.btn_ok).setOnClickListener { removeOverlay() }
            }

        overlayView = view
        windowManager?.addView(view, params)
    }

    private fun removeOverlay() {
        overlayView?.let {
            try {
                windowManager?.removeView(it)
            } catch (_: Exception) {
            }
        }
        overlayView = null
    }

    private fun reportBlockedAttempt(packageName: String) {
        // Debounce: previously this wrote a fresh alert (and triggered an FCM
        // push, via the onAlertCreated Cloud Function) on every single
        // TYPE_WINDOW_STATE_CHANGED event for a blocked package, so a child
        // repeatedly tapping a blocked icon could generate an unbounded
        // number of writes/pushes in a short window. One alert per package
        // per cooldown window is still meaningful information for the
        // parent (a first attempt, and then "still trying" at most every
        // few minutes) without the flood.
        val now = System.currentTimeMillis()
        val last = lastBlockedAlertAt[packageName]
        if (last != null && now - last < BLOCKED_ALERT_COOLDOWN_MS) return
        lastBlockedAlertAt[packageName] = now

        val prefs = SecurePreferences(this)
        val deviceId = prefs.getString(SecurePreferences.KEY_DEVICE_ID)

        db.collection(FirebasePaths.COLLECTION_ALERTS)
            .add(
                mapOf(
                    "deviceId" to deviceId,
                    "type" to AlertType.BLOCKED_APP_ATTEMPT.name,
                    "title" to "Blocked App Attempt",
                    "message" to "Child tried to open blocked app: $packageName",
                    "severity" to "MEDIUM",
                    "isRead" to false,
                    "timestamp" to Timestamp.now()
                )
            )
    }

    private fun getTodayStartMs(): Long {
        val cal = java.util.Calendar.getInstance()
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }
}
