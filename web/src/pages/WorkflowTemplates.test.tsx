import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { screen, waitFor, within } from "@testing-library/react";
import { Route, Routes, useLocation } from "react-router-dom";
import WorkflowTemplates from "./WorkflowTemplates";
import { jsonResponse } from "../test/http";
import { renderWithProviders } from "../test/render";
import { pinBrowserTimeZone } from "../test/timeZone";

const TOKEN_KEY = "adder.auth.token";
const ROLES_KEY = "adder.auth.roles";

type FetchMock = ReturnType<typeof vi.fn>;

const PAGE = {
  items: [
    { id: 1, name: "Default", description: "A plain open-to-done flow", statusCount: 3, fieldCount: 0, revision: 1, updatedAt: Date.UTC(2026, 10, 16, 20) },
    { id: 2, name: "Incident", description: null, statusCount: 5, fieldCount: 2, revision: 4, updatedAt: Date.UTC(2026, 10, 17, 20) },
  ],
  page: 1,
  pageSize: 20,
  total: 2,
};

function serve(mockFetch: FetchMock, mutations: Record<string, { status: number; body?: unknown }> = {}) {
  mockFetch.mockImplementation((url: string, init?: RequestInit) => {
    const method = init?.method ?? "GET";
    const mutation = mutations[`${method} ${url}`];
    if (mutation) return Promise.resolve(mutation.body === undefined ? new Response(null, { status: mutation.status }) : jsonResponse(mutation.status, mutation.body));
    if (method === "GET" && url.startsWith("/api/v1/workflow-templates?")) return Promise.resolve(jsonResponse(200, PAGE));
    return Promise.resolve(jsonResponse(404, { title: "x", status: 404 }));
  });
}

const findCall = (mockFetch: FetchMock, method: string, url: string) =>
  mockFetch.mock.calls.find(([u, init]) => ((init as RequestInit | undefined)?.method ?? "GET") === method && u === url);

function Probe() {
  return <p>at {useLocation().pathname}</p>;
}

function renderPage() {
  return renderWithProviders(
    <Routes>
      <Route path="/workflow-templates" element={<WorkflowTemplates />} />
      <Route path="/workflow-templates/new" element={<Probe />} />
      <Route path="/workflow-templates/:id/edit" element={<Probe />} />
    </Routes>,
    { route: "/workflow-templates" },
  );
}

