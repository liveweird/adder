import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { MantineProvider } from "@mantine/core";
import { MemoryRouter } from "react-router-dom";
import { act, render, screen, waitFor, within } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import App from "./App";
import { jsonResponse } from "./test/http";
import { lastSocket, makeLive, SocketStub } from "./test/socket";

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
});
