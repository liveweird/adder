import { afterEach, beforeEach, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { renderWithProviders, screen } from "../test/render";
import { jsonResponse } from "../test/http";
import ThreadWatcherModal from "./ThreadWatcherModal";

beforeEach(() => localStorage.setItem("adder.auth.token", "test-token"));
afterEach(() => {
  localStorage.clear();
  vi.unstubAllGlobals();
});

test("the roster pages and resets to the first page when it opens again", async () => {
  const user = userEvent.setup();
  const fetchPage = vi.fn(async (url: string) => {
    const page = Number(new URLSearchParams(url.split("?")[1]).get("page"));
    return jsonResponse(200, { page, pageSize: 20, total: 21, items: [{ userId: page, name: `Watcher ${page}` }] });
  });
  vi.stubGlobal("fetch", fetchPage);
  const onClose = vi.fn();
  const view = renderWithProviders(<ThreadWatcherModal roomId={12} number={42} opened onClose={onClose} />);
  expect(await screen.findByRole("link", { name: "Watcher 1" })).toHaveAttribute("href", "/people/1");

  await user.click(screen.getByRole("button", { name: "Next page" }));
  expect(await screen.findByRole("link", { name: "Watcher 2" })).toHaveAttribute("href", "/people/2");
  await user.click(screen.getByRole("button", { name: "Close" }));
  expect(onClose).toHaveBeenCalledOnce();
  view.rerender(<ThreadWatcherModal roomId={12} number={42} opened={false} onClose={onClose} />);
  view.rerender(<ThreadWatcherModal roomId={12} number={42} opened onClose={onClose} />);
  expect(await screen.findByRole("link", { name: "Watcher 1" })).toBeVisible();
});

test("the roster explains when nobody watches the thread", async () => {
  vi.stubGlobal("fetch", vi.fn(async () => jsonResponse(200, { page: 1, pageSize: 20, total: 0, items: [] })));
  renderWithProviders(<ThreadWatcherModal roomId={12} number={42} opened onClose={vi.fn()} />);
  expect(await screen.findByText("Nobody is watching this thread.")).toBeVisible();
  expect(screen.queryByRole("link")).toBeNull();
});

test("the roster shows an access failure without exposing any names", async () => {
  vi.stubGlobal("fetch", vi.fn(async () => jsonResponse(403, { title: "Forbidden", status: 403 })));
  renderWithProviders(<ThreadWatcherModal roomId={12} number={42} opened onClose={vi.fn()} />);
  expect(await screen.findByRole("alert")).toBeVisible();
  expect(screen.queryByRole("link")).toBeNull();
});
