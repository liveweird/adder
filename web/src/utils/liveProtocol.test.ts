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
    expect(LIVE_EVENT_KINDS).toHaveLength(16);
    expect(new Set(LIVE_EVENT_KINDS).size).toBe(16);
  });
});

describe("liveUrl", () => {
  test("is ws on http and wss on https, on the page's own host, with no ticket in it", () => {
    expect(liveUrl({ protocol: "http:", host: "localhost:5179" })).toBe("ws://localhost:5179/api/v1/live");
    expect(liveUrl({ protocol: "https:", host: "adder.example.test" })).toBe("wss://adder.example.test/api/v1/live");
  });
});

describe("parseServerFrame", () => {
  const parse = (frame: unknown) => parseServerFrame(JSON.stringify(frame));

  test("reads each frame of the protocol", () => {
    expect(parse({ type: "ready", protocol: 1, maxSubscriptions: 128 })).toEqual({ type: "ready", protocol: 1, maxSubscriptions: 128 });
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
