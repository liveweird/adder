import { describe, expect, test } from "vitest";
import { renderHook } from "@testing-library/react";
import { useArrivals, useFirstUnreadOnOpen } from "./useConversationUnread";

describe("useFirstUnreadOnOpen", () => {
  test("is taken from the first settled read and never moves afterwards", () => {
    const { result, rerender } = renderHook(({ id, settled }) => useFirstUnreadOnOpen(id, settled), { initialProps: { id: null as number | null, settled: false } });
    expect(result.current).toBeNull();
    // A stale copy that is still being re-read does not decide it.
    rerender({ id: 5, settled: false });
    expect(result.current).toBeNull();
    rerender({ id: 7, settled: true });
    expect(result.current).toBe(7);
    // The reader read on; a later read names a message that arrived under them: the separator stays.
    rerender({ id: 31, settled: true });
    expect(result.current).toBe(7);
    rerender({ id: null, settled: true });
    expect(result.current).toBe(7);
  });

  test("a settled read with nothing unread stays without a boundary", () => {
    const { result, rerender } = renderHook(({ id }) => useFirstUnreadOnOpen(id, true), { initialProps: { id: null as number | null } });
    rerender({ id: 9 });
    expect(result.current).toBeNull();
  });
});

describe("useArrivals", () => {
  const messages = (...ids: number[]) => ids.map((id) => ({ id, authorId: id % 2 === 0 ? 8 : 7 }));
  const setup = (initial: { list: { id: number; authorId: number }[]; atEnd: boolean }) =>
    renderHook(({ list, atEnd }) => useArrivals(list, atEnd, 7), { initialProps: initial });

  test("counts nothing at the end, and nothing before the first messages", () => {
    const { result, rerender } = setup({ list: [], atEnd: true });
    expect(result.current).toBe(0);
    rerender({ list: messages(1, 2), atEnd: true });
    rerender({ list: messages(1, 2, 4, 6), atEnd: true });
    expect(result.current).toBe(0);
  });

  test("counts the messages of others that arrive under a reader who is not at the end, and clears at the end", () => {
    const { result, rerender } = setup({ list: messages(1, 2, 3), atEnd: false });
    expect(result.current).toBe(0);
    // 4 and 6 are by someone else, 5 and 7 are the caller's own (odd ids are the caller's here).
    rerender({ list: messages(1, 2, 3, 4, 5, 6, 7), atEnd: false });
    expect(result.current).toBe(2);
    rerender({ list: messages(1, 2, 3, 4, 5, 6, 7, 8), atEnd: false });
    expect(result.current).toBe(3);
    rerender({ list: messages(1, 2, 3, 4, 5, 6, 7, 8), atEnd: true });
    expect(result.current).toBe(0);
    // Away again: the count starts from what was seen at the end.
    rerender({ list: messages(1, 2, 3, 4, 5, 6, 7, 8, 10), atEnd: false });
    expect(result.current).toBe(1);
  });

  test("older messages loaded above never count", () => {
    const { result, rerender } = setup({ list: messages(20, 22), atEnd: false });
    rerender({ list: messages(2, 4, 20, 22), atEnd: false });
    expect(result.current).toBe(0);
  });
});
