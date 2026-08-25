# Supabase cloud sync — setup

The app syncs the **user layer only** — favorites, notes, preferences, and the
activity trail. The bundled staff directory (offices / DS / GDS / outsiders /
mobiles) is **never** uploaded and stays on the device.

Cloud sync is **off** until you supply two keys at build time. With no keys, the
app runs 100% offline exactly as before.

## 1. Create the Supabase project
1. Sign in at <https://supabase.com> and create a new project (free tier is fine).
   Pick a region close to Tamil Nadu (e.g. `ap-south-1` Mumbai) for lower latency.
2. Wait for it to finish provisioning.

## 2. Create the tables
1. Open **SQL Editor → New query**.
2. Paste the contents of [`schema.sql`](schema.sql) and click **Run**.
3. Confirm four tables now exist under **Table Editor**: `app_favorites`,
   `app_notes`, `app_prefs`, `app_activity`.

### Later migrations
Each app feature that adds a synced table ships its own `migration_vN_*.sql`. Run every one
that you haven't yet, in order, in the SQL Editor. The latest is
[`migration_v16_office_staff_events.sql`](migration_v16_office_staff_events.sql), which adds
`app_office_hours` (office working-hours edits), `app_staff_edits` (manual staff add + exit
details), and `app_events` (dashboard Events & Announcements). Until it is run, those features
work locally on each device but do not sync across users.

## 3. Get the keys
In **Project Settings → API**, copy:
- **Project URL** — e.g. `https://abcdxyz.supabase.co`
- **anon public** key (the long JWT under "Project API keys")

## 4. Put the keys in the build
Add these two lines to `android-app/local.properties` (this file is machine-local
and is not committed, so the key stays out of source control):

```
SUPABASE_URL=https://abcdxyz.supabase.co
SUPABASE_ANON_KEY=eyJhbGciOi...your-anon-key...
```

Then rebuild the APK. On next launch the app detects the keys and enables the
**Cloud sync** card on the Profile screen. Sync runs automatically on sign-in and
can be triggered manually with **☁ Sync now**.

## How sync works
- **Push:** any local change (star/unstar, add/delete note, edit profile, new
  activity) is marked pending and upserted on the next sync, then marked synced.
- **Pull:** remote rows are merged back last-write-wins (by update time for notes
  and prefs; favorites mirror remote state) without clobbering a pending local edit.
- **Shared dataset:** because the login is a single shared PIN, every device using
  the same build shares one dataset — a note added on one phone appears on another
  after each syncs.

## Security
`schema.sql` grants the **anon** role read/write on these four tables. That means
anyone who has both the project URL and the anon key can read/write them. That is
acceptable for an internal, controlled distribution, but note:

- These tables hold favorites/notes/prefs/activity — **notes can contain PII** if
  staff type it in. Treat the anon key as sensitive.
- For stronger control, create a single **Supabase Auth** account (Authentication →
  Users), change the policies from `to anon` to `to authenticated`, and have the app
  sign in with that shared account. (Requires a small code change to add the auth
  token to requests — ask to wire this up.)
- Do **not** widen these policies to other tables, and never put the service-role
  key in the app.
