import { QueryClient } from "@tanstack/react-query";
import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { beginReactionEcho, beginReplyEcho, canCorrelateReply, clearReplyEchoes, deferReplyEcho } from "./liveReplyEcho";
import type { LiveServerFrame } from "./liveProtocol";

const firstId = "14d7ed05-24c1-4374-b6eb-60b147595822";

function posted(mutationId: string, messageId = 41, roomId = 7, threadNumber = 3): LiveServerFrame {
  return { type: "event", kind: "message.posted", roomId, threadNumber, messageId, mutationId };
}

function reacted(mutationId: string, messageId = 41, roomId = 7, threadNumber = 3): LiveServerFrame {
  return { type: "event", kind: "message.reaction_changed", roomId, threadNumber, messageId, mutationId };
}

describe("reply echo correlation", () => {
  let client: QueryClient;

  beforeEach(() => {
    vi.useFakeTimers();
    client = new QueryClient();
    vi.spyOn(crypto, "randomUUID").mockReturnValue(firstId);
  });

  afterEach(() => {
    clearReplyEchoes(client);
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
    vi.useRealTimers();
  });

  test("holds the writer's early hint but lets a different tab process it promptly", () => {
    const operation = beginReplyEcho(client, 7, 3)!;
    const replay = vi.fn();
    const otherTab = new QueryClient();

    expect(deferReplyEcho(client, posted(operation.id), replay)).toBe(true);
    expect(deferReplyEcho(otherTab, posted(operation.id), replay)).toBe(false);
    operation.confirm(41);
    expect(replay).not.toHaveBeenCalled();
    expect(deferReplyEcho(client, posted(operation.id), replay)).toBe(true);
  });

  test("a failed write or failed refresh replays the held hint once", () => {
    const operation = beginReplyEcho(client, 7, 3)!;
    const replay = vi.fn();

    deferReplyEcho(client, posted(operation.id), replay);
    operation.cancel();
    operation.cancel();
    expect(replay).toHaveBeenCalledOnce();
    expect(deferReplyEcho(client, posted(operation.id), replay)).toBe(false);
  });

  test("a mismatched message is replayed and unrelated or access hints always pass", () => {
    const operation = beginReplyEcho(client, 7, 3)!;
    const replay = vi.fn();

    expect(deferReplyEcho(client, posted(operation.id, 81, 8), replay)).toBe(false);
    expect(deferReplyEcho(client, posted(operation.id, 81, 7, 4), replay)).toBe(false);
    expect(deferReplyEcho(client, { ...posted(operation.id), type: "event", kind: "message.deleted", roomId: 7 }, replay)).toBe(false);
    expect(deferReplyEcho(client, { type: "revoked", roomId: 7 }, replay)).toBe(false);
    expect(deferReplyEcho(client, posted(operation.id, 81), replay)).toBe(true);
    operation.confirm(41);
    expect(replay).toHaveBeenCalledOnce();
    expect(deferReplyEcho(client, posted(operation.id, 81), replay)).toBe(false);
  });

  test("an abandoned operation expires and replays its hint", () => {
    const operation = beginReplyEcho(client, 7, 3)!;
    const replay = vi.fn();

    deferReplyEcho(client, posted(operation.id), replay);
    vi.advanceTimersByTime(30_000);
    expect(replay).toHaveBeenCalledOnce();
    expect(deferReplyEcho(client, posted(operation.id), replay)).toBe(false);
  });

  test("duplicate UUIDs and a full registry fall back to ordinary invalidation", () => {
    expect(beginReplyEcho(client, 7, 3)).toBeDefined();
    expect(beginReplyEcho(client, 7, 3)).toBeUndefined();
    let counter = 0;
    vi.mocked(crypto.randomUUID).mockImplementation(() => `14d7ed05-24c1-4374-b6eb-${(++counter).toString(16).padStart(12, "0")}`);
    for (let index = 0; index < 31; index++) expect(beginReplyEcho(client, 7, 3)).toBeDefined();
    expect(beginReplyEcho(client, 7, 3)).toBeUndefined();
  });

  test("browsers without secure UUID generation use ordinary invalidation", () => {
    vi.stubGlobal("crypto", {});
    expect(beginReplyEcho(client, 7, 3)).toBeUndefined();
  });

  test("a duplicate early hint replays once and a different message passes promptly", () => {
    const operation = beginReplyEcho(client, 7, 3)!;
    const replay = vi.fn();
    expect(deferReplyEcho(client, posted(operation.id), replay)).toBe(true);
    expect(deferReplyEcho(client, posted(operation.id), replay)).toBe(true);
    expect(deferReplyEcho(client, posted(operation.id, 42), replay)).toBe(false);
    operation.cancel();
    expect(replay).toHaveBeenCalledOnce();
  });

  test("session reset discards old queued hints and invalidates old operation handles", () => {
    const old = beginReplyEcho(client, 7, 3)!;
    const replay = vi.fn();

    deferReplyEcho(client, posted(old.id), replay);
    clearReplyEchoes(client);
    const next = beginReplyEcho(client, 7, 3)!;
    deferReplyEcho(client, posted(next.id), replay);
    old.cancel();
    expect(replay).not.toHaveBeenCalled();
    next.cancel();
    expect(replay).toHaveBeenCalledOnce();
  });
});

