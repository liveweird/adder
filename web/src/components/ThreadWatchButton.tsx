import { useState } from "react";
import { useTranslation } from "react-i18next";
import { Group, Text, VisuallyHidden } from "@mantine/core";
import { useQueryClient } from "@tanstack/react-query";
import { IconEye, IconEyeFilled } from "@tabler/icons-react";
import type { RoomResponse } from "../api/rooms";
import { setThreadWatch, type ThreadResponse } from "../api/threads";
import { canWatch, refreshAfterWatchFailure, refreshAfterWatchWrite, watchErrorMessage } from "../utils/watch";
import HeaderIconButton from "./HeaderIconButton";

/**
 * "Watch this thread" in the thread pane's header, beside the pin button: an icon button (`HeaderIconButton`), a toggle (`aria-pressed`, one constant label) for a member of
 * the room, with the number of watchers beside it. The state is the thread's own `watching` and `watcherCount` (the caller's flag and the
 * count of active watchers); the write is the caller's own state, so a closed thread and an archived room keep the control and an ADMIN who
 * is not a member gets none. The creator and each new owner already watch (1.1 D1) and may stop. A failure is handed to the pane (`onError`), which shows it in a full-width row of the header; a refusal
 * re-reads the room, so the control follows the truth.
 */
export default function ThreadWatchButton({ room, thread, onError }: { room: RoomResponse; thread: Pick<ThreadResponse, "number" | "watching" | "watcherCount">; onError: (message: string | null) => void }) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const [busy, setBusy] = useState(false);
  if (!canWatch(room)) return null;
  const { watching } = thread;
  const countLabel = t("threads.watch.count", { count: thread.watcherCount });

  async function toggle() {
    onError(null);
    setBusy(true);
    try {
      await setThreadWatch(room.id, thread.number, !watching);
      await refreshAfterWatchWrite(queryClient, room.id, thread.number);
    } catch (err) {
      onError(watchErrorMessage(err, t));
      await refreshAfterWatchFailure(queryClient, err);
    } finally {
      setBusy(false);
    }
  }

  return (
    <Group gap={4} wrap="nowrap">
      <HeaderIconButton
        label={t("threads.watch.thread")}
        pressed={watching}
        loading={busy}
        icon={watching ? <IconEyeFilled size={16} /> : <IconEye size={16} />}
        onClick={() => void toggle()}
      />
      <Text size="xs" c="dimmed" title={countLabel}>
        <VisuallyHidden>{countLabel}</VisuallyHidden>
        <span aria-hidden="true">{thread.watcherCount}</span>
      </Text>
    </Group>
  );
}
