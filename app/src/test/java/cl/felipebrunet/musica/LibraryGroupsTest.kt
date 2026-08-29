package cl.felipebrunet.musica

import cl.felipebrunet.musica.data.LibraryGroups
import cl.felipebrunet.musica.data.LibraryRepository
import cl.felipebrunet.musica.data.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryGroupsTest {

    @Test
    fun albumsGroupByArtistAndAlbumAndSortByDiscTrack() {
        val tracks = listOf(
            track(title = "B", album = "Wall", artist = "Floyd", disc = 1, number = 2),
            track(title = "A", album = "Wall", artist = "Floyd", disc = 1, number = 1),
            track(title = "C", album = "Dark", artist = "Floyd", disc = 1, number = 1)
        )
        val albums = LibraryGroups.albums(tracks)
        assertEquals(2, albums.size)
        assertEquals("Dark", albums[0].title)
        assertEquals("Wall", albums[1].title)
        assertEquals(listOf("A", "B"), albums[1].tracks.map { it.title })
    }

    @Test
    fun foldersGroupByPathAndSortByFileName() {
        val tracks = listOf(
            track(title = "Z", display = "02-z.mp3", folder = "/Music/Disco", folderName = "Disco"),
            track(title = "A", display = "01-a.mp3", folder = "/Music/Disco", folderName = "Disco"),
            track(title = "X", display = "x.flac", folder = "/Music/Otro", folderName = "Otro")
        )
        val folders = LibraryGroups.folders(tracks)
        assertEquals(2, folders.size)
        assertEquals(listOf("01-a.mp3", "02-z.mp3"), folders[0].tracks.map { it.displayName })
    }

    @Test
    fun trackCountLabelSpanish() {
        assertEquals("1 canción", LibraryGroups.trackCountLabel(1))
        assertEquals("12 canciones", LibraryGroups.trackCountLabel(12))
    }

    @Test
    fun supportedAudioByExtensionAndMime() {
        assertTrue(LibraryRepository.isSupportedAudio("tema.mp3", null))
        assertTrue(LibraryRepository.isSupportedAudio("tema.FLAC", null))
        assertTrue(LibraryRepository.isSupportedAudio("tema.ogg", null))
        assertTrue(LibraryRepository.isSupportedAudio("tema.wav", null))
        assertTrue(LibraryRepository.isSupportedAudio("tema.aac", null))
        assertTrue(LibraryRepository.isSupportedAudio("sin_ext", "audio/mpeg"))
        assertFalse(LibraryRepository.isSupportedAudio("foto.jpg", "image/jpeg"))
        assertFalse(LibraryRepository.isSupportedAudio("video.mp4", "video/mp4"))
    }

    @Test
    fun formatDuration() {
        assertEquals("", LibraryRepository.formatDuration(0))
        assertEquals("3:05", LibraryRepository.formatDuration(185_000))
    }

    @Test
    fun canonicalPathMatchesMediaStoreAndFile() {
        assertEquals(
            "music/disco",
            LibraryRepository.canonicalStoragePath("/storage/emulated/0/Music/Disco")
        )
        assertEquals(
            "music/disco",
            LibraryRepository.canonicalStoragePath("Music/Disco")
        )
        assertEquals(
            "music/disco",
            LibraryRepository.canonicalStoragePath("/sdcard/Music/Disco")
        )
        assertEquals(
            "music/disco",
            LibraryRepository.canonicalStoragePath("/storage/12F5-1A2B/Music/Disco")
        )
        assertEquals(
            "music/disco/01-a.mp3",
            LibraryRepository.identityKey("01-a.mp3", "Music/Disco")
        )
        assertEquals(
            LibraryRepository.identityKey("01-a.mp3", "Music/Disco"),
            LibraryRepository.identityKey("01-A.mp3", "/storage/emulated/0/Music/Disco")
        )
    }

    @Test
    fun cleanUnknownTags() {
        assertEquals("Artista desconocido", LibraryRepository.cleanArtist("<unknown>"))
        assertEquals("Álbum desconocido", LibraryRepository.cleanAlbum("unknown"))
        assertEquals("Tema", LibraryRepository.cleanTitle(null, "Tema.mp3"))
    }

    private fun track(
        title: String,
        album: String = "Album",
        artist: String = "Artist",
        disc: Int = 1,
        number: Int = 1,
        display: String = "$title.mp3",
        folder: String = "/Music/$album",
        folderName: String = album
    ): Track {
        return Track(
            id = title.hashCode().toLong(),
            uri = "content://media/external/audio/media/${title.hashCode()}",
            title = title,
            artist = artist,
            album = album,
            durationMs = 1000L,
            trackNumber = number,
            discNumber = disc,
            displayName = display,
            folderPath = folder,
            folderName = folderName,
            path = null
        )
    }
}
