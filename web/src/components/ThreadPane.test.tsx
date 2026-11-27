import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { cleanup, screen, waitFor, within } from "@testing-library/react";
import { QueryClient } from "@tanstack/react-query";
import ThreadPane from "./ThreadPane";
import type { RoomResponse } from "../api/rooms";
import { headingOutline } from "../test/headings";
import { jsonResponse } from "../test/http";
import { makeMessage, messagePage } from "../test/messageFixtures";
import { renderWithProviders } from "../test/render";
import { messageHit } from "../test/searchFixtures";
import { SIDE_WORKFLOW } from "../test/sideFixtures";
import userEvent from "@testing-library/user-event";
import { useLocation, useNavigate, useNavigationType } from "react-router-dom";
import { browserTimeZone, formatDateTime } from "../utils/formatDate";
import { focusThreadPaneState, quoteReturnState } from "../utils/roomLinks";

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
  hashtags: [],
  tags: [],
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
      if (url.includes("/threads/42/messages/context?messageId=")) {
        const messageId = Number(new URL(url, "http://local").searchParams.get("messageId"));
        return Promise.resolve(jsonResponse(200, { messageId, items: [...MESSAGES].reverse(), older: false, newer: false }));
      }
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
    // Unmount while fetch is still stubbed: leaving a conversation sends the marker it was holding back.
    cleanup();
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
    localStorage.clear();
  });

  test("the header shows the thread summary and Details shows both times in the browser's zone", async () => {
    const user = userEvent.setup();
    serve(jsonResponse(200, THREAD));
    renderWithProviders(<ThreadPane room={ROOM} numberParam="42" />);
    const region = await screen.findByRole("region", { name: "#42 Disk full on db-3" });
    expect(region).toHaveTextContent("Owner: Ann Owner");
    expect(within(screen.getByText(/^Owner:/)).getByRole("link", { name: "Ann Owner" })).toHaveAttribute("href", "/people/5");
    expect(region).toHaveTextContent("3 messages");
    await user.click(within(region).getByRole("button", { name: "Details" }));
    const details = within(region).getByRole("group", { name: "Details" });
    const started = within(details).getByText(/^Started by/);
    expect(within(started).getByRole("link", { name: "Bob Starter" })).toHaveAttribute("href", "/people/6");
    const zone = browserTimeZone();
    expect(details).toHaveTextContent(`Started by Bob Starter on ${formatDateTime(STARTED, zone)}`);
    expect(details).toHaveTextContent(`Last activity ${formatDateTime(MOVED, zone)}`);
    await waitFor(() => expect(region).toHaveTextContent("Triage"));
    expect(headingOutline()).toEqual([
      [3, "#42 Disk full on db-3"],
      [4, "Owner"],
      [4, "Details"],
      [4, "Tags"],
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
    // The closed thread refuses the message's own writes, not a pin: the menu is left with "Pin" alone.
    await user.click(screen.getByRole("button", { name: /^Operations for message by/ }));
    expect(await screen.findByRole("menuitem", { name: "Pin" })).toBeInTheDocument();
    expect(screen.queryByRole("menuitem", { name: "Edit" })).not.toBeInTheDocument();
    expect(screen.queryByRole("menuitem", { name: "Delete" })).not.toBeInTheDocument();
  });

  describe("the focus view and the message to jump to live in the URL", () => {
    function Probe() {
      const { pathname, search, state } = useLocation();
      const navigationType = useNavigationType();
      return <output aria-label="location" data-navigation={navigationType} data-state={JSON.stringify(state)}>{`${pathname}${search}`}</output>;
    }
    const renderPane = (route: string, options: { state?: unknown; queryClient?: QueryClient; numberParam?: string } = {}) =>
      renderWithProviders(
        <>
          <ThreadPane room={ROOM} numberParam={options.numberParam ?? "42"} />
          <Probe />
        </>,
        { route, state: options.state, queryClient: options.queryClient },
      );

    test("the toggle writes focus=1 by replace, keeps the other keys, is aria-pressed, and writing again removes it", async () => {
      const user = userEvent.setup();
      serve(jsonResponse(200, THREAD));
      renderPane("/rooms/12/threads/42?scope=all&message=2");
      const toggle = await screen.findByRole("button", { name: "Focus on this thread" });
      expect(toggle).toHaveAttribute("aria-pressed", "false");
      expect(screen.getByLabelText("location")).toHaveAttribute("data-navigation", "POP");
      await user.click(toggle);
      await waitFor(() => expect(toggle).toHaveAttribute("aria-pressed", "true"));
      expect(screen.getByLabelText("location")).toHaveTextContent("/rooms/12/threads/42?scope=all&message=2&focus=1");
      // A replace, never a push: Back does not step through the layout switch.
      expect(screen.getByLabelText("location")).toHaveAttribute("data-navigation", "REPLACE");
      await user.click(toggle);
      await waitFor(() => expect(toggle).toHaveAttribute("aria-pressed", "false"));
      expect(screen.getByLabelText("location")).toHaveTextContent("/rooms/12/threads/42?scope=all&message=2");
      expect(screen.getByLabelText("location")).toHaveAttribute("data-navigation", "REPLACE");
    });

    test("pressing the toggle leaves the focus on it, even after a navigation that had asked the pane for focus", async () => {
      const user = userEvent.setup();
      serve(jsonResponse(200, THREAD));
      renderPane("/rooms/12/threads/42", { state: focusThreadPaneState });
      const region = await screen.findByRole("region", { name: "#42 Disk full on db-3" });
      await waitFor(() => expect(region).toHaveFocus());
      const toggle = screen.getByRole("button", { name: "Focus on this thread" });
      await user.click(toggle);
      await waitFor(() => expect(toggle).toHaveAttribute("aria-pressed", "true"));
      expect(toggle).toHaveFocus();
    });

    test("a replace that keeps the pane-focus state (a list filter write) does not pull the focus into the pane either", async () => {
      const user = userEvent.setup();
      serve(jsonResponse(200, THREAD));
      function FilterWrite() {
        const navigate = useNavigate();
        return <button onClick={() => navigate("?scope=all", { replace: true, state: focusThreadPaneState })}>write filter</button>;
      }
      renderWithProviders(
        <>
          <ThreadPane room={ROOM} numberParam="42" />
          <FilterWrite />
        </>,
        { route: "/rooms/12/threads/42", state: focusThreadPaneState },
      );
      const region = await screen.findByRole("region", { name: "#42 Disk full on db-3" });
      await waitFor(() => expect(region).toHaveFocus());
      const write = screen.getByRole("button", { name: "write filter" });
      await user.click(write);
      expect(write).toHaveFocus();
    });

    test("a missing thread in the focus view offers the way back to the list, which turns the view off", async () => {
      const user = userEvent.setup();
      serve(jsonResponse(404, { title: "Not found", status: 404 }));
      renderPane("/rooms/12/threads/42?focus=1&scope=all");
      expect(await screen.findByText("This thread does not exist.")).toBeInTheDocument();
      await user.click(screen.getByRole("button", { name: "Show the thread list" }));
      expect(screen.getByLabelText("location")).toHaveTextContent("/rooms/12/threads/42?scope=all");
      expect(screen.queryByRole("button", { name: "Show the thread list" })).not.toBeInTheDocument();
    });

    test("a thread that fails to load in the focus view offers it too; outside the view there is no such button", async () => {
      serve(jsonResponse(500, { title: "Boom", status: 500 }));
      const { unmount } = renderPane("/rooms/12/threads/42?focus=1");
      expect(await screen.findByText("Could not load the thread")).toBeInTheDocument();
      expect(screen.getByRole("button", { name: "Show the thread list" })).toBeInTheDocument();
      unmount();
      renderPane("/rooms/12/threads/42");
      expect(await screen.findByText("Could not load the thread")).toBeInTheDocument();
      expect(screen.queryByRole("button", { name: "Show the thread list" })).not.toBeInTheDocument();
    });

    test("a URL that already says focus=1 shows the toggle pressed", async () => {
      serve(jsonResponse(200, THREAD));
      renderPane("/rooms/12/threads/42?focus=1");
      expect(await screen.findByRole("button", { name: "Focus on this thread" })).toHaveAttribute("aria-pressed", "true");
    });

    test("?message= makes the conversation jump to that message", async () => {
      serve(jsonResponse(200, THREAD));
      renderPane("/rooms/12/threads/42?message=1");
      const first = await screen.findByText("the opening message");
      await waitFor(() => expect(first.closest("li")).toHaveFocus());
      expect(first.closest("li")).toHaveAttribute("data-anchor", "true");
      expect(screen.queryByRole("button", { name: "Back to reply" })).not.toBeInTheDocument();
    });

    test("a quote jump returns to its containing reply, focuses it, and clears the transient return", async () => {
      serve(jsonResponse(200, THREAD));
      renderPane("/rooms/12/threads/42?message=1&scope=all", { state: quoteReturnState(12, 42, 2) });
      const first = (await screen.findByText("the opening message")).closest("li")!;
      await waitFor(() => expect(first).toHaveFocus());
      await userEvent.setup().click(screen.getByRole("button", { name: "Back to reply" }));
      const reply = screen.getByText("second").closest("li")!;
      await waitFor(() => expect(reply).toHaveFocus());
      expect(screen.getByLabelText("location")).toHaveTextContent("/rooms/12/threads/42?message=2&scope=all");
      expect(screen.queryByRole("button", { name: "Back to reply" })).not.toBeInTheDocument();
    });

    test("Back to latest clears the quote origin while retaining the conversation filters", async () => {
      serve(jsonResponse(200, THREAD));
      renderPane("/rooms/12/threads/42?message=1&scope=all&focus=1", { state: quoteReturnState(12, 42, 2) });
      expect(await screen.findByRole("button", { name: "Back to reply" })).toBeVisible();
      await userEvent.setup().click(screen.getByRole("button", { name: "Back to latest" }));
      const location = screen.getByLabelText("location");
      expect(location).toHaveTextContent("/rooms/12/threads/42?scope=all&focus=1");
      expect(location).toHaveAttribute("data-state", "null");
      expect(location).toHaveAttribute("data-navigation", "REPLACE");
    });

    test("a malformed message key jumps nowhere", async () => {
      serve(jsonResponse(200, THREAD));
      renderPane("/rooms/12/threads/42?message=abc");
      await screen.findByText("the opening message");
      expect(document.querySelector("[data-anchor]")).toBeNull();
      expect(screen.queryByRole("note")).not.toBeInTheDocument();
    });

    test("a jump wins over the pane's own focus request, also when the thread and its messages are already cached", async () => {
      serve(jsonResponse(200, THREAD));
      const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
      queryClient.setQueryData(["rooms", "threads", 12, "detail", 42], THREAD);
      queryClient.setQueryData(["rooms", "threads", 12, "messages", 42, "context", 1], {
        messageId: 1, items: [...MESSAGES].reverse(), older: false, newer: false,
      });
      renderPane("/rooms/12/threads/42?message=1", { state: focusThreadPaneState, queryClient });
      const first = (await screen.findByText("the opening message")).closest("li")!;
      await waitFor(() => expect(first).toHaveFocus());
      // Let every effect and the background re-reads settle: the section never takes the focus from the message.
      await waitFor(() => expect(queryClient.isFetching()).toBe(0));
      expect(first).toHaveFocus();
      expect(screen.getByRole("region", { name: "#42 Disk full on db-3" })).not.toHaveFocus();
    });


  });

  test("closing the thread from its header updates the status and replaces the reply box with the closed note", async () => {
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
    expect(within(region).getByRole("button", { name: "Details" })).toHaveAttribute("aria-expanded", "false");
    await user.click(await within(region).findByRole("button", { name: "Change status" }));
    await user.click(await screen.findByRole("menuitem", { name: "Close as Done" }));
    await user.click(within(await screen.findByRole("dialog", { name: "Close this thread?" })).getByRole("button", { name: "Close thread" }));
    await waitFor(() => expect(screen.getByRole("note")).toHaveTextContent("This thread is closed"));
    expect(within(region).getByRole("status", { name: "Thread status" })).toHaveTextContent("Done");
    expect(screen.queryByRole("textbox", { name: "Reply" })).not.toBeInTheDocument();
  });
});

