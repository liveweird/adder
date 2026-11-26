import type { QueryClient } from "@tanstack/react-query";
import type { LiveServerFrame } from "./liveProtocol";

const MAX_TRACKED_REPLIES = 32;
const REPLY_ECHO_LIFETIME_MS = 30_000;

interface ReplyEcho {
  id: string;
  confirm(messageId: number): void;
  cancel(): void;
}

interface HeldEcho {
  messageId: number;
  replay: () => void;
}

interface PendingReply {
  roomId: number;
  threadNumber: number;
  timer: ReturnType<typeof setTimeout>;
  confirmedMessageId?: number;
  held?: HeldEcho;
}

const sessions = new WeakMap<QueryClient, Map<string, PendingReply>>();

export function beginReplyEcho(queryClient: QueryClient, roomId: number, threadNumber: number): ReplyEcho | undefined {
  const id = globalThis.crypto?.randomUUID?.();
  if (id === undefined) return undefined;
  const replies = sessions.get(queryClient) ?? new Map<string, PendingReply>();
  if (replies.size >= MAX_TRACKED_REPLIES || replies.has(id)) return undefined;
  sessions.set(queryClient, replies);

  const expire = () => {
    if (replies.get(id) !== reply) return;
    replies.delete(id);
    reply.held?.replay();
  };

  const reply: PendingReply = { roomId, threadNumber, timer: setTimeout(expire, REPLY_ECHO_LIFETIME_MS) };
  replies.set(id, reply);
  return {
    id,
    confirm(messageId) {
      if (replies.get(id) !== reply || reply.confirmedMessageId !== undefined) return;
      reply.confirmedMessageId = messageId;
      const held = reply.held;
      delete reply.held;
      if (held && held.messageId !== messageId) held.replay();
    },
    cancel() {
      if (reply.confirmedMessageId !== undefined) return;
      clearTimeout(reply.timer);
      expire();
    },
  };
}

/** Only hold message-post echoes from one request in this session. Process every other frame as usual. */
export function deferReplyEcho(queryClient: QueryClient, frame: LiveServerFrame, replay: () => void): boolean {
  if (frame.type !== "event" || frame.kind !== "message.posted" || frame.mutationId === undefined || frame.messageId === undefined) return false;
  const reply = sessions.get(queryClient)?.get(frame.mutationId);
  if (!reply || reply.roomId !== frame.roomId || reply.threadNumber !== frame.threadNumber) return false;
  if (reply.confirmedMessageId !== undefined) return reply.confirmedMessageId === frame.messageId;
  if (reply.held) return reply.held.messageId === frame.messageId;
  reply.held = { messageId: frame.messageId, replay };
  return true;
}

/** Discard echoes from an earlier logical session and invalidate its operation handles. */
export function clearReplyEchoes(queryClient: QueryClient): void {
  const replies = sessions.get(queryClient);
  replies?.forEach((reply) => clearTimeout(reply.timer));
  replies?.clear();
  sessions.delete(queryClient);
}
