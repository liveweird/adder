import { useQuery, useQueryClient, type QueryClient, type QueryKey } from "@tanstack/react-query";
import { listThreadReferences } from "../api/threadReferences";
import { threadReferenceKeys } from "./roomKeys";

const MAX_BATCH = 100;
const MAX_IN_FLIGHT = 2;

const threadReferencePrefix = threadReferenceKeys.room;
export const threadReferenceKey = (roomId: number, number: number) => [...threadReferencePrefix(roomId), number] as const;

type Waiter = {
  signal: AbortSignal;
  resolve: (value: boolean) => void;
  reject: (error: unknown) => void;
  settled: boolean;
};

type Entry = { roomId: number; number: number; waiters: Waiter[]; abortIfIdle?: () => void };

class ThreadReferenceLoader {
  private readonly queued = new Map<number, Map<number, Entry>>();
  private readonly idle: () => void;
  private active = 0;
  private scheduled = false;

  constructor(idle: () => void) {
    this.idle = idle;
  }

  load(roomId: number, number: number, signal: AbortSignal): Promise<boolean> {
    return new Promise<boolean>((resolve, reject) => {
      if (signal.aborted) {
        reject(signal.reason);
        return;
      }
      const room = this.queued.get(roomId) ?? new Map<number, Entry>();
      this.queued.set(roomId, room);
      const entry = room.get(number) ?? { roomId, number, waiters: [] };
      room.set(number, entry);
      const waiter: Waiter = { signal, resolve, reject, settled: false };
      entry.waiters.push(waiter);
      signal.addEventListener("abort", () => this.abortWaiter(entry, waiter), { once: true });
      this.schedule();
    });
  }

  private abortWaiter(entry: Entry, waiter: Waiter): void {
    if (waiter.settled) return;
    waiter.settled = true;
    waiter.reject(waiter.signal.reason);
    const room = this.queued.get(entry.roomId);
    if (room?.get(entry.number) === entry && entry.waiters.every((candidate) => candidate.settled)) {
      room.delete(entry.number);
      if (room.size === 0) this.queued.delete(entry.roomId);
    }
    entry.abortIfIdle?.();
  }

  private schedule(): void {
    if (this.scheduled) return;
    this.scheduled = true;
    queueMicrotask(() => {
      this.scheduled = false;
      this.pump();
    });
  }

  private nextBatch(): Entry[] {
    const first = this.queued.entries().next().value as [number, Map<number, Entry>];
    const [roomId, room] = first;
    const entries = [...room.values()].slice(0, MAX_BATCH);
    entries.forEach((entry) => room.delete(entry.number));
    if (room.size === 0) this.queued.delete(roomId);
    return entries;
  }

  private pump(): void {
    while (this.active < MAX_IN_FLIGHT && this.queued.size > 0) {
      const entries = this.nextBatch();
      this.dispatch(entries);
    }
    if (this.active === 0 && this.queued.size === 0) this.idle();
  }

  private dispatch(entries: Entry[]): void {
    this.active += 1;
    const controller = new AbortController();
    const waiters = entries.flatMap((entry) => entry.waiters);
    const maybeAbort = () => {
      if (waiters.every((waiter) => waiter.settled)) controller.abort();
    };
    entries.forEach((entry) => { entry.abortIfIdle = maybeAbort; });
    void listThreadReferences(entries[0].roomId, entries.map((entry) => entry.number), controller.signal)
      .then((found) => entries.forEach((entry) => entry.waiters.forEach((waiter) => {
        if (!waiter.settled) {
          waiter.settled = true;
          waiter.resolve(found.has(entry.number));
        }
      })))
      .catch((error: unknown) => waiters.forEach((waiter) => {
        if (!waiter.settled) {
          waiter.settled = true;
          waiter.reject(error);
        }
      }))
      .finally(() => {
        this.active -= 1;
        this.pump();
      });
  }
}

const loaders = new WeakMap<QueryClient, ThreadReferenceLoader>();

function loaderFor(queryClient: QueryClient): ThreadReferenceLoader {
  const current = loaders.get(queryClient);
  if (current !== undefined) return current;
  const loader = new ThreadReferenceLoader(() => {
    if (loaders.get(queryClient) === loader) loaders.delete(queryClient);
  });
  loaders.set(queryClient, loader);
  return loader;
}

export function loadThreadReference(queryClient: QueryClient, roomId: number, number: number, signal: AbortSignal): Promise<boolean> {
  return loaderFor(queryClient).load(roomId, number, signal);
}

export function useThreadReference(roomId: number, number: number): boolean {
  const queryClient = useQueryClient();
  const { data } = useQuery({
    queryKey: threadReferenceKey(roomId, number),
    queryFn: ({ signal }) => loadThreadReference(queryClient, roomId, number, signal),
    staleTime: Infinity,
    retry: false,
  });
  return data === true;
}

/** Clear cached verdicts immediately; mounted references become plain while their authorized re-read runs. */
export function resetThreadReferences(queryClient: QueryClient, roomId?: number): Promise<void> {
  const queryKey = roomId === undefined ? threadReferenceKeys.all : threadReferencePrefix(roomId);
  queryClient.getQueryCache().findAll({ queryKey }).forEach((query) => query.reset());
  return Promise.resolve();
}

/** A thread deletion can change canonical and merged-alias verdicts; clear synchronously and re-read mounted references. */
export function refreshThreadReferencesAfterDeletion(queryClient: QueryClient, roomId: number): Promise<void> {
  return queryClient.resetQueries({ queryKey: threadReferencePrefix(roomId) });
}

/** Reconnect doubt only removes prior positive links; an already-running first verdict remains authoritative and is not restarted. */
export function resetPositiveThreadReferences(queryClient: QueryClient, roomId?: number): void {
  const queryKey = roomId === undefined ? threadReferenceKeys.all : threadReferencePrefix(roomId);
  queryClient.getQueryCache().findAll({ queryKey }).filter((query) => query.state.data === true).forEach((query) => query.reset());
}

/** A creation or newly gained membership can only change unresolved verdicts; keep established links and their reads untouched. */
export function unresolvedThreadReferenceKeys(queryClient: QueryClient, roomId: number, number?: number): QueryKey[] {
  const queryKey = number === undefined ? threadReferencePrefix(roomId) : threadReferenceKey(roomId, number);
  return queryClient.getQueryCache().findAll({ queryKey, exact: number !== undefined })
    .filter((query) => query.state.data !== true)
    .map((query) => query.queryKey);
}

/** Settled verdicts that existed before a reconnect gap; in-flight first reads already ask the current server and are left alone. */
export function settledThreadReferenceKeys(queryClient: QueryClient, roomId?: number): QueryKey[] {
  const queryKey = roomId === undefined ? threadReferenceKeys.all : threadReferencePrefix(roomId);
  return queryClient.getQueryCache().findAll({ queryKey })
    .filter((query) => query.state.fetchStatus === "idle")
    .map((query) => query.queryKey);
}

export function allThreadReferenceKeys(queryClient: QueryClient, roomId?: number): QueryKey[] {
  const queryKey = roomId === undefined ? threadReferenceKeys.all : threadReferencePrefix(roomId);
  return queryClient.getQueryCache().findAll({ queryKey }).map((query) => query.queryKey);
}
