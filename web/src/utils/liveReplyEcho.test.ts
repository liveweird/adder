import { QueryClient } from "@tanstack/react-query";
import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { beginReplyEcho, clearReplyEchoes, deferReplyEcho } from "./liveReplyEcho";
import type { LiveServerFrame } from "./liveProtocol";

const firstId = "14d7ed05-24c1-4374-b6eb-60b147595822";

function posted(mutationId: string, messageId = 41, roomId = 7, threadNumber = 3): LiveServerFrame {
  return { type: "event", kind: "message.posted", roomId, threadNumber, messageId, mutationId };
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
