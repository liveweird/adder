import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import RoomMemberSelect from "./RoomMemberSelect";
import type { PickedOption } from "./SearchPicker";
import { roomMemberPickerKey, searchRoomMemberOptions } from "../utils/roomMemberOptions";

/**
 * `RoomMemberSelect` for a FILTER whose value lives in the URL: the caller holds only the user id (a string, or null), so the label of an id
 * it did not pick in this session (a reload, a shared link) is looked up in the picker's own unfiltered head, which shares the picker's cache
 * entry; a member beyond that head reads as `#id`.
 */
export default function MemberFilterSelect({
  roomId,
  label,
  value,
  onChange,
}: {
  roomId: number;
  label: string;
  /** The picked user's id, or null. */
  value: string | null;
  onChange: (value: string | null) => void;
}) {
  const [chosen, setChosen] = useState<PickedOption | null>(null);
  const known = chosen !== null && chosen.value === value ? chosen : null;
  const head = useQuery({
    queryKey: [...roomMemberPickerKey(roomId), ""],
    queryFn: () => searchRoomMemberOptions(roomId, ""),
    enabled: value !== null && known === null,
  });
  const lookedUp = head.data?.find((option) => option.value === value);
  const picked: PickedOption | null = value === null ? null : (known ?? lookedUp ?? { value, label: `#${value}` });
  return (
    <RoomMemberSelect
      roomId={roomId}
      label={label}
      picked={picked}
      onPick={(next) => {
        setChosen(next);
        onChange(next?.value ?? null);
      }}
    />
  );
}
