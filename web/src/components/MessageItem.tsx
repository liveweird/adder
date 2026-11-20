import { useEffect, useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import { Box, Group, Menu, Stack, Text } from "@mantine/core";
import { useQueryClient } from "@tanstack/react-query";
import { IconPencil, IconTrash } from "@tabler/icons-react";
import { deleteMessage, type MessageResponse } from "../api/messages";
import { formatDateTime } from "../utils/formatDate";
import type { MessageActions } from "../utils/messageAccess";
import { messageErrorMessage, refreshAfterMessageWrite } from "../utils/messageForm";
import { refreshQueriesAfterMutation } from "../utils/queryRefresh";
import { isAccessRefusal } from "../utils/roomAccess";
import { tagFilterPath } from "../utils/roomLinks";
import ConfirmActionModal from "./ConfirmActionModal";
import FormErrorAlert from "./FormErrorAlert";
import MarkdownView from "./MarkdownView";
import MessageEditForm from "./MessageEditForm";
import MessagePinMenuItem from "./MessagePinMenuItem";
import RowActionsMenu from "./RowActionsMenu";

/**
 * One message of the conversation: its author (a bold run, never a heading), when it was posted (the browser's zone), an "edited" marker,
 * and its body as `message`-variant Markdown. A tombstone shows who removed it instead of the body. `actions` says what the caller may do
 * (`utils/messageAccess.ts`): Edit and Delete for the author, Remove for a room OWNER or an ADMIN (D2), and Pin or Unpin for any member (`pin`, even in
 * a closed thread: `MessagePinMenuItem`), under a kebab menu; Edit swaps the body
 * for the inline editor, and Delete/Remove asks first. A write re-reads the room's thread queries and the rail, and a refusal (403, 404, 409)
 * re-reads the whole `["rooms"]` prefix, so the controls follow the truth. The failure of an edit or a delete shows under the message: it
 * lives HERE, so it outlives an editor that the refusal takes away (the thread closed), and `editing` resets when the edit action goes.
 * Focus has somewhere to go: the message (`tabIndex={-1}`) takes it back after an edit ends (saved or cancelled) and when the message
 * becomes the tombstone of the caller's own delete or removal. `data-message-id` lets the list find the message it jumps to.
 */
export default function MessageItem({
  roomId,
  message,
  actions,
  zone,
  anchored = false,
}: {
  roomId: number;
  message: MessageResponse;
  actions: MessageActions;
  zone: string;
  /** The message a jump landed on: marked for the list's highlight (`data-anchor`; the list finds it by `data-message-id`). */
  anchored?: boolean;
}) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const [editing, setEditing] = useState(false);
  const [confirming, setConfirming] = useState(false);
  const [removing, setRemoving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const item = useRef<HTMLLIElement>(null);
  const refocusAfterEdit = useRef(false);
  const refocusTombstone = useRef(false);
  const when = formatDateTime(message.createdAt, zone);
  const moderate = actions.remove === "moderate";
  // The edit action went (the thread closed, the room was archived): the editor goes with it and must not come back by itself on a reopen.
  if (editing && !actions.edit) setEditing(false);
  useEffect(() => {
    if (!editing && refocusAfterEdit.current) {
      refocusAfterEdit.current = false;
      item.current?.focus();
    }
  }, [editing]);
  useEffect(() => {
    if (message.deleted && refocusTombstone.current) {
      refocusTombstone.current = false;
      item.current?.focus();
    }
  }, [message.deleted]);

  async function confirmRemove() {
    setError(null);
    setRemoving(true);
    try {
      await deleteMessage(roomId, message.id);
      refocusTombstone.current = true;
      setConfirming(false);
      await refreshAfterMessageWrite(queryClient, roomId);
    } catch (err) {
      // The failure shows under the message, so the dialog goes (its message is a paragraph, which holds no alert).
      setConfirming(false);
      setError(messageErrorMessage(err, t));
      if (isAccessRefusal(err)) await refreshQueriesAfterMutation(queryClient, ["rooms"]);
    } finally {
      setRemoving(false);
    }
  }

  return (
    <Box component="li" ref={item} tabIndex={-1} py="xs" data-message-id={message.id} data-anchor={anchored || undefined}>
      <Stack gap={4}>
        <Group justify="space-between" align="center" wrap="nowrap" gap="xs">
          <Group gap="xs" wrap="wrap" miw={0}>
            <Text fw={700} size="sm" component="span" style={{ overflowWrap: "anywhere" }}>
              {message.authorName}
            </Text>
            <Text size="xs" c="dimmed" component="span">
              {when}
            </Text>
            {message.editedAt !== null && !message.deleted && (
              <Text size="xs" c="dimmed" component="span">
                {t("threads.message.edited", { when: formatDateTime(message.editedAt, zone) })}
              </Text>
            )}
          </Group>
          {(actions.edit || actions.remove !== null || actions.pin) && !editing && (
            <RowActionsMenu label={t("common.table.operationsAria", { name: t("threads.message.name", { author: message.authorName, when }) })} loading={removing}>
              {actions.edit && (
                <Menu.Item
                  leftSection={<IconPencil size={14} />}
                  onClick={() => {
                    setError(null);
                    setEditing(true);
                  }}
                >
                  {t("common.action.edit")}
                </Menu.Item>
              )}
              {actions.pin && <MessagePinMenuItem roomId={roomId} messageId={message.id} onError={setError} />}
              {actions.remove !== null && (
                <Menu.Item
                  color="red"
                  leftSection={<IconTrash size={14} />}
                  onClick={() => {
                    setError(null);
                    setConfirming(true);
                  }}
                >
                  {t(moderate ? "threads.message.remove" : "common.action.delete")}
                </Menu.Item>
              )}
            </RowActionsMenu>
          )}
        </Group>
        {message.deleted ? (
          <Text size="sm" c="dimmed" fs="italic">
            {t(message.deletedByModerator ? "threads.message.deletedModerated" : "threads.message.deletedOwn")}
          </Text>
        ) : editing && actions.edit ? (
          <MessageEditForm
            roomId={roomId}
            message={message}
            onDone={() => {
              refocusAfterEdit.current = true;
              setError(null);
              setEditing(false);
            }}
            onError={setError}
          />
        ) : (
          <MarkdownView variant="message" tagHref={(tag) => tagFilterPath(roomId, message.threadNumber, tag)} mentions={message.mentions}>
            {message.body}
          </MarkdownView>
        )}
        {error && <FormErrorAlert>{error}</FormErrorAlert>}
      </Stack>
      <ConfirmActionModal
        opened={confirming}
        onClose={() => setConfirming(false)}
        title={t(moderate ? "threads.message.removeTitle" : "threads.message.deleteTitle")}
        message={t(moderate ? "threads.message.removeBody" : "threads.message.deleteBody")}
        cancelLabel={t("common.action.cancel")}
        confirmLabel={t(moderate ? "threads.message.remove" : "common.action.delete")}
        onConfirm={() => void confirmRemove()}
        loading={removing}
      />
    </Box>
  );
}
