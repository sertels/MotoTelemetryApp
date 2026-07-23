package com.example.mototelemetryapp

import android.accounts.Account
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.mototelemetryapp.data.AppDatabase
import com.example.mototelemetryapp.data.Session
import com.example.mototelemetryapp.data.TelemetryRecord
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class LeanSource { PHONE, BIKE }

class DashboardViewModel : ViewModel() {

    private val _leanSource = MutableStateFlow(LeanSource.PHONE)
    val leanSource = _leanSource.asStateFlow()

    fun toggleLeanSource() {
        _leanSource.value = if (_leanSource.value == LeanSource.PHONE) LeanSource.BIKE else LeanSource.PHONE
    }

    private val _currentData = MutableStateFlow<TelemetryRecord?>(null)
    val currentData = _currentData.asStateFlow()

    private val _isServiceBound = MutableStateFlow(false)
    val isServiceBound = _isServiceBound.asStateFlow()
    
    private val _backupStatus = MutableStateFlow<String?>(null)
    val backupStatus = _backupStatus.asStateFlow()

    // True while the service is (or is presumed to be) actively recording a ride,
    // independent of whether the UI is currently bound to it.
    private val _isTrackingActive = MutableStateFlow(false)
    val isTrackingActive = _isTrackingActive.asStateFlow()

    fun setTrackingActive(active: Boolean) {
        _isTrackingActive.value = active
    }

    // Cleared in unbindService()/onCleared(), so this never outlives the ViewModel's binding.
    @SuppressLint("StaticFieldLeak")
    private var telemetryService: TelemetryService? = null
    
    // Geçmiş veriler
    fun getLatestSessionRecords(context: Context) =
        AppDatabase.getDatabase(context).telemetryDao().getLatestSessionRecords()

    private val _fuelLevelPct = MutableStateFlow<Int?>(null)
    val fuelLevelPct = _fuelLevelPct.asStateFlow()

    // Km remaining until the next service interval (SERVICE_INTERVAL_KM), based on
    // lifetime distance across all sessions. No dedicated "last serviced at" tracking
    // exists yet, so this assumes service happens every SERVICE_INTERVAL_KM exactly.
    private val _serviceRemainingKm = MutableStateFlow<Int?>(null)
    val serviceRemainingKm = _serviceRemainingKm.asStateFlow()

    fun fetchDashboardSummary(context: Context) {
        viewModelScope.launch {
            val dao = AppDatabase.getDatabase(context).telemetryDao()
            _fuelLevelPct.value = dao.getLastRecord()?.fuelLevel
            val totalKm = dao.getTotalDistanceKm() ?: 0f
            val remaining = SERVICE_INTERVAL_KM - (totalKm % SERVICE_INTERVAL_KM)
            _serviceRemainingKm.value = remaining.toInt()
        }
    }

    fun backupToCloud(context: Context, account: Account) {
        viewModelScope.launch {
            _backupStatus.value = context.getString(R.string.backup_started)
            val manager = GoogleDriveManager(context)
            val success = manager.uploadDatabase(account)
            _backupStatus.value = if (success) context.getString(R.string.backup_success) else context.getString(R.string.backup_error)
            delay(2600)
            _backupStatus.value = null
        }
    }

    private val _sessions = MutableStateFlow<List<Session>>(emptyList())
    val sessions = _sessions.asStateFlow()

    fun fetchHistory(context: Context) {
        viewModelScope.launch {
            val db = AppDatabase.getDatabase(context)
            db.telemetryDao().getAllSessions().collect {
                _sessions.value = it
            }
        }
    }

    fun renameSession(context: Context, session: Session, newName: String) {
        viewModelScope.launch {
            val db = AppDatabase.getDatabase(context)
            db.telemetryDao().updateSession(session.copy(name = newName))
        }
    }

    fun deleteSession(context: Context, session: Session) {
        viewModelScope.launch {
            val db = AppDatabase.getDatabase(context)
            db.telemetryDao().deleteSession(session)
        }
    }

    fun getRecordsForSession(context: Context, sessionId: Long) = 
        AppDatabase.getDatabase(context).telemetryDao().getRecordsForSession(sessionId)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as TelemetryService.LocalBinder
            telemetryService = binder.getService()
            _isServiceBound.value = true
            _isTrackingActive.value = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            telemetryService = null
            _isServiceBound.value = false
        }
    }

    // autoCreate = false only attaches to an already-running (started) service instead of
    // silently spinning up an inert one that never had onStartCommand (and its sensor loop) run.
    fun bindService(context: Context, autoCreate: Boolean = true) {
        val intent = Intent(context, TelemetryService::class.java)
        val flags = if (autoCreate) Context.BIND_AUTO_CREATE else 0
        context.bindService(intent, connection, flags)
    }

    fun unbindService(context: Context) {
        if (_isServiceBound.value) {
            context.unbindService(connection)
            _isServiceBound.value = false
        }
        telemetryService = null
    }

    // Servis bağlıyken akışı expose et
    fun getTelemetryFlow() = telemetryService?.currentTelemetry

    fun calibrateLeanAngle() {
        telemetryService?.calibrateLeanAngle()
    }

    override fun onCleared() {
        super.onCleared()
        telemetryService = null
    }

    companion object {
        const val SERVICE_INTERVAL_KM = 6000f
    }
}
