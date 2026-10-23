
self.addEventListener('install', () => self.skipWaiting());

self.addEventListener('activate', event => {
  event.waitUntil(self.clients.claim());
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
