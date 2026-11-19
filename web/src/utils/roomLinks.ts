import { listSearch } from "./threadListParams";

/** The ONE place the room route family is spelled out — never hand-assemble a room URL. */
export const roomsPath = "/rooms";
export const newRoomPath = `${roomsPath}/new`;
export const roomPath = (id: number) => `${roomsPath}/${id}`;
export const roomMembersPath = (id: number) => `${roomPath(id)}/members`;
export const roomWorkflowPath = (id: number) => `${roomPath(id)}/workflow`;
export const roomSettingsPath = (id: number) => `${roomPath(id)}/settings`;

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
