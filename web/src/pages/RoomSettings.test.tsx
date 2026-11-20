import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { screen, waitFor, within } from "@testing-library/react";
import { QueryClient } from "@tanstack/react-query";
import { Route, Routes } from "react-router-dom";
import RoomSettings from "./RoomSettings";
import { headingOutline } from "../test/headings";
import { jsonResponse } from "../test/http";
import { renderWithProviders } from "../test/render";

type FetchMock = ReturnType<typeof vi.fn>;

const ROOM = {
  id: 12,
  name: "Night Shift",
  description: "Handover notes",
  archived: false,
  archivedAt: null,
  role: "OWNER",
  memberCount: 3,
  workflowRevision: 1,
  lastActivityAt: 1,
  createdAt: 1,
  updatedAt: 1,
};

function renderSettings(queryClient?: QueryClient) {
  return renderWithProviders(
    <Routes>
      <Route path="/rooms/:id/settings" element={<RoomSettings />} />
      <Route path="/rooms" element={<p>the rooms list</p>} />
    </Routes>,
    { route: "/rooms/12/settings", queryClient },
  );
}

/** A room that follows the writes: a PUT renames it, archive/unarchive flip it; `fail` answers a write instead. */
function serve(mockFetch: FetchMock, initial: Record<string, unknown> = ROOM, fail: Record<string, Response> = {}): (changes: Record<string, unknown>) => void {
  let room = initial;
  mockFetch.mockImplementation((url: string, init?: RequestInit) => {
    const method = init?.method ?? "GET";
    const key = `${method} ${url}`;
    if (fail[key]) return Promise.resolve(fail[key]);
    if (key === "GET /api/v1/rooms/12") return Promise.resolve(jsonResponse(200, room));
    if (key === "PUT /api/v1/rooms/12") {
      const body = JSON.parse(init!.body as string) as { name: string; description: string | null };
      room = { ...room, ...body };
      return Promise.resolve(new Response(null, { status: 204 }));
    }
    if (key === "POST /api/v1/rooms/12/archive" || key === "POST /api/v1/rooms/12/unarchive") {
      room = { ...room, archived: key.endsWith("/archive") };
      return Promise.resolve(new Response(null, { status: 204 }));
    }
    if (key === "DELETE /api/v1/rooms/12") return Promise.resolve(new Response(null, { status: 204 }));
    return Promise.resolve(jsonResponse(404, { title: "Not found", status: 404 }));
  });
  // Someone else's write: the room as the next read answers it.
  return (changes) => {
    room = { ...room, ...changes };
  };
}

// The room's own "seen" POST (opening a room is looking at it) is view state, not one of the writes these tests count.
const calls = (mockFetch: FetchMock, method: string) =>
  mockFetch.mock.calls.filter(([url, init]) => ((init as RequestInit | undefined)?.method ?? "GET") === method && !String(url).endsWith("/seen"));

