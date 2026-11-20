import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { screen, waitFor } from "@testing-library/react";
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
  const putCalls = () => mockFetch.mock.calls.filter(([, init]) => (init as RequestInit | undefined)?.method === "PUT") as [string, RequestInit][];
  const button = () => screen.getByRole("button", { name: "Pin this thread" });

  function serve(put: () => Response = () => new Response(null, { status: 204 })) {
    mockFetch.mockImplementation((url: string, init?: RequestInit) => {
      if (init?.method === "PUT") return Promise.resolve(put());
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
