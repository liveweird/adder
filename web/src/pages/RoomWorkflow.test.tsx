import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { fireEvent, screen, waitFor, within } from "@testing-library/react";
import { QueryClient, focusManager, useQuery } from "@tanstack/react-query";
import { Route, Routes, useLocation } from "react-router-dom";
import RoomWorkflow from "./RoomWorkflow";
import { getRoomWorkflow } from "../api/rooms";
import { showSuccessToast } from "../utils/toast";
import { headingOutline } from "../test/headings";
import { jsonResponse } from "../test/http";
import { renderWithProviders } from "../test/render";

vi.mock("../utils/toast", () => ({ showSuccessToast: vi.fn() }));

type FetchMock = ReturnType<typeof vi.fn>;

const ROOM = {
  id: 12,
  name: "Night Shift",
  description: null,
  archived: false,
  archivedAt: null,
  role: "OWNER",
  memberCount: 3,
  workflowRevision: 3,
  lastActivityAt: 1,
  createdAt: 1,
  updatedAt: 1,
};
const WORKFLOW = {
  revision: 3,
  statuses: [
    { key: "triage", label: "Triage", colour: "RED", category: "OPEN", initial: true, threadCount: 3 },
    { key: "working", label: "Working", colour: "BLUE", category: "OPEN", initial: false, threadCount: 2 },
    { key: "done", label: "Done", colour: "TEAL", category: "CLOSED", initial: false, threadCount: 0 },
    { key: "dropped", label: "Dropped", colour: "GRAY", category: "CLOSED", initial: false, threadCount: 0 },
  ],
  fields: [{ key: "sev", label: "Severity", type: "SELECT", required: true, options: [{ key: "s1", label: "S1" }] }],
};

type Workflow = typeof WORKFLOW;

/** What the fake server currently holds and how it answers; a test changes it to play "someone else saved" or "the server refused". */
type Server = {
  room: Record<string, unknown> | null;
  workflow: Workflow;
  /** The workflow GET answers this instead (a failed read). */
  workflowRead?: () => Response;
  /** The PUT answers this instead of applying the body. */
  put?: () => Response;
};

const problem = (status: number, type?: string, keys?: string[]) =>
  jsonResponse(status, { title: "x", status, ...(type ? { type } : {}), ...(keys ? { keys } : {}) });

function serve(mockFetch: FetchMock, initial: Partial<Server> = {}): Server {
  const server: Server = { room: ROOM, workflow: WORKFLOW, ...initial };
  mockFetch.mockImplementation((url: string, init?: RequestInit) => {
    const method = init?.method ?? "GET";
    if (method === "GET" && url === "/api/v1/rooms/12") return Promise.resolve(server.room ? jsonResponse(200, server.room) : problem(404));
    if (method === "GET" && url === "/api/v1/rooms/12/workflow") return Promise.resolve(server.workflowRead ? server.workflowRead() : jsonResponse(200, server.workflow));
    if (method === "PUT" && url === "/api/v1/rooms/12/workflow") {
      if (server.put) return Promise.resolve(server.put());
      const body = JSON.parse(init!.body as string) as Pick<Workflow, "statuses" | "fields">;
      server.workflow = { revision: server.workflow.revision + 1, statuses: body.statuses.map((s) => ({ ...s, threadCount: 0 })), fields: body.fields } as Workflow;
      return Promise.resolve(new Response(null, { status: 204 }));
    }
    return Promise.resolve(problem(404));
  });
  return server;
}

function Probe() {
  return <p>at {useLocation().pathname}</p>;
}

/** Stands in for the Threads view's use of the workflow: the same query key, so a save must refresh it. */
function Summary() {
  const { data } = useQuery({ queryKey: ["rooms", "workflow", 12], queryFn: () => getRoomWorkflow(12) });
  return <p data-testid="summary">{data?.statuses.map((status) => status.label).join(", ")}</p>;
}

function renderWorkflow(queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })) {
  return renderWithProviders(
    <Routes>
      <Route
        path="/rooms/:id/workflow"
        element={
          <>
            <RoomWorkflow />
            <Summary />
          </>
        }
      />
      <Route path="/rooms/:id" element={<Probe />} />
    </Routes>,
    { route: "/rooms/12/workflow", queryClient },
  );
}

