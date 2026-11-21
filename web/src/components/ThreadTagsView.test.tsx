import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { screen, waitFor, within } from "@testing-library/react";
import ThreadList from "./ThreadList";
import ThreadPane from "./ThreadPane";
import { jsonResponse } from "../test/http";
import { makeMessage, makeRoom, makeThread, messagePage } from "../test/messageFixtures";
import { renderWithProviders } from "../test/render";
import { SIDE_WORKFLOW, eventPage, makeEvent } from "../test/sideFixtures";

// A thread's curated tags (1.2 D3): pills on a list row (the row is one link, so they are not links) and in the pane header's tag row, and the
// side panel's "Tags" section; the hashtags keep their own rows.
const AT = Date.UTC(2026, 5, 1, 9, 30);
const tag = (name: string, retired = false) => ({ name, retired });
const row = (number: number, title: string, tags: { name: string; retired: boolean }[]) => ({
  id: number,
  number,
  title,
  status: "triage",
  closed: false,
  ownerId: 5,
  ownerName: "Ann Owner",
  messageCount: 2,
  lastActivityAt: AT,
  createdAt: AT,
  unreadCount: 0,
  watching: false,
  hashtags: [],
  tags,
});

describe("tags in the room view", () => {
  const originalScrollIntoView = Element.prototype.scrollIntoView;
  let mockFetch: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    mockFetch = vi.fn();
    vi.stubGlobal("fetch", mockFetch);
    localStorage.setItem("adder.auth.token", "fake-token");
  });
  afterEach(() => {
    vi.unstubAllGlobals();
    localStorage.clear();
    Element.prototype.scrollIntoView = originalScrollIntoView;
  });

  function serve(thread = makeThread({ number: 42 }), items = [row(2, "Disk full on db-3", [])]) {
    mockFetch.mockImplementation((url: string) => {
      if (url.startsWith("/api/v1/rooms/12/threads?")) return Promise.resolve(jsonResponse(200, { items, page: 1, pageSize: 50, total: items.length }));
      if (url === "/api/v1/rooms/12/threads/42") return Promise.resolve(jsonResponse(200, thread));
      if (url === "/api/v1/rooms/12/workflow") return Promise.resolve(jsonResponse(200, SIDE_WORKFLOW));
      if (url === "/api/v1/tag-categories") return Promise.resolve(jsonResponse(200, { items: [] }));
      if (url.startsWith("/api/v1/rooms/12/pins?")) return Promise.resolve(jsonResponse(200, { items: [], page: 1, pageSize: 50, total: 0 }));
      if (url.startsWith("/api/v1/rooms/12/threads/42/messages?")) return Promise.resolve(jsonResponse(200, messagePage([makeMessage({ id: 1 })])));
      if (url.startsWith("/api/v1/rooms/12/threads/42/events?")) return Promise.resolve(jsonResponse(200, eventPage([makeEvent()])));
      return Promise.resolve(jsonResponse(404, { title: "Not found", status: 404 }));
    });
  }

  test("a list row shows up to three tags as pills and \"+N\" in words for the rest, a retired one struck through and said, and stays ONE link with no link inside", async () => {
    serve(undefined, [
      row(2, "Disk full on db-3", [tag("data"), tag("old", true), tag("question"), tag("security"), tag("zeta")]),
      row(1, "Old alert", [tag("data")]),
      row(3, "Quiet", []),
    ]);
    renderWithProviders(<ThreadList room={makeRoom()} activeNumber={null} />);
    const many = await screen.findByRole("link", { name: /Disk full on db-3/ });
    const pills = many.querySelector("[data-row-tags]") as HTMLElement;
    expect(within(pills).getByText("data")).toBeInTheDocument();
    expect(within(pills).getByText("old (retired)")).toBeInTheDocument();
    expect(within(pills).getByText("question")).toBeInTheDocument();
    expect(within(pills).queryByText("security")).toBeNull();
    expect(within(pills).getByText("+2")).toBeInTheDocument();
    expect(within(pills).getByText("2 more tags")).toBeInTheDocument();
    expect(many.querySelectorAll("a, button")).toHaveLength(0);
    expect(within(screen.getByRole("link", { name: /Old alert/ })).getByText("data")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: /Quiet/ }).querySelector("[data-row-tags]")).toBeNull();
  });

  test("the pane header's tag row shows up to three pills in one group, and \"+N\" opens the details with every tag in its Tags section", async () => {
    serve(makeThread({ number: 42, tags: ["a", "b", "c", "d", "e"].map((name) => tag(name)) }));
    renderWithProviders(<ThreadPane room={makeRoom()} numberParam="42" />);
    const [group] = await screen.findAllByRole("group", { name: "Tags" });
    expect(within(group).getAllByText(/^[a-e]$/)).toHaveLength(3);
    expect(document.querySelector("[data-pills=true]")).not.toBeNull();
    const toggle = screen.getByRole("button", { name: "Details" });
    expect(toggle).toHaveAttribute("aria-expanded", "false");
    const scrolled = vi.fn();
    Element.prototype.scrollIntoView = scrolled;
    await userEvent.click(within(group).getByRole("button", { name: "+2: show 2 more tags in the details" }));
    expect(toggle).toHaveAttribute("aria-expanded", "true");
    await waitFor(() => expect(scrolled).toHaveBeenCalledTimes(1));
    expect(scrolled.mock.instances[0]).toHaveAttribute("data-section", "tags");
    const section = screen.getByRole("heading", { level: 4, name: "Tags" }).closest("[role=group]") as HTMLElement;
    expect(within(section).getAllByText(/^[a-e]$/)).toHaveLength(5);
  });

  test("a thread with no tag has no tag row, and its Tags section says so", async () => {
    serve();
    renderWithProviders(<ThreadPane room={makeRoom()} numberParam="42" />);
    await screen.findByRole("button", { name: "Details" });
    expect(screen.getAllByRole("group", { name: "Tags" })).toHaveLength(1); // only the side panel's section
    expect(document.querySelector("[data-pills]")).toBeNull();
    expect(screen.getByText("No tags")).toBeInTheDocument();
  });
});
