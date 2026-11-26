import { describe, expect, test, vi } from "vitest";
import { QueryClient, QueryObserver } from "@tanstack/react-query";
import type { KeyboardEvent } from "react";
import type { TFunction } from "i18next";
import { ApiError } from "../api/http";
import { isSendShortcut, messageErrorMessage, refreshAfterReply } from "./messageForm";
import { PROBLEM_TYPES } from "./problems";
import { messageBodyError } from "./threadForm";

// A translator that returns the key, so the assertions name the vocabulary entry.
const t = ((key: string) => key) as unknown as TFunction;
const problem = (status: number, type?: string) => new ApiError(status, { title: "x", status, ...(type ? { type } : {}) });

describe("messageErrorMessage", () => {
  test("the two 409s are told apart by their problem type; any other 409 is generic", () => {
    expect(messageErrorMessage(problem(409, PROBLEM_TYPES.threadClosed), t)).toBe("threads.message.error.closed");
    expect(messageErrorMessage(problem(409, PROBLEM_TYPES.roomArchived), t)).toBe("threads.message.error.archived");
    expect(messageErrorMessage(problem(409), t)).toBe("threads.message.error.conflict");
  });

  test("403, 404, 429 and 400 each have their own words", () => {
    expect(messageErrorMessage(problem(403), t)).toBe("threads.message.error.forbidden");
    expect(messageErrorMessage(problem(404), t)).toBe("threads.message.error.gone");
    expect(messageErrorMessage(problem(429), t)).toBe("threads.message.error.tooManyRequests");
    expect(messageErrorMessage(problem(400), t)).toBe("threads.message.error.invalid");
  });

  test("the typed quote-unavailable 400 says to remove the quote; any other 400 is the invalid-message words", () => {
    expect(messageErrorMessage(problem(400, PROBLEM_TYPES.quoteUnavailable), t)).toBe("threads.quote.unavailable");
    expect(messageErrorMessage(problem(400, "urn:adder:problem:other"), t)).toBe("threads.message.error.invalid");
  });

  test("a room-busy 429 has its own words, not the posting-too-quickly bucket copy", () => {
    expect(messageErrorMessage(problem(429, PROBLEM_TYPES.roomBusy), t)).toBe("common.error.roomBusy");
  });

  test("anything else is the generic action failure", () => {
    expect(messageErrorMessage(problem(500), t)).toBe("common.error.actionFailedStatus");
    expect(messageErrorMessage(new Error("offline"), t)).toBe("common.error.actionFailed");
  });
});

describe("isSendShortcut", () => {
  const key = (init: Partial<{ key: string; ctrlKey: boolean; metaKey: boolean; isComposing: boolean }>) =>
    ({ key: "Enter", ctrlKey: false, metaKey: false, nativeEvent: { isComposing: init.isComposing ?? false }, ...init }) as unknown as KeyboardEvent;

  test("Ctrl+Enter and Cmd+Enter send; a plain Enter, Shift+Enter and other keys do not", () => {
    expect(isSendShortcut(key({ ctrlKey: true }))).toBe(true);
    expect(isSendShortcut(key({ metaKey: true }))).toBe(true);
    expect(isSendShortcut(key({}))).toBe(false);
    expect(isSendShortcut(key({ key: "a", ctrlKey: true }))).toBe(false);
  });

  test("a key pressed during an IME composition never sends", () => {
    expect(isSendShortcut(key({ ctrlKey: true, isComposing: true }))).toBe(false);
  });
});

describe("messageBodyError", () => {
  test("the server's rules, after its own normalisation: 1 to 8000 characters", () => {
    expect(messageBodyError("hello", t)).toBeNull();
    expect(messageBodyError("  \r\n\n  ", t)).toBe("threads.validation.bodyLength");
    expect(messageBodyError("\n\nhello  \n", t)).toBeNull();
    expect(messageBodyError("x".repeat(8000), t)).toBeNull();
    expect(messageBodyError("x".repeat(8001), t)).toBe("threads.validation.bodyLength");
  });

  test("line feeds and tabs stay; other control and hidden direction characters are refused", () => {
    expect(messageBodyError("a\tb\nc", t)).toBeNull();
    expect(messageBodyError("a\u0007b", t)).toBe("threads.validation.bodyControl");
    expect(messageBodyError("a‮b", t)).toBe("threads.validation.bodyControl");
    expect(messageBodyError("a b", t)).toBe("threads.validation.bodyControl");
  });
});

describe("refreshAfterReply", () => {
  test("refreshes thread and activity views, Home, and watched searches", async () => {
    const queryClient = new QueryClient();
    const invalidate = vi.spyOn(queryClient, "invalidateQueries");
    await refreshAfterReply(queryClient, 12);
    expect(invalidate.mock.calls.map(([filters]) => filters?.queryKey)).toEqual([
      ["rooms", "threads", 12], ["rooms", "rail"], ["search"], ["home"],
    ]);
  });

  test("reports coverage only after a successful active refresh", async () => {
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const key = ["rooms", "threads", 12, "detail", 1];
    client.setQueryData(key, "old");
    const queryFn = vi.fn<() => Promise<string>>().mockRejectedValueOnce(new Error("offline")).mockResolvedValueOnce("fresh");
    const observer = new QueryObserver(client, { queryKey: key, queryFn, staleTime: Infinity });
    const unsubscribe = observer.subscribe(() => undefined);
    const onCovered = vi.fn();
    try {
      await refreshAfterReply(client, 12, onCovered);
      expect(onCovered).not.toHaveBeenCalled();
      await refreshAfterReply(client, 12, onCovered);
      expect(onCovered).toHaveBeenCalledOnce();
      expect(client.getQueryData(key)).toBe("fresh");
    } finally {
      unsubscribe();
      client.clear();
    }
  });

});
