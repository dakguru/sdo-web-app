# Karur SDO — Employee Directory & Office Management (Android)

Native Android port of the Karur Sub Division web tool (Department of Posts, Tamil Nadu
Circle). Fully **offline** — the app has **no INTERNET permission**; all data lives in an
**SQLCipher-encrypted** Room database on the device and is loaded by importing the monthly
payroll Excel files.

**Built modules:** Dashboard (stats + data freshness), **Employee Directory** (By Office /
Outsiders, global staff search, full service–bank–pay detail), **Office Management**
(office master, hierarchy/contact/operations, staff mapping), **Update Monthly Data**
(multi-file Excel import with auto-detection).
**Scaffolded:** Temporary Arrangements, Inspection Reports.

## Tech

Kotlin 2.1 · Jetpack Compose (Material 3) · MVVM + `StateFlow` · Hilt · Room + SQLCipher ·
Navigation Compose (type-safe routes) · Apache POI (legacy `.xls` only) + a custom
lightweight `.xlsx` reader (zip + XML pull parsing, mirroring the web app's `xlsx-lite.js`) ·
`minSdk 26`, `target/compileSdk 36`.

## Build

Prereqs: JDK 17+ and an Android SDK (platform 36, build-tools 36). This repo was built with
the portable toolchain in `..\android-toolchain` (JDK 21 + SDK cmdline-tools) — no Android
Studio required.

```powershell
# from android-app/
$env:JAVA_HOME = "C:\Users\ADMIN\Desktop\SDO\android-toolchain\jdk-21.0.11+10"
..\android-toolchain\gradle-8.11.1\bin\gradle.bat :app:assembleRelease   # signed APK
..\android-toolchain\gradle-8.11.1\bin\gradle.bat :app:testDebugUnitTest # unit tests
```

`local.properties` must point `sdk.dir` at the SDK. The release build is signed with the
internal keystore at `keystore/karursdo-internal.jks` (alias `karursdo`; override passwords
via `KARURSDO_STORE_PW` / `KARURSDO_KEY_PW` env vars — the checked-in defaults are for
internal sideloading only). Output: `app/build/outputs/apk/release/app-release.apk`.

## Sideload (internal distribution — not for Play Store)

1. Copy `app-release.apk` to the phone (USB / file share).
2. On the phone: Settings → allow "Install unknown apps" for your file manager.
3. Open the APK and install. First launch **pre-loads the full real dataset** bundled in
   `app/src/main/assets/data/` (exports of the web tool's `employees.json`,
   `office_master.json`, `mobiles.json` — JUN 2026 payroll: 130 DS + 263 GDS + 149
   outsiders + 341 offices + mobile map). Because of this, the APK itself contains
   staff PII — distribute it only to authorised staff.
4. For the monthly refresh, go to **Home → Update monthly data** and import the new files:
   - `DS <month>.xlsx` (departmental payroll — detected by the "Basic Pay" column)
   - `GS <month>.xlsx` (GDS payroll — detected by TRCA/SDBS; the single-office
     "GS Mayanur SO" variant with its extra `Concat` column also works)
   - `Outsiders Details.xls` (legacy .xls supported)
   - `Mobile Numbers.xlsx` (roster matched to staff by office + fuzzy name, exactly like
     the web app: office suffix stripping, aliases, Levenshtein tolerance)
5. Optional: tick "Replace whole set for uploaded type(s)" to drop staff who left
   (guarded by a confirm dialog).

## Security

- Room DB encrypted at rest with **SQLCipher**; the random passphrase lives only in
  `EncryptedSharedPreferences` (Android Keystore-backed).
- **App lock**: BiometricPrompt (biometric or device credential) on launch and again after
  the app goes to background.
- **FLAG_SECURE** app-wide: no screenshots, blank card in the recents switcher.
- Cloud backup & device-transfer are fully excluded (`data_extraction_rules.xml`).
- No analytics, no network SDKs, no INTERNET permission.

## Design fidelity

Colors, badges, field labels and section order are sampled from the web app
(`Leave Orders/employees.html` et al.): header gradient `#1e3a5f → #4f46e5 → #7c3aed`,
hero card `#1e3a5f → #4f46e5`, brand gradient `#4f46e5 → #7c3aed → #ec4899`, exact
badge palette (DS `#e0e7ff/#3730a3`, GDS `#ccfbf1/#0f766e`, OUT `#fef9c3/#854d0e`,
TEL `#dcfce7/#166534`), and the same positional pay-breakdown slicing
(Status → Total_earnings → Total_deductions → Total_thirdparty_deduction).
India Post red `#E4181C` / gold `#FDB913` drive the branded splash
("Karur Division Directory" · credit line "made by Arun Selvaraj", skippable,
honours the system remove-animations setting).

## Known deltas / next steps

- The splash animates the bundled raster logo with a sweep reveal; converting the official
  SVG to an `ImageVector` would enable a true stroke draw-on for the gold streaks.
- Shared-element (container-transform) list→detail morphs are stubbed with slide/fade
  transitions; wire `SharedTransitionLayout` keys on avatar/name next.
- Temporary Arrangements shows imported data read-only; the report builder
  (category/sub-division/date filters + Excel/PDF export) is future work.
- Office Management mirrors the web module (office master + mapped staff); the web app has
  no sanctioned-strength/vacancy data, so neither does this port.
