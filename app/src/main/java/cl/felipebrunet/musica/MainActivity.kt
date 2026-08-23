package cl.felipebrunet.musica

import android.Manifest
import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaControllerCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.view.View
import android.widget.SeekBar
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import cl.felipebrunet.musica.data.Group
import cl.felipebrunet.musica.data.LibraryRepository
import cl.felipebrunet.musica.data.Track
import cl.felipebrunet.musica.databinding.ActivityMainBinding
import cl.felipebrunet.musica.playback.PlaybackService
import cl.felipebrunet.musica.ui.GroupAdapter
import cl.felipebrunet.musica.ui.TrackAdapter
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var browser: MediaBrowserCompat
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private val groupAdapter = GroupAdapter { openGroup(it) }
    private val trackAdapter = TrackAdapter { _, index -> playCurrentGroup(index) }

    private var showingTracks = false
    private var currentGroup: Group? = null
    private var albumsMode = true
    private var userSeeking = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val granted = storageGranted() || result.entries.any { it.key != Manifest.permission.POST_NOTIFICATIONS && it.value }
        if (granted) {
            loadLibrary()
        } else {
            showPermission()
        }
    }

    private val connectionCallback = object : MediaBrowserCompat.ConnectionCallback() {
        override fun onConnected() {
            val controller = MediaControllerCompat(this@MainActivity, browser.sessionToken)
            MediaControllerCompat.setMediaController(this@MainActivity, controller)
            controller.registerCallback(controllerCallback)
            applyPendingQueue()
            bindController(controller)
        }

        override fun onConnectionSuspended() = Unit
        override fun onConnectionFailed() = Unit
    }

    private val controllerCallback = object : MediaControllerCompat.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackStateCompat?) {
            bindPlaybackState(state)
        }

        override fun onMetadataChanged(metadata: MediaMetadataCompat?) {
            bindMetadata(metadata)
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            val controller = MediaControllerCompat.getMediaController(this@MainActivity)
            val state = controller?.playbackState
            if (!userSeeking && state != null && state.state == PlaybackStateCompat.STATE_PLAYING) {
                binding.seekBar.progress = state.position.toInt().coerceAtLeast(0)
                binding.positionText.text = LibraryRepository.formatDuration(state.position)
            }
            main.postDelayed(this, 500L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = groupAdapter
        binding.list.itemAnimator = null

        binding.btnAlbums.setOnClickListener { showAlbums() }
        binding.btnFolders.setOnClickListener { showFolders() }
        binding.btnRefresh.setOnClickListener { loadLibrary() }
        binding.btnGrant.setOnClickListener { requestNeededPermissions() }
        binding.btnPlay.setOnClickListener { togglePlay() }
        binding.btnPrev.setOnClickListener {
            MediaControllerCompat.getMediaController(this)?.transportControls?.skipToPrevious()
        }
        binding.btnNext.setOnClickListener {
            MediaControllerCompat.getMediaController(this)?.transportControls?.skipToNext()
        }
        binding.btnPlayFolder.setOnClickListener { playCurrentGroup(0) }

        binding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    binding.positionText.text = LibraryRepository.formatDuration(progress.toLong())
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                userSeeking = true
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                userSeeking = false
                val pos = seekBar?.progress?.toLong() ?: 0L
                MediaControllerCompat.getMediaController(this@MainActivity)?.transportControls?.seekTo(pos)
            }
        })

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (showingTracks) {
                    closeGroup()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        browser = MediaBrowserCompat(
            this,
            ComponentName(this, PlaybackService::class.java),
            connectionCallback,
            null
        )

        restoreMiniPlayerFromPrefs()
        updateModeButtons()

        if (storageGranted()) {
            loadLibrary()
        } else {
            showPermission()
            requestNeededPermissions()
        }
    }

    override fun onStart() {
        super.onStart()
        if (!browser.isConnected) {
            browser.connect()
        }
        main.post(tick)
    }

    override fun onStop() {
        main.removeCallbacks(tick)
        val controller = MediaControllerCompat.getMediaController(this)
        controller?.unregisterCallback(controllerCallback)
        if (browser.isConnected) {
            browser.disconnect()
        }
        super.onStop()
    }

    override fun onDestroy() {
        io.shutdown()
        super.onDestroy()
    }

    private fun neededPermissions(): Array<String> {
        val list = ArrayList<String>()
        if (Build.VERSION.SDK_INT >= 33) {
            list.add(Manifest.permission.READ_MEDIA_AUDIO)
            list.add(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            list.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        return list.toTypedArray()
    }

    private fun storageGranted(): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= 33) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestNeededPermissions() {
        permissionLauncher.launch(neededPermissions())
    }

    private fun loadLibrary() {
        binding.permissionBox.visibility = View.GONE
        binding.emptyBox.visibility = View.GONE
        binding.list.visibility = View.VISIBLE
        io.execute {
            val app = application as MusicaApp
            app.library.scan(this)
            main.post {
                if (showingTracks) {
                    val key = currentGroup?.key
                    val groups = if (albumsMode) app.library.albums() else app.library.folders()
                    currentGroup = groups.firstOrNull { it.key == key }
                    val tracks = currentGroup?.tracks.orEmpty()
                    if (tracks.isEmpty()) {
                        closeGroup()
                    } else {
                        showTrackList(currentGroup!!)
                    }
                } else {
                    showCurrentBrowse()
                }
            }
        }
    }

    private fun showAlbums() {
        albumsMode = true
        showingTracks = false
        currentGroup = null
        updateModeButtons()
        showCurrentBrowse()
    }

    private fun showFolders() {
        albumsMode = false
        showingTracks = false
        currentGroup = null
        updateModeButtons()
        showCurrentBrowse()
    }

    private fun showCurrentBrowse() {
        showingTracks = false
        binding.btnPlayFolder.visibility = View.GONE
        binding.list.adapter = groupAdapter
        val app = application as MusicaApp
        val groups = if (albumsMode) app.library.albums() else app.library.folders()
        groupAdapter.submit(groups)
        if (!storageGranted()) {
            showPermission()
        } else if (groups.isEmpty()) {
            binding.list.visibility = View.GONE
            binding.permissionBox.visibility = View.GONE
            binding.emptyBox.visibility = View.VISIBLE
        } else {
            binding.list.visibility = View.VISIBLE
            binding.permissionBox.visibility = View.GONE
            binding.emptyBox.visibility = View.GONE
        }
        binding.toolbarTitle.text = getString(R.string.app_name)
    }

    private fun showPermission() {
        binding.list.visibility = View.GONE
        binding.emptyBox.visibility = View.GONE
        binding.permissionBox.visibility = View.VISIBLE
        binding.btnPlayFolder.visibility = View.GONE
    }

    private fun openGroup(group: Group) {
        currentGroup = group
        showTrackList(group)
    }

    private fun showTrackList(group: Group) {
        showingTracks = true
        binding.permissionBox.visibility = View.GONE
        binding.emptyBox.visibility = View.GONE
        binding.list.visibility = View.VISIBLE
        binding.list.adapter = trackAdapter
        binding.toolbarTitle.text = group.title
        binding.btnPlayFolder.visibility = View.VISIBLE
        val playingUri = MediaControllerCompat.getMediaController(this)
            ?.metadata
            ?.getString(MediaMetadataCompat.METADATA_KEY_MEDIA_URI)
        trackAdapter.submit(group.tracks, playingUri)
    }

    private fun closeGroup() {
        currentGroup = null
        showCurrentBrowse()
    }

    private fun playCurrentGroup(startIndex: Int) {
        val tracks = currentGroup?.tracks ?: return
        if (tracks.isEmpty()) return
        playTracks(tracks, startIndex.coerceIn(0, tracks.lastIndex))
    }

    private fun playTracks(tracks: List<Track>, startIndex: Int) {
        val app = application as MusicaApp
        val controller = MediaControllerCompat.getMediaController(this)
        if (controller != null) {
            sendQueue(controller, tracks, startIndex)
            app.pendingQueue = emptyList()
        } else {
            app.pendingQueue = tracks
            app.pendingIndex = startIndex
            if (!browser.isConnected) {
                browser.connect()
            }
        }
    }

    private fun applyPendingQueue() {
        val app = application as MusicaApp
        val pending = app.pendingQueue
        if (pending.isEmpty()) return
        val controller = MediaControllerCompat.getMediaController(this) ?: return
        sendQueue(controller, pending, app.pendingIndex)
        app.pendingQueue = emptyList()
    }

    private fun sendQueue(controller: MediaControllerCompat, tracks: List<Track>, startIndex: Int) {
        val extras = Bundle().apply {
            putStringArrayList(
                PlaybackService.EXTRA_URIS,
                ArrayList(tracks.map { it.uri })
            )
            putInt(PlaybackService.EXTRA_INDEX, startIndex)
        }
        controller.transportControls.sendCustomAction(PlaybackService.ACTION_PLAY_QUEUE, extras)
    }

    private fun togglePlay() {
        val controller = MediaControllerCompat.getMediaController(this) ?: return
        val state = controller.playbackState?.state
        if (state == PlaybackStateCompat.STATE_PLAYING || state == PlaybackStateCompat.STATE_BUFFERING) {
            controller.transportControls.pause()
        } else {
            controller.transportControls.play()
        }
    }

    private fun bindController(controller: MediaControllerCompat) {
        bindMetadata(controller.metadata)
        bindPlaybackState(controller.playbackState)
    }

    private fun bindMetadata(metadata: MediaMetadataCompat?) {
        if (metadata == null) {
            restoreMiniPlayerFromPrefs()
            return
        }
        val title = metadata.getString(MediaMetadataCompat.METADATA_KEY_TITLE).orEmpty()
        val artist = metadata.getString(MediaMetadataCompat.METADATA_KEY_ARTIST).orEmpty()
        if (title.isNotBlank()) {
            binding.nowTitle.text = title
            binding.nowArtist.text = artist
        }
        val duration = metadata.getLong(MediaMetadataCompat.METADATA_KEY_DURATION)
        if (duration > 0) {
            binding.seekBar.max = duration.toInt()
            binding.durationText.text = LibraryRepository.formatDuration(duration)
        }
        trackAdapter.setCurrent(metadata.getString(MediaMetadataCompat.METADATA_KEY_MEDIA_URI))
    }

    private fun bindPlaybackState(state: PlaybackStateCompat?) {
        val playing = state?.state == PlaybackStateCompat.STATE_PLAYING ||
            state?.state == PlaybackStateCompat.STATE_BUFFERING
        binding.btnPlay.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
        binding.btnPlay.contentDescription = getString(if (playing) R.string.pausar else R.string.reproducir)
        if (!userSeeking && state != null) {
            binding.seekBar.progress = state.position.toInt().coerceAtLeast(0)
            binding.positionText.text = LibraryRepository.formatDuration(state.position)
        }
    }

    private fun restoreMiniPlayerFromPrefs() {
        val store = (application as MusicaApp).playbackStore
        val title = store.lastTitle()
        if (title.isNotBlank()) {
            binding.nowTitle.text = title
            binding.nowArtist.text = store.lastArtist()
        } else {
            binding.nowTitle.text = getString(R.string.nada_sonando)
            binding.nowArtist.text = getString(R.string.elige_album_o_carpeta)
        }
    }

    private fun updateModeButtons() {
        binding.btnAlbums.isSelected = albumsMode && !showingTracks
        binding.btnFolders.isSelected = !albumsMode && !showingTracks
        val selected = ContextCompat.getColor(this, R.color.button_selected_text)
        val idle = ContextCompat.getColor(this, R.color.button_idle_text)
        binding.btnAlbums.setTextColor(if (albumsMode) selected else idle)
        binding.btnFolders.setTextColor(if (!albumsMode) selected else idle)
    }
}
