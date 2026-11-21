package com.example

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.example.model.AppLanguage
import com.example.model.VpnConnectionState
import com.example.ui.components.SmartImportDialog
import com.example.ui.screens.AppFilterScreen
import com.example.ui.screens.EditorScreen
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.ListsScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.screens.TestScreen
import com.example.ui.theme.GvpnTheme
import com.example.viewmodel.MainViewModel
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import java.util.Locale

@Serializable object HomeRoute
@Serializable data class EditorRoute(val profileId: String? = null)
@Serializable object SettingsRoute
@Serializable object AppFilterRoute
@Serializable object TestRoute
@Serializable object ListsRoute

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    setContent { GVpnApp() }
  }
}

@Composable
fun GVpnApp(vm: MainViewModel = viewModel()) {
  val profiles by vm.profiles.collectAsStateWithLifecycle()
  val activeProfile by vm.activeProfile.collectAsStateWithLifecycle()
  val activeProfileId by vm.activeProfileId.collectAsStateWithLifecycle()
  val settings by vm.settings.collectAsStateWithLifecycle()
  val vpnStats by vm.vpnStats.collectAsStateWithLifecycle()
  val adBlockRules by vm.adBlockRules.collectAsStateWithLifecycle()
  val diagnosticReport by vm.diagnosticReport.collectAsStateWithLifecycle()
  val installedApps by vm.installedApps.collectAsStateWithLifecycle()
  val isUpdatingBlocklist by vm.isUpdatingBlocklist.collectAsStateWithLifecycle()

  val baseContext = LocalContext.current
  val localizedContext =
    remember(baseContext, settings.language) { createLocalizedContext(baseContext, settings.language) }

  val snackbarHostState = remember { SnackbarHostState() }
  val scope = rememberCoroutineScope()
  var showImportDialog by remember { mutableStateOf(false) }

  LaunchedEffect(Unit) {
    vm.uiMessages.collect { msg ->
      snackbarHostState.showSnackbar(msg)
    }
  }

  val vpnConsentLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
      if (result.resultCode == android.app.Activity.RESULT_OK) {
        vm.connectActiveOrProfile()
      } else {
        scope.launch {
          snackbarHostState.showSnackbar(localizedContext.getString(R.string.vpn_permission_denied))
        }
      }
    }

  val notificationPermLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
      val consentIntent = vm.prepareVpnIntent()
      if (consentIntent != null) {
        vpnConsentLauncher.launch(consentIntent)
      } else {
        vm.connectActiveOrProfile()
      }
    }

  val filePickerLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
      if (uri != null) {
        val text =
          runCatching {
              baseContext.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            }
            .getOrNull()
        if (!text.isNullOrBlank()) {
          vm.importFromRawText(text) { res ->
            scope.launch {
              val msg =
                when {
                  res.imported.size == 1 -> {
                    val p = res.imported.first().profile
                    localizedContext.getString(R.string.import_success_single, p.displayTitle(), p.protocol.label)
                  }
                  res.imported.size > 1 ->
                    localizedContext.getString(R.string.import_success_batch, res.imported.size)
                  else ->
                    localizedContext.getString(
                      R.string.import_failed_prefix,
                      res.errors.firstOrNull() ?: "Invalid file",
                    )
                }
              snackbarHostState.showSnackbar(msg)
            }
          }
        }
      }
    }

  fun startOrStopVpn() {
    when (vpnStats.state) {
      VpnConnectionState.CONNECTED,
      VpnConnectionState.CONNECTING -> vm.disconnectVpn()
      VpnConnectionState.DISCONNECTED,
      VpnConnectionState.ERROR -> {
        if (activeProfile == null) {
          scope.launch {
            snackbarHostState.showSnackbar(localizedContext.getString(R.string.home_no_profile_selected))
          }
          return
        }
        if (
          Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(baseContext, Manifest.permission.POST_NOTIFICATIONS) !=
              PackageManager.PERMISSION_GRANTED
        ) {
          notificationPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
          return
        }
        val consentIntent = vm.prepareVpnIntent()
        if (consentIntent != null) {
          vpnConsentLauncher.launch(consentIntent)
        } else {
          vm.connectActiveOrProfile()
        }
      }
    }
  }

  CompositionLocalProvider(LocalContext provides localizedContext) {
    GvpnTheme(themeMode = settings.themeMode, accentPreset = settings.accentPreset) {
      val navController = rememberNavController()
      val animMs = if (settings.animationsEnabled) 220 else 0

      Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
      ) { _ ->
        Box(modifier = Modifier.fillMaxSize()) {
          NavHost(
            navController = navController,
            startDestination = HomeRoute,
            enterTransition = {
              fadeIn(tween(animMs)) +
                slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Start, tween(animMs))
            },
            exitTransition = { fadeOut(tween(animMs)) },
            popEnterTransition = { fadeIn(tween(animMs)) },
            popExitTransition = {
              fadeOut(tween(animMs)) +
                slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.End, tween(animMs))
            },
          ) {
            composable<HomeRoute> {
              HomeScreen(
                stats = vpnStats,
                activeProfile = activeProfile,
                allProfiles = profiles,
                settings = settings,
                onToggleConnection = { startOrStopVpn() },
                onSelectProfile = { id -> vm.selectActiveProfile(id) },
                onToggleAdBlock = { vm.toggleAdBlockQuick() },
                onOpenImport = { showImportDialog = true },
                onOpenEditor = { profileId -> navController.navigate(EditorRoute(profileId)) },
                onOpenSettings = { navController.navigate(SettingsRoute) },
                onOpenTest = { navController.navigate(TestRoute) },
                onOpenLists = { navController.navigate(ListsRoute) },
              )
            }

            composable<EditorRoute> { backStackEntry ->
              val route = backStackEntry.toRoute<EditorRoute>()
              EditorScreen(
                profileId = route.profileId,
                isExistingProfile = route.profileId != null,
                loadProfile = { id -> vm.loadProfileForEditor(id) },
                onSave = { p, s, selectActive ->
                  vm.saveProfile(p, s, selectActive) {
                    navController.popBackStack()
                  }
                },
                onSaveAndConnect = { p, s ->
                  vm.saveProfile(p, s, true) { saved ->
                    navController.popBackStack(HomeRoute, inclusive = false)
                    val consent = vm.prepareVpnIntent()
                    if (consent != null) vpnConsentLauncher.launch(consent)
                    else vm.connectActiveOrProfile(saved.id)
                  }
                },
                onSaveAndTest = { p, s ->
                  vm.saveProfile(p, s, true) { saved ->
                    navController.navigate(TestRoute)
                    vm.runDiagnostics(saved.id)
                  }
                },
                onDuplicate = { id -> vm.duplicateProfile(id) },
                onDelete = { id -> vm.deleteProfile(id) },
                onExportUri = { p -> vm.exportProfileToClipboard(p) },
                onOpenImportDialog = { showImportDialog = true },
                onBack = { navController.popBackStack() },
              )
            }

            composable<SettingsRoute> {
              SettingsScreen(
                settings = settings,
                stats = vpnStats,
                adBlockRules = adBlockRules,
                isUpdatingBlocklist = isUpdatingBlocklist,
                onUpdateSettings = { transform -> vm.updateSettings(transform) },
                onUpdateBlocklists = { vm.updateRemoteBlocklists() },
                onOpenAppFilter = { navController.navigate(AppFilterRoute) },
                onWipeAllData = { vm.wipeAllData() },
                onBack = { navController.popBackStack() },
              )
            }

            composable<AppFilterRoute> {
              AppFilterScreen(
                apps = installedApps,
                settings = settings,
                onLoadApps = { vm.loadInstalledApps() },
                onTogglePackage = { pkg ->
                  vm.updateSettings { cur ->
                    val next =
                      if (pkg in cur.selectedAppPackages) cur.selectedAppPackages - pkg
                      else cur.selectedAppPackages + pkg
                    cur.copy(selectedAppPackages = next)
                  }
                },
                onBack = { navController.popBackStack() },
              )
            }

            composable<TestRoute> {
              TestScreen(
                activeProfile = activeProfile,
                report = diagnosticReport,
                onRunDiagnostics = { vm.runDiagnostics() },
                onBack = { navController.popBackStack() },
              )
            }

            composable<ListsRoute> {
              ListsScreen(
                profiles = profiles,
                activeProfileId = activeProfileId ?: activeProfile?.id,
                adBlockRules = adBlockRules,
                onSelectActiveProfile = { id -> vm.selectActiveProfile(id) },
                onToggleFavorite = { id -> vm.toggleFavorite(id) },
                onEditProfile = { id -> navController.navigate(EditorRoute(id)) },
                onCreateProfile = { navController.navigate(EditorRoute(null)) },
                onDuplicateProfile = { id -> vm.duplicateProfile(id) },
                onExportProfile = { p -> vm.exportProfileToClipboard(p) },
                onDeleteProfile = { id -> vm.deleteProfile(id) },
                onOpenImportDialog = { showImportDialog = true },
                onAddDomainRule = { domain, cat -> vm.addDomainRule(domain, cat) },
                onToggleDomainRule = { rule -> vm.toggleDomainRule(rule) },
                onDeleteDomainRule = { id -> vm.deleteDomainRule(id) },
                onBack = { navController.popBackStack() },
              )
            }
          }

          if (showImportDialog) {
            SmartImportDialog(
              onDismiss = { showImportDialog = false },
              onPasteClipboard = { vm.readClipboardText() },
              onPickFile = {
                filePickerLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
              },
              onImportText = { raw ->
                vm.importFromRawText(raw) { res ->
                  showImportDialog = false
                  scope.launch {
                    val msg =
                      when {
                        res.imported.size == 1 && res.errors.isEmpty() -> {
                          val p = res.imported.first().profile
                          localizedContext.getString(
                            R.string.import_success_single,
                            p.displayTitle(),
                            p.protocol.label,
                          )
                        }
                        res.imported.isNotEmpty() -> {
                          val base =
                            localizedContext.getString(R.string.import_success_batch, res.imported.size)
                          if (res.errors.isNotEmpty()) "$base (${res.errors.size} errors)" else base
                        }
                        else ->
                          localizedContext.getString(
                            R.string.import_failed_prefix,
                            res.errors.firstOrNull() ?: "Invalid link",
                          )
                      }
                    snackbarHostState.showSnackbar(msg)
                  }
                }
              },
            )
          }
        }
      }
    }
  }
}

private fun createLocalizedContext(base: Context, lang: AppLanguage): Context {
  if (lang == AppLanguage.SYSTEM) return base
  val locale = Locale.forLanguageTag(lang.tag)
  val config = Configuration(base.resources.configuration)
  config.setLocale(locale)
  return base.createConfigurationContext(config)
}
