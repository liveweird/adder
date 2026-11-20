// The pane's part of the unread separator (0.6.0): it hands the list whether the thread read has settled. Kept apart from `ThreadPane.test.tsx`,
// whose describe is at the lint's function-size limit.
import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { screen } from "@testing-library/react";
import { QueryClient } from "@tanstack/react-query";
import ThreadPane from "./ThreadPane";
import { jsonResponse } from "../test/http";
import { makeMessage, makeRoom, makeThread, messagePage } from "../test/messageFixtures";
import { renderWithProviders } from "../test/render";

describe("ThreadPane: unread", () => {
  let mockFetch: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    mockFetch = vi.fn();
    vi.stubGlobal("fetch", mockFetch);
    localStorage.setItem("adder.auth.token", "fake-token");
  });
  afterEach(() => {
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  test("the New separator comes from the thread read once it has settled, not from a stale copy cached by an earlier visit", async () => {
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    queryClient.setQueryData(["rooms", "threads", 12, "detail", 42], makeThread({ unreadCount: 0, firstUnreadMessageId: null }));
    const messages = [makeMessage({ id: 2, authorName: "Di Other" }), makeMessage({ id: 1, authorName: "Bob Starter" })];
    mockFetch.mockImplementation((url: string) => {
      if (url === "/api/v1/rooms/12/threads/42") return Promise.resolve(jsonResponse(200, makeThread({ unreadCount: 1, firstUnreadMessageId: 2 })));
      if (url === "/api/v1/rooms/12/workflow") return Promise.resolve(jsonResponse(200, { revision: 1, statuses: [], fields: [] }));
      if (url.startsWith("/api/v1/rooms/12/threads/42/messages?")) return Promise.resolve(jsonResponse(200, messagePage(messages)));
      if (url.startsWith("/api/v1/rooms/12/threads/42/events?")) return Promise.resolve(jsonResponse(200, { items: [], page: 1, pageSize: 10, total: 0 }));
      return Promise.resolve(jsonResponse(404, { title: "Not found", status: 404 }));
    });
    renderWithProviders(<ThreadPane room={makeRoom()} numberParam="42" />, { queryClient });
    const separator = await screen.findByRole("separator", { name: "New messages" });
    expect(separator.closest("li")).toHaveAttribute("data-message-id", "2");
    expect(screen.getAllByRole("separator")).toHaveLength(1);
  });
});
