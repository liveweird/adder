import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { Route, Routes, useLocation } from "react-router-dom";
import { renderWithProviders, screen } from "../test/render";
import CommandPalette from "./CommandPalette";
import { palette } from "../utils/commandPalette";
import { jsonResponse } from "../test/http";

function Probe() {
  const { pathname, search } = useLocation();
  return (
    <>
      <p>at {pathname}</p>
      <p>with {search}</p>
    </>
  );
}

function renderPalette(route?: string) {
  return renderWithProviders(
    <>
      <CommandPalette />
      <Routes>
        <Route path="*" element={<Probe />} />
      </Routes>
    </>,
    { route },
  );
}

describe("CommandPalette", () => {
  beforeEach(() => {
    localStorage.setItem("adder.auth.token", "fake-token");
  });

  afterEach(() => {
    palette.close();
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  test("the header trigger opens the palette listing the session's pages", async () => {
    vi.stubGlobal("fetch", vi.fn());
    const user = userEvent.setup();
    renderPalette();
    // Label in Name (WCAG 2.5.3): the wide trigger is named by its visible words, with no aria-label of its own.
    await user.click(screen.getByRole("button", { name: /^Search or jump to a page…/ }));
    expect(await screen.findByRole("button", { name: /Home/ })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /Change password/ })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /Notification preferences/ })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /^Teams$/ })).toBeInTheDocument();
    // A regular session never sees the admin pages.
    expect(screen.queryByRole("button", { name: /^Users$/ })).not.toBeInTheDocument();
  });

  test("the room organizer stays discoverable in the palette", async () => {
    vi.stubGlobal("fetch", vi.fn());
    const user = userEvent.setup();
    renderPalette();
    palette.open();
    await user.type(await screen.findByRole("textbox"), "organize");
    await user.click(screen.getByRole("button", { name: "Organize rooms" }));
    expect(await screen.findByText("at /rooms/organize")).toBeInTheDocument();
  });

  test("an admin session's palette includes the Administration pages, and a pick navigates", async () => {
    localStorage.setItem("adder.auth.roles", JSON.stringify(["ADMIN"]));
    vi.stubGlobal("fetch", vi.fn());
    const user = userEvent.setup();
    renderPalette();
    palette.open();
    await user.click(await screen.findByRole("button", { name: /Users/ }));
    expect(await screen.findByText("at /users")).toBeInTheDocument();
  });

  test("an admin's palette has the telemetry action, which toggles the panel flag and says what it will do", async () => {
    localStorage.setItem("adder.auth.roles", JSON.stringify(["ADMIN"]));
    vi.stubGlobal("fetch", vi.fn());
    const user = userEvent.setup();
    renderPalette();
    palette.open();
    await user.click(await screen.findByRole("button", { name: /Show telemetry panel/ }));
    expect(localStorage.getItem("adder.viewSettings.telemetry.panelOpen")).toBe("true");
    palette.open();
    await user.click(await screen.findByRole("button", { name: /Hide telemetry panel/ }));
    expect(localStorage.getItem("adder.viewSettings.telemetry.panelOpen")).toBe("false");
  });

  test("the telemetry action is found by its keywords, not only its label", async () => {
    localStorage.setItem("adder.auth.roles", JSON.stringify(["ADMIN"]));
    vi.stubGlobal("fetch", vi.fn());
    const user = userEvent.setup();
    renderPalette();
    palette.open();
    await user.type(await screen.findByPlaceholderText("Search or jump to a page…"), "memory");
    expect(await screen.findByRole("button", { name: /Show telemetry panel/ })).toBeInTheDocument();
  });

  test("every session's palette has Threads I watch, which opens the search across rooms with the switch on", async () => {
    vi.stubGlobal("fetch", vi.fn(() => Promise.resolve(jsonResponse(200, { items: [], page: 1, pageSize: 100, total: 0 }))));
    const user = userEvent.setup();
    renderPalette();
    palette.open();
    await user.type(await screen.findByPlaceholderText("Search or jump to a page…"), "watch");
    await user.click(await screen.findByRole("button", { name: "Threads I watch" }));
    expect(await screen.findByText("at /search")).toBeInTheDocument();
    expect(screen.getByText("with ?watching=1")).toBeInTheDocument();
  });

  test("a regular session's palette has no telemetry action", async () => {
    vi.stubGlobal("fetch", vi.fn());
    renderPalette();
    palette.open();
    await screen.findByRole("button", { name: /Home/ });
    expect(screen.queryByRole("button", { name: /telemetry/i })).not.toBeInTheDocument();
  });

  test("typing narrows the list to pages matching the folded query", async () => {
    vi.stubGlobal("fetch", vi.fn());
    const user = userEvent.setup();
    renderPalette();
    palette.open();
    await user.type(await screen.findByRole("textbox"), "team");
    expect(await screen.findByRole("button", { name: /Teams/ })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /Change password/ })).not.toBeInTheDocument();
  });

  describe("the Rooms group", () => {
    const ROOMS = {
      items: [
        { id: 12, name: "Night Shift", description: null, archived: false, role: "OWNER", memberCount: 3, lastActivityAt: 5, createdAt: 1 },
        { id: 13, name: "Billing", description: null, archived: false, role: "MEMBER", memberCount: 9, lastActivityAt: 4, createdAt: 1 },
      ],
      page: 1,
      pageSize: 100,
      total: 2,
    };

    test("lists the caller's rooms beside the pages; a pick opens the room, and typing finds one by name", async () => {
      vi.stubGlobal("fetch", vi.fn(() => Promise.resolve(jsonResponse(200, ROOMS))));
      const user = userEvent.setup();
      renderPalette();
      palette.open();
      expect(await screen.findByRole("button", { name: /Night Shift/ })).toBeInTheDocument();
      expect(screen.getByRole("button", { name: /^Home/ })).toBeInTheDocument();
      await user.type(screen.getByRole("textbox"), "bill");
      expect(screen.queryByRole("button", { name: /Night Shift/ })).not.toBeInTheDocument();
      await user.click(await screen.findByRole("button", { name: /Billing/ }));
      expect(await screen.findByText("at /rooms/13")).toBeInTheDocument();
    });

    test("without rooms there is no Rooms group", async () => {
      vi.stubGlobal("fetch", vi.fn(() => Promise.resolve(jsonResponse(200, { items: [], page: 1, pageSize: 100, total: 0 }))));
      renderPalette();
      palette.open();
      await screen.findByRole("button", { name: /^Home/ });
      expect(screen.queryByText("Rooms")).not.toBeInTheDocument();
    });
  });

  describe("the Search group", () => {
    const ROOMS = {
      items: [{ id: 12, name: "Night Shift", description: null, archived: false, role: "OWNER", memberCount: 3, lastActivityAt: 5, createdAt: 1 }],
      page: 1,
      pageSize: 100,
      total: 1,
    };
    const ROOM = { ...ROOMS.items[0], archivedAt: null, workflowRevision: 1, updatedAt: 1 };

    function serveRooms() {
      vi.stubGlobal(
        "fetch",
        vi.fn((input: RequestInfo | URL) => Promise.resolve(String(input) === "/api/v1/rooms/12" ? jsonResponse(200, ROOM) : jsonResponse(200, ROOMS))),
      );
    }

    test("from two searchable characters, an action searches all rooms for the term, and a pick opens the page with the term", async () => {
      vi.stubGlobal("fetch", vi.fn(() => Promise.resolve(jsonResponse(200, { items: [], page: 1, pageSize: 100, total: 0 }))));
      const user = userEvent.setup();
      renderPalette();
      palette.open();
      const box = await screen.findByRole("textbox");
      await user.type(box, "o");
      expect(screen.queryByRole("button", { name: /Search all rooms/ })).not.toBeInTheDocument();
      await user.type(box, "ut");
      await user.click(await screen.findByRole("button", { name: "Search all rooms for “out”" }));
      expect(await screen.findByText("at /search")).toBeInTheDocument();
      expect(screen.getByText("with ?q=out")).toBeInTheDocument();
    });

    test("a term with no letter or digit offers no search", async () => {
      vi.stubGlobal("fetch", vi.fn(() => Promise.resolve(jsonResponse(200, { items: [], page: 1, pageSize: 100, total: 0 }))));
      const user = userEvent.setup();
      renderPalette();
      palette.open();
      await user.type(await screen.findByRole("textbox"), "!!");
      expect(await screen.findByText("Nothing found")).toBeInTheDocument();
      expect(screen.queryByRole("button", { name: /Search all rooms/ })).not.toBeInTheDocument();
    });

    test("inside a room there is also an action that searches that room, and it reaches the room's search page", async () => {
      serveRooms();
      const user = userEvent.setup();
      renderPalette("/rooms/12/threads/3");
      palette.open();
      await user.type(await screen.findByRole("textbox"), "disk full");
      expect(await screen.findByRole("button", { name: "Search all rooms for “disk full”" })).toBeInTheDocument();
      await user.click(await screen.findByRole("button", { name: "Search Night Shift for “disk full”" }));
      expect(await screen.findByText("at /rooms/12/search")).toBeInTheDocument();
      expect(screen.getByText("with ?q=disk+full")).toBeInTheDocument();
    });

    test("when the room cannot be read (a 404) there is no room action, only the search across rooms", async () => {
      vi.stubGlobal(
        "fetch",
        vi.fn((input: RequestInfo | URL) => Promise.resolve(String(input) === "/api/v1/rooms/12" ? jsonResponse(404, { title: "Not found", status: 404 }) : jsonResponse(200, ROOMS))),
      );
      const user = userEvent.setup();
      renderPalette("/rooms/12/search");
      palette.open();
      await user.type(await screen.findByRole("textbox"), "disk");
      await screen.findByRole("button", { name: "Search all rooms for “disk”" });
      expect(screen.queryByRole("button", { name: /^Search Night Shift/ })).not.toBeInTheDocument();
    });

    test("outside a room there is no room action", async () => {
      serveRooms();
      const user = userEvent.setup();
      renderPalette("/teams");
      palette.open();
      await user.type(await screen.findByRole("textbox"), "disk");
      await screen.findByRole("button", { name: "Search all rooms for “disk”" });
      expect(screen.queryByRole("button", { name: /^Search Night Shift/ })).not.toBeInTheDocument();
    });
  });
});
