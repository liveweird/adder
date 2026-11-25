import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { QueryClient } from "@tanstack/react-query";
import { Drawer } from "@mantine/core";
import { act, cleanup, fireEvent, renderWithProviders, screen, waitFor, within } from "../test/render";
import { jsonResponse } from "../test/http";
import { clearSession } from "../api/session";
import type { LinkPreview } from "../api/linkPreviews";
import { bindSessionQueryCache } from "../utils/sessionQueryCache";
import ExternalMessageLink from "./ExternalMessageLink";

const source = { roomId: 12, messageId: 71, editedAt: null };
const destination = new URL("https://images.example.com/replicas.png");
const content: LinkPreview = {
  kind: "PAGE",
  url: destination.href,
  finalUrl: "https://cdn.example.com/replicas.png",
  title: "Replica transfer diagram",
  image: { mediaType: "image/png", data: "ZmFrZQ==", width: 320, height: 180 },
};

const fetchMock = vi.fn<typeof fetch>();
const renderLink = (url = destination, queryClient?: QueryClient) => renderWithProviders(
  <ExternalMessageLink destination={url} source={source}>Replica notes</ExternalMessageLink>,
  { queryClient },
);

beforeEach(() => {
  localStorage.setItem("adder.auth.token", "example-fixture-token");
  vi.stubGlobal("fetch", fetchMock);
});

afterEach(() => {
  cleanup();
  vi.useRealTimers();
  localStorage.clear();
  vi.unstubAllGlobals();
  fetchMock.mockReset();
});

