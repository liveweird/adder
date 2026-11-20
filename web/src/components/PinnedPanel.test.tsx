import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { useLocation } from "react-router-dom";
import { screen, waitFor, within } from "@testing-library/react";
import PinnedPanel from "./PinnedPanel";
import type { RoomPin } from "../api/pins";
import type { RoomResponse } from "../api/rooms";
import { jsonResponse } from "../test/http";
import { SOME_TIME, makeMessage, makeRoom } from "../test/messageFixtures";
import { renderWithProviders } from "../test/render";
import { SIDE_WORKFLOW } from "../test/sideFixtures";
import { browserTimeZone, formatDateTime } from "../utils/formatDate";

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
  const renderPanel = (room: RoomResponse = makeRoom()) =>
    renderWithProviders(
      <>
        <PinnedPanel room={room} />
        <Where />
      </>,
      { route: "/rooms/12" },
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
    await waitFor(() => expect(dialog).toHaveTextContent("Triage"));
    await user.click(link);
    expect(screen.getByLabelText("where")).toHaveTextContent("/rooms/12/threads/42");
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
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
  });
});
