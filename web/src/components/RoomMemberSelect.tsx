import type { ReactNode } from "react";
import { useTranslation } from "react-i18next";
import SearchPicker, { type PickedOption } from "./SearchPicker";
import { roomMemberPickerKey, searchRoomMemberOptions } from "../utils/roomMemberOptions";

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
  readOnly,
  error,
  required,
  labelHidden,
}: {
  roomId: number;
  label: string;
  description?: string;
  picked: PickedOption | null;
  onPick: (picked: PickedOption | null) => void;
  disabled?: boolean;
  readOnly?: boolean;
  error?: ReactNode;
  required?: boolean;
  /** The label stays the accessible name but is not drawn (a section title already says it). */
  labelHidden?: boolean;
}) {
  const { t } = useTranslation();
  return (
    <SearchPicker
      label={label}
      description={description}
      required={required}
      labelHidden={labelHidden}
      placeholder={t("threads.memberPicker.placeholder")}
      clearLabel={t("threads.memberPicker.clear", { label })}
      noMatchLabel={t("threads.memberPicker.noMatch")}
      maxLength={MEMBER_NAME_MAX_LENGTH}
      // Under the ["rooms"] prefix, so any room refresh (a member removed, say) re-reads the options.
      queryKey={roomMemberPickerKey(roomId)}
      search={(term) => searchRoomMemberOptions(roomId, term)}
      picked={picked}
      onPick={onPick}
      disabled={disabled}
      readOnly={readOnly}
      error={error}
    />
  );
}
