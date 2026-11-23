import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { screen, waitFor } from "@testing-library/react";
import { QueryClient } from "@tanstack/react-query";
import RoomPage from "./RoomPage";
import { jsonResponse } from "../test/http";
import { renderWithProviders } from "../test/render";

describe("RoomPage access fallback", () => {
  const mockFetch = vi.fn();

  beforeEach(() => {
    mockFetch.mockReset();
    vi.stubGlobal("fetch", mockFetch);
    localStorage.setItem("adder.auth.token", "fake-token");
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  test("a discoverable non-member sees the access preview without room content", async () => {
    mockFetch.mockImplementation((url: string) =>
      Promise.resolve(
        url.endsWith("/access")
          ? jsonResponse(200, {
              id: 12,
              name: "Town Square",
              description: "Open to everyone",
              accessType: "PUBLIC",
              archived: false,
              requestStatus: null,
            })
          : jsonResponse(404, { title: "Not found", status: 404 }),
      ),
    );
    renderWithProviders(
      <RoomPage idParam="12" tab="home">
        {() => <p>secret room content</p>}
      </RoomPage>,
      { route: "/rooms/12" },
    );
    expect(await screen.findByRole("heading", { level: 2, name: "Town Square" })).toBeInTheDocument();
    expect(screen.getByText("Open to everyone")).toBeInTheDocument();
    expect(screen.queryByText("secret room content")).not.toBeInTheDocument();
    expect(mockFetch.mock.calls.map(([url]) => url)).toEqual(["/api/v1/rooms/12", "/api/v1/rooms/12/access"]);
  });

  test("a hidden room stays indistinguishable from a missing room", async () => {
    mockFetch.mockResolvedValue(jsonResponse(404, { title: "Not found", status: 404 }));
    renderWithProviders(
      <RoomPage idParam="12" tab="home">
        {() => <p>secret room content</p>}
      </RoomPage>,
      { route: "/rooms/12" },
    );
    expect(await screen.findByText("This room does not exist, or you are not a member of it.")).toBeInTheDocument();
    expect(screen.queryByText("secret room content")).not.toBeInTheDocument();
  });

  test("a fresh 404 suppresses cached room content while the access preview loads", async () => {
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    queryClient.setQueryData(["rooms", "detail", 12], {
      id: 12,
      name: "Former room",
      description: null,
      accessType: "PUBLIC",
      archived: false,
      archivedAt: null,
      role: "MEMBER",
      memberCount: 2,
      ownerCount: 1,
      workflowRevision: 1,
      lastActivityAt: 1,
      createdAt: 1,
      updatedAt: 1,
    });
    mockFetch.mockImplementation((url: string) =>
      Promise.resolve(
        url.endsWith("/access")
          ? jsonResponse(200, {
              id: 12,
              name: "Former room",
              description: null,
              accessType: "PUBLIC",
              archived: false,
              requestStatus: null,
            })
          : jsonResponse(404, { title: "Not found", status: 404 }),
      ),
    );
    renderWithProviders(
      <RoomPage idParam="12" tab="home">
        {() => <p>cached protected content</p>}
      </RoomPage>,
      { route: "/rooms/12", queryClient },
    );
    expect(await screen.findByRole("button", { name: "Join room" })).toBeInTheDocument();
    expect(screen.queryByText("cached protected content")).not.toBeInTheDocument();
  });

  test("a fresh access 404 suppresses a cached preview after the room is deleted", async () => {
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    queryClient.setQueryData(["rooms", "access", 12], {
      id: 12,
      name: "Deleted preview",
      description: "This must not survive the refetch",
      accessType: "PUBLIC",
      archived: false,
      requestStatus: null,
    });
    mockFetch.mockResolvedValue(jsonResponse(404, { title: "Not found", status: 404 }));

    renderWithProviders(
      <RoomPage idParam="12" tab="home">
        {() => <p>secret room content</p>}
      </RoomPage>,
      { route: "/rooms/12", queryClient },
    );

    expect(await screen.findByText("This room does not exist, or you are not a member of it.")).toBeInTheDocument();
    expect(screen.queryByRole("heading", { name: "Deleted preview" })).not.toBeInTheDocument();
  });

  test("an admin reading a visible room outside its membership still gets the enrollment action", async () => {
    mockFetch.mockImplementation((url: string, init?: RequestInit) => {
      if (init?.method === "POST") return Promise.resolve(new Response(null, { status: 204 }));
      if (url.includes("/pins?")) return Promise.resolve(jsonResponse(200, { items: [] }));
      if (url.endsWith("/access")) {
        return Promise.resolve(jsonResponse(200, { id: 12, name: "Town Square", description: null, accessType: "PUBLIC", archived: false, requestStatus: null }));
      }
      return Promise.resolve(
        jsonResponse(200, {
          id: 12,
          name: "Town Square",
          description: null,
          accessType: "PUBLIC",
          archived: false,
          archivedAt: null,
          role: null,
          memberCount: 2,
          ownerCount: 1,
          workflowRevision: 1,
          lastActivityAt: 1,
          createdAt: 1,
          updatedAt: 1,
        }),
      );
    });
    const user = userEvent.setup();
    renderWithProviders(
      <RoomPage idParam="12" tab="home">
        {() => <p>admin room content</p>}
      </RoomPage>,
      { route: "/rooms/12" },
    );

    expect(await screen.findByText("admin room content")).toBeInTheDocument();
    await user.click(await screen.findByRole("button", { name: "Join room" }));
    await waitFor(() => expect(mockFetch).toHaveBeenCalledWith("/api/v1/rooms/12/join", expect.objectContaining({ method: "POST" })));
  });
});
