import type { ReactNode } from "react";
import { useTranslation } from "react-i18next";
import { listRoomMembers } from "../api/rooms";
import { OPTION_PAGE_SIZE } from "../hooks/usePagedSort";
import SearchPicker, { type PickedOption } from "./SearchPicker";

/** The server's cap on the members' `name` filter. */
const MEMBER_NAME_MAX_LENGTH = 100;

/**
 * A searchable, clearable picker over one room's members (`GET /rooms/{id}/members?name=`, a reader's view: active people only). It
 * is how a USER field and (later) the thread owner are chosen — both must be active members. Options read "Name (email)", which tells
 * namesakes apart; the caller holds the pick.
 */
export default function RoomMemberSelect({
  roomId,
  label,
  description,
  picked,
  onPick,
  disabled,
  error,
  required,
}: {
  roomId: number;
  label: string;
  description?: string;
  picked: PickedOption | null;
  onPick: (picked: PickedOption | null) => void;
  disabled?: boolean;
  error?: ReactNode;
  required?: boolean;
}) {
  const { t } = useTranslation();
  return (
    <SearchPicker
      label={label}
      description={description}
      required={required}
      placeholder={t("threads.memberPicker.placeholder")}
      clearLabel={t("threads.memberPicker.clear", { label })}
      noMatchLabel={t("threads.memberPicker.noMatch")}
      maxLength={MEMBER_NAME_MAX_LENGTH}
      // Under the ["rooms"] prefix, so any room refresh (a member removed, say) re-reads the options.
      queryKey={["rooms", "members", roomId, "picker"]}
      search={async (term) => {
        const page = await listRoomMembers(roomId, { page: 1, pageSize: OPTION_PAGE_SIZE, sort: "name", name: term || undefined });
        return page.items.map((member) => ({ value: String(member.userId), label: `${member.name} (${member.email})` }));
      }}
      picked={picked}
      onPick={onPick}
      disabled={disabled}
      error={error}
    />
  );
}
