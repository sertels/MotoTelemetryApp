package com.example.mototelemetryapp

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.IntentSenderRequest
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.QueryStats
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.TwoWheeler
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.os.LocaleListCompat
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.mototelemetryapp.data.RestoreMode
import com.example.mototelemetryapp.data.Session
import com.example.mototelemetryapp.ui.AnalysisScreen
import com.example.mototelemetryapp.ui.BikeInfoScreen
import com.example.mototelemetryapp.ui.DashboardScreen
import com.example.mototelemetryapp.ui.HistoryScreen
import com.example.mototelemetryapp.ui.SettingsScreen
import com.example.mototelemetryapp.ui.theme.MotoTelemetryAppTheme
import com.example.mototelemetryapp.ui.theme.TelemetryAccent
import com.example.mototelemetryapp.ui.theme.TelemetryOnAccent
import com.example.mototelemetryapp.ui.theme.TelemetryOnSurface
import com.example.mototelemetryapp.ui.theme.TelemetryOnSurfaceMuted
import com.example.mototelemetryapp.ui.theme.TelemetrySurfaceElevated
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.api.services.drive.DriveScopes
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {
    
    private val dashboardViewModel: DashboardViewModel by viewModels()
    private val credentialManager by lazy { CredentialManager.create(this) }

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val context = LocalContext.current
            val navController = rememberNavController()
            val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
            val driveSignInErrorMessage = stringResource(R.string.drive_signin_error)
            var isRotationLocked by remember { mutableStateOf(false) }

            // Retained between fetching the Drive backup list and confirming a restore, since
            // that's two separate user actions (open dialog, then pick + confirm) sharing one token.
            var driveAccessToken by remember { mutableStateOf<String?>(null) }

            val appPrefs = remember { context.getSharedPreferences(APP_PREFS_NAME, Context.MODE_PRIVATE) }
            var batteryOptExempt by remember { mutableStateOf(isIgnoringBatteryOptimizations(context)) }
            val batteryOptLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.StartActivityForResult()
            ) {
                batteryOptExempt = isIgnoringBatteryOptimizations(context)
            }

            // Foreground services can still be killed by OEM battery managers (this is exactly
            // what happened to a recorded ride once) unless the app is exempted from battery
            // optimization. Ask once automatically; the Settings tab stays as a manual retry.
            LaunchedEffect(Unit) {
                if (!batteryOptExempt && !appPrefs.getBoolean(KEY_BATTERY_OPT_PROMPTED, false)) {
                    appPrefs.edit().putBoolean(KEY_BATTERY_OPT_PROMPTED, true).apply()
                    batteryOptLauncher.launch(requestIgnoreBatteryOptimizationsIntent(context))
                }
            }

            // Service binding management - only bind when activity is created, not on recomposition
            LaunchedEffect(Unit) {
                // Only attach if a session is already running; don't spin up an inert,
                // never-started service instance just by opening the app.
                dashboardViewModel.bindService(context, autoCreate = false)
                dashboardViewModel.fetchHistory(context)
                dashboardViewModel.fetchDashboardSummary(context)
            }
            
            // Only unbind when activity is actually destroyed, not on configuration change
            DisposableEffect(lifecycleOwner) {
                onDispose {
                    // Don't unbind on configuration changes
                    // Service should stay bound across orientation changes
                }
            }

            // Holds whichever callback is waiting on a token while the consent screen (launched
            // below) is on screen - the launcher's result callback has no other way to know
            // which of backup/restore triggered it.
            var pendingAccessTokenCallback by remember { mutableStateOf<((String) -> Unit)?>(null) }
            var pendingAccessTokenFailureCallback by remember { mutableStateOf<(() -> Unit)?>(null) }

            val authorizationLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.StartIntentSenderForResult()
            ) { result ->
                var handled = false
                if (result.resultCode == RESULT_OK) {
                    try {
                        val authResult = Identity.getAuthorizationClient(this)
                            .getAuthorizationResultFromIntent(result.data)
                        val token = authResult.accessToken
                        if (token != null) {
                            pendingAccessTokenCallback?.invoke(token)
                            handled = true
                        }
                    } catch (e: ApiException) {
                        Log.e("MainActivity", "Authorization failed: ${e.message}")
                    }
                }
                if (!handled) {
                    pendingAccessTokenFailureCallback?.invoke()
                }
                pendingAccessTokenCallback = null
                pendingAccessTokenFailureCallback = null
            }

            // Shared by backup and restore: signs in via Credential Manager, then requests a
            // Drive appDataFolder access token via the Authorization API, handing it to
            // whichever action asked for it. The token (not an Account) is what the Drive client
            // is built from - see GoogleDriveManager - since GoogleAccountCredential's classic
            // AccountManager-based auth is a separate system this sign-in flow never grants.
            fun requestDriveAccessToken(onToken: (String) -> Unit, onFailure: () -> Unit = {}) {
                lifecycleScope.launch {
                    try {
                        val googleIdOption = GetGoogleIdOption.Builder()
                            .setFilterByAuthorizedAccounts(false)
                            // Must be the Web-application OAuth client, not the Android one - Google's
                            // Credential Manager sign-in uses this as the token audience regardless of
                            // platform. Using the Android client ID here fails with "[28444] Developer
                            // console is not set up correctly" even though the Android client itself
                            // (package + SHA-1) is configured correctly.
                            .setServerClientId("215653511600-i6891dhdsd6u8hnc05nmipnn1g4gcukj.apps.googleusercontent.com")
                            .build()

                        val request = GetCredentialRequest.Builder()
                            .addCredentialOption(googleIdOption)
                            .build()

                        val result = credentialManager.getCredential(context, request)
                        val credential = result.credential

                        val googleIdTokenCredential = if (
                            credential is CustomCredential &&
                            credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
                        ) {
                            GoogleIdTokenCredential.createFrom(credential.data)
                        } else {
                            null
                        }

                        if (googleIdTokenCredential == null) {
                            Log.e("MainActivity", "Credential was not a Google ID token")
                            return@launch
                        }

                        val authRequest = AuthorizationRequest.builder()
                            .setRequestedScopes(listOf(Scope(DriveScopes.DRIVE_APPDATA)))
                            .build()

                        Identity.getAuthorizationClient(this@MainActivity)
                            .authorize(authRequest)
                            .addOnSuccessListener { authResult ->
                                if (authResult.hasResolution()) {
                                    authResult.pendingIntent?.let { pendingIntent ->
                                        pendingAccessTokenCallback = onToken
                                        pendingAccessTokenFailureCallback = onFailure
                                        val intentSenderRequest = IntentSenderRequest.Builder(pendingIntent.intentSender).build()
                                        authorizationLauncher.launch(intentSenderRequest)
                                    } ?: run {
                                        Log.e("MainActivity", "PendingIntent is null")
                                        onFailure()
                                    }
                                } else {
                                    val token = authResult.accessToken
                                    if (token != null) onToken(token) else onFailure()
                                }
                            }
                            .addOnFailureListener { e ->
                                Log.e("MainActivity", "Drive authorization request failed", e)
                                onFailure()
                            }
                    } catch (e: NoCredentialException) {
                        Log.e("MainActivity", "No Google account available for sign-in", e)
                        Toast.makeText(context, driveSignInErrorMessage, Toast.LENGTH_SHORT).show()
                        onFailure()
                    } catch (e: GetCredentialException) {
                        Log.e("MainActivity", "Credential retrieval failed", e)
                        onFailure()
                    } catch (e: Exception) {
                        Log.e("MainActivity", "Drive auth flow failed", e)
                        onFailure()
                    }
                }
            }

            MotoTelemetryAppTheme {
                val isBound by dashboardViewModel.isServiceBound.collectAsState()
                
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    topBar = {
                        TopAppBar(
                            title = { },
                            colors = TopAppBarDefaults.topAppBarColors(containerColor = TelemetrySurfaceElevated),
                            navigationIcon = {
                                if (isBound) {
                                    IconButton(onClick = { dashboardViewModel.unbindService(context) }) {
                                        Icon(Icons.Default.Home, contentDescription = stringResource(R.string.main_title))
                                    }
                                }
                            },
                            actions = {
                                IconButton(onClick = {
                                    isRotationLocked = !isRotationLocked
                                    requestedOrientation = if (isRotationLocked) {
                                        android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LOCKED
                                    } else {
                                        android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                                    }
                                }) {
                                    Icon(
                                        imageVector = if (isRotationLocked) Icons.Default.Lock else Icons.Default.LockOpen,
                                        contentDescription = stringResource(
                                            if (isRotationLocked) R.string.unlock_rotation else R.string.lock_rotation
                                        )
                                    )
                                }
                            }
                        )
                    },
                    bottomBar = {
                        val currentRoute = navController.currentBackStackEntryAsState().value?.destination?.route
                        val navItemColors = NavigationBarItemDefaults.colors(
                            selectedIconColor = TelemetryAccent,
                            selectedTextColor = TelemetryAccent,
                            unselectedIconColor = TelemetryOnSurfaceMuted,
                            unselectedTextColor = TelemetryOnSurfaceMuted,
                            indicatorColor = Color.Transparent
                        )
                        NavigationBar(containerColor = TelemetrySurfaceElevated) {
                            NavigationBarItem(
                                icon = { Icon(Icons.Default.Speed, contentDescription = null) },
                                label = { Text(stringResource(R.string.panel)) },
                                selected = currentRoute == "dashboard",
                                onClick = { navController.navigate("dashboard") },
                                colors = navItemColors
                            )
                            NavigationBarItem(
                                icon = { Icon(Icons.Default.History, contentDescription = null) },
                                label = { Text(stringResource(R.string.history)) },
                                selected = currentRoute == "history",
                                onClick = { navController.navigate("history") },
                                colors = navItemColors
                            )
                            NavigationBarItem(
                                icon = { Icon(Icons.Default.TwoWheeler, contentDescription = null) },
                                label = { Text(stringResource(R.string.bike_info)) },
                                selected = currentRoute == "bikeinfo",
                                onClick = { navController.navigate("bikeinfo") },
                                colors = navItemColors
                            )
                            NavigationBarItem(
                                icon = { Icon(Icons.Default.QueryStats, contentDescription = null) },
                                label = { Text(stringResource(R.string.analysis)) },
                                selected = currentRoute == "analysis",
                                onClick = { navController.navigate("analysis") },
                                colors = navItemColors
                            )
                            NavigationBarItem(
                                icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                                label = { Text(stringResource(R.string.settings)) },
                                selected = currentRoute == "settings",
                                onClick = { navController.navigate("settings") },
                                colors = navItemColors
                            )
                        }
                    }
                ) { innerPadding ->
                    Box(modifier = Modifier.padding(innerPadding)) {
                        val isTrackingActive by dashboardViewModel.isTrackingActive.collectAsState()

                        NavHost(navController = navController, startDestination = "dashboard") {
                            composable("dashboard") {
                                if (isBound) {
                                    val telemetryFlow = dashboardViewModel.getTelemetryFlow()
                                    val currentData by (telemetryFlow?.collectAsState(initial = null) ?: remember { mutableStateOf(null) })
                                    val leanSource by dashboardViewModel.leanSource.collectAsState()
                                    val obdConnected by dashboardViewModel.obdConnected.collectAsState()
                                    val obdSweepRunning by dashboardViewModel.obdSweepRunning.collectAsState()
                                    val obdSweepResults by dashboardViewModel.obdSweepResults.collectAsState()
                                    val obdSweepProgress by dashboardViewModel.obdSweepProgress.collectAsState()
                                    val obdConnectError by dashboardViewModel.obdConnectError.collectAsState()
                                    val obdMilOn by dashboardViewModel.obdMilOn.collectAsState()
                                    val obdDtcCodes by dashboardViewModel.obdDtcCodes.collectAsState()

                                    DashboardScreen(
                                        data = currentData,
                                        leanSource = leanSource,
                                        onToggleSource = { dashboardViewModel.toggleLeanSource() },
                                        onCalibrate = { dashboardViewModel.calibrateLeanAngle() },
                                        obdConnected = obdConnected,
                                        onFetchObdDevices = { dashboardViewModel.getPairedObdDevices() },
                                        onConnectObd = { address -> dashboardViewModel.connectObd(context, address) },
                                        obdSweepRunning = obdSweepRunning,
                                        obdSweepResults = obdSweepResults,
                                        obdSweepProgress = obdSweepProgress,
                                        onRunObdSweep = { dashboardViewModel.runObdSweep() },
                                        onCancelObdSweep = { dashboardViewModel.cancelObdSweep() },
                                        obdConnectError = obdConnectError,
                                        obdMilOn = obdMilOn,
                                        obdDtcCodes = obdDtcCodes,
                                        onClearObdDtcs = { dashboardViewModel.clearObdDtcs() }
                                    )
                                } else {
                                    val sessions by dashboardViewModel.sessions.collectAsState()
                                    val fuelLevelPct by dashboardViewModel.fuelLevelPct.collectAsState()
                                    val serviceRemainingKm by dashboardViewModel.serviceRemainingKm.collectAsState()
                                    val backupStatus by dashboardViewModel.backupStatus.collectAsState()
                                    val restoreStatus by dashboardViewModel.restoreStatus.collectAsState()
                                    val driveBackups by dashboardViewModel.driveBackups.collectAsState()
                                    val driveBackupsLoading by dashboardViewModel.driveBackupsLoading.collectAsState()
                                    MainScreen(
                                        isTrackingActive = isTrackingActive,
                                        lastRide = sessions.firstOrNull(),
                                        fuelLevelPct = fuelLevelPct,
                                        serviceRemainingKm = serviceRemainingKm,
                                        backupStatus = backupStatus,
                                        restoreStatus = restoreStatus,
                                        driveBackups = driveBackups,
                                        driveBackupsLoading = driveBackupsLoading,
                                        onStartService = {
                                            startTelemetryService()
                                            dashboardViewModel.setTrackingActive(true)
                                            dashboardViewModel.bindService(context)
                                        },
                                        onStopService = {
                                            stopService(Intent(this@MainActivity, TelemetryService::class.java))
                                            dashboardViewModel.setTrackingActive(false)
                                        },
                                        onGoToPanel = { dashboardViewModel.bindService(context) },
                                        onNavigateHistory = { navController.navigate("history") },
                                        onNavigateAnalysis = { navController.navigate("analysis") },
                                        onNavigateSettings = { navController.navigate("settings") },
                                        onBackup = {
                                            requestDriveAccessToken(onToken = { token ->
                                                dashboardViewModel.backupToCloud(context, token)
                                            })
                                        },
                                        onOpenRestore = {
                                            dashboardViewModel.setDriveBackupsLoading()
                                            requestDriveAccessToken(
                                                onToken = { token ->
                                                    driveAccessToken = token
                                                    dashboardViewModel.fetchDriveBackups(context, token)
                                                },
                                                onFailure = { dashboardViewModel.clearDriveBackups() }
                                            )
                                        },
                                        onDismissRestore = { dashboardViewModel.clearDriveBackups() },
                                        onConfirmRestore = { fileId, mode ->
                                            driveAccessToken?.let { token ->
                                                dashboardViewModel.restoreFromDrive(context, token, fileId, mode)
                                            }
                                        }
                                    )
                                }
                            }
                            composable("history") {
                                val history by dashboardViewModel.getLatestSessionRecords(context).collectAsState(initial = emptyList())
                                HistoryScreen(records = history)
                            }
                            composable("bikeinfo") {
                                val obdConnected by dashboardViewModel.obdConnected.collectAsState()
                                val obdMilOn by dashboardViewModel.obdMilOn.collectAsState()
                                val obdDtcCodes by dashboardViewModel.obdDtcCodes.collectAsState()
                                val obdRawData by dashboardViewModel.obdRawData.collectAsState()
                                val obdSweepRunning by dashboardViewModel.obdSweepRunning.collectAsState()
                                val obdSweepResults by dashboardViewModel.obdSweepResults.collectAsState()
                                val obdSweepProgress by dashboardViewModel.obdSweepProgress.collectAsState()

                                BikeInfoScreen(
                                    obdConnected = obdConnected,
                                    odometerKm = obdRawData["ODOMETER"],
                                    coolantC = obdRawData["COOLANT"],
                                    fuelLevelPct = obdRawData["FUEL_LEVEL"],
                                    fuelRateLph = obdRawData["FUEL_RATE"]?.let { it / 100f },
                                    obdMilOn = obdMilOn,
                                    obdDtcCodes = obdDtcCodes,
                                    onClearObdDtcs = { dashboardViewModel.clearObdDtcs() },
                                    obdSweepRunning = obdSweepRunning,
                                    obdSweepResults = obdSweepResults,
                                    obdSweepProgress = obdSweepProgress,
                                    onRunObdSweep = { dashboardViewModel.runObdSweep() },
                                    onCancelObdSweep = { dashboardViewModel.cancelObdSweep() }
                                )
                            }
                            composable("analysis") {
                                val sessions by dashboardViewModel.sessions.collectAsState()
                                AnalysisScreen(
                                    sessions = sessions,
                                    onRenameSession = { session, newName ->
                                        dashboardViewModel.renameSession(context, session, newName)
                                    },
                                    onDeleteSession = { session ->
                                        dashboardViewModel.deleteSession(context, session)
                                    },
                                    getRecords = { sessionId ->
                                        dashboardViewModel.getRecordsForSession(context, sessionId)
                                    }
                                )
                            }
                            composable("settings") {
                                var autoStartOnObdConnect by remember {
                                    mutableStateOf(appPrefs.getBoolean(KEY_AUTO_START_ON_OBD_CONNECT, false))
                                }
                                var rideGracePeriodMinutes by remember {
                                    mutableStateOf(appPrefs.getInt(KEY_RIDE_GRACE_PERIOD_MINUTES, DEFAULT_RIDE_GRACE_PERIOD_MINUTES))
                                }
                                val currentLocaleTag = AppCompatDelegate.getApplicationLocales()[0]?.language ?: "en"

                                SettingsScreen(
                                    autoStartOnObdConnect = autoStartOnObdConnect,
                                    onAutoStartToggleChange = { checked ->
                                        autoStartOnObdConnect = checked
                                        appPrefs.edit().putBoolean(KEY_AUTO_START_ON_OBD_CONNECT, checked).apply()
                                    },
                                    rideGracePeriodMinutes = rideGracePeriodMinutes,
                                    onRideGracePeriodChange = { minutes ->
                                        rideGracePeriodMinutes = minutes
                                        appPrefs.edit().putInt(KEY_RIDE_GRACE_PERIOD_MINUTES, minutes).apply()
                                    },
                                    batteryOptExempt = batteryOptExempt,
                                    onRequestBatteryOptExemption = {
                                        batteryOptLauncher.launch(requestIgnoreBatteryOptimizationsIntent(context))
                                    },
                                    currentLocaleTag = currentLocaleTag,
                                    onLanguageChange = { tag ->
                                        val appLocale: LocaleListCompat = LocaleListCompat.forLanguageTags(tag)
                                        AppCompatDelegate.setApplicationLocales(appLocale)
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private fun startTelemetryService() {
        val intent = Intent(this, TelemetryService::class.java)
        startForegroundService(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        // Only unbind service when activity is actually destroyed, not on configuration change
        if (!isChangingConfigurations) {
            dashboardViewModel.unbindService(this)
        }
    }
}

@Composable
fun MainScreen(
    isTrackingActive: Boolean,
    lastRide: Session?,
    fuelLevelPct: Int?,
    serviceRemainingKm: Int?,
    backupStatus: String?,
    restoreStatus: String?,
    driveBackups: List<DriveBackupEntry>?,
    driveBackupsLoading: Boolean,
    onStartService: () -> Unit,
    onStopService: () -> Unit,
    onGoToPanel: () -> Unit,
    onNavigateHistory: () -> Unit,
    onNavigateAnalysis: () -> Unit,
    onNavigateSettings: () -> Unit,
    onBackup: () -> Unit,
    onOpenRestore: () -> Unit,
    onDismissRestore: () -> Unit,
    onConfirmRestore: (fileId: String, mode: RestoreMode) -> Unit
) {
    val permissions = mutableListOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT
    )
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        permissions.add(Manifest.permission.POST_NOTIFICATIONS)
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results: Map<String, Boolean> ->
        val allGranted = results.values.all { it }
        if (!allGranted) {
            Log.w("MainActivity", "Not all permissions granted")
        }
    }

    LaunchedEffect(Unit) {
        launcher.launch(permissions.toTypedArray())
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(32.dp))

        Text(
            text = stringResource(R.string.main_title),
            style = MaterialTheme.typography.headlineSmall
        )
        Text(
            text = "Model: LX900-A · Engine: 4M96001",
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = TelemetryOnSurfaceMuted
        )

        Spacer(modifier = Modifier.height(26.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            InfoCard(modifier = Modifier.weight(1f)) {
                Text(text = stringResource(R.string.last_ride), color = TelemetryOnSurfaceMuted, fontSize = 9.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.5.sp)
                Text(
                    text = lastRide?.name ?: "—",
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 6.dp)
                )
                Text(
                    text = lastRide?.let {
                        SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault()).format(Date(it.startTime))
                    } ?: "",
                    color = TelemetryOnSurfaceMuted,
                    fontSize = 10.sp,
                    modifier = Modifier.padding(top = 2.dp)
                )
                Text(
                    text = if (lastRide != null) "%.1f km".format(lastRide.totalDistanceGpsKm) else "",
                    color = TelemetryAccent,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
            InfoCard(modifier = Modifier.weight(1f)) {
                Text(text = stringResource(R.string.fuel), color = TelemetryOnSurfaceMuted, fontSize = 9.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.5.sp)
                Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(top = 6.dp)) {
                    Text(text = "${fuelLevelPct ?: 0}", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text(text = "%", color = TelemetryOnSurfaceMuted, fontSize = 11.sp, modifier = Modifier.padding(bottom = 3.dp, start = 2.dp))
                }
                Box(
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .fillMaxWidth()
                        .height(5.dp)
                        .background(Color(0xFF2B2B2B), RoundedCornerShape(100.dp))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth((fuelLevelPct ?: 0) / 100f)
                            .fillMaxHeight()
                            .background(Color(0xFFFFE600), RoundedCornerShape(100.dp))
                    )
                }
            }
            InfoCard(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = stringResource(R.string.service),
                    color = TelemetryOnSurfaceMuted,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.5.sp,
                    modifier = Modifier.fillMaxWidth()
                )
                Box(modifier = Modifier.padding(top = 4.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(
                        progress = { ((serviceRemainingKm ?: 0) / DashboardViewModel.SERVICE_INTERVAL_KM).coerceIn(0f, 1f) },
                        modifier = Modifier.size(60.dp),
                        color = TelemetryAccent,
                        trackColor = Color(0xFF2B2B2B),
                        strokeWidth = 5.dp,
                        strokeCap = StrokeCap.Round
                    )
                    Text(text = "${serviceRemainingKm ?: 0}", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
                Text(text = stringResource(R.string.km_left), color = TelemetryOnSurfaceMuted, fontSize = 8.sp, modifier = Modifier.padding(top = 6.dp))
            }
        }

        Spacer(modifier = Modifier.height(28.dp))

        Text(
            text = stringResource(R.string.quick_access),
            color = Color(0xFF5A5A5A),
            fontSize = 9.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.sp,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            QuickAccessButton(
                icon = Icons.Default.Speed,
                label = stringResource(R.string.panel),
                onClick = onGoToPanel,
                modifier = Modifier.weight(1f)
            )
            QuickAccessButton(
                icon = Icons.Default.History,
                label = stringResource(R.string.history),
                onClick = onNavigateHistory,
                modifier = Modifier.weight(1f)
            )
            QuickAccessButton(
                icon = Icons.Default.QueryStats,
                label = stringResource(R.string.analysis),
                onClick = onNavigateAnalysis,
                modifier = Modifier.weight(1f)
            )
            QuickAccessButton(
                icon = Icons.Default.Settings,
                label = stringResource(R.string.settings),
                onClick = onNavigateSettings,
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (isTrackingActive) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(TelemetryAccent)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = stringResource(R.string.recording_status), color = TelemetryAccent)
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        Spacer(modifier = Modifier.height(16.dp))

        val pillShape = RoundedCornerShape(24.dp)
        val pillModifier = Modifier.width(220.dp).height(48.dp)

        Button(
            onClick = onStartService,
            modifier = pillModifier,
            shape = pillShape,
            colors = ButtonDefaults.buttonColors(containerColor = TelemetryAccent, contentColor = TelemetryOnAccent)
        ) {
            Text(stringResource(R.string.start_tracking), fontWeight = FontWeight.SemiBold)
        }
        Spacer(modifier = Modifier.height(14.dp))

        Button(
            onClick = onStopService,
            enabled = isTrackingActive,
            modifier = pillModifier,
            shape = pillShape,
            colors = ButtonDefaults.buttonColors(
                containerColor = TelemetrySurfaceElevated,
                contentColor = TelemetryOnSurface,
                disabledContainerColor = TelemetrySurfaceElevated,
                disabledContentColor = TelemetryOnSurfaceMuted
            )
        ) {
            Text(stringResource(R.string.stop), fontWeight = FontWeight.SemiBold)
        }
        Spacer(modifier = Modifier.height(14.dp))

        if (isTrackingActive) {
            Button(
                onClick = onGoToPanel,
                modifier = pillModifier,
                shape = pillShape,
                colors = ButtonDefaults.buttonColors(containerColor = TelemetryAccent, contentColor = TelemetryOnAccent)
            ) {
                Text(stringResource(R.string.go_to_panel), fontWeight = FontWeight.SemiBold)
            }
            Spacer(modifier = Modifier.height(14.dp))
        }

        OutlinedButton(
            onClick = onBackup,
            modifier = pillModifier,
            shape = pillShape,
            border = BorderStroke(1.5.dp, TelemetryAccent),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = TelemetryAccent)
        ) {
            Text(stringResource(R.string.backup_drive), fontWeight = FontWeight.SemiBold)
        }
        Spacer(modifier = Modifier.height(14.dp))

        var showRestoreDialog by remember { mutableStateOf(false) }

        OutlinedButton(
            onClick = {
                showRestoreDialog = true
                onOpenRestore()
            },
            modifier = pillModifier,
            shape = pillShape,
            border = BorderStroke(1.5.dp, TelemetryOnSurfaceMuted),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = TelemetryOnSurfaceMuted)
        ) {
            Text(stringResource(R.string.restore_drive), fontWeight = FontWeight.SemiBold)
        }

        if (showRestoreDialog) {
            RestoreDialog(
                backups = driveBackups,
                loading = driveBackupsLoading,
                onDismiss = {
                    showRestoreDialog = false
                    onDismissRestore()
                },
                onConfirm = { fileId, mode ->
                    showRestoreDialog = false
                    onConfirmRestore(fileId, mode)
                }
            )
        }

        if (backupStatus != null) {
            Spacer(modifier = Modifier.height(10.dp))
            Box(
                modifier = Modifier
                    .background(Color(0xFF1A1A1A), RoundedCornerShape(100.dp))
                    .border(1.dp, Color(0xFF262626), RoundedCornerShape(100.dp))
                    .padding(horizontal = 16.dp, vertical = 6.dp)
            ) {
                Text(text = backupStatus, color = Color(0xFFCCCCCC), fontSize = 12.sp)
            }
        }

        if (restoreStatus != null) {
            Spacer(modifier = Modifier.height(10.dp))
            Box(
                modifier = Modifier
                    .background(Color(0xFF1A1A1A), RoundedCornerShape(100.dp))
                    .border(1.dp, Color(0xFF262626), RoundedCornerShape(100.dp))
                    .padding(horizontal = 16.dp, vertical = 6.dp)
            ) {
                Text(text = restoreStatus, color = Color(0xFFCCCCCC), fontSize = 12.sp)
            }
        }

        Spacer(modifier = Modifier.height(32.dp))
    }
}

@Composable
fun RestoreDialog(
    backups: List<DriveBackupEntry>?,
    loading: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (fileId: String, mode: RestoreMode) -> Unit
) {
    var selectedFileId by remember(backups) { mutableStateOf(backups.orEmpty().firstOrNull()?.fileId) }
    var selectedMode by remember { mutableStateOf(RestoreMode.MERGE) }
    val dateFormat = remember { SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = { selectedFileId?.let { onConfirm(it, selectedMode) } },
                enabled = selectedFileId != null
            ) {
                Text(stringResource(R.string.restore_drive), color = TelemetryAccent)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
        title = { Text(stringResource(R.string.restore_dialog_title), color = Color.White) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                if (loading) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), color = TelemetryAccent, strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            stringResource(R.string.restore_loading),
                            color = TelemetryOnSurfaceMuted,
                            fontSize = 12.sp
                        )
                    }
                } else if (backups == null) {
                    Text(
                        stringResource(R.string.restore_list_error),
                        color = Color(0xFFFF8A80),
                        fontSize = 12.sp
                    )
                } else if (backups.isEmpty()) {
                    Text(
                        stringResource(R.string.restore_no_backups),
                        color = TelemetryOnSurfaceMuted,
                        fontSize = 12.sp
                    )
                } else {
                    Text(
                        stringResource(R.string.restore_pick_backup),
                        color = TelemetryOnSurfaceMuted,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 0.5.sp
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    backups.forEach { backup ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selectedFileId = backup.fileId }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = selectedFileId == backup.fileId,
                                onClick = { selectedFileId = backup.fileId },
                                colors = RadioButtonDefaults.colors(selectedColor = TelemetryAccent)
                            )
                            Text(
                                text = dateFormat.format(Date(backup.createdTimeMillis)),
                                color = Color.White,
                                fontSize = 13.sp
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        stringResource(R.string.restore_pick_mode),
                        color = TelemetryOnSurfaceMuted,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 0.5.sp
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { selectedMode = RestoreMode.MERGE },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = selectedMode == RestoreMode.MERGE,
                            onClick = { selectedMode = RestoreMode.MERGE },
                            colors = RadioButtonDefaults.colors(selectedColor = TelemetryAccent)
                        )
                        Column {
                            Text(stringResource(R.string.restore_mode_merge), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            Text(stringResource(R.string.restore_mode_merge_hint), color = TelemetryOnSurfaceMuted, fontSize = 11.sp)
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { selectedMode = RestoreMode.REPLACE },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = selectedMode == RestoreMode.REPLACE,
                            onClick = { selectedMode = RestoreMode.REPLACE },
                            colors = RadioButtonDefaults.colors(selectedColor = TelemetryAccent)
                        )
                        Column {
                            Text(stringResource(R.string.restore_mode_replace), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            Text(stringResource(R.string.restore_mode_replace_hint), color = TelemetryOnSurfaceMuted, fontSize = 11.sp)
                        }
                    }
                }
            }
        },
        containerColor = Color(0xFF1C1C1C)
    )
}

