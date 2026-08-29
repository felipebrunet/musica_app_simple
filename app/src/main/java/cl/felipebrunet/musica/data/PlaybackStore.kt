package cl.felipebrunet.musica.data

import android.content.Context
import android.net.Uri

class PlaybackStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun save(queue: List<Track>, index: Int, positionMs: Long, playing: Boolean) {
        if (queue.isEmpty() || index !in queue.indices) {
            return
        }
        val current = queue[index]
        prefs.edit()
            .putString(KEY_QUEUE, queue.joinToString("\n") { it.uri })
            .putInt(KEY_INDEX, index)
            .putLong(KEY_POSITION, positionMs.coerceAtLeast(0L))
            .putBoolean(KEY_PLAYING, playing)
            .putString(KEY_TITLE, current.title)
            .putString(KEY_ARTIST, current.artist)
            .putString(KEY_ALBUM, current.album)
            .apply()
    }

    fun lastTitle(): String = prefs.getString(KEY_TITLE, "") ?: ""

    fun lastArtist(): String = prefs.getString(KEY_ARTIST, "") ?: ""

    fun lastPosition(): Long = prefs.getLong(KEY_POSITION, 0L)

    fun lastIndex(): Int = prefs.getInt(KEY_INDEX, 0)

    fun lastPlaying(): Boolean = prefs.getBoolean(KEY_PLAYING, false)

    fun restoreQueue(library: List<Track>): List<Track> {
        val raw = prefs.getString(KEY_QUEUE, null) ?: return emptyList()
        if (raw.isBlank()) return emptyList()
        val byUri = library.associateBy { it.uri }
        val restored = ArrayList<Track>()
        for (line in raw.split('\n')) {
            if (line.isBlank()) continue
            val fromLibrary = byUri[line]
            if (fromLibrary != null) {
                restored.add(fromLibrary)
            } else {
                restored.add(placeholder(line))
            }
        }
        return restored
    }

    private fun placeholder(uriString: String): Track {
        val uri = Uri.parse(uriString)
        val name = uri.lastPathSegment ?: "Sin título"
        return Track(
            id = uriString.hashCode().toLong(),
            uri = uriString,
            title = name.substringBeforeLast('.'),
            artist = lastArtist().ifBlank { "Artista desconocido" },
            album = prefs.getString(KEY_ALBUM, "Álbum desconocido") ?: "Álbum desconocido",
            durationMs = 0L,
            trackNumber = 0,
            discNumber = 1,
            displayName = name,
            folderPath = "",
            folderName = "",
            path = if (uri.scheme == "file") uri.path else null
        )
    }

    companion object {
        private const val PREFS = "playback"
        private const val KEY_QUEUE = "queue"
        private const val KEY_INDEX = "index"
        private const val KEY_POSITION = "position"
        private const val KEY_PLAYING = "playing"
        private const val KEY_TITLE = "title"
        private const val KEY_ARTIST = "artist"
        private const val KEY_ALBUM = "album"
    }
}
