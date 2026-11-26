import { QueryClient } from "@tanstack/react-query";
import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import type { MessageActions } from "../utils/messageAccess";
import { makeMessage } from "../test/messageFixtures";
import { renderWithProviders } from "../test/render";
import { jsonResponse } from "../test/http";
import { browserTimeZone, formatDateTime } from "../utils/formatDate";
import { createLiveInvalidator } from "../utils/liveKeys";
import { FakeClock } from "../test/socket";
import MessageItem from "./MessageItem";

const ZONE = browserTimeZone();
const message = makeMessage({ id: 9 });
const actions: MessageActions = { edit: true, remove: "delete" };
const menuName = `Operations for message by Cy Author on ${formatDateTime(message.createdAt, ZONE)}`;
const item = (value = message, available = actions) => (
  <ul><MessageItem roomId={12} message={value} actions={available} zone={ZONE} /></ul>
);

async function startDelete(): Promise<void> {
  const user = userEvent.setup();
  await user.click(screen.getByRole("button", { name: menuName }));
  await user.click(await screen.findByRole("menuitem", { name: "Delete" }));
  await user.click(within(await screen.findByRole("dialog", { name: "Delete this message?" })).getByRole("button", { name: "Delete" }));
}

describe("MessageItem delete commit", () => {
  beforeEach(() => localStorage.setItem("adder.auth.token", "fake-token"));
  afterEach(() => {
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  test("a later tombstone hides a stale delete error", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(jsonResponse(409, {
      type: "urn:adder:problem:thread-closed",
      status: 409,
      title: "Closed",
    })));
    const { rerender } = renderWithProviders(item());
    await startDelete();
    expect(await screen.findByRole("alert")).toBeInTheDocument();
    rerender(item({ ...message, body: null, deleted: true }, { edit: false, remove: null }));
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  test("an exact live delete suppresses a lost-response alert", async () => {
    let rejectDelete!: (reason: Error) => void;
    const fetch = vi.fn((_url: string, init?: RequestInit) => init?.method === "DELETE"
      ? new Promise<Response>((_resolve, reject) => { rejectDelete = reject; })
      : Promise.resolve(new Response(null, { status: 204 })));
    vi.stubGlobal("fetch", fetch);
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const invalidator = createLiveInvalidator(queryClient, new FakeClock());
    renderWithProviders(item(), { queryClient });
    await startDelete();
    await waitFor(() => expect(fetch).toHaveBeenCalledOnce());
    invalidator.handle({ type: "event", kind: "message.deleted", roomId: 12, threadNumber: 42, messageId: 9 });
    rejectDelete(new Error("response lost"));
    await waitFor(() => expect(screen.queryByRole("dialog", { name: "Delete this message?" })).not.toBeInTheDocument());
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    invalidator.dispose();
    queryClient.clear();
  });
});
