import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { screen, waitFor, within } from "@testing-library/react";
import { jsonResponse } from "../test/http";
import { headingOutline } from "../test/headings";
import { renderWithProviders } from "../test/render";
import Tags from "./Tags";

const REGISTRY = {
  items: [
    { id: 1, name: "Kind", description: "What sort of thread", revision: 3, tags: ["decision", "question"] },
    { id: 2, name: "Area", description: null, revision: 5, tags: ["security"] },
  ],
};
const REVISION_CONFLICT = { type: "urn:adder:problem:revision-conflict", title: "Conflict", status: 409 };

type FetchMock = ReturnType<typeof vi.fn>;
type Mutations = { POST?: () => Response; PUT?: () => Response; DELETE?: () => Response };

function serve(mockFetch: FetchMock, items = REGISTRY.items, mutations: Mutations = {}) {
  mockFetch.mockImplementation((url: string, init?: RequestInit) => {
    const method = (init?.method ?? "GET") as "GET" | "POST" | "PUT" | "DELETE";
    if (method === "POST") return Promise.resolve((mutations.POST ?? (() => jsonResponse(201, { id: 9, name: "New", description: null, revision: 1, tags: ["x"] })))());
    if (method === "PUT") return Promise.resolve((mutations.PUT ?? (() => new Response(null, { status: 204 })))());
    if (method === "DELETE") return Promise.resolve((mutations.DELETE ?? (() => new Response(null, { status: 204 })))());
    return Promise.resolve(url === "/api/v1/tag-categories" ? jsonResponse(200, { items }) : jsonResponse(404, { title: "x", status: 404 }));
  });
}

function call(mockFetch: FetchMock, method: string, url: string) {
  const found = mockFetch.mock.calls.find(([u, init]) => ((init as RequestInit | undefined)?.method ?? "GET") === method && u === url);
  if (!found) return undefined;
  const init = found[1] as RequestInit;
  return { body: init.body ? JSON.parse(init.body as string) : undefined, revision: new Headers(init.headers).get("X-Expected-Revision") };
}

const reads = (mockFetch: FetchMock) => mockFetch.mock.calls.filter(([u, init]) => u === "/api/v1/tag-categories" && !(init as RequestInit | undefined)?.method).length;

