package com.childInHelp2026.app.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.util.Log
import java.util.*

class BluetoothBiometricManager(private val context: Context) {

    companion object {
        private const val TAG = "BiometricManager"
        private val HEART_RATE_SERVICE_UUID = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
        private val HEART_RATE_CHAR_UUID = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
        private val SPO2_SERVICE_UUID = UUID.fromString("00001822-0000-1000-8000-00805f9b34fb")
        private val SPO2_CHAR_UUID = UUID.fromString("00002a5f-0000-1000-8000-00805f9b34fb")
        private val CLIENT_CHARACTERISTIC_CONFIG = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter = bluetoothManager.adapter
    private var gatt: BluetoothGatt? = null
    private var targetDeviceAddress: String? = null

    var currentHeartRate: Int = 0
        private set
    var currentSpO2: Int = 0
        private set

    @SuppressLint("MissingPermission")
    fun startScanning(targetAddress: String? = null) {
        val prefs = BluetoothPreferences(context)
        if (!prefs.isEnabled) {
            Log.d(TAG, "Bluetooth tracking is disabled in preferences. Skipping scan.")
            return
        }

        targetDeviceAddress = targetAddress
        val scanner = adapter.bluetoothLeScanner ?: return
        Log.d(TAG, "Starting BLE scan... Target: $targetAddress")
        scanner.startScan(scanCallback)
    }

    @SuppressLint("MissingPermission")
    fun stopScanning() {
        adapter.bluetoothLeScanner?.stopScan(scanCallback)
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val deviceName = device.name ?: "Unknown Device"
            
            Log.v(TAG, "Scanning... found: $deviceName (${device.address})")

            if (targetDeviceAddress != null) {
                if (device.address == targetDeviceAddress) {
                    Log.i(TAG, "Target device found: $deviceName (${device.address}). Connecting...")
                    stopScanning()
                    connectToDevice(device)
                }
            } else {
                // Legacy auto-connect logic if no target is specified
                if (deviceName.contains("Heart", ignoreCase = true) ||
                    deviceName.contains("Pulse", ignoreCase = true) ||
                    deviceName.contains("TTGO_HEALTH", ignoreCase = true)) {
                    
                    Log.i(TAG, "Matching device found: $deviceName. Attempting connection...")
                    stopScanning()
                    connectToDevice(device)
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "Scan failed with error code: $errorCode")
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectToDevice(device: BluetoothDevice) {
        Log.d(TAG, "Connecting to ${device.address}")
        gatt = device.connectGatt(context, false, gattCallback)
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        gatt?.disconnect()
        gatt?.close()
        gatt = null
    }

    private val gattCallback by lazy {
        @Suppress("DEPRECATION")
        object : BluetoothGattCallback() {
            private val characteristicsToEnable = mutableListOf<BluetoothGattCharacteristic>()

            @SuppressLint("MissingPermission")
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    Log.i(TAG, "Connected to GATT server.")
                    gatt.discoverServices()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    Log.i(TAG, "Disconnected from GATT server.")
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    setupNotifications(gatt)
                }
            }

            @SuppressLint("MissingPermission")
            private fun setupNotifications(gatt: BluetoothGatt) {
                characteristicsToEnable.clear()
                
                // Collect all characteristics that need notifications
                gatt.getService(HEART_RATE_SERVICE_UUID)?.getCharacteristic(HEART_RATE_CHAR_UUID)?.let {
                    characteristicsToEnable.add(it)
                }
                gatt.getService(SPO2_SERVICE_UUID)?.getCharacteristic(SPO2_CHAR_UUID)?.let {
                    characteristicsToEnable.add(it)
                }
                
                enableNextNotification(gatt)
            }

            @SuppressLint("MissingPermission")
            private fun enableNextNotification(gatt: BluetoothGatt) {
                if (characteristicsToEnable.isEmpty()) return
                
                val characteristic = characteristicsToEnable.removeAt(0)
                Log.d(TAG, "Enabling notifications for ${characteristic.uuid}")
                
                gatt.setCharacteristicNotification(characteristic, true)
                val descriptor = characteristic.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG)
                if (descriptor != null) {
                    descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    val success = gatt.writeDescriptor(descriptor)
                    if (!success) {
                        Log.e(TAG, "Failed to write descriptor for ${characteristic.uuid}")
                        enableNextNotification(gatt)
                    }
                } else {
                    enableNextNotification(gatt)
                }
            }

            override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
                Log.d(TAG, "onDescriptorWrite status: $status for ${descriptor.uuid}")
                enableNextNotification(gatt)
            }

            @Deprecated("Deprecated in Java")
            override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
                when (characteristic.uuid) {
                    HEART_RATE_CHAR_UUID -> {
                        val format = if (characteristic.properties and 0x01 != 0) {
                            BluetoothGattCharacteristic.FORMAT_UINT16
                        } else {
                            BluetoothGattCharacteristic.FORMAT_UINT8
                        }
                        currentHeartRate = characteristic.getIntValue(format, 1) ?: 0
                        Log.d(TAG, "Heart Rate (HR Service): $currentHeartRate")
                    }
                    SPO2_CHAR_UUID -> {
                        val data = characteristic.value ?: return
                        if (data.size < 5) return

                        try {
                            // SFLOAT parsing (IEEE-11073)
                            val spo2Value = characteristic.getFloatValue(BluetoothGattCharacteristic.FORMAT_SFLOAT, 1)
                            val pulseRateValue = characteristic.getFloatValue(BluetoothGattCharacteristic.FORMAT_SFLOAT, 3)

                            if (spo2Value != null && spo2Value > 0) {
                                currentSpO2 = spo2Value.toInt()
                                Log.d(TAG, "SpO2 (PLX Service): $currentSpO2")
                            } else {
                                val rawSpO2 = data[1].toInt() and 0xFF
                                if (rawSpO2 in 1..100) {
                                    currentSpO2 = rawSpO2
                                    Log.d(TAG, "SpO2 (PLX Manual): $currentSpO2")
                                }
                            }
                            
                            if (pulseRateValue != null && pulseRateValue > 0) {
                                currentHeartRate = pulseRateValue.toInt()
                                Log.d(TAG, "Heart Rate (PLX Service): $currentHeartRate")
                            } else {
                                val rawPR = data[3].toInt() and 0xFF
                                if (rawPR > 0) {
                                    currentHeartRate = rawPR
                                    Log.d(TAG, "Heart Rate (PLX Manual): $currentHeartRate")
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error parsing PLX characteristic", e)
                        }
                    }
                }
            }
        }
    }
}
