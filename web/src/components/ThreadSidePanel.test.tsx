import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import ThreadSidePanel from "./ThreadSidePanel";
import { headingOutline } from "../test/headings";
import { jsonResponse } from "../test/http";
import { makeRoom, makeThread } from "../test/messageFixtures";
import { renderWithProviders, screen, waitFor } from "../test/render";
import { SIDE_WORKFLOW, eventPage, makeEvent } from "../test/sideFixtures";

describe("ThreadSidePanel", () => {
  let mockFetch: ReturnType<typeof vi.fn>;

  function serve(workflow: Response = jsonResponse(200, SIDE_WORKFLOW)) {
    mockFetch.mockImplementation((url: string) => {
      if (url === "/api/v1/rooms/12/workflow") return Promise.resolve(workflow.clone());
      if (url.startsWith("/api/v1/rooms/12/threads/42/events?")) return Promise.resolve(jsonResponse(200, eventPage([makeEvent()])));
      if (url.includes("/members?")) return Promise.resolve(jsonResponse(200, { items: [], page: 1, pageSize: 50, total: 0 }));
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

  test("Owner, Details, Tags and History have their own h4 sections", async () => {
    serve();
    renderWithProviders(<ThreadSidePanel room={makeRoom()} thread={makeThread({ number: 42 })} />);
    expect(await screen.findByRole("button", { name: "Edit details" })).toBeInTheDocument();
    expect(headingOutline()).toEqual([
      [4, "Owner"],
      [4, "Details"],
      [4, "Tags"],
      [4, "History"],
    ]);
    expect(await screen.findByText("Started the thread in Triage")).toBeInTheDocument();
  });

  test("the thread's owner (a plain member of the room) is a steward: the owner picker is there; another member only reads the owner", async () => {
    localStorage.setItem("adder.auth.userId", "5");
    serve();
    const { unmount } = renderWithProviders(<ThreadSidePanel room={makeRoom({ role: "MEMBER" })} thread={makeThread({ number: 42, ownerId: 5 })} />);
    expect(await screen.findByRole("combobox", { name: "Owner" })).toBeInTheDocument();
    unmount();
    localStorage.setItem("adder.auth.userId", "6");
    renderWithProviders(<ThreadSidePanel room={makeRoom({ role: "MEMBER" })} thread={makeThread({ number: 42, ownerId: 5 })} />);
    await screen.findByRole("button", { name: "Edit details" });
    expect(screen.queryByRole("combobox", { name: "Owner" })).not.toBeInTheDocument();
  });

  test("an ADMIN who is not a member is a steward too", async () => {
    localStorage.setItem("adder.auth.roles", JSON.stringify(["ADMIN"]));
    serve();
    renderWithProviders(<ThreadSidePanel room={makeRoom({ role: null })} thread={makeThread({ number: 42 })} />);
    expect(await screen.findByRole("combobox", { name: "Owner" })).toBeInTheDocument();
  });

  test("while the room's workflow loads the details wait, and a failed read says they are unavailable", async () => {
    mockFetch.mockImplementation(() => new Promise(() => undefined));
    const { unmount } = renderWithProviders(<ThreadSidePanel room={makeRoom()} thread={makeThread({ number: 42 })} />);
    expect(screen.getAllByRole("status", { name: "Loading…" }).length).toBeGreaterThanOrEqual(1);
    unmount();
    serve(jsonResponse(500, { title: "Boom", status: 500 }));
    renderWithProviders(<ThreadSidePanel room={makeRoom()} thread={makeThread({ number: 42 })} />);
    await waitFor(() => expect(screen.getByText("Not available until the room's statuses have loaded.")).toBeInTheDocument());
    expect(screen.getByRole("heading", { level: 4, name: "History" })).toBeInTheDocument();
  });
});
