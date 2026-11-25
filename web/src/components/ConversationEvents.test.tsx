import { describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import type { useConversationHistory } from "../hooks/useConversationHistory";
import { makeEvent } from "../test/sideFixtures";
import { renderWithProviders, screen } from "../test/render";
import ConversationEvent from "./ConversationEvent";
import ConversationHistoryMore from "./ConversationHistoryMore";

type HistoryQuery = ReturnType<typeof useConversationHistory>["query"];

function historyQuery(values: Partial<HistoryQuery> = {}): HistoryQuery {
  return {
    hasNextPage: true,
    isError: false,
    isFetching: false,
    isFetchNextPageError: false,
    fetchNextPage: vi.fn(),
    refetch: vi.fn(),
    ...values,
  } as unknown as HistoryQuery;
}

describe("conversation history overflow", () => {
  test("stays absent when the complete history loaded successfully", () => {
    renderWithProviders(<ConversationHistoryMore query={historyQuery({ hasNextPage: false })} />);
    expect(screen.queryByRole("button")).not.toBeInTheDocument();
  });

  test("loads the next event page and ignores a repeated click while a request is pending", async () => {
    const user = userEvent.setup();
    const fetchNextPage = vi.fn();
    const { rerender } = renderWithProviders(<ConversationHistoryMore query={historyQuery({ fetchNextPage })} />);
    await user.click(screen.getByRole("button", { name: "Load more workflow changes" }));
    expect(fetchNextPage).toHaveBeenCalledOnce();

    rerender(<ConversationHistoryMore query={historyQuery({ isFetching: true, fetchNextPage })} />);
    const pending = screen.getByRole("button", { name: "Load more workflow changes" });
    expect(pending).toHaveAttribute("aria-busy", "true");
    await user.click(pending);
    expect(fetchNextPage).toHaveBeenCalledOnce();
  });

  test("retries the initial history read after an error", async () => {
    const user = userEvent.setup();
    const refetch = vi.fn();
    renderWithProviders(
      <ConversationHistoryMore
        query={historyQuery({ hasNextPage: false, isError: true, isFetchNextPageError: false, refetch })}
      />,
    );
    await user.click(screen.getByRole("button", { name: "Retry loading workflow changes" }));
    expect(refetch).toHaveBeenCalledOnce();
  });

  test("retries the failed next page without discarding the pages already shown", async () => {
    const user = userEvent.setup();
    const fetchNextPage = vi.fn();
    renderWithProviders(
      <ConversationHistoryMore
        query={historyQuery({ hasNextPage: false, isError: true, isFetchNextPageError: true, fetchNextPage })}
      />,
    );
    await user.click(screen.getByRole("button", { name: "Retry loading workflow changes" }));
    expect(fetchNextPage).toHaveBeenCalledOnce();
  });
});

describe("inline conversation events", () => {
  test("renders the event sentence, detail lines, linked actor and machine-readable time", () => {
    renderWithProviders(
      <ul>
        <ConversationEvent
          event={makeEvent({
            type: "FIELD_CHANGED",
            params: { field: "sev", fieldLabel: "Severity", from: "S3", to: "S1" },
            actorId: 8,
            actorName: "Bob",
          })}
          zone="UTC"
        />
      </ul>,
    );
    expect(screen.getByText("Changed Severity")).toBeInTheDocument();
    expect(screen.getByText("Severity: S3 → S1")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Bob" })).toHaveAttribute("href", "/people/8");
    expect(screen.getByText("Bob", { selector: "a" }).parentElement?.querySelector("time")).toHaveAttribute(
      "datetime",
      "2026-06-01T09:30:00.000Z",
    );
  });

  test("marks a deleted actor without offering a dead profile link", () => {
    renderWithProviders(
      <ul><ConversationEvent event={makeEvent({ actorDeleted: true, actorName: "Former user" })} zone="UTC" /></ul>,
    );
    expect(screen.getByText(/Former user \(deleted account\)/)).toBeInTheDocument();
    expect(screen.queryByRole("link", { name: "Former user" })).not.toBeInTheDocument();
  });

  test("links both people in an ownership change and safely falls back for legacy malformed ids", () => {
    const valid = makeEvent({
      id: 2,
      type: "OWNER_CHANGED",
      params: { fromId: "5", fromName: "Ann", toId: "6", toName: "Bob" },
    });
    const malformed = makeEvent({ id: 3, type: "OWNER_CHANGED", params: { fromId: "old", toId: "6" } });
    const { rerender } = renderWithProviders(<ul><ConversationEvent event={valid} zone="UTC" /></ul>);
    expect(screen.getByRole("link", { name: "Ann" })).toHaveAttribute("href", "/people/5");
    expect(screen.getByRole("link", { name: "Bob" })).toHaveAttribute("href", "/people/6");

    rerender(<ul><ConversationEvent event={malformed} zone="UTC" /></ul>);
    expect(screen.getByText("Passed the thread from old to 6")).toBeInTheDocument();
    expect(screen.queryByRole("link", { name: "old" })).not.toBeInTheDocument();
    expect(screen.queryByRole("link", { name: "6" })).not.toBeInTheDocument();
  });
});
