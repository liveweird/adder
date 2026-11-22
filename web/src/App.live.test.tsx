import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { MantineProvider } from "@mantine/core";
import { MemoryRouter } from "react-router-dom";
import { act, render, screen, waitFor, within } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import App from "./App";
import { jsonResponse } from "./test/http";
import { ChannelStub } from "./test/broadcast";
import { lastSocket, makeLive, SocketStub } from "./test/socket";
import { resetTabVisibility, setTabVisible } from "./test/visibility";
import { setTitleCount } from "./utils/titleCount";
import { clearSession, persistSession } from "./api/session";

// The live channel through the real <App/>: the shell opens ONE socket after minting a ticket, subscribes to the open room and the rail,
// and a hint re-reads what the page shows. The socket is a stub and the timers are real (the invalidator's 300 ms settle).

const ROOM_12 = { id: 12, name: "Night Shift", description: null, archived: false, archivedAt: null, role: "OWNER", memberCount: 3, workflowRevision: 1, lastActivityAt: 5, createdAt: 1, updatedAt: 1 };
const RAIL = { items: [ROOM_12], page: 1, pageSize: 100, total: 1 };
const thread = (title: string) => ({ id: 1, number: 7, title, status: "open", closed: false, ownerId: 5, ownerName: "Ann Owner", messageCount: 2, lastActivityAt: 1, createdAt: 1, hashtags: [], tags: [] });
const WORKFLOW = { revision: 1, statuses: [{ key: "open", label: "Triage", colour: "BLUE", category: "OPEN", initial: true, threadCount: 1 }], fields: [] };

let title = "Disk full on db-3";
let roomStatus = 200;
let unseenTotal = 0;
const calls: string[] = [];

function api(url: string, init?: RequestInit): Promise<Response> {
  calls.push(`${init?.method ?? "GET"} ${url}`);
  if (url === "/api/v1/live/tickets") return Promise.resolve(jsonResponse(200, { ticket: "t".repeat(43), expiresAt: Date.now() + 30_000 }));
  if (url.startsWith("/api/v1/notifications?")) return Promise.resolve(jsonResponse(200, { items: [], page: 1, pageSize: 100, total: unseenTotal }));
  if (url.startsWith("/api/v1/rooms?")) return Promise.resolve(jsonResponse(200, RAIL));
  if (url === "/api/v1/rooms/12") return Promise.resolve(roomStatus === 200 ? jsonResponse(200, ROOM_12) : jsonResponse(roomStatus, { title: "Not found", status: roomStatus }));
  if (url === "/api/v1/rooms/12/workflow") return Promise.resolve(jsonResponse(200, WORKFLOW));
  if (url.startsWith("/api/v1/rooms/12/threads?")) return Promise.resolve(jsonResponse(200, { items: [thread(title)], page: 1, pageSize: 50, total: 1 }));
  if (url === "/api/v1/rooms/12/threads/7") {
    return Promise.resolve(jsonResponse(200, { ...thread(title), closedAt: null, createdById: 5, createdByName: "Ann Owner", fields: {}, fieldUserNames: {}, revision: 1, updatedAt: 1 }));
  }
  if (url.startsWith("/api/v1/rooms/12/threads/7/messages")) return Promise.resolve(jsonResponse(200, { items: [], page: 1, pageSize: 50, total: 0 }));
  return Promise.resolve(jsonResponse(404, { title: "Not found", status: 404 }));
}

function renderApp(route: string) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <MantineProvider env="test">
      <QueryClientProvider client={queryClient}>
        <MemoryRouter initialEntries={[route]}>
          <App />
        </MemoryRouter>
      </QueryClientProvider>
    </MantineProvider>,
  );
}

const reads = (url: string) => calls.filter((call) => call === `GET ${url}`).length;