function signIn(userId: number, roles: string[] = []) {
  localStorage.setItem("adder.auth.token", "fake-token");
  localStorage.setItem("adder.auth.roles", JSON.stringify(roles));
  localStorage.setItem("adder.auth.userId", String(userId));
}

const statusRow = (n: number) => screen.getByRole("group", { name: `Status ${n}` });
const label = (n: number) => within(statusRow(n)).getByRole("textbox", { name: "Label" });
const calls = (mockFetch: FetchMock, method: string, url: string) =>
  mockFetch.mock.calls.filter(([u, init]) => u === url && ((init as RequestInit | undefined)?.method ?? "GET") === method);
const putCalls = (mockFetch: FetchMock) => calls(mockFetch, "PUT", "/api/v1/rooms/12/workflow");
const putBody = (mockFetch: FetchMock, index = -1) => JSON.parse((putCalls(mockFetch).at(index)![1] as RequestInit).body as string);
const putRevision = (mockFetch: FetchMock) => new Headers((putCalls(mockFetch).at(-1)![1] as RequestInit).headers).get("X-Expected-Revision");
const save = (user: ReturnType<typeof userEvent.setup>) => user.click(screen.getByRole("button", { name: "Save" }));
const editLabel = async (user: ReturnType<typeof userEvent.setup>, n: number, extra: string) => user.type(await waitFor(() => label(n)), extra);

let mockFetch: FetchMock;

beforeEach(() => {
  mockFetch = vi.fn();
  vi.stubGlobal("fetch", mockFetch);
  vi.mocked(showSuccessToast).mockClear();
  signIn(7);
});

afterEach(() => {
  vi.unstubAllGlobals();
  localStorage.clear();
});

