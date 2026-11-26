import { afterEach, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { fireEvent, screen, waitFor } from "@testing-library/react";
import { jsonResponse } from "../test/http";
import { renderWithProviders } from "../test/render";
import { messageHit } from "../test/searchFixtures";
import ThreadMessageSearch from "./ThreadMessageSearch";

const page = (items = [messageHit(501)], total = items.length, current = 1) => ({ items, page: current, pageSize: 20, total });

function renderSearch(overrides: Partial<Parameters<typeof ThreadMessageSearch>[0]> = {}) {
  const trigger = document.createElement("button");
  trigger.textContent = "Find trigger";
  trigger.dataset.threadFindTestTrigger = "";
  document.body.append(trigger);
  const onClose = vi.fn();
  const onSelect = vi.fn();
  const view = renderWithProviders(
    <ThreadMessageSearch
      roomId={12}
      threadNumber={42}
      open
      triggerRef={{ current: trigger }}
      locationSearch="?scope=all&focus=1"
      focusView
      onClose={onClose}
      onSelect={onSelect}
      {...overrides}
    />,
    { route: "/rooms/12/threads/42?scope=all&focus=1" },
  );
  return { trigger, onClose, onSelect, ...view };
}

afterEach(() => {
  document.querySelectorAll("[data-thread-find-test-trigger]").forEach((trigger) => trigger.remove());
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
  localStorage.clear();
});

test("opens focused, validates without a request, and closes back to its trigger", async () => {
  const fetch = vi.fn();
  vi.stubGlobal("fetch", fetch);
  const user = userEvent.setup();
  const { trigger, onClose } = renderSearch();
  const input = screen.getByRole("searchbox", { name: "Search messages" });
  await waitFor(() => expect(input).toHaveFocus());
  await user.type(input, "x{Enter}");
  expect(await screen.findByText("Type at least two characters, with a letter or a digit.")).toBeVisible();
  expect(fetch).not.toHaveBeenCalled();
  await user.click(screen.getByRole("button", { name: "Close search" }));
  expect(onClose).toHaveBeenCalledOnce();
  await waitFor(() => expect(trigger).toHaveFocus());
});

test("inserts search syntax at the caret and returns focus to the query without a mode switch", async () => {
  vi.stubGlobal("fetch", vi.fn());
  const user = userEvent.setup();
  renderSearch();
  const input = screen.getByRole("searchbox", { name: "Search messages" });
  await user.type(input, "disk ");
  await user.click(screen.getByRole("button", { name: "Search syntax" }));
  await user.click(await screen.findByRole("button", { name: /in:"Night Shift" Search in one room/ }));
  expect(input).toHaveValue("disk in:");
  expect(input).toHaveFocus();
  expect(screen.queryByRole("switch", { name: /structured mode/i })).not.toBeInTheDocument();
});

test("submits a unified query, renders highlighted results, and selects a direct jump", async () => {
  const fetch = vi.fn((_input: RequestInfo | URL, _init?: RequestInit) => Promise.resolve(jsonResponse(200, page())));
  vi.stubGlobal("fetch", fetch);
  localStorage.setItem("adder.auth.token", "fake-token");
  const user = userEvent.setup();
  const { onClose, onSelect } = renderSearch();
  const input = screen.getByRole("searchbox", { name: "Search messages" });
  await user.type(input, '"outage"');
  await user.click(screen.getByRole("button", { name: "Search this thread" }));
  const result = await screen.findByRole("link", { name: /the outage started/i });
  const asked = new URL(String(fetch.mock.calls[0]![0]), "http://adder.test");
  expect(asked.pathname).toBe("/api/v1/rooms/12/threads/42/messages/search");
  expect(Object.fromEntries(asked.searchParams)).toMatchObject({ q: '"outage"', syntax: "unified", page: "1", pageSize: "20" });
  expect(fetch.mock.calls[0]![1]?.signal).toBeInstanceOf(AbortSignal);
  expect(screen.getByRole("status")).toHaveTextContent("1 message found");
  expect(result.querySelector("mark")).toHaveTextContent("outage");
  expect(result).toHaveAttribute("href", "/rooms/12/threads/42?message=501&scope=all&focus=1");
  await user.click(screen.getByRole("button", { name: "Search this thread" }));
  await waitFor(() => expect(fetch).toHaveBeenCalledTimes(2));
  const refreshedResult = await screen.findByRole("link", { name: /the outage started/i });
  fireEvent.click(refreshedResult, { ctrlKey: true });
  expect(onSelect).not.toHaveBeenCalled();
  expect(onClose).not.toHaveBeenCalled();
  await user.click(refreshedResult);
  expect(onSelect).toHaveBeenCalledWith(501);
  expect(onClose).toHaveBeenCalledOnce();
});

test("hides previously cached snippets when a repeated search discovers the room is unavailable", async () => {
  const fetch = vi.fn()
    .mockResolvedValueOnce(jsonResponse(200, page()))
    .mockResolvedValueOnce(jsonResponse(404, { title: "Not found", status: 404 }));
  vi.stubGlobal("fetch", fetch);
  localStorage.setItem("adder.auth.token", "fake-token");
  const user = userEvent.setup();
  renderSearch();
  await user.type(screen.getByRole("searchbox", { name: "Search messages" }), "outage{Enter}");
  expect(await screen.findByRole("link", { name: /the outage started/i })).toBeVisible();
  await user.click(screen.getByRole("button", { name: "Search this thread" }));
  expect(await screen.findByText("This room no longer exists, or you are no longer a member of it.")).toBeVisible();
  expect(screen.queryByRole("link", { name: /the outage started/i })).not.toBeInTheDocument();
});

test("keeps the submitted investigation while closed and pages through results", async () => {
  const fetch = vi.fn((input: RequestInfo | URL) => {
    const current = Number(new URL(String(input), "http://adder.test").searchParams.get("page"));
    return Promise.resolve(jsonResponse(200, page([messageHit(current === 1 ? 501 : 401)], 21, current)));
  });
  vi.stubGlobal("fetch", fetch);
  localStorage.setItem("adder.auth.token", "fake-token");
  const user = userEvent.setup();
  const view = renderSearch();
  const input = screen.getByRole("searchbox", { name: "Search messages" });
  await user.type(input, "outage{Enter}");
  expect(await screen.findByRole("link", { name: /the outage started/i })).toBeVisible();
  await user.click(screen.getByRole("button", { name: "2" }));
  await waitFor(() => expect(fetch).toHaveBeenCalledTimes(2));
  expect(new URL(String(fetch.mock.calls[1]![0]), "http://adder.test").searchParams.get("page")).toBe("2");
  view.rerender(
    <ThreadMessageSearch
      roomId={12}
      threadNumber={42}
      open={false}
      triggerRef={{ current: view.trigger }}
      locationSearch="?scope=all&focus=1"
      focusView
      onClose={view.onClose}
      onSelect={view.onSelect}
    />,
  );
  expect(screen.queryByRole("searchbox", { name: "Search messages" })).not.toBeInTheDocument();
  view.rerender(
    <ThreadMessageSearch
      roomId={12}
      threadNumber={42}
      open
      triggerRef={{ current: view.trigger }}
      locationSearch="?scope=all&focus=1"
      focusView
      onClose={view.onClose}
      onSelect={view.onSelect}
    />,
  );
  expect(screen.getByRole("searchbox", { name: "Search messages" })).toHaveValue("outage");
  expect(fetch).toHaveBeenCalledTimes(2);
});
