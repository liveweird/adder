import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { screen, waitFor } from "@testing-library/react";
import RoomJoinRequests from "./RoomJoinRequests";
import { jsonResponse } from "../test/http";
import { renderWithProviders } from "../test/render";

const request = (id: number) => ({ id, userId: 1000 + id, name: `Person ${id}`, requestedAt: 1_700_000_000_000 + id });

describe("RoomJoinRequests", () => {
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

  test("deciding the last request on the last page returns to the last valid page", async () => {
    let decided = false;
    mockFetch.mockImplementation((url: string, init?: RequestInit) => {
      if (init?.method === "PUT") {
        decided = true;
        return Promise.resolve(new Response(null, { status: 204 }));
      }
      const page = new URL(url, "http://example.test").searchParams.get("page");
      if (page === "2") {
        return Promise.resolve(jsonResponse(200, { items: decided ? [] : [request(21)], total: decided ? 20 : 21, page: 2, pageSize: 20 }));
      }
      return Promise.resolve(jsonResponse(200, { items: Array.from({ length: 20 }, (_, index) => request(index + 1)), total: decided ? 20 : 21, page: 1, pageSize: 20 }));
    });
    const user = userEvent.setup();
    renderWithProviders(<RoomJoinRequests roomId={12} />);

    expect(await screen.findByText("Person 1")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "2" }));
    expect(await screen.findByText("Person 21")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Approve Person 21" }));

    await waitFor(() => expect(screen.getByText("Person 1")).toBeInTheDocument());
    expect(screen.queryByText("No pending requests.")).not.toBeInTheDocument();
  });

  test("focuses the request queue when an async room route carries its anchor", async () => {
    mockFetch.mockResolvedValue(jsonResponse(200, { items: [], total: 0, page: 1, pageSize: 20 }));
    renderWithProviders(<RoomJoinRequests roomId={12} />, { route: "/rooms/12/members#room-join-requests-title" });

    const heading = await screen.findByRole("heading", { name: "Join requests" });
    await waitFor(() => expect(heading).toHaveFocus());
  });
});
