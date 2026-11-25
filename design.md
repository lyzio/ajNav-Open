# Software Design Document (SDD): ajNav (v1.2)

## 1. Projektöversikt & Kärnprinciper
**ajNav** är en modulär, öppen källkods-applikation för Android och Android Auto byggd för elbilsförare. Appen kombinerar turn-by-turn-navigering med realtidsdata från bilen via OBD2 (BLE), molntelemetri, dynamisk laddstoppsplanering, kostnadskalkyl, topografi/väder samt svensk trafikdata.

### Arkitekturprinciper
1. **Zero Google Play Services:** Appen får aldrig bero på `com.google.android.gms` (t.ex. `FusedLocationProviderClient` eller Google Maps). All kart-, plats- och bilskärmslogik ska fungera på de-Googled Android (GrapheneOS, LineageOS/microG).
2. **BYOK (Bring Your Own Key):** Repot innehåller inga API-nycklar. Slutanvändaren matar in sina egna nycklar direkt i appens UI (med möjlighet för utvecklare att ange förval via `local.properties` vid bygge). Nyckelfria öppna API:er används som standard där det är möjligt.
3. **100 % Read-Only & minimal OBD2:** Appen får under inga omständigheter skicka skriv-, konfigurations- eller felkodsraderingskommandon till fordonet (kritiskt för säkerhet och Stellantis SGW-kompatibilitet). Appen läser **endast** data som behövs för laddning, förbrukning och navigering – inga felkoder (DTC), ingen fordonsdiagnostik och inga övriga OBD-anrop.
4. **Hög modularitet:** Uppdelad i separata Gradle-moduler med tydliga gränssnitt så att communityt enkelt kan bidra med nya bilprofiler, laddkällor eller regionala API:er via Pull Requests.

---

## 2. Tech Stack
* **Språk:** Kotlin 2.x (100 % native Android).
* **UI (Mobil):** Jetpack Compose (Material 3) + Navigation Compose.
* **UI (Bilskärm):** Android for Cars App Library (`androidx.car.app:app`) + Mapbox Android Auto Components.
* **Karta & Navigation:** Mapbox Navigation SDK v3 (`com.mapbox.navigationcore:android`, `com.mapbox.navigationcore:android-auto-components`).
* **Bluetooth Low Energy (OBD):** Nordic Semiconductor Android BLE Library (`no.nordicsemi.android:ble-ktx`).
* **Asynkronitet & State:** Kotlin Coroutines & `StateFlow`.
* **Nätverk & Serialisering:** Ktor Client + `kotlinx.serialization` (JSON/XML).
* **Lokal lagring:** Room (SQLite) + Jetpack DataStore (krypterad lagring för API-nycklar).
* **Dependency Injection:** Hilt eller Koin.

---

## 3. Modulär Projektstruktur (Gradle Multi-Module)

```text
ajnav/
├── app/                        # Huvudapp, ForegroundService, Compose UI, CarAppService
├── core/
│   ├── model/                  # Rena Kotlin-dataklasser (VehicleState, ChargerStation, RouteStopPlan, etc.)
│   ├── security/               # ApiKeyRepository (BYOK), Encrypted DataStore
│   └── obd/                    # ObdBleManager, ReadOnlyObdGuard, VehiclePidProfile
├── feature/
│   ├── vehicles/               # Bilprofiler (Fiat500e2021Profile, MockVehicleProfile, etc.)
│   ├── charging/               # ChargingStationProvider (OCM), ChainedTariffProvider (OCPI/NAP + OCM + Sub)
│   ├── telemetry-cloud/        # Enode API-klient (valfri pre-trip telemetri)
│   ├── traffic-se/             # Trafikverket Open API & SMHI Open Data (väder/störningar)
│   └── navigation/             # Mapbox Navigation v3, Photon/Mapbox Geocoding, Open-Meteo Elevation, EvRoutePlannerUseCase
├── local.properties.example    # Mall för MAPBOX_DOWNLOADS_TOKEN vid kompilering
└── README.md                   # Guide för BYOK, DHU-testning och att bidra med bilprofiler
```

---

## 4. Detaljerad Modulspecifikation

