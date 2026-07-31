# Build Prompt — Native Android App
## "Karur Sub Division · Employee Directory & Office Management"

> **How to use this:** Paste this whole file into your coding agent (Claude Code, Cursor, etc.) as the spec. It is written to be self-contained. Where I say "match the existing web app," share a screenshot or the DB/JSON export so the agent can copy exact fields, labels, and colours. Verify the latest stable tool versions at build time (Android tooling moves fast) — do not pin the versions I mention below without checking.

---

## 1. What we're building

A **native Android app** that ports an existing internal web tool used by the **Department of Posts, Karur Sub Division (Tamil Nadu Circle)** to manage its offices and staff. The current tool runs locally in a browser; this is a first-class Android version of two of its modules — **Employee Directory** and **Office Management** — with the other modules stubbed and ready to grow.

- **Audience:** sub-divisional office staff / supervisors. Single-organisation, internal use. **Not** going on the Play Store — distribute as a signed APK/AAB for sideloading. (Because it's a permanently-private internal app, Google Play's target-API deadlines don't bind it, but we still target the current API for security and polish.)
- **Nature of data:** office-wise **departmental** staff, **GDS** (Gramin Dak Sevak) staff, and **outsider/contract** staff, each with full **service, bank, and pay** details, plus mapped **mobile numbers**. This is sensitive HR/PII — treat security as a first-class requirement (see §10).
- **Data lives on-device.** The app is **offline-first**; there is no server. Data is loaded by importing the monthly payroll Excel files (see §5).

### Scale (as of mid-2026 — will change monthly)
Total staff ≈ **393** · Offices ≈ **151** · Departmental ≈ **130** · GDS ≈ **263** · Outsiders ≈ **149**. Design for a few thousand records comfortably.

---

## 2. Non-negotiables — the feel

1. **Sleek, modern, Material 3 (Material You).** Airy layout, large rounded cards, pill badges, soft elevation, a violet/indigo brand accent — matching the existing web app's look.
2. **Butter-smooth motion.** 120 Hz-ready, zero jank, continuous shared-element transitions between list and detail, spring-based physics, predictive back. Motion is a headline feature, not an afterthought (see §8).
3. **Fast, offline, secure.** Instant search, cold start under ~1 s, encrypted local storage, app lock.

---

## 3. Tech stack

- **Language:** Kotlin (latest stable 2.x).
- **UI:** Jetpack Compose with **Material 3**. No XML layouts.
- **Architecture:** MVVM with unidirectional data flow (or MVI if you prefer), layered as `data / domain / ui`. Immutable UI state exposed via `StateFlow`; events flow up.
- **DI:** Hilt.
- **Persistence:** **Room** as the single source of truth, encrypted at rest with **SQLCipher**. Keys via Jetpack Security / `EncryptedSharedPreferences` (or the current AndroidX crypto successor — check at build time).
- **Async:** Kotlin Coroutines + Flow.
- **Navigation:** Navigation Compose with type-safe routes.
- **Excel import:** Apache POI (or a lighter `.xlsx` reader such as FastExcel) for `.xls`/`.xlsx` parsing, run off the main thread.
- **Avatars:** generated initials avatars (coloured tile from the person's initials, as in the web app's "AR" tile). Coil only if real images are ever needed.
- **Build:** Gradle Kotlin DSL + version catalog (`libs.versions.toml`).
- **SDK levels:** `minSdk = 26` (Android 8, broad device reach) · `compileSdk = 37` (Android 17, the current release) · `targetSdk = 36` (Android 16). Re-check the newest stable levels when you build.
- **Testing:** unit tests for the import/parsing and repository layers; a few Compose UI tests for the directory and detail screens.

---

## 4. Data model

Model these as Room entities. The **visible** fields below are authoritative; for everything else, **mirror whatever columns the existing dataset/Excel files actually contain** — design flexible schemas and don't drop fields.

- **Office** — `id`, `name` (e.g., "Mannur B.O", "Manavasi B.O", "Mushtakinathupatti B.O"), `type`/tags (Head Office / Sub Office / Branch Office; and whether it holds Departmental and/or GDS staff), derived `departmentalCount`, `gdsCount`. Office names use the postal suffixes **H.O / S.O / B.O**.
- **Employee** (covers Departmental and GDS) —
  - Identity: `name`, `employeeId` (e.g., `50089402`), `designation` (e.g., **ABPM**, and others like BPM/GDS-MC/Postman/PA/SPM/IP), `officeId`, `cadre` (**Departmental** | **GDS**), `level` (e.g., "2-8"), `payStatus` (e.g., **Paid**), `phone`.
  - **Service & Personal** section: carry over the existing fields (name, ID, plus likely date of birth, date of appointment, date of retirement, community/category, home office, etc. — use whatever the source has).
  - **Bank Details** section: bank name, account number, IFSC (mark as sensitive).
  - **Pay Details** section: basic pay, pay level, allowances, gross, etc.
- **Outsider** — `name`, `id`, `office`/agency, `phone`, engagement details, pay. Shown under the "Outsiders" tab.
- **MobileMapping** — raw imported mobile roster; matched to staff **by office + name** and merged into `Employee.phone`. Keep the raw import so re-matching is possible.
- **DataSource / ImportBatch** — mirrors the web app's freshness badges: `type` (**DS** = departmental, **GS** = GDS, **OUT** = outsiders, **TEL** = mobile), `fileName`, `recordCount`, `updatedDate`. Show these on the dashboard.
- **Office Management / Temporary Arrangements / Inspection Reports** entities — model to match the existing web app's data for those modules (see §6).

---

## 5. Data ingestion — "Update Monthly Data"

Reproduce the web app's upload panel exactly:

- **File picker (Storage Access Framework)** — let the user select **one or several** `.xls`/`.xlsx` files at once.
- **Auto-detect type** (DS / GS / Outsiders / Mobile Numbers) from the sheet headers and/or filename (e.g., "DS JUN 2026.xlsx", "GS Mayanur SO.xlsx", "Outsiders Details.xls", "Mobile Numbers.xlsx").
- **Upsert:** add new records, update existing ones (match departmental/GDS by `employeeId`; match outsiders and the mobile roster **by office + name**).
- **"Replace whole set for uploaded type(s)"** toggle — a destructive option that removes staff of that type not present in the upload. Guard it with a confirm dialog.
- Parse **off the main thread** with a visible progress indicator; produce a per-file **result summary** (added / updated / skipped) and a list of malformed rows the user can review.
- After import, update the **freshness badges** (DS · GS · OUT · TEL) with count, filename, and date.

---

## 6. Screens & navigation

The web app's top tabs are **Sub Divisional Manager · Employee Directory · Office Management · Temporary Arrangements · Inspection Reports →**. Map these into a clean Android structure — e.g., a **bottom navigation bar** for the primary destinations, with a large collapsing top app bar per screen. Build **Employee Directory** and **Office Management** fully; scaffold the rest to the same house style.

1. **Dashboard / Home**
   - Stat cards with **count-up animation**: Total Staff, Offices, Departmental, GDS, Outsiders.
   - "Update Monthly Data" entry (opens §5) and the four data-source **freshness badges**.
   - A global search entry.

2. **Employee Directory** ← core screen
   - **Segmented toggle:** *By Office* ↔ *Outsiders (149)* with a sliding selected indicator.
   - **Office list (By Office):** a *Jump to office* dropdown, a **search field** ("Search office name…"), **filter tabs** *All / Departmental / GDS*, and rows showing the office name + a count badge (e.g., "Mannur B.O · 2 GDS"). Show "X of Y" (e.g., "151 of 151").
   - **Office detail:** office header + list of its staff.
   - **Employee detail:** a **gradient hero card** (initials avatar, name, `designation · office`, and pill badges: **cadre**, **ID**, **level**, **pay status**, **phone**), followed by sections — **Service & Personal**, **Bank Details**, **Pay Details** — as labelled field rows. Tapping the phone badge offers **dial / SMS**; long-press copies. Include a "← Back to <office>" affordance.
   - **Outsiders tab:** searchable list of the 149 outsiders → outsider detail in the same card style.

3. **Office Management**
   - Manage offices (H.O / S.O / B.O; departmental/GDS), with sanctioned vs. posted strength, **vacancies**, and office metadata. Match the existing web app's fields and behaviour.

4. **Temporary Arrangements** *(scaffold to match the web app)*
   - Officiating / additional-charge / leave-vacancy / substitute arrangements, with the staff involved and start/end dates.

5. **Inspection Reports** *(scaffold)*
   - List + detail of Branch/Sub Office inspection reports.

6. **Sub Divisional Manager** *(scaffold — this is the landing/admin overview)*
   - Division-level summary and shortcuts.

> For the four scaffolded modules, replicate the existing web app's data and layout — ask me for a screenshot or export of each before finalising them.

---

## 7. Visual design system

- **Brand colour:** violet/indigo. The web app's header is a purple→indigo gradient (roughly `#7C3AED → #4F46E5`); the active toggle pill is violet; the employee hero card is a **dark navy/indigo gradient**. Use these as the Material 3 **seed/fallback** palette, and support **Material You dynamic colour** on Android 12+. Extract the exact hex values from the web app.
- **Surfaces:** light neutral backgrounds, white cards; full **dark theme**.
- **Shape:** large rounded corners (cards ~16–20 dp), **pill-shaped** chips/badges, soft/low elevation.
- **Typography:** Material 3 type scale with a clean sans (Inter / Roboto Flex / system default). Use **tabular figures** for the stat numbers and IDs.
- **Components to style consistently:** stat cards, segmented buttons, filter chips, search field, list rows with count badges, gradient profile hero card, labelled detail sections, the upload "dropzone," and rich empty/loading/error states.
- **Density:** comfortable and airy — mirror the web layout's generous spacing.

---

## 8. Motion & transitions — the "butter-smooth" mandate

Be deliberate here; this is what makes the app feel premium.

- Target **120 Hz**. Keep all animation on the render thread; profile for **zero dropped frames** during list scroll and screen transitions.
- **Shared-element transitions** (`SharedTransitionLayout` / stable Compose shared elements) for: office row → office detail, and employee row → employee detail — the **avatar, name, and badges morph continuously** into the hero card (container-transform feel).
- **Predictive back gesture** (Android 13+): the back-swipe previews the previous screen; wire up Compose predictive-back so it feels native.
- **Lists:** staggered entrance (fade + small translate) as items appear; animate insert/remove/reorder with item-placement animation; smooth **collapsing top app bar** (large → small) on scroll.
- **Segmented toggle & filter chips:** animated **sliding selected indicator**, not a hard swap.
- **Dashboard:** stat numbers **count up** on load.
- **Sheets & dialogs:** spring-based enter/exit.
- **Touch feedback:** ripple + subtle press-scale on cards/rows.
- **Physics:** prefer **spring** specs (medium/low bounce) for a lively-but-controlled feel; use standard Material easing for container transforms.
- **Accessibility:** honour the system **"remove animations / reduce motion"** setting.

---

## 8A. App-opening (splash) animation — India Post branding

Play a short, premium branded splash on cold start. **Provided asset:** the *official India Post logo* in full colour (भारतीय डाक / डाक सेवा-जन सेवा · the red envelope with gold motion streaks · "India Post" serif wordmark · "Dak Sewa-Jan Sewa" tagline). Bundle it in the app and reuse the same mark in the top app bar. This is the Department of Posts' official branding for an internal Karur Division tool — use it faithfully.

### Asset handling
- **Convert the logo to a vector** (`ImageVector` / `VectorDrawable`) where possible — the source is an SVG. Vector gives crisp scaling at every density and, crucially, lets you animate the **gold streaks with a path draw-on**. Keep the supplied `.webp` as a raster fallback.
- **Sample the exact brand colours from the asset — do not hardcode.** Approximate references only, to be verified against the file: India Post **red ≈ `#E4181C`**, **gold ≈ `#FDB913`**. Use the sampled red/gold as the splash accent; decide with me whether red or the violet UI accent leads the overall brand.

### Two layers
1. **System splash** (`androidx.core:core-splashscreen`) — the first cold-start frame: the India Post mark centred on a clean background, with a fade/scale **exit** that hands off seamlessly (no visible seam) into…
2. **Compose branded splash** — the full animated lockup below, then a smooth transition into the app (dashboard, or the biometric lock if enabled).

### Timeline (~2.4–2.8 s total · skippable on tap · honours reduce-motion)
Spring-based Compose animation, 120 Hz, zero jank. Drive it as a phased sequence (a coroutine timeline or a single `Transition` with keyframes).

- **0–0.7 s — Streak sweep (the hero moment).** The gold motion lines **draw/sweep in left-to-right**, as if the mail is arriving — a stroke-dash **path draw-on** on the vector with a gentle ease-out overshoot and a soft trailing/blur feel. Leans into the logo's own sense of motion.
- **0.4–1.1 s — Envelope + wordmark settle (overlaps).** The red envelope scales `0.92 → 1.0` and fades in; the Devanagari lines and the "India Post" serif wordmark fade + rise into place with a light spring settle.
- **1.1–1.6 s — Title reveal.** **"Karur Division Directory"** appears below the logo — fade + subtle upward translate, letter-spacing settling from slightly wide to normal, with a single refined shimmer sweeping across it once. Clean and confident.
- **1.6–2.1 s — Credit line.** **"made by Arun Selvaraj"** fades in near the bottom — **small, light-weight, letter-spaced, muted colour** (a tasteful italic or refined display face, always legible). Understated and elegant — this is the "small stylish font" line.
- **2.1–2.6 s — Hold + hand-off.** Brief hold, then transition out. *Preferred:* the India Post mark **shrinks and flies up to become the top-app-bar logo** (shared-element continuity) while the title and credit fade and the dashboard fades/slides in beneath (fade-through / shared-axis Z). *Fallback:* the whole lockup scales down and cross-fades into the app.

### Theming
- **Light (default):** near-white / soft warm off-white background with a very subtle radial vignette so the red + gold pop.
- **Dark:** deep charcoal / near-black; verify the logo stays legible (it reads well on dark). Match the system-splash background to the Compose-splash background so the hand-off is seamless.

### Behaviour & accessibility
- **Cold start:** full animation. **Warm resume / same session:** skip to a quick fade so repeat launches never feel slow.
- **Reduce-motion / "remove animations" on:** show a static branded lockup that simply fades in, then proceeds — no streak draw-on or shimmer.
- **Tap anywhere to skip** to the app.
- Content description on the logo: "India Post logo." Pre-load data behind the splash so the dashboard is ready when the animation ends; never block startup longer than the animation itself.

---

## 9. Search & performance

- **Instant, debounced** search across offices and employees (name, employee ID, designation, phone).
- Use Room **FTS** or well-indexed queries; `LazyColumn` with keys; paging if lists grow.
- Cold start **< ~1 s**; results update as the user types with no visible lag.

---

## 10. Security & privacy (staff PII)

This app holds names, employee IDs, phone numbers, and **bank + pay** details for government staff. Handle accordingly:

- **Encrypt the Room DB at rest** (SQLCipher) and any imported-file cache; store keys via Jetpack Security / the current AndroidX crypto library.
- **App lock:** require **biometric / device credential** (`BiometricPrompt`) on launch and after backgrounding.
- **No network egress** by default — the app is fully offline. Do not add analytics/cloud SDKs. If sync is ever added, it must be an explicit, authorised, separate decision.
- Apply **`FLAG_SECURE`** on screens showing bank/pay/PII to block screenshots and hide contents in the recents switcher.
- Provide **clear-data / lock** actions.

---

## 11. Accessibility, adaptivity & polish

- TalkBack content descriptions, ≥ 48 dp touch targets, dynamic type, AA contrast in both themes.
- Empty / loading / error states on every list and detail screen.
- **Adaptive layout:** on tablets/landscape, use a **two-pane** list+detail (directory on the left, detail on the right); phone stays single-pane with shared-element navigation.

---

## 12. Deliverables

- A modular Compose project (Gradle Kotlin DSL, version catalog) with the package layout above.
- **Seed/sample data** so the app runs before any import (a handful of offices, departmental + GDS + outsider records).
- A working **Excel import** for the four file types.
- **README** with build + sideload instructions and a signed internal **APK/AAB**.

---

## 13. Before you code — confirm these with me

1. The exact **column layouts** of the four Excel files (DS / GS / Outsiders / Mobile Numbers) and the full field list for the Service / Bank / Pay sections.
2. Screenshots or exports of **Office Management, Temporary Arrangements, Inspection Reports, and Sub Divisional Manager** so those match the web app.
3. The web app's exact **brand hex values** (header gradient, hero card, accent).
4. Whether the phone-number roster should stay editable in-app or is import-only.

Where I haven't specified something, follow the existing web app's behaviour and Material 3 defaults, and keep the motion and polish bar high throughout.
