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
ajNav is installed outside Google Play, so Android Auto hides it until you allow unknown sources once:

1. On the phone, open **Settings → Connected devices → Connection preferences → Android Auto** (or search settings for "Android Auto").
2. Scroll to the bottom and tap **Version** about 10 times until "Allow development settings?" appears. Tap **OK**.
3. Tap **⋮** (top right) → **Developer settings** → tick **Unknown sources**.
4. Open ajNav on the phone once, enter the Mapbox token and allow location.
5. Connect the phone to the car. ajNav appears in the Android Auto app launcher.

On the car screen: **Search** (keyboard only while parked), **Places** (Home, Work, recent destinations), route preview with **Start**, and turn-by-turn with **Mute** and **End**. While driving you can also say *"Hey Google, navigate to … with ajNav"*. A trip started on the phone continues on the car screen, and the other way round.

Note: Android Auto itself is a Google app. ajNav stays free of Google Play Services, but the phone needs Google's Android Auto app to connect to the car.

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
