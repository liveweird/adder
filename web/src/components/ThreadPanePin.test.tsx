import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { screen, waitFor, within } from "@testing-library/react";
import ThreadPane from "./ThreadPane";
import { jsonResponse } from "../test/http";
import { SOME_TIME, makeMessage, makeRoom, makeThread, messagePage } from "../test/messageFixtures";
import { renderWithProviders } from "../test/render";

// "Pin this thread" in the thread pane's button row (the rest of the pane is in ThreadPane.test.tsx).
const WORKFLOW = {
  revision: 1,
  statuses: [{ key: "triage", label: "Triage", colour: "BLUE", category: "OPEN", initial: true, threadCount: 1 }],
  fields: [],
};
const THREAD_PIN = { id: 3, kind: "THREAD", threadNumber: 42, threadTitle: "Disk full on db-3", threadStatus: "triage", threadClosed: false, message: null, pinnedById: 5, pinnedByName: "Ann Owner", pinnedAt: SOME_TIME };

describe("ThreadPane: pinning the thread", () => {
  let mockFetch: ReturnType<typeof vi.fn>;
  let pinned: unknown[];
  let thread: ReturnType<typeof makeThread>;
  // Only the pin writes: the conversation also moves the reader's read marker (a PUT of its own, view state).
  const putCalls = () =>
    mockFetch.mock.calls.filter(([url, init]) => (init as RequestInit | undefined)?.method === "PUT" && String(url).endsWith("/pin")) as [string, RequestInit][];
  const button = () => screen.getByRole("button", { name: "Pin this thread" });

  function serve(put: () => Response = () => new Response(null, { status: 204 })) {
    mockFetch.mockImplementation((url: string, init?: RequestInit) => {
      if (init?.method === "PUT" && url.endsWith("/pin")) return Promise.resolve(put());
      if (init?.method === "PUT") return Promise.resolve(new Response(null, { status: 204 }));
      if (url === "/api/v1/rooms/12/threads/42") return Promise.resolve(jsonResponse(200, thread));
      if (url === "/api/v1/rooms/12/workflow") return Promise.resolve(jsonResponse(200, WORKFLOW));
      if (url.startsWith("/api/v1/rooms/12/pins?")) return Promise.resolve(jsonResponse(200, { items: pinned, page: 1, pageSize: 50, total: pinned.length }));
      if (url.startsWith("/api/v1/rooms/12/threads/42/messages?")) return Promise.resolve(jsonResponse(200, messagePage([makeMessage({ id: 1 })])));
      if (url.startsWith("/api/v1/rooms/12/threads/42/events?")) return Promise.resolve(jsonResponse(200, { items: [], page: 1, pageSize: 10, total: 0 }));
      return Promise.resolve(jsonResponse(404, { title: "Not found", status: 404 }));
    });
  }

  beforeEach(() => {
    mockFetch = vi.fn();
    pinned = [];
    thread = makeThread();
    vi.stubGlobal("fetch", mockFetch);
    localStorage.setItem("adder.auth.token", "fake-token");
  });
  afterEach(() => {
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  test("the button is a toggle that follows the pins query, and pressing it PUTs the opposite state", async () => {
    const user = userEvent.setup();
    serve(() => {
      pinned = [THREAD_PIN];
      return new Response(null, { status: 204 });
    });
    renderWithProviders(<ThreadPane room={makeRoom()} numberParam="42" />);
    await screen.findByRole("button", { name: "Pin this thread" });
    expect(button()).toHaveAttribute("aria-pressed", "false");
    await user.click(button());
    await waitFor(() => expect(button()).toHaveAttribute("aria-pressed", "true"));
    expect([putCalls()[0][0], JSON.parse(putCalls()[0][1].body as string)]).toEqual(["/api/v1/rooms/12/threads/42/pin", { pinned: true }]);
    serve(() => {
      pinned = [];
      return new Response(null, { status: 204 });
    });
    await user.click(button());
    await waitFor(() => expect(button()).toHaveAttribute("aria-pressed", "false"));
    expect(JSON.parse(putCalls()[1][1].body as string)).toEqual({ pinned: false });
  });

  test("a thread the room has pinned reads as pressed on arrival", async () => {
    pinned = [THREAD_PIN];
    serve();
    renderWithProviders(<ThreadPane room={makeRoom()} numberParam="42" />);
    await screen.findByRole("button", { name: "Pin this thread" });
    await waitFor(() => expect(button()).toHaveAttribute("aria-pressed", "true"));
  });

  test("a pin of a message does not press the thread's button", async () => {
    pinned = [{ ...THREAD_PIN, kind: "MESSAGE", message: makeMessage({ id: 1 }) }];
    serve();
    renderWithProviders(<ThreadPane room={makeRoom()} numberParam="42" />);
    await screen.findByRole("button", { name: "Pin this thread" });
    await waitFor(() => expect(mockFetch.mock.calls.some(([url]) => String(url).startsWith("/api/v1/rooms/12/pins?"))).toBe(true));
    expect(button()).toHaveAttribute("aria-pressed", "false");
  });

  test("a pinned thread wears a visible Pinned badge in its header, read by assistive technology; an unpinned thread wears none", async () => {
    pinned = [THREAD_PIN];
    serve();
    const first = renderWithProviders(<ThreadPane room={makeRoom()} numberParam="42" />);
    const region = await screen.findByRole("region", { name: "#42 Disk full on db-3" });
    await waitFor(() => expect(within(region).getByText("Pinned")).toBeVisible());
    expect(region.querySelector("[data-pin-mark]")).not.toHaveAttribute("aria-hidden");
    // Neither the badge nor the pin button is the strip: a pinned thread alone has no strip.
    expect(screen.queryByRole("region", { name: "Pins in this thread" })).not.toBeInTheDocument();
    expect(region).not.toHaveAttribute("data-strip");
    first.unmount();
    pinned = [];
    renderWithProviders(<ThreadPane room={makeRoom()} numberParam="42" />);
    await screen.findByRole("region", { name: "#42 Disk full on db-3" });
    await waitFor(() => expect(mockFetch.mock.calls.filter(([url]) => String(url).startsWith("/api/v1/rooms/12/pins?")).length).toBeGreaterThan(1));
    expect(document.querySelector("[data-pin-mark]")).not.toBeInTheDocument();
  });

  test("a pinned message is marked in the conversation and listed in the thread's pin strip, under the header and above the messages", async () => {
    const user = userEvent.setup();
    pinned = [{ ...THREAD_PIN, id: 4, kind: "MESSAGE", message: makeMessage({ id: 1, body: "Restart the replica first" }) }];
    serve();
    renderWithProviders(<ThreadPane room={makeRoom()} numberParam="42" />);
    const strip = await screen.findByRole("region", { name: "Pins in this thread" });
    const toggle = within(strip).getByRole("button", { name: "Pinned: Restart the replica first" });
    expect(toggle).toHaveAttribute("aria-expanded", "false");
    // The pane says it carries a strip and whether its list is open (the wide layout gives the height back out of the conversation); a thread pin alone does not.
    expect(screen.getByRole("region", { name: "#42 Disk full on db-3" })).toHaveAttribute("data-strip", "closed");
    // Placement: the strip sits in the header box (after the thread's heading), before the conversation's messages region.
    const heading = screen.getByRole("heading", { name: "#42 Disk full on db-3" });
    const messages = await screen.findByRole("region", { name: "Messages" });
    expect(heading.compareDocumentPosition(strip) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    expect(strip.compareDocumentPosition(messages) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    // The message itself wears the mark, with the pinner's name in words.
    const item = await waitFor(() => {
      const found = messages.querySelector<HTMLElement>('[data-message-id="1"]');
      expect(found).toHaveAttribute("data-pinned", "true");
      return found!;
    });
    expect(within(item).getByText("Pinned by Ann Owner")).toBeInTheDocument();
    // Expanding keeps the conversation: the strip lists the pin with its jump, and the pane records that the list is open.
    await user.click(toggle);
    expect(within(strip).getByRole("link", { name: /^Show in thread/ })).toHaveAttribute("href", "/rooms/12/threads/42?message=1");
    expect(screen.getByRole("region", { name: "#42 Disk full on db-3" })).toHaveAttribute("data-strip", "open");
  });

  test("expanded or not is remembered per person in the browser; a corrupt stored value reads as collapsed", async () => {
    const user = userEvent.setup();
    pinned = [{ ...THREAD_PIN, id: 4, kind: "MESSAGE", message: makeMessage({ id: 1, body: "Restart the replica first" }) }];
    serve();
    const first = renderWithProviders(<ThreadPane room={makeRoom()} numberParam="42" />);
    await user.click(await screen.findByRole("button", { name: /^Pinned: / }));
    expect(localStorage.getItem("adder.viewSettings.threadPane.pinsOpen")).toBe("true");
    first.unmount();
    const second = renderWithProviders(<ThreadPane room={makeRoom()} numberParam="42" />);
    expect(await screen.findByRole("button", { name: "Pinned in this thread (1)" })).toHaveAttribute("aria-expanded", "true");
    second.unmount();
    localStorage.setItem("adder.viewSettings.threadPane.pinsOpen", '"yes"');
    renderWithProviders(<ThreadPane room={makeRoom()} numberParam="42" />);
    expect(await screen.findByRole("button", { name: /^Pinned: / })).toHaveAttribute("aria-expanded", "false");
  });

  test("'Show in thread' for the message the URL already names jumps to it again; for another message the URL change is the jump", async () => {
    const user = userEvent.setup();
    localStorage.setItem("adder.viewSettings.threadPane.pinsOpen", "true");
    pinned = [{ ...THREAD_PIN, id: 4, kind: "MESSAGE", message: makeMessage({ id: 1, body: "Restart the replica first" }) }];
    serve();
    renderWithProviders(<ThreadPane room={makeRoom()} numberParam="42" />, { route: "/rooms/12/threads/42?message=1" });
    const messages = await screen.findByRole("region", { name: "Messages" });
    const item = await waitFor(() => {
      const found = messages.querySelector<HTMLElement>('[data-message-id="1"]');
      expect(found).toHaveFocus();
      return found!;
    });
    screen.getByRole("textbox", { name: "Reply" }).focus();
    expect(item).not.toHaveFocus();
    await user.click(screen.getByRole("link", { name: /^Show in thread/ }));
    await waitFor(() => expect(item).toHaveFocus());
  });

  test("the room's 51st pin has its own words, beside the button", async () => {
    const user = userEvent.setup();
    serve(() => jsonResponse(409, { type: "urn:adder:problem:pin-limit", status: 409, title: "Pin limit" }));
    renderWithProviders(<ThreadPane room={makeRoom()} numberParam="42" />);
    await user.click(await screen.findByRole("button", { name: "Pin this thread" }));
    expect(await screen.findByText("This room already has 50 pins. Unpin one first.")).toBeInTheDocument();
    expect(button()).toHaveAttribute("aria-pressed", "false");
  });

  test("a closed thread can still be pinned: a pin is the room's", async () => {
    thread = makeThread({ closed: true, closedAt: SOME_TIME });
    serve();
    renderWithProviders(<ThreadPane room={makeRoom()} numberParam="42" />);
    expect(await screen.findByRole("button", { name: "Pin this thread" })).toBeEnabled();
  });

  test("an ADMIN outside the room and an archived room get no button", async () => {
    serve();
    const outside = renderWithProviders(<ThreadPane room={makeRoom({ role: null })} numberParam="42" />);
    await screen.findByRole("region", { name: "#42 Disk full on db-3" });
    expect(screen.queryByRole("button", { name: "Pin this thread" })).not.toBeInTheDocument();
    outside.unmount();
    renderWithProviders(<ThreadPane room={makeRoom({ archived: true })} numberParam="42" />);
    await screen.findByRole("region", { name: "#42 Disk full on db-3" });
    expect(screen.queryByRole("button", { name: "Pin this thread" })).not.toBeInTheDocument();
  });
});
