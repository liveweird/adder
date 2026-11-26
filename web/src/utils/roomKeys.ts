// The TanStack Query key PREFIXES of a room's data, spelled out ONCE. The readers (`useQuery`), the refresh-after-write calls and the live
// channel's `keysForFrame` all build their keys here, so renaming a key part cannot silently desync a live hint from the page it should refresh.
// Every key is a prefix: a reader appends its own parameters (a page number, a sort), a hint names the prefix.

/**
 * The search results: `["search", q, scope, page, watching, threadTag]` for the search across the caller's rooms, `["search", "room", R, q, scope, page, watching, threadTag]` for one room's.
 * Both sit OUTSIDE `["rooms"]`, because search results are not live (every re-read spends a token of the reader's `search` bucket and one of the
 * server's few search slots): only the frames that take the room away name them (`revoked`, and `room.deleted` for the room's own), and the session
 * boundary clears them. A hint that names a room's thread tree, or `["rooms"]`, never matches them.
 */
export const searchAllKey = ["search"] as const;

/**
 * The caller's notices (`["notifications", ...]`), OUTSIDE `["rooms"]` like the search: a notice is personal, not a page of a room. The live
 * `notification.created` event is the only frame that names it; the bell that reads it reads under this prefix.
 */
export const notificationsKey = ["notifications"] as const;

/** The caller's personal Home summary, outside the room tree because it combines rooms, threads and quoted replies. */
export const homeKey = ["home"] as const;

/** The caller's saved groups and room positions, shared by the organizer and the navigation. */
export const roomOrganizationKey = ["room-organization"] as const;

export const roomKeys = {
  /** Everything about rooms: the blunt refresh after a refusal, and a live `resync`. */
  all: ["rooms"] as const,
  /** The sidebar rail (`useRailRooms` appends `"active"` or `"archived"`). */
  rail: ["rooms", "rail"] as const,
  /** The Rooms page's list. */
  list: ["rooms", "list"] as const,
  /** The rooms one person is in, as the viewer may read them (the profile page): under the list prefix, so whatever refreshes the Rooms page refreshes it. */
  memberRooms: (userId: number) => [...roomKeys.list, "member", userId] as const,
  /** One room's record (`null`: a route that names no room, which is never fetched). */
  detail: (room: number | null) => ["rooms", "detail", room] as const,
  /** The discovery-only preview for a room the caller has not joined. */
  access: (room: number | null) => ["rooms", "access", room] as const,
  /** Pending access requests, read by room owners. */
  joinRequests: (room: number) => ["rooms", "join-requests", room] as const,
  /** The members table and the member picker (`"picker"` appended). */
  members: (room: number) => ["rooms", "members", room] as const,
  /** The shared workflow read (status pills, the thread form's fields) and the Workflow tab's own editor read. */
  workflow: (room: number) => ["rooms", "workflow", room] as const,
  workflowEdit: (room: number) => ["rooms", "workflow-edit", room] as const,
  /** The room's pinned threads and messages (one page of 50 is the whole set): not under the thread tree, so a thread's own refresh leaves it alone. */
  pins: (room: number) => ["rooms", "pins", room] as const,
  /** Everything about a room's threads: lists, details, histories, conversations. */
  threads: (room: number) => ["rooms", "threads", room] as const,
  threadList: (room: number) => ["rooms", "threads", room, "list"] as const,
  /** The room's hashtags (the tag filter's options): under the list prefix, so a live hint that refreshes the list refreshes them. */
  roomHashtags: (room: number) => [...roomKeys.threadList(room), "hashtags"] as const,
  /** Every thread's detail / history in the room (a workflow change), or one thread's. */
  threadDetails: (room: number) => ["rooms", "threads", room, "detail"] as const,
  threadHistories: (room: number) => ["rooms", "threads", room, "events"] as const,
  /** (`null` thread: a route that names no thread, which is never fetched.) */
  threadDetail: (room: number, thread: number | null) => ["rooms", "threads", room, "detail", thread] as const,
  /** The member-visible watcher roster; a page number is appended by its dialog. */
  threadWatchers: (room: number, thread: number) => ["rooms", "threads", room, "watchers", thread] as const,
  threadEvents: (room: number, thread: number) => ["rooms", "threads", room, "events", thread] as const,
  messages: (room: number, thread: number) => ["rooms", "threads", room, "messages", thread] as const,
  /** Sensitive, on-demand prior message bodies. A page number is appended by the dialog. */
  messageRevisionRoom: (room: number) => ["rooms", "threads", room, "message-revisions"] as const,
  messageRevisions: (room: number, thread: number, messageId?: number) => messageId === undefined
    ? [...roomKeys.messageRevisionRoom(room), thread] as const
    : [...roomKeys.messageRevisionRoom(room), thread, messageId] as const,
  messageContext: (room: number, thread: number, messageId: number | null) => [...roomKeys.messages(room, thread), "context", messageId] as const,
  /** A source-addressed message used only to revalidate a quote draft before its destination composer exposes the excerpt. */
  quoteSource: (room: number, messageId: number) => [...roomKeys.threads(room), "quote-source", messageId] as const,
  /** One room's search results (see `searchAllKey`): not under the room's thread tree, so the hints that re-read the tree leave an open search alone. */
  search: (room: number) => [...searchAllKey, "room", room] as const,
};
