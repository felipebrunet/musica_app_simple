package cl.felipebrunet.musica

import cl.felipebrunet.musica.data.PlaylistParser
import cl.felipebrunet.musica.data.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistParserTest {

    @Test
    fun parseM3uSkipsCommentsAndResolvesRelative() {
        val text = """
            #EXTM3U
            #EXTINF:123,Uno
            01-a.mp3
            /storage/emulated/0/Music/Disco/02-b.flac
            file:///storage/emulated/0/Music/Disco/03-c.ogg
        """.trimIndent()
        val paths = PlaylistParser.pathsFromText(text, "/storage/emulated/0/Music/Disco")
        assertEquals(
            listOf(
                "/storage/emulated/0/Music/Disco/01-a.mp3",
                "/storage/emulated/0/Music/Disco/02-b.flac",
                "/storage/emulated/0/Music/Disco/03-c.ogg"
            ),
            paths
        )
    }

    @Test
    fun parsePlsAndPulsarTxt() {
        val pls = """
            [playlist]
            File1=/sdcard/Music/a.mp3
            Title1=A
            File2=/sdcard/Music/b.mp3
            NumberOfEntries=2
        """.trimIndent()
        assertEquals(
            listOf("/sdcard/Music/a.mp3", "/sdcard/Music/b.mp3"),
            PlaylistParser.pathsFromText(pls, null)
        )

        val txt = """
            /sdcard/Music/x.mp3
            /sdcard/Music/y.flac
        """.trimIndent()
        assertEquals(2, PlaylistParser.pathsFromText(txt, null).size)
    }

    @Test
    fun resolveByPathAndFileName() {
        val library = listOf(
            track("A", path = "/storage/emulated/0/Music/Disco/01-a.mp3", display = "01-a.mp3"),
            track("B", path = "/storage/emulated/0/Music/Otro/02-b.mp3", display = "02-b.mp3")
        )
        val resolved = PlaylistParser.resolveTracks(
            listOf("/storage/emulated/0/Music/Disco/01-a.mp3", "02-b.mp3"),
            library
        )
        assertEquals(listOf("A", "B"), resolved.map { it.title })
    }

    @Test
    fun safeFileNameStripsIllegalChars() {
        assertEquals("Viaje_al_sur", PlaylistParser.safeFileName("Viaje/al:sur"))
        assertTrue(PlaylistParser.isPlaylistFile("favoritos.m3u8"))
        assertTrue(PlaylistParser.isPlaylistFile("pulsar.txt"))
    }

    private fun track(title: String, path: String, display: String): Track {
        return Track(
            id = title.hashCode().toLong(),
            uri = "file://$path",
            title = title,
            artist = "Artist",
            album = "Album",
            durationMs = 1000L,
            trackNumber = 1,
            discNumber = 1,
            displayName = display,
            folderPath = path.substringBeforeLast('/'),
            folderName = "folder",
            path = path
        )
    }
}
