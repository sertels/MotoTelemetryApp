package com.example.mototelemetryapp

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
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
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
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
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.mototelemetryapp.data.RestoreMode
import com.example.mototelemetryapp.data.Session
import com.example.mototelemetryapp.ui.AnalysisScreen
import com.example.mototelemetryapp.ui.BikeInfoScreen
import com.example.mototelemetryapp.ui.CheckEngineBadge
import com.example.mototelemetryapp.ui.DashboardScreen
import com.example.mototelemetryapp.ui.ObdConnectErrorPill
import com.example.mototelemetryapp.ui.ObdStatusBadge
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
        DiagnosticLog.init(this)
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

            // Requested here at the root (not inside MainScreen) since MainScreen is skipped
            // entirely whenever the service is already bound at first composition - e.g. the
            // OBD auto-start receiver already started the service before the user ever opened
            // the Activity, or "Go to panel"/Bike Info bound it - which previously meant these
            // permissions might never get requested at all on a fresh install.
            val runtimePermissions = remember {
                mutableListOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT
                ).apply {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        add(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
            }
            val permissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestMultiplePermissions()
            ) { results: Map<String, Boolean> ->
                if (!results.values.all { it }) {
                    Log.w("MainActivity", "Not all permissions granted")
                }
            }
            LaunchedEffect(Unit) {
                permissionLauncher.launch(runtimePermissions.toTypedArray())
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
                val currentRoute = navController.currentBackStackEntryAsState().value?.destination?.route
                // The Home/Launch screen (unbound "dashboard") has its own Quick Access grid and
                // no chrome. Every other route - including "dashboard" once bound, and tabs like
                // History/Analysis reached directly from Home without binding first - needs the
                // home button and bottom nav so the user is never stranded without navigation.
                val showChrome = currentRoute != "dashboard" || isBound

                // Hoisted here (rather than per-route) so the OBD status/check-engine/connect
                // controls can live once in the shared header instead of being duplicated on
                // Home, Panel, and Bike Info - the whole point being to give each of those
                // screens back the vertical space that duplication was costing them.
                val obdConnected by dashboardViewModel.obdConnected.collectAsState()
                val obdMilOn by dashboardViewModel.obdMilOn.collectAsState()
                val obdDtcCodes by dashboardViewModel.obdDtcCodes.collectAsState()
                val obdSweepRunning by dashboardViewModel.obdSweepRunning.collectAsState()
                val obdSweepResults by dashboardViewModel.obdSweepResults.collectAsState()
                val obdSweepProgress by dashboardViewModel.obdSweepProgress.collectAsState()
                val obdConnectError by dashboardViewModel.obdConnectError.collectAsState()
                val obdSimulated by dashboardViewModel.obdSimulated.collectAsState()

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    topBar = {
                        // A stock Material3 TopAppBar is 64dp tall, which on a landscape phone is
                        // a fifth of the screen - it would cost the gauges more room than hoisting
                        // the OBD controls up here saved them. So this is a hand-rolled bar sized
                        // to its content, and tighter still in landscape where height is scarcest.
                        val isLandscapeBar =
                            LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
                        val barHeight = if (isLandscapeBar) 34.dp else 42.dp
                        val barIconSize = if (isLandscapeBar) 18.dp else 20.dp
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(TelemetrySurfaceElevated)
                                // Horizontal side too, not just statusBars - in landscape the 3-button
                                // nav bar sits on the side rather than the bottom, and without this the
                                // lock icon at the end of the row renders underneath it.
                                .windowInsetsPadding(
                                    WindowInsets.statusBars.union(WindowInsets.navigationBars.only(WindowInsetsSides.Horizontal))
                                )
                                .height(barHeight)
                                .padding(horizontal = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (showChrome) {
                                IconButton(
                                    onClick = {
                                        dashboardViewModel.unbindService(context)
                                        navController.navigate("dashboard") {
                                            popUpTo("dashboard") { inclusive = true }
                                            launchSingleTop = true
                                        }
                                    },
                                    modifier = Modifier.size(barHeight)
                                ) {
                                    Icon(
                                        Icons.Default.Home,
                                        contentDescription = stringResource(R.string.main_title),
                                        modifier = Modifier.size(barIconSize)
                                    )
                                }
                                Spacer(modifier = Modifier.width(6.dp))
                            }
                            if (obdConnectError != null) {
                                ObdConnectErrorPill(obdConnectError!!)
                            }
                            Spacer(modifier = Modifier.weight(1f))
                            if (obdMilOn) {
                                CheckEngineBadge(dtcCodes = obdDtcCodes, onClearDtcs = { dashboardViewModel.clearObdDtcs() })
                                Spacer(modifier = Modifier.width(6.dp))
                            }
                            ObdStatusBadge(
                                connected = obdConnected,
                                // Plain Android Bluetooth query, not routed through the service -
                                // see getPairedBluetoothDeviceEntries for why (listing must not
                                // force-bind the service, which used to hijack Home into Panel).
                                onFetchDevices = { getPairedBluetoothDeviceEntries(context) },
                                onConnect = { address -> dashboardViewModel.connectObd(context, address) },
                                simulated = obdSimulated,
                                onDisconnect = { dashboardViewModel.disconnectObd() }
                            )
                            Spacer(modifier = Modifier.width(2.dp))
                            IconButton(
                                onClick = {
                                    isRotationLocked = !isRotationLocked
                                    requestedOrientation = if (isRotationLocked) {
                                        android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LOCKED
                                    } else {
                                        android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                                    }
                                },
                                modifier = Modifier.size(barHeight)
                            ) {
                                Icon(
                                    imageVector = if (isRotationLocked) Icons.Default.Lock else Icons.Default.LockOpen,
                                    contentDescription = stringResource(
                                        if (isRotationLocked) R.string.unlock_rotation else R.string.lock_rotation
                                    ),
                                    modifier = Modifier.size(barIconSize)
                                )
                            }
                        }
                    },
                    bottomBar = bottomBar@{
                        // Matches the design: the tab bar only makes sense once on a tab screen
                        // (Panel/History/Bike Info/Analysis/Settings) - the Launch/Home screen has
                        // its own Quick Access grid instead. See showChrome above.
                        if (!showChrome) return@bottomBar
                        val navItemColors = NavigationBarItemDefaults.colors(
                            selectedIconColor = TelemetryAccent,
                            selectedTextColor = TelemetryAccent,
                            unselectedIconColor = TelemetryOnSurfaceMuted,
                            unselectedTextColor = TelemetryOnSurfaceMuted,
                            indicatorColor = Color.Transparent
                        )
                        // Same reasoning as the top bar: NavigationBar defaults to 80dp, which
                        // together with the stock top bar was squeezing the telemetry screens into
                        // a scroll. Sized down here, harder in landscape, so Panel fits on a screen.
                        val isLandscapeNav =
                            LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
                        val navBarHeight = if (isLandscapeNav) 42.dp else 54.dp
                        val navIconSize = if (isLandscapeNav) 17.dp else 19.dp
                        val navLabelSize = if (isLandscapeNav) 9.sp else 10.sp
                        // The bar's own insets are zeroed and re-applied on the wrapper instead, so
                        // navBarHeight sizes the tab row itself rather than the row plus the system
                        // gesture/button inset - otherwise the height would eat into the tabs.
                        Column(
                            modifier = Modifier
                                .background(TelemetrySurfaceElevated)
                                .windowInsetsPadding(WindowInsets.navigationBars)
                        ) {
                        NavigationBar(
                            containerColor = TelemetrySurfaceElevated,
                            modifier = Modifier.height(navBarHeight),
                            windowInsets = WindowInsets(0, 0, 0, 0)
                        ) {
                            NavigationBarItem(
                                icon = { Icon(Icons.Default.Speed, contentDescription = null, modifier = Modifier.size(navIconSize)) },
                                label = { Text(stringResource(R.string.panel), fontSize = navLabelSize) },
                                selected = currentRoute == "dashboard",
                                onClick = {
                                    // Reachable while unbound (e.g. from History/Analysis without
                                    // ever visiting Home's Start Tracking flow) - bind first so
                                    // this always lands on live telemetry, not the Home screen.
                                    if (!isBound) dashboardViewModel.bindService(context)
                                    navController.navigate("dashboard")
                                },
                                colors = navItemColors
                            )
                            NavigationBarItem(
                                icon = { Icon(Icons.Default.History, contentDescription = null, modifier = Modifier.size(navIconSize)) },
                                label = { Text(stringResource(R.string.history), fontSize = navLabelSize) },
                                // The route's actual pattern (not the resolved "history/5" path)
                                // is what NavDestination.route reports while on this screen.
                                selected = currentRoute == "history/{sessionId}",
                                // -1 is the "no specific ride picked" sentinel - falls back to
                                // the latest ride, same as this tab always did.
                                onClick = { navController.navigate("history/-1") },
                                colors = navItemColors
                            )
                            NavigationBarItem(
                                icon = { Icon(Icons.Default.QueryStats, contentDescription = null, modifier = Modifier.size(navIconSize)) },
                                label = { Text(stringResource(R.string.analysis), fontSize = navLabelSize) },
                                selected = currentRoute == "analysis",
                                onClick = { navController.navigate("analysis") },
                                colors = navItemColors
                            )
                            NavigationBarItem(
                                icon = { Icon(Icons.Default.TwoWheeler, contentDescription = null, modifier = Modifier.size(navIconSize)) },
                                label = { Text(stringResource(R.string.bike_info), fontSize = navLabelSize) },
                                selected = currentRoute == "bikeinfo",
                                onClick = { navController.navigate("bikeinfo") },
                                colors = navItemColors
                            )
                            NavigationBarItem(
                                icon = { Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(navIconSize)) },
                                label = { Text(stringResource(R.string.settings), fontSize = navLabelSize) },
                                selected = currentRoute == "settings",
                                onClick = { navController.navigate("settings") },
                                colors = navItemColors
                            )
                        }
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
                                    val maxLeanLeft by dashboardViewModel.maxLeanLeft.collectAsState()
                                    val maxLeanRight by dashboardViewModel.maxLeanRight.collectAsState()
                                    val maxLeanLeftSource by dashboardViewModel.maxLeanLeftSource.collectAsState()
                                    val maxLeanRightSource by dashboardViewModel.maxLeanRightSource.collectAsState()
                                    val maxGForceLat by dashboardViewModel.maxGForceLat.collectAsState()
                                    val maxGForceLon by dashboardViewModel.maxGForceLon.collectAsState()

                                    DashboardScreen(
                                        data = currentData,
                                        leanSource = leanSource,
                                        onToggleSource = { dashboardViewModel.toggleLeanSource() },
                                        onCalibrate = { dashboardViewModel.calibrateLeanAngle() },
                                        maxLeanLeft = maxLeanLeft,
                                        maxLeanRight = maxLeanRight,
                                        maxLeanLeftSource = maxLeanLeftSource,
                                        maxLeanRightSource = maxLeanRightSource,
                                        maxGForceLat = maxGForceLat,
                                        maxGForceLon = maxGForceLon
                                    )
                                } else {
                                    val sessions by dashboardViewModel.sessions.collectAsState()
                                    val fuelLevelPct by dashboardViewModel.fuelLevelPct.collectAsState()
                                    val serviceRemainingKm by dashboardViewModel.serviceRemainingKm.collectAsState()
                                    val fuelRangeKm by dashboardViewModel.fuelRangeKm.collectAsState()
                                    val todayDistanceKm by dashboardViewModel.todayDistanceKm.collectAsState()
                                    val todayDurationLabel by dashboardViewModel.todayDurationLabel.collectAsState()
                                    val todayAvgSpeedKmh by dashboardViewModel.todayAvgSpeedKmh.collectAsState()
                                    val backupStatus by dashboardViewModel.backupStatus.collectAsState()
                                    val restoreStatus by dashboardViewModel.restoreStatus.collectAsState()
                                    val driveBackups by dashboardViewModel.driveBackups.collectAsState()
                                    val driveBackupsLoading by dashboardViewModel.driveBackupsLoading.collectAsState()
                                    MainScreen(
                                        isTrackingActive = isTrackingActive,
                                        lastRide = sessions.firstOrNull(),
                                        fuelLevelPct = fuelLevelPct,
                                        fuelRangeKm = fuelRangeKm,
                                        todayDistanceKm = todayDistanceKm,
                                        todayDurationLabel = todayDurationLabel,
                                        todayAvgSpeedKmh = todayAvgSpeedKmh,
                                        backupStatus = backupStatus,
                                        restoreStatus = restoreStatus,
                                        driveBackups = driveBackups,
                                        driveBackupsLoading = driveBackupsLoading,
                                        onNavigateBikeInfo = { navController.navigate("bikeinfo") },
                                        onStartService = {
                                            startTelemetryService()
                                            dashboardViewModel.setTrackingActive(true)
                                            dashboardViewModel.bindService(context)
                                        },
                                        onStopService = {
                                            // stopService() alone won't destroy the service while
                                            // this screen still holds a BIND_AUTO_CREATE binding to
                                            // it - unbind first so it can actually stop, otherwise
                                            // GPS/OBD polling and DB writes keep running silently.
                                            dashboardViewModel.unbindService(context)
                                            stopService(Intent(this@MainActivity, TelemetryService::class.java))
                                            dashboardViewModel.setTrackingActive(false)
                                        },
                                        onGoToPanel = { dashboardViewModel.bindService(context) },
                                        onNavigateHistory = { navController.navigate("history/-1") },
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
                            composable(
                                "history/{sessionId}",
                                arguments = listOf(navArgument("sessionId") { type = NavType.LongType; defaultValue = -1L })
                            ) { backStackEntry ->
                                val sessionId = backStackEntry.arguments?.getLong("sessionId") ?: -1L
                                // -1 (from the History tab / Home's Quick Access, neither of which
                                // pick a specific ride) means "whatever was ridden most recently" -
                                // any other id (from Analysis' "view route" button) means that ride
                                // specifically, since a session's route otherwise had no way to be
                                // seen on a map once it wasn't the latest one anymore.
                                val history by (
                                    if (sessionId > 0) {
                                        dashboardViewModel.getRecordsForSession(context, sessionId)
                                    } else {
                                        dashboardViewModel.getLatestSessionRecords(context)
                                    }
                                ).collectAsState(initial = emptyList())
                                HistoryScreen(records = history)
                            }
                            composable("bikeinfo") {
                                // Bike Info is reachable directly from the bottom nav without
                                // ever visiting the Panel tab, so ensure the service (and with
                                // it, the OBD manager) actually exists before letting the user
                                // try to connect a device from here.
                                LaunchedEffect(Unit) {
                                    if (!isBound) dashboardViewModel.bindService(context)
                                }
                                val obdRawData by dashboardViewModel.obdRawData.collectAsState()
                                val obdPidStatus by dashboardViewModel.obdPidStatus.collectAsState()
                                val canMonitorRunning by dashboardViewModel.canMonitorRunning.collectAsState()
                                val canMonitorFrames by dashboardViewModel.canMonitorFrames.collectAsState()
                                val serviceRemainingKm by dashboardViewModel.serviceRemainingKm.collectAsState()
                                LaunchedEffect(Unit) { dashboardViewModel.fetchDashboardSummary(context) }

                                BikeInfoScreen(
                                    obdConnected = obdConnected,
                                    pidStatus = obdPidStatus,
                                    odometerKm = obdRawData["ODOMETER"],
                                    distanceSinceClearKm = obdRawData["DIST_SINCE_CLEAR"],
                                    // Prefer a figure the ECU reports over the app's own estimate
                                    // from ride history, which can only ever count rides this
                                    // phone recorded. Only the simulator supplies one today.
                                    serviceRemainingKm = obdRawData["SERVICE_REMAINING"] ?: serviceRemainingKm,
                                    coolantC = obdRawData["COOLANT"],
                                    fuelLevelPct = obdRawData["FUEL_LEVEL"],
                                    fuelRateLph = obdRawData["FUEL_RATE"]?.let { it / 100f },
                                    speedKmh = obdRawData["SPEED"],
                                    batteryVolts = obdRawData["BATTERY"]?.let { it / 10f },
                                    intakeTempC = obdRawData["INTAKE_TEMP"],
                                    engineLoadPct = obdRawData["ENGINE_LOAD"],
                                    ambientTempC = obdRawData["AMBIENT_TEMP"],
                                    mapKpa = obdRawData["MAP_KPA"],
                                    catalystTempB1 = obdRawData["CATALYST_TEMP_B1"],
                                    engineRunTimeSec = obdRawData["ENGINE_RUNTIME_SEC"],
                                    distanceMilOnKm = obdRawData["DIST_MIL_ON"],
                                    obdMilOn = obdMilOn,
                                    obdDtcCodes = obdDtcCodes,
                                    onClearObdDtcs = { dashboardViewModel.clearObdDtcs() },
                                    obdSweepRunning = obdSweepRunning,
                                    obdSweepResults = obdSweepResults,
                                    obdSweepProgress = obdSweepProgress,
                                    onRunObdSweep = { dashboardViewModel.runObdSweep() },
                                    onRunStandardPidSweep = { dashboardViewModel.runStandardPidSweep() },
                                    onRunSecuritySessionProbe = { dashboardViewModel.runSecuritySessionProbe() },
                                    onCancelObdSweep = { dashboardViewModel.cancelObdSweep() },
                                    canMonitorRunning = canMonitorRunning,
                                    canMonitorFrames = canMonitorFrames,
                                    onRunCanMonitor = { seconds -> dashboardViewModel.runCanMonitor(seconds) }
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
                                    },
                                    onViewRoute = { sessionId -> navController.navigate("history/$sessionId") }
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
                                // Seeded from isRideSimulationEnabled() rather than the raw pref so the
                                // switch shows the value actually in force - including the
                                // no-adapter default that applies before anything has been stored.
                                var simulateObd by remember { mutableStateOf(isRideSimulationEnabled(context)) }

                                SettingsScreen(
                                    autoStartOnObdConnect = autoStartOnObdConnect,
                                    showDebugSection = BuildConfig.DEBUG,
                                    simulateObd = simulateObd,
                                    onSimulateObdChange = { checked ->
                                        simulateObd = checked
                                        appPrefs.edit().putBoolean(KEY_SIMULATE_OBD, checked).apply()
                                    },
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
                                    },
                                    onShareDiagnosticLog = {
                                        val intent = DiagnosticLog.shareIntent(context)
                                        if (intent != null) {
                                            context.startActivity(Intent.createChooser(intent, context.getString(R.string.share_diagnostic_log)))
                                        } else {
                                            Toast.makeText(context, R.string.diagnostic_log_empty, Toast.LENGTH_SHORT).show()
                                        }
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

private val HomeCardBg = Color(0xFF161616)
private val HomeCardBorder = Color(0xFF232323)
private val HomeBorderSoft = Color(0xFF262626)
private val HomeTrackGrey = Color(0xFF2B2B2B)
private val HomeFuelYellow = Color(0xFFFFE600)
private val HomeTextTertiary = Color(0xFF7A7A7A)
private val HomeTextQuaternary = Color(0xFF5A5A5A)
private val HomeModelGrey = Color(0xFF666666)
private val HomeStopRed = Color(0xFFFF5A6E)
private val HomeToastBg = Color(0xFF1A1A1A)

@Composable
fun MainScreen(
    isTrackingActive: Boolean,
    lastRide: Session?,
    fuelLevelPct: Int?,
    fuelRangeKm: Int?,
    todayDistanceKm: Float?,
    todayDurationLabel: String?,
    todayAvgSpeedKmh: Int?,
    backupStatus: String?,
    restoreStatus: String?,
    driveBackups: List<DriveBackupEntry>?,
    driveBackupsLoading: Boolean,
    onStartService: () -> Unit,
    onStopService: () -> Unit,
    onGoToPanel: () -> Unit,
    onNavigateHistory: () -> Unit,
    onNavigateAnalysis: () -> Unit,
    onNavigateBikeInfo: () -> Unit,
    onNavigateSettings: () -> Unit,
    onBackup: () -> Unit,
    onOpenRestore: () -> Unit,
    onDismissRestore: () -> Unit,
    onConfirmRestore: (fileId: String, mode: RestoreMode) -> Unit
) {
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    var showRestoreDialog by remember { mutableStateOf(false) }
    val backgroundBrush = Brush.radialGradient(
        colors = listOf(Color(0xFF1A1A1A), Color(0xFF0A0A0A)),
        radius = 1100f
    )

    val content: @Composable () -> Unit = {
        if (isLandscape) {
            HomeLandscapeContent(
                isTrackingActive = isTrackingActive,
                lastRide = lastRide,
                fuelLevelPct = fuelLevelPct,
                fuelRangeKm = fuelRangeKm,
                todayDistanceKm = todayDistanceKm,
                todayDurationLabel = todayDurationLabel,
                todayAvgSpeedKmh = todayAvgSpeedKmh,
                onStartService = onStartService,
                onStopService = onStopService,
                onGoToPanel = onGoToPanel,
                onNavigateHistory = onNavigateHistory,
                onNavigateAnalysis = onNavigateAnalysis,
                onNavigateBikeInfo = onNavigateBikeInfo,
                onNavigateSettings = onNavigateSettings,
                onBackup = onBackup,
                onOpenRestore = { showRestoreDialog = true; onOpenRestore() }
            )
        } else {
            HomePortraitContent(
                isTrackingActive = isTrackingActive,
                lastRide = lastRide,
                fuelLevelPct = fuelLevelPct,
                fuelRangeKm = fuelRangeKm,
                todayDistanceKm = todayDistanceKm,
                todayDurationLabel = todayDurationLabel,
                todayAvgSpeedKmh = todayAvgSpeedKmh,
                onStartService = onStartService,
                onStopService = onStopService,
                onGoToPanel = onGoToPanel,
                onNavigateHistory = onNavigateHistory,
                onNavigateAnalysis = onNavigateAnalysis,
                onNavigateBikeInfo = onNavigateBikeInfo,
                onNavigateSettings = onNavigateSettings,
                onBackup = onBackup,
                onOpenRestore = { showRestoreDialog = true; onOpenRestore() }
            )
        }
    }

    // The Home screen has no bottomBar (see showChrome in the caller), so unlike the other
    // tabs its Scaffold innerPadding never accounts for the nav bar / gesture inset - without
    // this, the CTA buttons at the bottom of portrait content render underneath the system
    // nav bar on edge-to-edge (targetSdk 35+) devices.
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundBrush)
            .windowInsetsPadding(WindowInsets.navigationBars)
    ) {
        content()

        Column(
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (backupStatus != null) {
                Box(
                    modifier = Modifier
                        .padding(top = 4.dp)
                        .background(HomeToastBg, RoundedCornerShape(100.dp))
                        .border(1.dp, HomeBorderSoft, RoundedCornerShape(100.dp))
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                ) {
                    Text(text = backupStatus, color = Color(0xFFCCCCCC), fontSize = 12.sp)
                }
            }
            if (restoreStatus != null) {
                Box(
                    modifier = Modifier
                        .padding(top = 4.dp)
                        .background(HomeToastBg, RoundedCornerShape(100.dp))
                        .border(1.dp, HomeBorderSoft, RoundedCornerShape(100.dp))
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                ) {
                    Text(text = restoreStatus, color = Color(0xFFCCCCCC), fontSize = 12.sp)
                }
            }
        }
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
}

@Composable
private fun FuelRing(pct: Int?, ringSize: androidx.compose.ui.unit.Dp, stroke: androidx.compose.ui.unit.Dp) {
    val fraction = ((pct ?: 0).coerceIn(0, 100)) / 100f
    Box(modifier = Modifier.size(ringSize), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val strokePx = stroke.toPx()
            val arcSize = Size(size.width - strokePx, size.height - strokePx)
            val topLeft = Offset(strokePx / 2f, strokePx / 2f)
            drawArc(
                color = HomeTrackGrey,
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                style = Stroke(width = strokePx, cap = StrokeCap.Round),
                topLeft = topLeft,
                size = arcSize
            )
            drawArc(
                color = HomeFuelYellow,
                startAngle = -90f,
                sweepAngle = 360f * fraction,
                useCenter = false,
                style = Stroke(width = strokePx, cap = StrokeCap.Round),
                topLeft = topLeft,
                size = arcSize
            )
        }
        // No "%" - the ring's fill arc plus the FUEL label above already say "percentage",
        // so it was redundant, and dropping it lets the number sit dead-center and bigger
        // (also helped a lot on the small landscape ring).
        Text(text = "${pct ?: 0}", color = Color.White, fontSize = (ringSize.value * 0.36f).sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun FuelHeroCard(
    fuelLevelPct: Int?,
    fuelRangeKm: Int?,
    lastRide: Session?,
    ringSize: androidx.compose.ui.unit.Dp,
    ringStroke: androidx.compose.ui.unit.Dp,
    cardPadding: androidx.compose.ui.unit.Dp = 20.dp,
    innerSpacing: androidx.compose.ui.unit.Dp = 10.dp,
    showLastRide: Boolean = true,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .background(Brush.linearGradient(listOf(Color(0xFF1C1C1C), Color(0xFF131313))), RoundedCornerShape(22.dp))
            .border(1.dp, HomeBorderSoft, RoundedCornerShape(22.dp))
            .padding(cardPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        FuelRing(pct = fuelLevelPct, ringSize = ringSize, stroke = ringStroke)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(innerSpacing)) {
            Column {
                Text(text = stringResource(R.string.fuel), color = HomeTextTertiary, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = "${fuelRangeKm ?: "--"} km",
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(text = stringResource(R.string.fuel_range_label), color = HomeTextTertiary, fontSize = 11.sp)
                }
            }
            if (showLastRide) {
                Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(HomeBorderSoft))
                Column {
                    Text(text = stringResource(R.string.last_ride), color = HomeTextTertiary, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    Row(modifier = Modifier.padding(top = 2.dp)) {
                        Text(
                            text = (lastRide?.name ?: "—") + " · ",
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = if (lastRide != null) "%.1f km".format(lastRide.totalDistanceGpsKm) else "",
                            color = TelemetryAccent,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TodayRideCard(
    distanceKm: Float?,
    durationLabel: String?,
    avgSpeedKmh: Int?,
    verticalPadding: androidx.compose.ui.unit.Dp = 14.dp,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .background(HomeCardBg, RoundedCornerShape(18.dp))
            .border(1.dp, HomeCardBorder, RoundedCornerShape(18.dp))
            .padding(horizontal = 18.dp, vertical = verticalPadding)
    ) {
        Text(text = stringResource(R.string.today_ride), color = HomeTextTertiary, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        Row(modifier = Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(text = "%.1f".format(distanceKm ?: 0f), color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    Text(text = " km", color = HomeTextTertiary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                }
                Text(text = stringResource(R.string.stat_distance), color = HomeTextQuaternary, fontSize = 9.sp, modifier = Modifier.padding(top = 2.dp))
            }
            Column {
                Text(text = durationLabel ?: "0m", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Text(text = stringResource(R.string.stat_duration), color = HomeTextQuaternary, fontSize = 9.sp, modifier = Modifier.padding(top = 2.dp))
            }
            Column {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(text = "${avgSpeedKmh ?: 0}", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    Text(text = " " + stringResource(R.string.unit_kmh), color = HomeTextTertiary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                }
                Text(text = stringResource(R.string.avg_kmh), color = HomeTextQuaternary, fontSize = 9.sp, modifier = Modifier.padding(top = 2.dp))
            }
        }
    }
}

@Composable
private fun HomeQuickAccessGrid(
    onGoToPanel: () -> Unit,
    onNavigateHistory: () -> Unit,
    onNavigateAnalysis: () -> Unit,
    onNavigateBikeInfo: () -> Unit,
    onNavigateSettings: () -> Unit,
    buttonVerticalPadding: androidx.compose.ui.unit.Dp = 12.dp,
    modifier: Modifier = Modifier
) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        QuickAccessButton(icon = Icons.Default.Speed, label = stringResource(R.string.panel), onClick = onGoToPanel, verticalPadding = buttonVerticalPadding, modifier = Modifier.weight(1f))
        QuickAccessButton(icon = Icons.Default.History, label = stringResource(R.string.history), onClick = onNavigateHistory, verticalPadding = buttonVerticalPadding, modifier = Modifier.weight(1f))
        QuickAccessButton(icon = Icons.Default.QueryStats, label = stringResource(R.string.analysis), onClick = onNavigateAnalysis, verticalPadding = buttonVerticalPadding, modifier = Modifier.weight(1f))
        QuickAccessButton(icon = Icons.Default.TwoWheeler, label = stringResource(R.string.bike_info), onClick = onNavigateBikeInfo, verticalPadding = buttonVerticalPadding, modifier = Modifier.weight(1f))
        QuickAccessButton(icon = Icons.Default.Settings, label = stringResource(R.string.settings), onClick = onNavigateSettings, verticalPadding = buttonVerticalPadding, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun HomeCtaStack(
    isTrackingActive: Boolean,
    onStartService: () -> Unit,
    onStopService: () -> Unit,
    onGoToPanel: () -> Unit,
    onBackup: () -> Unit,
    onOpenRestore: () -> Unit,
    pillModifier: Modifier,
    buttonHeight: androidx.compose.ui.unit.Dp,
    buttonSpacing: androidx.compose.ui.unit.Dp = 10.dp
) {
    val pillShape = RoundedCornerShape(50)
    val textSize = if (buttonHeight > 46.dp) 15.sp else 14.sp

    if (isTrackingActive) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(TelemetryAccent)
            )
            Text(
                text = stringResource(R.string.tracking_active_pill),
                color = TelemetryAccent,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.8.sp,
                modifier = Modifier
                    .background(Color(0x1F00B4FF), RoundedCornerShape(100.dp))
                    .border(1.dp, Color(0x6600B4FF), RoundedCornerShape(100.dp))
                    .padding(horizontal = 0.dp)
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
    }

    Button(
        onClick = { if (isTrackingActive) onGoToPanel() else onStartService() },
        modifier = pillModifier.height(buttonHeight),
        shape = pillShape,
        colors = ButtonDefaults.buttonColors(containerColor = TelemetryAccent, contentColor = TelemetryOnAccent)
    ) {
        Text(
            stringResource(if (isTrackingActive) R.string.view_live_ride else R.string.start_tracking),
            fontWeight = FontWeight.Bold,
            fontSize = textSize
        )
    }
    Spacer(modifier = Modifier.height(buttonSpacing))

    OutlinedButton(
        onClick = onStopService,
        enabled = isTrackingActive,
        modifier = pillModifier.height(buttonHeight),
        shape = pillShape,
        border = BorderStroke(1.dp, if (isTrackingActive) HomeStopRed else Color(0xFF383838)),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = HomeStopRed,
            disabledContentColor = TelemetryOnSurfaceMuted
        )
    ) {
        Text(stringResource(R.string.stop), fontWeight = FontWeight.SemiBold, fontSize = textSize)
    }
    Spacer(modifier = Modifier.height(buttonSpacing))

    // A REPLACE restore mid-ride deletes the session row the service is still writing to,
    // silently losing the whole ride, so both actions are blocked while tracking is active.
    OutlinedButton(
        onClick = onBackup,
        enabled = !isTrackingActive,
        modifier = pillModifier.height(buttonHeight),
        shape = pillShape,
        border = BorderStroke(1.5.dp, TelemetryAccent),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = TelemetryAccent)
    ) {
        Text(stringResource(R.string.backup_drive), fontWeight = FontWeight.SemiBold, fontSize = textSize)
    }
    Spacer(modifier = Modifier.height(buttonSpacing))

    OutlinedButton(
        onClick = onOpenRestore,
        enabled = !isTrackingActive,
        modifier = pillModifier.height(buttonHeight),
        shape = pillShape,
        border = BorderStroke(1.5.dp, TelemetryOnSurfaceMuted),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = TelemetryOnSurfaceMuted)
    ) {
        Text(stringResource(R.string.restore_drive), fontWeight = FontWeight.SemiBold, fontSize = textSize)
    }
}

@Composable
private fun HomePortraitContent(
    isTrackingActive: Boolean,
    lastRide: Session?,
    fuelLevelPct: Int?,
    fuelRangeKm: Int?,
    todayDistanceKm: Float?,
    todayDurationLabel: String?,
    todayAvgSpeedKmh: Int?,
    onStartService: () -> Unit,
    onStopService: () -> Unit,
    onGoToPanel: () -> Unit,
    onNavigateHistory: () -> Unit,
    onNavigateAnalysis: () -> Unit,
    onNavigateBikeInfo: () -> Unit,
    onNavigateSettings: () -> Unit,
    onBackup: () -> Unit,
    onOpenRestore: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = stringResource(R.string.main_title),
            color = Color.White,
            fontSize = 21.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "LX900-A · ENGINE 4M96001",
            color = HomeModelGrey,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 0.5.sp,
            modifier = Modifier.padding(top = 3.dp)
        )

        FuelHeroCard(
            fuelLevelPct = fuelLevelPct,
            fuelRangeKm = fuelRangeKm,
            lastRide = lastRide,
            ringSize = 72.dp,
            ringStroke = 6.dp,
            cardPadding = 14.dp,
            innerSpacing = 7.dp,
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp)
        )

        TodayRideCard(
            distanceKm = todayDistanceKm,
            durationLabel = todayDurationLabel,
            avgSpeedKmh = todayAvgSpeedKmh,
            verticalPadding = 10.dp,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
        )

        Text(
            text = stringResource(R.string.quick_access),
            color = HomeTextQuaternary,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.5.sp,
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
        )
        HomeQuickAccessGrid(
            onGoToPanel = onGoToPanel,
            onNavigateHistory = onNavigateHistory,
            onNavigateAnalysis = onNavigateAnalysis,
            onNavigateBikeInfo = onNavigateBikeInfo,
            onNavigateSettings = onNavigateSettings,
            buttonVerticalPadding = 9.dp,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
        )

        Spacer(modifier = Modifier.height(24.dp))

        HomeCtaStack(
            isTrackingActive = isTrackingActive,
            onStartService = onStartService,
            onStopService = onStopService,
            onGoToPanel = onGoToPanel,
            onBackup = onBackup,
            onOpenRestore = onOpenRestore,
            pillModifier = Modifier.widthIn(max = 260.dp).fillMaxWidth(),
            buttonHeight = 40.dp,
            buttonSpacing = 7.dp
        )
    }
}

@Composable
private fun HomeLandscapeContent(
    isTrackingActive: Boolean,
    lastRide: Session?,
    fuelLevelPct: Int?,
    fuelRangeKm: Int?,
    todayDistanceKm: Float?,
    todayDurationLabel: String?,
    todayAvgSpeedKmh: Int?,
    onStartService: () -> Unit,
    onStopService: () -> Unit,
    onGoToPanel: () -> Unit,
    onNavigateHistory: () -> Unit,
    onNavigateAnalysis: () -> Unit,
    onNavigateBikeInfo: () -> Unit,
    onNavigateSettings: () -> Unit,
    onBackup: () -> Unit,
    onOpenRestore: () -> Unit
) {
    // No verticalScroll here (unlike the portrait content): it would make the incoming height
    // constraint unbounded, and the right column below relies on fillMaxHeight() + Center to
    // vertically center its buttons - against infinity that resolves to wrap-content at the top
    // instead, which is exactly the bug this avoids. The left column scrolls on its own instead,
    // so a short landscape window still doesn't clip the quick-access grid.
    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Text(
                text = stringResource(R.string.main_title),
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "LX900-A · ENGINE 4M96001",
                color = HomeModelGrey,
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 0.5.sp,
                modifier = Modifier.padding(top = 2.dp)
            )

            FuelHeroCard(
                fuelLevelPct = fuelLevelPct,
                fuelRangeKm = fuelRangeKm,
                lastRide = lastRide,
                ringSize = 36.dp,
                ringStroke = 4.dp,
                cardPadding = 8.dp,
                innerSpacing = 3.dp,
                showLastRide = false,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
            )

            TodayRideCard(
                distanceKm = todayDistanceKm,
                durationLabel = todayDurationLabel,
                avgSpeedKmh = todayAvgSpeedKmh,
                verticalPadding = 5.dp,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
            )

            Text(
                text = stringResource(R.string.quick_access),
                color = HomeTextQuaternary,
                fontSize = 8.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.2.sp,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
            )
            HomeQuickAccessGrid(
                onGoToPanel = onGoToPanel,
                onNavigateHistory = onNavigateHistory,
                onNavigateAnalysis = onNavigateAnalysis,
                onNavigateBikeInfo = onNavigateBikeInfo,
                onNavigateSettings = onNavigateSettings,
                buttonVerticalPadding = 5.dp,
                modifier = Modifier.fillMaxWidth().padding(top = 3.dp)
            )
        }

        Box(modifier = Modifier.fillMaxHeight().width(1.dp).background(HomeCardBorder))

        Column(
            modifier = Modifier.weight(1f).fillMaxHeight(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            HomeCtaStack(
                isTrackingActive = isTrackingActive,
                onStartService = onStartService,
                onStopService = onStopService,
                onGoToPanel = onGoToPanel,
                onBackup = onBackup,
                onOpenRestore = onOpenRestore,
                pillModifier = Modifier.widthIn(max = 220.dp).fillMaxWidth(),
                buttonHeight = 44.dp
            )
        }
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
// Absent (rather than false) until the user picks a side, so isObdSimulationEnabled() can tell
// "never chosen" from "explicitly off" and only then fall back to detecting a missing adapter.
const val KEY_SIMULATE_OBD = "simulate_obd"
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
fun QuickAccessButton(icon: ImageVector, label: String, onClick: () -> Unit, verticalPadding: androidx.compose.ui.unit.Dp = 12.dp, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .background(Color(0xFF161616), RoundedCornerShape(12.dp))
            .border(1.dp, Color(0xFF262626), RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = verticalPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Icon(icon, contentDescription = null, tint = Color(0xFFCCCCCC), modifier = Modifier.size(17.dp))
        Text(text = label, color = Color(0xFFCCCCCC), fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
    }
}
