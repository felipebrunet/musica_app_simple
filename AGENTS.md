# Convenciones para quien trabaja este repo (incl. Grok)

Reglas fijas. No improvisar otras herramientas ni otros flujos.

## Git, no `gh`

- El remoto se maneja **solo con `git`**: `git status`, `git add`, `git commit`, `git tag`, `git push`.
- **Nunca** instalar ni usar `gh`, `hub`, Graphite ni CLIs raros de GitHub.
- Push: `git push origin master` y, si hay tag, `git push origin vX.Y.Z`.
- No force-push a `master`.

## APK: siempre local, nunca GitHub Actions

Esta máquina tiene cores de sobra. **No esperar a CI** para armar ni para publicar el APK. Actions no publica releases.

1. Subir `versionName` / `versionCode` en `app/build.gradle.kts` (y el ejemplo del README si aplica).
2. Compilar aquí:

```bash
./gradlew :app:assembleDebug --max-workers=16
```

El archivo es `app/build/outputs/apk/debug/app-debug.apk` (está en `.gitignore`; no se commitea).

3. Commit + tag + push con `git`:

```bash
git commit ...
git tag -a vX.Y.Z -m "Música Simple vX.Y.Z (debug)"
git push origin master
git push origin vX.Y.Z
```

4. **En cuanto el APK esté compilado**, adjuntarlo a la GitHub Release del mismo tag. Nombre del asset:

`musica-simple-X.Y.Z-debug.apk`

Subir con `curl` a la API de GitHub (hace falta `GITHUB_TOKEN` en el entorno, un PAT con permiso de contents). **No** usar `gh`. Si no hay token, decirlo y no quedarse esperando a Actions.

Ejemplo (release ya creada o se crea con POST `/releases`):

```bash
VER=0.1.4
NAME="musica-simple-${VER}-debug.apk"
cp app/build/outputs/apk/debug/app-debug.apk "$NAME"
# token solo por env, nunca en el repo
curl -sS -X POST \
  -H "Authorization: Bearer $GITHUB_TOKEN" \
  -H "Accept: application/vnd.github+json" \
  -H "Content-Type: application/vnd.android.package-archive" \
  --data-binary @"$NAME" \
  "https://uploads.github.com/repos/felipebrunet/musica_app_simple/releases/<id>/assets?name=$NAME"
```

Sideload: `adb install -r musica-simple-X.Y.Z-debug.apk`

## Qué no hacer

- No dejar un tag `v*` “para que CI arme el APK”.
- No inventar keystores, Play Store, Compose, ExoPlayer ni red. Es un player local para el Galaxy A10.