import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { screen, waitFor, within } from "@testing-library/react";
import { QueryClient } from "@tanstack/react-query";
import ThreadPane from "./ThreadPane";
import type { RoomResponse } from "../api/rooms";
import { headingOutline } from "../test/headings";
import { jsonResponse } from "../test/http";
import { makeMessage, messagePage } from "../test/messageFixtures";
import { renderWithProviders } from "../test/render";
import { SIDE_WORKFLOW } from "../test/sideFixtures";
import userEvent from "@testing-library/user-event";
import { browserTimeZone, formatDateTime } from "../utils/formatDate";
import { focusThreadPaneState } from "../utils/roomLinks";

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
  statuses: [{ key: "triage", label: "Triage", colour: "BLUE", category: "OPEN", initial: true, threadCount: 1 }],
  fields: [],
};
const STARTED = Date.UTC(2026, 5, 1, 9, 30);
const MOVED = Date.UTC(2026, 5, 2, 18, 5);
const THREAD = {
  id: 1,
  number: 42,
  title: "Disk full on db-3",
  status: "triage",
  closed: false,
  closedAt: null,
  ownerId: 5,
  ownerName: "Ann Owner",
  createdById: 6,
  createdByName: "Bob Starter",
  fields: {},
  fieldUserNames: {},
  messageCount: 3,
  revision: 1,
  lastActivityAt: MOVED,
  createdAt: STARTED,
  updatedAt: MOVED,
};

const MESSAGES = [makeMessage({ id: 2, authorName: "Di Other", body: "second" }), makeMessage({ id: 1, authorName: "Bob Starter", body: "the opening message" })];

