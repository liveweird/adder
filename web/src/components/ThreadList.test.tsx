import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
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
  statuses: [
    { key: "triage", label: "Triage", colour: "BLUE", category: "OPEN", initial: true, threadCount: 2 },
    { key: "resolved", label: "Resolved", colour: "TEAL", category: "CLOSED", initial: false, threadCount: 0 },
  ],
  fields: [],
};
const AT = Date.UTC(2026, 5, 1, 9, 30);
const THREADS = {
  items: [
    { id: 1, number: 2, title: "Disk full on db-3", status: "triage", closed: false, ownerId: 5, ownerName: "Ann Owner", messageCount: 4, lastActivityAt: AT, createdAt: AT },
    { id: 2, number: 1, title: "Old alert", status: "mystery", closed: false, ownerId: 6, ownerName: "Bob", messageCount: 1, lastActivityAt: AT, createdAt: AT },
  ],
  page: 1,
  pageSize: 50,
  total: 2,
};

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

  test("lists the open threads, most recently active first, as whole-row links with number, title, status, owner, count and time", async () => {
    serve();
    renderWithProviders(<ThreadList room={ROOM} activeNumber={null} />);
    const nav = await screen.findByRole("navigation", { name: "Threads" });
    expect(listCalls()).toEqual(["/api/v1/rooms/12/threads?page=1&pageSize=50&sort=-lastActivityAt&closed=false"]);
    const first = within(nav).getByRole("link", { name: /Disk full on db-3/ });
    expect(first).toHaveAttribute("href", "/rooms/12/threads/2");
    expect(first).not.toHaveAttribute("aria-current");
    expect(first).toHaveTextContent("#2");
    expect(first).toHaveTextContent("Ann Owner");
    expect(first).toHaveTextContent("4 messages");
    expect(first).toHaveTextContent(formatDateTime(AT, browserTimeZone()));
    await waitFor(() => expect(first).toHaveTextContent("Triage"));
    // A status the workflow does not list shows its key; a single message is singular.
    const second = within(nav).getByRole("link", { name: /Old alert/ });
    expect(second).toHaveTextContent("mystery");
    expect(second).toHaveTextContent("1 message");
    expect(second).not.toHaveTextContent("1 messages");
  });

  test("the open thread is marked with aria-current", async () => {
    serve();
    renderWithProviders(<ThreadList room={ROOM} activeNumber={1} />);
    expect(await screen.findByRole("link", { name: /Old alert/ })).toHaveAttribute("aria-current", "page");
    expect(screen.getByRole("link", { name: /Disk full/ })).not.toHaveAttribute("aria-current");
  });

  test("Closed, All, and the order each change the request and persist", async () => {
    serve();
    const user = userEvent.setup();
    renderWithProviders(<ThreadList room={ROOM} activeNumber={null} />);
    await screen.findByRole("navigation", { name: "Threads" });
    await user.click(screen.getByRole("radio", { name: "Closed" }));
    await waitFor(() => expect(listCalls().at(-1)).toBe("/api/v1/rooms/12/threads?page=1&pageSize=50&sort=-lastActivityAt&closed=true"));
    await user.click(screen.getByRole("radio", { name: "All" }));
    await waitFor(() => expect(listCalls().at(-1)).toBe("/api/v1/rooms/12/threads?page=1&pageSize=50&sort=-lastActivityAt"));
    await user.click(screen.getByRole("combobox", { name: "Order threads by" }));
    await user.click(await screen.findByRole("option", { name: "Number" }));
    await waitFor(() => expect(listCalls().at(-1)).toBe("/api/v1/rooms/12/threads?page=1&pageSize=50&sort=number"));
    await user.click(screen.getByRole("combobox", { name: "Order threads by" }));
    await user.click(await screen.findByRole("option", { name: "Newest" }));
    await waitFor(() => expect(listCalls().at(-1)).toBe("/api/v1/rooms/12/threads?page=1&pageSize=50&sort=-createdAt"));
    expect(localStorage.getItem("adder.viewSettings.roomThreads.12.scope")).toBe('"all"');
    expect(localStorage.getItem("adder.viewSettings.roomThreads.12.order")).toBe('"newest"');
  });

  test("a member of a live room sees New thread; an ADMIN outside the room and an archived room do not", async () => {
    serve();
    const { unmount } = renderWithProviders(<ThreadList room={ROOM} activeNumber={null} />);
    expect(await screen.findByRole("link", { name: "New thread" })).toHaveAttribute("href", "/rooms/12/threads/new");
    unmount();
    const outside = renderWithProviders(<ThreadList room={{ ...ROOM, role: null }} activeNumber={null} />);
    await screen.findByRole("navigation", { name: "Threads" });
    expect(screen.queryByRole("link", { name: "New thread" })).not.toBeInTheDocument();
    outside.unmount();
    renderWithProviders(<ThreadList room={{ ...ROOM, archived: true }} activeNumber={null} />);
    await screen.findByRole("navigation", { name: "Threads" });
    expect(screen.queryByRole("link", { name: "New thread" })).not.toBeInTheDocument();
  });

  test.each([
    ["open", "No open threads", "Start one to get the conversation going."],
    ["all", "No threads yet", "Start one to get the conversation going."],
    // Starting a thread does not fill the Closed list: it says how threads get there.
    ["closed", "No closed threads", "Threads land here when they reach a closed status."],
  ])("an empty %s list says so, with its own next step", async (scope, label, hint) => {
    localStorage.setItem("adder.viewSettings.roomThreads.12.scope", JSON.stringify(scope));
    serve({ items: [], page: 1, pageSize: 50, total: 0 });
    renderWithProviders(<ThreadList room={ROOM} activeNumber={null} />);
    expect(await screen.findByText(label)).toBeInTheDocument();
    expect(screen.getByText(hint)).toBeInTheDocument();
    expect(screen.queryByRole("navigation", { name: "Threads" })).not.toBeInTheDocument();
  });

  test("the Closed hint is for everyone, a reader included; the start-one hint only for who can start", async () => {
    localStorage.setItem("adder.viewSettings.roomThreads.12.scope", JSON.stringify("closed"));
    serve({ items: [], page: 1, pageSize: 50, total: 0 });
    renderWithProviders(<ThreadList room={{ ...ROOM, role: null }} activeNumber={null} />);
    expect(await screen.findByText("Threads land here when they reach a closed status.")).toBeInTheDocument();
  });

  test("the scope and the order are kept per room", async () => {
    serve();
    const user = userEvent.setup();
    renderWithProviders(<ThreadList room={ROOM} activeNumber={null} />);
    await screen.findByRole("navigation", { name: "Threads" });
    await user.click(screen.getByRole("radio", { name: "Closed" }));
    expect(localStorage.getItem("adder.viewSettings.roomThreads.12.scope")).toBe('"closed"');
    expect(localStorage.getItem("adder.viewSettings.roomThreads.13.scope")).toBeNull();
  });

  test("a reader who cannot start threads gets no hint", async () => {
    serve({ items: [], page: 1, pageSize: 50, total: 0 });
    renderWithProviders(<ThreadList room={{ ...ROOM, role: null }} activeNumber={null} />);
    expect(await screen.findByText("No open threads")).toBeInTheDocument();
    expect(screen.queryByText("Start one to get the conversation going.")).not.toBeInTheDocument();
  });

  test("a failed read is an inline alert and no empty state", async () => {
    mockFetch.mockImplementation((url: string) =>
      Promise.resolve(url === "/api/v1/rooms/12/workflow" ? jsonResponse(200, WORKFLOW) : jsonResponse(500, { title: "Boom", status: 500 })),
    );
    renderWithProviders(<ThreadList room={ROOM} activeNumber={null} />);
    expect(await screen.findByText("Could not load the threads")).toBeInTheDocument();
    expect(screen.queryByText("No open threads")).not.toBeInTheDocument();
  });

  test("more than one page of threads shows the pager (no page-size picker) and pages through the list", async () => {
    serve({ ...THREADS, total: 120 });
    const user = userEvent.setup();
    renderWithProviders(<ThreadList room={ROOM} activeNumber={null} />);
    expect(await screen.findByText("120 total")).toBeInTheDocument();
    expect(screen.queryByLabelText("Rows per page", { selector: "input" })).not.toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "3" }));
    await waitFor(() => expect(listCalls().at(-1)).toContain("page=3&pageSize=50"));
  });

  test("a page emptied under the reader steps back to the first", async () => {
    mockFetch.mockImplementation((url: string) => {
      if (url.includes("page=2")) return Promise.resolve(jsonResponse(200, { items: [], page: 2, pageSize: 50, total: 60 }));
      if (url.startsWith("/api/v1/rooms/12/threads?")) return Promise.resolve(jsonResponse(200, { ...THREADS, total: 120 }));
      return Promise.resolve(jsonResponse(200, WORKFLOW));
    });
    const user = userEvent.setup();
    renderWithProviders(<ThreadList room={ROOM} activeNumber={null} />);
    await screen.findByText("120 total");
    await user.click(screen.getByRole("button", { name: "2" }));
    await waitFor(() => expect(listCalls().at(-1)).toContain("page=1&pageSize=50"));
    expect(await screen.findByRole("link", { name: /Disk full/ })).toBeInTheDocument();
  });

  test("a changed filter reads page 1 from its very first request, never the old page under the new filter", async () => {
    serve({ ...THREADS, total: 120 });
    const user = userEvent.setup();
    renderWithProviders(<ThreadList room={ROOM} activeNumber={null} />);
    await screen.findByText("120 total");
    await user.click(screen.getByRole("button", { name: "3" }));
    await waitFor(() => expect(listCalls().at(-1)).toContain("page=3&pageSize=50"));
    await user.click(screen.getByRole("radio", { name: "Closed" }));
    await waitFor(() => expect(listCalls().at(-1)).toContain("closed=true"));
    expect(listCalls().filter((url) => url.includes("closed=true") && url.includes("page=3"))).toEqual([]);
    expect(listCalls().at(-1)).toContain("page=1&pageSize=50");
  });

  test("while a new filter loads the previous rows stay, dimmed and busy, and an empty answer is only shown once it is the current one", async () => {
    const pending: { answer?: (response: Response) => void } = {};
    mockFetch.mockImplementation((url: string) => {
      if (url === "/api/v1/rooms/12/workflow") return Promise.resolve(jsonResponse(200, WORKFLOW));
      if (url.includes("closed=true")) return new Promise<Response>((resolve) => (pending.answer = resolve));
      return Promise.resolve(jsonResponse(200, THREADS));
    });
    const user = userEvent.setup();
    renderWithProviders(<ThreadList room={ROOM} activeNumber={null} />);
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
    localStorage.setItem("adder.viewSettings.roomThreads.12.scope", JSON.stringify("closed"));
    const pending: { answer?: (response: Response) => void } = {};
    mockFetch.mockImplementation((url: string) => {
      if (url === "/api/v1/rooms/12/workflow") return Promise.resolve(jsonResponse(200, WORKFLOW));
      if (url.includes("closed=true")) return Promise.resolve(jsonResponse(200, { items: [], page: 1, pageSize: 50, total: 0 }));
      return new Promise<Response>((resolve) => (pending.answer = resolve));
    });
    const user = userEvent.setup();
    renderWithProviders(<ThreadList room={ROOM} activeNumber={null} />);
    await screen.findByText("No closed threads");
    await user.click(screen.getByRole("radio", { name: "All" }));
    expect(await screen.findByRole("status", { name: "Loading…" })).toBeInTheDocument();
    expect(screen.queryByText("No closed threads")).not.toBeInTheDocument();
    pending.answer?.(jsonResponse(200, THREADS));
    expect(await screen.findByRole("link", { name: /Disk full/ })).toBeInTheDocument();
  });
});
