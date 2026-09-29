// child-app/src/main/java/com/guardianlink/child/receiver/SmsReceiver.kt
package com.guardianlink.child.receiver

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.Telephony
import androidx.core.content.ContextCompat
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.FirebaseFirestore
import com.guardianlink.child.data.OfflineQueue
import com.guardianlink.common.constants.FirebasePaths
import com.guardianlink.common.firebase.createDocumentIfAbsent
import com.guardianlink.common.model.AlertSeverity
import com.guardianlink.common.model.AlertType
import com.guardianlink.common.model.ParentalSettings
import com.guardianlink.common.security.SecurePreferences
import com.guardianlink.common.util.CommunicationUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import timber.log.Timber
import java.util.Date

class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
            Timber.w("SMS received but READ_SMS permission is unavailable")
            return
        }

        val pendingResult = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                processMessages(appContext, intent)
            } catch (e: Exception) {
                Timber.e(e, "SMS monitoring failed")
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun processMessages(context: Context, intent: Intent) {
        val prefs = SecurePreferences(context)
        val deviceId = prefs.getString(SecurePreferences.KEY_DEVICE_ID)
        if (deviceId.isBlank()) return

        val settings = FirebaseFirestore.getInstance()
            .collection(FirebasePaths.COLLECTION_SETTINGS)
            .document(deviceId)
            .get()
            .await()
            .toObject(ParentalSettings::class.java)
            ?: return
        if (!settings.smsMonitoringEnabled) return

        val contactsPermissionGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED
        val contacts = if (contactsPermissionGranted) readContacts(context) else emptyMap()

        Telephony.Sms.Intents.getMessagesFromIntent(intent).forEach { sms ->
            val address = sms.originatingAddress.orEmpty()
            val body = sms.messageBody.orEmpty()
            val timestampMs = sms.timestampMillis.takeIf { it > 0L } ?: System.currentTimeMillis()
            val normalizedNumber = CommunicationUtils.normalizePhoneNumber(address)
            val matchedContact = if (contactsPermissionGranted && normalizedNumber.isNotBlank()) {
                contacts.entries.firstOrNull { (number, _) ->
                    CommunicationUtils.phoneNumbersMatch(number, normalizedNumber)
                }
            } else {
                null
            }
            val isUnknownContact = contactsPermissionGranted &&
                normalizedNumber.isNotBlank() &&
                contacts.isNotEmpty() &&
                matchedContact == null
            val flaggedKeywords = CommunicationUtils.findKeywordMatches(body, settings.blockedKeywords)
            val recordId = CommunicationUtils.stableRecordId(
                "sms",
                deviceId,
                normalizedNumber,
                timestampMs.toString(),
                body
            )

            val log = mapOf(
                "id" to recordId,
                "deviceId" to deviceId,
                "number" to normalizedNumber,
                "contactName" to (matchedContact?.value ?: ""),
                "body" to body,
                "direction" to "INBOX",
                "timestamp" to Timestamp(Date(timestampMs)),
                "isUnknownContact" to isUnknownContact,
                "containsKeyword" to flaggedKeywords.isNotEmpty(),
                "flaggedKeywords" to flaggedKeywords
            )
            createIfAbsent(
                context,
                FirebaseFirestore.getInstance()
                    .collection(FirebasePaths.COLLECTION_SMS_LOGS)
                    .document(recordId),
                log
            )

            if (flaggedKeywords.isNotEmpty()) {
                createAlertIfAbsent(
                    context,
                    alertId = CommunicationUtils.stableRecordId("sms_keyword", recordId),
                    deviceId = deviceId,
                    type = AlertType.SMS_KEYWORD,
                    title = "SMS keyword alert",
                    message = "An incoming SMS matched configured monitoring keywords.",
                    metadata = mapOf("recordId" to recordId, "keywords" to flaggedKeywords),
                    severity = AlertSeverity.HIGH
                )
            }
            if (isUnknownContact) {
                createAlertIfAbsent(
                    context,
                    alertId = CommunicationUtils.stableRecordId("sms_unknown", recordId),
                    deviceId = deviceId,
                    type = AlertType.UNKNOWN_CONTACT,
                    title = "SMS from unknown contact",
                    message = "An incoming SMS was received from a number not found in contacts.",
                    metadata = mapOf("recordId" to recordId),
                    severity = AlertSeverity.MEDIUM
                )
            }
        }
    }

    private fun readContacts(context: Context): Map<String, String> {
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
        return contacts
    }

    private suspend fun createIfAbsent(
        context: Context,
        reference: com.google.firebase.firestore.DocumentReference,
        data: Map<String, Any>
    ) {
        try {
            reference.createDocumentIfAbsent(data)
        } catch (e: FirebaseFirestoreException) {
            if (e.code != FirebaseFirestoreException.Code.ALREADY_EXISTS) {
                OfflineQueue(context).enqueueFirestoreCreate(
                    reference.parent.path,
                    reference.id,
                    data,
                    OfflineQueue.PRIORITY_LOG
                )
            }
            Timber.d("SMS record already exists or could not be created: ${e.javaClass.simpleName}")
        }
    }

    private suspend fun createAlertIfAbsent(
        context: Context,
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
            FirebaseFirestore.getInstance()
                .collection(FirebasePaths.COLLECTION_ALERTS)
                .document(alertId)
                .createDocumentIfAbsent(alert)
        } catch (e: FirebaseFirestoreException) {
            if (e.code != FirebaseFirestoreException.Code.ALREADY_EXISTS) {
                OfflineQueue(context).enqueueFirestoreCreate(
                    FirebasePaths.COLLECTION_ALERTS,
                    alertId,
                    alert,
                    OfflineQueue.PRIORITY_ALERT
                )
            }
            Timber.d("SMS alert already exists or could not be created: ${e.javaClass.simpleName}")
        }
    }
}