describe("ThreadPane", () => {
  let mockFetch: ReturnType<typeof vi.fn>;
  const threadCalls = () => mockFetch.mock.calls.map(([url]) => url as string).filter((url) => url.includes("/threads/"));

  function serve(thread: Response | (() => Response)) {
    mockFetch.mockImplementation((url: string) => {
      if (url === "/api/v1/rooms/12/threads/42") return Promise.resolve(typeof thread === "function" ? thread() : thread);
      if (url === "/api/v1/rooms/12/workflow") return Promise.resolve(jsonResponse(200, WORKFLOW));
      if (url.startsWith("/api/v1/rooms/12/threads/42/messages?")) return Promise.resolve(jsonResponse(200, messagePage(MESSAGES)));
      if (url.startsWith("/api/v1/rooms/12/threads/42/events?")) return Promise.resolve(jsonResponse(200, { items: [], page: 1, pageSize: 10, total: 0 }));
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
    vi.restoreAllMocks();
    localStorage.clear();
  });

  test("the header: #number and title as the pane's heading, the status, owner, count and both times in the browser's zone", async () => {
    serve(jsonResponse(200, THREAD));
    renderWithProviders(<ThreadPane room={ROOM} numberParam="42" />);
    const region = await screen.findByRole("region", { name: "#42 Disk full on db-3" });
    expect(region).toHaveTextContent("Owner: Ann Owner");
    expect(region).toHaveTextContent("3 messages");
    const zone = browserTimeZone();
    expect(region).toHaveTextContent(`Started by Bob Starter on ${formatDateTime(STARTED, zone)}`);
    expect(region).toHaveTextContent(`Last activity ${formatDateTime(MOVED, zone)}`);
    await waitFor(() => expect(region).toHaveTextContent("Triage"));
    expect(headingOutline()).toEqual([
      [3, "#42 Disk full on db-3"],
      [4, "Status"],
      [4, "Owner"],
      [4, "Details"],
      [4, "History"],
    ]);
  });

  test("a number that names no thread is the not-found state, without a request", () => {
    serve(jsonResponse(200, THREAD));
    renderWithProviders(<ThreadPane room={ROOM} numberParam="abc" />);
    expect(screen.getByText("This thread does not exist.")).toBeInTheDocument();
    expect(threadCalls()).toEqual([]);
  });

  test("a 404 is the same not-found state", async () => {
    serve(jsonResponse(404, { title: "Not found", status: 404 }));
    renderWithProviders(<ThreadPane room={ROOM} numberParam="42" />);
    expect(await screen.findByText("This thread does not exist.")).toBeInTheDocument();
  });

  test("a 404 wins over a copy of the thread cached from an earlier visit", async () => {
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    queryClient.setQueryData(["rooms", "threads", 12, "detail", 42], THREAD);
    serve(jsonResponse(404, { title: "Not found", status: 404 }));
    renderWithProviders(<ThreadPane room={ROOM} numberParam="42" />, { queryClient });
    expect(await screen.findByText("This thread does not exist.")).toBeInTheDocument();
    expect(screen.queryByRole("region")).not.toBeInTheDocument();
  });

  test("a failed read is an inline alert", async () => {
    serve(jsonResponse(500, { title: "Boom", status: 500 }));
    renderWithProviders(<ThreadPane room={ROOM} numberParam="42" />);
    expect(await screen.findByText("Could not load the thread")).toBeInTheDocument();
    expect(screen.getByText("Load failed (500)")).toBeInTheDocument();
  });

  test("while loading, a spinner stands in", () => {
    mockFetch.mockImplementation(() => new Promise(() => undefined));
    renderWithProviders(<ThreadPane room={ROOM} numberParam="42" />);
    expect(screen.getByRole("status", { name: "Loading…" })).toBeInTheDocument();
  });

  test("after a navigation that asks for it (a list row, a started thread) the pane takes focus once its thread has loaded", async () => {
    serve(jsonResponse(200, THREAD));
    renderWithProviders(<ThreadPane room={ROOM} numberParam="42" />, { route: "/rooms/12/threads/42", state: focusThreadPaneState });
    const region = await screen.findByRole("region", { name: "#42 Disk full on db-3" });
    expect(region).toHaveAttribute("tabindex", "-1");
    await waitFor(() => expect(region).toHaveFocus());
  });

  test("a deep link or a reload (no focus request) leaves focus alone", async () => {
    serve(jsonResponse(200, THREAD));
    renderWithProviders(<ThreadPane room={ROOM} numberParam="42" />, { route: "/rooms/12/threads/42" });
    const region = await screen.findByRole("region", { name: "#42 Disk full on db-3" });
    expect(region).not.toHaveFocus();
  });

  test("a background refetch of the same thread does not pull focus back", async () => {
    serve(jsonResponse(200, THREAD));
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    renderWithProviders(
      <>
        <ThreadPane room={ROOM} numberParam="42" />
        <button>elsewhere</button>
      </>,
      { route: "/rooms/12/threads/42", state: focusThreadPaneState, queryClient },
    );
    const region = await screen.findByRole("region", { name: "#42 Disk full on db-3" });
    await waitFor(() => expect(region).toHaveFocus());
    screen.getByRole("button", { name: "elsewhere" }).focus();
    await queryClient.invalidateQueries({ queryKey: ["rooms", "threads", 12, "detail", 42] });
    expect(screen.getByRole("button", { name: "elsewhere" })).toHaveFocus();
  });

  test("a failed workflow read is an inline alert above the header; the thread still shows, its status as the bare key", async () => {
    mockFetch.mockImplementation((url: string) =>
      Promise.resolve(url === "/api/v1/rooms/12/threads/42" ? jsonResponse(200, THREAD) : jsonResponse(500, { title: "Boom", status: 500 })),
    );
    renderWithProviders(<ThreadPane room={ROOM} numberParam="42" />);
    expect(await screen.findByText("Could not load the room's statuses")).toBeInTheDocument();
    const region = screen.getByRole("region", { name: "#42 Disk full on db-3" });
    expect(region).toHaveTextContent("triage");
  });

  test("the conversation sits under the header: the messages oldest first, then the reply box; the outline stays the pane's one heading", async () => {
    serve(jsonResponse(200, THREAD));
    renderWithProviders(<ThreadPane room={ROOM} numberParam="42" />);
    const messages = await screen.findByRole("region", { name: "Messages" });
    expect(within(messages).getAllByRole("listitem").map((item) => item.textContent)).toEqual([
      expect.stringContaining("the opening message"),
      expect.stringContaining("second"),
    ]);
    expect(screen.getByRole("textbox", { name: "Reply" })).toBeInTheDocument();
    // The messages add no heading: the outline is the pane's h3 and the side panel's four h4s.
    expect(headingOutline().map(([level]) => level)).toEqual([3, 4, 4, 4, 4]);
  });

  test("a closed thread keeps the conversation and swaps the box for the note", async () => {
    serve(jsonResponse(200, { ...THREAD, closed: true, closedAt: MOVED }));
    renderWithProviders(<ThreadPane room={ROOM} numberParam="42" />);
    await screen.findByRole("region", { name: "Messages" });
    expect(screen.getByRole("note")).toHaveTextContent("This thread is closed");
    expect(screen.queryByRole("textbox", { name: "Reply" })).not.toBeInTheDocument();
  });

  test("posting a reply shows it at the bottom once the conversation is re-read, and scrolls there", async () => {
    vi.spyOn(HTMLElement.prototype, "scrollHeight", "get").mockReturnValue(700);
    const user = userEvent.setup();
    let posted = false;
    mockFetch.mockImplementation((url: string, init?: RequestInit) => {
      if (url === "/api/v1/rooms/12/threads/42") return Promise.resolve(jsonResponse(200, THREAD));
      if (url === "/api/v1/rooms/12/workflow") return Promise.resolve(jsonResponse(200, WORKFLOW));
      if (url === "/api/v1/rooms/12/threads/42/messages" && init?.method === "POST") {
        posted = true;
        return Promise.resolve(jsonResponse(201, makeMessage({ id: 3, authorName: "Me", body: "on it" })));
      }
      if (url.startsWith("/api/v1/rooms/12/threads/42/messages?")) {
        return Promise.resolve(jsonResponse(200, messagePage(posted ? [makeMessage({ id: 3, authorName: "Me", body: "on it" }), ...MESSAGES] : MESSAGES)));
      }
      return Promise.resolve(jsonResponse(404, { title: "Not found", status: 404 }));
    });
    renderWithProviders(<ThreadPane room={ROOM} numberParam="42" />);
    const region = await screen.findByRole("region", { name: "Messages" });
    region.scrollTop = 0;
    await user.type(screen.getByRole("textbox", { name: "Reply" }), "on it");
    await user.click(screen.getByRole("button", { name: "Send" }));
    await waitFor(() => expect(within(region).getAllByRole("listitem")).toHaveLength(3));
    expect(within(region).getAllByRole("listitem").at(-1)).toHaveTextContent("on it");
    await waitFor(() => expect(region.scrollTop).toBe(700));
  });

  test("an edit refused because the thread closed: the real refetch closes the thread, the editor goes, the alert stays under the message and the composer becomes the closed note", async () => {
    localStorage.setItem("adder.auth.userId", "7");
    const user = userEvent.setup();
    let closed = false;
    mockFetch.mockImplementation((url: string, init?: RequestInit) => {
      if (url === "/api/v1/rooms/12/threads/42") return Promise.resolve(jsonResponse(200, closed ? { ...THREAD, closed: true, closedAt: MOVED } : THREAD));
      if (url === "/api/v1/rooms/12/workflow") return Promise.resolve(jsonResponse(200, WORKFLOW));
      if (url === "/api/v1/rooms/12/messages/100" && init?.method === "PUT") {
        closed = true;
        return Promise.resolve(jsonResponse(409, { type: "urn:adder:problem:thread-closed", status: 409, title: "Closed" }));
      }
      if (url.startsWith("/api/v1/rooms/12/threads/42/messages?")) return Promise.resolve(jsonResponse(200, messagePage([makeMessage({ id: 100, body: "my words" })])));
      return Promise.resolve(jsonResponse(404, { title: "Not found", status: 404 }));
    });
    renderWithProviders(<ThreadPane room={ROOM} numberParam="42" />);
    await screen.findByText("my words");
    await user.click(screen.getByRole("button", { name: /^Operations for message by Cy Author/ }));
    await user.click(await screen.findByRole("menuitem", { name: "Edit" }));
    await user.type(screen.getByRole("textbox", { name: "Edit message" }), "!");
    await user.click(screen.getByRole("button", { name: "Save" }));
    const alert = "This thread was closed in the meantime, so its messages can no longer be changed.";
    expect(await screen.findByText(alert)).toBeInTheDocument();
    await waitFor(() => expect(screen.queryByRole("textbox", { name: "Edit message" })).not.toBeInTheDocument());
    expect(screen.getByText(alert)).toBeInTheDocument();
    expect(screen.getByText("my words")).toBeInTheDocument();
    expect(screen.getByRole("note")).toHaveTextContent("This thread is closed");
    expect(screen.queryByRole("button", { name: /^Operations for message by/ })).not.toBeInTheDocument();
  });

  test("below the three-column width the side panel is folded behind a Details toggle that opens and closes it", async () => {
    serve(jsonResponse(200, THREAD));
    renderWithProviders(<ThreadPane room={ROOM} numberParam="42" />);
    const toggle = await screen.findByRole("button", { name: "Details" });
    const panel = document.getElementById(toggle.getAttribute("aria-controls")!)!;
    expect(toggle).toHaveAttribute("aria-expanded", "false");
    expect(panel).toHaveAttribute("data-open", "false");
    expect(within(panel).getByRole("heading", { level: 4, name: "Status" })).toBeInTheDocument();
    await userEvent.click(toggle);
    expect(toggle).toHaveAttribute("aria-expanded", "true");
    expect(panel).toHaveAttribute("data-open", "true");
    await userEvent.click(toggle);
    expect(panel).toHaveAttribute("data-open", "false");
  });

  test("closing the thread from the side panel re-reads it: the header's status follows and the composer becomes the closed note", async () => {
    const user = userEvent.setup();
    let status = "triage";
    mockFetch.mockImplementation((url: string, init?: RequestInit) => {
      if (url === "/api/v1/rooms/12/threads/42/status" && init?.method === "PUT") {
        status = JSON.parse(init.body as string).status;
        return Promise.resolve(new Response(null, { status: 204 }));
      }
      if (url === "/api/v1/rooms/12/threads/42") return Promise.resolve(jsonResponse(200, { ...THREAD, status, closed: status === "done", closedAt: status === "done" ? MOVED : null }));
      if (url === "/api/v1/rooms/12/workflow") return Promise.resolve(jsonResponse(200, SIDE_WORKFLOW));
      if (url.startsWith("/api/v1/rooms/12/threads/42/messages?")) return Promise.resolve(jsonResponse(200, messagePage(MESSAGES)));
      if (url.startsWith("/api/v1/rooms/12/threads/42/events?")) return Promise.resolve(jsonResponse(200, { items: [], page: 1, pageSize: 10, total: 0 }));
      return Promise.resolve(jsonResponse(404, { title: "Not found", status: 404 }));
    });
    renderWithProviders(<ThreadPane room={{ ...ROOM, role: "OWNER" }} numberParam="42" />);
    const region = await screen.findByRole("region", { name: "#42 Disk full on db-3" });
    await user.click(await screen.findByRole("combobox", { name: "Thread status" }));
    await user.click(await screen.findByRole("option", { name: /Done/ }));
    await user.click(within(await screen.findByRole("dialog", { name: "Close this thread?" })).getByRole("button", { name: "Close thread" }));
    await waitFor(() => expect(screen.getByRole("note")).toHaveTextContent("This thread is closed"));
    expect(within(region).getAllByText("Done").length).toBeGreaterThan(0);
    expect(screen.getByRole("combobox", { name: "Thread status" })).toHaveValue("Done");
    expect(screen.queryByRole("textbox", { name: "Reply" })).not.toBeInTheDocument();
  });
});
