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
) {
    // Artwork is compared by size: the bytes are large and decoding identical
    // artwork again would be wasted work.
    override fun equals(other: Any?): Boolean =
        other is NowPlayingSnapshot && deviceName == other.deviceName &&
            title == other.title && artist == other.artist && album == other.album &&
            playing == other.playing && position == other.position &&
            duration == other.duration &&
            (artwork?.size ?: 0) == (other.artwork?.size ?: 0)

    override fun hashCode(): Int =
        listOf(deviceName, title, artist, album, playing, position, duration).hashCode()
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
