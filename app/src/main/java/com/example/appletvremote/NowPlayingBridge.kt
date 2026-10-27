package com.example.appletvremote

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What the media notification needs in order to draw itself.
 *
 * A flattened copy rather than the live UiState: the service has no business
 * knowing about pairing or screens, and the playhead has to be sampled at a
 * known instant to be useful to the system's scrubber.
 */
class NowPlayingSnapshot(
    val deviceName: String,
    /** Null while connected with nothing playing. */
    val title: String?,
    val artist: String?,
    val album: String?,
    val artwork: ByteArray?,
    val playing: Boolean,
    /** Playhead in seconds when this snapshot was taken. */
    val position: Double?,
    /** Item length in seconds, null when the device reports none. */
    val duration: Double?,
    /**
     * True when there is no metadata tunnel, so the only thing the card can
     * offer is the Companion transport controls. Such a card stays available
     * even with nothing playing: it is the only way to start playback from the
     * shade, and there is no signal that could make it appear on its own.
     */
    val controlsOnly: Boolean = false,
) {
    /** Whether there is an actual item to present, as opposed to idle. */
    fun hasItem(): Boolean = title != null || artist != null || playing

    /** Whether the card should be drawn at all, item or not. */
    fun isWorthShowing(): Boolean = hasItem() || controlsOnly

    // The byte arrays are the same instance until the TV sends new artwork,
    // so contentEquals short-circuits on identity and costs nothing in the
    // common case. Comparing only the size would drop a new still of the same
    // dimensions.
    override fun equals(other: Any?): Boolean =
        other is NowPlayingSnapshot && deviceName == other.deviceName &&
            title == other.title && artist == other.artist && album == other.album &&
            playing == other.playing && position == other.position &&
            duration == other.duration && controlsOnly == other.controlsOnly &&
            artwork.contentEquals(other.artwork)

    override fun hashCode(): Int =
        listOf(
            deviceName,
            title,
            artist,
            album,
            playing,
            position,
            duration,
            controlsOnly,
        ).hashCode()
}

/** What the notification can ask the Apple TV to do. */
sealed interface RemoteCommand {
    data object PlayPause : RemoteCommand
    data class Skip(val seconds: Double) : RemoteCommand
    data class SeekTo(val seconds: Double) : RemoteCommand
}

/**
 * Handoff between the view model, which owns both connections, and the
 * notification, which cannot reach them.
 *
 * Process-wide state rather than binder plumbing: both live in the same
 * process, and the foreground service is what keeps that process alive.
 */
object NowPlayingBridge {

    private val _snapshot = MutableStateFlow<NowPlayingSnapshot?>(null)

    /** Null means there is nothing to show, which stops the service. */
    val snapshot: StateFlow<NowPlayingSnapshot?> = _snapshot.asStateFlow()

    @Volatile
    private var handler: ((RemoteCommand) -> Unit)? = null

    fun publish(snapshot: NowPlayingSnapshot?) {
        _snapshot.value = snapshot
    }

    /** The view model claims command handling for as long as it is alive. */
    fun handleCommands(handler: ((RemoteCommand) -> Unit)?) {
        this.handler = handler
    }

    /** Dropped when nothing is connected, which is the honest outcome. */
    fun send(command: RemoteCommand) {
        handler?.invoke(command)
    }
}
