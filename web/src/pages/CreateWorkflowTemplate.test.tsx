import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { screen, waitFor, within } from "@testing-library/react";
import { Route, Routes, useLocation } from "react-router-dom";
import CreateWorkflowTemplate from "./CreateWorkflowTemplate";
import { jsonResponse } from "../test/http";
import { renderWithProviders } from "../test/render";

type FetchMock = ReturnType<typeof vi.fn>;

function Probe() {
  return <p>at {useLocation().pathname}</p>;
}

function renderCreate() {
  return renderWithProviders(
    <Routes>
      <Route path="/workflow-templates/new" element={<CreateWorkflowTemplate />} />
      <Route path="/workflow-templates" element={<Probe />} />
    </Routes>,
    { route: "/workflow-templates/new" },
  );
}

const postCalls = (mockFetch: FetchMock) => mockFetch.mock.calls.filter(([, init]) => (init as RequestInit | undefined)?.method === "POST");

describe("CreateWorkflowTemplate page", () => {
  let mockFetch: FetchMock;

  beforeEach(() => {
    mockFetch = vi.fn();
    vi.stubGlobal("fetch", mockFetch);
    localStorage.setItem("adder.auth.token", "fake-token");
    localStorage.setItem("adder.auth.roles", JSON.stringify(["ADMIN"]));
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  test("starts from a valid two-status skeleton and creates the template, then returns to the list", async () => {
    mockFetch.mockImplementation((url: string, init?: RequestInit) =>
      Promise.resolve(
        init?.method === "POST" && url === "/api/v1/workflow-templates" ? jsonResponse(201, { id: 9 }) : jsonResponse(200, { items: [], page: 1, pageSize: 20, total: 0 }),
      ),
    );
    const user = userEvent.setup();
    renderCreate();
    expect(screen.getByRole("heading", { level: 2, name: "New workflow template" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Back to workflow templates" })).toHaveAttribute("href", "/workflow-templates");
    const first = screen.getByRole("group", { name: "Status 1" });
    expect(within(first).getByRole("textbox", { name: "Label (EN)" })).toHaveValue("Open");
    await user.type(screen.getByRole("textbox", { name: "Name" }), "Handover");
    await user.type(within(first).getByRole("textbox", { name: "Label (PL)" }), "Otwarte");
    await user.click(screen.getByRole("button", { name: "Create" }));
    expect(await screen.findByText("at /workflow-templates")).toBeInTheDocument();
    const [[url, init]] = postCalls(mockFetch);
    expect(url).toBe("/api/v1/workflow-templates");
    expect(JSON.parse((init as RequestInit).body as string)).toMatchObject({
      name: "Handover",
      description: null,
      statuses: [{ key: "open", label: { en: "Open", pl: "Otwarte" }, initial: true }, { key: "done", label: { en: "Done" } }],
    });
  });

  test("a blank name is refused before any request", async () => {
    const user = userEvent.setup();
    renderCreate();
    await user.click(screen.getByRole("button", { name: "Create" }));
    expect(await screen.findByText("Name must be 1–100 characters")).toBeInTheDocument();
    expect(mockFetch).not.toHaveBeenCalled();
  });

  test("a name clash (409) is marked on the name field and the form stays", async () => {
    mockFetch.mockImplementation(() => Promise.resolve(jsonResponse(409, { title: "Conflict", status: 409 })));
    const user = userEvent.setup();
    renderCreate();
    await user.type(screen.getByRole("textbox", { name: "Name" }), "Default");
    await user.click(screen.getByRole("button", { name: "Create" }));
    expect(await screen.findByText("A workflow template with this name already exists")).toBeInTheDocument();
    await waitFor(() => expect(screen.getByRole("button", { name: "Create" })).toBeEnabled());
    expect(screen.getByRole("heading", { level: 2, name: "New workflow template" })).toBeInTheDocument();
  });

  test("Cancel returns to the list while nothing was typed", async () => {
    const user = userEvent.setup();
    renderCreate();
    await user.click(screen.getByRole("button", { name: "Cancel" }));
    expect(await screen.findByText("at /workflow-templates")).toBeInTheDocument();
  });
});
