import { useState } from "react";
import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { fireEvent, screen, waitFor } from "@testing-library/react";
import { QueryClient } from "@tanstack/react-query";
import MentionTextarea from "./MentionTextarea";
import type { MentionReplacementRequest } from "../utils/mentionText";
import { jsonResponse } from "../test/http";
import { renderWithProviders } from "../test/render";
import { isSendShortcut } from "../utils/messageForm";

// The picker plumbing is EmojiButton's concern; a stub that inserts a fixed emoji exercises the caret splice.
vi.mock("./EmojiButton", () => ({
  default: ({ onSelect, label }: { onSelect: (native: string) => void; label: string }) => (
    <button aria-label={label} onClick={() => onSelect("😀")} />
  ),
}));

const MEMBERS = [
  { userId: 5, name: "Ann Owner", email: "ann@example.test", role: "OWNER", addedAt: 1 },
  { userId: 6, name: "Anna Other", email: "anna@example.test", role: "MEMBER", addedAt: 2 },
  { userId: 7, name: "Bob", email: "bob@example.test", role: "MEMBER", addedAt: 3 },
];

function Harness({
  onSend,
  initial = "",
  maxLength = 8000,
  offerWatchers,
  onAddAndMention,
}: {
  onSend?: () => void;
  initial?: string;
  maxLength?: number;
  offerWatchers?: boolean;
  onAddAndMention?: (request: MentionReplacementRequest) => void;
}) {
  const [text, setText] = useState(initial);
  return (
    <>
      <MentionTextarea
        roomId={9}
        aria-label="Reply"
        maxLength={maxLength}
        value={text}
        onChange={setText}
        offerWatchers={offerWatchers}
        onAddAndMention={onAddAndMention}
        onKeyDown={(event) => {
          if (isSendShortcut(event)) {
            event.preventDefault();
            onSend?.();
          }
        }}
      />
      <output aria-label="stored">{text}</output>
    </>
  );
}