test("Find in thread opens a focused submitted search and returns focus to the header control", async () => {
  const fetch = vi.fn((url: string) => {
    if (url === "/api/v1/rooms/12/threads/42") return Promise.resolve(jsonResponse(200, THREAD));
    if (url === "/api/v1/rooms/12/workflow") return Promise.resolve(jsonResponse(200, WORKFLOW));
    if (url.startsWith("/api/v1/rooms/12/threads/42/messages/search?")) {
      return Promise.resolve(jsonResponse(200, { items: [messageHit(501)], page: 1, pageSize: 20, total: 1 }));
    }
    if (url.startsWith("/api/v1/rooms/12/threads/42/messages?")) return Promise.resolve(jsonResponse(200, messagePage(MESSAGES)));
    if (url.startsWith("/api/v1/rooms/12/threads/42/events?")) return Promise.resolve(jsonResponse(200, { items: [], page: 1, pageSize: 10, total: 0 }));
    return Promise.resolve(jsonResponse(404, { title: "Not found", status: 404 }));
  });
  vi.stubGlobal("fetch", fetch);
  localStorage.setItem("adder.auth.token", "fake-token");
  const user = userEvent.setup();
  try {
    renderWithProviders(<ThreadPane room={ROOM} numberParam="42" />, { route: "/rooms/12/threads/42?scope=all" });
    const pane = await screen.findByRole("region", { name: "#42 Disk full on db-3" });
    const trigger = within(pane).getByRole("button", { name: "Find in thread" });
    await user.click(trigger);
    const input = within(pane).getByRole("searchbox", { name: "Search messages" });
    await waitFor(() => expect(input).toHaveFocus());
    await user.type(input, "outage{Enter}");
    expect(await within(pane).findByRole("link", { name: /the outage started/i })).toHaveAttribute("href", "/rooms/12/threads/42?message=501&scope=all");
    await user.click(within(pane).getByRole("button", { name: "Close search" }));
    await waitFor(() => expect(trigger).toHaveFocus());
  } finally {
    cleanup();
    vi.unstubAllGlobals();
    localStorage.clear();
  }
});

