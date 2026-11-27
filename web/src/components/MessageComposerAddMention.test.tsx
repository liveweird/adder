import { useState } from "react";
import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { QueryClient } from "@tanstack/react-query";
import { fireEvent, screen, waitFor } from "@testing-library/react";
import { clearSession } from "../api/session";
import MessageComposer from "./MessageComposer";
import ThreadDraftsProvider from "./ThreadDraftsProvider";
import { jsonResponse } from "../test/http";
import { makeRoom, makeThread } from "../test/messageFixtures";
import { renderWithProviders } from "../test/render";
import { createKnownNames, KnownNamesContext } from "../utils/knownNames";
import { MAX_MESSAGE_BODY_LENGTH } from "../utils/threadForm";

describe("MessageComposer add and mention", () => {
  let mockFetch: ReturnType<typeof vi.fn>;
  const posts = () => mockFetch.mock.calls.filter(([, init]) => (init as RequestInit | undefined)?.method === "POST") as [string, RequestInit][];
  const box = () => screen.getByRole("textbox", { name: "Reply" });

  beforeEach(() => {
    mockFetch = vi.fn();
    vi.stubGlobal("fetch", mockFetch);
    localStorage.setItem("adder.auth.token", "fake-token");
  });
  afterEach(() => {
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  const renderComposer = (queryClient?: QueryClient) => {
    const names = createKnownNames();
    return renderWithProviders(
      <KnownNamesContext.Provider value={names}>
        <ThreadDraftsProvider>
          <MessageComposer room={makeRoom({ role: "OWNER" })} thread={makeThread()} onPosted={vi.fn()} />
        </ThreadDraftsProvider>
      </KnownNamesContext.Provider>,
      { queryClient },
    );
  };

  function directory(status: number, body: unknown) {
    mockFetch.mockImplementation((url: string, init?: RequestInit) => {
      if (url.startsWith("/api/v1/users/directory?")) {
        return Promise.resolve(jsonResponse(200, {
          items: [{ id: 91, name: "New Person", email: "new@example.test" }], page: 1, pageSize: 20, total: 1,
        }));
      }
      if (url === "/api/v1/rooms/12/members" && init?.method === "POST") return Promise.resolve(jsonResponse(status, body));
      return Promise.resolve(jsonResponse(200, { items: [], page: 1, pageSize: 8, total: 0 }));
    });
  }

  async function pick(user: ReturnType<typeof userEvent.setup>) {
    await user.click(screen.getByRole("button", { name: "Add and mention" }));
    await user.type(screen.getByRole("combobox", { name: "User" }), "New");
    await user.click(await screen.findByText("New Person (new@example.test)"));
    await user.click(screen.getAllByRole("button", { name: "Add and mention" }).at(-1)!);
  }

  test("a failed room addition keeps the modal selection and unchanged draft", async () => {
    directory(500, { title: "Boom", status: 500 });
    const user = userEvent.setup();
    renderComposer();
    await user.type(box(), "Unchanged draft");
    await pick(user);
    expect(await screen.findByText("Action failed (500)")).toBeInTheDocument();
    expect(screen.getByRole("dialog", { name: "Add someone to this room" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Close" })).toBeInTheDocument();
    expect(screen.getByText("New Person (new@example.test)")).toBeInTheDocument();
    expect(box()).toHaveValue("Unchanged draft");
  });

  test("cancelling without adding does not redirect focus into the composer", async () => {
    directory(200, { added: 1, alreadyMembers: 0 });
    const user = userEvent.setup();
    renderComposer();
    const trigger = screen.getByRole("button", { name: "Add and mention" });
    await user.click(trigger);
    await user.click(screen.getByRole("button", { name: "Cancel" }));
    await waitFor(() => expect(screen.queryByRole("dialog", { name: "Add someone to this room" })).not.toBeInTheDocument());
    expect(box()).not.toHaveFocus();
  });

  test("the localized close control dismisses an idle dialog", async () => {
    directory(200, { added: 1, alreadyMembers: 0 });
    const user = userEvent.setup();
    renderComposer();
    await user.click(screen.getByRole("button", { name: "Add and mention" }));
    await user.click(screen.getByRole("button", { name: "Close" }));
    await waitFor(() => expect(screen.queryByRole("dialog", { name: "Add someone to this room" })).not.toBeInTheDocument());
  });

  test("a lost manager role keeps the draft and refreshes room authorization", async () => {
    directory(403, { title: "Forbidden", status: 403 });
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const invalidate = vi.spyOn(queryClient, "invalidateQueries");
    const user = userEvent.setup();
    renderComposer(queryClient);
    await user.type(box(), "Still mine");
    await pick(user);
    expect(await screen.findByText("Only the room's owners and administrators can add members.")).toBeInTheDocument();
    expect(box()).toHaveValue("Still mine");
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ["rooms"] });
  });

  test("the dialog and submission stay blocked while membership is being saved", async () => {
    let answer!: (response: Response) => void;
    directory(200, { added: 1, alreadyMembers: 0 });
    const implementation = mockFetch.getMockImplementation() as (url: string, init?: RequestInit) => Promise<Response>;
    mockFetch.mockImplementation((url: string, init?: RequestInit) => {
      if (url === "/api/v1/rooms/12/members" && init?.method === "POST") return new Promise<Response>((resolve) => (answer = resolve));
      return implementation(url, init);
    });
    const user = userEvent.setup();
    renderComposer();
    await user.type(box(), "Waiting");
    await pick(user);
    const dialog = screen.getByRole("dialog", { name: "Add someone to this room" });
    await waitFor(() => expect(posts()).toHaveLength(1));
    expect(screen.getByRole("button", { name: "Send" })).toBeDisabled();
    fireEvent.submit(box().closest("form")!);
    expect(posts()).toHaveLength(1);
    await user.keyboard("{Escape}");
    expect(dialog).toBeInTheDocument();
    expect(box()).toHaveValue("Waiting");
    answer(jsonResponse(200, { added: 1, alreadyMembers: 0 }));
    await waitFor(() => expect(dialog).not.toBeInTheDocument());
  });

  test("a successful add that finishes after navigation persists the mention in the thread draft", async () => {
    let answer!: (response: Response) => void;
    directory(200, { added: 1, alreadyMembers: 0 });
    const implementation = mockFetch.getMockImplementation() as (url: string, init?: RequestInit) => Promise<Response>;
    mockFetch.mockImplementation((url: string, init?: RequestInit) => {
      if (url === "/api/v1/rooms/12/members" && init?.method === "POST") return new Promise<Response>((resolve) => (answer = resolve));
      return implementation(url, init);
    });
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const invalidate = vi.spyOn(queryClient, "invalidateQueries");
    const user = userEvent.setup();
    renderWithProviders(<NavigationHarness />, { queryClient });
    await user.type(box(), "Kept while away");
    await pick(user);
    fireEvent.click(screen.getByRole("button", { name: "toggle pane" }));
    expect(screen.queryByRole("textbox", { name: "Reply" })).not.toBeInTheDocument();
    answer(jsonResponse(200, { added: 1, alreadyMembers: 0 }));
    await waitFor(() => expect(invalidate).toHaveBeenCalledWith({ queryKey: ["rooms"] }));
    await user.click(screen.getByRole("button", { name: "toggle pane" }));
    expect(box()).toHaveValue("Kept while away @New Person ");
  });

  test("an unmatched typed name opens the prefilled dialog and replaces only that unfinished term", async () => {
    directory(200, { added: 1, alreadyMembers: 0 });
    const user = userEvent.setup();
    renderComposer();
    await user.type(box(), "Before @New after");
    (box() as HTMLTextAreaElement).setSelectionRange(11, 11);
    fireEvent.select(box());
    await user.click(await screen.findByRole("option", { name: /Add and mention “New”/ }));
    const directoryBox = screen.getByRole("combobox", { name: "User" });
    expect(directoryBox).toHaveValue("New");
    await user.click(await screen.findByText("New Person (new@example.test)"));
    await user.click(screen.getAllByRole("button", { name: "Add and mention" }).at(-1)!);
    await waitFor(() => expect(box()).toHaveValue("Before @New Person after"));
  });

  test("adding a member never grows a reply beyond the server message limit", async () => {
    directory(200, { added: 1, alreadyMembers: 0 });
    const user = userEvent.setup();
    renderComposer();
    const fullDraft = "x".repeat(MAX_MESSAGE_BODY_LENGTH);
    fireEvent.change(box(), { target: { value: fullDraft } });
    await pick(user);
    await screen.findByText(`The message must be 1–${MAX_MESSAGE_BODY_LENGTH} characters`);
    expect(box()).toHaveValue(fullDraft);
  });

  test("a non-manager never gets the directory action in mention suggestions", async () => {
    directory(200, { added: 1, alreadyMembers: 0 });
    const user = userEvent.setup();
    renderWithProviders(
      <ThreadDraftsProvider><MessageComposer room={makeRoom()} thread={makeThread()} onPosted={vi.fn()} /></ThreadDraftsProvider>,
    );
    await user.type(box(), "@New");
    expect(await screen.findByText("Nothing in this room matches")).toBeInTheDocument();
    expect(screen.queryByRole("option", { name: /Add and mention/ })).not.toBeInTheDocument();
  });

  test("a session replacement while the request travels never modifies the next session's draft", async () => {
    let answer!: (response: Response) => void;
    directory(200, { added: 1, alreadyMembers: 0 });
    const implementation = mockFetch.getMockImplementation() as (url: string, init?: RequestInit) => Promise<Response>;
    mockFetch.mockImplementation((url: string, init?: RequestInit) => {
      if (url === "/api/v1/rooms/12/members" && init?.method === "POST") return new Promise<Response>((resolve) => (answer = resolve));
      return implementation(url, init);
    });
    const user = userEvent.setup();
    renderComposer();
    await user.type(box(), "Belongs to the old session");
    await pick(user);
    clearSession();
    answer(jsonResponse(200, { added: 1, alreadyMembers: 0 }));
    await waitFor(() => expect(screen.getByRole("button", { name: "Send" })).toBeEnabled());
    expect(box()).toHaveValue("Belongs to the old session");
    expect(screen.getByRole("dialog", { name: "Add someone to this room" })).toBeInTheDocument();
  });

  test("an add that finishes after the composer changes rooms never modifies the new room's draft", async () => {
    let answer!: (response: Response) => void;
    directory(200, { added: 1, alreadyMembers: 0 });
    const implementation = mockFetch.getMockImplementation() as (url: string, init?: RequestInit) => Promise<Response>;
    mockFetch.mockImplementation((url: string, init?: RequestInit) => {
      if (url === "/api/v1/rooms/12/members" && init?.method === "POST") return new Promise<Response>((resolve) => (answer = resolve));
      return implementation(url, init);
    });
    const user = userEvent.setup();
    renderWithProviders(<RoomChangeHarness />);
    await user.type(box(), "Old room draft");
    await pick(user);
    fireEvent.click(screen.getByRole("button", { name: "change room" }));
    expect(box()).toHaveValue("");
    answer(jsonResponse(200, { added: 1, alreadyMembers: 0 }));
    await waitFor(() => expect(screen.getByRole("button", { name: "Send" })).toBeEnabled());
    expect(box()).toHaveValue("");
  });

  test("a failed add from a replaced session shows no stale composer feedback", async () => {
    let rejectAdd!: (error: Error) => void;
    directory(200, { added: 1, alreadyMembers: 0 });
    const implementation = mockFetch.getMockImplementation() as (url: string, init?: RequestInit) => Promise<Response>;
    mockFetch.mockImplementation((url: string, init?: RequestInit) => {
      if (url === "/api/v1/rooms/12/members" && init?.method === "POST") {
        return new Promise<Response>((_resolve, reject) => (rejectAdd = reject));
      }
      return implementation(url, init);
    });
    const user = userEvent.setup();
    renderComposer();
    await user.type(box(), "Belongs to the old session");
    await pick(user);
    clearSession();
    rejectAdd(new Error("connection lost"));
    await waitFor(() => expect(screen.getByRole("button", { name: "Send" })).toBeEnabled());
    expect(box()).toHaveValue("Belongs to the old session");
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  test("the action is available only to room owners and administrators", () => {
    const member = renderWithProviders(
      <ThreadDraftsProvider><MessageComposer room={makeRoom()} thread={makeThread()} onPosted={vi.fn()} /></ThreadDraftsProvider>,
    );
    expect(screen.queryByRole("button", { name: "Add and mention" })).not.toBeInTheDocument();
    member.unmount();
    localStorage.setItem("adder.auth.roles", JSON.stringify(["ADMIN"]));
    renderWithProviders(
      <ThreadDraftsProvider><MessageComposer room={makeRoom()} thread={makeThread()} onPosted={vi.fn()} /></ThreadDraftsProvider>,
    );
    expect(screen.getByRole("button", { name: "Add and mention" })).toBeInTheDocument();
  });
});

function NavigationHarness() {
  const [shown, setShown] = useState(true);
  const [names] = useState(createKnownNames);
  return (
    <ThreadDraftsProvider>
      <KnownNamesContext.Provider value={names}>
        <button onClick={() => setShown(!shown)}>toggle pane</button>
        {shown && <MessageComposer room={makeRoom({ role: "OWNER" })} thread={makeThread()} onPosted={vi.fn()} />}
      </KnownNamesContext.Provider>
    </ThreadDraftsProvider>
  );
}

function RoomChangeHarness() {
  const [roomId, setRoomId] = useState(12);
  return (
    <ThreadDraftsProvider>
      <button onClick={() => setRoomId(13)}>change room</button>
      <MessageComposer room={makeRoom({ id: roomId, role: "OWNER" })} thread={makeThread()} onPosted={vi.fn()} />
    </ThreadDraftsProvider>
  );
}
