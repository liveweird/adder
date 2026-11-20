import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import App from "../App";
import appSource from "../App.tsx?raw";
import { headingOutline } from "../test/headings";
import { jsonResponse } from "../test/http";
import { renderWithProviders } from "../test/render";
import { SocketStub } from "../test/socket";

// The heading-outline guard for EVERY page the router registers (web/CLAUDE.md "One heading outline
// everywhere"): the PageHeader title is the h2, sections h3, inner blocks h4. Rendered through the
// real <App/> routes under an ADMIN session with an empty-everything API, so it pins the structure
// every page keeps whatever it loads (the per-page tests pin the loaded outlines).

/** Route patterns → a concrete path the page renders at: every `:param` (`/rooms/:id/threads/:number` has two) becomes 1. */
function concretePath(pattern: string): string {
  return pattern.replace(/:\w+/g, "1");
}

const ROUTE_PATTERNS = [...appSource.matchAll(/<Route\s+(?:index|path="([^"]+)")/g)]
  .map((m) => m[1] ?? "")
  .filter((p) => p !== "*");

// Pages outside the shell (the auth cards) render signed OUT; the rest need a session.
const SIGNED_OUT = new Set(["/login", "/reset-password", "/reset-password/confirm"]);

// One room (id 1, the caller its owner) with a one-status workflow and one thread, so the room's pages — the thread list, the new-thread
// form and a thread's pane included — render their loaded outlines rather than the not-found state.
const ROOM = { id: 1, name: "Night Shift", description: null, archived: false, archivedAt: null, role: "OWNER", memberCount: 1, workflowRevision: 1, lastActivityAt: 1, createdAt: 1, updatedAt: 1 };
const WORKFLOW = { revision: 1, statuses: [{ key: "open", label: "Open", colour: "BLUE", category: "OPEN", initial: true, threadCount: 1 }, { key: "done", label: "Done", colour: "TEAL", category: "CLOSED", initial: false, threadCount: 0 }], fields: [] };
const THREAD_ITEM = { id: 1, number: 1, title: "Disk full", status: "open", closed: false, ownerId: 1, ownerName: "Admin", messageCount: 1, lastActivityAt: 1, createdAt: 1 };
const THREAD = { ...THREAD_ITEM, closedAt: null, createdById: 1, createdByName: "Admin", fields: {}, fieldUserNames: {}, revision: 1, updatedAt: 1 };
const MESSAGE = { id: 1, threadNumber: 1, authorId: 1, authorName: "Admin", body: "# A heading in a message\n\nDisk is full.", deleted: false, deletedByModerator: false, editedAt: null, createdAt: 1, reactions: [] };
const PAGE = { items: [], page: 1, pageSize: 20, total: 0 };

function emptyApi() {
  return vi.fn((input: RequestInfo | URL) => {
    const url = String(input);
    if (url === "/api/v1/rooms/1") return Promise.resolve(jsonResponse(200, ROOM));
    if (url === "/api/v1/rooms/1/workflow") return Promise.resolve(jsonResponse(200, WORKFLOW));
    if (url === "/api/v1/rooms/1/threads/1") return Promise.resolve(jsonResponse(200, THREAD));
    if (url.startsWith("/api/v1/rooms/1/threads/1/messages?")) return Promise.resolve(jsonResponse(200, { ...PAGE, items: [MESSAGE], total: 1 }));
    if (url.startsWith("/api/v1/rooms/1/threads/1/events?")) return Promise.resolve(jsonResponse(200, PAGE));
    if (url.startsWith("/api/v1/rooms/1/threads?")) return Promise.resolve(jsonResponse(200, { ...PAGE, items: [THREAD_ITEM], total: 1 }));
    if (/\/api\/v1\/(users|teams|workflow-templates|rooms)(\?|$)|\/api\/v1\/rooms\/1\/members\?/.test(url)) return Promise.resolve(jsonResponse(200, PAGE));
    return Promise.resolve(jsonResponse(404, { title: "Not found", status: 404 }));
  });
}

describe("heading outline of every registered page", () => {
  beforeEach(() => {
    vi.stubGlobal("fetch", emptyApi());
    // The shell opens the live socket; a stub keeps every page test off the network.
    vi.stubGlobal("WebSocket", SocketStub);
  });
  afterEach(() => {
    vi.unstubAllGlobals();
    SocketStub.reset();
  });

  test("the route list was read from App.tsx", () => {
    expect(ROUTE_PATTERNS.length).toBeGreaterThanOrEqual(12);
  });

  test.each(ROUTE_PATTERNS.map((p) => [p === "" ? "/" : `/${p.replace(/^\//, "")}`] as const))(
    "%s starts at h2 and never skips a level",
    async (pattern) => {
      if (!SIGNED_OUT.has(pattern)) {
        localStorage.setItem("adder.auth.token", "fake-token");
        localStorage.setItem("adder.auth.roles", JSON.stringify(["ADMIN"]));
        localStorage.setItem("adder.auth.userId", "1");
      }
      renderWithProviders(<App />, { route: concretePath(pattern) });
      // Settled: a heading exists and nothing is still loading, so late sections are in the outline too.
      await waitFor(() => {
        expect(screen.getAllByRole("heading").length).toBeGreaterThan(0);
        expect(document.querySelector('[aria-busy="true"], .mantine-Loader-root')).toBeNull();
      });
      const outline = headingOutline();
      expect(outline[0][0], JSON.stringify(outline)).toBe(2);
      outline.forEach(([level], i) => {
        if (i > 0) expect(level, `${outline[i][1]} in ${JSON.stringify(outline)}`).toBeLessThanOrEqual(outline[i - 1][0] + 1);
      });
    },
  );
});
