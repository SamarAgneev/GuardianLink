// parent-app/src/main/java/com/guardianlink/parent/ui/dashboard/DashboardViewModel.kt
package com.guardianlink.parent.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import com.guardianlink.common.model.*
import com.guardianlink.parent.data.firebase.ParentFirebaseManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val firebaseManager: ParentFirebaseManager
) : ViewModel() {

    private val parentId: String
        get() = FirebaseAuth.getInstance().currentUser?.uid ?: ""

    // ── Selected Device ───────────────────────────────────────────────────────

    private val _selectedDeviceId = MutableStateFlow<String>("")
    val selectedDeviceId: StateFlow<String> = _selectedDeviceId.asStateFlow()

    fun selectDevice(deviceId: String) {
        _selectedDeviceId.value = deviceId
    }

    fun deleteSelectedDeviceData(onComplete: (Boolean) -> Unit) {
        val deviceId = _selectedDeviceId.value
        if (deviceId.isBlank()) {
            onComplete(false)
            return
        }
        viewModelScope.launch {
            onComplete(
                try {
                    firebaseManager.deleteChildDeviceData(deviceId)
                    true
                } catch (e: Exception) {
                    Timber.e(e, "Failed to delete child data")
                    false
                }
            )
        }
    }

    // ── Child Devices ─────────────────────────────────────────────────────────

    val childDevices: StateFlow<List<ChildDevice>> = firebaseManager
        .observeChildDevices(parentId)
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    // ── Device Status (switches with selected device) ─────────────────────────

    @OptIn(ExperimentalCoroutinesApi::class)
    val deviceStatus: StateFlow<Map<String, Any?>> = _selectedDeviceId
        .filter { it.isNotBlank() }
        .flatMapLatest { deviceId ->
            firebaseManager.observeDeviceStatus(deviceId)
        }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyMap())

    // ── Live Location ─────────────────────────────────────────────────────────

    @OptIn(ExperimentalCoroutinesApi::class)
    val liveLocation: StateFlow<LocationData?> = _selectedDeviceId
        .filter { it.isNotBlank() }
        .flatMapLatest { deviceId ->
            firebaseManager.observeLocation(deviceId)
        }
        .stateIn(viewModelScope, SharingStarted.Lazily, null)

    // ── Alerts ────────────────────────────────────────────────────────────────

    @OptIn(ExperimentalCoroutinesApi::class)
    val alerts: StateFlow<List<AlertData>> = _selectedDeviceId
        .filter { it.isNotBlank() }
        .flatMapLatest { deviceId ->
            firebaseManager.observeAlerts(deviceId)
        }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val unreadAlertCount: StateFlow<Int> = alerts
        .map { list -> list.count { !it.isRead } }
        .stateIn(viewModelScope, SharingStarted.Lazily, 0)

    // ── App Usage ─────────────────────────────────────────────────────────────

    private val _appUsage = MutableStateFlow<List<AppUsageData>>(emptyList())
    val appUsage: StateFlow<List<AppUsageData>> = _appUsage.asStateFlow()

    fun loadAppUsage(date: String) {
        viewModelScope.launch {
            val deviceId = _selectedDeviceId.value
            if (deviceId.isBlank()) return@launch
            _appUsage.value = firebaseManager.getAppUsage(deviceId, date)
        }
    }

    // ── Call Logs ─────────────────────────────────────────────────────────────

    private val _callLogs = MutableStateFlow<List<CallLogEntry>>(emptyList())
    val callLogs: StateFlow<List<CallLogEntry>> = _callLogs.asStateFlow()

    fun loadCallLogs() {
        viewModelScope.launch {
            val deviceId = _selectedDeviceId.value
            if (deviceId.isBlank()) return@launch
            _callLogs.value = firebaseManager.getCallLogs(deviceId)
        }
    }

    // ── SMS Logs ──────────────────────────────────────────────────────────────

    private val _smsLogs = MutableStateFlow<List<SmsLogEntry>>(emptyList())
    val smsLogs: StateFlow<List<SmsLogEntry>> = _smsLogs.asStateFlow()

    fun loadSmsLogs() {
        viewModelScope.launch {
            val deviceId = _selectedDeviceId.value
            if (deviceId.isBlank()) return@launch
            _smsLogs.value = firebaseManager.getSmsLogs(deviceId)
        }
    }

    // ── Commands ──────────────────────────────────────────────────────────────

    private val _commandState = MutableStateFlow<CommandState>(CommandState.Idle)
    val commandState: StateFlow<CommandState> = _commandState.asStateFlow()

    fun sendCommand(type: CommandType, payload: Map<String, Any> = emptyMap()) {
        viewModelScope.launch {
            val deviceId = _selectedDeviceId.value
            if (deviceId.isBlank()) {
                _commandState.value = CommandState.Error("No device selected")
                return@launch
            }
            _commandState.value = CommandState.Sending
            try {
                val commandKey = firebaseManager.sendCommand(parentId, deviceId, type, payload)
                _commandState.value = CommandState.Sent(commandKey)
            } catch (e: Exception) {
                Timber.e(e, "Command failed: $type")
                _commandState.value = CommandState.Error(e.message ?: "Unknown error")
            }
        }
    }

    // ── Settings ──────────────────────────────────────────────────────────────

    private val _settings = MutableStateFlow<ParentalSettings?>(null)
    val settings: StateFlow<ParentalSettings?> = _settings.asStateFlow()

    fun loadSettings() {
        viewModelScope.launch {
            val deviceId = _selectedDeviceId.value
            if (deviceId.isBlank()) return@launch
            _settings.value = firebaseManager.getSettings(deviceId)
        }
    }

    fun updateSetting(key: String, value: Any) {
        viewModelScope.launch {
            val deviceId = _selectedDeviceId.value
            if (deviceId.isBlank()) return@launch
            try {
                firebaseManager.updateSettings(deviceId, mapOf(key to value))
                loadSettings()
            } catch (e: Exception) {
                Timber.e(e, "Failed to update setting: $key")
            }
        }
    }

    fun blockApp(packageName: String) =
        updateSetting(
            "blockedApps",
            com.google.firebase.firestore.FieldValue.arrayUnion(packageName)
        )

    fun unblockApp(packageName: String) =
        updateSetting(
            "blockedApps",
            com.google.firebase.firestore.FieldValue.arrayRemove(packageName)
        )

    fun blockWebsite(domain: String) =
        updateSetting(
            "blockedWebsites",
            com.google.firebase.firestore.FieldValue.arrayUnion(domain)
        )

    // ── Alert Actions ─────────────────────────────────────────────────────────

    fun markAlertRead(alertId: String) {
        viewModelScope.launch {
            try { firebaseManager.markAlertRead(alertId) }
            catch (e: Exception) { Timber.e(e, "Failed to mark alert read") }
        }
    }

    fun resolveAlert(alertId: String) {
        viewModelScope.launch {
            try { firebaseManager.resolveAlert(alertId) }
            catch (e: Exception) { Timber.e(e, "Failed to resolve alert") }
        }
    }

    // ── Pairing ───────────────────────────────────────────────────────────────

    private val _pairingCode = MutableStateFlow<String>("")
    val pairingCode: StateFlow<String> = _pairingCode.asStateFlow()

    fun generatePairingCode() {
        viewModelScope.launch {
            try {
                _pairingCode.value = firebaseManager.generatePairingCode(parentId)
            } catch (e: Exception) {
                Timber.e(e, "Failed to generate pairing code")
            }
        }
    }
}

sealed class CommandState {
    object Idle : CommandState()
    object Sending : CommandState()
    data class Sent(val commandKey: String) : CommandState()
    data class Error(val message: String) : CommandState()
}
