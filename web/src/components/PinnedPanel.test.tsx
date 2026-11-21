import { useEffect, useRef } from "react";
import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { useLocation } from "react-router-dom";
import { QueryClient } from "@tanstack/react-query";
import { screen, waitFor, within } from "@testing-library/react";
import PinnedPanel from "./PinnedPanel";
import type { RoomPin } from "../api/pins";
import type { RoomResponse } from "../api/rooms";
import { jsonResponse } from "../test/http";
import { SOME_TIME, makeMessage, makeRoom } from "../test/messageFixtures";
import { renderWithProviders } from "../test/render";
import { SIDE_WORKFLOW } from "../test/sideFixtures";
import { browserTimeZone, formatDateTime } from "../utils/formatDate";
import { keysForFrame } from "../utils/liveKeys";
import { wantsThreadPaneFocus } from "../utils/roomLinks";

const ZONE = browserTimeZone();
const THREAD_PIN: RoomPin = {
  id: 1,
  kind: "THREAD",
  threadNumber: 42,
  threadTitle: "Awaria bazy",
  threadStatus: "triage",
  threadClosed: false,
  message: null,
  pinnedById: 5,
  pinnedByName: "Ann Owner",
  pinnedAt: SOME_TIME,
};
const MESSAGE_PIN: RoomPin = {
  id: 2,
  kind: "MESSAGE",
  threadNumber: 7,
  threadTitle: "Runbook",
  threadStatus: "triage",
  threadClosed: false,
  message: makeMessage({ id: 991, threadNumber: 7, authorName: "Cy Author", body: "Restart the **replica** first", mentions: [{ userId: 5, name: "Ann Owner" }] }),
  pinnedById: 6,
  pinnedByName: "Bob Starter",
  pinnedAt: SOME_TIME,
};
const page = (items: RoomPin[]) => ({ items, page: 1, pageSize: 50, total: items.length });

function Where() {
  const { pathname, search } = useLocation();
  return <output aria-label="where">{pathname + search}</output>;
}

/** Stands in for the thread pane: it takes focus when the navigation asks for it, like `ThreadPane` does. */
function PaneStub() {
  const { state, key } = useLocation();
  const pane = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (wantsThreadPaneFocus(state)) pane.current?.focus();
  }, [state, key]);
  return <div ref={pane} tabIndex={-1} aria-label="pane" />;
}

const settle = () => new Promise((resolve) => setTimeout(resolve, 100));

