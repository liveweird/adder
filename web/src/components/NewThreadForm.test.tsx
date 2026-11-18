import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { screen, waitFor } from "@testing-library/react";
import { Link, Route, Routes, useLocation } from "react-router-dom";
import NewThreadForm from "./NewThreadForm";
import ThreadDraftsProvider from "./ThreadDraftsProvider";
import type { RoomResponse } from "../api/rooms";
import { jsonResponse } from "../test/http";
import { renderWithProviders } from "../test/render";

const ROOM: RoomResponse = {
  id: 12,
  name: "Night Shift",
  description: null,
  archived: false,
  archivedAt: null,
  role: "MEMBER",
  memberCount: 3,
  workflowRevision: 1,
  lastActivityAt: 1,
  createdAt: 1,
  updatedAt: 1,
};
const WORKFLOW = {
  revision: 1,
  statuses: [{ key: "triage", label: "Triage", colour: "BLUE", category: "OPEN", initial: true, threadCount: 0 }],
  fields: [
    { key: "severity", label: "Severity", type: "SELECT", required: true, options: [{ key: "s1", label: "S1" }, { key: "s2", label: "S2" }] },
    { key: "component", label: "Component", type: "TEXT", required: false, options: [] },
    { key: "link", label: "Jira link", type: "URL", required: false, options: [] },
  ],
};
const CREATED = { id: 1, number: 7, title: "Disk full", status: "triage" };

function Where() {
  const location = useLocation();
  return (
    <>
      <p data-testid="where">{location.pathname}</p>
      <p data-testid="state">{JSON.stringify(location.state)}</p>
    </>
  );
}

function renderForm(room: RoomResponse = ROOM) {
  return renderWithProviders(
    <Routes>
      <Route path="/rooms/:id/threads/new" element={<NewThreadForm room={room} />} />
      <Route path="/rooms/:id" element={<Where />} />
      <Route path="/rooms/:id/threads/:number" element={<Where />} />
    </Routes>,
    { route: "/rooms/12/threads/new" },
  );
}

/** The form and a stand-in for the rest of the room view, under one draft store: leaving the form and coming back is a link click. */
function renderFormWithElsewhere() {
  return renderWithProviders(
    <ThreadDraftsProvider>
      <Routes>
        <Route path="/rooms/:id/threads/new" element={<NewThreadForm room={ROOM} />} />
        <Route path="/rooms/:id/*" element={<Where />} />
      </Routes>
      <Link to="/rooms/12/threads/new">to the form</Link>
      <Link to="/rooms/12/members">to a tab</Link>
    </ThreadDraftsProvider>,
    { route: "/rooms/12/threads/new" },
  );
}

