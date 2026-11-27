import { act, cleanup, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { useLocation, useNavigate } from "react-router-dom";
import { jsonResponse } from "../test/http";
import { makeMessage, makeThread, messagePage } from "../test/messageFixtures";
import { renderWithProviders } from "../test/render";
import type { LifecycleThread } from "../api/threadLifecycle";

const mergeCallbacks = new Map<number, Array<(target: number) => void | Promise<void>>>();

vi.mock("./ThreadLifecycleActions", () => ({
  default: ({ thread, onMerged }: { thread: LifecycleThread; onMerged: (target: number) => void | Promise<void> }) => {
    const callbacks = mergeCallbacks.get(thread.number) ?? [];
    if (!callbacks.includes(onMerged)) mergeCallbacks.set(thread.number, [...callbacks, onMerged]);
    return null;
  },
}));

import ThreadPane from "./ThreadPane";

const ROOM = {
  id: 12,
  name: "Night Shift",
  description: null,
  accessType: "RESTRICTED_HIDDEN" as const,
  archived: false,
  archivedAt: null,
  role: "OWNER" as const,
  memberCount: 3,
  ownerCount: 1,
  workflowRevision: 1,
  lastActivityAt: 1,
  createdAt: 1,
  updatedAt: 1,
};
const WORKFLOW = {
  revision: 1,
  statuses: [{ key: "triage", label: "Triage", colour: "BLUE", category: "OPEN", initial: true, threadCount: 2 }],
  fields: [],
};
const thread = (number: number): LifecycleThread => ({
  ...makeThread(),
  id: number,
  number,
  title: number === 42 ? "Source" : "Other",
  mergedIntoThreadNumber: null,
  mergedSourceCount: 0,
});

function Probe() {
  const location = useLocation();
  return <output aria-label="location">{`${location.pathname}${location.search}`}</output>;
}

describe("ThreadPane merge completion navigation", () => {
  beforeEach(() => {
    mergeCallbacks.clear();
    vi.stubGlobal("fetch", vi.fn((url: string) => {
      const match = url.match(/^\/api\/v1\/rooms\/12\/threads\/(\d+)$/);
      if (match) return Promise.resolve(jsonResponse(200, thread(Number(match[1]))));
      if (url === "/api/v1/rooms/12/workflow") return Promise.resolve(jsonResponse(200, WORKFLOW));
      if (url.includes("/messages?")) return Promise.resolve(jsonResponse(200, messagePage([makeMessage()])));
      if (url.includes("/events?")) return Promise.resolve(jsonResponse(200, { items: [], page: 1, pageSize: 10, total: 0 }));
      return Promise.resolve(jsonResponse(404, { title: "Not found", status: 404 }));
    }));
    localStorage.setItem("adder.auth.token", "fake-token");
    localStorage.setItem("adder.auth.userId", "5");
  });

  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  test("a completion callback from an older pane cannot navigate the current pane", async () => {
    function Harness() {
      const location = useLocation();
      const navigate = useNavigate();
      return (
        <>
          <button onClick={() => navigate("/rooms/12/threads/43?scope=closed")}>switch</button>
          <ThreadPane room={ROOM} numberParam={location.pathname.split("/").at(-1)} />
          <Probe />
        </>
      );
    }
    const user = userEvent.setup();
    renderWithProviders(<Harness />, { route: "/rooms/12/threads/42?scope=all" });
    await screen.findByRole("region", { name: "#42 Source" });
    const oldCompletion = mergeCallbacks.get(42)![0];

    await user.click(screen.getByRole("button", { name: "switch" }));
    await screen.findByRole("region", { name: "#43 Other" });
    await act(async () => { await oldCompletion(7); });

    expect(screen.getByLabelText("location")).toHaveTextContent("/rooms/12/threads/43?scope=closed");
  });

  test("a held completion callback uses the newest list filters", async () => {
    function Harness() {
      const navigate = useNavigate();
      return (
        <>
          <button onClick={() => navigate("?scope=closed&order=number")}>change filters</button>
          <ThreadPane room={ROOM} numberParam="42" />
          <Probe />
        </>
      );
    }
    const user = userEvent.setup();
    renderWithProviders(<Harness />, { route: "/rooms/12/threads/42?scope=all" });
    await screen.findByRole("region", { name: "#42 Source" });
    const heldCompletion = mergeCallbacks.get(42)![0];

    await user.click(screen.getByRole("button", { name: "change filters" }));
    await act(async () => { await heldCompletion(7); });

    await waitFor(() => expect(screen.getByLabelText("location"))
      .toHaveTextContent("/rooms/12/threads/7?scope=closed&order=number"));
  });
});
