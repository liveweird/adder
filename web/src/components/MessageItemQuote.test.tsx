// A message's quote (1.3): the "Quote" action in the menu and as an icon button, and the block that shows the message it quotes; the rest of the item is in MessageItem.test.tsx.
import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { cleanup, screen, within } from "@testing-library/react";
import MessageItem from "./MessageItem";
import { SOME_TIME, makeMessage } from "../test/messageFixtures";
import { renderWithProviders } from "../test/render";
import { browserTimeZone, formatDateTime } from "../utils/formatDate";
import type { MessageActions } from "../utils/messageAccess";
import type { MessageResponse } from "../api/messages";

const ZONE = browserTimeZone();
const WHEN = formatDateTime(SOME_TIME, ZONE);
const NAME = `Operations for message by Cy Author on ${WHEN}`;

const renderItem = (message: MessageResponse, actions: MessageActions) =>
  renderWithProviders(
    <ul>
      <MessageItem roomId={12} message={message} actions={actions} zone={ZONE} />
    </ul>,
  );

describe("MessageItem: quotes", () => {
  beforeEach(() => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(null, { status: 204 })));
    localStorage.setItem("adder.auth.token", "fake-token");
  });
  afterEach(() => {
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  const quoteOf = (overrides: Partial<NonNullable<MessageResponse["quote"]>> = {}): NonNullable<MessageResponse["quote"]> => ({
    messageId: 31,
    authorId: 4,
    authorName: "Ann Author",
    deleted: false,
    body: "Is the disk full?",
    truncated: false,
    editedAt: null,
    mentions: [],
    groupMentions: [],
    roomId: 12,
    threadNumber: 42,
    ...overrides,
  });
  const quotable: MessageActions = { edit: false, remove: null, pin: true, react: true, quote: true };
  const renderQuotable = (message: MessageResponse, onQuote = vi.fn(), actions: MessageActions = quotable) =>
    renderWithProviders(
      <ul>
        <MessageItem roomId={12} message={message} actions={actions} zone={ZONE} onQuote={onQuote} />
      </ul>,
    );

  test("Quote is the FIRST item of the kebab menu, and an icon button beside the menu says the same word", async () => {
    const user = userEvent.setup();
    const onQuote = vi.fn();
    const message = makeMessage({ id: 9 });
    renderQuotable(message, onQuote);
    // The one-click button is there with no menu opened.
    expect(screen.getByRole("button", { name: /^Quote the message by/ })).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: NAME }));
    const items = await screen.findAllByRole("menuitem");
    expect(items[0]).toHaveAccessibleName("Quote");
    await user.click(items[0]);
    expect(onQuote).toHaveBeenCalledExactlyOnceWith(message);
  });

  test("the icon button quotes in one click", async () => {
    const onQuote = vi.fn();
    const message = makeMessage({ id: 9 });
    renderQuotable(message, onQuote);
    await userEvent.setup().click(screen.getByRole("button", { name: /^Quote the message by/ }));
    expect(onQuote).toHaveBeenCalledExactlyOnceWith(message);
  });

  test("offers the destination picker for a live message even when this source thread cannot take a local reply", async () => {
    const onQuoteElsewhere = vi.fn();
    const message = makeMessage({ id: 9 });
    renderWithProviders(
      <ul>
        <MessageItem
          roomId={12}
          message={message}
          actions={{ edit: false, remove: null, pin: false, react: false, quote: false }}
          zone={ZONE}
          onQuoteElsewhere={onQuoteElsewhere}
        />
      </ul>,
    );
    const user = userEvent.setup();
    await user.click(screen.getByRole("button", { name: NAME }));
    await user.click(await screen.findByRole("menuitem", { name: "Quote in another thread…" }));
    expect(onQuoteElsewhere).toHaveBeenCalledExactlyOnceWith(message);
  });

  test("Quote comes before Edit and Delete for the author", async () => {
    const user = userEvent.setup();
    renderQuotable(makeMessage(), vi.fn(), { ...quotable, edit: true, remove: "delete" });
    await user.click(screen.getByRole("button", { name: NAME }));
    const names = (await screen.findAllByRole("menuitem")).map((item) => item.textContent);
    expect(names.slice(0, 3)).toEqual(["Quote", "Edit", "Pin"]);
    expect(names.at(-1)).toBe("Delete");
  });

  test("offers no quote without the action (a closed thread, an archived room, a non-member, a tombstone) or without a handler, and none while editing", async () => {
    const user = userEvent.setup();
    const { unmount } = renderItem(makeMessage(), { edit: false, remove: null, pin: true, react: false, quote: false });
    expect(screen.queryByRole("button", { name: "Quote" })).not.toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: NAME }));
    expect(await screen.findByRole("menuitem", { name: "Pin" })).toBeInTheDocument();
    expect(screen.queryByRole("menuitem", { name: "Quote" })).not.toBeInTheDocument();
    unmount();
    // The action without a handler (a list rendered with no composer behind it) shows nothing either.
    renderItem(makeMessage(), quotable);
    expect(screen.queryByRole("button", { name: "Quote" })).not.toBeInTheDocument();
    cleanup();
    renderQuotable(makeMessage(), vi.fn(), { ...quotable, edit: true, remove: "delete" });
    await user.click(screen.getByRole("button", { name: NAME }));
    await user.click(await screen.findByRole("menuitem", { name: "Edit" }));
    expect(screen.queryByRole("button", { name: "Quote" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: NAME })).not.toBeInTheDocument();
  });

  test("a message that quotes another shows the block above its text, as ONE link to the quoted message", () => {
    renderItem(makeMessage({ body: "Agreed, restarting now.", quote: quoteOf() }), { edit: false, remove: null });
    const link = screen.getByRole("link", { name: /^Quoted message by Ann Author/ });
    expect(link).toHaveAttribute("href", "/rooms/12/threads/42?message=31");
    expect(link).toHaveTextContent("Ann Author");
    expect(link).toHaveTextContent("Is the disk full?");
    const item = screen.getByRole("listitem");
    // Above the body, in the document order.
    expect(link.compareDocumentPosition(within(item).getByText("Agreed, restarting now."))).toBe(Node.DOCUMENT_POSITION_FOLLOWING);
  });

  test("a deleted quote reads as deleted with no text, and an edited one says so in words", () => {
    const { unmount } = renderItem(makeMessage({ quote: quoteOf({ deleted: true, body: null }) }), { edit: false, remove: null });
    expect(screen.getByText("The quoted message was deleted.")).toBeInTheDocument();
    expect(screen.queryByText("Is the disk full?")).not.toBeInTheDocument();
    unmount();
    renderItem(makeMessage({ quote: quoteOf({ editedAt: SOME_TIME }) }), { edit: false, remove: null });
    expect(screen.getByText("(edited)")).toBeInTheDocument();
  });

  test("a message with no quote member at all (an older server's answer) reads as no quote", () => {
    const message = makeMessage();
    delete (message as Partial<MessageResponse>).quote;
    renderItem(message, { edit: false, remove: null });
    expect(screen.queryByRole("link", { name: /^Quoted message by/ })).not.toBeInTheDocument();
    expect(screen.getByText("Restarted the", { exact: false })).toBeInTheDocument();
  });

  test("the icon button is named with its message like the menu, and a tooltip says Quote", async () => {
    const user = userEvent.setup();
    renderQuotable(makeMessage());
    const button = screen.getByRole("button", { name: `Quote the message by Cy Author on ${WHEN}` });
    await user.hover(button);
    expect(await screen.findByRole("tooltip")).toHaveTextContent("Quote");
  });

  test("a message that quotes nothing has no block, and a tombstone shows none", () => {
    const { unmount } = renderItem(makeMessage({ quote: null }), { edit: false, remove: null });
    expect(screen.queryByRole("link", { name: /^Quoted message by/ })).not.toBeInTheDocument();
    unmount();
    renderItem(makeMessage({ body: null, deleted: true, quote: quoteOf() }), { edit: false, remove: null });
    expect(screen.queryByRole("link", { name: /^Quoted message by/ })).not.toBeInTheDocument();
  });

  test("the block stays above the editor", async () => {
    const user = userEvent.setup();
    renderQuotable(makeMessage({ quote: quoteOf() }), vi.fn(), { ...quotable, edit: true, remove: "delete" });
    await user.click(screen.getByRole("button", { name: NAME }));
    await user.click(await screen.findByRole("menuitem", { name: "Edit" }));
    const box = screen.getByRole("textbox", { name: "Edit message" });
    const link = screen.getByRole("link", { name: /^Quoted message by Ann Author/ });
    expect(link.compareDocumentPosition(box)).toBe(Node.DOCUMENT_POSITION_FOLLOWING);
  });

  test("following the block tells the pane which message was asked for", async () => {
    const onShowQuoted = vi.fn();
    renderWithProviders(
      <ul>
        <MessageItem roomId={12} message={makeMessage({ quote: quoteOf({ messageId: 31 }) })} actions={{ edit: false, remove: null }} zone={ZONE} onShowQuoted={onShowQuoted} />
      </ul>,
    );
    await userEvent.setup().click(screen.getByRole("link", { name: /^Quoted message by/ }));
    expect(onShowQuoted).toHaveBeenCalledExactlyOnceWith(31);
  });
});