test("a deletion completed after switching threads cannot navigate away from the new pane", async () => {
  let finishDelete!: (response: Response) => void;
  const pendingDelete = new Promise<Response>((resolve) => { finishDelete = resolve; });
  const second = { ...THREAD, id: 2, number: 43, title: "Second thread" };
  const fetch = vi.fn((url: string, init?: RequestInit) => {
    if (url === "/api/v1/rooms/12/threads/42" && init?.method === "DELETE") return pendingDelete;
    if (url === "/api/v1/rooms/12/threads/42") return Promise.resolve(jsonResponse(200, THREAD));
    if (url === "/api/v1/rooms/12/threads/43") return Promise.resolve(jsonResponse(200, second));
    if (url === "/api/v1/rooms/12/workflow") return Promise.resolve(jsonResponse(200, WORKFLOW));
    if (url.includes("/messages?") || url.includes("/events?")) {
      return Promise.resolve(url.includes("/events?")
        ? jsonResponse(200, { items: [], page: 1, pageSize: 10, total: 0 })
        : jsonResponse(200, messagePage(MESSAGES)));
    }
    return Promise.resolve(jsonResponse(404, { title: "Not found", status: 404 }));
  });
  function Harness() {
    const location = useLocation();
    const navigate = useNavigate();
    const numberParam = location.pathname.split("/").at(-1);
    return (
      <>
        <button onClick={() => navigate("/rooms/12/threads/43?scope=closed")}>switch thread</button>
        <ThreadPane room={ROOM} numberParam={numberParam} />
        <output aria-label="location">{`${location.pathname}${location.search}`}</output>
      </>
    );
  }
  vi.stubGlobal("fetch", fetch);
  localStorage.setItem("adder.auth.token", "fake-token");
  localStorage.setItem("adder.auth.userId", "5");
  const user = userEvent.setup();
  try {
    renderWithProviders(<Harness />, { route: "/rooms/12/threads/42?scope=all" });
    await screen.findByRole("region", { name: "#42 Disk full on db-3" });
    await user.click(screen.getByRole("button", { name: "Thread actions" }));
    await user.click(screen.getByRole("menuitem", { name: "Delete thread" }));
    await user.click(within(await screen.findByRole("dialog", { name: "Delete #42?" })).getByRole("button", { name: "Delete thread" }));
    await waitFor(() => expect(fetch).toHaveBeenCalledWith(
      "/api/v1/rooms/12/threads/42",
      expect.objectContaining({ method: "DELETE" }),
    ));

    await user.click(screen.getByRole("button", { name: "switch thread" }));
    await screen.findByRole("region", { name: "#43 Second thread" });
    finishDelete(new Response(null, { status: 204 }));

    await waitFor(() => expect(fetch.mock.calls.filter(([url]) => url === "/api/v1/rooms/12/threads/43").length).toBeGreaterThan(1));
    expect(screen.getByLabelText("location")).toHaveTextContent("/rooms/12/threads/43?scope=closed");
  } finally {
    cleanup();
    vi.unstubAllGlobals();
    localStorage.clear();
  }
});

