import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { QueryClient } from "@tanstack/react-query";
import { act, cleanup, screen, waitFor } from "@testing-library/react";
import { renderWithProviders } from "../test/render";
import { threadReferenceKey } from "../utils/threadReferenceCache";
import MarkdownView, { type MarkdownVariant } from "./MarkdownView";

const tagHref = (tag: string) => `/rooms/7/threads/42?scope=all&tag=${encodeURIComponent(tag)}`;

function renderView(markdown: string, variant: MarkdownVariant = "message", roomId: number | null = 7) {
  return renderWithProviders(
    <MarkdownView variant={variant} tagHref={tagHref} threadRoomId={roomId ?? undefined}>{markdown}</MarkdownView>,
    { route: "/rooms/7/threads?scope=all" },
  );
}

let readable = new Set<number>();
let request: ReturnType<typeof vi.fn>;

beforeEach(() => {
  readable = new Set([4, 5, 6, 7, 42]);
  request = vi.fn((url: string) => {
    const params = new URL(url, "https://adder.test").searchParams;
    const items = params.getAll("number").map(Number).filter((number) => readable.has(number)).map((number) => ({ number }));
    return Promise.resolve(new Response(JSON.stringify({ items, page: 1, pageSize: 100, total: items.length }), {
      status: 200,
      headers: { "Content-Type": "application/json" },
    }));
  });
  vi.stubGlobal("fetch", request);
  localStorage.setItem("adder.auth.token", "token");
});

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  localStorage.clear();
});

