import { describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import RoomsRail from "./RoomsRail";
import type { RoomListItem } from "../api/rooms";
import type { useRailRooms } from "../hooks/useRailRooms";
import { renderWithProviders, screen, within } from "../test/render";

type Rooms = ReturnType<typeof useRailRooms>;

const room = (overrides: Partial<RoomListItem> & Pick<RoomListItem, "id" | "name">): RoomListItem => ({
  description: null,
  archived: false,
  role: "MEMBER",
  memberCount: 3,
  lastActivityAt: 1,
  createdAt: 1,
  unread: false,
  ...overrides,
});

function railOf(items: RoomListItem[]): Rooms {
  const empty = { data: undefined, error: null, isLoading: false };
  return { active: { data: { items, page: 1, pageSize: 100, total: items.length }, error: null, isLoading: false }, archived: empty } as unknown as Rooms;
}

function renderRail(
  items: RoomListItem[],
  {
    collapsed = false,
    activeTo = null,
    mentions,
    onNavigate = vi.fn(),
  }: {
    collapsed?: boolean;
    activeTo?: string | null;
    mentions?: ReadonlyMap<number, number>;
    onNavigate?: (to: string) => void;
  } = {},
) {
  return renderWithProviders(
    <RoomsRail rooms={railOf(items)} showArchived={false} onShowArchivedChange={vi.fn()} collapsed={collapsed} activeTo={activeTo} onNavigate={onNavigate} mentions={mentions} />,
  );
}

describe("RoomsRail unread marks", () => {
  test("a new room that is not open carries a dot, said in words by the link's description; its name stays the room's name", () => {
    renderRail([room({ id: 1, name: "Night Shift", unread: true }), room({ id: 2, name: "Day Shift" })]);
    const link = screen.getByRole("link", { name: "Night Shift" });
    expect(link).toHaveAccessibleDescription("New activity");
    expect(link).toHaveAccessibleName("Night Shift");
    // A room with nothing new has no description at all.
    expect(screen.getByRole("link", { name: "Day Shift" })).not.toHaveAttribute("aria-describedby");
  });

  test("the open room shows no dot, even when the server still says it is new", () => {
    renderRail([room({ id: 1, name: "Night Shift", unread: true })], { activeTo: "/rooms/1" });
    const link = screen.getByRole("link", { name: "Night Shift" });
    expect(link).toHaveAttribute("aria-current", "page");
    expect(link).not.toHaveAttribute("aria-describedby");
  });

  test("the unseen notices of a room are counted with an @ mark and described", () => {
    renderRail([room({ id: 1, name: "Night Shift" }), room({ id: 2, name: "Day Shift" })], { mentions: new Map([[1, 2], [2, 1]]) });
    const link = screen.getByRole("link", { name: "Night Shift" });
    expect(link).toHaveAccessibleDescription("2 unseen notifications");
    // The badge is for the eye: its text is hidden from the accessibility tree.
    expect(within(link).getByText("2").closest("[aria-hidden='true']")).not.toBeNull();
    expect(screen.getByRole("link", { name: "Day Shift" })).toHaveAccessibleDescription("1 unseen notification");
  });

  test("a new room with unseen notices says both, and the count caps at 99+", () => {
    renderRail([room({ id: 1, name: "Night Shift", unread: true })], { mentions: new Map([[1, 100]]) });
    const link = screen.getByRole("link", { name: "Night Shift" });
    expect(link).toHaveAccessibleDescription("New activity. 100 unseen notifications");
    expect(within(link).getByText("99+")).toBeInTheDocument();
  });

  test("the open room still counts its unseen notices (reading the thread clears them)", () => {
    renderRail([room({ id: 1, name: "Night Shift", unread: true })], { activeTo: "/rooms/1", mentions: new Map([[1, 3]]) });
    expect(screen.getByRole("link", { name: "Night Shift" })).toHaveAccessibleDescription("3 unseen notifications");
  });

  test("the icon rail keeps the room's name as the link's name and carries the same description", () => {
    renderRail([room({ id: 1, name: "Night Shift", unread: true }), room({ id: 2, name: "Day Shift" })], { collapsed: true, mentions: new Map([[1, 2]]) });
    const link = screen.getByRole("link", { name: "Night Shift" });
    expect(link).toHaveAccessibleDescription("New activity. 2 unseen notifications");
    expect(screen.getByRole("link", { name: "Day Shift" })).not.toHaveAttribute("aria-describedby");
  });

  test("a new room and a notice count are both the shared blue cue (a dot and a count badge)", () => {
    const { container } = renderRail([room({ id: 1, name: "Night Shift", unread: true })], { mentions: new Map([[1, 4]]) });
    expect(container.querySelectorAll("[data-unread-cue=dot]")).toHaveLength(1);
    expect(container.querySelector("[data-unread-cue=count]")).toHaveTextContent("4");
  });

  test("the icon rail shows a dot for a room that only holds unseen notices", () => {
    const { container } = renderRail([room({ id: 1, name: "Night Shift" }), room({ id: 2, name: "Day Shift" })], { collapsed: true, mentions: new Map([[1, 1]]) });
    expect(screen.getByRole("link", { name: "Night Shift" })).toHaveAccessibleDescription("1 unseen notification");
    // One unread-cue dot, on the room that has something to say.
    expect(container.querySelectorAll("[data-unread-cue=dot]")).toHaveLength(1);
  });

  test.each([
    { collapsed: false, layout: "expanded" },
    { collapsed: true, layout: "collapsed" },
  ])("activating a room from the $layout rail reports its destination so the mobile drawer can close", async ({ collapsed }) => {
    const user = userEvent.setup();
    const onNavigate = vi.fn();
    renderRail([room({ id: 17, name: "Night Shift" })], { collapsed, onNavigate });

    await user.click(screen.getByRole("link", { name: "Night Shift" }));

    expect(onNavigate).toHaveBeenCalledWith("/rooms/17");
  });

  test("activating the overflow link reports the rooms directory destination", async () => {
    const user = userEvent.setup();
    const onNavigate = vi.fn();
    const rooms = Array.from({ length: 101 }, (_, index) => room({ id: index + 1, name: `Room ${index + 1}` }));
    renderRail(rooms, { onNavigate });

    await user.click(screen.getByRole("link", { name: "More rooms…" }));

    expect(onNavigate).toHaveBeenCalledWith("/rooms");
  });
});
