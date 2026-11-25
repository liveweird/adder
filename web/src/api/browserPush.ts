import { jsonRequest, voidRequest } from "./http";
import { getSessionSnapshot, getUserId, isSameSessionIdentity, type SessionSnapshot } from "./session";

const STORAGE_KEY = "adder.browserPush";
const DEVICE_LOCK = "adder.browserPush.device";
const API = "/api/v1/notifications/push";

type BrowserPushBinding = { userId: number; id: number; bindingId: string };
type BrowserPushState = { active?: BrowserPushBinding; pending: BrowserPushBinding[] };

export type BrowserPushConfig = {
  available: boolean;
  publicKey?: string;
  registered?: boolean;
  enabledId: number | null;
};

export function browserPushSupported(): boolean {
  return globalThis.isSecureContext === true && "serviceWorker" in navigator &&
    typeof PushManager !== "undefined" && typeof Notification !== "undefined";
}

function validBinding(value: unknown): value is BrowserPushBinding {
  if (!value || typeof value !== "object") return false;
  const binding = value as Partial<BrowserPushBinding>;
  return Number.isSafeInteger(binding.id) && Number(binding.id) > 0 &&
    Number.isSafeInteger(binding.userId) && Number(binding.userId) > 0 &&
    typeof binding.bindingId === "string" && binding.bindingId.length > 0;
}

function state(): BrowserPushState {
  try {
    const parsed: unknown = JSON.parse(localStorage.getItem(STORAGE_KEY) ?? "null");
    if (!parsed || typeof parsed !== "object") return { pending: [] };
    const value = parsed as Record<string, unknown>;
    // Migrate the first-release shape without discarding a working device registration.
    if (Number.isSafeInteger(value.id) && Number.isSafeInteger(value.userId)) {
      return {
        active: {
          id: Number(value.id),
          userId: Number(value.userId),
          bindingId: `legacy-${String(value.userId)}-${String(value.id)}`,
        },
        pending: [],
      };
    }
    return {
      ...(validBinding(value.active) ? { active: value.active } : {}),
      pending: Array.isArray(value.pending) ? value.pending.filter(validBinding) : [],
    };
  } catch {
    return { pending: [] };
  }
}

function writeState(next: BrowserPushState): void {
  if (!next.active && next.pending.length === 0) localStorage.removeItem(STORAGE_KEY);
  else localStorage.setItem(STORAGE_KEY, JSON.stringify(next));
}

function sameBinding(left: BrowserPushBinding | undefined, right: BrowserPushBinding): boolean {
  return left?.userId === right.userId && left.id === right.id && left.bindingId === right.bindingId;
}

function addPending(binding: BrowserPushBinding): void {
  const current = state();
  if (current.pending.some((candidate) => sameBinding(candidate, binding))) return;
  writeState({ ...current, pending: [...current.pending, binding] });
}

function removePending(binding: BrowserPushBinding): void {
  const current = state();
  writeState({ ...current, pending: current.pending.filter((candidate) => !sameBinding(candidate, binding)) });
}

function isSameSubscription(left: BrowserPushBinding | undefined, right: BrowserPushBinding): boolean {
  return left?.userId === right.userId && left.id === right.id;
}

function currentBinding(): BrowserPushBinding | null {
  const active = state().active;
  return active?.userId === getUserId() ? active : null;
}

export function currentBrowserPushId(): number | null {
  return currentBinding()?.id ?? null;
}

async function withDeviceLock<T>(work: () => Promise<T>): Promise<T> {
  const locks = navigator.locks as LockManager | undefined;
  if (!locks) return work();
  let started = false;
  try {
    return await locks.request(DEVICE_LOCK, () => {
      started = true;
      return work();
    });
  } catch (error) {
    if (started) throw error;
    return work();
  }
}

function publicKeyBytes(key: string): Uint8Array<ArrayBuffer> {
  const base64 = key.replaceAll("-", "+").replaceAll("_", "/");
  const padded = base64.padEnd(Math.ceil(base64.length / 4) * 4, "=");
  return Uint8Array.from(atob(padded), (char) => char.charCodeAt(0));
}

function matchingKey(subscription: PushSubscription, publicKey: string): boolean {
  const actual = subscription.options.applicationServerKey;
  if (!actual) return false;
  const expected = publicKeyBytes(publicKey);
  const bytes = new Uint8Array(actual);
  return bytes.length === expected.length && bytes.every((value, index) => value === expected[index]);
}

async function nativeSubscription(): Promise<PushSubscription | null> {
  const registration = await navigator.serviceWorker.getRegistration();
  return (await registration?.pushManager.getSubscription()) ?? null;
}

async function unsubscribeIfStillCurrent(subscription: PushSubscription, binding?: BrowserPushBinding): Promise<void> {
  const active = state().active;
  if (active && (!binding || !sameBinding(active, binding))) return;
  const current = await nativeSubscription();
  if (current?.endpoint !== subscription.endpoint) return;
  if (state().active && (!binding || !sameBinding(state().active, binding))) return;
  await current.unsubscribe();
}

export function takeBrowserPushForLogout(): BrowserPushBinding | null {
  const binding = currentBinding();
  if (!binding) return null;
  const current = state();
  writeState({ pending: [...current.pending, binding] });
  return binding;
}

export async function detachBrowserPushForLogout(binding: BrowserPushBinding | null): Promise<void> {
  if (!binding || !browserPushSupported()) return;
  await withDeviceLock(async () => {
    const subscription = await nativeSubscription();
    if (subscription) await unsubscribeIfStillCurrent(subscription, binding);
  });
}

