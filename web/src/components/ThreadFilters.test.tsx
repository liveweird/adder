import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { useLocation } from "react-router-dom";
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
  statuses: [
    { key: "triage", label: "Triage", colour: "BLUE", category: "OPEN", initial: true, threadCount: 2 },
    { key: "fixing", label: "Fixing", colour: "ORANGE", category: "OPEN", initial: false, threadCount: 5 },
    { key: "resolved", label: "Resolved", colour: "TEAL", category: "CLOSED", initial: false, threadCount: 0 },
  ],
  fields: [
    { key: "severity", label: "Severity", type: "SELECT", required: false, options: [{ key: "s1", label: "Critical" }, { key: "s2", label: "Minor" }] },
    { key: "lead", label: "Lead", type: "USER", required: false, options: [] },
    { key: "notes", label: "Notes", type: "TEXT", required: false, options: [] },
    { key: "due", label: "Due", type: "DATE", required: false, options: [] },
  ],
};
const MEMBERS = {
  items: [
    { userId: 7, name: "Mia Manager", email: "mia@example.test", role: "OWNER", addedAt: 1 },
    { userId: 12, name: "Noa Member", email: "noa@example.test", role: "MEMBER", addedAt: 2 },
  ],
  page: 1,
  pageSize: 50,
  total: 2,
};
const THREADS = {
  items: [{ id: 1, number: 2, title: "Disk full", status: "triage", closed: false, ownerId: 5, ownerName: "Ann", messageCount: 4, lastActivityAt: 1, createdAt: 1, hashtags: [], tags: [] }],
  page: 1,
  pageSize: 50,
  total: 1,
};
const TAGS = {
  items: [
    { tag: "outage", threadCount: 4 },
    { tag: "wdrozenie", threadCount: 1 },
  ],
  page: 1,
  pageSize: 50,
  total: 2,
};
const EMPTY = { items: [], page: 1, pageSize: 50, total: 0 };

function Where() {
  const { pathname, search } = useLocation();
  return <output aria-label="where">{pathname + search}</output>;
}
const where = () => screen.getByLabelText("where").textContent;

