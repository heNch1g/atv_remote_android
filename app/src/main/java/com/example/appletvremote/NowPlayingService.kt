package com.example.appletvremote

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.IBinder
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Media notification for whatever the Apple TV is playing.
 *
 * The point of the service is not only the notification: a foreground service
 * is what keeps the Companion and MRP connections alive once the app leaves
 * the screen, which is exactly when a notification is worth having.
 *
 * The scrubber and the buttons in the system's media controls are driven by
 * the [MediaSession], not by the notification's own actions — those are the
 * fallback for older shades — so both are kept in step.
 */
class NowPlayingService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var session: MediaSession

    /** Decoded artwork, kept until the bytes change, since decoding is not cheap. */
    private var artwork: Bitmap? = null
    private var artworkSource: ByteArray? = null

    private val nothingPlaying: String get() = getString(R.string.nothing_playing)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()

        session = MediaSession(this, "AppleTvRemote").apply {
            setSessionActivity(openApp())
            setCallback(object : MediaSession.Callback() {
                // The TV owns playback state, so play and pause are the same
                // toggle from here; the icon drawn is whatever the last
                // reported state implies.
                override fun onPlay() = NowPlayingBridge.send(RemoteCommand.PlayPause)
                override fun onPause() = NowPlayingBridge.send(RemoteCommand.PlayPause)
                override fun onRewind() = NowPlayingBridge.send(RemoteCommand.Skip(-SKIP))
                override fun onFastForward() = NowPlayingBridge.send(RemoteCommand.Skip(SKIP))
                override fun onSkipToPrevious() =
                    NowPlayingBridge.send(RemoteCommand.Skip(-SKIP))

                override fun onSkipToNext() = NowPlayingBridge.send(RemoteCommand.Skip(SKIP))
                override fun onSeekTo(pos: Long) =
                    NowPlayingBridge.send(RemoteCommand.SeekTo(pos / 1000.0))
            })
            isActive = true
        }

        scope.launch {
            NowPlayingBridge.snapshot.collectLatest { snapshot ->
                if (snapshot == null) {
                    // Nothing playing, or the remote disconnected.
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                } else {
                    apply(snapshot)
                    notificationManager().notify(NOTIFICATION_ID, notification(snapshot))
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY_PAUSE -> NowPlayingBridge.send(RemoteCommand.PlayPause)
            ACTION_REWIND -> NowPlayingBridge.send(RemoteCommand.Skip(-SKIP))
            ACTION_FORWARD -> NowPlayingBridge.send(RemoteCommand.Skip(SKIP))
            // No branch for ACTION_SHOW: it falls through to the re-post.
        }

        // Android gives a started service seconds to show its notification,
        // whether or not a snapshot has arrived yet; ACTION_SHOW re-posts the
        // current card after the system or the user folded it away.
        val snapshot = NowPlayingBridge.snapshot.value
        startForeground(NOTIFICATION_ID, notification(snapshot))
        snapshot?.let(::apply)
        if (snapshot == null) {
            // A stale request while nothing is connected: a card that cannot
            // even pause would only sit in the shade misleading.
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        session.isActive = false
        session.release()
        scope.cancel()
        super.onDestroy()
    }

    /** Push the snapshot into the session, which is what the shade reads. */
    private fun apply(snapshot: NowPlayingSnapshot) {
        // Nothing on screen: clear the session so the system does not keep a
        // stale media card in its carousel while the service is only
        // listening for playback in the background.
        if (!snapshot.isWorthShowing()) {
            session.setMetadata(MediaMetadata.Builder().build())
            session.setPlaybackState(
                PlaybackState.Builder()
                    .setState(
                        PlaybackState.STATE_NONE,
                        PlaybackState.PLAYBACK_POSITION_UNKNOWN,
                        0f,
                    )
                    .build(),
            )
            return
        }

        if (snapshot.artwork !== artworkSource) {
            artworkSource = snapshot.artwork
            artwork = snapshot.artwork?.let {
                runCatching { BitmapFactory.decodeByteArray(it, 0, it.size) }.getOrNull()
            }
        }

        val metadata = MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, snapshot.title ?: nothingPlaying)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, snapshot.artist ?: snapshot.deviceName)
            .putString(MediaMetadata.METADATA_KEY_ALBUM, snapshot.album)
            .putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, artwork)
        // A duration only means something alongside a position; without one the
        // system hides the scrubber, which beats drawing a bar that cannot move.
        snapshot.duration?.let {
            metadata.putLong(MediaMetadata.METADATA_KEY_DURATION, (it * 1000).toLong())
        }
        session.setMetadata(metadata.build())

        var actions = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
            PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_REWIND or
            PlaybackState.ACTION_FAST_FORWARD or
            PlaybackState.ACTION_SKIP_TO_PREVIOUS or
            PlaybackState.ACTION_SKIP_TO_NEXT
        // The system's own media player gives its side buttons to the
        // skip-to-previous/next actions, so those are what must be advertised
        // for ±15 seconds to be reachable there; the callbacks below route
        // them (and the rewind/fast-forward pair some shades draw instead)
        // through the same ±15 second skip.
        // Both halves are needed for the scrubber: seeking is relative to the
        // current playhead, so advertising it without one gives the shade a
        // scrubber whose every drag is silently discarded.
        if (snapshot.duration != null && snapshot.position != null) {
            actions = actions or PlaybackState.ACTION_SEEK_TO
        }

        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(actions)
                .setState(
                    if (snapshot.playing) PlaybackState.STATE_PLAYING
                    else PlaybackState.STATE_PAUSED,
                    snapshot.position?.let { (it * 1000).toLong() }
                        ?: PlaybackState.PLAYBACK_POSITION_UNKNOWN,
                    // The shade runs the scrubber forward from here itself, so
                    // it animates between the updates the Apple TV sends.
                    if (snapshot.playing) 1f else 0f,
                )
                .build(),
        )
    }

    /**
     * The foreground-service card used while the app only listens for
     * playback. Deliberately not a MediaStyle notification: the shade should
     * get the player card when something starts, not before.
     */
    private fun idleNotification(snapshot: NowPlayingSnapshot?): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(snapshot?.deviceName ?: getString(R.string.app_name))
            .setContentText(getString(R.string.notif_waiting))
            .setContentIntent(openApp())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .build()

    private fun notification(snapshot: NowPlayingSnapshot?): Notification {
        // While the service waits for playback it is still a foreground
        // service and therefore must show something; keep that card plain and
        // quiet instead of presenting an empty media player.
        if (snapshot == null || !snapshot.isWorthShowing()) return idleNotification(snapshot)

        val playing = snapshot.playing
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(snapshot.title ?: nothingPlaying)
            .setContentText(snapshot.artist ?: snapshot.deviceName)
            .setContentIntent(openApp())
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            // Pinned while the item exists: the user reads the playhead in the
            // shade, and pausing must not make the card vanish. It goes away
            // when playback stops (the service falls back to the quiet
            // connection card) or when the remote disconnects.
            .setOngoing(true)
            .setStyle(
                Notification.MediaStyle()
                    .setMediaSession(session.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2),
            )
            .addAction(
                action(
                    R.drawable.ic_media_rewind,
                    getString(R.string.skip_back),
                    ACTION_REWIND,
                ),
            )
            .addAction(
                action(
                    if (playing) R.drawable.ic_media_pause else R.drawable.ic_media_play,
                    if (playing) getString(R.string.pause) else getString(R.string.play),
                    ACTION_PLAY_PAUSE,
                ),
            )
            .addAction(
                action(
                    R.drawable.ic_media_forward,
                    getString(R.string.skip_forward),
                    ACTION_FORWARD,
                ),
            )

        snapshot.deviceName.let { builder.setSubText(it) }
        artwork?.let { builder.setLargeIcon(it) }
        return builder.build()
    }

    private fun action(icon: Int, title: String, action: String): Notification.Action {
        val intent = Intent(this, NowPlayingService::class.java).setAction(action)
        val pending = PendingIntent.getService(
            this,
            action.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Action.Builder(
            android.graphics.drawable.Icon.createWithResource(this, icon),
            title,
            pending,
        ).build()
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java)
            .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun notificationManager() = getSystemService(NotificationManager::class.java)

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel_nowplaying),
            // Media controls are glanceable, not interruptive.
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notif_channel_nowplaying_desc)
            setShowBadge(false)
        }
        notificationManager().createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "now_playing"
        private const val NOTIFICATION_ID = 1

        /** Both the shade buttons and the remote's seek are ±15 seconds. */
        private const val SKIP = 15.0

        private const val ACTION_PLAY_PAUSE = "com.example.appletvremote.PLAY_PAUSE"
        private const val ACTION_REWIND = "com.example.appletvremote.REWIND"
        private const val ACTION_FORWARD = "com.example.appletvremote.FORWARD"

        /** Re-posts the card when the system or the user folded it away. */
        const val ACTION_SHOW = "com.example.appletvremote.SHOW"

        fun show(context: Context) = runCatching {
            ContextCompat.startForegroundService(
                context,
                Intent(context, NowPlayingService::class.java).setAction(ACTION_SHOW),
            )
        }
    }
}
