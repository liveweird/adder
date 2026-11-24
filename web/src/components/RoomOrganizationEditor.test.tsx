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

  test("creates and renames groups in focused inline editors", async () => {
    vi.spyOn(crypto, "randomUUID").mockReturnValue("00000000-0000-4000-8000-000000000003");
    const user = userEvent.setup();
    renderWithProviders(<Editor />);

    await user.click(screen.getByRole("button", { name: "New group" }));
    const newName = screen.getByRole("textbox", { name: "Group name" });
    expect(newName).toHaveFocus();
    await user.type(newName, "Customers");
    await user.click(screen.getByRole("button", { name: "Create group" }));
    expect(screen.getByRole("region", { name: "Customers" })).toBeInTheDocument();

    await openActions(user, "Operations");
    await user.click(screen.getByRole("menuitem", { name: "Rename" }));
    const operationsName = screen.getByRole("textbox", { name: "Rename Operations" });
    expect(operationsName).toHaveFocus();
    await user.clear(operationsName);
    await user.type(operationsName, "Response");
    await user.keyboard("{Enter}");
    expect(screen.getByRole("region", { name: "Response" })).toBeInTheDocument();
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
    expect(projects).toHaveAttribute("data-drop-target", "true");
    fireEvent.pointerUp(handle, { pointerId: 7, clientX: 20, clientY: 30 });
    expect(within(projects).getByText("General")).toBeInTheDocument();
  });

  test("drags groups and supports Escape cancellation", () => {
    const elementFromPoint = vi.spyOn(document, "elementFromPoint");
    renderWithProviders(<Editor />);
    const projectHandle = screen.getByRole("button", { name: "Drag Projects" });
    const operations = screen.getByRole("region", { name: "Operations" });
    elementFromPoint.mockReturnValue(operations);
    Object.defineProperty(projectHandle, "setPointerCapture", { value: vi.fn(), configurable: true });
    Object.defineProperty(projectHandle, "releasePointerCapture", { value: vi.fn(), configurable: true });
    fireEvent.pointerDown(projectHandle, { pointerId: 8, button: 0, isPrimary: true, clientX: 10, clientY: 10 });
    fireEvent.pointerMove(projectHandle, { pointerId: 8, clientX: 20, clientY: 30 });
    fireEvent.pointerUp(projectHandle, { pointerId: 8, clientX: 20, clientY: 30 });
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
  });
});