describe("reading and editing", () => {
  test("an owner gets the editor over the room's single labels, Save disabled until a change, under the room's header and tabs", async () => {
    serve(mockFetch);
    renderWorkflow();
    expect(await within(await screen.findByRole("group", { name: "Status 1" })).findByRole("textbox", { name: "Label" })).toHaveValue("Triage");
    expect(label(2)).toHaveValue("Working");
    // One label column: no per-language labels, and a saved row's key is read-only.
    expect(within(statusRow(1)).queryByRole("textbox", { name: "Label (PL)" })).not.toBeInTheDocument();
    expect(within(statusRow(1)).getByRole("textbox", { name: "Key" })).toHaveAttribute("readonly");
    expect(screen.getByRole("link", { name: "Workflow" })).toHaveAttribute("aria-current", "page");
    expect(screen.getByRole("button", { name: "Save" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "Cancel" })).toBeEnabled();
    expect(screen.queryByRole("note")).not.toBeInTheDocument();
    expect(headingOutline()).toEqual([
      [2, "Night Shift"],
      [3, "Workflow"],
      [4, "Statuses"],
      [4, "Fields"],
    ]);
  });

  test("saves the whole document under the revision it was read at, re-seeds from the new revision and refreshes the room", async () => {
    const user = userEvent.setup();
    serve(mockFetch);
    renderWorkflow();
    await editLabel(user, 3, " (closed)");
    expect(await screen.findByTestId("summary")).toHaveTextContent("Triage, Working, Done, Dropped");
    await save(user);
    await waitFor(() => expect(label(3)).toHaveValue("Done (closed)"));
    // The Threads view's workflow read (the same query key) re-reads the saved workflow.
    await waitFor(() => expect(screen.getByTestId("summary")).toHaveTextContent("Triage, Working, Done (closed), Dropped"));
    expect(showSuccessToast).toHaveBeenCalledWith("Workflow saved");
    expect(putRevision(mockFetch)).toBe("3");
    expect(putBody(mockFetch)).toMatchObject({
      statuses: [
        { key: "triage", label: "Triage", colour: "RED", category: "OPEN", initial: true },
        { key: "working", label: "Working" },
        { key: "done", label: "Done (closed)", category: "CLOSED" },
        { key: "dropped", label: "Dropped" },
      ],
      fields: [{ key: "sev", label: "Severity", type: "SELECT", required: true, options: [{ key: "s1", label: "S1" }] }],
    });
    expect(putBody(mockFetch)).not.toHaveProperty("replacements");
    // Re-seeded: clean again (Save disabled) and the next save names revision 4.
    await waitFor(() => expect(screen.getByRole("button", { name: "Save" })).toBeDisabled());
    expect(screen.getByRole("button", { name: "Save" })).not.toHaveAttribute("data-loading");
    await editLabel(user, 1, "!");
    await save(user);
    await waitFor(() => expect(putCalls(mockFetch)).toHaveLength(2));
    expect(putRevision(mockFetch)).toBe("4");
    // The header re-reads the room.
    expect(calls(mockFetch, "GET", "/api/v1/rooms/12").length).toBeGreaterThan(1);
  });

  test("a save also re-reads the room's threads (it can move them to a replacement status)", async () => {
    const user = userEvent.setup();
    serve(mockFetch);
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const invalidate = vi.spyOn(queryClient, "invalidateQueries");
    renderWorkflow(queryClient);
    await editLabel(user, 3, " (closed)");
    await save(user);
    await waitFor(() => expect(invalidate).toHaveBeenCalledWith({ queryKey: ["rooms", "threads", 12] }));
  });

  test("an administrator who is not a member edits too", async () => {
    signIn(1, ["ADMIN"]);
    serve(mockFetch, { room: { ...ROOM, role: null } });
    renderWorkflow();
    expect(await waitFor(() => label(1))).toBeEnabled();
    expect(screen.getByRole("button", { name: "Save" })).toBeInTheDocument();
  });

  test("a validation error blocks the save and is shown on the row", async () => {
    const user = userEvent.setup();
    serve(mockFetch);
    renderWorkflow();
    await waitFor(() => label(2));
    await user.clear(label(2));
    await save(user);
    expect(await screen.findByText("Fix the highlighted fields before saving.")).toBeInTheDocument();
    expect(screen.getByText("The label is required")).toBeInTheDocument();
    expect(putCalls(mockFetch)).toHaveLength(0);
  });

  test("a save moves focus to the workflow group, and so does Reload", async () => {
    const user = userEvent.setup();
    const server = serve(mockFetch);
    renderWorkflow();
    await editLabel(user, 1, "!");
    await save(user);
    await waitFor(() => expect(screen.getByRole("group", { name: "Room workflow" })).toHaveFocus());
    await editLabel(user, 1, "?");
    server.put = () => problem(409, "urn:adder:problem:revision-conflict");
    await save(user);
    await user.click(await screen.findByRole("button", { name: "Reload the latest version" }));
    await waitFor(() => expect(label(1)).toHaveValue("Triage!"));
    await waitFor(() => expect(screen.getByRole("group", { name: "Room workflow" })).toHaveFocus());
  });

  test("the editor is locked while a save is in flight", async () => {
    const user = userEvent.setup();
    serve(mockFetch);
    let release!: () => void;
    const held = new Promise<void>((resolve) => {
      release = resolve;
    });
    renderWorkflow();
    await editLabel(user, 1, "!");
    const answer = mockFetch.getMockImplementation() as (url: string, init?: RequestInit) => Promise<Response>;
    mockFetch.mockImplementation((url: string, init?: RequestInit) =>
      init?.method === "PUT" ? held.then(() => new Response(null, { status: 204 })) : answer(url, init),
    );
    await save(user);
    await waitFor(() => expect(label(1)).toBeDisabled());
    expect(screen.getByRole("button", { name: "Add status" })).toBeDisabled();
    release();
    await waitFor(() => expect(showSuccessToast).toHaveBeenCalled());
  });

  test("the fix-the-fields notice goes once the draft is valid again", async () => {
    const user = userEvent.setup();
    serve(mockFetch);
    renderWorkflow();
    await waitFor(() => label(2));
    await user.clear(label(2));
    await save(user);
    expect(await screen.findByText("Fix the highlighted fields before saving.")).toBeInTheDocument();
    await user.type(label(2), "Working");
    await waitFor(() => expect(screen.queryByText("Fix the highlighted fields before saving.")).not.toBeInTheDocument());
  });

  test("a background refetch never rebases the draft of someone who can edit", async () => {
    const user = userEvent.setup();
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const server = serve(mockFetch);
    renderWorkflow(queryClient);
    await editLabel(user, 1, " draft");
    server.workflow = { ...WORKFLOW, revision: 9, statuses: WORKFLOW.statuses.map((s) => ({ ...s, label: `${s.label} (theirs)` })) };
    await queryClient.refetchQueries({ queryKey: ["rooms", "workflow-edit", 12] });
    expect(label(1)).toHaveValue("Triage draft");
  });

  test("regaining window focus does not re-read the workflow of someone who can edit", async () => {
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    serve(mockFetch);
    renderWorkflow(queryClient);
    await waitFor(() => label(1));
    const updates = (key: string) => queryClient.getQueryState(["rooms", key, 12])?.dataUpdateCount;
    const [edit, summary] = [updates("workflow-edit"), updates("workflow")];
    focusManager.setFocused(false);
    focusManager.setFocused(true);
    // The summary (a default query) refetched on focus; the editor's own read did not.
    await waitFor(() => expect(updates("workflow")).toBeGreaterThan(summary!));
    expect(updates("workflow-edit")).toBe(edit);
  });
});

