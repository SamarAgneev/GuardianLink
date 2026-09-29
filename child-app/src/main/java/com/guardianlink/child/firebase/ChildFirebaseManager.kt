// child-app/src/main/java/com/guardianlink/child/firebase/ChildFirebaseManager.kt
package com.guardianlink.child.firebase

import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.CallLog
import android.provider.Telephony
import android.util.Base64
import androidx.core.content.ContextCompat
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.guardianlink.child.BuildConfig
import com.guardianlink.child.data.OfflineQueue
import com.guardianlink.child.ui.PairingResult
import com.guardianlink.common.constants.FirebasePaths
import com.guardianlink.common.firebase.WorkerApiClient
import com.guardianlink.common.firebase.createDocumentIfAbsent
import com.guardianlink.common.model.*
import com.guardianlink.common.security.SecurePreferences
import com.guardianlink.common.util.CommunicationUtils
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.tasks.await
import timber.log.Timber
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ChildFirebaseManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val securePrefs: SecurePreferences,
    private val offlineQueue: OfflineQueue
) {
    private val db      = FirebaseFirestore.getInstance()
    private val rtdb    = FirebaseDatabase.getInstance().reference

    // ── Location ──────────────────────────────────────────────────────────────

    suspend fun pushLocation(deviceId: String, data: LocationData) {
        if (deviceId.isBlank()) return
        if (!com.guardianlink.common.util.LocationUtils.isValidCoordinate(data.latitude, data.longitude)) return
        val locationTimeMs = data.timestamp.toDate().time
        if (locationTimeMs <= 0L || System.currentTimeMillis() - locationTimeMs > 5 * 60_000L) return

        val map = mapOf(
            "lat" to data.latitude,
            "lng" to data.longitude,
            "accuracy" to data.accuracy,
            "speed" to data.speed,
            "altitude" to data.altitude,
            "address" to data.address,
            "battery" to data.batteryLevel,
            "timestamp" to ServerValue.TIMESTAMP
        )
        try {
            rtdb.child(FirebasePaths.locationPath(deviceId)).setValue(map).await()
        } catch (e: Exception) {
            offlineQueue.enqueueRealtimeSet(
                FirebasePaths.locationPath(deviceId),
                mapOf(
                    "lat" to data.latitude,
                    "lng" to data.longitude,
                    "accuracy" to data.accuracy,
                    "speed" to data.speed,
                    "altitude" to data.altitude,
                    "address" to data.address,
                    "battery" to data.batteryLevel,
                    "timestamp" to locationTimeMs
                ),
                OfflineQueue.PRIORITY_LOCATION,
                itemId = "${FirebasePaths.locationPath(deviceId)}:$locationTimeMs"
            )
        }

        val historyId = com.guardianlink.common.util.CommunicationUtils.stableRecordId(
            "location", deviceId, locationTimeMs.toString(), data.latitude.toString(), data.longitude.toString()
        )
        val history = data.copy(timestamp = Timestamp(Date(locationTimeMs)))
        try {
            db.collection(FirebasePaths.COLLECTION_DEVICES)
                .document(deviceId)
                .collection("location_history")
                .document(historyId)
                .createDocumentIfAbsent(history)
        } catch (e: com.google.firebase.firestore.FirebaseFirestoreException) {
            if (e.code != com.google.firebase.firestore.FirebaseFirestoreException.Code.ALREADY_EXISTS) {
                offlineQueue.enqueueFirestoreCreate(
                    "${FirebasePaths.COLLECTION_DEVICES}/$deviceId/location_history",
                    historyId,
                    mapOf(
                        "deviceId" to deviceId,
                        "latitude" to data.latitude,
                        "longitude" to data.longitude,
                        "accuracy" to data.accuracy,
                        "speed" to data.speed,
                        "bearing" to data.bearing,
                        "altitude" to data.altitude,
                        "address" to data.address,
                        "timestamp" to Timestamp(Date(locationTimeMs)),
                        "batteryLevel" to data.batteryLevel
                    ),
                    OfflineQueue.PRIORITY_LOCATION
                )
            }
        }
    }

    // ── Status / Heartbeat ────────────────────────────────────────────────────

    suspend fun pushHeartbeat(deviceId: String) {
        val updates = mapOf("online" to true, "lastSeen" to ServerValue.TIMESTAMP, "battery" to getBatteryLevel())
        try {
            rtdb.child(FirebasePaths.statusPath(deviceId)).updateChildren(updates).await()
        } catch (e: Exception) {
            offlineQueue.enqueueRealtimeUpdate(FirebasePaths.statusPath(deviceId), updates, 20)
        }
    }

    suspend fun setOnlineStatus(deviceId: String, status: DeviceOnlineStatus) {
        rtdb.child(FirebasePaths.statusPath(deviceId)).updateChildren(
            mapOf(
                "online"    to (status == DeviceOnlineStatus.ONLINE),
                "status"    to status.name,
                "lastSeen"  to ServerValue.TIMESTAMP
            )
        ).await()
    }

    // ── Commands ──────────────────────────────────────────────────────────────

    suspend fun updateCommandStatus(
        deviceId: String,
        commandKey: String,
        status: String,
        errorMsg: String? = null,
        result: String? = null
    ) {
        val updates = mutableMapOf<String, Any>(
            "status"         to status,
            "lastUpdatedAt" to ServerValue.TIMESTAMP
        )
        val normalizedStatus = status.uppercase(Locale.US)
        if (normalizedStatus in setOf("EXECUTING", "COMPLETED", "FAILED", "EXPIRED", "REJECTED")) {
            updates["executedAt"] = ServerValue.TIMESTAMP
        }
        if (errorMsg != null) updates["error"] = errorMsg
        if (result != null) updates["result"] = result

        try {
            rtdb.child("commands/$deviceId/$commandKey").updateChildren(updates).await()
        } catch (e: Exception) {
            offlineQueue.enqueueRealtimeUpdate(
                "commands/$deviceId/$commandKey",
                updates,
                OfflineQueue.PRIORITY_COMMAND,
                itemId = "commands/$deviceId/$commandKey:$normalizedStatus"
            )
        }
    }

    /**
     * Atomically claims a command for execution by transitioning its status
     * from PENDING to EXECUTING inside a Realtime Database transaction.
     *
     * This is the durable, restart-safe replacement for the previous
     * in-memory-only `processedCommandIds` de-duplication: because the
     * transaction runs against the server-held value of `status` (not a
     * client-cached value), at most one caller — across process restarts,
     * duplicate `ValueEventListener` deliveries, or listener re-attachment
     * after a dropped connection — can ever win the PENDING -> EXECUTING
     * transition for a given command. A process-memory `processedCommandIds`
     * set is still used by the caller as a fast-path optimization to avoid
     * a network round trip for events it already knows it handled in this
     * process lifetime, but it is no longer the security/correctness
     * boundary; this transaction is.
     *
     * Returns true only if this call performed the PENDING -> EXECUTING
     * transition (i.e., this caller "won" the claim and should proceed to
     * execute the command). Returns false if the command was not PENDING
     * (already claimed, already terminal, or missing), or if the transaction
     * could not be committed (e.g. offline).
     */
    suspend fun claimCommandForExecution(deviceId: String, commandKey: String): Boolean {
        if (deviceId.isBlank() || commandKey.isBlank()) return false
        val statusRef = rtdb.child("commands/$deviceId/$commandKey/status")
        return try {
            kotlinx.coroutines.suspendCancellableCoroutine { cont ->
                statusRef.runTransaction(object : com.google.firebase.database.Transaction.Handler {
                    override fun doTransaction(
                        currentData: com.google.firebase.database.MutableData
                    ): com.google.firebase.database.Transaction.Result {
                        val current = currentData.getValue(String::class.java)
                        return if (current == CommandStatus.PENDING.name) {
                            currentData.value = CommandStatus.EXECUTING.name
                            com.google.firebase.database.Transaction.success(currentData)
                        } else {
                            // Already claimed/terminal — abort without modifying data.
                            com.google.firebase.database.Transaction.abort()
                        }
                    }

                    override fun onComplete(
                        error: com.google.firebase.database.DatabaseError?,
                        committed: Boolean,
                        snapshot: com.google.firebase.database.DataSnapshot?
                    ) {
                        if (error != null) {
                            Timber.e(error.toException(), "Command claim transaction failed")
                        }
                        if (cont.isActive) cont.resume(committed && error == null) {}
                    }
                })
            }.also { won ->
                if (won) {
                    // Record the authoritative EXECUTING timestamp/marker now that we've won the
                    // claim, so a stale-command reaper (see reapStaleExecutingCommands) has an
                    // accurate `lastUpdatedAt` to measure staleness from even if this device
                    // crashes before ever calling updateCommandStatus(EXECUTING) again.
                    try {
                        rtdb.child("commands/$deviceId/$commandKey")
                            .updateChildren(mapOf("lastUpdatedAt" to ServerValue.TIMESTAMP))
                            .await()
                    } catch (_: Exception) {
                        // Non-fatal — the reaper falls back to a conservative default if this
                        // write didn't land; see reapStaleExecutingCommands.
                    }
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Command claim failed (offline or transaction error)")
            false
        }
    }

    /**
     * On MonitoringService startup, any command left in EXECUTING from a
     * previous process lifetime (crash, OOM-kill, force-stop mid-execution)
     * would otherwise be stuck forever: the listener in MonitoringService
     * deliberately ignores non-PENDING commands, so nothing will ever
     * re-drive it to a terminal state, and the parent UI would show a
     * command as perpetually "in progress."
     *
     * This reaps any EXECUTING command older than [staleAfterMs] and marks
     * it FAILED with an explicit, honest reason, rather than leaving it
     * silently stuck or guessing at re-execution (re-executing a command of
     * unknown type after an unknown-duration crash is not safe to do
     * automatically for privileged commands such as LOCK_DEVICE or
     * START_CAMERA_STREAM).
     */
    suspend fun reapStaleExecutingCommands(deviceId: String, staleAfterMs: Long = 5 * 60_000L) {
        if (deviceId.isBlank()) return
        try {
            val snapshot = rtdb.child("commands/$deviceId").get().await()
            val nowMs = System.currentTimeMillis()
            snapshot.children.forEach { child ->
                val status = child.child("status").getValue(String::class.java)
                if (status != CommandStatus.EXECUTING.name) return@forEach
                val lastUpdatedAt = child.child("lastUpdatedAt").getValue(Long::class.java) ?: 0L
                val isStale = lastUpdatedAt <= 0L || (nowMs - lastUpdatedAt) >= staleAfterMs
                if (isStale) {
                    val key = child.key ?: return@forEach
                    Timber.w("Reaping stale EXECUTING command $key on device $deviceId")
                    updateCommandStatus(
                        deviceId,
                        key,
                        CommandStatus.FAILED.name,
                        errorMsg = "Execution did not complete before the app restarted"
                    )
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to reap stale executing commands")
        }
    }

    // ── Alerts ────────────────────────────────────────────────────────────────

    suspend fun pushAlert(
        deviceId: String,
        type: AlertType,
        title: String,
        message: String,
        severity: AlertSeverity = AlertSeverity.MEDIUM,
        metadata: Map<String, Any> = emptyMap()
    ) {
        val alertId = com.guardianlink.common.util.CommunicationUtils.stableRecordId(
            "alert", deviceId, type.name, title, message, UUID.randomUUID().toString()
        )
        val alert = mapOf(
            "id"         to alertId,
            "deviceId"   to deviceId,
            "type"       to type.name,
            "severity"   to severity.name,
            "title"      to title,
            "message"    to message,
            "metadata"   to metadata,
            "isRead"     to false,
            "isResolved" to false,
            "timestamp"  to Timestamp.now()
        )
        try {
            db.collection(FirebasePaths.COLLECTION_ALERTS).document(alertId).createDocumentIfAbsent(alert)
        } catch (e: com.google.firebase.firestore.FirebaseFirestoreException) {
            if (e.code != com.google.firebase.firestore.FirebaseFirestoreException.Code.ALREADY_EXISTS) {
                offlineQueue.enqueueFirestoreCreate(
                    FirebasePaths.COLLECTION_ALERTS,
                    alertId,
                    alert,
                    OfflineQueue.PRIORITY_ALERT
                )
            }
        }
    }

    // ── App Blocking ──────────────────────────────────────────────────────────

    suspend fun blockApp(deviceId: String, packageName: String) {
        db.collection(FirebasePaths.COLLECTION_SETTINGS).document(deviceId)
            .update("blockedApps", com.google.firebase.firestore.FieldValue.arrayUnion(packageName))
            .await()
    }

    suspend fun unblockApp(deviceId: String, packageName: String) {
        db.collection(FirebasePaths.COLLECTION_SETTINGS).document(deviceId)
            .update("blockedApps", com.google.firebase.firestore.FieldValue.arrayRemove(packageName))
            .await()
    }

    // ── Geofences ─────────────────────────────────────────────────────────────

    suspend fun getGeofences(deviceId: String): List<GeoFence> {
        return try {
            val doc = db.collection(FirebasePaths.COLLECTION_SETTINGS)
                .document(deviceId).get().await()
            @Suppress("UNCHECKED_CAST")
            val geofenceData = doc.get("geofences") as? List<Map<String, Any>> ?: emptyList()
            geofenceData.map { map ->
                GeoFence(
                    id           = map["id"] as? String ?: "",
                    name         = map["name"] as? String ?: "",
                    latitude     = (map["latitude"] as? Double) ?: 0.0,
                    longitude    = (map["longitude"] as? Double) ?: 0.0,
                    radiusMeters = ((map["radiusMeters"] as? Double) ?: 200.0).toFloat(),
                    alertOnEnter = map["alertOnEnter"] as? Boolean ?: true,
                    alertOnExit  = map["alertOnExit"] as? Boolean ?: true,
                    isActive     = map["isActive"] as? Boolean ?: true
                )
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to fetch geofences")
            emptyList()
        }
    }

    // ── Call Logs ─────────────────────────────────────────────────────────────

    suspend fun syncCallLogs(context: Context, deviceId: String) {
        if (deviceId.isBlank() || !hasPermission(context, android.Manifest.permission.READ_CALL_LOG)) return
        val settings = fetchAndApplySettings(deviceId) ?: return
        if (!settings.callMonitoringEnabled) return
        val contacts = readContactsIfPermitted(context)
        val cursor: Cursor? = context.contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            arrayOf(
                CallLog.Calls._ID,
                CallLog.Calls.NUMBER,
                CallLog.Calls.CACHED_NAME,
                CallLog.Calls.TYPE,
                CallLog.Calls.DURATION,
                CallLog.Calls.DATE
            ),
            null, null,
            "${CallLog.Calls.DATE} DESC LIMIT 100"
        )

        val logs = mutableListOf<Map<String, Any>>()
        cursor?.use { c ->
            val numberIdx  = c.getColumnIndex(CallLog.Calls.NUMBER)
            val nameIdx    = c.getColumnIndex(CallLog.Calls.CACHED_NAME)
            val typeIdx    = c.getColumnIndex(CallLog.Calls.TYPE)
            val durIdx     = c.getColumnIndex(CallLog.Calls.DURATION)
            val dateIdx    = c.getColumnIndex(CallLog.Calls.DATE)

            while (c.moveToNext()) {
                val rowId = c.getLong(c.getColumnIndexOrThrow(CallLog.Calls._ID)).toString()
                val rawNumber = c.getString(c.getColumnIndexOrThrow(CallLog.Calls.NUMBER)).orEmpty()
                val normalizedNumber = CommunicationUtils.normalizePhoneNumber(rawNumber)
                val contact = findContact(contacts, normalizedNumber)
                val timestampMs = c.getLong(c.getColumnIndexOrThrow(CallLog.Calls.DATE))
                if (timestampMs <= 0L) continue
                val type = when (c.getInt(typeIdx)) {
                    CallLog.Calls.INCOMING_TYPE  -> "INCOMING"
                    CallLog.Calls.OUTGOING_TYPE  -> "OUTGOING"
                    CallLog.Calls.MISSED_TYPE    -> "MISSED"
                    CallLog.Calls.REJECTED_TYPE,
                    CallLog.Calls.BLOCKED_TYPE   -> "REJECTED"
                    else                          -> "UNKNOWN"
                }
                val recordId = CommunicationUtils.stableRecordId("call", deviceId, rowId)
                val cachedName = c.getString(nameIdx).orEmpty()
                val isUnknownContact = contacts.accessible &&
                    normalizedNumber.isNotBlank() &&
                    contacts.numbers.isNotEmpty() &&
                    contact == null
                val log = mapOf(
                    "id"              to recordId,
                    "deviceId"        to deviceId,
                    "number"          to normalizedNumber,
                    "contactName"     to cachedName.ifBlank { contact?.value.orEmpty() },
                    "type"            to type,
                    "durationSeconds" to c.getLong(durIdx),
                    "timestamp"       to Timestamp(Date(timestampMs)),
                    "isUnknownContact" to isUnknownContact
                )
                createIfAbsent(db.collection(FirebasePaths.COLLECTION_CALL_LOGS).document(recordId), log)
                if (isUnknownContact) {
                    createAlertIfAbsent(
                        alertId = CommunicationUtils.stableRecordId("call_unknown", recordId),
                        deviceId = deviceId,
                        type = AlertType.UNKNOWN_CONTACT,
                        title = "Call from unknown contact",
                        message = "A call was received from a number not found in contacts.",
                        metadata = mapOf("recordId" to recordId),
                        severity = AlertSeverity.MEDIUM
                    )
                }
            }
        }
    }

    // ── SMS Logs ──────────────────────────────────────────────────────────────

    suspend fun syncSmsLogs(context: Context, deviceId: String) {
        if (deviceId.isBlank() || !hasPermission(context, android.Manifest.permission.READ_SMS)) return
        val settings = fetchAndApplySettings(deviceId) ?: return
        if (!settings.smsMonitoringEnabled) return
        val contacts = readContactsIfPermitted(context)
        val cursor = context.contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf(
                Telephony.Sms._ID,
                Telephony.Sms.ADDRESS,
                Telephony.Sms.BODY,
                Telephony.Sms.TYPE,
                Telephony.Sms.DATE,
                Telephony.Sms.PERSON
            ),
            null, null,
            "${Telephony.Sms.DATE} DESC LIMIT 200"
        )

        val logs = mutableListOf<Map<String, Any>>()
        cursor?.use { c ->
            val addrIdx = c.getColumnIndex(Telephony.Sms.ADDRESS)
            val bodyIdx = c.getColumnIndex(Telephony.Sms.BODY)
            val typeIdx = c.getColumnIndex(Telephony.Sms.TYPE)
            val dateIdx = c.getColumnIndex(Telephony.Sms.DATE)

            while (c.moveToNext()) {
                val rawNumber = c.getString(c.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)).orEmpty()
                val normalizedNumber = CommunicationUtils.normalizePhoneNumber(rawNumber)
                val body = c.getString(c.getColumnIndexOrThrow(Telephony.Sms.BODY)).orEmpty()
                val timestampMs = c.getLong(c.getColumnIndexOrThrow(Telephony.Sms.DATE))
                if (timestampMs <= 0L) continue
                val direction = if (c.getInt(typeIdx) == Telephony.Sms.MESSAGE_TYPE_INBOX)
                    "INBOX" else "SENT"
                val contact = findContact(contacts, normalizedNumber)
                val flaggedKeywords = CommunicationUtils.findKeywordMatches(body, settings.blockedKeywords)
                // Deliberately NOT using the provider row `_ID` here (unlike
                // syncCallLogs, which has no real-time counterpart and so has
                // no cross-path collision risk). SmsReceiver — the real-time
                // path for the exact same underlying messages — has no
                // reliable access to a provider row id at broadcast time (the
                // SMS_RECEIVED broadcast can fire before the message is even
                // inserted into the Telephony provider), so it derives its
                // record id from (number, timestamp, body) instead. Using a
                // different derivation here would mean the same real message
                // gets two different record ids — and therefore two separate
                // Firestore documents — depending on which path processed it
                // first, defeating createIfAbsent's dedup guarantee. Matching
                // SmsReceiver's exact formula here means both paths produce
                // the identical id for the identical message.
                val recordId = CommunicationUtils.stableRecordId(
                    "sms", deviceId, normalizedNumber, timestampMs.toString(), body
                )
                val isUnknownContact = contacts.accessible &&
                    normalizedNumber.isNotBlank() &&
                    contacts.numbers.isNotEmpty() &&
                    contact == null
                val log = mapOf(
                    "id" to recordId,
                    "deviceId" to deviceId,
                    "number" to normalizedNumber,
                    "contactName" to contact?.value.orEmpty(),
                    "body" to body,
                    "direction" to direction,
                    "timestamp" to Timestamp(Date(timestampMs)),
                    "isUnknownContact" to isUnknownContact,
                    "containsKeyword" to flaggedKeywords.isNotEmpty(),
                    "flaggedKeywords" to flaggedKeywords
                )
                createIfAbsent(db.collection(FirebasePaths.COLLECTION_SMS_LOGS).document(recordId), log)
                if (flaggedKeywords.isNotEmpty()) {
                    createAlertIfAbsent(
                        alertId = CommunicationUtils.stableRecordId("sms_keyword", recordId),
                        deviceId = deviceId,
                        type = AlertType.SMS_KEYWORD,
                        title = "SMS keyword alert",
                        message = "An SMS matched configured monitoring keywords.",
                        metadata = mapOf("recordId" to recordId, "keywords" to flaggedKeywords),
                        severity = AlertSeverity.HIGH
                    )
                }
                if (isUnknownContact) {
                    createAlertIfAbsent(
                        alertId = CommunicationUtils.stableRecordId("sms_unknown", recordId),
                        deviceId = deviceId,
                        type = AlertType.UNKNOWN_CONTACT,
                        title = "SMS from unknown contact",
                        message = "An SMS was received from a number not found in contacts.",
                        metadata = mapOf("recordId" to recordId),
                        severity = AlertSeverity.MEDIUM
                    )
                }
            }
        }
    }

    private data class ContactDirectory(
        val accessible: Boolean,
        val numbers: Map<String, String>
    )

    private fun readContactsIfPermitted(context: Context): ContactDirectory {
        if (!hasPermission(context, android.Manifest.permission.READ_CONTACTS)) {
            return ContactDirectory(false, emptyMap())
        }
        val contacts = mutableMapOf<String, String>()
        context.contentResolver.query(
            Phone.CONTENT_URI,
            arrayOf(Phone.NUMBER, Phone.DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
            val numberIndex = cursor.getColumnIndex(Phone.NUMBER)
            val nameIndex = cursor.getColumnIndex(Phone.DISPLAY_NAME)
            while (cursor.moveToNext()) {
                val number = CommunicationUtils.normalizePhoneNumber(cursor.getString(numberIndex))
                if (number.isNotBlank()) contacts[number] = cursor.getString(nameIndex).orEmpty()
            }
        }
        return ContactDirectory(true, contacts)
    }

    private fun findContact(directory: ContactDirectory, number: String): Map.Entry<String, String>? =
        if (!directory.accessible || number.isBlank()) null
        else directory.numbers.entries.firstOrNull { (contactNumber, _) ->
            CommunicationUtils.phoneNumbersMatch(contactNumber, number)
        }

    private fun hasPermission(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private suspend fun createIfAbsent(
        reference: com.google.firebase.firestore.DocumentReference,
        data: Map<String, Any>
    ) {
        try {
            reference.createDocumentIfAbsent(data)
        } catch (e: com.google.firebase.firestore.FirebaseFirestoreException) {
            if (e.code != com.google.firebase.firestore.FirebaseFirestoreException.Code.ALREADY_EXISTS) {
                offlineQueue.enqueueFirestoreCreate(
                    reference.parent.path,
                    reference.id,
                    data,
                    OfflineQueue.PRIORITY_LOG
                )
            }
            Timber.d("Communication record already exists or could not be created: ${e.javaClass.simpleName}")
        }
    }

    private suspend fun createAlertIfAbsent(
        alertId: String,
        deviceId: String,
        type: AlertType,
        title: String,
        message: String,
        metadata: Map<String, Any>,
        severity: AlertSeverity
    ) {
        val alert = mapOf(
            "deviceId" to deviceId,
            "type" to type.name,
            "severity" to severity.name,
            "title" to title,
            "message" to message,
            "metadata" to metadata,
            "isRead" to false,
            "isResolved" to false,
            "timestamp" to Timestamp.now()
        )
        try {
            db.collection(FirebasePaths.COLLECTION_ALERTS).document(alertId).createDocumentIfAbsent(alert)
        } catch (e: com.google.firebase.firestore.FirebaseFirestoreException) {
            if (e.code != com.google.firebase.firestore.FirebaseFirestoreException.Code.ALREADY_EXISTS) {
                offlineQueue.enqueueFirestoreCreate(
                    FirebasePaths.COLLECTION_ALERTS,
                    alertId,
                    alert,
                    OfflineQueue.PRIORITY_ALERT
                )
            }
            Timber.d("Communication alert already exists or could not be created: ${e.javaClass.simpleName}")
        }
    }

    // ── Installed Apps ────────────────────────────────────────────────────────

    suspend fun pushInstalledApps(deviceId: String, apps: List<Map<String, Any>>) {
        db.collection(FirebasePaths.COLLECTION_DEVICES)
            .document(deviceId)
            .update("installedApps", apps)
            .await()
    }

    // ── Streaming Frames (fallback) ───────────────────────────────────────────

    fun pushScreenFrame(deviceId: String, sessionId: String, frameBytes: ByteArray) {
        val base64 = Base64.encodeToString(frameBytes, Base64.NO_WRAP)
        rtdb.child("stream_frames/$deviceId/screen").setValue(
            mapOf(
                "frame" to base64,
                "sessionId" to sessionId,
                "ts" to ServerValue.TIMESTAMP
            )
        )
    }

    fun pushCameraFrame(deviceId: String, sessionId: String, frameBytes: ByteArray) {
        val base64 = Base64.encodeToString(frameBytes, Base64.NO_WRAP)
        rtdb.child("stream_frames/$deviceId/camera").setValue(
            mapOf(
                "frame" to base64,
                "sessionId" to sessionId,
                "ts" to ServerValue.TIMESTAMP
            )
        )
    }

    fun pushAudioChunk(deviceId: String, sessionId: String, data: ByteArray) {
        val base64 = Base64.encodeToString(data, Base64.NO_WRAP)
        rtdb.child("stream_frames/$deviceId/audio").setValue(
            mapOf(
                "chunk" to base64,
                "sessionId" to sessionId,
                "ts" to ServerValue.TIMESTAMP
            )
        )
    }

    // ── Photo Upload ──────────────────────────────────────────────────────────

    suspend fun uploadPhoto(deviceId: String, file: File, filename: String) {
        val currentUser = FirebaseAuth.getInstance().currentUser
            ?: error("Paired device authentication is required")
        require(currentUser.uid == deviceId) { "A device may upload only its own photos" }
        val idToken = currentUser.getIdToken(false).await().token
            ?: error("Could not retrieve device authentication token")
        WorkerApiClient.putBytes(
            BuildConfig.CLOUDFLARE_WORKER_URL,
            "/v1/media/photos/${Uri.encode(deviceId)}/${Uri.encode(filename)}",
            idToken,
            "image/jpeg",
            file.readBytes()
        )
    }

    // ── Stream Status ─────────────────────────────────────────────────────────

    suspend fun updateStreamStatus(deviceId: String, type: StreamType, active: Boolean) {
        rtdb.child(FirebasePaths.streamStatusPath(deviceId))
            .updateChildren(mapOf(type.name.lowercase() to active))
            .await()
    }

    suspend fun updateScreenStreamStatus(
        deviceId: String,
        sessionId: String,
        state: String,
        reason: String
    ) {
        rtdb.child(FirebasePaths.streamStatusPath(deviceId)).child("screen").setValue(
            mapOf(
                "state" to state,
                "active" to (state == "ACTIVE"),
                "sessionId" to sessionId,
                "reason" to reason,
                "updatedAt" to ServerValue.TIMESTAMP
            )
        ).await()
    }

    suspend fun updateStreamSession(
        sessionId: String,
        deviceId: String,
        status: StreamSessionStatus,
        active: Boolean,
        reason: String = ""
    ) {
        val now = Timestamp.now()
        val updates = mutableMapOf<String, Any>(
            "status" to status.name,
            "isActive" to active,
            "failureReason" to reason,
            "lastUpdatedAt" to now
        )
        if (status == StreamSessionStatus.ACTIVE) updates["startedAt"] = now
        if (status == StreamSessionStatus.STOPPED || status == StreamSessionStatus.FAILED) {
            updates["endedAt"] = now
        }
        db.collection(FirebasePaths.COLLECTION_STREAM_SESSIONS)
            .document(sessionId)
            .update(updates)
            .await()
    }

    // ── Settings ──────────────────────────────────────────────────────────────

    suspend fun fetchAndApplySettings(deviceId: String): ParentalSettings? {
        return try {
            val doc = db.collection(FirebasePaths.COLLECTION_SETTINGS)
                .document(deviceId).get().await()
            doc.toObject(ParentalSettings::class.java)
        } catch (e: Exception) {
            Timber.e(e, "Failed to fetch settings")
            null
        }
    }

    // ── Pairing ───────────────────────────────────────────────────────────────

    suspend fun redeemPairingCode(code: String, childName: String): PairingResult {
        return try {
            val result = WorkerApiClient.postJson(
                BuildConfig.CLOUDFLARE_WORKER_URL,
                "/v1/pairing/redeem",
                null,
                mapOf("code" to code.trim(), "childName" to childName.trim())
            )
            val deviceId = result.optString("deviceId")
            val parentId = result.optString("parentId")
            val customToken = result.optString("customToken")
            if (parentId.isBlank()) {
                return PairingResult.InvalidCode
            }
            require(deviceId.isNotBlank() && customToken.isNotBlank()) {
                "Pairing service returned incomplete credentials"
            }
            FirebaseAuth.getInstance().signInWithCustomToken(customToken).await()

            securePrefs.putString(SecurePreferences.KEY_DEVICE_ID, deviceId)
            securePrefs.putString(SecurePreferences.KEY_PARENT_ID, parentId)
            securePrefs.putString(SecurePreferences.KEY_CHILD_NAME, childName.trim())
            securePrefs.putBoolean(SecurePreferences.KEY_PAIRING_DONE, true)
            PairingResult.Success(parentId)
        } catch (e: Exception) {
            Timber.e(e, "Pairing error")
            if (e.message?.contains("expired", ignoreCase = true) == true ||
                e.message?.contains("invalid", ignoreCase = true) == true ||
                e.message?.contains("used", ignoreCase = true) == true ||
                e.message?.contains("permission-denied", ignoreCase = true) == true) {
                PairingResult.InvalidCode
            } else {
                PairingResult.Error(e.message)
            }
        }
    }

    private fun getBatteryLevel(): Int {
        val bm = context.getSystemService(android.os.BatteryManager::class.java)
        return bm?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
    }
}
