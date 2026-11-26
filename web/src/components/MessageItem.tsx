import { useEffect, useRef, useState, type CSSProperties } from "react";
import { useTranslation } from "react-i18next";
import { ActionIcon, Avatar, Box, Divider, Group, Menu, Stack, Text, Tooltip } from "@mantine/core";
import { useQueryClient } from "@tanstack/react-query";
import { IconArrowRight, IconHistory, IconPencil, IconPinFilled, IconQuote, IconTrash } from "@tabler/icons-react";
import { deleteMessage, type MessageResponse } from "../api/messages";
import type { RoomResponse } from "../api/rooms";
import { useThreadRefHref } from "../hooks/useThreadRefHref";
import { formatDateTime } from "../utils/formatDate";
import type { MessageActions } from "../utils/messageAccess";
import { messageErrorMessage } from "../utils/messageForm";
import { refreshQueriesAfterMutation } from "../utils/queryRefresh";
import { isAccessRefusal } from "../utils/roomAccess";
import { beginMessageDeleteCommit, type MessageDeleteCommit } from "../utils/messageDeleteCommit";
import { tagFilterPath } from "../utils/roomLinks";
import { fillSlots, slot } from "../utils/slots";
import { initialsOf } from "../utils/text";
import ConfirmActionModal from "./ConfirmActionModal";
import FormErrorAlert from "./FormErrorAlert";
import MarkdownView from "./MarkdownView";
import MessageEditForm from "./MessageEditForm";
import MessageRevisionDialog from "./MessageRevisionDialog";
import MessagePinMenuItem from "./MessagePinMenuItem";
import MessageQuote from "./MessageQuote";
import MessageReactions from "./MessageReactions";
import PersonLink from "./PersonLink";
import RowActionsMenu from "./RowActionsMenu";
import UnreadCue from "./UnreadCue";
import classes from "./MessageItem.module.css";

async function failedDeleteMessage(
  commit: MessageDeleteCommit,
  queryClient: ReturnType<typeof useQueryClient>,
  error: unknown,
  format: (error: unknown) => string,
): Promise<string | null> {
  if (await commit.reject()) return null;
  if (isAccessRefusal(error)) await refreshQueriesAfterMutation(queryClient, ["rooms"]);
  return format(error);
}

const deleteConfirmationIsVisible = (confirming: boolean, deleted: boolean) => confirming && !deleted;

/**
 * One message of the conversation: its author (a bold run, never a heading), when it was posted (the browser's zone), an "edited" marker,
 * and its body as `message`-variant Markdown. A tombstone shows who removed it instead of the body. `actions` says what the caller may do
 * (`utils/messageAccess.ts`): Edit and Delete for the author, Remove for a room OWNER or an ADMIN (D2), and Pin or Unpin for any member (`pin`, even in
 * a closed thread: `MessagePinMenuItem`), under a kebab menu; `react` shows the emoji picker under the body, where the reactions sit
 * (`MessageReactions`; without it they are read-only, and a tombstone has none); Edit swaps the body
 * for the inline editor, and Delete/Remove asks first. A confirmed deletion refreshes the affected caches, and a refusal (403, 404, 409)
 * re-reads the whole `["rooms"]` prefix, so the controls follow the truth. The failure of an edit or a delete shows under the message: it
 * lives HERE, so it outlives an editor that the refusal takes away (the thread closed), and `editing` resets when the edit action goes.
 * Focus has somewhere to go: the message (`tabIndex={-1}`) takes it back after an edit ends (saved or cancelled) and when the message
 * becomes the tombstone of the caller's own delete or removal. A pinned message (`pinnedBy`, from the room's pins) wears a "Pinned by {name}" line
 * with a filled pin and a gray bar down its edge: words and shapes, never colour alone (1.2 D5). `data-message-id` lets the list find the message it jumps to.
 * The author and the pinner are links to their profiles (1.2 D6): the author's with a QUIET underline (a column of them down the conversation),
 * the pinner's in the dimmed line like any name in a sentence.
 *
 * Quotes (1.3): a message that quotes another shows it above its text (`MessageQuote`: one link that jumps to the quoted message, whose `onShowQuoted`
 * lets the pane run the jump again when the URL already names it) and stays above the editor too. `actions.quote` offers "Quote" twice: the FIRST item of the
 * kebab menu and an always-visible icon button beside it (1.3 D3; named "Quote the message by {author} on {time}" like the kebab, with a "Quote" tooltip); both hand the message to `onQuote`
 * (the pane puts it in the composer), and neither shows without it, nor while the message is being edited. `quote` may be missing from an older
 * server's answer: it reads as no quote.
 */