describe("MentionTextarea", () => {
  let mockFetch: ReturnType<typeof vi.fn>;
  const memberCalls = () => mockFetch.mock.calls.map(([url]) => url as string).filter((url) => url.startsWith("/api/v1/rooms/9/members"));
  const box = () => screen.getByRole("textbox", { name: "Reply" }) as HTMLTextAreaElement;
  const stored = () => screen.getByLabelText("stored").textContent;

  beforeEach(() => {
    // The server filters by name; so does the stub.
    mockFetch = vi.fn((url: string) => {
      if (!url.startsWith("/api/v1/rooms/9/members?")) return Promise.resolve(jsonResponse(404, { title: "x", status: 404 }));
      const name = new URL(url, "http://x").searchParams.get("name")?.toLowerCase() ?? "";
      const items = MEMBERS.filter((member) => member.name.toLowerCase().includes(name));
      return Promise.resolve(jsonResponse(200, { items, page: 1, pageSize: 8, total: items.length }));
    });
    vi.stubGlobal("fetch", mockFetch);
    localStorage.setItem("adder.auth.token", "fake-token");
  });
  afterEach(() => {
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  test("typing @ and letters lists the matching members", async () => {
    const user = userEvent.setup();
    renderWithProviders(<Harness />);
    await user.type(box(), "hello @an");
    expect(await screen.findByRole("option", { name: /Ann Owner/ })).toBeInTheDocument();
    expect(screen.getByRole("option", { name: /Anna Other/ })).toBeInTheDocument();
    expect(screen.queryByRole("option", { name: /Bob/ })).not.toBeInTheDocument();
    // The server is asked once the debounce has passed: eight rows, by name.
    await waitFor(() => expect(new URL(memberCalls().at(-1)!, "http://x").searchParams.get("name")).toBe("an"));
    expect(new URL(memberCalls().at(-1)!, "http://x").searchParams.get("pageSize")).toBe("8");
    // The text typed so far is stored as typed; an address opens nothing.
    expect(stored()).toBe("hello @an");
  });

  test("the arrow keys and Enter pick, and the post carries the token", async () => {
    const user = userEvent.setup();
    renderWithProviders(<Harness />);
    await user.type(box(), "hi @an");
    await screen.findByRole("option", { name: /Anna Other/ });
    await user.keyboard("{ArrowDown}");
    await user.keyboard("{Enter}");
    // The box shows the name, the stored text holds the token (the counter counts the stored text).
    expect(box()).toHaveValue("hi @Anna Other ");
    expect(stored()).toBe("hi <@6> ");
    expect(screen.getByText("8 / 8000")).toBeInTheDocument();
    expect(screen.queryByRole("listbox")).not.toBeInTheDocument();
    // Typing on goes after the space, and the token survives.
    await user.type(box(), "please look");
    expect(stored()).toBe("hi <@6> please look");
    expect(box()).toHaveValue("hi @Anna Other please look");
  });

  test("Tab picks the highlighted person and ArrowUp wraps to the last", async () => {
    const user = userEvent.setup();
    renderWithProviders(<Harness />);
    await user.type(box(), "@an");
    await screen.findByRole("option", { name: /Anna Other/ });
    await user.keyboard("{ArrowUp}{Tab}");
    expect(stored()).toBe("<@6> ");
  });

  test("a click on an option picks it and the box keeps the caret", async () => {
    const user = userEvent.setup();
    renderWithProviders(<Harness />);
    await user.type(box(), "@bo");
    await user.click(await screen.findByRole("option", { name: /Bob/ }));
    expect(stored()).toBe("<@7> ");
    expect(box()).toHaveValue("@Bob ");
    expect(box()).toHaveFocus();
  });

  test("backspace into a mention removes it whole", async () => {
    const user = userEvent.setup();
    renderWithProviders(<Harness />);
    await user.type(box(), "@bo");
    await user.click(await screen.findByRole("option", { name: /Bob/ }));
    // "@Bob " - one backspace takes the space, the next the whole mention
    await user.keyboard("{Backspace}{Backspace}");
    expect(stored()).toBe("");
    expect(box()).toHaveValue("");
  });

  test("Escape closes the list and Enter is a newline again", async () => {
    const user = userEvent.setup();
    renderWithProviders(<Harness />);
    await user.type(box(), "@an");
    await screen.findByRole("listbox");
    await user.keyboard("{Escape}");
    expect(screen.queryByRole("listbox")).not.toBeInTheDocument();
    await user.keyboard("{Enter}");
    expect(stored()).toBe("@an\n");
  });

  test("Ctrl+Enter still sends while the list is closed", async () => {
    const user = userEvent.setup();
    const onSend = vi.fn();
    renderWithProviders(<Harness onSend={onSend} />);
    await user.type(box(), "plain words");
    await user.keyboard("{Control>}{Enter}{/Control}");
    expect(onSend).toHaveBeenCalledTimes(1);
    // With the list open the same keys pick instead of sending.
    await user.type(box(), " @bo");
    await screen.findByRole("option", { name: /Bob/ });
    await user.keyboard("{Control>}{Enter}{/Control}");
    expect(onSend).toHaveBeenCalledTimes(1);
    expect(stored()).toBe("plain words <@7> ");
  });

  test("an IME composition opens nothing", async () => {
    renderWithProviders(<Harness />);
    fireEvent.compositionStart(box());
    fireEvent.change(box(), { target: { value: "@an" } });
    expect(stored()).toBe("@an");
    expect(screen.queryByRole("listbox")).not.toBeInTheDocument();
    expect(memberCalls()).toHaveLength(0);
    fireEvent.compositionEnd(box());
    expect(await screen.findByRole("option", { name: /Ann Owner/ })).toBeInTheDocument();
  });

  test("the list is labelled and the active option is announced", async () => {
    const user = userEvent.setup();
    renderWithProviders(<Harness />);
    await user.type(box(), "@an");
    const list = await screen.findByRole("listbox", { name: "People, teams and groups to mention" });
    expect(box()).toHaveAttribute("aria-controls", list.id);
    expect(box()).toHaveAttribute("aria-autocomplete", "list");
    const activeId = () => box().getAttribute("aria-activedescendant");
    expect(document.getElementById(activeId()!)).toHaveAccessibleName(/Ann Owner/);
    expect(document.getElementById(activeId()!)).toHaveAttribute("aria-selected", "true");
    await user.keyboard("{ArrowDown}");
    expect(document.getElementById(activeId()!)).toHaveAccessibleName(/Anna Other/);
    expect(await screen.findByText("2 matches")).toBeInTheDocument();
    // Closed, the textarea points at nothing.
    await user.keyboard("{Escape}");
    expect(box()).not.toHaveAttribute("aria-controls");
    expect(box()).not.toHaveAttribute("aria-activedescendant");
  });

  test("no one matching says so and shows no list", async () => {
    const user = userEvent.setup();
    renderWithProviders(<Harness />);
    await user.type(box(), "@zzz");
    expect(await screen.findByText("Nothing in this room matches")).toBeInTheDocument();
    expect(screen.queryByRole("listbox")).not.toBeInTheDocument();
  });

  test("a settled non-member term offers a keyboard-pickable add-and-mention request with canonical offsets", async () => {
    const user = userEvent.setup();
    const onAddAndMention = vi.fn();
    renderWithProviders(<Harness initial="Before " onAddAndMention={onAddAndMention} />);
    await user.type(box(), "@New");
    const action = await screen.findByRole("option", { name: /Add and mention “New”/ });
    expect(action).toBeInTheDocument();
    await user.keyboard("{Enter}");
    expect(onAddAndMention).toHaveBeenCalledWith({ from: 7, to: 11, source: "@New", term: "New" });
    expect(stored()).toBe("Before @New");
  });

  test("a manager can still pick a matching keyword or an existing member without opening the directory", async () => {
    const user = userEvent.setup();
    const onAddAndMention = vi.fn();
    renderWithProviders(<Harness onAddAndMention={onAddAndMention} />);
    await user.type(box(), "@everyone");
    expect(await screen.findByRole("option", { name: /Add and mention “everyone”/ })).toBeVisible();
    await user.keyboard("{Enter}");
    await user.type(box(), "@bo");
    await user.click(await screen.findByRole("option", { name: /Bob/ }));
    expect(stored()).toBe("<@everyone> <@7> ");
    expect(onAddAndMention).not.toHaveBeenCalled();
  });

  test("a stored token shows as the name of a person the session knows, and as the token otherwise", async () => {
    const user = userEvent.setup();
    renderWithProviders(<Harness />);
    await user.type(box(), "@bo");
    await user.click(await screen.findByRole("option", { name: /Bob/ }));
    await user.type(box(), "<@99>");
    expect(box()).toHaveValue("@Bob <@99>");
    expect(stored()).toBe("<@7> <@99>");
  });

  test("a character typed over a selection inside a mention replaces the whole mention", async () => {
    const user = userEvent.setup();
    renderWithProviders(<Harness />);
    await user.type(box(), "x @bo");
    await user.click(await screen.findByRole("option", { name: /Bob/ }));
    await user.type(box(), "y");
    expect(box()).toHaveValue("x @Bob y");
    // Select "@Bob" (2-6) and type the "@" that precedes it: the text alone reads as a deletion
    box().focus();
    box().setSelectionRange(2, 6);
    fireEvent.select(box());
    await user.keyboard("@");
    expect(stored()).toBe("x @ y");
    expect(box()).toHaveValue("x @ y");
  });

  test("at the length cap a character typed in the middle leaves the caret where it was", async () => {
    const user = userEvent.setup();
    renderWithProviders(<Harness initial="abcde" maxLength={5} />);
    box().focus();
    box().setSelectionRange(2, 2);
    await user.keyboard("x");
    expect(stored()).toBe("abcde");
    await waitFor(() => expect(box().selectionStart).toBe(2));
    expect(box().selectionEnd).toBe(2);
  });

  test("a pick that does not fit in the limit closes the list", async () => {
    const user = userEvent.setup();
    // "@bo" is 3 characters, "<@7> " is 5
    renderWithProviders(<Harness maxLength={4} />);
    await user.type(box(), "@bo");
    await screen.findByRole("option", { name: /Bob/ });
    await user.keyboard("{Enter}");
    expect(screen.queryByRole("listbox")).not.toBeInTheDocument();
    expect(box()).not.toHaveAttribute("aria-activedescendant");
    expect(stored()).toBe("@bo");
  });

  test("a refetch that shrinks the list under the highlighted row still picks, and the highlight points at a row", async () => {
    const user = userEvent.setup();
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    let reads = 0;
    mockFetch.mockImplementation(() => {
      // The first read lists two people, every later one only the first (someone left the room)
      const items = reads++ === 0 ? MEMBERS.slice(0, 2) : MEMBERS.slice(0, 1);
      return Promise.resolve(jsonResponse(200, { items, page: 1, pageSize: 8, total: items.length }));
    });
    renderWithProviders(<Harness />, { queryClient });
    await user.type(box(), "@an");
    await screen.findByRole("option", { name: /Anna Other/ });
    await user.keyboard("{ArrowDown}");
    await queryClient.invalidateQueries();
    await waitFor(() => expect(screen.getAllByRole("option")).toHaveLength(1));
    expect(document.getElementById(box().getAttribute("aria-activedescendant")!)).toHaveAccessibleName(/Ann Owner/);
    await user.keyboard("{Enter}");
    expect(stored()).toBe("<@5> ");
  });

  test("Escape stays in force while the text before the @ changes, and ends with that @", async () => {
    const user = userEvent.setup();
    renderWithProviders(<Harness />);
    await user.type(box(), "x @an");
    await screen.findByRole("listbox");
    await user.keyboard("{Escape}");
    expect(screen.queryByRole("listbox")).not.toBeInTheDocument();
    // Typing before the "@" shifts it; back at the term the list stays closed
    box().setSelectionRange(0, 0);
    fireEvent.select(box());
    await user.keyboard("yy");
    expect(stored()).toBe("yyx @an");
    box().setSelectionRange(7, 7);
    fireEvent.select(box());
    await new Promise((resolve) => setTimeout(resolve, 400));
    expect(screen.queryByRole("listbox")).not.toBeInTheDocument();
    // A different "@" is a different list
    await user.type(box(), " @bo");
    expect(await screen.findByRole("option", { name: /Bob/ })).toBeInTheDocument();
  });

  test("a response that arrives late for an earlier term does not change the list", async () => {
    const user = userEvent.setup();
    const late: Array<() => void> = [];
    mockFetch.mockImplementation((url: string) => {
      const name = new URL(url, "http://x").searchParams.get("name")?.toLowerCase() ?? "";
      // This stub answers the earlier term "a" with everyone, and only when told to
      const items = name === "a" ? MEMBERS : MEMBERS.filter((member) => member.name.toLowerCase().includes(name));
      const response = () => jsonResponse(200, { items, page: 1, pageSize: 8, total: items.length });
      return name === "a" ? new Promise((resolve) => late.push(() => resolve(response()))) : Promise.resolve(response());
    });
    renderWithProviders(<Harness />);
    await user.type(box(), "@a");
    await waitFor(() => expect(late).toHaveLength(1));
    await user.type(box(), "n");
    expect(await screen.findByRole("option", { name: /Anna Other/ })).toBeInTheDocument();
    expect(screen.queryByRole("option", { name: /Bob/ })).not.toBeInTheDocument();
    late.forEach((resolve) => resolve());
    await new Promise((resolve) => setTimeout(resolve, 50));
    expect(screen.getAllByRole("option")).toHaveLength(2);
    expect(screen.queryByRole("option", { name: /Bob/ })).not.toBeInTheDocument();
  });

  test("while the read for the new term is pending, the previous read is narrowed by what is typed", async () => {
    const user = userEvent.setup();
    const pending: Array<() => void> = [];
    mockFetch.mockImplementation((url: string) => {
      const name = new URL(url, "http://x").searchParams.get("name")?.toLowerCase() ?? "";
      if (name === "a") return Promise.resolve(jsonResponse(200, { items: MEMBERS, page: 1, pageSize: 8, total: 3 }));
      return new Promise((resolve) => pending.push(() => resolve(jsonResponse(200, { items: [MEMBERS[1]], page: 1, pageSize: 8, total: 1 }))));
    });
    renderWithProviders(<Harness />);
    await user.type(box(), "@a");
    expect(await screen.findByRole("option", { name: /Bob/ })).toBeInTheDocument();
    await user.type(box(), "n");
    // Bob does not match "an": gone at once, though the read for "an" is still out
    await waitFor(() => expect(screen.queryByRole("option", { name: /Bob/ })).not.toBeInTheDocument());
    expect(screen.getAllByRole("option")).toHaveLength(2);
    await user.keyboard("{ArrowDown}{Enter}");
    expect(stored()).toBe("<@6> ");
  });

  test("a picker emoji inside a mention takes the mention whole and the caret follows the emoji", async () => {
    const user = userEvent.setup();
    renderWithProviders(<Harness />);
    await user.type(box(), "x @bo");
    await user.click(await screen.findByRole("option", { name: /Bob/ }));
    expect(box()).toHaveValue("x @Bob ");
    box().setSelectionRange(5, 5);
    await user.click(screen.getByLabelText("Insert emoji"));
    expect(stored()).toBe("x 😀 ");
    // "x " and the emoji (2 units): the caret sits right after it
    await waitFor(() => expect(box().selectionStart).toBe(4));
  });
});
