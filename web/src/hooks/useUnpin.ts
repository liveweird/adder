import { useState } from "react";
import { useTranslation } from "react-i18next";
import { useQueryClient } from "@tanstack/react-query";
import { setMessagePin, setThreadPin, type RoomPin } from "../api/pins";
import { pinErrorMessage, refreshAfterPinFailure, refreshAfterPinWrite } from "../utils/pins";

/**
 * "Unpin" for the two places that list pins (the room's drawer and the open thread's strip): one write at a time, the failure kept as words
 * and the pins re-read after it (`utils/pins.ts`: the success re-reads only the room's pins, a refusal what the refusal calls for). `unpin` answers
 * whether the pin was removed, so a caller can put the focus somewhere sensible once its button is gone.
 */
export function useUnpin(roomId: number) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const [failure, setFailure] = useState<string | null>(null);
  const [busyId, setBusyId] = useState<number | null>(null);

  async function unpin(pin: RoomPin): Promise<boolean> {
    // A message pin without its message cannot be addressed; it must never fall through to unpinning the THREAD.
    if (pin.kind === "MESSAGE" && !pin.message) return false;
    setFailure(null);
    setBusyId(pin.id);
    try {
      if (pin.kind === "MESSAGE" && pin.message) await setMessagePin(roomId, pin.message.id, false);
      else await setThreadPin(roomId, pin.threadNumber, false);
      await refreshAfterPinWrite(queryClient, roomId);
      return true;
    } catch (err) {
      setFailure(pinErrorMessage(err, t));
      await refreshAfterPinFailure(queryClient, roomId, err);
      return false;
    } finally {
      setBusyId(null);
    }
  }

  return { unpin, busyId, failure };
}