/** Retire an active device owned by a session that is no longer current. */
export async function reconcileBrowserPushSession(): Promise<void> {
  const active = state().active;
  if (!active || active.userId === getUserId()) return;
  await withDeviceLock(async () => {
    const current = state();
    const foreign = current.active;
    if (!foreign || foreign.userId === getUserId()) return;
    const pending = current.pending.some((candidate) => sameBinding(candidate, foreign))
      ? current.pending
      : [...current.pending, foreign];
    writeState({ pending });
    if (!browserPushSupported()) return;
    const subscription = await nativeSubscription();
    if (subscription) await unsubscribeIfStillCurrent(subscription, foreign);
  });
}

async function retryPendingCleanup(session: SessionSnapshot, userId: number): Promise<void> {
  await withDeviceLock(async () => {
    for (const pending of state().pending.filter((binding) => binding.userId === userId)) {
      if (!isSameSessionIdentity(session, getSessionSnapshot())) return;
      if (isSameSubscription(state().active, pending)) {
        removePending(pending);
        continue;
      }
      try {
        await voidRequest(`${API}/subscriptions/${pending.id}`, { method: "DELETE" });
        removePending(pending);
      } catch {
        // Keep the opaque id for a later visit while this same account is signed in.
      }
    }
  });
}

async function retireBinding(binding: BrowserPushBinding, session: SessionSnapshot, needsServerCleanup: boolean): Promise<void> {
  await withDeviceLock(async () => {
    if (!isSameSessionIdentity(session, getSessionSnapshot()) || !sameBinding(state().active, binding)) return;
    const subscription = await nativeSubscription();
    const current = state();
    if (!sameBinding(current.active, binding)) return;
    writeState({ pending: needsServerCleanup ? [...current.pending, binding] : current.pending });
    if (subscription) await unsubscribeIfStillCurrent(subscription);
  });
}

export async function getBrowserPushConfig(): Promise<BrowserPushConfig> {
  const session = getSessionSnapshot();
  const userId = getUserId();
  if (userId !== null) await retryPendingCleanup(session, userId);
  const binding = currentBinding();
  const suffix = binding ? `?subscriptionId=${binding.id}` : "";
  const config = await jsonRequest<Omit<BrowserPushConfig, "enabledId">>(`${API}/config${suffix}`);
  if (!config.available || !config.publicKey) {
    if (binding) await retireBinding(binding, session, true);
    return { ...config, enabledId: null };
  }
  await navigator.serviceWorker.register("/adder-push.js", { scope: "/" });
  if (!binding) return { ...config, enabledId: null };
  const subscription = await nativeSubscription();
  const valid = config.registered === true && Notification.permission === "granted" &&
    subscription !== null && matchingKey(subscription, config.publicKey);
  if (!valid) await retireBinding(binding, session, config.registered === true);
  return { ...config, enabledId: valid ? binding.id : null };
}

async function cleanupFailedEnrollment(subscription: PushSubscription, binding: BrowserPushBinding): Promise<void> {
  await unsubscribeIfStillCurrent(subscription, binding);
}

/** Permission is requested directly by the enable button. */
export async function enableBrowserPush({ publicKey, permission }: {
  publicKey: string;
  permission: Promise<NotificationPermission>;
}): Promise<number | null> {
  const session = getSessionSnapshot();
  const userId = getUserId();
  if (userId === null || await permission !== "granted" || !isSameSessionIdentity(session, getSessionSnapshot())) return null;
  return withDeviceLock(async () => {
    if (!isSameSessionIdentity(session, getSessionSnapshot()) || getUserId() !== userId) return null;
    const existingBinding = currentBinding();
    if (existingBinding) return existingBinding.id;
    const registration = await navigator.serviceWorker.ready;
    let subscription = await registration.pushManager.getSubscription();
    if (subscription && !matchingKey(subscription, publicKey)) {
      await subscription.unsubscribe();
      subscription = null;
    }
    if (!subscription) {
      subscription = await registration.pushManager.subscribe({
        userVisibleOnly: true,
        applicationServerKey: publicKeyBytes(publicKey),
      });
    }
    const bindingId = crypto.randomUUID();
    let registeredBinding: BrowserPushBinding | null = null;
    try {
      const { id } = await jsonRequest<{ id: number }>(`${API}/subscriptions`, {
        method: "POST",
        body: JSON.stringify({ endpoint: subscription.endpoint }),
      });
      const binding = { userId, id, bindingId };
      registeredBinding = binding;
      const stillRegistered = await nativeSubscription();
      if (!isSameSessionIdentity(session, getSessionSnapshot()) || getUserId() !== userId) {
        addPending(binding);
        await cleanupFailedEnrollment(subscription, binding);
        return null;
      }
      if (stillRegistered?.endpoint !== subscription.endpoint) {
        addPending(binding);
        return null;
      }
      const current = state();
      writeState({
        active: binding,
        pending: current.pending.filter((candidate) => !isSameSubscription(candidate, binding)),
      });
      return id;
    } catch (error) {
      if (registeredBinding) addPending(registeredBinding);
      await cleanupFailedEnrollment(subscription, { userId, id: -1, bindingId });
      if (!isSameSessionIdentity(session, getSessionSnapshot())) return null;
      throw error;
    }
  });
}

export async function disableBrowserPush(id: number): Promise<void> {
  const session = getSessionSnapshot();
  await withDeviceLock(async () => {
    const binding = currentBinding();
    if (!binding || binding.id !== id || !isSameSessionIdentity(session, getSessionSnapshot())) return;
    await voidRequest(`${API}/subscriptions/${id}`, { method: "DELETE" });
    const subscription = await nativeSubscription();
    const current = state();
    if (!sameBinding(current.active, binding)) return;
    writeState({ pending: current.pending });
    if (subscription) await unsubscribeIfStillCurrent(subscription);
  });
}
