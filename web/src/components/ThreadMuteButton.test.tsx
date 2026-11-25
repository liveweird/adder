import { afterEach, beforeEach, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { QueryClient } from "@tanstack/react-query";
import { renderWithProviders, screen } from "../test/render";
import { jsonResponse } from "../test/http";
import { roomKeys } from "../utils/roomKeys";
import ThreadMuteButton from "./ThreadMuteButton";

beforeEach(() => localStorage.setItem("adder.auth.token", "test-token"));
afterEach(() => {
  localStorage.clear();
  vi.unstubAllGlobals();
});

test("the mute button sends the opposite state and refreshes the thread detail", async () => {
  const user = userEvent.setup();
  const send = vi.fn(async () => new Response(null, { status: 204 }));
  vi.stubGlobal("fetch", send);
  const client = new QueryClient();
  const refresh = vi.spyOn(client, "invalidateQueries");
  const onError = vi.fn();
  renderWithProviders(<ThreadMuteButton roomId={12} number={42} muted onError={onError} />, { queryClient: client });

  const button = screen.getByRole("button", { name: "Mute thread notifications" });
  expect(button).toHaveAttribute("aria-pressed", "true");
  await user.click(button);
  await vi.waitFor(() => expect(refresh).toHaveBeenCalledWith({ queryKey: roomKeys.threadDetail(12, 42) }));
  expect(send).toHaveBeenCalledWith("/api/v1/rooms/12/threads/42/mute", expect.objectContaining({
    method: "PUT",
    body: JSON.stringify({ muted: false }),
  }));
  expect(onError.mock.calls).toEqual([[null]]);
});

test("a lost membership reports the refusal and refreshes room access", async () => {
  const user = userEvent.setup();
  vi.stubGlobal("fetch", vi.fn(async () => jsonResponse(404, { title: "Room not found", status: 404 })));
  const client = new QueryClient();
  const refresh = vi.spyOn(client, "invalidateQueries");
  const onError = vi.fn();
  renderWithProviders(<ThreadMuteButton roomId={12} number={42} muted={false} onError={onError} />, { queryClient: client });

  await user.click(screen.getByRole("button", { name: "Mute thread notifications" }));
  await vi.waitFor(() => expect(refresh).toHaveBeenCalledWith({ queryKey: roomKeys.all }));
  expect(onError).toHaveBeenLastCalledWith(expect.stringContaining("404"));
});

test("rate limiting shows an error without refreshing unrelated room access", async () => {
  const user = userEvent.setup();
  vi.stubGlobal("fetch", vi.fn(async () => jsonResponse(429, { title: "Too many requests", status: 429 })));
  const client = new QueryClient();
  const refresh = vi.spyOn(client, "invalidateQueries");
  const onError = vi.fn();
  renderWithProviders(<ThreadMuteButton roomId={12} number={42} muted={false} onError={onError} />, { queryClient: client });

  await user.click(screen.getByRole("button", { name: "Mute thread notifications" }));
  await vi.waitFor(() => expect(onError).toHaveBeenLastCalledWith(expect.stringContaining("429")));
  expect(refresh).not.toHaveBeenCalled();
});
