package com.example.mototelemetryapp

import android.accounts.Account
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.mototelemetryapp.data.AppDatabase
import com.example.mototelemetryapp.data.TelemetryRecord
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

    private var telemetryService: TelemetryService? = null
    
    // Geçmiş veriler
    private val _history = MutableStateFlow<List<TelemetryRecord>>(emptyList())
    val history = _history.asStateFlow()

    fun backupToCloud(context: Context, account: Account) {
        viewModelScope.launch {
            _backupStatus.value = context.getString(R.string.backup_started)
            val manager = GoogleDriveManager(context)
            val success = manager.uploadDatabase(account)
            _backupStatus.value = if (success) context.getString(R.string.backup_success) else context.getString(R.string.backup_error)
        }
    }

    fun fetchHistory(context: Context) {
        viewModelScope.launch {
            val db = AppDatabase.getDatabase(context)
            db.telemetryDao().getAllRecords().collect {
                _history.value = it
            }
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as TelemetryService.LocalBinder
            telemetryService = binder.getService()
            _isServiceBound.value = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            telemetryService = null
            _isServiceBound.value = false
        }
    }

    fun bindService(context: Context) {
        val intent = Intent(context, TelemetryService::class.java)
        context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    fun unbindService(context: Context) {
        if (_isServiceBound.value) {
            context.unbindService(connection)
            _isServiceBound.value = false
        }
    }

    // Servis bağlıyken akışı expose et
    fun getTelemetryFlow() = telemetryService?.currentTelemetry
}
