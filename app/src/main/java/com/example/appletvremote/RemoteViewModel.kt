package com.example.appletvremote

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.appletvremote.protocol.Log
import com.example.appletvremote.protocol.airplay.AirPlayAuth
import com.example.appletvremote.protocol.airplay.AirPlayConnection
import com.example.appletvremote.protocol.airplay.Ap2Session
import com.example.appletvremote.protocol.companion.AppleTvRemote
import com.example.appletvremote.protocol.companion.Button
import com.example.appletvremote.protocol.companion.CompanionClient
import com.example.appletvremote.protocol.companion.MediaCapabilities
import com.example.appletvremote.protocol.companion.TouchPhase
import com.example.appletvremote.protocol.discovery.AppleTvDevice
import com.example.appletvremote.protocol.hap.Credentials
import com.example.appletvremote.protocol.hap.HapException
import com.example.appletvremote.protocol.mrp.NowPlaying
import com.example.appletvremote.protocol.mrp.PlaybackState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException

/** How the central pad behaves: discrete direction buttons or a touch surface. */
enum class PadMode { BUTTONS, TOUCHPAD }

/** Which palette the app should use: follow the system, always dark, always light. */
enum class ThemeMode { AUTO, DARK, LIGHT }

/** Where the user currently is in the connect/pair/control flow. */
sealed interface Screen {
    data object DeviceList : Screen

    /**
     * [forAirPlay] distinguishes the second, independent pairing that the
     * now-playing tunnel needs from the main remote pairing.
     */
    data class PinEntry(val device: AppleTvDevice, val forAirPlay: Boolean = false) : Screen
    data class Remote(val device: AppleTvDevice) : Screen
}

data class UiState(
    val screen: Screen = Screen.DeviceList,
    val devices: List<AppleTvDevice> = emptyList(),
    val pairedKeys: Set<String> = emptySet(),
    val scanning: Boolean = false,
    val busy: Boolean = false,
    val connected: Boolean = false,
    val reconnecting: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
    /** Device to offer a wake-and-retry for after a failed connect. */
    val wakeTarget: AppleTvDevice? = null,
    /** Whether the TV reports a focused text field. */
    val textFieldFocused: Boolean = false,
    /** Contents of the focused field on the TV, as last reported. */
    val fieldText: String? = null,
    val textPanelOpen: Boolean = false,
    val textDraft: String = "",
    /** Null until the device reports whether it can route volume. */
    val volumeAvailable: Boolean? = null,
    val volume: Double? = null,
    val padMode: PadMode = PadMode.TOUCHPAD,
    /** Whether the volume bar is shown on the remote. */
    val volumeControls: Boolean = true,
    /** Whether accidental reverse movement is filtered out of swipes. */
    val swipeGuard: Boolean = true,
    /** Palette requested in settings. */
    val themeMode: ThemeMode = ThemeMode.AUTO,
    /** What the Apple TV says is playing, from the separate AirPlay tunnel. */
    val nowPlaying: NowPlaying? = null,
    /** Playhead in seconds as of [playheadAt], or null if none is known. */
    val playhead: Double? = null,
    val playheadAt: Long = 0L,
    val playheadAdvancing: Boolean = false,
    val airplayPaired: Boolean = false,
    /** A failure of the optional now-playing tunnel, shown in settings. */
    val nowPlayingError: String? = null,
)

/**
 * The playhead right now, carried forward from the last anchor.
 *
 * The Apple TV reports a position occasionally, not every second, so the
 * scrubber has to run the clock itself between updates.
 */
fun UiState.positionNow(): Double? {
    val base = playhead ?: return null
    if (!playheadAdvancing) return base
    val since = (SystemClock.elapsedRealtime() - playheadAt) / 1000.0
    return (base + since).coerceAtMost(nowPlaying?.duration ?: Double.MAX_VALUE)
}

/**
 * Fold in a now-playing update, re-anchoring the playhead only when the device
 * reports a new one or playback starts or stops. Re-anchoring on every message
 * would let a metadata-only update — fresh artwork, say — rewind the scrubber
 * to a position sampled seconds ago.
 */
fun UiState.withNowPlaying(playing: NowPlaying?): UiState {
    if (playing == null) {
        return copy(nowPlaying = null, playhead = null, playheadAdvancing = false)
    }
    val advancing = playing.playbackState == PlaybackState.PLAYING
    val resampled = playing.elapsedTime != nowPlaying?.elapsedTime
    if (!resampled && advancing == playheadAdvancing) return copy(nowPlaying = playing)
    return copy(
        nowPlaying = playing,
        // Falling back to the reported position matters on the first update
        // that carries one: without it the anchor stays null, and the seek
        // the notification offers has nothing to seek from.
        playhead = if (resampled) playing.position?.first
        else positionNow() ?: playing.position?.first,
        playheadAt = SystemClock.elapsedRealtime(),
        playheadAdvancing = advancing,
    )
}

class RemoteViewModel(app: Application) : AndroidViewModel(app) {

    private val discovery = NsdDiscovery(app)
    private val store = CredentialStore(app)