test("a held deletion returns to the room with the latest list filters", async () => {
  let finishDelete!: (response: Response) => void;
  const pendingDelete = new Promise<Response>((resolve) => { finishDelete = resolve; });
  const fetch = vi.fn((url: string, init?: RequestInit) => {
    if (url === "/api/v1/rooms/12/threads/42" && init?.method === "DELETE") return pendingDelete;
    if (url === "/api/v1/rooms/12/threads/42") return Promise.resolve(jsonResponse(200, THREAD));
    if (url === "/api/v1/rooms/12/workflow") return Promise.resolve(jsonResponse(200, WORKFLOW));
    if (url.includes("/messages?") || url.includes("/events?")) {
      return Promise.resolve(url.includes("/events?")
        ? jsonResponse(200, { items: [], page: 1, pageSize: 10, total: 0 })
        : jsonResponse(200, messagePage(MESSAGES)));
    }
    return Promise.resolve(jsonResponse(404, { title: "Not found", status: 404 }));
  });
  function Harness() {
    const location = useLocation();
    const navigate = useNavigate();
    return (
      <>
        <button onClick={() => navigate("?scope=closed&order=number")}>change filters</button>
        <ThreadPane room={ROOM} numberParam="42" />
        <output aria-label="location">{`${location.pathname}${location.search}`}</output>
      </>
    );
  }
  vi.stubGlobal("fetch", fetch);
  localStorage.setItem("adder.auth.token", "fake-token");
  localStorage.setItem("adder.auth.userId", "5");
  const user = userEvent.setup();
  try {
    renderWithProviders(<Harness />, { route: "/rooms/12/threads/42?scope=all" });
    await screen.findByRole("region", { name: "#42 Disk full on db-3" });
    await user.click(screen.getByRole("button", { name: "Thread actions" }));
    await user.click(screen.getByRole("menuitem", { name: "Delete thread" }));
    await user.click(within(await screen.findByRole("dialog", { name: "Delete #42?" })).getByRole("button", { name: "Delete thread" }));
    await user.click(screen.getByRole("button", { name: "change filters" }));

    finishDelete(new Response(null, { status: 204 }));

    await waitFor(() => expect(screen.getByLabelText("location")).toHaveTextContent("/rooms/12?scope=closed&order=number"));
  } finally {
    cleanup();
    vi.unstubAllGlobals();
    localStorage.clear();
  }
});

