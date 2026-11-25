# ajNav

Open-source navigation for electric cars on Android (and later Android Auto). No Google Play Services, and you bring your own API keys.

**Status: v0.1 — phone navigation.** Search a destination, preview the route, then drive with turn-by-turn voice guidance, lane arrows, and your speed against the speed limit. Home/Work favourites and recent destinations are included. Charging, Android Auto and car data (OBD) come in later versions. See [`design.md`](design.md) §5.

## Principles
1. **Zero Google Play Services:** works on GrapheneOS, LineageOS and microG. CI fails if a `com.google.android.gms` dependency appears.
2. **BYOK:** the repo contains no keys. Users enter their own in the app.
3. **Read-only, minimal OBD2** (from v0.4).
4. **Modular:** Gradle modules that contributors can extend.

## Install (testers)
1. Open the latest successful run under **Actions → Android build**.
2. Download the `ajnav-debug-apk` artifact, unzip it and install the APK. You may need to allow "install unknown apps".
3. In the app, go to **Settings** and paste your **Mapbox public token** (`pk.…`) from [console.mapbox.com](https://console.mapbox.com/account/access-tokens/).

## Build locally
Requirements: JDK 17 and the Android SDK (API 36).

```bash
cp local.properties.example local.properties   # optional, see comments inside
./gradlew assembleDebug
```

- `MAPBOX_DOWNLOADS_TOKEN` (secret `sk.` token with `DOWNLOADS:READ`) is only needed if Mapbox requires authentication to download its SDK. In CI it is the GitHub secret of the same name.
- `MAPBOX_PUBLIC_TOKEN` in `local.properties` optionally pre-fills the in-app token for developers.

## Modules
| Module | Purpose |
|---|---|
| `:app` | Activity, tabs, Settings, Mapbox start-up |
| `:core:model` | Pure Kotlin domain models |
| `:core:security` | Encrypted API-key storage (Android Keystore) |
| `:feature:navigation` | Map, search (Photon), route preview, turn-by-turn, favourites, recents, theme |

## Licence
GNU AGPL-3.0. Map data © Mapbox © OpenStreetMap contributors. Search by [Photon](https://photon.komoot.io).
