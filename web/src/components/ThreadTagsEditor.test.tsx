import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { useQuery } from "@tanstack/react-query";
import ThreadTagsPanel from "./ThreadTagsPanel";
import { getThread, type ThreadResponse } from "../api/threads";
import { jsonResponse } from "../test/http";
import { makeRoom, makeThread } from "../test/messageFixtures";
import { renderWithProviders, screen, waitFor, within } from "../test/render";

type Categories = { items: { id: number; name: string; description: null; revision: number; tags: string[] }[]; page: number; pageSize: number; total: number };
const CATEGORIES: Categories = {
  items: [
    { id: 1, name: "Kind", description: null, revision: 1, tags: ["decision", "question"] },
    { id: 2, name: "Area", description: null, revision: 1, tags: ["data", "security"] },
  ],
  page: 1,
  pageSize: 100,
  total: 2,
};

/** The panel beside the thread query it normally sits under, so a refetch reaches it as it would in the side panel. */
function Harness({ room = makeRoom(), steward = true }: { room?: ReturnType<typeof makeRoom>; steward?: boolean }) {
  const { data } = useQuery({ queryKey: ["rooms", "threads", 12, "detail", 42], queryFn: () => getThread(12, 42) });
  return data ? <ThreadTagsPanel room={room} thread={data} steward={steward} /> : null;
}

