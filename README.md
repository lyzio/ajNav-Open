# ajNav

Open-source navigation for electric cars on Android and Android Auto. No Google Play Services, and you bring your own API keys.

**Status: v0.3 — charging stops (without the car link).** Search a destination, preview the route, then drive with turn-by-turn voice guidance, lane arrows, and your speed against the speed limit — on the phone or on the car screen via Android Auto. Phone and car share one trip.

New in v0.3: type your battery % in the route preview and ajNav suggests fast chargers along the route (Open Charge Map) with charging time and cost; tap **Add** to drive via one. Energy use is adjusted for weather (SMHI) and hills (Open-Meteo). The **Operators & Prices** tab sets preferred networks and your own prices. Map themes (green dark / light / auto), voice engine and language, and a logo screen in the car. The car link (OBD) comes in v0.4. See [`design.md`](design.md) §5 and the website at [lyzio.github.io/ajNav-Open](https://lyzio.github.io/ajNav-Open/).

## Principles
1. **Zero Google Play Services:** works on GrapheneOS, LineageOS and microG. CI fails if a `com.google.android.gms` dependency appears.
2. **BYOK:** the repo contains no keys. Users enter their own in the app.
3. **Read-only, minimal OBD2** (from v0.4).
4. **Modular:** Gradle modules that contributors can extend.

## Install (testers)
1. Open the latest successful run under **Actions → Android build**.
2. Download the `ajnav-debug-apk` artifact, unzip it and install the APK. You may need to allow "install unknown apps".
3. In the app, go to **Settings** and paste your **Mapbox public token** (`pk.…`) from [console.mapbox.com](https://console.mapbox.com/account/access-tokens/).
4. For charging stops, also paste your free **Open Charge Map API key** ([openchargemap.org](https://openchargemap.org) → My Profile → My Apps → Register an application).

## Android Auto (testers)
Google's Android Auto app only shows car-template apps like ajNav when they are installed from Google Play. Its "Unknown sources" developer setting covers self-installed music and messaging apps, not navigation apps built with the Car App Library. Two ways to test on the car screen:

**A. Google Play internal testing (used by the project owner)**
1. Download the `ajnav-release-aab` artifact from a green CI run and unzip it (`app-release.aab`, signed with the project's upload key).
2. In Play Console: *Test and release → Testing → Internal testing → Testers*, create an email list with your Google account. Then *Create new release*, upload the `.aab`, and roll it out.
3. Open the tester link (on the *Testers* tab) on the phone, accept, and install ajNav from the Play Store. Uninstall any self-installed ajNav first; the Play copy has a different signature. The first link can take a few hours to become active.
4. Connect to the car. ajNav appears in Android Auto's app launcher. New versions arrive as normal Play updates after each new internal release.

**B. [AARE](https://github.com/prototux/AARE)** — an open-source replacement for the phone side of Android Auto (no root, no Google). ajNav's test builds accept it as a host. Some factory head units refuse it, and Play Protect may warn when installing it.

On the car screen: **Search** (keyboard only while parked), **Places** (Home, Work, recent destinations), **Hide map** (logo screen in free drive), route preview with **Charging** (battery %, suggested stops) and **Start**, and turn-by-turn with **Chargers**, **Mute** and **End**. A trip started on the phone continues on the car screen, and the other way round. Do not turn on AARE's *Passenger mode*.

## Build locally
Requirements: JDK 17 and the Android SDK (API 36).

```bash
cp local.properties.example local.properties   # optional, see comments inside
./gradlew assembleDebug
```

- `MAPBOX_DOWNLOADS_TOKEN` (secret `sk.` token with `DOWNLOADS:READ`) is only needed if Mapbox requires authentication to download its SDK. In CI it is the GitHub secret of the same name.
- `MAPBOX_PUBLIC_TOKEN` and `OCM_API_KEY` in `local.properties` optionally pre-fill the in-app keys for developers.
- Unit tests (planner, prices, weather parser, car profiles): `./gradlew testDebugUnitTest :feature:vehicles:test`.

## Modules
| Module | Purpose |
|---|---|
| `:app` | Activity, tabs, Settings, Mapbox start-up, Android Auto screens (`car/`) |
| `:core:model` | Pure Kotlin domain models |
| `:core:security` | Encrypted API-key storage (Android Keystore) |
| `:feature:vehicles` | Car profiles (pure Kotlin): `Fiat500e2021Profile`. Add new cars here |
| `:feature:charging` | Open Charge Map, price chain, offline charger cache (Room), heights (Open-Meteo), charging planner |
| `:feature:traffic-se` | SMHI weather (Trafikverket from v0.5) |
| `:feature:navigation` | Shared trip (`TripController`), map drawing and themes (`MapRenderer`), search (Photon), route preview with charging, turn-by-turn, voice, favourites, recents |

## Licence
GNU AGPL-3.0. Map data © Mapbox © OpenStreetMap contributors. Search by [Photon](https://photon.komoot.io). Charger data © [Open Charge Map](https://openchargemap.org) contributors (CC BY 4.0). Weather © [SMHI](https://www.smhi.se) (CC BY 4.0). Heights by [Open-Meteo](https://open-meteo.com).

## Website
The website lives in [`docs/`](docs/) (Home, Roadmap, FAQ, Privacy) and is served by GitHub Pages from `master` / `docs`.
