package cl.felipebrunet.musica

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaControllerCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.view.View
import android.widget.EditText
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
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

    private enum class BrowseMode { ALBUMS, FOLDERS, PLAYLISTS }

    private lateinit var binding: ActivityMainBinding
    private lateinit var browser: MediaBrowserCompat
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private val groupAdapter = GroupAdapter(
        onClick = { openGroup(it) },
        onLongClick = { confirmDeletePlaylist(it) }
    )
    private val trackAdapter = TrackAdapter { _, index -> playCurrentGroup(index) }

    private var showingTracks = false
    private var currentGroup: Group? = null
    private var browseMode = BrowseMode.ALBUMS
    private var userSeeking = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val granted = storageGranted() || result.entries.any { it.key != Manifest.permission.POST_NOTIFICATIONS && it.value }
        if (granted) {
            loadLibrary()
            maybeAskBackgroundPlayback()
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

        binding.btnAlbums.setOnClickListener { showBrowse(BrowseMode.ALBUMS) }
        binding.btnFolders.setOnClickListener { showBrowse(BrowseMode.FOLDERS) }
        binding.btnPlaylists.setOnClickListener { showBrowse(BrowseMode.PLAYLISTS) }
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
        binding.btnSavePlaylist.setOnClickListener { saveCurrentGroupAsPlaylist() }

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
            maybeAskBackgroundPlayback()
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
            app.playlists.scan(this, app.library.tracks)
            main.post {
                if (showingTracks) {
                    val key = currentGroup?.key
                    val groups = currentGroups()
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

    private fun showBrowse(mode: BrowseMode) {
        browseMode = mode
        showingTracks = false
        currentGroup = null
        updateModeButtons()
        showCurrentBrowse()
    }

    private fun currentGroups(): List<Group> {
        val app = application as MusicaApp
        return when (browseMode) {
            BrowseMode.ALBUMS -> app.library.albums()
            BrowseMode.FOLDERS -> app.library.folders()
            BrowseMode.PLAYLISTS -> app.playlists.groups()
        }
    }

    private fun showCurrentBrowse() {
        showingTracks = false
        binding.trackActions.visibility = View.GONE
        binding.btnPlayFolder.visibility = View.GONE
        binding.btnSavePlaylist.visibility = View.GONE
        binding.list.adapter = groupAdapter
        val groups = currentGroups()
        groupAdapter.submit(groups)
        if (!storageGranted()) {
            showPermission()
        } else if (groups.isEmpty()) {
            binding.list.visibility = View.GONE
            binding.permissionBox.visibility = View.GONE
            binding.emptyBox.visibility = View.VISIBLE
            if (browseMode == BrowseMode.PLAYLISTS) {
                binding.emptyTitle.setText(R.string.vacio_listas_titulo)
                binding.emptyBody.setText(R.string.vacio_listas_texto)
            } else {
                binding.emptyTitle.setText(R.string.vacio_titulo)
                binding.emptyBody.setText(R.string.vacio_texto)
            }
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
        binding.trackActions.visibility = View.GONE
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
        binding.trackActions.visibility = View.VISIBLE
        binding.btnPlayFolder.visibility = View.VISIBLE
        val canSave = browseMode != BrowseMode.PLAYLISTS
        binding.btnSavePlaylist.visibility = if (canSave) View.VISIBLE else View.GONE
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
        binding.btnAlbums.isSelected = browseMode == BrowseMode.ALBUMS && !showingTracks
        binding.btnFolders.isSelected = browseMode == BrowseMode.FOLDERS && !showingTracks
        binding.btnPlaylists.isSelected = browseMode == BrowseMode.PLAYLISTS && !showingTracks
        val selected = ContextCompat.getColor(this, R.color.button_selected_text)
        val idle = ContextCompat.getColor(this, R.color.button_idle_text)
        binding.btnAlbums.setTextColor(if (browseMode == BrowseMode.ALBUMS) selected else idle)
        binding.btnFolders.setTextColor(if (browseMode == BrowseMode.FOLDERS) selected else idle)
        binding.btnPlaylists.setTextColor(if (browseMode == BrowseMode.PLAYLISTS) selected else idle)
    }

    private fun saveCurrentGroupAsPlaylist() {
        val group = currentGroup ?: return
        askPlaylistName(group.title) { name ->
            io.execute {
                val app = application as MusicaApp
                app.playlists.create(this, name, group.tracks)
                main.post {
                    Toast.makeText(this, R.string.lista_guardada, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun confirmDeletePlaylist(group: Group): Boolean {
        if (browseMode != BrowseMode.PLAYLISTS) return false
        val playlist = (application as MusicaApp).playlists.playlists.firstOrNull { it.id == group.key }
        if (playlist == null || !playlist.editable) return false
        AlertDialog.Builder(this)
            .setTitle(R.string.borrar_lista_titulo)
            .setMessage(R.string.borrar_lista_texto)
            .setPositiveButton(R.string.borrar) { _, _ ->
                (application as MusicaApp).playlists.deleteOwned(playlist.id)
                showCurrentBrowse()
            }
            .setNegativeButton(R.string.cancelar, null)
            .show()
        return true
    }

    private fun askPlaylistName(defaultName: String, onName: (String) -> Unit) {
        val input = EditText(this)
        input.setText(defaultName)
        input.setSelection(input.text.length)
        input.hint = getString(R.string.nombre_lista)
        val pad = (20 * resources.displayMetrics.density).toInt()
        input.setPadding(pad, pad, pad, pad)
        AlertDialog.Builder(this)
            .setTitle(R.string.nueva_lista)
            .setView(input)
            .setPositiveButton(R.string.guardar) { _, _ ->
                val name = input.text.toString().trim().ifBlank { defaultName }
                onName(name)
            }
            .setNegativeButton(R.string.cancelar, null)
            .show()
    }

    private fun maybeAskBackgroundPlayback() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val prefs = getSharedPreferences("ui", MODE_PRIVATE)
        if (prefs.getBoolean("battery_asked", false)) return
        val pm = getSystemService(PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            prefs.edit().putBoolean("battery_asked", true).apply()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.segundo_plano_titulo)
            .setMessage(R.string.segundo_plano_texto)
            .setPositiveButton(R.string.entendido) { _, _ ->
                prefs.edit().putBoolean("battery_asked", true).apply()
                requestIgnoreBatteryOptimizations()
            }
            .setNegativeButton(R.string.cancelar) { _, _ ->
                prefs.edit().putBoolean("battery_asked", true).apply()
            }
            .show()
    }

    private fun requestIgnoreBatteryOptimizations() {
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
            )
        } catch (_: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (_: Exception) {
            }
        }
    }
}
