import { useEffect, useRef, useState, useSyncExternalStore } from "react";
import { Link as RouterLink } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { Alert, Anchor, Button, Group, Stack } from "@mantine/core";
import { useQueryClient } from "@tanstack/react-query";
import { postMessage } from "../api/messages";
import type { RoomResponse } from "../api/rooms";
import type { ThreadResponse } from "../api/threads";
import { useBeforeUnloadGuard } from "../hooks/useBeforeUnloadGuard";
import { useThreadDrafts } from "../hooks/useThreadDrafts";
import { messageComposeBlock } from "../utils/messageAccess";
import { isSendShortcut, messageErrorMessage, refreshAfterMessageWrite } from "../utils/messageForm";
import { refreshQueriesAfterMutation } from "../utils/queryRefresh";
import { isAccessRefusal } from "../utils/roomAccess";
import { roomMembersPath } from "../utils/roomLinks";
import { MAX_MESSAGE_BODY_LENGTH, messageBodyError, normalizeBody } from "../utils/threadForm";
import EmojiTextarea from "./EmojiTextarea";
import FormErrorAlert from "./FormErrorAlert";

// Posts in flight, by room and thread, OUTSIDE any component: a composer that is unmounted and mounted again (another thread and back, a tab and
// back) while its post is still travelling would otherwise start from the kept draft with nothing in flight and post it a second time. The value
// is the text that was sent. `sentOk` remembers, per thread, the text of a post that succeeded since the last one began, so a composer that was
// mounted after the post started can still clear its box when it finishes.
const inFlight = new Map<string, string>();
const sentOk = new Map<string, string>();
const listeners = new Set<() => void>();

function subscribePending(listener: () => void) {
  listeners.add(listener);
  return () => void listeners.delete(listener);
}

function setPending(key: string, text: string | null) {
  if (text === null) inFlight.delete(key);
  else inFlight.set(key, text);
  listeners.forEach((listener) => listener());
}

type ComposerProps = { room: RoomResponse; thread: ThreadResponse; onPosted: (messageId: number) => void };

/**
 * The reply box. The text is kept per thread above the routes (`useThreadDrafts`) as it changes, so leaving the thread for another one, a
 * tab or the list does not lose a half-written reply; coming back restores it, and a post clears it. A reload warns while it holds something
 * (the in-app half of the guard is the kept draft: there is no Cancel here to confirm). Ctrl/Cmd+Enter or Send posts; Enter is a newline.
 * A failure keeps the text; a refusal (403, 404, 409) re-reads the room and the thread, so the notice that explains it replaces the box.
 */
function ComposerForm({ room, thread, onPosted }: ComposerProps) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const drafts = useThreadDrafts();
  const [text, setText] = useState(() => drafts.getReply(room.id, thread.number));
  const key = `${room.id}/${thread.number}`;
  // The text this thread's post in flight carries, if any — from THIS composer or an earlier one for the same thread.
  const pendingText = useSyncExternalStore(subscribePending, () => inFlight.get(key));
  const posting = pendingText !== undefined;
  const [error, setError] = useState<string | null>(null);
  useEffect(() => {
    drafts.setReply(room.id, thread.number, text);
  }, [drafts, room.id, thread.number, text]);
  useBeforeUnloadGuard(() => text.trim() !== "");
  // A post that finished while this box was mounted after it began (the earlier composer is gone): clear the box if it still shows what was sent.
  const wasPending = useRef(pendingText);
  useEffect(() => {
    const sent = wasPending.current;
    if (sent !== undefined && pendingText === undefined && sentOk.get(key) === sent) {
      sentOk.delete(key);
      setText((current) => (current === sent ? "" : current));
    }
    wasPending.current = pendingText;
  }, [pendingText, key]);

  async function send() {
    // Checked on the module-level map, not a state: a held Ctrl+Enter fires again before the first press has rendered, and a remounted box
    // must not post what is already travelling.
    if (inFlight.has(key)) return;
    const invalid = messageBodyError(text, t);
    setError(invalid);
    if (invalid) return;
    sentOk.delete(key);
    setPending(key, text);
    try {
      const created = await postMessage(room.id, thread.number, normalizeBody(text));
      // Cleared in the store too: a post that finishes after the reader navigated away has no mounted box to clear it, and the sent text
      // would otherwise come back as a draft.
      drafts.setReply(room.id, thread.number, "");
      setText("");
      sentOk.set(key, text);
      onPosted(created.id);
      await refreshAfterMessageWrite(queryClient, room.id);
    } catch (err) {
      setError(messageErrorMessage(err, t));
      if (isAccessRefusal(err)) await refreshQueriesAfterMutation(queryClient, ["rooms"]);
    } finally {
      setPending(key, null);
    }
  }

  return (
    <form
      noValidate
      onSubmit={(event) => {
        event.preventDefault();
        void send();
      }}
    >
      <Stack gap="xs">
        <EmojiTextarea
          label={t("threads.composer.label")}
          description={t("threads.composer.hint")}
          autosize
          minRows={2}
          maxRows={10}
          maxLength={MAX_MESSAGE_BODY_LENGTH}
          inputWrapperOrder={["label", "input", "description", "error"]}
          value={text}
          onChange={setText}
          // Read-only, not disabled: a disabled box would drop the caret the reader is writing the next line at.
          readOnly={posting}
          onKeyDown={(event) => {
            if (isSendShortcut(event)) {
              event.preventDefault();
              void send();
            }
          }}
        />
        {error && <FormErrorAlert>{error}</FormErrorAlert>}
        <Group justify="flex-end">
          <Button type="submit" loading={posting}>
            {t("threads.composer.send")}
          </Button>
        </Group>
      </Stack>
    </form>
  );
}

/**
 * The composer under the conversation, or the note that says why there is none: an archived room, a closed thread, or — for an ADMIN who is
 * not a member (speech is for members; the server answers 403) — a link to the Members page to join first (`messageComposeBlock`, UX only).
 */
export default function MessageComposer({ room, thread, onPosted }: ComposerProps) {
  const { t } = useTranslation();
  const block = messageComposeBlock(room, thread);
  // Keyed by the thread, so the box starts from THAT thread's kept reply and never carries the previous one's text along.
  if (block === null) return <ComposerForm key={`${room.id}/${thread.number}`} room={room} thread={thread} onPosted={onPosted} />;
  return (
    <Alert color="gray" variant="light" role="note">
      <Stack gap={4} align="flex-start">
        <span>{t(`threads.composer.${block}`)}</span>
        {block === "notMember" && (
          <Anchor component={RouterLink} to={roomMembersPath(room.id)} size="sm">
            {t("threads.composer.membersLink")}
          </Anchor>
        )}
      </Stack>
    </Alert>
  );
}
