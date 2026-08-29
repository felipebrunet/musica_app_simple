package cl.felipebrunet.musica.data

import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.nio.charset.Charset
import java.util.UUID

class PlaylistRepository {

    @Volatile
    var playlists: List<Playlist> = emptyList()
        private set

    fun groups(): List<Group> = playlists.map { it.asGroup() }

    fun scan(context: Context, library: List<Track>): List<Playlist> {
        val found = ArrayList<Playlist>()
        val seenPaths = HashSet<String>()
        val seenContent = HashSet<String>()

        fun add(parsed: Playlist?) {
            if (parsed != null && PlaylistIndex.accept(parsed, seenPaths, seenContent)) {
                found.add(parsed)
            }
        }

        for (file in ownedDir(context).listFiles().orEmpty()) {
            add(playlistFromFile(file, library, editable = true, source = "Lista propia"))
        }

        for (dir in pulsarDirs(context)) {
            for (file in listPlaylistFiles(dir, includeTxt = true)) {
                add(playlistFromFile(file, library, editable = false, source = "Pulsar"))
            }
        }

        for (root in LibraryRepository.storageRoots(context)) {
            walkPlaylistFiles(root, depth = 0) { file ->
                add(playlistFromFile(file, library, editable = false, source = "Archivo"))
            }
        }

        for (playlist in queryMediaStorePlaylists(context, library)) {
            add(playlist)
        }
        playlists = found.sortedBy { it.title.lowercase() }
        return playlists
    }

    fun create(context: Context, title: String, tracks: List<Track>): Playlist {
        val name = title.trim().ifBlank { "Lista" }
        var file = PlaylistParser.ownedFile(ownedDir(context), name)
        if (file.exists()) {
            file = PlaylistParser.ownedFile(ownedDir(context), "$name-${UUID.randomUUID().toString().take(4)}")
        }
        writeM3u(file, tracks)
        val playlist = Playlist(
            id = file.absolutePath,
            title = file.nameWithoutExtension,
            tracks = tracks,
            editable = true,
            sourceLabel = "Lista propia"
        )
        playlists = (playlists + playlist).sortedBy { it.title.lowercase() }
        return playlist
    }

    fun deleteOwned(playlistId: String): Boolean {
        val current = playlists.firstOrNull { it.id == playlistId && it.editable } ?: return false
        File(current.id).delete()
        playlists = playlists.filterNot { it.id == playlistId }
        return true
    }

    private fun playlistFromFile(
        file: File,
        library: List<Track>,
        editable: Boolean,
        source: String
    ): Playlist? {
        if (!file.isFile || !file.canRead()) return null
        if (!PlaylistParser.isPlaylistFile(file.name)) return null
        val text = readText(file) ?: return null
        val paths = PlaylistParser.pathsFromText(text, file.parent)
        val tracks = PlaylistParser.resolveTracks(paths, library)
        if (tracks.isEmpty()) return null
        return Playlist(
            id = file.absolutePath,
            title = file.nameWithoutExtension,
            tracks = tracks,
            editable = editable,
            sourceLabel = source
        )
    }

