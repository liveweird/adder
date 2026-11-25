import { useState } from "react";
import { fireEvent } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, test, vi } from "vitest";
import type { RoomListItem } from "../api/rooms";
import { renderWithProviders, screen, waitFor, within } from "../test/render";
import type { RoomOrganizationDraft } from "../utils/roomOrganizationDraft";
import RoomOrganizationEditor from "./RoomOrganizationEditor";

const rooms = [
  { id: 10, name: "Incident room", archived: false },
  { id: 11, name: "Delivery room", archived: true },
  { id: 12, name: "General", archived: false },
].map((room) => ({
  ...room,
  description: null,
  accessType: "RESTRICTED_HIDDEN" as const,
  role: "MEMBER" as const,
  memberCount: 2,
  lastActivityAt: 1,
  createdAt: 1,
  unread: false,
})) satisfies RoomListItem[];

const operationsId = "00000000-0000-4000-8000-000000000001";
const projectsId = "00000000-0000-4000-8000-000000000002";
const initial: RoomOrganizationDraft = {
  groups: [
    { id: operationsId, name: "Operations" },
    { id: projectsId, name: "Projects" },
  ],
  rooms: [
    { roomId: 10, groupId: operationsId },
    { roomId: 11, groupId: operationsId },
    { roomId: 12, groupId: null },
  ],
};

function Editor({ onSave = vi.fn(), saving = false, reloading = false, seed = initial }: {
  onSave?: () => void;
  saving?: boolean;
  reloading?: boolean;
  seed?: RoomOrganizationDraft;
}) {
  const [draft, setDraft] = useState(seed);
  return (
    <RoomOrganizationEditor
      rooms={rooms}
      draft={draft}
      onChange={setDraft}
      onSave={onSave}
      onDiscard={() => setDraft(seed)}
      saving={saving}
      reloading={reloading}
      dirty={draft !== initial}
    />
  );
}

async function openActions(user: ReturnType<typeof userEvent.setup>, name: string) {
  await user.click(screen.getByRole("button", { name: `Actions for ${name}` }));
}

function mockRect(element: Element, top: number, height: number) {
  vi.spyOn(element, "getBoundingClientRect").mockReturnValue({
    top, bottom: top + height, left: 0, right: 200, width: 200, height, x: 0, y: top, toJSON: () => ({}),
  });
}

