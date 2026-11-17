import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { screen, waitFor, within } from "@testing-library/react";
import { QueryClient } from "@tanstack/react-query";
import { Route, Routes, useLocation } from "react-router-dom";
import EditWorkflowTemplate from "./EditWorkflowTemplate";
import { jsonResponse } from "../test/http";
import { renderWithProviders } from "../test/render";

type FetchMock = ReturnType<typeof vi.fn>;

const TEMPLATE = {
  id: 4,
  name: "Incident",
  description: "Major incidents",
  statuses: [
    { key: "open", label: { en: "Open", pl: "Otwarte" }, colour: "RED", category: "OPEN", initial: true },
    { key: "done", label: { en: "Done" }, colour: "TEAL", category: "CLOSED", initial: false },
  ],
  fields: [{ key: "sev", label: { en: "Severity" }, type: "SELECT", required: true, options: [{ key: "s1", label: { en: "S1" } }] }],
  revision: 3,
  createdAt: 1,
  updatedAt: 2,
};

function Probe() {
  return <p>at {useLocation().pathname}</p>;
}

function renderEdit(route = "/workflow-templates/4/edit", queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })) {
  return renderWithProviders(
    <Routes>
      <Route path="/workflow-templates/:id/edit" element={<EditWorkflowTemplate />} />
      <Route path="/workflow-templates" element={<Probe />} />
    </Routes>,
    { route, queryClient },
  );
}

const putCalls = (mockFetch: FetchMock) => mockFetch.mock.calls.filter(([, init]) => (init as RequestInit | undefined)?.method === "PUT");
const statusRow = (n: number) => screen.getByRole("group", { name: `Status ${n}` });

