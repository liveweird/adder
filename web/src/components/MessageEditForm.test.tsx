import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { QueryClient } from "@tanstack/react-query";
import { fireEvent, screen, waitFor } from "@testing-library/react";
import MessageEditForm from "./MessageEditForm";
import type { MessageResponse } from "../api/messages";
import { jsonResponse } from "../test/http";
import { makeMessage } from "../test/messageFixtures";
import { renderWithProviders } from "../test/render";

describe("MessageEditForm", () => {
  let mockFetch: ReturnType<typeof vi.fn>;
  const puts = () => mockFetch.mock.calls.filter(([, init]) => (init as RequestInit | undefined)?.method === "PUT") as [string, RequestInit][];

  beforeEach(() => {
    mockFetch = vi.fn().mockResolvedValue(new Response(null, { status: 204 }));
    vi.stubGlobal("fetch", mockFetch);
    localStorage.setItem("adder.auth.token", "fake-token");
  });
  afterEach(() => {
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  const box = () => screen.getByRole("textbox", { name: "Edit message" });

  function renderForm(message: MessageResponse, queryClient?: QueryClient) {
    const onDone = vi.fn();
    const onError = vi.fn();
    renderWithProviders(<MessageEditForm roomId={12} message={message} onDone={onDone} onError={onError} />, { queryClient });
    return { onDone, onError };
  }

  test("starts from the body; Save sends the normalised text, re-reads the room's threads and the rail, then hands back", async () => {
    const user = userEvent.setup();
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const invalidate = vi.spyOn(queryClient, "invalidateQueries");
    const { onDone } = renderForm(makeMessage({ id: 9, body: "first" }), queryClient);
    expect(box()).toHaveValue("first");
    expect(screen.getByRole("button", { name: "Save" })).toBeDisabled();
    await user.type(box(), "  \n\n");
    await user.type(box(), "!");
    await user.click(screen.getByRole("button", { name: "Save" }));
    await waitFor(() => expect(onDone).toHaveBeenCalledTimes(1));
    expect(puts()).toHaveLength(1);
    expect(puts()[0][0]).toBe("/api/v1/rooms/12/messages/9");
    expect(JSON.parse(puts()[0][1].body as string)).toEqual({ body: "first  \n\n!" });
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ["rooms", "threads", 12] });
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ["rooms", "rail"] });
    expect(invalidate).not.toHaveBeenCalledWith({ queryKey: ["rooms"] });
  });

  test("Ctrl+Enter saves too", async () => {
    const user = userEvent.setup();
    const { onDone } = renderForm(makeMessage({ id: 9, body: "first" }));
    await user.type(box(), "?");
    await user.keyboard("{Control>}{Enter}{/Control}");
    await waitFor(() => expect(onDone).toHaveBeenCalled());
    expect(JSON.parse(puts()[0][1].body as string)).toEqual({ body: "first?" });
  });

  test("while a save is in flight the box is read-only, not disabled, and a second Ctrl+Enter does not save again", async () => {
    let finish!: (response: Response) => void;
    mockFetch.mockImplementation(() => new Promise<Response>((resolve) => (finish = resolve)));
    const user = userEvent.setup();
    renderForm(makeMessage({ id: 9, body: "first" }));
    await user.type(box(), "!");
    await user.keyboard("{Control>}{Enter}{Enter}{/Control}");
    await waitFor(() => expect(box()).toHaveAttribute("readonly"));
    expect(box()).not.toBeDisabled();
    expect(puts()).toHaveLength(1);
    finish(new Response(null, { status: 204 }));
  });

  test("a body that normalises to the original is not sent", async () => {
    const user = userEvent.setup();
    const { onDone } = renderForm(makeMessage({ body: "first" }));
    await user.type(box(), "   ");
    await user.click(screen.getByRole("button", { name: "Save" }));
    expect(onDone).toHaveBeenCalledTimes(1);
    expect(puts()).toEqual([]);
  });

  test("an empty or control-character body is refused before any request, through onError", async () => {
    const user = userEvent.setup();
    const { onError } = renderForm(makeMessage({ body: "first" }));
    await user.clear(box());
    await user.click(screen.getByRole("button", { name: "Save" }));
    expect(onError).toHaveBeenLastCalledWith("The message must be 1–8000 characters");
    fireEvent.change(box(), { target: { value: "bell\u0007" } });
    await user.click(screen.getByRole("button", { name: "Save" }));
    expect(onError).toHaveBeenLastCalledWith("The message must not contain control or hidden formatting characters");
    expect(puts()).toEqual([]);
  });

  test("Cancel on an untouched editor just leaves; on a changed one it asks, and Keep editing stays", async () => {
    const user = userEvent.setup();
    const { onDone } = renderForm(makeMessage({ body: "first" }));
    await user.click(screen.getByRole("button", { name: "Cancel" }));
    expect(onDone).toHaveBeenCalledTimes(1);

    await user.type(box(), "x");
    await user.click(screen.getByRole("button", { name: "Cancel" }));
    expect(onDone).toHaveBeenCalledTimes(1);
    const dialog = await screen.findByRole("dialog", { name: "Discard changes?" });
    await user.click(screen.getByRole("button", { name: "Keep editing" }));
    await waitFor(() => expect(dialog).not.toBeVisible());
    expect(box()).toHaveValue("firstx");
    await user.click(screen.getByRole("button", { name: "Cancel" }));
    await user.click(await screen.findByRole("button", { name: "Discard" }));
    expect(onDone).toHaveBeenCalledTimes(2);
  });

  test("a closed thread's 409 goes to onError in its own words, keeps the text and focus, and re-reads the whole room", async () => {
    mockFetch.mockResolvedValue(jsonResponse(409, { type: "urn:adder:problem:thread-closed", status: 409, title: "Closed" }));
    const user = userEvent.setup();
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const invalidate = vi.spyOn(queryClient, "invalidateQueries");
    const { onDone, onError } = renderForm(makeMessage({ body: "first" }), queryClient);
    await user.type(box(), "!");
    await user.click(screen.getByRole("button", { name: "Save" }));
    await waitFor(() => expect(onError).toHaveBeenLastCalledWith("This thread was closed in the meantime, so its messages can no longer be changed."));
    expect(box()).toHaveValue("first!");
    expect(box()).not.toHaveAttribute("readonly");
    await waitFor(() => expect(box()).toHaveFocus());
    expect(onDone).not.toHaveBeenCalled();
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ["rooms"] });
  });

  test("a reload warns while the text is changed, and not before", async () => {
    const user = userEvent.setup();
    renderForm(makeMessage({ body: "first" }));
    const unload = () => {
      const event = new Event("beforeunload", { cancelable: true });
      window.dispatchEvent(event);
      return event.defaultPrevented;
    };
    expect(unload()).toBe(false);
    await user.type(box(), "x");
    expect(unload()).toBe(true);
  });
});
