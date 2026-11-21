import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { useLocation } from "react-router-dom";
import { screen, waitFor, within } from "@testing-library/react";
import ThreadList from "./ThreadList";
import type { RoomResponse } from "../api/rooms";
import { jsonResponse } from "../test/http";
import { renderWithProviders } from "../test/render";

// The thread list's "Tags" filter (1.2 D3): the curated registry, grouped by category, written as the repeated `threadTag` key beside the
// hashtags' `tag`. The rest of the panel is `ThreadFilters.test.tsx`.
const ROOM: RoomResponse = {
  id: 12, name: "Night Shift", description: null, archived: false, archivedAt: null, role: "MEMBER", memberCount: 3,
  workflowRevision: 1, lastActivityAt: 1, createdAt: 1, updatedAt: 1,
};
const WORKFLOW = { revision: 1, statuses: [], fields: [] };
const CATEGORIES = {
  items: [
    { id: 1, name: "Kind", description: null, revision: 1, tags: ["decision", "question"] },
    { id: 2, name: "Area", description: null, revision: 1, tags: ["security"] },
  ],
};
const THREADS = { items: [], page: 1, pageSize: 50, total: 0 };
const HASHTAGS = { items: [{ tag: "outage", threadCount: 4 }], page: 1, pageSize: 50, total: 1 };

function Where() {
  const { pathname, search } = useLocation();
  return <output aria-label="where">{pathname + search}</output>;
}
const where = () => screen.getByLabelText("where").textContent;

describe("the thread filter panel's Tags control", () => {
  let mockFetch: ReturnType<typeof vi.fn>;
  const listCalls = () => mockFetch.mock.calls.map(([url]) => url as string).filter((url) => url.startsWith("/api/v1/rooms/12/threads?"));

  beforeEach(() => {
    mockFetch = vi.fn((url: string) => {
      if (url.startsWith("/api/v1/rooms/12/threads?")) return Promise.resolve(jsonResponse(200, THREADS));
      if (url === "/api/v1/rooms/12/workflow") return Promise.resolve(jsonResponse(200, WORKFLOW));
      if (url.startsWith("/api/v1/rooms/12/tags?")) return Promise.resolve(jsonResponse(200, HASHTAGS));
      if (url === "/api/v1/tag-categories") return Promise.resolve(jsonResponse(200, CATEGORIES));
      return Promise.resolve(jsonResponse(200, { items: [], page: 1, pageSize: 50, total: 0 }));
    });
    vi.stubGlobal("fetch", mockFetch);
    localStorage.setItem("adder.auth.token", "fake-token");
    localStorage.setItem("adder.auth.userId", "5");
  });
  afterEach(() => {
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  async function openPanel(user: ReturnType<typeof userEvent.setup>, route = "/rooms/12") {
    renderWithProviders(
      <>
        <ThreadList room={ROOM} activeNumber={null} />
        <Where />
      </>,
      { route },
    );
    await user.click(await screen.findByRole("button", { name: /Filters/ }));
  }

  test("the Tags control offers the registry's live tags grouped by category, and its picks write the repeated threadTag key beside the hashtags", async () => {
    const user = userEvent.setup();
    await openPanel(user);
    const tags = screen.getByRole("combobox", { name: "Tags" });
    expect(tags).toHaveAttribute("placeholder", "All");
    await user.click(tags);
    const kind = await screen.findByRole("group", { name: "Kind" });
    expect(within(kind).getAllByRole("option").map((option) => option.textContent)).toEqual(["decision", "question"]);
    expect(within(screen.getByRole("group", { name: "Area" })).getByRole("option", { name: "security" })).toBeInTheDocument();
    await user.click(within(kind).getByRole("option", { name: "question" }));
    await user.click(screen.getByRole("option", { name: "security" }));
    await waitFor(() => expect(where()).toBe("/rooms/12?threadTag=question&threadTag=security"));
    expect(listCalls().at(-1)).toBe("/api/v1/rooms/12/threads?page=1&pageSize=50&sort=-lastActivityAt&closed=false&threadTag=question&threadTag=security");
    // It is its own dimension: a hashtag pick is another key, and Clear drops both.
    await user.click(screen.getByRole("combobox", { name: "Hashtags" }));
    await user.click(await screen.findByRole("option", { name: "#outage (4)" }));
    await waitFor(() => expect(where()).toBe("/rooms/12?tag=outage&threadTag=question&threadTag=security"));
    await user.click(screen.getByRole("button", { name: "Remove question" }));
    await waitFor(() => expect(where()).toBe("/rooms/12?tag=outage&threadTag=security"));
    await user.click(screen.getByRole("button", { name: "Clear filters" }));
    await waitFor(() => expect(where()).toBe("/rooms/12"));
  });

  test("a curated tag the URL names that the registry no longer lists keeps its pill and its filter (a retired tag still finds its threads)", async () => {
    const user = userEvent.setup();
    await openPanel(user, "/rooms/12?threadTag=retired-one");
    expect(await screen.findByRole("button", { name: "Remove retired-one" })).toBeInTheDocument();
    await waitFor(() => expect(listCalls().at(-1)).toContain("threadTag=retired-one"));
  });
});
