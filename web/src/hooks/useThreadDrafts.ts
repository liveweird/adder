import { createContext, useContext, useState } from "react";
import type { ThreadFormValues } from "../utils/threadForm";

/** Unsent new-thread drafts, one per room, in memory only: leaving the form (a list row, a tab, "Back to threads") must not lose a half-written message. */
export type ThreadDraftStore = {
  get: (roomId: number) => ThreadFormValues | null;
  /** `null` clears the room's draft. */
  set: (roomId: number, draft: ThreadFormValues | null) => void;
};

export function createThreadDraftStore(): ThreadDraftStore {
  const drafts = new Map<number, ThreadFormValues>();
  return {
    get: (roomId) => drafts.get(roomId) ?? null,
    set: (roomId, draft) => {
      if (draft === null) drafts.delete(roomId);
      else drafts.set(roomId, draft);
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
