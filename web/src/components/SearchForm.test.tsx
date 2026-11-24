import { afterEach, describe, expect, test, vi } from "vitest";
import { cleanup, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { renderWithProviders } from "../test/render";
import { SEARCH_ASSISTANT_KEY } from "../utils/searchQueryEditor";
import SearchForm from "./SearchForm";

const listRooms = vi.fn();
const searchUserDirectory = vi.fn();

vi.mock("../hooks/useTagCategories", () => ({ useTagCategories: () => ({ data: [] }) }));
vi.mock("../api/rooms", () => ({ listRooms: (...args: unknown[]) => listRooms(...args) }));
vi.mock("../api/users", () => ({ searchUserDirectory: (...args: unknown[]) => searchUserDirectory(...args) }));
vi.mock("./EmojiButton", () => ({
  default: ({ label, onSelect }: { label: string; onSelect: (emoji: string) => void }) => (
    <button type="button" aria-label={label} onClick={() => onSelect("🚀")}>emoji</button>
  ),
}));

function renderForm({ q = "", structured = false, onSubmit = vi.fn() } = {}) {
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
    />,
  );
  return onSubmit;
}

afterEach(() => {
  cleanup();
  listRooms.mockReset();
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
