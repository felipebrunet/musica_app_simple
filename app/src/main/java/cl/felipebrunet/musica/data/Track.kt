package cl.felipebrunet.musica.data

data class Track(
    val id: Long,
    val uri: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val trackNumber: Int,
    val discNumber: Int,
    val displayName: String,
    val folderPath: String,
    val folderName: String,
    val path: String?
) {
    val albumKey: String get() = "$artist\u0000$album"
}

data class Group(
    val key: String,
    val title: String,
    val subtitle: String,
    val tracks: List<Track>
)
