package com.example.mototelemetryapp

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.mototelemetryapp.data.AppDatabase
import com.example.mototelemetryapp.data.RestoreMode
import com.example.mototelemetryapp.data.Session
import com.example.mototelemetryapp.data.TelemetryRecord
import com.example.mototelemetryapp.data.restoreFromBackupFile
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job

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

    fun backupToCloud(context: Context, accessToken: String) {
        viewModelScope.launch {
            _backupStatus.value = context.getString(R.string.backup_started)
            val manager = GoogleDriveManager(context)
            val success = manager.uploadDatabase(accessToken)
            _backupStatus.value = if (success) context.getString(R.string.backup_success) else context.getString(R.string.backup_error)
            delay(2600)
            _backupStatus.value = null
        }
    }

    // Null means "not loaded" - either still loading, or the fetch/sign-in failed - which is
    // distinct from a successful fetch that just found zero backups (empty list).
    private val _driveBackups = MutableStateFlow<List<DriveBackupEntry>?>(null)
    val driveBackups = _driveBackups.asStateFlow()

    private val _driveBackupsLoading = MutableStateFlow(false)
    val driveBackupsLoading = _driveBackupsLoading.asStateFlow()

    private val _restoreStatus = MutableStateFlow<String?>(null)
    val restoreStatus = _restoreStatus.asStateFlow()

    // Called the moment the restore dialog opens, before the (async) sign-in even starts, so
    // the dialog shows a loading state immediately rather than briefly flashing "no backups".
    fun setDriveBackupsLoading() {
        _driveBackupsLoading.value = true
        _driveBackups.value = null
    }

    fun fetchDriveBackups(context: Context, accessToken: String) {
        viewModelScope.launch {
            _driveBackupsLoading.value = true
            _driveBackups.value = GoogleDriveManager(context).listBackups(accessToken)
            _driveBackupsLoading.value = false
        }
    }

    fun clearDriveBackups() {
        _driveBackups.value = null
        _driveBackupsLoading.value = false
    }

    fun restoreFromDrive(context: Context, accessToken: String, fileId: String, mode: RestoreMode) {
        viewModelScope.launch {
            _restoreStatus.value = context.getString(R.string.restore_started)
            val backupFile = GoogleDriveManager(context).downloadBackup(accessToken, fileId)
            val success = backupFile != null && restoreFromBackupFile(context, backupFile, mode)
            _restoreStatus.value = if (success) context.getString(R.string.restore_success) else context.getString(R.string.restore_error)
            if (success) {
                fetchHistory(context)
                fetchDashboardSummary(context)
            }
            delay(2600)
            _restoreStatus.value = null
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

    private val _obdConnected = MutableStateFlow(false)
    val obdConnected = _obdConnected.asStateFlow()
    private var obdConnectedCollectJob: Job? = null

    private val _obdSweepRunning = MutableStateFlow(false)
    val obdSweepRunning = _obdSweepRunning.asStateFlow()
    private var obdSweepRunningCollectJob: Job? = null

    private val _obdSweepResults = MutableStateFlow<List<ObdSweepEntry>>(emptyList())
    val obdSweepResults = _obdSweepResults.asStateFlow()
    private var obdSweepResultsCollectJob: Job? = null

    private val _obdMilOn = MutableStateFlow(false)
    val obdMilOn = _obdMilOn.asStateFlow()
    private var obdMilOnCollectJob: Job? = null

    private val _obdDtcCodes = MutableStateFlow<List<String>>(emptyList())
    val obdDtcCodes = _obdDtcCodes.asStateFlow()
    private var obdDtcCodesCollectJob: Job? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as TelemetryService.LocalBinder
            telemetryService = binder.getService()
            _isServiceBound.value = true
            _isTrackingActive.value = true
            obdConnectedCollectJob?.cancel()
            obdConnectedCollectJob = telemetryService?.obdConnected?.let { flow ->
                viewModelScope.launch { flow.collect { _obdConnected.value = it } }
            }
            obdSweepRunningCollectJob?.cancel()
            obdSweepRunningCollectJob = telemetryService?.obdSweepRunning?.let { flow ->
                viewModelScope.launch { flow.collect { _obdSweepRunning.value = it } }
            }
            obdSweepResultsCollectJob?.cancel()
            obdSweepResultsCollectJob = telemetryService?.obdSweepResults?.let { flow ->
                viewModelScope.launch { flow.collect { _obdSweepResults.value = it } }
            }
            obdMilOnCollectJob?.cancel()
            obdMilOnCollectJob = telemetryService?.obdMilOn?.let { flow ->
                viewModelScope.launch { flow.collect { _obdMilOn.value = it } }
            }
            obdDtcCodesCollectJob?.cancel()
            obdDtcCodesCollectJob = telemetryService?.obdDtcCodes?.let { flow ->
                viewModelScope.launch { flow.collect { _obdDtcCodes.value = it } }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            telemetryService = null
            _isServiceBound.value = false
            obdConnectedCollectJob?.cancel()
            _obdConnected.value = false
            obdSweepRunningCollectJob?.cancel()
            obdSweepResultsCollectJob?.cancel()
            _obdSweepRunning.value = false
            obdMilOnCollectJob?.cancel()
            _obdMilOn.value = false
            obdDtcCodesCollectJob?.cancel()
            _obdDtcCodes.value = emptyList()
        }
    }

    fun getPairedObdDevices(): List<Pair<String, String>> = telemetryService?.getPairedObdDevices() ?: emptyList()

    fun connectObd(address: String) {
        viewModelScope.launch {
            telemetryService?.connectObd(address)
        }
    }

    fun runObdSweep() {
        viewModelScope.launch {
            telemetryService?.runObdSweep()
        }
    }

    fun clearObdDtcs() {
        viewModelScope.launch {
            telemetryService?.clearObdDtcs()
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
        obdConnectedCollectJob?.cancel()
        _obdConnected.value = false
        obdSweepRunningCollectJob?.cancel()
        obdSweepResultsCollectJob?.cancel()
        _obdSweepRunning.value = false
        obdMilOnCollectJob?.cancel()
        _obdMilOn.value = false
        obdDtcCodesCollectJob?.cancel()
        _obdDtcCodes.value = emptyList()
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
