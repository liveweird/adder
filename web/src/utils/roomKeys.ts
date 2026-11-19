// The TanStack Query key PREFIXES of a room's data, spelled out ONCE. The readers (`useQuery`), the refresh-after-write calls and the live
// channel's `keysForFrame` all build their keys here, so renaming a key part cannot silently desync a live hint from the page it should refresh.
// Every key is a prefix: a reader appends its own parameters (a page number, a sort), a hint names the prefix.

export const roomKeys = {
  /** Everything about rooms: the blunt refresh after a refusal, and a live `resync`. */
  all: ["rooms"] as const,
  /** The sidebar rail (`useRailRooms` appends `"active"` or `"archived"`). */
  rail: ["rooms", "rail"] as const,
  /** The Rooms page's list. */
  list: ["rooms", "list"] as const,
  /** One room's record (`null`: a route that names no room, which is never fetched). */
  detail: (room: number | null) => ["rooms", "detail", room] as const,
  /** The members table and the member picker (`"picker"` appended). */
  members: (room: number) => ["rooms", "members", room] as const,
  /** The shared workflow read (status pills, the thread form's fields) and the Workflow tab's own editor read. */
  workflow: (room: number) => ["rooms", "workflow", room] as const,
  workflowEdit: (room: number) => ["rooms", "workflow-edit", room] as const,
  /** Everything about a room's threads: lists, details, histories, conversations. */
  threads: (room: number) => ["rooms", "threads", room] as const,
  threadList: (room: number) => ["rooms", "threads", room, "list"] as const,
  /** The room's hashtags (the tag filter's options): under the list prefix, so a live hint that refreshes the list refreshes them. */
  threadTags: (room: number) => [...roomKeys.threadList(room), "tags"] as const,
  /** Every thread's detail / history in the room (a workflow change), or one thread's. */
  threadDetails: (room: number) => ["rooms", "threads", room, "detail"] as const,
  threadHistories: (room: number) => ["rooms", "threads", room, "events"] as const,
  /** (`null` thread: a route that names no thread, which is never fetched.) */
  threadDetail: (room: number, thread: number | null) => ["rooms", "threads", room, "detail", thread] as const,
  threadEvents: (room: number, thread: number) => ["rooms", "threads", room, "events", thread] as const,
  messages: (room: number, thread: number) => ["rooms", "threads", room, "messages", thread] as const,
};
