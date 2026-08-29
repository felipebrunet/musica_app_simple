# Música Simple

Reproductor de música **local**, liviano y sin anuncios. Está pensado para el papá de Felipe: copia CDs al teléfono (o a la tarjeta SD) y los escucha por Bluetooth en el auto.

No es un clon de Pulsar. No hay temas, Chromecast, Last.fm, tienda, analítica ni red. Solo lee archivos que ya están en el aparato y los reproduce.

Licencia: [MIT](LICENSE).

## Para qué teléfono

Objetivo: **Samsung Galaxy A10** (2 GB de RAM, Exynos 7884, Bluetooth 5), **Android 9 a 11** (One UI).

- `minSdk` 28 (Android 9)
- Se instala también en API más nuevas, pero el diseño es para Pie–11
- El APK incluye **armeabi-v7a y arm64-v8a** (no solo 64 bits)
- Vistas clásicas (AppCompat + RecyclerView). Sin Jetpack Compose
- Reproducción con `MediaPlayer` + `MediaSessionCompat` (sin ExoPlayer/Media3)

## Qué hace

- Lee música del almacenamiento interno y de la SD con **MediaStore** (y un barrido de las carpetas Música / Descargas / Documentos). Formatos: **mp3, flac, ogg, wav, aac** (también m4a)
- Pide `READ_EXTERNAL_STORAGE` en Android 9–12. **No** usa `MANAGE_EXTERNAL_STORAGE`
- Lista **álbumes** (etiquetas ID3 / MediaStore) y **carpetas**, con filas grandes y textos en español de Chile
- Reproduce un álbum o una carpeta en orden: play / pausa / siguiente / anterior
- Sigue sonando en segundo plano, con notificación y controles en la pantalla de bloqueo
- Los botones del stereo del auto (AVRCP) controlan play/pausa/siguiente. Android se encarga del audio por Bluetooth (A2DP); esta app no implementa una pila Bluetooth
- Recuerda la última pista y la posición

## No está en Play Store

Se instala a mano (sideload):

1. Copia el APK al teléfono (USB, tarjeta SD o mensajería).
2. En el A10: **Ajustes → Biometría y seguridad → Instalar apps desconocidas** (o “Orígenes desconocidos”) y permite la app que uses para abrir el APK.
3. Abre el APK e instala.
4. La primera vez concede el permiso para ver archivos de audio.

O desde un computador con el teléfono en depuración USB:

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

## Cómo generar el APK

Necesitas **JDK 17** (o 21) y el **Android SDK** (plataforma 34 y build-tools 34).

```bash
# Ejemplo de variables (ajusta la ruta de tu SDK)
export ANDROID_HOME="$HOME/android-sdk"
export PATH="$PATH:$ANDROID_HOME/platform-tools"

# En este repositorio:
./gradlew :app:assembleDebug
```

El APK queda en:

```
app/build/outputs/apk/debug/app-debug.apk
```

El APK de depuración basta para usarlo en casa. **No hay keystore ni secretos en el repositorio.**

### Publicar el debug en GitHub Releases

`git tag` + `git push` del tag `vX.Y.Z`. GitHub Actions arma el debug y crea la release con `musica-simple-X.Y.Z-debug.apk`. Detalle para Grok: `AGENTS.md`. En esta máquina solo se usa `git` (nunca `gh`).

Para una build de *release* **sin firmar** (minify/shrink, `debuggable false`):

```bash
./gradlew :app:assembleRelease
```

El APK queda en:

```
app/build/outputs/apk/release/app-release-unsigned.apk
```

Android no instala un APK sin firma. Firma en tu computador, con tu `.jks` (nunca lo subas al repo):

```bash
# Ejemplo; ajusta alias y rutas. Te pedirá las contraseñas en la terminal.
"$ANDROID_HOME/build-tools/34.0.0/apksigner" sign \
  --ks /ruta/secreta/tu.jks \
  --out musica-simple-0.1.5-release.apk \
  app/build/outputs/apk/release/app-release-unsigned.apk
```

### Instalar el SDK en un computador Linux (si no lo tienes)

```bash
# Herramientas de línea de comando:
# https://developer.android.com/studio#command-line-tools-only
mkdir -p "$HOME/android-sdk/cmdline-tools"
# Descomprime el zip en $HOME/android-sdk/cmdline-tools/latest
export ANDROID_HOME="$HOME/android-sdk"
yes | "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --licenses
"$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" \
  "platforms;android-34" \
  "build-tools;34.0.0" \
  "platform-tools"
```

## Uso en el auto

1. Copia los rips a **Música** (o Descargas) del teléfono o de la SD.
2. Abre **Música Simple**, concede el permiso, elige **Álbumes** o **Carpetas**.
3. Pulsa **Reproducir todo** o una canción. Deja la app abierta o minimízala; el audio sigue.
4. Empareja el teléfono con el auto por Bluetooth (Ajustes de Android). El stereo debería mostrar el título y reaccionar a los botones.

Si el Bluetooth se corta (saliste del auto), la música se pausa.

## Lo que no incluye a propósito

Sin anuncios, Firebase, Play Billing, Chromecast, Last.fm, Coil/Glide de red, analítica ni acceso a internet. El APK se mantiene chico para 2 GB de RAM.

## Desarrollo

Proyecto Gradle de un solo módulo (`:app`). Android Studio no es obligatorio.

```bash
./gradlew :app:assembleDebug :app:testDebugUnitTest
```
