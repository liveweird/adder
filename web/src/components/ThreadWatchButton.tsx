import { useState } from "react";
import { useTranslation } from "react-i18next";
import { Button, Text } from "@mantine/core";
import { useQueryClient } from "@tanstack/react-query";
import { IconEye, IconEyeFilled } from "@tabler/icons-react";
import type { RoomResponse } from "../api/rooms";
import { setThreadWatch, type ThreadResponse } from "../api/threads";
import { canWatch, refreshAfterWatchFailure, refreshAfterWatchWrite, watchErrorMessage } from "../utils/watch";
import FormErrorAlert from "./FormErrorAlert";

/**
 * "Watch this thread" in the thread pane's button row, beside the pin button: a toggle (`aria-pressed`, one constant label) for a member of
 * the room, with the number of watchers beside it. The state is the thread's own `watching` and `watcherCount` (the caller's flag and the
 * count of active watchers); the write is the caller's own state, so a closed thread and an archived room keep the control and an ADMIN who
 * is not a member gets none. The creator and each new owner already watch (1.1 D1) and may stop. A failure shows beside the button; a refusal
 * re-reads the room, so the control follows the truth.
 */
export default function ThreadWatchButton({ room, thread }: { room: RoomResponse; thread: Pick<ThreadResponse, "number" | "watching" | "watcherCount"> }) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  if (!canWatch(room)) return null;
  const { watching } = thread;

  async function toggle() {
    setError(null);
    setBusy(true);
    try {
      await setThreadWatch(room.id, thread.number, !watching);
      await refreshAfterWatchWrite(queryClient, room.id, thread.number);
    } catch (err) {
      setError(watchErrorMessage(err, t));
      await refreshAfterWatchFailure(queryClient, err);
    } finally {
      setBusy(false);
    }
  }

  return (
    <>
      <Button
        variant={watching ? "light" : "default"}
        size="xs"
        aria-pressed={watching}
        loading={busy}
        leftSection={watching ? <IconEyeFilled size={14} /> : <IconEye size={14} />}
        onClick={() => void toggle()}
      >
        {t("threads.watch.thread")}
      </Button>
      <Text size="xs" c="dimmed">
        {t("threads.watch.count", { count: thread.watcherCount })}
      </Text>
      {error && <FormErrorAlert w="100%">{error}</FormErrorAlert>}
    </>
  );
}
