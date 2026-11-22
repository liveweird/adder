import { matchPath } from "react-router-dom";
import { listSearch, writeFocus, writeThreadListParams } from "./threadListParams";

/** The ONE place the room route family is spelled out — never hand-assemble a room URL. */
export const roomsPath = "/rooms";
export const newRoomPath = `${roomsPath}/new`;
export const roomPath = (id: number) => `${roomsPath}/${id}`;
export const roomMembersPath = (id: number) => `${roomPath(id)}/members`;
export const roomWorkflowPath = (id: number) => `${roomPath(id)}/workflow`;
export const roomSettingsPath = (id: number) => `${roomPath(id)}/settings`;
/** Search: one room's, and the one across the caller's rooms. */
export const roomSearchPath = (id: number) => `${roomPath(id)}/search`;
export const searchPath = "/search";

/**
 * Whether `pathname` is the Threads tab of a room: the list at `/rooms/:id`, a thread's pane or the new-thread form beside it. Only there do the
 * URL's `scope`, `order` and `page` mean the thread list's; on the Search tab (and Members, Workflow, Settings) the same keys are another page's.
 */
export function isThreadListRoute(pathname: string): boolean {
  const match = matchPath({ path: `${roomsPath}/:id`, end: true }, pathname) ?? matchPath(`${roomsPath}/:id/threads/*`, pathname);
  return match !== null && parseRoomId(match.params.id) !== null;
}

/** `search` when `pathname` is the Threads tab of a room (its list keys are the thread list's), else the empty query. */
export function listRouteSearch(pathname: string, search: string): string {
  return isThreadListRoute(pathname) ? search : "";
}

/** The numeric id of a `:id` route parameter, or null when it is not a plain positive int32 (`12abc`, `1e3`, `0`, `-1`): such a room cannot exist. */
export function parseRoomId(param: string | undefined): number | null {
  if (param === undefined || !/^\d+$/.test(param)) return null;
  const id = Number(param);
  return id >= 1 && id <= 2_147_483_647 ? id : null;
}

/** The threads of a room live under its home route: the list at `/rooms/:id`, the new-thread form and one thread's pane beside it. */
export const newThreadPath = (roomId: number) => `${roomPath(roomId)}/threads/new`;
export const threadPath = (roomId: number, number: number) => `${roomPath(roomId)}/threads/${number}`;

/**
 * `path` carrying the thread list's keys (scope, order, page; see `threadListParams.ts`) of `search`, the current URL's query: the list
 * state lives in the URL, so every in-room link (a row, "Back to threads", "New thread", the way out of a started thread) takes it along.
 * Other keys are not carried, and junk and defaults are dropped, so a bare list gives a bare link.
 */
export function withListParams(path: string, search: string | URLSearchParams): string {
  const query = listSearch(new URLSearchParams(search)).toString();
  return query === "" ? path : `${path}?${query}`;
}

/**
 * A hashtag's link: the thread that is open, with the list filtered to the tag as TYPED (the server folds case and accents) across open and
 * closed threads. The list's other filters are not carried (a status filter would hide threads that have the tag), and the value is
 * URL-encoded by the query writer.
 */
export function tagFilterPath(roomId: number, number: number, tag: string): string {
  return `${threadPath(roomId, number)}?${writeThreadListParams(new URLSearchParams(), { scope: "all", tag: [tag] })}`;
}

/**
 * A found message's link: the thread that holds it, with `message=<id>` naming the message to jump to (the thread view reads it). `search`, the
 * current URL's query, passes the thread list's keys through [listSearch] when the link should keep the reader's list state. [listSearch] keeps
 * `scope` and `page` too, and the search tab has its own (`/rooms/:id/search?scope=closed&page=3`): a caller that is not on the thread list's
 * route passes `listRouteSearch(pathname, search)`, never the raw query.
 */
export function threadMessagePath(roomId: number, number: number, messageId: number, search?: string | URLSearchParams): string {
  const query = new URLSearchParams({ message: String(messageId) });
  if (search !== undefined) for (const [key, value] of listSearch(new URLSearchParams(search))) query.append(key, value);
  return `${threadPath(roomId, number)}?${query}`;
}

/**
 * The link of a jump made from INSIDE the open thread (the pin strip's "Show in thread", a quote's block): [threadMessagePath] with the list's
 * keys of `search` kept (the caller passes `listRouteSearch(pathname, search)`), and `focus=1` as well when the reader is in the focus view
 * (`stayFocused`: the jump must not leave it). The drawer's link goes to the plain thread: `focus` is not a list key.
 */
export function messageJumpPath(roomId: number, number: number, messageId: number, search: string, stayFocused: boolean): string {
  const [path, query = ""] = threadMessagePath(roomId, number, messageId, search).split("?");
  return `${path}?${stayFocused ? writeFocus(new URLSearchParams(query), true) : query}`;
}

/** The per-room number of a `:number` route parameter, or null when it is not a plain positive int32 (`new`, `0`, `1e3`): no such thread exists. */
export function parseThreadNumber(param: string | undefined): number | null {
  return parseRoomId(param);
}

/**
 * The router state a navigation to a thread's pane carries (a list row, a started thread) so the pane takes focus: it is where a keyboard or
 * screen-reader user wants to be, and the clicked row stays behind in the list. A deep link, a reload and "Back" carry none and leave focus alone.
 */
export const focusThreadPaneState = { focusPane: true } as const;

export function wantsThreadPaneFocus(state: unknown): boolean {
  return typeof state === "object" && state !== null && "focusPane" in state && state.focusPane === true;
}
