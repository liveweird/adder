import { createContext, useContext, useState } from "react";
import type { ThreadFormValues } from "../utils/threadForm";

/**
 * Unsent drafts, in memory only: the new-thread form's, one per room, and the reply composer's, one per thread. Leaving the form or the thread
 * (a list row, a tab, "Back to threads") must not lose a half-written message.
 */
export type ThreadDraftStore = {
  get: (roomId: number) => ThreadFormValues | null;
  /** `null` clears the room's draft. */
  set: (roomId: number, draft: ThreadFormValues | null) => void;
  /** The thread's unsent reply (`""` when there is none). */
  getReply: (roomId: number, threadNumber: number) => string;
  /** A blank text clears the thread's reply draft. */
  setReply: (roomId: number, threadNumber: number, text: string) => void;
};

export function createThreadDraftStore(): ThreadDraftStore {
  const drafts = new Map<number, ThreadFormValues>();
  const replies = new Map<string, string>();
  const replyKey = (roomId: number, threadNumber: number) => `${roomId}/${threadNumber}`;
  return {
    get: (roomId) => drafts.get(roomId) ?? null,
    set: (roomId, draft) => {
      if (draft === null) drafts.delete(roomId);
      else drafts.set(roomId, draft);
    },
    getReply: (roomId, threadNumber) => replies.get(replyKey(roomId, threadNumber)) ?? "",
    setReply: (roomId, threadNumber, text) => {
      if (text.trim() === "") replies.delete(replyKey(roomId, threadNumber));
      else replies.set(replyKey(roomId, threadNumber), text);
    },
  };
}

/** Provided once above the routes (`ThreadDraftsProvider`, inside the session boundary, so a new sign-in starts with none). */
export const ThreadDraftsContext = createContext<ThreadDraftStore | null>(null);

/** The shell's store, or — with no provider (a form rendered alone) — a private one that lives as long as the component. */
export function useThreadDrafts(): ThreadDraftStore {
  const shared = useContext(ThreadDraftsContext);
  const [own] = useState(createThreadDraftStore);
  return shared ?? own;
}
