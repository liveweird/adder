import { afterEach, describe, expect, test, vi } from "vitest";
import { cleanup, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { renderWithProviders } from "../test/render";
import { SEARCH_ASSISTANT_KEY } from "../utils/searchQueryEditor";
import SearchForm from "./SearchForm";
import type { RoomWorkflow } from "../api/rooms";

const listRooms = vi.fn();
const getRoom = vi.fn();
const getRoomWorkflow = vi.fn();
const listRoomHashtags = vi.fn();
const searchUserDirectory = vi.fn();

vi.mock("../hooks/useTagCategories", () => ({ useTagCategories: () => ({ data: [] }) }));
vi.mock("../api/rooms", () => ({
  getRoom: (...args: unknown[]) => getRoom(...args),
  getRoomWorkflow: (...args: unknown[]) => getRoomWorkflow(...args),
  listRooms: (...args: unknown[]) => listRooms(...args),
}));
vi.mock("../api/threads", () => ({ listRoomHashtags: (...args: unknown[]) => listRoomHashtags(...args) }));
vi.mock("../api/users", () => ({ searchUserDirectory: (...args: unknown[]) => searchUserDirectory(...args) }));
vi.mock("./EmojiButton", () => ({
  default: ({ label, onSelect }: { label: string; onSelect: (emoji: string) => void }) => (
    <button type="button" aria-label={label} onClick={() => onSelect("🚀")}>emoji</button>
  ),
}));

const STATUSES: RoomWorkflow["statuses"] = [
  { key: "investigating", label: "Investigating", colour: "BLUE", category: "OPEN", initial: true, threadCount: 4 },
  { key: "resolved", label: "Resolved", colour: "TEAL", category: "CLOSED", initial: false, threadCount: 2 },
];

function renderForm({ q = "", structured = false, onSubmit = vi.fn(), roomId, statuses }: {
  q?: string;
  structured?: boolean;
  onSubmit?: (q: string, structured: boolean) => void;
  roomId?: number;
  statuses?: RoomWorkflow["statuses"];
} = {}) {
  renderWithProviders(
    <SearchForm
      label="Search all your rooms"
      q={q}
      scope="all"
      watching={false}
      threadTag={[]}
      structured={structured}
      onSubmit={onSubmit}
      onScope={vi.fn()}
      onWatching={vi.fn()}
      onThreadTag={vi.fn()}
      assistantRoomId={roomId}
      assistantStatuses={statuses}
    />,
  );
  return onSubmit;
}

afterEach(() => {
  cleanup();
  listRooms.mockReset();
  getRoom.mockReset();
  getRoomWorkflow.mockReset();
  listRoomHashtags.mockReset();
  searchUserDirectory.mockReset();
});

describe("SearchForm structured query assistant", () => {
  test("keeps assistant lookups outside the search-result invalidation prefix", () => {
    expect(SEARCH_ASSISTANT_KEY[0]).not.toBe("search");
  });

  test("keeps the existing searchbox contract and submits a plain query as plain", async () => {
    const onSubmit = renderForm();
    const box = screen.getByRole("searchbox", { name: "Search all your rooms" });
    await userEvent.type(box, "outage{Enter}");
    expect(onSubmit).toHaveBeenCalledWith("outage", false);
    expect(screen.getByRole("button", { name: "Search syntax" })).toBeInTheDocument();
  });

  test("inserts a format at the caret and submits it as structured", async () => {
    const onSubmit = renderForm();
    const user = userEvent.setup();
    const box = screen.getByRole("searchbox", { name: "Search all your rooms" });
    await user.type(box, "disk ");
    await user.click(screen.getByRole("button", { name: "Search syntax" }));
    expect(screen.getByRole("button", { name: /has:reaction Messages with any reaction/ })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /from:"Ada Lovelace" Messages written/ })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /status:investigating Threads in a workflow status/ })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /hashtag:incident Threads containing a hashtag/ })).toBeInTheDocument();
    await user.click(await screen.findByRole("button", { name: /in:"Night Shift" Search in one room/ }));
    expect(box).toHaveValue("disk in:");
    expect(box).toHaveFocus();
    await user.type(box, "Night{Enter}");
    expect(onSubmit).toHaveBeenCalledWith("disk in:Night", true);
  });

  test("offers operator completion and moves to it with ArrowDown", async () => {
    renderForm();
    const user = userEvent.setup();
    const box = screen.getByRole("searchbox");
    await user.type(box, "fro");
    const suggestion = screen.getByRole("button", { name: /from:/ });
    await user.keyboard("{ArrowDown}");
    expect(suggestion).toHaveFocus();
    await user.keyboard("{Enter}");
    expect(box).toHaveValue("from:");
  });

  test("shows an explicit correction without rewriting the draft", async () => {
    renderForm();
    const user = userEvent.setup();
    const box = screen.getByRole("searchbox");
    await user.type(box, "form:ada");
    expect(box).toHaveValue("form:ada");
    expect(screen.getByText("Did you mean from:ada?")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Use suggestion" }));
    expect(box).toHaveValue("from:ada");
  });

  test("suggests bounded room and directory matches and inserts stable ids", async () => {
    listRooms.mockResolvedValue({ items: [{ id: 12, name: "Night Shift" }] });
    searchUserDirectory.mockResolvedValue({ items: [{ id: 7, name: "Ada Lovelace", email: "ada@example.com" }] });
    renderForm();
    const user = userEvent.setup();
    const box = screen.getByRole("searchbox");
    await user.type(box, "in:ni");
    await user.click(await screen.findByRole("button", { name: "Night Shift Room" }));
    expect(box).toHaveValue("in:#12");
    expect(listRooms).toHaveBeenCalledWith({ page: 1, pageSize: 8, sort: "name", name: "ni", member: true });

    await user.clear(box);
    await user.type(box, "owner:ad");
    await user.click(await screen.findByRole("button", { name: "Ada Lovelace ada@example.com" }));
    expect(box).toHaveValue("owner:#7");
    expect(searchUserDirectory).toHaveBeenCalledWith({ q: "ad", page: 1, pageSize: 8, sort: "name" });
  });

  test("reuses the emoji picker for reaction and message-emoji filters", async () => {
    renderForm();
    const user = userEvent.setup();
    const box = screen.getByRole("searchbox");
    await user.click(screen.getByRole("button", { name: "Search syntax" }));
    await user.click(screen.getByRole("button", { name: "Choose a reaction" }));
    expect(box).toHaveValue("has:reaction:🚀");
    await user.click(screen.getByRole("button", { name: "Choose an emoji in a message" }));
    expect(box).toHaveValue("has:reaction:🚀 has:emoji:🚀");
  });

  test("suggests stable status keys and exact indexed hashtags in a known room", async () => {
    listRoomHashtags.mockResolvedValue({ items: [{ tag: "incident-żółć", threadCount: 3 }] });
    renderForm({ roomId: 12, statuses: STATUSES });
    const user = userEvent.setup();
    const box = screen.getByRole("searchbox");
    await user.type(box, "status:inv");
    await user.click(screen.getByRole("button", { name: /Investigating.*Status/ }));
    expect(box).toHaveValue("status:investigating");
    await user.clear(box);
    await user.type(box, "hashtag:#inc");
    await user.click(await screen.findByRole("button", { name: /incident-żółć.*Hashtag/ }));
    expect(box).toHaveValue("hashtag:incident-żółć");
    expect(listRoomHashtags).toHaveBeenCalledWith(12, { page: 1, pageSize: 8, sort: "-threadCount", q: "inc" });
  });

  test("inserts indexed hashtag operands as one clause when normalization introduced whitespace or quotes", async () => {
    const tag = 'a 1/2"';
    listRoomHashtags.mockResolvedValue({ items: [{ tag, threadCount: 1 }] });
    const onSubmit = renderForm({ roomId: 12 });
    const user = userEvent.setup();
    const box = screen.getByRole("searchbox");
    await user.type(box, "replica hashtag:a");
    await user.click(await screen.findByRole("button", { name: /a 1\/2".*Hashtag/ }));
    expect(box).toHaveValue('replica hashtag:"a 1/2\\""');
    await user.keyboard("{Enter}");
    expect(onSubmit).toHaveBeenCalledWith('replica hashtag:"a 1/2\\""', true);
  });

  test("global status and hashtag values ask for one room until in: resolves a joined room", async () => {
    getRoom.mockResolvedValue({ id: 12, name: "Night Shift", role: "MEMBER" });
    getRoomWorkflow.mockResolvedValue({ statuses: STATUSES, fields: [], revision: 1 });
    renderForm();
    const user = userEvent.setup();
    const box = screen.getByRole("searchbox");
    await user.type(box, "status:inv");
    expect(screen.getByText("Choose one room with in: to see its statuses and hashtags.")).toBeVisible();
    expect(getRoomWorkflow).not.toHaveBeenCalled();
    await user.clear(box);
    await user.type(box, "in:#12 status:inv");
    expect(await screen.findByRole("button", { name: /Investigating.*Status/ })).toBeVisible();
    expect(getRoom).toHaveBeenCalledWith(12);
    expect(getRoomWorkflow).toHaveBeenCalledWith(12);
  });

  test("resolves an exact joined room name before offering hashtag values", async () => {
    listRooms.mockResolvedValue({ items: [{ id: 12, name: "Night Shift", role: "MEMBER" }] });
    listRoomHashtags.mockResolvedValue({ items: [{ tag: "incident", threadCount: 7 }] });
    renderForm();
    const user = userEvent.setup();
    const box = screen.getByRole("searchbox");
    await user.type(box, 'in:"Night Shift" hashtag:inc');
    expect(await screen.findByRole("button", { name: /incident.*Hashtag/ })).toBeVisible();
    expect(listRooms).toHaveBeenCalledWith({ page: 1, pageSize: 8, sort: "name", name: "Night Shift", member: true });
  });

  test("drops hashtag values from a previous room while the next room lookup settles", async () => {
    let finishSecond: () => void = () => undefined;
    const secondHashtags = new Promise<{ items: Array<{ tag: string; threadCount: number }> }>((resolve) => {
      finishSecond = () => resolve({ items: [{ tag: "incident-new", threadCount: 2 }] });
    });
    getRoom.mockImplementation((id: number) => Promise.resolve({ id, name: `Room ${id}`, role: "MEMBER" }));
    listRoomHashtags.mockImplementation((id: number) => id === 12
      ? Promise.resolve({ items: [{ tag: "incident-old", threadCount: 5 }] })
      : secondHashtags);
    renderForm();
    const user = userEvent.setup();
    const box = screen.getByRole("searchbox");
    await user.type(box, "in:#12 hashtag:inc");
    await waitFor(() => expect(listRoomHashtags).toHaveBeenCalled());
    expect(await screen.findByRole("button", { name: /incident-old.*Hashtag/ })).toBeVisible();
    await user.clear(box);
    await user.type(box, "in:#13 hashtag:inc");
    await waitFor(() => expect(listRoomHashtags).toHaveBeenLastCalledWith(13, { page: 1, pageSize: 8, sort: "-threadCount", q: "inc" }));
    expect(screen.queryByRole("button", { name: /incident-old/ })).not.toBeInTheDocument();
    finishSecond();
    expect(await screen.findByRole("button", { name: /incident-new.*Hashtag/ })).toBeVisible();
  });

  test("suggestions from a previous lookup cannot be selected while the next lookup loads", async () => {
    const bob = { items: [{ id: 8, name: "Bob Jones", email: "bob@example.com" }] };
    let finishLookup = () => {};
    const pending = new Promise<typeof bob>((resolve) => {
      finishLookup = () => resolve(bob);
    });
    searchUserDirectory
      .mockResolvedValueOnce({ items: [{ id: 7, name: "Alice Smith", email: "alice@example.com" }] })
      .mockReturnValueOnce(pending);
    renderForm();
    const user = userEvent.setup();
    const box = screen.getByRole("searchbox");
    await user.type(box, "from:al");
    expect(await screen.findByRole("button", { name: /Alice Smith/ })).toBeVisible();
    await user.clear(box);
    await user.type(box, "from:bo");
    await waitFor(() => expect(searchUserDirectory).toHaveBeenLastCalledWith({ q: "bo", page: 1, pageSize: 8, sort: "name" }));
    expect(screen.queryByRole("button", { name: /Alice Smith/ })).not.toBeInTheDocument();
    finishLookup();
    expect(await screen.findByRole("button", { name: /Bob Jones/ })).toBeVisible();
  });

  test("keeps a structured URL draft in structured mode and reports its longer limit", async () => {
    const onSubmit = renderForm({ q: "a".repeat(513), structured: true });
    expect(screen.getByText("Use at most 512 characters in a structured query.")).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "Search" }));
    expect(onSubmit).not.toHaveBeenCalled();
    expect(screen.getByRole("switch", { name: "Structured mode" })).toBeChecked();
  });

  test("structured mode can be selected for bare words and stays active while editing a shared query", async () => {
    const onSubmit = renderForm({ q: "disk", structured: true });
    const user = userEvent.setup();
    const box = screen.getByRole("searchbox");
    const mode = screen.getByRole("switch", { name: "Structured mode" });
    await user.type(box, " alert{Enter}");
    expect(onSubmit).toHaveBeenLastCalledWith("disk alert", true);
    await user.click(mode);
    await user.type(box, "{Enter}");
    expect(onSubmit).toHaveBeenLastCalledWith("disk alert", false);
    await user.click(mode);
    await user.clear(box);
    await user.type(box, "x{Enter}");
    expect(onSubmit).toHaveBeenLastCalledWith("x", true);
  });
});
