package cl.felipebrunet.musica.data

import java.text.Normalizer
import java.util.Locale

object LibraryGroups {

    const val VARIOUS_ARTISTS = "Varios artistas"
    const val UNKNOWN_ARTIST = "Artista desconocido"
    const val UNKNOWN_ALBUM = "Álbum desconocido"

    private val GENERIC_FOLDER_NAMES = setOf(
        "music", "musica", "música", "download", "downloads", "descargas",
        "documents", "documentos", "audio", "songs", "canciones"
    )

    fun albums(tracks: List<Track>): List<Group> {
        val buckets = LinkedHashMap<String, AlbumBucket>()

        fun add(key: String, title: String, artistLabel: String, addTracks: List<Track>) {
            val existing = buckets[key]
            if (existing == null) {
                buckets[key] = AlbumBucket(key, title, artistLabel, addTracks.toMutableList())
            } else {
                existing.tracks.addAll(addTracks)
            }
        }

        val generic = ArrayList<Track>()
        tracks.groupBy { it.folderPath }.forEach { (path, folderTracks) ->
            val folderName = folderTracks.first().folderName
            if (isGenericFolderName(folderName)) {
                generic.addAll(folderTracks)
            } else {
                val title = albumTitle(folderTracks)
                val artistLabel = artistLabel(folderTracks)
                val mixed = hasMixedAlbums(folderTracks) || artistLabel == VARIOUS_ARTISTS
                val key = if (mixed) {
                    "folder:$path"
                } else {
                    "album:${title.lowercase(Locale.US)}\u0000${artistLabel.lowercase(Locale.US)}"
                }
                add(key, title, artistLabel, folderTracks)
            }
        }

        generic.groupBy { it.album.lowercase(Locale.US) }.forEach { (_, albumTracks) ->
            val title = albumTracks.first().album
            val artistLabel = artistLabel(albumTracks)
            val key = "album:${title.lowercase(Locale.US)}\u0000${artistLabel.lowercase(Locale.US)}"
            add(key, title, artistLabel, albumTracks)
        }

        return buckets.values
            .map { bucket ->
                Group(
                    key = bucket.key,
                    title = bucket.title,
                    subtitle = bucket.artistLabel + " · " + trackCountLabel(bucket.tracks.size),
                    tracks = sortAlbumTracks(bucket.tracks)
                )
            }
            .sortedBy { it.title.lowercase(Locale.US) }
    }

    fun folders(tracks: List<Track>): List<Group> {
        return tracks
            .groupBy { it.folderPath }
            .map { (path, groupTracks) ->
                val first = groupTracks.first()
                Group(
                    key = path,
                    title = first.folderName,
                    subtitle = path + " · " + trackCountLabel(groupTracks.size),
                    tracks = sortFolderTracks(groupTracks)
                )
            }
            .sortedBy { it.title.lowercase(Locale.US) }
    }

    fun sortAlbumTracks(tracks: List<Track>): List<Track> {
        if (isCompilation(tracks)) {
            return sortFolderTracks(tracks)
        }
        return tracks.sortedWith(
            compareBy<Track> { it.discNumber }
                .thenBy { if (it.trackNumber > 0) it.trackNumber else Int.MAX_VALUE }
                .thenBy { it.displayName.lowercase(Locale.US) }
                .thenBy { it.title.lowercase(Locale.US) }
        )
    }

    fun sortFolderTracks(tracks: List<Track>): List<Track> {
        return tracks.sortedWith(
            compareBy<Track> { it.displayName.lowercase(Locale.US) }
                .thenBy { it.title.lowercase(Locale.US) }
        )
    }

    fun isCompilation(tracks: List<Track>): Boolean {
        val albums = tracks.map { it.album }.filter { it != UNKNOWN_ALBUM }.toSet()
        val artists = tracks.map { it.artist }.filter { it != UNKNOWN_ARTIST }.toSet()
        return albums.size > 1 || artists.size > 1
    }

    fun isGenericFolderName(name: String): Boolean {
        return foldForSearch(name) in GENERIC_FOLDER_NAMES
    }

    fun filterGroups(groups: List<Group>, query: String): List<Group> {
        val q = foldForSearch(query).trim()
        if (q.isEmpty()) return groups
        return groups.filter { groupMatches(it, q) }
    }

    fun groupMatches(group: Group, foldedQuery: String): Boolean {
        if (foldForSearch(group.title).contains(foldedQuery)) return true
        if (foldForSearch(group.subtitle).contains(foldedQuery)) return true
        return group.tracks.any { trackMatches(it, foldedQuery) }
    }

    fun trackMatches(track: Track, foldedQuery: String): Boolean {
        return foldForSearch(track.title).contains(foldedQuery) ||
            foldForSearch(track.artist).contains(foldedQuery) ||
            foldForSearch(track.album).contains(foldedQuery) ||
            foldForSearch(track.displayName).contains(foldedQuery) ||
            foldForSearch(track.folderName).contains(foldedQuery)
    }

    fun foldForSearch(text: String): String {
        val lower = text.lowercase(Locale.US)
        val nfd = Normalizer.normalize(lower, Normalizer.Form.NFD)
        return nfd.replace(COMBINING_MARKS, "")
    }

    fun trackCountLabel(count: Int): String {
        return if (count == 1) "1 canción" else "$count canciones"
    }

    internal fun albumTitle(tracks: List<Track>): String {
        val known = tracks.map { it.album }.filter { it != UNKNOWN_ALBUM }.distinct()
        return when {
            known.size == 1 -> known[0]
            else -> tracks.first().folderName
        }
    }

    internal fun artistLabel(tracks: List<Track>): String {
        val known = tracks.map { it.artist }.filter { it != UNKNOWN_ARTIST }.distinct()
        return when {
            known.size == 1 -> known[0]
            known.isEmpty() -> UNKNOWN_ARTIST
            else -> VARIOUS_ARTISTS
        }
    }

    private fun hasMixedAlbums(tracks: List<Track>): Boolean {
        return tracks.map { it.album }.filter { it != UNKNOWN_ALBUM }.distinct().size > 1
    }

    private data class AlbumBucket(
        val key: String,
        val title: String,
        val artistLabel: String,
        val tracks: MutableList<Track>
    )

    private val COMBINING_MARKS = Regex("\\p{Mn}+")
}