### 4.1 `:core:model`
Rena domänmodeller utan Android-beroenden:
* `VehicleState`: `socPercent: Float?`, `batteryCapacityKwh: Float`, `batteryTempCelsius: Float?`, `batteryPowerKw: Float?`, `baseConsumptionKwhPer100Km: Float`, `weightedConsumptionKwhPer100Km: Float`, `source: TelemetrySource` (`OBD_LIVE`, `ENODE_CLOUD`, `MANUAL`).
* `ChargeCurveStep`: `upToSocPercent: Int`, `maxPowerKw: Float`.
* `ChargerStation`: `id: String`, `name: String`, `operatorId: Int?`, `operatorName: String`, `latitude: Double`, `longitude: Double`, `maxPowerKw: Float`, `availablePoints: Int?`, `totalPoints: Int?`, `usageCostRaw: String?`, `estimatedPricePerKwh: Float?`, `currency: String`.
* `RouteStopPlan`: `station: ChargerStation`, `arrivalSocPercent: Float`, `targetSocPercent: Float`, `neededKwh: Float`, `estimatedChargeMinutes: Int`, `estimatedCost: Float`, `currency: String`, `isColdgateAdjusted: Boolean`.

---

### 4.2 `:core:security` (BYOK – API-nyckelhantering)
* **`ApiKeyRepository`:**
  * Läser/skriver nycklar i krypterad lokal lagring (med fallback till `BuildConfig` om angivet i `local.properties`).
  * **Obligatoriska nycklar (för full kärnfunktion):**
    * `MAPBOX_PUBLIC_TOKEN` (Kartor & Navigation)
    * `OCM_API_KEY` (Open Charge Map)
  * **Valfria nycklar (moduler aktiveras dynamiskt om nyckel finns):**
    * `NAP_OCPI_API_KEY` (t.ex. NOBIL / nationell åtkomstpunkt för realtidspriser & status)
    * `ENODE_CLIENT_ID` & `ENODE_CLIENT_SECRET` (Molntelemetri före avfärd)
    * `TRAFIKVERKET_API_KEY` (Vägväder & störningar i Sverige)
  * **Nyckelfria API:er (fungerar utan konfiguration):** Photon Geocoding, Open-Meteo Elevation, SMHI Open Data.
* **Gradle-konfiguration (`settings.gradle.kts`):** Läser `MAPBOX_DOWNLOADS_TOKEN` från `local.properties` eller miljövariabeln `MAPBOX_DOWNLOADS_TOKEN` för nedladdning av Mapbox SDK.

---

### 4.3 `:core:obd` & `:feature:vehicles` (OBDLink CX & Säkerhet)

#### A. BLE-kommunikation (`ObdBleManager`)
* Byggt på **Nordic Semiconductor Android BLE Library**.
* Stödjer **OBDLink CX** (BLE 5.1 / STN-chipset):
  * GATT-tjänst/characteristics: `FFF1` (Notify) och `FFF2` (Write).
  * Begär `requestMtu(512)` vid anslutning.
  * Sekventiell kö (Request-Response): Skickar aldrig nästa kommando förrän `>` mottagits eller timeout (300 ms) löpt ut. Minst 150 ms paus mellan anrop för att undvika CAN-bus flooding.

#### B. Hårdvarunära Read-Only- & minimal-spärr (`ReadOnlyObdGuard`)
Varje utgående sträng måste passera `ReadOnlyObdGuard.validateOrThrow(cmd)` direkt innan BLE-skrivning. Spärren är en **exakt vitlista** – allt som inte uttryckligen står här blockeras:
* **Tillåtna AT/ST-kommandon (endast adapter-uppsättning):** `ATZ`, `ATE0`, `ATL0`, `ATS0`, `ATH1`, `ATSP6`, samt `ATSH xxx` (endast headers som aktiv bilprofil deklarerar).
* **Tillåtna OBD2/UDS-tjänster:** Endast `01` (Live data) och `22` (UDS ReadDataByIdentifier) – och **endast de exakta kommandon som finns i aktiv bilprofils `pollingCommands`** (laddnings-, förbruknings- och navigeringsdata).
* **Uttryckligen utanför scope (blockeras):** `03`/`07`/`19` (felkoder/DTC), `09` (VIN/Fordonsinfo), `ATRV` (12V-spänning).
* **Explicit blockerade tjänster:** `04` (Clear DTC), `10` (DiagnosticSessionControl – appen stannar alltid i säker Default Session `0x01`), `11` (ECUReset), `14` (ClearDiagnosticInformation), `27` (SecurityAccess), `2E`/`3D` (Write), `2F`/`31` (IO/Routine Control), `34`/`36`/`37` (Flashing).

