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
    expect(messageActions({ room: live, thread: open, message: own, userId: 7, admin: false })).toEqual({ edit: true, remove: "delete", pin: true, react: true });
    // An OWNER's own message is still theirs to edit: the author path wins over moderation.
    expect(messageActions({ room: { role: "OWNER", archived: false }, thread: open, message: own, userId: 7, admin: false })).toEqual({ edit: true, remove: "delete", pin: true, react: true });
  });

  test("a plain member has nothing to do to someone else's message", () => {
    expect(messageActions({ room: live, thread: open, message: someoneElses, userId: 7, admin: false })).toEqual({ edit: false, remove: null, pin: true, react: true });
  });

  test("a room OWNER or an ADMIN removes, never edits, someone else's message", () => {
    expect(messageActions({ room: { role: "OWNER", archived: false }, thread: open, message: someoneElses, userId: 7, admin: false })).toEqual({ edit: false, remove: "moderate", pin: true, react: true });
    expect(messageActions({ room: { role: null, archived: false }, thread: open, message: someoneElses, userId: 7, admin: true })).toEqual({ edit: false, remove: "moderate", pin: false, react: false });
  });

  test("an author who left the room (and is no ADMIN) has no actions on their own message", () => {
    expect(messageActions({ room: { role: null, archived: false }, thread: open, message: own, userId: 7, admin: false })).toEqual({ edit: false, remove: null, pin: false, react: false });
  });

  test("a tombstone, a closed thread and an archived room offer no edit or delete, whoever asks", () => {
    const none = { edit: false, remove: null };
    expect(messageActions({ room: live, thread: open, message: { authorId: 7, deleted: true }, userId: 7, admin: true })).toEqual({ ...none, pin: false, react: false });
    expect(messageActions({ room: { role: "OWNER", archived: true }, thread: open, message: own, userId: 7, admin: true })).toEqual({ ...none, pin: false, react: false });
    // A closed thread refuses the message's own writes but not a pin: a pin is the room's.
    expect(messageActions({ room: live, thread: { closed: true }, message: own, userId: 7, admin: true })).toEqual({ ...none, pin: true, react: false });
  });

  test("any member pins a live message; an ADMIN outside the room, a tombstone and an archived room cannot", () => {
    const pin = (room: { role: "OWNER" | "MEMBER" | null; archived: boolean }, message: { authorId: number; deleted: boolean }, admin = false) =>
      messageActions({ room, thread: open, message, userId: 7, admin }).pin;
    expect(pin(live, someoneElses)).toBe(true);
    expect(pin({ role: "OWNER", archived: false }, someoneElses)).toBe(true);
    expect(pin({ role: null, archived: false }, someoneElses, true)).toBe(false);
    expect(pin({ role: "MEMBER", archived: true }, someoneElses)).toBe(false);
    expect(pin(live, { authorId: 8, deleted: true })).toBe(false);
  });

  test("any member of a live room reacts to a live message, in an open thread; a closed thread, an archived room, a tombstone and a non-member cannot", () => {
    const react = (room: { role: "OWNER" | "MEMBER" | null; archived: boolean }, message: { authorId: number; deleted: boolean }, thread = open, admin = false) =>
      messageActions({ room, thread, message, userId: 7, admin }).react;
    expect(react(live, someoneElses)).toBe(true);
    expect(react(live, own)).toBe(true);
    expect(react({ role: "OWNER", archived: false }, someoneElses)).toBe(true);
    expect(react(live, someoneElses, { closed: true })).toBe(false);
    expect(react({ role: "MEMBER", archived: true }, someoneElses)).toBe(false);
    expect(react(live, { authorId: 8, deleted: true })).toBe(false);
    expect(react({ role: null, archived: false }, someoneElses, open, true)).toBe(false);
  });
});
