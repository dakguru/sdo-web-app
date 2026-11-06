# Installable app (iPhone / Android) — deployment

The portal is installable as a Progressive Web App and can send the same two
notifications the Android app sends. Everything renders in Liquid Glass; the
structure (top app bar, five tabs, home sections) comes from the Android app.

## What shipped

| Piece | Where |
|---|---|
| Web manifest | `app/wwwroot/manifest.webmanifest` → served at `/manifest.webmanifest` |
| Service worker | `app/wwwroot/sw.js` → served at `/sw.js` (scope `/`) |
| App shell (chrome, theme, push client) | `Leave Orders/pwa/android-shell.{css,js}` |
| Home screen | `Leave Orders/pwa/home-android.js` (injected into `homepage.html`) |
| Offline page | `Leave Orders/pwa/offline.html` |
| Icons + iPhone launch images | `assets/pwa/` |
| Push endpoint | `api/push.js` → `/api/push` |
| New tabs | `Leave Orders/messages.html`, `Leave Orders/profile.html` |

The shell CSS/JS are loaded with a `?v=N` query. **Bump it whenever a file in
`Leave Orders/pwa/` changes**, or browsers and the service worker will keep
serving the old copy. The same number is `CACHE_VERSION` in `sw.js`.

## 1. Database

Run once in the Supabase SQL editor:

```sql
-- Web Push subscriptions, one row per installed app on one device.
-- The Android app's FCM tokens live in app_push_tokens; this is the iOS/web twin.
create table if not exists public.app_web_push_subs (
  endpoint       text primary key,
  p256dh         text not null,
  auth           text not null,
  username       text,
  user_agent     text,
  created_at_ms  bigint,
  updated_at_ms  bigint
);

-- Only the server (service-role key) ever touches this table: it holds the
-- keys needed to push to a device, so the anon key must not reach it.
alter table public.app_web_push_subs enable row level security;

create index if not exists app_web_push_subs_username_idx
  on public.app_web_push_subs (username);
```

No policies are created on purpose — with RLS on and no policy, the anon key
can read and write nothing, while `api/push.js` (service role) bypasses RLS.

## 2. Environment variables

Set these in the Vercel project (Settings → Environment Variables), never in
client JavaScript:

| Name | Value |
|---|---|
| `VAPID_PUBLIC_KEY` | `BNSZfC7xAZ-DZItbYD_AR9Pdco9iacoiScajBl7JXr-2nijiihthaHo8t8a6Keug_AWG0_hlEbGPDz7N5KWkfMU` |
| `VAPID_PRIVATE_KEY` | *(generated with the key pair above — keep secret)* |
| `VAPID_SUBJECT` | `mailto:` address of whoever owns the deployment |

`SUPABASE_URL` and `SUPABASE_SERVICE_ROLE_KEY` are already set for the Quiz
module and are reused here.

To generate a fresh pair (Node, no dependencies):

```bash
node -e "const c=require('crypto'),e=c.createECDH('prime256v1');e.generateKeys();const b=x=>Buffer.from(x).toString('base64url');console.log('public ',b(e.getPublicKey()));console.log('private',b(e.getPrivateKey()))"
```

Changing the keys invalidates every existing subscription — users have to turn
notifications on again.

## 3. How notifications flow

```
someone posts a message            →  app_messages row
  messages.html calls /api/push?action=notify {kind:'chat', id}
  api/push.js re-reads THAT row with the service key, builds the title and
  body itself, and fans out to every subscription except the author's
    →  sw.js 'push' event  →  showNotification()
```

The client never supplies the notification text — only a row id — so a forged
call cannot push arbitrary content, and a row older than 10 minutes is ignored
so an id cannot be replayed later as fresh news.

Wording matches `AppNotifier.kt` exactly:

- chat — **New message · \<display name\>** / the message body
- programme — **New MO I programme** / `\<date\> — \<details\>  (by \<author\>)`

Mail Overseer programmes are written by the Android app today; the moment a web
screen writes one it should call `KSD.push.notify('programme', id)` the same way.

## 4. iPhone: what users must do

iOS only allows notifications from a web app **installed to the Home Screen**
(iOS 16.4+). In Safari: Share → *Add to Home Screen* → open from the Home
Screen → Profile → *Turn on*. Profile explains this and hides the button until
the app is installed, rather than offering a control that cannot work.

Verify with *Send a test* on the Profile screen once notifications are on.

## 5. Checking it after a deploy

- `/manifest.webmanifest` returns JSON with `Content-Type: application/manifest+json`
- `/sw.js` returns JS with `Cache-Control: no-store`-ish revalidation and registers (DevTools → Application → Service Workers)
- `/api/push?action=public-key` returns `{"publicKey":"B…","configured":true}` — `configured:false` means an env var is missing
- Home, Message, Offices, MO and Profile all open from the tab bar
