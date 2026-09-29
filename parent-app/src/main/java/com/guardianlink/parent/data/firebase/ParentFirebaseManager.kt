// parent-app/src/main/java/com/guardianlink/parent/data/firebase/ParentFirebaseManager.kt
package com.guardianlink.parent.data.firebase

import android.content.Context
import android.net.Uri
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import com.guardianlink.common.constants.FirebasePaths
import com.guardianlink.common.firebase.WorkerApiClient
import com.guardianlink.common.model.*
import com.guardianlink.parent.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import timber.log.Timber
import java.util.Date
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ParentFirebaseManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val db   = FirebaseFirestore.getInstance()
    private val rtdb = FirebaseDatabase.getInstance().reference

    // ── Device Status (realtime) ──────────────────────────────────────────────

    fun observeDeviceStatus(deviceId: String): Flow<Map<String, Any?>> = callbackFlow {
        val ref = rtdb.child(FirebasePaths.statusPath(deviceId))
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                @Suppress("UNCHECKED_CAST")
                trySend(snapshot.value as? Map<String, Any?> ?: emptyMap())
            }
            override fun onCancelled(error: DatabaseError) {
                close(Exception(error.message))
            }
        }
        ref.addValueEventListener(listener)
        awaitClose { ref.removeEventListener(listener) }
    }

    // ── Live Location (realtime) ──────────────────────────────────────────────

    fun observeLocation(deviceId: String): Flow<LocationData?> = callbackFlow {
        val ref = rtdb.child(FirebasePaths.locationPath(deviceId))
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                try {
                    val lat = snapshot.child("lat").getValue(Double::class.java) ?: 0.0
                    val lng = snapshot.child("lng").getValue(Double::class.java) ?: 0.0
                    if (!com.guardianlink.common.util.LocationUtils.isValidCoordinate(lat, lng)) {
                        trySend(null)
                        return
                    }

                    val acc = snapshot.child("accuracy").getValue(Double::class.java)?.toFloat() ?: 0f
                    val addr = snapshot.child("address").getValue(String::class.java) ?: ""
                    val bat = snapshot.child("battery").getValue(Int::class.java) ?: 0
                    val speed = snapshot.child("speed").getValue(Double::class.java)?.toFloat() ?: 0f
                    val altitude = snapshot.child("altitude").getValue(Double::class.java) ?: 0.0
                    val timestampMs = snapshot.child("timestamp").getValue(Long::class.java)
                        ?: System.currentTimeMillis()

                    trySend(LocationData(
                        deviceId = deviceId,
                        latitude = lat,
                        longitude = lng,
                        accuracy = acc.coerceAtLeast(1f),
                        speed = speed,
                        altitude = altitude,
                        address = addr,
                        timestamp = Timestamp(Date(timestampMs)),
                        batteryLevel = bat
                    ))
                } catch (e: Exception) {
                    Timber.e(e, "Location parse error")
                    trySend(null)
                }
            }
            override fun onCancelled(error: DatabaseError) {
                trySend(null)
            }
        }
        ref.addValueEventListener(listener)
        awaitClose { ref.removeEventListener(listener) }
    }

    // ── Live Stream Frames (realtime) ─────────────────────────────────────────

    fun observeScreenFrames(deviceId: String): Flow<String?> = callbackFlow {
        val ref = rtdb.child("stream_frames/$deviceId/screen")
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val frame = snapshot.child("frame").getValue(String::class.java)
                trySend(frame)
            }
            override fun onCancelled(error: DatabaseError) { trySend(null) }
        }
        ref.addValueEventListener(listener)
        awaitClose { ref.removeEventListener(listener) }
    }

    suspend fun findLatestStreamSession(deviceId: String, type: StreamType): StreamSession? {
        return db.collection(FirebasePaths.COLLECTION_STREAM_SESSIONS)
            .whereEqualTo("deviceId", deviceId)
            .whereEqualTo("type", type.name)
            .get()
            .await()
            .documents
            .mapNotNull { document ->
                document.toObject(StreamSession::class.java)?.copy(sessionId = document.id)
            }
            .filter { it.parentId == com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid }
            .maxByOrNull { it.lastUpdatedAt }
    }

    fun observeCameraFrames(deviceId: String): Flow<String?> = callbackFlow {
        val ref = rtdb.child("stream_frames/$deviceId/camera")
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val frame = snapshot.child("frame").getValue(String::class.java)
                trySend(frame)
            }
            override fun onCancelled(error: DatabaseError) { trySend(null) }
        }
        ref.addValueEventListener(listener)
        awaitClose { ref.removeEventListener(listener) }
    }

    // ── Alerts (realtime Firestore) ───────────────────────────────────────────

    fun observeAlerts(
        deviceId: String,
        limit: Long = 50
    ): Flow<List<AlertData>> = callbackFlow {
        val registration: ListenerRegistration = db
            .collection(FirebasePaths.COLLECTION_ALERTS)
            .whereEqualTo("deviceId", deviceId)
            .orderBy("timestamp", Query.Direction.DESCENDING)
            .limit(limit)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Timber.e(error, "Alerts listener error")
                    trySend(emptyList())
                    return@addSnapshotListener
                }
                val alerts = snapshot?.documents?.mapNotNull { doc ->
                    doc.toObject(AlertData::class.java)?.copy(id = doc.id)
                } ?: emptyList()
                trySend(alerts)
            }
        awaitClose { registration.remove() }
    }

    // ── Commands ──────────────────────────────────────────────────────────────

    /**
     * Sends a command to the child device via Firebase Realtime DB.
     * Returns the command key for tracking.
     */
    suspend fun sendCommand(
        parentId: String,
        deviceId: String,
        type: CommandType,
        payload: Map<String, Any> = emptyMap()
    ): String {
        require(parentId.isNotBlank()) { "Parent ID is required" }
        require(deviceId.isNotBlank()) { "Device ID is required" }

        val commandKey = UUID.randomUUID().toString()
        val nowMs = System.currentTimeMillis()
        val expiresAtMs = nowMs + 5 * 60_000L
        val streamPayload = if (type == CommandType.START_CAMERA_STREAM || type == CommandType.START_AUDIO_STREAM) {
            val sessionId = UUID.randomUUID().toString()
            val streamType = if (type == CommandType.START_AUDIO_STREAM) {
                StreamType.AUDIO
            } else if (payload["front"] as? Boolean ?: true) {
                StreamType.CAMERA_FRONT
            } else {
                StreamType.CAMERA_BACK
            }
            val sessionData = mapOf(
                "sessionId" to sessionId,
                "deviceId" to deviceId,
                "parentId" to parentId,
                "type" to streamType.name,
                "status" to StreamSessionStatus.PENDING.name,
                "isActive" to false,
                "createdAt" to Timestamp(Date(nowMs)),
                "expiresAt" to Timestamp(Date(expiresAtMs)),
                "lastUpdatedAt" to Timestamp(Date(nowMs)),
                "failureReason" to ""
            )
            db.collection(FirebasePaths.COLLECTION_STREAM_SESSIONS)
                .document(sessionId)
                .set(sessionData)
                .await()
            rtdb.child(FirebasePaths.webRtcPath(sessionId)).setValue(
                mapOf(
                    "sessionId" to sessionId,
                    "deviceId" to deviceId,
                    "parentId" to parentId,
                    "type" to streamType.name,
                    "status" to StreamSessionStatus.PENDING.name,
                    "expiresAt" to expiresAtMs
                )
            ).await()
            payload + mapOf(
                "sessionId" to sessionId,
                "expiresAt" to expiresAtMs,
                "streamType" to streamType.name
            )
        } else {
            payload
        }
        val commandData = mapOf(
            "id"                 to commandKey,
            "parentId"           to parentId,
            "childDeviceId"      to deviceId,
            "type"               to type.name,
            "payload"            to streamPayload,
            "status"             to CommandStatus.PENDING.name,
            "createdAt"          to nowMs,
            "expiresAt"          to expiresAtMs,
            "lastUpdatedAt"     to nowMs,
            "authorizedParentId" to parentId,
            "replayToken"        to commandKey,
            "result"             to null,
            "error"              to null
        )

        val currentUser = FirebaseAuth.getInstance().currentUser
            ?: error("Parent authentication is required")
        require(currentUser.uid == parentId) { "Only the authenticated parent may send commands" }
        val idToken = currentUser.getIdToken(false).await().token
            ?: error("Could not retrieve parent authentication token")
        WorkerApiClient.postJson(
            BuildConfig.CLOUDFLARE_WORKER_URL,
            "/v1/commands",
            idToken,
            mapOf("deviceId" to deviceId, "commandId" to commandKey, "command" to commandData)
        )

        Timber.i("Command sent: $type → $deviceId (commandId=$commandKey)")
        return commandKey
    }

    // ── Pairing Code Generation ───────────────────────────────────────────────

    suspend fun generatePairingCode(parentId: String): String {
        require(parentId.isNotBlank()) { "Parent ID is required" }
        val currentUserId = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid
        require(currentUserId != null && currentUserId == parentId) { "Only the authenticated parent may generate pairing codes" }

        val code = (100000..999999).random().toString()
        val now = Timestamp.now()
        val expires = Timestamp(com.google.firebase.Timestamp(
            now.seconds + 600, 0 // 10-minute expiry
        ).toDate())

        db.collection(FirebasePaths.COLLECTION_PAIRING_CODES).add(
            mapOf(
                "code"          to code,
                "parentId"      to parentId,
                "parentName"    to currentUserId,
                "isUsed"        to false,
                "status"        to "ACTIVE",
                "createdAt"     to now,
                "expiresAt"     to expires,
                "usedByDeviceId" to "",
                "usedAt"        to null
            )
        ).await()

        return code
    }

    // ── Child Devices ─────────────────────────────────────────────────────────

    suspend fun getChildDevices(parentId: String): List<ChildDevice> {
        return try {
            db.collection(FirebasePaths.COLLECTION_DEVICES)
                .whereEqualTo("parentId", parentId)
                .whereEqualTo("isActive", true)
                .get()
                .await()
                .documents
                .mapNotNull { it.toObject(ChildDevice::class.java) }
        } catch (e: Exception) {
            Timber.e(e, "Failed to fetch child devices")
            emptyList()
        }
    }

    suspend fun deleteChildDeviceData(deviceId: String) {
        require(deviceId.isNotBlank()) { "Device ID is required" }
        val currentUser = FirebaseAuth.getInstance().currentUser
        require(currentUser != null) { "Parent authentication is required" }
        val idToken = currentUser.getIdToken(false).await().token
            ?: error("Could not retrieve parent authentication token")
        WorkerApiClient.delete(
            BuildConfig.CLOUDFLARE_WORKER_URL,
            "/v1/children/${Uri.encode(deviceId)}",
            idToken
        )
    }

    suspend fun downloadPhoto(deviceId: String, filename: String): ByteArray {
        require(deviceId.isNotBlank()) { "Device ID is required" }
        require(filename.isNotBlank()) { "Photo filename is required" }
        val currentUser = FirebaseAuth.getInstance().currentUser
            ?: error("Parent authentication is required")
        val idToken = currentUser.getIdToken(false).await().token
            ?: error("Could not retrieve parent authentication token")
        return WorkerApiClient.getBytes(
            BuildConfig.CLOUDFLARE_WORKER_URL,
            "/v1/media/photos/${Uri.encode(deviceId)}/${Uri.encode(filename)}",
            idToken
        )
    }

    suspend fun listPhotos(deviceId: String): List<PhotoMetadata> {
        require(deviceId.isNotBlank()) { "Device ID is required" }
        val currentUser = FirebaseAuth.getInstance().currentUser
            ?: error("Parent authentication is required")
        val idToken = currentUser.getIdToken(false).await().token
            ?: error("Could not retrieve parent authentication token")
        val result = WorkerApiClient.getJson(
            BuildConfig.CLOUDFLARE_WORKER_URL,
            "/v1/media/photos/${Uri.encode(deviceId)}",
            idToken
        )
        val photos = result.optJSONArray("photos") ?: return emptyList()
        return (0 until photos.length()).mapNotNull { index ->
            photos.optJSONObject(index)?.let { photo ->
                PhotoMetadata(
                    deviceId = photo.optString("deviceId"),
                    filename = photo.optString("filename"),
                    objectKey = photo.optString("objectKey"),
                    url = photo.optString("url"),
                    timestamp = runCatching {
                        Timestamp(Date.from(Instant.parse(photo.optString("timestamp"))))
                    }.getOrDefault(Timestamp.now()),
                    etag = photo.optString("etag")
                )
            }
        }
    }

    fun observeChildDevices(parentId: String): Flow<List<ChildDevice>> = callbackFlow {
        val reg = db.collection(FirebasePaths.COLLECTION_DEVICES)
            .whereEqualTo("parentId", parentId)
            .whereEqualTo("isActive", true)
            .addSnapshotListener { snapshot, error ->
                if (error != null) { trySend(emptyList()); return@addSnapshotListener }
                val devices = snapshot?.documents?.mapNotNull {
                    it.toObject(ChildDevice::class.java)
                } ?: emptyList()
                trySend(devices)
            }
        awaitClose { reg.remove() }
    }

    // ── Settings ──────────────────────────────────────────────────────────────

    suspend fun updateSettings(deviceId: String, settings: Map<String, Any>) {
        db.collection(FirebasePaths.COLLECTION_SETTINGS)
            .document(deviceId)
            .update(settings)
            .await()
    }

    suspend fun getSettings(deviceId: String): ParentalSettings? {
        return try {
            db.collection(FirebasePaths.COLLECTION_SETTINGS)
                .document(deviceId)
                .get()
                .await()
                .toObject(ParentalSettings::class.java)
        } catch (e: Exception) {
            Timber.e(e, "Failed to fetch settings")
            null
        }
    }

    // ── Call / SMS Logs ───────────────────────────────────────────────────────

    suspend fun getCallLogs(deviceId: String, limit: Long = 100): List<CallLogEntry> {
        return try {
            db.collection(FirebasePaths.COLLECTION_CALL_LOGS)
                .whereEqualTo("deviceId", deviceId)
                .orderBy("timestamp", Query.Direction.DESCENDING)
                .limit(limit)
                .get().await()
                .documents.mapNotNull { it.toObject(CallLogEntry::class.java) }
        } catch (e: Exception) {
            Timber.e(e, "Failed to fetch call logs")
            emptyList()
        }
    }

    suspend fun getSmsLogs(deviceId: String, limit: Long = 200): List<SmsLogEntry> {
        return try {
            db.collection(FirebasePaths.COLLECTION_SMS_LOGS)
                .whereEqualTo("deviceId", deviceId)
                .orderBy("timestamp", Query.Direction.DESCENDING)
                .limit(limit)
                .get().await()
                .documents.mapNotNull { it.toObject(SmsLogEntry::class.java) }
        } catch (e: Exception) {
            Timber.e(e, "Failed to fetch SMS logs")
            emptyList()
        }
    }

    // ── App Usage ─────────────────────────────────────────────────────────────

    suspend fun getAppUsage(deviceId: String, date: String): List<AppUsageData> {
        return try {
            db.collection(FirebasePaths.COLLECTION_APP_USAGE)
                .whereEqualTo("deviceId", deviceId)
                .whereEqualTo("date", date)
                .orderBy("foregroundTimeMs", Query.Direction.DESCENDING)
                .get().await()
                .documents.mapNotNull { it.toObject(AppUsageData::class.java) }
        } catch (e: Exception) {
            Timber.e(e, "Failed to fetch app usage")
            emptyList()
        }
    }

    // ── Location History ──────────────────────────────────────────────────────

    suspend fun getLocationHistory(deviceId: String, date: String): List<LocationData> {
        return try {
            db.collection(FirebasePaths.COLLECTION_DEVICES)
                .document(deviceId)
                .collection("location_history")
                .orderBy("timestamp", Query.Direction.DESCENDING)
                .limit(500)
                .get().await()
                .documents.mapNotNull { it.toObject(LocationData::class.java) }
        } catch (e: Exception) {
            Timber.e(e, "Failed to fetch location history")
            emptyList()
        }
    }

    // ── Alert Actions ─────────────────────────────────────────────────────────

    suspend fun markAlertRead(alertId: String) {
        db.collection(FirebasePaths.COLLECTION_ALERTS)
            .document(alertId)
            .update("isRead", true)
            .await()
    }

    suspend fun resolveAlert(alertId: String) {
        db.collection(FirebasePaths.COLLECTION_ALERTS)
            .document(alertId)
            .update(mapOf("isRead" to true, "isResolved" to true))
            .await()
    }
}
