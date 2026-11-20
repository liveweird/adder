import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { QueryClient } from "@tanstack/react-query";
import { screen, waitFor } from "@testing-library/react";
import MessageReactions from "./MessageReactions";
import { jsonResponse } from "../test/http";
import { makeMessage } from "../test/messageFixtures";
import { renderWithProviders } from "../test/render";
import type { MessageResponse } from "../api/messages";

// The real emoji-mart is a shadow-DOM custom element happy-dom cannot drive (EmojiButton.test.tsx).
vi.mock("./EmojiPicker", () => ({
  default: ({ onSelect }: { onSelect: (native: string) => void }) => <button onClick={() => onSelect("🎉")}>pick</button>,
}));

const THUMBS = { emoji: "👍", count: 3, reacted: true, names: ["Ada", "Bob", "Cy"] };
const HEART = { emoji: "❤️", count: 5, reacted: false, names: ["Ada", "Bob", "Cy"] };

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

  test("a chip per emoji shows the emoji and its count; the reader's own is pressed and says so", () => {
    renderReactions(makeMessage({ reactions: [THUMBS, HEART] }));
    const own = screen.getByRole("button", { name: "👍 3, you reacted" });
    expect(own).toHaveAttribute("aria-pressed", "true");
    expect(own).toHaveTextContent("👍 3");
    const other = screen.getByRole("button", { name: "❤️ 5" });
    expect(other).toHaveAttribute("aria-pressed", "false");
  });

  test("pressing a chip sends the opposite state of the reader's own reaction, to the message's reaction route, with the emoji in the body", async () => {
    const user = userEvent.setup();
    renderReactions(makeMessage({ id: 9, reactions: [THUMBS, HEART] }));
    await user.click(screen.getByRole("button", { name: "👍 3, you reacted" }));
    await waitFor(() => expect(puts()).toHaveLength(1));
    expect(puts()[0][0]).toBe("/api/v1/rooms/12/messages/9/reaction");
    expect(JSON.parse(puts()[0][1].body as string)).toEqual({ emoji: "👍", reacted: false });
    await user.click(screen.getByRole("button", { name: "❤️ 5" }));
    await waitFor(() => expect(puts()).toHaveLength(2));
    expect(JSON.parse(puts()[1][1].body as string)).toEqual({ emoji: "❤️", reacted: true });
  });

  test("after a 204 only the conversation of the thread is re-read", async () => {
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const invalidate = vi.spyOn(queryClient, "invalidateQueries");
    renderReactions(makeMessage({ threadNumber: 42, reactions: [THUMBS] }), true, { queryClient });
    await userEvent.setup().click(screen.getByRole("button", { name: "👍 3, you reacted" }));
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
    await user.hover(screen.getByRole("button", { name: "👍 3, you reacted" }));
    expect(await screen.findByText("Ada, Bob, and Cy")).toBeInTheDocument();
    await user.unhover(screen.getByRole("button", { name: "👍 3, you reacted" }));
    await user.hover(screen.getByRole("button", { name: "❤️ 5" }));
    expect(await screen.findByText("Ada, Bob, Cy and 2 more")).toBeInTheDocument();
  });

  test("a name is text, never markup", async () => {
    const user = userEvent.setup();
    renderReactions(makeMessage({ reactions: [{ emoji: "👍", count: 1, reacted: false, names: ["<b>Eve</b>"] }] }));
    await user.hover(screen.getByRole("button", { name: "👍 1" }));
    expect(await screen.findByText("<b>Eve</b>")).toBeInTheDocument();
    expect(document.querySelector("b")).toBeNull();
  });

  test("a 409 reaction-limit is told to the message, in its own words, and re-reads only the conversation", async () => {
    mockFetch.mockResolvedValue(jsonResponse(409, { type: "urn:adder:problem:reaction-limit", status: 409, title: "Limit" }));
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const invalidate = vi.spyOn(queryClient, "invalidateQueries");
    const onError = vi.fn();
    renderReactions(makeMessage({ reactions: [HEART] }), true, { onError, queryClient });
    await userEvent.setup().click(screen.getByRole("button", { name: "❤️ 5" }));
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
    await userEvent.setup().click(screen.getByRole("button", { name: "❤️ 5" }));
    await waitFor(() => expect(onError).toHaveBeenLastCalledWith(words));
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ["rooms"] });
  });

  test("a press while a write is in flight is ignored", async () => {
    let finish: (response: Response) => void = () => {};
    mockFetch.mockReturnValue(new Promise<Response>((resolve) => (finish = resolve)));
    const user = userEvent.setup();
    renderReactions(makeMessage({ reactions: [THUMBS, HEART] }));
    await user.click(screen.getByRole("button", { name: "❤️ 5" }));
    await user.click(screen.getByRole("button", { name: "👍 3, you reacted" }));
    expect(puts()).toHaveLength(1);
    finish(new Response(null, { status: 204 }));
    await waitFor(() => expect(screen.getByRole("group", { name: "Reactions" })).not.toHaveAttribute("aria-busy"));
  });

  test("where the reader may not react the chips are read-only text with their label, and there is no picker", () => {
    renderReactions(makeMessage({ reactions: [THUMBS, HEART] }), false);
    expect(screen.queryByRole("button")).toBeNull();
    expect(screen.getByRole("img", { name: "👍 3, you reacted" })).toBeInTheDocument();
    expect(screen.getByRole("img", { name: "❤️ 5" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Add a reaction" })).toBeNull();
  });

  test("nothing at all without reactions and without the right to react", () => {
    renderReactions(makeMessage({ reactions: [] }), false);
    expect(screen.queryByRole("group")).toBeNull();
  });

  test("a tombstone shows no reactions and no picker", () => {
    renderReactions(makeMessage({ body: null, deleted: true, reactions: [THUMBS] }));
    expect(screen.queryByRole("group")).toBeNull();
    expect(screen.queryByRole("button")).toBeNull();
  });
});
