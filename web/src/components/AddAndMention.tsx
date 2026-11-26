import { useState } from "react";
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
import type { MentionReplacementRequest } from "../utils/mentionText";
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
  request = null,
  onRequestClose,
}: {
  room: RoomResponse;
  disabled?: boolean;
  onBusyChange?: (busy: boolean) => void;
  onMention: (mention: AddedMention, request: MentionReplacementRequest | null) => void;
  focusAfterAdd?: () => void;
  /** A mention-list action opens the same dialog with its directory term and replacement range. */
  request?: MentionReplacementRequest | null;
  onRequestClose?: () => void;
}) {
  const { t } = useTranslation();
  const admin = useAdmin();
  const knownNames = useKnownNames();
  const queryClient = useQueryClient();
  const [explicitlyOpened, setExplicitlyOpened] = useState(false);
  const [person, setPerson] = useState<PickedOption | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [adding, setAdding] = useState(false);
  const [pickerCycle, setPickerCycle] = useState(0);
  const [seenRequest, setSeenRequest] = useState(request);
  const [focusComposerOnExit, setFocusComposerOnExit] = useState(false);

  // A fresh inline action is a fresh picker, even when it repeats the same term; reset during render so the dialog never flashes an old pick.
  if (request !== seenRequest) {
    setSeenRequest(request);
    if (request !== null) {
      setFocusComposerOnExit(false);
      setPerson(null);
      setError(null);
      setPickerCycle((cycle) => cycle + 1);
    }
  }

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
      onMention({ userId, name, token: `<@${userId}>` }, request);
      setFocusComposerOnExit(true);
      setExplicitlyOpened(false);
      onRequestClose?.();
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

  function close() {
    if (adding) return;
    setExplicitlyOpened(false);
    onRequestClose?.();
  }

  function openExplicitly() {
    setFocusComposerOnExit(false);
    setPerson(null);
    setError(null);
    onRequestClose?.();
    setPickerCycle((cycle) => cycle + 1);
    setExplicitlyOpened(true);
  }

  return (
    <>
      <Button
        type="button"
        size="sm"
        variant="subtle"
        leftSection={<IconUserPlus size={15} aria-hidden="true" />}
        disabled={disabled || adding}
        onClick={openExplicitly}
      >
        {t("threads.composer.addAndMention")}
      </Button>
      <Modal
        opened={explicitlyOpened || request !== null}
        onClose={close}
        // Keep Mantine's focus return off until the next opening after a successful add.
        // Enabling it while closed schedules a second focus move back to the opener.
        returnFocus={!focusComposerOnExit}
        title={t("threads.composer.addAndMentionTitle")}
        closeButtonProps={{ "aria-label": t("common.action.close") }}
        transitionProps={{
          onExited: () => {
            if (focusComposerOnExit) focusAfterAdd?.();
          },
        }}
        centered
      >
        <Stack gap="md">
          <Text size="sm" c="dimmed">{t("threads.composer.addAndMentionDescription")}</Text>
          <UserDirectorySelect key={pickerCycle} initialSearch={request?.term} picked={person}
            onPick={(next) => { setPerson(next); setError(null); }} disabled={adding} />
          {error && <FormErrorAlert>{error}</FormErrorAlert>}
          <Group justify="flex-end">
            <Button variant="default" onClick={close} disabled={adding}>{t("common.action.cancel")}</Button>
            <Button onClick={() => void add()} disabled={person === null} loading={adding}>{t("threads.composer.addAndMention")}</Button>
          </Group>
        </Stack>
      </Modal>
    </>
  );
}
