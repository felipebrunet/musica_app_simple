package cl.felipebrunet.musica.data

object LibraryGroups {

    fun albums(tracks: List<Track>): List<Group> {
        return tracks
            .groupBy { it.albumKey }
            .map { (key, groupTracks) ->
                val first = groupTracks.first()
                Group(
                    key = key,
                    title = first.album,
                    subtitle = first.artist + " · " + trackCountLabel(groupTracks.size),
                    tracks = sortAlbumTracks(groupTracks)
                )
            }
            .sortedBy { it.title.lowercase() }
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
            .sortedBy { it.title.lowercase() }
    }

    fun sortAlbumTracks(tracks: List<Track>): List<Track> {
        return tracks.sortedWith(
            compareBy<Track> { it.discNumber }
                .thenBy { it.trackNumber }
                .thenBy { it.title.lowercase() }
        )
    }

    fun sortFolderTracks(tracks: List<Track>): List<Track> {
        return tracks.sortedWith(
            compareBy<Track> { it.displayName.lowercase() }
                .thenBy { it.title.lowercase() }
        )
    }

    fun trackCountLabel(count: Int): String {
        return if (count == 1) "1 canción" else "$count canciones"
    }
}
