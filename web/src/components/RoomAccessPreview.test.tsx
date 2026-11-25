import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { screen, waitFor } from "@testing-library/react";
import RoomAccessPreview from "./RoomAccessPreview";
import { jsonResponse } from "../test/http";
import { renderWithProviders } from "../test/render";
import { TOKEN_KEY } from "../api/session";

type FetchMock = ReturnType<typeof vi.fn>;

describe("RoomAccessPreview", () => {
  let mockFetch: FetchMock;

  beforeEach(() => {
    localStorage.setItem(TOKEN_KEY, "member-token");
    mockFetch = vi.fn(() => Promise.resolve(new Response(null, { status: 204 })));
    vi.stubGlobal("fetch", mockFetch);
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  test("a public room offers immediate self-join", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <RoomAccessPreview
        room={{ id: 12, name: "Town Square", description: "For everyone", archived: false, accessType: "PUBLIC", requestStatus: null }}
      />,
    );
    expect(screen.getByRole("heading", { level: 2, name: "Town Square" })).toBeInTheDocument();
    expect(screen.getByText("For everyone")).toBeInTheDocument();
    expect(screen.getByText("Public")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Join room" }));
    await waitFor(() =>
      expect(mockFetch).toHaveBeenCalledWith(
        "/api/v1/rooms/12/join",
        expect.objectContaining({ method: "POST" }),
      ),
    );
  });

  test("a restricted visible room shows request state and only a viable action", async () => {
    const user = userEvent.setup();
    const first = renderWithProviders(
      <RoomAccessPreview
        room={{ id: 13, name: "Incident Leads", description: null, archived: false, accessType: "RESTRICTED_VISIBLE", requestStatus: "PENDING" }}
      />,
    );
    expect(screen.getByText("Request pending")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /Request/ })).not.toBeInTheDocument();
    first.unmount();

    mockFetch.mockResolvedValueOnce(new Response(null, { status: 204 })).mockResolvedValueOnce(
      jsonResponse(200, {
        id: 13,
        name: "Incident Leads",
        description: null,
        archived: false,
        accessType: "RESTRICTED_VISIBLE",
        requestStatus: "PENDING",
      }),
    );
    renderWithProviders(
      <RoomAccessPreview
        room={{ id: 13, name: "Incident Leads", description: null, archived: false, accessType: "RESTRICTED_VISIBLE", requestStatus: "REJECTED" }}
      />,
    );
    expect(screen.getByText("Request declined")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Request again" }));
    await waitFor(() =>
      expect(mockFetch).toHaveBeenCalledWith(
        "/api/v1/rooms/13/join-requests",
        expect.objectContaining({ method: "POST" }),
      ),
    );
  });
});