describe("ExternalMessageLink", () => {
  test("only explicit Preview fetches and cached hover shows the same image without navigation", async () => {
    fetchMock.mockResolvedValue(jsonResponse(200, content));
    renderLink();
    const user = userEvent.setup();
    const trigger = screen.getByRole("button", { name: "Replica notes" });
    await user.hover(trigger);
    expect(fetchMock).not.toHaveBeenCalled();
    expect(screen.queryByRole("tooltip")).not.toBeInTheDocument();
    await user.click(trigger);
    const dialog = screen.getByRole("dialog", { name: "Open external link" });
    expect(fetchMock).not.toHaveBeenCalled();
    expect(within(dialog).getByRole("link", { name: "Open link" })).toHaveAttribute("href", destination.href);
    await user.click(within(dialog).getByRole("button", { name: "Preview" }));
    expect(await within(dialog).findByText("Replica transfer diagram")).toBeVisible();
    expect(within(dialog).getByRole("img")).toHaveAttribute("src", "data:image/png;base64,ZmFrZQ==");
    const [endpoint, init] = fetchMock.mock.calls[0];
    expect(endpoint).toBe("/api/v1/rooms/12/messages/71/link-preview");
    expect(init).toMatchObject({ method: "POST", body: JSON.stringify({ url: destination.href }) });
    await user.click(within(dialog).getByRole("button", { name: "Hide preview" }));
    expect(within(dialog).queryByRole("img")).toBeNull();
    await user.click(within(dialog).getByRole("button", { name: "Cancel" }));
    expect(trigger).toHaveFocus();
    await user.hover(trigger);
    const tooltip = await screen.findByRole("tooltip", { name: "Preview" });
    expect(within(tooltip).getByRole("img")).toBeVisible();
    expect(within(tooltip).queryByRole("link")).toBeNull();
    await user.hover(tooltip);
    await user.unhover(tooltip);
    await waitFor(() => expect(screen.queryByRole("tooltip")).toBeNull());
    await user.hover(trigger);
    expect(screen.getByRole("tooltip", { name: "Preview" })).toBeVisible();
    await user.keyboard("{Escape}");
    expect(screen.queryByRole("tooltip")).toBeNull();
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  test("keeps the verified original address available when a preview cannot load", async () => {
    fetchMock.mockResolvedValueOnce(jsonResponse(200, { kind: "UNAVAILABLE", url: destination.href, finalUrl: null, title: null, image: null }));
    renderLink();
    const user = userEvent.setup();
    await user.click(screen.getByRole("button", { name: "Replica notes" }));
    await user.click(screen.getByRole("button", { name: "Preview" }));
    expect(await screen.findByRole("status")).toHaveTextContent("A preview is unavailable for this link.");
    expect(screen.getByRole("link", { name: "Open link" })).toHaveAttribute("href", destination.href);
    await user.keyboard("{Escape}");
    expect(screen.queryByRole("dialog")).toBeNull();
  });

  test("renders text-only page previews and keeps touch hover explicit even when cached", async () => {
    fetchMock.mockResolvedValueOnce(jsonResponse(200, { ...content, image: null }));
    renderLink();
    const user = userEvent.setup();
    const trigger = screen.getByRole("button", { name: "Replica notes" });
    await user.click(trigger);
    await user.click(screen.getByRole("button", { name: "Preview" }));
    expect(await screen.findByText("Replica transfer diagram")).toBeVisible();
    expect(screen.queryByRole("img")).toBeNull();
    await user.click(screen.getByRole("button", { name: "Cancel" }));

    fireEvent.pointerEnter(trigger, { pointerType: "touch" });
    expect(screen.queryByRole("tooltip")).toBeNull();
    await user.click(trigger);
    expect(screen.getByRole("dialog", { name: "Open external link" })).toHaveTextContent("Replica transfer diagram");
    expect(screen.getByRole("button", { name: "Hide preview" })).toBeVisible();
  });

  test("renders raster-only image previews without inventing a title", async () => {
    fetchMock.mockResolvedValueOnce(jsonResponse(200, { ...content, kind: "IMAGE", title: null }));
    renderLink();
    const user = userEvent.setup();
    await user.click(screen.getByRole("button", { name: "Replica notes" }));
    await user.click(screen.getByRole("button", { name: "Preview" }));
    expect(await screen.findByRole("img", { name: "Preview of the linked image" })).toHaveAttribute("src", "data:image/png;base64,ZmFrZQ==");
    expect(screen.queryByText("Replica transfer diagram")).toBeNull();
  });

  test("keeps a cached unavailable result passive on later hover", async () => {
    fetchMock.mockResolvedValueOnce(jsonResponse(200, { kind: "UNAVAILABLE", url: destination.href, finalUrl: null, title: null, image: null }));
    renderLink();
    const user = userEvent.setup();
    const trigger = screen.getByRole("button", { name: "Replica notes" });
    await user.click(trigger);
    await user.click(screen.getByRole("button", { name: "Preview" }));
    expect(await screen.findByText("A preview is unavailable for this link.")).toBeVisible();
    await user.click(screen.getByRole("button", { name: "Cancel" }));
    await user.hover(trigger);
    expect(screen.queryByRole("tooltip")).toBeNull();
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  test("confirms without offering a preview when source or session identity is missing", async () => {
    const user = userEvent.setup();
    const { unmount } = renderWithProviders(
      <ExternalMessageLink destination={destination}>Without source</ExternalMessageLink>,
    );
    await user.click(screen.getByRole("button", { name: "Without source" }));
    expect(screen.getByRole("dialog", { name: "Open external link" })).toBeVisible();
    expect(screen.queryByRole("button", { name: "Preview" })).toBeNull();
    unmount();

    localStorage.clear();
    renderLink();
    await user.click(screen.getByRole("button", { name: "Replica notes" }));
    expect(screen.getByRole("dialog", { name: "Open external link" })).toBeVisible();
    expect(screen.queryByRole("button", { name: "Preview" })).toBeNull();
    expect(fetchMock).not.toHaveBeenCalled();
  });

  test("confirms non-HTTPS links but leaves them out of the preview request", async () => {
    renderLink(new URL("http://example.com/guide"));
    await userEvent.click(screen.getByRole("button", { name: "Replica notes" }));
    expect(screen.queryByRole("button", { name: "Preview" })).toBeNull();
    expect(screen.getByRole("link", { name: "Open link" })).toHaveAttribute("href", "http://example.com/guide");
    expect(fetchMock).not.toHaveBeenCalled();
  });

  test("a failed preview can be retried and pressing Open link dismisses the confirmation", async () => {
    fetchMock.mockResolvedValueOnce(jsonResponse(503, { title: "Unavailable", status: 503 }));
    fetchMock.mockResolvedValueOnce(jsonResponse(200, content));
    renderLink();
    const user = userEvent.setup();
    const trigger = screen.getByRole("button", { name: "Replica notes" });
    await user.click(trigger);
    await user.click(screen.getByRole("button", { name: "Preview" }));
    expect(await screen.findByText("A preview is unavailable for this link.")).toBeVisible();
    await user.click(screen.getByRole("button", { name: "Preview" }));
    expect(await screen.findByText("Replica transfer diagram")).toBeVisible();
    const opener = screen.getByRole("link", { name: "Open link" });
    opener.addEventListener("click", (event) => event.preventDefault());
    fireEvent.click(opener);
    expect(screen.queryByRole("dialog")).toBeNull();
    expect(trigger).toHaveFocus();
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });

  test("clicking outside closes the confirmation without spending a preview request", async () => {
    renderLink();
    const user = userEvent.setup();
    await user.click(screen.getByRole("button", { name: "Replica notes" }));
    expect(screen.getByRole("dialog")).toBeInTheDocument();
    await user.click(document.body);
    await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
    expect(fetchMock).not.toHaveBeenCalled();
  });

  test("a delayed pointer leave cannot dismiss an explicit confirmation", () => {
    renderLink();
    const trigger = screen.getByRole("button", { name: "Replica notes" });
    fireEvent.click(trigger);
    expect(screen.getByRole("dialog", { name: "Open external link" })).toBeVisible();
    vi.useFakeTimers();
    fireEvent.pointerLeave(trigger, { pointerType: "mouse" });
    act(() => vi.advanceTimersByTime(151));
    expect(screen.getByRole("dialog", { name: "Open external link" })).toBeVisible();
  });

  test("Escape closes the top confirmation and returns focus before a separate Escape closes the containing drawer", async () => {
    const closeDrawer = vi.fn();
    fetchMock.mockResolvedValue(jsonResponse(200, content));
    renderWithProviders(
      <Drawer opened onClose={closeDrawer} title="Pinned items">
        <ExternalMessageLink destination={destination} source={source}>Replica notes</ExternalMessageLink>
      </Drawer>,
    );
    const user = userEvent.setup();
    const trigger = screen.getByRole("button", { name: "Replica notes" });
    await user.click(trigger);
    const dialog = screen.getByRole("dialog", { name: "Open external link" });
    const cancel = within(dialog).getByRole("button", { name: "Cancel" });
    const preview = within(dialog).getByRole("button", { name: "Preview" });
    const opener = within(dialog).getByRole("link", { name: "Open link" });
    expect(screen.getByRole("dialog", { name: "Pinned items" })).toContainElement(dialog);
    cancel.focus();
    await user.tab();
    expect(opener).toHaveFocus();
    await user.tab();
    expect(preview).toHaveFocus();
    await user.tab({ shift: true });
    expect(opener).toHaveFocus();
    await user.click(preview);
    expect(await within(dialog).findByText("Replica transfer diagram")).toBeVisible();
    opener.focus();
    await user.keyboard("{Escape}");
    expect(closeDrawer).not.toHaveBeenCalled();
    expect(screen.queryByRole("dialog", { name: "Open external link" })).toBeNull();
    expect(trigger).toHaveFocus();
    await user.hover(trigger);
    expect(screen.queryByRole("tooltip")).toBeNull();
    await user.keyboard("{Escape}");
    expect(closeDrawer).toHaveBeenCalledTimes(1);
  });

  test("closes the overlay and ignores an outstanding result after the logical session is replaced", async () => {
    let complete!: (response: Response) => void;
    fetchMock.mockImplementationOnce(() => new Promise<Response>((resolve) => { complete = resolve; }));
    const client = new QueryClient();
    const unbind = bindSessionQueryCache(client);
    try {
      renderLink(destination, client);
      const user = userEvent.setup();
      await user.click(screen.getByRole("button", { name: "Replica notes" }));
      await user.click(screen.getByRole("button", { name: "Preview" }));
      await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1));
      act(() => clearSession());
      await act(async () => { complete(jsonResponse(200, content)); });
      expect(screen.queryByRole("dialog")).toBeNull();
      expect(screen.queryByText(content.title!)).toBeNull();
      expect(client.getQueryCache().getAll()).toEqual([]);
    } finally {
      unbind();
    }
  });
});
