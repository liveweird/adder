import { useState } from "react";
import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { act, screen, waitFor } from "@testing-library/react";
import RoomHashtagSelect from "./RoomHashtagSelect";
import { jsonResponse } from "../test/http";
import { renderWithProviders } from "../test/render";

const TAGS = {
  items: [
    { tag: "outage", threadCount: 5 },
    { tag: "wdrozenie", threadCount: 2 },
  ],
  page: 1,
  pageSize: 50,
  total: 2,
};

function Harness({ initial = [], onChange, roomId = 12 }: { initial?: string[]; onChange?: (next: string[]) => void; roomId?: number }) {
  const [value, setValue] = useState(initial);
  return (
    <RoomHashtagSelect
      roomId={roomId}
      label="Hashtags"
      emptyLabel="All"
      value={value}
      onChange={(next) => {
        setValue(next);
        onChange?.(next);
      }}
    />
  );
}

describe("RoomHashtagSelect", () => {
  let mockFetch: ReturnType<typeof vi.fn>;
  const tagCalls = () => mockFetch.mock.calls.map(([url]) => url as string).filter((url) => url.startsWith("/api/v1/rooms/12/tags?"));
  const serve = (answer: () => Promise<Response> = () => Promise.resolve(jsonResponse(200, TAGS))) =>
    mockFetch.mockImplementation((url: string) => (url.startsWith("/api/v1/rooms/12/tags?") ? answer() : Promise.resolve(jsonResponse(404, { title: "Not found", status: 404 }))));

  beforeEach(() => {
    mockFetch = vi.fn();
    vi.stubGlobal("fetch", mockFetch);
    localStorage.setItem("adder.auth.token", "fake-token");
  });
  afterEach(() => {
    vi.useRealTimers();
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  test("offers the room's most used tags with their thread counts, and a pick hands back the tag as the list spells it", async () => {
    serve();
    const onChange = vi.fn();
    const user = userEvent.setup();
    renderWithProviders(<Harness onChange={onChange} />);
    const box = screen.getByRole("combobox", { name: "Hashtags" });
    expect(box).toHaveAttribute("placeholder", "All");
    await user.click(box);
    expect(await screen.findByRole("option", { name: "#outage (5)" })).toBeInTheDocument();
    await user.click(screen.getByRole("option", { name: "#wdrozenie (2)" }));
    expect(onChange).toHaveBeenLastCalledWith(["wdrozenie"]);
    expect(tagCalls()).toEqual(["/api/v1/rooms/12/tags?page=1&pageSize=50"]);
    expect(await screen.findByRole("button", { name: "Remove #wdrozenie" })).toBeInTheDocument();
  });

  test("searches on the server with the debounced term, without the leading hash", async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    serve();
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
    renderWithProviders(<Harness />);
    await user.click(screen.getByRole("combobox", { name: "Hashtags" }));
    await screen.findByRole("option", { name: "#outage (5)" });
    await user.type(screen.getByRole("combobox", { name: "Hashtags" }), "#wdro");
    // Typing is over but the debounce has not settled: no search for a partial term went out.
    await act(() => vi.advanceTimersByTimeAsync(100));
    expect(tagCalls().filter((url) => url.includes("q="))).toEqual([]);
    await act(() => vi.advanceTimersByTimeAsync(400));
    await waitFor(() => expect(tagCalls().at(-1)).toBe("/api/v1/rooms/12/tags?page=1&pageSize=50&q=wdro"));
    // One request for the settled term, not one per keystroke.
    expect(tagCalls().filter((url) => url.includes("q="))).toEqual(["/api/v1/rooms/12/tags?page=1&pageSize=50&q=wdro"]);
  });

  test("the term is trimmed again after the hash goes: \"# a\" searches for a", async () => {
    serve();
    const user = userEvent.setup();
    renderWithProviders(<Harness />);
    await user.click(screen.getByRole("combobox", { name: "Hashtags" }));
    await user.type(screen.getByRole("combobox", { name: "Hashtags" }), "# a");
    await waitFor(() => expect(tagCalls().at(-1)).toBe("/api/v1/rooms/12/tags?page=1&pageSize=50&q=a"));
  });

  test("a term is capped at the server's 50 characters", async () => {
    serve();
    const user = userEvent.setup({ delay: null });
    renderWithProviders(<Harness />);
    await user.click(screen.getByRole("combobox", { name: "Hashtags" }));
    await user.type(screen.getByRole("combobox", { name: "Hashtags" }), "a".repeat(60));
    await waitFor(() => expect(tagCalls().at(-1)).toBe(`/api/v1/rooms/12/tags?page=1&pageSize=50&q=${"a".repeat(50)}`));
  });

  test("a picked value the search does not return keeps its pill and its remove button, spelled as typed", async () => {
    serve();
    const onChange = vi.fn();
    const user = userEvent.setup();
    renderWithProviders(<Harness initial={["Wdrożenie", "hawai'i"]} onChange={onChange} />);
    expect(await screen.findByRole("button", { name: "Remove #hawai'i" })).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Remove #Wdrożenie" }));
    expect(onChange).toHaveBeenLastCalledWith(["hawai'i"]);
  });

  test("a picked spelling of a returned tag is not a second option: #Outage beside #outage, its pill still named", async () => {
    serve();
    const user = userEvent.setup();
    renderWithProviders(<Harness initial={["Outage"]} />);
    await user.click(screen.getByRole("combobox", { name: "Hashtags" }));
    await screen.findByRole("option", { name: "#outage (5)" });
    expect(screen.getAllByRole("option").map((option) => option.textContent)).toEqual(["#outage(5)", "#wdrozenie(2)"]);
    expect(screen.getByRole("button", { name: "Remove #Outage" })).toBeInTheDocument();
  });

  test("another room's tags never stand in while this room's are read", async () => {
    const pending: { answer?: (response: Response) => void } = {};
    mockFetch.mockImplementation((url: string) => {
      if (url.startsWith("/api/v1/rooms/12/tags?")) return Promise.resolve(jsonResponse(200, TAGS));
      if (url.startsWith("/api/v1/rooms/13/tags?")) return new Promise<Response>((resolve) => (pending.answer = resolve));
      return Promise.resolve(jsonResponse(404, { title: "Not found", status: 404 }));
    });
    const user = userEvent.setup();
    const { rerender } = renderWithProviders(<Harness roomId={12} />);
    await user.click(screen.getByRole("combobox", { name: "Hashtags" }));
    await screen.findByRole("option", { name: "#outage (5)" });
    rerender(<Harness roomId={13} />);
    await waitFor(() => expect(pending.answer).toBeDefined());
    expect(screen.queryByRole("option", { name: "#outage (5)" })).toBeNull();
    pending.answer?.(jsonResponse(200, { items: [{ tag: "other", threadCount: 1 }], page: 1, pageSize: 50, total: 1 }));
    expect(await screen.findByRole("option", { name: "#other (1)" })).toBeInTheDocument();
  });

  test("says so when the search finds nothing", async () => {
    serve(() => Promise.resolve(jsonResponse(200, { items: [], page: 1, pageSize: 50, total: 0 })));
    const user = userEvent.setup();
    renderWithProviders(<Harness />);
    await user.click(screen.getByRole("combobox", { name: "Hashtags" }));
    expect(await screen.findByText("No hashtags found")).toBeInTheDocument();
  });

  test("takes at most 10 values, the server's limit", async () => {
    serve();
    const user = userEvent.setup();
    const ten = Array.from({ length: 10 }, (_, i) => `t${i}`);
    const onChange = vi.fn();
    renderWithProviders(<Harness initial={ten} onChange={onChange} />);
    await user.click(screen.getByRole("combobox", { name: "Hashtags" }));
    await user.click(await screen.findByRole("option", { name: "#outage (5)" }));
    expect(onChange).not.toHaveBeenCalled();
    // Removing one makes room again.
    await user.click(screen.getByRole("button", { name: "Remove #t0" }));
    expect(onChange).toHaveBeenLastCalledWith(ten.slice(1));
  });

  test("a failed read shows an alert, not an empty list", async () => {
    serve(() => Promise.resolve(jsonResponse(500, { title: "Boom", status: 500 })));
    renderWithProviders(<Harness />);
    expect(await screen.findByRole("alert")).toBeInTheDocument();
  });
});
