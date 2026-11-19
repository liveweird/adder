import type { MessageSearchHit, ThreadSearchHit, ThreadSearchPage } from "../api/search";

/** A search message hit: the snippet is one plain segment unless `snippet` says otherwise. */
export function messageHit(id: number, overrides: Partial<MessageSearchHit> = {}): MessageSearchHit {
  return {
    id,
    authorId: 7,
    authorName: "Ada",
    createdAt: 1_795_000_000_000,
    snippet: [
      { text: "the ", hit: false },
      { text: "outage", hit: true },
      { text: " started", hit: false },
    ],
    ...overrides,
  };
}

/** A search thread hit in room 12 ("Night Shift"), open, with one matching message. */
export function threadHit(number: number, overrides: Partial<ThreadSearchHit> = {}): ThreadSearchHit {
  return {
    roomId: 12,
    roomName: "Night Shift",
    roomArchived: false,
    threadNumber: number,
    title: "Database outage",
    titleSnippet: [
      { text: "Database ", hit: false },
      { text: "outage", hit: true },
    ],
    status: "open",
    closed: false,
    ownerId: 7,
    ownerName: "Ada",
    messageCount: 31,
    lastActivityAt: 1_795_000_000_000,
    matchCount: 1,
    messages: [messageHit(number * 100)],
    ...overrides,
  };
}

export function searchPage(items: ThreadSearchHit[], total = items.length, page = 1): ThreadSearchPage {
  return { items, page, pageSize: 20, total };
}