test("a merge completed after switching threads cannot navigate away from the new pane", async () => {
  let finishMerge!: (response: Response) => void;
  const pendingMerge = new Promise<Response>((resolve) => { finishMerge = resolve; });
  const second = { ...THREAD, id: 2, number: 43, title: "Second thread" };
  const target = { ...THREAD, id: 3, number: 7, title: "Canonical thread" };
  const fetch = vi.fn((url: string, init?: RequestInit) => {
    if (url === "/api/v1/rooms/12/threads/42/merge" && init?.method === "PUT") return pendingMerge;
    if (url === "/api/v1/rooms/12/threads/42") return Promise.resolve(jsonResponse(200, THREAD));
    if (url === "/api/v1/rooms/12/threads/43") return Promise.resolve(jsonResponse(200, second));
    if (url === "/api/v1/rooms/12/threads/7") return Promise.resolve(jsonResponse(200, target));
    if (url === "/api/v1/rooms/12/workflow") return Promise.resolve(jsonResponse(200, WORKFLOW));
    if (url.includes("/messages?") || url.includes("/events?")) {
      return Promise.resolve(url.includes("/events?")
        ? jsonResponse(200, { items: [], page: 1, pageSize: 10, total: 0 })
        : jsonResponse(200, messagePage(MESSAGES)));
    }
    return Promise.resolve(jsonResponse(404, { title: "Not found", status: 404 }));
  });
  function Harness() {
    const location = useLocation();
    const navigate = useNavigate();
    const numberParam = location.pathname.match(/\/threads\/(\d+)$/)?.[1];
    return (
      <>
        <button onClick={() => navigate("/rooms/12/threads/43?scope=closed")}>switch thread</button>
        <ThreadPane room={ROOM} numberParam={numberParam} />
        <output aria-label="location">{`${location.pathname}${location.search}`}</output>
      </>
    );
  }
  vi.stubGlobal("fetch", fetch);
  localStorage.setItem("adder.auth.token", "fake-token");
  localStorage.setItem("adder.auth.userId", "5");
  const user = userEvent.setup();
  try {
    renderWithProviders(<Harness />, { route: "/rooms/12/threads/42?scope=all" });
    await screen.findByRole("region", { name: "#42 Disk full on db-3" });
    await user.click(screen.getByRole("button", { name: "Thread actions" }));
    await user.click(screen.getByRole("menuitem", { name: "Merge duplicate" }));
    const dialog = await screen.findByRole("dialog", { name: "Merge duplicate thread" });
    await user.type(within(dialog).getByRole("textbox", { name: "Target thread number" }), "7");
    await user.click(await within(dialog).findByRole("button", { name: "Merge into #7" }));

    await user.click(screen.getByRole("button", { name: "switch thread" }));
    await screen.findByRole("region", { name: "#43 Second thread" });
    finishMerge(new Response(null, { status: 204 }));

    await waitFor(() => expect(fetch.mock.calls.filter(([url]) => url === "/api/v1/rooms/12/threads/43").length).toBeGreaterThan(1));
    expect(screen.getByLabelText("location")).toHaveTextContent("/rooms/12/threads/43?scope=closed");
  } finally {
    cleanup();
    vi.unstubAllGlobals();
    localStorage.clear();
  }
});

