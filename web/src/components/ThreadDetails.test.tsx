import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import ThreadDetails from "./ThreadDetails";
import { jsonResponse } from "../test/http";
import { makeRoom, makeThread } from "../test/messageFixtures";
import { renderWithProviders, screen, waitFor, within } from "../test/render";
import { SIDE_WORKFLOW } from "../test/sideFixtures";

const THREAD = makeThread({
  number: 42,
  fields: { sev: "s9", note: "db-3", link: "https://example.test/runbook", lead: 12 },
  fieldUserNames: { "12": "Ann Owner" },
});

describe("ThreadDetails", () => {
  let mockFetch: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    mockFetch = vi.fn((_url: string, init?: RequestInit) => Promise.resolve(init?.method === "PUT" ? new Response(null, { status: 204 }) : jsonResponse(200, {})));
    vi.stubGlobal("fetch", mockFetch);
    localStorage.setItem("adder.auth.token", "fake-token");
  });
  afterEach(() => {
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  test("the read view lists every active field with its value: labels, a removed option marked, a person's name, a link, and Not set", () => {
    renderWithProviders(<ThreadDetails room={makeRoom()} thread={THREAD} workflow={SIDE_WORKFLOW} />);
    const term = (name: string) => screen.getByText(name, { selector: "dt" });
    expect(term("Severity").nextElementSibling).toHaveTextContent("s9 (removed option)");
    expect(term("Note").nextElementSibling).toHaveTextContent("db-3");
    expect(within(term("Runbook").nextElementSibling as HTMLElement).getByRole("link", { name: "https://example.test/runbook" })).toHaveAttribute("target", "_blank");
    expect(term("Lead").nextElementSibling).toHaveTextContent("Ann Owner");
    renderWithProviders(<ThreadDetails room={makeRoom()} thread={makeThread({ fields: {} })} workflow={SIDE_WORKFLOW} />);
    expect(screen.getAllByText("Not set")).toHaveLength(4);
  });

  test("a workflow with no fields says so, and the editor still edits the title", async () => {
    const user = userEvent.setup();
    renderWithProviders(<ThreadDetails room={makeRoom()} thread={THREAD} workflow={{ ...SIDE_WORKFLOW, fields: [] }} />);
    expect(screen.getByText("This room's workflow has no extra fields.")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Edit details" }));
    expect(await screen.findByRole("textbox", { name: /Title/ })).toHaveValue("Disk full on db-3");
  });

  test("Edit details opens the editor; Cancel closes it and puts focus back on the button that opened it", async () => {
    const user = userEvent.setup();
    renderWithProviders(<ThreadDetails room={makeRoom()} thread={THREAD} workflow={SIDE_WORKFLOW} />);
    await user.click(screen.getByRole("button", { name: "Edit details" }));
    expect(screen.queryByRole("button", { name: "Edit details" })).not.toBeInTheDocument();
    expect(screen.getByRole("textbox", { name: "Note" })).toHaveValue("db-3");
    await user.click(screen.getByRole("button", { name: "Cancel" }));
    expect(screen.queryByRole("textbox", { name: "Note" })).not.toBeInTheDocument();
    await waitFor(() => expect(screen.getByRole("button", { name: "Edit details" })).toHaveFocus());
  });

  test("a saved edit closes the editor", async () => {
    const user = userEvent.setup();
    renderWithProviders(<ThreadDetails room={makeRoom()} thread={THREAD} workflow={SIDE_WORKFLOW} />);
    await user.click(screen.getByRole("button", { name: "Edit details" }));
    await user.type(screen.getByRole("textbox", { name: /Title/ }), "!");
    await user.click(screen.getByRole("button", { name: "Save" }));
    await waitFor(() => expect(screen.queryByRole("textbox", { name: /Title/ })).not.toBeInTheDocument());
    expect(mockFetch.mock.calls.filter(([, init]) => (init as RequestInit | undefined)?.method === "PUT")).toHaveLength(1);
  });

  test("a closed thread and an archived room offer no editor, only the reason", () => {
    const { unmount } = renderWithProviders(<ThreadDetails room={makeRoom()} thread={makeThread({ closed: true, closedAt: 5 })} workflow={SIDE_WORKFLOW} />);
    expect(screen.queryByRole("button", { name: "Edit details" })).not.toBeInTheDocument();
    expect(screen.getByText("This thread is closed, so its details can no longer change. Reopen it to edit them.")).toBeInTheDocument();
    unmount();
    renderWithProviders(<ThreadDetails room={makeRoom({ archived: true })} thread={THREAD} workflow={SIDE_WORKFLOW} />);
    expect(screen.queryByRole("button", { name: "Edit details" })).not.toBeInTheDocument();
    expect(screen.getByText("This room is archived, so the details can no longer change.")).toBeInTheDocument();
  });
});
