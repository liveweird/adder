import { listRoomMembers } from "../api/rooms";
import { OPTION_PAGE_SIZE } from "../hooks/usePagedSort";
import type { PickedOption } from "../components/SearchPicker";
import { roomKeys } from "./roomKeys";

/** The query-key prefix of a room's member picker (the debounced term is appended). Under the ["rooms"] prefix, so a room refresh re-reads the options. */
export const roomMemberPickerKey = (roomId: number) => [...roomKeys.members(roomId), "picker"] as const;

/** One room's members as picker options ("Name (email)", which tells namesakes apart), `term` filtering by name; "" is the unfiltered head. */
export async function searchRoomMemberOptions(roomId: number, term: string): Promise<PickedOption[]> {
  const page = await listRoomMembers(roomId, { page: 1, pageSize: OPTION_PAGE_SIZE, sort: "name", name: term || undefined });
  return page.items.map((member) => ({ value: String(member.userId), label: `${member.name} (${member.email})` }));
}