#### C. Utbytbara Bilprofiler (`VehiclePidProfile`)
```kotlin
interface VehiclePidProfile {
    val profileId: String
    val displayName: String
    val vehicleMassKg: Float
    val defaultBatteryCapacityKwh: Float
    val optimalBatteryTempCelsius: Float
    val chargeCurve: List<ChargeCurveStep> // Icke-linjär laddkurva
    val pollingCommands: List<ObdCommand>
    fun parseTelemetry(commandId: String, rawHex: String): PartialVehicleTelemetry?
}
```
* **Standardprofil 1: `Fiat500e2021Profile`**
  * Massa: `1465f` kg (inkl. förare). Kapacitet: `37.3f` kWh netto (`42.0f` kWh brutto). Optimal batteritemp: `20.0f` °C.
  * **3-stegs laddkurva:** `0–50 % -> 85 kW`, `50–80 % -> 55 kW`, `80–100 % -> 20 kW`.
  * Protokoll: `ATSP6` (ISO 15765-4 CAN 11/500).
  * Polling (UDS Service `22` mot BECM `ATSH 7E4`):
    * Display SoC (%): `22 20 02` (alt. `22 01 01`)
    * HV Battery Temp (°C): `22 20 05`
    * HV Voltage & Current (för kW och förbrukning): `22 20 03` & `22 20 04`
* **Standardprofil 2: `MockVehicleProfile`**
  * Simulerar körning, gradvis SoC-minskning, batteritemperatur och effekt för utveckling utan bil.

---

### 4.4 `:feature:charging` (Laddstationer & Priskedja)

#### A. Laddstationssökning (`OpenChargeMapProvider`)
* Cachning av OCM-operatörer (`GET /v3/referencedata/`) i Room för att låta användaren bocka i **Föredragna operatörer** och välja läge: *Endast valda* eller *Föredra valda*.
* Sökning längs rutt:
  `GET https://api.openchargemap.io/v3/poi/?polyline={SIMPLIFIED_POLYLINE}&distance=3&distanceunit=km&connectiontypeid=33&levelid=3&statustypeid=50&compact=true&verbose=false&maxresults=100`
  *(Inkluderar `operatorid=...` om läge = "Endast valda").*
* Alla hämtade laddstationer längs aktiv rutt sparas i Room som **offline-cache** ifall mobiltäckning försvinner under resan.

#### B. Pris- och Statuskedja (`ChainedTariffProvider`)
Beräknar pris per kWh och synkar lediga stolpar i följande strikta prioritetsordning:
1. **User Subscription Override (Valfritt):** Om användaren registrerat ett eget medlemskap/avtal för en operatör (t.ex. Tesla-medlemskap eller Ionity Passport med eget kr/kWh-pris) används detta i första hand.
2. **Primär live-källa (`OcpiNapTariffProvider`):** Hämtar strukturerade realtidstariffer (AFIR ad hoc-pris per kWh/minut) och live-tillgänglighet (lediga/upptagna uttag) från öppna OCPI/NAP-endpoints (t.ex. NOBIL i Norden) om nyckel är konfigurerad.
3. **Sekundär källa (`OcmUsageCostParser`):** Extraherar numeriska kWh-priser ur OCM:s `UsageCost`-sträng om stationen saknas i OCPI/NAP-feeden.
4. **Fallback (`DefaultOperatorTariffProvider`):** Använder användarens manuella standardpriser per operatör eller global default (`5.50 SEK/kWh`).

---

### 4.5 `:feature:telemetry-cloud` & `:feature:traffic-se` (Moln, Väder & Trafik)
* **`EnodeClient` (`:feature:telemetry-cloud`, valfri):**
  * OAuth2 REST-klient som hämtar `chargeState.batteryLevel` och `isPluggedIn` när OBDLink CX är frånkopplad (för ruttplanering hemifrån).
* **`SmhiWeatherClient` (`:feature:traffic-se`, nyckelfri):**
  * Anropar SMHI Open Data (`https://opendata-download-metfcst.smhi.se/api/category/pmp3g/version/2/geotype/point/lon/{lon}/lat/{lat}/data.json`) för ruttens mittpunkt och destination för att få temperatur, vind och nederbörd i hela Norden utan API-nyckel.
