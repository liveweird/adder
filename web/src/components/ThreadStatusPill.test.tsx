import { describe, expect, test } from "vitest";
import ThreadStatusPill from "./ThreadStatusPill";
import { renderWithProviders, screen } from "../test/render";

const STATUSES = [
  { key: "triage", label: "Triage", colour: "BLUE", category: "OPEN", initial: true, threadCount: 1 },
  { key: "resolved", label: "Resolved", colour: "TEAL", category: "CLOSED", initial: false, threadCount: 0 },
] as const;

describe("ThreadStatusPill", () => {
  test("shows the workflow's label for the status key", () => {
    renderWithProviders(<ThreadStatusPill statuses={STATUSES.map((status) => ({ ...status }))} statusKey="resolved" />);
    expect(screen.getByText("Resolved")).toBeInTheDocument();
  });

  test("a key the workflow does not list (or no workflow yet) shows the bare key", () => {
    const { rerender } = renderWithProviders(<ThreadStatusPill statuses={STATUSES.map((status) => ({ ...status }))} statusKey="gone" />);
    expect(screen.getByText("gone")).toBeInTheDocument();
    rerender(<ThreadStatusPill statuses={undefined} statusKey="triage" />);
    expect(screen.getByText("triage")).toBeInTheDocument();
  });
});
