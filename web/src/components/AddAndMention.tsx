import { useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import { Button, Group, Modal, Stack, Text } from "@mantine/core";
import { useQueryClient } from "@tanstack/react-query";
import { IconUserPlus } from "@tabler/icons-react";
import { useAdmin } from "../auth";
import { addRoomMembers, type RoomResponse } from "../api/rooms";
import { getSessionSnapshot, isSameSessionIdentity } from "../api/session";
import { useKnownNames } from "../utils/knownNames";
import { refreshQueriesAfterMutation } from "../utils/queryRefresh";
import { canManageRoom, isAccessRefusal } from "../utils/roomAccess";
import { saveErrorMessage } from "../utils/saveError";
import FormErrorAlert from "./FormErrorAlert";
import type { PickedOption } from "./SearchPicker";
import UserDirectorySelect from "./UserDirectorySelect";

export type AddedMention = { userId: number; name: string; token: string };

/** Directory-backed room admission followed by a mention insertion, shared by every message composer. */
export default function AddAndMention({
  room,
  disabled = false,
  onBusyChange,
  onMention,
  focusAfterAdd,
}: {
  room: RoomResponse;
  disabled?: boolean;
  onBusyChange?: (busy: boolean) => void;
  onMention: (mention: AddedMention) => void;
  focusAfterAdd?: () => void;
}) {
  const { t } = useTranslation();
  const admin = useAdmin();
  const knownNames = useKnownNames();
  const queryClient = useQueryClient();
  const [opened, setOpened] = useState(false);
  const [person, setPerson] = useState<PickedOption | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [adding, setAdding] = useState(false);
  const focusOnExit = useRef(false);

  if (!canManageRoom(room, admin)) return null;

  async function add() {
    if (person === null) return;
    const userId = Number(person.value);
    // UserDirectorySelect's option contract is always `Name (email)`; the final delimiter keeps parentheses inside a name intact.
    const name = person.label.slice(0, person.label.lastIndexOf(" ("));
    const session = getSessionSnapshot();
    setAdding(true);
    onBusyChange?.(true);
    setError(null);
    try {
      await addRoomMembers(room.id, { userIds: [userId] });
      // A sign-out or replacement login while the request travelled must not modify the next person's draft or known-name store.
      if (!isSameSessionIdentity(session, getSessionSnapshot())) return;
      knownNames.remember([{ userId, name }]);
      onMention({ userId, name, token: `<@${userId}>` });
      focusOnExit.current = true;
      setOpened(false);
      setPerson(null);
      await refreshQueriesAfterMutation(queryClient, ["rooms"]);
    } catch (failure) {
      setError(saveErrorMessage(failure, t, {
        forbidden: "rooms.members.addForbidden",
        notFound: "rooms.members.addGone",
        conflict: "rooms.members.addFull",
        tooManyRequests: "common.error.tooManyRequests",
        invalid: "rooms.members.addInvalid",
        failedStatus: "common.error.actionFailedStatus",
        failed: "common.error.actionFailed",
      }));
      if (isAccessRefusal(failure)) await refreshQueriesAfterMutation(queryClient, ["rooms"]);
    } finally {
      setAdding(false);
      onBusyChange?.(false);
    }
  }

  return (
    <>
      <Button
        type="button"
        size="sm"
        variant="subtle"
        leftSection={<IconUserPlus size={15} aria-hidden="true" />}
        disabled={disabled || adding}
        onClick={() => { focusOnExit.current = false; setOpened(true); }}
      >
        {t("threads.composer.addAndMention")}
      </Button>
      <Modal
        opened={opened}
        onClose={() => !adding && setOpened(false)}
        title={t("threads.composer.addAndMentionTitle")}
        closeButtonProps={{ "aria-label": t("common.action.close") }}
        transitionProps={{
          onExited: () => {
            if (!focusOnExit.current) return;
            focusOnExit.current = false;
            focusAfterAdd?.();
          },
        }}
        centered
      >
        <Stack gap="md">
          <Text size="sm" c="dimmed">{t("threads.composer.addAndMentionDescription")}</Text>
          <UserDirectorySelect picked={person} onPick={(next) => { setPerson(next); setError(null); }} disabled={adding} />
          {error && <FormErrorAlert>{error}</FormErrorAlert>}
          <Group justify="flex-end">
            <Button variant="default" onClick={() => setOpened(false)} disabled={adding}>{t("common.action.cancel")}</Button>
            <Button onClick={() => void add()} disabled={person === null} loading={adding}>{t("threads.composer.addAndMention")}</Button>
          </Group>
        </Stack>
      </Modal>
    </>
  );
}
