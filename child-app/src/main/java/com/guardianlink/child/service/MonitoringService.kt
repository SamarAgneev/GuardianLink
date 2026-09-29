// child-app/src/main/java/com/guardianlink/child/service/MonitoringService.kt
package com.guardianlink.child.service

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.guardianlink.child.R
import com.guardianlink.child.data.AppUsageTracker
import com.guardianlink.child.data.OfflineQueue
import com.guardianlink.child.data.TamperDetector
import com.guardianlink.child.firebase.ChildFirebaseManager
import com.guardianlink.child.security.PinManager
import com.guardianlink.child.ui.MainActivity
import com.guardianlink.common.constants.FirebasePaths
import com.guardianlink.common.constants.NotificationChannels
import com.guardianlink.common.constants.NotificationIds
import com.guardianlink.common.model.Command
import com.guardianlink.common.model.CommandSecurity
import com.guardianlink.common.model.CommandStatus
import com.guardianlink.common.model.CommandType
import com.guardianlink.common.model.DeviceOnlineStatus
import com.guardianlink.common.security.SecurePreferences
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * MonitoringService — the central foreground service that:
 *  1. Keeps the process alive with a persistent notification
 *  2. Listens for commands from Firebase Realtime DB
 *  3. Dispatches to sub-services (Location, Camera, Screen, Audio)
 *  4. Heartbeats device status every 30s
 *  5. Detects tamper attempts
 */
@AndroidEntryPoint
class MonitoringService : LifecycleService() {

    companion object {
        const val ACTION_START = "guardianlink.action.START_MONITORING"
        const val ACTION_STOP  = "guardianlink.action.STOP_MONITORING"

        fun startIntent(context: Context) =
            Intent(context, MonitoringService::class.java).apply { action = ACTION_START }

        fun stopIntent(context: Context) =
            Intent(context, MonitoringService::class.java).apply { action = ACTION_STOP }
    }

    @Inject lateinit var firebaseManager: ChildFirebaseManager
    @Inject lateinit var securePrefs: SecurePreferences
    @Inject lateinit var pinManager: PinManager
    @Inject lateinit var appUsageTracker: AppUsageTracker
    @Inject lateinit var tamperDetector: TamperDetector
    @Inject lateinit var offlineQueue: OfflineQueue

