import { describe, expect, test } from "vitest";
import { messageActions, messageComposeBlock } from "./messageAccess";

describe("messageComposeBlock", () => {
  const open = { closed: false };

  test("a member of a live room posts in an open thread", () => {
    expect(messageComposeBlock({ role: "MEMBER", archived: false }, open)).toBeNull();
    expect(messageComposeBlock({ role: "OWNER", archived: false }, open)).toBeNull();
  });

  test("an archived room wins, then a closed thread, then missing membership (an ADMIN who has not joined)", () => {
    expect(messageComposeBlock({ role: "OWNER", archived: true }, { closed: true })).toBe("archived");
    expect(messageComposeBlock({ role: null, archived: false }, { closed: true })).toBe("closed");
    expect(messageComposeBlock({ role: "MEMBER", archived: false }, { closed: true })).toBe("closed");
    expect(messageComposeBlock({ role: null, archived: false }, open)).toBe("notMember");
  });
});

describe("messageActions", () => {
  const live = { role: "MEMBER", archived: false } as const;
  const open = { closed: false };
  const own = { authorId: 7, deleted: false };
  const someoneElses = { authorId: 8, deleted: false };

  test("the author, while a member, edits and deletes their own message", () => {
    expect(messageActions({ room: live, thread: open, message: own, userId: 7, admin: false })).toEqual({ edit: true, remove: "delete" });
    // An OWNER's own message is still theirs to edit: the author path wins over moderation.
    expect(messageActions({ room: { role: "OWNER", archived: false }, thread: open, message: own, userId: 7, admin: false })).toEqual({ edit: true, remove: "delete" });
  });

  test("a plain member has nothing to do to someone else's message", () => {
    expect(messageActions({ room: live, thread: open, message: someoneElses, userId: 7, admin: false })).toEqual({ edit: false, remove: null });
  });

  test("a room OWNER or an ADMIN removes, never edits, someone else's message", () => {
    expect(messageActions({ room: { role: "OWNER", archived: false }, thread: open, message: someoneElses, userId: 7, admin: false })).toEqual({ edit: false, remove: "moderate" });
    expect(messageActions({ room: { role: null, archived: false }, thread: open, message: someoneElses, userId: 7, admin: true })).toEqual({ edit: false, remove: "moderate" });
  });

  test("an author who left the room (and is no ADMIN) has no actions on their own message", () => {
    expect(messageActions({ room: { role: null, archived: false }, thread: open, message: own, userId: 7, admin: false })).toEqual({ edit: false, remove: null });
  });

  test("a tombstone, a closed thread and an archived room offer nothing, whoever asks", () => {
    const none = { edit: false, remove: null };
    expect(messageActions({ room: live, thread: open, message: { authorId: 7, deleted: true }, userId: 7, admin: true })).toEqual(none);
    expect(messageActions({ room: live, thread: { closed: true }, message: own, userId: 7, admin: true })).toEqual(none);
    expect(messageActions({ room: { role: "OWNER", archived: true }, thread: open, message: own, userId: 7, admin: true })).toEqual(none);
  });
});