describe("WorkflowTemplates page", () => {
  let mockFetch: FetchMock;
  let zone: ReturnType<typeof pinBrowserTimeZone>;

  beforeEach(() => {
    mockFetch = vi.fn();
    vi.stubGlobal("fetch", mockFetch);
    localStorage.setItem(TOKEN_KEY, "fake-token");
    localStorage.setItem(ROLES_KEY, JSON.stringify(["ADMIN"]));
    zone = pinBrowserTimeZone("Pacific/Auckland");
  });

  afterEach(() => {
    zone.mockRestore();
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  test("lists the templates with their counts and dates, the names linking to the editor", async () => {
    serve(mockFetch);
    renderPage();
    expect(await screen.findByRole("heading", { level: 2, name: "Workflow templates" })).toBeInTheDocument();
    expect(await screen.findByRole("link", { name: "Open workflow template Incident" })).toHaveAttribute("href", "/workflow-templates/2/edit");
    // Updated 2026-11-17T20:00Z: the 18th in the pinned zone (Auckland), the reader's day rather than UTC's.
    const incident = screen.getByRole("row", { name: /Incident/ });
    expect(within(incident).getAllByRole("cell").map((c) => c.textContent)).toEqual(["Incident", "—", "5", "2", "2026-11-18", ""]);
    expect(within(screen.getByRole("row", { name: /Default/ })).getByText("A plain open-to-done flow")).toBeInTheDocument();
    expect(mockFetch.mock.calls[0][0]).toBe("/api/v1/workflow-templates?page=1&pageSize=20&sort=name");
  });

  test("the empty state spans every column", async () => {
    mockFetch.mockImplementation(() => Promise.resolve(jsonResponse(200, { ...PAGE, items: [], total: 0 })));
    renderPage();
    const empty = await screen.findByText("No workflow templates yet");
    expect(empty.closest("td")).toHaveAttribute("colspan", String(screen.getAllByRole("columnheader").length));
  });

  test("New template opens the create page, and a row's Edit opens its editor", async () => {
    serve(mockFetch);
    const user = userEvent.setup();
    renderPage();
    await user.click(await screen.findByRole("button", { name: "Operations for Default" }));
    await user.click(await screen.findByRole("menuitem", { name: "Edit Default" }));
    expect(await screen.findByText("at /workflow-templates/1/edit")).toBeInTheDocument();
  });

  test("the New template action is a link to the create page", async () => {
    serve(mockFetch);
    renderPage();
    expect(await screen.findByRole("link", { name: "New template" })).toHaveAttribute("href", "/workflow-templates/new");
  });

  test("sorting by Updated asks the server for -updatedAt", async () => {
    serve(mockFetch);
    const user = userEvent.setup();
    renderPage();
    await screen.findByText("Incident");
    await user.click(within(screen.getByRole("columnheader", { name: /Updated/ })).getByRole("button"));
    await waitFor(() => expect(mockFetch.mock.calls.some(([u]) => typeof u === "string" && u.includes("sort=updatedAt"))).toBe(true));
  });

  test("the name filter refetches with name= and stops at the server's 100 characters", async () => {
    serve(mockFetch);
    const user = userEvent.setup();
    renderPage();
    await screen.findByText("Incident");
    await user.click(screen.getByRole("button", { name: /filters/i }));
    const filter = screen.getByLabelText("Name");
    expect(filter).toHaveAttribute("maxlength", "100");
    await user.type(filter, "inc");
    await waitFor(() => expect(mockFetch.mock.calls.some(([url]) => typeof url === "string" && url.includes("name=inc"))).toBe(true));
  });

  test("an admin deletes a template after confirming, and a vanished one names it", async () => {
    serve(mockFetch, {
      "DELETE /api/v1/workflow-templates/2": { status: 204 },
      "DELETE /api/v1/workflow-templates/1": { status: 404, body: { title: "Not found", status: 404 } },
    });
    const user = userEvent.setup();
    renderPage();
    await user.click(await screen.findByRole("button", { name: "Operations for Incident" }));
    await user.click(await screen.findByRole("menuitem", { name: "Delete Incident" }));
    expect(await screen.findByText(/"Incident" will be deleted. Rooms already created from it keep their own copy./)).toBeInTheDocument();
    await user.click(within(screen.getByRole("dialog")).getByRole("button", { name: /^delete$/i }));
    await waitFor(() => expect(findCall(mockFetch, "DELETE", "/api/v1/workflow-templates/2")).toBeDefined());
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());

    await user.click(screen.getByRole("button", { name: "Operations for Default" }));
    await user.click(await screen.findByRole("menuitem", { name: "Delete Default" }));
    await user.click(within(await screen.findByRole("dialog")).getByRole("button", { name: /^delete$/i }));
    expect(await screen.findByText("This workflow template was already deleted.")).toBeInTheDocument();
  });

  test("shows the load-failure alert", async () => {
    mockFetch.mockImplementation(() => Promise.resolve(jsonResponse(500, { title: "boom", status: 500 })));
    renderPage();
    expect(await screen.findByText("Could not load the workflow templates")).toBeInTheDocument();
  });

  test("a non-admin session requests nothing", async () => {
    localStorage.setItem(ROLES_KEY, "[]");
    serve(mockFetch);
    renderPage();
    await screen.findByRole("heading", { level: 2, name: "Workflow templates" });
    expect(mockFetch).not.toHaveBeenCalled();
  });
});
