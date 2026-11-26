import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { QueryClient } from "@tanstack/react-query";
import { act, screen, waitFor } from "@testing-library/react";
import MessageRevisionDialog from "./MessageRevisionDialog";
import { jsonResponse } from "../test/http";
import { renderWithProviders } from "../test/render";
import { clearMessageRevisions } from "../utils/messageRevisionCache";

describe("MessageRevisionDialog", () => {
  let mockFetch: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    mockFetch = vi.fn().mockResolvedValue(jsonResponse(200, {
      items: [{ id: 4, body: "[danger](https://evil.test)\n\n**plain source**", createdAt: 1_800_000, supersededAt: 3_600_000 }],
      page: 1,
      pageSize: 20,
      total: 1,
    }));
    vi.stubGlobal("fetch", mockFetch);
    localStorage.setItem("adder.auth.token", "fake-token");
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  test("loads only while open and renders historical Markdown as inert prewrapped source", async () => {
    const onClose = vi.fn();
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    renderWithProviders(
      <MessageRevisionDialog roomId={12} threadNumber={42} messageId={9} opened onClose={onClose} />,
      { queryClient },
    );
    await screen.findByText(/danger/);
    const dialog = screen.getByRole("dialog", { name: "Edit history" });
    expect(dialog).toHaveTextContent("[danger](https://evil.test)");
    expect(dialog).toHaveTextContent("**plain source**");
    expect(dialog.querySelector("pre")).toHaveStyle({ whiteSpace: "pre-wrap" });
    expect(screen.queryByRole("link", { name: "danger" })).not.toBeInTheDocument();
    expect(mockFetch).toHaveBeenCalledWith("/api/v1/rooms/12/messages/9/revisions?page=1&pageSize=20&sort=-id", expect.anything());
  });

  test("closing forgets the sensitive query data", async () => {
    const user = userEvent.setup();
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: Infinity } } });
    renderWithProviders(
      <MessageRevisionDialog roomId={12} threadNumber={42} messageId={9} opened onClose={() => undefined} />,
      { queryClient },
    );
    await screen.findByText(/danger/);
    expect(queryClient.getQueryData(["rooms", "threads", 12, "message-revisions", 42, 9, 1])).toBeDefined();
    await user.click(screen.getByRole("button", { name: "Close" }));
    await waitFor(() => expect(queryClient.getQueryData(["rooms", "threads", 12, "message-revisions", 42, 9, 1])).toBeUndefined());
  });

  test("clearing access removes a prior body from an open dialog immediately", async () => {
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    renderWithProviders(
      <MessageRevisionDialog roomId={12} threadNumber={42} messageId={9} opened onClose={vi.fn()} />,
      { queryClient },
    );
    await screen.findByText(/danger/);
    mockFetch.mockImplementation(() => new Promise<Response>(() => undefined));

    act(() => clearMessageRevisions(queryClient, 12, 42, 9));

    await waitFor(() => expect(screen.queryByText(/danger/)).not.toBeInTheDocument());
    expect(screen.getByRole("dialog", { name: "Edit history" })).toBeVisible();
  });
});