describe("NewThreadForm", () => {
  let mockFetch: ReturnType<typeof vi.fn>;
  const posts = () => mockFetch.mock.calls.filter(([, init]) => (init as RequestInit | undefined)?.method === "POST");

  function serve(workflow: Response | undefined = jsonResponse(200, WORKFLOW), create: () => Response = () => jsonResponse(201, CREATED)) {
    mockFetch.mockImplementation((url: string, init?: RequestInit) => {
      if (url === "/api/v1/rooms/12/workflow") return Promise.resolve(workflow.clone());
      if (url === "/api/v1/rooms/12/threads" && init?.method === "POST") return Promise.resolve(create());
      return Promise.resolve(jsonResponse(200, { items: [], page: 1, pageSize: 50, total: 0 }));
    });
  }

  beforeEach(() => {
    mockFetch = vi.fn();
    vi.stubGlobal("fetch", mockFetch);
    localStorage.setItem("adder.auth.token", "fake-token");
  });
  afterEach(() => {
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  async function fillRequired(user: ReturnType<typeof userEvent.setup>) {
    await user.type(await screen.findByRole("textbox", { name: /Title/ }), "  Disk full  ");
    await user.type(screen.getByRole("textbox", { name: /First message/ }), "db-3 is at 100%");
  }

  test("a required field leads, the others sit behind More fields, and a start posts the typed body and lands on the new thread", async () => {
    serve();
    const user = userEvent.setup();
    renderForm();
    expect(await screen.findByRole("heading", { level: 3, name: "New thread" })).toBeInTheDocument();
    expect(await screen.findByRole("combobox", { name: /Severity/ })).toBeRequired();
    const more = screen.getByRole("button", { name: "More fields" });
    expect(more).toHaveAttribute("aria-expanded", "false");
    await user.click(more);
    expect(more).toHaveAttribute("aria-expanded", "true");
    await user.type(screen.getByRole("textbox", { name: "Component" }), "db");
    await fillRequired(user);
    await user.click(screen.getByRole("combobox", { name: /Severity/ }));
    await user.click(await screen.findByRole("option", { name: "S2" }));
    await user.click(screen.getByRole("button", { name: "Start thread" }));
    await waitFor(() => expect(screen.getByTestId("where")).toHaveTextContent("/rooms/12/threads/7"));
    // The new thread's pane takes focus (the navigation says so).
    expect(JSON.parse(screen.getByTestId("state").textContent ?? "null")).toEqual({ focusPane: true });
    expect(posts()).toHaveLength(1);
    expect(JSON.parse((posts()[0][1] as RequestInit).body as string)).toEqual({
      title: "Disk full",
      body: "db-3 is at 100%",
      fields: { severity: "s2", component: "db" },
    });
  });

  test("a form with a required field empty is refused before any request, and an invalid optional field opens More fields", async () => {
    serve();
    const user = userEvent.setup();
    renderForm();
    await fillRequired(user);
    await user.click(screen.getByRole("button", { name: "More fields" }));
    await user.type(screen.getByRole("textbox", { name: "Jira link" }), "ftp://nope");
    await user.click(screen.getByRole("button", { name: "More fields" }));
    expect(screen.getByRole("button", { name: "More fields" })).toHaveAttribute("aria-expanded", "false");
    await user.click(screen.getByRole("button", { name: "Start thread" }));
    expect(await screen.findByText("This field is required")).toBeInTheDocument();
    expect(screen.getByText("Jira link must be a valid http(s) address (at most 2000 characters)")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "More fields" })).toHaveAttribute("aria-expanded", "true");
    expect(posts()).toEqual([]);
  });

  test("a blank title and message are refused with their rules", async () => {
    serve();
    const user = userEvent.setup();
    renderForm();
    await user.click(await screen.findByRole("button", { name: "Start thread" }));
    expect(await screen.findByText("Title must be 1–200 characters")).toBeInTheDocument();
    expect(screen.getByText("The message must be 1–8000 characters")).toBeInTheDocument();
    expect(posts()).toEqual([]);
  });

  test("a room without extra fields shows no More fields control", async () => {
    serve(jsonResponse(200, { ...WORKFLOW, fields: [] }));
    renderForm();
    await screen.findByRole("textbox", { name: /Title/ });
    expect(screen.queryByRole("button", { name: "More fields" })).not.toBeInTheDocument();
  });

  test.each([
    [403, "Only members of the room can start threads"],
    [404, "no longer exists"],
    [409, "archived in the meantime"],
    [429, "posting too quickly"],
    [400, "one or more fields are invalid"],
    [500, "Create failed (500)"],
  ])("a %i answer is an inline alert, the draft stays, and the form can be sent again", async (status, words) => {
    serve(undefined, () => jsonResponse(status, { title: "No", status }));
    const user = userEvent.setup();
    renderForm();
    await fillRequired(user);
    await user.click(screen.getByRole("combobox", { name: /Severity/ }));
    await user.click(await screen.findByRole("option", { name: "S1" }));
    await user.click(screen.getByRole("button", { name: "Start thread" }));
    expect(await screen.findByRole("alert")).toHaveTextContent(words);
    expect(screen.getByRole("textbox", { name: /Title/ })).toHaveValue("  Disk full  ");
    expect(screen.getByRole("button", { name: "Start thread" })).toBeEnabled();
    // A refusal that says the caller's standing moved re-reads the room's queries.
    if (status === 403 || status === 404 || status === 409) await waitFor(() => expect(mockFetch.mock.calls.filter(([url]) => url === "/api/v1/rooms/12/workflow").length).toBeGreaterThan(1));
  });

  test("Cancel leaves a clean form at once and asks before discarding a dirty one", async () => {
    serve();
    const user = userEvent.setup();
    const { unmount } = renderForm();
    await user.click(await screen.findByRole("button", { name: "Cancel" }));
    expect(await screen.findByTestId("where")).toHaveTextContent("/rooms/12");
    unmount();
    renderForm();
    await user.type(await screen.findByRole("textbox", { name: /Title/ }), "draft");
    await user.click(screen.getByRole("button", { name: "Cancel" }));
    expect(await screen.findByText("Discard changes?")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Keep editing" }));
    expect(screen.getByRole("textbox", { name: /Title/ })).toHaveValue("draft");
    await user.click(screen.getByRole("button", { name: "Cancel" }));
    await user.click(await screen.findByRole("button", { name: "Discard" }));
    expect(await screen.findByTestId("where")).toHaveTextContent("/rooms/12");
  });

  test("an ADMIN who is not a member, and an archived room, see why there is no form", async () => {
    serve();
    const { unmount } = renderForm({ ...ROOM, role: null });
    expect(screen.getByRole("note")).toHaveTextContent("Only members of the room can start threads. Join the room from its Members page first.");
    expect(screen.queryByRole("textbox", { name: /Title/ })).not.toBeInTheDocument();
    unmount();
    renderForm({ ...ROOM, archived: true });
    expect(screen.getByRole("note")).toHaveTextContent("This room is archived, so no new threads can be started.");
  });

  test("a failed workflow read is an inline alert instead of a form, and a slow one shows a spinner", async () => {
    serve(jsonResponse(500, { title: "Boom", status: 500 }));
    const { unmount } = renderForm();
    expect(screen.getByRole("status", { name: "Loading…" })).toBeInTheDocument();
    expect(await screen.findByText("Could not load the room's fields")).toBeInTheDocument();
    expect(screen.queryByRole("textbox", { name: /Title/ })).not.toBeInTheDocument();
    unmount();
  });

  test("a half-written thread survives leaving the form and returns when it is reopened, laid over the fields", async () => {
    serve();
    const user = userEvent.setup();
    renderFormWithElsewhere();
    await user.type(await screen.findByRole("textbox", { name: /Title/ }), "Disk full");
    await user.type(screen.getByRole("textbox", { name: /First message/ }), "db-3 is at 100%");
    await user.click(screen.getByRole("combobox", { name: /Severity/ }));
    await user.click(await screen.findByRole("option", { name: "S2" }));
    await user.click(screen.getByRole("link", { name: "to a tab" }));
    expect(await screen.findByTestId("where")).toHaveTextContent("/rooms/12/members");
    await user.click(screen.getByRole("link", { name: "to the form" }));
    expect(await screen.findByRole("textbox", { name: /Title/ })).toHaveValue("Disk full");
    expect(screen.getByRole("textbox", { name: /First message/ })).toHaveValue("db-3 is at 100%");
    expect(screen.getByRole("combobox", { name: /Severity/ })).toHaveValue("S2");
    // It is still a draft to protect: Cancel asks before throwing it away.
    await user.click(screen.getByRole("button", { name: "Cancel" }));
    expect(await screen.findByText("Discard changes?")).toBeInTheDocument();
  });

  test("an emptied form keeps nothing, and a discarded draft is gone on the next visit", async () => {
    serve();
    const user = userEvent.setup();
    renderFormWithElsewhere();
    const title = await screen.findByRole("textbox", { name: /Title/ });
    await user.type(title, "x");
    await user.clear(title);
    await user.click(screen.getByRole("link", { name: "to a tab" }));
    await user.click(await screen.findByRole("link", { name: "to the form" }));
    expect(await screen.findByRole("textbox", { name: /Title/ })).toHaveValue("");
    // Discard.
    await user.type(screen.getByRole("textbox", { name: /Title/ }), "draft");
    await user.click(screen.getByRole("button", { name: "Cancel" }));
    await user.click(await screen.findByRole("button", { name: "Discard" }));
    expect(await screen.findByTestId("where")).toHaveTextContent("/rooms/12");
    await user.click(screen.getByRole("link", { name: "to the form" }));
    expect(await screen.findByRole("textbox", { name: /Title/ })).toHaveValue("");
  });

  test("a started thread clears the draft", async () => {
    serve();
    const user = userEvent.setup();
    renderFormWithElsewhere();
    await user.type(await screen.findByRole("textbox", { name: /Title/ }), "Disk full");
    await user.type(screen.getByRole("textbox", { name: /First message/ }), "db-3");
    await user.click(screen.getByRole("combobox", { name: /Severity/ }));
    await user.click(await screen.findByRole("option", { name: "S1" }));
    await user.click(screen.getByRole("button", { name: "Start thread" }));
    await waitFor(() => expect(screen.getByTestId("where")).toHaveTextContent("/rooms/12/threads/7"));
    await user.click(screen.getByRole("link", { name: "to the form" }));
    expect(await screen.findByRole("textbox", { name: /Title/ })).toHaveValue("");
  });

  test("a 400 shows the server's own sentence, re-reads the workflow, and one about a field says the fields may have changed", async () => {
    serve(undefined, () => jsonResponse(400, { title: "Bad", status: 400, detail: "Unknown field 'severity'" }));
    const user = userEvent.setup();
    renderForm();
    await fillRequired(user);
    await user.click(screen.getByRole("combobox", { name: /Severity/ }));
    await user.click(await screen.findByRole("option", { name: "S1" }));
    const readsBefore = mockFetch.mock.calls.filter(([url]) => url === "/api/v1/rooms/12/workflow").length;
    await user.click(screen.getByRole("button", { name: "Start thread" }));
    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent("The room's fields may have changed");
    expect(alert).toHaveTextContent("(Unknown field 'severity')");
    await waitFor(() => expect(mockFetch.mock.calls.filter(([url]) => url === "/api/v1/rooms/12/workflow").length).toBeGreaterThan(readsBefore));
  });

  test("a 400 about the title or message keeps the generic words and adds the server's sentence", async () => {
    serve(undefined, () => jsonResponse(400, { title: "Bad", status: 400, detail: "Title must be 1-200 characters" }));
    const user = userEvent.setup();
    renderForm();
    await fillRequired(user);
    await user.click(screen.getByRole("combobox", { name: /Severity/ }));
    await user.click(await screen.findByRole("option", { name: "S1" }));
    await user.click(screen.getByRole("button", { name: "Start thread" }));
    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent("The thread could not be started: one or more fields are invalid (Title must be 1-200 characters)");
    expect(alert).not.toHaveTextContent("fields may have changed");
  });
});
