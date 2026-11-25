import { useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import { Button, Group, Stack } from "@mantine/core";
import { useQueryClient } from "@tanstack/react-query";
import { editMessage, type MessageResponse } from "../api/messages";
import type { RoomResponse } from "../api/rooms";
import { useKnownNames } from "../utils/knownNames";
import { useBeforeUnloadGuard } from "../hooks/useBeforeUnloadGuard";
import { isSendShortcut, messageErrorMessage, refreshAfterMessageWrite } from "../utils/messageForm";
import { appendMentionToken } from "../utils/mentionText";
import { refreshQueriesAfterMutation } from "../utils/queryRefresh";
import { isAccessRefusal } from "../utils/roomAccess";
import { MAX_MESSAGE_BODY_LENGTH, messageBodyError, normalizeBody } from "../utils/threadForm";
import ConfirmActionModal from "./ConfirmActionModal";
import AddAndMention from "./AddAndMention";
import MentionTextarea from "./MentionTextarea";

/**
 * A message's inline editor (its author's, in the message's own place). It saves with Save or Ctrl/Cmd+Enter and ends through `onDone`
 * once the conversation has been re-read, so the new body shows the moment the editor goes. A body that did not change is not sent (the
 * server would answer 204 and do nothing); Cancel on a changed body asks first, and a reload warns. The box is read-only, not disabled,
 * while a save is in flight (a disabled box would drop the caret), and takes focus back when the save fails.
 *
 * Failures (a body the server's rules refuse, or a refused write) go to the owner through `onError`, which shows them under the message:
 * a refusal (403, 404, 409) re-reads the whole room and its threads, which can take the editor away (a thread that closed), and the alert
 * must outlive it. `onError(null)` clears a shown one.
 */
export default function MessageEditForm({
  roomId,
  room,
  message,
  onDone,
  onError,
}: {
  roomId: number;
  room?: RoomResponse;
  message: MessageResponse;
  onDone: () => void;
  onError: (message: string | null) => void;
}) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const original = message.body ?? "";
  const known = useKnownNames();
  // The body is edited as stored (tokens and all); the box shows the names of the people and the handles of the teams it mentions, so they must be known BEFORE the first
  // render (the store is not reactive). Remembering is idempotent, which is why a render may do it.
  const [text, setText] = useState(() => {
    known.remember(message.mentions);
    known.rememberTeams(message.groupMentions);
    return original;
  });
  const [saving, setSaving] = useState(false);
  const [addingMember, setAddingMember] = useState(false);
  const [confirmCancel, setConfirmCancel] = useState(false);
  const box = useRef<HTMLDivElement>(null);
  // The in-flight guard is a ref: a held Ctrl+Enter fires again before the state of the first press has rendered (the composer's shape).
  const inFlight = useRef(false);
  const dirty = text !== original;
  useBeforeUnloadGuard(dirty);

  async function save() {
    if (inFlight.current || addingMember) return;
    const invalid = messageBodyError(text, t);
    onError(invalid);
    if (invalid) return;
    if (normalizeBody(text) === original) {
      onDone();
      return;
    }
    inFlight.current = true;
    setSaving(true);
    try {
      await editMessage(roomId, message.id, normalizeBody(text));
      await refreshAfterMessageWrite(queryClient, roomId, { pins: true });
      onDone();
    } catch (err) {
      onError(messageErrorMessage(err, t));
      inFlight.current = false;
      setSaving(false);
      // The caret goes back to the text (the click on Save took the focus, and a loading button holds none).
      box.current?.querySelector("textarea")?.focus();
      if (isAccessRefusal(err)) await refreshQueriesAfterMutation(queryClient, ["rooms"]);
    }
  }

  return (
    <Stack gap="xs">
      <div ref={box}>
        <MentionTextarea
          roomId={roomId}
          aria-label={t("threads.message.editLabel")}
          autosize
          minRows={2}
          maxRows={12}
          maxLength={MAX_MESSAGE_BODY_LENGTH}
          value={text}
          onChange={setText}
          readOnly={saving || addingMember}
          inputWrapperOrder={["label", "input", "description", "error"]}
          onKeyDown={(event) => {
            if (isSendShortcut(event)) {
              event.preventDefault();
              void save();
            }
          }}
        />
      </div>
      <Group justify="flex-end" gap="xs">
        {room && <AddAndMention room={room} disabled={saving} onMention={({ token }) => setText((current) => appendMentionToken(current, token))}
          onBusyChange={setAddingMember} focusAfterAdd={() => box.current?.querySelector("textarea")?.focus()} />}
        <Button variant="default" size="compact-sm" onClick={() => (dirty ? setConfirmCancel(true) : onDone())} disabled={saving || addingMember}>
          {t("common.action.cancel")}
        </Button>
        <Button size="compact-sm" onClick={() => void save()} loading={saving} disabled={!dirty || addingMember}>
          {t("common.action.save")}
        </Button>
      </Group>
      <ConfirmActionModal
        opened={confirmCancel}
        onClose={() => setConfirmCancel(false)}
        title={t("common.discard.title")}
        message={t("common.discard.message")}
        cancelLabel={t("common.action.keepEditing")}
        confirmLabel={t("common.action.discard")}
        onConfirm={onDone}
      />
    </Stack>
  );
}