test("a held merge opens the destination with the latest list filters", async () => {
  let finishMerge!: (response: Response) => void;
  const pendingMerge = new Promise<Response>((resolve) => { finishMerge = resolve; });
  const target = { ...THREAD, id: 3, number: 7, title: "Canonical thread" };
  const fetch = vi.fn((url: string, init?: RequestInit) => {
    if (url === "/api/v1/rooms/12/threads/42/merge" && init?.method === "PUT") return pendingMerge;
    if (url === "/api/v1/rooms/12/threads/42") return Promise.resolve(jsonResponse(200, THREAD));
    if (url === "/api/v1/rooms/12/threads/7") return Promise.resolve(jsonResponse(200, target));
    if (url === "/api/v1/rooms/12/workflow") return Promise.resolve(jsonResponse(200, WORKFLOW));
    if (url.includes("/messages?") || url.includes("/events?")) {
      return Promise.resolve(url.includes("/events?")
        ? jsonResponse(200, { items: [], page: 1, pageSize: 10, total: 0 })
        : jsonResponse(200, messagePage(MESSAGES)));
    }
    return Promise.resolve(jsonResponse(404, { title: "Not found", status: 404 }));
  });
  function Harness() {
    const location = useLocation();
    const navigate = useNavigate();
    return (
      <>
        <button onClick={() => navigate({ search: "?scope=closed&order=number" })}>change filters</button>
        <ThreadPane room={ROOM} numberParam="42" />
        <output aria-label="location">{`${location.pathname}${location.search}`}</output>
      </>
    );
  }
  vi.stubGlobal("fetch", fetch);
  localStorage.setItem("adder.auth.token", "fake-token");
  localStorage.setItem("adder.auth.userId", "5");
  const user = userEvent.setup();
  try {
    renderWithProviders(<Harness />, { route: "/rooms/12/threads/42?scope=all" });
    await screen.findByRole("region", { name: "#42 Disk full on db-3" });
    await user.click(screen.getByRole("button", { name: "Thread actions" }));
    await user.click(screen.getByRole("menuitem", { name: "Merge duplicate" }));
    const dialog = await screen.findByRole("dialog", { name: "Merge duplicate thread" });
    await user.type(within(dialog).getByRole("textbox", { name: "Target thread number" }), "7");
    await user.click(await within(dialog).findByRole("button", { name: "Merge into #7" }));
    await user.click(screen.getByRole("button", { name: "change filters" }));

    finishMerge(new Response(null, { status: 204 }));

    await waitFor(() => expect(screen.getByLabelText("location"))
      .toHaveTextContent("/rooms/12/threads/7?scope=closed&order=number"));
  } finally {
    cleanup();
    vi.unstubAllGlobals();
    localStorage.clear();
  }
});

