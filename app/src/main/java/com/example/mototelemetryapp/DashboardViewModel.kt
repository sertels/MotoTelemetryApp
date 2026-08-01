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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import java.util.Calendar
import kotlin.math.roundToInt

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

    // Estimated range = tank capacity x current fuel level x fuel economy. Economy is derived
    // from real ride history (distance/fuel per completed session) when there's enough of it;
    // otherwise falls back to the manufacturer-quoted figure so the home screen doesn't have to
    // show nothing on a fresh install.
    private val _fuelRangeKm = MutableStateFlow<Int?>(null)
    val fuelRangeKm = _fuelRangeKm.asStateFlow()

    private val _todayDistanceKm = MutableStateFlow<Float?>(null)
    val todayDistanceKm = _todayDistanceKm.asStateFlow()

    private val _todayDurationLabel = MutableStateFlow<String?>(null)
    val todayDurationLabel = _todayDurationLabel.asStateFlow()

    private val _todayAvgSpeedKmh = MutableStateFlow<Int?>(null)
    val todayAvgSpeedKmh = _todayAvgSpeedKmh.asStateFlow()

    fun fetchDashboardSummary(context: Context) {
        viewModelScope.launch {
            val dao = AppDatabase.getDatabase(context).telemetryDao()
            val fuelPct = dao.getLastRecord()?.fuelLevel
            _fuelLevelPct.value = fuelPct

            val totalKm = dao.getTotalDistanceKm() ?: 0f
            val remaining = SERVICE_INTERVAL_KM - (totalKm % SERVICE_INTERVAL_KM)
            _serviceRemainingKm.value = remaining.toInt()

            val allSessions = dao.getAllSessions().first()

            val economyKmPerLiter = estimateFuelEconomyKmPerLiter(allSessions)
            _fuelRangeKm.value = fuelPct?.let { (TANK_CAPACITY_LITERS * (it / 100f) * economyKmPerLiter).roundToInt() }

            val todayStart = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis
            val todaySessions = allSessions.filter { it.startTime >= todayStart && it.endTime != null }
            val todayDistance = todaySessions.sumOf { it.totalDistanceGpsKm.toDouble() }.toFloat()
            val todayDurationMs = todaySessions.sumOf { it.endTime!! - it.startTime }
            _todayDistanceKm.value = todayDistance
            _todayDurationLabel.value = formatDurationLabel(todayDurationMs)
            _todayAvgSpeedKmh.value = if (todayDurationMs > 0) {
                (todayDistance / (todayDurationMs / 3_600_000f)).roundToInt()
            } else 0
        }
    }

    private fun estimateFuelEconomyKmPerLiter(sessions: List<Session>): Float {
        val usable = sessions.filter { it.totalFuelLiters > 0.5f && it.totalDistanceBikeKm > 1f }
        return if (usable.isNotEmpty()) {
            usable.map { it.totalDistanceBikeKm / it.totalFuelLiters }.average().toFloat()
        } else {
            MANUFACTURER_AVG_KM_PER_LITER
        }
    }

    private fun formatDurationLabel(durationMs: Long): String {
        val totalMinutes = (durationMs / 60_000L).toInt()
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
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

    private val _obdSweepProgress = MutableStateFlow(0 to 0)
    val obdSweepProgress = _obdSweepProgress.asStateFlow()
    private var obdSweepProgressCollectJob: Job? = null

    // The coroutine actually running the sweep, so a user-initiated cancel can stop it mid-run
    // instead of just dismissing the dialog while it keeps probing the bike in the background.
    private var obdSweepJob: Job? = null

    // One-shot error message for a failed connect attempt (e.g. tapped a device and nothing
    // visibly happened) - mirrors the backupStatus pattern below.
    private val _obdConnectError = MutableStateFlow<String?>(null)
    val obdConnectError = _obdConnectError.asStateFlow()

    private val _obdMilOn = MutableStateFlow(false)
    val obdMilOn = _obdMilOn.asStateFlow()
    private var obdMilOnCollectJob: Job? = null

    private val _obdDtcCodes = MutableStateFlow<List<String>>(emptyList())
    val obdDtcCodes = _obdDtcCodes.asStateFlow()
    private var obdDtcCodesCollectJob: Job? = null

    private val _obdRawData = MutableStateFlow<Map<String, Int>>(emptyMap())
    val obdRawData = _obdRawData.asStateFlow()
    private var obdRawDataCollectJob: Job? = null

    private val _obdPidStatus = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val obdPidStatus = _obdPidStatus.asStateFlow()
    private var obdPidStatusCollectJob: Job? = null

    // Fixed for the service's lifetime rather than a flow - the source is chosen once in
    // onCreate() and never swapped, so this only needs re-reading on (re)bind.
    private val _obdSimulated = MutableStateFlow(false)
    val obdSimulated = _obdSimulated.asStateFlow()

    // Max lean this ride, live - not just the value saved to the session at the end.
    private val _maxLeanLeft = MutableStateFlow(0f)
    val maxLeanLeft = _maxLeanLeft.asStateFlow()
    private var maxLeanLeftCollectJob: Job? = null

    private val _maxLeanRight = MutableStateFlow(0f)
    val maxLeanRight = _maxLeanRight.asStateFlow()
    private var maxLeanRightCollectJob: Job? = null

    // Which source (phone or bike) actually produced the current Max L/Max R, so the UI can
    // color-code it the same way live OBD readouts already are.
    private val _maxLeanLeftSource = MutableStateFlow(LeanSource.BIKE)
    val maxLeanLeftSource = _maxLeanLeftSource.asStateFlow()
    private var maxLeanLeftSourceCollectJob: Job? = null

    private val _maxLeanRightSource = MutableStateFlow(LeanSource.BIKE)
    val maxLeanRightSource = _maxLeanRightSource.asStateFlow()
    private var maxLeanRightSourceCollectJob: Job? = null

    // Max G this ride, live - same reasoning as maxLeanLeft/Right above.
    private val _maxGForceLat = MutableStateFlow(0f)
    val maxGForceLat = _maxGForceLat.asStateFlow()
    private var maxGForceLatCollectJob: Job? = null

    private val _maxGForceLon = MutableStateFlow(0f)
    val maxGForceLon = _maxGForceLon.asStateFlow()
    private var maxGForceLonCollectJob: Job? = null

    private var trackingActiveCollectJob: Job? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as TelemetryService.LocalBinder
            telemetryService = binder.getService()
            _isServiceBound.value = true
            _obdSimulated.value = telemetryService?.obdSimulated == true
            // Binding (e.g. just opening Panel or Bike Info) isn't the same as a ride actually
            // being recorded - observe the service's real tracking state instead of assuming
            // true, otherwise Backup/Restore's "don't run mid-ride" guard gets stuck disabled.
            // Collected (not read once) since onStartCommand's flag flip and this bind callback
            // are dispatched independently with no ordering guarantee between them.
            trackingActiveCollectJob?.cancel()
            trackingActiveCollectJob = telemetryService?.isTrackingActive?.let { flow ->
                viewModelScope.launch { flow.collect { _isTrackingActive.value = it } }
            }
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
            obdSweepProgressCollectJob?.cancel()
            obdSweepProgressCollectJob = telemetryService?.obdSweepProgress?.let { flow ->
                viewModelScope.launch { flow.collect { _obdSweepProgress.value = it } }
            }
            obdMilOnCollectJob?.cancel()
            obdMilOnCollectJob = telemetryService?.obdMilOn?.let { flow ->
                viewModelScope.launch { flow.collect { _obdMilOn.value = it } }
            }
            obdDtcCodesCollectJob?.cancel()
            obdDtcCodesCollectJob = telemetryService?.obdDtcCodes?.let { flow ->
                viewModelScope.launch { flow.collect { _obdDtcCodes.value = it } }
            }
            obdRawDataCollectJob?.cancel()
            obdRawDataCollectJob = telemetryService?.obdRawData?.let { flow ->
                viewModelScope.launch { flow.collect { _obdRawData.value = it } }
            }
            obdPidStatusCollectJob?.cancel()
            obdPidStatusCollectJob = telemetryService?.obdPidStatus?.let { flow ->
                viewModelScope.launch { flow.collect { _obdPidStatus.value = it } }
            }
            maxLeanLeftCollectJob?.cancel()
            maxLeanLeftCollectJob = telemetryService?.maxLeanLeft?.let { flow ->
                viewModelScope.launch { flow.collect { _maxLeanLeft.value = it } }
            }
            maxLeanRightCollectJob?.cancel()
            maxLeanRightCollectJob = telemetryService?.maxLeanRight?.let { flow ->
                viewModelScope.launch { flow.collect { _maxLeanRight.value = it } }
            }
            maxLeanLeftSourceCollectJob?.cancel()
            maxLeanLeftSourceCollectJob = telemetryService?.maxLeanLeftSource?.let { flow ->
                viewModelScope.launch { flow.collect { _maxLeanLeftSource.value = it } }
            }
            maxLeanRightSourceCollectJob?.cancel()
            maxLeanRightSourceCollectJob = telemetryService?.maxLeanRightSource?.let { flow ->
                viewModelScope.launch { flow.collect { _maxLeanRightSource.value = it } }
            }
            maxGForceLatCollectJob?.cancel()
            maxGForceLatCollectJob = telemetryService?.maxGForceLat?.let { flow ->
                viewModelScope.launch { flow.collect { _maxGForceLat.value = it } }
            }
            maxGForceLonCollectJob?.cancel()
            maxGForceLonCollectJob = telemetryService?.maxGForceLon?.let { flow ->
                viewModelScope.launch { flow.collect { _maxGForceLon.value = it } }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            telemetryService = null
            _isServiceBound.value = false
            _obdSimulated.value = false
            // _isTrackingActive is intentionally left as-is - it reflects whether a ride is
            // actually being recorded, independent of whether the UI is currently bound.
            trackingActiveCollectJob?.cancel()
            obdConnectedCollectJob?.cancel()
            _obdConnected.value = false
            obdSweepRunningCollectJob?.cancel()
            obdSweepResultsCollectJob?.cancel()
            obdSweepProgressCollectJob?.cancel()
            _obdSweepRunning.value = false
            _obdSweepProgress.value = 0 to 0
            obdMilOnCollectJob?.cancel()
            _obdMilOn.value = false
            obdDtcCodesCollectJob?.cancel()
            _obdDtcCodes.value = emptyList()
            obdRawDataCollectJob?.cancel()
            _obdRawData.value = emptyMap()
            obdPidStatusCollectJob?.cancel()
            _obdPidStatus.value = emptyMap()
            maxLeanLeftCollectJob?.cancel()
            maxLeanRightCollectJob?.cancel()
            maxLeanLeftSourceCollectJob?.cancel()
            maxLeanRightSourceCollectJob?.cancel()
            maxGForceLatCollectJob?.cancel()
            maxGForceLonCollectJob?.cancel()
            // Left as-is (not reset to 0) on disconnect, same reasoning as _isTrackingActive: a
            // ride keeps recording in the background even while the UI is briefly unbound, so the
            // last-known max lean/G shouldn't flash back to zero.
        }
    }

    fun getPairedObdDevices(): List<Pair<String, String>> = telemetryService?.getPairedObdDevices() ?: emptyList()

    fun connectObd(context: Context, address: String) {
        viewModelScope.launch {
            val connected = telemetryService?.connectObd(address) ?: false
            if (!connected) {
                _obdConnectError.value = context.getString(R.string.obd_connect_failed)
                delay(3000)
                _obdConnectError.value = null
            }
        }
    }

    fun runObdSweep() {
        obdSweepJob = viewModelScope.launch {
            telemetryService?.runObdSweep()
        }
    }

    fun cancelObdSweep() {
        obdSweepJob?.cancel()
        obdSweepJob = null
    }

    fun disconnectObd() {
        telemetryService?.disconnectObd()
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
        obdRawDataCollectJob?.cancel()
        _obdRawData.value = emptyMap()
        obdPidStatusCollectJob?.cancel()
        _obdPidStatus.value = emptyMap()
        maxLeanLeftCollectJob?.cancel()
        maxLeanRightCollectJob?.cancel()
        maxLeanLeftSourceCollectJob?.cancel()
        maxLeanRightSourceCollectJob?.cancel()
        maxGForceLatCollectJob?.cancel()
        maxGForceLonCollectJob?.cancel()
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

        // Manufacturer spec: 17L tank, ~19 km/L rated economy (~320-350km rated range) - used
        // as the fallback economy figure until enough real ride history exists to derive one.
        private const val TANK_CAPACITY_LITERS = 17f
        private const val MANUFACTURER_AVG_KM_PER_LITER = 19f
    }
}
