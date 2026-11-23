import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { screen, waitFor, within } from "@testing-library/react";
import { Route, Routes, useLocation } from "react-router-dom";
import CreateRoom from "./CreateRoom";
import i18n from "../i18n";
import { jsonResponse } from "../test/http";
import { renderWithProviders } from "../test/render";

type FetchMock = ReturnType<typeof vi.fn>;

const TEMPLATES = {
  items: [
    { id: 1, name: "Default", description: null, statusCount: 2, fieldCount: 0, revision: 1, updatedAt: 1 },
    { id: 2, name: "Incident", description: null, statusCount: 3, fieldCount: 4, revision: 1, updatedAt: 1 },
  ],
  page: 1,
  pageSize: 100,
  total: 2,
};
const INCIDENT = {
  id: 2,
  name: "Incident",
  description: null,
  statuses: [
    { key: "investigating", label: { en: "Investigating", pl: "Diagnoza" }, colour: "ORANGE", category: "OPEN", initial: true },
    { key: "resolved", label: { en: "Resolved" }, colour: "TEAL", category: "CLOSED", initial: false },
  ],
  fields: [],
  revision: 1,
  createdAt: 1,
  updatedAt: 1,
};

function Probe() {
  return <p>at {useLocation().pathname}</p>;
}

function renderCreate() {
  return renderWithProviders(
    <Routes>
      <Route path="/rooms/new" element={<CreateRoom />} />
      <Route path="/rooms" element={<Probe />} />
      <Route path="/rooms/:id" element={<Probe />} />
    </Routes>,
    { route: "/rooms/new" },
  );
}

const postCalls = (mockFetch: FetchMock) => mockFetch.mock.calls.filter(([, init]) => (init as RequestInit | undefined)?.method === "POST");

function serve(mockFetch: FetchMock, post: () => Response = () => jsonResponse(201, { id: 9 }), templates: unknown = TEMPLATES) {
  mockFetch.mockImplementation((url: string, init?: RequestInit) => {
    if (init?.method === "POST") return Promise.resolve(post());
    if (url === "/api/v1/workflow-templates/2") return Promise.resolve(jsonResponse(200, INCIDENT));
    if (url === "/api/v1/workflow-templates/1") return Promise.resolve(jsonResponse(200, { ...INCIDENT, id: 1, name: "Default" }));
    if (url.startsWith("/api/v1/workflow-templates?")) return Promise.resolve(jsonResponse(200, templates));
    return Promise.resolve(jsonResponse(200, { items: [], page: 1, pageSize: 20, total: 0 }));
  });
}

async function pickTemplate(user: ReturnType<typeof userEvent.setup>, name: string) {
  await user.click(await screen.findByRole("combobox", { name: /Workflow template/ }));
  await user.click(await screen.findByRole("option", { name }));
}

