import { partialMatchKey, type QueryClient, type QueryKey } from "@tanstack/react-query";
import { clearMessageRevisions } from "./messageRevisionCache";
import { quoteDependencyKeys } from "./quoteCacheDependencies";
import { homeKey, notificationsKey, roomKeys } from "./roomKeys";
import type { LiveServerFrame } from "./liveProtocol";
import { activeQueriesAreFresh } from "./queryRefresh";
import { createDeleteCommitTracker, type DeleteCommit } from "./deleteCommitTracker";

type DeleteTarget = { roomId: number; threadNumber: number; messageId: number };
export type MessageDeleteCommit = DeleteCommit;
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

function refreshMessageDelete(
  queryClient: QueryClient,
  target: DeleteTarget,
  frame: LiveServerFrame,
  sessionIsValid: () => boolean,
): Promise<boolean> {
  const dependencies = quoteDependencyKeys(queryClient, frame);
  const keys = operationKeys(target, [...dependencies, ...pendingMessageOrPinKeys(queryClient)]);

  // Cancel first so a response issued before the delete cannot restore either the body or a quote excerpt.
  const cancellations = keys.map((queryKey) => queryClient.cancelQueries({ queryKey }));
  clearMessageRevisions(queryClient, target.roomId, target.threadNumber, target.messageId);
  dependencies.forEach((key) => {
    queryClient.setQueryData(key, (data) => redactQuote(data, target));
  });
  void queryClient.resetQueries({ queryKey: [...roomKeys.threads(target.roomId), "quote-source"] });

  return Promise.all(cancellations)
    .then(() => sessionIsValid()
      ? Promise.all(keys.map((queryKey) => queryClient.invalidateQueries({ queryKey })))
      : [])
    .then(() => sessionIsValid() && activeQueriesAreFresh(queryClient, ...keys));
}

const tracker = createDeleteCommitTracker<DeleteTarget>({
  idOf,
  targetFromFrame: deleteFrameTarget,
  frameFromTarget: (target) => ({ type: "event", kind: "message.deleted", ...target }),
  refresh: refreshMessageDelete,
});

/** Register before DELETE starts; registration alone never changes cached content. */
export function beginMessageDeleteCommit(queryClient: QueryClient, target: DeleteTarget): MessageDeleteCommit {
  return tracker.begin(queryClient, target);
}

/** Consume only an exact, terminal hint for a delete begun in this QueryClient. */
export function commitTrackedMessageDelete(queryClient: QueryClient, frame: LiveServerFrame, replay: () => void): boolean {
  return tracker.commit(queryClient, frame, replay);
}

/** A logical session boundary must not retain proof from the previous caller. */
export function clearMessageDeleteCommits(queryClient: QueryClient): void {
  tracker.clear(queryClient);
}
