import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { QueryClient } from "@tanstack/react-query";
import { jsonResponse } from "../test/http";
import { fireEvent, renderWithProviders, screen, waitFor } from "../test/render";
import { PROBLEM_TYPES } from "../utils/problems";
import RoomOrganizationPage from "./RoomOrganization";

vi.mock("../utils/toast", () => ({ showSuccessToast: vi.fn() }));

const groupId = "00000000-0000-4000-8000-000000000001";
const room = {
  id: 10,
  name: "Incident room",
  description: null,
  accessType: "RESTRICTED_HIDDEN",
  archived: false,
  role: "OWNER",
  memberCount: 2,
  lastActivityAt: 1,
  createdAt: 1,
  unread: false,
};

describe("RoomOrganizationPage", () => {
  const mockFetch = vi.fn();

  beforeEach(() => {
    localStorage.setItem("adder.auth.token", "fake-token");
    vi.stubGlobal("fetch", mockFetch);
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    localStorage.clear();
    mockFetch.mockReset();
    vi.restoreAllMocks();
  });

  test("keeps a stale draft until Reload deliberately replaces it", async () => {
    let organization = {
      revision: 1,
      groups: [{ id: groupId, name: "Operations" }],
      rooms: [{ roomId: 10, groupId }],
    };
    let sentRevision: string | null = null;
    let sentBody: unknown = null;
    let failNextReload = false;
    mockFetch.mockImplementation((url: string, init?: RequestInit) => {
      if (url === "/api/v1/room-organization" && (init?.method ?? "GET") === "GET") {
        if (failNextReload) {
          failNextReload = false;
          return Promise.resolve(jsonResponse(503, { status: 503, title: "Unavailable" }));
        }
        return Promise.resolve(jsonResponse(200, organization));
      }
      if (url === "/api/v1/room-organization" && init?.method === "PUT") {
        sentRevision = new Headers(init.headers).get("X-Expected-Revision");
        sentBody = JSON.parse(init.body as string);
        return Promise.resolve(jsonResponse(409, {
          status: 409,
          title: "Conflict",
          type: PROBLEM_TYPES.revisionConflict,
        }, { "Content-Type": "application/problem+json" }));
      }
      if (url.startsWith("/api/v1/rooms?")) {
        return Promise.resolve(jsonResponse(200, { items: [room], total: 1, page: 1, pageSize: 100 }));
      }
      return Promise.resolve(jsonResponse(404, { status: 404, title: "Not found" }));
    });
    const user = userEvent.setup();
    renderWithProviders(<RoomOrganizationPage />, { route: "/rooms/organize" });

    const name = await screen.findByRole("textbox", { name: "Rename Operations" });
    await user.clear(name);
    await user.type(name, "Local response");
    await user.click(screen.getByRole("button", { name: "Save organization" }));

    expect(await screen.findByText("This organization changed elsewhere")).toBeInTheDocument();
    expect(screen.getByRole("textbox", { name: "Rename Local response" })).toHaveValue("Local response");
    expect(sentRevision).toBe("1");
    expect(sentBody).toMatchObject({ groups: [{ id: groupId, name: "Local response" }] });

    organization = { ...organization, revision: 2, groups: [{ id: groupId, name: "Remote response" }] };
    failNextReload = true;
    await user.click(screen.getByRole("button", { name: "Reload organization" }));
    await waitFor(() => expect(screen.getByRole("button", { name: "Reload organization" })).not.toBeDisabled());
    expect(screen.getByRole("textbox", { name: "Rename Local response" })).toHaveValue("Local response");
    expect(screen.getByText("This organization changed elsewhere")).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "Reload organization" }));
    expect(await screen.findByRole("textbox", { name: "Rename Remote response" })).toHaveValue("Remote response");
    await waitFor(() => expect(screen.queryByText("This organization changed elsewhere")).not.toBeInTheDocument());
  });

  test("adopts a newer background response while the cached editor is clean", async () => {
    let resolveOrganization!: (response: Response) => void;
    const organizationResponse = new Promise<Response>((resolve) => { resolveOrganization = resolve; });
    mockFetch.mockImplementation((url: string) => {
      if (url === "/api/v1/room-organization") return organizationResponse;
      if (url.startsWith("/api/v1/rooms?")) {
        return Promise.resolve(jsonResponse(200, { items: [room], total: 1, page: 1, pageSize: 100 }));
      }
      return Promise.resolve(jsonResponse(404, { status: 404, title: "Not found" }));
    });
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    queryClient.setQueryData(["room-organization"], {
      revision: 1,
      groups: [{ id: groupId, name: "Cached operations" }],
      rooms: [{ roomId: 10, groupId }],
    });
    renderWithProviders(<RoomOrganizationPage />, { route: "/rooms/organize", queryClient });

    expect(await screen.findByRole("textbox", { name: "Rename Cached operations" })).toBeInTheDocument();
    resolveOrganization(jsonResponse(200, {
      revision: 1,
      groups: [{ id: groupId, name: "Remote operations" }],
      rooms: [{ roomId: 10, groupId }],
    }));
    expect(await screen.findByRole("textbox", { name: "Rename Remote operations" })).toBeInTheDocument();
  });

  test("does not pin loaded ungrouped rooms when only a group is created", async () => {
    vi.spyOn(crypto, "randomUUID").mockReturnValue("00000000-0000-4000-8000-000000000002");
    let sentBody: { rooms?: unknown[] } | null = null;
    mockFetch.mockImplementation((url: string, init?: RequestInit) => {
      if (url === "/api/v1/room-organization" && (init?.method ?? "GET") === "GET") {
        return Promise.resolve(jsonResponse(200, { revision: 1, groups: [], rooms: [] }));
      }
      if (url === "/api/v1/room-organization" && init?.method === "PUT") {
        sentBody = JSON.parse(init.body as string);
        return Promise.resolve(jsonResponse(200, { revision: 2, ...(sentBody as object) }));
      }
      if (url.startsWith("/api/v1/rooms?")) {
        return Promise.resolve(jsonResponse(200, { items: [room], total: 1, page: 1, pageSize: 100 }));
      }
      return Promise.resolve(jsonResponse(404, { status: 404, title: "Not found" }));
    });
    const user = userEvent.setup();
    renderWithProviders(<RoomOrganizationPage />, { route: "/rooms/organize" });

    await user.type(await screen.findByRole("textbox", { name: "Group name" }), "Operations");
    await user.click(screen.getByRole("button", { name: "Add group" }));
    await user.click(screen.getByRole("button", { name: "Back to rooms" }));
    expect(await screen.findByRole("dialog", { name: "Discard changes?" })).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Keep editing" }));
    expect(screen.getByRole("textbox", { name: "Rename Operations" })).toHaveValue("Operations");
    await user.click(screen.getByRole("button", { name: "Save organization" }));
    await waitFor(() => expect(sentBody).not.toBeNull());
    expect(sentBody).toMatchObject({ rooms: [] });
  });

  test("discard adopts placements pruned after an invalid membership save", async () => {
    let membershipLost = false;
    let organizationReads = 0;
    const sentBodies: Array<{ groups: Array<{ name: string }>; rooms: unknown[] }> = [];
    mockFetch.mockImplementation((url: string, init?: RequestInit) => {
      if (url === "/api/v1/room-organization" && (init?.method ?? "GET") === "GET") {
        organizationReads += 1;
        return Promise.resolve(jsonResponse(200, {
          revision: 1,
          groups: [{ id: groupId, name: "Operations" }],
          rooms: membershipLost ? [] : [{ roomId: 10, groupId }],
        }));
      }
      if (url === "/api/v1/room-organization" && init?.method === "PUT") {
        const body = JSON.parse(init.body as string) as { groups: Array<{ name: string }>; rooms: unknown[] };
        sentBodies.push(body);
        if (!membershipLost) {
          membershipLost = true;
          return Promise.resolve(jsonResponse(400, { status: 400, title: "Invalid organization" }));
        }
        return Promise.resolve(jsonResponse(200, { revision: 2, ...body }));
      }
      if (url.startsWith("/api/v1/rooms?")) {
        return Promise.resolve(jsonResponse(200, { items: [room], total: 1, page: 1, pageSize: 100 }));
      }
      return Promise.resolve(jsonResponse(404, { status: 404, title: "Not found" }));
    });
    const user = userEvent.setup();
    renderWithProviders(<RoomOrganizationPage />, { route: "/rooms/organize" });

    const name = await screen.findByRole("textbox", { name: "Rename Operations" });
    await user.clear(name);
    await user.type(name, "First edit");
    await user.click(screen.getByRole("button", { name: "Save organization" }));
    await waitFor(() => expect(organizationReads).toBe(2));

    await user.click(screen.getByRole("button", { name: "Discard changes" }));
    const recoveredName = await screen.findByRole("textbox", { name: "Rename Operations" });
    await user.clear(recoveredName);
    await user.type(recoveredName, "Second edit");
    await user.click(screen.getByRole("button", { name: "Save organization" }));
    await waitFor(() => expect(sentBodies).toHaveLength(2));
    expect(sentBodies[1]).toMatchObject({ groups: [{ name: "Second edit" }], rooms: [] });
  });

  test("loads another room page and guards a dirty organization from browser exit", async () => {
    vi.spyOn(crypto, "randomUUID").mockReturnValue("00000000-0000-4000-8000-000000000002");
    mockFetch.mockImplementation((url: string) => {
      if (url === "/api/v1/room-organization") {
        return Promise.resolve(jsonResponse(200, { revision: 1, groups: [], rooms: [] }));
      }
      if (url.includes("/api/v1/rooms?") && url.includes("page=2")) {
        return Promise.resolve(jsonResponse(200, {
          items: [{ ...room, id: 11, name: "Delivery room" }],
          total: 101,
          page: 2,
          pageSize: 100,
        }));
      }
      if (url.startsWith("/api/v1/rooms?")) {
        return Promise.resolve(jsonResponse(200, { items: [room], total: 101, page: 1, pageSize: 100 }));
      }
      return Promise.resolve(jsonResponse(404, { status: 404, title: "Not found" }));
    });
    const user = userEvent.setup();
    renderWithProviders(<RoomOrganizationPage />, { route: "/rooms/organize" });

    await user.click(await screen.findByRole("button", { name: "Load more rooms" }));
    expect(await screen.findByText("Delivery room")).toBeInTheDocument();
    expect(screen.getByText("Loaded 2 of 101 joined rooms")).toBeInTheDocument();

    await user.type(screen.getByRole("textbox", { name: "Group name" }), "Operations");
    await user.click(screen.getByRole("button", { name: "Add group" }));
    const event = new Event("beforeunload", { cancelable: true });
    fireEvent(window, event);
    expect(event.defaultPrevented).toBe(true);
  });
});
