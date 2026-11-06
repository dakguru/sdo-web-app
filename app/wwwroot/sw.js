/*  sw.js — service worker for the Karur SDO installable app.
 *  ─────────────────────────────────────────────────────────
 *  Served from the origin root (vercel.json rewrites /sw.js here), so its scope is
 *  the whole site and it controls every page.
 *
 *  Three jobs:
 *    1. Keep the app shell (CSS / JS / icons) available offline, so an installed
 *       iPhone app opens instantly and still works on a dead signal.
 *    2. Receive Web Push and raise the same two notifications the Android app
 *       raises — new chat messages and new Mail Overseer programmes.
 *    3. Focus the already-open app when a notification is tapped, rather than
 *       stacking up duplicate windows.
 *
 *  Bump CACHE_VERSION whenever a precached file changes, or installed clients
 *  will keep serving the old copy.
 */

'use strict';

const CACHE_VERSION = 'ksd-v6';
const SHELL_CACHE = CACHE_VERSION + '-shell';
const PAGE_CACHE = CACHE_VERSION + '-pages';
const ASSET_CACHE = CACHE_VERSION + '-assets';

const OFFLINE_URL = '/leave-orders/pwa/offline.html';

// The shell is small and versioned — fetch it up front so the first offline
// launch has chrome, fonts and icons rather than a blank screen.
const SHELL_FILES = [
  OFFLINE_URL,
  '/leave-orders/pwa/android-shell.css?v=6',
  '/leave-orders/pwa/android-shell.js?v=6',
  '/assets/pwa/icon-192.png',
  '/assets/pwa/badge-192.png',
];

// Anything under these paths is live data or an authenticated call — never cached.
const NEVER_CACHE = [/^\/api\//];

self.addEventListener('install', (event) => {
  event.waitUntil(
    caches
      .open(SHELL_CACHE)
      // addAll is all-or-nothing; one 404 would leave us with no shell at all,
      // so each file is added on its own and a miss is simply skipped.
      .then((cache) => Promise.all(SHELL_FILES.map((url) => cache.add(url).catch(() => {}))))
      .then(() => self.skipWaiting())
  );
});

self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches
      .keys()
      .then((names) =>
        Promise.all(
          names
            .filter((n) => !n.startsWith(CACHE_VERSION))
            .map((n) => caches.delete(n))
        )
      )
      .then(() => self.clients.claim())
  );
});

// The page asks for the new worker to take over immediately after an update.
self.addEventListener('message', (event) => {
  if (event.data === 'skip-waiting') self.skipWaiting();
});

function isNeverCached(url) {
  return NEVER_CACHE.some((re) => re.test(url.pathname));
}

/** Network first, falling back to the last good copy, then to the offline page. */
async function handlePage(request) {
  try {
    const fresh = await fetch(request);
    if (fresh && fresh.ok) {
      const copy = fresh.clone();
      caches.open(PAGE_CACHE).then((c) => c.put(request, copy)).catch(() => {});
    }
    return fresh;
  } catch (err) {
    const cached = await caches.match(request, { ignoreSearch: true });
    if (cached) return cached;
    const offline = await caches.match(OFFLINE_URL);
    if (offline) return offline;
    throw err;
  }
}

/** Serve from cache at once, refresh in the background for next time. */
async function handleAsset(request) {
  const cached = await caches.match(request);
  const network = fetch(request)
    .then((res) => {
      if (res && res.ok) {
        const copy = res.clone();
        caches.open(ASSET_CACHE).then((c) => c.put(request, copy)).catch(() => {});
      }
      return res;
    })
    .catch(() => null);
  return cached || network.then((res) => res || Response.error());
}

self.addEventListener('fetch', (event) => {
  const { request } = event;
  if (request.method !== 'GET') return;

  const url = new URL(request.url);
  if (url.origin !== self.location.origin) return; // Supabase, fonts, CDNs: leave alone
  if (isNeverCached(url)) return;

  if (request.mode === 'navigate') {
    event.respondWith(handlePage(request));
    return;
  }

  if (['style', 'script', 'image', 'font'].includes(request.destination)) {
    event.respondWith(handleAsset(request));
  }
});

// ───────────────────────────────────────────────────────────────────
//  Push — mirrors the Android app's two notification channels
// ───────────────────────────────────────────────────────────────────

self.addEventListener('push', (event) => {
  let payload = {};
  try {
    payload = event.data ? event.data.json() : {};
  } catch (err) {
    payload = { title: 'Karur SDO', body: event.data ? event.data.text() : '' };
  }

  const kind = payload.kind === 'programme' ? 'programme' : 'chat';
  const title = payload.title || 'Karur SDO';
  const body = payload.body || '';
  const url = payload.url || (kind === 'programme' ? '/mail-overseers' : '/messages');

  event.waitUntil(
    (async () => {
      // Don't buzz a reader who is already looking at that screen — the Android
      // app suppresses chat notifications the same way (Notifications.chatScreenVisible).
      if (kind === 'chat') {
        const clientList = await self.clients.matchAll({ type: 'window', includeUncontrolled: true });
        const reading = clientList.some(
          (c) => c.visibilityState === 'visible' && new URL(c.url).pathname === '/messages'
        );
        if (reading) return;
      }

      await self.registration.showNotification(title, {
        body,
        tag: kind, // one slot per kind, so a burst collapses instead of stacking
        renotify: true,
        icon: '/assets/pwa/badge-192.png',
        badge: '/assets/pwa/badge-192.png',
        data: { url },
        timestamp: payload.at || Date.now(),
      });
    })()
  );
});

self.addEventListener('notificationclick', (event) => {
  event.notification.close();
  const target = (event.notification.data && event.notification.data.url) || '/';

  event.waitUntil(
    (async () => {
      const clientList = await self.clients.matchAll({ type: 'window', includeUncontrolled: true });
      for (const client of clientList) {
        if ('focus' in client) {
          await client.focus();
          if ('navigate' in client && new URL(client.url).pathname !== target) {
            await client.navigate(target).catch(() => {});
          }
          return;
        }
      }
      if (self.clients.openWindow) await self.clients.openWindow(target);
    })()
  );
});

// Safari can drop a subscription on its own; re-register so pushes keep arriving.
self.addEventListener('pushsubscriptionchange', (event) => {
  event.waitUntil(
    (async () => {
      const res = await fetch('/api/push?action=public-key').catch(() => null);
      if (!res || !res.ok) return;
      const { publicKey } = await res.json();
      if (!publicKey) return;
      const sub = await self.registration.pushManager.subscribe({
        userVisibleOnly: true,
        applicationServerKey: publicKey,
      });
      await fetch('/api/push?action=subscribe', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ subscription: sub.toJSON() }),
      }).catch(() => {});
    })()
  );
});
