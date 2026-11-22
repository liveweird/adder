import { describe, expect, test } from "vitest";
import { isThreadListRoute, listRouteSearch, newRoomPath, newThreadPath, parseRoomId, parseThreadNumber, roomMembersPath, roomPath, roomSearchPath, roomSettingsPath, roomWorkflowPath, roomsPath, searchPath, messageJumpPath, threadMessagePath, threadPath, focusThreadPaneState, tagFilterPath, wantsThreadPaneFocus, withListParams } from "./roomLinks";

describe("roomLinks", () => {
  test("builds the list, create, room, members, workflow and settings paths", () => {
    expect(roomsPath).toBe("/rooms");
    expect(newRoomPath).toBe("/rooms/new");
    expect(roomPath(7)).toBe("/rooms/7");
    expect(roomMembersPath(7)).toBe("/rooms/7/members");
    expect(roomWorkflowPath(7)).toBe("/rooms/7/workflow");
    expect(roomSettingsPath(7)).toBe("/rooms/7/settings");
  });

  test("parseRoomId accepts a plain positive int32 and nothing else", () => {
    expect(parseRoomId("12")).toBe(12);
    expect(parseRoomId("2147483647")).toBe(2147483647);
    for (const bad of [undefined, "", "0", "-1", "1e3", "12abc", " 5", "1.5", "2147483648"]) expect(parseRoomId(bad)).toBeNull();
  });

  test("the thread paths hang under the room's home route, and a thread number parses like a room id", () => {
    expect(newThreadPath(7)).toBe("/rooms/7/threads/new");
    expect(threadPath(7, 42)).toBe("/rooms/7/threads/42");
    expect(parseThreadNumber("42")).toBe(42);
    for (const bad of [undefined, "new", "0", "-1", "4.2"]) expect(parseThreadNumber(bad)).toBeNull();
  });

  test("tagFilterPath keeps the thread, filters the list to the tag as typed across open and closed threads, and encodes it", () => {
    expect(tagFilterPath(7, 42, "Outage")).toBe("/rooms/7/threads/42?scope=all&tag=Outage");
    expect(tagFilterPath(7, 42, "Wdro\u017cenie")).toBe("/rooms/7/threads/42?scope=all&tag=Wdro%C5%BCenie");
    expect(tagFilterPath(7, 42, "a&b=c-d_1")).toBe("/rooms/7/threads/42?scope=all&tag=a%26b%3Dc-d_1");
    expect(new URLSearchParams(tagFilterPath(7, 42, "a&b=c").split("?")[1]).getAll("tag")).toEqual(["a&b=c"]);
  });

  test("the pane-focus state is recognised, and nothing else is", () => {
    expect(wantsThreadPaneFocus(focusThreadPaneState)).toBe(true);
    for (const other of [null, undefined, "focusPane", {}, { focusPane: false }, { focusPane: "yes" }]) expect(wantsThreadPaneFocus(other)).toBe(false);
  });

  test("withListParams carries the list's keys (normalized, page included) and nothing else", () => {
    expect(withListParams("/rooms/7/threads/42", "?scope=closed&order=number&page=3")).toBe("/rooms/7/threads/42?scope=closed&order=number&page=3");
    expect(withListParams("/rooms/7", new URLSearchParams("page=2&message=9&focus=1"))).toBe("/rooms/7?page=2");
    expect(withListParams("/rooms/7", "?scope=junk&order=activity&page=1")).toBe("/rooms/7");
    expect(withListParams("/rooms/7", "")).toBe("/rooms/7");
  });

  test("the search routes: one room's, and the one across rooms", () => {
    expect(roomSearchPath(7)).toBe("/rooms/7/search");
    expect(searchPath).toBe("/search");
  });

  test("threadMessagePath names the thread and the message to jump to", () => {
    expect(threadMessagePath(7, 42, 991)).toBe("/rooms/7/threads/42?message=991");
  });

  test("only the Threads tab's URL keys are the thread list's: the list, a pane and the new-thread form, not Search, Members, Workflow or Settings", () => {
    for (const path of ["/rooms/12", "/rooms/12/", "/rooms/12/threads/5", "/rooms/12/threads/new"]) expect(isThreadListRoute(path)).toBe(true);
    for (const path of ["/rooms/12/search", "/rooms/12/members", "/rooms/12/workflow", "/rooms/12/settings", "/search", "/rooms", "/rooms/new"]) expect(isThreadListRoute(path)).toBe(false);
    expect(listRouteSearch("/rooms/12/threads/5", "?scope=closed&page=3")).toBe("?scope=closed&page=3");
    expect(listRouteSearch("/rooms/12/search", "?q=disk&scope=closed&page=3")).toBe("");
  });

  test("threadMessagePath carries the list's keys only when asked, normalized, and never a search page's own `q`", () => {
    expect(threadMessagePath(7, 42, 991, "?scope=closed&order=number&page=3&q=outage&message=5")).toBe("/rooms/7/threads/42?message=991&scope=closed&order=number&page=3");
    expect(threadMessagePath(7, 42, 991, new URLSearchParams("tag=a&tag=b&scope=junk"))).toBe("/rooms/7/threads/42?message=991&tag=a&tag=b");
    expect(threadMessagePath(7, 42, 991, "")).toBe("/rooms/7/threads/42?message=991");
  });

  test("messageJumpPath keeps the list's keys, and the focus view only when asked", () => {
    expect(messageJumpPath(7, 42, 991, "?scope=closed&page=3&q=x", false)).toBe("/rooms/7/threads/42?message=991&scope=closed&page=3");
    expect(messageJumpPath(7, 42, 991, "?scope=closed", true)).toBe("/rooms/7/threads/42?message=991&scope=closed&focus=1");
    expect(messageJumpPath(7, 42, 991, "", true)).toBe("/rooms/7/threads/42?message=991&focus=1");
    expect(messageJumpPath(7, 42, 991, "", false)).toBe("/rooms/7/threads/42?message=991");
  });
});
