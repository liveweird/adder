import type { QueryClient } from "@tanstack/react-query";
import type { LiveServerFrame } from "./liveProtocol";

const MAX_TRACKED_MUTATIONS = 32;
const MUTATION_ECHO_LIFETIME_MS = 30_000;

interface ReplyEcho {
  id: string;
  confirm(messageId: number): void;
  cancel(): void;
}

interface HeldEcho {
  messageId: number;
  replay: () => void;
}

interface PendingMutation {
  kind: "message.posted" | "message.reaction_changed";
  roomId: number;
  threadNumber: number;
  expectedMessageId?: number;
  timer: ReturnType<typeof setTimeout>;
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
  kind: PendingMutation["kind"],
  roomId: number,
  threadNumber: number,
  expectedMessageId?: number,
): { id: string; mutation: PendingMutation; expire: () => void } | undefined {
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

  const mutation: PendingMutation = { kind, roomId, threadNumber, expectedMessageId, timer: setTimeout(expire, MUTATION_ECHO_LIFETIME_MS) };
  mutations.set(id, mutation);
  return { id, mutation, expire };
}

export function beginReplyEcho(queryClient: QueryClient, roomId: number, threadNumber: number): ReplyEcho | undefined {
  const registered = beginEcho(queryClient, "message.posted", roomId, threadNumber);
  if (!registered) return undefined;
  const { id, mutation, expire } = registered;
  return {
    id,
    confirm(messageId) {
      if (sessions.get(queryClient)?.get(id) !== mutation || mutation.confirmedMessageId !== undefined) return;
      mutation.confirmedMessageId = messageId;
      const held = mutation.held;
      delete mutation.held;
      if (held && held.messageId !== messageId) held.replay();
    },
    cancel() {
      if (mutation.confirmedMessageId !== undefined) return;
      clearTimeout(mutation.timer);
      expire();
    },
  };
}

export function beginReactionEcho(queryClient: QueryClient, roomId: number, threadNumber: number, messageId: number) {
  const registered = beginEcho(queryClient, "message.reaction_changed", roomId, threadNumber, messageId);
  if (!registered) return undefined;
  const { id, mutation, expire } = registered;
  return {
    id,
    confirm() {
      if (sessions.get(queryClient)?.get(id) !== mutation || mutation.confirmedMessageId !== undefined) return;
      mutation.confirmedMessageId = messageId;
      delete mutation.held;
    },
    cancel() {
      if (mutation.confirmedMessageId !== undefined) return;
      clearTimeout(mutation.timer);
      expire();
    },
  };
}

/** Only hold exact correlated message echoes from one request in this session. Process every other frame as usual. */
export function deferReplyEcho(queryClient: QueryClient, frame: LiveServerFrame, replay: () => void): boolean {
  if (
    frame.type !== "event" ||
    (frame.kind !== "message.posted" && frame.kind !== "message.reaction_changed") ||
    frame.mutationId === undefined ||
    frame.messageId === undefined
  ) return false;
  const mutation = sessions.get(queryClient)?.get(frame.mutationId);
  if (
    !mutation || mutation.kind !== frame.kind || mutation.roomId !== frame.roomId || mutation.threadNumber !== frame.threadNumber ||
    (mutation.expectedMessageId !== undefined && mutation.expectedMessageId !== frame.messageId)
  ) return false;
  if (mutation.confirmedMessageId !== undefined) return mutation.confirmedMessageId === frame.messageId;
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
