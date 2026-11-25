import userEvent from "@testing-library/user-event";
import { useLocation } from "react-router-dom";
import { afterEach, beforeEach, expect, test, vi } from "vitest";
import { createThreadDraftStore, ThreadDraftsContext } from "../hooks/useThreadDrafts";
import { jsonResponse } from "../test/http";
import { makeMessage, makeRoom, makeThread } from "../test/messageFixtures";
import { renderWithProviders, screen, waitFor, within } from "../test/render";
import QuoteDestinationModal from "./QuoteDestinationModal";

const source = makeMessage({ id: 19, threadNumber: 8, body: "Source excerpt" });
const other = makeRoom({ id: 14, name: "Second page room" });
const openThread = makeThread({ number: 11, title: "Second page thread" });

function LocationLabel() {
  const { pathname } = useLocation();
  return <output aria-label="Location">{pathname}</output>;
}

function destinations(url: string) {
  const route = new URL(url, "http://localhost");
  const second = route.searchParams.get("page") === "2";
  if (route.pathname === "/api/v1/rooms") {
    return jsonResponse(200, { items: second ? [other] : [], total: 101 });
  }
  if (route.pathname === "/api/v1/rooms/14/threads") {
    return jsonResponse(200, { items: second ? [openThread] : [], total: 101 });
  }
  return jsonResponse(200, { items: [], total: 0 });
}

beforeEach(() => {
  localStorage.setItem("adder.auth.token", "test-token");
  vi.stubGlobal("fetch", vi.fn().mockImplementation(async (url: string) => destinations(url)));
});

afterEach(() => {
  vi.unstubAllGlobals();
  localStorage.clear();
});

test("can pick a room and thread from later pages without replacing destination reply text", async () => {
  const user = userEvent.setup();
  const close = vi.fn();
  const drafts = createThreadDraftStore();
  drafts.setReply(other.id, openThread.number, "Already writing");
  renderWithProviders(
    <ThreadDraftsContext.Provider value={drafts}>
      <QuoteDestinationModal source={source} currentRoom={makeRoom()} onClose={close} />
      <LocationLabel />
    </ThreadDraftsContext.Provider>,
  );
  const dialog = screen.getByRole("dialog", { name: "Choose where to quote" });
  const controls = within(dialog);
  const roomPicker = controls.getByRole("combobox", { name: "Room" });
  const threadPicker = controls.getByRole("combobox", { name: "Thread" });
  const next = { name: "Next page" };
  const go = controls.getByRole("button", { name: "Continue to thread" });
  expect(go).toBeDisabled();

  await user.click(await controls.findByRole("button", next));
  await waitFor(() => expect(roomPicker).toBeEnabled());
  await user.click(roomPicker);
  await user.click(await screen.findByRole("option", { name: other.name }));
  await waitFor(() => expect(controls.getAllByRole("button", next)).toHaveLength(2));
  await user.click(controls.getAllByRole("button", next)[1]!);
  await waitFor(() => expect(threadPicker).toBeEnabled());
  await user.click(threadPicker);
  await user.click(await screen.findByRole("option", { name: "#11 Second page thread" }));
  await user.click(go);

  expect(close).toHaveBeenCalledOnce();
  expect(screen.getByLabelText("Location")).toHaveTextContent("/rooms/14/threads/11");
  expect(drafts.getReply(other.id, openThread.number)).toBe("Already writing");
  expect(drafts.getQuote(other.id, openThread.number)).toMatchObject({ id: 19, roomId: 12, threadNumber: 8 });
});

test("shows a read failure and offers no destination action from an archived source", async () => {
  vi.stubGlobal("fetch", vi.fn().mockResolvedValue(jsonResponse(500, { status: 500 })));
  const close = vi.fn();
  renderWithProviders(<QuoteDestinationModal source={source} currentRoom={makeRoom({ archived: true })} onClose={close} />);
  const dialog = within(screen.getByRole("dialog", { name: "Choose where to quote" }));
  expect(await dialog.findByRole("alert")).toBeInTheDocument();
  expect(dialog.getByRole("combobox", { name: "Thread" })).toBeDisabled();
  expect(dialog.getByRole("button", { name: "Continue to thread" })).toBeDisabled();
  await userEvent.setup().click(dialog.getByRole("button", { name: "Cancel" }));
  expect(close).toHaveBeenCalledOnce();
});
