package com.example.mototelemetryapp

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.IntentSenderRequest
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.QueryStats
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.mototelemetryapp.ui.AnalysisScreen
import com.example.mototelemetryapp.ui.DashboardScreen
import com.example.mototelemetryapp.ui.HistoryScreen
import com.example.mototelemetryapp.ui.theme.MotoTelemetryAppTheme
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.api.services.drive.DriveScopes
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    
    private val dashboardViewModel: DashboardViewModel by viewModels()
    private val credentialManager by lazy { CredentialManager.create(this) }

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val context = LocalContext.current
            val navController = rememberNavController()
            val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
            val backupErrorMessage = stringResource(R.string.backup_error)
            var isRotationLocked by remember { mutableStateOf(false) }
            
            // Service binding management - only bind when activity is created, not on recomposition
            LaunchedEffect(Unit) {
                dashboardViewModel.bindService(context)
                dashboardViewModel.fetchHistory(context)
            }
            
            // Only unbind when activity is actually destroyed, not on configuration change
            DisposableEffect(lifecycleOwner) {
                onDispose {
                    // Don't unbind on configuration changes
                    // Service should stay bound across orientation changes
                }
            }

            val authorizationLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.StartIntentSenderForResult()
            ) { result ->
                if (result.resultCode == RESULT_OK) {
                    try {
                        Identity.getAuthorizationClient(this)
                            .getAuthorizationResultFromIntent(result.data)
                        
                        // Permission granted, start backup
                        dashboardViewModel.backupToCloud(context, android.accounts.Account("authorized", "com.google"))
                    } catch (e: ApiException) {
                        Log.e("MainActivity", "Authorization failed: ${e.message}")
                    }
                }
            }

            val backupStatus by dashboardViewModel.backupStatus.collectAsState()
            LaunchedEffect(backupStatus) {
                backupStatus?.let {
                    Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
                }
            }

            MotoTelemetryAppTheme {
                val isBound by dashboardViewModel.isServiceBound.collectAsState()
                
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    topBar = {
                        if (isBound) {
                            TopAppBar(
                                title = { },
                                navigationIcon = {
                                    IconButton(onClick = { dashboardViewModel.unbindService(context) }) {
                                        Icon(Icons.Default.Home, contentDescription = stringResource(R.string.main_title))
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
                        }
                    },
                    bottomBar = {
                        if (isBound) {
                            NavigationBar {
                                NavigationBarItem(
                                    icon = { Icon(Icons.Default.Speed, contentDescription = null) },
                                    label = { Text(stringResource(R.string.panel)) },
                                    selected = false,
                                    onClick = { navController.navigate("dashboard") }
                                )
                                NavigationBarItem(
                                    icon = { Icon(Icons.Default.History, contentDescription = null) },
                                    label = { Text(stringResource(R.string.history)) },
                                    selected = false,
                                    onClick = { navController.navigate("history") }
                                )
                                NavigationBarItem(
                                    icon = { Icon(Icons.Default.QueryStats, contentDescription = null) },
                                    label = { Text(stringResource(R.string.analysis)) },
                                    selected = false,
                                    onClick = { navController.navigate("analysis") }
                                )
                            }
                        }
                    }
                ) { innerPadding ->
                    Box(modifier = Modifier.padding(innerPadding)) {
                        if (isBound) {
                            NavHost(navController = navController, startDestination = "dashboard") {
                                composable("dashboard") {
                                    val telemetryFlow = dashboardViewModel.getTelemetryFlow()
                                    val currentData by (telemetryFlow?.collectAsState(initial = null) ?: remember { mutableStateOf(null) })
                                    val leanSource by dashboardViewModel.leanSource.collectAsState()
                                    
                                    DashboardScreen(
                                        data = currentData,
                                        leanSource = leanSource,
                                        onToggleSource = { dashboardViewModel.toggleLeanSource() },
                                        onCalibrate = { dashboardViewModel.calibrateLeanAngle() }
                                    )
                                }
                                composable("history") {
                                    val history by dashboardViewModel.history.collectAsState()
                                    HistoryScreen(records = history)
                                }
                                composable("analysis") {
                                    val sessions by dashboardViewModel.sessions.collectAsState()
                                    AnalysisScreen(
                                        sessions = sessions,
                                        onRenameSession = { session, newName ->
                                            dashboardViewModel.renameSession(context, session, newName)
                                        },
                                        getRecords = { sessionId ->
                                            dashboardViewModel.getRecordsForSession(context, sessionId)
                                        }
                                    )
                                }
                            }
                        } else {
                            MainScreen(
                                onStartService = { startTelemetryService() },
                                onStopService = { stopService(Intent(this@MainActivity, TelemetryService::class.java)) },
                                onBackup = {
                                    lifecycleScope.launch {
                                        try {
                                            // 1. Sign-in
                                            val googleIdOption = GetGoogleIdOption.Builder()
                                                .setFilterByAuthorizedAccounts(false)
                                                .setServerClientId("215653511600-csa6ge8s64b5dacl7to64hhscfr0p85s.apps.googleusercontent.com")
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

                                            if (googleIdTokenCredential != null) {
                                                val email = googleIdTokenCredential.id
                                                val account = android.accounts.Account(email, "com.google")

                                                // 2. Request Drive Authorization
                                                val authRequest = AuthorizationRequest.builder()
                                                    .setRequestedScopes(listOf(Scope(DriveScopes.DRIVE_APPDATA)))
                                                    .build()

                                                Identity.getAuthorizationClient(this@MainActivity)
                                                    .authorize(authRequest)
                                                    .addOnSuccessListener { authResult ->
                                                        if (authResult.hasResolution()) {
                                                            authResult.pendingIntent?.let { pendingIntent ->
                                                                val intentSenderRequest = IntentSenderRequest.Builder(pendingIntent.intentSender).build()
                                                                authorizationLauncher.launch(intentSenderRequest)
                                                            } ?: run {
                                                                Log.e("MainActivity", "PendingIntent is null")
                                                                dashboardViewModel.backupToCloud(context, account)
                                                            }
                                                        } else {
                                                            dashboardViewModel.backupToCloud(context, account)
                                                        }
                                                    }
                                            } else {
                                                Log.e("MainActivity", "Credential was not a Google ID token")
                                            }
                                        } catch (e: NoCredentialException) {
                                            Log.e("MainActivity", "No Google account available for sign-in", e)
                                            Toast.makeText(context, backupErrorMessage, Toast.LENGTH_SHORT).show()
                                        } catch (e: GetCredentialException) {
                                            Log.e("MainActivity", "Credential retrieval failed", e)
                                        } catch (e: Exception) {
                                            Log.e("MainActivity", "Backup flow failed", e)
                                        }
                                    }
                                },
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
    onStartService: () -> Unit, 
    onStopService: () -> Unit, 
    onBackup: () -> Unit,
    onLanguageChange: (String) -> Unit
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

    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = stringResource(R.string.main_title),
                style = MaterialTheme.typography.headlineMedium
            )
            Text(
                text = "Model: LX900-A | Engine: 4M96001",
                style = MaterialTheme.typography.labelSmall,
                color = Color.Gray
            )
            Spacer(modifier = Modifier.height(32.dp))
            
            Button(onClick = onStartService, modifier = Modifier.width(200.dp)) {
                Text(stringResource(R.string.start_tracking))
            }
            Spacer(modifier = Modifier.height(16.dp))
            
            Button(onClick = onStopService, modifier = Modifier.width(200.dp)) {
                Text(stringResource(R.string.stop))
            }
            Spacer(modifier = Modifier.height(16.dp))
            
            Button(onClick = onBackup, modifier = Modifier.width(200.dp)) {
                Text(stringResource(R.string.backup_drive))
            }
            
            Spacer(modifier = Modifier.height(48.dp))
            
            // Language Selection
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Language, contentDescription = null, tint = Color.Gray)
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = stringResource(R.string.select_language), color = Color.Gray)
            }
            Row {
                TextButton(onClick = { onLanguageChange("en") }) { Text("English") }
                TextButton(onClick = { onLanguageChange("tr") }) { Text("Türkçe") }
            }
        }
    }
}
