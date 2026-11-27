import { describe, expect, expectTypeOf, test } from "vitest";
import type { components } from "../api/schema";
import { LIVE_EVENT_KINDS, liveUrl, parseServerFrame, type LiveClientFrame, type LiveEventKind, type LiveServerFrame } from "./liveProtocol";

type Schemas = components["schemas"];

describe("the frame types are the generated ones", () => {
  test("the kinds, the client frames and every server frame but the event's kind are pinned to schema.ts", () => {
    expectTypeOf<LiveEventKind>().toEqualTypeOf<Schemas["LiveEventKind"]>();
    expectTypeOf<LiveClientFrame>().toEqualTypeOf<Schemas["LiveClientFrame"]>();
    type Event = Extract<LiveServerFrame, { type: "event" }>;
    expectTypeOf<Exclude<LiveServerFrame, Event>>().toEqualTypeOf<Exclude<Schemas["LiveServerFrame"], Schemas["LiveEventFrame"]>>();
    expectTypeOf<Omit<Event, "kind">>().toEqualTypeOf<Omit<Schemas["LiveEventFrame"], "kind">>();
    // The one widening: a newer server's kind is still a frame.
    expectTypeOf<Event["kind"]>().toEqualTypeOf<string>();
    expect(LIVE_EVENT_KINDS).toHaveLength(21);
    expect(new Set(LIVE_EVENT_KINDS).size).toBe(21);
  });
});

describe("liveUrl", () => {
  test("is ws on http and wss on https, on the page's own host, with no ticket in it", () => {
    expect(liveUrl({ protocol: "http:", host: "localhost:5179" })).toBe("ws://localhost:5179/api/v1/live");
    expect(liveUrl({ protocol: "https:", host: "adder.example.test" })).toBe("wss://adder.example.test/api/v1/live");
  });

  test("follows VITE_API_BASE: an absolute base maps http(s) to ws(s) on that origin, a relative one is a prefix on the page's host", () => {
    const page = { protocol: "https:", host: "app.example.test" };
    expect(liveUrl(page, "")).toBe("wss://app.example.test/api/v1/live");
    expect(liveUrl(page, "https://api.example.test")).toBe("wss://api.example.test/api/v1/live");
    expect(liveUrl(page, "https://api.example.test/")).toBe("wss://api.example.test/api/v1/live");
    expect(liveUrl({ protocol: "http:", host: "localhost:5179" }, "http://localhost:8087")).toBe("ws://localhost:8087/api/v1/live");
    expect(liveUrl(page, "http://api.example.test:8080/adder/")).toBe("ws://api.example.test:8080/adder/api/v1/live");
    expect(liveUrl(page, "/adder")).toBe("wss://app.example.test/adder/api/v1/live");
    expect(liveUrl(page, "adder")).toBe("wss://app.example.test/adder/api/v1/live");
    // Like REST's fetch: a base without a leading slash is relative to the page's own path.
    expect(liveUrl({ ...page, pathname: "/rooms/5" }, "adder")).toBe("wss://app.example.test/rooms/adder/api/v1/live");
    expect(liveUrl({ ...page, pathname: "/rooms/5" }, "/adder")).toBe("wss://app.example.test/adder/api/v1/live");
    expect(liveUrl(page, "  ")).toBe("wss://app.example.test/api/v1/live");
  });

  test("an unusable absolute base falls back to the page's own host", () => {
    expect(liveUrl({ protocol: "http:", host: "localhost:5179" }, "http://")).toBe("ws://localhost:5179/api/v1/live");
  });
});

