import { createContext, useContext, useState } from "react";
import type { MessageResponse } from "../api/messages";
import type { ThreadFormValues } from "../utils/threadForm";

/**
 * The message a reply is about to quote (1.3), as it read when it was picked: what the composer's line shows (`ComposerQuote`) and the id the post
 * sends. The composer keeps it honest from what it already knows (1.3 A7, `utils/quoteDraft.ts`): the loaded conversation showing the message
 * edited refreshes the text, showing it deleted, or a post refused with `quote-unavailable`, BLANKS it (`unavailable`: the author stays, the text
 * goes, so a moderator's delete is effective here too).
 */
export type QuoteDraft = Pick<MessageResponse, "id" | "authorId" | "authorName" | "body" | "mentions" | "groupMentions"> & {
  roomId: number;
  threadNumber: number;
  unavailable?: true;
};

/**
 * Unsent drafts, in memory only: the new-thread form's, one per room, and the reply composer's text and quote, one per thread. Leaving the
 * form or the thread (a list row, a tab, "Back to threads") must not lose a half-written message.
 */
export type ThreadDraftStore = {
  get: (roomId: number) => ThreadFormValues | null;
  /** `null` clears the room's draft. */
  set: (roomId: number, draft: ThreadFormValues | null) => void;
  /** The thread's unsent reply (`""` when there is none). */
  getReply: (roomId: number, threadNumber: number) => string;
  /** A blank text clears the thread's reply draft. */
  setReply: (roomId: number, threadNumber: number, text: string) => void;
  /** The message the thread's reply quotes, or `null`. */
  getQuote: (roomId: number, threadNumber: number) => QuoteDraft | null;
  /** `null` clears the thread's quote. */
  setQuote: (roomId: number, threadNumber: number, quote: QuoteDraft | null) => void;
};

export function createThreadDraftStore(): ThreadDraftStore {
  const drafts = new Map<number, ThreadFormValues>();
  const replies = new Map<string, string>();
  const quotes = new Map<string, QuoteDraft>();
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
    getQuote: (roomId, threadNumber) => quotes.get(replyKey(roomId, threadNumber)) ?? null,
    setQuote: (roomId, threadNumber, quote) => {
      if (quote === null) quotes.delete(replyKey(roomId, threadNumber));
      else quotes.set(replyKey(roomId, threadNumber), quote);
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
