import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { screen, waitFor, within } from "@testing-library/react";
import ThreadPane from "./ThreadPane";
import ThreadDraftsProvider from "./ThreadDraftsProvider";
import { jsonResponse } from "../test/http";
import { SOME_TIME, makeMessage, makeRoom, makeThread, messagePage } from "../test/messageFixtures";
import { renderWithProviders } from "../test/render";
import { browserTimeZone, formatDateTime } from "../utils/formatDate";

// "Quote" on a message and the reply box that takes it (the rest of the pane is in ThreadPane.test.tsx).
const WORKFLOW = {
  revision: 1,
  statuses: [{ key: "triage", label: "Triage", colour: "BLUE", category: "OPEN", initial: true, threadCount: 1 }],
  fields: [],
};
const WHEN = formatDateTime(SOME_TIME, browserTimeZone());
const MESSAGES = [
  makeMessage({ id: 2, authorId: 8, authorName: "Di Other", body: "Is the disk full on db-3?" }),
  makeMessage({ id: 1, authorId: 6, authorName: "Bob Starter", body: "the opening message" }),
];

describe("ThreadPane: quoting a message", () => {
  let mockFetch: ReturnType<typeof vi.fn>;
  const box = () => screen.getByRole("textbox", { name: "Reply" });
  const item = (text: string) => screen.getByText(text).closest("li")!;
  const posts = () => mockFetch.mock.calls.filter(([, init]) => (init as RequestInit | undefined)?.method === "POST") as [string, RequestInit][];

  function serve(room = makeRoom(), thread = makeThread()) {
    mockFetch.mockImplementation((url: string, init?: RequestInit) => {
      const detail = /^\/api\/v1\/rooms\/12\/threads\/(\d+)$/.exec(url);
      if (detail) return Promise.resolve(jsonResponse(200, { ...thread, number: Number(detail[1]) }));
      if (url === "/api/v1/rooms/12/threads/42/messages" && init?.method === "POST") return Promise.resolve(jsonResponse(201, makeMessage({ id: 3, body: "Agreed" })));
      if (init?.method === "PUT") return Promise.resolve(new Response(null, { status: 204 }));
      if (url === "/api/v1/rooms/12/workflow") return Promise.resolve(jsonResponse(200, WORKFLOW));
      if (url.startsWith("/api/v1/rooms/12/threads/42/messages?")) return Promise.resolve(jsonResponse(200, messagePage(MESSAGES)));
      if (url.startsWith("/api/v1/rooms/12/threads/43/messages?")) return Promise.resolve(jsonResponse(200, messagePage([])));
      if (url.includes("/events?")) return Promise.resolve(jsonResponse(200, { items: [], page: 1, pageSize: 10, total: 0 }));
      if (url.startsWith("/api/v1/rooms/12/pins?")) return Promise.resolve(jsonResponse(200, { items: [], page: 1, pageSize: 50, total: 0 }));
      return Promise.resolve(jsonResponse(404, { title: "Not found", status: 404 }));
    });
    return room;
  }
  const ui = (number: string, room = makeRoom()) => (
    <ThreadDraftsProvider>
      <ThreadPane room={room} numberParam={number} />
    </ThreadDraftsProvider>
  );

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

  test("the icon button quotes the message into the reply box and moves the focus there", async () => {
    serve();
    const user = userEvent.setup();
    renderWithProviders(ui("42"));
    await screen.findByText("Is the disk full on db-3?");
    await user.click(within(item("Is the disk full on db-3?")).getByRole("button", { name: /^Quote the message by/ }));
    expect(await screen.findByText("Quoting Di Other", { selector: "p" })).toBeInTheDocument();
    await waitFor(() => expect(box()).toHaveFocus());
  });

  test("the kebab menu's Quote does the same, and the focus is still in the box once the menu has closed and given it back", async () => {
    serve();
    const user = userEvent.setup();
    renderWithProviders(ui("42"));
    await screen.findByText("Is the disk full on db-3?");
    await user.click(within(item("Is the disk full on db-3?")).getByRole("button", { name: `Operations for message by Di Other on ${WHEN}` }));
    await user.click(await screen.findByRole("menuitem", { name: "Quote" }));
    expect(await screen.findByText("Quoting Di Other", { selector: "p" })).toBeInTheDocument();
    await waitFor(() => expect(box()).toHaveFocus());
    // Mantine's focus return fires after 10 ms and only if the focus has not moved: wait past it and look again.
    await new Promise((resolve) => setTimeout(resolve, 120));
    expect(box()).toHaveFocus();
  });

  test("the quote goes with the reply, and Send posts the quoted message's id", async () => {
    serve();
    const user = userEvent.setup();
    renderWithProviders(ui("42"));
    await screen.findByText("Is the disk full on db-3?");
    await user.click(within(item("Is the disk full on db-3?")).getByRole("button", { name: /^Quote the message by/ }));
    await user.type(await screen.findByRole("textbox", { name: "Reply" }), "Agreed");
    await user.click(screen.getByRole("button", { name: "Send" }));
    await waitFor(() => expect(posts()).toHaveLength(1));
    expect(JSON.parse(posts()[0][1].body as string)).toEqual({ body: "Agreed", quotedMessageId: 2 });
  });

  test("another thread has no quote, and the first thread's quote is back when the reader returns, without taking the focus", async () => {
    serve();
    const user = userEvent.setup();
    const { rerender } = renderWithProviders(ui("42"));
    await screen.findByText("Is the disk full on db-3?");
    await user.click(within(item("Is the disk full on db-3?")).getByRole("button", { name: /^Quote the message by/ }));
    await screen.findByText("Quoting Di Other", { selector: "p" });
    rerender(ui("43"));
    await waitFor(() => expect(screen.getByRole("region", { name: "#43 Disk full on db-3" })).toBeInTheDocument());
    expect(screen.queryByText("Quoting Di Other", { selector: "p" })).not.toBeInTheDocument();
    rerender(ui("42"));
    expect(await screen.findByText("Quoting Di Other", { selector: "p" })).toBeInTheDocument();
    await new Promise((resolve) => setTimeout(resolve, 100));
    expect(box()).not.toHaveFocus();
  });

  test("a closed thread, an archived room and a reader who is not a member are offered no Quote", async () => {
    serve(makeRoom(), makeThread({ closed: true }));
    const { unmount } = renderWithProviders(ui("42"));
    await screen.findByText("Is the disk full on db-3?");
    expect(screen.queryByRole("button", { name: "Quote" })).not.toBeInTheDocument();
    unmount();
    serve(makeRoom({ archived: true }));
    const archived = renderWithProviders(ui("42", makeRoom({ archived: true })));
    await screen.findByText("Is the disk full on db-3?");
    expect(screen.queryByRole("button", { name: "Quote" })).not.toBeInTheDocument();
    archived.unmount();
    serve(makeRoom({ role: null }));
    renderWithProviders(ui("42", makeRoom({ role: null })));
    await screen.findByText("Is the disk full on db-3?");
    expect(screen.queryByRole("button", { name: "Quote" })).not.toBeInTheDocument();
  });
});
