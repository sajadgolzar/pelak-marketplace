
self.addEventListener('install', () => self.skipWaiting());

// Only cache public site assets. Never cache Supabase API, private photos or auth.
const PELAK_CACHE = 'pelak-public-shell-v2';
const PELAK_CACHE_PREFIX = 'pelak-public-shell-';
const PELAK_STATIC = new Set([
  '/pelak-simple.css', '/pelak-simple.js', '/pelak-android-bridge.js',
  '/pelak-logo.jpg', '/pelak-sclass-hero.webp',
  '/pelak-icon-192.png', '/pelak-icon-512.png'
]);

self.addEventListener('activate', event => {
  event.waitUntil((async () => {
    const names = await caches.keys();
    await Promise.all(names.filter(n => n.startsWith(PELAK_CACHE_PREFIX) && n !== PELAK_CACHE)
      .map(n => caches.delete(n)));
    await self.clients.claim();
  })());
});

// Network-first: online always gets the latest code; offline gets the last known-good shell.
self.addEventListener('fetch', event => {
  const request = event.request;
  if (request.method !== 'GET') return;
  const url = new URL(request.url);
  if (url.origin !== self.location.origin) return;

  const isHome = url.pathname === '/' || url.pathname === '/index.html';
  const isStatic = PELAK_STATIC.has(url.pathname);
  if (!(isStatic || (isHome && request.mode === 'navigate'))) return;

  event.respondWith((async () => {
    const cache = await caches.open(PELAK_CACHE);
    const key = isHome ? new URL('/', self.registration.scope).href : request;
    try {
      const response = await fetch(request);
      if (response.ok && response.type === 'basic') {
        // Cache only public HTML/assets, never cookies or personalized API responses.
        await cache.put(key, response.clone());
      }
      return response;
    } catch (_) {
      const cached = await cache.match(key);
      if (cached) return cached;
      return new Response('<!doctype html><html lang="fa" dir="rtl"><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"><title>پلاک | بدون اینترنت</title><main style="font-family:sans-serif;max-width:430px;margin:20vh auto;padding:24px;text-align:center"><h1>پلاک</h1><p>اتصال اینترنت برقرار نیست. دوباره امتحان کن.</p><a href="/" style="display:inline-block;padding:14px;background:#10233f;color:white;border-radius:12px">تلاش مجدد</a></main></html>', {
        status: 503, headers: { 'content-type': 'text/html;charset=utf-8', 'cache-control': 'no-store' }
      });
    }
  })());
});

self.addEventListener('push', event => {
  let data = {};

  try {
    data = event.data?.json() || {};
  } catch (_) {}

  const kind = [
    'new_draft',
    'listing_message',
    'support_message'
  ].includes(data.kind) ? data.kind : 'message';

  const title =
    kind === 'new_draft'
      ? 'پلاک | آگهی جدید'
      : kind === 'listing_message'
        ? 'پلاک | پیام جدید'
        : 'پلاک | پیام پشتیبانی';

  const body =
    kind === 'new_draft'
      ? 'یک آگهی جدید در انتظار بررسی است.'
      : 'پیام تازه‌ای دریافت کرده‌اید.';

  const target =
    kind === 'new_draft'
      ? 'admin'
      : kind === 'support_message'
        ? 'support'
        : 'messages';

  event.waitUntil(
    self.registration.showNotification(title, {
      body,
      tag: data.id || kind,
      renotify: false,
      icon: './pelak-icon-192.png',
      badge: './pelak-icon-192.png',
      data: { target }
    })
  );
});

self.addEventListener('notificationclick', event => {
  event.notification.close();

  const target =
    event.notification.data?.target || 'messages';

  const url = new URL('./', self.registration.scope);
  url.searchParams.set('pelakView', target);

  event.waitUntil((async () => {
    const windows = await clients.matchAll({
      type: 'window',
      includeUncontrolled: true
    });

    for (const client of windows) {
      if (new URL(client.url).origin === url.origin) {
        await client.navigate(url.href);
        return client.focus();
      }
    }

    return clients.openWindow(url.href);
  })());
});
