import { describe, expect, test } from "vitest";
import { newRoomPath, newThreadPath, parseRoomId, parseThreadNumber, roomMembersPath, roomPath, roomSettingsPath, roomWorkflowPath, roomsPath, threadPath, focusThreadPaneState, tagFilterPath, wantsThreadPaneFocus, withListParams } from "./roomLinks";

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
});
