package com.example.appletvremote

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import com.example.appletvremote.ui.AppleTvRemoteTheme
import com.example.appletvremote.ui.DeviceListScreen
import com.example.appletvremote.ui.PinScreen
import com.example.appletvremote.ui.RemoteScreen

class MainActivity : ComponentActivity() {

    private val vm: RemoteViewModel by viewModels()

    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    // isStatusBarContrastEnforced is deprecated on API 35 but still the only
    // way to switch off the bar scrim on Android 10–14.
    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Both bars are transparent over the app's own dark background, with
        // light icons. Without the explicit style, the system picks light icons
        // in light mode and paints a white nav bar under a black app.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10+ otherwise adds a translucent scrim to the bars,
            // which shows up as a lighter strip against the remote.
            window.isNavigationBarContrastEnforced = false
            window.isStatusBarContrastEnforced = false
        }

        requestNotificationPermissionIfNeeded()

        setContent {
            val uiState by vm.state.collectAsState()
            val darkTheme = when (uiState.themeMode) {
                ThemeMode.AUTO -> isSystemInDarkTheme()
                ThemeMode.DARK -> true
                ThemeMode.LIGHT -> false
            }
            // System bar icons must contrast with the active palette: light
            // icons over the dark surface, dark icons over the light one.
            SideEffect {
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !darkTheme
                    isAppearanceLightNavigationBars = !darkTheme
                }
            }
            AppleTvRemoteTheme(themeMode = uiState.themeMode) {
                // The gradient is built from the theme's own surface tones and
                // spans the whole window, system bars included. It fades from
                // the elevated surface into the plain background, so the bars
                // carry the app's colour instead of a flat black strip.
                val scheme = MaterialTheme.colorScheme
                // Text placed directly in a Box would default to black. Give
                // the whole tree the palette's content colour so headings and
                // device names stay readable in both palettes.
                CompositionLocalProvider(LocalContentColor provides scheme.onBackground) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    listOf(scheme.surfaceContainerLow, scheme.background),
                                ),
                            ),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .safeDrawingPadding(),
                        ) {
                            AppRoot(vm)
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Returning to the app is also the easiest way to summon the media
        // card back after the shade dropped it.
        vm.showPlayerNotification()
    }

    /**
     * The now-playing notification is posted by a foreground service. From
     * Android 13 the notification is hidden without this permission, which
     * would make the media controls look broken rather than merely absent.
     */
    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}

@Composable
private fun AppRoot(vm: RemoteViewModel) {
    val state by vm.state.collectAsState()

    when (val screen = state.screen) {
        Screen.DeviceList -> DeviceListScreen(
            state = state,
            onScan = vm::scan,
            onSelect = vm::select,
            onWake = vm::wakeDevice,
            onForget = vm::forget,
            onDismissError = vm::dismissError,
            onDismissNotice = vm::dismissNotice,
        )

        is Screen.PinEntry -> {
            BackHandler { vm.cancelPairing() }
            PinScreen(
                device = screen.device,
                forAirPlay = screen.forAirPlay,
                busy = state.busy,
                error = state.error,
                onSubmit = { pin -> vm.submitPin(screen.device, pin) },
                onCancel = vm::cancelPairing,
                onDismissError = vm::dismissError,
            )
        }

        is Screen.Remote -> {
            BackHandler { vm.disconnect() }
            RemoteScreen(
                state = state,
                device = screen.device,
                onBack = vm::disconnect,
                onPress = vm::press,
                onSelect = vm::select,
                onHoldHome = vm::holdHome,
                onHoldSelect = vm::holdSelect,
                onSleep = vm::sleep,
                onWakeDevice = { vm.wakeDevice(screen.device) },
                onVolumeUp = vm::volumeUp,
                onVolumeDown = vm::volumeDown,
                onToggleTextPanel = vm::toggleTextPanel,
                onCloseTextPanel = vm::closeTextPanel,
                onTextDraftChange = vm::setTextDraft,
                onSendText = vm::sendText,
                onClearText = vm::clearText,
                onDismissError = vm::dismissError,
                onDismissNotice = vm::dismissNotice,
                onSetPadMode = vm::setPadMode,
                onTouch = vm::touch,
                onSetVolumeControls = vm::setVolumeControls,
                onSetSwipeGuard = vm::setSwipeGuard,
                onSetThemeMode = vm::setThemeMode,
                onSetPlayerBackground = vm::setPlayerBackground,
                onPairPlayer = { vm.startAirPlayPairing(screen.device) },
                onShowPlayer = vm::showPlayerNotification,
            )
        }
    }
}
