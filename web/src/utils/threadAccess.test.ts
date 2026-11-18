import { describe, expect, test } from "vitest";
import { isThreadSteward, statusMoveBlock, threadEditBlock, threadStartBlock } from "./threadAccess";

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

describe("isThreadSteward (D1)", () => {
  const thread = { ownerId: 5 };

  test("the thread's owner while a member, a room OWNER and an ADMIN are stewards", () => {
    expect(isThreadSteward({ room: { role: "MEMBER" }, thread, userId: 5, admin: false })).toBe(true);
    expect(isThreadSteward({ room: { role: "OWNER" }, thread, userId: 6, admin: false })).toBe(true);
    expect(isThreadSteward({ room: { role: null }, thread, userId: 6, admin: true })).toBe(true);
  });

  test("a plain member is not, nor is a signed-out reader, nor an owner who left the room (no role)", () => {
    expect(isThreadSteward({ room: { role: "MEMBER" }, thread, userId: 6, admin: false })).toBe(false);
    expect(isThreadSteward({ room: { role: "MEMBER" }, thread, userId: null, admin: false })).toBe(false);
    expect(isThreadSteward({ room: { role: null }, thread, userId: 5, admin: false })).toBe(false);
  });
});

describe("statusMoveBlock", () => {
  const open = { key: "open", category: "OPEN" } as const;
  const other = { key: "waiting", category: "OPEN" } as const;
  const done = { key: "done", category: "CLOSED" } as const;
  const wontDo = { key: "wontdo", category: "CLOSED" } as const;
  const live = { archived: false };

  test("a move between two OPEN statuses is open to every reader", () => {
    expect(statusMoveBlock(live, { closed: false, status: "open" }, false, other)).toBeNull();
  });

  test("entering a CLOSED status needs a steward; a steward may", () => {
    expect(statusMoveBlock(live, { closed: false, status: "open" }, false, done)).toBe("needsSteward");
    expect(statusMoveBlock(live, { closed: false, status: "open" }, true, done)).toBeNull();
  });

  test("leaving a CLOSED status (reopen, or CLOSED to CLOSED) needs a steward too", () => {
    expect(statusMoveBlock(live, { closed: true, status: "done" }, false, open)).toBe("needsSteward");
    expect(statusMoveBlock(live, { closed: true, status: "done" }, false, wontDo)).toBe("needsSteward");
    expect(statusMoveBlock(live, { closed: true, status: "done" }, true, open)).toBeNull();
  });

  test("the current status is never blocked (picking it is a no-op), even for a member on a closed thread", () => {
    expect(statusMoveBlock(live, { closed: true, status: "done" }, false, done)).toBeNull();
  });

  test("an archived room blocks every other status, stewards included", () => {
    expect(statusMoveBlock({ archived: true }, { closed: false, status: "open" }, true, other)).toBe("archived");
    expect(statusMoveBlock({ archived: true }, { closed: false, status: "open" }, false, done)).toBe("archived");
  });
});

describe("threadEditBlock", () => {
  test("a live room and an open thread are editable", () => {
    expect(threadEditBlock({ archived: false }, { closed: false })).toBeNull();
  });

  test("a closed thread refuses, an archived room refuses, and the archived notice wins", () => {
    expect(threadEditBlock({ archived: false }, { closed: true })).toBe("closed");
    expect(threadEditBlock({ archived: true }, { closed: false })).toBe("archived");
    expect(threadEditBlock({ archived: true }, { closed: true })).toBe("archived");
  });
});
