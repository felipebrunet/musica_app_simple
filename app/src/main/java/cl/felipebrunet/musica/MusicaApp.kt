package cl.felipebrunet.musica

import android.app.Application
import cl.felipebrunet.musica.data.LibraryRepository
import cl.felipebrunet.musica.data.PlaybackStore
import cl.felipebrunet.musica.data.PlaylistRepository
import cl.felipebrunet.musica.data.Track

class MusicaApp : Application() {
    val library = LibraryRepository()
    val playlists = PlaylistRepository()
    lateinit var playbackStore: PlaybackStore
        private set

    @Volatile
    var pendingQueue: List<Track> = emptyList()

    @Volatile
    var pendingIndex: Int = 0

    override fun onCreate() {
        super.onCreate()
        playbackStore = PlaybackStore(this)
    }
}