describe("MarkdownView (message): #42 thread references (1.2 A9)", () => {
  test("only readable numbers become in-app links and one render batches them", async () => {
    renderView("Duplicate of #42, see also #7.");
    const link = await screen.findByRole("link", { name: "#42" });
    expect(link).toHaveAttribute("href", "/rooms/7/threads/42?scope=all");
    expect(link).not.toHaveAttribute("target");
    expect(link).not.toHaveAttribute("rel");
    expect(screen.getByRole("link", { name: "#7" })).toHaveAttribute("href", "/rooms/7/threads/7?scope=all");
    expect(link.closest("p")).toHaveTextContent("Duplicate of #42, see also #7.");
    expect(request).toHaveBeenCalledOnce();
  });

  test("two renderers reuse one TanStack verdict for the same room and number", async () => {
    renderWithProviders(
      <>
        <MarkdownView variant="message" threadRoomId={7}>first #42</MarkdownView>
        <MarkdownView variant="message" threadRoomId={7}>second #42</MarkdownView>
      </>,
      { route: "/rooms/7/threads?scope=all" },
    );
    expect(await screen.findAllByRole("link", { name: "#42" })).toHaveLength(2);
    expect(request).toHaveBeenCalledOnce();
    expect(new URL(request.mock.calls[0][0], "https://adder.test").searchParams.getAll("number")).toEqual(["42"]);
  });

  test("a hashtag and a number side by side are each their own link", async () => {
    renderView("#outage is #42");
    expect(screen.getByRole("link", { name: "#outage" })).toHaveAttribute("href", "/rooms/7/threads/42?scope=all&tag=outage");
    expect(await screen.findByRole("link", { name: "#42" })).toHaveAttribute("href", "/rooms/7/threads/42?scope=all");
    expect(screen.getAllByRole("link")).toHaveLength(2);
  });

  test("a number glued to a hashtag is no reference: #release#42 is the hashtag alone", async () => {
    renderView("see #release#42 and #release #7");
    expect(screen.getAllByRole("link", { name: "#release" })).toHaveLength(2);
    await waitFor(() => expect(screen.queryByRole("link", { name: "#42" })).toBeNull());
    expect(await screen.findByRole("link", { name: "#7" })).toBeInTheDocument();
  });

  test("numbers in code and in links stay text, external links ask for confirmation, and glued numbers are no references", async () => {
    renderView("`#1` and [docs #2](https://example.com/#3) and https://example.com/x#4 and foo#5 and #6");
    expect(screen.getByText("#1").tagName).toBe("CODE");
    expect(screen.getByRole("button", { name: "docs #2" })).not.toHaveAttribute("href");
    expect(screen.getByRole("button", { name: "https://example.com/x#4" })).not.toHaveAttribute("href");
    expect(await screen.findByRole("link", { name: "#6" })).not.toHaveAttribute("target");
    expect(screen.getAllByRole("link")).toHaveLength(1);
    expect(screen.queryByRole("link", { name: "#5" })).toBeNull();
  });

  test("a number is text inside its link: markup around it is never interpreted", async () => {
    renderView("#4<img src=x onerror=alert(1)> and **#5**");
    expect(document.querySelector("img")).toBeNull();
    expect((await screen.findByRole("link", { name: "#5" })).closest("strong")).not.toBeNull();
    expect(screen.getByRole("link", { name: "#4" }).children).toHaveLength(0);
  });

  test("without a thread room a number stays plain text, and the document variant never requests or links one", () => {
    renderView("see #42", "message", null);
    expect(screen.getByText("see #42")).toBeInTheDocument();
    expect(screen.queryByRole("link")).toBeNull();
    cleanup();
    renderView("see #42", "document");
    expect(screen.getByText("see #42")).toBeInTheDocument();
    expect(screen.queryByRole("link")).toBeNull();
  });

  test("a re-render keeps the very same validated link element, so focus survives", async () => {
    const view = <MarkdownView variant="message" threadRoomId={7}>{"see #42"}</MarkdownView>;
    const { rerender } = renderWithProviders(view, { route: "/rooms/7/threads?scope=all" });
    const link = await screen.findByRole("link", { name: "#42" });
    link.focus();
    rerender(view);
    expect(screen.getByRole("link", { name: "#42" })).toBe(link);
    expect(link).toHaveAttribute("href", "/rooms/7/threads/42?scope=all");
    expect(link).toHaveFocus();
  });

  test("a focused reference keeps focus local while its link is removed and revalidated", async () => {
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    queryClient.setQueryData(threadReferenceKey(7, 42), true);
    renderWithProviders(<MarkdownView variant="message" threadRoomId={7}>see #42</MarkdownView>, {
      route: "/rooms/7/threads?scope=all",
      queryClient,
    });
    const link = screen.getByRole("link", { name: "#42" });
    link.focus();

    act(() => queryClient.setQueryData(threadReferenceKey(7, 42), false));

    await waitFor(() => expect(screen.queryByRole("link", { name: "#42" })).toBeNull());
    const fallback = screen.getByText("#42");
    expect(fallback).toHaveAttribute("tabindex", "-1");
    expect(fallback).toHaveFocus();

    act(() => queryClient.setQueryData(threadReferenceKey(7, 42), true));

    const restored = await screen.findByRole("link", { name: "#42" });
    expect(restored.parentElement).toBe(fallback);
    expect(fallback).toHaveAttribute("tabindex", "-1");
    expect(fallback).toHaveFocus();
  });

  test("missing and failed references stay plain text without a retry storm", async () => {
    readable.clear();
    renderView("missing #42");
    await waitFor(() => expect(request).toHaveBeenCalledOnce());
    expect(screen.getByText("#42").closest("p")).toHaveTextContent("missing #42");
    expect(screen.queryByRole("link")).toBeNull();
    cleanup();
    request.mockRejectedValue(new Error("offline"));
    renderView("failed #42");
    await waitFor(() => expect(request).toHaveBeenCalledTimes(2));
    expect(screen.getByText("#42").closest("p")).toHaveTextContent("failed #42");
    expect(screen.queryByRole("link")).toBeNull();
    await new Promise((resolve) => setTimeout(resolve, 20));
    expect(request).toHaveBeenCalledTimes(2);
  });
});
