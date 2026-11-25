# iOS port — Compose Multiplatform migration plan

**Status:** scoping document, no code written yet
**Basis:** KarurSDO v2.17.0 (versionCode 35), commit `dac937b`
**Written:** 25 November 2026

---

## Verdict

The port is feasible and the codebase is unusually well-placed for it. **83% of the Kotlin
(14,313 of 17,060 lines) is already platform-neutral or close to it.** The 16% that needs real
work is bounded and enumerated below — it is dominated by two files, the PDF report renderers.

Two findings make this cheaper than a typical Android→iOS port:

1. **The DAO layer is already KMP-shaped.** Room KMP requires `suspend` functions and `Flow`
   return types off Android. `Daos.kt` has 141 `suspend` functions and 41 `Flow` returns,
   **zero `LiveData`, zero `@RawQuery`**. No reshaping needed.
2. **The Compose screens barely touch Android.** The four largest UI files (CpvScreens 1,970 LOC,
   MoScreens 1,069, UserScreens 932, ChatScreens 873) import Android APIs only for Hilt and
   `ViewModel`. Swap the DI framework and they compile for iOS essentially as-is.

**The hard requirement is a Mac.** Kotlin/Native cannot produce Apple binaries on Linux or
Windows — this is not something code can work around.

---

## Verified facts

Checked against primary sources on 25 November 2026, not from memory. Sources and what they
actually say:

