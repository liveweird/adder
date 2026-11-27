import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { screen, waitFor } from "@testing-library/react";
import { QueryClient, QueryObserver, useQuery } from "@tanstack/react-query";
import { notifications } from "@mantine/notifications";
import { Link, Route, Routes, useLocation, useParams } from "react-router-dom";
import RoomPage from "./RoomPage";
import { jsonResponse } from "../test/http";
import { renderWithProviders } from "../test/render";
import { homeKey, roomKeys, roomOrganizationKey } from "../utils/roomKeys";
import { clearSession, TOKEN_KEY } from "../api/session";
import { threadReferenceKey } from "../utils/threadReferenceCache";

const room = (role: "MEMBER" | null) => ({
  id: 12,
  name: "Town Square",
  description: null,
  accessType: "PUBLIC",
  archived: false,
  archivedAt: null,
  role,
  memberCount: role === null ? 2 : 3,
  ownerCount: 1,
  workflowRevision: 1,
  lastActivityAt: 1,
  createdAt: 1,
  updatedAt: 1,
});

function MembershipDependentContent() {
  useQuery({
    queryKey: [...roomKeys.threadList(12), 1],
    queryFn: async () => (await fetch("/test/thread-list")).json(),
  });
  useQuery({
    queryKey: roomKeys.threadDetail(12, 7),
    queryFn: async () => (await fetch("/test/thread-detail")).json(),
  });
  useQuery({
    queryKey: roomKeys.workflow(12),
    queryFn: async () => (await fetch("/test/workflow")).json(),
  });
  return <p>admin room content</p>;
}

function RoutedRoomPage() {
  const { id } = useParams();
  return <RoomPage idParam={id} tab="members">{() => <p>current route content</p>}</RoomPage>;
}

function LocationProbe() {
  return <output aria-label="location">{useLocation().pathname}</output>;
}

function RoomRoutes() {
  return (
    <>
      <LocationProbe />
      <Routes>
        <Route path="/rooms/:id" element={<RoutedRoomPage />} />
        <Route path="/rooms/:id/members" element={<RoutedRoomPage />} />
        <Route path="/rooms" element={<><p>rooms landing</p><Link to="/rooms/12/members">Return to room</Link></>} />
      </Routes>
    </>
  );
}

function MembershipRoutedRoomPage() {
  const { id } = useParams();
  return <RoomPage idParam={id} tab="members">{() => <MembershipDependentContent />}</RoomPage>;
}

function MembershipRoomRoutes() {
  return (
    <>
      <LocationProbe />
      <Routes>
        <Route path="/rooms/:id" element={<MembershipRoutedRoomPage />} />
        <Route path="/rooms/:id/members" element={<MembershipRoutedRoomPage />} />
      </Routes>
    </>
  );
}

function SameRoomRoutedPage() {
  const { id } = useParams();
  return (
    <>
      <Link to={`/rooms/${id}/threads/7`}>Open thread</Link>
      <RoomPage idParam={id} tab="home">{() => <p>same room content</p>}</RoomPage>
    </>
  );
}

function SameRoomRoutes() {
  return (
    <>
      <LocationProbe />
      <Routes><Route path="/rooms/:id/*" element={<SameRoomRoutedPage />} /></Routes>
    </>
  );
}

const mockFetch = vi.fn();

beforeEach(() => {
  mockFetch.mockReset();
  vi.stubGlobal("fetch", mockFetch);
  localStorage.setItem("adder.auth.token", "fake-token");
});

afterEach(() => {
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
  localStorage.clear();
});

describe("RoomPage access fallback", () => {
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
});