describe("read-only", () => {
  const first = () => screen.findByText("Triage");

  test("a plain member reads the workflow as plain text under a note, with no controls", async () => {
    signIn(8);
    serve(mockFetch, { room: { ...ROOM, role: "MEMBER" } });
    renderWorkflow();
    await first();
    expect(screen.getByRole("note")).toHaveTextContent("Only the room's owners and administrators can change the workflow.");
    // Every value is text a keyboard and a screen reader reach, not a disabled control.
    expect(screen.queryByRole("textbox")).not.toBeInTheDocument();
    expect(screen.getByText("Open · Initial status")).toBeInTheDocument();
    expect(screen.getAllByText("Closed", { selector: "p" })).toHaveLength(2);
    expect(screen.getByText("Severity").closest("li")).toHaveTextContent("Options: S1");
    expect(screen.getByText("Select")).toBeInTheDocument();
    expect(screen.getByText("Required")).toBeInTheDocument();
    expect(screen.getByText("Options: S1")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Save" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Cancel" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Add status" })).not.toBeInTheDocument();
    expect(headingOutline()).toEqual([
      [2, "Night Shift"],
      [3, "Workflow"],
      [4, "Statuses"],
      [4, "Fields"],
    ]);
  });

  test("a workflow without fields says so", async () => {
    signIn(8);
    serve(mockFetch, { room: { ...ROOM, role: "MEMBER" }, workflow: { ...WORKFLOW, fields: [] } });
    renderWorkflow();
    await first();
    expect(screen.getByText("No fields yet")).toBeInTheDocument();
  });

  test("an archived room is read-only for its owner too, with its own note", async () => {
    serve(mockFetch, { room: { ...ROOM, archived: true } });
    renderWorkflow();
    await first();
    expect(screen.getByRole("note")).toHaveTextContent("This room is archived, so its workflow is read-only. Unarchive it to edit it.");
    expect(screen.queryByRole("textbox")).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Save" })).not.toBeInTheDocument();
  });

  test("a reader who cannot edit sees a newer revision when the room is re-read", async () => {
    signIn(8);
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const server = serve(mockFetch, { room: { ...ROOM, role: "MEMBER" } });
    renderWorkflow(queryClient);
    await first();
    server.workflow = { ...WORKFLOW, revision: 4, statuses: WORKFLOW.statuses.map((s) => ({ ...s, label: `${s.label} v4` })) };
    await queryClient.refetchQueries({ queryKey: ["rooms", "workflow-edit", 12] });
    expect(await screen.findByText("Triage v4")).toBeInTheDocument();
  });

  test("regaining window focus re-reads the workflow of a reader", async () => {
    signIn(8);
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    serve(mockFetch, { room: { ...ROOM, role: "MEMBER" } });
    renderWorkflow(queryClient);
    await first();
    const updates = () => queryClient.getQueryState(["rooms", "workflow-edit", 12])?.dataUpdateCount ?? 0;
    const before = updates();
    focusManager.setFocused(false);
    focusManager.setFocused(true);
    await waitFor(() => expect(updates()).toBeGreaterThan(before));
  });
});

describe("the retire dialog", () => {
  test("removing a status that threads use asks where they go, shows an undoable notice and sends the replacement", async () => {
    const user = userEvent.setup();
    serve(mockFetch);
    renderWorkflow();
    await waitFor(() => label(2));
    await user.click(within(statusRow(2)).getByRole("button", { name: "Remove status 2" }));
    const dialog = await screen.findByRole("dialog", { name: "Move threads before removing" });
    expect(within(dialog).getByText("2 threads are in “Working”. Move them to:")).toBeInTheDocument();
    await user.click(within(dialog).getByRole("combobox", { name: "Move to" }));
    await user.click(await screen.findByRole("option", { name: "Triage" }));
    await user.click(within(dialog).getByRole("button", { name: "Remove status" }));
    expect(await screen.findByText("“Working” will be removed; its 2 threads move to “Triage”.")).toBeInTheDocument();
    await save(user);
    await waitFor(() => expect(putCalls(mockFetch)).toHaveLength(1));
    expect(putBody(mockFetch).statuses.map((s: { key: string }) => s.key)).toEqual(["triage", "done", "dropped"]);
    expect(putBody(mockFetch).replacements).toEqual({ working: "triage" });
  });

  test("a 409 status-in-use re-opens the dialog for the status it names, and the next save carries the replacement", async () => {
    const user = userEvent.setup();
    const server = serve(mockFetch);
    renderWorkflow();
    await waitFor(() => label(4));
    // No threads the page knows of, so the status goes without a question.
    await user.click(within(statusRow(4)).getByRole("button", { name: "Remove status 4" }));
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    server.put = () => problem(409, "urn:adder:problem:status-in-use", ["dropped"]);
    await save(user);
    const dialog = await screen.findByRole("dialog", { name: "Move threads before removing" });
    expect(within(dialog).getByText("Threads have joined “Dropped” since you opened this page. Move them to:")).toBeInTheDocument();
    expect(await screen.findByText(/Threads still use a status you removed or re-categorised/)).toBeInTheDocument();
    await user.click(within(dialog).getByRole("combobox", { name: "Move to" }));
    await user.click(await screen.findByRole("option", { name: "Done" }));
    await user.click(within(dialog).getByRole("button", { name: "Remove status" }));
    delete server.put;
    await save(user);
    await waitFor(() => expect(putCalls(mockFetch)).toHaveLength(2));
    expect(putBody(mockFetch).replacements).toEqual({ dropped: "done" });
  });
});

describe("a status whose category was changed while threads joined it", () => {
  test("a 409 status-in-use for a status still in the document says to set the category back and opens no dialog", async () => {
    const user = userEvent.setup();
    const server = serve(mockFetch);
    renderWorkflow();
    await waitFor(() => label(3));
    // Done holds no threads as far as the page knows, so its category can be changed; the server knows better.
    await user.click(within(statusRow(3)).getByText("Open", { exact: true }));
    server.put = () => problem(409, "urn:adder:problem:status-in-use", ["done"]);
    await save(user);
    expect(await screen.findByText(/Threads now use Done\. The category of a status that threads use cannot change/)).toBeInTheDocument();
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    // Setting it back makes the draft clean again.
    await user.click(within(statusRow(3)).getByText("Closed", { exact: true }));
    expect(screen.getByRole("button", { name: "Save" })).toBeDisabled();
  });
});

describe("a save the server refuses", () => {
  test("a stale save keeps the draft; Reload re-seeds from the newer version and the next save carries its revision", async () => {
    const user = userEvent.setup();
    const server = serve(mockFetch);
    renderWorkflow();
    await editLabel(user, 1, " draft");
    server.put = () => problem(409, "urn:adder:problem:revision-conflict");
    await save(user);
    expect(await screen.findByText("This workflow was changed elsewhere")).toBeInTheDocument();
    expect(label(1)).toHaveValue("Triage draft");

    // Someone else saved revision 4 meanwhile; Reload shows it, clean.
    delete server.put;
    server.workflow = { ...WORKFLOW, revision: 4, statuses: WORKFLOW.statuses.map((s) => ({ ...s, label: `${s.label} (theirs)` })) };
    await user.click(screen.getByRole("button", { name: "Reload the latest version" }));
    await waitFor(() => expect(label(1)).toHaveValue("Triage (theirs)"));
    expect(screen.queryByText("This workflow was changed elsewhere")).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Save" })).toBeDisabled();
    await editLabel(user, 1, "!");
    await save(user);
    await waitFor(() => expect(putCalls(mockFetch)).toHaveLength(2));
    expect(putRevision(mockFetch)).toBe("4");
  });

  test("a Reload that cannot read the newer version says so and keeps the draft and the alert", async () => {
    const user = userEvent.setup();
    const server = serve(mockFetch);
    renderWorkflow();
    await editLabel(user, 1, " draft");
    server.put = () => problem(409, "urn:adder:problem:revision-conflict");
    await save(user);
    server.workflowRead = () => problem(500);
    await user.click(await screen.findByRole("button", { name: "Reload the latest version" }));
    expect(await screen.findByText("Load failed (500)")).toBeInTheDocument();
    expect(label(1)).toHaveValue("Triage draft");
    expect(screen.getByText("This workflow was changed elsewhere")).toBeInTheDocument();
  });

  test.each([
    ["a plain 409 is the key budget", 409, undefined, undefined, "This room has used up the keys it may reserve"],
    ["a 409 field-type-immutable names the keys", 409, "urn:adder:problem:field-type-immutable", ["sev"], "A field with the key sev already existed with another type"],
    ["a 400 is the server disagreeing with the editor", 400, undefined, undefined, "The server rejected this workflow."],
    ["a 500 is status-tagged", 500, undefined, undefined, "Save failed (500)"],
  ])("%s", async (_name, status, type, keys, message) => {
    const user = userEvent.setup();
    serve(mockFetch, { put: () => problem(status, type, keys) });
    renderWorkflow();
    await editLabel(user, 1, "!");
    await save(user);
    expect(await screen.findByText(message, { exact: false })).toBeInTheDocument();
    // The draft is kept and can be saved again.
    expect(label(1)).toHaveValue("Triage!");
    expect(screen.getByRole("button", { name: "Save" })).toBeEnabled();
  });

  test.each([
    ["names the wait from the Retry-After", { "Retry-After": "12" }, "You're doing that too fast. Try again in 12 seconds."],
    ["says in a moment without one", {}, "You're doing that too fast. Try again in a moment."],
  ])("a 429 from the chat-write bucket %s, and the draft is kept", async (_name, headers, message) => {
    const user = userEvent.setup();
    serve(mockFetch, { put: () => jsonResponse(429, { title: "Too Many Requests", status: 429 }, headers) });
    renderWorkflow();
    await editLabel(user, 1, "!");
    await save(user);
    expect(await screen.findByText(message)).toBeInTheDocument();
    expect(screen.queryByText("Save failed (429)")).not.toBeInTheDocument();
    expect(label(1)).toHaveValue("Triage!");
  });

  test("demoted behind the page: a 403 re-reads the room, the editor turns read-only and the refusal stays visible", async () => {
    const user = userEvent.setup();
    const server = serve(mockFetch);
    renderWorkflow();
    await editLabel(user, 1, "!");
    server.room = { ...ROOM, role: "MEMBER" };
    server.put = () => problem(403);
    await save(user);
    // The read-only view shows the STORED workflow, not the unsaved draft; the refusal stays on screen.
    expect(await screen.findByText("Triage")).toBeInTheDocument();
    expect(screen.queryByText("Triage!")).not.toBeInTheDocument();
    expect(screen.queryByRole("textbox")).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Save" })).not.toBeInTheDocument();
    expect(screen.getByRole("alert")).toHaveTextContent("Only the room's owners and administrators can change the workflow");
  });

  test("archived behind the page: a 409 room-archived re-reads the room and the editor turns read-only", async () => {
    const user = userEvent.setup();
    const server = serve(mockFetch);
    renderWorkflow();
    await editLabel(user, 1, "!");
    server.room = { ...ROOM, archived: true };
    server.put = () => problem(409, "urn:adder:problem:room-archived");
    await save(user);
    expect(await screen.findByText("Triage")).toBeInTheDocument();
    expect(screen.queryByText("Triage!")).not.toBeInTheDocument();
    expect(screen.queryByRole("textbox")).not.toBeInTheDocument();
    expect(screen.getByText("This room was archived in the meantime, so its workflow can no longer be edited.")).toBeInTheDocument();
    expect(screen.getByRole("note")).toHaveTextContent("This room is archived");
  });

  test("removed behind the page: a 404 re-reads the room and the page turns to not-found", async () => {
    const user = userEvent.setup();
    const server = serve(mockFetch);
    renderWorkflow();
    await editLabel(user, 1, "!");
    server.room = null;
    server.put = () => problem(404);
    await save(user);
    expect(await screen.findByText("This room does not exist, or you are not a member of it.")).toBeInTheDocument();
  });
});

describe("a save that committed but whose re-read failed", () => {
  test("the editor freezes behind a reload prompt; Reload re-seeds and unfreezes it", async () => {
    const user = userEvent.setup();
    const server = serve(mockFetch);
    renderWorkflow();
    await editLabel(user, 1, "!");
    // The PUT commits, then the re-read fails.
    mockFetch.mockImplementationOnce(() => Promise.resolve(new Response(null, { status: 204 })));
    server.workflowRead = () => problem(500);
    await save(user);
    expect(await screen.findByText("Saved, but the latest version could not be loaded")).toBeInTheDocument();
    expect(label(1)).toBeDisabled();
    expect(screen.getByRole("button", { name: "Save" })).toBeDisabled();

    delete server.workflowRead;
    server.workflow = { ...WORKFLOW, revision: 4, statuses: WORKFLOW.statuses.map((s, i) => (i === 0 ? { ...s, label: "Triage!" } : s)) };
    await user.click(screen.getByRole("button", { name: "Reload the latest version" }));
    await waitFor(() => expect(label(1)).toBeEnabled());
    expect(label(1)).toHaveValue("Triage!");
    expect(screen.queryByText("Saved, but the latest version could not be loaded")).not.toBeInTheDocument();
  });
});

describe("leaving", () => {
  test("Cancel leaves straight away while the draft is clean", async () => {
    const user = userEvent.setup();
    serve(mockFetch);
    renderWorkflow();
    await waitFor(() => label(1));
    await user.click(screen.getByRole("button", { name: "Cancel" }));
    expect(await screen.findByText("at /rooms/12")).toBeInTheDocument();
  });

  test("Cancel on a dirty draft asks first: Keep editing stays, Discard leaves", async () => {
    const user = userEvent.setup();
    serve(mockFetch);
    renderWorkflow();
    await editLabel(user, 1, "!");
    await user.click(screen.getByRole("button", { name: "Cancel" }));
    let dialog = await screen.findByRole("dialog");
    await user.click(within(dialog).getByRole("button", { name: "Keep editing" }));
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    expect(label(1)).toHaveValue("Triage!");
    await user.click(screen.getByRole("button", { name: "Cancel" }));
    dialog = await screen.findByRole("dialog");
    await user.click(within(dialog).getByRole("button", { name: "Discard" }));
    expect(await screen.findByText("at /rooms/12")).toBeInTheDocument();
  });

  test("closing or reloading the tab is intercepted only while the draft is dirty", async () => {
    const user = userEvent.setup();
    serve(mockFetch);
    renderWorkflow();
    await waitFor(() => label(1));
    expect(fireEvent(window, new Event("beforeunload", { cancelable: true }))).toBe(true);
    await editLabel(user, 1, "!");
    expect(fireEvent(window, new Event("beforeunload", { cancelable: true }))).toBe(false);
  });
});

describe("loading", () => {
  test("a failed workflow read is an inline alert under the room's header", async () => {
    serve(mockFetch, { workflowRead: () => problem(500) });
    renderWorkflow();
    expect(await screen.findByText("Could not load the workflow")).toBeInTheDocument();
    expect(screen.getByText("Load failed (500)")).toBeInTheDocument();
    expect(screen.getByRole("heading", { level: 2, name: "Night Shift" })).toBeInTheDocument();
  });

  test("Retry reads the workflow again and shows the editor", async () => {
    const user = userEvent.setup();
    const server = serve(mockFetch, { workflowRead: () => problem(500) });
    renderWorkflow();
    await user.click(await screen.findByRole("button", { name: "Retry" }));
    expect(await screen.findByText("Load failed (500)")).toBeInTheDocument();
    delete server.workflowRead;
    await user.click(screen.getByRole("button", { name: "Retry" }));
    expect(await waitFor(() => label(1))).toHaveValue("Triage");
  });

  test("the workflow answering 404 re-reads the room, which turns the page to not-found", async () => {
    const server = serve(mockFetch);
    server.workflowRead = () => {
      server.room = null;
      return problem(404);
    };
    renderWorkflow();
    expect(await screen.findByText("This room does not exist, or you are not a member of it.")).toBeInTheDocument();
  });
});
