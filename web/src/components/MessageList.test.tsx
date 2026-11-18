import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { screen, waitFor, within } from "@testing-library/react";
import MessageList from "./MessageList";
import { jsonResponse } from "../test/http";
import { makeMessage, makeRoom, makeThread, messagePage } from "../test/messageFixtures";
import { renderWithProviders } from "../test/render";

const NEWEST_FIRST = [
  makeMessage({ id: 30, authorId: 7, authorName: "Cy Author", body: "third, mine" }),
  makeMessage({ id: 20, authorId: 8, authorName: "Di Other", body: "second" }),
];

describe("MessageList", () => {
  let mockFetch: ReturnType<typeof vi.fn>;
  const listUrls = () => mockFetch.mock.calls.map(([url]) => decodeURIComponent(url as string)).filter((url) => url.includes("/messages?"));

  function serve(handler: (url: string) => Response) {
    mockFetch.mockImplementation((url: string) => Promise.resolve(handler(decodeURIComponent(url))));
  }

  beforeEach(() => {
    mockFetch = vi.fn();
    vi.stubGlobal("fetch", mockFetch);
    localStorage.setItem("adder.auth.token", "fake-token");
    localStorage.setItem("adder.auth.userId", "7");
  });
  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
    localStorage.clear();
  });

  const renderList = (props: Partial<React.ComponentProps<typeof MessageList>> = {}) =>
    renderWithProviders(<MessageList room={makeRoom()} thread={makeThread()} scrollToId={null} onScrolled={vi.fn()} {...props} />);

  test("reads the newest page and shows it oldest first, the newest at the bottom, in a labelled focusable region", async () => {
    serve(() => jsonResponse(200, messagePage(NEWEST_FIRST)));
    renderList();
    const region = await screen.findByRole("region", { name: "Messages" });
    expect(region).toHaveAttribute("tabindex", "0");
    const items = within(region).getAllByRole("listitem");
    expect(items.map((item) => item.textContent)).toEqual([expect.stringContaining("second"), expect.stringContaining("third, mine")]);
    expect(listUrls()).toEqual(["/api/v1/rooms/12/threads/42/messages?page=1&pageSize=50&sort=-id"]);
    expect(screen.queryByRole("button", { name: "Load older messages" })).not.toBeInTheDocument();
  });

  test("offers actions by the caller's standing: the author's own message has a menu, another's does not", async () => {
    serve(() => jsonResponse(200, messagePage(NEWEST_FIRST)));
    renderList();
    await screen.findByRole("region", { name: "Messages" });
    expect(screen.getAllByRole("button", { name: /^Operations for message by/ })).toHaveLength(1);
    expect(screen.getByRole("button", { name: /^Operations for message by Cy Author/ })).toBeInTheDocument();
  });

  test("a room owner may remove the others' messages", async () => {
    serve(() => jsonResponse(200, messagePage(NEWEST_FIRST)));
    renderList({ room: makeRoom({ role: "OWNER" }) });
    await screen.findByRole("region", { name: "Messages" });
    expect(screen.getAllByRole("button", { name: /^Operations for message by/ })).toHaveLength(2);
  });

  test("a closed thread offers no message actions", async () => {
    serve(() => jsonResponse(200, messagePage(NEWEST_FIRST)));
    renderList({ thread: makeThread({ closed: true }) });
    await screen.findByRole("region", { name: "Messages" });
    expect(screen.queryByRole("button", { name: /^Operations for/ })).not.toBeInTheDocument();
  });

  test("older messages: the button shows while the page's total exceeds it, and loads the next page through the oldest id as a cursor", async () => {
    const user = userEvent.setup();
    serve((url) =>
      url.includes("id[lt]=20")
        ? jsonResponse(200, messagePage([makeMessage({ id: 10, authorId: 8, authorName: "Di Other", body: "the very first" })], 1))
        : jsonResponse(200, messagePage(NEWEST_FIRST, 3)),
    );
    renderList();
    const region = await screen.findByRole("region", { name: "Messages" });
    expect(within(region).getAllByRole("listitem")).toHaveLength(2);
    await user.click(screen.getByRole("button", { name: "Load older messages" }));
    await waitFor(() => expect(within(region).getAllByRole("listitem")).toHaveLength(3));
    expect(within(region).getAllByRole("listitem")[0]).toHaveTextContent("the very first");
    expect(listUrls().at(-1)).toBe("/api/v1/rooms/12/threads/42/messages?page=1&pageSize=50&sort=-id&id[lt]=20");
    // The last page's total equals what it returned: nothing older remains.
    expect(screen.queryByRole("button", { name: "Load older messages" })).not.toBeInTheDocument();
  });

  test("when the load that finishes the history removes the button, focus lands on the oldest message instead of the page", async () => {
    const user = userEvent.setup();
    serve((url) =>
      url.includes("id[lt]=20")
        ? jsonResponse(200, messagePage([makeMessage({ id: 10, authorId: 8, authorName: "Di Other", body: "the very first" })], 1))
        : jsonResponse(200, messagePage(NEWEST_FIRST, 3)),
    );
    renderList();
    const region = await screen.findByRole("region", { name: "Messages" });
    await user.click(screen.getByRole("button", { name: "Load older messages" }));
    await waitFor(() => expect(within(region).getAllByRole("listitem")[0]).toHaveTextContent("the very first"));
    await waitFor(() => expect(within(region).getAllByRole("listitem")[0]).toHaveFocus());
  });

  test("a load that leaves older messages keeps the button, and the focus on it", async () => {
    const user = userEvent.setup();
    serve((url) =>
      url.includes("id[lt]=20")
        ? jsonResponse(200, messagePage([makeMessage({ id: 10, body: "middle" })], 2))
        : jsonResponse(200, messagePage(NEWEST_FIRST, 3)),
    );
    renderList();
    await screen.findByRole("region", { name: "Messages" });
    const button = screen.getByRole("button", { name: "Load older messages" });
    await user.click(button);
    await waitFor(() => expect(screen.getAllByRole("listitem")).toHaveLength(3));
    expect(button).toHaveFocus();
  });

  test("a failed load of older messages keeps the list and the button", async () => {
    const user = userEvent.setup();
    serve((url) => (url.includes("id[lt]=") ? jsonResponse(500, { title: "Boom", status: 500 }) : jsonResponse(200, messagePage(NEWEST_FIRST, 3))));
    renderList();
    await screen.findByRole("region", { name: "Messages" });
    await user.click(screen.getByRole("button", { name: "Load older messages" }));
    expect(await screen.findByText("Could not load the messages")).toBeInTheDocument();
    expect(screen.getAllByRole("listitem")).toHaveLength(2);
    expect(screen.getByRole("button", { name: "Load older messages" })).toBeInTheDocument();
  });

  test("scrolls to the bottom on open", async () => {
    vi.spyOn(HTMLElement.prototype, "scrollHeight", "get").mockReturnValue(900);
    serve(() => jsonResponse(200, messagePage(NEWEST_FIRST)));
    renderList();
    const region = await screen.findByRole("region", { name: "Messages" });
    expect(region.scrollTop).toBe(900);
  });

  test("scrolls to the bottom once the caller's own message is listed, then clears the request", async () => {
    const heights = vi.spyOn(HTMLElement.prototype, "scrollHeight", "get").mockReturnValue(500);
    const onScrolled = vi.fn();
    serve(() => jsonResponse(200, messagePage(NEWEST_FIRST)));
    const { rerender } = renderList({ onScrolled });
    const region = await screen.findByRole("region", { name: "Messages" });
    // 40 has not been listed yet (the newest is 30): the request waits.
    heights.mockReturnValue(800);
    rerender(<MessageList room={makeRoom()} thread={makeThread()} scrollToId={40} onScrolled={onScrolled} />);
    expect(region.scrollTop).toBe(500);
    expect(onScrolled).not.toHaveBeenCalled();
    // Listed: it goes to the bottom.
    rerender(<MessageList room={makeRoom()} thread={makeThread()} scrollToId={30} onScrolled={onScrolled} />);
    expect(region.scrollTop).toBe(800);
    expect(onScrolled).toHaveBeenCalledTimes(1);
  });

  test("loading older messages keeps the reader where they were", async () => {
    const user = userEvent.setup();
    let height = 500;
    vi.spyOn(HTMLElement.prototype, "scrollHeight", "get").mockImplementation(() => height);
    serve((url) => {
      if (url.includes("id[lt]=20")) {
        height = 900;
        return jsonResponse(200, messagePage([makeMessage({ id: 10, body: "older" })], 1));
      }
      return jsonResponse(200, messagePage(NEWEST_FIRST, 3));
    });
    renderList();
    const region = await screen.findByRole("region", { name: "Messages" });
    region.scrollTop = 40;
    await user.click(screen.getByRole("button", { name: "Load older messages" }));
    await waitFor(() => expect(screen.getAllByRole("listitem")).toHaveLength(3));
    // 40 + (900 - 500): the 400px the older page added are above the reader.
    expect(region.scrollTop).toBe(440);
  });

  test("a failed first read is an inline alert with Retry, which reads again; while loading a spinner stands in", async () => {
    const user = userEvent.setup();
    let healthy = false;
    serve(() => (healthy ? jsonResponse(200, messagePage(NEWEST_FIRST)) : jsonResponse(500, { title: "Boom", status: 500 })));
    renderList();
    expect(screen.getByRole("status", { name: "Loading…" })).toBeInTheDocument();
    expect(await screen.findByText("Could not load the messages")).toBeInTheDocument();
    expect(screen.getByText("Load failed (500)")).toBeInTheDocument();
    healthy = true;
    await user.click(screen.getByRole("button", { name: "Retry" }));
    await waitFor(() => expect(screen.getAllByRole("listitem")).toHaveLength(2));
    expect(screen.queryByText("Could not load the messages")).not.toBeInTheDocument();
  });

  test("a failed load of older messages offers no Retry (its own button stays)", async () => {
    const user = userEvent.setup();
    serve((url) => (url.includes("id[lt]=") ? jsonResponse(500, { title: "Boom", status: 500 }) : jsonResponse(200, messagePage(NEWEST_FIRST, 3))));
    renderList();
    await screen.findByRole("region", { name: "Messages" });
    await user.click(screen.getByRole("button", { name: "Load older messages" }));
    expect(await screen.findByText("Could not load the messages")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Retry" })).not.toBeInTheDocument();
  });

  test("while older messages load the button keeps focus: it is busy, not disabled", async () => {
    const user = userEvent.setup();
    let answer!: (response: Response) => void;
    mockFetch.mockImplementation((url: string) =>
      decodeURIComponent(url).includes("id[lt]=20")
        ? new Promise<Response>((resolve) => (answer = resolve))
        : Promise.resolve(jsonResponse(200, messagePage(NEWEST_FIRST, 3))),
    );
    renderList();
    await screen.findByRole("region", { name: "Messages" });
    const button = screen.getByRole("button", { name: "Load older messages" });
    await user.click(button);
    await waitFor(() => expect(button).toHaveAttribute("aria-busy", "true"));
    expect(button).toBeEnabled();
    expect(button).toHaveFocus();
    // A second press during the load asks for nothing more.
    await user.click(button);
    expect(listUrls().filter((url) => url.includes("id[lt]="))).toHaveLength(1);
    answer(jsonResponse(200, messagePage([makeMessage({ id: 10, body: "older" })], 1)));
    await waitFor(() => expect(screen.getAllByRole("listitem")).toHaveLength(3));
  });
});
