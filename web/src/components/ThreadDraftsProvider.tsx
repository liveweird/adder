import { useState, type ReactNode } from "react";
import { ThreadDraftsContext, createThreadDraftStore } from "../hooks/useThreadDrafts";

/** Holds the unsent new-thread drafts above the routes, so they outlive the room view's pages (the Members tab, a thread's pane). */
export default function ThreadDraftsProvider({ children }: { children: ReactNode }) {
  const [store] = useState(createThreadDraftStore);
  return <ThreadDraftsContext.Provider value={store}>{children}</ThreadDraftsContext.Provider>;
}
