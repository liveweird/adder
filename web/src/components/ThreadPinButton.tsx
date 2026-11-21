import { useState } from "react";
import { useTranslation } from "react-i18next";
import { useQueryClient } from "@tanstack/react-query";
import { IconPin, IconPinFilled } from "@tabler/icons-react";
import { setThreadPin } from "../api/pins";
import type { RoomResponse } from "../api/rooms";
import { useRoomPins } from "../hooks/useRoomPins";
import { canPin, pinErrorMessage, refreshAfterPinFailure, refreshAfterPinWrite } from "../utils/pins";
import HeaderIconButton from "./HeaderIconButton";

/**
 * "Pin this thread" in the thread pane's header, an icon button (`HeaderIconButton`): a toggle (`aria-pressed`, one constant label) for a member of a live room. Whether the
 * thread is pinned comes from the room's pins query (the thread carries no flag). A closed thread can be pinned (a pin is the room's). The
 * failure (the room's 50-pin limit has its own words) goes to the pane (`onError`), which draws it in a full-width row of the header, outside the icon group; a refusal re-reads the room, so the control follows the truth.
 */
export default function ThreadPinButton({ room, threadNumber, onError }: { room: RoomResponse; threadNumber: number; onError: (message: string | null) => void }) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const { threadNumbers } = useRoomPins(room.id);
  const [busy, setBusy] = useState(false);
  if (!canPin(room)) return null;
  const pinned = threadNumbers.has(threadNumber);

  async function toggle() {
    onError(null);
    setBusy(true);
    try {
      await setThreadPin(room.id, threadNumber, !pinned);
      await refreshAfterPinWrite(queryClient, room.id);
    } catch (err) {
      onError(pinErrorMessage(err, t));
      await refreshAfterPinFailure(queryClient, room.id, err);
    } finally {
      setBusy(false);
    }
  }

  return (
    <HeaderIconButton
      label={t("threads.pin.thread")}
      pressed={pinned}
      loading={busy}
      icon={pinned ? <IconPinFilled size={16} /> : <IconPin size={16} />}
      onClick={() => void toggle()}
    />
  );
}
