import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { MantineProvider } from "@mantine/core";
import { MemoryRouter } from "react-router-dom";
import { render, screen, within } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import App from "./App";
import { jsonResponse } from "./test/http";
import { SocketStub } from "./test/socket";

// The room view's routes through the real <App/> (the shell, the rooms rail and the lazy RoomHome chunk): a thread's route and the
// new-thread route, which must not be read as the thread numbered "new". Split out of App.test.tsx, which is at its function-size cap.

const ROOM_12 = { id: 12, name: "Night Shift", description: null, archived: false, archivedAt: null, role: "OWNER", memberCount: 3, workflowRevision: 1, lastActivityAt: 5, createdAt: 1, updatedAt: 1 };
const RAIL = { items: [ROOM_12], page: 1, pageSize: 100, total: 1 };
const THREAD_ITEM = { id: 1, number: 7, title: "Disk full on db-3", status: "open", closed: false, ownerId: 5, ownerName: "Ann Owner", messageCount: 2, lastActivityAt: 1, createdAt: 1 };
const WORKFLOW = { revision: 1, statuses: [{ key: "open", label: "Triage", colour: "BLUE", category: "OPEN", initial: true, threadCount: 1 }], fields: [] };

function renderApp(route: string) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <MantineProvider env="test">
      <QueryClientProvider client={queryClient}>
        <MemoryRouter initialEntries={[route]}>
          <App />
        </MemoryRouter>
      </QueryClientProvider>
    </MantineProvider>,
  );
}

describe("a room's thread routes", () => {
  beforeEach(() => {
    localStorage.setItem("adder.auth.token", "fake-token");
    vi.stubGlobal("WebSocket", SocketStub);
    vi.stubGlobal(
      "fetch",
      vi.fn((url: string) => {
        if (url.startsWith("/api/v1/rooms?")) return Promise.resolve(jsonResponse(200, RAIL));
        if (url === "/api/v1/rooms/12") return Promise.resolve(jsonResponse(200, ROOM_12));
        if (url === "/api/v1/rooms/12/workflow") return Promise.resolve(jsonResponse(200, WORKFLOW));
        if (url.startsWith("/api/v1/rooms/12/threads?")) return Promise.resolve(jsonResponse(200, { items: [THREAD_ITEM], page: 1, pageSize: 50, total: 1 }));
        if (url === "/api/v1/rooms/12/threads/7") {
          return Promise.resolve(jsonResponse(200, { ...THREAD_ITEM, closedAt: null, createdById: 5, createdByName: "Ann Owner", fields: {}, fieldUserNames: {}, revision: 1, updatedAt: 1 }));
        }
        return Promise.resolve(jsonResponse(404, { title: "Not found", status: 404 }));
      }),
    );
  });
  afterEach(() => {
    vi.unstubAllGlobals();
    SocketStub.reset();
    localStorage.clear();
  });

  test("/rooms/:id/threads/:number opens the room view on that thread, with the room lit in the rail and the Threads tab open", async () => {
    renderApp("/rooms/12/threads/7");
    expect(await screen.findByRole("region", { name: "#7 Disk full on db-3" })).toBeInTheDocument();
    expect(screen.getByRole("heading", { level: 2, name: "Night Shift" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Threads" })).toHaveAttribute("aria-current", "page");
    const rooms = screen.getByRole("group", { name: "Rooms" });
    expect(await within(rooms).findByRole("link", { name: "Night Shift" })).toHaveAttribute("aria-current", "page");
    const list = await screen.findByRole("navigation", { name: "Threads" });
    expect(within(list).getByRole("link", { name: /Disk full on db-3/ })).toHaveAttribute("aria-current", "page");
  });

  test("/rooms/:id/threads/new opens the new-thread form (not the thread route with the number 'new')", async () => {
    renderApp("/rooms/12/threads/new");
    expect(await screen.findByRole("heading", { level: 3, name: "New thread" })).toBeInTheDocument();
    expect(await screen.findByRole("textbox", { name: /Title/ })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Threads" })).toHaveAttribute("aria-current", "page");
    expect(screen.queryByText("This thread does not exist.")).not.toBeInTheDocument();
  });
});