    /** Sent during pairing; this is what the Apple TV lists us as. */
    private val deviceName = app.ownDeviceName()

    /** Network work outlives individual composables, so it gets its own scope. */
    private val netScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var remote: AppleTvRemote? = null
    private var currentDevice: AppleTvDevice? = null
    private var pairingClient: CompanionClient? = null
    private var pairingSession: CompanionClient.PairingSession? = null

    /** The separate AirPlay 2 tunnel that carries now-playing metadata. */
    private var ap2: Ap2Session? = null
    private var airplayConnection: AirPlayConnection? = null
    private var airplayPairing: AirPlayAuth.AirPlayPairing? = null

    /** Whether the media notification's service has been asked to run. */
    private var notificationRunning = false

    /** Serialises wake attempts so a burst of presses wakes the TV once. */
    private val wakeMutex = Mutex()

    /** Remembers the pad style between launches. */
    private val uiPrefs = app.getSharedPreferences("ui", Context.MODE_PRIVATE)

    /**
     * Touch samples are queued and written by a single worker: sending them
     * straight from the gesture callback would block the UI thread on socket
     * writes, while parallel sends would scramble the swipe path.
     */
    private val touchQueue = Channel<TouchSample>(capacity = Channel.UNLIMITED)

    /**
     * Bumped on every connection. A late disconnect callback from a replaced
     * remote must not mark the new connection as dead.
     */
    private var connectionGeneration = 0

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        Log.enabled = BuildConfig.WIRE_LOGGING
        val savedMode = uiPrefs.getString(KEY_PAD_MODE, null)
        val padMode = PadMode.entries.firstOrNull { it.name == savedMode } ?: PadMode.TOUCHPAD
        val volumeControls = uiPrefs.getBoolean(KEY_VOLUME_CONTROLS, true)
        val swipeGuard = uiPrefs.getBoolean(KEY_SWIPE_GUARD, true)
        val savedTheme = uiPrefs.getString(KEY_THEME_MODE, null)
        val themeMode = ThemeMode.entries.firstOrNull { it.name == savedTheme } ?: ThemeMode.AUTO

        // Reconnect to the Apple TV used last, without waiting for a scan.
        val lastDevice = store.loadLastDevice()
        val autoTarget = lastDevice?.takeIf { store.isPaired(it.credentialKey) }

        _state.update {
            it.copy(
                padMode = padMode,
                volumeControls = volumeControls,
                swipeGuard = swipeGuard,
                themeMode = themeMode,
                devices = listOfNotNull(autoTarget),
                pairedKeys = store.pairedKeys(),
            )
        }

        // The notification service cannot reach the Apple TV itself, so it is
        // handed a flat snapshot and sends commands back through the bridge.
        NowPlayingBridge.handleCommands(::handleRemoteCommand)
        viewModelScope.launch {
            _state.collect { syncNotification(it) }
        }

        netScope.launch {
            for (sample in touchQueue) {
                val r = remote ?: continue
                runCatching { r.touch(sample.x, sample.y, sample.phase) }
                    .onFailure { if (sample.phase == TouchPhase.RELEASE) reportFailure(it) }
            }
        }

