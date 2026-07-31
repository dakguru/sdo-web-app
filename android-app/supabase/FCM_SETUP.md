# Push Notifications (FCM) — one-time setup

This switches the app from "poll every ~15 min when closed" to **instant** push for new chat
messages and Mail Overseer programmes, even when the app is closed. Do this once.

## A. Firebase project + google-services.json  (≈5 min)
1. Go to <https://console.firebase.google.com> → **Add project** (name e.g. `KarurSDO`). Google
   Analytics is optional — you can skip it.
2. In the project, click the **Android** icon (Add app).
   - **Android package name:** `com.karursdo`  (must match exactly)
   - Nickname / debug signing SHA-1: leave blank (not needed for FCM).
   - Click **Register app**.
3. **Download `google-services.json`** and place it at:
   `E:\SDO\android-app\app\google-services.json`
   (That's the `app/` folder next to `build.gradle.kts`.)
4. Skip the remaining "add SDK" wizard steps — the app already has the code.

That file alone turns on FCM in the app build. (Without it the app still works and falls back
to polling.)

## B. Service account for the server (Edge Function)  (≈3 min)
1. Firebase console → ⚙ **Project settings** → **Service accounts** tab.
2. Click **Generate new private key** → confirm → a JSON file downloads. Keep it secret.

## C. Supabase: tables, function, secret, webhooks
1. **Tables** — in the Supabase SQL editor run `migration_v8_reads_presence_datasets.sql`
   (creates `app_push_tokens` among others). Required for app v2.3.0 regardless of push.
2. **Install the Supabase CLI** (if not already) and from `E:\SDO\android-app\supabase`:
   ```bash
   supabase login
   supabase link --project-ref hfzbvqpxraeoqmvhtjto
   supabase secrets set FCM_SERVICE_ACCOUNT="$(cat /path/to/service-account.json)"
   supabase functions deploy push-notify --no-verify-jwt
   ```
   (On Windows PowerShell, set the secret with:
   `supabase secrets set FCM_SERVICE_ACCOUNT=(Get-Content -Raw .\service-account.json)`)
3. **Database Webhooks** — Supabase Dashboard → **Database → Webhooks → Create a new hook**,
   once for each table:
   - Table: `app_messages`, Events: **Insert** → Type: **Supabase Edge Function** →
     select `push-notify`. (Method POST; the default headers are fine.)
   - Repeat for table `app_programmes`, Events: **Insert** → `push-notify`.
   - If "Create webhook" fails with `schema "supabase_functions" does not exist`, enable the
     `pg_net` extension first (Database → Extensions → `pg_net` → ON), then retry.

That's it. New inserts fan out to every registered device except the sender's.

## D. Rebuild the app with FCM enabled
After `google-services.json` is in `app/`, rebuild:
```bash
cd E:\SDO\android-app
set JAVA_HOME=E:\SDO\android-toolchain\jdk-21.0.11+10
gradlew :app:assembleRelease
```
Install `app\build\outputs\apk\release\app-release.apk` (delivered as `KarurSDO-v2.3.0.apk`).

## Notes
- The device registers its FCM token on login (`app_push_tokens`). Users must sign in once on
  the new build for their device to start receiving push.
- Android 13+ shows a notification-permission prompt on first launch; it must be allowed.
- If a push doesn't arrive: check the Edge Function logs in the Supabase Dashboard, confirm the
  webhook fired, and that `app_push_tokens` has rows.
