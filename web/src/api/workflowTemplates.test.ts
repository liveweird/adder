import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import {
  createWorkflowTemplate,
  deleteWorkflowTemplate,
  getWorkflowTemplate,
  listWorkflowTemplates,
  updateWorkflowTemplate,
  type WorkflowTemplateUpdateBody,
} from "./workflowTemplates";

const BODY: WorkflowTemplateUpdateBody = {
  name: "Incident",
  description: null,
  statuses: [{ key: "open", label: { en: "Open" }, colour: "BLUE", category: "OPEN", initial: true }],
  fields: [],
};

describe("workflow templates API wrappers", () => {
  let mockFetch: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    mockFetch = vi.fn();
    vi.stubGlobal("fetch", mockFetch);
    localStorage.setItem("adder.auth.token", "fake-token");
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  const lastCall = () => mockFetch.mock.calls.at(-1) as [string, RequestInit];
  const json = (status: number, body: unknown) =>
    new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });

  test("listWorkflowTemplates sends paging, sort and the name filter, and omits an empty filter", async () => {
    mockFetch.mockImplementation(() => Promise.resolve(json(200, { items: [], page: 1, pageSize: 20, total: 0 })));
    await listWorkflowTemplates({ page: 2, pageSize: 40, sort: "-updatedAt", name: "inc" });
    expect(lastCall()[0]).toBe("/api/v1/workflow-templates?page=2&pageSize=40&sort=-updatedAt&name=inc");
    await listWorkflowTemplates({ page: 1, pageSize: 20 });
    expect(lastCall()[0]).toBe("/api/v1/workflow-templates?page=1&pageSize=20");
  });

  test("getWorkflowTemplate reads the template by id", async () => {
    mockFetch.mockResolvedValue(json(200, { id: 3, revision: 4 }));
    await expect(getWorkflowTemplate(3)).resolves.toMatchObject({ id: 3, revision: 4 });
    expect(lastCall()[0]).toBe("/api/v1/workflow-templates/3");
  });

  test("createWorkflowTemplate POSTs the body", async () => {
    mockFetch.mockResolvedValue(json(201, { id: 9 }));
    await createWorkflowTemplate(BODY);
    const [url, init] = lastCall();
    expect(url).toBe("/api/v1/workflow-templates");
    expect(init.method).toBe("POST");
    expect(JSON.parse(init.body as string)).toEqual(BODY);
  });

  test("updateWorkflowTemplate PUTs the whole document under X-Expected-Revision", async () => {
    mockFetch.mockResolvedValue(new Response(null, { status: 204 }));
    await updateWorkflowTemplate(3, BODY, 7);
    const [url, init] = lastCall();
    expect(url).toBe("/api/v1/workflow-templates/3");
    expect(init.method).toBe("PUT");
    expect(new Headers(init.headers).get("X-Expected-Revision")).toBe("7");
    expect(JSON.parse(init.body as string)).toEqual(BODY);
  });

  test("a stale revision rejects with the typed 409", async () => {
    mockFetch.mockResolvedValue(json(409, { type: "urn:adder:problem:revision-conflict", status: 409 }));
    await expect(updateWorkflowTemplate(3, BODY, 1)).rejects.toMatchObject({ status: 409, type: "urn:adder:problem:revision-conflict" });
  });

  test("deleteWorkflowTemplate DELETEs the template", async () => {
    mockFetch.mockResolvedValue(new Response(null, { status: 204 }));
    await deleteWorkflowTemplate(3);
    const [url, init] = lastCall();
    expect(url).toBe("/api/v1/workflow-templates/3");
    expect(init.method).toBe("DELETE");
  });
});
