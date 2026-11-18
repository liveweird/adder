import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { useState } from "react";
import userEvent from "@testing-library/user-event";
import { screen, waitFor } from "@testing-library/react";
import RoomMemberSelect from "./RoomMemberSelect";
import type { PickedOption } from "./SearchPicker";
import { jsonResponse } from "../test/http";
import { renderWithProviders } from "../test/render";

const MEMBERS = {
  items: [
    { userId: 7, name: "Mia Manager", email: "mia@example.test", role: "OWNER", addedAt: 1 },
    { userId: 12, name: "Mia Other", email: "other@example.test", role: "MEMBER", addedAt: 2 },
  ],
  page: 1,
  pageSize: 50,
  total: 2,
};

function Harness({ onPick }: { onPick: (picked: PickedOption | null) => void }) {
  const [picked, setPicked] = useState<PickedOption | null>(null);
  return (
    <RoomMemberSelect
      roomId={9}
      label="Lead"
      required
      picked={picked}
      onPick={(next) => {
        setPicked(next);
        onPick(next);
      }}
    />
  );
}

describe("RoomMemberSelect", () => {
  let mockFetch: ReturnType<typeof vi.fn>;
  const memberCalls = () => mockFetch.mock.calls.map(([url]) => url as string).filter((url) => url.startsWith("/api/v1/rooms/9/members"));

  beforeEach(() => {
    mockFetch = vi.fn((url: string) => Promise.resolve(url.startsWith("/api/v1/rooms/9/members?") ? jsonResponse(200, MEMBERS) : jsonResponse(404, { title: "x", status: 404 })));
    vi.stubGlobal("fetch", mockFetch);
    localStorage.setItem("adder.auth.token", "fake-token");
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  test("lists the room's members with their email, searches by name and reports the pick", async () => {
    const onPick = vi.fn();
    const user = userEvent.setup();
    renderWithProviders(<Harness onPick={onPick} />);
    const box = screen.getByRole("combobox", { name: /Lead/ });
    expect(box).toBeRequired();
    await user.click(box);
    // The unfiltered head is searched too (no minimum): the first page of the room's members by name.
    await screen.findByRole("option", { name: "Mia Manager (mia@example.test)" });
    expect(memberCalls()[0]).toBe("/api/v1/rooms/9/members?page=1&pageSize=50&sort=name");
    await user.type(box, "mia");
    // The term settles (300 ms) before it is searched.
    await waitFor(() => expect(memberCalls().some((url) => url.endsWith("&sort=name&name=mia"))).toBe(true));
    await user.click(await screen.findByRole("option", { name: "Mia Other (other@example.test)" }));
    expect(onPick).toHaveBeenLastCalledWith({ value: "12", label: "Mia Other (other@example.test)" });
    expect(screen.getByLabelText("Clear Lead")).toBeInTheDocument();
  });

  test("a search with no hits says so", async () => {
    mockFetch.mockImplementation(() => Promise.resolve(jsonResponse(200, { items: [], page: 1, pageSize: 50, total: 0 })));
    const user = userEvent.setup();
    renderWithProviders(<Harness onPick={vi.fn()} />);
    await user.type(screen.getByRole("combobox", { name: /Lead/ }), "zz");
    expect(await screen.findByText("No matching members")).toBeInTheDocument();
  });
});
