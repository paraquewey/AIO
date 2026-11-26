package com.example

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.MonitorHeart
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.example.presentation.components.SshHostKeyDialog
import com.example.presentation.configs.ConfigsScreen
import com.example.presentation.configs.ProfileEditorScreen
import com.example.presentation.dashboard.DashboardScreen
import com.example.presentation.diagnostics.DiagnosticsScreen
import com.example.presentation.navigation.AioRoute
import com.example.presentation.servers.ServersScreen
import com.example.presentation.settings.SettingsScreen
import com.example.presentation.state.MainViewModel
import com.example.ui.theme.AioTunnelTheme

private data class TopLevelDestination(
    val route: AioRoute,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val testTag: String
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AioTunnelAppRoot()
        }
    }
}

@Composable
fun AioTunnelAppRoot(viewModel: MainViewModel = viewModel()) {
    val context = LocalContext.current
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val connectionState by viewModel.connectionState.collectAsStateWithLifecycle()
    val statistics by viewModel.statistics.collectAsStateWithLifecycle()
    val hostKeyChallenge by viewModel.pendingHostKeyChallenge.collectAsStateWithLifecycle()
    val diagnosticResults by viewModel.diagnosticResults.collectAsStateWithLifecycle()
    val knownHosts by viewModel.knownHostKeys.collectAsStateWithLifecycle()
    val logs by viewModel.logs.collectAsStateWithLifecycle()
    val installedApps by viewModel.installedApps.collectAsStateWithLifecycle()
    val isMeasuringLatency by viewModel.isMeasuringLatency.collectAsStateWithLifecycle()
    val userMessage by viewModel.userMessage.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    var pendingProfileIdForVpn by remember { mutableStateOf<String?>(null) }

    val vpnPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val profileId = pendingProfileIdForVpn
        pendingProfileIdForVpn = null
        if (result.resultCode == Activity.RESULT_OK && profileId != null) {
            viewModel.startVpnConnection(profileId)
        } else {
            viewModel.postMessage("Permiso VPN del sistema denegado por el usuario.")
        }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ ->
        // Foreground VPN service works regardless, though persistent status notification requires grant on API 33+
    }

    LaunchedEffect(userMessage) {
        userMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            viewModel.consumeMessage()
        }
    }

    val selectedProfile = remember(profiles, settings.selectedProfileId) {
        profiles.firstOrNull { it.id == settings.selectedProfileId } ?: profiles.firstOrNull()
    }

    val startOrStopVpn = {
        if (connectionState.isActiveOrTransitioning) {
            viewModel.stopVpnConnection()
        } else {
            val target = selectedProfile
            if (target == null) {
                viewModel.postMessage("Crea o importa un perfil antes de conectar.")
            } else {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    val granted = ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.POST_NOTIFICATIONS
                    ) == PackageManager.PERMISSION_GRANTED
                    if (!granted) {
                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
                val prepareIntent = VpnService.prepare(context)
                if (prepareIntent != null) {
                    pendingProfileIdForVpn = target.id
                    vpnPermissionLauncher.launch(prepareIntent)
                } else {
                    viewModel.startVpnConnection(target.id)
                }
            }
        }
    }

    val topDestinations = remember {
        listOf(
            TopLevelDestination(AioRoute.Home, "Inicio", Icons.Filled.Home, Icons.Outlined.Home, "nav_home"),
            TopLevelDestination(AioRoute.Servers, "Servidores", Icons.Filled.Dns, Icons.Outlined.Dns, "nav_servers"),
            TopLevelDestination(AioRoute.Configs, "Perfiles", Icons.Filled.Tune, Icons.Outlined.Tune, "nav_configs"),
            TopLevelDestination(AioRoute.Diagnostics, "Diagnóstico", Icons.Filled.MonitorHeart, Icons.Outlined.MonitorHeart, "nav_diagnostics"),
            TopLevelDestination(AioRoute.Settings, "Ajustes", Icons.Filled.Settings, Icons.Outlined.Settings, "nav_settings")
        )
    }

    AioTunnelTheme(useLightTheme = settings.useLightTheme) {
        val navController = rememberNavController()
        val backStackEntry by navController.currentBackStackEntryAsState()
        val currentDestination = backStackEntry?.destination
        val isEditorScreen = currentDestination?.hasRoute<AioRoute.ProfileEditor>() == true

        val navigateTopLevel: (AioRoute) -> Unit = { route ->
            navController.navigate(route) {
                popUpTo(navController.graph.findStartDestination().id) {
                    saveState = true
                }
                launchSingleTop = true
                restoreState = true
            }
        }

        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val useNavRail = maxWidth >= 600.dp

            Scaffold(
                modifier = Modifier.fillMaxSize(),
                containerColor = MaterialTheme.colorScheme.background,
                contentWindowInsets = WindowInsets.safeDrawing,
                snackbarHost = { SnackbarHost(snackbarHostState) },
                bottomBar = {
                    if (!useNavRail && !isEditorScreen) {
                        NavigationBar(
                            windowInsets = WindowInsets.navigationBars,
                            containerColor = MaterialTheme.colorScheme.surface
                        ) {
                            topDestinations.forEach { dest ->
                                val selected = currentDestination?.hasRoute(dest.route::class) == true
                                NavigationBarItem(
                                    selected = selected,
                                    onClick = { navigateTopLevel(dest.route) },
                                    icon = {
                                        Icon(
                                            imageVector = if (selected) dest.selectedIcon else dest.unselectedIcon,
                                            contentDescription = dest.label
                                        )
                                    },
                                    label = { Text(dest.label, maxLines = 1) },
                                    modifier = Modifier.testTag(dest.testTag)
                                )
                            }
                        }
                    }
                }
            ) { innerPadding ->
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                ) {
                    if (useNavRail && !isEditorScreen) {
                        NavigationRail(containerColor = MaterialTheme.colorScheme.surface) {
                            topDestinations.forEach { dest ->
                                val selected = currentDestination?.hasRoute(dest.route::class) == true
                                NavigationRailItem(
                                    selected = selected,
                                    onClick = { navigateTopLevel(dest.route) },
                                    icon = {
                                        Icon(
                                            imageVector = if (selected) dest.selectedIcon else dest.unselectedIcon,
                                            contentDescription = dest.label
                                        )
                                    },
                                    label = { Text(dest.label) },
                                    modifier = Modifier.testTag(dest.testTag)
                                )
                            }
                        }
                    }

                    Box(modifier = Modifier.weight(1f)) {
                        NavHost(
                            navController = navController,
                            startDestination = AioRoute.Home
                        ) {
                            composable<AioRoute.Home> {
                                DashboardScreen(
                                    connectionState = connectionState,
                                    selectedProfile = selectedProfile,
                                    statistics = statistics,
                                    onToggleConnection = startOrStopVpn,
                                    onOpenServers = { navigateTopLevel(AioRoute.Servers) },
                                    onOpenCreateConfig = { navController.navigate(AioRoute.ProfileEditor(null)) },
                                    onOpenDiagnostics = { navigateTopLevel(AioRoute.Diagnostics) },
                                    onDismissError = { viewModel.clearConnectionError() }
                                )
                            }
                            composable<AioRoute.Servers> {
                                ServersScreen(
                                    profiles = profiles,
                                    selectedProfileId = selectedProfile?.id,
                                    isMeasuringLatency = isMeasuringLatency,
                                    onSelectProfile = { id ->
                                        viewModel.selectProfile(id)
                                        navigateTopLevel(AioRoute.Home)
                                    },
                                    onToggleFavorite = { viewModel.toggleFavorite(it) },
                                    onMeasureAllLatency = { viewModel.measureAllServersLatency(autoSelectFastest = false) },
                                    onAutoSelectFastest = { viewModel.measureAllServersLatency(autoSelectFastest = true) },
                                    onAddProfile = { navController.navigate(AioRoute.ProfileEditor(null)) }
                                )
                            }
                            composable<AioRoute.Configs> {
                                ConfigsScreen(
                                    profiles = profiles,
                                    onCreateNew = { navController.navigate(AioRoute.ProfileEditor(null)) },
                                    onEditProfile = { id -> navController.navigate(AioRoute.ProfileEditor(id)) },
                                    onDuplicateProfile = { id -> viewModel.duplicateProfile(id) },
                                    onDeleteProfile = { id -> viewModel.deleteProfile(id) },
                                    onImportRawText = { text -> viewModel.importConfigurations(text) },
                                    onBuildExportJson = { ids, inc -> viewModel.buildExportJson(ids, inc) },
                                    onExportUri = { profile -> viewModel.exportProfileUri(profile) },
                                    onShowMessage = { msg -> viewModel.postMessage(msg) }
                                )
                            }
                            composable<AioRoute.Diagnostics> {
                                DiagnosticsScreen(
                                    checks = diagnosticResults,
                                    logs = logs,
                                    knownHosts = knownHosts,
                                    connectionState = connectionState,
                                    onRunDiagnostics = { viewModel.runFullDiagnostics(selectedProfile) },
                                    onClearLogs = { viewModel.clearLogs() },
                                    onRemoveKnownHost = { host, port -> viewModel.removeKnownHost(host, port) },
                                    onNotify = { viewModel.postMessage(it) }
                                )
                            }
                            composable<AioRoute.Settings> {
                                SettingsScreen(
                                    settings = settings,
                                    onUpdateSettings = { viewModel.updateSettings(it) },
                                    onClearKnownHosts = { viewModel.clearAllKnownHosts() }
                                )
                            }
                            composable<AioRoute.ProfileEditor> { entry ->
                                val args = entry.toRoute<AioRoute.ProfileEditor>()
                                val existing = profiles.firstOrNull { it.id == args.profileId }
                                val existingSecrets = remember(args.profileId) {
                                    args.profileId?.let { viewModel.loadSecretsForEditor(it) }
                                        ?: com.example.domain.models.ProfileSecrets()
                                }
                                ProfileEditorScreen(
                                    existingProfile = existing,
                                    existingSecrets = existingSecrets,
                                    appSettings = settings,
                                    installedApps = installedApps,
                                    onLoadInstalledApps = { viewModel.loadInstalledLaunchableApps() },
                                    onSave = { profile, secrets ->
                                        viewModel.saveProfile(profile, secrets)
                                        navController.popBackStack()
                                    },
                                    onBack = { navController.popBackStack() }
                                )
                            }
                        }
                    }
                }
            }

            hostKeyChallenge?.let { challenge ->
                SshHostKeyDialog(
                    challenge = challenge,
                    onTrust = { viewModel.resolveHostKeyChallenge(true) },
                    onReject = { viewModel.resolveHostKeyChallenge(false) }
                )
            }
        }
    }
}
