import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { screen, waitFor, within } from "@testing-library/react";
import ThreadPane from "./ThreadPane";
import { jsonResponse } from "../test/http";
import { SOME_TIME, makeMessage, makeRoom, makeThread, messagePage } from "../test/messageFixtures";
import { renderWithProviders } from "../test/render";

// "Watch this thread" in the thread pane's button row, beside the pin button (the rest of the pane is in ThreadPane.test.tsx).
const WORKFLOW = {
  revision: 1,
  statuses: [{ key: "triage", label: "Triage", colour: "BLUE", category: "OPEN", initial: true, threadCount: 1 }],
  fields: [],
};

describe("ThreadPane: watching the thread", () => {
  let mockFetch: ReturnType<typeof vi.fn>;
  let thread: ReturnType<typeof makeThread>;
  // Only the watch writes: the conversation also moves the reader's read marker (a PUT of its own, view state).
  const watchCalls = () =>
    mockFetch.mock.calls.filter(([url, init]) => (init as RequestInit | undefined)?.method === "PUT" && String(url).endsWith("/watch")) as [string, RequestInit][];
  const button = () => screen.getByRole("button", { name: "Watch this thread" });

  /** `put` answers the watch PUT; by default it applies the asked state to the thread the next read returns, like the server. */
  function serve(put?: (watching: boolean) => Response) {
    mockFetch.mockImplementation((url: string, init?: RequestInit) => {
      if (init?.method === "PUT" && url.endsWith("/watch")) {
        const watching = (JSON.parse(init.body as string) as { watching: boolean }).watching;
        if (put) return Promise.resolve(put(watching));
        thread = { ...thread, watching, watcherCount: thread.watcherCount + (watching ? 1 : -1) };
        return Promise.resolve(new Response(null, { status: 204 }));
      }
      if (init?.method === "PUT") return Promise.resolve(new Response(null, { status: 204 }));
      if (url === "/api/v1/rooms/12/threads/42") return Promise.resolve(jsonResponse(200, thread));
      if (url.startsWith("/api/v1/rooms/12/threads/42/watchers?")) {
        return Promise.resolve(jsonResponse(200, { items: [{ userId: 5, name: "Ann Owner" }], page: 1, pageSize: 20, total: 1 }));
      }
      if (url === "/api/v1/rooms/12/workflow") return Promise.resolve(jsonResponse(200, WORKFLOW));
      if (url.startsWith("/api/v1/rooms/12/pins?")) return Promise.resolve(jsonResponse(200, { items: [], page: 1, pageSize: 50, total: 0 }));
      if (url.startsWith("/api/v1/rooms/12/threads/42/messages?")) return Promise.resolve(jsonResponse(200, messagePage([makeMessage({ id: 1 })])));
      if (url.startsWith("/api/v1/rooms/12/threads/42/events?")) return Promise.resolve(jsonResponse(200, { items: [], page: 1, pageSize: 10, total: 0 }));
      return Promise.resolve(jsonResponse(404, { title: "Not found", status: 404 }));
    });
  }

  beforeEach(() => {
    mockFetch = vi.fn();
    thread = makeThread();
    vi.stubGlobal("fetch", mockFetch);
    localStorage.setItem("adder.auth.token", "fake-token");
  });
  afterEach(() => {
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  test("the button is a toggle that follows the thread, with the watcher count beside it, and pressing it PUTs the opposite state", async () => {
    const user = userEvent.setup();
    serve();
    renderWithProviders(<ThreadPane room={makeRoom()} numberParam="42" />);
    await screen.findByRole("button", { name: "Watch this thread" });
    expect(button()).toHaveAttribute("aria-pressed", "false");
    expect(screen.getByRole("button", { name: "Show 1 watcher" })).toHaveTextContent("1");
    await user.click(button());
    await waitFor(() => expect(button()).toHaveAttribute("aria-pressed", "true"));
    expect([watchCalls()[0][0], JSON.parse(watchCalls()[0][1].body as string)]).toEqual(["/api/v1/rooms/12/threads/42/watch", { watching: true }]);
    expect(await screen.findByRole("button", { name: "Show 2 watchers" })).toHaveTextContent("2");
    await user.click(button());
    await waitFor(() => expect(button()).toHaveAttribute("aria-pressed", "false"));
    expect(JSON.parse(watchCalls()[1][1].body as string)).toEqual({ watching: false });
    expect(await screen.findByRole("button", { name: "Show 1 watcher" })).toHaveTextContent("1");
  });

  test("a thread the caller already watches (its creator, a new owner) reads as pressed on arrival", async () => {
    thread = makeThread({ watching: true, watcherCount: 3 });
    serve();
    renderWithProviders(<ThreadPane room={makeRoom()} numberParam="42" />);
    await screen.findByRole("button", { name: "Watch this thread" });
    expect(button()).toHaveAttribute("aria-pressed", "true");
    expect(screen.getByRole("button", { name: "Show 3 watchers" })).toHaveTextContent("3");
  });

  test("the watcher count opens a member roster whose names link to profiles", async () => {
    const user = userEvent.setup();
    serve();
    renderWithProviders(<ThreadPane room={makeRoom()} numberParam="42" />);
    await user.click(await screen.findByRole("button", { name: "Show 1 watcher" }));
    const dialog = await screen.findByRole("dialog", { name: "People watching" });
    expect(within(dialog).getByRole("link", { name: "Ann Owner" })).toHaveAttribute("href", "/people/5");
  });

  test("a closed thread and an archived room keep the control: a watch is the member's own state", async () => {
    thread = makeThread({ closed: true, closedAt: SOME_TIME });
    serve();
    const closed = renderWithProviders(<ThreadPane room={makeRoom()} numberParam="42" />);
    expect(await screen.findByRole("button", { name: "Watch this thread" })).toBeEnabled();
    closed.unmount();
    renderWithProviders(<ThreadPane room={makeRoom({ archived: true })} numberParam="42" />);
    expect(await screen.findByRole("button", { name: "Watch this thread" })).toBeEnabled();
  });

  test("an ADMIN who is not a member gets no button", async () => {
    serve();
    renderWithProviders(<ThreadPane room={makeRoom({ role: null })} numberParam="42" />);
    await screen.findByRole("region", { name: "#42 Disk full on db-3" });
    expect(screen.queryByRole("button", { name: "Watch this thread" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /Show .* watcher/ })).not.toBeInTheDocument();
  });

  test("a refused write says why beside the button and leaves the state as it was", async () => {
    const user = userEvent.setup();
    serve(() => jsonResponse(429, { title: "Too many requests", status: 429 }));
    renderWithProviders(<ThreadPane room={makeRoom()} numberParam="42" />);
    await user.click(await screen.findByRole("button", { name: "Watch this thread" }));
    expect(await screen.findByText("You are changing things too quickly. Wait a minute and try again.")).toBeInTheDocument();
    expect(button()).toHaveAttribute("aria-pressed", "false");
  });

  test("a refusal is drawn in a full-width row of the header, outside the icon group", async () => {
    const user = userEvent.setup();
    serve(() => jsonResponse(429, { title: "Too many requests", status: 429 }));
    renderWithProviders(<ThreadPane room={makeRoom()} numberParam="42" />);
    await user.click(await screen.findByRole("button", { name: "Watch this thread" }));
    const alert = await screen.findByRole("alert");
    const actions = screen.getByRole("group", { name: "Thread actions" });
    expect(actions).toContainElement(button());
    expect(actions).not.toContainElement(alert);
    // The head (title row, alert, meta line) holds both, so the alert is a row of its own there.
    expect(alert.parentElement).toBe(actions.parentElement!.parentElement);
  });

  test("a 404 (removed from the room meanwhile) says so and re-reads the room", async () => {
    const user = userEvent.setup();
    serve(() => jsonResponse(404, { title: "Not found", status: 404 }));
    renderWithProviders(<ThreadPane room={makeRoom()} numberParam="42" />);
    await user.click(await screen.findByRole("button", { name: "Watch this thread" }));
    expect(await screen.findByText(/no longer exists, or you no longer have access/)).toBeInTheDocument();
    const threadReads = () => mockFetch.mock.calls.filter(([url]) => url === "/api/v1/rooms/12/threads/42").length;
    await waitFor(() => expect(threadReads()).toBeGreaterThan(1));
  });

  test("a 403 has its own words", async () => {
    const user = userEvent.setup();
    serve(() => jsonResponse(403, { title: "Forbidden", status: 403 }));
    renderWithProviders(<ThreadPane room={makeRoom()} numberParam="42" />);
    await user.click(await screen.findByRole("button", { name: "Watch this thread" }));
    expect(await screen.findByText("Only members of the room can watch threads.")).toBeInTheDocument();
  });
});
