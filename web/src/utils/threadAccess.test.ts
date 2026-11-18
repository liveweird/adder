import { describe, expect, test } from "vitest";
import { threadStartBlock } from "./threadAccess";

describe("threadStartBlock", () => {
  test("a member of a live room can start a thread", () => {
    expect(threadStartBlock({ role: "MEMBER", archived: false })).toBeNull();
    expect(threadStartBlock({ role: "OWNER", archived: false })).toBeNull();
  });

  test("an ADMIN outside the room, which has no role, must join first", () => {
    expect(threadStartBlock({ role: null, archived: false })).toBe("notMember");
  });

  test("an archived room is read-only for everyone, and that notice wins", () => {
    expect(threadStartBlock({ role: "OWNER", archived: true })).toBe("archived");
    expect(threadStartBlock({ role: null, archived: true })).toBe("archived");
  });
});