describe("RoomOrganizationEditor", () => {
  afterEach(() => vi.restoreAllMocks());

  test("keeps group and room movement in compact action menus", async () => {
    const user = userEvent.setup();
    renderWithProviders(<Editor />);

    await openActions(user, "Projects");
    await user.click(screen.getByRole("menuitem", { name: "Move up" }));
    expect(screen.getAllByRole("region").map((region) => region.getAttribute("aria-labelledby"))).toEqual([
      `room-group-${projectsId}`,
      `room-group-${operationsId}`,
      "room-group-ungrouped",
    ]);

    await user.click(screen.getByRole("button", { name: "Collapse Projects" }));
    await openActions(user, "General");
    await user.click(screen.getByRole("menuitem", { name: "Move to group" }));
    fireEvent.click(await screen.findByRole("menuitem", { name: "Projects" }));
    const projects = within(screen.getByRole("region", { name: "Projects" }));
    expect(projects.getByText("General")).toBeInTheDocument();
    expect(projects.getByRole("button", { name: "Collapse Projects" })).toBeInTheDocument();
    await waitFor(() => expect(projects.getByRole("button", { name: "Actions for General" })).toHaveFocus());
  });

  test("announces relative menu moves and exposes every compact action", async () => {
    const user = userEvent.setup();
    renderWithProviders(<Editor />);
    const status = screen.getByRole("status");

    await openActions(user, "Incident room");
    await user.click(screen.getByRole("menuitem", { name: "Move down" }));
    await waitFor(() => expect(status).toHaveTextContent("Moved Incident room after Delivery room"));
    await openActions(user, "Incident room");
    await user.click(screen.getByRole("menuitem", { name: "Move up" }));
    await waitFor(() => expect(status).toHaveTextContent("Moved Incident room before Delivery room"));

    await openActions(user, "Operations");
    await user.click(screen.getByRole("menuitem", { name: "Move down" }));
    await waitFor(() => expect(status).toHaveTextContent("Moved Operations after Projects"));
    await openActions(user, "Operations");
    await user.click(screen.getByRole("menuitem", { name: "Delete" }));
    expect(screen.queryByRole("region", { name: "Operations" })).not.toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "Collapse Ungrouped" }));
    expect(screen.getByRole("button", { name: "Expand Ungrouped" })).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Expand Ungrouped" }));
    expect(screen.getByRole("button", { name: "Collapse Ungrouped" })).toBeInTheDocument();
  });

  test("creates and renames groups in focused inline editors", async () => {
    vi.spyOn(crypto, "randomUUID").mockReturnValue("00000000-0000-4000-8000-000000000003");
    const user = userEvent.setup();
    renderWithProviders(<Editor />);

    await user.click(screen.getByRole("button", { name: "New group" }));
    const newName = screen.getByRole("textbox", { name: "Group name" });
    expect(newName).toHaveFocus();
    await user.type(newName, "Customers");
    await user.keyboard("{Enter}");
    expect(screen.getByRole("region", { name: "Customers" })).toBeInTheDocument();

    await openActions(user, "Operations");
    await user.click(screen.getByRole("menuitem", { name: "Rename" }));
    const operationsName = screen.getByRole("textbox", { name: "Rename Operations" });
    expect(operationsName).toHaveFocus();
    await user.clear(operationsName);
    await user.type(operationsName, "Response");
    await user.keyboard("{Enter}");
    expect(screen.getByRole("region", { name: "Response" })).toBeInTheDocument();

    await openActions(user, "Projects");
    await user.click(screen.getByRole("menuitem", { name: "Rename" }));
    await user.click(screen.getByRole("button", { name: "Cancel" }));
    await waitFor(() => expect(screen.getByRole("button", { name: "Actions for Projects" })).toHaveFocus());
  });

  test("collapses groups and keeps archived room state visible", async () => {
    const user = userEvent.setup();
    renderWithProviders(<Editor />);
    const operations = screen.getByRole("region", { name: "Operations" });
    expect(within(operations).getByText("Archived")).toBeInTheDocument();
    await user.click(within(operations).getByRole("button", { name: "Collapse Operations" }));
    expect(within(operations).queryByText("Incident room")).not.toBeInTheDocument();
    await user.click(within(operations).getByRole("button", { name: "Expand Operations" }));
    expect(within(operations).getByText("Incident room")).toBeInTheDocument();
  });

  test("moves a room between groups with the pointer handle", () => {
    const elementFromPoint = vi.spyOn(document, "elementFromPoint");
    renderWithProviders(<Editor />);
    const handle = screen.getByRole("button", { name: "Drag General" });
    const projects = screen.getByRole("region", { name: "Projects" });
    elementFromPoint.mockReturnValue(projects);
    Object.defineProperty(handle, "setPointerCapture", { value: vi.fn(), configurable: true });
    Object.defineProperty(handle, "releasePointerCapture", { value: vi.fn(), configurable: true });
    fireEvent.pointerDown(handle, { pointerId: 7, button: 0, isPrimary: true, clientX: 10, clientY: 10 });
    fireEvent.pointerMove(handle, { pointerId: 7, clientX: 20, clientY: 30 });
    expect(projects).toHaveAttribute("data-drop-target", "end");
    fireEvent.pointerUp(handle, { pointerId: 7, clientX: 20, clientY: 30 });
    expect(within(projects).getByText("General")).toBeInTheDocument();
  });

  test("drags groups and supports Escape cancellation", () => {
    const elementFromPoint = vi.spyOn(document, "elementFromPoint");
    renderWithProviders(<Editor />);
    const projectHandle = screen.getByRole("button", { name: "Drag Projects" });
    const operations = screen.getByRole("region", { name: "Operations" });
    mockRect(operations.querySelector("[data-group-header]")!, 0, 100);
    elementFromPoint.mockReturnValue(operations);
    Object.defineProperty(projectHandle, "setPointerCapture", { value: vi.fn(), configurable: true });
    Object.defineProperty(projectHandle, "releasePointerCapture", { value: vi.fn(), configurable: true });
    fireEvent.pointerDown(projectHandle, { pointerId: 8, button: 0, isPrimary: true, clientX: 10, clientY: 10 });
    fireEvent.pointerMove(projectHandle, { pointerId: 8, clientX: 20, clientY: 20 });
    expect(operations).toHaveAttribute("data-drop-target", "before");
    fireEvent.pointerUp(projectHandle, { pointerId: 8, clientX: 20, clientY: 20 });
    expect(screen.getAllByRole("region")[0]).toHaveAccessibleName("Projects");

    const roomHandle = screen.getByRole("button", { name: "Drag General" });
    const projects = screen.getByRole("region", { name: "Projects" });
    elementFromPoint.mockReturnValue(projects);
    Object.defineProperty(roomHandle, "setPointerCapture", { value: vi.fn(), configurable: true });
    Object.defineProperty(roomHandle, "releasePointerCapture", { value: vi.fn(), configurable: true });
    fireEvent.pointerDown(roomHandle, { pointerId: 9, button: 0, isPrimary: true, clientX: 10, clientY: 10 });
    fireEvent.pointerMove(roomHandle, { pointerId: 9, clientX: 20, clientY: 30 });
    fireEvent.keyDown(window, { key: "Escape" });
    expect(within(screen.getByRole("region", { name: "Ungrouped" })).getByText("General")).toBeInTheDocument();
  });

  test("uses the lower half marker to drag a group downward", () => {
    const elementFromPoint = vi.spyOn(document, "elementFromPoint");
    renderWithProviders(<Editor />);
    const handle = screen.getByRole("button", { name: "Drag Operations" });
    const projects = screen.getByRole("region", { name: "Projects" });
    mockRect(projects.querySelector("[data-group-header]")!, 0, 100);
    elementFromPoint.mockReturnValue(projects);
    Object.defineProperty(handle, "setPointerCapture", { value: vi.fn(), configurable: true });
    Object.defineProperty(handle, "releasePointerCapture", { value: vi.fn(), configurable: true });
    fireEvent.pointerDown(handle, { pointerId: 10, button: 0, isPrimary: true, clientX: 10, clientY: 10 });
    fireEvent.pointerMove(handle, { pointerId: 10, clientX: 20, clientY: 80 });
    expect(projects).toHaveAttribute("data-drop-target", "after");
    fireEvent.pointerUp(handle, { pointerId: 10, clientX: 20, clientY: 80 });
    expect(screen.getAllByRole("region").slice(0, 2).map((region) => region.getAttribute("aria-labelledby"))).toEqual([
      `room-group-${projectsId}`,
      `room-group-${operationsId}`,
    ]);
  });

  test("places rooms before and after hit-tested rows", () => {
    const elementFromPoint = vi.spyOn(document, "elementFromPoint");
    renderWithProviders(<Editor />);
    const handle = screen.getByRole("button", { name: "Drag General" });
    const incident = screen.getByText("Incident room").closest("[data-room-id]")!;
    mockRect(incident, 0, 100);
    elementFromPoint.mockReturnValue(incident);
    Object.defineProperty(handle, "setPointerCapture", { value: vi.fn(), configurable: true });
    Object.defineProperty(handle, "releasePointerCapture", { value: vi.fn(), configurable: true });
    fireEvent.pointerDown(handle, { pointerId: 12, button: 0, isPrimary: true, clientX: 10, clientY: 10 });
    fireEvent.pointerMove(handle, { pointerId: 12, clientX: 20, clientY: 20 });
    expect(incident).toHaveAttribute("data-drop-target", "before");
    fireEvent.pointerUp(handle, { pointerId: 12, clientX: 20, clientY: 20 });

    const movedHandle = screen.getByRole("button", { name: "Drag General" });
    const delivery = screen.getByText("Delivery room").closest("[data-room-id]")!;
    mockRect(delivery, 0, 100);
    elementFromPoint.mockReturnValue(delivery);
    Object.defineProperty(movedHandle, "setPointerCapture", { value: vi.fn(), configurable: true });
    Object.defineProperty(movedHandle, "releasePointerCapture", { value: vi.fn(), configurable: true });
    fireEvent.pointerDown(movedHandle, { pointerId: 13, button: 0, isPrimary: true, clientX: 10, clientY: 10 });
    fireEvent.pointerMove(movedHandle, { pointerId: 13, clientX: 20, clientY: 80 });
    expect(delivery).toHaveAttribute("data-drop-target", "after");
    fireEvent.pointerUp(movedHandle, { pointerId: 13, clientX: 20, clientY: 80 });
  });

  test("cleans up cancelled, lost and off-target pointer gestures", () => {
    const elementFromPoint = vi.spyOn(document, "elementFromPoint");
    const scrollBy = vi.spyOn(window, "scrollBy").mockImplementation(() => undefined);
    let animationFrame: FrameRequestCallback | undefined;
    vi.spyOn(window, "requestAnimationFrame").mockImplementation((callback) => {
      animationFrame = callback;
      return 1;
    });
    renderWithProviders(<Editor />);
    const handle = screen.getByRole("button", { name: "Drag General" });
    Object.defineProperty(handle, "setPointerCapture", { value: vi.fn(), configurable: true });
    Object.defineProperty(handle, "releasePointerCapture", { value: vi.fn(), configurable: true });
    Object.defineProperty(handle, "hasPointerCapture", { value: vi.fn(() => true), configurable: true });
    elementFromPoint.mockReturnValue(null);
    fireEvent.pointerDown(handle, { pointerId: 14, button: 1, isPrimary: true, clientX: 10, clientY: 10 });
    fireEvent.pointerDown(handle, { pointerId: 14, button: 0, isPrimary: false, clientX: 10, clientY: 10 });
    fireEvent.pointerDown(handle, { pointerId: 14, button: 0, isPrimary: true, clientX: 10, clientY: 10 });
    fireEvent.pointerMove(handle, { pointerId: 99, clientX: 20, clientY: 20 });
    fireEvent.pointerMove(handle, { pointerId: 14, clientX: 12, clientY: 12 });
    fireEvent.pointerMove(handle, { pointerId: 14, clientX: 20, clientY: 1 });
    animationFrame?.(1);
    expect(scrollBy).toHaveBeenCalled();
    fireEvent.pointerCancel(handle, { pointerId: 14 });

    fireEvent.pointerDown(handle, { pointerId: 15, button: 0, isPrimary: true, clientX: 10, clientY: 10 });
    fireEvent.pointerMove(handle, { pointerId: 15, clientX: 20, clientY: window.innerHeight - 1 });
    animationFrame?.(2);
    fireEvent.lostPointerCapture(handle, { pointerId: 15 });

    fireEvent.pointerDown(handle, { pointerId: 16, button: 0, isPrimary: true, clientX: 10, clientY: 10 });
    elementFromPoint.mockReturnValue(document.body);
    fireEvent.pointerMove(handle, { pointerId: 16, clientX: 20, clientY: window.innerHeight / 2 });
    animationFrame?.(3);
    fireEvent.pointerUp(handle, { pointerId: 99, clientX: 20, clientY: 20 });
    fireEvent.keyDown(window, { key: "Escape" });
  });

  test("prevents duplicate names and freezes all edits while saving or reloading", async () => {
    const user = userEvent.setup();
    const { rerender } = renderWithProviders(<Editor />);
    await openActions(user, "Projects");
    await user.click(screen.getByRole("menuitem", { name: "Rename" }));
    const projectsName = screen.getByRole("textbox", { name: "Rename Projects" });
    await user.clear(projectsName);
    await user.type(projectsName, "operations");
    expect(screen.getByText("Group names must be unique.")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Save organization" })).not.toBeInTheDocument();
    rerender(<Editor saving />);
    expect(screen.getByRole("button", { name: "New group" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "Actions for Operations" })).toBeDisabled();
    rerender(<Editor reloading />);
    expect(screen.getByRole("button", { name: "Drag Incident room" })).toBeDisabled();
  });

  test("clears an unfinished rename when dirty changes are discarded", async () => {
    const user = userEvent.setup();
    renderWithProviders(<Editor />);
    await openActions(user, "Projects");
    await user.click(screen.getByRole("menuitem", { name: "Move up" }));
    await openActions(user, "Projects");
    await user.click(screen.getByRole("menuitem", { name: "Rename" }));
    await user.type(screen.getByRole("textbox", { name: "Rename Projects" }), " draft");
    await user.click(screen.getByRole("button", { name: "Discard changes" }));
    await waitFor(() => expect(screen.queryByRole("textbox", { name: "Rename Projects" })).not.toBeInTheDocument());
    expect(screen.getByRole("region", { name: "Projects" })).toBeInTheDocument();
  });

  test("disables a destination that would exceed the placement limit", async () => {
    const user = userEvent.setup();
    const full: RoomOrganizationDraft = {
      groups: initial.groups,
      rooms: Array.from({ length: 2_000 }, (_, index) => ({ roomId: index + 1_000, groupId: null })),
    };
    renderWithProviders(<Editor seed={full} />);
    await openActions(user, "General");
    await user.click(screen.getByRole("menuitem", { name: "Move to group" }));
    expect(await screen.findByRole("menuitem", { name: "Projects" })).toBeDisabled();

    await user.keyboard("{Escape}{Escape}");
    const handle = screen.getByRole("button", { name: "Drag General" });
    const projects = screen.getByRole("region", { name: "Projects" });
    vi.spyOn(document, "elementFromPoint").mockReturnValue(projects);
    Object.defineProperty(handle, "setPointerCapture", { value: vi.fn(), configurable: true });
    Object.defineProperty(handle, "releasePointerCapture", { value: vi.fn(), configurable: true });
    fireEvent.pointerDown(handle, { pointerId: 11, button: 0, isPrimary: true, clientX: 10, clientY: 10 });
    fireEvent.pointerMove(handle, { pointerId: 11, clientX: 20, clientY: 30 });
    expect(projects).not.toHaveAttribute("data-drop-target");
    fireEvent.pointerUp(handle, { pointerId: 11, clientX: 20, clientY: 30 });
  });

  test("cancels a new group cleanly and freezes pending controls", async () => {
    const user = userEvent.setup();
    const { rerender } = renderWithProviders(<Editor />);
    const trigger = screen.getByRole("button", { name: "New group" });
    await user.click(trigger);
    await user.type(screen.getByRole("textbox", { name: "Group name" }), "Temporary");
    await user.click(screen.getByRole("button", { name: "Cancel" }));
    expect(screen.queryByRole("textbox", { name: "Group name" })).not.toBeInTheDocument();
    await waitFor(() => expect(trigger).toHaveFocus());

    await user.click(trigger);
    expect(screen.getByRole("textbox", { name: "Group name" })).toHaveValue("");
    await user.type(screen.getByRole("textbox", { name: "Group name" }), "Pending");
    rerender(<Editor saving />);
    expect(screen.getByRole("textbox", { name: "Group name" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "Create group" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "Cancel" })).toBeDisabled();
  });
});
