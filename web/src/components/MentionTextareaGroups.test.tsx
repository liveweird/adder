import { useState } from "react";
import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { screen, waitFor } from "@testing-library/react";
import MentionTextarea from "./MentionTextarea";
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
}: {
  onSend?: () => void;
  initial?: string;
  maxLength?: number;
  offerWatchers?: boolean;
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

describe("MentionTextarea: teams and groups", () => {
  let mockFetch: ReturnType<typeof vi.fn>;
  const memberCalls = () => mockFetch.mock.calls.map(([url]) => url as string).filter((url) => url.startsWith("/api/v1/rooms/9/members"));
  const box = () => screen.getByRole("textbox", { name: "Reply" }) as HTMLTextAreaElement;
  const stored = () => screen.getByLabelText("stored").textContent;

  beforeEach(() => {
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

  describe("teams and groups", () => {
    const TEAMS = [
      { id: 12, name: "On call", handle: "oncall", description: null, managerId: null, managerName: null, memberCount: 3, createdAt: 1, updatedAt: 1 },
      { id: 13, name: "Platform", handle: "platform", description: null, managerId: null, managerName: null, memberCount: 2, createdAt: 1, updatedAt: 1 },
    ];
    const teamCalls = () => mockFetch.mock.calls.map(([url]) => url as string).filter((url) => url.startsWith("/api/v1/teams?"));

    beforeEach(() => {
      const members = mockFetch.getMockImplementation() as (url: string) => Promise<Response>;
      mockFetch.mockImplementation((url: string) => {
        if (url.startsWith("/api/v1/rooms/9/members?")) return members(url);
        if (url.startsWith("/api/v1/rooms/9")) return Promise.resolve(jsonResponse(200, { id: 9, name: "War room", memberCount: 12 }));
        if (!url.startsWith("/api/v1/teams?")) return Promise.resolve(jsonResponse(404, { title: "x", status: 404 }));
        const handle = new URL(url, "http://x").searchParams.get("handle")?.toLowerCase() ?? "";
        const items = TEAMS.filter((team) => team.handle.includes(handle));
        return Promise.resolve(jsonResponse(200, { items, page: 1, pageSize: 5, total: items.length }));
      });
    });

    test("a team whose handle matches is listed from the first letter, under its own heading, and a pick stores the id", async () => {
      const user = userEvent.setup();
      renderWithProviders(<Harness />);
      await user.type(box(), "ping @on");
      const option = await screen.findByRole("option", { name: /@oncall/ });
      expect(screen.getByRole("group", { name: "Teams" })).toContainElement(option);
      expect(screen.queryByRole("option", { name: /@platform/ })).not.toBeInTheDocument();
      // The server is asked for the handle, five rows, by handle.
      await waitFor(() => expect(new URL(teamCalls().at(-1)!, "http://x").searchParams.get("handle")).toBe("on"));
      expect(new URL(teamCalls().at(-1)!, "http://x").searchParams.get("pageSize")).toBe("5");
      await user.click(option);
      expect(stored()).toBe("ping <@team:12> ");
      expect(box()).toHaveValue("ping @oncall ");
    });

    test("no team is read for a term a handle cannot hold - a space, an uppercase-only symbol, past 30 characters", async () => {
      const user = userEvent.setup();
      renderWithProviders(<Harness />);
      await user.type(box(), "@an na");
      await screen.findByText("Nothing in this room matches");
      await user.clear(box());
      await user.type(box(), `@${"x".repeat(31)}`);
      await user.clear(box());
      await user.type(box(), "@a!b");
      await waitFor(() => expect(memberCalls().length).toBeGreaterThan(0));
      expect(teamCalls()).toHaveLength(0);
      // A term a handle can hold is sent folded and lowercase.
      await user.clear(box());
      await user.type(box(), "@On");
      await waitFor(() => expect(new URL(teamCalls().at(-1)!, "http://x").searchParams.get("handle")).toBe("on"));
    });

    test("no team is read before a letter is typed", async () => {
      const user = userEvent.setup();
      renderWithProviders(<Harness />);
      await user.type(box(), "@");
      await screen.findByRole("option", { name: /Ann Owner/ });
      expect(teamCalls()).toHaveLength(0);
    });

    test("the groups are offered by their word, and the watchers also by 'observers'", async () => {
      const user = userEvent.setup();
      renderWithProviders(<Harness />);
      await user.type(box(), "@ev");
      const everyone = await screen.findByRole("option", { name: /@everyone/ });
      expect(screen.getByRole("group", { name: "Groups" })).toContainElement(everyone);
      expect(screen.queryByRole("option", { name: /@watchers/ })).not.toBeInTheDocument();
      await user.click(everyone);
      expect(stored()).toBe("<@everyone> ");
      expect(box()).toHaveValue("@everyone ");
      await user.type(box(), "@obs");
      await user.keyboard("{Enter}");
      expect(stored()).toBe("<@everyone> <@watchers> ");
      expect(box()).toHaveValue("@everyone @watchers ");
    });

    test("an empty @ lists the people and then the groups; where there are no watchers only 'everyone' is offered", async () => {
      const user = userEvent.setup();
      const { unmount } = renderWithProviders(<Harness />);
      await user.type(box(), "@");
      await screen.findByRole("option", { name: /Ann Owner/ });
      expect(screen.getAllByRole("option").map((option) => option.textContent)).toEqual([
        expect.stringContaining("Ann Owner"),
        expect.stringContaining("Anna Other"),
        expect.stringContaining("Bob"),
        expect.stringContaining("@everyone"),
        expect.stringContaining("@watchers"),
      ]);
      unmount();
      renderWithProviders(<Harness offerWatchers={false} />);
      await user.type(box(), "@");
      await screen.findByRole("option", { name: /@everyone/ });
      expect(screen.queryByRole("option", { name: /@watchers/ })).not.toBeInTheDocument();
    });

    test("a draft that names everyone says how many people that reaches, without asking", async () => {
      renderWithProviders(<Harness initial="all hands <@everyone>" />);
      expect(await screen.findByText("@everyone notifies the 11 other members of this room.")).toBeInTheDocument();
      expect(screen.getByTestId("everyone-note")).toHaveAttribute("role", "status");
    });

    test("the note counts the others - one other, and nobody else", async () => {
      const room = (memberCount: number) => ({ id: 9, name: "War room", memberCount });
      mockFetch.mockImplementation((url: string) =>
        Promise.resolve(url === "/api/v1/rooms/9" ? jsonResponse(200, room(2)) : jsonResponse(404, { title: "x", status: 404 })),
      );
      const { unmount } = renderWithProviders(<Harness initial="<@everyone>" />);
      expect(await screen.findByText("@everyone notifies the other member of this room.")).toBeInTheDocument();
      unmount();
      mockFetch.mockImplementation((url: string) =>
        Promise.resolve(url === "/api/v1/rooms/9" ? jsonResponse(200, room(1)) : jsonResponse(404, { title: "x", status: 404 })),
      );
      renderWithProviders(<Harness initial="<@everyone>" />);
      expect(await screen.findByText("@everyone has no one else to notify in this room.")).toBeInTheDocument();
    });

    test("a draft that does not name everyone says nothing and reads no room", async () => {
      renderWithProviders(<Harness initial="all hands <@watchers> <@team:12>" />);
      expect(screen.queryByTestId("everyone-note")).not.toBeInTheDocument();
      expect(mockFetch.mock.calls.map(([url]) => url as string).filter((url) => url === "/api/v1/rooms/9")).toHaveLength(0);
    });
  });

});
