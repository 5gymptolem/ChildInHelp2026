package com.childInHelp2026.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.location.Location
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import android.hardware.Sensor
import android.hardware.SensorManager
import android.view.KeyEvent
import com.google.android.gms.location.LocationServices
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.childInHelp2026.app.databinding.ActivityMainBinding
import com.childInHelp2026.app.firebase.AppFirebase
import com.childInHelp2026.app.repository.AedRepository
import com.childInHelp2026.app.repository.AuthRepository
import com.childInHelp2026.app.repository.SettingsRepository
import com.childInHelp2026.app.repository.SosRepository
import com.childInHelp2026.app.sync.UserSessionSyncCoordinator
import com.childInHelp2026.app.ui.aed.AedUiController
import com.childInHelp2026.app.ui.main.MainMapController
import com.childInHelp2026.app.ui.sos.ShakeDetector
import com.childInHelp2026.app.ui.sos.SosFlowController
import com.childInHelp2026.app.ui.sos.SosUiController
import java.util.Locale

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"

        private const val ROLE_ADMIN = "admin"
        private const val ROLE_OPERATOR = "operator"
        private const val ROLE_USER = "user"

        const val EXTRA_OPEN_FROM_NOTIFICATION = "open_from_notification"
        const val EXTRA_NOTIFICATION_TYPE = "notification_type"
        const val EXTRA_SOS_ID = "sos_id"
        const val EXTRA_SOS_LATITUDE = "sos_latitude"
        const val EXTRA_SOS_LONGITUDE = "sos_longitude"

        const val NOTIFICATION_TYPE_SOS_ALERT = "sos_alert"

        private const val INITIAL_USER_ZOOM = 18.5
        private const val INITIAL_FOCUS_DELAY_MS = 350L
    }

    private enum class MainTab {
        SOS,
        AED
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var authRepository: AuthRepository
    private lateinit var aedRepository: AedRepository
    private lateinit var sosRepository: SosRepository
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var mapController: MainMapController
    private lateinit var aedUiController: AedUiController
    private lateinit var sosUiController: SosUiController
    private lateinit var sosFlowController: SosFlowController
    private lateinit var sessionSyncCoordinator: UserSessionSyncCoordinator

    private lateinit var sensorManager: SensorManager
    private var shakeDetector: ShakeDetector? = null
    private var isVolumeUpPressed = false

    private val database: FirebaseDatabase by lazy { AppFirebase.database }
    private val fusedLocationClient by lazy {
        LocationServices.getFusedLocationProviderClient(this)
    }
    private val mainHandler = Handler(Looper.getMainLooper())

    private var currentTab: MainTab = MainTab.AED
    private var currentUserRole: String = ROLE_USER
    private var currentUserLocation: Location? = null

    private var currentUserLocationListener: ValueEventListener? = null
    private var aedListener: ValueEventListener? = null
    private var sosListener: ValueEventListener? = null
    private var appSettingsListener: ValueEventListener? = null

    private var pendingNotificationSosId: String? = null
    private var pendingNotificationLatitude: String? = null
    private var pendingNotificationLongitude: String? = null

    private var initialUserFocusDone = false

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            sessionSyncCoordinator.ensureCurrentUserProfileThenSync(
                existingLocationListener = currentUserLocationListener,
                setLocationListener = { currentUserLocationListener = it }
            )
        }

    private val locationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            val fineGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true
            val coarseGranted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true

            if (fineGranted || coarseGranted) {
                sessionSyncCoordinator.ensureCurrentUserProfileThenSync(
                    existingLocationListener = currentUserLocationListener,
                    setLocationListener = { currentUserLocationListener = it }
                )
            } else {
                Toast.makeText(
                    this,
                    getString(R.string.Den_Dothike_Adeia_topothsias),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        authRepository = AuthRepository()
        aedRepository = AedRepository()
        sosRepository = SosRepository()
        settingsRepository = SettingsRepository()

        mapController = MainMapController(
            context = this,
            mapView = binding.mapView,
            statusView = binding.textMapStatus
        )

        aedUiController = AedUiController(
            context = this,
            mapController = mapController,
            getCurrentUserLocation = { currentUserLocation },
            onStatusMessage = { message -> binding.textMapStatus.text = message }
        )

        sosUiController = SosUiController(
            context = this,
            mapController = mapController,
            sosRepository = sosRepository,
            getCurrentUserUid = { authRepository.getCurrentUser()?.uid },
            getCurrentUserEmail = { authRepository.getCurrentUser()?.email ?: "" },
            getCurrentUserRole = { currentUserRole },
            getCurrentUserLocation = { currentUserLocation },
            onStatusMessage = { message -> binding.textMapStatus.text = message },
            onNearestAedRequested = {
                switchToTab(MainTab.AED)
                val shown = aedUiController.showNearestAedDialog()
                if (!shown) {
                    Toast.makeText(this,
                        getString(R.string.Den_Yparxei_apinidotis), Toast.LENGTH_SHORT)
                        .show()
                }
            }
        )

        sosFlowController = SosFlowController(
            context = this,
            getCurrentUser = { authRepository.getCurrentUser() },
            getCurrentUserRole = { currentUserRole },
            getCurrentUserLocation = { currentUserLocation },
            onSwitchToSosTab = { switchToTab(MainTab.SOS) },
            onStatusMessage = { message -> binding.textMapStatus.text = message }
        )

        sessionSyncCoordinator = UserSessionSyncCoordinator(
            context = this,
            authRepository = authRepository,
            database = database,
            fusedLocationClient = fusedLocationClient,
            onRoleLoaded = { role ->
                currentUserRole = role.lowercase(Locale.ROOT)
                Log.d(TAG, "Role updated in activity: $currentUserRole")
            },
            onLocationUpdated = { location ->
                currentUserLocation = location
                mapController.updateUserLocation(location)
                aedUiController.onUserLocationUpdated()
                tryFocusMapOnUserImmediately()
            },
            onSyncError = { message ->
                Log.e(TAG, message)
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            }
        )

        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        shakeDetector = ShakeDetector {
            if (isVolumeUpPressed) {
                sosFlowController.triggerPanicSos()
            }
        }

        setupUi()
        updateUi()
        ensureNotificationPermissionIfNeeded()
        ensureLocationPermissionIfNeeded()

        sessionSyncCoordinator.ensureCurrentUserProfileThenSync(
            existingLocationListener = currentUserLocationListener,
            setLocationListener = { currentUserLocationListener = it }
        )

        observeAppSettings()
        observeAeds()
        observeSos()

        mapController.setOnAdminLongPress { point ->
            if (currentUserRole == ROLE_ADMIN) {
                sosFlowController.showAdminBroadcastDialog(point)
            }
        }

        handleNotificationIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleNotificationIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        mapController.onResume()
        updateUi()

        val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        sensorManager.registerListener(shakeDetector, accelerometer, SensorManager.SENSOR_DELAY_UI)

        sessionSyncCoordinator.ensureCurrentUserProfileThenSync(
            existingLocationListener = currentUserLocationListener,
            setLocationListener = { currentUserLocationListener = it }
        )

        tryFocusMapOnUserImmediately()
        tryOpenPendingNotificationSos()
    }

    override fun onPause() {
        sensorManager.unregisterListener(shakeDetector)
        mapController.onPause()
        super.onPause()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            isVolumeUpPressed = true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            isVolumeUpPressed = false
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)

        aedListener?.let { aedRepository.removeObserver(it) }
        sosListener?.let { sosRepository.removeObserver(it) }
        appSettingsListener?.let { settingsRepository.removeListener(it) }
        sessionSyncCoordinator.removeCurrentUserLocationListener(currentUserLocationListener)
        super.onDestroy()
    }

    private fun setupUi() {
        binding.btnInfo.setOnClickListener {
            showInfoDialog()
        }

        binding.btnRefresh.setOnClickListener {
            mapController.resetCenteredState()
            initialUserFocusDone = false

            sessionSyncCoordinator.ensureCurrentUserProfileThenSync(
                existingLocationListener = currentUserLocationListener,
                setLocationListener = { currentUserLocationListener = it }
            )

            mainHandler.postDelayed({
                tryFocusMapOnUserImmediately(force = true)
            }, INITIAL_FOCUS_DELAY_MS)

            Toast.makeText(this, "Έγινε ανανέωση", Toast.LENGTH_SHORT).show()
        }

        binding.btnMenu.setOnClickListener {
            showMainMenu()
        }

        binding.btnList.setOnClickListener {
            when (currentTab) {
                MainTab.AED -> aedUiController.showAedListDialog()
                MainTab.SOS -> sosUiController.showSosListDialog()
            }
        }

        binding.tabSos.setOnClickListener {
            switchToTab(MainTab.SOS)
        }

        binding.tabAed.setOnClickListener {
            switchToTab(MainTab.AED)
        }

        binding.btnEmergency.setOnClickListener {
            sosFlowController.showCreateSosDialog()
        }

        binding.btnLoginOverlay.setOnClickListener {
            startActivity(Intent(this, LoginActivity::class.java))
        }

        binding.btnRegisterOverlay.setOnClickListener {
            startActivity(Intent(this, RegisterActivity::class.java))
        }

        switchToTab(MainTab.AED)
    }

    private fun updateUi() {
        val currentUser = authRepository.getCurrentUser()
        val isLoggedIn = currentUser != null

        binding.textHeaderSubtitle.text = if (isLoggedIn) {
            currentUser.email ?: getString(R.string.syndedemenos_xristis)
        } else {
            getString(R.string.mi_syndedemenos_xristis)
        }

        binding.authOverlayCard.visibility = if (isLoggedIn) {
            android.view.View.GONE
        } else {
            android.view.View.VISIBLE
        }
    }

    @SuppressLint("SetTextI18n")
    private fun switchToTab(tab: MainTab) {
        currentTab = tab
        updateTabStyles()

        when (tab) {
            MainTab.SOS -> {
                binding.btnEmergency.text = getString(R.string.Sos)
                binding.btnList.text = getString(R.string.Lists)
                sosUiController.renderForActiveTab()
                aedUiController.renderForInactiveTab()
            }

            MainTab.AED -> {
                binding.btnEmergency.text = getString(R.string.Sos)
                binding.btnList.text = getString(R.string.Lists)
                aedUiController.renderForActiveTab()
                sosUiController.renderForInactiveTab()
            }
        }
    }

    private fun updateTabStyles() {
        val activeColor = ContextCompat.getColor(this, R.color.sp_tab_active)
        val transparent = ContextCompat.getColor(this, android.R.color.transparent)

        when (currentTab) {
            MainTab.SOS -> {
                binding.tabSos.isChecked = true
                binding.tabAed.isChecked = false
                binding.tabSos.setBackgroundColor(activeColor)
                binding.tabAed.setBackgroundColor(transparent)
            }

            MainTab.AED -> {
                binding.tabSos.isChecked = false
                binding.tabAed.isChecked = true
                binding.tabSos.setBackgroundColor(transparent)
                binding.tabAed.setBackgroundColor(activeColor)
            }
        }
    }

    private fun observeAppSettings() {
        appSettingsListener = settingsRepository.observeAppSettings(
            onChange = { settings ->
                sosUiController.updateAppSettings(settings)
            },
            onError = { errorMessage ->
                Log.e(TAG, "getString(R.string.apotyxia_fortosis)$errorMessage")
            }
        )
    }

    @SuppressLint("SetTextI18n")
    private fun observeAeds() {
        aedListener = aedRepository.observeActiveAeds(
            onData = { aeds ->
                aedUiController.updateAeds(aeds, currentTab == MainTab.AED)
            },
            onError = { errorMessage ->
                binding.textMapStatus.text = getString(R.string.Sfalma_fortosis_AED, errorMessage)
                Toast.makeText(this, getString(R.string.aed, errorMessage), Toast.LENGTH_LONG)
                    .show()
            }
        )
    }

    @SuppressLint("SetTextI18n")
    private fun observeSos() {
        sosListener = sosRepository.observeActiveSos(
            onData = { sosList ->
                sosUiController.updateSos(sosList, currentTab == MainTab.SOS)
                tryOpenPendingNotificationSos()
            },
            onError = { errorMessage ->
                binding.textMapStatus.text = getString(R.string.Sfalma_fortosis_sos,errorMessage)
                Toast.makeText(this, getString(R.string.Sfalma_fortosis_sos),Toast.LENGTH_LONG)
                    .show()
            }
        )
    }

    private fun tryFocusMapOnUserImmediately(force: Boolean = false) {
        val location = currentUserLocation ?: return
        if (pendingNotificationSosId != null) return
        if (initialUserFocusDone && !force) return

        mainHandler.removeCallbacksAndMessages("initial_user_focus")

        val runnable = Runnable {
            mapController.updateUserLocation(location)
            mapController.centerOnUserLocation()
            binding.mapView.controller.setZoom(INITIAL_USER_ZOOM)
            initialUserFocusDone = true

            Log.d(
                TAG,
                "Initial user focus applied at lat=${location.latitude}, lng=${location.longitude}"
            )
        }

        binding.mapView.postDelayed(runnable, INITIAL_FOCUS_DELAY_MS)
    }

    private fun tryOpenPendingNotificationSos() {
        val sosId = pendingNotificationSosId ?: return

        switchToTab(MainTab.SOS)

        val focused = sosUiController.focusOnSosById(sosId)

        if (focused) {
            Toast.makeText(
                this,
                getString(R.string.Symban_apo_eidopoihsh),
                Toast.LENGTH_SHORT
            ).show()

            Log.d(TAG, getString(R.string.notification_sos_focused_successfully, sosId))

            pendingNotificationSosId = null
            pendingNotificationLatitude = null
            pendingNotificationLongitude = null
        } else {
            Log.d(TAG, getString(R.string.notification_sos_not_ready_yet, sosId))
        }
    }

    @SuppressLint("SetTextI18n")
    private fun handleNotificationIntent(intent: Intent?) {
        if (intent == null) return

        val openedFromNotification =
            intent.getBooleanExtra(EXTRA_OPEN_FROM_NOTIFICATION, false)
        val notificationType =
            intent.getStringExtra(EXTRA_NOTIFICATION_TYPE).orEmpty()

        if (!openedFromNotification || notificationType != NOTIFICATION_TYPE_SOS_ALERT) {
            return
        }

        val sosId = intent.getStringExtra(EXTRA_SOS_ID).orEmpty()
        val latitude = intent.getStringExtra(EXTRA_SOS_LATITUDE).orEmpty()
        val longitude = intent.getStringExtra(EXTRA_SOS_LONGITUDE).orEmpty()

        if (sosId.isBlank()) {
            Log.w(TAG, getString(R.string.notification_intent_received_without_sosid))
            return
        }

        pendingNotificationSosId = sosId
        pendingNotificationLatitude = latitude
        pendingNotificationLongitude = longitude

        binding.textMapStatus.text = getString(R.string.Fortosi_symvantos_Apo_eidopoihsh, sosId)

        Log.d(
            TAG,
            getString(
                R.string.notification_intent_handled_sosid_latitude_longitude,
                sosId,
                latitude,
                longitude
            )
        )

        tryOpenPendingNotificationSos()
        intent.removeExtra(EXTRA_OPEN_FROM_NOTIFICATION)
    }

    private fun showInfoDialog() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.About))
            .setMessage(
                getString(R.string.childInHelp2026_safe_mdef) +
                        getString(R.string.aed_sos_marker)
            )
            .setPositiveButton("ΟΚ", null)
            .show()
    }

    private fun showMainMenu() {
        val currentUser = authRepository.getCurrentUser()

        if (currentUser == null) {
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.Menu))
                .setItems(arrayOf(getString(R.string.Syndesi), getString(R.string.Eggrafi))) { _, which ->
                    when (which) {
                        0 -> startActivity(Intent(this, LoginActivity::class.java))
                        1 -> startActivity(Intent(this, RegisterActivity::class.java))
                    }
                }
                .setNegativeButton(getString(R.string.Akyro), null)
                .show()
            return
        }

        val menuItems = if (currentUserRole == ROLE_ADMIN || currentUserRole == ROLE_OPERATOR) {
            arrayOf(getString(R.string.O_logariasmos_mou),
                getString(R.string.Rolos, currentUserRole), getString(R.string.Aposyndesi))
        } else {
            arrayOf(getString(R.string.O_logariasmos_mou), getString(R.string.Aposyndesi))
        }

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.Menu))
            .setItems(menuItems) { _, which ->
                when {
                    menuItems[which] == "Ο λογαριασμός μου" -> {
                        startActivity(Intent(this, ProfileActivity::class.java))
                    }

                    menuItems[which] == "Αποσύνδεση" -> {
                        authRepository.logout()
                        currentUserRole = ROLE_USER
                        updateUi()
                        Toast.makeText(this, "Έγινε αποσύνδεση.", Toast.LENGTH_SHORT).show()
                    }

                    else -> {
                        Toast.makeText(this, "Τρέχων ρόλος: $currentUserRole", Toast.LENGTH_SHORT)
                            .show()
                    }
                }
            }
            .setNegativeButton("Άκυρο", null)
            .show()
    }

    private fun ensureNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return

        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

        if (!granted) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun ensureLocationPermissionIfNeeded() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }

        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }

        if (missing.isNotEmpty()) {
            locationPermissionLauncher.launch(permissions.toTypedArray())
        }
    }

}