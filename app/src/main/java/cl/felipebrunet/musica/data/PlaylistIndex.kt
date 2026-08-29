package cl.felipebrunet.musica.data

import java.util.Locale

object PlaylistIndex {

    fun accept(
        playlist: Playlist,
        seenPaths: MutableSet<String>,
        seenContent: MutableSet<String>
    ): Boolean {
        val path = pathKey(playlist.id)
        if (path.isNotEmpty() && !seenPaths.add(path)) return false
        return seenContent.add(contentKey(playlist))
    }

    fun pathKey(id: String): String {
        if (id.startsWith("mediastore:")) return id
        return LibraryRepository.canonicalStoragePath(id)
    }

    fun contentKey(playlist: Playlist): String {
        val title = playlist.title.trim().lowercase(Locale.US)
        val tracks = playlist.tracks.map { trackKey(it) }.sorted()
        return title + "\u0000" + tracks.joinToString("\u0001")
    }

    fun trackKey(track: Track): String {
        val identity = LibraryRepository.identityKey(track.displayName, track.folderPath)
        return identity.ifBlank { track.uri }
    }
}
