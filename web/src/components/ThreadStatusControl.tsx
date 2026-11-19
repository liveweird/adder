import { useState } from "react";
import { useTranslation } from "react-i18next";
import { Select, Stack, Text } from "@mantine/core";
import { useQueryClient } from "@tanstack/react-query";
import type { RoomResponse, RoomWorkflow } from "../api/rooms";
import { setThreadStatus, type ThreadResponse } from "../api/threads";
import { refreshQueriesAfterMutation } from "../utils/queryRefresh";
import { isStaleWrite, threadStatusErrorMessage } from "../utils/threadActions";
import { statusMoveBlock } from "../utils/threadAccess";
import { showSuccessToast } from "../utils/toast";
import ConfirmActionModal from "./ConfirmActionModal";
import FormErrorAlert from "./FormErrorAlert";
import { roomKeys } from "../utils/roomKeys";

type WorkflowStatus = RoomWorkflow["statuses"][number];
/** What the control needs of a status (the current one may be a bare key the workflow no longer lists). */
type Choice = Pick<WorkflowStatus, "key" | "label" | "category">;

/**
 * The thread's status as a Select grouped Open and Closed (the room's workflow, in its own order). D1: a move between two OPEN statuses is
 * open to every reader; closing, reopening and a move between CLOSED statuses are for a steward (`isThreadSteward`), so for anyone else those
 * options are disabled WITH their hint ("Needs the thread owner, a room owner or an administrator") rather than hidden. An archived room
 * disables them all. Closing an open thread asks first (it stops everyone until a steward reopens it). The status PUT is not revisioned; a
 * 400 (the status was retired meanwhile), 403, 404 or 409 re-reads the room, so the options follow the truth. The select shows the
 * server's status and follows a refetch; a failure shows under it.
 */
export default function ThreadStatusControl({
  room,
  thread,
  statuses,
  steward,
}: {
  room: RoomResponse;
  thread: ThreadResponse;
  statuses: readonly WorkflowStatus[];
  steward: boolean;
}) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [closing, setClosing] = useState<Choice | null>(null);

  // A key the workflow no longer lists (a read older than the thread's last move) still shows as the current choice, under its own category.
  const known = statuses.some((status) => status.key === thread.status);
  const all: readonly Choice[] = known
    ? statuses
    : [...statuses, { key: thread.status, label: thread.status, category: thread.closed ? "CLOSED" : "OPEN" }];
  const hints: Record<string, string> = {};
  const item = (status: Choice) => {
    const block = statusMoveBlock(room, thread, steward, status);
    if (block !== null) hints[status.key] = t(block === "archived" ? "threads.status.hintArchived" : "threads.status.hintSteward");
    return { value: status.key, label: status.label, disabled: block !== null };
  };
  const data = [
    { group: t("threads.status.open"), items: all.filter((status) => status.category === "OPEN").map(item) },
    { group: t("threads.status.closed"), items: all.filter((status) => status.category === "CLOSED").map(item) },
  ].filter((group) => group.items.length > 0);

  async function move(key: string) {
    setError(null);
    setSaving(true);
    try {
      await setThreadStatus(room.id, thread.number, key);
    } catch (err) {
      setError(threadStatusErrorMessage(err, t));
      // The standing or the workflow moved under the page: show the truth (the room, the thread, the statuses and their counts).
      if (isStaleWrite(err)) await refreshQueriesAfterMutation(queryClient, ["rooms"]);
      setSaving(false);
      setClosing(null);
      return;
    }
    showSuccessToast(t("threads.toast.statusChanged"));
    // The thread, the list, the history and the rail follow, and so do the workflow's `threadCount`s; a move changes no room header.
    await refreshQueriesAfterMutation(queryClient, roomKeys.threads(room.id), roomKeys.rail, roomKeys.workflow(room.id));
    setSaving(false);
    setClosing(null);
  }

  function choose(key: string | null) {
    const target = all.find((status) => status.key === key);
    if (!target || target.key === thread.status) return;
    // Closing an OPEN thread stops everyone: ask first. A CLOSED to CLOSED move and a reopen go straight through.
    if (!thread.closed && target.category === "CLOSED") setClosing(target);
    else void move(target.key);
  }

  return (
    <Stack gap="xs">
      <Select
        aria-label={t("threads.status.label")}
        data={data}
        value={thread.status}
        onChange={choose}
        allowDeselect={false}
        // Read-only while a move is in flight (disabling would drop the focus the person is working at).
        readOnly={saving}
        disabled={room.archived}
        renderOption={({ option }) => (
          <Stack gap={0}>
            <Text size="sm">{option.label}</Text>
            {hints[option.value] && (
              <Text size="xs" c="dimmed">
                {hints[option.value]}
              </Text>
            )}
          </Stack>
        )}
      />
      {room.archived ? (
        <Text size="xs" c="dimmed">
          {t("threads.status.archivedNote")}
        </Text>
      ) : (
        !steward && (
          <Text size="xs" c="dimmed">
            {t("threads.status.stewardNote")}
          </Text>
        )
      )}
      {error && <FormErrorAlert>{error}</FormErrorAlert>}
      <ConfirmActionModal
        opened={closing !== null}
        onClose={() => setClosing(null)}
        title={t("threads.status.closeTitle")}
        message={t("threads.status.closeMessage", { number: thread.number, status: closing?.label ?? "" })}
        cancelLabel={t("common.action.cancel")}
        confirmLabel={t("threads.status.closeConfirm")}
        confirmColor="adder"
        loading={saving}
        onConfirm={() => closing && void move(closing.key)}
      />
    </Stack>
  );
}
