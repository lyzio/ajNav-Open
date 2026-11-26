# Software Design Document (SDD): ajNav (v1.4.3)

## 1. Projektöversikt & Kärnprinciper
**ajNav** är en modulär, öppen källkods-applikation för Android och Android Auto byggd för elbilsförare. Appen kombinerar turn-by-turn-navigering med realtidsdata från bilen via OBD2 (BLE), molntelemetri, dynamisk laddstoppsplanering, kostnadskalkyl, topografi/väder samt svensk trafikdata.

### Arkitekturprinciper
1. **Zero Google Play Services:** Appen får aldrig bero på `com.google.android.gms` (t.ex. `FusedLocationProviderClient` eller Google Maps). All kart-, plats- och bilskärmslogik ska fungera på de-Googled Android (GrapheneOS, LineageOS/microG). **Enda godkända undantag (2026-11-25):** `com.google.android.gms:play-services-cronet` (med dess följdberoenden `play-services-base`, `-basement`, `-tasks`) som Mapbox Common drar in. Den används bara om Google Play Services finns på telefonen och gör inget annars. CI tillåter endast dessa; alla andra `gms`/`firebase`-beroenden stoppar bygget.
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
* **Dependency Injection:** Hilt.
* **Bygge (CI):** GitHub Actions bygger APK vid varje push (repot är publikt). `MAPBOX_DOWNLOADS_TOKEN` läggs som GitHub-secret om Mapbox kräver det. CI kontrollerar även att inga `com.google.android.gms`-beroenden finns i appen.
* **Appens språk:** Engelska.
* **UI-tema (LyzLarm-paletten, från UI-mockup 2026-11-25):** Mörkt tema. Bakgrund `#1B2F33`, kort `#243D42`, kantlinjer `#2C474D`, aktiv/nästa sväng `#006266`, rutt/framsteg `#4FB3A9`, bilmarkör/CTA `#ED4C67`, text `#D3DEE0`/`#EEF3F3`, dämpad text `#A9BCC0`. Mörk kartstil, turkos ruttlinje, korallröd bilmarkör.
  * *Obs Android Auto (v0.2):* Bilskärmen tillåter endast Googles fasta mallar. Karta, rutt och bilmarkör kan stylas fritt; svängkort, ETA-fält och listor följer Android Autos egen layout.

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
    val coldgateThresholdCelsius: Float    // Under denna temp skalas laddeffekten ner
    val chargeCurve: List<ChargeCurveStep> // Icke-linjär laddkurva
    val pollingCommands: List<ObdCommand>
    fun parseTelemetry(commandId: String, rawHex: String): PartialVehicleTelemetry?
}
```
* **Standardprofil 1: `Fiat500e2021Profile`**
  * Massa: `1465f` kg (inkl. förare). Kapacitet: `37.3f` kWh netto (`42.0f` kWh brutto). Optimal batteritemp: `20.0f` °C. Coldgate-tröskel: `15.0f` °C.
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
   * **Coldgate-justering:** Om `VehicleState.batteryTempCelsius` understiger bilprofilens `coldgateThresholdCelsius` (`15 °C` för Fiat 500e) skalas beräknad laddeffekt ner linjärt från 100 % vid `15 °C` till 50 % vid `0 °C` (50 % även under `0 °C`) och stoppet flaggas som `isColdgateAdjusted = true`.
5. **v0.3-beslut (utan bilkoppling, 2026-11-26):** Basförbrukning standard **16 kWh/100 km** för Fiat 500e 42 kWh, ändringsbar i Inställningar (rullande snitt kommer med OBD i v0.4). Mål: **ankomst ≥ 10 %**, **ladda till 80 %** (båda ändringsbara). Föreslagna laddstopp visas i ruttförhandsvisningen; användaren trycker *Lägg till* för att lägga stoppet som delmål i rutten. Utan batteritemperatur används SMHI:s utetemperatur som ersättning för Coldgate och stoppet märks *uppskattad*. Batteri-% matas in manuellt vid planering.
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
  * **v0.2-anpassning (beslut D12, 2026-11-25):** Mobilens fria kort ersätts på bilskärmen av standardmallar. Karta, rutt och bilmarkör stylas fritt (LyzLarm: mörk karta, teal rutt, korall markör); hastighet + hastighetsgräns ritas på kartytan. v0.2 omfattar:
    * `MainNavigationScreen` (`NavigationTemplate`): karta, nästa sväng med filpilar, ankomsttid, återstående sträcka/tid, röst via bilens högtalare. Batterivärden och laddarknapp tillkommer i v0.3–v0.5.
    * `DestinationSearchScreen` (`SearchTemplate`): röst alltid, tangentbord endast parkerad; max 6 träffar (Photon).
    * `QuickDestinationsScreen` (`PlaceListNavigationTemplate`/`ListTemplate`): Hem, Jobb och senaste destinationer.
    * `RoutePreviewScreen` (`RoutePreviewNavigationTemplate`): tid, sträcka och Start-knapp.
    * **Synk telefon ↔ bil:** en gemensam navigeringssession; resa startad på ena skärmen fortsätter på den andra.
    * Mapbox-token anges endast på telefonen; saknas den visar bilskärmen "Set up ajNav on your phone".
    * Om Mapbox Android Auto Components drar in Google-beroenden (CI-spärren) byggs skärmarna direkt på Car App Library i stället.
  * **Karttema (beslut 2026-11-26):** Kartan färgsätts om i appen (ingen Mapbox Studio-stil, så det fungerar med allas egen nyckel). *Mörk* = LyzLarm-grön mörk ton (ersätter Mapbox standard-mörk), *Ljus* = ljus LyzLarm-variant. Inställning i appen: **Auto / Mörk / Ljus**; Auto följer bilens dag/natt-signal i Android Auto och telefonens mörkt läge på telefonen. Gäller både telefon och bil.
  * **Röst (beslut 2026-11-26):** Inställningar för **röstmotor** (Mapbox onlineröst / telefonens egen röst, Android TTS) och **röstspråk** (Telefonens språk / Svenska / English). Bakgrund: Mapbox svenska röst uttalade "Hemsögatan" som "Hemsgatan" trots korrekt text.
  * **Logoskärm i bilen (beslut 2026-11-26):** Knapp i bilens åtgärdsrad, *Dölj karta* / *Visa karta*, endast i fri körning (ingen aktiv rutt). Kartan ersätts av en mörk yta med ajNav-logotypen och hastighetsmärket (hastighet + gräns). Framtid: aktuell batterinivå och beräknad räckvidd (v0.4–v0.5). Startas en rutt (från bil eller telefon) visas kartan automatiskt igen.
  * **Notiser (`CarToast`):** Varning vid trafikstörning framåt (Trafikverket) eller om batteriet beräknas nå `< 8 %` före planerat stopp.

---

## 5. Leveransordning (versionsplan)
GPS-navigering först, bildata (OBD) i senare versioner. Varje version ska vara körbar och testbar i verkligheten innan nästa påbörjas. Varje version byggs först efter ägarens "Let's build version …" och föregås av en build brief med acceptanskriterier.

1. **v0.1 – Mobilnavigering (endast telefon):** `settings.gradle.kts`, `libs.versions.toml`, `local.properties.example`, `README.md`, Hilt-uppsättning, `:core:model`, `:core:security` (BYOK, `MAPBOX_PUBLIC_TOKEN`), samt `:feature:navigation` (Mapbox Navigation v3, `PhotonGeocodingProvider`) med destinationssökning, karta, rutt och röststyrd turn-by-turn via telefonens egen GPS (utan Google Play Services). Minimal `:app` med Navigering- och Inställningar-flikarna. Även: favoriter Hem/Jobb, senaste destinationer (lagras lokalt i DataStore; Room tillkommer i v0.3), aktuell hastighet + hastighetsgräns (från Mapbox), filvägledning (lane guidance) samt LyzLarm-temat. *Test: kör en verklig rutt med telefonen.*
2. **v0.2 – Android Auto:** `EvCarAppService`, `automotive_app_desc.xml`, `MainNavigationScreen`, `DestinationSearchScreen`, `QuickDestinationsScreen` (Hem/Jobb/senaste), `RoutePreviewScreen` samt synk telefon ↔ bil (se 4.7 C). *Test: direkt i den riktiga bilen (stock Android-telefon).*
3. **v0.3 – Laddstopp utan bilkoppling:** `:feature:vehicles` (`Fiat500e2021Profile` med kapacitet och laddkurva), `:feature:charging` (`OpenChargeMapProvider`, `ChainedTariffProvider`, Room offline-cache), Open-Meteo Elevation, SMHI-väder samt `EvRoutePlannerUseCase` med **manuellt inmatad SoC %** (`TelemetrySource.MANUAL`). Flikarna Operatörer & Priser, `ChargerListScreen` i Android Auto. Dessutom från v0.2-testerna: bilpilen ritas ovanför ruttlinjen, kartteman (grön mörk + ljus, Auto/Mörk/Ljus), logoskärm i bilen samt val av röstmotor och röstspråk (se 4.7 C). Webbplatsen (GitHub Pages) flyttas till `docs/` på `master` och utökas med Hem (funktioner + skärmbilder), Färdplan/status och FAQ/support, på engelska; integritetspolicyns adress behålls. *Test: planera en verklig resa och jämför med utfallet.*
4. **v0.4 – OBD-anslutning + NOBIL:** NOBIL live-tillgänglighet (lediga/upptagna uttag) via användarens egen NOBIL-nyckel (`OcpiNapTariffProvider`/NOBIL-klient), används i laddarrankningen. **OBD:** `:core:obd` (`ObdBleManager`, `ReadOnlyObdGuard`), Fiat-profilens `pollingCommands`, `MockVehicleProfile`, `VehicleTelemetryService` (ForegroundService, WakeLock, BLE-behörigheter) och Dashboard-fliken med live-data. Manuell SoC finns kvar som reserv. *Test: anslut till Fiat 500e och verifiera avläsningar (inkl. PID:ar markerade "alt.").*
5. **v0.5 – Live-planering:** Live-data matar `EvRoutePlannerUseCase` (viktad förbrukning, omplanering vid < 8 %, Coldgate-justering), live-värden i Android Auto, `TrafikverketClient` samt valfri `EnodeClient`. *Test: verkliga resor.*
6. **v1.0 – Release** efter verifierade tester i bil.

---

## 6. Ändringslogg
* **v1.4.3 (2026-11-26):** NOBIL live-tillgänglighet flyttas till v0.4 (v0.3 använder OCM + egna priser).
* **v1.4.2 (2026-11-26):** v0.3-planerare: 16 kWh/100 km standard, ankomst ≥ 10 %, ladda till 80 %, stopp läggs till av användaren, Coldgate uppskattas från SMHI-temperatur.
* **v1.4.1 (2026-11-26):** Webbplats: flyttas från grenen `gh-pages` till `docs/` på `master` (en gren); nya sidor Hem, Färdplan/status, FAQ/support (engelska). Planerat i v0.3.
* **v1.4 (2026-11-26):** Kartteman (grön mörk + ljus, Auto/Mörk/Ljus), logoskärm i bilen (fri körning, logotyp + hastighetsmärke) samt val av röstmotor och röstspråk. Planerat i v0.3 tillsammans med testfynd (inga delversioner: endast 0.2, 0.3 osv.).
* **v1.3.1 (2026-11-26):** (Ingår i v0.2.) Distribution för test i bil: Googles Android Auto visar bara mallappar installerade från Google Play. Testversioner distribueras därför via Play Console (intern testning) som signerad release-AAB byggd i CI (uppladdningsnyckel i GitHub Secrets). Appen förblir fri från Google Play Services. Publik distribution (Play/F-Droid/AARE) beslutas före v1.0.
* **v1.3 (2026-11-25):** v0.2-omfång för Android Auto fastställt (D12): standardmallar för karta/turn-by-turn, sök, snabbdestinationer och ruttförhandsvisning, synk telefon ↔ bil. Test direkt i bilen.
* **v1.2 (2026-11-25):** Godkänt undantag för Mapbox play-services-cronet. v0.1-omfång utökat (favoriter, senaste, hastighet/gräns, filvägledning), UI-tema (LyzLarm), engelskt UI, CI via GitHub Actions. Ny versionsplan: GPS-navigering först (v0.1 telefon, v0.2 Android Auto, v0.3 laddstopp med manuell SoC), OBD i v0.4 och live-planering i v0.5. Dependency Injection fastställd till Hilt. Coldgate-tröskel fastställd till 15 °C (ny profilegenskap `coldgateThresholdCelsius`; `optimalBatteryTempCelsius` = 20 °C används endast för Dashboard-indikatorn). Namnbyte OpenEV Nav → ajNav. Felkodsläsning (DTC) och fordonsdiagnostik borttagen ur scope (`DtcScanner`, `DtcRecord`, `dtc_descriptions.json`, Diagnostik-fliken, DTC-/12V-notiser). OBD begränsat till minimal vitlista: endast data för laddning, förbrukning och navigering (inga `03`/`07`/`19`/`09`/`ATRV`). En utvecklarkonsol för att verifiera nya PID:ar kan övervägas senare.
* **v1.1:** Ursprunglig version.
