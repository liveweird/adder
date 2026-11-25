import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { Link, Route, Routes, useLocation, useParams } from "react-router-dom";
import { screen, waitFor, within } from "@testing-library/react";
import ThreadList from "./ThreadList";
import type { RoomResponse } from "../api/rooms";
import { jsonResponse } from "../test/http";
import { renderWithProviders } from "../test/render";
import { browserTimeZone, formatDateTime } from "../utils/formatDate";

const ROOM: RoomResponse = {
  id: 12,
  name: "Night Shift",
  description: null,
  accessType: "RESTRICTED_HIDDEN",
  archived: false,
  archivedAt: null,
  role: "MEMBER",
  memberCount: 3,
  ownerCount: 1,
  workflowRevision: 1,
  lastActivityAt: 1,
  createdAt: 1,
  updatedAt: 1,
};
const WORKFLOW = {
  revision: 1,
  statuses: [
    { key: "triage", label: "Triage", colour: "BLUE", category: "OPEN", initial: true, threadCount: 2 },
    { key: "resolved", label: "Resolved", colour: "TEAL", category: "CLOSED", initial: false, threadCount: 0 },
  ],
  fields: [],
};
const AT = Date.UTC(2026, 5, 1, 9, 30);
const THREADS = {
  items: [
    { id: 1, number: 2, title: "Disk full on db-3", status: "triage", closed: false, ownerId: 5, ownerName: "Ann Owner", messageCount: 4, lastActivityAt: AT, createdAt: AT, hashtags: [], tags: [] },
    { id: 2, number: 1, title: "Old alert", status: "mystery", closed: false, ownerId: 6, ownerName: "Bob", messageCount: 1, lastActivityAt: AT, createdAt: AT, hashtags: [], tags: [] },
  ],
  page: 1,
  pageSize: 50,
  total: 2,
};
const REMEMBERED = "adder.viewSettings.roomThreads.12.query";

/** The router's location, as text, beside the list: the list's state is the URL. */
function Where() {
  const { pathname, search } = useLocation();
  return <output aria-label="where">{pathname + search}</output>;
}

function renderList(room: RoomResponse = ROOM, route = "/rooms/12", activeNumber: number | null = null) {
  return renderWithProviders(
    <>
      <ThreadList room={room} activeNumber={activeNumber} />
      <Where />
    </>,
    { route },
  );
}

const where = () => screen.getByLabelText("where").textContent;