    private fun readText(file: File): String? {
        return try {
            val bytes = file.readBytes()
            if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
                String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
            } else {
                String(bytes, Charset.forName("UTF-8"))
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun writeM3u(file: File, tracks: List<Track>) {
        file.parentFile?.mkdirs()
        val body = buildString {
            appendLine("#EXTM3U")
            for (track in tracks) {
                append("#EXTINF:-1,")
                append(track.artist)
                append(" - ")
                appendLine(track.title)
                appendLine(track.path ?: track.uri)
            }
        }
        file.writeText(body, Charsets.UTF_8)
    }

    private fun ownedDir(context: Context): File {
        val dir = File(context.filesDir, "playlists")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun pulsarDirs(context: Context): List<File> {
        val dirs = ArrayList<File>()
        for (volume in volumeRoots(context)) {
            dirs.add(File(volume, "Music/pulsar/playlists"))
            dirs.add(File(volume, "Android/data/com.rhmsoft.pulsar/files/playlists"))
            dirs.add(File(volume, "Android/data/com.rhmsoft.pulsar.pro/files/playlists"))
        }
        return dirs.filter { it.exists() && it.canRead() }
    }

    @Suppress("DEPRECATION")
    private fun volumeRoots(context: Context): List<File> {
        val roots = LinkedHashSet<File>()
        val primary = Environment.getExternalStorageDirectory()
        if (primary != null) roots.add(primary)
        val dirs = context.getExternalFilesDirs(null)
        if (dirs != null) {
            for (filesDir in dirs) {
                val volume = filesDir?.parentFile?.parentFile?.parentFile?.parentFile ?: continue
                roots.add(volume)
            }
        }
        return roots.toList()
    }

    private fun listPlaylistFiles(dir: File, includeTxt: Boolean): List<File> {
        if (!dir.isDirectory || !dir.canRead()) return emptyList()
        return dir.listFiles()
            ?.filter { it.isFile && PlaylistParser.isPlaylistFile(it.name, includeTxt) }
            .orEmpty()
    }

    private fun walkPlaylistFiles(dir: File, depth: Int, out: (File) -> Unit) {
        if (depth > 8 || !dir.canRead()) return
        val children = dir.listFiles() ?: return
        for (child in children) {
            if (child.isHidden) continue
            if (child.isDirectory) {
                if (child.name.equals("Android", ignoreCase = true)) continue
                walkPlaylistFiles(child, depth + 1, out)
            } else if (child.isFile && PlaylistParser.isPlaylistFile(child.name, includeTxt = false)) {
                out(child)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun queryMediaStorePlaylists(
        context: Context,
        library: List<Track>
    ): List<Playlist> {
        if (Build.VERSION.SDK_INT >= 31) return emptyList()
        val result = ArrayList<Playlist>()
        val byId = library.associateBy { it.id }
        try {
            context.contentResolver.query(
                MediaStore.Audio.Playlists.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Audio.Playlists._ID, MediaStore.Audio.Playlists.NAME),
                null,
                null,
                MediaStore.Audio.Playlists.NAME
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Playlists._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Playlists.NAME)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val key = "mediastore:$id"
                    val name = cursor.getString(nameCol)?.trim().orEmpty().ifBlank { "Lista" }
                    val tracks = queryMembers(context, id, byId, library)
                    if (tracks.isEmpty()) continue
                    result.add(
                        Playlist(
                            id = key,
                            title = name,
                            tracks = tracks,
                            editable = false,
                            sourceLabel = "Teléfono"
                        )
                    )
                }
            }
        } catch (_: SecurityException) {
        } catch (_: Exception) {
        }
        return result
    }

    @Suppress("DEPRECATION")
    private fun queryMembers(
        context: Context,
        playlistId: Long,
        byId: Map<Long, Track>,
        library: List<Track>
    ): List<Track> {
        val tracks = ArrayList<Track>()
        val members = MediaStore.Audio.Playlists.Members.getContentUri("external", playlistId)
        try {
            context.contentResolver.query(
                members,
                arrayOf(
                    MediaStore.Audio.Playlists.Members.AUDIO_ID,
                    MediaStore.Audio.Playlists.Members.DATA
                ),
                null,
                null,
                MediaStore.Audio.Playlists.Members.PLAY_ORDER
            )?.use { cursor ->
                val audioCol = cursor.getColumnIndex(MediaStore.Audio.Playlists.Members.AUDIO_ID)
                val dataCol = cursor.getColumnIndex(MediaStore.Audio.Playlists.Members.DATA)
                while (cursor.moveToNext()) {
                    val audioId = if (audioCol >= 0) cursor.getLong(audioCol) else -1L
                    val fromId = byId[audioId]
                    if (fromId != null) {
                        tracks.add(fromId)
                        continue
                    }
                    if (dataCol >= 0) {
                        val path = cursor.getString(dataCol)
                        if (!path.isNullOrBlank()) {
                            tracks.addAll(PlaylistParser.resolveTracks(listOf(path), library))
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }
        return tracks
    }
}