describe("reply correlation origin", () => {
  test.each([
    ["", true],
    ["/adder/", true],
    ["https://adder.test/api", true],
    ["https://adder.test:443/api", true],
    ["//other.test/api", false],
    ["https://other.test/api", false],
    ["http://adder.test/api", false],
    ["https://", false],
  ])("resolves %s against the page origin", (base, expected) => {
    expect(canCorrelateReply(base, "https://adder.test/rooms/5")).toBe(expected);
  });
});

describe("reaction echo correlation", () => {
  let client: QueryClient;

  beforeEach(() => {
    vi.useFakeTimers();
    client = new QueryClient();
    vi.spyOn(crypto, "randomUUID").mockReturnValue(firstId);
  });

  afterEach(() => {
    clearReplyEchoes(client);
    vi.restoreAllMocks();
    vi.useRealTimers();
  });

  test("holds only the exact reaction event until its authoritative refresh is confirmed", () => {
    const operation = beginReactionEcho(client, 7, 3, 41)!;
    const replay = vi.fn();
    expect(deferReplyEcho(client, reacted(operation.id), replay)).toBe(true);
    expect(deferReplyEcho(client, reacted(operation.id, 42), replay)).toBe(false);
    expect(deferReplyEcho(client, posted(operation.id), replay)).toBe(false);
    operation.confirm();
    expect(replay).not.toHaveBeenCalled();
  });

  test("a failed or expired reaction refresh replays one held hint", () => {
    const operation = beginReactionEcho(client, 7, 3, 41)!;
    const replay = vi.fn();
    deferReplyEcho(client, reacted(operation.id), replay);
    operation.cancel();
    expect(replay).toHaveBeenCalledOnce();

    vi.mocked(crypto.randomUUID).mockReturnValue("14d7ed05-24c1-4374-b6eb-60b147595823");
    const expired = beginReactionEcho(client, 7, 3, 41)!;
    deferReplyEcho(client, reacted(expired.id), replay);
    vi.advanceTimersByTime(30_000);
    expect(replay).toHaveBeenCalledTimes(2);
  });

  test("a reaction falls back when another operation already holds the same correlation ID", () => {
    const reply = beginReplyEcho(client, 7, 3)!;
    const replay = vi.fn();

    expect(beginReactionEcho(client, 7, 3, 41)).toBeUndefined();
    expect(deferReplyEcho(client, reacted(reply.id), replay)).toBe(false);
    expect(deferReplyEcho(client, posted(reply.id), replay)).toBe(true);
    reply.cancel();
    expect(replay).toHaveBeenCalledOnce();
  });

  test("a confirmed reaction ignores late cleanup and repeated hints for that operation", () => {
    const reaction = beginReactionEcho(client, 7, 3, 41)!;
    const replay = vi.fn();

    reaction.confirm();
    reaction.cancel();
    expect(deferReplyEcho(client, reacted(reaction.id), replay)).toBe(true);
    expect(deferReplyEcho(client, reacted(reaction.id, 42), replay)).toBe(false);
    reaction.confirm();
    vi.advanceTimersByTime(30_000);
    expect(replay).not.toHaveBeenCalled();
    expect(deferReplyEcho(client, reacted(reaction.id), replay)).toBe(false);
  });

});
