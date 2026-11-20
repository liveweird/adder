import { useTranslation } from "react-i18next";
import { Menu } from "@mantine/core";
import { useQueryClient } from "@tanstack/react-query";
import { IconPin, IconPinnedOff } from "@tabler/icons-react";
import { setMessagePin } from "../api/pins";
import { useRoomPins } from "../hooks/useRoomPins";
import { pinErrorMessage, refreshAfterPinWrite } from "../utils/pins";
import { refreshQueriesAfterMutation } from "../utils/queryRefresh";
import { isAccessRefusal } from "../utils/roomAccess";

/**
 * "Pin" or "Unpin" in a message's menu, by whether the room's pins query lists the message (the message carries no flag). Any member of a live
 * room may; the caller decides that (`MessageActions.pin`). A pin is the room's, so a closed thread's message can be pinned. The failure goes to
 * `onError` (the message shows it under itself, as for an edit or a delete); the menu has closed by then, so this holds no state.
 */
export default function MessagePinMenuItem({ roomId, messageId, onError }: { roomId: number; messageId: number; onError: (message: string | null) => void }) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const { messageIds } = useRoomPins(roomId);
  const pinned = messageIds.has(messageId);

  async function toggle() {
    onError(null);
    try {
      await setMessagePin(roomId, messageId, !pinned);
      await refreshAfterPinWrite(queryClient, roomId);
    } catch (err) {
      onError(pinErrorMessage(err, t));
      if (isAccessRefusal(err)) await refreshQueriesAfterMutation(queryClient, ["rooms"]);
    }
  }

  return (
    <Menu.Item leftSection={pinned ? <IconPinnedOff size={14} /> : <IconPin size={14} />} onClick={() => void toggle()}>
      {t(pinned ? "threads.pin.unpin" : "threads.pin.pin")}
    </Menu.Item>
  );
}
