import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import ThreadHistory from "./ThreadHistory";
import { jsonResponse } from "../test/http";
import { renderWithProviders, screen, waitFor } from "../test/render";
import { eventPage, makeEvent } from "../test/sideFixtures";

describe("ThreadHistory", () => {
  let mockFetch: ReturnType<typeof vi.fn>;
  const urls = () => mockFetch.mock.calls.map(([url]) => url as string);

  beforeEach(() => {
    mockFetch = vi.fn();
    vi.stubGlobal("fetch", mockFetch);
    localStorage.setItem("adder.auth.token", "fake-token");
  });
  afterEach(() => {
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  test("renders the server's structured events as sentences in the viewer's language, ten to a page", async () => {
    mockFetch.mockResolvedValue(
      jsonResponse(
        200,
        eventPage([
          makeEvent({ id: 3, type: "FIELD_CHANGED", params: { field: "sev", fieldLabel: "Severity", from: "S3", to: "S1" }, userName: "Bob" }),
          makeEvent({ id: 2, type: "OWNER_CHANGED", params: { fromId: "5", toId: "6", fromName: "Ann Owner", toName: "Bob" } }),
          makeEvent({ id: 1 }),
        ]),
      ),
    );
    renderWithProviders(<ThreadHistory roomId={12} number={42} />);
    expect(await screen.findByText("Changed Severity")).toBeInTheDocument();
    expect(screen.getByText("Severity: S3 → S1")).toBeInTheDocument();
    expect(screen.getByText("Passed the thread from Ann Owner to Bob")).toBeInTheDocument();
    expect(screen.getByText("Started the thread in Triage")).toBeInTheDocument();
    expect(urls()).toEqual(["/api/v1/rooms/12/threads/42/events?page=1&pageSize=10"]);
  });

  test("an event kind this build does not know shows its raw type, and a deleted account is marked", async () => {
    mockFetch.mockResolvedValue(jsonResponse(200, eventPage([makeEvent({ type: "MESSAGE_PINNED" as never, params: {}, userName: "Zed", userDeleted: true })])));
    renderWithProviders(<ThreadHistory roomId={12} number={42} />);
    expect(await screen.findByText("MESSAGE_PINNED")).toBeInTheDocument();
    expect(screen.getByText(/Zed \(deleted account\)/)).toBeInTheDocument();
  });

  test("an empty history says so; a failed read is an alert, not an empty history", async () => {
    mockFetch.mockResolvedValue(jsonResponse(200, eventPage([])));
    const { unmount } = renderWithProviders(<ThreadHistory roomId={12} number={42} />);
    expect(await screen.findByText("No history yet.")).toBeInTheDocument();
    unmount();
    mockFetch.mockResolvedValue(jsonResponse(500, { title: "Boom", status: 500 }));
    renderWithProviders(<ThreadHistory roomId={12} number={42} />);
    expect(await screen.findByText("Load failed (500)")).toBeInTheDocument();
    expect(screen.queryByText("No history yet.")).not.toBeInTheDocument();
  });

  test("past ten events the pager asks the server for the next page", async () => {
    const user = userEvent.setup();
    mockFetch.mockImplementation((url: string) =>
      Promise.resolve(jsonResponse(200, url.includes("page=2") ? eventPage([makeEvent({ id: 1, type: "REOPENED", params: { from: "done", to: "triage", fromLabel: "Done", toLabel: "Triage" } })], 11, 2) : eventPage([makeEvent({ id: 11 })], 11, 1))),
    );
    renderWithProviders(<ThreadHistory roomId={12} number={42} />);
    await screen.findByText("Started the thread in Triage");
    await user.click(screen.getByRole("button", { name: "Next page" }));
    expect(await screen.findByText("Reopened the thread: Done → Triage")).toBeInTheDocument();
    await waitFor(() => expect(urls()).toContain("/api/v1/rooms/12/threads/42/events?page=2&pageSize=10"));
  });

  test("a failed later page keeps the pager and offers Retry, so it is never a dead end", async () => {
    const user = userEvent.setup();
    let page2Fails = true;
    mockFetch.mockImplementation((url: string) => {
      if (url.includes("page=2")) {
        return Promise.resolve(page2Fails ? jsonResponse(500, { title: "Boom", status: 500 }) : jsonResponse(200, eventPage([makeEvent({ id: 1, type: "REOPENED", params: { from: "done", to: "triage", fromLabel: "Done", toLabel: "Triage" } })], 11, 2)));
      }
      return Promise.resolve(jsonResponse(200, eventPage([makeEvent({ id: 11 })], 11, 1)));
    });
    renderWithProviders(<ThreadHistory roomId={12} number={42} />);
    await screen.findByText("Started the thread in Triage");
    await user.click(screen.getByRole("button", { name: "Next page" }));
    expect(await screen.findByText("Load failed (500)")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Previous page" })).toBeInTheDocument();
    page2Fails = false;
    await user.click(screen.getByRole("button", { name: "Retry" }));
    expect(await screen.findByText("Reopened the thread: Done → Triage")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Retry" })).not.toBeInTheDocument();
  });
});
