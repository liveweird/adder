import { partialMatchKey, type QueryClient, type QueryKey } from "@tanstack/react-query";
import { clearMessageRevisions } from "./messageRevisionCache";
import { quoteDependencyKeys } from "./quoteCacheDependencies";
import { homeKey, notificationsKey, roomKeys } from "./roomKeys";
import type { LiveServerFrame } from "./liveProtocol";
import { activeQueriesAreFresh } from "./queryRefresh";

type DeleteTarget = { roomId: number; threadNumber: number; messageId: number };
type Replay = () => void;

interface TrackedDelete extends DeleteTarget {
  refresh: Promise<boolean> | null;
  committed: boolean;
  session: { valid: boolean };
  expires: ReturnType<typeof setTimeout>;
}

interface DeleteRegistry {
  operations: Map<string, TrackedDelete>;
  session: { valid: boolean };
}

export interface MessageDeleteCommit {
  /** HTTP 204 is authoritative even when the matching live hint has not arrived yet. */
  confirm(): Promise<void>;
  /** Whether the failed response is already settled by a live commit, or belongs to an ended logical session. */
  reject(): Promise<boolean>;
}

const tracked = new WeakMap<QueryClient, DeleteRegistry>();
const RETAIN_MS = 30_000;
const MAX_TRACKED = 32;
const idOf = ({ roomId, threadNumber, messageId }: DeleteTarget) => `${roomId}:${threadNumber}:${messageId}`;

function deleteFrameTarget(frame: LiveServerFrame): DeleteTarget | null {
  return frame.type === "event" && frame.kind === "message.deleted" && frame.threadNumber !== undefined && frame.messageId !== undefined
    ? { roomId: frame.roomId, threadNumber: frame.threadNumber, messageId: frame.messageId }
    : null;
}

function redactQuote(value: unknown, target: DeleteTarget): unknown {
  if (typeof value !== "object" || value === null) return value;
  if (Array.isArray(value)) {
    const copy = value.map((entry) => redactQuote(entry, target));
    return copy.some((entry, index) => entry !== value[index]) ? copy : value;
  }
  const source = value as Record<string, unknown>;
  const quote = source.quote;
  if (typeof quote === "object" && quote !== null) {
    const location = quote as Record<string, unknown>;
    if (location.roomId === target.roomId && location.threadNumber === target.threadNumber && location.messageId === target.messageId) {
      return { ...source, quote: { ...location, body: null, deleted: true } };
    }
  }
  const copy: Record<string, unknown> = {};
  let changed = false;
  Object.entries(source).forEach(([key, entry]) => {
    const next = redactQuote(entry, target);
    copy[key] = next;
    changed ||= next !== entry;
  });
  return changed ? copy : value;
}

function operationKeys(target: DeleteTarget, dependents: readonly QueryKey[]): QueryKey[] {
  const primary: QueryKey[] = [
    roomKeys.messages(target.roomId, target.threadNumber),
    roomKeys.threadDetail(target.roomId, target.threadNumber),
    roomKeys.threadList(target.roomId),
    roomKeys.pins(target.roomId),
    notificationsKey,
    homeKey,
  ];
  const ordered = [
    ...primary,
    ...[...dependents].sort((left, right) => left.length - right.length),
  ];
  return ordered.reduce<QueryKey[]>((minimal, key) => {
    if (!minimal.some((prefix) => partialMatchKey(key, prefix))) minimal.push(key);
    return minimal;
  }, []);
}

function pendingMessageOrPinKeys(queryClient: QueryClient): QueryKey[] {
  return queryClient.getQueryCache().getAll()
    .filter(({ queryKey, state }) => state.fetchStatus === "fetching" && queryKey[0] === "rooms" &&
      (queryKey.includes("messages") || queryKey.includes("pins")))
    .map(({ queryKey }) => queryKey);
}

function startRefresh(queryClient: QueryClient, operation: TrackedDelete, frame: LiveServerFrame): Promise<boolean> {
  if (operation.refresh !== null) return operation.refresh;
  if (!operation.session.valid) return Promise.resolve(false);
  operation.committed = true;
  const target: DeleteTarget = operation;
  const dependencies = quoteDependencyKeys(queryClient, frame);
  const keys = operationKeys(target, [...dependencies, ...pendingMessageOrPinKeys(queryClient)]);

  // Cancel first so a response issued before the delete cannot restore either the body or a quote excerpt.
  const cancellations = keys.map((queryKey) => queryClient.cancelQueries({ queryKey }));
  clearMessageRevisions(queryClient, target.roomId, target.threadNumber, target.messageId);
  dependencies.forEach((key) => {
    queryClient.setQueryData(key, (data) => redactQuote(data, target));
  });
  void queryClient.resetQueries({ queryKey: [...roomKeys.threads(target.roomId), "quote-source"] });

  operation.refresh = Promise.all(cancellations)
    .then(() => operation.session.valid
      ? Promise.all(keys.map((queryKey) => queryClient.invalidateQueries({ queryKey })))
      : [])
    .then(() => {
      return operation.session.valid && activeQueriesAreFresh(queryClient, ...keys);
    });
  return operation.refresh;
}

function remove(queryClient: QueryClient, operation: TrackedDelete): void {
  const registry = tracked.get(queryClient);
  if (registry?.operations.get(idOf(operation)) !== operation) return;
  clearTimeout(operation.expires);
  registry.operations.delete(idOf(operation));
}

/** Register before DELETE starts; registration alone never changes cached content. */
export function beginMessageDeleteCommit(queryClient: QueryClient, target: DeleteTarget): MessageDeleteCommit {
  let registry = tracked.get(queryClient);
  if (registry === undefined) {
    registry = { operations: new Map(), session: { valid: true } };
    tracked.set(queryClient, registry);
  }
  const operations = registry.operations;
  const previous = operations.get(idOf(target));
  if (previous !== undefined) remove(queryClient, previous);
  if (operations.size >= MAX_TRACKED) {
    // The size guard makes the map's first value non-null.
    remove(queryClient, operations.values().next().value as TrackedDelete);
  }
  const operation: TrackedDelete = {
    ...target,
    refresh: null,
    committed: false,
    session: registry.session,
    expires: setTimeout(() => remove(queryClient, operation), RETAIN_MS),
  };
  operations.set(idOf(target), operation);
  const syntheticFrame: LiveServerFrame = { type: "event", kind: "message.deleted", ...target };
  return {
    confirm: () => startRefresh(queryClient, operation, syntheticFrame).then(() => undefined),
    async reject() {
      if (!operation.session.valid) return true;
      if (!operation.committed) {
        remove(queryClient, operation);
        return false;
      }
      await operation.refresh;
      return operation.committed;
    },
  };
}

/** Consume only an exact, terminal hint for a delete begun in this QueryClient. */
export function commitTrackedMessageDelete(queryClient: QueryClient, frame: LiveServerFrame, replay: Replay): boolean {
  const target = deleteFrameTarget(frame);
  if (target === null) return false;
  const operation = tracked.get(queryClient)?.operations.get(idOf(target));
  if (operation === undefined) return false;
  void startRefresh(queryClient, operation, frame).then((reliable) => {
    if (!reliable && operation.session.valid) {
      remove(queryClient, operation);
      replay();
    }
  });
  return true;
}

/** A logical session boundary must not retain proof from the previous caller. */
export function clearMessageDeleteCommits(queryClient: QueryClient): void {
  const registry = tracked.get(queryClient);
  registry?.operations.forEach((operation) => {
    clearTimeout(operation.expires);
  });
  if (registry !== undefined) registry.session.valid = false;
  tracked.delete(queryClient);
}
