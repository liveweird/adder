import { afterEach, beforeEach, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { renderWithProviders, screen } from "../test/render";
import { jsonResponse } from "../test/http";
import { clearSession, getToken, persistSession } from "../api/session";
import { logout } from "../api/auth";
import {
  currentBrowserPushId,
  detachBrowserPushForLogout,
  disableBrowserPush,
  enableBrowserPush,
  getBrowserPushConfig,
  takeBrowserPushForLogout,
} from "../api/browserPush";
import BrowserPushCard from "./BrowserPushCard";

const endpoint = "https://fcm.googleapis.com/fcm/send/fixture";
const configPath = "/api/v1/notifications/push/config";
const subscriptions = "/api/v1/notifications/push/subscriptions";

function browser(permission: NotificationPermission = "default", applicationServerKey = Uint8Array.of(4)) {
  const unsubscribe = vi.fn(async () => true);
  const subscription = { endpoint, unsubscribe, options: { applicationServerKey: applicationServerKey.buffer } };
  let currentSubscription: typeof subscription | null = permission === "granted" ? subscription : null;
  const subscribe = vi.fn(async () => {
    currentSubscription = subscription;
    return subscription;
  });
  const registration = { pushManager: { subscribe, getSubscription: vi.fn(async () => currentSubscription) } };
  let lockTail = Promise.resolve<unknown>(undefined);
  const locks = {
    request: vi.fn((_name: string, callback: () => Promise<unknown>) => {
      const result = lockTail.then(callback);
      lockTail = result.catch(() => undefined);
      return result;
    }),
  };
  const serviceWorker = {
    register: vi.fn(async () => registration),
    ready: Promise.resolve(registration),
    getRegistration: vi.fn(async () => registration),
  };
  const notification = { permission, requestPermission: vi.fn<() => Promise<NotificationPermission>>() };
  const requestPermission = notification.requestPermission.mockImplementation(async () => {
    notification.permission = "granted";
    return "granted";
  });
  vi.stubGlobal("navigator", { ...navigator, locks, serviceWorker });
  vi.stubGlobal("isSecureContext", true);
  vi.stubGlobal("PushManager", class {});
  vi.stubGlobal("Notification", notification);
  return { locks, registration, requestPermission, serviceWorker, subscribe, subscription, unsubscribe };
}

function api(available = true, registered = true) {
  const send = vi.fn(async (input: string) => {
    if (input.startsWith(configPath)) {
      return jsonResponse(200, available ? { available, publicKey: "BA", registered } : { available });
    }
    if (input === subscriptions) return jsonResponse(200, { id: 9 });
    return new Response(null, { status: 204 });
  });
  vi.stubGlobal("fetch", send);
  return send;
}

beforeEach(() => {
  localStorage.setItem("adder.auth.token", "fixture-token");
  localStorage.setItem("adder.auth.userId", "7");
});

afterEach(() => {
  localStorage.clear();
  vi.unstubAllGlobals();
});

test("the explicit browser action registers a device and disabling removes that device", async () => {
  const user = userEvent.setup();
  const { requestPermission, serviceWorker, subscribe, unsubscribe } = browser();
  const send = api();
  renderWithProviders(<BrowserPushCard />);

  const enable = await screen.findByRole("button", { name: "Enable on this browser" });
  expect(serviceWorker.register).toHaveBeenCalledWith("/adder-push.js", { scope: "/" });
  expect(requestPermission).not.toHaveBeenCalled();
  await user.click(enable);
  const disable = await screen.findByRole("button", { name: "Disable on this browser" });
  expect(requestPermission).toHaveBeenCalledOnce();
  expect(subscribe).toHaveBeenCalledWith({ userVisibleOnly: true, applicationServerKey: Uint8Array.of(4) });
  expect(send).toHaveBeenCalledWith(subscriptions, expect.objectContaining({ method: "POST", body: JSON.stringify({ endpoint }) }));

  await user.click(disable);
  await screen.findByRole("button", { name: "Enable on this browser" });
  expect(send).toHaveBeenCalledWith(`${subscriptions}/9`, expect.objectContaining({ method: "DELETE" }));
  expect(unsubscribe).toHaveBeenCalledOnce();
});

test("server configuration and browser permission both gate the enable control", async () => {
  const unavailable = browser();
  api(false);
  const view = renderWithProviders(<BrowserPushCard />);
  expect(await screen.findByText("Browser notifications are unavailable on this server.")).toBeVisible();
  expect(unavailable.serviceWorker.register).not.toHaveBeenCalled();
  expect(screen.queryByRole("button", { name: "Enable on this browser" })).toBeNull();
  view.unmount();

  const denied = browser("denied");
  api();
  renderWithProviders(<BrowserPushCard />);
  expect(screen.getByText(/Allow notifications in this browser/)).toBeVisible();
  expect(screen.queryByRole("button", { name: "Enable on this browser" })).toBeNull();
  expect(denied.requestPermission).not.toHaveBeenCalled();
});

test("enabling after browser permission is already granted does not prompt again", async () => {
  const user = userEvent.setup();
  const { requestPermission } = browser("granted");
  const send = api();
  renderWithProviders(<BrowserPushCard />);

  await user.click(await screen.findByRole("button", { name: "Enable on this browser" }));

  expect(await screen.findByRole("button", { name: "Disable on this browser" })).toBeVisible();
  expect(requestPermission).not.toHaveBeenCalled();
  expect(send).toHaveBeenCalledWith(subscriptions, expect.objectContaining({ method: "POST" }));
});

test("a disabled server detaches a previously enrolled browser and queues server cleanup", async () => {
  const { serviceWorker, unsubscribe } = browser("granted");
  api(false);
  localStorage.setItem("adder.browserPush", JSON.stringify({ userId: 7, id: 9 }));

  renderWithProviders(<BrowserPushCard />);

  expect(await screen.findByText("Browser notifications are unavailable on this server.")).toBeVisible();
  expect(screen.queryByRole("button", { name: "Disable on this browser" })).toBeNull();
  expect(serviceWorker.register).not.toHaveBeenCalled();
  expect(unsubscribe).toHaveBeenCalledOnce();
  expect(currentBrowserPushId()).toBeNull();
  expect(localStorage.getItem("adder.browserPush")).toContain('"pending"');
});

test("an unsupported browser leaves the notification preferences usable", () => {
  vi.stubGlobal("isSecureContext", false);
  const send = api();
  renderWithProviders(<BrowserPushCard />);
  expect(screen.getByText(/cannot receive background notifications/)).toBeVisible();
  expect(send).not.toHaveBeenCalled();
});

test("invalid and other-account device records are ignored", () => {
  localStorage.setItem("adder.browserPush", "not-json");
  expect(currentBrowserPushId()).toBeNull();

  localStorage.setItem("adder.browserPush", JSON.stringify({
    active: { userId: 8, id: 9, bindingId: "other-account" },
  }));
  expect(currentBrowserPushId()).toBeNull();

  localStorage.setItem("adder.browserPush", JSON.stringify({
    active: { userId: 7, id: 9, bindingId: "current-account" },
  }));
  expect(currentBrowserPushId()).toBe(9);
});

test("late logout cleanup does not detach a replacement account's binding without Web Locks", async () => {
  const { serviceWorker, unsubscribe } = browser("granted");
  vi.stubGlobal("navigator", { ...navigator, locks: undefined, serviceWorker });
  localStorage.setItem("adder.browserPush", JSON.stringify({ userId: 7, id: 9 }));
  const oldBinding = takeBrowserPushForLogout();
  localStorage.setItem("adder.browserPush", JSON.stringify({
    active: { userId: 8, id: 10, bindingId: "replacement" },
    pending: [],
  }));

  await detachBrowserPushForLogout(oldBinding);

  expect(unsubscribe).not.toHaveBeenCalled();
});

test("logout cleanup leaves a provider subscription that changed while it was being inspected", async () => {
  const device = browser("granted");
  const replacement = { ...device.subscription, endpoint: `${endpoint}/replacement` };
  device.registration.pushManager.getSubscription
    .mockResolvedValueOnce(device.subscription)
    .mockResolvedValueOnce(replacement);
  localStorage.setItem("adder.browserPush", JSON.stringify({ userId: 7, id: 9 }));
  const oldBinding = takeBrowserPushForLogout();

  await detachBrowserPushForLogout(oldBinding);

  expect(device.unsubscribe).not.toHaveBeenCalled();
});

test("logout cleanup tolerates an already removed provider subscription", async () => {
  const device = browser("granted");
  device.registration.pushManager.getSubscription.mockResolvedValue(null);
  localStorage.setItem("adder.browserPush", JSON.stringify({ userId: 7, id: 9 }));

  await detachBrowserPushForLogout(takeBrowserPushForLogout());

  expect(device.unsubscribe).not.toHaveBeenCalled();
});

test("device enrollment falls back when Web Locks cannot be acquired", async () => {
  const device = browser();
  const locks = { request: vi.fn(() => { throw new DOMException("unavailable", "SecurityError"); }) };
  vi.stubGlobal("navigator", { ...navigator, locks, serviceWorker: device.serviceWorker });
  api();

  await expect(enableBrowserPush({ publicKey: "BA", permission: Promise.resolve("granted") })).resolves.toBe(9);

  expect(device.subscribe).toHaveBeenCalledOnce();
  expect(locks.request).toHaveBeenCalledOnce();
});

test("the lock fallback does not publish a subscription removed during registration", async () => {
  let currentSubscription: PushSubscription | null = null;
  const unsubscribe = vi.fn(async () => {
    currentSubscription = null;
    return true;
  });
  const subscription = {
    endpoint: `${endpoint}/new-account`,
    unsubscribe,
    options: { applicationServerKey: Uint8Array.of(4).buffer },
  } as unknown as PushSubscription;
  const pushManager = {
    getSubscription: vi.fn(async () => currentSubscription),
    subscribe: vi.fn(async () => {
      currentSubscription = subscription;
      return subscription;
    }),
  };
  const registration = { pushManager } as unknown as ServiceWorkerRegistration;
  const serviceWorker = {
    ready: Promise.resolve(registration),
    getRegistration: vi.fn(async () => registration),
  };
  vi.stubGlobal("navigator", { ...navigator, locks: undefined, serviceWorker });
  vi.stubGlobal("isSecureContext", true);
  vi.stubGlobal("PushManager", class {});
  vi.stubGlobal("Notification", { permission: "granted" });
  localStorage.setItem("adder.browserPush", JSON.stringify({ userId: 7, id: 7 }));
  const oldBinding = takeBrowserPushForLogout();
  persistSession({
    token: "replacement-token",
    expiresAt: 2_000_000_000_000,
    refreshToken: "replacement-refresh",
    refreshExpiresAt: 2_000_000_000_000,
    userId: 8,
    roles: [],
    disabledFeatures: [],
    language: "en",
  });
  let finishPost: (response: Response) => void = (_response) => undefined;
  const post = new Promise<Response>((resolve) => { finishPost = resolve; });
  vi.stubGlobal("fetch", vi.fn((input: string) => input === subscriptions
    ? post
    : Promise.resolve(new Response(null, { status: 204 }))));
  const enrollment = enableBrowserPush({ publicKey: "BA", permission: Promise.resolve("granted") });
  await vi.waitFor(() => expect(pushManager.subscribe).toHaveBeenCalledOnce());

  await detachBrowserPushForLogout(oldBinding);
  finishPost(jsonResponse(200, { id: 10 }));

  await expect(enrollment).resolves.toBeNull();
  expect(currentBrowserPushId()).toBeNull();
  expect(localStorage.getItem("adder.browserPush")).toContain('"id":10');
});

test("an account switch during native verification cannot publish the old account’s subscription", async () => {
  const device = browser("granted");
  api();
  device.registration.pushManager.getSubscription
    .mockResolvedValueOnce(device.subscription)
    .mockImplementationOnce(async () => {
      clearSession();
      return device.subscription;
    });

  await expect(enableBrowserPush({ publicKey: "BA", permission: Promise.resolve("granted") })).resolves.toBeNull();
  expect(JSON.parse(localStorage.getItem("adder.browserPush")!)).toEqual({
    pending: [{ userId: 7, id: 9, bindingId: expect.any(String) }],
  });
  expect(device.unsubscribe).toHaveBeenCalledOnce();
});

test("logout captures the browser subscription before clearing the session", async () => {
  const { unsubscribe } = browser("granted");
  const send = api();
  localStorage.setItem("adder.browserPush", JSON.stringify({ userId: 7, id: 9 }));
  const finished = logout();
  expect(getToken()).toBeNull();
  await finished;
  expect(send).toHaveBeenCalledWith("/api/v1/logout", expect.objectContaining({
    headers: expect.objectContaining({ Authorization: "Bearer fixture-token", "X-Adder-Push-Subscription": "9" }),
  }));
  expect(unsubscribe).toHaveBeenCalledOnce();
  expect(localStorage.getItem("adder.browserPush")).toContain('"pending"');
});

test("a logout response never substitutes for authenticated subscription deletion", async () => {
  const { unsubscribe } = browser("granted");
  const send = api();
  localStorage.setItem("adder.browserPush", JSON.stringify({ userId: 7, id: 9 }));

  await logout();

  expect(unsubscribe).toHaveBeenCalledOnce();
  expect(localStorage.getItem("adder.browserPush")).toContain('"id":9');
  localStorage.setItem("adder.auth.token", "fixture-token-2");
  localStorage.setItem("adder.auth.userId", "7");
  await getBrowserPushConfig();
  expect(send).toHaveBeenCalledWith(`${subscriptions}/9`, expect.objectContaining({ method: "DELETE" }));
  expect(localStorage.getItem("adder.browserPush")).toBeNull();
});

test("native unsubscribe failure cannot discard pending authenticated cleanup", async () => {
  const device = browser("granted");
  device.unsubscribe.mockRejectedValueOnce(new Error("provider unavailable"));
  api();
  localStorage.setItem("adder.browserPush", JSON.stringify({ userId: 7, id: 9 }));

  await expect(logout()).resolves.toBeUndefined();

  expect(localStorage.getItem("adder.browserPush")).toContain('"id":9');
  expect(currentBrowserPushId()).toBeNull();
});

test("offline logout detaches the native subscription and retries server cleanup for the same account", async () => {
  const { unsubscribe } = browser("granted");
  const send = vi.fn(async (input: string) => {
    if (input === "/api/v1/logout") throw new Error("offline");
    if (input.startsWith(configPath)) return jsonResponse(200, { available: true, publicKey: "BA" });
    return new Response(null, { status: 204 });
  });
  vi.stubGlobal("fetch", send);
  localStorage.setItem("adder.browserPush", JSON.stringify({ userId: 7, id: 9 }));

  await logout();

  expect(unsubscribe).toHaveBeenCalledOnce();
  expect(localStorage.getItem("adder.browserPush")).not.toBeNull();
  localStorage.setItem("adder.auth.token", "fixture-token-2");
  localStorage.setItem("adder.auth.userId", "7");
  renderWithProviders(<BrowserPushCard />);
  await screen.findByRole("button", { name: "Enable on this browser" });
  expect(send).toHaveBeenCalledWith(`${subscriptions}/9`, expect.objectContaining({ method: "DELETE" }));
  expect(localStorage.getItem("adder.browserPush")).toBeNull();
});

test("an expired server registration is removed locally and offered for re-enrollment", async () => {
  const { unsubscribe } = browser("granted");
  const send = api(true, false);
  localStorage.setItem("adder.browserPush", JSON.stringify({ userId: 7, id: 9 }));

  renderWithProviders(<BrowserPushCard />);

  expect(await screen.findByRole("button", { name: "Enable on this browser" })).toBeVisible();
  expect(send).toHaveBeenCalledWith(`${configPath}?subscriptionId=9`, expect.anything());
  expect(unsubscribe).toHaveBeenCalledOnce();
});

test("a missing native subscription is reconciled as disabled", async () => {
  const device = browser("granted");
  device.registration.pushManager.getSubscription.mockResolvedValue(null);
  api(true, true);
  localStorage.setItem("adder.browserPush", JSON.stringify({ userId: 7, id: 9 }));

  renderWithProviders(<BrowserPushCard />);

  expect(await screen.findByRole("button", { name: "Enable on this browser" })).toBeVisible();
  expect(currentBrowserPushId()).toBeNull();
  expect(localStorage.getItem("adder.browserPush")).toContain('"pending"');
});

test("a changed server key retires the incompatible native subscription", async () => {
  const { unsubscribe } = browser("granted", Uint8Array.of(5));
  api(true, true);
  localStorage.setItem("adder.browserPush", JSON.stringify({ userId: 7, id: 9, publicKey: "BQ" }));

  renderWithProviders(<BrowserPushCard />);

  expect(await screen.findByRole("button", { name: "Enable on this browser" })).toBeVisible();
  expect(unsubscribe).toHaveBeenCalledOnce();
});

test("a provider subscription without readable key metadata is re-enrolled safely", async () => {
  const device = browser("granted");
  device.registration.pushManager.getSubscription.mockResolvedValue({
    ...device.subscription,
    options: { applicationServerKey: null },
  } as unknown as typeof device.subscription);
  api(true, true);
  localStorage.setItem("adder.browserPush", JSON.stringify({ userId: 7, id: 9 }));

  await expect(getBrowserPushConfig()).resolves.toMatchObject({ enabledId: null });

  expect(device.unsubscribe).toHaveBeenCalledOnce();
});

test("explicit enrollment replaces an orphan subscription with a different server key", async () => {
  const { subscribe, unsubscribe } = browser("granted", Uint8Array.of(5));
  const send = api();

  await expect(enableBrowserPush({ publicKey: "BA", permission: Promise.resolve("granted") })).resolves.toBe(9);

  expect(unsubscribe).toHaveBeenCalledOnce();
  expect(subscribe).toHaveBeenCalledOnce();
  expect(send).toHaveBeenCalledWith(subscriptions, expect.anything());
});

test("an already enrolled binding makes a repeated enable action idempotent", async () => {
  const { subscribe } = browser("granted");
  const send = api();
  localStorage.setItem("adder.browserPush", JSON.stringify({
    active: { userId: 7, id: 9, bindingId: "existing" },
    pending: [],
  }));

  await expect(enableBrowserPush({ publicKey: "BA", permission: Promise.resolve("granted") })).resolves.toBe(9);

  expect(subscribe).not.toHaveBeenCalled();
  expect(send).not.toHaveBeenCalledWith(subscriptions, expect.anything());
});

test("reenrollment clears a failed cleanup for the same server subscription id", async () => {
  browser("granted");
  let cleanupAttempts = 0;
  const send = vi.fn(async (input: string) => {
    if (input === `${subscriptions}/9`) {
      cleanupAttempts += 1;
      throw new Error("offline");
    }
    if (input.startsWith(configPath)) return jsonResponse(200, { available: true, publicKey: "BA", registered: true });
    if (input === subscriptions) return jsonResponse(200, { id: 9 });
    return new Response(null, { status: 204 });
  });
  vi.stubGlobal("fetch", send);
  localStorage.setItem("adder.browserPush", JSON.stringify({
    pending: [{ userId: 7, id: 9, bindingId: "failed-cleanup" }],
  }));
  await getBrowserPushConfig();

  await expect(enableBrowserPush({ publicKey: "BA", permission: Promise.resolve("granted") })).resolves.toBe(9);
  await getBrowserPushConfig();

  expect(cleanupAttempts).toBe(1);
  expect(localStorage.getItem("adder.browserPush")).not.toContain('"pending":[{');
  expect(currentBrowserPushId()).toBe(9);
});

test("pending cleanup never deletes a currently active registration with the same id", async () => {
  browser("granted");
  const send = api(true, true);
  localStorage.setItem("adder.browserPush", JSON.stringify({
    active: { userId: 7, id: 9, bindingId: "reactivated" },
    pending: [{ userId: 7, id: 9, bindingId: "old-cleanup" }],
  }));

  await expect(getBrowserPushConfig()).resolves.toMatchObject({ enabledId: 9 });

  expect(send).not.toHaveBeenCalledWith(`${subscriptions}/9`, expect.anything());
  expect(localStorage.getItem("adder.browserPush")).not.toContain("old-cleanup");
});

test("an enrollment waiting for the device lock stops after the account changes", async () => {
  const device = browser();
  let releaseLock: () => void = () => undefined;
  const locks = {
    request: vi.fn((_name: string, callback: () => Promise<unknown>) => new Promise<unknown>((resolve, reject) => {
      releaseLock = () => { callback().then(resolve, reject); };
    })),
  };
  vi.stubGlobal("navigator", { ...navigator, locks, serviceWorker: device.serviceWorker });
  const pending = enableBrowserPush({ publicKey: "BA", permission: Promise.resolve("granted") });
  await vi.waitFor(() => expect(locks.request).toHaveBeenCalledOnce());

  clearSession();
  releaseLock();

  await expect(pending).resolves.toBeNull();
  expect(device.subscribe).not.toHaveBeenCalled();
});

test("a late old-account registration is detached before a replacement account can enroll", async () => {
  const user = userEvent.setup();
  const oldSubscription = {
    endpoint: `${endpoint}/old`,
    unsubscribe: vi.fn(async () => true),
    options: { applicationServerKey: Uint8Array.of(4).buffer },
  };
  const replacementSubscription = {
    endpoint: `${endpoint}/replacement`,
    unsubscribe: vi.fn(async () => true),
    options: { applicationServerKey: Uint8Array.of(4).buffer },
  };
  const device = browser();
  device.registration.pushManager.getSubscription
    .mockResolvedValueOnce(null)
    .mockResolvedValueOnce(oldSubscription)
    .mockResolvedValueOnce(oldSubscription)
    .mockResolvedValue(replacementSubscription);
  device.subscribe.mockResolvedValueOnce(oldSubscription).mockResolvedValueOnce(replacementSubscription);
  let finishOldPost: (response: Response) => void = (_response) => undefined;
  const oldPost = new Promise<Response>((resolve) => { finishOldPost = resolve; });
  const send = vi.fn((input: string, init?: RequestInit) => {
    if (input.startsWith(configPath)) return Promise.resolve(jsonResponse(200, { available: true, publicKey: "BA" }));
    if (input === subscriptions && JSON.parse(String(init?.body)).endpoint === oldSubscription.endpoint) return oldPost;
    if (input === subscriptions) return Promise.resolve(jsonResponse(200, { id: 10 }));
    return Promise.resolve(new Response(null, { status: 204 }));
  });
  vi.stubGlobal("fetch", send);
  renderWithProviders(<BrowserPushCard />);
  await user.click(await screen.findByRole("button", { name: "Enable on this browser" }));
  await vi.waitFor(() => expect(send).toHaveBeenCalledWith(subscriptions, expect.objectContaining({
    body: JSON.stringify({ endpoint: oldSubscription.endpoint }),
  })));

  clearSession();
  persistSession({
    token: "replacement-token",
    expiresAt: 2_000_000_000_000,
    refreshToken: "replacement-refresh",
    refreshExpiresAt: 2_000_000_000_000,
    userId: 8,
    roles: [],
    disabledFeatures: [],
    language: "en",
  });
  const replacement = enableBrowserPush({ publicKey: "BA", permission: Promise.resolve("granted") });
  finishOldPost(jsonResponse(200, { id: 9 }));

  await expect(replacement).resolves.toBe(10);
  expect(oldSubscription.unsubscribe).toHaveBeenCalledOnce();
  expect(replacementSubscription.unsubscribe).not.toHaveBeenCalled();
  expect(localStorage.getItem("adder.browserPush")).toContain('"userId":8');
});

test("a failed enrollment removes the orphan native subscription and leaves the control retryable", async () => {
  const user = userEvent.setup();
  const { unsubscribe } = browser();
  const send = vi.fn(async (input: string) => {
    if (input.startsWith(configPath)) return jsonResponse(200, { available: true, publicKey: "BA" });
    throw new Error("offline");
  });
  vi.stubGlobal("fetch", send);
  renderWithProviders(<BrowserPushCard />);

  await user.click(await screen.findByRole("button", { name: "Enable on this browser" }));

  expect(await screen.findByText("Could not update browser notifications. Try again.")).toBeVisible();
  expect(unsubscribe).toHaveBeenCalledOnce();
  expect(currentBrowserPushId()).toBeNull();
  await expect(disableBrowserPush(99)).resolves.toBeUndefined();
  expect(send).not.toHaveBeenCalledWith(`${subscriptions}/99`, expect.anything());
});

test("a provider validation failure keeps the registered server id pending", async () => {
  const device = browser();
  device.registration.pushManager.getSubscription
    .mockResolvedValueOnce(null)
    .mockRejectedValueOnce(new Error("provider unavailable"))
    .mockResolvedValue(null);
  api();

  await expect(enableBrowserPush({ publicKey: "BA", permission: Promise.resolve("granted") })).rejects.toThrow("provider unavailable");

  expect(currentBrowserPushId()).toBeNull();
  expect(localStorage.getItem("adder.browserPush")).toContain('"id":9');
});

test("a failed old-account registration stays silent after the account changes", async () => {
  const device = browser();
  let failPost: () => void = () => undefined;
  const post = new Promise<Response>((_resolve, reject) => {
    failPost = () => reject(new Error("offline"));
  });
  const send = vi.fn((input: string) => input === subscriptions
    ? post
    : Promise.resolve(jsonResponse(200, { available: true, publicKey: "BA" })));
  vi.stubGlobal("fetch", send);
  const pending = enableBrowserPush({ publicKey: "BA", permission: Promise.resolve("granted") });
  await vi.waitFor(() => expect(send).toHaveBeenCalledWith(subscriptions, expect.anything()));

  clearSession();
  failPost();

  await expect(pending).resolves.toBeNull();
  expect(device.unsubscribe).toHaveBeenCalledOnce();
});

test("disable preserves a replacement binding that appeared while the server request was pending", async () => {
  const device = browser("granted");
  let finishDelete: (response: Response) => void = (_response) => undefined;
  const deletion = new Promise<Response>((resolve) => { finishDelete = resolve; });
  const send = vi.fn((input: string) => input === `${subscriptions}/9`
    ? deletion
    : Promise.resolve(new Response(null, { status: 204 })));
  vi.stubGlobal("fetch", send);
  localStorage.setItem("adder.browserPush", JSON.stringify({ userId: 7, id: 9 }));
  const pending = disableBrowserPush(9);
  await vi.waitFor(() => expect(send).toHaveBeenCalled());
  localStorage.setItem("adder.browserPush", JSON.stringify({
    active: { userId: 7, id: 10, bindingId: "replacement" },
    pending: [],
  }));

  finishDelete(new Response(null, { status: 204 }));
  await pending;

  expect(device.unsubscribe).not.toHaveBeenCalled();
  expect(currentBrowserPushId()).toBe(10);
});

test("disable succeeds when the provider subscription is already absent", async () => {
  const device = browser("granted");
  device.registration.pushManager.getSubscription.mockResolvedValue(null);
  api();
  localStorage.setItem("adder.browserPush", JSON.stringify({ userId: 7, id: 9 }));

  await disableBrowserPush(9);

  expect(currentBrowserPushId()).toBeNull();
  expect(device.unsubscribe).not.toHaveBeenCalled();
});

test("signing out while permission is open cannot enroll the old account", async () => {
  const user = userEvent.setup();
  const { requestPermission, subscribe } = browser();
  const send = api();
  let release: (permission: "granted") => void = (_permission) => undefined;
  requestPermission.mockImplementationOnce(() => new Promise((resolve) => { release = resolve; }));
  renderWithProviders(<BrowserPushCard />);
  await user.click(await screen.findByRole("button", { name: "Enable on this browser" }));
  clearSession();
  release("granted");
  expect(await screen.findByRole("button", { name: "Enable on this browser" })).toBeVisible();
  expect(subscribe).not.toHaveBeenCalled();
  expect(send).not.toHaveBeenCalledWith(subscriptions, expect.anything());
});
