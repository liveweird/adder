/* global self */

self.addEventListener("push", (event) => {
  const body = self.navigator.language.startsWith("pl") ? "Masz nowe powiadomienia" : "You have new notifications";
  event.waitUntil(self.registration.showNotification("Adder", {
    body,
    tag: "adder-notifications",
    icon: "/favicon.svg",
  }));
});

self.addEventListener("notificationclick", (event) => {
  event.notification.close();
  event.waitUntil((async () => {
    const url = new URL("/?notifications=1", self.location.origin).href;
    const [existing] = await self.clients.matchAll({ type: "window", includeUncontrolled: true });
    const client = await existing?.navigate(url);
    if (client) await client.focus();
    else await self.clients.openWindow(url);
  })());
});