private const val KEY_BATTERY_OPT_PROMPTED = "battery_opt_prompted"
const val APP_PREFS_NAME = "app_prefs"
const val KEY_AUTO_START_ON_OBD_CONNECT = "auto_start_on_obd_connect"
const val KEY_RIDE_GRACE_PERIOD_MINUTES = "ride_grace_period_minutes"
const val DEFAULT_RIDE_GRACE_PERIOD_MINUTES = 10
const val MIN_RIDE_GRACE_PERIOD_MINUTES = 1
const val MAX_RIDE_GRACE_PERIOD_MINUTES = 60

private fun isIgnoringBatteryOptimizations(context: Context): Boolean {
    val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    return powerManager.isIgnoringBatteryOptimizations(context.packageName)
}

@SuppressLint("BatteryLife")
private fun requestIgnoreBatteryOptimizationsIntent(context: Context): Intent =
    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).setData(Uri.parse("package:${context.packageName}"))

@Composable
fun InfoCard(
    modifier: Modifier = Modifier,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier
            .background(Color(0xFF161616), RoundedCornerShape(14.dp))
            .border(1.dp, Color(0xFF232323), RoundedCornerShape(14.dp))
            .padding(horizontal = 10.dp, vertical = 12.dp),
        horizontalAlignment = horizontalAlignment,
        content = content
    )
}

@Composable
fun QuickAccessButton(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .background(Color(0xFF161616), RoundedCornerShape(12.dp))
            .border(1.dp, Color(0xFF262626), RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Icon(icon, contentDescription = null, tint = Color(0xFFCCCCCC), modifier = Modifier.size(17.dp))
        Text(text = label, color = Color(0xFFCCCCCC), fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
    }
}
