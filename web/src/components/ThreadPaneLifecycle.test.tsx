import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { cleanup } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { useLocation } from "react-router-dom";
import { jsonResponse } from "../test/http";
import { makeMessage, makeRoom, makeThread, messagePage } from "../test/messageFixtures";
import { renderWithProviders, screen, waitFor, within } from "../test/render";
import ThreadPane from "./ThreadPane";

const WORKFLOW = {
  revision: 1,
  statuses: [{ key: "triage", label: "Triage", colour: "BLUE", category: "OPEN", initial: true, threadCount: 1 }],
  fields: [],
};

function LocationProbe() {
  const location = useLocation();
  return <output aria-label="Current location">{`${location.pathname}${location.search}`}</output>;
}

describe("ThreadPane lifecycle integration", () => {
  let request: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    request = vi.fn();
    vi.stubGlobal("fetch", request);
    localStorage.setItem("adder.auth.token", "fake-token");
    localStorage.setItem("adder.auth.userId", "5");
    localStorage.setItem("adder.auth.roles", "[]");
  });

  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  function serve(thread: ReturnType<typeof makeThread>, mutation?: (url: string, init?: RequestInit) => Response | undefined) {
    request.mockImplementation((url: string, init?: RequestInit) => {
      const mutated = mutation?.(url, init);
      if (mutated) return Promise.resolve(mutated);
      if (url === "/api/v1/rooms/12/threads/42") return Promise.resolve(jsonResponse(200, thread));
      if (url === "/api/v1/rooms/12/workflow") return Promise.resolve(jsonResponse(200, WORKFLOW));
      if (url.startsWith("/api/v1/rooms/12/threads/42/messages?")) {
        return Promise.resolve(jsonResponse(200, messagePage([makeMessage({ body: "Preserved history" })])));
      }
      if (url.startsWith("/api/v1/rooms/12/threads/42/events?")) {
        return Promise.resolve(jsonResponse(200, { items: [], page: 1, pageSize: 50, total: 0 }));
      }
      return Promise.resolve(jsonResponse(404, { title: "Not found", status: 404 }));
    });
  }

  test("a merged source keeps history and steward deletion, but removes canonical-only controls", async () => {
    const user = userEvent.setup();
    serve(makeThread({ mergedIntoThreadNumber: 7 }));
    renderWithProviders(
      <>
        <ThreadPane room={makeRoom()} numberParam="42" />
        <LocationProbe />
      </>,
      { route: "/rooms/12/threads/42?scope=closed&order=number&page=2" },
    );

    expect(await screen.findByText("Preserved history")).toBeInTheDocument();
    expect(screen.getByText("This conversation was merged into #7. Its history remains available here.")).toBeInTheDocument();
    expect(screen.queryByRole("textbox", { name: "Reply" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Watch this thread" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /Mute/ })).not.toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "Thread actions" }));
    expect(screen.queryByRole("menuitem", { name: "Merge duplicate" })).not.toBeInTheDocument();
    expect(screen.getByRole("menuitem", { name: "Delete thread" })).toBeInTheDocument();
    await user.keyboard("{Escape}");
    await user.click(screen.getByRole("link", { name: "Open #7" }));
    expect(screen.getByRole("status", { name: "Current location" })).toHaveTextContent(
      "/rooms/12/threads/7?scope=closed&order=number&page=2",
    );
  });

  test("successful merge and delete actions navigate while retaining list state", async () => {
    const user = userEvent.setup();
    serve(makeThread(), (url, init) => {
      if (url === "/api/v1/rooms/12/threads/7") return jsonResponse(200, makeThread({ number: 7, title: "Canonical" }));
      if (url.endsWith("/42/merge") && init?.method === "PUT") return new Response(null, { status: 204 });
      return undefined;
    });
    const first = renderWithProviders(
      <>
        <ThreadPane room={makeRoom()} numberParam="42" />
        <LocationProbe />
      </>,
      { route: "/rooms/12/threads/42?scope=all&order=number&page=3" },
    );
    await screen.findByText("Preserved history");
    await user.click(screen.getByRole("button", { name: "Thread actions" }));
    await user.click(screen.getByRole("menuitem", { name: "Merge duplicate" }));
    const merge = await screen.findByRole("dialog", { name: "Merge duplicate thread" });
    await user.type(within(merge).getByRole("textbox", { name: "Target thread number" }), "7");
    await user.click(await within(merge).findByRole("button", { name: "Merge into #7" }));
    await waitFor(() => expect(screen.getByRole("status", { name: "Current location" })).toHaveTextContent(
      "/rooms/12/threads/7?scope=all&order=number&page=3",
    ));
    first.unmount();

    serve(makeThread({ mergedIntoThreadNumber: 7 }), (url, init) =>
      url.endsWith("/threads/42") && init?.method === "DELETE" ? new Response(null, { status: 204 }) : undefined,
    );
    renderWithProviders(
      <>
        <ThreadPane room={makeRoom()} numberParam="42" />
        <LocationProbe />
      </>,
      { route: "/rooms/12/threads/42?scope=all&order=number&page=3" },
    );
    await screen.findByText("Preserved history");
    await user.click(screen.getByRole("button", { name: "Thread actions" }));
    await user.click(screen.getByRole("menuitem", { name: "Delete thread" }));
    const remove = await screen.findByRole("dialog", { name: "Delete #42?" });
    await user.click(within(remove).getByRole("button", { name: "Delete thread" }));
    await waitFor(() => expect(screen.getByRole("status", { name: "Current location" })).toHaveTextContent(
      "/rooms/12?scope=all&order=number&page=3",
    ));
  });
});