| Fact | Source |
|---|---|
| Compose Multiplatform for iOS is **Stable**: "Android, iOS, and desktop are Stable and ready for production" | [kotlinlang.org/compose-multiplatform](https://kotlinlang.org/compose-multiplatform/) |
| **A Mac is mandatory**: "Building final binaries for Apple targets on Linux and Windows is also not possible." Also: "If your project uses cinterop dependencies (including CocoaPods), you must use a macOS host." | [native-target-support](https://kotlinlang.org/docs/native-target-support.html), dated 19 Aug 2026 |
| Default minimum deployment target: **iOS 15.0 and later**; `iosArm64` / `iosSimulatorArm64` are Tier 1 | same page |
| Room supports iOS via **`room3` artifacts, version 3.1.0-alpha04**, with `androidx.sqlite:sqlite-bundled` 2.7.1 | [Room KMP guide](https://developer.android.com/kotlin/multiplatform/room) |
| Room KMP recommends `BundledSQLiteDriver` — "includes SQLite compiled from source" | same page |
| Navigation Compose is multiplatform: "The Compose Multiplatform team contributes multiplatform support to the AndroidX Navigation library." Artifact `org.jetbrains.androidx.navigation:navigation-compose:2.10.0-rc01` | [compose-navigation](https://kotlinlang.org/docs/multiplatform/compose-navigation.html) |
| iOS back-swipe works by default: "on iOS the back gesture triggers native-like animation of the swipe transition" | same page |
| Ktor's iOS engine is **Darwin** (`io.ktor:ktor-client-darwin`), using `NSURLSession`. Ktor 3.6.1 | [Ktor client engines](https://ktor.io/docs/client-engines.html) |
| Koin supports iOS (`iosArm64`, `iosX64`, `iosSimulatorArm64`): "`koin-core` and the Compiler Plugin support every Kotlin target." Koin 4.2 | [Koin KMP setup](https://insert-koin.io/docs/reference/koin-core/kmp-setup) |
| Apple Developer Program: **$99/year**, unlocks TestFlight for up to 10,000 testers | [developer.apple.com/programs](https://developer.apple.com/programs/) |
| Free Apple Account can install to your own device, but profiles "expire 7 days from issuance", max 3 devices / 3 apps | [Developer account overview](https://developer.apple.com/support/compare-memberships/) |
| Distribution outside the App Store is limited to named regions — **Brazil, Japan and the EU**. India is not listed. | [developer.apple.com/distribute](https://developer.apple.com/distribute/) |

> **Flag:** Room KMP ships as `3.1.0-alpha04`. The app currently runs Room 2.7.1 stable against
> a 23-entity, version-12 schema holding Department of Posts data. Moving a production database
> onto an alpha library is a real risk and is listed as an open decision below.

---

## What moves and what doesn't

Measured across all 50 Kotlin files:

| Layer | LOC | Files | Disposition |
|---|---:|---:|---|
| Compose UI (screens, theme, nav, components) | 10,252 | 22 | Moves with mechanical edits |
| Data (Room entities/DAOs, repositories, sync, ingest) | 4,046 | 14 | Moves with library swaps |
| Other portable | 15 | 1 | Moves as-is |
| **Platform rewrite required** | **2,747** | **13** | See below |
| **Total** | **17,060** | **50** | |

### The 2,747 lines that need rewriting

| LOC | File | Why |
|---:|---|---|
| 640 | `report/CpvReportPdf.kt` | `android.graphics` Canvas/Paint/Typeface + `PdfDocument` |
| 605 | `MainActivity.kt` | Activity, BiometricPrompt, splashscreen, edge-to-edge, runtime permissions |
| 280 | `report/MoReportPdf.kt` | same as CpvReportPdf |
| 277 | `data/ingest/SheetReader.kt` | Apache POI (.xls) + XmlPullParser/ZipInputStream (.xlsx) |
| 257 | `di/AppModule.kt` | Hilt module + SQLCipher `SupportFactory` |
| 148 | `data/security/DbKeyManager.kt` | EncryptedSharedPreferences + Android Keystore |
| 143 | `data/sync/SupabaseClient.kt` | `HttpURLConnection` |
| 112 | `notify/AppNotifier.kt` | NotificationManager |
| 81 | `notify/Notifications.kt` | NotificationManager / channels |
| 64 | `notify/KsdMessagingService.kt` | Firebase Messaging service |
| 64 | `CrashReporter.kt` | UncaughtExceptionHandler + Android file paths |
| 60 | `notify/SyncNotificationWorker.kt` | WorkManager |
| 16 | `KarurSdoApp.kt` | `@HiltAndroidApp` Application |

### Small platform shims (not full rewrites)

These are scattered one-liners inside otherwise portable files. Each becomes a tiny
`expect`/`actual` pair:

| Usage | Sites | iOS equivalent |
|---|---:|---|
| `Intent.ACTION_DIAL` / `ACTION_SENDTO` / `geo:` | 8 | `UIApplication.openURL` with `tel:` / `sms:` / `maps:` |
| `DateUtils.getRelativeTimeSpanString` | 2 | ~20 lines of common Kotlin, or `kotlinx-datetime` |
| `ctx.assets.open(...)` | 2 | `Res.readBytes("files/...")` (Compose resources) |
| `Typeface.createFromAsset` | 2 | `Res.font` / CoreText |

---

## The nine platform replacements

| # | Android today | iOS / shared replacement | Difficulty |
|---|---|---|---|
| 1 | Hilt (`@HiltViewModel` ×12, `@Provides` ×21, `@Inject constructor` ×24) | **Koin 4.2** (`koin-core`, `koin-compose-viewmodel`) | Low — mechanical, but touches ~37 sites |
| 2 | `HttpURLConnection` | **Ktor 3.6.1**, `ktor-client-engine-defaults` in commonMain (Darwin on iOS, OkHttp on Android) | Low — the client is 143 LOC with a 4-method surface (upsert / delete / count / select) |
| 3 | Room 2.7.1 (`androidx.room`) | **`androidx.room3` 3.1.0-alpha04** + `androidx.sqlite:sqlite-bundled` 2.7.1, `BundledSQLiteDriver` | Medium — coordinates change, DAOs already compliant, 12 migrations must be re-verified |
| 4 | SQLCipher (`net.zetetic:sqlcipher-android`) | **No drop-in.** See open decision #1 | **High / needs your call** |
| 5 | EncryptedSharedPreferences + Android Keystore | **iOS Keychain** (`Security.framework`) behind an `expect` interface; or DataStore 1.3.0 for non-secret prefs | Medium |
| 6 | Apache POI HSSF (`.xls`) | **No KMP equivalent.** See open decision #2 | **Medium / needs your call** |
| 7 | `android.graphics.pdf.PdfDocument` + Canvas/Paint | **CoreGraphics `CGContext` / `UIGraphicsPDFRenderer`** behind a shared drawing interface | **High — the largest single piece** |
| 8 | WorkManager (15-min periodic sync) | **`BGTaskScheduler`** (`BGAppRefreshTask`). Note: iOS does not guarantee timing — the cadence becomes best-effort | Medium — behaviour changes, not just code |
| 9 | Firebase Messaging (Android service) | **Firebase iOS SDK + APNs**, requires an Apple Push certificate/key from the developer account | Medium |

---

## Target module structure

```
karursdo/
├── shared/                        # Kotlin Multiplatform module
│   ├── commonMain/                # ~14,300 LOC moves here
│   │   ├── data/db/               # Room entities + DAOs (unchanged shape)
│   │   ├── data/repo/             # repositories
│   │   ├── data/sync/             # SyncEngine (925 LOC, already 0 Android imports)
│   │   ├── data/ingest/           # ImportEngine, RecordMappers, xlsx parser
│   │   ├── ui/                    # every Compose screen
│   │   ├── report/                # shared report LAYOUT + an expect drawing canvas
│   │   └── platform/              # expect: dialer, keychain, pdf, files, notifier
│   ├── androidMain/               # actual: Intent, Keystore, PdfDocument, WorkManager, FCM
│   ├── iosMain/                   # actual: UIApplication, Keychain, CGContext, BGTaskScheduler
│   └── composeResources/          # drawable/ font/ files/ — fonts + seed JSON
├── androidApp/                    # thin: MainActivity, Application, manifest
└── iosApp/                        # thin: Xcode project, ComposeUIViewController, Info.plist
```

The key design decision is in `report/`: **keep the report layout logic in `commonMain` and make
only the drawing primitives `expect`.** The two report files are ~920 LOC, but most of that is
column arithmetic, pagination, text wrapping and the letterhead — all portable. Only the
primitive calls (`drawText`, `drawRect`, `drawLine`, `measureText`, typeface loading) need an
`actual` per platform. That turns a 920-line rewrite into roughly a 150-line interface plus two
backends, and it keeps the Android PDFs byte-identical to what's shipping today.

---

## Phased plan

Each phase ends at a point where the Android app still builds and ships — no long broken trunk.

**Phase 0 — Prove the toolchain (0.5 day, needs the Mac)**
Create the KMP skeleton, get an empty `ComposeUIViewController` running on an iPhone simulator
and then on a real device. Stop here if anything about signing or provisioning is going to bite.

**Phase 1 — Decouple from Hilt (2–3 days, no Mac needed)**
Replace Hilt with Koin *in the existing Android app*, on the current branch. Ship it as a normal
Android release and confirm nothing regressed. This is the single highest-value step: it unblocks
~10,000 LOC of UI and costs nothing if the port is later shelved.

**Phase 2 — Extract `shared/` (2–3 days, no Mac needed)**
Move the portable 83% into `commonMain` with Android still the only target. Swap
`HttpURLConnection` → Ktor and `androidx.room` → `androidx.room3`. Android app still builds and
passes its 15 unit tests. Still useful on its own even without iOS.

**Phase 3 — Platform shims (3–4 days)**
Write the `expect`/`actual` pairs for dialer, assets, relative-time, keychain and file paths.
Android `actual`s are the existing code, moved.

**Phase 4 — The report renderer (4–6 days)**
Refactor `CpvReportPdf` and `MoReportPdf` onto the shared drawing interface, Android backend
first. Verify the generated PDFs are visually identical to v2.17.0's output before touching iOS.
Then write the CoreGraphics backend.

**Phase 5 — iOS turn-on (4–6 days, Mac)**
Add the `iosArm64` / `iosSimulatorArm64` targets, write the iOS `actual`s, build, and work through
the compile errors screen by screen.

**Phase 6 — iOS-only subsystems (4–6 days, Mac)**
Biometric lock via `LocalAuthentication`, background refresh via `BGTaskScheduler`, push via
Firebase iOS + APNs, crash reporting.

**Phase 7 — Harden and ship (3–5 days, Mac)**
Device testing, Excel import on iOS, TestFlight build, distribute to Mail Overseers.

**Rough total: 5–7 weeks of focused work**, of which Phases 1–4 (about half) need no Mac and
leave the Android app strictly better even if iOS is abandoned.

---

## Open decisions — these need your call

### 1. Database encryption on iOS *(security posture — most important)*

SQLCipher has no Room KMP driver. Three options:

- **(a) iOS Data Protection** — mark the database file `NSFileProtectionComplete`. iOS encrypts it
  at rest with a key tied to the device passcode. *Protects a lost or stolen device, which is the
  realistic threat for a field app.* Readable while the device is unlocked and the app is running.
  Cheapest, and the conventional iOS answer.
- **(b) SQLCipher via cinterop/CocoaPods** — a custom `SQLiteDriver` wrapping SQLCipher for iOS.
  Matches Android's guarantee exactly. Significant work, and it forces a macOS host for all builds
  (per Kotlin's cinterop rule above).
- **(c) Application-layer encryption** — encrypt sensitive columns before they hit the DB. Portable,
  but invasive across the whole data layer and breaks query/sort on encrypted columns.

**My recommendation: (a).** It's the standard iOS posture and the threat model here is a lost
phone, not a compromised OS. But this is a data-protection decision for Department of Posts
records, so it's yours, not mine.

### 2. Legacy `.xls` import on iOS

`SheetReader.kt` has four parsing paths. Only one is actually stuck:

| Path | ~LOC | Portable? |
|---|---:|---|
| HTML-table fallback (HRMS exports saved as `.xls`) | 45 | **Yes** — pure Kotlin `Regex` |
| `.xlsx` custom OOXML parser | 147 | **Yes, with swaps** — needs a KMP zip reader + KMP XML parser |
| `.xls` via Apache POI HSSF | 32 | **No** — JVM-only, no KMP equivalent |
| Shared helpers | 53 | **Yes** |

So the code gap is ~32 lines, not 277 — but those 32 lines matter more than the count suggests.

I checked the magic bytes of all 10 `.xls` files in this repo. Eight are genuine BIFF (the
format only POI reads); two are HTML tables wearing an `.xls` extension, which the portable
path already handles:

| File | Real format |
|---|---|
| `CPV/New Format Data/{SSA, TD 1, TD 2, TD 3, TD 5}.xls` | **BIFF — needs POI** |
| `Office Master.xls` | **BIFF — needs POI** |
| `IR 2025/{SB, SSA} MINNAMPALLI.xls` | **BIFF — needs POI** |
| `Outsiders Details.xls` | HTML table — portable |
| `Temporary Arrangements.xls` | HTML table — portable |

**The CPV "New Format Data" files are BIFF.** That is the live source data for the Cent Percent
Verification module — the one the Mail Overseers actually use in the field. So dropping `.xls`
on iOS is not a cosmetic limitation; it would break CPV import on the iPhone. (The other 63
spreadsheets in the repo are `.xlsx` and port fine.)

Options:
- **(a)** Accept `.xlsx` and HTML-table only on iOS. **Not viable** given the above, unless the
  CPV upstream export can be switched to `.xlsx` at source — worth asking, since that would make
  the whole problem disappear.
- **(b)** Convert server-side in a Supabase Edge Function. *You already run one (`push-notify`),
  so the pattern and the deployment path exist.* Both platforms then share one import route, and
  Android gets to drop its 3.5 MB POI dependency.
- **(c)** Port the BIFF8 record parsing by hand — real work for an obsolete format.

**My recommendation: (b), or fix it upstream.** If whoever generates the CPV extracts can emit
`.xlsx` instead, that is by far the cheapest fix and it simplifies the Android app too.

### 3. Room alpha

`room3 3.1.0-alpha04` is alpha. Either accept it, or keep Android on stable Room and let iOS lead
on `room3` (costs a divergence), or wait for stable. **My recommendation: start Phases 1–4, which
don't depend on it, and re-check its status when you reach Phase 5.**

### 4. Notification cadence

WorkManager's 15-minute periodic sync has no exact iOS equivalent. `BGAppRefreshTask` is
best-effort and iOS decides when to run it. If near-real-time matters, push (FCM/APNs) has to
carry more of the load on iOS than it does on Android. Worth deciding before Phase 6.

---

## What it costs

| Item | Cost |
|---|---|
| Apple Developer Program | **$99/year** — needed for TestFlight; the free tier's 7-day expiry is unusable for staff |
| A Mac | Required, no workaround. Any Apple-silicon Mac can build this. |
| Apple Push key (APNs) | Included in the membership |
| Engineering | ~5–7 weeks, ~half of it Mac-free |
| Distribution | TestFlight (up to 10,000 testers). Sideloading is **not** available in India. |

---

## What I'd do first

**Phase 1 — replace Hilt with Koin in the existing Android app.** It needs no Mac, no Apple
account and no commitment to the port. It unblocks the largest block of code, it's independently
reviewable, and if the iOS project never happens you've lost nothing: the Android app ships the
same, just on a multiplatform-ready DI framework.

I can start that whenever you want it.