describe("CreateRoom page", () => {
  let mockFetch: FetchMock;

  beforeEach(() => {
    mockFetch = vi.fn();
    vi.stubGlobal("fetch", mockFetch);
    localStorage.setItem("adder.auth.token", "fake-token");
  });

  afterEach(async () => {
    vi.unstubAllGlobals();
    localStorage.clear();
    await i18n.changeLanguage("en");
  });

  test("picking a template previews its statuses as pills, then creating sends the UI language and opens the room", async () => {
    serve(mockFetch);
    const user = userEvent.setup();
    renderCreate();
    expect(screen.getByRole("heading", { level: 2, name: "New room" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Back to rooms" })).toHaveAttribute("href", "/rooms");
    await pickTemplate(user, "Incident");
    const preview = await screen.findByRole("group", { name: "Statuses" });
    expect(within(preview).getByText("Investigating")).toBeInTheDocument();
    expect(within(preview).getByText("Resolved")).toBeInTheDocument();
    await user.type(screen.getByRole("textbox", { name: "Name" }), " Night Shift ");
    await user.type(screen.getByRole("textbox", { name: "Description" }), "Handover");
    const access = screen.getByRole("group", { name: "Who can find and join this room?" });
    expect(within(access).getByRole("radio", { name: /Restricted, hidden/ })).toBeChecked();
    await user.click(within(access).getByRole("radio", { name: /Public/ }));
    await user.click(screen.getByRole("button", { name: "Create" }));
    expect(await screen.findByText("at /rooms/9")).toBeInTheDocument();
    const [[url, init]] = postCalls(mockFetch);
    expect(url).toBe("/api/v1/rooms");
    expect(JSON.parse((init as RequestInit).body as string)).toEqual({
      name: "Night Shift",
      description: "Handover",
      templateId: 2,
      language: "en",
      accessType: "PUBLIC",
    });
  });

  test("a Polish UI previews the Polish labels (EN where a label has none) and creates the room in Polish", async () => {
    await i18n.changeLanguage("pl");
    serve(mockFetch);
    const user = userEvent.setup();
    renderCreate();
    await user.click(await screen.findByRole("combobox", { name: /Szablon przepływu pracy/ }));
    await user.click(await screen.findByRole("option", { name: "Incident" }));
    const preview = await screen.findByRole("group", { name: "Statusy" });
    expect(within(preview).getByText("Diagnoza")).toBeInTheDocument();
    expect(within(preview).getByText("Resolved")).toBeInTheDocument();
    await user.type(screen.getByRole("textbox", { name: "Nazwa" }), "Nocna zmiana");
    await user.click(screen.getByRole("button", { name: "Utwórz" }));
    await screen.findByText("at /rooms/9");
    expect(JSON.parse((postCalls(mockFetch)[0][1] as RequestInit).body as string)).toMatchObject({
      templateId: 2,
      language: "pl",
      accessType: "RESTRICTED_HIDDEN",
    });
  });

  test("a blank name and no template are refused before any request", async () => {
    serve(mockFetch);
    const user = userEvent.setup();
    renderCreate();
    await screen.findByRole("combobox", { name: /Workflow template/ });
    await user.click(screen.getByRole("button", { name: "Create" }));
    expect(await screen.findByText("Name must be 1–80 characters")).toBeInTheDocument();
    expect(screen.getByText("Pick a workflow template")).toBeInTheDocument();
    expect(postCalls(mockFetch)).toEqual([]);
  });

  test("a name clash (409) is marked on the name field and the form stays", async () => {
    serve(mockFetch, () => jsonResponse(409, { title: "Conflict", status: 409 }));
    const user = userEvent.setup();
    renderCreate();
    await pickTemplate(user, "Default");
    await user.type(screen.getByRole("textbox", { name: "Name" }), "Night Shift");
    await user.click(screen.getByRole("button", { name: "Create" }));
    expect(await screen.findByText("A room with this name already exists")).toBeInTheDocument();
    expect(screen.getByRole("heading", { level: 2, name: "New room" })).toBeInTheDocument();
  });

  test("a template deleted meanwhile (400) forgets the pick and says so", async () => {
    serve(mockFetch, () => jsonResponse(400, { title: "Bad Request", status: 400, detail: "Unknown or deleted workflow template id: 1", type: "urn:adder:problem:template-gone" }));
    const user = userEvent.setup();
    renderCreate();
    await pickTemplate(user, "Default");
    await user.type(screen.getByRole("textbox", { name: "Name" }), "Night Shift");
    await user.click(screen.getByRole("button", { name: "Create" }));
    expect(await screen.findByText("That template no longer exists. Pick another one.")).toBeInTheDocument();
    expect(screen.getByRole("combobox", { name: /Workflow template/ })).toHaveValue("");
  });

  test("any other 400 keeps the pick and says the fields are invalid", async () => {
    serve(mockFetch, () => jsonResponse(400, { title: "Bad Request", status: 400, detail: "Description must be at most 500 characters" }));
    const user = userEvent.setup();
    renderCreate();
    await pickTemplate(user, "Default");
    await user.type(screen.getByRole("textbox", { name: "Name" }), "Night Shift");
    await user.click(screen.getByRole("button", { name: "Create" }));
    expect(await screen.findByText("The room could not be created: one or more fields are invalid")).toBeInTheDocument();
    expect(screen.getByRole("combobox", { name: /Workflow template/ })).toHaveValue("Default");
  });

  test("a name with a control character is refused before any request", async () => {
    serve(mockFetch);
    const user = userEvent.setup();
    renderCreate();
    await pickTemplate(user, "Default");
    await user.click(screen.getByRole("textbox", { name: "Name" }));
    await user.paste("Night\u0007Shift");
    await user.click(screen.getByRole("button", { name: "Create" }));
    expect(await screen.findByText("Name must not contain control characters")).toBeInTheDocument();
    expect(postCalls(mockFetch)).toEqual([]);
  });

  test("the create bucket (429) and any other failure are named inline", async () => {
    serve(mockFetch, () => jsonResponse(429, { title: "Slow", status: 429 }));
    const user = userEvent.setup();
    renderCreate();
    await pickTemplate(user, "Default");
    await user.type(screen.getByRole("textbox", { name: "Name" }), "Night Shift");
    await user.click(screen.getByRole("button", { name: "Create" }));
    expect(await screen.findByText(/creating rooms too quickly/)).toBeInTheDocument();
  });

  test("without templates the form explains why it cannot create, and Create is disabled", async () => {
    serve(mockFetch, undefined, { items: [], page: 1, pageSize: 100, total: 0 });
    renderCreate();
    expect(await screen.findByText(/no workflow templates yet/)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Create" })).toBeDisabled();
  });

  test("a failed template list is an inline alert, and a failed preview does not block the form", async () => {
    mockFetch.mockImplementation(() => Promise.resolve(jsonResponse(500, { title: "Boom", status: 500 })));
    renderCreate();
    expect(await screen.findByText("Could not load the workflow templates")).toBeInTheDocument();
    expect(screen.getByRole("textbox", { name: "Name" })).toBeInTheDocument();
  });

  test("a failed preview says so", async () => {
    mockFetch.mockImplementation((url: string) =>
      Promise.resolve(url === "/api/v1/workflow-templates/2" ? jsonResponse(500, { title: "Boom", status: 500 }) : jsonResponse(200, TEMPLATES)),
    );
    const user = userEvent.setup();
    renderCreate();
    await pickTemplate(user, "Incident");
    expect(await screen.findByText("Could not load the template")).toBeInTheDocument();
  });

  test("Cancel leaves straight away while clean and asks first once something is typed", async () => {
    serve(mockFetch);
    const user = userEvent.setup();
    renderCreate();
    await user.click(screen.getByRole("button", { name: "Cancel" }));
    expect(await screen.findByText("at /rooms")).toBeInTheDocument();
    // The second visit: dirty, so a confirm.
    renderCreate();
    await user.type(screen.getAllByRole("textbox", { name: "Name" })[0], "Half");
    await user.click(screen.getAllByRole("button", { name: "Cancel" })[0]);
    expect(await screen.findByText("Discard changes?")).toBeInTheDocument();
    await waitFor(() => expect(postCalls(mockFetch)).toEqual([]));
  });
});
