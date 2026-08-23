package cl.felipebrunet.musica.data

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.util.Locale

class LibraryRepository {

    @Volatile
    var tracks: List<Track> = emptyList()
        private set

    fun albums(): List<Group> = LibraryGroups.albums(tracks)

    fun folders(): List<Group> = LibraryGroups.folders(tracks)

    fun scan(context: Context): List<Track> {
        val fromStore = queryMediaStore(context)
        val seen = HashSet<String>(fromStore.size * 2)
        val merged = ArrayList<Track>(fromStore.size + 32)
        for (track in fromStore) {
            merged.add(track)
            seen.add(dedupeKey(track))
        }
        for (track in scanFolders(context)) {
            val key = dedupeKey(track)
            if (seen.add(key)) {
                merged.add(track)
            }
        }
        tracks = merged
        return merged
    }

    private fun dedupeKey(track: Track): String {
        val path = track.path?.let { normalizePath(it) }
            return path ?: track.uri
    }

    companion object {
        private val AUDIO_EXTENSIONS = setOf("mp3", "flac", "ogg", "oga", "wav", "aac", "m4a")

        private val UNKNOWN_ARTIST_MARKERS = setOf("<unknown>", "unknown", "<unknown artist>")
        private val UNKNOWN_ALBUM_MARKERS = setOf("<unknown>", "unknown", "<unknown album>")

        @Suppress("DEPRECATION")
        fun queryMediaStore(context: Context): List<Track> {
            val result = ArrayList<Track>()
            val collectionUris = mediaCollections(context)
            val projection = mutableListOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.TRACK,
                MediaStore.Audio.Media.DISPLAY_NAME,
                MediaStore.Audio.Media.MIME_TYPE
            )
            if (Build.VERSION.SDK_INT >= 29) {
                projection.add(MediaStore.Audio.Media.RELATIVE_PATH)
            } else {
                projection.add(MediaStore.Audio.Media.DATA)
            }

            val selection = "${MediaStore.Audio.Media.IS_MUSIC}!=0"

            for (collection in collectionUris) {
                try {
                    context.contentResolver.query(
                        collection,
                        projection.toTypedArray(),
                        selection,
                        null,
                        null
                    )?.use { cursor ->
                        val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                        val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                        val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                        val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                        val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                        val trackCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TRACK)
                        val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
                        val mimeCol = cursor.getColumnIndex(MediaStore.Audio.Media.MIME_TYPE)
                        val pathCol = if (Build.VERSION.SDK_INT >= 29) {
                            cursor.getColumnIndex(MediaStore.Audio.Media.RELATIVE_PATH)
                        } else {
                            cursor.getColumnIndex(MediaStore.Audio.Media.DATA)
                        }

                        while (cursor.moveToNext()) {
                            val mime = if (mimeCol >= 0) cursor.getString(mimeCol) else null
                            val displayName = cursor.getString(nameCol) ?: ""
                            if (!isSupportedAudio(displayName, mime)) continue

                            val id = cursor.getLong(idCol)
                            val rawTrack = cursor.getInt(trackCol)
                            val disc = if (rawTrack >= 1000) rawTrack / 1000 else 1
                            val number = when {
                                rawTrack >= 1000 -> rawTrack % 1000
                                rawTrack > 0 -> rawTrack
                                else -> 0
                            }
                            val location = if (pathCol >= 0) cursor.getString(pathCol) else null
                            val folder = folderFromStore(location, displayName)
                            result.add(
                                Track(
                                    id = id,
                                    uri = ContentUris.withAppendedId(collection, id).toString(),
                                    title = cleanTitle(cursor.getString(titleCol), displayName),
                                    artist = cleanArtist(cursor.getString(artistCol)),
                                    album = cleanAlbum(cursor.getString(albumCol)),
                                    durationMs = cursor.getLong(durationCol).coerceAtLeast(0L),
                                    trackNumber = number,
                                    discNumber = disc,
                                    displayName = displayName,
                                    folderPath = folder.first,
                                    folderName = folder.second,
                                    path = if (Build.VERSION.SDK_INT < 29) location else null
                                )
                            )
                        }
                    }
                } catch (_: SecurityException) {
                    // Permission not granted yet.
                }
            }
            return result
        }

        private fun mediaCollections(context: Context): List<Uri> {
            return if (Build.VERSION.SDK_INT >= 29) {
                MediaStore.getExternalVolumeNames(context).map { volume ->
                    MediaStore.Audio.Media.getContentUri(volume)
                }
            } else {
                listOf(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI)
            }
        }

        fun scanFolders(context: Context): List<Track> {
            val found = ArrayList<Track>()
            for (root in musicRoots(context)) {
                walkAudioFiles(root, depth = 0, out = found)
            }
            return found
        }

        @Suppress("DEPRECATION")
        private fun musicRoots(context: Context): List<File> {
            val roots = LinkedHashSet<File>()
            addIfExists(roots, Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC))
            addIfExists(roots, Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS))
            addIfExists(roots, Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS))

            val dirs = context.getExternalFilesDirs(null)
            if (dirs != null) {
                for (filesDir in dirs) {
                    val volume = filesDir?.parentFile?.parentFile?.parentFile?.parentFile ?: continue
                    addIfExists(roots, File(volume, Environment.DIRECTORY_MUSIC))
                    addIfExists(roots, File(volume, Environment.DIRECTORY_DOWNLOADS))
                    addIfExists(roots, File(volume, "Download"))
                    addIfExists(roots, File(volume, Environment.DIRECTORY_DOCUMENTS))
                }
            }
            return roots.toList()
        }

        private fun addIfExists(into: MutableSet<File>, file: File?) {
            if (file != null && file.exists()) {
                into.add(file)
            }
        }

        private fun walkAudioFiles(dir: File, depth: Int, out: MutableList<Track>) {
            if (depth > 8 || !dir.canRead()) return
            val children = dir.listFiles() ?: return
            for (child in children) {
                if (child.isHidden) continue
                if (child.isDirectory) {
                    if (child.name.equals("Android", ignoreCase = true)) continue
                    walkAudioFiles(child, depth + 1, out)
                } else if (child.isFile && isSupportedAudio(child.name, null) && child.canRead()) {
                    out.add(trackFromFile(child))
                }
            }
        }

        private fun trackFromFile(file: File): Track {
            val folder = file.parentFile
            val folderPath = folder?.absolutePath ?: file.absolutePath
            return Track(
                id = file.absolutePath.hashCode().toLong(),
                uri = Uri.fromFile(file).toString(),
                title = file.nameWithoutExtension,
                artist = "Artista desconocido",
                album = folder?.name ?: "Álbum desconocido",
                durationMs = 0L,
                trackNumber = 0,
                discNumber = 1,
                displayName = file.name,
                folderPath = folderPath,
                folderName = folder?.name ?: folderPath,
                path = file.absolutePath
            )
        }

        private fun folderFromStore(location: String?, displayName: String): Pair<String, String> {
            if (location.isNullOrBlank()) {
                return "Música" to "Música"
            }
            val asPath = location.replace('\\', '/')
            val folderPath = if (asPath.contains('/') && asPath.substringAfterLast('/') == displayName) {
                asPath.substringBeforeLast('/')
            } else {
                asPath.trimEnd('/')
            }
            val name = folderPath.trimEnd('/').substringAfterLast('/').ifBlank { "Música" }
            return folderPath to name
        }

        fun isSupportedAudio(name: String, mime: String?): Boolean {
            val ext = name.substringAfterLast('.', "").lowercase(Locale.US)
            if (ext in AUDIO_EXTENSIONS) return true
            val m = mime?.lowercase(Locale.US) ?: return false
            return m.startsWith("audio/") && (
                m.contains("mpeg") ||
                    m.contains("mp3") ||
                    m.contains("flac") ||
                    m.contains("ogg") ||
                    m.contains("wav") ||
                    m.contains("aac") ||
                    m.contains("mp4") ||
                    m.contains("x-m4a")
                )
        }

        fun cleanTitle(title: String?, displayName: String): String {
            val t = title?.trim().orEmpty()
            if (t.isEmpty() || t == "<unknown>") {
                return displayName.substringBeforeLast('.').ifBlank { "Sin título" }
            }
            return t
        }

        fun cleanArtist(artist: String?): String {
            val a = artist?.trim().orEmpty()
            if (a.isEmpty() || a.lowercase(Locale.US) in UNKNOWN_ARTIST_MARKERS) {
                return "Artista desconocido"
            }
            return a
        }

        fun cleanAlbum(album: String?): String {
            val a = album?.trim().orEmpty()
            if (a.isEmpty() || a.lowercase(Locale.US) in UNKNOWN_ALBUM_MARKERS) {
                return "Álbum desconocido"
            }
            return a
        }

        fun formatDuration(durationMs: Long): String {
            if (durationMs <= 0L) return ""
            val totalSec = durationMs / 1000L
            val min = totalSec / 60L
            val sec = totalSec % 60L
            return "%d:%02d".format(Locale.US, min, sec)
        }

        private fun normalizePath(path: String): String {
            return path.replace('\\', '/').lowercase(Locale.US).trimEnd('/')
        }
    }
}