describe("the thread's Tags section (1.2 D1, 1.2 A6)", () => {
  let mockFetch: ReturnType<typeof vi.fn>;
  let server: ThreadResponse;
  let putStatus: number;
  let putBody: unknown;
  let categories: Categories = CATEGORIES;
  const puts = () => mockFetch.mock.calls.filter(([, init]) => (init as RequestInit | undefined)?.method === "PUT");
  const putBodies = () => puts().map(([, init]) => JSON.parse((init as RequestInit).body as string));
  const picker = () => screen.findByRole("combobox", { name: "Tags" });

  beforeEach(() => {
    server = makeThread({ number: 42, revision: 3, tags: [{ name: "security", retired: false }] });
    putStatus = 204;
    putBody = undefined;
    categories = CATEGORIES;
    mockFetch = vi.fn((url: string, init?: RequestInit) => {
      if (init?.method === "PUT") return Promise.resolve(putStatus === 204 ? new Response(null, { status: 204 }) : jsonResponse(putStatus, putBody));
      if (url === "/api/v1/rooms/12/threads/42") return Promise.resolve(jsonResponse(200, server));
      if (url.startsWith("/api/v1/tag-categories?")) return Promise.resolve(jsonResponse(200, categories));
      return Promise.resolve(jsonResponse(200, { items: [], page: 1, pageSize: 50, total: 0 }));
    });
    vi.stubGlobal("fetch", mockFetch);
    localStorage.setItem("adder.auth.token", "fake-token");
  });
  afterEach(() => {
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  test("it shows the pills (or \"No tags\") and an Edit button that swaps in the picker, and Cancel puts the pills back with the button focused", async () => {
    const user = userEvent.setup();
    renderWithProviders(<Harness />);
    expect(await screen.findByText("security")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Edit tags" }));
    expect(await picker()).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Save tags" })).toBeDisabled();
    await user.click(screen.getByRole("button", { name: "Cancel" }));
    expect(await screen.findByRole("button", { name: "Edit tags" })).toHaveFocus();
    expect(puts()).toHaveLength(0);
  });

  test("a thread with no tag reads \"No tags\"", async () => {
    server = makeThread({ number: 42, tags: [] });
    renderWithProviders(<Harness />);
    expect(await screen.findByText("No tags")).toBeInTheDocument();
  });

  test("the picker lists the registry grouped by category; a save sends the WHOLE set under the revision the editor was opened at, then ends and re-reads", async () => {
    const user = userEvent.setup();
    renderWithProviders(<Harness />);
    await user.click(await screen.findByRole("button", { name: "Edit tags" }));
    await user.click(await picker());
    const kind = await screen.findByRole("group", { name: "Kind" });
    await user.click(within(kind).getByRole("option", { name: "question" }));
    await user.click(screen.getByRole("button", { name: "Save tags" }));
    await waitFor(() => expect(screen.getByRole("button", { name: "Edit tags" })).toBeInTheDocument());
    const [[url, init]] = puts();
    expect(url).toBe("/api/v1/rooms/12/threads/42/tags");
    expect(new Headers((init as RequestInit).headers).get("X-Expected-Revision")).toBe("3");
    expect(new Headers((init as RequestInit).headers).get("X-Mutation-Id")).toMatch(/^[0-9a-f-]{36}$/);
    expect(putBodies()).toEqual([{ tags: ["security", "question"] }]);
  });

  test("a retired tag is shown as retired, dropped from the set a save sends, and adding a tag to a thread that carries one succeeds", async () => {
    server = makeThread({ number: 42, revision: 4, tags: [{ name: "old-one", retired: true }, { name: "data", retired: false }] });
    const user = userEvent.setup();
    renderWithProviders(<Harness />);
    // Read-only: struck through, said in words, with the note that explains it.
    expect(await screen.findByText("old-one (retired)")).toBeInTheDocument();
    expect(screen.getByText(/struck-through tag was retired/i)).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Edit tags" }));
    await picker();
    // Editing: the retired tag is not in the picker (only live ones), it is listed under it with the cue, and Save is open even before a pick.
    const retired = document.querySelector("[data-retired]") as HTMLElement;
    expect(within(retired).getByText(/removed when you save/i)).toBeInTheDocument();
    expect(within(retired).getByText("old-one (retired)")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Save tags" })).toBeEnabled();
    await user.click(await picker());
    await user.click(await screen.findByRole("option", { name: "security" }));
    await user.click(screen.getByRole("button", { name: "Save tags" }));
    await waitFor(() => expect(puts()).toHaveLength(1));
    expect(putBodies()).toEqual([{ tags: ["data", "security"] }]);
  });

  test("a tag the registry no longer lists is retired whatever the thread's cached flag says: the editor seeds without it, so the first save is accepted", async () => {
    // A retirement emits no live hint (1.2 A7), so the thread read the page holds can still say `retired: false`.
    server = makeThread({ number: 42, revision: 4, tags: [{ name: "gone-since", retired: false }, { name: "data", retired: false }] });
    const user = userEvent.setup();
    renderWithProviders(<Harness />);
    await user.click(await screen.findByRole("button", { name: "Edit tags" }));
    await picker();
    const retired = await waitFor(() => document.querySelector("[data-retired]") as HTMLElement);
    expect(within(retired).getByText("gone-since (retired)")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Save tags" }));
    await waitFor(() => expect(puts()).toHaveLength(1));
    expect(putBodies()).toEqual([{ tags: ["data"] }]);
  });

  test("a 400 re-reads the registry and the thread: the retirement that raced the editor is then shown, and the next save goes through", async () => {
    // The registry the editor cached still lists `soon-gone`; by the time of the save it does not, so the save is a 400.
    categories = { items: [{ id: 1, name: "Kind", description: null, revision: 1, tags: ["data", "soon-gone"] }], page: 1, pageSize: 100, total: 1 };
    server = makeThread({ number: 42, revision: 4, tags: [{ name: "soon-gone", retired: false }, { name: "data", retired: false }] });
    putStatus = 400;
    putBody = { title: "Bad request", status: 400 };
    const user = userEvent.setup();
    renderWithProviders(<Harness />);
    await user.click(await screen.findByRole("button", { name: "Edit tags" }));
    await picker();
    await user.click(screen.getByRole("button", { name: "Remove data" }));
    await user.click(screen.getByRole("button", { name: "Remove soon-gone" }));
    categories = { items: [{ id: 1, name: "Kind", description: null, revision: 2, tags: ["data"] }], page: 1, pageSize: 100, total: 1 };
    server = makeThread({ number: 42, revision: 4, tags: [{ name: "soon-gone", retired: true }, { name: "data", retired: false }] });
    await user.click(screen.getByRole("button", { name: "Save tags" }));
    expect(await screen.findByText(/The tags could not be saved/)).toBeInTheDocument();
    const retired = await waitFor(() => document.querySelector("[data-retired]") as HTMLElement);
    expect(within(retired).getByText("soon-gone (retired)")).toBeInTheDocument();
    expect(mockFetch.mock.calls.filter(([url]) => String(url).startsWith("/api/v1/tag-categories?")).length).toBeGreaterThan(1);
    // The draft was edited, so it is kept; a save of it now names only live tags.
    putStatus = 204;
    await user.click(screen.getByRole("button", { name: "Save tags" }));
    await waitFor(() => expect(puts()).toHaveLength(2));
    expect(putBodies()[1]).toEqual({ tags: [] });
  });

  test("saving with nothing picked but a retired tag removes just the retired one", async () => {
    server = makeThread({ number: 42, revision: 4, tags: [{ name: "old-one", retired: true }, { name: "data", retired: false }] });
    const user = userEvent.setup();
    renderWithProviders(<Harness />);
    await user.click(await screen.findByRole("button", { name: "Edit tags" }));
    await picker();
    await user.click(screen.getByRole("button", { name: "Save tags" }));
    await waitFor(() => expect(puts()).toHaveLength(1));
    expect(putBodies()).toEqual([{ tags: ["data"] }]);
  });

  test("clearing every tag sends an empty set", async () => {
    const user = userEvent.setup();
    renderWithProviders(<Harness />);
    await user.click(await screen.findByRole("button", { name: "Edit tags" }));
    await picker();
    await user.click(screen.getByRole("button", { name: "Remove security" }));
    await user.click(screen.getByRole("button", { name: "Save tags" }));
    await waitFor(() => expect(puts()).toHaveLength(1));
    expect(putBodies()).toEqual([{ tags: [] }]);
  });

  test("a stale revision (409) keeps the pick under the orange notice and Reload re-seeds from the latest thread", async () => {
    putStatus = 409;
    putBody = { type: "urn:adder:problem:revision-conflict", title: "Conflict", status: 409 };
    const user = userEvent.setup();
    renderWithProviders(<Harness />);
    await user.click(await screen.findByRole("button", { name: "Edit tags" }));
    await user.click(await picker());
    await user.click(await screen.findByRole("option", { name: "data" }));
    await user.click(screen.getByRole("button", { name: "Save tags" }));
    expect(await screen.findByText("This thread was changed elsewhere")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Save tags" })).toBeDisabled();
    server = makeThread({ number: 42, revision: 5, tags: [{ name: "decision", retired: false }] });
    await user.click(screen.getByRole("button", { name: "Reload the latest version" }));
    await waitFor(() => expect(screen.queryByText("This thread was changed elsewhere")).toBeNull());
    expect((await screen.findAllByText("decision")).length).toBeGreaterThan(0);
    // The re-seeded editor starts from the latest thread's tags and is clean again (Save waits for a change).
    expect(screen.getByRole("button", { name: "Save tags" })).toBeDisabled();
  });

  test("a refusal has fixed words: a closed thread (403), an archived room (409) and a bad name (400)", async () => {
    const user = userEvent.setup();
    renderWithProviders(<Harness />);
    await user.click(await screen.findByRole("button", { name: "Edit tags" }));
    await user.click(await picker());
    await user.click(await screen.findByRole("option", { name: "data" }));
    putStatus = 403;
    await user.click(screen.getByRole("button", { name: "Save tags" }));
    expect(await screen.findByText(/only its owner, the room owner or an administrator can change its tags/)).toBeInTheDocument();
    putStatus = 409;
    putBody = { type: "urn:adder:problem:room-archived", title: "Archived", status: 409 };
    await user.click(screen.getByRole("button", { name: "Save tags" }));
    expect(await screen.findByText(/This room was archived in the meantime/)).toBeInTheDocument();
    putStatus = 400;
    putBody = { title: "Bad request", status: 400 };
    await user.click(screen.getByRole("button", { name: "Save tags" }));
    expect(await screen.findByText(/The tags could not be saved/)).toBeInTheDocument();
  });

  test("a closed thread is for stewards: a plain member sees the pills and the reason, no Edit button; a steward edits", async () => {
    server = makeThread({ number: 42, closed: true, tags: [{ name: "data", retired: false }] });
    const { unmount } = renderWithProviders(<Harness steward={false} />);
    expect(await screen.findByText("data")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Edit tags" })).toBeNull();
    expect(screen.getByText(/This thread is closed, so only its owner/)).toBeInTheDocument();
    unmount();
    renderWithProviders(<Harness steward />);
    expect(await screen.findByRole("button", { name: "Edit tags" })).toBeInTheDocument();
  });

  test("an archived room refuses everyone, stewards included", async () => {
    renderWithProviders(<Harness room={makeRoom({ archived: true })} steward />);
    expect(await screen.findByText(/This room is archived, so the tags can no longer change/)).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Edit tags" })).toBeNull();
  });
});
