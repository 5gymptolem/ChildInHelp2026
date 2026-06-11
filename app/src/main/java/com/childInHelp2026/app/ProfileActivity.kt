package com.childInHelp2026.app

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.lifecycleScope
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AlertDialog
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.activity.result.contract.ActivityResultContracts
import com.childInHelp2026.app.bluetooth.BluetoothPreferences
import com.childInHelp2026.app.bluetooth.HealthConnectManager
import com.childInHelp2026.app.data.AppSettings
import com.childInHelp2026.app.data.UserProfile
import com.childInHelp2026.app.databinding.ActivityProfileBinding
import com.childInHelp2026.app.repository.AuthRepository
import kotlinx.coroutines.launch

class ProfileActivity : AppCompatActivity() {

    private lateinit var binding: ActivityProfileBinding
    private lateinit var authRepository: AuthRepository
    private val healthConnectManager by lazy { HealthConnectManager(this) }
    private val btPrefs by lazy { BluetoothPreferences(this) }
    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        val manager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        manager.adapter
    }

    private val handler = Handler(Looper.getMainLooper())

    private val bluetoothPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            if (permissions.values.all { it }) {
                startBluetoothScan()
            } else {
                Toast.makeText(this, "Απαιτούνται άδειες για την αναζήτηση Bluetooth", Toast.LENGTH_SHORT).show()
            }
        }

    private val scanResults = mutableMapOf<String, android.bluetooth.BluetoothDevice>()
    private var isScanning = false

    private val requestPermissionLauncher =
        registerForActivityResult(PermissionController.createRequestPermissionResultContract()) { granted ->
            if (granted.containsAll(HealthConnectManager.PERMISSIONS)) {
                Toast.makeText(this, getString(R.string.hc_access_granted), Toast.LENGTH_SHORT).show()
                updateHealthConnectButton()
            } else {
                Toast.makeText(this, getString(R.string.hc_access_denied), Toast.LENGTH_SHORT).show()
            }
        }

    private var currentSettings: AppSettings = AppSettings()
    private var currentProfile: UserProfile? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityProfileBinding.inflate(layoutInflater)
        setContentView(binding.root)

        authRepository = AuthRepository()

        val currentUser = authRepository.getCurrentUser()
        if (currentUser == null) {
            Toast.makeText(this, "Δεν υπάρχει συνδεδεμένος χρήστης.", Toast.LENGTH_LONG).show()
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        binding.btnSaveProfile.setOnClickListener {
            attemptSave()
        }

        binding.btnLogout.setOnClickListener {
            authRepository.logout()
            Toast.makeText(this, "Έγινε αποσύνδεση.", Toast.LENGTH_SHORT).show()
            startActivity(Intent(this, LoginActivity::class.java))
            finishAffinity()
        }

        binding.btnBack.setOnClickListener {
            finish()
        }

        binding.btnHealthConnect.setOnClickListener {
            checkAndRequestHealthConnectPermissions()
        }

        binding.switchBluetoothEnabled.isChecked = btPrefs.isEnabled
        binding.switchBluetoothEnabled.setOnCheckedChangeListener { _, isChecked ->
            btPrefs.isEnabled = isChecked
            updateBluetoothDeviceInfo()
        }

        binding.btnScanBluetooth.setOnClickListener {
            startBluetoothScan()
        }

        loadSettingsAndProfile()
        updateHealthConnectButton()
        updateBluetoothDeviceInfo()
    }

    private fun updateBluetoothDeviceInfo() {
        val name = btPrefs.deviceName
        val address = btPrefs.deviceAddress
        val isEnabled = btPrefs.isEnabled

        if (name != null && address != null) {
            binding.textSelectedDevice.text = getString(R.string.bt_selected_device, "$name ($address)")
        } else {
            binding.textSelectedDevice.text = getString(R.string.bt_no_device_selected)
        }

        binding.btnScanBluetooth.isEnabled = isEnabled
        if (!isEnabled) {
            binding.textSelectedDevice.alpha = 0.5f
        } else {
            binding.textSelectedDevice.alpha = 1.0f
        }
    }

    @SuppressLint("MissingPermission")
    private fun startBluetoothScan() {
        if (!hasBluetoothPermissions()) {
            requestBluetoothPermissions()
            return
        }

        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) {
            Toast.makeText(this, "Παρακαλώ ενεργοποιήστε το Bluetooth", Toast.LENGTH_SHORT).show()
            return
        }

        if (isScanning) return

        scanResults.clear()
        isScanning = true
        binding.btnScanBluetooth.text = getString(R.string.bt_scanning)
        binding.btnScanBluetooth.isEnabled = false

        adapter.bluetoothLeScanner?.startScan(btScanCallback)

        handler.postDelayed({
            stopBluetoothScan()
            showDeviceSelectionDialog()
        }, 10000) // Scan for 10 seconds
    }

    @SuppressLint("MissingPermission")
    private fun stopBluetoothScan() {
        if (!isScanning) return
        isScanning = false
        bluetoothAdapter?.bluetoothLeScanner?.stopScan(btScanCallback)
        binding.btnScanBluetooth.text = getString(R.string.bt_scan_devices)
        binding.btnScanBluetooth.isEnabled = true
    }

    private val btScanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val name = device.name
            if (!name.isNullOrBlank()) {
                scanResults[device.address] = device
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun showDeviceSelectionDialog() {
        if (scanResults.isEmpty()) {
            Toast.makeText(this, "Δεν βρέθηκαν συσκευές", Toast.LENGTH_SHORT).show()
            return
        }

        val devices = scanResults.values.toList()
        val deviceNames = devices.map { "${it.name}\n${it.address}" }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Επιλογή συσκευής")
            .setItems(deviceNames) { _, which ->
                val selectedDevice = devices[which]
                btPrefs.deviceAddress = selectedDevice.address
                btPrefs.deviceName = selectedDevice.name
                updateBluetoothDeviceInfo()
                Toast.makeText(this, "Η συσκευή επιλέχθηκε", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Άκυρο", null)
            .show()
    }

    private fun checkAndRequestHealthConnectPermissions() {
        if (!healthConnectManager.isHealthConnectAvailable()) {
            Toast.makeText(this, getString(R.string.hc_not_available), Toast.LENGTH_LONG).show()
            return
        }

        lifecycleScope.launch {
            if (healthConnectManager.hasAllPermissions()) {
                showHealthConnectDisconnectDialog()
            } else {
                requestPermissionLauncher.launch(HealthConnectManager.PERMISSIONS)
            }
        }
    }

    private fun showHealthConnectDisconnectDialog() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.hc_disconnect))
            .setMessage(getString(R.string.hc_disconnect_confirm))
            .setPositiveButton("Αποσύνδεση") { _, _ ->
                lifecycleScope.launch {
                    healthConnectManager.revokePermissions()
                    updateHealthConnectButton()
                    Toast.makeText(this@ProfileActivity, getString(R.string.hc_disconnected), Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Άκυρο", null)
            .show()
    }

    private fun updateHealthConnectButton() {
        lifecycleScope.launch {
            if (healthConnectManager.isHealthConnectAvailable()) {
                if (healthConnectManager.hasAllPermissions()) {
                    binding.btnHealthConnect.text = getString(R.string.hc_connected)
                    binding.btnHealthConnect.isEnabled = true // Keep enabled for disconnect
                } else {
                    binding.btnHealthConnect.text = getString(R.string.hc_connect)
                    binding.btnHealthConnect.isEnabled = true
                }
            } else {
                binding.btnHealthConnect.text = getString(R.string.hc_unavailable)
                binding.btnHealthConnect.isEnabled = false
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun loadSettingsAndProfile() {
        setLoading(true)
        binding.textStatus.text = "Φόρτωση στοιχείων..."

        authRepository.fetchGeneralSettings(
            onSuccess = { settings ->
                currentSettings = settings
                authRepository.fetchCurrentUserProfile(
                    onSuccess = { profile ->
                        setLoading(false)
                        currentProfile = profile
                        bindProfile(profile)
                        binding.textStatus.text = "Το προφίλ φορτώθηκε."
                    },
                    onError = { errorMessage ->
                        setLoading(false)
                        binding.textStatus.text = "Σφάλμα: $errorMessage"
                        Toast.makeText(this, errorMessage, Toast.LENGTH_LONG).show()
                    }
                )
            },
            onError = { errorMessage ->
                setLoading(false)
                binding.textStatus.text = "Σφάλμα: $errorMessage"
                Toast.makeText(this, errorMessage, Toast.LENGTH_LONG).show()
            }
        )
    }

    @SuppressLint("SetTextI18n")
    private fun bindProfile(profile: UserProfile) {
        binding.editFullName.setText(profile.fullName)
        binding.editEmail.setText(profile.email)
        binding.editPhone.setText(profile.phone)
        binding.editArea.setText(profile.area)
        binding.checkboxCertified.isChecked = profile.certifiedFirstAid
        binding.editRadius.setText(profile.preferredRadiusKm.toString())
        binding.textSettingsInfo.text =
            "Default ακτίνα: ${currentSettings.defaultRadiusKm} km | Μέγιστη: ${currentSettings.maxRadiusKm} km"
    }

    @SuppressLint("SetTextI18n")
    private fun attemptSave() {
        clearErrors()

        val currentUser = authRepository.getCurrentUser()
        if (currentUser == null) {
            Toast.makeText(this, "Δεν υπάρχει συνδεδεμένος χρήστης.", Toast.LENGTH_LONG).show()
            return
        }

        val fullName = binding.editFullName.text?.toString()?.trim().orEmpty()
        val phone = binding.editPhone.text?.toString()?.trim().orEmpty()
        val area = binding.editArea.text?.toString()?.trim().orEmpty()
        val radiusText = binding.editRadius.text?.toString()?.trim().orEmpty()
        val certified = binding.checkboxCertified.isChecked

        var hasError = false

        if (fullName.isBlank()) {
            binding.layoutFullName.error = "Συμπλήρωσε ονοματεπώνυμο"
            hasError = true
        }

        if (phone.isBlank()) {
            binding.layoutPhone.error = "Συμπλήρωσε τηλέφωνο"
            hasError = true
        }

        if (area.isBlank()) {
            binding.layoutArea.error = "Συμπλήρωσε περιοχή"
            hasError = true
        }

        val resolvedRadius = resolveRadius(radiusText)
        if (resolvedRadius == null) {
            binding.layoutRadius.error =
                "Η ακτίνα πρέπει να είναι αριθμός από 1 έως ${currentSettings.maxRadiusKm}"
            hasError = true
        }

        if (hasError || resolvedRadius == null) return

        val baseProfile = currentProfile
        if (baseProfile == null) {
            Toast.makeText(this, "Το προφίλ δεν έχει φορτωθεί ακόμη.", Toast.LENGTH_LONG).show()
            return
        }

        val updatedProfile = baseProfile.copy(
            fullName = fullName,
            phone = phone,
            area = area,
            certifiedFirstAid = certified,
            preferredRadiusKm = resolvedRadius,
            updatedAt = System.currentTimeMillis()
        )

        setLoading(true)
        binding.textStatus.text = "Αποθήκευση προφίλ..."

        authRepository.updateCurrentUserProfile(
            updatedProfile = updatedProfile,
            onSuccess = {
                setLoading(false)
                currentProfile = updatedProfile
                binding.textStatus.text = "Το προφίλ ενημερώθηκε."
                Toast.makeText(this, "Το προφίλ ενημερώθηκε.", Toast.LENGTH_LONG).show()
            },
            onError = { errorMessage ->
                setLoading(false)
                binding.textStatus.text = "Σφάλμα: $errorMessage"
                Toast.makeText(this, errorMessage, Toast.LENGTH_LONG).show()
            }
        )
    }

    private fun resolveRadius(radiusText: String): Int? {
        if (!currentSettings.allowCustomRadius) {
            return currentSettings.defaultRadiusKm
        }

        if (radiusText.isBlank()) {
            return currentSettings.defaultRadiusKm
        }

        val parsed = radiusText.toIntOrNull() ?: return null
        if (parsed < 1 || parsed > currentSettings.maxRadiusKm) return null

        return parsed
    }

    private fun clearErrors() {
        binding.layoutFullName.error = null
        binding.layoutPhone.error = null
        binding.layoutArea.error = null
        binding.layoutRadius.error = null
    }

    private fun setLoading(loading: Boolean) {
        binding.progressBar.visibility = if (loading) View.VISIBLE else View.GONE
        binding.btnSaveProfile.isEnabled = !loading
        binding.btnLogout.isEnabled = !loading
        binding.btnBack.isEnabled = !loading
    }

    private fun hasBluetoothPermissions(): Boolean {
        val permissions = mutableListOf<String>()
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        return permissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestBluetoothPermissions() {
        val permissions = mutableListOf<String>()
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        bluetoothPermissionLauncher.launch(permissions.toTypedArray())
    }
}