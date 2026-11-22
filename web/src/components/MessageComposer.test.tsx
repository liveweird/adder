import { useState } from "react";
import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { QueryClient } from "@tanstack/react-query";
import { fireEvent, screen, waitFor } from "@testing-library/react";
import MessageComposer from "./MessageComposer";
import ThreadDraftsProvider from "./ThreadDraftsProvider";
import { jsonResponse } from "../test/http";
import { makeMessage, makeRoom, makeThread } from "../test/messageFixtures";
import { renderWithProviders } from "../test/render";
import { createKnownNames, KnownNamesContext } from "../utils/knownNames";

describe("MessageComposer", () => {
  let mockFetch: ReturnType<typeof vi.fn>;
  const posts = () => mockFetch.mock.calls.filter(([, init]) => (init as RequestInit | undefined)?.method === "POST") as [string, RequestInit][];
  const box = () => screen.getByRole("textbox", { name: "Reply" });

  beforeEach(() => {
    mockFetch = vi.fn().mockResolvedValue(jsonResponse(201, makeMessage({ id: 77 })));
    vi.stubGlobal("fetch", mockFetch);
    localStorage.setItem("adder.auth.token", "fake-token");
  });
  afterEach(() => {
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  const renderComposer = (props: Partial<React.ComponentProps<typeof MessageComposer>> = {}, queryClient?: QueryClient) =>
    renderWithProviders(
      <ThreadDraftsProvider>
        <MessageComposer room={makeRoom()} thread={makeThread()} onPosted={vi.fn()} {...props} />
      </ThreadDraftsProvider>,
      { queryClient },
    );

  test("Ctrl+Enter posts the normalised body, tells the pane the new id, clears the box and re-reads the room", async () => {
    const user = userEvent.setup();
    const onPosted = vi.fn();
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const invalidate = vi.spyOn(queryClient, "invalidateQueries");
    renderComposer({ onPosted }, queryClient);
    await user.type(box(), "\r\nhello **team**  ");
    await user.keyboard("{Control>}{Enter}{/Control}");
    await waitFor(() => expect(onPosted).toHaveBeenCalledWith(77));
    expect(posts()).toHaveLength(1);
    expect(posts()[0][0]).toBe("/api/v1/rooms/12/threads/42/messages");
    expect(JSON.parse(posts()[0][1].body as string)).toEqual({ body: "hello **team**" });
    await waitFor(() => expect(box()).toHaveValue(""));
    // Per plan 4.5: the room's thread queries and the rail, not the whole prefix.
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ["rooms", "threads", 12] });
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ["rooms", "rail"] });
    expect(invalidate).not.toHaveBeenCalledWith({ queryKey: ["rooms"] });
  });

  test("a second Ctrl+Enter before the first post has answered posts nothing more", async () => {
    let answer!: (response: Response) => void;
    mockFetch.mockImplementation(() => new Promise<Response>((resolve) => (answer = resolve)));
    const user = userEvent.setup();
    renderComposer();
    await user.type(box(), "once");
    await user.keyboard("{Control>}{Enter}{Enter}{Enter}{/Control}");
    expect(posts()).toHaveLength(1);
    answer(jsonResponse(201, makeMessage({ id: 77 })));
    await waitFor(() => expect(box()).toHaveValue(""));
    expect(posts()).toHaveLength(1);
  });

  test("a post that finishes after the reader has left the thread leaves no draft behind", async () => {
    let answer!: (response: Response) => void;
    mockFetch.mockImplementation(() => new Promise<Response>((resolve) => (answer = resolve)));
    const user = userEvent.setup();
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const invalidate = vi.spyOn(queryClient, "invalidateQueries");
    renderWithProviders(<Harness />, { queryClient });
    await user.type(box(), "sent text");
    await user.click(screen.getByRole("button", { name: "Send" }));
    await waitFor(() => expect(posts()).toHaveLength(1));
    // Leave the pane while the post is in flight, then let it succeed.
    await user.click(screen.getByRole("button", { name: "toggle pane" }));
    expect(screen.queryByRole("textbox")).not.toBeInTheDocument();
    answer(jsonResponse(201, makeMessage({ id: 77 })));
    // The refresh is the last step of a post: the draft has been dealt with by then.
    await waitFor(() => expect(invalidate).toHaveBeenCalled());
    await user.click(screen.getByRole("button", { name: "toggle pane" }));
    expect(box()).toHaveValue("");
  });

  test("a composer mounted again while its post is still in flight posts nothing more, shows the box busy, and clears it when the post succeeds", async () => {
    let answer!: (response: Response) => void;
    mockFetch.mockImplementation(() => new Promise<Response>((resolve) => (answer = resolve)));
    const user = userEvent.setup();
    renderWithProviders(<Harness />);
    await user.type(box(), "still travelling");
    await user.click(screen.getByRole("button", { name: "Send" }));
    await waitFor(() => expect(posts()).toHaveLength(1));
    await user.click(screen.getByRole("button", { name: "toggle pane" }));
    await user.click(screen.getByRole("button", { name: "toggle pane" }));
    // The kept draft is back, read-only, and neither the shortcut nor the button sends it again.
    expect(box()).toHaveValue("still travelling");
    expect(box()).toHaveAttribute("readonly");
    await user.click(box());
    await user.keyboard("{Control>}{Enter}{/Control}");
    expect(posts()).toHaveLength(1);
    answer(jsonResponse(201, makeMessage({ id: 77 })));
    await waitFor(() => expect(box()).toHaveValue(""));
    expect(box()).not.toHaveAttribute("readonly");
    expect(posts()).toHaveLength(1);
  });

  test("Cmd+Enter and the Send button post too; a plain Enter only adds a line", async () => {
    const user = userEvent.setup();
    renderComposer();
    await user.type(box(), "one{Enter}two");
    expect(box()).toHaveValue("one\ntwo");
    expect(posts()).toEqual([]);
    await user.keyboard("{Meta>}{Enter}{/Meta}");
    await waitFor(() => expect(posts()).toHaveLength(1));
    expect(JSON.parse(posts()[0][1].body as string)).toEqual({ body: "one\ntwo" });
    await user.type(box(), "again");
    await user.click(screen.getByRole("button", { name: "Send" }));
    await waitFor(() => expect(posts()).toHaveLength(2));
  });

  test("shows a character count and describes the reply field with it", async () => {
    const user = userEvent.setup();
    renderComposer();
    expect(screen.getByText("0 / 8000")).toBeInTheDocument();
    expect(box()).toHaveAccessibleDescription(/Markdown is supported.*0 \/ 8000/);
    await user.type(box(), "abc");
    expect(screen.getByText("3 / 8000")).toBeInTheDocument();
    expect(box()).toHaveAccessibleDescription(/3 \/ 8000/);
  });

  test("an empty reply and one with a control character are refused before any request", async () => {
    const user = userEvent.setup();
    renderComposer();
    await user.click(screen.getByRole("button", { name: "Send" }));
    expect(await screen.findByText("The message must be 1–8000 characters")).toBeInTheDocument();
    fireEvent.change(box(), { target: { value: "bell\u0007" } });
    await user.click(screen.getByRole("button", { name: "Send" }));
    expect(await screen.findByText("The message must not contain control or hidden formatting characters")).toBeInTheDocument();
    expect(posts()).toEqual([]);
  });

  /** One provider that outlives the composer, and buttons that move between threads, hide the composer (leaving the pane) and close the thread. */
  function Harness() {
    const [number, setNumber] = useState(42);
    const [shown, setShown] = useState(true);
    const [closed, setClosed] = useState(false);
    return (
      <ThreadDraftsProvider>
        <button onClick={() => setNumber(number === 42 ? 43 : 42)}>switch thread</button>
        <button onClick={() => setShown(!shown)}>toggle pane</button>
        <button onClick={() => setClosed(!closed)}>toggle closed</button>
        {shown && <MessageComposer room={makeRoom()} thread={makeThread({ number, closed })} onPosted={vi.fn()} />}
      </ThreadDraftsProvider>
    );
  }

  test("the reply is kept per thread: another thread starts empty, and each one's text is back when the reader returns", async () => {
    const user = userEvent.setup();
    renderWithProviders(<Harness />);
    await user.type(box(), "half a thought");
    await user.click(screen.getByRole("button", { name: "switch thread" }));
    expect(box()).toHaveValue("");
    await user.type(box(), "other thread");
    await user.click(screen.getByRole("button", { name: "switch thread" }));
    expect(box()).toHaveValue("half a thought");
    // Leaving the pane altogether (a tab, "Back to threads") keeps it too.
    await user.click(screen.getByRole("button", { name: "toggle pane" }));
    expect(screen.queryByRole("textbox")).not.toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "toggle pane" }));
    expect(box()).toHaveValue("half a thought");
  });

  test("a mention is posted as its token, and the kept draft comes back with its names", async () => {
    mockFetch.mockImplementation((url: string, init?: RequestInit) => {
      if (url.startsWith("/api/v1/rooms/12/members?")) {
        const items = [{ userId: 7, name: "Bob Builder", email: "bob@example.test", role: "MEMBER", addedAt: 1 }];
        return Promise.resolve(jsonResponse(200, { items, page: 1, pageSize: 8, total: 1 }));
      }
      return Promise.resolve(init?.method === "POST" ? jsonResponse(201, makeMessage({ id: 77 })) : jsonResponse(200, { items: [], page: 1, pageSize: 50, total: 0 }));
    });
    const names = createKnownNames();
    function Named() {
      const [shown, setShown] = useState(true);
      return (
        <ThreadDraftsProvider>
          <KnownNamesContext.Provider value={names}>
            <button onClick={() => setShown(!shown)}>toggle pane</button>
            {shown && <MessageComposer room={makeRoom()} thread={makeThread()} onPosted={vi.fn()} />}
          </KnownNamesContext.Provider>
        </ThreadDraftsProvider>
      );
    }
    const user = userEvent.setup();
    renderWithProviders(<Named />);
    await user.type(box(), "@bo");
    await user.click(await screen.findByRole("option", { name: /Bob Builder/ }));
    await user.type(box(), "look");
    await user.click(screen.getByRole("button", { name: "toggle pane" }));
    await user.click(screen.getByRole("button", { name: "toggle pane" }));
    expect(box()).toHaveValue("@Bob Builder look");
    await user.click(screen.getByRole("button", { name: "Send" }));
    await waitFor(() => expect(posts()).toHaveLength(1));
    expect(JSON.parse(posts()[0][1].body as string)).toEqual({ body: "<@7> look" });
  });

  test("a posted reply leaves no draft behind", async () => {
    const user = userEvent.setup();
    renderWithProviders(<Harness />);
    await user.type(box(), "sent");
    await user.click(screen.getByRole("button", { name: "Send" }));
    await waitFor(() => expect(box()).toHaveValue(""));
    await user.click(screen.getByRole("button", { name: "toggle pane" }));
    await user.click(screen.getByRole("button", { name: "toggle pane" }));
    expect(box()).toHaveValue("");
  });

  test("a draft survives the thread being closed and reopened", async () => {
    const user = userEvent.setup();
    renderWithProviders(<Harness />);
    await user.type(box(), "kept through the close");
    await user.click(screen.getByRole("button", { name: "toggle closed" }));
    expect(screen.queryByRole("textbox")).not.toBeInTheDocument();
    expect(screen.getByRole("note")).toHaveTextContent("This thread is closed");
    await user.click(screen.getByRole("button", { name: "toggle closed" }));
    expect(box()).toHaveValue("kept through the close");
  });

  test("a reload warns while the box holds something", async () => {
    const user = userEvent.setup();
    renderComposer();
    const unload = () => {
      const event = new Event("beforeunload", { cancelable: true });
      window.dispatchEvent(event);
      return event.defaultPrevented;
    };
    expect(unload()).toBe(false);
    await user.type(box(), "x");
    expect(unload()).toBe(true);
  });

  test("a closed thread shows its notice instead of a box", () => {
    renderComposer({ thread: makeThread({ closed: true }) });
    expect(screen.getByRole("note")).toHaveTextContent("This thread is closed, so it takes no new messages.");
    expect(screen.queryByRole("textbox")).not.toBeInTheDocument();
  });

  test("an archived room shows its notice", () => {
    renderComposer({ room: makeRoom({ archived: true, role: "OWNER" }) });
    expect(screen.getByRole("note")).toHaveTextContent("This room is archived, so no new messages can be posted.");
    expect(screen.queryByRole("textbox")).not.toBeInTheDocument();
  });

  test("an ADMIN who is not a member is told to join, with a link to the Members page", () => {
    renderComposer({ room: makeRoom({ role: null }) });
    expect(screen.getByRole("note")).toHaveTextContent("Only members of the room can post.");
    expect(screen.getByRole("link", { name: "Open the Members page" })).toHaveAttribute("href", "/rooms/12/members");
    expect(screen.queryByRole("textbox")).not.toBeInTheDocument();
  });

  test("a 409 thread-closed says so, keeps the text and re-reads the room and the thread (the notice then replaces the box)", async () => {
    mockFetch.mockResolvedValue(jsonResponse(409, { type: "urn:adder:problem:thread-closed", status: 409, title: "Closed" }));
    const user = userEvent.setup();
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const invalidate = vi.spyOn(queryClient, "invalidateQueries");
    const onPosted = vi.fn();
    renderComposer({ onPosted }, queryClient);
    await user.type(box(), "too late");
    await user.click(screen.getByRole("button", { name: "Send" }));
    expect(await screen.findByText("This thread was closed in the meantime, so its messages can no longer be changed.")).toBeInTheDocument();
    expect(box()).toHaveValue("too late");
    expect(onPosted).not.toHaveBeenCalled();
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ["rooms"] });
  });

  test("a 429 shows the wait message and keeps the text", async () => {
    mockFetch.mockResolvedValue(jsonResponse(429, { title: "Too many", status: 429 }));
    const user = userEvent.setup();
    renderComposer();
    await user.type(box(), "fast");
    await user.click(screen.getByRole("button", { name: "Send" }));
    expect(await screen.findByText("You are posting too quickly. Wait a minute and try again.")).toBeInTheDocument();
    expect(box()).toHaveValue("fast");
  });

  test("any other failure is the generic action failure, and the box stays usable", async () => {
    mockFetch.mockResolvedValue(jsonResponse(500, { title: "Boom", status: 500 }));
    const user = userEvent.setup();
    renderComposer();
    await user.type(box(), "x");
    await user.click(screen.getByRole("button", { name: "Send" }));
    expect(await screen.findByText("Action failed (500)")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Send" })).toBeEnabled();
  });
});
