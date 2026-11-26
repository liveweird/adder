import { useState } from "react";
import { useTranslation } from "react-i18next";
import { Button, Group, Stack, Text } from "@mantine/core";
import { useQueryClient } from "@tanstack/react-query";
import { API_BASE } from "../api/http";
import type { RoomResponse } from "../api/rooms";
import { setThreadOwner, type ThreadResponse } from "../api/threads";
import { beginOwnerEcho, canCorrelateReply } from "../utils/liveReplyEcho";
import { activeQueriesAreFresh, refreshQueriesAfterMutation } from "../utils/queryRefresh";
import { isStaleWrite, threadOwnerErrorMessage } from "../utils/threadActions";
import { threadEditBlock } from "../utils/threadAccess";
import { showSuccessToast } from "../utils/toast";
import FormErrorAlert from "./FormErrorAlert";
import PersonLink from "./PersonLink";
import RoomMemberSelect from "./RoomMemberSelect";
import type { PickedOption } from "./SearchPicker";
import { roomKeys } from "../utils/roomKeys";
import { WATCH_SEARCH_KEYS } from "../utils/watch";

/**
 * The thread's owner, and — for a STEWARD only (the thread's owner while a member, a room OWNER, an ADMIN; D1) — the way to pass it on: a
 * picker over the room's active members (`RoomMemberSelect`, the server accepts nobody else) and an explicit "Pass on" button, so choosing a
 * name in the list never hands the thread over by itself. A closed thread and an archived room refuse the hand-over (409), so there the owner
 * is the owner's name, a link to their profile (1.2 D6), under the reason ("reopen it first" only for a steward, who can follow it); anyone else just reads the name and the plain rule. The picker (a steward on a live, open thread) shows the owner in its input, which holds no link: the header's "Owner" line carries it. A 403, 404 or 409 re-reads the room (the standing moved), and so does a
 * 400 (the person left the room meanwhile — the picker's list is stale); a failure shows under the control.
 */
export default function ThreadOwnerSelect({ room, thread, steward }: { room: RoomResponse; thread: ThreadResponse; steward: boolean }) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  // `undefined` = nobody chosen yet: the picker shows the current owner; a person (or `null` after clearing) is the pick.
  const [choice, setChoice] = useState<PickedOption | null | undefined>(undefined);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const block = threadEditBlock(room, thread);
  if (block === "merged") return <PersonLink id={thread.ownerId} name={thread.ownerName} inherit />;

  // The reason, in the words that fit the reader: an archived room refuses everyone; "reopen it first" is advice only a steward can follow, so
  // anyone else just gets the plain rule.
  const note = block === "archived" ? "archivedNote" : !steward ? "stewardNote" : block === "closed" ? "closedNote" : null;
  if (note !== null) {
    return (
      <Stack gap={2}>
        <Text size="sm">
          <PersonLink id={thread.ownerId} name={thread.ownerName} inherit />
        </Text>
        <Text size="xs" c="dimmed">
          {t(`threads.owner.${note}`)}
        </Text>
      </Stack>
    );
  }

  const current: PickedOption = { value: String(thread.ownerId), label: thread.ownerName };
  const picked = choice === undefined ? current : choice;
  const changed = picked !== null && picked.value !== current.value;

  async function passOn() {
    if (!picked || !changed) return;
    setError(null);
    setSaving(true);
    const echo = canCorrelateReply(API_BASE, window.location.href)
      ? beginOwnerEcho(queryClient, room.id, thread.number)
      : undefined;
    try {
      await setThreadOwner(room.id, thread.number, Number(picked.value), echo?.id);
    } catch (err) {
      echo?.cancel();
      setError(threadOwnerErrorMessage(err, t));
      if (isStaleWrite(err)) await refreshQueriesAfterMutation(queryClient, ["rooms"]);
      setSaving(false);
      return;
    }
    showSuccessToast(t("threads.toast.ownerChanged"));
    // The thread (its owner), the list, the history and the rail; the pick goes back to the owner only once they are read, so the picker never
    // flashes the old owner. A hand-over moves no workflow count and changes no room header.
    // A new owner watches the thread (the caller, when they took it): the watched search lists are stale too.
    const refreshedKeys = [roomKeys.threads(room.id), roomKeys.rail, ...WATCH_SEARCH_KEYS] as const;
    try {
      await refreshQueriesAfterMutation(queryClient, ...refreshedKeys);
      if (echo && activeQueriesAreFresh(queryClient, ...refreshedKeys)) echo.confirm();
    } finally {
      echo?.cancel();
    }
    setChoice(undefined);
    setSaving(false);
  }

  return (
    <Stack gap="xs">
      <RoomMemberSelect
        roomId={room.id}
        label={t("threads.owner.label")}
        labelHidden
        description={t("threads.owner.hint")}
        picked={picked}
        onPick={setChoice}
        disabled={saving}
      />
      <Group justify="flex-end">
        <Button size="xs" onClick={() => void passOn()} loading={saving} disabled={!changed}>
          {t("threads.owner.passOn")}
        </Button>
      </Group>
      {error && <FormErrorAlert>{error}</FormErrorAlert>}
    </Stack>
  );
}
