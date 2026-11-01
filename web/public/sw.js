// VAYORA service worker: shows push notifications, opens the app when one is clicked, and
// stands a small "you are offline" page in for a page that cannot be fetched. It caches that
// page and nothing else: every other request goes to the network as if it were not here.

const CACHE = 'vayora-offline-v1';
const OFFLINE = '/offline.html';

self.addEventListener('install', (event) => {
  event.waitUntil(
    caches
      .open(CACHE)
      .then((cache) => cache.add(OFFLINE))
      .then(() => self.skipWaiting()),
  );
});

self.addEventListener('activate', (event) => {
  // An earlier version's cache is dropped once this one is in charge.
  event.waitUntil(
    caches
      .keys()
      .then((names) => Promise.all(names.filter((name) => name !== CACHE).map((name) => caches.delete(name))))
      .then(() => self.clients.claim()),
  );
});

self.addEventListener('fetch', (event) => {
  // Only whole pages: the API, scripts and images are never answered from here.
  if (event.request.mode !== 'navigate') return;
  event.respondWith(fetch(event.request).catch(() => caches.match(OFFLINE)));
});

self.addEventListener('push', (event) => {
  let message = { title: 'VAYORA', body: '', url: '/notifications' };
  try {
    message = { ...message, ...event.data.json() };
  } catch {
    // A push without a readable body still says something arrived.
  }
  event.waitUntil(self.registration.showNotification(message.title, { body: message.body, data: { url: message.url } }));
});

self.addEventListener('notificationclick', (event) => {
  event.notification.close();
  const url = (event.notification.data && event.notification.data.url) || '/notifications';
  event.waitUntil(
    self.clients.matchAll({ type: 'window', includeUncontrolled: true }).then((windows) => {
      const open = windows.find((client) => 'focus' in client);
      if (open) {
        open.navigate(url);
        return open.focus();
      }
      return self.clients.openWindow(url);
    }),
  );
});
