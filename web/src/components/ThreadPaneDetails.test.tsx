import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { screen, within } from "@testing-library/react";
import ThreadPane from "./ThreadPane";
import { headingOutline } from "../test/headings";
import { jsonResponse } from "../test/http";
import { makeMessage, makeRoom, makeThread, messagePage } from "../test/messageFixtures";
import { renderWithProviders } from "../test/render";

// The side panel takes space only when opened, and the header's icon actions (1.2 D4; the rest of the pane is in ThreadPane.test.tsx).
const WORKFLOW = {
  revision: 1,
  statuses: [{ key: "triage", label: "Triage", colour: "BLUE", category: "OPEN", initial: true, threadCount: 1 }],
  fields: [],
};
const STORAGE_KEY = "adder.viewSettings.threadPane.detailsOpen";

describe("ThreadPane: the side panel opens on demand", () => {
  let mockFetch: ReturnType<typeof vi.fn>;

  function serve() {
    mockFetch.mockImplementation((url: string) => {
      if (url === "/api/v1/rooms/12/threads/42") return Promise.resolve(jsonResponse(200, makeThread({ watching: false, watcherCount: 2 })));
      if (url === "/api/v1/rooms/12/workflow") return Promise.resolve(jsonResponse(200, WORKFLOW));
      if (url.startsWith("/api/v1/rooms/12/pins?")) return Promise.resolve(jsonResponse(200, { items: [], page: 1, pageSize: 50, total: 0 }));
      if (url.startsWith("/api/v1/rooms/12/threads/42/messages?")) return Promise.resolve(jsonResponse(200, messagePage([makeMessage({ id: 1 })])));
      if (url.startsWith("/api/v1/rooms/12/threads/42/events?")) return Promise.resolve(jsonResponse(200, { items: [], page: 1, pageSize: 10, total: 0 }));
      return Promise.resolve(jsonResponse(404, { title: "Not found", status: 404 }));
    });
  }

  beforeEach(() => {
    mockFetch = vi.fn();
    vi.stubGlobal("fetch", mockFetch);
    localStorage.setItem("adder.auth.token", "fake-token");
    serve();
  });
  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
    localStorage.clear();
  });

  test("it is collapsed by default behind the header's Details button, which opens and closes it", async () => {
    const { container } = renderWithProviders(<ThreadPane room={makeRoom()} numberParam="42" />);
    const toggle = await screen.findByRole("button", { name: "Details" });
    const panel = document.getElementById(toggle.getAttribute("aria-controls")!)!;
    expect(toggle).toHaveAttribute("aria-expanded", "false");
    expect(panel).toHaveAttribute("data-open", "false");
    expect(container.querySelector("[data-side]")).toHaveAttribute("data-side", "closed");
    expect(within(panel).getByRole("heading", { level: 4, name: "Status" })).toBeInTheDocument();
    await userEvent.click(toggle);
    expect(toggle).toHaveAttribute("aria-expanded", "true");
    expect(panel).toHaveAttribute("data-open", "true");
    expect(container.querySelector("[data-side]")).toHaveAttribute("data-side", "open");
    await userEvent.click(toggle);
    expect(toggle).toHaveAttribute("aria-expanded", "false");
    expect(panel).toHaveAttribute("data-open", "false");
  });

  test("the choice is one switch per person per browser: written on a click and read by the next pane", async () => {
    serve();
    const first = renderWithProviders(<ThreadPane room={makeRoom()} numberParam="42" />);
    await userEvent.click(await screen.findByRole("button", { name: "Details" }));
    expect(localStorage.getItem(STORAGE_KEY)).toBe("true");
    first.unmount();
    renderWithProviders(<ThreadPane room={makeRoom()} numberParam="42" />);
    expect(await screen.findByRole("button", { name: "Details" })).toHaveAttribute("aria-expanded", "true");
    await userEvent.click(screen.getByRole("button", { name: "Details" }));
    expect(localStorage.getItem(STORAGE_KEY)).toBe("false");
  });

  test("a corrupt stored value falls back to collapsed", async () => {
    localStorage.setItem(STORAGE_KEY, '"yes"');
    renderWithProviders(<ThreadPane room={makeRoom()} numberParam="42" />);
    expect(await screen.findByRole("button", { name: "Details" })).toHaveAttribute("aria-expanded", "false");
  });

  test("a browser that refuses storage still opens and closes the panel for the visit", async () => {
    vi.spyOn(Storage.prototype, "getItem").mockImplementation((key) => {
      if (key === STORAGE_KEY) throw new Error("denied");
      return key === "adder.auth.token" ? "fake-token" : null;
    });
    vi.spyOn(Storage.prototype, "setItem").mockImplementation((key) => {
      if (key === STORAGE_KEY) throw new Error("quota");
    });
    renderWithProviders(<ThreadPane room={makeRoom()} numberParam="42" />);
    const toggle = await screen.findByRole("button", { name: "Details" });
    expect(toggle).toHaveAttribute("aria-expanded", "false");
    await userEvent.click(toggle);
    expect(toggle).toHaveAttribute("aria-expanded", "true");
  });

  test("the header's four actions are icon buttons that keep their accessible names and states", async () => {
    renderWithProviders(<ThreadPane room={makeRoom()} numberParam="42" />);
    expect(await screen.findByRole("button", { name: "Focus on this thread" })).toHaveAttribute("aria-pressed", "false");
    expect(screen.getByRole("button", { name: "Pin this thread" })).toHaveAttribute("aria-pressed", "false");
    expect(screen.getByRole("button", { name: "Watch this thread" })).toHaveAttribute("aria-pressed", "false");
    expect(screen.getByText("Watchers: 2")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Details" })).toHaveAttribute("aria-expanded", "false");
    // Icon-only: no visible text, the name is the label.
    for (const name of ["Focus on this thread", "Pin this thread", "Watch this thread", "Details"]) expect(screen.getByRole("button", { name })).toHaveTextContent("");
  });

  test("the panel is a focusable, labelled region: its scroller is reachable by keyboard even when nothing inside can take focus", async () => {
    renderWithProviders(<ThreadPane room={makeRoom()} numberParam="42" />);
    const toggle = await screen.findByRole("button", { name: "Details" });
    await userEvent.click(toggle);
    const panel = await screen.findByRole("region", { name: "Thread details" });
    expect(panel).toHaveAttribute("tabindex", "0");
    expect(panel.id).toBe(toggle.getAttribute("aria-controls"));
  });

  test("the outline is unchanged: the pane's h3, then the panel's five h4 sections (collapsed ones are hidden by CSS only)", async () => {
    renderWithProviders(<ThreadPane room={makeRoom()} numberParam="42" />);
    await screen.findByRole("region", { name: "#42 Disk full on db-3" });
    expect(headingOutline()).toEqual([
      [3, "#42 Disk full on db-3"],
      [4, "Status"],
      [4, "Owner"],
      [4, "Details"],
      [4, "Tags"],
      [4, "History"],
    ]);
  });
});
