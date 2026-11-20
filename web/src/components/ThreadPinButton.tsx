import { useState } from "react";
import { useTranslation } from "react-i18next";
import { Button } from "@mantine/core";
import { useQueryClient } from "@tanstack/react-query";
import { IconPin, IconPinFilled } from "@tabler/icons-react";
import { setThreadPin } from "../api/pins";
import type { RoomResponse } from "../api/rooms";
import { useRoomPins } from "../hooks/useRoomPins";
import { canPin, pinErrorMessage, refreshAfterPinFailure, refreshAfterPinWrite } from "../utils/pins";
import FormErrorAlert from "./FormErrorAlert";

/**
 * "Pin this thread" in the thread pane's button row: a toggle (`aria-pressed`, one constant label) for a member of a live room. Whether the
 * thread is pinned comes from the room's pins query (the thread carries no flag). A closed thread can be pinned (a pin is the room's). The
 * failure (the room's 50-pin limit has its own words) shows beside the button; a refusal re-reads the room, so the control follows the truth.
 */
export default function ThreadPinButton({ room, threadNumber }: { room: RoomResponse; threadNumber: number }) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const { threadNumbers } = useRoomPins(room.id);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  if (!canPin(room)) return null;
  const pinned = threadNumbers.has(threadNumber);

  async function toggle() {
    setError(null);
    setBusy(true);
    try {
      await setThreadPin(room.id, threadNumber, !pinned);
      await refreshAfterPinWrite(queryClient, room.id);
    } catch (err) {
      setError(pinErrorMessage(err, t));
      await refreshAfterPinFailure(queryClient, room.id, err);
    } finally {
      setBusy(false);
    }
  }

  return (
    <>
      <Button
        variant={pinned ? "light" : "default"}
        size="xs"
        aria-pressed={pinned}
        loading={busy}
        leftSection={pinned ? <IconPinFilled size={14} /> : <IconPin size={14} />}
        onClick={() => void toggle()}
      >
        {t("threads.pin.thread")}
      </Button>
      {error && <FormErrorAlert w="100%">{error}</FormErrorAlert>}
    </>
  );
}