describe("Tags page", () => {
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

  test("lists every category with its description and its tags as pills", async () => {
    serve(mockFetch);
    renderWithProviders(<Tags />);
    const row = (await screen.findByText("Kind")).closest("tr")!;
    expect(within(row).getByText("What sort of thread")).toBeInTheDocument();
    expect(within(row).getByText("decision")).toBeInTheDocument();
    expect(within(row).getByText("question")).toBeInTheDocument();
    const area = screen.getByText("Area").closest("tr")!;
    expect(within(area).getByText("—")).toBeInTheDocument();
    expect(screen.getByRole("table", { name: "Tags" })).toBeInTheDocument();
    expect(headingOutline()).toEqual([[2, "Tags"]]);
  });

  test("an empty registry and a failed load say so", async () => {
    serve(mockFetch, []);
    const { unmount } = renderWithProviders(<Tags />);
    expect(await screen.findByText("No tag categories yet")).toBeInTheDocument();
    unmount();
    mockFetch.mockImplementation(() => Promise.resolve(new Response(null, { status: 500 })));
    renderWithProviders(<Tags />);
    expect(await screen.findByText("Could not load the tag categories")).toBeInTheDocument();
    expect(screen.queryByText("No tag categories yet")).not.toBeInTheDocument();
  });

  test("a category is created with normalised tags and the registry refreshes", async () => {
    serve(mockFetch);
    const user = userEvent.setup();
    renderWithProviders(<Tags />);
    await user.click(await screen.findByRole("button", { name: "New category" }));
    const dialog = await screen.findByRole("dialog");
    await user.type(within(dialog).getByLabelText("Name"), "Concern");
    await user.type(within(dialog).getByLabelText("Description"), " cross-cutting ");
    await user.type(within(dialog).getByRole("combobox", { name: "Tags" }), "Cost  Control,Latency,");
    await user.click(within(dialog).getByRole("button", { name: "Create" }));
    await waitFor(() => expect(call(mockFetch, "POST", "/api/v1/tag-categories")).toBeDefined());
    expect(call(mockFetch, "POST", "/api/v1/tag-categories")?.body).toEqual({ name: "Concern", description: "cross-cutting", tags: ["cost control", "latency"] });
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    await waitFor(() => expect(reads(mockFetch)).toBeGreaterThan(1));
  });

  test("an invalid form is blocked on the client with the fields' words", async () => {
    serve(mockFetch);
    const user = userEvent.setup();
    renderWithProviders(<Tags />);
    await user.click(await screen.findByRole("button", { name: "New category" }));
    const dialog = await screen.findByRole("dialog");
    await user.click(within(dialog).getByRole("button", { name: "Create" }));
    expect(await within(dialog).findByText("Name must be 1–60 characters")).toBeInTheDocument();
    expect(within(dialog).getByText("Add at least one tag")).toBeInTheDocument();
    expect(call(mockFetch, "POST", "/api/v1/tag-categories")).toBeUndefined();
    await user.click(within(dialog).getByRole("button", { name: "Cancel" }));
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
  });

  test("an edit starts from the category and PUTs the whole replacement under the loaded revision", async () => {
    serve(mockFetch);
    const user = userEvent.setup();
    renderWithProviders(<Tags />);
    await user.click(await screen.findByRole("button", { name: "Operations for Kind" }));
    await user.click(await screen.findByRole("menuitem", { name: "Edit Kind" }));
    const dialog = await screen.findByRole("dialog");
    expect(within(dialog).getByLabelText("Name")).toHaveValue("Kind");
    await user.type(within(dialog).getByRole("combobox", { name: "Tags" }), "Ops{Enter}");
    await user.click(within(dialog).getByRole("button", { name: "Save" }));
    await waitFor(() => expect(call(mockFetch, "PUT", "/api/v1/tag-categories/1")).toBeDefined());
    expect(call(mockFetch, "PUT", "/api/v1/tag-categories/1")?.body).toEqual({
      name: "Kind",
      description: "What sort of thread",
      tags: ["decision", "question", "ops"],
    });
    expect(call(mockFetch, "PUT", "/api/v1/tag-categories/1")?.revision).toBe("3");
  });

  test("a stale edit is refused in words, keeps the dialog and re-reads the registry", async () => {
    serve(mockFetch, REGISTRY.items, { PUT: () => jsonResponse(409, REVISION_CONFLICT) });
    const user = userEvent.setup();
    renderWithProviders(<Tags />);
    await user.click(await screen.findByRole("button", { name: "Operations for Kind" }));
    await user.click(await screen.findByRole("menuitem", { name: "Edit Kind" }));
    const dialog = await screen.findByRole("dialog");
    const before = reads(mockFetch);
    await user.click(within(dialog).getByRole("button", { name: "Save" }));
    expect(await within(dialog).findByText(/Another administrator changed this category after you opened it/)).toBeInTheDocument();
    expect(within(dialog).queryByText("The category name or one of its tags is already in use")).not.toBeInTheDocument();
    await waitFor(() => expect(reads(mockFetch)).toBeGreaterThan(before));
  });

  test("a refused save keeps the dialog open with the fixed words", async () => {
    serve(mockFetch, REGISTRY.items, { POST: () => jsonResponse(409, { title: "Conflict", status: 409 }) });
    const user = userEvent.setup();
    renderWithProviders(<Tags />);
    await user.click(await screen.findByRole("button", { name: "New category" }));
    const dialog = await screen.findByRole("dialog");
    await user.type(within(dialog).getByLabelText("Name"), "Kind");
    await user.type(within(dialog).getByRole("combobox", { name: "Tags" }), "decision{Enter}");
    await user.click(within(dialog).getByRole("button", { name: "Create" }));
    expect(await within(dialog).findByText("The category name or one of its tags is already in use")).toBeInTheDocument();
    expect(screen.getByRole("dialog")).toBeInTheDocument();
  });

  test("deleting asks first, says what happens to the tags and then DELETEs under the loaded revision", async () => {
    serve(mockFetch);
    const user = userEvent.setup();
    renderWithProviders(<Tags />);
    await user.click(await screen.findByRole("button", { name: "Operations for Area" }));
    await user.click(await screen.findByRole("menuitem", { name: "Delete Area" }));
    const dialog = await screen.findByRole("dialog", { name: "Delete tag category?" });
    expect(within(dialog).getByText(/keep them, marked as retired/)).toBeInTheDocument();
    await user.click(within(dialog).getByRole("button", { name: "Delete" }));
    await waitFor(() => expect(call(mockFetch, "DELETE", "/api/v1/tag-categories/2")).toBeDefined());
    expect(call(mockFetch, "DELETE", "/api/v1/tag-categories/2")?.revision).toBe("5");
  });

  test("a stale delete deletes nothing, says why and re-reads the registry", async () => {
    serve(mockFetch, REGISTRY.items, { DELETE: () => jsonResponse(409, REVISION_CONFLICT) });
    const user = userEvent.setup();
    renderWithProviders(<Tags />);
    await user.click(await screen.findByRole("button", { name: "Operations for Area" }));
    await user.click(await screen.findByRole("menuitem", { name: "Delete Area" }));
    const before = reads(mockFetch);
    await user.click(within(await screen.findByRole("dialog")).getByRole("button", { name: "Delete" }));
    expect(await screen.findByText(/was changed after you opened it, so it was not deleted/)).toBeInTheDocument();
    await waitFor(() => expect(reads(mockFetch)).toBeGreaterThan(before));
  });

  test("a failed delete reports in the dialog", async () => {
    serve(mockFetch, REGISTRY.items, { DELETE: () => jsonResponse(404, { title: "Not found", status: 404 }) });
    const user = userEvent.setup();
    renderWithProviders(<Tags />);
    await user.click(await screen.findByRole("button", { name: "Operations for Area" }));
    await user.click(await screen.findByRole("menuitem", { name: "Delete Area" }));
    await user.click(within(await screen.findByRole("dialog")).getByRole("button", { name: "Delete" }));
    expect(await screen.findByText("This category no longer exists — reload the page")).toBeInTheDocument();
  });
});
