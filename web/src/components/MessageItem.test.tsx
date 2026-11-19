import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { useLocation } from "react-router-dom";
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
});
