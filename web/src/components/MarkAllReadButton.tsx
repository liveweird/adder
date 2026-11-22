import { useState } from "react";
import { useTranslation } from "react-i18next";
import { Button } from "@mantine/core";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { IconChecks } from "@tabler/icons-react";
import { markRoomRead, type RoomResponse } from "../api/rooms";
import { notificationKeys } from "../utils/notificationKeys";
import { markRoomReadInCaches } from "../utils/readCache";
import { isAccessRefusal } from "../utils/roomAccess";
import { roomKeys } from "../utils/roomKeys";
import { saveErrorMessage } from "../utils/saveError";
import { showSuccessToast } from "../utils/toast";
import ConfirmActionModal from "./ConfirmActionModal";

/**
 * "Mark all as read" above the thread list (1.2 A10): for a MEMBER of the room (an ADMIN outside it has no read state, the server answers
 * them 403, so they get no button), archived rooms included. It asks first: the write cannot be taken back and it also settles the caller's
 * notices of this room. One request (`POST /rooms/{id}/read-all`, idempotent, nobody else's state moves); a success reads every cached row
 * as read and re-reads the lists, the rail and the bell (`markRoomReadInCaches`); a failure shows inside the dialog, which stays open, and a
 * 403 or 404 also re-reads the room, so the control follows the truth, and any other failure re-reads the lists, the rail and the bell (the
 * server's floor may already be raised; a retry is safe and settles the rest). A second click while one runs is the server's `read-busy` 429.
 */
export default function MarkAllReadButton({ room }: { room: Pick<RoomResponse, "id" | "name" | "role"> }) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const [confirming, setConfirming] = useState(false);
  const mutation = useMutation({
    mutationFn: () => markRoomRead(room.id),
    onSuccess: () => {
      setConfirming(false);
      markRoomReadInCaches(queryClient, room.id);
      showSuccessToast(t("threads.toast.markedRead"));
    },
    onError: (err) => {
      if (isAccessRefusal(err)) {
        void queryClient.invalidateQueries({ queryKey: roomKeys.all });
        return;
      }
      // The floor may already be raised when the settle of the notices fails (and a refused second click leaves the first running): read
      // the lists, the rail and the bell again, so the page shows the server's state. Calling again is safe: it settles the rest.
      void queryClient.invalidateQueries({ queryKey: roomKeys.threadList(room.id) });
      void queryClient.invalidateQueries({ queryKey: roomKeys.rail });
      void queryClient.invalidateQueries({ queryKey: notificationKeys.all });
    },
  });
  if (room.role === null) return null;
  const failure = mutation.isError
    ? saveErrorMessage(mutation.error, t, {
        retryAfter: true,
        forbidden: "threads.markAllRead.forbidden",
        notFound: "threads.markAllRead.notFound",
        failed: "threads.markAllRead.failed",
      })
    : null;

  return (
    <>
      <Button
        size="compact-xs"
        variant="subtle"
        leftSection={<IconChecks size={14} aria-hidden="true" />}
        onClick={() => {
          mutation.reset();
          setConfirming(true);
        }}
        style={{ alignSelf: "flex-start" }}
      >
        {t("threads.markAllRead.action")}
      </Button>
      <ConfirmActionModal
        opened={confirming}
        onClose={() => setConfirming(false)}
        title={t("threads.markAllRead.title")}
        message={t("threads.markAllRead.body", { name: room.name })}
        cancelLabel={t("common.action.cancel")}
        confirmLabel={t("threads.markAllRead.confirm")}
        confirmColor="adder"
        onConfirm={() => mutation.mutate()}
        loading={mutation.isPending}
        error={failure}
      />
    </>
  );
}
