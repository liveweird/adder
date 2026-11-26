import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { act } from "@testing-library/react";
import { QueryClient, useQuery } from "@tanstack/react-query";
import ThreadDetailsForm from "./ThreadDetailsForm";
import { getThread, type ThreadResponse } from "../api/threads";
import { jsonResponse } from "../test/http";
import { makeRoom, makeThread } from "../test/messageFixtures";
import { renderWithProviders, screen, waitFor, within } from "../test/render";
import { SIDE_WORKFLOW } from "../test/sideFixtures";

const STORED = { sev: "s1", note: "db-3", link: "https://example.test/runbook" };

describe("ThreadDetailsForm", () => {
  let mockFetch: ReturnType<typeof vi.fn>;
  let server: ThreadResponse;
  let putStatus: number;
  let putBody: unknown;
  const puts = () => mockFetch.mock.calls.filter(([, init]) => (init as RequestInit | undefined)?.method === "PUT");

  /** The form beside the thread query it normally sits under, so a refetch reaches it as it would in the panel. */
  function Harness({ room = makeRoom(), onDone, workflowRevision = SIDE_WORKFLOW.revision }: { room?: ReturnType<typeof makeRoom>; onDone: () => void; workflowRevision?: number }) {
    const { data } = useQuery({ queryKey: ["rooms", "threads", 12, "detail", 42], queryFn: () => getThread(12, 42) });
    return data ? <ThreadDetailsForm room={room} thread={data} fields={SIDE_WORKFLOW.fields} workflowRevision={workflowRevision} onDone={onDone} /> : null;
  }
  function renderForm(opts: { room?: ReturnType<typeof makeRoom>; queryClient?: QueryClient } = {}) {
    const onDone = vi.fn();
    const queryClient = opts.queryClient ?? new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const { unmount, rerender } = renderWithProviders(<Harness room={opts.room} onDone={onDone} />, { queryClient });
    return { onDone, queryClient, unmount, rerender: (workflowRevision: number) => rerender(<Harness room={opts.room} onDone={onDone} workflowRevision={workflowRevision} />) };
  }
  const refetch = (queryClient: QueryClient) => act(() => queryClient.invalidateQueries({ queryKey: ["rooms", "threads", 12, "detail", 42] }));
  const titleBox = () => screen.findByRole("textbox", { name: /Title/ });

  beforeEach(() => {
    server = makeThread({ number: 42, title: "Disk full", revision: 3, fields: { ...STORED }, fieldUserNames: {} });
    putStatus = 204;
    putBody = undefined;
    mockFetch = vi.fn((url: string, init?: RequestInit) => {
      if (init?.method === "PUT") {
        return Promise.resolve(putStatus === 204 ? new Response(null, { status: 204 }) : jsonResponse(putStatus, putBody));
      }
      if (url === "/api/v1/rooms/12/threads/42") return Promise.resolve(jsonResponse(200, server));
      return Promise.resolve(jsonResponse(200, { items: [], page: 1, pageSize: 50, total: 0 }));
    });
    vi.stubGlobal("fetch", mockFetch);
    localStorage.setItem("adder.auth.token", "fake-token");
  });
  afterEach(() => {
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  test("opens seeded from the thread with the title focused; Save waits for a change", async () => {
    renderForm();
    const title = await titleBox();
    expect(title).toHaveValue("Disk full");
    await waitFor(() => expect(title).toHaveFocus());
    expect(screen.getByRole("combobox", { name: "Severity" })).toHaveValue("S1");
    expect(screen.getByRole("textbox", { name: "Note" })).toHaveValue("db-3");
    expect(screen.getByRole("button", { name: "Save" })).toBeDisabled();
    await userEvent.type(title, "!");
    expect(screen.getByRole("button", { name: "Save" })).toBeEnabled();
  });

  test("saving sends the changed values under X-Expected-Revision, a cleared field as null, then ends the editor once the thread is re-read", async () => {
    const user = userEvent.setup();
    const { onDone } = renderForm();
    const title = await titleBox();
    await user.clear(title);
    await user.type(title, "Disk full on db-3");
    await user.clear(screen.getByRole("textbox", { name: "Note" }));
    await user.click(screen.getByRole("button", { name: "Save" }));
    await waitFor(() => expect(onDone).toHaveBeenCalledTimes(1));
    const [[url, init]] = puts();
    expect(url).toBe("/api/v1/rooms/12/threads/42");
    expect(new Headers((init as RequestInit).headers).get("X-Expected-Revision")).toBe("3");
    expect(new Headers((init as RequestInit).headers).get("X-Mutation-Id")).toMatch(/^[0-9a-f-]{36}$/);
    expect(JSON.parse((init as RequestInit).body as string)).toEqual({ title: "Disk full on db-3", fields: { note: null } });
  });

  test("a bad value is named under its input and nothing is sent", async () => {
    const user = userEvent.setup();
    renderForm();
    const runbook = await screen.findByRole("textbox", { name: "Runbook" });
    await user.clear(runbook);
    await user.type(runbook, "ftp://example.test");
    await user.click(screen.getByRole("button", { name: "Save" }));
    expect(await screen.findByText(/Runbook must be a valid http\(s\) address/)).toBeInTheDocument();
    expect(puts()).toEqual([]);
  });

  test("a 409 revision-conflict keeps the draft and offers Reload, which replaces it with the newer version on click", async () => {
    const user = userEvent.setup();
    renderForm();
    const title = await titleBox();
    await user.type(title, " (mine)");
    putStatus = 409;
    putBody = { type: "urn:adder:problem:revision-conflict", title: "Conflict", status: 409 };
    server = { ...server, title: "Disk full (theirs)", revision: 4 };
    await user.click(screen.getByRole("button", { name: "Save" }));
    expect(await screen.findByText("This thread was changed elsewhere")).toBeInTheDocument();
    expect(screen.getByRole("textbox", { name: /Title/ })).toHaveValue("Disk full (mine)");
    await user.click(screen.getByRole("button", { name: "Reload the latest version" }));
    await waitFor(() => expect(screen.getByRole("textbox", { name: /Title/ })).toHaveValue("Disk full (theirs)"));
    expect(screen.queryByText("This thread was changed elsewhere")).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Save" })).toBeDisabled();
  });

  test("a 409 thread-closed (closed while the form was open) says so, re-reads the thread, and the form becomes a disabled one that keeps the draft", async () => {
    const user = userEvent.setup();
    renderForm();
    const title = await titleBox();
    await user.type(title, " (mine)");
    putStatus = 409;
    putBody = { type: "urn:adder:problem:thread-closed", title: "Closed", status: 409 };
    server = { ...server, closed: true, closedAt: 5, status: "done" };
    await user.click(screen.getByRole("button", { name: "Save" }));
    expect(await screen.findByText("This thread was closed in the meantime, so its details can no longer change. Your changes are kept here.")).toBeInTheDocument();
    await waitFor(() => expect(screen.getByRole("note")).toHaveTextContent("This thread is closed, so its details can no longer change."));
    expect(screen.getByRole("textbox", { name: /Title/ })).toBeDisabled();
    expect(screen.getByRole("textbox", { name: /Title/ })).toHaveValue("Disk full (mine)");
    expect(screen.getByRole("button", { name: "Save" })).toBeDisabled();
  });

  test("a closed thread or an archived room opens the form disabled, under the note", async () => {
    server = { ...server, closed: true, closedAt: 5, status: "done" };
    const { unmount } = renderForm();
    expect(await titleBox()).toBeDisabled();
    expect(screen.getByRole("note")).toHaveTextContent("This thread is closed");
    unmount();
    server = { ...server, closed: false, closedAt: null, status: "triage" };
    renderForm({ room: makeRoom({ archived: true }) });
    expect(await titleBox()).toBeDisabled();
    expect(screen.getByRole("note")).toHaveTextContent("This room is archived");
  });

  test("a refetch that moves the status (the revision does not) never wipes a dirty draft; a bumped revision under a clean form re-seeds it", async () => {
    const user = userEvent.setup();
    const { queryClient } = renderForm();
    const title = await titleBox();
    await user.type(title, " (mine)");
    server = { ...server, status: "working", ownerId: 9, ownerName: "Bob", messageCount: 9 };
    await refetch(queryClient);
    expect(screen.getByRole("textbox", { name: /Title/ })).toHaveValue("Disk full (mine)");
    expect(screen.queryByText("This thread was changed elsewhere")).not.toBeInTheDocument();
    // Undo the edit by hand: the form is clean again, so a newer revision now re-seeds it.
    await user.clear(screen.getByRole("textbox", { name: /Title/ }));
    await user.type(screen.getByRole("textbox", { name: /Title/ }), "Disk full");
    server = { ...server, title: "Renamed by someone", revision: 4 };
    await refetch(queryClient);
    await waitFor(() => expect(screen.getByRole("textbox", { name: /Title/ })).toHaveValue("Renamed by someone"));
  });

  test("a newer revision under a DIRTY form keeps the draft and shows the conflict alert at once", async () => {
    const user = userEvent.setup();
    const { queryClient } = renderForm();
    await user.type(await titleBox(), " (mine)");
    server = { ...server, title: "Renamed by someone", revision: 4 };
    await refetch(queryClient);
    expect(await screen.findByText("This thread was changed elsewhere")).toBeInTheDocument();
    expect(screen.getByRole("textbox", { name: /Title/ })).toHaveValue("Disk full (mine)");
  });

  test("Cancel on a clean form leaves at once; on a dirty one it asks, and Keep editing keeps the draft", async () => {
    const user = userEvent.setup();
    const { onDone } = renderForm();
    await user.click(await screen.findByRole("button", { name: "Cancel" }));
    expect(onDone).toHaveBeenCalledTimes(1);
    await user.type(screen.getByRole("textbox", { name: /Title/ }), "!");
    await user.click(screen.getByRole("button", { name: "Cancel" }));
    const dialog = await screen.findByRole("dialog", { name: "Discard changes?" });
    await user.click(within(dialog).getByRole("button", { name: "Keep editing" }));
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    expect(onDone).toHaveBeenCalledTimes(1);
    expect(screen.getByRole("textbox", { name: /Title/ })).toHaveValue("Disk full!");
    await user.click(screen.getByRole("button", { name: "Cancel" }));
    await user.click(within(await screen.findByRole("dialog", { name: "Discard changes?" })).getByRole("button", { name: "Discard" }));
    expect(onDone).toHaveBeenCalledTimes(2);
  });

  test("a reload or close warns while the draft differs, and not once it is clean", async () => {
    const user = userEvent.setup();
    renderForm();
    const title = await titleBox();
    const attempt = () => {
      const event = new Event("beforeunload", { cancelable: true });
      window.dispatchEvent(event);
      return event.defaultPrevented;
    };
    expect(attempt()).toBe(false);
    await user.type(title, "!");
    expect(attempt()).toBe(true);
    await user.type(title, "{Backspace}");
    expect(attempt()).toBe(false);
  });

  test("another refusal (a 400) shows under the form with the server's own sentence and keeps the draft", async () => {
    const user = userEvent.setup();
    renderForm();
    await user.type(await titleBox(), "!");
    putStatus = 400;
    putBody = { title: "Bad", status: 400, detail: "Unknown field 'x'" };
    await user.click(screen.getByRole("button", { name: "Save" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("The details could not be saved: one or more values are invalid (Unknown field 'x')");
    expect(screen.getByRole("textbox", { name: /Title/ })).toHaveValue("Disk full!");
  });

  test("a save narrows its refresh to the thread's queries and the rail, and a save in flight makes the inputs read-only, not disabled", async () => {
    const user = userEvent.setup();
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const invalidate = vi.spyOn(queryClient, "invalidateQueries");
    let answer!: (response: Response) => void;
    mockFetch.mockImplementation((url: string, init?: RequestInit) => {
      if (init?.method === "PUT") return new Promise<Response>((resolve) => (answer = resolve));
      if (url === "/api/v1/rooms/12/threads/42") return Promise.resolve(jsonResponse(200, server));
      return Promise.resolve(jsonResponse(200, {}));
    });
    renderForm({ queryClient });
    const title = await titleBox();
    await user.type(title, "!");
    await user.click(screen.getByRole("button", { name: "Save" }));
    await waitFor(() => expect(puts()).toHaveLength(1));
    // The caret stays: nothing is disabled, the inputs only refuse edits.
    expect(title).not.toBeDisabled();
    expect(title).toHaveAttribute("readonly");
    expect(screen.getByRole("textbox", { name: "Note" })).toHaveAttribute("readonly");
    expect(screen.getByRole("combobox", { name: "Severity" })).not.toBeDisabled();
    answer(new Response(null, { status: 204 }));
    await waitFor(() => expect(invalidate).toHaveBeenCalledWith({ queryKey: ["rooms", "rail"] }));
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ["rooms", "threads", 12] });
    expect(invalidate).not.toHaveBeenCalledWith({ queryKey: ["rooms"] });
  });

  test("a save the form's own rules refuse puts the caret on the first input with an error", async () => {
    const user = userEvent.setup();
    renderForm();
    const runbook = await screen.findByRole("textbox", { name: "Runbook" });
    await user.clear(runbook);
    await user.type(runbook, "ftp://example.test");
    await user.click(screen.getByRole("button", { name: "Save" }));
    await waitFor(() => expect(runbook).toHaveFocus());
    const title = screen.getByRole("textbox", { name: /Title/ });
    await user.clear(title);
    await user.click(screen.getByRole("button", { name: "Save" }));
    await waitFor(() => expect(title).toHaveFocus());
  });

  test("a save the server refuses puts the caret back on the title", async () => {
    const user = userEvent.setup();
    renderForm();
    const title = await titleBox();
    await user.type(title, "!");
    putStatus = 400;
    putBody = { title: "Bad", status: 400 };
    await user.click(screen.getByRole("button", { name: "Save" }));
    await screen.findByRole("alert");
    await waitFor(() => expect(title).toHaveFocus());
  });

  test("the room's fields changing under a dirty draft says so, keeps the draft, and Reload picks up the current fields", async () => {
    const user = userEvent.setup();
    const { rerender } = renderForm();
    const title = await titleBox();
    await user.type(title, " (mine)");
    expect(screen.queryByText("The room's fields were changed")).not.toBeInTheDocument();
    rerender(SIDE_WORKFLOW.revision + 1);
    expect(await screen.findByText("The room's fields were changed")).toBeInTheDocument();
    expect(screen.getByRole("textbox", { name: /Title/ })).toHaveValue("Disk full (mine)");
    await user.click(screen.getByRole("button", { name: "Reload the latest version" }));
    await waitFor(() => expect(screen.queryByText("The room's fields were changed")).not.toBeInTheDocument());
    expect(screen.getByRole("textbox", { name: /Title/ })).toHaveValue("Disk full");
  });
});
