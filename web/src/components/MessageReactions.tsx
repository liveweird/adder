import { useState } from "react";
import { useTranslation } from "react-i18next";
import type { TFunction } from "i18next";
import { Box, Button, Group, Text, Tooltip } from "@mantine/core";
import { useQueryClient } from "@tanstack/react-query";
import { setMessageReaction, type MessageResponse, type ReactionSummary } from "../api/messages";
import { messageErrorMessage } from "../utils/messageForm";
import { PROBLEM_TYPES, isProblem } from "../utils/problems";
import { refreshQueriesAfterMutation } from "../utils/queryRefresh";
import { isAccessRefusal } from "../utils/roomAccess";
import { roomKeys } from "../utils/roomKeys";
import EmojiButton from "./EmojiButton";

/** "Ada, Bob and Cy", or "Ada, Bob, Cy and 2 more" when the server's first three are not everyone. The names are text, never Markdown. */
function reactorNames(reaction: ReactionSummary, t: TFunction, language: string): string {
  const more = reaction.count - reaction.names.length;
  if (more > 0) return t("threads.reaction.names", { count: more, names: reaction.names.join(", ") });
  return new Intl.ListFormat(language, { style: "long", type: "conjunction" }).format(reaction.names);
}

/**
 * The reactions under one message: a chip per emoji (the emoji and its count; pressed when the reader reacted; a tooltip names the first reactors)
 * and, while the reader may react (`actions.react`), the emoji picker that adds one. Pressing a chip sets the opposite state of the reader's own
 * reaction; the server is the only truth, so nothing is changed until the conversation is re-read (no optimistic update). Where the reader may
 * not react (a closed thread, an archived room, a non-member) the chips are plain read-only text and there is no picker. A tombstone has none.
 * The failure goes to `onError` (the message shows it under itself); a refusal re-reads the room, so the controls follow the truth, and the
 * `reaction-limit` 409 (the room is fine) re-reads only the conversation.
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
  // A press is ignored (not disabled: that would drop the focus the pressed chip holds) while one write is in flight.
  const [busy, setBusy] = useState(false);
  const { reactions } = message;
  if (message.deleted || (reactions.length === 0 && !canReact)) return null;

  async function set(emoji: string, reacted: boolean) {
    if (busy) return;
    onError(null);
    setBusy(true);
    try {
      await setMessageReaction(roomId, message.id, emoji, reacted);
      await refreshQueriesAfterMutation(queryClient, roomKeys.messages(roomId, message.threadNumber));
    } catch (err) {
      onError(messageErrorMessage(err, t));
      if (isProblem(err, PROBLEM_TYPES.reactionLimit)) await refreshQueriesAfterMutation(queryClient, roomKeys.messages(roomId, message.threadNumber));
      else if (isAccessRefusal(err)) await refreshQueriesAfterMutation(queryClient, ["rooms"]);
    } finally {
      setBusy(false);
    }
  }

  return (
    <Group gap={4} wrap="wrap" role="group" aria-label={t("threads.reaction.group")} aria-busy={busy || undefined}>
      {reactions.map((reaction) => {
        const label = t(reaction.reacted ? "threads.reaction.chipReacted" : "threads.reaction.chip", { emoji: reaction.emoji, total: reaction.count });
        const body = (
          <>
            <span aria-hidden="true">{reaction.emoji}</span>{" "}
            <Text component="span" size="xs" aria-hidden="true">
              {reaction.count}
            </Text>
          </>
        );
        return (
          <Tooltip key={reaction.emoji} label={reactorNames(reaction, t, i18n.resolvedLanguage ?? "en")} withinPortal>
            {canReact ? (
              <Button
                size="compact-xs"
                variant={reaction.reacted ? "light" : "default"}
                aria-pressed={reaction.reacted}
                aria-label={label}
                onClick={() => void set(reaction.emoji, !reaction.reacted)}
              >
                {body}
              </Button>
            ) : (
              <Box component="span" role="img" aria-label={label} data-reacted={reaction.reacted || undefined} px="xs" fz="sm">
                {body}
              </Box>
            )}
          </Tooltip>
        );
      })}
      {canReact && <EmojiButton label={t("threads.reaction.add")} onSelect={(emoji) => void set(emoji, true)} />}
    </Group>
  );
}
