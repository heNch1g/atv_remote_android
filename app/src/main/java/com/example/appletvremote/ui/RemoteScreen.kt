package com.example.appletvremote.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Backspace
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.appletvremote.PadMode
import com.example.appletvremote.R
import com.example.appletvremote.ThemeMode
import com.example.appletvremote.UiState
import com.example.appletvremote.positionNow
import com.example.appletvremote.protocol.companion.Button as RemoteButton
import com.example.appletvremote.protocol.companion.TouchPhase
import com.example.appletvremote.protocol.discovery.AppleTvDevice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable
fun RemoteScreen(
    state: UiState,
    device: AppleTvDevice,
    onBack: () -> Unit,
    onPress: (RemoteButton) -> Unit,
    onSelect: () -> Unit,
    onHoldHome: () -> Unit,
    onHoldSelect: () -> Unit,
    onSleep: () -> Unit,
    onWakeDevice: () -> Unit,
    onVolumeUp: () -> Unit,
    onVolumeDown: () -> Unit,
    onToggleTextPanel: () -> Unit,
    onCloseTextPanel: () -> Unit,
    onTextDraftChange: (String) -> Unit,
    onSendText: () -> Unit,
    onClearText: () -> Unit,
    onDismissError: () -> Unit,
    onDismissNotice: () -> Unit,
    onSetPadMode: (PadMode) -> Unit,
    onTouch: (Int, Int, TouchPhase) -> Unit,
    onSetVolumeControls: (Boolean) -> Unit,
    onSetSwipeGuard: (Boolean) -> Unit,
    onSetThemeMode: (ThemeMode) -> Unit,
    onSetPlayerBackground: (Boolean) -> Unit,
    onPairPlayer: () -> Unit,
    onShowPlayer: () -> Unit,
) {
    var showSettings by remember { mutableStateOf(false) }
    val haptics = rememberHaptics()

    /**
     * Every key is a physical button on the real remote, so every key gets a
     * click; holds are wrapped separately because they must feel different.
     */
    fun tap(action: () -> Unit): () -> Unit = {
        haptics.tap()
        action()
    }

    fun hold(action: () -> Unit): () -> Unit = {
        haptics.longPress()
        action()
    }

    // System back closes the on-screen keyboard panel before leaving the remote.
    BackHandler(enabled = state.textPanelOpen) { onCloseTextPanel() }

    BoxWithConstraints(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter,
    ) {
        val compact = maxHeight < 720.dp
        val sectionGap = if (compact) 8.dp else 12.dp
        val keySize = if (compact) 52.dp else 62.dp
        // The now-playing card is drawn only while the TV reports something:
        // an idle Apple TV should leave the gap empty instead of showing a
        // placeholder.
        val nowPlaying = state.nowPlaying?.takeIf { it.isActive }
        // Everything except the pad and the round keys.
        val chrome = 50.dp + 40.dp + 46.dp + 50.dp + 4.dp + sectionGap * 4
        // Keep a little room for status cards and never exceed a
        // comfortable thumb-sized pad.
        val padHeight = (
            maxHeight - chrome - (keySize + 14.dp) - 40.dp
            ).coerceIn(150.dp, 280.dp)
        // On very short screens (typically landscape) the controls no
        // longer fit; fall back to a scrollable column.
        val scrollable = maxHeight < 480.dp

        val messages: @Composable () -> Unit = {
            state.error?.let { message ->
                MessageCard(
                    message = message,
                    isError = true,
                    onDismiss = onDismissError,
                )
                Spacer(Modifier.height(6.dp))
            }
            state.notice?.let { message ->
                MessageCard(
                    message = message,
                    isError = false,
                    onDismiss = onDismissNotice,
                )
                Spacer(Modifier.height(6.dp))
            }
        }

        val controls: @Composable () -> Unit = {
            ModeSwitch(
                mode = state.padMode,
                onMode = { mode -> haptics.tap(); onSetPadMode(mode) },
            )

            Spacer(Modifier.height(sectionGap))

            // The pad keeps its own width; centring it here stops the
            // circular D-pad from hugging the left edge of the column.
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                when (state.padMode) {
                    PadMode.BUTTONS -> DirectionPad(
                        size = padHeight,
                        onUp = tap { onPress(RemoteButton.UP) },
                        onDown = tap { onPress(RemoteButton.DOWN) },
                        onLeft = tap { onPress(RemoteButton.LEFT) },
                        onRight = tap { onPress(RemoteButton.RIGHT) },
                        onSelect = tap { onSelect() },
                        onSelectLongPress = hold { onHoldSelect() },
                    )

                    PadMode.TOUCHPAD -> Touchpad(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(padHeight),
                        swipeGuard = state.swipeGuard,
                        onTouch = onTouch,
                        onSelect = onSelect,
                        onHoldSelect = onHoldSelect,
                    )
                }
            }

            Spacer(Modifier.height(sectionGap))

            KeyRow(
                keySize = keySize,
                onMenu = tap { onPress(RemoteButton.MENU) },
                onHome = tap { onPress(RemoteButton.HOME) },
                onHoldHome = hold { onHoldHome() },
                onPlayPause = tap { onPress(RemoteButton.PLAY_PAUSE) },
            )

            Spacer(Modifier.height(sectionGap))

            if (state.volumeControls) {
                VolumeBar(
                    available = state.volumeAvailable,
                    volume = state.volume,
                    onUp = tap { onVolumeUp() },
                    onDown = tap { onVolumeDown() },
                )

                Spacer(Modifier.height(if (compact) 6.dp else 8.dp))
            }

            UtilityBar(
                connected = state.connected,
                busy = state.busy,
                textPanelOpen = state.textPanelOpen,
                playerAvailable = state.nowPlaying?.isActive == true ||
                    state.playerBackground ||
                    !state.airplayPaired,
                onKeyboard = tap { onToggleTextPanel() },
                onShowPlayer = tap { onShowPlayer() },
                onPower = tap { if (state.connected) onSleep() else onWakeDevice() },
            )

            Spacer(Modifier.height(4.dp))
        }

        val columnBase = Modifier
            .widthIn(max = 440.dp)
            .fillMaxWidth()
            .fillMaxHeight()
            .padding(horizontal = 18.dp)

        if (scrollable) {
            Column(
                modifier = columnBase.verticalScroll(rememberScrollState()),
            ) {
                RemoteHeader(
                    deviceName = device.name,
                    connected = state.connected,
                    reconnecting = state.reconnecting,
                    onBack = onBack,
                    onSettings = { showSettings = true },
                )
                if (state.reconnecting) {
                    ReconnectBar()
                }
                Spacer(Modifier.height(12.dp))
                if (nowPlaying != null) {
                    NowPlayingPanel(state = state)
                    Spacer(Modifier.height(12.dp))
                }
                messages()
                controls()
            }
        } else {
            Column(modifier = columnBase) {
                RemoteHeader(
                    deviceName = device.name,
                    connected = state.connected,
                    reconnecting = state.reconnecting,
                    onBack = onBack,
                    onSettings = { showSettings = true },
                )

                if (state.reconnecting) {
                    ReconnectBar()
                }

                // Messages live in the flexible gap so they never push
                // the controls away from the bottom edge. The now-playing
                // card sits at the top of the same gap, right under the
                // header, which is the empty area the pad leaves free.
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                ) {
                    if (nowPlaying != null) {
                        Spacer(Modifier.height(12.dp))
                        NowPlayingPanel(state = state)
                    }
                    Spacer(Modifier.weight(1f))
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState()),
                    ) {
                        messages()
                    }
                }

                controls()
            }
        }

        if (state.textPanelOpen) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .imePadding()
                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.6f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onCloseTextPanel,
                    ),
                contentAlignment = Alignment.BottomCenter,
            ) {
                TextInputPanel(
                    state = state,
                    onDraftChange = onTextDraftChange,
                    onSend = onSendText,
                    onClear = onClearText,
                    onClose = onCloseTextPanel,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
    }

    if (showSettings) {
        AlertDialog(
            onDismissRequest = { showSettings = false },
            title = { Text(stringResource(R.string.settings_title)) },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    SettingThemeRow(
                        theme = state.themeMode,
                        onTheme = onSetThemeMode,
                    )
                    Spacer(Modifier.height(16.dp))
                    SettingSwitchRow(
                        title = stringResource(R.string.setting_swipe_guard),
                        subtitle = stringResource(R.string.setting_swipe_guard_hint),
                        checked = state.swipeGuard,
                        onCheckedChange = onSetSwipeGuard,
                    )
                    Spacer(Modifier.height(16.dp))
                    SettingSwitchRow(
                        title = stringResource(R.string.setting_volume),
                        subtitle = stringResource(R.string.setting_volume_hint),
                        checked = state.volumeControls,
                        onCheckedChange = onSetVolumeControls,
                    )
                    Spacer(Modifier.height(16.dp))
                    SettingSwitchRow(
                        title = stringResource(R.string.setting_player_background),
                        subtitle = stringResource(R.string.setting_player_background_hint),
                        checked = state.playerBackground,
                        onCheckedChange = onSetPlayerBackground,
                    )
                    Spacer(Modifier.height(16.dp))
                    SettingPlayerRow(
                        paired = state.airplayPaired,
                        busy = state.busy,
                        error = state.nowPlayingError,
                        onPair = onPairPlayer,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showSettings = false }) {
                    Text(stringResource(R.string.done))
                }
            },
        )
    }
}