describe("parseServerFrame", () => {
  const parse = (frame: unknown) => parseServerFrame(JSON.stringify(frame));

  test("reads each frame of the protocol", () => {
    expect(parse({ type: "ready", protocol: 1, maxSubscriptions: 128 })).toEqual({ type: "ready", protocol: 1, maxSubscriptions: 128 });
    expect(parse({ type: "ready", protocol: 1, maxSubscriptions: 128, presenceSupported: true })).toEqual({
      type: "ready", protocol: 1, maxSubscriptions: 128, presenceSupported: true,
    });
    expect(parse({ type: "subscribed", roomIds: [12], refused: [40], capped: [] })).toEqual({ type: "subscribed", roomIds: [12], refused: [40], capped: [] });
    expect(parse({ type: "event", kind: "message.posted", roomId: 12, threadNumber: 7, messageId: 991 })).toEqual({
      type: "event",
      kind: "message.posted",
      roomId: 12,
      threadNumber: 7,
      messageId: 991,
    });
    expect(parse({ type: "revoked", roomId: 12 })).toEqual({ type: "revoked", roomId: 12 });
    expect(parse({ type: "resync" })).toEqual({ type: "resync" });
    expect(parse({ type: "pong" })).toEqual({ type: "pong" });
  });

  test("an event without a thread or a message keeps just its room, and an unknown kind is still an event", () => {
    expect(parse({ type: "event", kind: "room.updated", roomId: 3 })).toEqual({ type: "event", kind: "room.updated", roomId: 3 });
    expect(parse({ type: "event", kind: "room.from_the_future", roomId: 3, threadNumber: null })).toEqual({ type: "event", kind: "room.from_the_future", roomId: 3 });
  });

  test("a ready frame that does not announce the cap means the protocol's 128", () => {
    expect(parse({ type: "ready", protocol: 1 })).toEqual({ type: "ready", protocol: 1, maxSubscriptions: 128 });
  });

  test("a subscribed frame may omit the lists an older server did not send", () => {
    expect(parse({ type: "subscribed", roomIds: [1] })).toEqual({ type: "subscribed", roomIds: [1], refused: [], capped: [] });
  });

  test("a valid reply correlation survives parsing and unusable correlation leaves the event intact", () => {
    const event = { type: "event", kind: "message.posted", roomId: 7, threadNumber: 3, messageId: 41 };
    const mutationId = "14d7ed05-24c1-4374-b6eb-60b147595822";
    expect(parse({ ...event, mutationId })).toEqual({ ...event, mutationId });
    expect(parse({ ...event, kind: "message.reaction_changed", mutationId })).toEqual({ ...event, kind: "message.reaction_changed", mutationId });
    expect(parse({ ...event, kind: "message.edited", mutationId })).toEqual({ ...event, kind: "message.edited", mutationId });
    expect(parse({ ...event, kind: "thread.owner_changed", messageId: undefined, mutationId }))
      .toEqual({ type: "event", kind: "thread.owner_changed", roomId: 7, threadNumber: 3, mutationId });
    expect(parse({ ...event, kind: "thread.status_changed", messageId: undefined, mutationId }))
      .toEqual({ type: "event", kind: "thread.status_changed", roomId: 7, threadNumber: 3, mutationId });
    expect(parse({ ...event, kind: "thread.updated", messageId: undefined, mutationId }))
      .toEqual({ type: "event", kind: "thread.updated", roomId: 7, threadNumber: 3, mutationId });
    expect(parse({ ...event, kind: "thread.merged", messageId: undefined, mutationId }))
      .toEqual({ type: "event", kind: "thread.merged", roomId: 7, threadNumber: 3, mutationId });
    expect(parse({ ...event, kind: "room.member_added", threadNumber: undefined, messageId: undefined, mutationId }))
      .toEqual({ type: "event", kind: "room.member_added", roomId: 7, mutationId });
    expect(parse({ ...event, kind: "room.member_removed", threadNumber: undefined, messageId: undefined, mutationId }))
      .toEqual({ type: "event", kind: "room.member_removed", roomId: 7, mutationId });
    expect(parse({ ...event, mutationId: mutationId.toUpperCase() })).toEqual(event);
    expect(parse({ ...event, mutationId, kind: "message.deleted" })).toEqual({ ...event, kind: "message.deleted" });
  });

  test("unknown members are dropped and unknown types are ignored", () => {
    expect(parse({ type: "revoked", roomId: 4, why: "because" })).toEqual({ type: "revoked", roomId: 4 });
    expect(parse({ type: "hello" })).toBeNull();
    expect(parse({})).toBeNull();
  });

  test("anything malformed is ignored, never thrown", () => {
    expect(parseServerFrame(new Blob(["x"]))).toBeNull();
    expect(parseServerFrame(undefined)).toBeNull();
    expect(parseServerFrame("{not json")).toBeNull();
    expect(parseServerFrame("42")).toBeNull();
    expect(parseServerFrame("null")).toBeNull();
    expect(parseServerFrame("[]")).toBeNull();
    expect(parse({ type: "ready", protocol: "1", maxSubscriptions: 128 })).toBeNull();
    expect(parse({ type: "ready", protocol: 1, maxSubscriptions: 0 })).toBeNull();
    expect(parse({ type: "subscribed", roomIds: "12", refused: [], capped: [] })).toBeNull();
    expect(parse({ type: "subscribed", roomIds: [12, -1], refused: [], capped: [] })).toBeNull();
    expect(parse({ type: "event", kind: 7, roomId: 12 })).toBeNull();
    expect(parse({ type: "event", kind: "room.updated", roomId: 0 })).toBeNull();
    expect(parse({ type: "event", kind: "message.posted", roomId: 1, threadNumber: "7" })).toBeNull();
    expect(parse({ type: "event", kind: "message.posted", roomId: 1, messageId: 1.5 })).toBeNull();
    expect(parse({ type: "revoked", roomId: "12" })).toBeNull();
  });
});
