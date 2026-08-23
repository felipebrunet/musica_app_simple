# Tiny local player: keep our code and MediaSession entry points so
# minify still works on Android 9 (Galaxy A10 / API 28).
-keep class cl.felipebrunet.musica.** { *; }
-keep class androidx.media.session.MediaButtonReceiver { *; }
-keep class androidx.media.** { *; }
-dontwarn androidx.media.**
