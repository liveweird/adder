// The thread list's unread marks (0.6.0): a bold row, its count, the `@` mark and the one description. Kept apart from `ThreadList.test.tsx`,
// whose describe is at the lint's function-size limit.
import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { screen, waitFor, within } from "@testing-library/react";
import ThreadList from "./ThreadList";
import type { RoomResponse } from "../api/rooms";
import { jsonResponse } from "../test/http";
import { renderWithProviders } from "../test/render";

const ROOM: RoomResponse = {
  id: 12,
  name: "Night Shift",
  description: null,
  archived: false,
  archivedAt: null,
  role: "MEMBER",
  memberCount: 3,
  workflowRevision: 1,
  lastActivityAt: 1,
  createdAt: 1,
  updatedAt: 1,
};
const WORKFLOW = {
  revision: 1,
  statuses: [{ key: "triage", label: "Triage", colour: "BLUE", category: "OPEN", initial: true, threadCount: 2 }],
  fields: [],
};
const AT = Date.UTC(2026, 5, 1, 9, 30);
const THREADS = {
  items: [
    { id: 1, number: 2, title: "Disk full on db-3", status: "triage", closed: false, ownerId: 5, ownerName: "Ann Owner", messageCount: 4, lastActivityAt: AT, createdAt: AT },
    { id: 2, number: 1, title: "Old alert", status: "triage", closed: false, ownerId: 6, ownerName: "Bob", messageCount: 1, lastActivityAt: AT, createdAt: AT },
  ],
  page: 1,
  pageSize: 50,
  total: 2,
};

describe("ThreadList", () => {
  let mockFetch: ReturnType<typeof vi.fn>;
  const renderList = () => renderWithProviders(<ThreadList room={ROOM} activeNumber={null} />, { route: "/rooms/12" });

  beforeEach(() => {
    mockFetch = vi.fn();
    vi.stubGlobal("fetch", mockFetch);
    localStorage.setItem("adder.auth.token", "fake-token");
  });
  afterEach(() => {
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  describe("unread marks", () => {
    const withUnread = (counts: [number, number]) => ({
      ...THREADS,
      items: THREADS.items.map((thread, index) => ({ ...thread, unreadCount: counts[index] })),
    });
    const notice = (threadNumber: number, roomId = 12) => ({ id: 70 + threadNumber, type: "MENTIONED", seen: false, createdAt: AT, actorId: 5, actorName: "Ann Owner", roomId, roomName: "Night Shift", threadNumber, threadTitle: "t", messageId: 9 });

    function serveUnread(threads: unknown, notices: unknown[] = []) {
      mockFetch.mockImplementation((url: string) => {
        if (url.startsWith("/api/v1/notifications?")) return Promise.resolve(jsonResponse(200, { items: notices, page: 1, pageSize: 100, total: notices.length }));
        if (url.startsWith("/api/v1/rooms/12/threads?")) return Promise.resolve(jsonResponse(200, threads));
        if (url === "/api/v1/rooms/12/workflow") return Promise.resolve(jsonResponse(200, WORKFLOW));
        return Promise.resolve(jsonResponse(404, { title: "Not found", status: 404 }));
      });
    }
    const titleOf = (link: HTMLElement) => within(link).getByText(/Disk full on db-3|Old alert/);

    test("an unread row is bold, shows its count and says it in words; a read row is none of that", async () => {
      serveUnread(withUnread([4, 0]));
      renderList();
      const unread = await screen.findByRole("link", { name: /Disk full on db-3/ });
      expect(unread).toHaveAccessibleDescription("4 unread messages");
      expect(within(unread).getByText("4").closest("[aria-hidden='true']")).not.toBeNull();
      expect(titleOf(unread).closest("p")).toHaveStyle({ fontWeight: "700" });
      const read = screen.getByRole("link", { name: /Old alert/ });
      expect(read).not.toHaveAttribute("aria-describedby");
      expect(titleOf(read).closest("p")).toHaveStyle({ fontWeight: "500" });
      // The name is still the thread's own.
      expect(screen.queryByRole("link", { name: /unread/ })).not.toBeInTheDocument();
    });

    test("one unread message is singular, and 100 shows as 99+ and reads as 100 or more", async () => {
      serveUnread(withUnread([1, 100]));
      renderList();
      expect(await screen.findByRole("link", { name: /Disk full on db-3/ })).toHaveAccessibleDescription("1 unread message");
      const capped = screen.getByRole("link", { name: /Old alert/ });
      expect(capped).toHaveAccessibleDescription("100 or more unread messages");
      expect(within(capped).getByText("99+")).toBeInTheDocument();
    });

    test("a thread with an unseen notice of this room carries an @ mark; a notice of another room does not", async () => {
      serveUnread(withUnread([0, 0]), [notice(2), notice(1, 99)]);
      renderList();
      const marked = await screen.findByRole("link", { name: /Disk full on db-3/ });
      await waitFor(() => expect(marked).toHaveAccessibleDescription("You have an unseen notification in this thread"));
      expect(marked.querySelector("svg.tabler-icon-at")).not.toBeNull();
      expect(screen.getByRole("link", { name: /Old alert/ })).not.toHaveAttribute("aria-describedby");
    });

    test("unread, an unseen notice and a pin share one description", async () => {
      mockFetch.mockImplementation((url: string) => {
        if (url.startsWith("/api/v1/notifications?")) return Promise.resolve(jsonResponse(200, { items: [notice(2)], page: 1, pageSize: 100, total: 1 }));
        if (url.startsWith("/api/v1/rooms/12/pins?")) {
          const pin = { id: 3, kind: "THREAD", threadNumber: 2, threadTitle: "Disk full on db-3", threadStatus: "triage", threadClosed: false, message: null, pinnedById: 5, pinnedByName: "Ann Owner", pinnedAt: AT };
          return Promise.resolve(jsonResponse(200, { items: [pin], page: 1, pageSize: 50, total: 1 }));
        }
        if (url.startsWith("/api/v1/rooms/12/threads?")) return Promise.resolve(jsonResponse(200, withUnread([2, 0])));
        if (url === "/api/v1/rooms/12/workflow") return Promise.resolve(jsonResponse(200, WORKFLOW));
        return Promise.resolve(jsonResponse(404, { title: "Not found", status: 404 }));
      });
      renderList();
      const row = await screen.findByRole("link", { name: /Disk full on db-3/ });
      await waitFor(() => expect(row).toHaveAccessibleDescription("2 unread messages. You have an unseen notification in this thread. Pinned"));
    });
  });
});
