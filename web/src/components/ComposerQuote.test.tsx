import { describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { screen } from "@testing-library/react";
import ComposerQuote from "./ComposerQuote";
import type { QuoteDraft } from "../hooks/useThreadDrafts";
import { renderWithProviders } from "../test/render";

const quote: QuoteDraft = { id: 31, authorId: 4, authorName: "Ann Author", body: "# Plan\n\nRestart **db-3** and ask <@5>", mentions: [{ userId: 5, name: "Bob Starter" }], groupMentions: [], roomId: 2, threadNumber: 8 };

describe("ComposerQuote", () => {
  test("says who is quoted and shows a plain excerpt of what they said", () => {
    renderWithProviders(<ComposerQuote quote={quote} onRemove={vi.fn()} />);
    expect(screen.getByText("Quoting Ann Author")).toBeInTheDocument();
    expect(screen.getByText("Plan Restart db-3 and ask @Bob Starter")).toBeInTheDocument();
    expect(document.querySelector("strong")).toBeNull();
  });

  test("the icon is decoration and the button removes the quote", async () => {
    const onRemove = vi.fn();
    renderWithProviders(<ComposerQuote quote={quote} onRemove={onRemove} />);
    expect(document.querySelector("svg[aria-hidden='true']")).not.toBeNull();
    await userEvent.setup().click(screen.getByRole("button", { name: "Remove the quote" }));
    expect(onRemove).toHaveBeenCalledOnce();
  });

  test("a quote that is gone shows the author and the words, never text", () => {
    renderWithProviders(<ComposerQuote quote={{ ...quote, body: "", mentions: [], unavailable: true }} onRemove={vi.fn()} />);
    expect(screen.getByText("Quoting Ann Author")).toBeInTheDocument();
    expect(screen.getByText("The message you are quoting is no longer available. Remove the quote to send your reply.")).toBeInTheDocument();
  });

  test("never renders HTML from the quoted text", () => {
    renderWithProviders(<ComposerQuote quote={{ ...quote, body: "<img src=x onerror=alert(1)>" }} onRemove={vi.fn()} />);
    expect(document.querySelector("img")).toBeNull();
    expect(screen.getByText("<img src=x onerror=alert(1)>")).toBeInTheDocument();
  });
});
