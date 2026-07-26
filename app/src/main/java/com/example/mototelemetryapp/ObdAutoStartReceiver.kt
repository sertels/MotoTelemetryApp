package com.example.mototelemetryapp

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat

// Registered in the manifest (not just at runtime) so it still fires when the app process
// isn't running - ACL_CONNECTED is on Android's implicit-broadcast exemption list, so this
// works even under the API 26+ background broadcast restrictions.
class ObdAutoStartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != BluetoothDevice.ACTION_ACL_CONNECTED) return

        val appPrefs = context.getSharedPreferences(APP_PREFS_NAME, Context.MODE_PRIVATE)
        if (!appPrefs.getBoolean(KEY_AUTO_START_ON_OBD_CONNECT, false)) return

        val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        } ?: return

        val preferredAddress = BluetoothOBDManager.getPreferredDeviceAddress(context) ?: return
        if (device.address != preferredAddress) return

        ContextCompat.startForegroundService(context, Intent(context, TelemetryService::class.java))
    }
}