@Composable
private fun SettingSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(10.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/**
 * Palette picker: a compact M3 segmented control, the same component family
 * used for the pad switch. The checkmark icon is suppressed so the row stays
 * narrow inside the dialog.
 */
@Composable
private fun SettingThemeRow(
    theme: ThemeMode,
    onTheme: (ThemeMode) -> Unit,
) {
    val options = listOf(
        ThemeMode.AUTO to R.string.theme_auto,
        ThemeMode.DARK to R.string.theme_dark,
        ThemeMode.LIGHT to R.string.theme_light,
    )
    Column {
        Text(
            text = stringResource(R.string.setting_theme),
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            text = stringResource(R.string.setting_theme_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            options.forEachIndexed { index, (option, label) ->
                SegmentedButton(
                    selected = theme == option,
                    onClick = { onTheme(option) },
                    shape = SegmentedButtonDefaults.itemShape(index, options.size),
                    modifier = Modifier
                        .weight(1f)
                        .height(40.dp),
                    icon = {},
                ) {
                    Text(
                        text = stringResource(label),
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/**
 * The media notification needs a second AirPlay pairing with its own PIN.
 * Until it exists the player can still be paused and skipped through
 * Companion Link; only the progress bar depends on this pairing.
 *
 * Bringing the shade card back is no longer a setting: it lives in the
 * utility row of the remote as the player key.
 */
@Composable
private fun SettingPlayerRow(
    paired: Boolean,
    busy: Boolean,
    error: String?,
    onPair: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.setting_player),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = when {
                    // A tunnel failure after pairing matters more than the
                    // optimistic "connected" line: it explains missing progress.
                    error != null -> error
                    paired -> stringResource(R.string.setting_player_connected)
                    else -> stringResource(R.string.setting_player_hint)
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (error != null) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        if (!paired) {
            Spacer(Modifier.width(10.dp))
            TextButton(onClick = onPair, enabled = !busy) {
                Text(
                    stringResource(
                        if (busy) R.string.setting_player_working
                        else R.string.setting_player_pair,
                    ),
                )
            }
        }
    }
}

/**
 * Now-playing card for the free space under the header: the same title,
 * artist and scrubber the media card shows in the shade.
 *
 * The Apple TV reports the playhead only occasionally, so while playback runs
 * the card re-reads [UiState.positionNow] twice a second. Without the ticker
 * the bar would stand still between those reports.
 */
@Composable
private fun NowPlayingPanel(
    state: UiState,
    modifier: Modifier = Modifier,
) {
    val playing = state.nowPlaying?.takeIf { it.isActive } ?: return
    var tick by remember { mutableStateOf(0L) }
    LaunchedEffect(state.playheadAdvancing) {
        while (state.playheadAdvancing) {
            tick = SystemClock.elapsedRealtime()
            delay(500)
        }
    }
    val position = remember(
        tick,
        state.playhead,
        state.playheadAt,
        state.playheadAdvancing,
    ) {
        state.positionNow()
    }
    val total = playing.duration?.takeIf { it.isFinite() && it > 0 }
    val fraction = if (position != null && total != null) {
        (position / total).coerceIn(0.0, 1.0).toFloat()
    } else {
        null
    }
    val title = playing.title ?: playing.artist ?: playing.appName
        ?: stringResource(R.string.nothing_playing)
    val subtitle = listOfNotNull(
        playing.title?.let { playing.artist },
        playing.album?.takeIf { it != playing.title },
        playing.appName,
    ).distinct().joinToString(" · ")

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Rounded.PlayCircle,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.notif_channel_nowplaying),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Artwork is optional: without the AirPlay pairing, or for a
                // live stream, the TV may report none. The slot disappears in
                // that case instead of leaving an empty tile.
                if (playing.artwork != null) {
                    AlbumArt(
                        artwork = playing.artwork,
                        modifier = Modifier.size(56.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (subtitle.isNotEmpty()) {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            if (fraction != null && total != null && position != null) {
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(CircleShape),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                )
                Spacer(Modifier.height(4.dp))
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = formatPlaybackTime(position),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = formatPlaybackTime(total),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * Artwork from the MRP tunnel, decoded off the main thread.
 *
 * Decoding is keyed on the byte array, so a metadata refresh carrying the same
 * artwork does not decode it again. Until the bitmap is ready, and if the
 * bytes turn out not to be an image, a neutral tile holds the space so the
 * text does not jump sideways.
 */
@Composable
private fun AlbumArt(
    artwork: ByteArray,
    modifier: Modifier = Modifier,
) {
    var bitmap by remember(artwork) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(artwork) {
        bitmap = withContext(Dispatchers.Default) {
            runCatching { BitmapFactory.decodeByteArray(artwork, 0, artwork.size) }
                .getOrNull()
        }
    }
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier = modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        val decoded = bitmap
        if (decoded != null) {
            Image(
                bitmap = decoded.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                imageVector = Icons.Rounded.PlayCircle,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** mm:ss for short items, h:mm:ss once a film runs past an hour. */
private fun formatPlaybackTime(seconds: Double): String {
    val whole = seconds.toLong().coerceAtLeast(0L)
    val hours = whole / 3600
    val minutes = (whole % 3600) / 60
    val secs = whole % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, secs)
    } else {
        "%d:%02d".format(minutes, secs)
    }
}

// ---------------------------------------------------------------- header

@Composable
private fun ReconnectBar() {
    LinearProgressIndicator(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp)
            .height(3.dp)
            .clip(RoundedCornerShape(1.5.dp)),
        color = MaterialTheme.colorScheme.primary,
        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
    )
}

@Composable
private fun RemoteHeader(
    deviceName: String,
    connected: Boolean,
    reconnecting: Boolean,
    onBack: () -> Unit,
    onSettings: () -> Unit,
) {
    val statusColor = when {
        reconnecting -> MaterialTheme.colorScheme.tertiary
        connected -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.error
    }
    val statusText = when {
        reconnecting -> stringResource(R.string.reconnecting)
        connected -> stringResource(R.string.connected)
        else -> stringResource(R.string.disconnected)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack, modifier = Modifier.size(40.dp)) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = stringResource(R.string.back),
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }

        Spacer(Modifier.width(4.dp))

        Column(Modifier.weight(1f)) {
            Text(
                text = deviceName,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(statusColor),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }

        IconButton(onClick = onSettings, modifier = Modifier.size(40.dp)) {
            Icon(
                imageVector = Icons.Rounded.Settings,
                contentDescription = stringResource(R.string.settings),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(4.dp))
    }
}

// ------------------------------------------------------------ mode switch

@Composable
private fun ModeSwitch(mode: PadMode, onMode: (PadMode) -> Unit) {
    val options = listOf(
        PadMode.BUTTONS to stringResource(R.string.pad_mode_buttons),
        PadMode.TOUCHPAD to stringResource(R.string.pad_mode_touchpad),
    )
    // Google's segmented button: a single filled segment, the rest outlined.
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, (option, label) ->
            SegmentedButton(
                selected = mode == option,
                onClick = { onMode(option) },
                shape = SegmentedButtonDefaults.itemShape(index, options.size),
                modifier = Modifier
                    .weight(1f)
                    .height(46.dp),
                icon = {},
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                )
            }
        }
    }
}

// ------------------------------------------------------------- direction pad

@Composable
private fun DirectionPad(
    size: Dp,
    onUp: () -> Unit,
    onDown: () -> Unit,
    onLeft: () -> Unit,
    onRight: () -> Unit,
    onSelect: () -> Unit,
    onSelectLongPress: () -> Unit,
) {
    Surface(
        modifier = Modifier.size(size),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            DirectionButton(
                icon = Icons.Rounded.KeyboardArrowUp,
                description = stringResource(R.string.up),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 8.dp),
                onClick = onUp,
            )
            DirectionButton(
                icon = Icons.Rounded.KeyboardArrowDown,
                description = stringResource(R.string.down),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 8.dp),
                onClick = onDown,
            )
            DirectionButton(
                icon = Icons.AutoMirrored.Rounded.KeyboardArrowLeft,
                description = stringResource(R.string.left),
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 8.dp),
                onClick = onLeft,
            )
            DirectionButton(
                icon = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                description = stringResource(R.string.right),
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 8.dp),
                onClick = onRight,
            )

            RoundKey(
                onClick = onSelect,
                onLongClick = onSelectLongPress,
                modifier = Modifier.size(size * 0.36f),
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ) {
                Text(
                    text = stringResource(R.string.select),
                    color = MaterialTheme.colorScheme.onPrimary,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun DirectionButton(
    icon: ImageVector,
    description: String,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    FilledTonalIconButton(
        onClick = onClick,
        modifier = modifier.size(56.dp),
        shape = CircleShape,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            modifier = Modifier.size(30.dp),
        )
    }
}

/**
 * A round M3 key with an optional hold.
 *
 * Material's icon buttons cannot report a long press, and the remote needs
 * one (hold OK / hold Home), so this is a tonal circle with the theme's ripple
 * borrowed from the default clickable indication.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RoundKey(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    containerColor: Color = MaterialTheme.colorScheme.secondaryContainer,
    contentColor: Color = MaterialTheme.colorScheme.onSecondaryContainer,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(
                if (enabled) containerColor
                else containerColor.copy(alpha = 0.38f),
            )
            .combinedClickable(
                enabled = enabled,
                onClick = onClick,
                onLongClick = onLongClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

// ---------------------------------------------------------------- round keys

@Composable
private fun KeyRow(
    keySize: Dp,
    onMenu: () -> Unit,
    onHome: () -> Unit,
    onHoldHome: () -> Unit,
    onPlayPause: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.Top,
    ) {
        CircleKey(
            glyph = KeyGlyph.BACK,
            label = stringResource(R.string.menu),
            size = keySize,
            onClick = onMenu,
        )
        CircleKey(
            glyph = KeyGlyph.TV,
            label = stringResource(R.string.home),
            size = keySize,
            onClick = onHome,
            onLongClick = onHoldHome,
        )
        CircleKey(
            glyph = KeyGlyph.PLAY_PAUSE,
            label = stringResource(R.string.play_pause),
            size = keySize,
            onClick = onPlayPause,
        )
    }
}

private enum class KeyGlyph { BACK, TV, PLAY_PAUSE }

@Composable
private fun CircleKey(
    glyph: KeyGlyph,
    label: String,
    size: Dp,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier.width(size + 26.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        RoundKey(
            onClick = onClick,
            onLongClick = onLongClick,
            modifier = Modifier.size(size),
        ) {
            RemoteGlyph(
                glyph = glyph,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(size * 0.40f),
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Original-remote glyphs: back triangle, TV, play/pause. */
@Composable
private fun RemoteGlyph(glyph: KeyGlyph, tint: Color, modifier: Modifier) {
    when (glyph) {
        KeyGlyph.TV -> Icon(
            imageVector = Icons.Rounded.Tv,
            contentDescription = null,
            tint = tint,
            modifier = modifier,
        )

        KeyGlyph.BACK -> Canvas(modifier) { drawBackGlyph(tint) }
        KeyGlyph.PLAY_PAUSE -> Canvas(modifier) { drawPlayPauseGlyph(tint) }
    }
}

private fun DrawScope.drawBackGlyph(color: Color) {
    val w = size.width
    val h = size.height
    val path = Path().apply {
        moveTo(w * 0.72f, h * 0.04f)
        lineTo(w * 0.12f, h * 0.50f)
        lineTo(w * 0.72f, h * 0.96f)
        close()
    }
    drawPath(path, color)
}

private fun DrawScope.drawPlayPauseGlyph(color: Color) {
    val w = size.width
    val h = size.height
    val top = h * 0.08f
    val barHeight = h - top * 2
    val barWidth = w * 0.14f
    val barRadius = CornerRadius(barWidth * 0.25f, barWidth * 0.25f)

    val play = Path().apply {
        moveTo(w * 0.04f, top)
        lineTo(w * 0.48f, h * 0.50f)
        lineTo(w * 0.04f, h - top)
        close()
    }
    drawPath(play, color)
    drawRoundRect(
        color = color,
        topLeft = Offset(w * 0.62f, top),
        size = Size(barWidth, barHeight),
        cornerRadius = barRadius,
    )
    drawRoundRect(
        color = color,
        topLeft = Offset(w * 0.84f, top),
        size = Size(barWidth, barHeight),
        cornerRadius = barRadius,
    )
}

// ----------------------------------------------------------------- volume

@Composable
private fun VolumeBar(
    available: Boolean?,
    volume: Double?,
    onUp: () -> Unit,
    onDown: () -> Unit,
) {
    val enabled = available != false
    val level = (if (enabled) (volume ?: 0.0) else 0.0).toFloat()

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilledTonalIconButton(
                onClick = onDown,
                enabled = enabled,
                modifier = Modifier.size(40.dp),
                shape = CircleShape,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Remove,
                    contentDescription = stringResource(R.string.volume_down),
                    modifier = Modifier.size(20.dp),
                )
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(
                            if (enabled) R.string.volume else R.string.volume_unavailable_short,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                    if (enabled && volume != null) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "${(volume * 100).toInt()}%",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { level },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(CircleShape),
                    color = if (enabled) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHighest
                    },
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                )
            }

            FilledTonalIconButton(
                onClick = onUp,
                enabled = enabled,
                modifier = Modifier.size(40.dp),
                shape = CircleShape,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Add,
                    contentDescription = stringResource(R.string.volume_up),
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

// --------------------------------------------------------------- utility

@Composable
private fun UtilityBar(
    connected: Boolean,
    busy: Boolean,
    textPanelOpen: Boolean,
    playerAvailable: Boolean,
    onKeyboard: () -> Unit,
    onShowPlayer: () -> Unit,
    onPower: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.Top,
    ) {
        UtilityKey(
            icon = Icons.Rounded.Keyboard,
            label = stringResource(R.string.keyboard),
            active = textPanelOpen,
            enabled = true,
            onClick = onKeyboard,
        )
        UtilityKey(
            icon = Icons.Rounded.PlayCircle,
            label = stringResource(R.string.player),
            active = false,
            enabled = connected && playerAvailable,
            onClick = onShowPlayer,
        )
        UtilityKey(
            icon = Icons.Rounded.PowerSettingsNew,
            label = stringResource(if (connected) R.string.sleep else R.string.wake),
            active = false,
            enabled = !busy,
            onClick = onPower,
        )
    }
}

@Composable
private fun UtilityKey(
    icon: ImageVector,
    label: String,
    active: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier.width(96.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // A toggle in Material style: the lit state is the primary container,
        // the idle state a plain surface tone.
        FilledTonalIconButton(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.size(44.dp),
            shape = CircleShape,
            colors = IconButtonDefaults.filledTonalIconButtonColors(
                containerColor = if (active) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHigh
                },
                contentColor = if (active) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            ),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                modifier = Modifier.size(20.dp),
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ------------------------------------------------------------- text input

@Composable
private fun TextInputPanel(
    state: UiState,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onClear: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {},
            ),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Rounded.Keyboard,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.text_input),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onClose) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = stringResource(R.string.close),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            state.fieldText?.let { current ->
                Text(
                    text = stringResource(R.string.text_on_screen, current),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }

            if (!state.textFieldFocused) {
                Text(
                    text = stringResource(R.string.text_no_field),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }

            OutlinedTextField(
                value = state.textDraft,
                onValueChange = onDraftChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp)
                    .focusRequester(focusRequester),
                placeholder = { Text(stringResource(R.string.text_placeholder)) },
                minLines = 2,
                maxLines = 4,
                shape = MaterialTheme.shapes.medium,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { onSend() }),
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilledTonalButton(
                    onClick = onClear,
                    enabled = state.textDraft.isNotEmpty() || state.fieldText != null,
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.Backspace,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.clear))
                }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = onSend,
                    enabled = state.textDraft.isNotEmpty(),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.Send,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.send))
                }
            }

            Text(
                text = stringResource(R.string.text_focus_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}
