import { useState } from "react";
import { afterEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
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

const initial: RoomOrganizationDraft = {
  groups: [
    { id: "00000000-0000-4000-8000-000000000001", name: "Operations" },
    { id: "00000000-0000-4000-8000-000000000002", name: "Projects" },
  ],
  rooms: [
    { roomId: 10, groupId: "00000000-0000-4000-8000-000000000001" },
    { roomId: 11, groupId: "00000000-0000-4000-8000-000000000001" },
    { roomId: 12, groupId: null },
  ],
};

function Editor({ onSave = vi.fn(), saving = false }: { onSave?: () => void; saving?: boolean }) {
  const [draft, setDraft] = useState(initial);
  return (
    <RoomOrganizationEditor
      rooms={rooms}
      draft={draft}
      onChange={setDraft}
      onSave={onSave}
      onDiscard={() => setDraft(initial)}
      saving={saving}
      dirty={draft !== initial}
    />
  );
}

describe("RoomOrganizationEditor", () => {
  afterEach(() => vi.restoreAllMocks());

  test("moves groups and rooms using named controls and keeps DOM order aligned", async () => {
    const user = userEvent.setup();
    renderWithProviders(<Editor />);

    await user.click(screen.getByRole("button", { name: "Move Projects up" }));
    expect(screen.getAllByRole("region").map((region) => region.getAttribute("aria-labelledby"))).toEqual([
      "room-group-00000000-0000-4000-8000-000000000002",
      "room-group-00000000-0000-4000-8000-000000000001",
      "room-group-ungrouped",
    ]);

    await user.click(screen.getByRole("button", { name: "Move Projects down" }));
    expect(screen.getAllByRole("region").map((region) => region.getAttribute("aria-labelledby"))).toEqual([
      "room-group-00000000-0000-4000-8000-000000000001",
      "room-group-00000000-0000-4000-8000-000000000002",
      "room-group-ungrouped",
    ]);

    await user.click(screen.getByRole("button", { name: "Move Delivery room up" }));
    expect(within(screen.getByRole("region", { name: "Operations" })).getAllByText(/room$/).map((item) => item.textContent)).toEqual([
      "Delivery room",
      "Incident room",
    ]);

    await user.click(screen.getByRole("combobox", { name: "Group for General" }));
    await user.click(screen.getByRole("option", { name: "Projects" }));
    const projects = within(screen.getByRole("region", { name: "Projects" }));
    expect(projects.getByText("General")).toBeInTheDocument();
    await waitFor(() => expect(projects.getByRole("combobox", { name: "Group for General" })).toHaveFocus());
  });

  test("creates, renames and deletes private groups before an explicit save", async () => {
    vi.spyOn(crypto, "randomUUID").mockReturnValue("00000000-0000-4000-8000-000000000003");
    const onSave = vi.fn();
    const user = userEvent.setup();
    renderWithProviders(<Editor onSave={onSave} />);

    await user.type(screen.getByRole("textbox", { name: "Group name" }), "Customers");
    await user.click(screen.getByRole("button", { name: "Add group" }));
    expect(screen.getByRole("region", { name: "Customers" })).toBeInTheDocument();

    const operationsName = screen.getByRole("textbox", { name: "Rename Operations" });
    await user.clear(operationsName);
    await user.type(operationsName, "Response");
    expect(screen.getByRole("region", { name: "Response" })).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "Delete Response" }));
    expect(within(screen.getByRole("region", { name: "Ungrouped" })).getByText("Incident room")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Save organization" }));
    expect(onSave).toHaveBeenCalledOnce();
  });

  test("prevents ambiguous duplicate names and freezes every edit while saving", async () => {
    const user = userEvent.setup();
    const { rerender } = renderWithProviders(<Editor />);

    const projectsName = screen.getByRole("textbox", { name: "Rename Projects" });
    await user.clear(projectsName);
    await user.type(projectsName, "operations");
    expect(screen.getAllByText("Group names must be unique.")).toHaveLength(2);
    expect(screen.getByRole("button", { name: "Save organization" })).toBeDisabled();

    rerender(<Editor saving />);
    expect(screen.getByRole("textbox", { name: "Rename Operations" })).toBeDisabled();
    expect(screen.getByRole("combobox", { name: "Group for Incident room" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "Delete Operations" })).toBeDisabled();
  });
});
