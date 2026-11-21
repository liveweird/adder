// "The reader is looking at this thread": the one conversation that is open, visible and scrolled to its end (`useThreadReadMarker`
// registers it while all three hold). The live channel asks it before a `notification.created` hint raises the bell: a notice about the
// thread the reader is reading is acknowledged by the pane through the read marker, so the badge must not go up and straight down again.
// A module-level singleton, like the live invalidators' registry: there is one tab, one open conversation.

/**
 * A thread being read. [covers] says whether the pane will acknowledge a notice with this message id itself: a thread-level notice (no
 * message) and a notice about a message NEWER than the newest one the reader has loaded (the reply that is arriving) are covered; a notice
 * about an older message (an edit that mentions the reader there) is not, because the reader does not see it come in and the bell must say
 * so. [acknowledge] takes over a covered notice: a thread-level one (no message id) sends the read marker NOW, even when the newest id was
 * already sent; a message-level one (the reply that is arriving) is recorded as a TARGET the next marker must reach, and the bell re-reads
 * if the reader stops looking, or the marker fails, before it does.
 */
interface Looking {
  roomId: number;
  threadNumber: number;
  covers: (messageId: number | undefined) => boolean;
  acknowledge: (messageId: number | undefined) => void;
}

let current: Looking | null = null;

/** Registers [looking]; the returned function unregisters it (and only it: a later registration is not undone by an earlier cleanup). */
export function startLooking(looking: Looking): () => void {
  current = looking;
  return () => {
    if (current === looking) current = null;
  };
}

/** The registration for this thread, or null when the reader is not looking at it (another thread, scrolled up, tab hidden, no thread open). */
export function lookingAt(roomId: number, threadNumber: number | undefined): Looking | null {
  return current !== null && current.roomId === roomId && current.threadNumber === threadNumber ? current : null;
}
