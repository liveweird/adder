import { describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import EventTimeline, { type TimelineItem } from "./EventTimeline";
import { renderWithProviders, screen } from "../test/render";
import { browserTimeZone, formatDateTime } from "../utils/formatDate";

const WHEN = Date.UTC(2026, 5, 1, 9, 30);
const ITEMS: TimelineItem[] = [
  { key: 2, title: "Closed the thread: Working → Done", lines: [], who: "Bob", when: WHEN },
  { key: 1, title: "Changed Severity", lines: ["Severity: S3 → S1"], who: "Ann", when: WHEN },
];

function renderTimeline(props: Partial<Parameters<typeof EventTimeline>[0]> = {}) {
  const onPageChange = vi.fn();
  renderWithProviders(
    <EventTimeline items={ITEMS} isLoading={false} isError={false} error={null} emptyMessage="No history yet." page={1} pageSize={10} total={2} onPageChange={onPageChange} {...props} />,
  );
  return onPageChange;
}

describe("EventTimeline", () => {
  test("each entry shows its sentence, its body lines and `who · when` in the browser's zone", () => {
    renderTimeline();
    expect(screen.getByText("Closed the thread: Working → Done")).toBeInTheDocument();
    expect(screen.getByText("Severity: S3 → S1")).toBeInTheDocument();
    expect(screen.getByText(`Ann · ${formatDateTime(WHEN, browserTimeZone())}`)).toBeInTheDocument();
  });

  test("a spinner while loading, the alert on a failed load (never the empty note) and the note for an empty history", () => {
    const loading = renderWithProviders(<EventTimeline items={undefined} isLoading isError={false} error={null} emptyMessage="No history yet." page={1} pageSize={10} total={0} onPageChange={vi.fn()} />);
    expect(screen.getByRole("status", { name: "Loading…" })).toBeInTheDocument();
    loading.unmount();
    const failed = renderWithProviders(<EventTimeline items={undefined} isLoading={false} isError error={new Error("x")} emptyMessage="No history yet." page={1} pageSize={10} total={0} onPageChange={vi.fn()} />);
    expect(screen.getByRole("alert")).toHaveTextContent("Network error");
    expect(screen.queryByText("No history yet.")).not.toBeInTheDocument();
    failed.unmount();
    renderTimeline({ items: [], total: 0 });
    expect(screen.getByText("No history yet.")).toBeInTheDocument();
  });

  test("no pager within one page; past it the pager's named controls change the page", async () => {
    const user = userEvent.setup();
    const { unmount } = renderWithProviders(<EventTimeline items={ITEMS} isLoading={false} isError={false} error={null} emptyMessage="" page={1} pageSize={10} total={10} onPageChange={vi.fn()} />);
    expect(screen.queryByRole("button", { name: "Next page" })).not.toBeInTheDocument();
    unmount();
    const onPageChange = renderTimeline({ total: 25 });
    await user.click(screen.getByRole("button", { name: "Next page" }));
    expect(onPageChange).toHaveBeenCalledWith(2);
    expect(screen.getByRole("button", { name: "Previous page" })).toBeInTheDocument();
  });

  test("while a later page loads, the previous rows stay, dimmed and busy", () => {
    renderTimeline({ isPlaceholder: true, total: 25 });
    expect(screen.getByText("Changed Severity").closest("[aria-busy]")).toHaveAttribute("aria-busy", "true");
  });
});