describe("the thread filter panel", () => {
  let mockFetch: ReturnType<typeof vi.fn>;
  const listCalls = () => mockFetch.mock.calls.map(([url]) => url as string).filter((url) => url.startsWith("/api/v1/rooms/12/threads?"));
  const panelButton = () => screen.getByRole("button", { name: /Filters/ });

  function serve(threads: unknown = THREADS, workflow: () => Promise<Response> = () => Promise.resolve(jsonResponse(200, WORKFLOW))) {
    mockFetch.mockImplementation((url: string) => {
      if (url.startsWith("/api/v1/rooms/12/threads?")) return Promise.resolve(jsonResponse(200, threads));
      if (url === "/api/v1/rooms/12/workflow") return workflow();
      if (url.startsWith("/api/v1/rooms/12/members?")) return Promise.resolve(jsonResponse(200, MEMBERS));
      if (url.startsWith("/api/v1/rooms/12/tags?")) return Promise.resolve(jsonResponse(200, TAGS));
      return Promise.resolve(jsonResponse(404, { title: "Not found", status: 404 }));
    });
  }

  function renderPanel(route = "/rooms/12") {
    return renderWithProviders(
      <>
        <ThreadList room={ROOM} activeNumber={null} />
        <Where />
      </>,
      { route },
    );
  }

  /** Opens the (collapsed by default) panel once the list has loaded. */
  async function openPanel(user: ReturnType<typeof userEvent.setup>) {
    await screen.findByRole("navigation", { name: "Threads" });
    await user.click(panelButton());
  }

  beforeEach(() => {
    mockFetch = vi.fn();
    vi.stubGlobal("fetch", mockFetch);
    localStorage.setItem("adder.auth.token", "fake-token");
    localStorage.setItem("adder.auth.userId", "5");
  });
  afterEach(() => {
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  test("the status control lists the workflow's statuses with their thread counts, and a pick writes the URL and the request", async () => {
    serve();
    const user = userEvent.setup();
    renderPanel();
    await openPanel(user);
    const status = screen.getByRole("combobox", { name: "Status" });
    expect(status).toHaveAttribute("placeholder", "All");
    await user.click(status);
    expect(await screen.findByRole("option", { name: "Fixing (5)" })).toBeInTheDocument();
    await user.click(screen.getByRole("option", { name: "Triage (2)" }));
    await user.click(screen.getByRole("option", { name: "Fixing (5)" }));
    await waitFor(() => expect(listCalls().at(-1)).toBe("/api/v1/rooms/12/threads?page=1&pageSize=50&sort=-lastActivityAt&closed=false&status=triage&status=fixing"));
    expect(where()).toBe("/rooms/12?status=triage&status=fixing");
    // Each pill's remove button is a named control.
    await user.click(screen.getByRole("button", { name: "Remove Triage" }));
    await waitFor(() => expect(where()).toBe("/rooms/12?status=fixing"));
    expect(listCalls().at(-1)).toContain("status=fixing");
    expect(listCalls().at(-1)).not.toContain("status=triage");
  });

  test("the hashtag control follows the status control, and its picks write the repeated tag key and the request", async () => {
    serve();
    const user = userEvent.setup();
    renderPanel();
    await openPanel(user);
    const tags = screen.getByRole("combobox", { name: "Hashtags" });
    const follows = (before: HTMLElement, after: HTMLElement) => Boolean(before.compareDocumentPosition(after) & Node.DOCUMENT_POSITION_FOLLOWING);
    expect(follows(screen.getByRole("combobox", { name: "Status" }), tags)).toBe(true);
    expect(follows(tags, screen.getByRole("combobox", { name: "Owner" }))).toBe(true);
    expect(tags).toHaveAttribute("placeholder", "All");
    await user.click(tags);
    await user.click(await screen.findByRole("option", { name: "#outage (4)" }));
    await user.click(screen.getByRole("option", { name: "#wdrozenie (1)" }));
    await waitFor(() => expect(where()).toBe("/rooms/12?tag=outage&tag=wdrozenie"));
    expect(listCalls().at(-1)).toBe("/api/v1/rooms/12/threads?page=1&pageSize=50&sort=-lastActivityAt&closed=false&tag=outage&tag=wdrozenie");
    await user.click(screen.getByRole("button", { name: "Remove #outage" }));
    await waitFor(() => expect(where()).toBe("/rooms/12?tag=wdrozenie"));
    expect(listCalls().at(-1)).not.toContain("tag=outage");
  });

  test("a tag from a link is shown as typed, and Clear filters drops it with the rest", async () => {
    serve();
    const user = userEvent.setup();
    renderPanel("/rooms/12?scope=all&tag=Wdro%C5%BCenie&tag=a%201%2F2");
    await openPanel(user);
    expect(await screen.findByRole("button", { name: "Remove #Wdro\u017cenie" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Remove #a 1/2" })).toBeInTheDocument();
    expect(listCalls().at(-1)).toBe("/api/v1/rooms/12/threads?page=1&pageSize=50&sort=-lastActivityAt&tag=Wdro%C5%BCenie&tag=a+1%2F2");
    await user.click(screen.getByRole("button", { name: "Clear filters" }));
    await waitFor(() => expect(where()).toBe("/rooms/12?scope=all"));
    expect(listCalls().at(-1)).not.toContain("tag=");
    expect(JSON.parse(localStorage.getItem("adder.viewSettings.roomThreads.12.query") ?? "null")).toBe("scope=all");
  });

  test("the badge counts the filters in use: the statuses, the owner, the tags and each field", async () => {
    serve();
    renderPanel("/rooms/12?status=triage&status=fixing&owner=me&tag=outage&tag=wdrozenie&field=severity%3As1&field=severity%3As2&scope=all");
    await screen.findByRole("navigation", { name: "Threads" });
    // Two statuses are ONE filter; the owner one; the two tags one; the two severity values one: 4. The scope lives outside the panel.
    expect(within(panelButton()).getByText("4")).toBeInTheDocument();
    expect(listCalls().at(-1)).toBe(
      "/api/v1/rooms/12/threads?page=1&pageSize=50&sort=-lastActivityAt&status=triage&status=fixing&ownerId=5&field=severity%3As1&field=severity%3As2&tag=outage&tag=wdrozenie",
    );
  });

  test("with no filter in use the badge and Clear are absent", async () => {
    serve();
    const user = userEvent.setup();
    renderPanel();
    await openPanel(user);
    expect(within(panelButton()).queryByText(/^\d+$/)).toBeNull();
    expect(screen.queryByRole("button", { name: "Clear filters" })).toBeNull();
  });

  test("Mine sends the session's id as ownerId, is pressed while on, and toggles off", async () => {
    serve();
    const user = userEvent.setup();
    renderPanel();
    await openPanel(user);
    const mine = screen.getByRole("button", { name: "Mine" });
    expect(mine).toHaveAttribute("aria-pressed", "false");
    await user.click(mine);
    await waitFor(() => expect(listCalls().at(-1)).toBe("/api/v1/rooms/12/threads?page=1&pageSize=50&sort=-lastActivityAt&closed=false&ownerId=5"));
    expect(where()).toBe("/rooms/12?owner=me");
    expect(screen.getByRole("button", { name: "Mine" })).toHaveAttribute("aria-pressed", "true");
    await user.click(screen.getByRole("button", { name: "Mine" }));
    await waitFor(() => expect(where()).toBe("/rooms/12"));
    expect(listCalls().at(-1)).not.toContain("ownerId");
  });

  test("Mentions me sits beside Mine, sends mentionsMe, writes mention=me, and toggles off", async () => {
    serve();
    const user = userEvent.setup();
    renderPanel();
    await openPanel(user);
    const mentions = screen.getByRole("button", { name: "Mentions me" });
    expect(mentions).toHaveAttribute("aria-pressed", "false");
    expect(Boolean(screen.getByRole("button", { name: "Mine" }).compareDocumentPosition(mentions) & Node.DOCUMENT_POSITION_FOLLOWING)).toBe(true);
    await user.click(mentions);
    await waitFor(() => expect(listCalls().at(-1)).toBe("/api/v1/rooms/12/threads?page=1&pageSize=50&sort=-lastActivityAt&closed=false&mentionsMe=true"));
    expect(where()).toBe("/rooms/12?mention=me");
    expect(screen.getByRole("button", { name: "Mentions me" })).toHaveAttribute("aria-pressed", "true");
    // It is a filter of its own: Mine is untouched, and the two compose.
    expect(screen.getByRole("button", { name: "Mine" })).toHaveAttribute("aria-pressed", "false");
    await user.click(screen.getByRole("button", { name: "Mine" }));
    await waitFor(() => expect(where()).toBe("/rooms/12?owner=me&mention=me"));
    expect(listCalls().at(-1)).toContain("ownerId=5&mentionsMe=true");
    await user.click(screen.getByRole("button", { name: "Mentions me" }));
    await waitFor(() => expect(where()).toBe("/rooms/12?owner=me"));
    expect(listCalls().at(-1)).not.toContain("mentionedId");
  });

  test("Watching sits beside Mentions me, sends watching=true, writes watching=me, composes with Mine, and Clear drops it", async () => {
    serve();
    const user = userEvent.setup();
    renderPanel();
    await openPanel(user);
    const watching = screen.getByRole("button", { name: "Watching" });
    expect(Boolean(screen.getByRole("button", { name: "Mentions me" }).compareDocumentPosition(watching) & Node.DOCUMENT_POSITION_FOLLOWING)).toBe(true);
    await user.click(watching);
    await waitFor(() => expect(listCalls().at(-1)).toBe("/api/v1/rooms/12/threads?page=1&pageSize=50&sort=-lastActivityAt&closed=false&watching=true"));
    expect(where()).toBe("/rooms/12?watching=me");
    expect(screen.getByRole("button", { name: "Watching" })).toHaveAttribute("aria-pressed", "true");
    await user.click(screen.getByRole("button", { name: "Mine" }));
    await waitFor(() => expect(where()).toBe("/rooms/12?owner=me&watching=me"));
    await user.click(screen.getByRole("button", { name: "Clear filters" }));
    await waitFor(() => expect(where()).toBe("/rooms/12"));
    expect(screen.getByRole("button", { name: "Watching" })).toHaveAttribute("aria-pressed", "false");
  });

  test("the badge counts Mentions me as one filter and Clear filters removes it", async () => {
    serve();
    const user = userEvent.setup();
    renderPanel("/rooms/12?mention=me&scope=all");
    await screen.findByRole("navigation", { name: "Threads" });
    expect(within(panelButton()).getByText("1")).toBeInTheDocument();
    expect(listCalls().at(-1)).toBe("/api/v1/rooms/12/threads?page=1&pageSize=50&sort=-lastActivityAt&mentionsMe=true");
    await user.click(panelButton());
    expect(screen.getByRole("button", { name: "Mentions me" })).toHaveAttribute("aria-pressed", "true");
    await user.click(screen.getByRole("button", { name: "Clear filters" }));
    await waitFor(() => expect(where()).toBe("/rooms/12?scope=all"));
    expect(listCalls().at(-1)).not.toContain("mentionedId");
    expect(JSON.parse(localStorage.getItem("adder.viewSettings.roomThreads.12.query") ?? "null")).toBe("scope=all");
  });

  test("Mentions me is disabled, and not pressed, for a session without a user id", async () => {
    localStorage.removeItem("adder.auth.userId");
    serve();
    const user = userEvent.setup();
    renderPanel("/rooms/12?mention=me");
    await openPanel(user);
    const mentions = screen.getByRole("button", { name: "Mentions me" });
    expect(mentions).toBeDisabled();
    expect(mentions).toHaveAttribute("aria-pressed", "false");
    expect(listCalls()).toEqual(["/api/v1/rooms/12/threads?page=1&pageSize=50&sort=-lastActivityAt&closed=false"]);
  });

  test("the owner picker chooses any member and Mine gives way to it", async () => {
    serve();
    const user = userEvent.setup();
    renderPanel("/rooms/12?owner=me");
    await openPanel(user);
    await user.click(screen.getByRole("combobox", { name: "Owner" }));
    await user.click(await screen.findByRole("option", { name: "Noa Member (noa@example.test)" }));
    await waitFor(() => expect(where()).toBe("/rooms/12?owner=12"));
    expect(listCalls().at(-1)).toContain("ownerId=12");
    expect(screen.getByRole("button", { name: "Mine" })).toHaveAttribute("aria-pressed", "false");
    await user.click(screen.getByLabelText("Clear Owner"));
    await waitFor(() => expect(where()).toBe("/rooms/12"));
  });

  test("an owner id from the URL is named from the room's members, or by its number when it is not among them", async () => {
    serve();
    const first = renderPanel("/rooms/12?owner=7");
    const user = userEvent.setup();
    await openPanel(user);
    await waitFor(() => expect(screen.getByRole("combobox", { name: "Owner" })).toHaveValue("Mia Manager (mia@example.test)"));
    first.unmount();
    renderPanel("/rooms/12?owner=99");
    await waitFor(() => expect(screen.getByRole("combobox", { name: "Owner" })).toHaveValue("#99"));
  });

  test("a control appears only for an active SELECT, MULTI_SELECT or USER field", async () => {
    serve();
    const user = userEvent.setup();
    renderPanel();
    await openPanel(user);
    expect(await screen.findByRole("combobox", { name: "Severity" })).toBeInTheDocument();
    expect(screen.getByRole("combobox", { name: "Lead" })).toBeInTheDocument();
    expect(screen.queryByRole("combobox", { name: "Notes" })).toBeNull();
    expect(screen.queryByRole("combobox", { name: "Due" })).toBeNull();
  });

  test("a SELECT field filter writes key:value pairs, any-of within the field", async () => {
    serve();
    const user = userEvent.setup();
    renderPanel();
    await openPanel(user);
    await user.click(await screen.findByRole("combobox", { name: "Severity" }));
    await user.click(await screen.findByRole("option", { name: "Critical" }));
    await user.click(screen.getByRole("option", { name: "Minor" }));
    await waitFor(() => expect(where()).toBe("/rooms/12?field=severity%3As1&field=severity%3As2"));
    expect(listCalls().at(-1)).toContain("&field=severity%3As1&field=severity%3As2");
    await user.click(screen.getByRole("button", { name: "Remove Critical" }));
    await waitFor(() => expect(where()).toBe("/rooms/12?field=severity%3As2"));
  });

  test("a USER field filter writes the member's id and clears with its own button", async () => {
    serve();
    const user = userEvent.setup();
    renderPanel();
    await openPanel(user);
    await user.click(await screen.findByRole("combobox", { name: "Lead" }));
    await user.click(await screen.findByRole("option", { name: "Mia Manager (mia@example.test)" }));
    await waitFor(() => expect(where()).toBe("/rooms/12?field=lead%3A7"));
    expect(listCalls().at(-1)).toContain("&field=lead%3A7");
    await user.click(screen.getByLabelText("Clear Lead"));
    await waitFor(() => expect(where()).toBe("/rooms/12"));
  });

  test("a field filter shares the request with the others and all-of across fields", async () => {
    serve();
    const user = userEvent.setup();
    renderPanel("/rooms/12?field=lead%3A7");
    await openPanel(user);
    await user.click(await screen.findByRole("combobox", { name: "Severity" }));
    await user.click(await screen.findByRole("option", { name: "Critical" }));
    await waitFor(() => expect(where()).toBe("/rooms/12?field=lead%3A7&field=severity%3As1"));
    expect(listCalls().at(-1)).toContain("&field=lead%3A7&field=severity%3As1");
  });

  test("Clear filters drops the panel filters and the page, keeps the scope and the order, and remembers what remains", async () => {
    serve({ ...THREADS, total: 120 });
    const user = userEvent.setup();
    renderPanel("/rooms/12?scope=all&order=number&status=triage&owner=me&field=severity%3As1&page=2&message=9");
    await openPanel(user);
    await user.click(screen.getByRole("button", { name: "Clear filters" }));
    await waitFor(() => expect(where()).toBe("/rooms/12?message=9&scope=all&order=number"));
    expect(listCalls().at(-1)).toBe("/api/v1/rooms/12/threads?page=1&pageSize=50&sort=number");
    expect(screen.getByRole("radio", { name: "All" })).toBeChecked();
    expect(screen.getByRole("combobox", { name: "Order threads by" })).toHaveValue("Number");
    expect(JSON.parse(localStorage.getItem("adder.viewSettings.roomThreads.12.query") ?? "null")).toBe("scope=all&order=number");
    expect(screen.queryByRole("button", { name: "Clear filters" })).toBeNull();
  });

  test("Clear filters on a list with only filters leaves the plain list and forgets the remembered query", async () => {
    serve();
    const user = userEvent.setup();
    renderPanel("/rooms/12?status=triage");
    await openPanel(user);
    await user.click(screen.getByRole("button", { name: "Clear filters" }));
    await waitFor(() => expect(where()).toBe("/rooms/12"));
    expect(listCalls().at(-1)).toBe("/api/v1/rooms/12/threads?page=1&pageSize=50&sort=-lastActivityAt&closed=false");
    expect(JSON.parse(localStorage.getItem("adder.viewSettings.roomThreads.12.query") ?? "null")).toBe("");
  });

  test("a stale field entry (a retired key, a value of the wrong shape) is dropped from the request and pruned from the URL and the remembered query", async () => {
    serve();
    renderPanel("/rooms/12?field=gone%3Ax&field=notes%3Ax&field=severity%3A7&field=lead%3Aabc&field=severity%3As1&message=9");
    await screen.findByRole("navigation", { name: "Threads" });
    await waitFor(() => expect(where()).toBe("/rooms/12?message=9&field=severity%3As1"));
    // The request never carried the stale entries (one request, not a refetch after the prune), and the badge counts the one real filter.
    expect(listCalls()).toEqual(["/api/v1/rooms/12/threads?page=1&pageSize=50&sort=-lastActivityAt&closed=false&field=severity%3As1"]);
    expect(within(panelButton()).getByText("1")).toBeInTheDocument();
    expect(JSON.parse(localStorage.getItem("adder.viewSettings.roomThreads.12.query") ?? "null")).toBe("field=severity%3As1");
  });

  test("a URL with only stale field entries ends up plain, forgets them, and shows no badge and no Clear", async () => {
    serve();
    const user = userEvent.setup();
    renderPanel("/rooms/12?field=gone%3Ax&page=3");
    await screen.findByRole("navigation", { name: "Threads" });
    await waitFor(() => expect(where()).toBe("/rooms/12?page=3"));
    expect(within(panelButton()).queryByText(/^\d+$/)).toBeNull();
    await user.click(panelButton());
    expect(screen.queryByRole("button", { name: "Clear filters" })).toBeNull();
    expect(JSON.parse(localStorage.getItem("adder.viewSettings.roomThreads.12.query") ?? "null")).toBe("");
  });

  test("pruning keeps the other remembered keys: a remembered query with a stale field comes back without it", async () => {
    localStorage.setItem("adder.viewSettings.roomThreads.12.query", JSON.stringify("scope=closed&field=gone%3Ax&status=triage"));
    serve();
    renderPanel();
    await screen.findByRole("navigation", { name: "Threads" });
    await waitFor(() => expect(where()).toBe("/rooms/12?scope=closed&status=triage"));
    expect(JSON.parse(localStorage.getItem("adder.viewSettings.roomThreads.12.query") ?? "null")).toBe("scope=closed&status=triage");
  });

  test("stale entries do not eat the cap: a new value is added to the real ones once the stale are pruned", async () => {
    serve();
    const user = userEvent.setup();
    const stale = Array.from({ length: 20 }, (_, i) => `field=gone%3Ax${i}`).join("&");
    renderPanel(`/rooms/12?${stale}`);
    await screen.findByRole("navigation", { name: "Threads" });
    await waitFor(() => expect(where()).toBe("/rooms/12"));
    await user.click(panelButton());
    await user.click(await screen.findByRole("combobox", { name: "Severity" }));
    await user.click(await screen.findByRole("option", { name: "Critical" }));
    await waitFor(() => expect(where()).toBe("/rooms/12?field=severity%3As1"));
  });

  test("Mine is disabled, and not pressed, for a session without a user id", async () => {
    localStorage.removeItem("adder.auth.userId");
    serve();
    const user = userEvent.setup();
    renderPanel("/rooms/12?owner=me");
    await openPanel(user);
    const mine = screen.getByRole("button", { name: "Mine" });
    expect(mine).toBeDisabled();
    expect(mine).toHaveAttribute("aria-pressed", "false");
    // No id, no owner filter: nothing was asked for.
    expect(listCalls()).toEqual(["/api/v1/rooms/12/threads?page=1&pageSize=50&sort=-lastActivityAt&closed=false"]);
  });

  test("a field filter waits for the workflow: no list request goes out before it is read", async () => {
    const pending: { answer?: (response: Response) => void } = {};
    serve(THREADS, () => new Promise<Response>((resolve) => (pending.answer = resolve)));
    renderPanel("/rooms/12?field=severity%3As1");
    expect(await screen.findByRole("status", { name: "Loading…" })).toBeInTheDocument();
    await waitFor(() => expect(pending.answer).toBeDefined());
    expect(listCalls()).toEqual([]);
    pending.answer?.(jsonResponse(200, WORKFLOW));
    expect(await screen.findByRole("link", { name: /Disk full/ })).toBeInTheDocument();
    expect(listCalls()).toEqual(["/api/v1/rooms/12/threads?page=1&pageSize=50&sort=-lastActivityAt&closed=false&field=severity%3As1"]);
  });

  test("a workflow that cannot be read says so instead of spinning for ever when field filters wait for it", async () => {
    serve(THREADS, () => Promise.resolve(jsonResponse(500, { title: "Boom", status: 500 })));
    renderPanel("/rooms/12?field=severity%3As1");
    expect(await screen.findByText("Could not load the threads")).toBeInTheDocument();
    expect(screen.queryByRole("status", { name: "Loading…" })).toBeNull();
    expect(listCalls()).toEqual([]);
  });

  test("an empty answer under a filter is the filtered empty state, with no start-one hint; Clear is still offered", async () => {
    serve(EMPTY);
    const user = userEvent.setup();
    renderPanel("/rooms/12?status=triage");
    expect(await screen.findByText("No threads match these filters.")).toBeInTheDocument();
    expect(screen.queryByText("Start one to get the conversation going.")).toBeNull();
    await user.click(panelButton());
    expect(screen.getByRole("button", { name: "Clear filters" })).toBeInTheDocument();
  });

  test("the order Workflow order sends sort=status", async () => {
    serve();
    const user = userEvent.setup();
    renderPanel();
    await screen.findByRole("navigation", { name: "Threads" });
    await user.click(screen.getByRole("combobox", { name: "Order threads by" }));
    await user.click(await screen.findByRole("option", { name: "Workflow order" }));
    await waitFor(() => expect(listCalls().at(-1)).toBe("/api/v1/rooms/12/threads?page=1&pageSize=50&sort=status&closed=false"));
    expect(where()).toBe("/rooms/12?order=status");
  });
});