describe("the shell's live socket", () => {
  beforeEach(() => {
    title = "Disk full on db-3";
    roomStatus = 200;
    unseenTotal = 0;
    calls.length = 0;
    localStorage.setItem("adder.auth.token", "fake-token");
    vi.stubGlobal("WebSocket", SocketStub);
    vi.stubGlobal("fetch", vi.fn(api));
    // The jitter (the 0 to 2 s spread of the ready and subscribed re-reads, the reconnect delay) draws from crypto.getRandomValues: pin it to
    // the shortest wait so the real timers below only have the 300 ms settle to cover. The jitter itself is pinned in liveKeys/liveClient tests.
    vi.spyOn(crypto, "getRandomValues").mockImplementation((array) => {
      (array as Uint32Array).fill(0);
      return array;
    });
  });
  afterEach(() => {
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
    SocketStub.reset();
    ChannelStub.reset();
    resetTabVisibility();
    // Module state outlives the test (`isolate: false`): the shell's unmount clears the count, this makes sure of it, and the title with it.
    setTitleCount(0);
    document.title = "";
    localStorage.clear();
  });

  async function openLiveThread() {
    renderApp("/rooms/12/threads/7");
    await vi.waitFor(() => expect(SocketStub.instances).toHaveLength(1));
    act(() => makeLive(lastSocket()));
    expect(await screen.findByRole("region", { name: "#7 Disk full on db-3" })).toBeInTheDocument();
    return lastSocket();
  }

  test("mints a ticket, opens ONE socket on the page's own host, sends the ticket in the first frame and subscribes to the open room", async () => {
    renderApp("/rooms/12/threads/7");
    await vi.waitFor(() => expect(SocketStub.instances).toHaveLength(1));
    const socket = lastSocket();
    expect(socket.url).toBe("ws://localhost:3000/api/v1/live");
    expect(calls.filter((call) => call === "POST /api/v1/live/tickets")).toHaveLength(1);
    act(() => socket.open());
    expect(socket.frames()).toEqual([{ type: "auth", ticket: "t".repeat(43) }]);
    act(() => socket.receive({ type: "ready", protocol: 1, maxSubscriptions: 128 }));
    // The rail's room and the open room are the same room: asked for once.
    await waitFor(() => expect(socket.frames().slice(1)).toEqual([{ type: "subscribe", roomIds: [12] }]));
    expect(SocketStub.instances).toHaveLength(1);
  });

  test("the header's status region is always present and shows nothing while live", async () => {
    await openLiveThread();
    const region = within(screen.getByRole("banner")).getByRole("status");
    expect(region).toBeEmptyDOMElement();
  });

  test("a hint re-reads the page's own data: a renamed thread shows its new title", async () => {
    const socket = await openLiveThread();
    await screen.findByRole("link", { name: /Disk full on db-3/ });
    title = "Disk full on db-4";
    act(() => socket.receive({ type: "event", kind: "thread.updated", roomId: 12, threadNumber: 7 }));
    expect(await screen.findByRole("region", { name: "#7 Disk full on db-4" })).toBeInTheDocument();
    expect(await screen.findByRole("link", { name: /Disk full on db-4/ })).toBeInTheDocument();
  });

  test("a notification.created frame updates the bell's badge without a reload, and a notice reaches the recipient with no room subscription", async () => {
    const socket = await openLiveThread();
    const bell = await screen.findByRole("button", { name: "Notifications (0 unseen)" });
    unseenTotal = 2;
    act(() => socket.receive({ type: "event", kind: "notification.created", roomId: 99, threadNumber: 3, messageId: 5 }));
    expect(await screen.findByRole("button", { name: "Notifications (2 unseen)" })).toBe(bell);
    expect(within(screen.getByRole("banner")).getByText("2")).toBeInTheDocument();
  });

  test("revoked turns the room's pages to the uniform not-found without a reload", async () => {
    const socket = await openLiveThread();
    roomStatus = 404;
    act(() => socket.receive({ type: "revoked", roomId: 12 }));
    expect(await screen.findByText("This room does not exist, or you are not a member of it.")).toBeInTheDocument();
  });

  test("the subscribed ack re-reads the open room, closing the gap between the page's read and the subscription", async () => {
    const socket = await openLiveThread();
    await screen.findByRole("link", { name: /Disk full on db-3/ });
    const before = reads("/api/v1/rooms/12");
    act(() => socket.receive({ type: "subscribed", roomIds: [12], refused: [], capped: [] }));
    await waitFor(() => expect(reads("/api/v1/rooms/12")).toBeGreaterThan(before));
  });

  test("a socket that drops shows Reconnecting… only after five seconds (the status region stays mounted)", async () => {
    vi.useFakeTimers({ toFake: ["setTimeout", "clearTimeout"] });
    try {
      renderApp("/");
      await act(async () => {
        for (let i = 0; i < 10; i++) await Promise.resolve();
      });
      expect(SocketStub.instances).toHaveLength(1);
      act(() => makeLive(lastSocket()));
      act(() => lastSocket().closeWith(1006));
      const region = within(screen.getByRole("banner")).getByRole("status");
      expect(region).toBeEmptyDOMElement();
      act(() => vi.advanceTimersByTime(5000));
      expect(region).toHaveTextContent("Reconnecting…");
    } finally {
      vi.useRealTimers();
    }
  });

  describe("the tab title and the person's other tabs (1.2 D8, D9)", () => {
    test("the title counts the bell's number once the socket is live, follows the bell and keeps it through a short drop", async () => {
      unseenTotal = 3;
      renderApp("/");
      await vi.waitFor(() => expect(SocketStub.instances).toHaveLength(1));
      await screen.findByRole("button", { name: "Notifications (3 unseen)" });
      // Connecting: the number could be stale, so none is shown.
      expect(document.title).not.toMatch(/^\(/);
      act(() => makeLive(lastSocket()));
      await waitFor(() => expect(document.title).toMatch(/^\(3\) /));
      expect(document.title).toMatch(/Adder$/);
      unseenTotal = 2;
      act(() => lastSocket().receive({ type: "event", kind: "notification.created", roomId: 12, threadNumber: 1 }));
      await waitFor(() => expect(document.title).toMatch(/^\(2\) /));
      // A socket that drops keeps the number for the 5 s grace (a re-mint must not blink the title); `useTabTitleCount.test.tsx` pins its end.
      act(() => lastSocket().closeWith(1006));
      expect(document.title).toMatch(/^\(2\) /);
    });

    test("a hidden tab keeps its number while quiet and its socket, and shown again it is live", async () => {
      vi.useFakeTimers({ toFake: ["setTimeout", "clearTimeout"] });
      try {
        unseenTotal = 3;
        renderApp("/");
        await act(async () => {
          for (let i = 0; i < 20; i++) await Promise.resolve();
        });
        act(() => makeLive(lastSocket()));
        await act(async () => {
          await vi.advanceTimersByTimeAsync(100);
        });
        expect(document.title).toMatch(/^\(3\) /);
        setTabVisible(false);
        await act(async () => {
          await vi.advanceTimersByTimeAsync(5 * 60_000);
        });
        const socket = lastSocket();
        expect(socket.close).not.toHaveBeenCalled();
        expect(SocketStub.instances).toHaveLength(1);
        // The deliberate quiet is no reconnecting: the region stays empty and the title keeps its number.
        expect(within(screen.getByRole("banner")).getByRole("status")).toBeEmptyDOMElement();
        expect(document.title).toMatch(/^\(3\) /);
        // The rail's room was dropped from the socket (no room subscription while quiet), the socket itself stays.
        expect(socket.frames().filter((frame) => frame.type === "unsubscribe")).toEqual([{ type: "unsubscribe", roomIds: [12] }]);
        setTabVisible(true);
        expect(socket.close).not.toHaveBeenCalled();
        expect(socket.frames().filter((frame) => frame.type === "subscribe")).toEqual([
          { type: "subscribe", roomIds: [12] },
          { type: "subscribe", roomIds: [12] },
        ]);
      } finally {
        vi.useRealTimers();
      }
    });

    test("no number is left in the title after sign-out, and a switch of user shows none until the new user's socket is live", async () => {
      unseenTotal = 3;
      renderApp("/");
      await vi.waitFor(() => expect(SocketStub.instances).toHaveLength(1));
      await screen.findByRole("button", { name: "Notifications (3 unseen)" });
      act(() => makeLive(lastSocket()));
      await waitFor(() => expect(document.title).toMatch(/^\(3\) /));
      // Another person signs in here: the old socket is stopped, the new one is not live yet, and the old person's number is not shown.
      act(() => persistSession({ token: "t2", expiresAt: 1, refreshToken: "r2", refreshExpiresAt: 2, roles: [], userId: 2, disabledFeatures: [], language: "en" }));
      await waitFor(() => expect(SocketStub.instances).toHaveLength(2));
      expect(document.title).not.toMatch(/^\(/);
      act(() => makeLive(lastSocket()));
      await waitFor(() => expect(document.title).toMatch(/^\(3\) /));
      act(() => clearSession());
      await waitFor(() => expect(document.title).not.toMatch(/^\(/));
    });

    test("a tab hidden for 8 hours (idle) issues no request for another tab's message, and shown again it reconnects", async () => {
      vi.useFakeTimers({ toFake: ["setTimeout", "clearTimeout"] });
      try {
        localStorage.setItem("adder.auth.userId", "7");
        vi.stubGlobal("BroadcastChannel", ChannelStub);
        unseenTotal = 3;
        renderApp("/");
        await act(async () => {
          for (let i = 0; i < 20; i++) await Promise.resolve();
        });
        act(() => makeLive(lastSocket()));
        await act(async () => {
          await vi.advanceTimersByTimeAsync(100);
        });
        expect(document.title).toMatch(/^\(3\) /);
        setTabVisible(false);
        // Five minutes to quiet, then every minute answered (a silent socket would fail at the next tick) up to the 8-hour close.
        await act(async () => {
          await vi.advanceTimersByTimeAsync(5 * 60_000);
        });
        for (let minute = 0; minute < 8 * 60 - 5 + 1; minute++) {
          await act(async () => {
            await vi.advanceTimersByTimeAsync(60_000);
          });
          act(() => lastSocket().receive({ type: "pong" }));
        }
        expect(lastSocket().close).toHaveBeenCalledWith(1000);
        expect(document.title).not.toMatch(/^\(/);
        const before = calls.length;
        act(() => new ChannelStub("adder.seen.7").postMessage({ v: 1, userId: 7 }));
        await act(async () => {
          await vi.advanceTimersByTimeAsync(70_000);
        });
        expect(calls.length, "no request for a message an idle tab cannot use").toBe(before);
        setTabVisible(true);
        await act(async () => {
          await vi.advanceTimersByTimeAsync(1_500);
        });
        expect(SocketStub.instances.length).toBeGreaterThan(1);
      } finally {
        vi.useRealTimers();
      }
    });

    test("another tab's 'notices seen' re-reads the bell and the title follows; another user's message is ignored", async () => {
      localStorage.setItem("adder.auth.userId", "7");
      vi.stubGlobal("BroadcastChannel", ChannelStub);
      unseenTotal = 3;
      renderApp("/");
      await vi.waitFor(() => expect(SocketStub.instances).toHaveLength(1));
      await screen.findByRole("button", { name: "Notifications (3 unseen)" });
      act(() => makeLive(lastSocket()));
      await waitFor(() => expect(document.title).toMatch(/^\(3\) /));
      expect(ChannelStub.names).toEqual(["adder.seen.7"]);
      unseenTotal = 0;
      act(() => ChannelStub.open[0].deliver({ v: 1, userId: 8 }));
      await new Promise((resolve) => setTimeout(resolve, 50));
      expect(document.title).toMatch(/^\(3\) /);
      // The other tab: a second channel of the same name.
      const otherTab = new ChannelStub("adder.seen.7");
      act(() => otherTab.postMessage({ v: 1, userId: 7 }));
      await screen.findByRole("button", { name: "Notifications (0 unseen)" });
      await waitFor(() => expect(document.title).not.toMatch(/^\(/));
    });
  });
});
