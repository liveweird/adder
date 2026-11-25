import { describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { fireEvent, screen, within } from "@testing-library/react";
import { useLocation } from "react-router-dom";
import MessageQuote from "./MessageQuote";
import type { MessageQuoteSummary } from "../api/messages";
import { renderWithProviders } from "../test/render";

function makeQuote(overrides: Partial<MessageQuoteSummary> = {}): MessageQuoteSummary {
  return {
    messageId: 31,
    authorId: 4,
    authorName: "Ann Author",
    deleted: false,
    body: "Is the **disk** full on db-3?",
    truncated: false,
    editedAt: null,
    mentions: [],
    groupMentions: [],
    roomId: 12,
    threadNumber: 42,
    ...overrides,
  };
}

const Where = () => {
  const location = useLocation();
  return <output aria-label="where" data-state={JSON.stringify(location.state)}>{location.pathname + location.search}</output>;
};

describe("MessageQuote", () => {
  test("is ONE link whose name is its content (so it holds the visible words): who is quoted and what they said, with the author in text and a plain one-line excerpt", () => {
    renderWithProviders(<MessageQuote roomId={12} threadNumber={42} sourceMessageId={55} quote={makeQuote()} />);
    // No aria-label: the name is built from the visually hidden lead-in and the visible words (axe `label-content-name-mismatch`).
    const link = screen.getByRole("link", { name: "Quoted message by Ann Author Is the disk full on db-3?" });
    expect(link).not.toHaveAttribute("aria-label");
    expect(link).toHaveAttribute("href", "/rooms/12/threads/42?message=31");
    // The author's name is text (a link cannot hold a link), the excerpt is plain text: no Markdown element, no nested link.
    expect(within(link).getByText("Ann Author")).toBeInTheDocument();
    expect(within(link).getByText("Is the disk full on db-3?")).toBeInTheDocument();
    expect(within(link).queryByRole("link")).not.toBeInTheDocument();
    expect(link.querySelector("strong")).toBeNull();
    // The icon is decoration: the words are the mark.
    expect(link.querySelector("svg")).toHaveAttribute("aria-hidden", "true");
    expect(screen.queryByText("(edited)")).not.toBeInTheDocument();
  });

  test("shows a mention in the quoted text as the name its own list gives", () => {
    renderWithProviders(<MessageQuote roomId={12} threadNumber={42} sourceMessageId={55} quote={makeQuote({ body: "Ask <@5> about it", mentions: [{ userId: 5, name: "Bob Starter" }] })} />);
    expect(screen.getByText("Ask @Bob Starter about it")).toBeInTheDocument();
  });

  test("never renders HTML or Markdown from the quoted text", () => {
    renderWithProviders(<MessageQuote roomId={12} threadNumber={42} sourceMessageId={55} quote={makeQuote({ body: "<img src=x onerror=alert(1)> [click](https://evil.example)" })} />);
    const link = screen.getByRole("link", { name: /^Quoted message by/ });
    expect(link.querySelector("img")).toBeNull();
    expect(link.querySelector("a")).toBeNull();
    expect(link).toHaveTextContent("<img src=x onerror=alert(1)> click");
  });

  test("ends in an ellipsis when the server cut the quoted body", () => {
    renderWithProviders(<MessageQuote roomId={12} threadNumber={42} sourceMessageId={55} quote={makeQuote({ body: "A very long message cut at a word", truncated: true })} />);
    expect(screen.getByText("A very long message cut at a word…")).toBeInTheDocument();
  });

  test("marks a quote whose message was edited with the word, which is in the link's name too (never colour alone)", () => {
    renderWithProviders(<MessageQuote roomId={12} threadNumber={42} sourceMessageId={55} quote={makeQuote({ editedAt: 1_700_000_000_000 })} />);
    const link = screen.getByRole("link", { name: "Quoted message by Ann Author (edited) Is the disk full on db-3?" });
    expect(within(link).getByText("(edited)")).toBeInTheDocument();
  });

  test("a deleted quote says so in words, shows no text and stays a link to the tombstone", () => {
    renderWithProviders(<MessageQuote roomId={12} threadNumber={42} sourceMessageId={55} quote={makeQuote({ deleted: true, body: null, editedAt: null })} />);
    const link = screen.getByRole("link", { name: "Quoted message by Ann Author The quoted message was deleted." });
    expect(link).toHaveAttribute("href", "/rooms/12/threads/42?message=31");
    expect(within(link).getByText("The quoted message was deleted.")).toBeInTheDocument();
    expect(screen.queryByText("(edited)")).not.toBeInTheDocument();
  });

  test("keeps the list's keys of the URL and the focus view, like the pin strip's jump, and drops a search page's own", async () => {
    renderWithProviders(
      <>
        <MessageQuote roomId={12} threadNumber={42} sourceMessageId={55} quote={makeQuote()} />
        <Where />
      </>,
      { route: "/rooms/12/threads/42?scope=closed&order=number&focus=1&message=5" },
    );
    const link = screen.getByRole("link", { name: /^Quoted message by/ });
    expect(link).toHaveAttribute("href", "/rooms/12/threads/42?message=31&scope=closed&order=number&focus=1");
    await userEvent.setup().click(link);
    expect(screen.getByLabelText("where")).toHaveTextContent("/rooms/12/threads/42?message=31&scope=closed&order=number&focus=1");
    expect(screen.getByLabelText("where")).toHaveAttribute(
      "data-state",
      JSON.stringify({ quoteReturnRoomId: 12, quoteReturnThreadNumber: 42, quoteReturnMessageId: 55 }),
    );
  });

  test("tells the pane which message was asked for, so the jump can be repeated when the URL already names it", async () => {
    const onShow = vi.fn();
    renderWithProviders(<MessageQuote roomId={12} threadNumber={42} sourceMessageId={55} quote={makeQuote({ messageId: 31 })} onShow={onShow} />, { route: "/rooms/12/threads/42?message=31" });
    await userEvent.setup().click(screen.getByRole("link", { name: /^Quoted message by/ }));
    expect(onShow).toHaveBeenCalledWith(31);
  });

  test("a cross-room quote links to its source and keeps the reply's full location for the return action", async () => {
    const onShow = vi.fn();
    renderWithProviders(
      <>
        <MessageQuote
          roomId={12}
          threadNumber={42}
          sourceMessageId={55}
          quote={makeQuote({ roomId: 9, threadNumber: 7 })}
          onShow={onShow}
        />
        <Where />
      </>,
      { route: "/rooms/12/threads/42" },
    );
    const link = screen.getByRole("link", { name: /^Quoted message by/ });
    expect(link).toHaveAttribute("href", "/rooms/9/threads/7?message=31");
    await userEvent.setup().click(link);
    expect(onShow).not.toHaveBeenCalled();
    expect(screen.getByLabelText("where")).toHaveAttribute(
      "data-state",
      JSON.stringify({ quoteReturnRoomId: 12, quoteReturnThreadNumber: 42, quoteReturnMessageId: 55 }),
    );
  });

  test.each([{ ctrlKey: true }, { metaKey: true }, { shiftKey: true }, { altKey: true }, { button: 1 }])(
    "a modified or non-primary click leaves the conversation alone (%j)", (gesture) => {
      const onShow = vi.fn();
      renderWithProviders(
        <>
          <MessageQuote roomId={12} threadNumber={42} sourceMessageId={55} quote={makeQuote()} onShow={onShow} />
          <Where />
        </>,
        { route: "/rooms/12/threads/42" },
      );
      fireEvent.click(screen.getByRole("link", { name: /^Quoted message by/ }), gesture);
      expect(onShow).not.toHaveBeenCalled();
      expect(screen.getByLabelText("where")).toHaveTextContent("/rooms/12/threads/42");
    },
  );
});