describe("RoomPage public join completion", () => {
  test("an admin reading a visible room outside its membership still gets the enrollment action", async () => {
    let joined = false;
    mockFetch.mockImplementation((url: string, init?: RequestInit) => {
      if (init?.method === "POST") {
        joined = true;
        return Promise.resolve(new Response(null, { status: 204 }));
      }
      if (url.includes("/pins?")) return Promise.resolve(jsonResponse(200, { items: [] }));
      if (url.endsWith("/access")) {
        return Promise.resolve(jsonResponse(200, { id: 12, name: "Town Square", description: null, accessType: "PUBLIC", archived: false, requestStatus: null }));
      }
      return Promise.resolve(jsonResponse(200, room(joined ? "MEMBER" : null)));
    });
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const invalidate = vi.spyOn(queryClient, "invalidateQueries");
    const user = userEvent.setup();
    renderWithProviders(
      <RoomPage idParam="12" tab="home">
        {() => <p>admin room content</p>}
      </RoomPage>,
      { route: "/rooms/12", queryClient },
    );

    expect(await screen.findByText("admin room content")).toBeInTheDocument();
    await user.click(await screen.findByRole("button", { name: "Join room" }));
    await waitFor(() => expect(mockFetch).toHaveBeenCalledWith("/api/v1/rooms/12/join", expect.objectContaining({ method: "POST" })));
    await waitFor(() => {
      expect(invalidate).toHaveBeenCalledWith({ queryKey: roomKeys.threadList(12) });
      expect(invalidate).toHaveBeenCalledWith({ queryKey: roomKeys.threadDetails(12) });
    });
  });

  test("a response-only public join refreshes targeted authority and loads current content", async () => {
    let joined = false;
    mockFetch.mockImplementation((url: string, init?: RequestInit) => {
      if (url === "/api/v1/rooms/12/join" && init?.method === "POST") {
        joined = true;
        return Promise.resolve(new Response(null, { status: 204 }));
      }
      if (url === "/api/v1/rooms/12") {
        return Promise.resolve(joined ? jsonResponse(200, room("MEMBER")) : jsonResponse(404, { title: "Not found", status: 404 }));
      }
      if (url === "/api/v1/rooms/12/access") {
        return Promise.resolve(jsonResponse(200, {
          id: 12, name: "Town Square", description: null, accessType: "PUBLIC", archived: false, requestStatus: null,
        }));
      }
      if (url.includes("/pins?")) return Promise.resolve(jsonResponse(200, { items: [], page: 1, pageSize: 50, total: 0 }));
      return Promise.resolve(jsonResponse(200, { items: [], page: 1, pageSize: 20, total: 0 }));
    });
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const invalidate = vi.spyOn(queryClient, "invalidateQueries");
    const unresolvedReference = threadReferenceKey(12, 42);
    queryClient.setQueryData(unresolvedReference, false);
    let resolveReference!: (value: boolean) => void;
    const referenceObserver = new QueryObserver(queryClient, {
      queryKey: unresolvedReference,
      queryFn: () => new Promise<boolean>((resolve) => { resolveReference = resolve; }),
      staleTime: Infinity,
    });
    const unsubscribeReference = referenceObserver.subscribe(() => undefined);
    const user = userEvent.setup();
    renderWithProviders(
      <RoomPage idParam="12" tab="home">{() => <p>current room content</p>}</RoomPage>,
      { route: "/rooms/12", queryClient },
    );

    await user.click(await screen.findByRole("button", { name: "Join room" }));
    expect(await screen.findByText("current room content")).toBeInTheDocument();
    for (const queryKey of [
      roomKeys.detail(12), roomKeys.access(12), roomKeys.members(12), roomKeys.rail, roomKeys.list, homeKey, roomOrganizationKey,
    ]) {
      expect(invalidate).toHaveBeenCalledWith({ queryKey });
    }
    expect(invalidate).not.toHaveBeenCalledWith({ queryKey: roomKeys.pins(12) });
    expect(invalidate).not.toHaveBeenCalledWith({ queryKey: roomKeys.workflow(12) });
    expect(invalidate).not.toHaveBeenCalledWith({ queryKey: roomKeys.threadList(12) });
    // Numeric-reference recovery is exact and intentionally does not hold up successful enrollment.
    await waitFor(() => expect(queryClient.isFetching({ queryKey: unresolvedReference, exact: true })).toBe(1));
    expect(screen.getByText("current room content")).toBeInTheDocument();
    resolveReference(true);
    await waitFor(() => expect(queryClient.isFetching({ queryKey: unresolvedReference, exact: true })).toBe(0));
    unsubscribeReference();
  });

  test("an outside-admin join re-reads thread membership projections but not unrelated room content", async () => {
    let joined = false;
    let release: () => void = () => {};
    mockFetch.mockImplementation((url: string, init?: RequestInit) => {
      if (url === "/api/v1/rooms/12/join" && init?.method === "POST") {
        return new Promise<Response>((resolve) => {
          release = () => {
            joined = true;
            resolve(new Response(null, { status: 204 }));
          };
        });
      }
      if (url === "/api/v1/rooms/12") return Promise.resolve(jsonResponse(200, room(joined ? "MEMBER" : null)));
      if (url === "/api/v1/rooms/12/access") {
        return Promise.resolve(jsonResponse(200, {
          id: 12, name: "Town Square", description: null, accessType: "PUBLIC", archived: false, requestStatus: null,
        }));
      }
      if (url.includes("/pins?")) return Promise.resolve(jsonResponse(200, { items: [], page: 1, pageSize: 50, total: 0 }));
      if (url.startsWith("/test/")) return Promise.resolve(jsonResponse(200, { marker: url }));
      return Promise.resolve(jsonResponse(200, { items: [], page: 1, pageSize: 20, total: 0 }));
    });
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const user = userEvent.setup();
    renderWithProviders(
      <RoomPage idParam="12" tab="home">{() => <MembershipDependentContent />}</RoomPage>,
      { route: "/rooms/12", queryClient },
    );
    expect(await screen.findByText("admin room content")).toBeInTheDocument();
    await waitFor(() => expect(mockFetch.mock.calls.filter(([url]) => String(url).startsWith("/test/"))).toHaveLength(3));
    await user.click(await screen.findByRole("button", { name: "Join room" }));
    release();
    await waitFor(() => expect(screen.queryByRole("button", { name: "Join room" })).not.toBeInTheDocument());

    expect(mockFetch.mock.calls.filter(([url]) => url === "/test/thread-list")).toHaveLength(2);
    expect(mockFetch.mock.calls.filter(([url]) => url === "/test/thread-detail")).toHaveLength(2);
    expect(mockFetch.mock.calls.filter(([url]) => url === "/test/workflow")).toHaveLength(1);
    expect(mockFetch.mock.calls.filter(([url]) => String(url).includes("/pins?"))).toHaveLength(1);
  });

  test("a held response still finishes the original route after live replaces its preview with content", async () => {
    let member = false;
    let release: () => void = () => {};
    mockFetch.mockImplementation((url: string, init?: RequestInit) => {
      if (url === "/api/v1/rooms/12/join" && init?.method === "POST") {
        return new Promise<Response>((resolve) => {
          release = () => {
            member = true;
            resolve(new Response(null, { status: 204 }));
          };
        });
      }
      if (url === "/api/v1/rooms/12") {
        return Promise.resolve(member ? jsonResponse(200, room("MEMBER")) : jsonResponse(404, { title: "Not found", status: 404 }));
      }
      if (url === "/api/v1/rooms/12/access") {
        return Promise.resolve(jsonResponse(200, {
          id: 12, name: "Town Square", description: null, accessType: "PUBLIC", archived: false, requestStatus: null,
        }));
      }
      if (url.includes("/pins?")) return Promise.resolve(jsonResponse(200, { items: [], page: 1, pageSize: 50, total: 0 }));
      return Promise.resolve(jsonResponse(200, { items: [], page: 1, pageSize: 20, total: 0 }));
    });
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const showToast = vi.spyOn(notifications, "show");
    const user = userEvent.setup();
    renderWithProviders(<MembershipRoomRoutes />, { route: "/rooms/12/members", queryClient });
    await user.click(await screen.findByRole("button", { name: "Join room" }));

    member = true;
    queryClient.setQueryData(roomKeys.detail(12), room("MEMBER"));
    expect(await screen.findByText("admin room content")).toBeInTheDocument();
    // The real body is live-ready before the response. The preview authority captured at submission prevents admin-only fallbacks.
    await waitFor(() => expect(mockFetch.mock.calls.filter(([url]) => String(url).startsWith("/test/"))).toHaveLength(3));
    release();

    await waitFor(() => expect(screen.getByRole("status", { name: "location" })).toHaveTextContent("/rooms/12"));
    expect(showToast).toHaveBeenCalledWith(expect.objectContaining({ message: "You joined the room" }));
    expect(mockFetch.mock.calls.filter(([url]) => url === "/test/thread-list")).toHaveLength(1);
    expect(mockFetch.mock.calls.filter(([url]) => url === "/test/thread-detail")).toHaveLength(1);
    expect(mockFetch.mock.calls.filter(([url]) => url === "/test/workflow")).toHaveLength(1);
  });

  test("a held response cannot take a still-mounted room page away from its newly opened thread", async () => {
    let joined = false;
    let release: () => void = () => {};
    mockFetch.mockImplementation((url: string, init?: RequestInit) => {
      if (url === "/api/v1/rooms/12/join" && init?.method === "POST") {
        return new Promise<Response>((resolve) => {
          release = () => {
            joined = true;
            resolve(new Response(null, { status: 204 }));
          };
        });
      }
      if (url === "/api/v1/rooms/12") return Promise.resolve(jsonResponse(200, room(joined ? "MEMBER" : null)));
      if (url === "/api/v1/rooms/12/access") {
        return Promise.resolve(jsonResponse(200, {
          id: 12, name: "Town Square", description: null, accessType: "PUBLIC", archived: false, requestStatus: null,
        }));
      }
      if (url.includes("/pins?")) return Promise.resolve(jsonResponse(200, { items: [], page: 1, pageSize: 50, total: 0 }));
      return Promise.resolve(jsonResponse(200, { items: [], page: 1, pageSize: 20, total: 0 }));
    });
    const showToast = vi.spyOn(notifications, "show");
    const user = userEvent.setup();
    renderWithProviders(<SameRoomRoutes />, { route: "/rooms/12" });
    await user.click(await screen.findByRole("button", { name: "Join room" }));
    await user.click(screen.getByRole("link", { name: "Open thread" }));

    expect(screen.getByRole("status", { name: "location" })).toHaveTextContent("/rooms/12/threads/7");
    expect(screen.getByRole("button", { name: "Join room" })).toBeEnabled();
    release();

    await waitFor(() => expect(screen.queryByRole("button", { name: "Join room" })).not.toBeInTheDocument());
    expect(screen.getByRole("status", { name: "location" })).toHaveTextContent("/rooms/12/threads/7");
    expect(showToast).not.toHaveBeenCalled();
  });

  test("a fresh removal response cannot redirect using retained member data", async () => {
    let removed = false;
    let release: () => void = () => {};
    mockFetch.mockImplementation((url: string, init?: RequestInit) => {
      if (url === "/api/v1/rooms/12/join" && init?.method === "POST") {
        return new Promise<Response>((resolve) => {
          release = () => {
            removed = true;
            resolve(new Response(null, { status: 204 }));
          };
        });
      }
      if (url === "/api/v1/rooms/12") {
        return Promise.resolve(jsonResponse(404, { title: removed ? "Removed" : "Not found", status: 404 }));
      }
      if (url === "/api/v1/rooms/12/access") {
        return Promise.resolve(jsonResponse(200, {
          id: 12, name: "Town Square", description: null, accessType: "PUBLIC", archived: false, requestStatus: null,
        }));
      }
      if (url.includes("/pins?")) return Promise.resolve(jsonResponse(200, { items: [], page: 1, pageSize: 50, total: 0 }));
      return Promise.resolve(jsonResponse(200, { items: [], page: 1, pageSize: 20, total: 0 }));
    });
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const user = userEvent.setup();
    renderWithProviders(<RoomRoutes />, { route: "/rooms/12/members", queryClient });
    await user.click(await screen.findByRole("button", { name: "Join room" }));

    queryClient.setQueryData(roomKeys.detail(12), room("MEMBER"));
    expect(await screen.findByText("current route content")).toBeInTheDocument();
    release();

    expect(await screen.findByRole("button", { name: "Join room" })).toBeInTheDocument();
    expect(queryClient.getQueryState(roomKeys.detail(12))?.status).toBe("error");
    expect(screen.getByRole("status", { name: "location" })).toHaveTextContent("/rooms/12/members");
  });

  test("a response from an abandoned route cannot redirect a revisited room", async () => {
    let member = false;
    let release: () => void = () => {};
    mockFetch.mockImplementation((url: string, init?: RequestInit) => {
      if (url === "/api/v1/rooms/12/join" && init?.method === "POST") {
        return new Promise<Response>((resolve) => {
          release = () => {
            member = true;
            resolve(new Response(null, { status: 204 }));
          };
        });
      }
      if (url === "/api/v1/rooms/12") {
        return Promise.resolve(member ? jsonResponse(200, room("MEMBER")) : jsonResponse(404, { title: "Not found", status: 404 }));
      }
      if (url === "/api/v1/rooms/12/access") {
        return Promise.resolve(jsonResponse(200, {
          id: 12, name: "Town Square", description: null, accessType: "PUBLIC", archived: false, requestStatus: null,
        }));
      }
      if (url.includes("/pins?")) return Promise.resolve(jsonResponse(200, { items: [], page: 1, pageSize: 50, total: 0 }));
      return Promise.resolve(jsonResponse(200, { items: [], page: 1, pageSize: 20, total: 0 }));
    });
    const user = userEvent.setup();
    renderWithProviders(<RoomRoutes />, { route: "/rooms/12/members" });
    await user.click(await screen.findByRole("button", { name: "Join room" }));
    await user.click(screen.getByRole("link", { name: "Back to room directory" }));
    await user.click(await screen.findByRole("link", { name: "Return to room" }));
    expect(await screen.findByRole("button", { name: "Join room" })).toBeInTheDocument();

    release();
    expect(await screen.findByText("current route content")).toBeInTheDocument();
    expect(screen.getByRole("status", { name: "location" })).toHaveTextContent("/rooms/12/members");
  });

  test("a replacement session cannot be redirected after its preview was removed", async () => {
    let release: () => void = () => {};
    mockFetch.mockImplementation((url: string, init?: RequestInit) => {
      if (url === "/api/v1/rooms/12/join" && init?.method === "POST") {
        return new Promise<Response>((resolve) => { release = () => resolve(new Response(null, { status: 204 })); });
      }
      if (url === "/api/v1/rooms/12") return Promise.resolve(jsonResponse(404, { title: "Not found", status: 404 }));
      if (url === "/api/v1/rooms/12/access") {
        return Promise.resolve(jsonResponse(200, {
          id: 12, name: "Town Square", description: null, accessType: "PUBLIC", archived: false, requestStatus: null,
        }));
      }
      if (url.includes("/pins?")) return Promise.resolve(jsonResponse(200, { items: [], page: 1, pageSize: 50, total: 0 }));
      return Promise.resolve(jsonResponse(200, { items: [], page: 1, pageSize: 20, total: 0 }));
    });
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const user = userEvent.setup();
    renderWithProviders(<RoomRoutes />, { route: "/rooms/12/members", queryClient });
    await user.click(await screen.findByRole("button", { name: "Join room" }));
    clearSession();
    localStorage.setItem(TOKEN_KEY, "replacement-token");
    queryClient.setQueryData(roomKeys.detail(12), room("MEMBER"));
    expect(await screen.findByText("current route content")).toBeInTheDocument();
    release();

    await waitFor(() => expect(screen.getByRole("status", { name: "location" })).toHaveTextContent("/rooms/12/members"));
  });
});
