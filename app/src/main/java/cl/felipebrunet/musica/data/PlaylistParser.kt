package cl.felipebrunet.musica.data

import java.io.File
import java.util.Locale

object PlaylistParser {

    private val PLAYLIST_EXT = setOf("m3u", "m3u8", "pls", "txt")

    fun isPlaylistFile(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase(Locale.US)
        return ext in PLAYLIST_EXT
    }

    fun pathsFromText(text: String, playlistDir: String?): List<String> {
        val lines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val isPls = lines.any { it.trim().startsWith("[playlist]", ignoreCase = true) } ||
            lines.any { it.trim().startsWith("File", ignoreCase = true) && it.contains('=') }

        val raw = if (isPls) {
            lines.mapNotNull { line ->
                val trimmed = line.trim()
                val eq = trimmed.indexOf('=')
                if (eq <= 0) return@mapNotNull null
                val key = trimmed.substring(0, eq)
                if (!key.startsWith("File", ignoreCase = true)) return@mapNotNull null
                if (key.equals("Filelist", ignoreCase = true)) return@mapNotNull null
                trimmed.substring(eq + 1).trim().trim('"')
            }
        } else {
            lines.map { it.trim() }.filter { line ->
                line.isNotEmpty() &&
                    !line.startsWith("#") &&
                    !line.startsWith(";") &&
                    !line.startsWith("[")
            }
        }

        return raw.mapNotNull { resolvePath(it, playlistDir) }.filter { it.isNotBlank() }
    }

    fun resolveTracks(paths: List<String>, library: List<Track>): List<Track> {
        if (paths.isEmpty() || library.isEmpty()) return emptyList()
        val byPath = HashMap<String, Track>(library.size * 2)
        val byName = HashMap<String, MutableList<Track>>()
        for (track in library) {
            val path = track.path?.let { normalizePath(it) }
            if (path != null) byPath[path] = track
            val uriPath = uriToPath(track.uri)?.let { normalizePath(it) }
            if (uriPath != null && uriPath !in byPath) byPath[uriPath] = track
            val name = track.displayName.lowercase(Locale.US)
            if (name.isNotEmpty()) {
                byName.getOrPut(name) { ArrayList() }.add(track)
            }
        }

        val out = ArrayList<Track>(paths.size)
        for (raw in paths) {
            val normalized = normalizePath(raw)
            val exact = byPath[normalized]
            if (exact != null) {
                out.add(exact)
                continue
            }
            val fromUri = library.firstOrNull { it.uri == raw || it.uri == normalized }
            if (fromUri != null) {
                out.add(fromUri)
                continue
            }
            val fileName = normalized.substringAfterLast('/')
            val parent = normalized.substringBeforeLast('/', "")
            val candidates = byName[fileName].orEmpty()
            val matched = when {
                candidates.size == 1 -> candidates[0]
                candidates.isNotEmpty() && parent.isNotEmpty() -> {
                    candidates.firstOrNull { track ->
                        val p = track.path?.let { normalizePath(it) } ?: ""
                        p.endsWith("/$fileName") && p.contains(parent.substringAfterLast('/'))
                    } ?: candidates.firstOrNull { track ->
                        normalizePath(track.folderPath).endsWith(parent.substringAfterLast('/'))
                    }
                }
                else -> null
            }
            if (matched != null) out.add(matched)
        }
        return out
    }

    private fun resolvePath(entry: String, playlistDir: String?): String? {
        var value = entry.trim().trim('"').replace('\\', '/')
        if (value.isEmpty()) return null
        if (value.startsWith("file:", ignoreCase = true)) {
            value = value.removePrefix("file://").removePrefix("file:")
        }
        if (looksAbsolute(value) || value.contains("://")) {
            return value
        }
        val base = playlistDir?.replace('\\', '/')?.trimEnd('/') ?: return value
        return "$base/$value"
    }

    private fun looksAbsolute(path: String): Boolean {
        if (path.startsWith("/")) return true
        return path.length >= 3 && path[1] == ':' && (path[2] == '/' || path[2] == '\\')
    }

    private fun uriToPath(uri: String): String? {
        if (uri.startsWith("file://")) return uri.removePrefix("file://")
        if (uri.startsWith("file:")) return uri.removePrefix("file:")
        return null
    }

    fun normalizePath(path: String): String {
        return path.replace('\\', '/').trim().trimEnd('/').lowercase(Locale.US)
    }

    fun safeFileName(name: String): String {
        val cleaned = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
        return cleaned.ifBlank { "lista" }
    }

    fun ownedFile(playlistsDir: File, title: String): File {
        return File(playlistsDir, safeFileName(title) + ".m3u8")
    }
}
