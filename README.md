# Arabic Anime – CloudStream Plugins

Arabic (and multi-language) anime sources for [CloudStream](https://github.com/recloudstream/cloudstream). This repo provides a single install link; new or updated plugins are built and pushed automatically via GitHub Actions.

## Install

1. Open **CloudStream** → **Extensions** (⚙️) → **Add Repository**.
2. Paste the link below and confirm:

```
https://raw.githubusercontent.com/alrahbisami1/Arabic-Anime-Plugins/builds/repo.json
```

3. Enable the plugins you want. Updates are fetched from the same link—no need to re-add it.

## Included Plugins

| Plugin | Main URL | Language | Status |
|--------|----------|----------|--------|
| Anime3rb | `anime3rb.com` | Arabic | ✅ v1 |
| WitAnime | `witanime.com` | Arabic | ✅ v1 |
| Anime4up | `w1.anime4up.rest` | Arabic | ✅ v2 |

> Links and selectors may change over time. If a source stops working, open an issue here.

## Development

### Prerequisites

- Android Studio or JDK 17+
- Android SDK

### Build locally

```bash
./gradlew make
```

Output `.cs3` files (plugin binaries) are created under each module's `build/` directory.

### Project structure

```
├── Anime3rb/      # module: source code + build config
├── Anime4up/
├── WitAnime/
├── library/       # shared commonMain (extractors, utilities)
├── build.gradle.kts
├── settings.gradle.kts
├── repo.json      # repo manifest (points to plugins.json)
└── .github/workflows/build.yml
```

### CI/CD

On every push to `main`, GitHub Actions:
1. Builds every module into a `.cs3` file
2. Copies the builds + `plugins.json` + `repo.json` to the `builds` branch
3. Force-pushes the updated `builds` branch

End users see the update automatically from the same repo link.

### Adding a new source

1. Copy any existing module folder and rename it (e.g. `MySite/`).
2. Update `settings.gradle.kts` to include `include(":MySite")`.
3. Inside the new module, edit:
   - `build.gradle.kts` – set `version = 1`, `iconUrl`, `description`
   - `src/main/AndroidManifest.xml` – set `package`
   - `src/main/kotlin/.../MySitePlugin.kt` – register the provider
   - `src/main/kotlin/.../MySiteProvider.kt` – implement `MainAPI`
4. `git push` → CI builds and publishes automatically.

## License

This repository is provided as-is for educational purposes. Individual sources are owned by their respective operators. Do not use in violation of applicable laws.