describe("RoomSettings page", () => {
  let mockFetch: FetchMock;

  beforeEach(() => {
    mockFetch = vi.fn();
    vi.stubGlobal("fetch", mockFetch);
    localStorage.setItem("adder.auth.token", "fake-token");
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  test("an owner edits the details: Save waits for a change, sends the trimmed body and the form follows the saved room", async () => {
    serve(mockFetch);
    const user = userEvent.setup();
    renderSettings();
    const name = await screen.findByRole("textbox", { name: "Name" });
    expect(name).toHaveValue("Night Shift");
    expect(screen.getByRole("textbox", { name: "Description" })).toHaveValue("Handover notes");
    expect(screen.getByRole("link", { name: "Settings" })).toHaveAttribute("aria-current", "page");
    expect(screen.getByRole("button", { name: "Save" })).toBeDisabled();
    await user.clear(name);
    await user.type(name, " Day Shift ");
    await user.clear(screen.getByRole("textbox", { name: "Description" }));
    await user.click(screen.getByRole("button", { name: "Save" }));
    await waitFor(() => expect(calls(mockFetch, "PUT")).toHaveLength(1));
    expect(JSON.parse((calls(mockFetch, "PUT")[0][1] as RequestInit).body as string)).toEqual({ name: "Day Shift", description: null });
    // The refresh re-read the room: the header carries the new name and Save is clean again.
    expect(await screen.findByRole("heading", { level: 2, name: "Day Shift" })).toBeInTheDocument();
    await waitFor(() => expect(screen.getByRole("button", { name: "Save" })).toBeDisabled());
    expect(headingOutline()).toEqual([
      [2, "Day Shift"],
      [3, "Details"],
      [3, "Archive and delete"],
    ]);
  });

  test("Cancel puts the typed values back", async () => {
    serve(mockFetch);
    const user = userEvent.setup();
    renderSettings();
    const name = await screen.findByRole("textbox", { name: "Name" });
    await user.type(name, " extra");
    expect(screen.getByRole("button", { name: "Save" })).toBeEnabled();
    await user.click(screen.getByRole("button", { name: "Cancel" }));
    expect(name).toHaveValue("Night Shift");
  });

  test("a blank name is refused before any request", async () => {
    serve(mockFetch);
    const user = userEvent.setup();
    renderSettings();
    const name = await screen.findByRole("textbox", { name: "Name" });
    await user.clear(name);
    await user.click(screen.getByRole("button", { name: "Save" }));
    expect(await screen.findByText("Name must be 1–80 characters")).toBeInTheDocument();
    expect(calls(mockFetch, "PUT")).toEqual([]);
  });

  test("a name clash is marked on the name field", async () => {
    serve(mockFetch, ROOM, { "PUT /api/v1/rooms/12": jsonResponse(409, { title: "Conflict", status: 409 }) });
    const user = userEvent.setup();
    renderSettings();
    await user.type(await screen.findByRole("textbox", { name: "Name" }), "!");
    await user.click(screen.getByRole("button", { name: "Save" }));
    expect(await screen.findByText("A room with this name already exists")).toBeInTheDocument();
  });

  test("a room archived behind the page (409 room-archived) says so and turns the form read-only", async () => {
    // The refusal is what reveals the archiving: the room the page re-reads afterwards is archived.
    let archived = false;
    mockFetch.mockImplementation((url: string, init?: RequestInit) => {
      if (init?.method === "PUT") {
        archived = true;
        return Promise.resolve(jsonResponse(409, { type: "urn:adder:problem:room-archived", title: "Archived", status: 409 }));
      }
      return Promise.resolve(url === "/api/v1/rooms/12" ? jsonResponse(200, { ...ROOM, archived }) : jsonResponse(404, { title: "x", status: 404 }));
    });
    const user = userEvent.setup();
    renderSettings();
    await user.type(await screen.findByRole("textbox", { name: "Name" }), "!");
    await user.click(screen.getByRole("button", { name: "Save" }));
    // The form re-mounts read-only, and the reason for the refusal is still on screen.
    expect(await screen.findByText(/archived, so its details are read-only/)).toBeInTheDocument();
    expect(screen.getByText("This room was archived in the meantime, so it can no longer be edited.")).toBeInTheDocument();
    expect(screen.getByRole("textbox", { name: "Name" })).toHaveAttribute("readonly");
  });

  test("an id that names no room is the not-found state, without a request", () => {
    renderWithProviders(
      <Routes>
        <Route path="/rooms/:id/settings" element={<RoomSettings />} />
      </Routes>,
      { route: "/rooms/abc/settings" },
    );
    expect(screen.getByText("This room does not exist, or you are not a member of it.")).toBeInTheDocument();
    expect(mockFetch).not.toHaveBeenCalled();
  });

  test("any other save failure is named inline", async () => {
    serve(mockFetch, ROOM, { "PUT /api/v1/rooms/12": jsonResponse(403, { title: "Forbidden", status: 403 }) });
    const user = userEvent.setup();
    renderSettings();
    await user.type(await screen.findByRole("textbox", { name: "Name" }), "!");
    await user.click(screen.getByRole("button", { name: "Save" }));
    expect(await screen.findByText("Only the room's owners and administrators can change its details")).toBeInTheDocument();
  });

  test("a plain member reads the details but has no Save and no lifecycle controls", async () => {
    serve(mockFetch, { ...ROOM, role: "MEMBER" });
    renderSettings();
    const name = await screen.findByRole("textbox", { name: "Name" });
    expect(name).toHaveAttribute("readonly");
    expect(screen.getByText("Only the room's owners and administrators can change these settings.")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Save" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Archive room" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Delete room" })).not.toBeInTheDocument();
  });

  test("an owner archives the room through a confirm; the page turns read-only and offers Unarchive", async () => {
    serve(mockFetch);
    const user = userEvent.setup();
    renderSettings();
    await user.click(await screen.findByRole("button", { name: "Archive room" }));
    const dialog = await screen.findByRole("dialog", { name: "Archive this room?" });
    expect(within(dialog).getByText("“Night Shift” becomes read-only until someone unarchives it.")).toBeInTheDocument();
    await user.click(within(dialog).getByRole("button", { name: "Archive room" }));
    expect(await screen.findByRole("button", { name: "Unarchive room" })).toBeInTheDocument();
    expect(calls(mockFetch, "POST").map(([url]) => url)).toEqual(["/api/v1/rooms/12/archive"]);
    expect(screen.getByRole("textbox", { name: "Name" })).toHaveAttribute("readonly");
    expect(screen.queryByRole("button", { name: "Save" })).not.toBeInTheDocument();
    // An owner has no Delete: that is the administrator's.
    expect(screen.queryByRole("button", { name: "Delete room" })).not.toBeInTheDocument();
  });

  test("Unarchive is the same flow the other way round, and Cancel in the confirm changes nothing", async () => {
    serve(mockFetch, { ...ROOM, archived: true });
    const user = userEvent.setup();
    renderSettings();
    expect(await screen.findByText("Archived")).toBeInTheDocument();
    await user.click(await screen.findByRole("button", { name: "Unarchive room" }));
    const dialog = await screen.findByRole("dialog", { name: "Unarchive this room?" });
    await user.click(within(dialog).getByRole("button", { name: "Cancel" }));
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    expect(calls(mockFetch, "POST")).toEqual([]);
    await user.click(screen.getByRole("button", { name: "Unarchive room" }));
    await user.click(within(await screen.findByRole("dialog")).getByRole("button", { name: "Unarchive room" }));
    expect(await screen.findByRole("button", { name: "Archive room" })).toBeInTheDocument();
    expect(screen.getByRole("textbox", { name: "Name" })).not.toHaveAttribute("readonly");
  });

  test("a failed archive closes the confirm and shows the failure inline", async () => {
    serve(mockFetch, ROOM, { "POST /api/v1/rooms/12/archive": jsonResponse(403, { title: "Forbidden", status: 403 }) });
    const user = userEvent.setup();
    renderSettings();
    await user.click(await screen.findByRole("button", { name: "Archive room" }));
    await user.click(within(await screen.findByRole("dialog")).getByRole("button", { name: "Archive room" }));
    expect(await screen.findByText("Only the room's owners and administrators can change its details")).toBeInTheDocument();
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
  });

  test("an administrator who is not a member manages the room and deletes it through the confirm, landing on the list", async () => {
    localStorage.setItem("adder.auth.roles", JSON.stringify(["ADMIN"]));
    serve(mockFetch, { ...ROOM, role: null });
    const user = userEvent.setup();
    renderSettings();
    await user.click(await screen.findByRole("button", { name: "Delete room" }));
    const dialog = await screen.findByRole("dialog", { name: "Delete room?" });
    expect(within(dialog).getByText("“Night Shift” will be deleted for everyone.")).toBeInTheDocument();
    await user.click(within(dialog).getByRole("button", { name: "Delete" }));
    expect(await screen.findByText("the rooms list")).toBeInTheDocument();
    expect(calls(mockFetch, "DELETE").map(([url]) => url)).toEqual(["/api/v1/rooms/12"]);
  });

  test("a failed delete is shown in the confirm and the room stays", async () => {
    localStorage.setItem("adder.auth.roles", JSON.stringify(["ADMIN"]));
    serve(mockFetch, ROOM, { "DELETE /api/v1/rooms/12": jsonResponse(500, { title: "Boom", status: 500 }) });
    const user = userEvent.setup();
    renderSettings();
    await user.click(await screen.findByRole("button", { name: "Delete room" }));
    await user.click(within(await screen.findByRole("dialog")).getByRole("button", { name: "Delete" }));
    expect(await screen.findByText("Could not delete the room")).toBeInTheDocument();
    expect(screen.getByRole("heading", { level: 2, name: "Night Shift" })).toBeInTheDocument();
  });

  describe("when the room changes under the page (a live update re-reads it)", () => {
    const newClient = () => new QueryClient({ defaultOptions: { queries: { retry: false } } });

    test("a clean form follows the room, without a notice", async () => {
      const change = serve(mockFetch);
      const queryClient = newClient();
      renderSettings(queryClient);
      const name = await screen.findByRole("textbox", { name: "Name" });
      change({ name: "Late Shift", description: "New notes" });
      await queryClient.invalidateQueries({ queryKey: ["rooms"] });
      await waitFor(() => expect(screen.getByRole("textbox", { name: "Name" })).toHaveValue("Late Shift"));
      expect(screen.getByRole("textbox", { name: "Description" })).toHaveValue("New notes");
      expect(screen.getByRole("textbox", { name: "Name" })).toBe(name);
      expect(screen.queryByText("This room changed while you were editing")).not.toBeInTheDocument();
      expect(screen.getByRole("button", { name: "Save" })).toBeDisabled();
    });

    test("a dirty form keeps its draft and offers Reload, which starts again from the current room", async () => {
      const change = serve(mockFetch);
      const user = userEvent.setup();
      const queryClient = newClient();
      renderSettings(queryClient);
      const name = await screen.findByRole("textbox", { name: "Name" });
      await user.type(name, " (mine)");
      change({ name: "Late Shift" });
      await queryClient.invalidateQueries({ queryKey: ["rooms"] });
      expect(await screen.findByText("This room changed while you were editing")).toBeInTheDocument();
      expect(screen.getByText("Saving keeps your version. Reload to start again from the current one.")).toBeInTheDocument();
      expect(screen.getByRole("textbox", { name: "Name" })).toHaveValue("Night Shift (mine)");
      expect(screen.getByRole("button", { name: "Save" })).toBeEnabled();
      await user.click(screen.getByRole("button", { name: "Reload the latest version" }));
      expect(screen.getByRole("textbox", { name: "Name" })).toHaveValue("Late Shift");
      expect(screen.queryByText("This room changed while you were editing")).not.toBeInTheDocument();
      expect(screen.getByRole("button", { name: "Save" })).toBeDisabled();
      expect(screen.getByRole("textbox", { name: "Name" })).toHaveFocus();
    });

    test("Cancel after the room moved puts back the room's current values", async () => {
      const change = serve(mockFetch);
      const user = userEvent.setup();
      const queryClient = newClient();
      renderSettings(queryClient);
      await user.type(await screen.findByRole("textbox", { name: "Name" }), " (mine)");
      change({ name: "Late Shift" });
      await queryClient.invalidateQueries({ queryKey: ["rooms"] });
      await screen.findByText("This room changed while you were editing");
      await user.click(screen.getByRole("button", { name: "Cancel" }));
      await waitFor(() => expect(screen.getByRole("textbox", { name: "Name" })).toHaveValue("Late Shift"));
      expect(screen.queryByText("This room changed while you were editing")).not.toBeInTheDocument();
    });

    test("a draft that already equals the room's new values (the caller's own save) shows no notice", async () => {
      const change = serve(mockFetch);
      const user = userEvent.setup();
      const queryClient = newClient();
      renderSettings(queryClient);
      const name = await screen.findByRole("textbox", { name: "Name" });
      await user.type(name, " 2");
      // Another tab of the same caller saved the same text: the read answers what the draft already says.
      change({ name: "Night Shift 2" });
      await queryClient.invalidateQueries({ queryKey: ["rooms"] });
      await waitFor(() => expect(screen.getByRole("button", { name: "Save" })).toBeDisabled());
      expect(screen.getByRole("textbox", { name: "Name" })).toHaveValue("Night Shift 2");
      expect(screen.queryByText("This room changed while you were editing")).not.toBeInTheDocument();
    });

    test("a room archived behind a dirty draft: the form follows the server, read-only, with no notice", async () => {
      const change = serve(mockFetch);
      const user = userEvent.setup();
      const queryClient = newClient();
      renderSettings(queryClient);
      await user.type(await screen.findByRole("textbox", { name: "Name" }), " (mine)");
      change({ archived: true, name: "Late Shift" });
      await queryClient.invalidateQueries({ queryKey: ["rooms"] });
      await waitFor(() => expect(screen.getByRole("textbox", { name: "Name" })).toHaveAttribute("readonly"));
      expect(screen.getByRole("textbox", { name: "Name" })).toHaveValue("Late Shift");
      expect(screen.queryByText("This room changed while you were editing")).not.toBeInTheDocument();
      expect(screen.queryByRole("button", { name: "Save" })).not.toBeInTheDocument();
    });

    test("Reload, then type, then Cancel: the fields go back to the reloaded values", async () => {
      const change = serve(mockFetch);
      const user = userEvent.setup();
      const queryClient = newClient();
      renderSettings(queryClient);
      await user.type(await screen.findByRole("textbox", { name: "Name" }), " (mine)");
      change({ name: "Late Shift" });
      await queryClient.invalidateQueries({ queryKey: ["rooms"] });
      await user.click(await screen.findByRole("button", { name: "Reload the latest version" }));
      await user.type(screen.getByRole("textbox", { name: "Name" }), " again");
      expect(screen.getByRole("textbox", { name: "Name" })).toHaveValue("Late Shift again");
      await user.click(screen.getByRole("button", { name: "Cancel" }));
      expect(screen.getByRole("textbox", { name: "Name" })).toHaveValue("Late Shift");
      expect(screen.queryByText("This room changed while you were editing")).not.toBeInTheDocument();
    });

    test("while a save is in flight the fields are read-only and no notice shows, before or after it ends", async () => {
      let release!: () => void;
      let room: Record<string, unknown> = ROOM;
      mockFetch.mockImplementation((url: string, init?: RequestInit) => {
        if (init?.method === "PUT") {
          const body = JSON.parse(init.body as string) as { name: string };
          return new Promise<Response>((resolve) => {
            release = () => {
              room = { ...room, name: body.name };
              resolve(new Response(null, { status: 204 }));
            };
          });
        }
        return Promise.resolve(url === "/api/v1/rooms/12" ? jsonResponse(200, room) : jsonResponse(404, { title: "x", status: 404 }));
      });
      const user = userEvent.setup();
      renderSettings();
      await user.type(await screen.findByRole("textbox", { name: "Name" }), "!");
      await user.click(screen.getByRole("button", { name: "Save" }));
      await waitFor(() => expect(screen.getByRole("textbox", { name: "Name" })).toHaveAttribute("readonly"));
      expect(screen.getByRole("textbox", { name: "Description" })).toHaveAttribute("readonly");
      await user.type(screen.getByRole("textbox", { name: "Name" }), "??");
      expect(screen.getByRole("textbox", { name: "Name" })).toHaveValue("Night Shift!");
      release();
      await waitFor(() => expect(screen.getByRole("heading", { level: 2, name: "Night Shift!" })).toBeInTheDocument());
      await waitFor(() => expect(screen.getByRole("textbox", { name: "Name" })).not.toHaveAttribute("readonly"));
      expect(screen.getByRole("textbox", { name: "Name" })).toHaveValue("Night Shift!");
      expect(screen.queryByText("This room changed while you were editing")).not.toBeInTheDocument();
    });

    test("Reload and Cancel clear the page's save error", async () => {
      const change = serve(mockFetch, ROOM, { "PUT /api/v1/rooms/12": jsonResponse(403, { title: "Forbidden", status: 403 }) });
      const user = userEvent.setup();
      const queryClient = newClient();
      renderSettings(queryClient);
      await user.type(await screen.findByRole("textbox", { name: "Name" }), "!");
      await user.click(screen.getByRole("button", { name: "Save" }));
      const refusal = "Only the room's owners and administrators can change its details";
      expect(await screen.findByText(refusal)).toBeInTheDocument();
      await user.click(screen.getByRole("button", { name: "Cancel" }));
      expect(screen.queryByText(refusal)).not.toBeInTheDocument();
      await user.type(screen.getByRole("textbox", { name: "Name" }), "?");
      await user.click(screen.getByRole("button", { name: "Save" }));
      expect(await screen.findByText(refusal)).toBeInTheDocument();
      change({ name: "Late Shift" });
      await queryClient.invalidateQueries({ queryKey: ["rooms"] });
      await user.click(await screen.findByRole("button", { name: "Reload the latest version" }));
      expect(screen.queryByText(refusal)).not.toBeInTheDocument();
    });

    test("one's own save shows no notice at any point", async () => {
      serve(mockFetch);
      const user = userEvent.setup();
      renderSettings();
      await user.type(await screen.findByRole("textbox", { name: "Name" }), "!");
      await user.click(screen.getByRole("button", { name: "Save" }));
      await waitFor(() => expect(screen.getByRole("heading", { level: 2, name: "Night Shift!" })).toBeInTheDocument());
      await waitFor(() => expect(screen.getByRole("button", { name: "Save" })).toBeDisabled());
      expect(screen.getByRole("textbox", { name: "Name" })).toHaveValue("Night Shift!");
      expect(screen.queryByText("This room changed while you were editing")).not.toBeInTheDocument();
    });
  });
});