test("a post completed after switching threads cannot clear the new thread's message anchor", async () => {
  let finishPost!: (response: Response) => void;
  const pendingPost = new Promise<Response>((resolve) => { finishPost = resolve; });
  let thread43Reads = 0;
  const second = { ...THREAD, id: 2, number: 43, title: "Second thread" };
  const fetch = vi.fn((url: string, init?: RequestInit) => {
    if (url === "/api/v1/rooms/12/threads/42/messages" && init?.method === "POST") return pendingPost;
    if (url === "/api/v1/rooms/12/threads/43/messages" && init?.method === "POST") {
      return Promise.resolve(jsonResponse(201, makeMessage({ id: 901, threadNumber: 43, body: "new reply" })));
    }
    if (url === "/api/v1/rooms/12/threads/42") return Promise.resolve(jsonResponse(200, THREAD));
    if (url === "/api/v1/rooms/12/threads/43") {
      thread43Reads += 1;
      return Promise.resolve(jsonResponse(200, second));
    }
    if (url === "/api/v1/rooms/12/workflow") return Promise.resolve(jsonResponse(200, WORKFLOW));
    if (url.includes("/messages/context?messageId=1")) {
      return Promise.resolve(jsonResponse(200, { messageId: 1, items: [...MESSAGES].reverse(), older: false, newer: false }));
    }
    if (url.includes("/events?")) return Promise.resolve(jsonResponse(200, { items: [], page: 1, pageSize: 10, total: 0 }));
    return Promise.resolve(jsonResponse(404, { title: "Not found", status: 404 }));
  });
  function LocationProbe() {
    const location = useLocation();
    return <output aria-label="location">{`${location.pathname}${location.search}`}</output>;
  }
  vi.stubGlobal("fetch", fetch);
  localStorage.setItem("adder.auth.token", "fake-token");
  const user = userEvent.setup();
  try {
    const { rerender } = renderWithProviders(
      <><ThreadPane room={ROOM} numberParam="42" /><LocationProbe /></>,
      { route: "/rooms/12/threads/42?message=1&scope=all" },
    );
    await user.type(await screen.findByRole("textbox", { name: "Reply" }), "still sending");
    await user.click(screen.getByRole("button", { name: "Send" }));
    await waitFor(() => expect(fetch.mock.calls.some(([url, init]) => url === "/api/v1/rooms/12/threads/42/messages" && init?.method === "POST")).toBe(true));
    rerender(<><ThreadPane room={ROOM} numberParam="43" /><LocationProbe /></>);
    await screen.findByRole("region", { name: "#43 Second thread" });
    finishPost(jsonResponse(201, makeMessage({ id: 900, body: "still sending" })));
    await waitFor(() => expect(thread43Reads).toBeGreaterThan(1));
    expect(screen.getByLabelText("location")).toHaveTextContent("/rooms/12/threads/42?message=1&scope=all");
    await user.type(screen.getByRole("textbox", { name: "Reply" }), "new reply");
    await user.click(screen.getByRole("button", { name: "Send" }));
    await waitFor(() => expect(screen.getByLabelText("location")).toHaveTextContent("/rooms/12/threads/42?scope=all"));
  } finally {
    cleanup();
    vi.unstubAllGlobals();
    localStorage.clear();
  }
});