* **`TrafikverketClient` (`:feature:traffic-se`, valfri med nyckel):**
  * Skickar kombinerat XML-anrop (`POST https://api.trafikverket.se/v2/data.json`) var 10:e minut under aktiv rutt med en WKT-polygon buffrad 2 km runt rutten:
    * `Situation` (schemaversion 1.5): Olyckor, vägarbeten och avstängningar (varnar om störning finns inom 30 km framför bilen).
    * `WeatherMeasurepoint` (schemaversion 2.1): Vägbanetemp, lufttemp, vind och nederbörd längs statliga vägar.

---

### 4.6 `:feature:navigation` (Mapbox, Geokodning, Topografi & Räckviddsmotor)

#### A. Utbytbar Geokodning (`GeocodingProvider`)
* **Standard (`PhotonGeocodingProvider` – nyckelfri):** `GET https://photon.komoot.io/api/?q={query}&lat={currentLat}&lon={currentLon}&limit=6`.
* **Valbart alternativ (`MapboxGeocodingProvider`):** Återanvänder `MAPBOX_PUBLIC_TOKEN` för förbättrad POI-/företagssökning.

#### B. Topografi / Höjdprofil (`OpenMeteoElevationProvider` – nyckelfri)
* Samplar punkter längs rutten (var 5:e km) och anropar `GET https://api.open-meteo.com/v1/elevation?latitude=...&longitude=...`.
* Beräknar energipåverkan från stigningar och utförsbackar: $\Delta E = \frac{m \cdot g \cdot \Delta h}{\eta}$ (där $\eta \approx 0.85$ vid stigning och $\eta \approx 0.65$ för regenerering vid utförsbacke).

#### C. Räckvidds-, Laddtids- & Coldgate-motor (`EvRoutePlannerUseCase`)
1. **Stabil förbrukningsmodell (Kallstartsskydd):** För att hindra att kupévärmaren under de första 10 minuterna triggar falska laddstopp beräknas förbrukningen som ett viktat värde: `70 % basförbrukning + 30 % rullande 15-minuterssnitt`, justerat med väderfaktor (`+10 %` till `+25 %` vid `< 2°C`, nederbörd eller motvind `> 10 m/s`) samt höjdprofilens $\Delta E$.
2. **Stationär urval & poängsättning:** Identifierar fönstret där beräknad SoC faller mellan **10 % och 25 %** och rankar laddare baserat på operatörspreferens (`+100p`), pris via `ChainedTariffProvider`, effekt (`+50p` om `>= 150 kW`), avstånd från rutt (`+30p` om `< 1 km`) och live-tillgänglighet (`-200p` om 0 lediga uttag).
3. **Realistisk laddtid (Laddkurva + Coldgate):**
   * Integrerar laddtiden stegvis över bilprofilens `chargeCurve` (inte bara toppeffekten).
   * **Coldgate-justering:** Om `VehicleState.batteryTempCelsius` understiger `optimalBatteryTempCelsius` (t.ex. `< 15 °C`) skalas beräknad laddeffekt ner linjärt (ner till 50 % vid `0 °C`) och stoppet flaggas som `isColdgateAdjusted = true`.
4. **Live-övervakning under körning:** Om faktisk förbrukning gör att beräknad ankomst-SoC sjunker under **8 %**, triggas en automatisk omsökning och användaren får en prompt att lägga till ett tidigare laddstopp.

---

### 4.7 `:app` (Bakgrundstjänst, Mobil-UI & Android Auto)

#### A. Bakgrundstjänst, WakeLock & Behörigheter (`VehicleTelemetryService`)
* `ForegroundService` (`foregroundServiceType="connectedDevice|location"`) som håller en partiell `PowerManager.WakeLock` under aktiv körning så att GrapheneOS/LineageOS Doze-läge inte dödar BLE-pollingen när telefonskärmen är släckt.
* Runtime-hantering av Android 12+ BLE-behörigheter (`BLUETOOTH_SCAN` med `neverForLocation`, `BLUETOOTH_CONNECT`), `ACCESS_FINE_LOCATION`, `POST_NOTIFICATIONS` samt prompt för `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`.

