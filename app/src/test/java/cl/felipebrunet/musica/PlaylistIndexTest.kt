package cl.felipebrunet.musica

import cl.felipebrunet.musica.data.Playlist
import cl.felipebrunet.musica.data.PlaylistIndex
import cl.felipebrunet.musica.data.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistIndexTest {

    @Test
    fun dropsSameFileViaDifferentStoragePaths() {
        val seenPaths = HashSet<String>()
        val seenContent = HashSet<String>()
        val first = playlist(
            id = "/storage/emulated/0/Music/pulsar/playlists/viaje.m3u",
            title = "Viaje"
        )
        val duplicate = playlist(
            id = "/sdcard/Music/pulsar/playlists/viaje.m3u",
            title = "Viaje"
        )
        assertTrue(PlaylistIndex.accept(first, seenPaths, seenContent))
        assertFalse(PlaylistIndex.accept(duplicate, seenPaths, seenContent))
    }

    @Test
    fun dropsSameTitleAndTracksFromAnotherSource() {
        val seenPaths = HashSet<String>()
        val seenContent = HashSet<String>()
        val file = playlist(
            id = "/storage/emulated/0/Music/pulsar/playlists/70s.m3u",
            title = "70s Greatest Hits",
            tracks = listOf(track("A", "01-a.mp3"), track("B", "02-b.mp3"))
        )
        val mediaStore = playlist(
            id = "mediastore:12",
            title = "70s Greatest Hits",
            tracks = listOf(track("B", "02-b.mp3"), track("A", "01-a.mp3"))
        )
        assertTrue(PlaylistIndex.accept(file, seenPaths, seenContent))
        assertFalse(PlaylistIndex.accept(mediaStore, seenPaths, seenContent))
    }

    @Test
    fun keepsSameTitleWithDifferentSongs() {
        val seenPaths = HashSet<String>()
        val seenContent = HashSet<String>()
        val one = playlist("file-a", "Favoritos", listOf(track("A", "a.mp3")))
        val two = playlist("file-b", "Favoritos", listOf(track("B", "b.mp3")))
        assertTrue(PlaylistIndex.accept(one, seenPaths, seenContent))
        assertTrue(PlaylistIndex.accept(two, seenPaths, seenContent))
    }

    @Test
    fun canonicalPathKey() {
        assertEquals(
            PlaylistIndex.pathKey("/storage/emulated/0/Music/listas/viaje.m3u8"),
            PlaylistIndex.pathKey("/sdcard/Music/listas/viaje.m3u8")
        )
        assertEquals("mediastore:9", PlaylistIndex.pathKey("mediastore:9"))
    }

    private fun playlist(
        id: String,
        title: String,
        tracks: List<Track> = listOf(track("A", "a.mp3"))
    ): Playlist {
        return Playlist(
            id = id,
            title = title,
            tracks = tracks,
            editable = false,
            sourceLabel = "Pulsar"
        )
    }

    private fun track(title: String, display: String): Track {
        return Track(
            id = title.hashCode().toLong(),
            uri = "content://media/$title",
            title = title,
            artist = "Artist",
            album = "Album",
            durationMs = 1000L,
            trackNumber = 1,
            discNumber = 1,
            displayName = display,
            folderPath = "music/disco",
            folderName = "Disco",
            path = "/storage/emulated/0/Music/Disco/$display"
        )
    }
}