describe("PinnedPanel", () => {
  let mockFetch: ReturnType<typeof vi.fn>;
  let pins: RoomPin[];
  const puts = () => mockFetch.mock.calls.filter(([, init]) => (init as RequestInit | undefined)?.method === "PUT") as [string, RequestInit][];

  function serve(put?: () => Response) {
    mockFetch.mockImplementation((url: string, init?: RequestInit) => {
      if (init?.method === "PUT") return Promise.resolve(put ? put() : new Response(null, { status: 204 }));
      if (url.startsWith("/api/v1/rooms/12/pins?")) return Promise.resolve(jsonResponse(200, page(pins)));
      if (url === "/api/v1/rooms/12/workflow") return Promise.resolve(jsonResponse(200, SIDE_WORKFLOW));
      return Promise.resolve(jsonResponse(404, { title: "Not found", status: 404 }));
    });
  }
  const renderPanel = (room: RoomResponse = makeRoom(), route = "/rooms/12", queryClient?: QueryClient) =>
    renderWithProviders(
      <>
        <PinnedPanel room={room} />
        <Where />
        <PaneStub />
      </>,
      { route, queryClient },
    );
  const openPanel = async (user: ReturnType<typeof userEvent.setup>) => {
    await user.click(await screen.findByRole("button", { name: /^Pinned/ }));
    return screen.findByRole("dialog", { name: "Pinned in this room" });
  };

  beforeEach(() => {
    mockFetch = vi.fn();
    pins = [THREAD_PIN, MESSAGE_PIN];
    vi.stubGlobal("fetch", mockFetch);
    localStorage.setItem("adder.auth.token", "fake-token");
  });
  afterEach(() => {
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  test("the button carries the count once the pins are read", async () => {
    serve();
    renderPanel();
    expect(await screen.findByRole("button", { name: "Pinned (2)" })).toHaveAttribute("aria-haspopup", "dialog");
  });

  test("a failed read leaves the button without a count, and the drawer says so", async () => {
    const user = userEvent.setup();
    mockFetch.mockResolvedValue(jsonResponse(500, { title: "Boom", status: 500 }));
    renderPanel();
    const dialog = await openPanel(user);
    expect(screen.getByRole("button", { name: "Pinned" })).toBeInTheDocument();
    expect(await within(dialog).findByText("Could not load the pins")).toBeInTheDocument();
  });

  test("a thread pin links to its thread, with the status pill and who pinned it", async () => {
    const user = userEvent.setup();
    serve();
    renderPanel();
    const dialog = await openPanel(user);
    const link = await within(dialog).findByRole("link", { name: "#42 Awaria bazy" });
    expect(link).toHaveAttribute("href", "/rooms/12/threads/42");
    expect(dialog).toHaveTextContent(`Pinned by Ann Owner on ${formatDateTime(SOME_TIME, ZONE)}`);
    // Every card wears the pin shape beside those words (decoration; the words are the mark, 1.2 D5).
    const cards = within(dialog).getAllByRole("listitem");
    expect(cards).toHaveLength(2);
    for (const card of cards) expect(card.querySelector("svg")).toHaveAttribute("aria-hidden", "true");
    await waitFor(() => expect(dialog).toHaveTextContent("Triage"));
    await user.click(link);
    expect(screen.getByLabelText("where")).toHaveTextContent("/rooms/12/threads/42");
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
  });

  test("on the Threads tab a thread link keeps the list's URL keys, the message link too", async () => {
    const user = userEvent.setup();
    serve();
    renderPanel(makeRoom(), "/rooms/12/threads/5?scope=closed&page=3");
    const dialog = await openPanel(user);
    expect(await within(dialog).findByRole("link", { name: "#42 Awaria bazy" })).toHaveAttribute("href", "/rooms/12/threads/42?scope=closed&page=3");
    expect(within(dialog).getByRole("link", { name: "Open in thread #7 Runbook" })).toHaveAttribute("href", "/rooms/12/threads/7?message=991&scope=closed&page=3");
  });

  test("on the Search tab the search's own scope and page are not carried onto the thread links", async () => {
    const user = userEvent.setup();
    serve();
    renderPanel(makeRoom(), "/rooms/12/search?q=disk&scope=closed&page=3");
    const dialog = await openPanel(user);
    expect(await within(dialog).findByRole("link", { name: "#42 Awaria bazy" })).toHaveAttribute("href", "/rooms/12/threads/42");
    expect(within(dialog).getByRole("link", { name: "Open in thread #7 Runbook" })).toHaveAttribute("href", "/rooms/12/threads/7?message=991");
  });

  test("following a thread link hands focus to the pane and the drawer does not take it back to the button", async () => {
    const user = userEvent.setup();
    serve();
    renderPanel();
    const trigger = await screen.findByRole("button", { name: "Pinned (2)" });
    const dialog = await openPanel(user);
    await user.click(await within(dialog).findByRole("link", { name: "#42 Awaria bazy" }));
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    await settle();
    expect(screen.getByLabelText("pane")).toHaveFocus();
    expect(trigger).not.toHaveFocus();
  });

  test("following Open in thread does not return focus to the button either", async () => {
    const user = userEvent.setup();
    serve();
    renderPanel();
    const trigger = await screen.findByRole("button", { name: "Pinned (2)" });
    const dialog = await openPanel(user);
    await user.click(await within(dialog).findByRole("link", { name: "Open in thread #7 Runbook" }));
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    await settle();
    expect(trigger).not.toHaveFocus();
  });

  test("the author of a pinned message and who pinned it are links to their profiles, and following one closes the drawer without returning focus to the button", async () => {
    const user = userEvent.setup();
    serve();
    renderPanel();
    const trigger = await screen.findByRole("button", { name: "Pinned (2)" });
    const dialog = await openPanel(user);
    expect(within(dialog).getByRole("link", { name: "Cy Author" })).toHaveAttribute("href", "/people/7");
    expect(within(dialog).getByRole("link", { name: "Bob Starter" })).toHaveAttribute("href", "/people/6");
    // Ann Owner pinned the thread (5): her name is in the "Pinned by" line of that card.
    expect(within(dialog).getByRole("link", { name: "Ann Owner" })).toHaveAttribute("href", "/people/5");
    await user.click(within(dialog).getByRole("link", { name: "Bob Starter" }));
    expect(screen.getByLabelText("where")).toHaveTextContent("/people/6");
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    await settle();
    expect(trigger).not.toHaveFocus();
  });

  test("a message pin shows the message as Markdown and its thread, and Open in thread carries message=", async () => {
    const user = userEvent.setup();
    serve();
    renderPanel();
    const dialog = await openPanel(user);
    const open = await within(dialog).findByRole("link", { name: "Open in thread #7 Runbook" });
    expect(open).toHaveTextContent("Open in thread");
    expect(open).toHaveAttribute("href", "/rooms/12/threads/7?message=991");
    expect(dialog).toHaveTextContent("Message by Cy Author");
    expect(dialog).toHaveTextContent("In #7 Runbook");
    expect(within(dialog).getByText("replica").tagName).toBe("STRONG");
    await user.click(open);
    expect(screen.getByLabelText("where")).toHaveTextContent("/rooms/12/threads/7?message=991");
  });

  test("a body is text or Markdown, never markup", async () => {
    const user = userEvent.setup();
    pins = [{ ...MESSAGE_PIN, message: makeMessage({ id: 991, body: "<img src=x onerror=alert(1)> and <b>bold</b>" }) }];
    serve();
    renderPanel();
    const dialog = await openPanel(user);
    await within(dialog).findByText(/<b>bold<\/b>/);
    expect(dialog.querySelector("img, b")).toBeNull();
  });

  test("Unpin on a thread pin PUTs pinned=false and the pins are read again", async () => {
    const user = userEvent.setup();
    serve(() => {
      pins = [MESSAGE_PIN];
      return new Response(null, { status: 204 });
    });
    renderPanel();
    const dialog = await openPanel(user);
    await user.click(await within(dialog).findByRole("button", { name: "Unpin #42 Awaria bazy" }));
    await waitFor(() => expect(within(dialog).queryByRole("link", { name: "#42 Awaria bazy" })).not.toBeInTheDocument());
    const [url, init] = puts()[0];
    expect([url, JSON.parse(init.body as string)]).toEqual(["/api/v1/rooms/12/threads/42/pin", { pinned: false }]);
    expect(screen.getByRole("button", { name: "Pinned (1)" })).toBeInTheDocument();
  });

  test("Unpin on a message pin PUTs to the message", async () => {
    const user = userEvent.setup();
    serve();
    renderPanel();
    const dialog = await openPanel(user);
    await user.click(await within(dialog).findByRole("button", { name: `Unpin message by Cy Author on ${formatDateTime(SOME_TIME, ZONE)}` }));
    await waitFor(() => expect(puts()).toHaveLength(1));
    expect(puts()[0][0]).toBe("/api/v1/rooms/12/messages/991/pin");
  });

  test("a refused unpin says so under the title and keeps the list", async () => {
    const user = userEvent.setup();
    serve(() => jsonResponse(403, { title: "Forbidden", status: 403 }));
    renderPanel();
    const dialog = await openPanel(user);
    await user.click(await within(dialog).findByRole("button", { name: "Unpin #42 Awaria bazy" }));
    expect(await within(dialog).findByRole("alert")).toHaveTextContent("Only members of the room can pin or unpin.");
    expect(within(dialog).getByRole("link", { name: "#42 Awaria bazy" })).toBeInTheDocument();
  });

  test("a reader who may not change pins (an ADMIN outside the room) sees no Unpin", async () => {
    const user = userEvent.setup();
    serve();
    renderPanel(makeRoom({ role: null }));
    const dialog = await openPanel(user);
    await within(dialog).findByRole("link", { name: "#42 Awaria bazy" });
    expect(within(dialog).queryByRole("button", { name: /^Unpin/ })).not.toBeInTheDocument();
  });

  test("an archived room offers no Unpin either", async () => {
    const user = userEvent.setup();
    serve();
    renderPanel(makeRoom({ archived: true }));
    const dialog = await openPanel(user);
    await within(dialog).findByRole("link", { name: "#42 Awaria bazy" });
    expect(within(dialog).queryByRole("button", { name: /^Unpin/ })).not.toBeInTheDocument();
  });

  test("nothing pinned: the empty state says how to pin", async () => {
    const user = userEvent.setup();
    pins = [];
    serve();
    renderPanel();
    const dialog = await openPanel(user);
    expect(await within(dialog).findByText("Nothing is pinned yet.")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Pinned (0)" })).toBeInTheDocument();
  });

  test("the drawer's close button has a name and closes it; the panel is reachable by keyboard", async () => {
    const user = userEvent.setup();
    serve();
    renderPanel();
    const trigger = await screen.findByRole("button", { name: "Pinned (2)" });
    trigger.focus();
    await user.keyboard("{Enter}");
    const dialog = await screen.findByRole("dialog", { name: "Pinned in this room" });
    await user.click(within(dialog).getByRole("button", { name: "Close" }));
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    // Closing it without going anywhere still gives focus back to the button.
    await waitFor(() => expect(screen.getByRole("button", { name: "Pinned (2)" })).toHaveFocus());
  });

  test("an open drawer drops a row after room.pin_changed and a re-read", async () => {
    const user = userEvent.setup();
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    serve();
    renderPanel(makeRoom(), "/rooms/12", queryClient);
    const dialog = await openPanel(user);
    await within(dialog).findByRole("link", { name: "#42 Awaria bazy" });
    pins = [MESSAGE_PIN];
    for (const queryKey of keysForFrame({ type: "event", kind: "room.pin_changed", roomId: 12 })) await queryClient.invalidateQueries({ queryKey });
    await waitFor(() => expect(within(dialog).queryByRole("link", { name: "#42 Awaria bazy" })).not.toBeInTheDocument());
    expect(within(dialog).getByRole("link", { name: "Open in thread #7 Runbook" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Pinned (1)" })).toBeInTheDocument();
  });

  test("an open drawer shows an edited body after message.edited, never the old one", async () => {
    const user = userEvent.setup();
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    serve();
    renderPanel(makeRoom(), "/rooms/12", queryClient);
    const dialog = await openPanel(user);
    await within(dialog).findByText("replica");
    pins = [{ ...MESSAGE_PIN, message: makeMessage({ id: 991, threadNumber: 7, authorName: "Cy Author", body: "redacted" }) }];
    for (const queryKey of keysForFrame({ type: "event", kind: "message.edited", roomId: 12, threadNumber: 7, messageId: 991 })) await queryClient.invalidateQueries({ queryKey });
    expect(await within(dialog).findByText("redacted")).toBeInTheDocument();
    expect(within(dialog).queryByText("replica")).not.toBeInTheDocument();
  });

  test("a message pin whose message is missing does nothing on Unpin: it never unpins the thread instead", async () => {
    const user = userEvent.setup();
    pins = [{ ...MESSAGE_PIN, message: null }];
    serve();
    renderPanel();
    const dialog = await openPanel(user);
    await user.click(await within(dialog).findByRole("button", { name: "Unpin #7 Runbook" }));
    await settle();
    expect(puts()).toEqual([]);
    expect(within(dialog).queryByRole("alert")).not.toBeInTheDocument();
  });

  test("a busy room is told apart from a rate limit", async () => {
    const user = userEvent.setup();
    serve(() => jsonResponse(429, { type: "urn:adder:problem:room-busy", status: 429, title: "Busy" }));
    renderPanel();
    const dialog = await openPanel(user);
    await user.click(await within(dialog).findByRole("button", { name: "Unpin #42 Awaria bazy" }));
    expect(await within(dialog).findByRole("alert")).toHaveTextContent("The room is busy right now — try again in a moment.");
  });

  test("the pin limit re-reads only the pins; a 403 re-reads the whole rooms prefix", async () => {
    const user = userEvent.setup();
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const invalidate = vi.spyOn(queryClient, "invalidateQueries");
    serve(() => jsonResponse(409, { type: "urn:adder:problem:pin-limit", status: 409, title: "Pin limit" }));
    renderPanel(makeRoom(), "/rooms/12", queryClient);
    const dialog = await openPanel(user);
    await user.click(await within(dialog).findByRole("button", { name: "Unpin #42 Awaria bazy" }));
    expect(await within(dialog).findByRole("alert")).toHaveTextContent("This room already has 50 pins. Unpin one first.");
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ["rooms", "pins", 12] });
    expect(invalidate).not.toHaveBeenCalledWith({ queryKey: ["rooms"] });
    serve(() => jsonResponse(403, { title: "Forbidden", status: 403 }));
    await user.click(within(dialog).getByRole("button", { name: "Unpin #42 Awaria bazy" }));
    await waitFor(() => expect(invalidate).toHaveBeenCalledWith({ queryKey: ["rooms"] }));
  });
});
