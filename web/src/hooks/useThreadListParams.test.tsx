import type { ReactNode } from "react";
import { afterEach, describe, expect, test } from "vitest";
import { act, renderHook } from "@testing-library/react";
import { MemoryRouter, useLocation, useNavigate } from "react-router-dom";
import { useThreadListParams } from "./useThreadListParams";

const KEY = "adder.viewSettings.roomThreads.12.query";

/** The room is the one the PATH names (`/rooms/<id>/…`, anything else reads as `fallback`), like the route parameter it is in the app: a room switch is a navigation. */
function useRoomFromPath(fallback: number) {
  const id = Number(useLocation().pathname.split("/")[2]);
  return Number.isInteger(id) && id > 0 ? id : fallback;
}

function renderParams(route: string, state?: unknown, fallbackRoom = 12) {
  const entry = state === undefined ? route : { pathname: route.split("?")[0], search: route.includes("?") ? `?${route.split("?")[1]}` : "", state };
  return renderHook(() => ({ list: useThreadListParams(useRoomFromPath(fallbackRoom)), location: useLocation(), navigate: useNavigate() }), {
    wrapper: ({ children }: { children: ReactNode }) => <MemoryRouter initialEntries={[entry]}>{children}</MemoryRouter>,
  });
}

describe("useThreadListParams", () => {
  afterEach(() => localStorage.clear());

  test("reads the list's state from the URL; a bare one is the plain list", () => {
    expect(renderParams("/rooms/12").result.current.list.params).toEqual({ scope: "open", order: "activity", status: [], owner: null, mention: false, watching: false, unread: false, tag: [], threadTag: [], field: [], page: 1 });
    expect(renderParams("/rooms/12?scope=closed&order=number&page=3&scope=all").result.current.list.params).toEqual({ scope: "closed", order: "number", status: [], owner: null, mention: false, watching: false, unread: false, tag: [], threadTag: [], field: [], page: 3 });
    expect(renderParams("/rooms/12?scope=junk&page=0").result.current.list.params).toEqual({ scope: "open", order: "activity", status: [], owner: null, mention: false, watching: false, unread: false, tag: [], threadTag: [], field: [], page: 1 });
  });

  test("a write uses replace: it adds no history entry, so Back leaves the list", () => {
    const { result } = renderParams("/start");
    act(() => result.current.navigate("/rooms/12"));
    act(() => result.current.list.update({ scope: "closed" }));
    expect(result.current.location.search).toBe("?scope=closed");
    act(() => result.current.navigate(-1));
    expect(result.current.location.pathname).toBe("/start");
  });

  test("a write keeps location.state and the keys that are not the list's, and a change resets the page", () => {
    const { result } = renderParams("/rooms/12/threads/7?page=3&message=9", { focusPane: true });
    act(() => result.current.list.update({ order: "newest" }));
    expect(result.current.location.search).toBe("?message=9&order=newest");
    expect(result.current.location.state).toEqual({ focusPane: true });
    act(() => result.current.list.update({ page: 2 }));
    expect(result.current.location.search).toBe("?message=9&order=newest&page=2");
  });

  test("a stale handler builds on the latest parameters", () => {
    const { result } = renderParams("/rooms/12");
    const stale = result.current.list.update;
    act(() => result.current.navigate("/rooms/12?scope=closed"));
    act(() => stale({ order: "number" }));
    expect(result.current.location.search).toBe("?scope=closed&order=number");
  });

  test("two writes in one event both land", () => {
    const { result } = renderParams("/rooms/12");
    act(() => {
      result.current.list.update({ scope: "all" });
      result.current.list.update({ order: "newest" });
    });
    expect(result.current.location.search).toBe("?scope=all&order=newest");
  });

  test("every write remembers the room's query, the page left out; the plain list remembers nothing", () => {
    const { result } = renderParams("/rooms/12");
    act(() => result.current.list.update({ scope: "closed" }));
    act(() => result.current.list.update({ page: 4 }));
    expect(JSON.parse(localStorage.getItem(KEY) ?? "null")).toBe("scope=closed");
    expect(localStorage.getItem("adder.viewSettings.roomThreads.13.query")).toBeNull();
    act(() => result.current.list.update({ scope: "open" }));
    expect(JSON.parse(localStorage.getItem(KEY) ?? "null")).toBe("");
  });

  test("the remembered query is restored once on a bare URL, by replace, and the state reads it at once", () => {
    localStorage.setItem(KEY, JSON.stringify("scope=closed&order=number"));
    const { result } = renderParams("/rooms/12/threads/7", { focusPane: true });
    expect(result.current.list.params).toEqual({ scope: "closed", order: "number", status: [], owner: null, mention: false, watching: false, unread: false, tag: [], threadTag: [], field: [], page: 1 });
    expect(result.current.location.search).toBe("?scope=closed&order=number");
    expect(result.current.location.state).toEqual({ focusPane: true });
    // Choosing the plain list afterwards is a choice: the bare URL is NOT restored again.
    act(() => result.current.list.update({ scope: "open", order: "activity" }));
    expect(result.current.location.search).toBe("");
    expect(result.current.list.params).toEqual({ scope: "open", order: "activity", status: [], owner: null, mention: false, watching: false, unread: false, tag: [], threadTag: [], field: [], page: 1 });
  });

  test("a URL that spells a list key is never overwritten by the remembered query", () => {
    localStorage.setItem(KEY, JSON.stringify("scope=closed"));
    const { result } = renderParams("/rooms/12?order=newest");
    expect(result.current.location.search).toBe("?order=newest");
    expect(result.current.list.params).toEqual({ scope: "open", order: "newest", status: [], owner: null, mention: false, watching: false, unread: false, tag: [], threadTag: [], field: [], page: 1 });
  });

  test("another room's remembered query is not this room's; a corrupt one is ignored", () => {
    localStorage.setItem("adder.viewSettings.roomThreads.13.query", JSON.stringify("scope=closed"));
    localStorage.setItem(KEY, JSON.stringify(42));
    const { result } = renderParams("/rooms/12");
    expect(result.current.location.search).toBe("");
    expect(result.current.list.params.scope).toBe("open");
  });

  test("Clear drops the panel filters and the page, keeps the scope, the order and the other keys, and remembers what remains", () => {
    localStorage.setItem(KEY, JSON.stringify("scope=closed&order=number&status=triage"));
    const { result } = renderParams("/rooms/12?scope=closed&order=number&status=triage&owner=me&tag=outage&field=severity%3As1&page=3&message=9");
    act(() => result.current.list.clear());
    expect(result.current.location.search).toBe("?message=9&scope=closed&order=number");
    expect(JSON.parse(localStorage.getItem(KEY) ?? "null")).toBe("scope=closed&order=number");
    expect(result.current.list.params).toEqual({ scope: "closed", order: "number", status: [], owner: null, mention: false, watching: false, unread: false, tag: [], threadTag: [], field: [], page: 1 });
  });

  test("Clear on a list with nothing but filters leaves the plain list and forgets the remembered query", () => {
    localStorage.setItem(KEY, JSON.stringify("status=triage"));
    const { result } = renderParams("/rooms/12?status=triage&message=9");
    act(() => result.current.list.clear());
    expect(result.current.location.search).toBe("?message=9");
    expect(JSON.parse(localStorage.getItem(KEY) ?? "null")).toBe("");
    expect(result.current.list.params).toEqual({ scope: "open", order: "activity", status: [], owner: null, mention: false, watching: false, unread: false, tag: [], threadTag: [], field: [], page: 1 });
  });

  test("the multi-value filters round-trip through the URL and the remembered query, and Clear drops them but not the scope or the order", () => {
    const { result } = renderParams("/rooms/12?message=9");
    act(() => result.current.list.update({ status: ["triage", "fixing"], owner: "me", field: ["severity:s1", "severity:s2", "lead:42"] }));
    expect(result.current.location.search).toBe("?message=9&status=triage&status=fixing&owner=me&field=severity%3As1&field=severity%3As2&field=lead%3A42");
    expect(JSON.parse(localStorage.getItem(KEY) ?? "null")).toBe("status=triage&status=fixing&owner=me&field=severity%3As1&field=severity%3As2&field=lead%3A42");
    expect(result.current.list.params).toMatchObject({ status: ["triage", "fixing"], owner: "me", field: ["severity:s1", "severity:s2", "lead:42"] });
    act(() => result.current.list.update({ scope: "all" }));
    act(() => result.current.list.clear());
    expect(result.current.location.search).toBe("?message=9&scope=all");
    expect(JSON.parse(localStorage.getItem(KEY) ?? "null")).toBe("scope=all");
  });

  test("Mentions me is remembered with the rest, restored onto a bare URL, and dropped by Clear", () => {
    const { result } = renderParams("/rooms/12");
    act(() => result.current.list.update({ mention: true }));
    expect(result.current.location.search).toBe("?mention=me");
    expect(JSON.parse(localStorage.getItem(KEY) ?? "null")).toBe("mention=me");
    act(() => result.current.list.clear());
    expect(result.current.location.search).toBe("");
    expect(localStorage.getItem(KEY)).toBe(JSON.stringify(""));
    localStorage.setItem(KEY, JSON.stringify("mention=me"));
    const restored = renderParams("/rooms/12");
    expect(restored.result.current.list.params.mention).toBe(true);
    expect(restored.result.current.location.search).toBe("?mention=me");
  });

  test("Watching is remembered with the rest, restored onto a bare URL, and dropped by Clear", () => {
    const { result } = renderParams("/rooms/12");
    act(() => result.current.list.update({ watching: true }));
    expect(result.current.location.search).toBe("?watching=me");
    expect(JSON.parse(localStorage.getItem(KEY) ?? "null")).toBe("watching=me");
    act(() => result.current.list.clear());
    expect(result.current.location.search).toBe("");
    expect(localStorage.getItem(KEY)).toBe(JSON.stringify(""));
    localStorage.setItem(KEY, JSON.stringify("watching=me"));
    const restored = renderParams("/rooms/12");
    expect(restored.result.current.list.params.watching).toBe(true);
    expect(restored.result.current.location.search).toBe("?watching=me");
  });

  test("a remembered multi-value query is restored onto a bare URL", () => {
    localStorage.setItem(KEY, JSON.stringify("status=triage&status=fixing&owner=7"));
    const { result } = renderParams("/rooms/12");
    expect(result.current.list.params).toMatchObject({ status: ["triage", "fixing"], owner: "7" });
    expect(result.current.location.search).toBe("?status=triage&status=fixing&owner=7");
  });

  test("the restore is a replace: it adds no history entry, so Back leaves the room", () => {
    localStorage.setItem(KEY, JSON.stringify("scope=closed"));
    const { result } = renderParams("/start");
    act(() => result.current.navigate("/rooms/12"));
    expect(result.current.location.search).toBe("?scope=closed");
    act(() => result.current.navigate(-1));
    expect(result.current.location.pathname).toBe("/start");
  });

  test("Back onto a bare entry restores the remembered query again, onto that entry", () => {
    const { result } = renderParams("/rooms/12");
    act(() => result.current.navigate("/rooms/12/threads/7"));
    act(() => result.current.list.update({ order: "number" }));
    expect(result.current.location.search).toBe("?order=number");
    act(() => result.current.navigate(-1));
    expect(result.current.location.pathname).toBe("/rooms/12");
    expect(result.current.location.search).toBe("?order=number");
    expect(result.current.list.params.order).toBe("number");
    // Forward lands on the entry that was written, still in step.
    act(() => result.current.navigate(1));
    expect(result.current.location.pathname).toBe("/rooms/12/threads/7");
    expect(result.current.location.search).toBe("?order=number");
  });

  test("a room switch with the hook mounted restores the new room's own query, and a room with none stays bare", () => {
    localStorage.setItem(KEY, JSON.stringify("scope=closed"));
    localStorage.setItem("adder.viewSettings.roomThreads.13.query", JSON.stringify("order=newest"));
    const { result } = renderParams("/rooms/12");
    expect(result.current.location.search).toBe("?scope=closed");
    act(() => result.current.navigate("/rooms/13"));
    expect(result.current.location.search).toBe("?order=newest");
    expect(result.current.list.params).toEqual({ scope: "open", order: "newest", status: [], owner: null, mention: false, watching: false, unread: false, tag: [], threadTag: [], field: [], page: 1 });
    act(() => result.current.navigate("/rooms/14"));
    expect(result.current.location.search).toBe("");
    expect(result.current.list.params).toEqual({ scope: "open", order: "activity", status: [], owner: null, mention: false, watching: false, unread: false, tag: [], threadTag: [], field: [], page: 1 });
    // Nothing of room 12's or 13's was touched along the way.
    expect(JSON.parse(localStorage.getItem(KEY) ?? "null")).toBe("scope=closed");
    expect(JSON.parse(localStorage.getItem("adder.viewSettings.roomThreads.13.query") ?? "null")).toBe("order=newest");
    expect(localStorage.getItem("adder.viewSettings.roomThreads.14.query")).toBeNull();
  });

  test("a handler captured in room A's render files its write under the room the latest render is for", () => {
    const { result } = renderParams("/rooms/12");
    const staleUpdate = result.current.list.update;
    const staleClear = result.current.list.clear;
    act(() => result.current.navigate("/rooms/13"));
    act(() => staleUpdate({ status: ["triage"] }));
    expect(JSON.parse(localStorage.getItem("adder.viewSettings.roomThreads.13.query") ?? "null")).toBe("status=triage");
    expect(localStorage.getItem(KEY)).toBeNull();
    act(() => staleClear());
    expect(JSON.parse(localStorage.getItem("adder.viewSettings.roomThreads.13.query") ?? "null")).toBe("");
    expect(localStorage.getItem(KEY)).toBeNull();
  });

  test("a page in a hand-edited remembered value is never restored", () => {
    localStorage.setItem(KEY, JSON.stringify("scope=closed&page=7"));
    const { result } = renderParams("/rooms/12");
    expect(result.current.location.search).toBe("?scope=closed");
    expect(result.current.list.params).toEqual({ scope: "closed", order: "activity", status: [], owner: null, mention: false, watching: false, unread: false, tag: [], threadTag: [], field: [], page: 1 });
  });
});
