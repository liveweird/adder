import { describe, expect, test } from "vitest";
import { renderHook } from "@testing-library/react";
import type { ReactNode } from "react";
import ThreadDraftsProvider from "../components/ThreadDraftsProvider";
import { createThreadDraftStore, useThreadDrafts } from "./useThreadDrafts";
import { emptyThreadForm } from "../utils/threadForm";

const draft = { ...emptyThreadForm([]), title: "T" };

describe("the thread draft store", () => {
  test("keeps a draft per room until it is cleared with null", () => {
    const store = createThreadDraftStore();
    expect(store.get(1)).toBeNull();
    store.set(1, draft);
    store.set(2, { ...draft, title: "other" });
    expect(store.get(1)).toBe(draft);
    expect(store.get(2)?.title).toBe("other");
    store.set(1, null);
    expect(store.get(1)).toBeNull();
    expect(store.get(2)?.title).toBe("other");
  });
});

describe("the reply drafts", () => {
  test("are kept per thread, per room, and a blank text clears one", () => {
    const store = createThreadDraftStore();
    expect(store.getReply(1, 7)).toBe("");
    store.setReply(1, 7, "half a thought");
    store.setReply(1, 8, "another");
    store.setReply(2, 7, "elsewhere");
    expect(store.getReply(1, 7)).toBe("half a thought");
    expect(store.getReply(1, 8)).toBe("another");
    expect(store.getReply(2, 7)).toBe("elsewhere");
    store.setReply(1, 7, "  \n");
    expect(store.getReply(1, 7)).toBe("");
    expect(store.getReply(1, 8)).toBe("another");
  });

  test("do not touch the new-thread drafts", () => {
    const store = createThreadDraftStore();
    store.setReply(1, 7, "x");
    expect(store.get(1)).toBeNull();
  });
});

describe("the quote drafts (1.3)", () => {
  const picked = { id: 9, authorId: 4, authorName: "Ann Author", body: "Is the disk full?", mentions: [], groupMentions: [] };

  test("are kept per thread, per room, until they are cleared with null", () => {
    const store = createThreadDraftStore();
    expect(store.getQuote(1, 7)).toBeNull();
    store.setQuote(1, 7, picked);
    store.setQuote(1, 8, { ...picked, id: 10 });
    expect(store.getQuote(1, 7)).toBe(picked);
    expect(store.getQuote(1, 8)?.id).toBe(10);
    expect(store.getQuote(2, 7)).toBeNull();
    store.setQuote(1, 7, null);
    expect(store.getQuote(1, 7)).toBeNull();
    expect(store.getQuote(1, 8)?.id).toBe(10);
  });

  test("live apart from the reply text and the new-thread draft", () => {
    const store = createThreadDraftStore();
    store.setQuote(1, 7, picked);
    expect(store.getReply(1, 7)).toBe("");
    expect(store.get(1)).toBeNull();
    store.setReply(1, 7, "text");
    store.setReply(1, 7, "");
    expect(store.getQuote(1, 7)).toBe(picked);
  });
});

describe("useThreadDrafts", () => {
  test("without a provider each component owns one private, stable store", () => {
    const first = renderHook(() => useThreadDrafts());
    const second = renderHook(() => useThreadDrafts());
    const store = first.result.current;
    first.rerender();
    expect(first.result.current).toBe(store);
    store.set(1, draft);
    expect(second.result.current.get(1)).toBeNull();
  });

  test("under the provider every component shares the shell's store", () => {
    const wrapper = ({ children }: { children: ReactNode }) => <ThreadDraftsProvider>{children}</ThreadDraftsProvider>;
    const first = renderHook(() => useThreadDrafts(), { wrapper });
    first.result.current.set(1, draft);
    first.rerender();
    expect(first.result.current.get(1)).toBe(draft);
  });
});
