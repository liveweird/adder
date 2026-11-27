import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { useMemo, type ComponentProps } from "react";
import userEvent from "@testing-library/user-event";
import { screen, waitFor } from "@testing-library/react";
import { QueryClient } from "@tanstack/react-query";
import RoomAccessPreview from "./RoomAccessPreview";
import { usePublicRoomEnrollment } from "../hooks/usePublicRoomEnrollment";
import { jsonResponse } from "../test/http";
import { renderWithProviders } from "../test/render";
import { clearSession, TOKEN_KEY } from "../api/session";
import { roomKeys } from "../utils/roomKeys";

type FetchMock = ReturnType<typeof vi.fn>;
type PreviewRoom = ComponentProps<typeof RoomAccessPreview>["room"];

function TestRoomAccessPreview({ room }: { room: PreviewRoom }) {
  const routeToken = useMemo(() => Symbol("preview-route"), []);
  const enrollment = usePublicRoomEnrollment(room.id, false, routeToken, () => routeToken);
  return <RoomAccessPreview room={room} publicEnrollment={enrollment} />;
}

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
      <TestRoomAccessPreview
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

  test("an archived public room keeps the self-join action", () => {
    renderWithProviders(
      <TestRoomAccessPreview
        room={{ id: 12, name: "Town Square", description: null, archived: true, accessType: "PUBLIC", requestStatus: null }}
      />,
    );

    expect(screen.getByText("Archived")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Join room" })).toBeEnabled();
  });

  test("a rejected public join keeps the preview and shows the room-full error", async () => {
    mockFetch.mockResolvedValue(jsonResponse(409, { title: "Room full", status: 409 }));
    const user = userEvent.setup();
    renderWithProviders(
      <TestRoomAccessPreview
        room={{ id: 12, name: "Town Square", description: null, archived: false, accessType: "PUBLIC", requestStatus: null }}
      />,
    );

    await user.click(screen.getByRole("button", { name: "Join room" }));

    const error = await screen.findByRole("alert");
    expect(error).toHaveTextContent("Could not join the room");
    expect(error).toHaveTextContent("This room is full.");
    expect(screen.getByRole("heading", { level: 2, name: "Town Square" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Join room" })).toBeEnabled();
  });

  test("a restricted visible room shows request state and only a viable action", async () => {
    const user = userEvent.setup();
    const first = renderWithProviders(
      <TestRoomAccessPreview
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
      <TestRoomAccessPreview
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

  test("a late public-join response never removes a newer access preview", async () => {
    let release: () => void = () => {};
    mockFetch.mockImplementation(() => new Promise<Response>((resolve) => {
      release = () => resolve(new Response(null, { status: 204 }));
    }));
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const newer = {
      id: 12, name: "New authority", description: null, archived: false, accessType: "PUBLIC", requestStatus: null,
    } as const;
    const user = userEvent.setup();
    renderWithProviders(
      <TestRoomAccessPreview
        room={{ id: 12, name: "Town Square", description: null, archived: false, accessType: "PUBLIC", requestStatus: null }}
      />,
      { queryClient },
    );
    await user.click(screen.getByRole("button", { name: "Join room" }));
    queryClient.setQueryData(roomKeys.access(12), newer);
    release();

    await waitFor(() => expect(screen.getByRole("button", { name: "Join room" })).toBeEnabled());
    expect(queryClient.getQueryData(roomKeys.access(12))).toEqual(newer);
  });

  test("a public-join response from an old logical session leaves replacement caches untouched", async () => {
    let release: () => void = () => {};
    mockFetch.mockImplementation(() => new Promise<Response>((resolve) => {
      release = () => resolve(new Response(null, { status: 204 }));
    }));
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const invalidate = vi.spyOn(queryClient, "invalidateQueries");
    const user = userEvent.setup();
    renderWithProviders(
      <TestRoomAccessPreview
        room={{ id: 12, name: "Town Square", description: null, archived: false, accessType: "PUBLIC", requestStatus: null }}
      />,
      { queryClient },
    );
    await user.click(screen.getByRole("button", { name: "Join room" }));
    clearSession();
    localStorage.setItem(TOKEN_KEY, "replacement-token");
    queryClient.setQueryData(["rooms", "replacement"], { value: "new session" });
    release();

    await waitFor(() => expect(screen.getByRole("button", { name: "Join room" })).toBeEnabled());
    expect(invalidate).not.toHaveBeenCalled();
    expect(queryClient.getQueryData(["rooms", "replacement"])).toEqual({ value: "new session" });
  });
});
