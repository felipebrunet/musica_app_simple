package cl.felipebrunet.musica.data

data class Playlist(
    val id: String,
    val title: String,
    val tracks: List<Track>,
    val editable: Boolean,
    val sourceLabel: String
) {
    fun asGroup(): Group {
        return Group(
            key = id,
            title = title,
            subtitle = sourceLabel + " · " + LibraryGroups.trackCountLabel(tracks.size),
            tracks = tracks
        )
    }
}
