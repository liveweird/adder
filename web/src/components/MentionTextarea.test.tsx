import { useState } from "react";
import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { fireEvent, screen, waitFor } from "@testing-library/react";
import MentionTextarea from "./MentionTextarea";
import { jsonResponse } from "../test/http";
import { renderWithProviders } from "../test/render";
import { isSendShortcut } from "../utils/messageForm";

const MEMBERS = [
  { userId: 5, name: "Ann Owner", email: "ann@example.test", role: "OWNER", addedAt: 1 },
  { userId: 6, name: "Anna Other", email: "anna@example.test", role: "MEMBER", addedAt: 2 },
  { userId: 7, name: "Bob", email: "bob@example.test", role: "MEMBER", addedAt: 3 },
];

function Harness({ onSend, initial = "" }: { onSend?: () => void; initial?: string }) {
  const [text, setText] = useState(initial);
  return (
    <>
      <MentionTextarea
        roomId={9}
        aria-label="Reply"
        maxLength={8000}
        value={text}
        onChange={setText}
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
    const list = await screen.findByRole("listbox", { name: "People in this room" });
    expect(box()).toHaveAttribute("aria-controls", list.id);
    expect(box()).toHaveAttribute("aria-autocomplete", "list");
    const activeId = () => box().getAttribute("aria-activedescendant");
    expect(document.getElementById(activeId()!)).toHaveAccessibleName(/Ann Owner/);
    expect(document.getElementById(activeId()!)).toHaveAttribute("aria-selected", "true");
    await user.keyboard("{ArrowDown}");
    expect(document.getElementById(activeId()!)).toHaveAccessibleName(/Anna Other/);
    expect(await screen.findByText("2 people match")).toBeInTheDocument();
    // Closed, the textarea points at nothing.
    await user.keyboard("{Escape}");
    expect(box()).not.toHaveAttribute("aria-controls");
    expect(box()).not.toHaveAttribute("aria-activedescendant");
  });

  test("no one matching says so and shows no list", async () => {
    const user = userEvent.setup();
    renderWithProviders(<Harness />);
    await user.type(box(), "@zzz");
    expect(await screen.findByText("No one in this room matches")).toBeInTheDocument();
    expect(screen.queryByRole("listbox")).not.toBeInTheDocument();
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
});