describe("EditWorkflowTemplate page", () => {
  let mockFetch: FetchMock;
  // The template the server currently holds; a test bumps it to play "someone else saved".
  let server: typeof TEMPLATE;
  let putStatus: number;
  let putBody: unknown;

  beforeEach(() => {
    server = TEMPLATE;
    putStatus = 204;
    putBody = undefined;
    mockFetch = vi.fn((url: string, init?: RequestInit) => {
      const method = init?.method ?? "GET";
      if (method === "GET" && url === "/api/v1/workflow-templates/4") return Promise.resolve(jsonResponse(200, server));
      if (method === "GET" && url.startsWith("/api/v1/workflow-templates?")) return Promise.resolve(jsonResponse(200, { items: [], page: 1, pageSize: 20, total: 0 }));
      if (method === "PUT" && url === "/api/v1/workflow-templates/4") {
        return Promise.resolve(putBody === undefined ? new Response(null, { status: putStatus }) : jsonResponse(putStatus, putBody));
      }
      return Promise.resolve(jsonResponse(404, { title: "x", status: 404 }));
    });
    vi.stubGlobal("fetch", mockFetch);
    localStorage.setItem("adder.auth.token", "fake-token");
    localStorage.setItem("adder.auth.roles", JSON.stringify(["ADMIN"]));
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  test("loads the template into the form: its text, both label languages and Save disabled until a change", async () => {
    renderEdit();
    expect(await screen.findByRole("textbox", { name: "Name" })).toHaveValue("Incident");
    expect(screen.getByRole("heading", { level: 2, name: "Edit workflow template" })).toBeInTheDocument();
    expect(within(statusRow(1)).getByRole("textbox", { name: "Label (PL)" })).toHaveValue("Otwarte");
    // A persisted row's key is read-only.
    expect(within(statusRow(1)).getByRole("textbox", { name: "Key" })).toHaveAttribute("readonly");
    expect(screen.getByRole("button", { name: "Save" })).toBeDisabled();
  });

  test("saves the whole document under the revision it was read at, then returns to the list", async () => {
    const user = userEvent.setup();
    renderEdit();
    await user.type(await screen.findByRole("textbox", { name: "Description" }), " and outages");
    await user.type(within(statusRow(2)).getByRole("textbox", { name: "Label (PL)" }), "Gotowe");
    await user.click(screen.getByRole("button", { name: "Save" }));
    expect(await screen.findByText("at /workflow-templates")).toBeInTheDocument();
    const [[, init]] = putCalls(mockFetch);
    expect(new Headers((init as RequestInit).headers).get("X-Expected-Revision")).toBe("3");
    expect(JSON.parse((init as RequestInit).body as string)).toMatchObject({
      name: "Incident",
      description: "Major incidents and outages",
      statuses: [{ key: "open", label: { en: "Open", pl: "Otwarte" } }, { key: "done", label: { en: "Done", pl: "Gotowe" } }],
      fields: [{ key: "sev", type: "SELECT", options: [{ key: "s1", label: { en: "S1" } }] }],
    });
  });

  test("leaving after a save does not wait on a refetch of the detail", async () => {
    const user = userEvent.setup();
    renderEdit();
    await user.type(await screen.findByRole("textbox", { name: "Name" }), "!");
    const detailReads = () => mockFetch.mock.calls.filter(([url, init]) => url === "/api/v1/workflow-templates/4" && !(init as RequestInit | undefined)?.method).length;
    expect(detailReads()).toBe(1);
    // From here a detail read never answers: were Save to wait on one, the page would never leave.
    const answer = mockFetch.getMockImplementation() as (url: string, init?: RequestInit) => Promise<Response>;
    mockFetch.mockImplementation((url: string, init?: RequestInit) =>
      url === "/api/v1/workflow-templates/4" && !init?.method ? new Promise<Response>(() => {}) : answer(url, init),
    );
    await user.click(screen.getByRole("button", { name: "Save" }));
    expect(await screen.findByText("at /workflow-templates")).toBeInTheDocument();
    expect(detailReads()).toBe(1);
  });

  test("a save refused as gone (404) says the template no longer exists and keeps the draft", async () => {
    putStatus = 404;
    putBody = { title: "Not found", status: 404 };
    const user = userEvent.setup();
    renderEdit();
    await user.type(await screen.findByRole("textbox", { name: "Name" }), "!");
    await user.click(screen.getByRole("button", { name: "Save" }));
    expect(await screen.findByText("This workflow template no longer exists.")).toBeInTheDocument();
    expect(screen.getByRole("textbox", { name: "Name" })).toHaveValue("Incident!");
    expect(screen.getByRole("button", { name: "Save" })).toBeEnabled();
  });

  test("a save the server rejects (400) gets neutral copy that does not point at highlighted fields", async () => {
    putStatus = 400;
    putBody = { title: "Bad request", status: 400 };
    const user = userEvent.setup();
    renderEdit();
    await user.type(await screen.findByRole("textbox", { name: "Name" }), "!");
    await user.click(screen.getByRole("button", { name: "Save" }));
    expect(await screen.findByText("The server rejected this template. Review its contents and try again.")).toBeInTheDocument();
    expect(screen.queryByText(/highlighted/)).not.toBeInTheDocument();
  });

  test("a stale save keeps the draft; Reload re-seeds the form from the newer version", async () => {
    putStatus = 409;
    putBody = { type: "urn:adder:problem:revision-conflict", title: "Conflict", status: 409 };
    const user = userEvent.setup();
    renderEdit();
    await user.type(await screen.findByRole("textbox", { name: "Name" }), " draft");
    await user.click(screen.getByRole("button", { name: "Save" }));
    expect(await screen.findByText("This workflow was changed elsewhere")).toBeInTheDocument();
    expect(screen.getByRole("textbox", { name: "Name" })).toHaveValue("Incident draft");

    // Someone else saved revision 4 meanwhile; Reload shows it and the next save carries 4.
    server = { ...TEMPLATE, name: "Incident (theirs)", revision: 4 };
    await user.click(screen.getByRole("button", { name: "Reload the latest version" }));
    await waitFor(() => expect(screen.getByRole("textbox", { name: "Name" })).toHaveValue("Incident (theirs)"));
    expect(screen.queryByText("This workflow was changed elsewhere")).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Save" })).toBeDisabled();

    putStatus = 204;
    putBody = undefined;
    await user.type(screen.getByRole("textbox", { name: "Name" }), "!");
    await user.click(screen.getByRole("button", { name: "Save" }));
    expect(await screen.findByText("at /workflow-templates")).toBeInTheDocument();
    expect(new Headers((putCalls(mockFetch).at(-1)![1] as RequestInit).headers).get("X-Expected-Revision")).toBe("4");
  });

  test("a background refetch never rebases the draft", async () => {
    const user = userEvent.setup();
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    renderEdit("/workflow-templates/4/edit", queryClient);
    await user.type(await screen.findByRole("textbox", { name: "Name" }), " draft");
    server = { ...TEMPLATE, name: "Incident (theirs)", revision: 9 };
    await queryClient.refetchQueries({ queryKey: ["workflowTemplates", "detail", 4] });
    expect(screen.getByRole("textbox", { name: "Name" })).toHaveValue("Incident draft");
  });

  test("an unknown template shows the not-found state under the page title", async () => {
    mockFetch.mockImplementation(() => Promise.resolve(jsonResponse(404, { title: "Not found", status: 404 })));
    renderEdit();
    expect(await screen.findByText("This workflow template does not exist (or was deleted).")).toBeInTheDocument();
    expect(screen.getByRole("heading", { level: 2, name: "Edit workflow template" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Back to workflow templates" })).toHaveAttribute("href", "/workflow-templates");
  });

  test("any other load failure is status-tagged", async () => {
    mockFetch.mockImplementation(() => Promise.resolve(jsonResponse(500, { title: "boom", status: 500 })));
    renderEdit();
    expect(await screen.findByText("Load failed (500)")).toBeInTheDocument();
  });

  test("a non-numeric id goes back to the list", async () => {
    renderEdit("/workflow-templates/abc/edit");
    expect(await screen.findByText("at /workflow-templates")).toBeInTheDocument();
    expect(mockFetch).not.toHaveBeenCalled();
  });
});
