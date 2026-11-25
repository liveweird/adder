import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { QueryClient } from "@tanstack/react-query";
import { act, screen, waitFor, within } from "@testing-library/react";
import MessageReactions from "./MessageReactions";
import i18n from "../i18n";
import { jsonResponse } from "../test/http";
import { makeMessage } from "../test/messageFixtures";
import { renderWithProviders } from "../test/render";
import type { MessageResponse } from "../api/messages";

// The real emoji-mart is a shadow-DOM custom element happy-dom cannot drive (EmojiButton.test.tsx).
vi.mock("./EmojiPicker", () => ({
  default: ({ onSelect }: { onSelect: (native: string) => void }) => <button onClick={() => onSelect("🎉")}>pick</button>,
}));

const THUMBS = { emoji: "👍", key: "a".repeat(64), count: 3, reacted: true, names: ["Ada", "Bob", "Cy"] };
const HEART = { emoji: "❤️", key: "b".repeat(64), count: 5, reacted: false, names: ["Ada", "Bob", "Cy"] };

describe("MessageReactions", () => {
  let mockFetch: ReturnType<typeof vi.fn>;
  const puts = () => mockFetch.mock.calls.filter(([, init]) => (init as RequestInit | undefined)?.method === "PUT") as [string, RequestInit][];

  beforeEach(() => {
    mockFetch = vi.fn().mockResolvedValue(new Response(null, { status: 204 }));
    vi.stubGlobal("fetch", mockFetch);
    localStorage.setItem("adder.auth.token", "fake-token");
  });
  afterEach(() => {
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  function renderReactions(message: MessageResponse, canReact = true, extras: { onError?: (m: string | null) => void; queryClient?: QueryClient } = {}) {
    return renderWithProviders(<MessageReactions roomId={12} message={message} canReact={canReact} onError={extras.onError ?? vi.fn()} />, { queryClient: extras.queryClient });
  }

  const reactionList = () => within(screen.getByRole("list", { name: "Reactions" }));

  test("a chip per emoji is a list item with a text label; the reader's own is pressed and the label does not repeat it", () => {
    renderReactions(makeMessage({ reactions: [THUMBS, HEART] }));
    // One item per emoji; the picker is no list item.
    expect(reactionList().getAllByRole("listitem")).toHaveLength(2);
    const own = screen.getByRole("button", { name: "👍, 3 reactions" });
    expect(own).toHaveAttribute("aria-pressed", "true");
    expect(own).toHaveTextContent("👍 3");
    const other = screen.getByRole("button", { name: "❤️, 5 reactions" });
    expect(other).toHaveAttribute("aria-pressed", "false");
  });

  test("a chip is described by the reactors' names for assistive technology, not only by the tooltip", () => {
    renderReactions(makeMessage({ reactions: [THUMBS, HEART] }));
    expect(screen.getByRole("button", { name: "👍, 3 reactions" })).toHaveAccessibleDescription("Ada, Bob, and Cy");
    expect(screen.getByRole("button", { name: "❤️, 5 reactions" })).toHaveAccessibleDescription("Ada, Bob, Cy and 2 more");
  });

  test("pressing a chip sends the opposite state of the reader's own reaction, to the message's reaction route, with the emoji in the body", async () => {
    const user = userEvent.setup();
    renderReactions(makeMessage({ id: 9, reactions: [THUMBS, HEART] }));
    await user.click(screen.getByRole("button", { name: "👍, 3 reactions" }));
    await waitFor(() => expect(puts()).toHaveLength(1));
    expect(puts()[0][0]).toBe("/api/v1/rooms/12/messages/9/reaction");
    expect(JSON.parse(puts()[0][1].body as string)).toEqual({ emoji: "👍", reacted: false });
    await user.click(screen.getByRole("button", { name: "❤️, 5 reactions" }));
    await waitFor(() => expect(puts()).toHaveLength(2));
    expect(JSON.parse(puts()[1][1].body as string)).toEqual({ emoji: "❤️", reacted: true });
  });

  test("after a 204 only the conversation of the thread is re-read", async () => {
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const invalidate = vi.spyOn(queryClient, "invalidateQueries");
    renderReactions(makeMessage({ threadNumber: 42, reactions: [THUMBS] }), true, { queryClient });
    await userEvent.setup().click(screen.getByRole("button", { name: "👍, 3 reactions" }));
    await waitFor(() => expect(invalidate).toHaveBeenCalledTimes(1));
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ["rooms", "threads", 12, "messages", 42] });
  });

  test("the picker adds the chosen emoji as the reader's own reaction, on a message with none", async () => {
    const user = userEvent.setup();
    renderReactions(makeMessage({ id: 9, reactions: [] }));
    await user.click(screen.getByRole("button", { name: "Add a reaction" }));
    await user.click(await screen.findByText("pick"));
    await waitFor(() => expect(puts()).toHaveLength(1));
    expect(JSON.parse(puts()[0][1].body as string)).toEqual({ emoji: "🎉", reacted: true });
  });

  test("hovering a chip names the first reactors: all of them, or the first three and how many more", async () => {
    const user = userEvent.setup();
    renderReactions(makeMessage({ reactions: [THUMBS, { ...HEART, names: ["Ada", "Bob", "Cy"], count: 5 }] }));
    await user.hover(screen.getByRole("button", { name: "👍, 3 reactions" }));
    expect(await screen.findByRole("tooltip")).toHaveTextContent("Ada, Bob, and Cy");
    await user.unhover(screen.getByRole("button", { name: "👍, 3 reactions" }));
    await user.hover(screen.getByRole("button", { name: "❤️, 5 reactions" }));
    await waitFor(() => expect(screen.getByRole("tooltip")).toHaveTextContent("Ada, Bob, Cy and 2 more"));
  });

  test("the people action opens a paged roster whose names link to profiles", async () => {
    mockFetch.mockImplementation((url: string) => Promise.resolve(jsonResponse(200, url.includes("page=2")
      ? { items: [{ userId: 9, name: "Cy" }], page: 2, pageSize: 20, total: 21 }
      : { items: [{ userId: 7, name: "Ada" }, { userId: 8, name: "Bob" }], page: 1, pageSize: 20, total: 21 })));
    renderReactions(makeMessage({ id: 9, reactions: [THUMBS] }));
    const user = userEvent.setup();
    await user.click(screen.getByRole("button", { name: "See everyone who reacted with 👍" }));
    expect(await screen.findByRole("dialog", { name: "👍 reactions" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Ada" })).toHaveAttribute("href", "/people/7");
    expect(screen.getByRole("link", { name: "Bob" })).toHaveAttribute("href", "/people/8");
    expect(mockFetch.mock.calls[0][0]).toBe(`/api/v1/rooms/12/messages/9/reactors?reactionKey=${THUMBS.key}&page=1&pageSize=20&sort=createdAt`);
    await user.click(screen.getByRole("button", { name: "Next page" }));
    expect(await screen.findByRole("link", { name: "Cy" })).toHaveAttribute("href", "/people/9");
    expect(mockFetch.mock.calls[1][0]).toBe(`/api/v1/rooms/12/messages/9/reactors?reactionKey=${THUMBS.key}&page=2&pageSize=20&sort=createdAt`);
    await user.click(screen.getByRole("button", { name: "Close" }));
    expect(screen.queryByRole("dialog", { name: "👍 reactions" })).toBeNull();
  });

  test("an emptied reaction roster stays open with an explanation", async () => {
    const user = userEvent.setup();
    mockFetch.mockResolvedValue(jsonResponse(200, { items: [], page: 1, pageSize: 20, total: 0 }));
    const view = renderReactions(makeMessage({ id: 9, reactions: [THUMBS] }), false);
    await user.click(screen.getByRole("button", { name: "See everyone who reacted with 👍" }));
    expect(await screen.findByText("Nobody has reacted with this emoji.")).toBeVisible();
    view.rerender(<MessageReactions roomId={12} message={makeMessage({ id: 9, reactions: [] })} canReact={false} onError={vi.fn()} />);
    expect(screen.getByRole("dialog", { name: "👍 reactions" })).toBeVisible();
  });

  test("an inaccessible reaction roster shows its error inside the dialog", async () => {
    const user = userEvent.setup();
    mockFetch.mockResolvedValue(jsonResponse(404, { title: "Message not found", status: 404 }));
    renderReactions(makeMessage({ id: 9, reactions: [THUMBS] }), false);
    await user.click(screen.getByRole("button", { name: "See everyone who reacted with 👍" }));
    const dialog = await screen.findByRole("dialog", { name: "👍 reactions" });
    expect(await within(dialog).findByRole("alert")).toBeVisible();
    expect(within(dialog).queryByRole("link")).toBeNull();
  });

  test("a name is text, never markup", async () => {
    const user = userEvent.setup();
    renderReactions(makeMessage({ reactions: [{ emoji: "👍", key: "a".repeat(64), count: 1, reacted: false, names: ["<b>Eve</b>"] }] }));
    await user.hover(screen.getByRole("button", { name: "👍, 1 reaction" }));
    expect(await screen.findByRole("tooltip")).toHaveTextContent("<b>Eve</b>");
    expect(screen.getByRole("button", { name: "👍, 1 reaction" })).toHaveAccessibleDescription("<b>Eve</b>");
    expect(document.querySelector("b")).toBeNull();
  });

  test("a 409 reaction-limit is told to the message, in its own words, and re-reads only the conversation", async () => {
    mockFetch.mockResolvedValue(jsonResponse(409, { type: "urn:adder:problem:reaction-limit", status: 409, title: "Limit" }));
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const invalidate = vi.spyOn(queryClient, "invalidateQueries");
    const onError = vi.fn();
    renderReactions(makeMessage({ reactions: [HEART] }), true, { onError, queryClient });
    await userEvent.setup().click(screen.getByRole("button", { name: "❤️, 5 reactions" }));
    await waitFor(() => expect(onError).toHaveBeenLastCalledWith("This message cannot take another reaction."));
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ["rooms", "threads", 12, "messages", 42] });
    expect(invalidate).not.toHaveBeenCalledWith({ queryKey: ["rooms"] });
  });

  test.each([
    [409, { type: "urn:adder:problem:thread-closed", status: 409, title: "Closed" }, "This thread was closed in the meantime, so its messages can no longer be changed."],
    [409, { type: "urn:adder:problem:room-archived", status: 409, title: "Archived" }, "This room was archived in the meantime, so its messages can no longer be changed."],
    [403, { status: 403, title: "Forbidden" }, "You do not have permission to do that in this room"],
    [404, { status: 404, title: "Not found" }, "This message no longer exists, or you no longer have access to its room — reload the page"],
  ])("a %i refusal is told in its own words and re-reads the whole room tree", async (status, body, words) => {
    mockFetch.mockResolvedValue(jsonResponse(status, body));
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const invalidate = vi.spyOn(queryClient, "invalidateQueries");
    const onError = vi.fn();
    renderReactions(makeMessage({ reactions: [HEART] }), true, { onError, queryClient });
    await userEvent.setup().click(screen.getByRole("button", { name: "❤️, 5 reactions" }));
    await waitFor(() => expect(onError).toHaveBeenLastCalledWith(words));
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ["rooms"] });
  });

  test("a chip press while a write is in flight is ignored; the guard is a ref, so two presses in one tick send one write", async () => {
    let finish: (response: Response) => void = () => {};
    mockFetch.mockReturnValue(new Promise<Response>((resolve) => (finish = resolve)));
    renderReactions(makeMessage({ reactions: [THUMBS, HEART] }));
    const heart = screen.getByRole("button", { name: "❤️, 5 reactions" });
    const thumbs = screen.getByRole("button", { name: "👍, 3 reactions" });
    // Both clicks are dispatched in one tick, before React could re-render: only a ref sees the first.
    act(() => {
      heart.click();
      thumbs.click();
    });
    expect(puts()).toHaveLength(1);
    await act(async () => finish(new Response(null, { status: 204 })));
  });

  test("a picker pick during an in-flight write is queued and sent after it", async () => {
    const answers: ((response: Response) => void)[] = [];
    mockFetch.mockImplementation(() => new Promise<Response>((resolve) => answers.push(resolve)));
    const user = userEvent.setup();
    renderReactions(makeMessage({ id: 9, reactions: [THUMBS] }));
    await user.click(screen.getByRole("button", { name: "👍, 3 reactions" }));
    expect(puts()).toHaveLength(1);
    await user.click(screen.getByRole("button", { name: "Add a reaction" }));
    await user.click(await screen.findByText("pick"));
    expect(puts()).toHaveLength(1);
    await act(async () => answers[0](new Response(null, { status: 204 })));
    await waitFor(() => expect(puts()).toHaveLength(2));
    expect(JSON.parse(puts()[1][1].body as string)).toEqual({ emoji: "🎉", reacted: true });
    await act(async () => answers[1](new Response(null, { status: 204 })));
  });

  test("the write is free again as soon as the 204 is in, without waiting for the conversation's re-read", async () => {
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    let release: () => void = () => {};
    // The re-read does not finish until released: a guard held across it would swallow the second press.
    vi.spyOn(queryClient, "invalidateQueries").mockReturnValue(new Promise<void>((resolve) => (release = resolve)));
    const user = userEvent.setup();
    renderReactions(makeMessage({ reactions: [THUMBS, HEART] }), true, { queryClient });
    await user.click(screen.getByRole("button", { name: "❤️, 5 reactions" }));
    await waitFor(() => expect(puts()).toHaveLength(1));
    await user.click(screen.getByRole("button", { name: "👍, 3 reactions" }));
    await waitFor(() => expect(puts()).toHaveLength(2));
    release();
  });

  test("a message without reactions shows the picker alone: no list, so the conversation's list of messages gains no empty list item", () => {
    renderReactions(makeMessage({ reactions: [] }));
    expect(screen.queryByRole("list")).toBeNull();
    expect(screen.queryByRole("listitem")).toBeNull();
    expect(screen.getByRole("button", { name: "Add a reaction" })).toBeInTheDocument();
  });

  test("a picker pick returns focus to the add button, and the result is told through a polite status", async () => {
    const user = userEvent.setup();
    renderReactions(makeMessage({ id: 9, reactions: [] }));
    await user.click(screen.getByRole("button", { name: "Add a reaction" }));
    await user.click(await screen.findByText("pick"));
    expect(screen.getByRole("button", { name: "Add a reaction" })).toHaveFocus();
    await waitFor(() => expect(screen.getByRole("status")).toHaveTextContent("You reacted with 🎉"));
  });

  test("when the pressed chip vanishes (its count reached 0) focus moves to the add button", async () => {
    const user = userEvent.setup();
    const solo = { emoji: "👍", key: "a".repeat(64), count: 1, reacted: true, names: ["Me"] };
    const { rerender } = renderReactions(makeMessage({ reactions: [solo] }));
    const chip = screen.getByRole("button", { name: "👍, 1 reaction" });
    await user.click(chip);
    expect(chip).toHaveFocus();
    await waitFor(() => expect(screen.getByRole("status")).toHaveTextContent("You removed your 👍 reaction"));
    rerender(<MessageReactions roomId={12} message={makeMessage({ reactions: [] })} canReact onError={vi.fn()} />);
    expect(chip).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Add a reaction" })).toHaveFocus();
  });

  test("a chip that survives its press keeps the focus", async () => {
    const user = userEvent.setup();
    const { rerender } = renderReactions(makeMessage({ reactions: [THUMBS] }));
    await user.click(screen.getByRole("button", { name: "👍, 3 reactions" }));
    await waitFor(() => expect(puts()).toHaveLength(1));
    rerender(<MessageReactions roomId={12} message={makeMessage({ reactions: [{ ...THUMBS, count: 2, reacted: false }] })} canReact onError={vi.fn()} />);
    expect(screen.getByRole("button", { name: "👍, 2 reactions" })).toHaveFocus();
  });

  test("where the reader may not react the chips are list items of text naming the reactors and the reader's own reaction, and there is no picker", () => {
    renderReactions(makeMessage({ reactions: [THUMBS, HEART] }), false);
    expect(screen.queryByRole("button", { name: "👍, 3 reactions" })).toBeNull();
    expect(screen.getByRole("button", { name: "See everyone who reacted with 👍" })).toBeInTheDocument();
    expect(screen.queryByRole("img")).toBeNull();
    expect(screen.queryByRole("status")).toBeNull();
    const [own, other] = reactionList().getAllByRole("listitem");
    expect(own).toHaveTextContent("👍, 3 reactions. You reacted. Ada, Bob, and Cy");
    expect(other).toHaveTextContent("❤️, 5 reactions. Ada, Bob, Cy and 2 more");
    expect(screen.queryByRole("button", { name: "Add a reaction" })).toBeNull();
  });

  test("where the reader may not react, their own reaction is marked apart from the others", () => {
    renderReactions(makeMessage({ reactions: [THUMBS, HEART] }), false);
    const [own, other] = reactionList().getAllByRole("listitem");
    expect(own.querySelector("[data-reacted]")).not.toBeNull();
    expect(other.querySelector("[data-reacted]")).toBeNull();
  });

  test.each([
    [3, "👍, 3 reakcje", "Ada, Bob i Cy"],
    [5, "👍, 5 reakcji", "Ada, Bob, Cy i 2 inne osoby"],
    [8, "👍, 8 reakcji", "Ada, Bob, Cy i 5 innych osób"],
  ])("in Polish a chip of %i reactions reads by the Polish plural rules, label and reactors alike", async (count, label, names) => {
    await i18n.changeLanguage("pl");
    try {
      renderReactions(makeMessage({ reactions: [{ emoji: "👍", key: "a".repeat(64), count, reacted: false, names: ["Ada", "Bob", "Cy"] }] }));
      expect(screen.getByRole("button", { name: label })).toHaveAccessibleDescription(names);
    } finally {
      await i18n.changeLanguage("en");
    }
  });

  test("nothing at all without reactions and without the right to react", () => {
    renderReactions(makeMessage({ reactions: [] }), false);
    expect(screen.queryByRole("list")).toBeNull();
  });

  test("a tombstone shows no reactions and no picker", () => {
    renderReactions(makeMessage({ body: null, deleted: true, reactions: [THUMBS] }));
    expect(screen.queryByRole("list")).toBeNull();
    expect(screen.queryByRole("button")).toBeNull();
  });
});
