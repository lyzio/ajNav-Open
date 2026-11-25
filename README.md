# ajNav

Open-source navigation for electric cars on Android and Android Auto. No Google Play Services, and you bring your own API keys.

**Status: v0.2 — phone + Android Auto navigation.** Search a destination, preview the route, then drive with turn-by-turn voice guidance, lane arrows, and your speed against the speed limit — on the phone or on the car screen via Android Auto. Phone and car share one trip. Home/Work favourites and recent destinations are included. Charging and car data (OBD) come in later versions. See [`design.md`](design.md) §5.

## Principles
1. **Zero Google Play Services:** works on GrapheneOS, LineageOS and microG. CI fails if a `com.google.android.gms` dependency appears.
2. **BYOK:** the repo contains no keys. Users enter their own in the app.
3. **Read-only, minimal OBD2** (from v0.4).
4. **Modular:** Gradle modules that contributors can extend.

## Install (testers)
1. Open the latest successful run under **Actions → Android build**.
2. Download the `ajnav-debug-apk` artifact, unzip it and install the APK. You may need to allow "install unknown apps".
3. In the app, go to **Settings** and paste your **Mapbox public token** (`pk.…`) from [console.mapbox.com](https://console.mapbox.com/account/access-tokens/).

## Android Auto (testers)
Google's Android Auto app only shows car-template apps like ajNav when they are installed from Google Play. Its "Unknown sources" developer setting covers self-installed music and messaging apps, not navigation apps built with the Car App Library. For self-installed test builds, use **[AARE](https://github.com/prototux/AARE)**, an open-source replacement for the phone side of Android Auto (no root, no Google). ajNav's test builds accept AARE as a host.

1. Install AARE from its [releases page](https://github.com/prototux/AARE/releases) and complete *Finish setting up* in the app.
2. Open ajNav on the phone once, enter the Mapbox token and allow location.
3. Optional: use AARE's **Preview car screen** to try ajNav without the car.
4. Plug the phone into the car's Android Auto USB port. When Android asks which app to use, pick **AARE** (choose "Just once" until you know it works).
5. If the car screen refuses AARE (some factory head units only trust Google), unplug and pick Android Auto again.

On the car screen: **Search** (keyboard only while parked), **Places** (Home, Work, recent destinations), route preview with **Start**, and turn-by-turn with **Mute** and **End**. A trip started on the phone continues on the car screen, and the other way round. Do not turn on AARE's *Passenger mode*.

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
| `:app` | Activity, tabs, Settings, Mapbox start-up, Android Auto screens (`car/`) |
| `:core:model` | Pure Kotlin domain models |
| `:core:security` | Encrypted API-key storage (Android Keystore) |
| `:feature:navigation` | Shared trip (`TripController`), map drawing (`MapRenderer`), search (Photon), route preview, turn-by-turn, favourites, recents, theme |

## Licence
GNU AGPL-3.0. Map data © Mapbox © OpenStreetMap contributors. Search by [Photon](https://photon.komoot.io).
