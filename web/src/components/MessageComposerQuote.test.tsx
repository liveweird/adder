// The reply box and the message it quotes (1.3): the line above the box, the id Send carries, what a failure keeps; the rest of the composer is in MessageComposer.test.tsx.
import { useState } from "react";
import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { screen, waitFor } from "@testing-library/react";
import MessageComposer, { type QuoteRequest } from "./MessageComposer";
import ThreadDraftsProvider from "./ThreadDraftsProvider";
import type { QuoteDraft } from "../hooks/useThreadDrafts";
import { jsonResponse } from "../test/http";
import { makeMessage, makeRoom, makeThread } from "../test/messageFixtures";
import { renderWithProviders } from "../test/render";

describe("MessageComposer: quoting a message", () => {
  let mockFetch: ReturnType<typeof vi.fn>;
  const posts = () => mockFetch.mock.calls.filter(([, init]) => (init as RequestInit | undefined)?.method === "POST") as [string, RequestInit][];
  const box = () => screen.getByRole("textbox", { name: "Reply" });

  beforeEach(() => {
    mockFetch = vi.fn().mockResolvedValue(jsonResponse(201, makeMessage({ id: 77 })));
    vi.stubGlobal("fetch", mockFetch);
    localStorage.setItem("adder.auth.token", "fake-token");
  });
  afterEach(() => {
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  const picked: QuoteDraft = { id: 31, authorId: 4, authorName: "Ann Author", body: "Is the **disk** full on db-3?", mentions: [], groupMentions: [] };
  const other: QuoteDraft = { id: 32, authorId: 5, authorName: "Bob Starter", body: "No, the log is.", mentions: [], groupMentions: [] };
  const bodyOf = (call: [string, RequestInit]) => JSON.parse(call[1].body as string) as Record<string, unknown>;

  /** The pane's side of "Quote": buttons that make a numbered request for the open thread, plus the thread, pane and closed switches of `Harness`. */
  function QuoteHarness() {
    const [number, setNumber] = useState(42);
    const [shown, setShown] = useState(true);
    const [request, setRequest] = useState<QuoteRequest | null>(null);
    const pick = (quote: QuoteDraft) => setRequest((prev) => ({ threadNumber: number, quote, seq: (prev?.seq ?? 0) + 1 }));
    return (
      <ThreadDraftsProvider>
        <button onClick={() => pick(picked)}>quote Ann</button>
        <button onClick={() => pick(other)}>quote Bob</button>
        <button onClick={() => setNumber(number === 42 ? 43 : 42)}>switch thread</button>
        <button onClick={() => setShown(!shown)}>toggle pane</button>
        {shown && <MessageComposer room={makeRoom()} thread={makeThread({ number })} onPosted={vi.fn()} quoteRequest={request} />}
      </ThreadDraftsProvider>
    );
  }

  test("a quote request shows the line above the box, tells assistive technology once and takes the focus", async () => {
    const user = userEvent.setup();
    renderWithProviders(<QuoteHarness />);
    expect(screen.queryByText("Quoting Ann Author")).not.toBeInTheDocument();
    // The status region is there before a quote is: what appears in it is announced.
    const status = screen.getAllByRole("status").find((node) => node.textContent === "");
    expect(status).toBeDefined();
    await user.click(screen.getByRole("button", { name: "quote Ann" }));
    expect(await screen.findByText("Is the disk full on db-3?")).toBeInTheDocument();
    expect(status).toHaveTextContent("Quoting Ann Author: Is the disk full on db-3?");
    // Once the menu it came from has let go, the box has the focus.
    await waitFor(() => expect(box()).toHaveFocus());
    // Said once to assistive technology: the visible line's words are hidden from it, the status line has them.
    expect(screen.getByText("Quoting Ann Author").closest("[aria-hidden='true']")).not.toBeNull();
  });

  test("every new pick changes what the status line says", async () => {
    const user = userEvent.setup();
    renderWithProviders(<QuoteHarness />);
    const status = screen.getAllByRole("status").find((node) => node.textContent === "")!;
    await user.click(screen.getByRole("button", { name: "quote Ann" }));
    expect(status).toHaveTextContent("Quoting Ann Author: Is the disk full on db-3?");
    await user.click(screen.getByRole("button", { name: "quote Bob" }));
    expect(status).toHaveTextContent("Quoting Bob Starter: No, the log is.");
    await user.click(screen.getByRole("button", { name: "Remove the quote" }));
    expect(status).toBeEmptyDOMElement();
  });

  test("Send carries the quoted message's id; a post clears the text and the quote together", async () => {
    const user = userEvent.setup();
    renderWithProviders(<QuoteHarness />);
    await user.click(screen.getByRole("button", { name: "quote Ann" }));
    await user.type(box(), "Agreed");
    await user.click(screen.getByRole("button", { name: "Send" }));
    await waitFor(() => expect(posts()).toHaveLength(1));
    expect(bodyOf(posts()[0])).toEqual({ body: "Agreed", quotedMessageId: 31 });
    await waitFor(() => expect(box()).toHaveValue(""));
    expect(screen.queryByText("Is the disk full on db-3?")).not.toBeInTheDocument();
    // Nothing of it comes back with the next reply, or from the kept draft.
    await user.type(box(), "No quote now");
    await user.click(screen.getByRole("button", { name: "Send" }));
    await waitFor(() => expect(posts()).toHaveLength(2));
    expect(bodyOf(posts()[1])).toEqual({ body: "No quote now" });
  });

  test("a failed post keeps the text and the line", async () => {
    mockFetch.mockResolvedValue(jsonResponse(500, { title: "Boom", status: 500 }));
    const user = userEvent.setup();
    renderWithProviders(<QuoteHarness />);
    await user.click(screen.getByRole("button", { name: "quote Ann" }));
    await user.type(box(), "Agreed");
    await user.click(screen.getByRole("button", { name: "Send" }));
    expect(await screen.findByText("Action failed (500)")).toBeInTheDocument();
    expect(box()).toHaveValue("Agreed");
    expect(screen.getByText("Is the disk full on db-3?")).toBeInTheDocument();
  });

  test("the typed quote-unavailable 400 says to remove the quote, keeps the text and the line, and a send without the quote then goes through", async () => {
    mockFetch.mockResolvedValueOnce(jsonResponse(400, { type: "urn:adder:problem:quote-unavailable", title: "Bad Request", status: 400, detail: "The quoted message is unavailable." }));
    const user = userEvent.setup();
    renderWithProviders(<QuoteHarness />);
    await user.click(screen.getByRole("button", { name: "quote Ann" }));
    await user.type(box(), "Agreed");
    await user.click(screen.getByRole("button", { name: "Send" }));
    expect(await screen.findByText("The message you are quoting is no longer available. Remove the quote to send your reply.")).toBeInTheDocument();
    expect(screen.queryByText("The message is not valid. Check its length and that it has no control characters.")).not.toBeInTheDocument();
    expect(box()).toHaveValue("Agreed");
    expect(screen.getByText("Is the disk full on db-3?")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Remove the quote" }));
    // The words that told the reader to remove it go with it; the text stays for the next try.
    expect(screen.queryByText("The message you are quoting is no longer available. Remove the quote to send your reply.")).not.toBeInTheDocument();
    expect(box()).toHaveValue("Agreed");
    await user.click(screen.getByRole("button", { name: "Send" }));
    await waitFor(() => expect(posts()).toHaveLength(2));
    expect(bodyOf(posts()[1])).toEqual({ body: "Agreed" });
  });

  test("Remove the quote removes the line, keeps the text and leaves the focus in the box", async () => {
    const user = userEvent.setup();
    renderWithProviders(<QuoteHarness />);
    await user.click(screen.getByRole("button", { name: "quote Ann" }));
    await user.type(box(), "keep this");
    await user.click(screen.getByRole("button", { name: "Remove the quote" }));
    expect(screen.queryByText("Is the disk full on db-3?")).not.toBeInTheDocument();
    expect(box()).toHaveValue("keep this");
    expect(box()).toHaveFocus();
    // The status line is empty again: the next quote is announced afresh.
    expect(screen.queryByText("Quoting Ann Author")).not.toBeInTheDocument();
  });

  test("quoting another message replaces the quote; quoting the same one again after removing it brings it back", async () => {
    const user = userEvent.setup();
    renderWithProviders(<QuoteHarness />);
    await user.click(screen.getByRole("button", { name: "quote Ann" }));
    await user.click(screen.getByRole("button", { name: "quote Bob" }));
    expect(screen.getByText("No, the log is.")).toBeInTheDocument();
    expect(screen.queryByText("Is the disk full on db-3?")).not.toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Remove the quote" }));
    await user.click(screen.getByRole("button", { name: "quote Bob" }));
    expect(screen.getByText("No, the log is.")).toBeInTheDocument();
  });

  test("the quote is kept per thread: another thread has none, and each one's quote is back when the reader returns, or the pane is left and reopened", async () => {
    const user = userEvent.setup();
    renderWithProviders(<QuoteHarness />);
    await user.click(screen.getByRole("button", { name: "quote Ann" }));
    await user.click(screen.getByRole("button", { name: "switch thread" }));
    expect(screen.queryByText("Is the disk full on db-3?")).not.toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "switch thread" }));
    expect(screen.getByText("Is the disk full on db-3?")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "toggle pane" }));
    await user.click(screen.getByRole("button", { name: "toggle pane" }));
    expect(screen.getByText("Is the disk full on db-3?")).toBeInTheDocument();
    // A request that was already made does not come back by itself after a removal: the kept draft is the truth.
    await user.click(screen.getByRole("button", { name: "Remove the quote" }));
    await user.click(screen.getByRole("button", { name: "toggle pane" }));
    await user.click(screen.getByRole("button", { name: "toggle pane" }));
    expect(screen.queryByText("Is the disk full on db-3?")).not.toBeInTheDocument();
  });

  test("a request made for another thread is not this box's", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <ThreadDraftsProvider>
        <MessageComposer room={makeRoom()} thread={makeThread({ number: 42 })} onPosted={vi.fn()} quoteRequest={{ threadNumber: 43, quote: picked, seq: 1 }} />
      </ThreadDraftsProvider>,
    );
    expect(screen.queryByText("Quoting Ann Author")).not.toBeInTheDocument();
    await user.type(box(), "plain");
    expect(box()).toHaveValue("plain");
  });

  test("a composer mounted again while its post is in flight shows the quote, and drops it with the text when the post succeeds", async () => {
    let answer!: (response: Response) => void;
    mockFetch.mockImplementation(() => new Promise<Response>((resolve) => (answer = resolve)));
    const user = userEvent.setup();
    renderWithProviders(<QuoteHarness />);
    await user.click(screen.getByRole("button", { name: "quote Ann" }));
    await user.type(box(), "still travelling");
    await user.click(screen.getByRole("button", { name: "Send" }));
    await waitFor(() => expect(posts()).toHaveLength(1));
    expect(bodyOf(posts()[0])).toEqual({ body: "still travelling", quotedMessageId: 31 });
    await user.click(screen.getByRole("button", { name: "toggle pane" }));
    await user.click(screen.getByRole("button", { name: "toggle pane" }));
    expect(screen.getByText("Is the disk full on db-3?")).toBeInTheDocument();
    answer(jsonResponse(201, makeMessage({ id: 77 })));
    await waitFor(() => expect(box()).toHaveValue(""));
    await waitFor(() => expect(screen.queryByText("Is the disk full on db-3?")).not.toBeInTheDocument());
  });

  test("the same message picked AGAIN while the post travels is another pick: it is still there when the post succeeds, and the next reply carries it", async () => {
    let answer!: (response: Response) => void;
    mockFetch.mockImplementationOnce(() => new Promise<Response>((resolve) => (answer = resolve)));
    const user = userEvent.setup();
    renderWithProviders(<QuoteHarness />);
    await user.click(screen.getByRole("button", { name: "quote Ann" }));
    await user.type(box(), "first");
    await user.click(screen.getByRole("button", { name: "Send" }));
    await waitFor(() => expect(posts()).toHaveLength(1));
    await user.click(screen.getByRole("button", { name: "quote Ann" }));
    answer(jsonResponse(201, makeMessage({ id: 77 })));
    await waitFor(() => expect(box()).toHaveValue(""));
    expect(screen.getByText("Is the disk full on db-3?")).toBeInTheDocument();
    await user.type(box(), "second");
    await user.click(screen.getByRole("button", { name: "Send" }));
    await waitFor(() => expect(posts()).toHaveLength(2));
    expect(bodyOf(posts()[1])).toEqual({ body: "second", quotedMessageId: 31 });
  });

  test("the same holds for a box mounted again while the post travels", async () => {
    let answer!: (response: Response) => void;
    mockFetch.mockImplementationOnce(() => new Promise<Response>((resolve) => (answer = resolve)));
    const user = userEvent.setup();
    renderWithProviders(<QuoteHarness />);
    await user.click(screen.getByRole("button", { name: "quote Ann" }));
    await user.type(box(), "first");
    await user.click(screen.getByRole("button", { name: "Send" }));
    await waitFor(() => expect(posts()).toHaveLength(1));
    await user.click(screen.getByRole("button", { name: "toggle pane" }));
    await user.click(screen.getByRole("button", { name: "toggle pane" }));
    await user.click(screen.getByRole("button", { name: "quote Ann" }));
    answer(jsonResponse(201, makeMessage({ id: 77 })));
    await waitFor(() => expect(box()).toHaveValue(""));
    expect(screen.getByText("Is the disk full on db-3?")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Remove the quote" })).toBeInTheDocument();
  });
});
