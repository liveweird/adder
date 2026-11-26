import { useEffect, useId, useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import type { TFunction } from "i18next";
import { ActionIcon, Box, Button, Group, Text, Tooltip, VisuallyHidden } from "@mantine/core";
import { useQueryClient } from "@tanstack/react-query";
import { IconUsers } from "@tabler/icons-react";
import { API_BASE } from "../api/http";
import { setMessageReaction, type MessageResponse, type ReactionSummary } from "../api/messages";
import { beginReactionEcho, canCorrelateReply } from "../utils/liveReplyEcho";
import { messageErrorMessage } from "../utils/messageForm";
import { PROBLEM_TYPES, isProblem } from "../utils/problems";
import { activeQueriesAreFresh, refreshQueriesAfterMutation } from "../utils/queryRefresh";
import { isAccessRefusal } from "../utils/roomAccess";
import { roomKeys } from "../utils/roomKeys";
import EmojiButton from "./EmojiButton";
import MessageReactionRoster from "./MessageReactionRoster";

/** "Ada, Bob and Cy", or "Ada, Bob, Cy and 2 more" when the server's first three are not everyone. The names are text, never Markdown. */
function reactorNames(reaction: ReactionSummary, t: TFunction, language: string): string {
  const more = reaction.count - reaction.names.length;
  if (more > 0) return t("threads.reaction.names", { count: more, names: reaction.names.join(", ") });
  return new Intl.ListFormat(language, { style: "long", type: "conjunction" }).format(reaction.names);
}

/**
 * The reactions under one message, a list with one item per emoji: a chip (the emoji and its count, both hidden from assistive technology behind
 * a text label "👍, 3 reactions" and, as the description, the first reactors' names; a tooltip shows the names too) and, while the reader may react
 * (`actions.react`), the emoji picker that adds one. Pressing a chip sets the opposite state of the reader's own reaction (`aria-pressed` says
 * which; the label does not repeat it); the server is the only truth, so nothing is changed until the conversation is re-read (no optimistic
 * update). Where the reader may not react (a closed thread, an archived room, a non-member) the chips are list items of plain text (the reader's own
 * is marked by a shape and said in the text) and there is no picker. A tombstone has none.
 *
 * One write is in flight at a time (a ref: a second press in the same tick must see it, and a re-render would be too late). A chip press during one
 * is ignored; a picker pick is queued (the last one wins) and sent as soon as the write is answered. Focus: the chip the reader pressed can vanish
 * (its count reached 0), then focus moves to the "Add a reaction" button, which a picker pick returns it to as well; the result is told through a
 * polite status that is part of this component. The failure goes to `onError` (the message shows it under itself); a refusal re-reads the room,
 * so the controls follow the truth, and the `reaction-limit` 409 (the room is fine) re-reads only the conversation.
 */
export default function MessageReactions({
  roomId,
  message,
  canReact,
  onError,
}: {
  roomId: number;
  message: MessageResponse;
  canReact: boolean;
  onError: (message: string | null) => void;
}) {
  const { t, i18n } = useTranslation();
  const queryClient = useQueryClient();
  const idBase = useId();
  // A press is ignored (the chip is not disabled: that would drop the focus it holds) while one write is in flight.
  const busy = useRef(false);
  const queuedPick = useRef<string | null>(null);
  const pressedEmoji = useRef<string | null>(null);
  const addRef = useRef<HTMLSpanElement>(null);
  const [announcement, setAnnouncement] = useState("");
  const [rosterReaction, setRosterReaction] = useState<ReactionSummary | null>(null);
  const { reactions } = message;

  // The chip the reader pressed has gone (its count reached 0): its focus went with it, so the add button takes it.
  useEffect(() => {
    const emoji = pressedEmoji.current;
    if (emoji === null || reactions.some((reaction) => reaction.emoji === emoji)) return;
    pressedEmoji.current = null;
    if (document.activeElement === null || document.activeElement === document.body) addRef.current?.querySelector("button")?.focus();
  }, [reactions]);

  if (message.deleted || (reactions.length === 0 && !canReact && rosterReaction === null)) return null;

  const rereadConversation = () => refreshQueriesAfterMutation(queryClient, roomKeys.messages(roomId, message.threadNumber));

  function sendQueuedPick() {
    const next = queuedPick.current;
    queuedPick.current = null;
    if (next !== null) void write(next, true);
  }

  async function write(emoji: string, reacted: boolean) {
    onError(null);
    busy.current = true;
    const messagesKey = roomKeys.messages(roomId, message.threadNumber);
    const echo = canCorrelateReply(API_BASE, window.location.href)
      ? beginReactionEcho(queryClient, roomId, message.threadNumber, message.id)
      : undefined;
    try {
      await setMessageReaction(roomId, message.id, emoji, reacted, echo?.id);
    } catch (err) {
      echo?.cancel();
      busy.current = false;
      onError(messageErrorMessage(err, t));
      sendQueuedPick();
      if (isProblem(err, PROBLEM_TYPES.reactionLimit)) await rereadConversation();
      else if (isAccessRefusal(err)) await refreshQueriesAfterMutation(queryClient, ["rooms"]);
      return;
    }
    // Answered: the next write may start now, not after the re-read (which only shows the truth).
    busy.current = false;
    setAnnouncement(t(reacted ? "threads.reaction.addedAnnounce" : "threads.reaction.removedAnnounce", { emoji }));
    sendQueuedPick();
    try {
      await rereadConversation();
      if (echo && activeQueriesAreFresh(queryClient, messagesKey)) echo.confirm();
    } finally {
      echo?.cancel();
    }
  }

  function pressChip(reaction: ReactionSummary) {
    pressedEmoji.current = reaction.emoji;
    if (!busy.current) void write(reaction.emoji, !reaction.reacted);
  }

  function pick(emoji: string) {
    if (busy.current) queuedPick.current = emoji;
    else void write(emoji, true);
    addRef.current?.querySelector("button")?.focus();
  }

  const language = i18n.resolvedLanguage ?? "en";
  return (
    <Box display="flex" style={{ flexWrap: "wrap", alignItems: "center", gap: 4 }}>
      {/* No list without a reaction, and the picker is no list item: a message's `li` never nests an empty or picker-only list (the conversation is a list of messages). */}
      {reactions.length > 0 && (
      <Box component="ul" role="list" aria-label={t("threads.reaction.group")} display="flex" m={0} p={0} style={{ listStyle: "none", flexWrap: "wrap", gap: 4 }}>
        {reactions.map((reaction, index) => {
          const label = t("threads.reaction.chip", { emoji: reaction.emoji, count: reaction.count });
          const names = reactorNames(reaction, t, language);
          const visible = (
            <>
              <span aria-hidden="true">{reaction.emoji}</span>{" "}
              <Text component="span" size="xs" aria-hidden="true">
                {reaction.count}
              </Text>
            </>
          );
          const nameId = `${idBase}-names-${index}`;
          return (
            <Group component="li" key={reaction.emoji} gap={2} wrap="nowrap">
              {canReact ? (
                <>
                  <Tooltip label={names} withinPortal>
                    <Button
                      size="compact-xs"
                      variant={reaction.reacted ? "light" : "default"}
                      aria-pressed={reaction.reacted}
                      aria-label={label}
                      aria-describedby={nameId}
                      onClick={() => pressChip(reaction)}
                    >
                      {visible}
                    </Button>
                  </Tooltip>
                  <VisuallyHidden id={nameId}>{names}</VisuallyHidden>
                </>
              ) : (
                <>
                  {/* The reader's own reaction is a shape (a tint, a heavier weight and border), never colour alone. */}
                  <Tooltip label={names} withinPortal>
                    <Box
                      component="span"
                      data-reacted={reaction.reacted || undefined}
                      px="xs"
                      fz="sm"
                      fw={reaction.reacted ? 700 : 400}
                      bg={reaction.reacted ? "var(--mantine-primary-color-light)" : undefined}
                      bd={`${reaction.reacted ? 2 : 1}px solid var(--mantine-color-default-border)`}
                      style={{ borderRadius: "var(--mantine-radius-sm)", display: "inline-block" }}
                    >
                      {visible}
                    </Box>
                  </Tooltip>
                  <VisuallyHidden>{[label, reaction.reacted ? t("threads.reaction.youReacted") : "", names].filter(Boolean).join(". ")}</VisuallyHidden>
                </>
              )}
              <ActionIcon
                variant="subtle"
                size="sm"
                aria-label={t("threads.reaction.viewPeople", { emoji: reaction.emoji })}
                aria-haspopup="dialog"
                onClick={() => setRosterReaction(reaction)}
              >
                <IconUsers size={14} aria-hidden="true" />
              </ActionIcon>
            </Group>
          );
        })}
      </Box>
      )}
      {canReact && (
        <Box component="span" ref={addRef}>
          <EmojiButton label={t("threads.reaction.add")} onSelect={pick} />
        </Box>
      )}
      {/* Mounted with its text (never a standing empty region per message: the conversation has a status of its own, and a long thread has hundreds of messages). */}
      {announcement !== "" && <VisuallyHidden role="status">{announcement}</VisuallyHidden>}
      {rosterReaction && (
        <MessageReactionRoster
          key={rosterReaction.key}
          roomId={roomId}
          threadNumber={message.threadNumber}
          messageId={message.id}
          reaction={rosterReaction}
          onClose={() => setRosterReaction(null)}
        />
      )}
    </Box>
  );
}
