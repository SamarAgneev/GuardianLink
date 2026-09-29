// child-app/src/main/java/com/guardianlink/child/data/OfflineQueue.kt
package com.guardianlink.child.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.google.firebase.Timestamp
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.guardianlink.common.firebase.createDocumentIfAbsent
import com.guardianlink.common.util.OfflineQueuePolicy
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.tasks.await
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.util.Date
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OfflineQueue @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        const val TYPE_FIRESTORE_CREATE = "FIRESTORE_CREATE"
        const val TYPE_RTDB_SET = "RTDB_SET"
        const val TYPE_RTDB_UPDATE = "RTDB_UPDATE"
        const val PRIORITY_COMMAND = 100
        const val PRIORITY_ALERT = 90
        const val PRIORITY_LOCATION = 80
        const val PRIORITY_LOG = 70
        private const val UNIQUE_WORK = "guardianlink_offline_drain"
    }

    private val database = OfflineDatabase.get(context)
    private val dao = database.offlineQueueDao()
    private val firestore = FirebaseFirestore.getInstance()
    private val rtdb = FirebaseDatabase.getInstance().reference
    private val initialized = AtomicBoolean(false)

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            Timber.i("Network available; scheduling offline drain")
            FirebaseFirestore.getInstance().enableNetwork()
            scheduleDrain()
        }

        override fun onLost(network: Network) {
            Timber.w("Network lost; offline items remain in Room")
        }
    }

    fun initialize() {
        if (!initialized.compareAndSet(false, true)) return
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        try {
            cm?.registerNetworkCallback(request, networkCallback)
        } catch (e: Exception) {
            Timber.e(e, "Unable to register offline network callback")
        }
        if (isNetworkAvailable()) scheduleDrain()
    }

    fun isNetworkAvailable(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    suspend fun enqueueFirestoreCreate(
        collection: String,
        documentId: String,
        data: Map<String, Any>,
        priority: Int
    ) {
        enqueue(
            TYPE_FIRESTORE_CREATE,
            "$collection/$documentId",
            mapOf("collection" to collection, "documentId" to documentId, "data" to data),
            priority
        )
    }

    suspend fun enqueueRealtimeSet(path: String, data: Any, priority: Int, itemId: String = path) {
        enqueue(TYPE_RTDB_SET, itemId, mapOf("path" to path, "data" to data), priority)
    }

    suspend fun enqueueRealtimeUpdate(
        path: String,
        data: Map<String, Any>,
        priority: Int,
        itemId: String = path
    ) {
        enqueue(TYPE_RTDB_UPDATE, itemId, mapOf("path" to path, "data" to data), priority)
    }

    suspend fun enqueue(type: String, itemId: String, payload: Map<String, Any?>, priority: Int) {
        require(type.isNotBlank() && itemId.isNotBlank())
        val now = System.currentTimeMillis()
        val entity = OfflineQueueEntity(
            type = type,
            itemId = itemId,
            payload = JSONObject(sanitizeMap(payload)).toString(),
            createdAt = now,
            priority = priority
        )
        dao.insertIfAbsent(entity)
        scheduleDrain()
    }

    suspend fun drain() {
        if (!isNetworkAvailable()) return
        dao.resetInFlight()
        while (isNetworkAvailable()) {
            val items = dao.pending(System.currentTimeMillis())
            if (items.isEmpty()) return
            for (item in items) {
                if (!isNetworkAvailable()) return
                val attemptAt = System.currentTimeMillis()
                dao.update(item.copy(status = "IN_FLIGHT", lastAttempt = attemptAt))
                try {
                    process(item)
                    dao.delete(item.type, item.itemId)
                } catch (error: Exception) {
                    val next = item.retryCount + 1
                    val dead = next >= OfflineQueuePolicy.MAX_RETRIES
                    dao.update(
                        item.copy(
                            retryCount = next,
                            lastAttempt = attemptAt,
                            nextAttemptAt = attemptAt + OfflineQueuePolicy.backoffMs(next),
                            status = if (dead) "DEAD" else "PENDING"
                        )
                    )
                    if (dead) Timber.e(error, "Offline item permanently failed: ${item.type}/${item.itemId}")
                    else Timber.w(error, "Offline item deferred: ${item.type}/${item.itemId} retry=$next")
                }
            }
        }
    }

    suspend fun pendingCount(): Int = dao.pendingCount()

    fun scheduleDrain() {
        val request = OneTimeWorkRequestBuilder<OfflineSyncWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            UNIQUE_WORK,
            ExistingWorkPolicy.KEEP,
            request
        )
    }

    fun destroy() {
        if (!initialized.compareAndSet(true, false)) return
        val cm = context.getSystemService(ConnectivityManager::class.java)
        try {
            cm?.unregisterNetworkCallback(networkCallback)
        } catch (_: Exception) {
        }
    }

    private suspend fun process(item: OfflineQueueEntity) {
        val root = JSONObject(item.payload)
        when (item.type) {
            TYPE_FIRESTORE_CREATE -> {
                val collection = root.getString("collection")
                val documentId = root.getString("documentId")
                val data = jsonObjectToMap(root.getJSONObject("data"))
                try {
                    firestore.collection(collection).document(documentId).createDocumentIfAbsent(data)
                } catch (e: FirebaseFirestoreException) {
                    if (e.code != FirebaseFirestoreException.Code.ALREADY_EXISTS) throw e
                }
            }
            TYPE_RTDB_SET -> {
                FirebaseDatabase.getInstance().reference
                    .child(root.getString("path"))
                    .setValue(jsonValueToAny(root.get("data")))
                    .await()
            }
            TYPE_RTDB_UPDATE -> {
                @Suppress("UNCHECKED_CAST")
                val updates = jsonObjectToMap(root.getJSONObject("data"))
                rtdb.child(root.getString("path")).updateChildren(updates).await()
            }
            else -> throw IllegalArgumentException("Unknown offline item type: ${item.type}")
        }
    }

    private fun jsonObjectToMap(value: JSONObject): Map<String, Any> {
        return value.keys().asSequence().associateWith { key ->
            jsonValueToAny(value.get(key))
        }
    }

    private fun jsonValueToAny(value: Any?): Any {
        return when (value) {
            JSONObject.NULL, null -> ""
            is JSONObject -> if (value.has("__timestampMs") && value.length() == 1) {
                Timestamp(Date(value.getLong("__timestampMs")))
            } else {
                jsonObjectToMap(value)
            }
            is JSONArray -> (0 until value.length()).map { jsonValueToAny(value.get(it)) }
            is Number, is Boolean, is String -> value
            else -> value.toString()
        }
    }

    private fun sanitizeMap(value: Map<String, Any?>): Map<String, Any?> =
        value.mapValues { sanitizeValue(it.value) }

    private fun sanitizeValue(value: Any?): Any? = when (value) {
        is Timestamp -> mapOf("__timestampMs" to value.toDate().time)
        is Map<*, *> -> value.entries.associate { it.key.toString() to sanitizeValue(it.value) }
        is Iterable<*> -> value.map { sanitizeValue(it) }
        else -> value
    }
}