    private var commandListener: ValueEventListener? = null
    private var heartbeatJob: Job? = null
    private var statusJob: Job? = null
    private val processedCommandIds = mutableSetOf<String>()

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        offlineQueue.initialize()
        startForegroundWithNotification()
        Timber.i("MonitoringService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        when (intent?.action) {
            ACTION_STOP -> {
                if (pinManager.isPinVerified()) stopSelf()
                else Timber.w("Stop attempted without PIN verification — ignored")
                return START_NOT_STICKY
            }
            else -> {
                initialize()
            }
        }
        // Restart automatically if killed
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        cleanup()
        // Reschedule via WorkManager fallback
        scheduleWorkManagerFallback()
        Timber.i("MonitoringService destroyed — scheduling restart")
    }

    // ── Initialization ────────────────────────────────────────────────────────

    private fun initialize() {
        val deviceId = securePrefs.getString(SecurePreferences.KEY_DEVICE_ID)
        if (deviceId.isBlank()) {
            Timber.e("No device ID found — cannot initialize monitoring")
            return
        }

        setDeviceOnline(deviceId)
        lifecycleScope.launch(Dispatchers.IO) {
            // Must happen before the listener attaches so a command that was left
            // EXECUTING by a crashed/killed previous process lifetime is resolved
            // to a terminal state rather than silently stuck forever (see
            // ChildFirebaseManager.reapStaleExecutingCommands doc comment).
            firebaseManager.reapStaleExecutingCommands(deviceId)
        }
        startCommandListener(deviceId)
        startHeartbeat(deviceId)
        startAppUsageTracking()
        tamperDetector.startWatching()
    }

    // ── Foreground notification ───────────────────────────────────────────────

    private fun startForegroundWithNotification() {
        val tapIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, NotificationChannels.MONITORING_SERVICE)
            .setContentTitle("GuardianLink Active")
            .setContentText("Device monitoring is running")
            .setSmallIcon(R.drawable.ic_shield)
            .setContentIntent(tapIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .build()

        startForeground(NotificationIds.FOREGROUND_SERVICE, notification)
    }

    // ── Command System ────────────────────────────────────────────────────────

    private fun startCommandListener(deviceId: String) {
        val ref = FirebaseDatabase.getInstance()
            .getReference(FirebasePaths.commandPath(deviceId))

        commandListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                snapshot.children.forEach { commandSnapshot ->
                    val command = commandSnapshot.getValue(Command::class.java) ?: return@forEach
                    val commandId = CommandSecurity.commandKeyOrId(command, commandSnapshot.key ?: "")
                    if (command.status != CommandStatus.PENDING || processedCommandIds.contains(commandId)) {
                        return@forEach
                    }

                    val parentId = securePrefs.getString(SecurePreferences.KEY_PARENT_ID)
                    val validation = CommandSecurity.validateForExecution(
                        command = command,
                        expectedDeviceId = deviceId,
                        authorizedParentId = parentId,
                        nowMs = System.currentTimeMillis(),
                        expectedCommandKey = commandSnapshot.key
                    )

                    when {
                        validation.isAllowed -> {
                            // Fast-path, in-process de-duplication only (avoids a redundant
                            // transaction round trip for events we've already claimed within
                            // this process lifetime). The actual, restart-safe correctness
                            // boundary is the claimCommandForExecution() RTDB transaction
                            // inside executeCommand() below — see its doc comment.
                            if (processedCommandIds.add(commandId)) {
                                lifecycleScope.launch(Dispatchers.IO) {
                                    executeCommand(command, commandSnapshot.key ?: "")
                                }
                            }
                        }
                        else -> lifecycleScope.launch(Dispatchers.IO) {
                            firebaseManager.updateCommandStatus(
                                deviceId,
                                commandSnapshot.key ?: "",
                                validation.status.name,
                                validation.message
                            )
                        }
                    }
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Timber.e("Command listener cancelled: ${error.message}")
                lifecycleScope.launch {
                    delay(5_000)
                    startCommandListener(deviceId)
                }
            }
        }
        ref.addValueEventListener(commandListener!!)
    }

    private suspend fun executeCommand(command: Command, commandKey: String) {
        val deviceId = securePrefs.getString(SecurePreferences.KEY_DEVICE_ID)
        val parentId = securePrefs.getString(SecurePreferences.KEY_PARENT_ID)
        val validation = CommandSecurity.validateForExecution(
            command = command,
            expectedDeviceId = deviceId,
            authorizedParentId = parentId,
            nowMs = System.currentTimeMillis(),
            expectedCommandKey = commandKey
        )
        if (!validation.isAllowed) {
            firebaseManager.updateCommandStatus(
                deviceId,
                commandKey,
                validation.status.name,
                validation.message
            )
            return
        }

        // Durable, restart-safe idempotency gate: only the caller that wins this
        // PENDING -> EXECUTING RTDB transaction may proceed. If this returns false,
        // some other invocation (e.g. a duplicate listener delivery, or an already-
        // completed prior attempt) has already claimed or resolved this command, so
        // executing it again here would risk double-executing a privileged action
        // (e.g. a second LOCK_DEVICE or START_CAMERA_STREAM).
        if (!firebaseManager.claimCommandForExecution(command.childDeviceId, commandKey)) {
            Timber.i("Command $commandKey already claimed or no longer pending — skipping")
            return
        }

        Timber.i("Executing command: ${command.type} for parent=${command.parentId}")

        try {
            when (command.type) {
                CommandType.START_CAMERA_STREAM -> {
                    val front = command.payload["front"] as? Boolean ?: true
                    startCameraStream(
                        front,
                        command.payload["sessionId"] as? String
                            ?: throw IllegalArgumentException("Stream session id required"),
                        (command.payload["expiresAt"] as? Number)?.toLong()
                            ?: throw IllegalArgumentException("Stream session expiry required")
                    )
                }
                CommandType.STOP_CAMERA_STREAM -> stopCameraStream()
                CommandType.CAPTURE_PHOTO -> capturePhoto(
                    front = command.payload["front"] as? Boolean ?: true
                )
                CommandType.SWITCH_CAMERA -> switchCamera()
                CommandType.START_SCREEN_MIRROR -> startScreenMirror()
                CommandType.STOP_SCREEN_MIRROR -> stopScreenMirror()
                CommandType.START_AUDIO_STREAM -> startAudioStream(
                    command.payload["sessionId"] as? String
                        ?: throw IllegalArgumentException("Stream session id required"),
                    (command.payload["expiresAt"] as? Number)?.toLong()
                        ?: throw IllegalArgumentException("Stream session expiry required")
                )
                CommandType.STOP_AUDIO_STREAM -> stopAudioStream()
                CommandType.LOCK_DEVICE -> lockDevice()
                CommandType.REBOOT -> {
                    if (!rebootDevice()) {
                        throw IllegalStateException("Reboot is unavailable on this device")
                    }
                }
                CommandType.BLOCK_APP -> {
                    val pkg = command.payload["packageName"] as? String ?: throw IllegalArgumentException("packageName required")
                    blockApp(pkg)
                }
                CommandType.UNBLOCK_APP -> {
                    val pkg = command.payload["packageName"] as? String ?: throw IllegalArgumentException("packageName required")
                    unblockApp(pkg)
                }
                CommandType.GET_INSTALLED_APPS -> reportInstalledApps()
                CommandType.GET_LOCATION -> reportCurrentLocation()
                CommandType.GET_CALL_LOGS -> reportCallLogs()
                CommandType.GET_SMS_LOGS -> reportSmsLogs()
                CommandType.GET_APP_USAGE -> reportAppUsage()
                CommandType.SYNC_SETTINGS -> syncSettings()
                CommandType.PING -> { /* Acknowledge success without side effects */ }
                else -> throw UnsupportedOperationException("Unsupported command: ${command.type}")
            }

            firebaseManager.updateCommandStatus(
                command.childDeviceId,
                commandKey,
                CommandStatus.COMPLETED.name,
                result = "Command completed successfully"
            )
        } catch (e: Exception) {
            Timber.e(e, "Command execution failed: ${command.type}")
            firebaseManager.updateCommandStatus(
                command.childDeviceId,
                commandKey,
                CommandStatus.FAILED.name,
                e.message ?: "Command execution failed"
            )
        }
    }

    // ── Heartbeat ─────────────────────────────────────────────────────────────

    private fun startHeartbeat(deviceId: String) {
        heartbeatJob?.cancel()
        heartbeatJob = lifecycleScope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    firebaseManager.pushHeartbeat(deviceId)
                } catch (e: Exception) {
                    Timber.e(e, "Heartbeat failed")
                }
                delay(30_000) // Every 30 seconds
            }
        }
    }

    // ── App Usage Tracking ────────────────────────────────────────────────────

    private fun startAppUsageTracking() {
        lifecycleScope.launch(Dispatchers.IO) {
            appUsageTracker.startTracking()
        }
    }

    // ── Service Dispatchers ───────────────────────────────────────────────────

    private fun startCameraStream(front: Boolean, sessionId: String, expiresAt: Long) {
        val intent = Intent(this, CameraStreamService::class.java).apply {
            action = CameraStreamService.ACTION_START
            putExtra("front", front)
            putExtra(CameraStreamService.EXTRA_SESSION_ID, sessionId)
            putExtra(CameraStreamService.EXTRA_EXPIRES_AT, expiresAt)
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun stopCameraStream() {
        startService(Intent(this, CameraStreamService::class.java).apply {
            action = CameraStreamService.ACTION_STOP
        })
    }

    private fun capturePhoto(front: Boolean) {
        startService(Intent(this, CameraStreamService::class.java).apply {
            action = CameraStreamService.ACTION_CAPTURE_PHOTO
            putExtra("front", front)
        })
    }

    private fun switchCamera() {
        startService(Intent(this, CameraStreamService::class.java).apply {
            action = CameraStreamService.ACTION_SWITCH_CAMERA
        })
    }

    private fun startScreenMirror() {
        startService(Intent(this, ScreenMirrorService::class.java).apply {
            action = ScreenMirrorService.ACTION_START
        })
    }

    private fun stopScreenMirror() {
        startService(Intent(this, ScreenMirrorService::class.java).apply {
            action = ScreenMirrorService.ACTION_STOP
        })
    }

    private fun startAudioStream(sessionId: String, expiresAt: Long) {
        ContextCompat.startForegroundService(this, Intent(this, AudioStreamService::class.java).apply {
            action = AudioStreamService.ACTION_START
            putExtra(AudioStreamService.EXTRA_SESSION_ID, sessionId)
            putExtra(AudioStreamService.EXTRA_EXPIRES_AT, expiresAt)
        })
    }

    private fun stopAudioStream() {
        startService(Intent(this, AudioStreamService::class.java).apply {
            action = AudioStreamService.ACTION_STOP
        })
    }

    private fun lockDevice() {
        val dpm = getSystemService(android.app.admin.DevicePolicyManager::class.java)
        dpm?.lockNow()
    }

    private fun rebootDevice(): Boolean {
        // Requires REBOOT permission or Device Owner status.
        // Fail closed unless the device is explicitly granted a privileged owner role.
        val dpm = getSystemService(android.app.admin.DevicePolicyManager::class.java)
        return dpm != null && false
    }

    private fun blockApp(packageName: String) {
        // Write to Firestore settings — AccessibilityService reads it
        lifecycleScope.launch(Dispatchers.IO) {
            firebaseManager.blockApp(
                securePrefs.getString(SecurePreferences.KEY_DEVICE_ID), packageName
            )
        }
    }

    private fun unblockApp(packageName: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            firebaseManager.unblockApp(
                securePrefs.getString(SecurePreferences.KEY_DEVICE_ID), packageName
            )
        }
    }

    private fun reportInstalledApps() {
        lifecycleScope.launch(Dispatchers.IO) {
            val apps = packageManager.getInstalledApplications(0).map { info ->
                mapOf(
                    "packageName" to info.packageName,
                    "appName"     to (packageManager.getApplicationLabel(info).toString())
                )
            }
            val deviceId = securePrefs.getString(SecurePreferences.KEY_DEVICE_ID)
            firebaseManager.pushInstalledApps(deviceId, apps)
        }
    }

    private fun reportCurrentLocation() {
        startService(LocationService.startIntent(this))
    }

    private fun reportCallLogs() {
        lifecycleScope.launch(Dispatchers.IO) {
            val deviceId = securePrefs.getString(SecurePreferences.KEY_DEVICE_ID)
            firebaseManager.syncCallLogs(this@MonitoringService, deviceId)
        }
    }

    private fun reportSmsLogs() {
        lifecycleScope.launch(Dispatchers.IO) {
            val deviceId = securePrefs.getString(SecurePreferences.KEY_DEVICE_ID)
            firebaseManager.syncSmsLogs(this@MonitoringService, deviceId)
        }
    }

    private fun reportAppUsage() {
        lifecycleScope.launch(Dispatchers.IO) {
            val deviceId = securePrefs.getString(SecurePreferences.KEY_DEVICE_ID)
            appUsageTracker.syncToFirebase(deviceId)
        }
    }

    private fun syncSettings() {
        lifecycleScope.launch(Dispatchers.IO) {
            val deviceId = securePrefs.getString(SecurePreferences.KEY_DEVICE_ID)
            firebaseManager.fetchAndApplySettings(deviceId)
        }
    }

    // ── Status ────────────────────────────────────────────────────────────────

    private fun setDeviceOnline(deviceId: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            firebaseManager.setOnlineStatus(deviceId, DeviceOnlineStatus.ONLINE)
        }
    }

    // ── Cleanup ───────────────────────────────────────────────────────────────

    private fun cleanup() {
        heartbeatJob?.cancel()
        statusJob?.cancel()
        commandListener?.let {
            val deviceId = securePrefs.getString(SecurePreferences.KEY_DEVICE_ID)
            FirebaseDatabase.getInstance()
                .getReference(FirebasePaths.commandPath(deviceId))
                .removeEventListener(it)
        }
        tamperDetector.stopWatching()
        offlineQueue.destroy()
        lifecycleScope.launch(Dispatchers.IO) {
            val deviceId = securePrefs.getString(SecurePreferences.KEY_DEVICE_ID)
            firebaseManager.setOnlineStatus(deviceId, DeviceOnlineStatus.OFFLINE)
        }
    }

    private fun scheduleWorkManagerFallback() {
        androidx.work.OneTimeWorkRequestBuilder<com.guardianlink.child.worker.ServiceRestartWorker>()
            .setInitialDelay(10, java.util.concurrent.TimeUnit.SECONDS)
            .build()
            .also { request ->
                androidx.work.WorkManager.getInstance(applicationContext)
                    .enqueueUniqueWork(
                        "service_restart",
                        androidx.work.ExistingWorkPolicy.REPLACE,
                        request
                    )
            }
    }
}