export default function MessageItem({
  roomId,
  mentionRoom,
  message,
  actions,
  zone,
  anchored = false,
  firstNew = false,
  pinnedBy,
  onQuote,
  onQuoteElsewhere,
  onShowQuoted,
}: {
  roomId: number;
  mentionRoom?: RoomResponse;
  message: MessageResponse;
  actions: MessageActions;
  zone: string;
  /** The message a jump landed on: marked for the list's highlight (`data-anchor`; the list finds it by `data-message-id`). */
  anchored?: boolean;
  /** The first message that was unread when the thread opened: a "New" separator stands above it (inside the item, so the list stays a list of items). */
  firstNew?: boolean;
  /** Who pinned this message, when the room's pins list it (1.2 D5): a "Pinned by" line with the pin icon and a gray bar (`data-pinned`) mark it. */
  pinnedBy?: { id: number; name: string };
  /** The reader chose "Quote" on this message (1.3): the pane puts it in the reply box. Without it the message offers no quote. */
  onQuote?: (message: MessageResponse) => void;
  /** Opens a destination picker; unlike the local action this remains useful while the source thread is closed or merged. */
  onQuoteElsewhere?: (message: MessageResponse) => void;
  /** The reader followed this message's quote block (1.3): the quoted message's id, for the pane's repeat jump. */
  onShowQuoted?: (messageId: number) => void;
}) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const threadHref = useThreadRefHref(roomId);
  const [editing, setEditing] = useState(false);
  const [confirming, setConfirming] = useState(false);
  const [historyOpened, setHistoryOpened] = useState(false);
  const [removing, setRemoving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const item = useRef<HTMLLIElement>(null);
  const refocusAfterEdit = useRef(false);
  const refocusTombstone = useRef(false);
  const when = formatDateTime(message.createdAt, zone);
  const moderate = actions.remove === "moderate";
  const confirmVisible = deleteConfirmationIsVisible(confirming, message.deleted);
  // A tombstone never wears the mark.
  const pinner = message.deleted ? undefined : pinnedBy;
  const pinned = pinner !== undefined;
  const quoting = actions.quote === true && onQuote !== undefined ? () => onQuote(message) : undefined;
  const quotingElsewhere = !message.deleted && onQuoteElsewhere !== undefined ? () => onQuoteElsewhere(message) : undefined;
  const canViewHistory = actions.history === true && message.editedAt !== null && !message.deleted;
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
  if (historyOpened && !canViewHistory) setHistoryOpened(false);

  async function confirmRemove() {
    setError(null);
    setRemoving(true);
    refocusTombstone.current = true;
    const commit = beginMessageDeleteCommit(queryClient, {
      roomId,
      threadNumber: message.threadNumber,
      messageId: message.id,
    });
    try {
      await deleteMessage(roomId, message.id);
      await commit.confirm();
      setConfirming(false);
    } catch (err) {
      // The failure shows under the message, so the dialog goes (its message is a paragraph, which holds no alert).
      setConfirming(false);
      setError(await failedDeleteMessage(commit, queryClient, err, (failure) => messageErrorMessage(failure, t)));
    } finally {
      setRemoving(false);
    }
  }

  return (
    <Box component="li" ref={item} tabIndex={-1} className={classes.item} data-message-id={message.id} data-anchor={anchored || undefined} data-pinned={pinned || undefined}>
      {firstNew && (
        <Divider
          mb="xs"
          labelPosition="left"
          aria-label={t("threads.unread.separatorAria")}
          // The rule and the word are the unread blue (UnreadCue.tsx); the separator's aria-label carries the words.
          style={{ "--divider-color": "var(--adder-unread-fill)" } as CSSProperties}
          label={<UnreadCue>{t("threads.unread.separator")}</UnreadCue>}
        />
      )}
      <div className={classes.layout}>
        <Avatar size={32} radius="xl" color="gray" variant="light" aria-hidden className={classes.avatar}>
          {initialsOf(message.authorName)}
        </Avatar>
        <Stack gap={3} className={classes.content}>
          {pinner && (
            <Group gap={4} wrap="nowrap" align="center" className={classes.pinLine}>
              <IconPinFilled size={12} aria-hidden="true" style={{ flex: "none" }} />
              <Text size="xs" fw={600} c="dimmed">
                {fillSlots(t("threads.pin.by", { name: slot("name") }), { name: <PersonLink id={pinner.id} name={pinner.name} inherit /> })}
              </Text>
            </Group>
          )}
          <Group justify="space-between" align="flex-start" wrap="nowrap" gap="xs" className={classes.header}>
            <Group gap={6} wrap="wrap" miw={0} className={classes.meta}>
              <PersonLink id={message.authorId} name={message.authorName} quiet fw={700} size="sm" style={{ overflowWrap: "anywhere" }} />
              <Text size="xs" c="dimmed" component="span">
                {when}
              </Text>
              {message.editedAt !== null && !message.deleted && (
                <Text size="xs" c="dimmed" component="span">
                  {t("threads.message.edited", { when: formatDateTime(message.editedAt, zone) })}
                </Text>
              )}
            </Group>
            {(quoting || quotingElsewhere || actions.edit || actions.remove !== null || actions.pin || canViewHistory) && !editing && (
              <Group gap={2} wrap="nowrap" className={classes.actions}>
                {quoting && (
                  <Tooltip label={t("threads.quote.action")}>
                    <ActionIcon size="sm" aria-label={t("threads.quote.actionAria", { author: message.authorName, when })} onClick={quoting}>
                      <IconQuote size={16} />
                    </ActionIcon>
                  </Tooltip>
                )}
                <RowActionsMenu label={t("common.table.operationsAria", { name: t("threads.message.name", { author: message.authorName, when }) })} loading={removing}>
                  {quoting && (
                    <Menu.Item leftSection={<IconQuote size={14} />} onClick={quoting}>
                      {t("threads.quote.action")}
                    </Menu.Item>
                  )}
                  {quotingElsewhere && (
                    <Menu.Item leftSection={<IconArrowRight size={14} />} onClick={quotingElsewhere}>
                      {t("threads.quote.elsewhere")}
                    </Menu.Item>
                  )}
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
                  {canViewHistory && (
                    <Menu.Item leftSection={<IconHistory size={14} />} onClick={() => setHistoryOpened(true)}>
                      {t("threads.message.history.action")}
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
              </Group>
            )}
          </Group>
          {message.quote && !message.deleted && (
            <MessageQuote roomId={roomId} threadNumber={message.threadNumber} sourceMessageId={message.id} quote={message.quote} onShow={onShowQuoted} />
          )}
          <Box className={classes.body}>
            {message.deleted ? (
              <Text size="sm" c="dimmed" fs="italic">
                {t(message.deletedByModerator ? "threads.message.deletedModerated" : "threads.message.deletedOwn")}
              </Text>
            ) : editing && actions.edit ? (
              <MessageEditForm
                roomId={roomId}
                room={mentionRoom}
                message={message}
                onDone={() => {
                  refocusAfterEdit.current = true;
                  setError(null);
                  setEditing(false);
                }}
                onError={setError}
              />
            ) : (
              <MarkdownView
                variant="message"
                tagHref={(tag) => tagFilterPath(roomId, message.threadNumber, tag)}
                threadHref={threadHref}
                mentions={message.mentions}
                groupMentions={message.groupMentions}
                linkSource={{ roomId, messageId: message.id, editedAt: message.editedAt }}
              >
                {message.body}
              </MarkdownView>
            )}
          </Box>
          {!editing && <MessageReactions roomId={roomId} message={message} canReact={actions.react === true} onError={setError} />}
          {!message.deleted && error && <FormErrorAlert>{error}</FormErrorAlert>}
        </Stack>
      </div>
      <ConfirmActionModal
        opened={confirmVisible}
        onClose={() => setConfirming(false)}
        title={t(moderate ? "threads.message.removeTitle" : "threads.message.deleteTitle")}
        message={t(moderate ? "threads.message.removeBody" : "threads.message.deleteBody")}
        cancelLabel={t("common.action.cancel")}
        confirmLabel={t(moderate ? "threads.message.remove" : "common.action.delete")}
        onConfirm={() => void confirmRemove()}
        loading={removing}
      />
      {canViewHistory && (
        <MessageRevisionDialog
          roomId={roomId}
          threadNumber={message.threadNumber}
          messageId={message.id}
          opened={historyOpened}
          onClose={() => setHistoryOpened(false)}
        />
      )}
    </Box>
  );
}
