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
