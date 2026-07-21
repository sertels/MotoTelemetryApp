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
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Language
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
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.mototelemetryapp.ui.DashboardScreen
import com.example.mototelemetryapp.ui.HistoryScreen
import com.example.mototelemetryapp.ui.theme.MotoTelemetryAppTheme
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.common.api.ApiException

class MainActivity : ComponentActivity() {
    
    private val dashboardViewModel: DashboardViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val context = LocalContext.current
            val navController = rememberNavController()
            
            // Servise bağlanma/ayrılma yönetimi
            DisposableEffect(Unit) {
                dashboardViewModel.bindService(context)
                dashboardViewModel.fetchHistory(context)
                onDispose {
                    dashboardViewModel.unbindService(context)
                }
            }

            val googleSignInLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.StartActivityForResult()
            ) { result ->
                val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
                try {
                    val account = task.getResult(ApiException::class.java)
                    if (account != null) {
                        dashboardViewModel.backupToCloud(context, account)
                    }
                } catch (e: ApiException) {
                    Log.e("MainActivity", "Google Sign-In failed: ${e.message}")
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
                                        onToggleSource = { dashboardViewModel.toggleLeanSource() }
                                    )
                                }
                                composable("history") {
                                    val history by dashboardViewModel.history.collectAsState()
                                    HistoryScreen(records = history)
                                }
                            }
                        } else {
                            MainScreen(
                                onStartService = { startTelemetryService() },
                                onStopService = { stopService(Intent(this@MainActivity, TelemetryService::class.java)) },
                                onBackup = {
                                    val manager = GoogleDriveManager(context)
                                    googleSignInLauncher.launch(manager.getGoogleSignInClient().signInIntent)
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
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
        Manifest.permission.ACCESS_COARSE_LOCATION
    )

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        permissions.add(Manifest.permission.BLUETOOTH_SCAN)
        permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        permissions.add(Manifest.permission.POST_NOTIFICATIONS)
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val allGranted = results.values.all { it }
        if (!allGranted) {
            // İzinler verilmedi uyarısı
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
            
            // Dil Seçimi
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