        if (autoTarget != null) {
            // The Companion port may have rotated while the app was closed,
            // so a failed fast attempt falls back to mDNS rediscovery.
            connect(autoTarget, rediscoverOnFailure = true)
        } else {
            scan()
        }
    }

    // ----------------------------------------------------- media notification

    /**
     * Mirror the connection into the notification.
     *
     * It runs for as long as the remote screen is open rather than only while
     * something plays. Waiting for playback would mean the notification could
     * never appear unless the app happened to be in the foreground, and the
     * foreground service is itself what keeps the connections alive after the
     * screen is dismissed.
     */
    private fun syncNotification(state: UiState) {
        val snapshot = buildSnapshot(state)
        if (snapshot == null) {
            // A null snapshot is the service's cue to stop itself.
            if (notificationRunning) {
                notificationRunning = false
                NowPlayingBridge.publish(null)
            }
            return
        }

        NowPlayingBridge.publish(snapshot)

        if (!notificationRunning) {
            notificationRunning = true
            // Connecting always follows a tap, so this start is a foreground
            // one — but a race with the app being dismissed would otherwise
            // take the whole process down, and losing the notification is a
            // far better outcome than a crash.
            runCatching {
                ContextCompat.startForegroundService(
                    getApplication(),
                    Intent(getApplication(), NowPlayingService::class.java),
                )
            }.onFailure {
                notificationRunning = false
                android.util.Log.w("AppleTvRemote", "could not start media notification", it)
            }
        }
    }

    /**
     * Re-post the media card. Android 13+ lets the notification be folded
     * away even from a foreground service, and the card the shade builds from
     * it goes with it well before the connection is lost.
     */
    fun showPlayerNotification() {
        if (_state.value.screen !is Screen.Remote) return
        NowPlayingService.show(getApplication())
    }

    /** Flatten the state into what the shade should be told. */
    private fun buildSnapshot(state: UiState): NowPlayingSnapshot? {
        val device = (state.screen as? Screen.Remote)?.device ?: return null
        val playing = state.nowPlaying?.takeIf { it.isActive }
        return NowPlayingSnapshot(
            deviceName = device.name,
            title = playing?.title,
            artist = playing?.artist,
            album = playing?.album,
            artwork = playing?.artwork,
            playing = playing?.playbackState == PlaybackState.PLAYING,
            position = state.positionNow(),
            duration = playing?.position?.second,
        )
    }

    private fun handleRemoteCommand(command: RemoteCommand) {
        when (command) {
            RemoteCommand.PlayPause -> press(Button.PLAY_PAUSE)
            is RemoteCommand.Skip -> skip(command.seconds)
            is RemoteCommand.SeekTo -> seekTo(command.seconds)
        }
    }

    // ------------------------------------------------------------ discovery

    fun scan() {
        _state.update { it.copy(scanning = true) }

        // The last device connected to appears immediately, ahead of any
        // discovery answer; stored credentials make it tappable right away.
        store.loadLastDevice()?.let { last ->
            _state.update { s ->
                if (s.devices.any { it.credentialKey == last.credentialKey }) s
                else s.copy(devices = s.devices + last)
            }
        }

        viewModelScope.launch {
            val found = runCatching { discovery.scan(5000, ::onDeviceResolved) }
                .getOrDefault(emptyList())
            _state.update { s ->
                // A paired device that is asleep does not answer discovery;
                // keep the last known entry rather than dropping it.
                val last = store.loadLastDevice()
                val merged = if (last != null && found.none { it.credentialKey == last.credentialKey }) {
                    found + last
                } else {
                    found
                }
                s.copy(
                    scanning = false,
                    devices = merged,
                    pairedKeys = store.pairedKeys(),
                )
            }
        }
    }

    /** Merge a freshly resolved device into the list without duplicates. */
    private fun onDeviceResolved(device: AppleTvDevice) {
        _state.update { s ->
            if (s.devices.any { it.credentialKey == device.credentialKey }) s
            else s.copy(
                devices = s.devices + device,
                pairedKeys = if (store.isPaired(device.credentialKey)) {
                    s.pairedKeys + device.credentialKey
                } else {
                    s.pairedKeys
                },
            )
        }
    }

    // --------------------------------------------------------- pair/connect

    /** Connect if already paired, otherwise begin pair-setup. */
    fun select(device: AppleTvDevice) {
        if (store.load(device.credentialKey) != null) connect(device) else startPairing(device)
    }

    private fun startPairing(device: AppleTvDevice) {
        _state.update { it.copy(busy = true, error = null, notice = null) }
        netScope.launch {
            try {
                val client = CompanionClient(device.address, device.port, netScope)
                client.connect()
                pairingClient = client
                pairingSession = client.startPairing(deviceName)
                _state.update {
                    it.copy(busy = false, screen = Screen.PinEntry(device))
                }
            } catch (e: Exception) {
                closePairing()
                _state.update {
                    it.copy(
                        busy = false,
                        error = str(R.string.error_pairing_start, e.message ?: ""),
                    )
                }
            }
        }
    }

    fun submitPin(device: AppleTvDevice, pin: String) {
        if ((_state.value.screen as? Screen.PinEntry)?.forAirPlay == true) {
            submitAirPlayPin(device, pin)
            return
        }
        val session = pairingSession ?: return
        if (pin.length != 4) {
            _state.update { it.copy(error = str(R.string.error_pin_length)) }
            return
        }
        _state.update { it.copy(busy = true, error = null) }
        netScope.launch {
            try {
                val credentials = session.complete(pin)
                store.save(device.credentialKey, credentials)
                store.saveLastDevice(device)
                closePairing()
                _state.update { it.copy(busy = false, pairedKeys = store.pairedKeys()) }
                connect(device)
            } catch (e: Exception) {
                closePairing()
                _state.update {
                    it.copy(
                        busy = false,
                        screen = Screen.DeviceList,
                        error = if (e.message?.contains("Incorrect PIN") == true) {
                            str(R.string.error_wrong_pin)
                        } else {
                            str(R.string.error_pairing_failed, e.message ?: "")
                        },
                    )
                }
            }
        }
    }

    fun cancelPairing() {
        closePairing()
        closeAirPlayPairing()
        val screen = _state.value.screen
        // An AirPlay pairing is started from inside the remote, so cancelling
        // it belongs back there rather than on the device list.
        val back = if (screen is Screen.PinEntry && screen.forAirPlay) {
            Screen.Remote(screen.device)
        } else {
            Screen.DeviceList
        }
        _state.update { it.copy(screen = back, busy = false) }
    }

    private fun closePairing() {
        runCatching { pairingClient?.close() }
        pairingClient = null
        pairingSession = null
    }

    private fun closeAirPlayPairing() {
        runCatching { airplayConnection?.close() }
        airplayConnection = null
        airplayPairing = null
    }

    private fun connect(device: AppleTvDevice, rediscoverOnFailure: Boolean = false) {
        val credentials = store.load(device.credentialKey) ?: run {
            startPairing(device)
            return
        }
        _state.update { it.copy(busy = true, error = null, notice = null, wakeTarget = null) }
        netScope.launch {
            try {
                val (r, target) = establishWithRetry(device, credentials, rediscoverOnFailure)
                remote = r
                currentDevice = target
                store.saveLastDevice(target)
                _state.update {
                    it.copy(
                        busy = false,
                        connected = true,
                        reconnecting = false,
                        screen = Screen.Remote(target),
                        pairedKeys = store.pairedKeys(),
                        wakeTarget = null,
                        volume = null,
                        volumeAvailable = null,
                        airplayPaired = store.isAirPlayPaired(target.credentialKey),
                        nowPlayingError = null,
                    ).withNowPlaying(null)
                }
                // Now-playing has to come up on every connect, not only right
                // after its pairing, or it silently stops working on the next
                // app start.
                startNowPlaying(target)
            } catch (e: Exception) {
                remote = null
                // Pair-verify only fails when the stored credentials are
                // unusable, most often because the pairing was removed on the
                // Apple TV. Drop them so the next tap pairs afresh.
                val stale = e is HapException
                if (stale) store.forget(device.credentialKey)
                _state.update {
                    it.copy(
                        busy = false,
                        connected = false,
                        screen = Screen.DeviceList,
                        pairedKeys = store.pairedKeys(),
                        // A sleeping Apple TV is the usual reason for a plain
                        // timeout; offer the wake-and-retry path.
                        wakeTarget = if (stale) null else device,
                        error = if (stale) str(R.string.error_stale_pairing)
                        else str(R.string.error_connect, e.message ?: ""),
                    )
                }
                scan()
            }
        }
    }

    /**
     * Connect, and if the cached Companion port turns out to be stale,
     * rediscover the Apple TV once and retry. Pairing failures are never
     * retried: those need the user to pair again.
     */
    private suspend fun establishWithRetry(
        device: AppleTvDevice,
        credentials: Credentials,
        rediscover: Boolean,
    ): Pair<AppleTvRemote, AppleTvDevice> {
        return try {
            establish(device, credentials) to device
        } catch (e: Exception) {
            if (!rediscover || e is HapException) throw e
            val fresh = runCatching {
                discovery.scan(4000)
                    .firstOrNull { it.credentialKey == device.credentialKey }
            }.getOrNull() ?: throw e
            if (fresh.address == device.address && fresh.port == device.port) throw e
            establish(fresh, credentials) to fresh
        }
    }

    /**
     * Build and connect a remote, wiring up its callbacks. Shared by the
     * initial connect and by transparent reconnection.
     */
    private suspend fun establish(device: AppleTvDevice, credentials: Credentials): AppleTvRemote {
        val generation = ++connectionGeneration
        remote?.let { runCatching { it.close() } }

        val r = AppleTvRemote(device.address, device.port, credentials, netScope, deviceName)
        r.onDisconnect = {
            if (generation == connectionGeneration) {
                _state.update {
                    it.copy(
                        connected = false,
                        textFieldFocused = false,
                        fieldText = null,
                        textPanelOpen = false,
                    ).withNowPlaying(null)
                }
            }
        }
        // Mirror the first-party remote: when the Apple TV focuses a text
        // field, present the text panel; dismiss it when focus goes away.
        r.onTextFocus = { session ->
            if (generation == connectionGeneration) {
                _state.update {
                    if (session != null) {
                        it.copy(
                            textFieldFocused = true,
                            fieldText = session.textBeforeCursor,
                            textPanelOpen = true,
                        )
                    } else {
                        it.copy(
                            textFieldFocused = false,
                            fieldText = null,
                            textPanelOpen = false,
                        )
                    }
                }
            }
        }
        r.onCapabilities = { caps: MediaCapabilities ->
            if (generation == connectionGeneration) {
                _state.update { it.copy(volumeAvailable = caps.volume) }
                if (caps.volume) refreshVolume()
            }
        }
        try {
            r.connect()
        } catch (e: Exception) {
            runCatching { r.close() }
            throw e
        }
        return r
    }

    /**
     * An Apple TV drops its Companion connection when it sleeps or the network
     * blips. Rebuild the session and let the caller retry once.
     */
    private suspend fun reconnect(): AppleTvRemote? {
        val known = currentDevice ?: return null
        val credentials = store.load(known.credentialKey) ?: return null
        _state.update { it.copy(reconnecting = true) }
        return try {
            // The Apple TV rotates its Companion port, so the cached port is
            // often already stale. Rediscover first and fall back to what we
            // knew if the scan turns up nothing.
            val device = runCatching {
                discovery.scan(4000).firstOrNull { it.credentialKey == known.credentialKey }
            }.getOrNull() ?: known
            currentDevice = device

            val r = establish(device, credentials)
            // The user may have left the remote while rediscovery was running;
            // do not drag them back into a screen they dismissed.
            if (_state.value.screen !is Screen.Remote) {
                runCatching { r.close() }
                remote = null
                _state.update { it.copy(reconnecting = false) }
                return null
            }
            remote = r
            store.saveLastDevice(device)
            _state.update {
                it.copy(
                    reconnecting = false,
                    connected = true,
                    screen = Screen.Remote(device),
                    error = null,
                    airplayPaired = store.isAirPlayPaired(device.credentialKey),
                )
            }
            startNowPlaying(device)
            r
        } catch (e: HapException) {
            remote = null
            store.forget(known.credentialKey)
            _state.update {
                it.copy(
                    reconnecting = false,
                    connected = false,
                    screen = Screen.DeviceList,
                    pairedKeys = store.pairedKeys(),
                    error = str(R.string.error_stale_pairing),
                ).withNowPlaying(null)
            }
            scan()
            null
        } catch (e: Exception) {
            remote = null
            _state.update { it.copy(reconnecting = false) }
            null
        }
    }

    fun disconnect() {
        connectionGeneration++
        currentDevice = null
        netScope.launch {
            runCatching { remote?.close() }
            runCatching { ap2?.close() }
            remote = null
            ap2 = null
            _state.update {
                it.copy(
                    screen = Screen.DeviceList,
                    connected = false,
                    reconnecting = false,
                    busy = false,
                    textPanelOpen = false,
                    textFieldFocused = false,
                    fieldText = null,
                    textDraft = "",
                    volume = null,
                    volumeAvailable = null,
                    airplayPaired = false,
                    nowPlayingError = null,
                ).withNowPlaying(null)
            }
            // The list may be stale after a long remote session.
            scan()
        }
    }

    fun forget(device: AppleTvDevice) {
        store.forget(device.credentialKey)
        if (currentDevice?.credentialKey == device.credentialKey) {
            disconnect()
        }
        _state.update {
            it.copy(
                pairedKeys = store.pairedKeys(),
                notice = str(R.string.notice_forgotten),
            )
        }
    }

    // ------------------------------------------------------------- commands

    /**
     * Fire-and-forget command.
     *
     * A sleeping Apple TV is the common reason a command fails: the Companion
     * socket is gone, so [withRemote] tries a plain reconnect first. If the TV
     * is asleep that cannot work either — it answers only a bare WAKE press —
     * so the same button press doubles as the wake-up: wake the TV, wait for
     * its services, then replay the original command. That is what makes any
     * button bring the Apple TV out of sleep.
     */
    private fun command(
        wakeOnFailure: Boolean = true,
        block: suspend AppleTvRemote.() -> Unit,
    ) {
        netScope.launch {
            try {
                withRemote { target -> block(target) }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (wakeOnFailure && currentDevice != null && isConnectionLost(e)) {
                    if (wakeAndReconnect()) {
                        val revived = remote
                        if (revived != null) {
                            runCatching { block(revived) }
                                .onFailure(::reportFailure)
                            return@launch
                        }
                    }
                }
                reportFailure(e)
            }
        }
    }

    /**
     * Run [block] against the current remote. If the connection has died,
     * re-establish it and retry once before reporting anything to the user.
     */
    private suspend fun <T> withRemote(block: suspend (AppleTvRemote) -> T): T {
        val current = remote ?: throw IOException("not connected")
        return try {
            block(current)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            if (!isConnectionLost(e)) throw e
            // A socket that already knows it is closed cannot be dialled back
            // in; skip straight to the wake path, which is much faster.
            if (!current.isConnected) throw e
            val revived = reconnect() ?: throw e
            block(revived)
        }
    }

    /**
     * Wake a sleeping Apple TV and bring the Companion session back up.
     *
     * A bare session (connect, verify, one WAKE press) is all a sleeping device
     * answers, so this skips the full handshake first. Returns true when the
     * caller can retry its command; the serialisation means a burst of presses
     * wakes the TV once instead of opening a session per tap.
     */
    private suspend fun wakeAndReconnect(): Boolean = wakeMutex.withLock {
        // Another press may have revived the connection while this one waited.
        if (remote?.isConnected == true) return@withLock true

        val known = currentDevice ?: return@withLock false
        val credentials = store.load(known.credentialKey) ?: return@withLock false
        _state.update { it.copy(reconnecting = true) }

        val target = try {
            try {
                sendWake(known, credentials)
                known
            } catch (e: HapException) {
                throw e
            } catch (e: Exception) {
                // A sleeping Apple TV usually rotated its ephemeral port.
                val fresh = runCatching {
                    discovery.scan(4000)
                        .firstOrNull { it.credentialKey == known.credentialKey }
                }.getOrNull() ?: throw e
                sendWake(fresh, credentials)
                fresh
            }
        } catch (e: Exception) {
            _state.update { it.copy(reconnecting = false) }
            if (e is HapException) {
                store.forget(known.credentialKey)
                _state.update {
                    it.copy(
                        connected = false,
                        screen = Screen.DeviceList,
                        pairedKeys = store.pairedKeys(),
                        error = str(R.string.error_stale_pairing),
                    ).withNowPlaying(null)
                }
                scan()
            }
            return@withLock false
        }

        // Give tvOS a moment to bring its remote services back up.
        delay(2500)
        return@withLock try {
            val r = establishAfterWake(target, credentials)
            remote = r
            currentDevice = target
            store.saveLastDevice(target)
            _state.update {
                it.copy(
                    reconnecting = false,
                    connected = true,
                    error = null,
                    screen = Screen.Remote(target),
                    wakeTarget = null,
                    airplayPaired = store.isAirPlayPaired(target.credentialKey),
                )
            }
            startNowPlaying(target)
            true
        } catch (e: Exception) {
            _state.update { it.copy(reconnecting = false) }
            false
        }
    }

    /**
     * Connect right after a wake, when tvOS has only just started listening.
     *
     * The WAKE press is accepted before the remote service starts accepting
     * connections, so a single attempt frequently loses that race and shows an
     * error for a press that did in fact wake the TV. A few spaced attempts
     * absorb the gap; a rejected pairing is never retried.
     */
    private suspend fun establishAfterWake(
        device: AppleTvDevice,
        credentials: Credentials,
    ): AppleTvRemote {
        var last: Exception? = null
        repeat(WAKE_CONNECT_ATTEMPTS) { attempt ->
            try {
                return establish(device, credentials)
            } catch (e: HapException) {
                throw e
            } catch (e: Exception) {
                last = e
                // Do not add a pointless pause after the final attempt.
                if (attempt < WAKE_CONNECT_ATTEMPTS - 1) {
                    delay(WAKE_CONNECT_DELAY_MS * (attempt + 1))
                }
            }
        }
        throw last ?: IOException("Apple TV did not accept the connection")
    }

    private fun reportFailure(e: Throwable) {
        val message = if (isConnectionLost(e)) {
            str(R.string.error_lost_connection)
        } else {
            e.message ?: str(R.string.error_generic)
        }
        // Do not overwrite a more specific error that a failed reconnect has
        // already put on screen (for example a stale pairing), and stay quiet
        // if the user has already left the remote screen.
        _state.update { s ->
            if (s.screen !is Screen.Remote || s.error != null) s else s.copy(error = message)
        }
    }

    private fun isConnectionLost(e: Throwable): Boolean {
        val message = (e.message ?: "").lowercase()
        return e is IOException ||
            "broken pipe" in message ||
            "closed" in message ||
            "reset" in message ||
            "not connected" in message ||
            "timed out" in message
    }

    fun press(button: Button) = command { press(button) }

    /**
     * Tap on the touch surface or OK in button mode.
     *
     * A sleeping tvOS can keep the Companion socket open and swallow HID
     * events without raising an error, so the failed-command path never fires
     * and the tap does nothing. Sending WAKE first makes a tap open the TV;
     * an awake system simply ignores it. Swipes deliberately do not take this
     * path, so brushing the pad does not turn the TV on.
     */
    fun select() = command {
        runCatching { wake() }
        press(Button.SELECT)
    }

    // --------------------------------------------------------- pad controls

    fun setPadMode(mode: PadMode) {
        if (_state.value.padMode == mode) return
        uiPrefs.edit().putString(KEY_PAD_MODE, mode.name).apply()
        _state.update { it.copy(padMode = mode) }
    }

    /** Show or hide the volume bar, for setups where network volume does not work. */
    fun setVolumeControls(enabled: Boolean) {
        uiPrefs.edit().putBoolean(KEY_VOLUME_CONTROLS, enabled).apply()
        _state.update { it.copy(volumeControls = enabled) }
    }

    /** Filter accidental reverse movement out of touchpad swipes. */
    fun setSwipeGuard(enabled: Boolean) {
        uiPrefs.edit().putBoolean(KEY_SWIPE_GUARD, enabled).apply()
        _state.update { it.copy(swipeGuard = enabled) }
    }

    /** Choose the palette: follow the system, or force dark / light. */
    fun setThemeMode(mode: ThemeMode) {
        if (_state.value.themeMode == mode) return
        uiPrefs.edit().putString(KEY_THEME_MODE, mode.name).apply()
        _state.update { it.copy(themeMode = mode) }
    }

    /**
     * Queue one touch sample from the on-screen pad. Coordinates use the
     * protocol's 0..1000 space. A dead socket is only reported on release, so
     * a failed swipe does not spam the screen.
     */
    fun touch(x: Int, y: Int, phase: TouchPhase) {
        if (remote == null) return
        touchQueue.trySend(TouchSample(x, y, phase))
    }

    fun holdHome() = command { holdHome() }

    /** Hold OK, which is how tvOS opens a contextual menu. */
    fun holdSelect() = command {
        runCatching { wake() }
        buttonDown(Button.SELECT)
        delay(900)
        buttonUp(Button.SELECT)
    }

    fun sleep() = command(wakeOnFailure = false) { sleep() }

    fun volumeUp() = command {
        press(Button.VOLUME_UP)
        refreshVolume()
    }

    fun volumeDown() = command {
        press(Button.VOLUME_DOWN)
        refreshVolume()
    }

    private fun refreshVolume() {
        val r = remote ?: return
        netScope.launch {
            val value = runCatching { r.getVolume() }.getOrNull()
            _state.update { it.copy(volume = value) }
        }
    }

    /**
     * Nudge a sleeping Apple TV. A bare Companion session (connect, verify,
     * one WAKE press) is all a sleeping device will answer, so this skips the
     * full remote handshake, then retries the real connect once the TV has had
     * a moment to come up.
     */
    fun wakeDevice(device: AppleTvDevice) {
        val credentials = store.load(device.credentialKey) ?: return
        _state.update { it.copy(busy = true, error = null, notice = null, wakeTarget = null) }
        netScope.launch {
            // A sleeping Apple TV has usually rotated its ephemeral Companion
            // port by now, so the cached address:port often refuses the
            // connection. Try it once (fast path), then rediscover the device
            // by its stable rpMRtID and retry against the fresh port.
            val target = runCatching {
                try {
                    sendWake(device, credentials)
                    device
                } catch (e: HapException) {
                    throw e
                } catch (e: Exception) {
                    val fresh = runCatching {
                        discovery.scan(4000)
                            .firstOrNull { it.credentialKey == device.credentialKey }
                    }.getOrNull() ?: throw e
                    sendWake(fresh, credentials)
                    fresh
                }
            }
            when (val failure = target.exceptionOrNull()) {
                null -> {
                    _state.update {
                        it.copy(busy = false, notice = str(R.string.notice_wake_sent))
                    }
                    // Give tvOS a moment to bring its remote services back up.
                    delay(2500)
                    connect(target.getOrThrow())
                }

                is HapException -> {
                    store.forget(device.credentialKey)
                    _state.update {
                        it.copy(
                            busy = false,
                            pairedKeys = store.pairedKeys(),
                            error = str(R.string.error_stale_pairing),
                        )
                    }
                }

                else -> _state.update {
                    it.copy(busy = false, error = str(R.string.error_wake_failed))
                }
            }
        }
    }

    /** Bare Companion session: connect, verify, one WAKE press, hang up. */
    private suspend fun sendWake(device: AppleTvDevice, credentials: Credentials) {
        val client = CompanionClient(device.address, device.port, netScope)
        try {
            client.connect()
            client.authenticate(credentials)
            client.hidButton(Button.WAKE.code, down = false)
        } finally {
            runCatching { client.close() }
        }
    }

    // ---------------------------------------------------------- now playing

    /** Bring up the MRP tunnel if this device has AirPlay credentials. */
    private fun startNowPlaying(device: AppleTvDevice) {
        val credentials = store.loadAirPlay(device.credentialKey) ?: return
        netScope.launch {
            try {
                ap2?.close()
                _state.update { it.copy(nowPlayingError = null) }
                val session = Ap2Session(device.address, credentials, netScope, deviceName)
                session.onNowPlaying = { np ->
                    _state.update { it.withNowPlaying(np) }
                }
                session.onDisconnect = { _state.update { it.withNowPlaying(null) } }
                session.connect()
                ap2 = session
            } catch (e: Exception) {
                ap2 = null
                // Now-playing is optional; a failure here must not break the
                // remote, but it must still be visible rather than silent.
                android.util.Log.w("AppleTvRemote", "now-playing tunnel failed", e)
                if (e is HapException) {
                    // The AirPlay pairing is a separate entry on the TV and can
                    // be removed on its own; put the pair button back.
                    store.forgetAirPlay(device.credentialKey)
                    _state.update {
                        it.withNowPlaying(null).copy(airplayPaired = false, nowPlayingError = null)
                    }
                } else {
                    _state.update {
                        it.withNowPlaying(null).copy(nowPlayingError = e.message)
                    }
                }
            }
        }
    }

    /** Begin the separate AirPlay pairing that now-playing requires. */
    fun startAirPlayPairing(device: AppleTvDevice) {
        _state.update { it.copy(busy = true, error = null, nowPlayingError = null) }
        netScope.launch {
            try {
                val connection = AirPlayConnection(device.address, AIRPLAY_PORT)
                connection.connect()
                airplayPairing = AirPlayAuth.startPairing(connection, deviceName)
                airplayConnection = connection
                _state.update {
                    it.copy(busy = false, screen = Screen.PinEntry(device, forAirPlay = true))
                }
            } catch (e: Exception) {
                closeAirPlayPairing()
                val message = str(R.string.error_airplay_pairing_start, e.message ?: "")
                _state.update {
                    it.copy(busy = false, error = message, nowPlayingError = message)
                }
            }
        }
    }

    private fun submitAirPlayPin(device: AppleTvDevice, pin: String) {
        val pairing = airplayPairing ?: return
        if (pin.length != 4) {
            _state.update { it.copy(error = str(R.string.error_pin_length)) }
            return
        }
        _state.update { it.copy(busy = true, error = null) }
        netScope.launch {
            try {
                val credentials = pairing.complete(pin)
                store.saveAirPlay(device.credentialKey, credentials)
                closeAirPlayPairing()
                _state.update {
                    it.copy(
                        busy = false,
                        screen = Screen.Remote(device),
                        airplayPaired = true,
                        nowPlayingError = null,
                    )
                }
                startNowPlaying(device)
            } catch (e: Exception) {
                closeAirPlayPairing()
                val message = if (e.message?.contains("Incorrect PIN") == true) {
                    str(R.string.error_wrong_pin)
                } else {
                    str(R.string.error_airplay_pairing_failed, e.message ?: "")
                }
                _state.update {
                    it.copy(
                        busy = false,
                        screen = Screen.Remote(device),
                        error = message,
                        nowPlayingError = message,
                    )
                }
            }
        }
    }

    /** Seek relative to the current position; negative rewinds. */
    fun skip(seconds: Double) {
        nudgePlayhead(seconds)
        command { skipBy(seconds) }
    }

    /**
     * Seek to an absolute position. Companion Link only offers a relative
     * skip, so this asks for the difference from where the playhead is
     * believed to be.
     */
    fun seekTo(seconds: Double) {
        val current = _state.value.positionNow() ?: return
        skip(seconds - current)
    }

    /**
     * Move the local anchor immediately, so the scrubber follows the finger
     * instead of snapping back until the device reports the new position.
     */
    private fun nudgePlayhead(seconds: Double) = _state.update { s ->
        val from = s.positionNow() ?: return@update s
        // Only META_ELAPSED is sanity-checked on the way in, so a duration
        // that is negative or NaN reaches here intact and would make coerceIn
        // throw on its own arguments.
        val limit = s.nowPlaying?.duration?.takeIf { it.isFinite() && it > 0 }
            ?: Double.MAX_VALUE
        s.copy(
            playhead = (from + seconds).coerceIn(0.0, limit),
            playheadAt = SystemClock.elapsedRealtime(),
        )
    }

    // ---------------------------------------------------------- text input

    fun setTextDraft(value: String) = _state.update { it.copy(textDraft = value) }

    fun toggleTextPanel() {
        val opening = !_state.value.textPanelOpen
        _state.update { it.copy(textPanelOpen = opening) }
        if (opening) refreshTextField()
    }

    fun closeTextPanel() = _state.update { it.copy(textPanelOpen = false) }

    /** Ask the device whether a text field is focused, and what it holds. */
    private fun refreshTextField() {
        netScope.launch {
            val session = runCatching { withRemote { it.textInputSession() } }.getOrNull()
            _state.update {
                if (session == null) {
                    it.copy(textFieldFocused = false, fieldText = null)
                } else {
                    it.copy(textFieldFocused = true, fieldText = session.textBeforeCursor)
                }
            }
        }
    }

    /** Type the draft into the focused field on the TV. */
    fun sendText() {
        val text = _state.value.textDraft
        if (text.isEmpty()) return
        netScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    withRemote { it.sendText(text) }
                }
                if (result == null) {
                    _state.update {
                        it.copy(
                            textFieldFocused = false,
                            error = str(R.string.error_no_text_field),
                        )
                    }
                } else {
                    _state.update {
                        it.copy(
                            textDraft = "",
                            fieldText = result,
                            textFieldFocused = true,
                            textPanelOpen = true,
                        )
                    }
                }
            } catch (e: Exception) {
                reportFailure(e)
            }
        }
    }

    /** Replace the focused field's contents with nothing. */
    fun clearText() {
        netScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    withRemote { it.sendText("", clearPrevious = true) }
                }
                if (result == null) {
                    _state.update { it.copy(error = str(R.string.error_no_text_field)) }
                } else {
                    _state.update { it.copy(textDraft = "", fieldText = result) }
                }
            } catch (e: Exception) {
                reportFailure(e)
            }
        }
    }

    // ------------------------------------------------------------- messages

    fun dismissError() = _state.update { it.copy(error = null) }

    fun dismissNotice() = _state.update { it.copy(notice = null) }

    private fun str(id: Int, vararg args: Any?): String =
        getApplication<Application>().getString(id, *args)

    private companion object {
        const val KEY_PAD_MODE = "pad-mode"
        const val KEY_VOLUME_CONTROLS = "volume-controls"
        const val KEY_SWIPE_GUARD = "swipe-guard"
        const val KEY_THEME_MODE = "theme-mode"

        /** AirPlay control port, where MRP and its pairing live. */
        const val AIRPLAY_PORT = 7000

        /** Attempts to reach the TV's remote service after a wake press. */
        const val WAKE_CONNECT_ATTEMPTS = 3
        const val WAKE_CONNECT_DELAY_MS = 1200L
    }

    override fun onCleared() {
        // The notification outlives neither the connection nor this view
        // model, which owns it, so both go at once.
        NowPlayingBridge.handleCommands(null)
        NowPlayingBridge.publish(null)
        notificationRunning = false
        closePairing()
        closeAirPlayPairing()
        runCatching { remote?.close() }
        runCatching { ap2?.close() }
        remote = null
        ap2 = null
        netScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        super.onCleared()
    }
}

/** One queued sample from the touch surface. */
private data class TouchSample(val x: Int, val y: Int, val phase: TouchPhase)