#### B. Mobil-UI (Jetpack Compose – 4 flikar)
1. **Dashboard:** Live SoC %, batteritemp (med indikator när batteriet nått optimal snabbladdningstemp), aktuell effekt (kW), aktuell förbrukning, OBD-anslutningsstatus samt snabbknapp för Mock-läge.
2. **Navigering:** Sök destination (Photon/Mapbox), visa rutt + föreslagna laddstopp (med kWh, laddtid inkl. ev. Coldgate-varning, operatör och uppskattad kostnad i kr) samt knapp för att starta navigering.
3. **Operatörer & Priser:** Lista över OCM-operatörer för val av favoriter samt inmatning av egna abonnemangspriser (kr/kWh).
4. **Inställningar & API-nycklar:** Val av bilprofil (`Fiat500e2021Profile`, etc.), val av geokodningsmotor (Photon/Mapbox), säkerhetsmarginaler och BYOK-formulär för API-nycklar.

#### C. Android Auto (`EvCarAppService` & `MainNavigationScreen`)
* **Manifest & Deklaration:** Inkluderar `res/xml/automotive_app_desc.xml` (`<uses name="template" />`) samt `<category android:name="androidx.car.app.category.NAVIGATION" />` i `AndroidManifest.xml`.
* **Strikta Car App Templates:** Följer Android Auto UX-riktlinjer (inga fria Compose-vyer på bilskärmen, max 6 listobjekt per skärm under körning):
  * `MainNavigationScreen` (Mapbox `NavigationScreen` / `NavigationTemplate`): Visar karta, svänginstruktioner och en anpassad info-overlay / ActionStrip med **Live SoC %**, **Beräknad ankomst-SoC %**, **Batteritemp (°C)** samt snabbknapp för *"Sök laddare längs rutt"*.
  * `ChargerListScreen` (`PlaceListNavigationTemplate`): Visar de närmaste/bästa laddarna längs rutten (max 6 st) med avstånd, kW, pris och lediga stolpar.
  * `DestinationSearchScreen` (`SearchTemplate`): Röst- och textbaserad destinationssökning via `GeocodingProvider` när bilen står parkerad.
  * **Notiser (`CarToast`):** Varning vid trafikstörning framåt (Trafikverket) eller om batteriet beräknas nå `< 8 %` före planerat stopp.

---

## 5. Leveransordning vid kodgenerering
För att hålla koden ren och kompilerbar utan trunkering ska implementationen delas upp i 4 steg:
1. **Steg 1 (Grund & Kärnmoduler):** `settings.gradle.kts`, `libs.versions.toml`, `local.properties.example`, `README.md`, samt `:core:model`, `:core:security` och `:core:obd` (inklusive `ReadOnlyObdGuard` och `ObdBleManager`).
2. **Steg 2 (Bilprofiler & Laddning/Priser):** `:feature:vehicles` (`Fiat500e2021Profile` med laddkurva + `MockVehicleProfile`) samt `:feature:charging` (`OpenChargeMapProvider` + `ChainedTariffProvider` + Room offline-cache).
3. **Steg 3 (Navigation, Topografi, Väder & Moln):** `:feature:telemetry-cloud` (Enode), `:feature:traffic-se` (Trafikverket + SMHI) samt `:feature:navigation` (Mapbox v3, Photon/Mapbox Geocoding, Open-Meteo Elevation och `EvRoutePlannerUseCase`).
4. **Steg 4 (App, Mobil-UI & Android Auto):** `:app` med `AndroidManifest.xml`, `automotive_app_desc.xml`, `VehicleTelemetryService` (med WakeLock & BLE-permissions), samtliga 4 Compose-vyer och `EvCarAppService` (`MainNavigationScreen`, `ChargerListScreen`, `DestinationSearchScreen`).

---

## 6. Ändringslogg
* **v1.2 (2026-11-25):** Namnbyte OpenEV Nav → ajNav. Felkodsläsning (DTC) och fordonsdiagnostik borttagen ur scope (`DtcScanner`, `DtcRecord`, `dtc_descriptions.json`, Diagnostik-fliken, DTC-/12V-notiser). OBD begränsat till minimal vitlista: endast data för laddning, förbrukning och navigering (inga `03`/`07`/`19`/`09`/`ATRV`). En utvecklarkonsol för att verifiera nya PID:ar kan övervägas senare.
* **v1.1:** Ursprunglig version.
