package cl.felipebrunet.musica.playback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.Process
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.media.MediaBrowserServiceCompat
import androidx.media.app.NotificationCompat.MediaStyle
import androidx.media.session.MediaButtonReceiver
import cl.felipebrunet.musica.MainActivity
import cl.felipebrunet.musica.MusicaApp
import cl.felipebrunet.musica.R
import cl.felipebrunet.musica.data.PlaybackStore
import cl.felipebrunet.musica.data.Track
import java.io.FileInputStream

class PlaybackService : MediaBrowserServiceCompat() {

    private lateinit var session: MediaSessionCompat
    private lateinit var store: PlaybackStore
    private lateinit var audioManager: AudioManager
    private lateinit var focusRequest: AudioFocusRequest

    private var player: MediaPlayer? = null
    private var queue: List<Track> = emptyList()
    private var index: Int = 0
    private var pendingSeekMs: Long = 0L
    private var playWhenReady: Boolean = false
    private var preparing: Boolean = false
    private var noisyRegistered: Boolean = false
    private var focusGranted: Boolean = false

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                pauseInternal()
            }
        }
    }

    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> pauseInternal()
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                player?.setVolume(0.25f, 0.25f)
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                player?.setVolume(1f, 1f)
            }
        }
    }

    private val sessionCallback = object : MediaSessionCompat.Callback() {
        override fun onPlay() {
            if (queue.isEmpty()) {
                restoreFromDisk()
            }
            if (player == null) {
                playIndex(index, start = true, seekMs = store.lastPosition())
            } else {
                startPlayback()
            }
        }

        override fun onPause() {
            pauseInternal()
        }

        override fun onStop() {
            stopInternal()
        }

        override fun onSkipToNext() {
            skip(+1, start = isPlayingOrPreparing())
        }

        override fun onSkipToPrevious() {
            val pos = currentPosition()
            if (pos > RESTART_THRESHOLD_MS) {
                seekInternal(0L)
            } else {
                skip(-1, start = isPlayingOrPreparing())
            }
        }

        override fun onSeekTo(pos: Long) {
            seekInternal(pos)
        }

        override fun onPlayFromUri(uri: Uri?, extras: Bundle?) {
            if (uri == null) return
            val i = queue.indexOfFirst { it.uri == uri.toString() }
            if (i >= 0) {
                playIndex(i, start = true, seekMs = 0L)
            }
        }

        override fun onCustomAction(action: String?, extras: Bundle?) {
            if (action == ACTION_PLAY_QUEUE && extras != null) {
                val uris = extras.getStringArrayList(EXTRA_URIS) ?: return
                val start = extras.getInt(EXTRA_INDEX, 0)
                val library = (application as MusicaApp).library.tracks
                val byUri = library.associateBy { it.uri }
                val loaded = uris.mapNotNull { byUri[it] }
                if (loaded.isEmpty()) return
                queue = loaded
                playIndex(start.coerceIn(0, loaded.lastIndex), start = true, seekMs = 0L)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        store = PlaybackStore(this)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setOnAudioFocusChangeListener(focusChangeListener)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .build()

        session = MediaSessionCompat(this, SESSION_TAG).apply {
            setCallback(sessionCallback)
            setFlags(
                MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                    MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS
            )
            isActive = true
        }
        sessionToken = session.sessionToken
        createChannel()
        restoreFromDisk()
        publishState()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        MediaButtonReceiver.handleIntent(session, intent)
        return START_STICKY
    }

    override fun onDestroy() {
        persist()
        unregisterNoisy()
        abandonFocus()
        releasePlayer()
        session.isActive = false
        session.release()
        super.onDestroy()
    }

    override fun onGetRoot(
        clientPackageName: String,
        clientUid: Int,
        rootHints: Bundle?
    ): BrowserRoot? {
        // Bluetooth / lock screen use MediaSession, not MediaBrowser.
        // Reject other apps so they cannot browse or send PLAY_QUEUE.
        if (clientUid != Process.myUid() || clientPackageName != packageName) {
            return null
        }
        return BrowserRoot(ROOT_ID, null)
    }

    override fun onLoadChildren(
        parentId: String,
        result: Result<MutableList<MediaBrowserCompat.MediaItem>>
    ) {
        result.sendResult(mutableListOf())
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        persist()
        super.onTaskRemoved(rootIntent)
    }

    private fun restoreFromDisk() {
        val library = (application as MusicaApp).library.tracks
        val restored = store.restoreQueue(library)
        if (restored.isNotEmpty()) {
            queue = restored
            index = store.lastIndex().coerceIn(0, restored.lastIndex)
            pendingSeekMs = store.lastPosition()
            publishMetadata(queue[index])
            publishState()
        }
    }

    private fun playIndex(newIndex: Int, start: Boolean, seekMs: Long) {
        if (queue.isEmpty()) return
        index = newIndex.coerceIn(0, queue.lastIndex)
        pendingSeekMs = seekMs.coerceAtLeast(0L)
        playWhenReady = start
        val track = queue[index]
        publishMetadata(track)
        persist()
        // Show the notification immediately so a foreground start never times out
        // while MediaPlayer prepares a large FLAC from the SD card.
        startForeground(NOTIFICATION_ID, buildNotification())

        releasePlayer()
        preparing = true
        val mp = MediaPlayer()
        player = mp
        mp.setWakeMode(this, PowerManager.PARTIAL_WAKE_LOCK)
        mp.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
        )
        mp.setOnPreparedListener {
            preparing = false
            if (pendingSeekMs > 0L) {
                try {
                    it.seekTo(pendingSeekMs.toInt())
                } catch (_: IllegalStateException) {
                }
                pendingSeekMs = 0L
            }
            if (playWhenReady) {
                startPlayback()
            } else {
                publishState()
                refreshNotification()
            }
        }
        mp.setOnCompletionListener {
            skip(+1, start = true)
        }
        mp.setOnErrorListener { _, _, _ ->
            preparing = false
            skip(+1, start = playWhenReady)
            true
        }
        try {
            setDataSource(mp, track)
            mp.prepareAsync()
        } catch (_: Exception) {
            preparing = false
            skip(+1, start = start)
        }
        publishState()
        refreshNotification()
    }

    private fun setDataSource(mp: MediaPlayer, track: Track) {
        try {
            mp.setDataSource(applicationContext, Uri.parse(track.uri))
        } catch (first: Exception) {
            val path = track.path
            if (path.isNullOrBlank()) throw first
            FileInputStream(path).use { fis ->
                mp.setDataSource(fis.fd)
            }
        }
    }

    private fun startPlayback() {
        val mp = player ?: run {
            playIndex(index, start = true, seekMs = pendingSeekMs)
            return
        }
        if (preparing) {
            playWhenReady = true
            return
        }
        if (!requestFocus()) {
            publishState()
            return
        }
        try {
            mp.start()
            playWhenReady = true
            registerNoisy()
            startForeground(NOTIFICATION_ID, buildNotification())
            persist()
        } catch (_: IllegalStateException) {
            playIndex(index, start = true, seekMs = currentPosition())
            return
        }
        publishState()
        refreshNotification()
    }

    private fun pauseInternal() {
        playWhenReady = false
        try {
            if (player?.isPlaying == true) {
                player?.pause()
            }
        } catch (_: IllegalStateException) {
        }
        persist()
        publishState()
        refreshNotification()
        stopForeground(false)
    }

    private fun stopInternal() {
        playWhenReady = false
        persist()
        unregisterNoisy()
        abandonFocus()
        releasePlayer()
        publishState()
        stopForeground(true)
        stopSelf()
    }

    private fun skip(delta: Int, start: Boolean) {
        if (queue.isEmpty()) return
        val next = index + delta
        if (next !in queue.indices) {
            pauseInternal()
            seekInternal(0L)
            return
        }
        playIndex(next, start = start, seekMs = 0L)
    }

    private fun seekInternal(pos: Long) {
        val mp = player
        if (mp == null || preparing) {
            pendingSeekMs = pos
            persist()
            publishState()
            return
        }
        try {
            mp.seekTo(pos.toInt().coerceAtLeast(0))
        } catch (_: IllegalStateException) {
        }
        persist()
        publishState()
    }

    private fun currentPosition(): Long {
        return try {
            player?.currentPosition?.toLong() ?: pendingSeekMs
        } catch (_: IllegalStateException) {
            pendingSeekMs
        }
    }

    private fun currentDuration(): Long {
        return try {
            val d = player?.duration ?: 0
            if (d > 0) d.toLong() else queue.getOrNull(index)?.durationMs ?: 0L
        } catch (_: IllegalStateException) {
            queue.getOrNull(index)?.durationMs ?: 0L
        }
    }

    private fun isPlayingOrPreparing(): Boolean {
        return playWhenReady || (player?.isPlaying == true)
    }

    private fun actuallyPlaying(): Boolean {
        return try {
            player?.isPlaying == true
        } catch (_: IllegalStateException) {
            false
        }
    }

    private fun persist() {
        if (queue.isEmpty()) return
        store.save(queue, index, currentPosition())
    }

    private fun publishMetadata(track: Track) {
        val duration = currentDuration().let { if (it > 0) it else track.durationMs }
        session.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, track.uri)
                .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_URI, track.uri)
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, track.title)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, track.artist)
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, track.album)
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, duration)
                .putLong(MediaMetadataCompat.METADATA_KEY_TRACK_NUMBER, track.trackNumber.toLong())
                .putLong(MediaMetadataCompat.METADATA_KEY_DISC_NUMBER, track.discNumber.toLong())
                .build()
        )
    }

    private fun publishState() {
        val playing = actuallyPlaying()
        val state = when {
            preparing && playWhenReady -> PlaybackStateCompat.STATE_BUFFERING
            playing -> PlaybackStateCompat.STATE_PLAYING
            player != null || queue.isNotEmpty() -> PlaybackStateCompat.STATE_PAUSED
            else -> PlaybackStateCompat.STATE_NONE
        }
        session.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(ACTIONS)
                .setState(state, currentPosition(), if (playing) 1f else 0f)
                .build()
        )
    }

    private fun requestFocus(): Boolean {
        val result = audioManager.requestAudioFocus(focusRequest)
        focusGranted = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        return focusGranted
    }

    private fun abandonFocus() {
        if (focusGranted) {
            audioManager.abandonAudioFocusRequest(focusRequest)
            focusGranted = false
        }
    }

    private fun registerNoisy() {
        if (noisyRegistered) return
        val filter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(noisyReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(noisyReceiver, filter)
        }
        noisyRegistered = true
    }

    private fun unregisterNoisy() {
        if (!noisyRegistered) return
        try {
            unregisterReceiver(noisyReceiver)
        } catch (_: IllegalArgumentException) {
        }
        noisyRegistered = false
    }

    private fun releasePlayer() {
        preparing = false
        try {
            player?.reset()
            player?.release()
        } catch (_: Exception) {
        }
        player = null
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel),
            NotificationManager.IMPORTANCE_LOW
        )
        channel.setShowBadge(false)
        channel.description = getString(R.string.notification_channel)
        manager.createNotificationChannel(channel)
    }

    private fun refreshNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val track = queue.getOrNull(index)
        val playing = actuallyPlaying() || (preparing && playWhenReady)
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val playPauseIcon = if (playing) R.drawable.ic_pause else R.drawable.ic_play
        val playPauseDesc = if (playing) getString(R.string.pausar) else getString(R.string.reproducir)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(track?.title ?: getString(R.string.app_name))
            .setContentText(track?.artist ?: "")
            .setSubText(track?.album)
            .setContentIntent(openApp)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setOngoing(playing)
            .addAction(
                NotificationCompat.Action(
                    R.drawable.ic_prev,
                    getString(R.string.anterior),
                    MediaButtonReceiver.buildMediaButtonPendingIntent(
                        this,
                        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
                    )
                )
            )
            .addAction(
                NotificationCompat.Action(
                    playPauseIcon,
                    playPauseDesc,
                    MediaButtonReceiver.buildMediaButtonPendingIntent(
                        this,
                        PlaybackStateCompat.ACTION_PLAY_PAUSE
                    )
                )
            )
            .addAction(
                NotificationCompat.Action(
                    R.drawable.ic_next,
                    getString(R.string.siguiente),
                    MediaButtonReceiver.buildMediaButtonPendingIntent(
                        this,
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT
                    )
                )
            )
            .setStyle(
                MediaStyle()
                    .setMediaSession(session.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
                    .setShowCancelButton(true)
                    .setCancelButtonIntent(
                        MediaButtonReceiver.buildMediaButtonPendingIntent(
                            this,
                            PlaybackStateCompat.ACTION_STOP
                        )
                    )
            )
            .build()
    }

    companion object {
        const val ACTION_PLAY_QUEUE = "cl.felipebrunet.musica.PLAY_QUEUE"
        const val EXTRA_URIS = "uris"
        const val EXTRA_INDEX = "index"

        private const val SESSION_TAG = "MusicaSimple"
        private const val ROOT_ID = "root"
        private const val CHANNEL_ID = "playback"
        private const val NOTIFICATION_ID = 1
        private const val RESTART_THRESHOLD_MS = 3000L

        private const val ACTIONS =
            PlaybackStateCompat.ACTION_PLAY or
                PlaybackStateCompat.ACTION_PAUSE or
                PlaybackStateCompat.ACTION_PLAY_PAUSE or
                PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                PlaybackStateCompat.ACTION_SEEK_TO or
                PlaybackStateCompat.ACTION_STOP or
                PlaybackStateCompat.ACTION_PLAY_FROM_URI

    }
}
