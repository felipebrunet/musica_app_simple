# Local-only player: keep the playback service and MediaSession entry points.
-keep class cl.felipebrunet.musica.playback.PlaybackService { *; }
-keep class androidx.media.session.MediaButtonReceiver { *; }
