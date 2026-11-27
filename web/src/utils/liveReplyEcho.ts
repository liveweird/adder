import type { QueryClient } from "@tanstack/react-query";
import type { LiveServerFrame } from "./liveProtocol";

const MAX_TRACKED_MUTATIONS = 32;
const MUTATION_ECHO_LIFETIME_MS = 30_000;

export interface MutationEcho {
  id: string;
  confirm(messageId?: number): void;
  cancel(): void;
}

interface HeldEcho {
  messageId?: number;
  replay: () => void;
}

type MutationTarget =
  | {
      type: "message";
      kind: "message.posted" | "message.edited" | "message.reaction_changed";
      roomId: number;
      threadNumber: number;
      expectedMessageId?: number;
    }
  | {
      type: "thread";
      kind: "thread.updated" | "thread.status_changed" | "thread.owner_changed" | "thread.merged";
      roomId: number;
      threadNumber: number;
    };

interface PendingMutation {
  target: MutationTarget;
  timer: ReturnType<typeof setTimeout>;
  confirmed: boolean;
  confirmedMessageId?: number;
  held?: HeldEcho;
}

const sessions = new WeakMap<QueryClient, Map<string, PendingMutation>>();

/** A cross-origin reply must also work with an older server's CORS header allowlist. */
export function canCorrelateReply(apiBase: string, pageUrl: string): boolean {
  try {
    return new URL(apiBase, pageUrl).origin === new URL(pageUrl).origin;
  } catch {
    return false;
  }
}

function beginEcho(
  queryClient: QueryClient,
  target: MutationTarget,
): MutationEcho | undefined {
  const id = globalThis.crypto?.randomUUID?.();
  if (id === undefined) return undefined;
  const mutations = sessions.get(queryClient) ?? new Map<string, PendingMutation>();
  if (mutations.size >= MAX_TRACKED_MUTATIONS || mutations.has(id)) return undefined;
  sessions.set(queryClient, mutations);

  const expire = () => {
    if (mutations.get(id) !== mutation) return;
    mutations.delete(id);
    mutation.held?.replay();
  };

  const mutation: PendingMutation = { target, timer: setTimeout(expire, MUTATION_ECHO_LIFETIME_MS), confirmed: false };
  mutations.set(id, mutation);
  return {
    id,
    confirm(messageId) {
      if (sessions.get(queryClient)?.get(id) !== mutation || mutation.confirmed) return;
      mutation.confirmed = true;
      mutation.confirmedMessageId = messageId;
      const held = mutation.held;
      delete mutation.held;
      if (held && target.type === "message" && target.expectedMessageId === undefined && held.messageId !== messageId) held.replay();
    },
    cancel() {
      if (mutation.confirmed) return;
      clearTimeout(mutation.timer);
      expire();
    },
  };
}

export function beginReplyEcho(queryClient: QueryClient, roomId: number, threadNumber: number): MutationEcho | undefined {
  return beginEcho(queryClient, { type: "message", kind: "message.posted", roomId, threadNumber });
}

export function beginReactionEcho(queryClient: QueryClient, roomId: number, threadNumber: number, messageId: number) {
  const echo = beginEcho(queryClient, {
    type: "message", kind: "message.reaction_changed", roomId, threadNumber, expectedMessageId: messageId,
  });
  if (!echo) return undefined;
  return { id: echo.id, confirm: () => echo.confirm(messageId), cancel: echo.cancel };
}

export function beginMessageEditEcho(queryClient: QueryClient, roomId: number, threadNumber: number, messageId: number) {
  const echo = beginEcho(queryClient, {
    type: "message", kind: "message.edited", roomId, threadNumber, expectedMessageId: messageId,
  });
  if (!echo) return undefined;
  return { id: echo.id, confirm: () => echo.confirm(messageId), cancel: echo.cancel };
}

export function beginOwnerEcho(queryClient: QueryClient, roomId: number, threadNumber: number) {
  const echo = beginEcho(queryClient, { type: "thread", kind: "thread.owner_changed", roomId, threadNumber });
  if (!echo) return undefined;
  return { id: echo.id, confirm: () => echo.confirm(), cancel: echo.cancel };
}

export function beginStatusEcho(queryClient: QueryClient, roomId: number, threadNumber: number) {
  const echo = beginEcho(queryClient, { type: "thread", kind: "thread.status_changed", roomId, threadNumber });
  if (!echo) return undefined;
  return { id: echo.id, confirm: () => echo.confirm(), cancel: echo.cancel };
}

export function beginThreadUpdateEcho(queryClient: QueryClient, roomId: number, threadNumber: number) {
  const echo = beginEcho(queryClient, { type: "thread", kind: "thread.updated", roomId, threadNumber });
  if (!echo) return undefined;
  return { id: echo.id, confirm: () => echo.confirm(), cancel: echo.cancel };
}

export function beginMergeEcho(queryClient: QueryClient, roomId: number, sourceThreadNumber: number) {
  const echo = beginEcho(queryClient, { type: "thread", kind: "thread.merged", roomId, threadNumber: sourceThreadNumber });
  if (!echo) return undefined;
  return { id: echo.id, confirm: () => echo.confirm(), cancel: echo.cancel };
}

function matches(mutation: PendingMutation, frame: Extract<LiveServerFrame, { type: "event" }>): boolean {
  const target = mutation.target;
  if (target.kind !== frame.kind || target.roomId !== frame.roomId || target.threadNumber !== frame.threadNumber) return false;
  if (target.type === "thread") return frame.messageId === undefined;
  if (frame.messageId === undefined) return false;
  const expected = target.expectedMessageId ?? mutation.confirmedMessageId;
  return expected === undefined || expected === frame.messageId;
}

/** Only hold exact correlated echoes from one request in this session. Process every other frame as usual. */
export function deferReplyEcho(queryClient: QueryClient, frame: LiveServerFrame, replay: () => void): boolean {
  if (
    frame.type !== "event" ||
    !["message.posted", "message.edited", "message.reaction_changed", "thread.updated", "thread.status_changed", "thread.owner_changed", "thread.merged"].includes(frame.kind) ||
    frame.mutationId === undefined
  ) return false;
  const mutation = sessions.get(queryClient)?.get(frame.mutationId);
  if (!mutation || !matches(mutation, frame)) return false;
  if (mutation.confirmed) return true;
  if (mutation.held) return mutation.held.messageId === frame.messageId;
  mutation.held = { messageId: frame.messageId, replay };
  return true;
}

/** Discard echoes from an earlier logical session and invalidate its operation handles. */
export function clearReplyEchoes(queryClient: QueryClient): void {
  const mutations = sessions.get(queryClient);
  mutations?.forEach((mutation) => clearTimeout(mutation.timer));
  mutations?.clear();
  sessions.delete(queryClient);
}
