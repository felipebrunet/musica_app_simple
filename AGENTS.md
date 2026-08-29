# Convenciones para quien trabaja este repo (incl. Grok)

Reglas fijas. No improvisar otras herramientas ni otros flujos.

## Git, no `gh`

- El remoto se maneja **solo con `git`**: `git status`, `git add`, `git commit`, `git tag`, `git push`.
- **Nunca** instalar ni usar `gh`, `hub`, Graphite ni CLIs raros de GitHub **en esta máquina**.
- Push: `git push origin master` y, si hay tag, `git push origin vX.Y.Z`.
- No force-push a `master`.

## APK debug en GitHub Releases

Los releases 0.1.2–0.1.4 los publicó **GitHub Actions** (`github-actions[bot]`) al pushear el tag `v*`. En el runner sí hay `GITHUB_TOKEN` (el de Actions). En esta máquina **no hay PAT** para `curl` a la API.

1. Subir `versionName` / `versionCode` en `app/build.gradle.kts` (y el ejemplo del README si aplica).
2. Compilar aquí si hace falta sideload inmediato:

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

4. El job `github-release` del workflow arma el APK y crea la GitHub Release con asset:

`musica-simple-X.Y.Z-debug.apk`

El workflow del **commit tageado** es el que corre. Si el tag apunta a un commit sin el job de release, no se publica.

Sideload: `adb install -r musica-simple-X.Y.Z-debug.apk`

Si hay `GITHUB_TOKEN` (PAT con `contents`) en el entorno, también se puede adjuntar a mano con `curl` a `uploads.github.com`. No es el camino habitual.

## Qué no hacer

- No inventar keystores, Play Store, Compose, ExoPlayer ni red. Es un player local para el Galaxy A10.