test("switching to another thread mid-jump starts the conversation clean: the new thread's own anchor is looked for there", async () => {
  const second = { ...THREAD, id: 2, number: 43, title: "Second thread" };
  const fetch = vi.fn((url: string) => {
    if (url === "/api/v1/rooms/12/threads/42") return Promise.resolve(jsonResponse(200, THREAD));
    if (url === "/api/v1/rooms/12/threads/43") return Promise.resolve(jsonResponse(200, second));
    if (url === "/api/v1/rooms/12/workflow") return Promise.resolve(jsonResponse(200, WORKFLOW));
    // The first thread's context is still in flight when the reader moves away.
    if (url.includes("/threads/42/messages/context?")) return new Promise<Response>(() => undefined);
    if (url.includes("/threads/43/messages/context?")) {
      return Promise.resolve(jsonResponse(200, {
        messageId: 5, items: [makeMessage({ id: 5, threadNumber: 43, body: "in forty-three" })], older: false, newer: false,
      }));
    }
    if (url.includes("/events?")) return Promise.resolve(jsonResponse(200, { items: [], page: 1, pageSize: 10, total: 0 }));
    return Promise.resolve(jsonResponse(404, { title: "Not found", status: 404 }));
  });
  vi.stubGlobal("fetch", fetch);
  localStorage.setItem("adder.auth.token", "fake-token");
  try {
    const { rerender } = renderWithProviders(<ThreadPane room={ROOM} numberParam="42" />, { route: "/rooms/12/threads/42?message=5" });
    await waitFor(() => expect(fetch.mock.calls.some(([url]) => String(url).endsWith("/threads/42/messages/context?messageId=5"))).toBe(true));
    rerender(<ThreadPane room={ROOM} numberParam="43" />);
    const target = (await screen.findByText("in forty-three")).closest("li")!;
    await waitFor(() => expect(target).toHaveFocus());
    expect(screen.queryByText("in forty-two")).not.toBeInTheDocument();
    expect(screen.queryByRole("note")).not.toBeInTheDocument();
  } finally {
    cleanup();
    vi.unstubAllGlobals();
    localStorage.clear();
  }
});
