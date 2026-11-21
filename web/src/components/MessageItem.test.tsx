import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { useLocation } from "react-router-dom";
import { QueryClient } from "@tanstack/react-query";
import { screen, waitFor, within } from "@testing-library/react";
import MessageItem from "./MessageItem";
import { jsonResponse } from "../test/http";
import { SOME_TIME, makeMessage } from "../test/messageFixtures";
import { renderWithProviders } from "../test/render";
import { browserTimeZone, formatDateTime } from "../utils/formatDate";
import type { MessageActions } from "../utils/messageAccess";
import type { MessageResponse } from "../api/messages";

const ZONE = browserTimeZone();
const WHEN = formatDateTime(SOME_TIME, ZONE);
const NAME = `Operations for message by Cy Author on ${WHEN}`;

const list = (message: MessageResponse, actions: MessageActions) => (
  <ul>
    <MessageItem roomId={12} message={message} actions={actions} zone={ZONE} />
  </ul>
);

function renderItem(message: MessageResponse, actions: MessageActions) {
  return renderWithProviders(list(message, actions));
}

describe("MessageItem", () => {
  let mockFetch: ReturnType<typeof vi.fn>;
  const calls = (method: string) => mockFetch.mock.calls.filter(([, init]) => (init as RequestInit | undefined)?.method === method) as [string, RequestInit][];

  beforeEach(() => {
    mockFetch = vi.fn().mockResolvedValue(new Response(null, { status: 204 }));
    vi.stubGlobal("fetch", mockFetch);
    localStorage.setItem("adder.auth.token", "fake-token");
  });
  afterEach(() => {
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  test("shows the author, the time in the browser's zone and the body as Markdown, with no heading", () => {
    renderItem(makeMessage({ body: "# Big\n\nRestarted the **replica**." }), { edit: false, remove: null });
    const item = screen.getByRole("listitem");
    expect(item).toHaveTextContent("Cy Author");
    expect(item).toHaveTextContent(WHEN);
    expect(within(item).getByText("replica").tagName).toBe("STRONG");
    expect(screen.queryByRole("heading")).not.toBeInTheDocument();
    expect(screen.queryByText(/^edited /)).not.toBeInTheDocument();
    expect(screen.queryByRole("button")).not.toBeInTheDocument();
  });

  test("a pinned message says who pinned it, with the pin shape, and is marked for its bar; an unpinned one and a tombstone carry nothing", () => {
    const withPin = (message: MessageResponse, pinnedBy?: string) => (
      <ul>
        <MessageItem roomId={12} message={message} actions={{ edit: false, remove: null }} zone={ZONE} pinnedBy={pinnedBy} />
      </ul>
    );
    const { rerender } = renderWithProviders(withPin(makeMessage(), "Ann Owner"));
    const item = screen.getByRole("listitem");
    expect(item).toHaveAttribute("data-pinned", "true");
    const line = within(item).getByText("Pinned by Ann Owner");
    // The icon is decoration: the words are the mark.
    expect(line.parentElement?.querySelector("svg")).toHaveAttribute("aria-hidden", "true");
    rerender(withPin(makeMessage()));
    expect(screen.getByRole("listitem")).not.toHaveAttribute("data-pinned");
    expect(screen.queryByText(/^Pinned by/)).not.toBeInTheDocument();
    // A deleted message loses its pin on the server; until the pins are re-read it must not wear the mark.
    rerender(withPin(makeMessage({ deleted: true, body: null }), "Ann Owner"));
    expect(screen.getByRole("listitem")).not.toHaveAttribute("data-pinned");
    expect(screen.queryByText(/^Pinned by/)).not.toBeInTheDocument();
  });

  test("a mention in the body reads as the person's name when the message lists it, and as the raw token when it does not", () => {
    renderItem(makeMessage({ body: "Ask <@5> or <@77>.", mentions: [{ userId: 5, name: "Ann Owner" }] }), { edit: false, remove: null });
    expect(screen.getByText("@Ann Owner")).toBeInTheDocument();
    expect(screen.getByText(/or <@77>\./)).toBeInTheDocument();
  });

  test("a hashtag in the body is a link that keeps the thread open and filters the list to the tag as typed, across open and closed threads", () => {
    renderItem(makeMessage({ body: "Rolled back, see #Wdro\u017cenie and #outage-3.", threadNumber: 42 }), { edit: false, remove: null });
    const tag = screen.getByRole("link", { name: "#Wdro\u017cenie" });
    expect(tag).toHaveAttribute("href", "/rooms/12/threads/42?scope=all&tag=Wdro%C5%BCenie");
    expect(tag).not.toHaveAttribute("target");
    expect(screen.getByRole("link", { name: "#outage-3" })).toHaveAttribute("href", "/rooms/12/threads/42?scope=all&tag=outage-3");
  });

  test("clicking a hashtag navigates inside the app, to the thread with the filter in the URL", async () => {
    const Where = () => <output aria-label="where">{useLocation().pathname + useLocation().search}</output>;
    renderWithProviders(
      <>
        {list(makeMessage({ body: "see #outage" }), { edit: false, remove: null })}
        <Where />
      </>,
    );
    await userEvent.setup().click(screen.getByRole("link", { name: "#outage" }));
    expect(screen.getByLabelText("where")).toHaveTextContent("/rooms/12/threads/42?scope=all&tag=outage");
  });

  test("a tombstone shows no body, so no tag link", () => {
    renderItem(makeMessage({ body: null, deleted: true }), { edit: false, remove: null });
    expect(screen.queryByRole("link")).toBeNull();
  });

  test("an edited message carries the marker, with its time as visible text", () => {
    renderItem(makeMessage({ editedAt: SOME_TIME + 60_000 }), { edit: false, remove: null });
    expect(screen.getByText(`edited ${formatDateTime(SOME_TIME + 60_000, ZONE)}`)).toBeInTheDocument();
  });

  test("a tombstone says who removed it, keeps the author and time, and offers nothing", () => {
    const { unmount } = renderItem(makeMessage({ body: null, deleted: true }), { edit: false, remove: null });
    expect(screen.getByText("This message was deleted.")).toBeInTheDocument();
    expect(screen.getByRole("listitem")).toHaveTextContent("Cy Author");
    expect(screen.queryByRole("button")).not.toBeInTheDocument();
    unmount();
    renderItem(makeMessage({ body: null, deleted: true, deletedByModerator: true, editedAt: SOME_TIME }), { edit: false, remove: null });
    expect(screen.getByText("Removed by a room owner or an administrator.")).toBeInTheDocument();
    expect(screen.queryByText(/^edited /)).not.toBeInTheDocument();
  });

  test("the author's menu offers Edit and Delete", async () => {
    const user = userEvent.setup();
    renderItem(makeMessage(), { edit: true, remove: "delete" });
    await user.click(screen.getByRole("button", { name: NAME }));
    expect(await screen.findByRole("menuitem", { name: "Edit" })).toBeInTheDocument();
    expect(screen.getByRole("menuitem", { name: "Delete" })).toBeInTheDocument();
    expect(screen.queryByRole("menuitem", { name: "Remove" })).not.toBeInTheDocument();
  });

  test("a moderator's menu offers Remove only", async () => {
    const user = userEvent.setup();
    renderItem(makeMessage(), { edit: false, remove: "moderate" });
    await user.click(screen.getByRole("button", { name: NAME }));
    expect(await screen.findByRole("menuitem", { name: "Remove" })).toBeInTheDocument();
    expect(screen.queryByRole("menuitem", { name: "Edit" })).not.toBeInTheDocument();
    expect(screen.queryByRole("menuitem", { name: "Delete" })).not.toBeInTheDocument();
  });

  test("Edit swaps the body for the editor, and saving puts it back", async () => {
    const user = userEvent.setup();
    renderItem(makeMessage({ id: 9, body: "first" }), { edit: true, remove: "delete" });
    await user.click(screen.getByRole("button", { name: NAME }));
    await user.click(await screen.findByRole("menuitem", { name: "Edit" }));
    const box = screen.getByRole("textbox", { name: "Edit message" });
    expect(screen.queryByRole("button", { name: NAME })).not.toBeInTheDocument();
    await user.type(box, "!");
    await user.click(screen.getByRole("button", { name: "Save" }));
    await waitFor(() => expect(screen.queryByRole("textbox", { name: "Edit message" })).not.toBeInTheDocument());
    expect(calls("PUT")[0][0]).toBe("/api/v1/rooms/12/messages/9");
    expect(screen.getByRole("button", { name: NAME })).toBeInTheDocument();
  });

  test("Delete asks first; Cancel leaves the message alone", async () => {
    const user = userEvent.setup();
    renderItem(makeMessage(), { edit: true, remove: "delete" });
    await user.click(screen.getByRole("button", { name: NAME }));
    await user.click(await screen.findByRole("menuitem", { name: "Delete" }));
    const dialog = await screen.findByRole("dialog", { name: "Delete this message?" });
    expect(dialog).toHaveTextContent("It is replaced by a note that it was deleted.");
    await user.click(within(dialog).getByRole("button", { name: "Cancel" }));
    await waitFor(() => expect(dialog).not.toBeVisible());
    expect(calls("DELETE")).toEqual([]);
  });

  test("confirming Delete sends the DELETE and closes the dialog", async () => {
    const user = userEvent.setup();
    renderItem(makeMessage({ id: 9 }), { edit: true, remove: "delete" });
    await user.click(screen.getByRole("button", { name: NAME }));
    await user.click(await screen.findByRole("menuitem", { name: "Delete" }));
    const dialog = await screen.findByRole("dialog", { name: "Delete this message?" });
    await user.click(within(dialog).getByRole("button", { name: "Delete" }));
    await waitFor(() => expect(calls("DELETE")).toHaveLength(1));
    expect(calls("DELETE")[0][0]).toBe("/api/v1/rooms/12/messages/9");
    await waitFor(() => expect(dialog).not.toBeVisible());
  });

  test("a delete re-reads the room's pins: a removed message loses its pin and its body leaves the panel", async () => {
    const user = userEvent.setup();
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const invalidate = vi.spyOn(queryClient, "invalidateQueries");
    renderWithProviders(list(makeMessage({ id: 9 }), { edit: true, remove: "delete" }), { queryClient });
    await user.click(screen.getByRole("button", { name: NAME }));
    await user.click(await screen.findByRole("menuitem", { name: "Delete" }));
    await user.click(within(await screen.findByRole("dialog", { name: "Delete this message?" })).getByRole("button", { name: "Delete" }));
    await waitFor(() => expect(invalidate).toHaveBeenCalledWith({ queryKey: ["rooms", "pins", 12] }));
    expect(invalidate).not.toHaveBeenCalledWith({ queryKey: ["rooms"] });
  });

  test("a moderator's confirm says Remove, in its own words", async () => {
    const user = userEvent.setup();
    renderItem(makeMessage({ id: 9 }), { edit: false, remove: "moderate" });
    await user.click(screen.getByRole("button", { name: NAME }));
    await user.click(await screen.findByRole("menuitem", { name: "Remove" }));
    const dialog = await screen.findByRole("dialog", { name: "Remove this message?" });
    expect(dialog).toHaveTextContent("a room owner or an administrator removed it");
    await user.click(within(dialog).getByRole("button", { name: "Remove" }));
    await waitFor(() => expect(calls("DELETE")).toHaveLength(1));
  });

  test("a refused delete closes the dialog and says why under the message", async () => {
    mockFetch.mockResolvedValue(jsonResponse(409, { type: "urn:adder:problem:thread-closed", status: 409, title: "Closed" }));
    const user = userEvent.setup();
    renderItem(makeMessage(), { edit: true, remove: "delete" });
    await user.click(screen.getByRole("button", { name: NAME }));
    await user.click(await screen.findByRole("menuitem", { name: "Delete" }));
    await user.click(within(await screen.findByRole("dialog", { name: "Delete this message?" })).getByRole("button", { name: "Delete" }));
    expect(await screen.findByText("This thread was closed in the meantime, so its messages can no longer be changed.")).toBeInTheDocument();
    await waitFor(() => expect(screen.queryByRole("dialog", { name: "Delete this message?" })).not.toBeInTheDocument());
  });
  test("each message is a programmatic focus target", () => {
    renderItem(makeMessage(), { edit: false, remove: null });
    expect(screen.getByRole("listitem")).toHaveAttribute("tabindex", "-1");
  });

  test("after an edit is saved, focus goes back to the message", async () => {
    const user = userEvent.setup();
    renderItem(makeMessage({ id: 9, body: "first" }), { edit: true, remove: "delete" });
    await user.click(screen.getByRole("button", { name: NAME }));
    await user.click(await screen.findByRole("menuitem", { name: "Edit" }));
    await user.type(screen.getByRole("textbox", { name: "Edit message" }), "!");
    await user.click(screen.getByRole("button", { name: "Save" }));
    await waitFor(() => expect(screen.queryByRole("textbox", { name: "Edit message" })).not.toBeInTheDocument());
    await waitFor(() => expect(screen.getByRole("listitem")).toHaveFocus());
  });

  test("after an edit is cancelled, focus goes back to the message", async () => {
    const user = userEvent.setup();
    renderItem(makeMessage({ id: 9, body: "first" }), { edit: true, remove: "delete" });
    await user.click(screen.getByRole("button", { name: NAME }));
    await user.click(await screen.findByRole("menuitem", { name: "Edit" }));
    await user.click(screen.getByRole("button", { name: "Cancel" }));
    await waitFor(() => expect(screen.getByRole("listitem")).toHaveFocus());
  });

  test("after the caller's own delete, focus goes to the message once it has become the tombstone", async () => {
    const user = userEvent.setup();
    const message = makeMessage({ id: 9 });
    const { rerender } = renderItem(message, { edit: true, remove: "delete" });
    await user.click(screen.getByRole("button", { name: NAME }));
    await user.click(await screen.findByRole("menuitem", { name: "Delete" }));
    await user.click(within(await screen.findByRole("dialog", { name: "Delete this message?" })).getByRole("button", { name: "Delete" }));
    await waitFor(() => expect(calls("DELETE")).toHaveLength(1));
    // The refetch hands the tombstone down.
    rerender(list({ ...message, body: null, deleted: true }, { edit: false, remove: null }));
    await waitFor(() => expect(screen.getByRole("listitem")).toHaveFocus());
    expect(screen.getByText("This message was deleted.")).toBeInTheDocument();
  });

  test("a refused edit shows under the message, and when the edit action goes the editor goes with it, the alert stays and it does not come back", async () => {
    mockFetch.mockResolvedValue(jsonResponse(409, { type: "urn:adder:problem:thread-closed", status: 409, title: "Closed" }));
    const user = userEvent.setup();
    const message = makeMessage({ id: 9, body: "first" });
    const { rerender } = renderItem(message, { edit: true, remove: "delete" });
    await user.click(screen.getByRole("button", { name: NAME }));
    await user.click(await screen.findByRole("menuitem", { name: "Edit" }));
    await user.type(screen.getByRole("textbox", { name: "Edit message" }), "!");
    await user.click(screen.getByRole("button", { name: "Save" }));
    const alert = "This thread was closed in the meantime, so its messages can no longer be changed.";
    expect(await screen.findByText(alert)).toBeInTheDocument();
    // The re-read says the thread is closed: no actions any more.
    rerender(list(message, { edit: false, remove: null }));
    expect(screen.queryByRole("textbox", { name: "Edit message" })).not.toBeInTheDocument();
    expect(screen.getByText(alert)).toBeInTheDocument();
    expect(screen.getByText("first")).toBeInTheDocument();
    // Reopened: the actions return, but not the editor.
    rerender(list(message, { edit: true, remove: "delete" }));
    expect(screen.queryByRole("textbox", { name: "Edit message" })).not.toBeInTheDocument();
  });

  describe("pinning", () => {
    const pinsPage = (items: unknown[]) => ({ items, page: 1, pageSize: 50, total: items.length });
    const messagePin = { id: 3, kind: "MESSAGE", threadNumber: 42, threadTitle: "T", threadStatus: "triage", threadClosed: false, message: makeMessage({ id: 9 }), pinnedById: 5, pinnedByName: "Ann", pinnedAt: SOME_TIME };

    function servePins(items: unknown[], put: () => Response = () => new Response(null, { status: 204 })) {
      mockFetch.mockImplementation((url: string, init?: RequestInit) => {
        if (init?.method === "PUT") return Promise.resolve(put());
        if (url.startsWith("/api/v1/rooms/12/pins?")) return Promise.resolve(jsonResponse(200, pinsPage(items)));
        return Promise.resolve(jsonResponse(404, { title: "Not found", status: 404 }));
      });
    }

    test("a member's menu offers Pin on a message the pins do not list, and pressing it PUTs pinned=true", async () => {
      const user = userEvent.setup();
      servePins([]);
      renderItem(makeMessage({ id: 9 }), { edit: false, remove: null, pin: true });
      await user.click(screen.getByRole("button", { name: NAME }));
      await user.click(await screen.findByRole("menuitem", { name: "Pin" }));
      await waitFor(() => expect(calls("PUT")).toHaveLength(1));
      expect([calls("PUT")[0][0], JSON.parse(calls("PUT")[0][1].body as string)]).toEqual(["/api/v1/rooms/12/messages/9/pin", { pinned: true }]);
    });

    test("a pinned message offers Unpin and pressing it PUTs pinned=false", async () => {
      const user = userEvent.setup();
      servePins([messagePin]);
      renderItem(makeMessage({ id: 9 }), { edit: false, remove: null, pin: true });
      await user.click(screen.getByRole("button", { name: NAME }));
      await user.click(await screen.findByRole("menuitem", { name: "Unpin" }));
      await waitFor(() => expect(calls("PUT")).toHaveLength(1));
      expect(JSON.parse(calls("PUT")[0][1].body as string)).toEqual({ pinned: false });
    });

    test("the menu item flips from Pin to Unpin once the pins are read again", async () => {
      const user = userEvent.setup();
      let items: unknown[] = [];
      mockFetch.mockImplementation((url: string, init?: RequestInit) => {
        if (init?.method === "PUT") {
          items = [messagePin];
          return Promise.resolve(new Response(null, { status: 204 }));
        }
        if (url.startsWith("/api/v1/rooms/12/pins?")) return Promise.resolve(jsonResponse(200, pinsPage(items)));
        return Promise.resolve(jsonResponse(404, { title: "Not found", status: 404 }));
      });
      renderItem(makeMessage({ id: 9 }), { edit: false, remove: null, pin: true });
      await user.click(screen.getByRole("button", { name: NAME }));
      await user.click(await screen.findByRole("menuitem", { name: "Pin" }));
      await waitFor(() => expect(calls("PUT")).toHaveLength(1));
      await waitFor(() => expect(screen.queryByRole("menuitem")).not.toBeInTheDocument());
      await user.click(screen.getByRole("button", { name: NAME }));
      expect(await screen.findByRole("menuitem", { name: "Unpin" })).toBeInTheDocument();
      expect(screen.queryByRole("menuitem", { name: "Pin" })).not.toBeInTheDocument();
    });

    test("the room's 51st pin says so under the message, and only the pins are read again", async () => {
      const user = userEvent.setup();
      servePins([], () => jsonResponse(409, { type: "urn:adder:problem:pin-limit", status: 409, title: "Pin limit" }));
      const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
      const invalidate = vi.spyOn(queryClient, "invalidateQueries");
      renderWithProviders(list(makeMessage({ id: 9 }), { edit: false, remove: null, pin: true }), { queryClient });
      await user.click(screen.getByRole("button", { name: NAME }));
      await user.click(await screen.findByRole("menuitem", { name: "Pin" }));
      expect(await screen.findByText("This room already has 50 pins. Unpin one first.")).toBeInTheDocument();
      expect(invalidate).toHaveBeenCalledWith({ queryKey: ["rooms", "pins", 12] });
      expect(invalidate).not.toHaveBeenCalledWith({ queryKey: ["rooms"] });
    });

    test("without the pin action there is no Pin item", async () => {
      const user = userEvent.setup();
      servePins([]);
      renderItem(makeMessage({ id: 9 }), { edit: true, remove: "delete" });
      await user.click(screen.getByRole("button", { name: NAME }));
      await screen.findByRole("menuitem", { name: "Edit" });
      expect(screen.queryByRole("menuitem", { name: "Pin" })).not.toBeInTheDocument();
    });
  });

  describe("reactions", () => {
    const THUMBS = { emoji: "👍", count: 2, reacted: false, names: ["Ann Owner", "Bob Starter"] };

    test("with the react action the message offers the chips and the picker", () => {
      renderItem(makeMessage({ reactions: [THUMBS] }), { edit: false, remove: null, react: true });
      expect(screen.getByRole("button", { name: "👍, 2 reactions" })).toHaveAttribute("aria-pressed", "false");
      expect(screen.getByRole("button", { name: "Add a reaction" })).toBeInTheDocument();
    });

    test("without it the chips are read-only and there is no picker (a closed thread, an archived room, a non-member)", () => {
      renderItem(makeMessage({ reactions: [THUMBS] }), { edit: false, remove: null });
      expect(screen.getByRole("list", { name: "Reactions" })).toHaveTextContent("👍, 2 reactions. Ann Owner and Bob Starter");
      expect(screen.queryByRole("button")).not.toBeInTheDocument();
    });

    test("a message with no reactions and no react action shows nothing of them", () => {
      renderItem(makeMessage(), { edit: false, remove: null });
      expect(screen.queryByRole("list", { name: "Reactions" })).not.toBeInTheDocument();
    });

    test("a tombstone shows none, even with the action", () => {
      renderItem(makeMessage({ body: null, deleted: true, reactions: [THUMBS] }), { edit: false, remove: null, react: true });
      expect(screen.queryByRole("list", { name: "Reactions" })).not.toBeInTheDocument();
    });

    test("a refused reaction is told under the message, and stays while the message is not being edited", async () => {
      mockFetch.mockResolvedValue(jsonResponse(409, { type: "urn:adder:problem:reaction-limit", status: 409, title: "Limit" }));
      renderItem(makeMessage({ reactions: [THUMBS] }), { edit: false, remove: null, react: true });
      await userEvent.setup().click(screen.getByRole("button", { name: "👍, 2 reactions" }));
      expect(await screen.findByText("This message cannot take another reaction.")).toBeInTheDocument();
    });
  });
});