describe("ThreadList", () => {
  let mockFetch: ReturnType<typeof vi.fn>;
  const listCalls = () => mockFetch.mock.calls.map(([url]) => url as string).filter((url) => url.startsWith("/api/v1/rooms/12/threads?"));

  function serve(threads: unknown = THREADS) {
    mockFetch.mockImplementation((url: string) => {
      if (url.startsWith("/api/v1/rooms/12/threads?")) return Promise.resolve(jsonResponse(200, threads));
      if (url === "/api/v1/rooms/12/workflow") return Promise.resolve(jsonResponse(200, WORKFLOW));
      return Promise.resolve(jsonResponse(404, { title: "Not found", status: 404 }));
    });
  }

  beforeEach(() => {
    mockFetch = vi.fn();
    vi.stubGlobal("fetch", mockFetch);
    localStorage.setItem("adder.auth.token", "fake-token");
  });
  afterEach(() => {
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  test("lists the open threads with separate thread and owner links, most recently active first", async () => {
    serve();
    renderList();
    const nav = await screen.findByRole("navigation", { name: "Threads" });
    expect(listCalls()).toEqual(["/api/v1/rooms/12/threads?page=1&pageSize=50&sort=-lastActivityAt&closed=false"]);
    const first = within(nav).getByRole("link", { name: /Disk full on db-3/ });
    expect(first).toHaveAttribute("href", "/rooms/12/threads/2");
    expect(first).not.toHaveAttribute("aria-current");
    expect(first).toHaveTextContent("#2");
    expect(within(nav).getByRole("link", { name: "Ann Owner" })).toHaveAttribute("href", "/people/5");
    expect(first.closest("[data-thread-row]")).toHaveTextContent("4 messages");
    expect(first.closest("[data-thread-row]")).toHaveTextContent(formatDateTime(AT, browserTimeZone()));
    await waitFor(() => expect(first.closest("[data-thread-row]")).toHaveTextContent("Triage"));
    // A status the workflow does not list shows its key; a single message is singular.
    const second = within(nav).getByRole("link", { name: /Old alert/ });
    expect(second.closest("[data-thread-row]")).toHaveTextContent("mystery");
    expect(second.closest("[data-thread-row]")).toHaveTextContent("1 message");
    expect(second.closest("[data-thread-row]")).not.toHaveTextContent("1 messages");
  });

  test("the open thread is marked with aria-current", async () => {
    serve();
    renderList(ROOM, "/rooms/12/threads/1", 1);
    expect(await screen.findByRole("link", { name: /Old alert/ })).toHaveAttribute("aria-current", "page");
    expect(screen.getByRole("link", { name: /Disk full/ })).not.toHaveAttribute("aria-current");
  });

  test("the scope and the order come from the URL, and the request follows them", async () => {
    serve();
    renderList(ROOM, "/rooms/12?scope=closed&order=number");
    await screen.findByRole("navigation", { name: "Threads" });
    expect(listCalls()).toEqual(["/api/v1/rooms/12/threads?page=1&pageSize=50&sort=number&closed=true"]);
    expect(screen.getByRole("radio", { name: "Closed" })).toBeChecked();
    expect(screen.getByRole("combobox", { name: "Order threads by" })).toHaveValue("Number");
  });

  test("junk in the URL reads as the plain list", async () => {
    serve();
    renderList(ROOM, "/rooms/12?scope=bogus&order=sideways&page=-3");
    await screen.findByRole("navigation", { name: "Threads" });
    expect(listCalls()).toEqual(["/api/v1/rooms/12/threads?page=1&pageSize=50&sort=-lastActivityAt&closed=false"]);
  });

  test("Closed, All and the order each change the request and write the URL by replace, defaults left out", async () => {
    serve();
    const user = userEvent.setup();
    renderList();
    await screen.findByRole("navigation", { name: "Threads" });
    await user.click(screen.getByRole("radio", { name: "Closed" }));
    await waitFor(() => expect(listCalls().at(-1)).toBe("/api/v1/rooms/12/threads?page=1&pageSize=50&sort=-lastActivityAt&closed=true"));
    expect(where()).toBe("/rooms/12?scope=closed");
    await user.click(screen.getByRole("radio", { name: "All" }));
    await waitFor(() => expect(listCalls().at(-1)).toBe("/api/v1/rooms/12/threads?page=1&pageSize=50&sort=-lastActivityAt"));
    expect(where()).toBe("/rooms/12?scope=all");
    await user.click(screen.getByRole("combobox", { name: "Order threads by" }));
    await user.click(await screen.findByRole("option", { name: "Number" }));
    await waitFor(() => expect(listCalls().at(-1)).toBe("/api/v1/rooms/12/threads?page=1&pageSize=50&sort=number"));
    expect(where()).toBe("/rooms/12?scope=all&order=number");
    await user.click(screen.getByRole("combobox", { name: "Order threads by" }));
    await user.click(await screen.findByRole("option", { name: "Newest" }));
    await waitFor(() => expect(listCalls().at(-1)).toBe("/api/v1/rooms/12/threads?page=1&pageSize=50&sort=-createdAt"));
    await user.click(screen.getByRole("radio", { name: "Open" }));
    await user.click(screen.getByRole("combobox", { name: "Order threads by" }));
    await user.click(await screen.findByRole("option", { name: "Recent activity" }));
    await waitFor(() => expect(where()).toBe("/rooms/12"));
    expect(JSON.parse(localStorage.getItem(REMEMBERED) ?? "null")).toBe("");
  });

  test("the old per-room scope and order keys are no longer read", async () => {
    localStorage.setItem("adder.viewSettings.roomThreads.12.scope", JSON.stringify("closed"));
    localStorage.setItem("adder.viewSettings.roomThreads.12.order", JSON.stringify("number"));
    serve();
    renderList();
    await screen.findByRole("navigation", { name: "Threads" });
    expect(listCalls()).toEqual(["/api/v1/rooms/12/threads?page=1&pageSize=50&sort=-lastActivityAt&closed=false"]);
  });

  test("a bare URL gets the room's remembered query at once: the first request already carries it", async () => {
    localStorage.setItem(REMEMBERED, JSON.stringify("scope=closed&order=newest"));
    serve();
    renderList();
    await screen.findByRole("navigation", { name: "Threads" });
    expect(listCalls()).toEqual(["/api/v1/rooms/12/threads?page=1&pageSize=50&sort=-createdAt&closed=true"]);
    await waitFor(() => expect(where()).toBe("/rooms/12?scope=closed&order=newest"));
  });

  test("a member of a live room sees New thread; an ADMIN outside the room and an archived room do not", async () => {
    serve();
    const { unmount } = renderList();
    expect(await screen.findByRole("link", { name: "New thread" })).toHaveAttribute("href", "/rooms/12/threads/new");
    unmount();
    const outside = renderList({ ...ROOM, role: null });
    await screen.findByRole("navigation", { name: "Threads" });
    expect(screen.queryByRole("link", { name: "New thread" })).not.toBeInTheDocument();
    outside.unmount();
    renderList({ ...ROOM, archived: true });
    await screen.findByRole("navigation", { name: "Threads" });
    expect(screen.queryByRole("link", { name: "New thread" })).not.toBeInTheDocument();
  });

  test("the rows and New thread keep the list's keys: a thread opens on the page and the filter the reader chose", async () => {
    serve();
    renderList(ROOM, "/rooms/12?scope=all&order=number&page=2&message=9");
    const nav = await screen.findByRole("navigation", { name: "Threads" });
    expect(within(nav).getByRole("link", { name: /Disk full/ })).toHaveAttribute("href", "/rooms/12/threads/2?scope=all&order=number&page=2");
    expect(screen.getByRole("link", { name: "New thread" })).toHaveAttribute("href", "/rooms/12/threads/new?scope=all&order=number&page=2");
  });

  test.each([
    ["open", "No open threads", "Start one to get the conversation going."],
    ["all", "No threads yet", "Start one to get the conversation going."],
    // Starting a thread does not fill the Closed list: it says how threads get there.
    ["closed", "No closed threads", "Threads land here when they reach a closed status."],
  ])("an empty %s list says so, with its own next step", async (scope, label, hint) => {
    serve({ items: [], page: 1, pageSize: 50, total: 0 });
    renderList(ROOM, `/rooms/12?scope=${scope}`);
    expect(await screen.findByText(label)).toBeInTheDocument();
    expect(screen.getByText(hint)).toBeInTheDocument();
    expect(screen.queryByRole("navigation", { name: "Threads" })).not.toBeInTheDocument();
  });

  test("the Closed hint is for everyone, a reader included; the start-one hint only for who can start", async () => {
    serve({ items: [], page: 1, pageSize: 50, total: 0 });
    renderList({ ...ROOM, role: null }, "/rooms/12?scope=closed");
    expect(await screen.findByText("Threads land here when they reach a closed status.")).toBeInTheDocument();
  });

  test("the scope is kept per room: the remembered query is the room's own", async () => {
    serve();
    const user = userEvent.setup();
    renderList();
    await screen.findByRole("navigation", { name: "Threads" });
    await user.click(screen.getByRole("radio", { name: "Closed" }));
    expect(JSON.parse(localStorage.getItem(REMEMBERED) ?? "null")).toBe("scope=closed");
    expect(localStorage.getItem("adder.viewSettings.roomThreads.13.query")).toBeNull();
  });

  test("a reader who cannot start threads gets no hint", async () => {
    serve({ items: [], page: 1, pageSize: 50, total: 0 });
    renderList({ ...ROOM, role: null });
    expect(await screen.findByText("No open threads")).toBeInTheDocument();
    expect(screen.queryByText("Start one to get the conversation going.")).not.toBeInTheDocument();
  });

  test("a failed read is an inline alert and no empty state", async () => {
    mockFetch.mockImplementation((url: string) =>
      Promise.resolve(url === "/api/v1/rooms/12/workflow" ? jsonResponse(200, WORKFLOW) : jsonResponse(500, { title: "Boom", status: 500 })),
    );
    renderList();
    expect(await screen.findByText("Could not load the threads")).toBeInTheDocument();
    expect(screen.queryByText("No open threads")).not.toBeInTheDocument();
  });

  test("more than one page of threads shows the pager (no page-size picker) and pages through the list, the page in the URL", async () => {
    serve({ ...THREADS, total: 120 });
    const user = userEvent.setup();
    renderList();
    expect(await screen.findByText("120 total")).toBeInTheDocument();
    expect(screen.queryByLabelText("Rows per page", { selector: "input" })).not.toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "3" }));
    await waitFor(() => expect(listCalls().at(-1)).toContain("page=3&pageSize=50"));
    expect(where()).toBe("/rooms/12?page=3");
  });

  test("the page comes from the URL, and a page that is out of range steps back to the first", async () => {
    mockFetch.mockImplementation((url: string) => {
      if (url.includes("page=9")) return Promise.resolve(jsonResponse(200, { items: [], page: 9, pageSize: 50, total: 60 }));
      if (url.startsWith("/api/v1/rooms/12/threads?")) return Promise.resolve(jsonResponse(200, { ...THREADS, total: 120 }));
      return Promise.resolve(jsonResponse(200, WORKFLOW));
    });
    renderList(ROOM, "/rooms/12?scope=all&page=9");
    expect(await screen.findByRole("link", { name: /Disk full/ })).toBeInTheDocument();
    expect(listCalls()[0]).toContain("page=9&pageSize=50");
    expect(listCalls().at(-1)).toContain("page=1&pageSize=50");
    expect(where()).toBe("/rooms/12?scope=all");
  });

  test("a changed filter reads page 1 from its very first request, never the old page under the new filter", async () => {
    serve({ ...THREADS, total: 120 });
    const user = userEvent.setup();
    renderList();
    await screen.findByText("120 total");
    await user.click(screen.getByRole("button", { name: "3" }));
    await waitFor(() => expect(listCalls().at(-1)).toContain("page=3&pageSize=50"));
    await user.click(screen.getByRole("radio", { name: "Closed" }));
    await waitFor(() => expect(listCalls().at(-1)).toContain("closed=true"));
    expect(listCalls().filter((url) => url.includes("closed=true") && url.includes("page=3"))).toEqual([]);
    expect(listCalls().at(-1)).toContain("page=1&pageSize=50");
    expect(where()).toBe("/rooms/12?scope=closed");
  });

  test("while a new filter loads the previous rows stay, dimmed and busy, and an empty answer is only shown once it is the current one", async () => {
    const pending: { answer?: (response: Response) => void } = {};
    mockFetch.mockImplementation((url: string) => {
      if (url === "/api/v1/rooms/12/workflow") return Promise.resolve(jsonResponse(200, WORKFLOW));
      if (url.includes("closed=true")) return new Promise<Response>((resolve) => (pending.answer = resolve));
      return Promise.resolve(jsonResponse(200, THREADS));
    });
    const user = userEvent.setup();
    renderList();
    const nav = await screen.findByRole("navigation", { name: "Threads" });
    expect(nav).toHaveAttribute("aria-busy", "false");
    await user.click(screen.getByRole("radio", { name: "Closed" }));
    await waitFor(() => expect(screen.getByRole("navigation", { name: "Threads" })).toHaveAttribute("aria-busy", "true"));
    expect(screen.getByRole("link", { name: /Disk full on db-3/ })).toBeInTheDocument();
    expect(screen.queryByText("No closed threads")).not.toBeInTheDocument();
    pending.answer?.(jsonResponse(200, { items: [], page: 1, pageSize: 50, total: 0 }));
    expect(await screen.findByText("No closed threads")).toBeInTheDocument();
    expect(screen.queryByRole("navigation", { name: "Threads" })).not.toBeInTheDocument();
  });

  test("a filter that follows an empty answer shows the spinner, not a blank, while it loads", async () => {
    const pending: { answer?: (response: Response) => void } = {};
    mockFetch.mockImplementation((url: string) => {
      if (url === "/api/v1/rooms/12/workflow") return Promise.resolve(jsonResponse(200, WORKFLOW));
      if (url.includes("closed=true")) return Promise.resolve(jsonResponse(200, { items: [], page: 1, pageSize: 50, total: 0 }));
      return new Promise<Response>((resolve) => (pending.answer = resolve));
    });
    const user = userEvent.setup();
    renderList(ROOM, "/rooms/12?scope=closed");
    await screen.findByText("No closed threads");
    await user.click(screen.getByRole("radio", { name: "All" }));
    expect(await screen.findByRole("status", { name: "Loading…" })).toBeInTheDocument();
    expect(screen.queryByText("No closed threads")).not.toBeInTheDocument();
    pending.answer?.(jsonResponse(200, THREADS));
    expect(await screen.findByRole("link", { name: /Disk full/ })).toBeInTheDocument();
  });

  test("a room switch with the list still mounted shows the new room's own remembered query, and a room with none the plain list", async () => {
    localStorage.setItem(REMEMBERED, JSON.stringify("scope=closed"));
    localStorage.setItem("adder.viewSettings.roomThreads.13.query", JSON.stringify("order=newest"));
    mockFetch.mockImplementation((url: string) => {
      if (/^\/api\/v1\/rooms\/\d+\/threads\?/.test(url)) return Promise.resolve(jsonResponse(200, THREADS));
      if (/^\/api\/v1\/rooms\/\d+\/workflow$/.test(url)) return Promise.resolve(jsonResponse(200, WORKFLOW));
      return Promise.resolve(jsonResponse(404, { title: "Not found", status: 404 }));
    });
    const calls = (room: number) => mockFetch.mock.calls.map(([url]) => url as string).filter((url) => url.startsWith(`/api/v1/rooms/${room}/threads?`));
    function Host() {
      const { id } = useParams();
      return <ThreadList room={{ ...ROOM, id: Number(id) }} activeNumber={null} />;
    }
    const user = userEvent.setup();
    renderWithProviders(
      <>
        <Routes>
          <Route path="/rooms/:id" element={<Host />} />
        </Routes>
        <Where />
        <Link to="/rooms/13">to room 13</Link>
        <Link to="/rooms/14">to room 14</Link>
      </>,
      { route: "/rooms/12" },
    );
    await screen.findByRole("navigation", { name: "Threads" });
    await waitFor(() => expect(where()).toBe("/rooms/12?scope=closed"));
    await user.click(screen.getByRole("link", { name: "to room 13" }));
    await waitFor(() => expect(where()).toBe("/rooms/13?order=newest"));
    await waitFor(() => expect(calls(13).at(-1)).toBe("/api/v1/rooms/13/threads?page=1&pageSize=50&sort=-createdAt&closed=false"));
    expect(calls(13).filter((url) => url.includes("closed=true"))).toEqual([]);
    await user.click(screen.getByRole("link", { name: "to room 14" }));
    await waitFor(() => expect(calls(14).at(-1)).toBe("/api/v1/rooms/14/threads?page=1&pageSize=50&sort=-lastActivityAt&closed=false"));
    expect(where()).toBe("/rooms/14");
    expect(calls(14).filter((url) => url.includes("closed=true") || url.includes("sort=-createdAt"))).toEqual([]);
    expect(screen.getByRole("radio", { name: "Open" })).toBeChecked();
  });

  test("a placeholder answer that is empty does not step the page back: only the current page's own empty answer does", async () => {
    const pending: { answer?: (response: Response) => void } = {};
    mockFetch.mockImplementation((url: string) => {
      if (url === "/api/v1/rooms/12/workflow") return Promise.resolve(jsonResponse(200, WORKFLOW));
      // An odd answer (page 1 empty, yet a total past one page) stands in for "an empty previous answer that is on screen as the placeholder".
      if (url.includes("page=2")) return new Promise<Response>((resolve) => (pending.answer = resolve));
      return Promise.resolve(jsonResponse(200, { items: [], page: 1, pageSize: 50, total: 60 }));
    });
    const user = userEvent.setup();
    renderList(ROOM, "/rooms/12?scope=all");
    await screen.findByText("60 total");
    await user.click(screen.getByRole("button", { name: "2" }));
    await waitFor(() => expect(listCalls().at(-1)).toContain("page=2&pageSize=50"));
    await waitFor(() => expect(pending.answer).toBeDefined());
    // Page 2 is still loading and the empty page-1 answer is the placeholder: nothing may write page 1 back.
    expect(where()).toBe("/rooms/12?scope=all&page=2");
    pending.answer?.(jsonResponse(200, { ...THREADS, page: 2, total: 60 }));
    expect(await screen.findByRole("link", { name: /Disk full/ })).toBeInTheDocument();
    expect(where()).toBe("/rooms/12?scope=all&page=2");
    expect(listCalls().filter((url) => url.includes("page=1&"))).toHaveLength(1);
  });

  test("a pinned thread's row carries a mark, described to assistive technology, and its name stays the thread's", async () => {
    mockFetch.mockImplementation((url: string) => {
      if (url.startsWith("/api/v1/rooms/12/pins?")) {
        const pin = { id: 3, kind: "THREAD", threadNumber: 2, threadTitle: "Disk full on db-3", threadStatus: "triage", threadClosed: false, message: null, pinnedById: 5, pinnedByName: "Ann Owner", pinnedAt: AT };
        return Promise.resolve(jsonResponse(200, { items: [pin], page: 1, pageSize: 50, total: 1 }));
      }
      if (url.startsWith("/api/v1/rooms/12/threads?")) return Promise.resolve(jsonResponse(200, THREADS));
      if (url === "/api/v1/rooms/12/workflow") return Promise.resolve(jsonResponse(200, WORKFLOW));
      return Promise.resolve(jsonResponse(404, { title: "Not found", status: 404 }));
    });
    renderList();
    const pinned = await screen.findByRole("link", { name: /Disk full on db-3/ });
    await waitFor(() => expect(pinned).toHaveAccessibleDescription("Pinned"));
    // The mark is a description (as the rail's are), so the link is still found by the thread's own name.
    expect(screen.queryByRole("link", { name: /Pinned/ })).not.toBeInTheDocument();
    expect(screen.getByRole("link", { name: /Old alert/ })).not.toHaveAccessibleDescription("Pinned");
    // The visible mark (1.2 D5): a "Pinned" badge with the pin shape beside the status pill, hidden from the accessibility tree (the description
    // above says it once), on the pinned row only.
    const row = pinned.closest("[data-thread-row]")!;
    const badge = within(row as HTMLElement).getByText("Pinned");
    expect(badge.closest("[data-pin-mark]")).toHaveAttribute("aria-hidden", "true");
    expect(document.querySelectorAll("[data-pin-mark]")).toHaveLength(1);
    expect(within(screen.getByRole("link", { name: /Old alert/ }).closest("[data-thread-row]") as HTMLElement).queryByText("Pinned")).not.toBeInTheDocument();
  });

  test("a watched thread's row carries a mark, described in words, and a thread the caller does not watch carries none", async () => {
    mockFetch.mockImplementation((url: string) => {
      if (url.startsWith("/api/v1/rooms/12/threads?")) {
        return Promise.resolve(jsonResponse(200, { ...THREADS, items: THREADS.items.map((thread, index) => ({ ...thread, watching: index === 0 })) }));
      }
      if (url === "/api/v1/rooms/12/workflow") return Promise.resolve(jsonResponse(200, WORKFLOW));
      if (url.startsWith("/api/v1/rooms/12/pins?")) return Promise.resolve(jsonResponse(200, { items: [], page: 1, pageSize: 50, total: 0 }));
      return Promise.resolve(jsonResponse(404, { title: "Not found", status: 404 }));
    });
    renderList();
    const watched = await screen.findByRole("link", { name: /Disk full on db-3/ });
    await waitFor(() => expect(watched).toHaveAccessibleDescription("You are watching this thread"));
    expect(screen.getByRole("link", { name: /Old alert/ })).not.toHaveAccessibleDescription("You are watching this thread");
  });

  test("a failed pins read marks nothing", async () => {
    serve();
    renderList();
    const row = await screen.findByRole("link", { name: /Disk full on db-3/ });
    expect(row).not.toHaveAccessibleDescription("Pinned");
  });
});
