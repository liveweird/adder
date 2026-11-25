import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { MantineProvider } from "@mantine/core";
import { MemoryRouter } from "react-router-dom";
import { render, screen, waitFor, within } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import App from "./App";
import { consumeSignedOut } from "./auth";
import { APP_VERSION } from "./changelog/version";
import { jsonResponse } from "./test/http";
import { SocketStub } from "./test/socket";
import { currentFixture, historyFixture } from "./test/telemetryFixtures";

const TOKEN_KEY = "adder.auth.token";

function renderApp(route: string) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
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

describe("App shell", () => {
  beforeEach(() => {
    vi.stubGlobal("fetch", vi.fn());
    // The shell opens the live socket; a stub keeps these tests off the network (App.live.test.tsx drives it).
    vi.stubGlobal("WebSocket", SocketStub);
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    SocketStub.reset();
    localStorage.clear();
    // The signed-out banner flag is module-level state (`auth.tsx`); the sign-out flows set it and only the login page consumes it.
    consumeSignedOut();
  });

  describe("when authenticated", () => {
    beforeEach(() => {
      localStorage.setItem(TOKEN_KEY, "fake-token");
    });

    test("renders the brand and the home page at /", async () => {
      renderApp("/");
      expect(await screen.findByRole("heading", { level: 2, name: "Home" })).toBeInTheDocument();
      expect(screen.getAllByText("Adder").length).toBeGreaterThan(0);
    });

    test("the navbar shows the version stamp", async () => {
      renderApp("/");
      expect(await screen.findByText(new RegExp(`v${APP_VERSION.replace(/\./g, "\\.")}`))).toBeInTheDocument();
    });

    test("the sidebar renders its sections with every leaf link addressable", async () => {
      renderApp("/");
      // Sections are labelled groups, never toggles — every leaf is in the DOM immediately.
      const overview = await screen.findByRole("group", { name: "Overview" });
      expect(within(overview).getByRole("link", { name: "Home" })).toHaveAttribute("href", "/");
      // Rooms: the static "All rooms" leaf (the caller's own rooms hang under it, see "the rooms rail").
      const rooms = screen.getByRole("group", { name: "Rooms" });
      expect(within(rooms).getByRole("link", { name: "All rooms" })).toHaveAttribute("href", "/rooms");
      expect(within(rooms).queryByRole("link", { name: "Organize rooms" })).not.toBeInTheDocument();
      // A non-admin session still sees Administration — Teams are everyone's, only Users and
      // Feature flags are admin-only and drop out.
      const admin = screen.getByRole("group", { name: "Administration" });
      expect(within(admin).getByRole("link", { name: "Teams" })).toHaveAttribute("href", "/teams");
      expect(within(admin).queryByRole("link", { name: "Users" })).not.toBeInTheDocument();
      expect(within(admin).queryByRole("link", { name: "Feature flags" })).not.toBeInTheDocument();
      expect(within(admin).queryByRole("link", { name: "Workflow templates" })).not.toBeInTheDocument();
      expect(within(admin).queryByRole("link", { name: "Tags" })).not.toBeInTheDocument();
      // Account items live in the header menu, not the sidebar.
      expect(screen.queryByRole("link", { name: "Change password" })).not.toBeInTheDocument();
    });

    test("the nav toggle collapses the sidebar to an icon rail and persists the choice", async () => {
      const user = userEvent.setup();
      renderApp("/");
      const overview = await screen.findByRole("group", { name: "Overview" });
      expect(within(overview).getByText("Overview")).toBeInTheDocument();

      await user.click(screen.getByRole("button", { name: "Show or hide the navigation" }));

      // The section label text is gone, but every leaf is still addressable by name and href.
      expect(screen.queryByText("Overview")).not.toBeInTheDocument();
      expect(screen.getByRole("link", { name: "Home" })).toHaveAttribute("href", "/");
      expect(screen.getByRole("link", { name: "Teams" })).toHaveAttribute("href", "/teams");
      expect(within(screen.getByRole("navigation", { name: "Navigation" })).getByRole("link", { name: "All rooms" })).toHaveAttribute("href", "/rooms");
      expect(localStorage.getItem("adder.viewSettings.appShell.navCollapsed")).toBe("true");
    });

    test("mobile navigation dismisses on Escape and focuses the destination when Search clears the query", async () => {
      const desktopWidth = window.innerWidth;
      window.innerWidth = 390;
      try {
        const user = userEvent.setup();
        renderApp("/search?q=incident");
        const burger = screen.getByRole("button", { name: "Toggle navigation" });
        expect(burger).toHaveAttribute("aria-expanded", "false");
        await user.click(burger);
        const drawer = await screen.findByRole("dialog", { name: "Navigation" });
        expect(burger).toHaveAttribute("aria-expanded", "true");
        expect(within(drawer).getByRole("link", { name: "All rooms" })).toHaveAttribute("href", "/rooms");
        await user.keyboard("{Escape}");
        expect(drawer).not.toBeInTheDocument();
        expect(burger).toHaveAttribute("aria-expanded", "false");

        await user.click(burger);
        const reopened = await screen.findByRole("dialog", { name: "Navigation" });
        await user.click(within(reopened).getByRole("link", { name: "Search" }));
        await waitFor(() => expect(screen.getByRole("main")).toHaveFocus());
        expect(reopened).not.toBeInTheDocument();
      } finally {
        window.innerWidth = desktopWidth;
      }
    });

    test("a fresh render starts collapsed once the choice was persisted", async () => {
      localStorage.setItem("adder.viewSettings.appShell.navCollapsed", "true");
      renderApp("/");
      await screen.findByRole("heading", { level: 2, name: "Home" });
      expect(screen.queryByText("Overview")).not.toBeInTheDocument();
      const toggle = screen.getByRole("button", { name: "Show or hide the navigation" });
      expect(toggle).not.toHaveAttribute("data-expanded");
      expect(screen.getByRole("link", { name: "Home" })).toHaveAttribute("href", "/");
    });

    test("an admin session additionally sees Users, Workflow templates, Tags and Feature flags in Administration", async () => {
      localStorage.setItem("adder.auth.roles", JSON.stringify(["ADMIN"]));
      renderApp("/");
      const admin = await screen.findByRole("group", { name: "Administration" });
      expect(within(admin).getByRole("link", { name: "Teams" })).toHaveAttribute("href", "/teams");
      expect(within(admin).getByRole("link", { name: "Users" })).toHaveAttribute("href", "/users");
      expect(within(admin).getByRole("link", { name: "Workflow templates" })).toHaveAttribute("href", "/workflow-templates");
      expect(within(admin).getByRole("link", { name: "Tags" })).toHaveAttribute("href", "/tags");
      expect(within(admin).getByRole("link", { name: "Feature flags" })).toHaveAttribute("href", "/feature-flags");
    });

    describe("the rooms rail", () => {
      const ROOM = { description: null, role: "OWNER", memberCount: 3, lastActivityAt: 5, createdAt: 1 };
      const ACTIVE = {
        items: [
          { id: 12, name: "Night Shift", archived: false, ...ROOM },
          { id: 13, name: "Billing", archived: false, ...ROOM },
        ],
        page: 1,
        pageSize: 100,
        total: 2,
      };
      const ARCHIVED = { items: [{ id: 20, name: "Old Incident", archived: true, ...ROOM }], page: 1, pageSize: 100, total: 1 };

      /** Answers the rail's two list queries; every other request is a 404 (the page behind it is not the subject). */
      function stubRooms(active: unknown = ACTIVE, archived: unknown = ARCHIVED) {
        const fetchMock = vi.fn((url: string) => {
          if (url.startsWith("/api/v1/rooms?")) return Promise.resolve(jsonResponse(200, url.includes("archived=true") ? archived : active));
          return Promise.resolve(jsonResponse(404, { title: "Not found", status: 404 }));
        });
        vi.stubGlobal("fetch", fetchMock);
        return fetchMock;
      }

      test("lists the caller's active rooms under All rooms, newest activity first, one request", async () => {
        const fetchMock = stubRooms();
        renderApp("/");
        const rooms = await screen.findByRole("group", { name: "Rooms" });
        expect(await within(rooms).findByRole("link", { name: "Night Shift" })).toHaveAttribute("href", "/rooms/12");
        expect(within(rooms).getByRole("link", { name: "Billing" })).toHaveAttribute("href", "/rooms/13");
        expect(within(rooms).queryByRole("link", { name: "Old Incident" })).not.toBeInTheDocument();
        const railCalls = fetchMock.mock.calls.filter(([url]) => String(url).startsWith("/api/v1/rooms?"));
        // The rail and the command palette share ONE query.
        expect(railCalls.map(([url]) => url)).toEqual(["/api/v1/rooms?page=1&pageSize=100&sort=personalOrder&archived=false&member=true"]);
      });

      test("a new room and the unseen notices of a room are marked in the rail by words, from the rooms list and the bell's query", async () => {
        const notice = (id: number) => ({ id, type: "MENTIONED", seen: false, createdAt: 1, actorId: 5, actorName: "Ann", roomId: 13, roomName: "Billing", threadNumber: 2, threadTitle: "t", messageId: 9 });
        vi.stubGlobal(
          "fetch",
          vi.fn((url: string) => {
            if (url.startsWith("/api/v1/notifications/feed?") || url.startsWith("/api/v1/notifications?")) return Promise.resolve(jsonResponse(200, { items: [notice(1), notice(2)], page: 1, pageSize: 100, total: 2 }));
            if (url.startsWith("/api/v1/rooms?")) {
              const items = ACTIVE.items.map((room) => ({ ...room, unread: room.id === 12 }));
              return Promise.resolve(jsonResponse(200, { ...ACTIVE, items }));
            }
            return Promise.resolve(jsonResponse(404, { title: "Not found", status: 404 }));
          }),
        );
        renderApp("/");
        const rooms = await screen.findByRole("group", { name: "Rooms" });
        await waitFor(() => expect(within(rooms).getByRole("link", { name: "Night Shift" })).toHaveAccessibleDescription("New activity"));
        await waitFor(() => expect(within(rooms).getByRole("link", { name: "Billing" })).toHaveAccessibleDescription("2 unseen notifications"));
      });

      test("a room's own pages highlight that room (not All rooms), including pages the app has no route for yet", async () => {
        stubRooms();
        renderApp("/rooms/12/members");
        const rooms = await screen.findByRole("group", { name: "Rooms" });
        const night = await within(rooms).findByRole("link", { name: "Night Shift" });
        expect(night).toHaveAttribute("aria-current", "page");
        expect(within(rooms).getByRole("link", { name: "Billing" })).not.toHaveAttribute("aria-current");
        expect(within(rooms).getByRole("link", { name: "All rooms" })).not.toHaveAttribute("aria-current");
      });

      test("Show archived adds the archived rooms, dimmed under their own group, and the choice persists", async () => {
        const user = userEvent.setup();
        const fetchMock = stubRooms();
        renderApp("/");
        const rooms = await screen.findByRole("group", { name: "Rooms" });
        await within(rooms).findByRole("link", { name: "Night Shift" });
        expect(fetchMock.mock.calls.some(([url]) => String(url).includes("archived=true"))).toBe(false);
        await user.click(within(rooms).getByRole("switch", { name: "Show archived" }));
        const archived = await within(rooms).findByRole("group", { name: "Archived rooms" });
        expect(within(archived).getByRole("link", { name: "Old Incident" })).toHaveAttribute("href", "/rooms/20");
        expect(localStorage.getItem("adder.viewSettings.rooms.rail.showArchived")).toBe("true");
      });

      test("the stored archived toggle is honoured on a fresh render", async () => {
        localStorage.setItem("adder.viewSettings.rooms.rail.showArchived", "true");
        stubRooms();
        renderApp("/rooms/20/settings");
        const archived = await screen.findByRole("group", { name: "Archived rooms" });
        expect(await within(archived).findByRole("link", { name: "Old Incident" })).toHaveAttribute("aria-current", "page");
      });

      test("creating a room refreshes the rail: the new room is listed and current without a reload", async () => {
        let created = false;
        const NEW_ROOM = { id: 30, name: "Fresh Room", archived: false, ...ROOM };
        vi.stubGlobal(
          "fetch",
          vi.fn((url: string, init?: RequestInit) => {
            if (init?.method === "POST" && url === "/api/v1/rooms") {
              created = true;
              return Promise.resolve(jsonResponse(201, NEW_ROOM));
            }
            if (url.startsWith("/api/v1/rooms?")) {
              return Promise.resolve(jsonResponse(200, { ...ACTIVE, items: created ? [NEW_ROOM, ...ACTIVE.items] : ACTIVE.items }));
            }
            if (url.startsWith("/api/v1/workflow-templates?")) {
              return Promise.resolve(jsonResponse(200, { items: [{ id: 1, name: "Default", description: null, statusCount: 2, fieldCount: 0, revision: 1, updatedAt: 1 }], page: 1, pageSize: 100, total: 1 }));
            }
            if (url === "/api/v1/workflow-templates/1") {
              return Promise.resolve(jsonResponse(200, { id: 1, name: "Default", description: null, statuses: [], fields: [], revision: 1, createdAt: 1, updatedAt: 1 }));
            }
            return Promise.resolve(jsonResponse(404, { title: "Not found", status: 404 }));
          }),
        );
        const user = userEvent.setup();
        renderApp("/rooms/new");
        const rooms = await screen.findByRole("group", { name: "Rooms" });
        await within(rooms).findByRole("link", { name: "Night Shift" });
        expect(within(rooms).queryByRole("link", { name: "Fresh Room" })).not.toBeInTheDocument();
        await user.type(await screen.findByRole("textbox", { name: "Name" }), "Fresh Room");
        await user.click(screen.getByRole("combobox", { name: /Workflow template/ }));
        await user.click(await screen.findByRole("option", { name: "Default" }));
        await user.click(screen.getByRole("button", { name: "Create" }));
        const fresh = await within(rooms).findByRole("link", { name: "Fresh Room" });
        expect(fresh).toHaveAttribute("href", "/rooms/30");
        await waitFor(() => expect(fresh).toHaveAttribute("aria-current", "page"));
      });

      test("the icon rail shows each room as an initials avatar named by the room", async () => {
        localStorage.setItem("adder.viewSettings.appShell.navCollapsed", "true");
        stubRooms();
        renderApp("/");
        const night = await screen.findByRole("link", { name: "Night Shift" });
        expect(night).toHaveTextContent("NS");
        expect(night).toHaveAttribute("href", "/rooms/12");
        // The toggle and the captions only exist in the full-width rail.
        expect(screen.queryByRole("switch", { name: "Show archived" })).not.toBeInTheDocument();
      });

      test("an empty list says so, compactly", async () => {
        stubRooms({ items: [], page: 1, pageSize: 100, total: 0 });
        renderApp("/");
        expect(await screen.findByText("You are not in any room yet")).toBeInTheDocument();
      });

      test("a list longer than the rail offers a link to the Rooms page", async () => {
        stubRooms({ ...ACTIVE, total: 150 });
        renderApp("/");
        expect(await screen.findByRole("link", { name: "More rooms…" })).toHaveAttribute("href", "/rooms");
      });

      test("a failed load shows a compact alert and the rest of the shell keeps working", async () => {
        vi.stubGlobal("fetch", vi.fn(() => Promise.resolve(jsonResponse(500, { title: "Boom", status: 500 }))));
        renderApp("/");
        expect(await screen.findByText("Could not load your rooms")).toBeInTheDocument();
        expect(within(screen.getByRole("navigation", { name: "Navigation" })).getByRole("link", { name: "All rooms" })).toBeInTheDocument();
      });

      test("a failed load in the icon rail is a labelled icon", async () => {
        localStorage.setItem("adder.viewSettings.appShell.navCollapsed", "true");
        vi.stubGlobal("fetch", vi.fn(() => Promise.resolve(jsonResponse(500, { title: "Boom", status: 500 }))));
        renderApp("/");
        expect(await screen.findByRole("img", { name: "Could not load your rooms" })).toBeInTheDocument();
      });
    });

    describe("the telemetry panel", () => {
      const PANEL_KEY = "adder.viewSettings.telemetry.panelOpen";

      function stubTelemetry(current: unknown = currentFixture()) {
        const fetchMock = vi.fn((url: string) => {
          if (url.startsWith("/api/v1/telemetry/current")) return Promise.resolve(jsonResponse(200, current));
          if (url.startsWith("/api/v1/telemetry/history")) return Promise.resolve(jsonResponse(200, historyFixture("memory", "24h", [])));
          return Promise.reject(new Error("offline"));
        });
        vi.stubGlobal("fetch", fetchMock);
        return fetchMock;
      }

      test("an admin opens it from the account menu (it pushes the page, lands in an aside) and closes it with its own button", async () => {
        localStorage.setItem("adder.auth.roles", JSON.stringify(["ADMIN"]));
        stubTelemetry();
        const user = userEvent.setup();
        renderApp("/");
        await user.click(await screen.findByRole("button", { name: /account menu/i }));
        const item = await screen.findByRole("menuitem", { name: "Telemetry panel" });
        expect(item).not.toHaveAttribute("aria-current");
        await user.click(item);
        const aside = await screen.findByRole("complementary", { name: "Telemetry" });
        expect(await within(aside).findByRole("button", { name: "Memory" })).toHaveAttribute("aria-expanded", "true");
        expect(localStorage.getItem(PANEL_KEY)).toBe("true");
        await user.click(within(aside).getByRole("button", { name: "Close" }));
        await waitFor(() => expect(screen.queryByRole("complementary", { name: "Telemetry" })).not.toBeInTheDocument());
        expect(localStorage.getItem(PANEL_KEY)).toBe("false");
      });

      test("a stored open flag never shows it to a regular user, and nothing is requested", async () => {
        localStorage.setItem(PANEL_KEY, "true");
        const fetchMock = stubTelemetry();
        renderApp("/");
        await screen.findByRole("group", { name: "Overview" });
        expect(screen.queryByRole("complementary", { name: "Telemetry" })).not.toBeInTheDocument();
        expect(fetchMock.mock.calls.some(([url]) => String(url).includes("/telemetry/"))).toBe(false);
      });

      test("a render crash inside the panel keeps the page and offers Close panel, which clears the stored flag", async () => {
        localStorage.setItem("adder.auth.roles", JSON.stringify(["ADMIN"]));
        localStorage.setItem(PANEL_KEY, "true");
        // A malformed answer (no processes) crashes the panel's render.
        stubTelemetry({ generatedAt: 1 });
        const consoleError = vi.spyOn(console, "error").mockImplementation(() => {});
        const user = userEvent.setup();
        try {
          renderApp("/");
          const aside = await screen.findByRole("complementary", { name: "Telemetry" });
          expect(await within(aside).findByText("The telemetry panel stopped working")).toBeInTheDocument();
          // The shell around it survives.
          expect(screen.getByRole("group", { name: "Overview" })).toBeInTheDocument();
          await user.click(within(aside).getByRole("button", { name: "Close panel" }));
          await waitFor(() => expect(screen.queryByRole("complementary", { name: "Telemetry" })).not.toBeInTheDocument());
          expect(localStorage.getItem(PANEL_KEY)).toBe("false");
        } finally {
          consoleError.mockRestore();
        }
      });

      test("an admin with the flag stored finds it open after a reload", async () => {
        localStorage.setItem("adder.auth.roles", JSON.stringify(["ADMIN"]));
        localStorage.setItem(PANEL_KEY, "true");
        stubTelemetry();
        renderApp("/");
        expect(await screen.findByRole("complementary", { name: "Telemetry" })).toBeInTheDocument();
      });
    });

    test("the account menu holds the Changelog link; the stamp links there and the trigger carries the dot", async () => {
      const user = userEvent.setup();
      renderApp("/");
      expect(await screen.findByTitle("Build version")).toHaveAttribute("href", "/changelog");
      // The what's-new dot rides the account-menu trigger while the version is unseen.
      expect(screen.getByTitle("What's new").querySelector("[data-unread-cue=dot]")).not.toBeNull();
      await user.click(screen.getByRole("button", { name: /account menu/i }));
      const changelog = await screen.findByRole("menuitem", { name: /Changelog/ });
      expect(changelog).toHaveAttribute("href", "/changelog");
      // The menu item's "New" marker is the same blue cue.
      expect(changelog.querySelector("[data-unread-cue=count]")).toHaveTextContent("New");
    });

    test("opening the changelog clears the what's-new dot immediately", async () => {
      const user = userEvent.setup();
      renderApp("/");
      await user.click(await screen.findByRole("button", { name: /account menu/i }));
      await user.click(await screen.findByRole("menuitem", { name: /Changelog/ }));
      // Explicit timeout — the Changelog chunk is lazy.
      expect(
        await screen.findByRole("heading", { level: 2, name: "Changelog" }, { timeout: 5000 }),
      ).toBeInTheDocument();
      await waitFor(() => expect(screen.queryByTitle("What's new")).not.toBeInTheDocument());
      expect(JSON.parse(localStorage.getItem("adder.changelog")!)).toEqual({
        seenVersion: APP_VERSION,
      });
    });

    test("shows no dot when the current version was already seen", async () => {
      localStorage.setItem("adder.changelog", JSON.stringify({ seenVersion: APP_VERSION }));
      renderApp("/");
      await screen.findByRole("heading", { level: 2, name: "Home" });
      expect(screen.queryByTitle("What's new")).not.toBeInTheDocument();
    });

    test("an unmatched URL renders the not-found page inside the shell", async () => {
      renderApp("/definitely/not-a-page");
      expect(
        await screen.findByRole("heading", { level: 2, name: "Page not found" }),
      ).toBeInTheDocument();
      // The Shell mounted around it — an unmatched URL must never render a blank document.
      expect(screen.getByText("Adder")).toBeInTheDocument();
      expect(screen.getByRole("link", { name: "Back to home" })).toHaveAttribute("href", "/");
    });

    test("logout clears the session and lands on the login page", async () => {
      (fetch as unknown as ReturnType<typeof vi.fn>).mockResolvedValue(
        new Response(null, { status: 204 }),
      );
      const user = userEvent.setup();
      renderApp("/");
      await user.click(await screen.findByRole("button", { name: /account menu/i }));
      await user.click(await screen.findByRole("menuitem", { name: "Sign out" }));
      await waitFor(() => expect(localStorage.getItem(TOKEN_KEY)).toBeNull());
      expect(await screen.findByRole("heading", { name: "Sign in" })).toBeInTheDocument();
      // The signed-out banner rides the flagSignedOut() handoff.
      expect(screen.getByText("You've been signed out.")).toBeInTheDocument();
    });
  });

  describe("when not authenticated", () => {
    test("a protected route redirects to the login page", async () => {
      renderApp("/");
      expect(await screen.findByRole("heading", { name: "Sign in" })).toBeInTheDocument();
    });

    test("the login page renders directly at /login", async () => {
      renderApp("/login");
      expect(await screen.findByRole("heading", { name: "Sign in" })).toBeInTheDocument();
      expect(screen.getByText("Adder")).toBeInTheDocument();
    });
  });
});
