package com.childInHelp2026.app.bluetooth

import android.content.Context
import android.content.SharedPreferences

class BluetoothPreferences(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("bluetooth_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_ENABLED = "bt_enabled"
        private const val KEY_DEVICE_ADDRESS = "bt_device_address"
        private const val KEY_DEVICE_NAME = "bt_device_name"
    }

    var isEnabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()

    var deviceAddress: String?
        get() = prefs.getString(KEY_DEVICE_ADDRESS, null)
        set(value) = prefs.edit().putString(KEY_DEVICE_ADDRESS, value).apply()

    var deviceName: String?
        get() = prefs.getString(KEY_DEVICE_NAME, null)
        set(value) = prefs.edit().putString(KEY_DEVICE_NAME, value).apply()
}
