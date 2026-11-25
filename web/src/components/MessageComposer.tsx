import { useCallback, useEffect, useId, useRef, useState, useSyncExternalStore } from "react";
import { Link as RouterLink } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { Alert, Anchor, Button, Group, Stack, Text, VisuallyHidden } from "@mantine/core";
import { useQueryClient } from "@tanstack/react-query";
import { IconSend } from "@tabler/icons-react";
import { postMessage } from "../api/messages";
import type { RoomResponse } from "../api/rooms";
import type { ThreadResponse } from "../api/threads";
import { useBeforeUnloadGuard } from "../hooks/useBeforeUnloadGuard";
import { useLoadedMessage } from "../hooks/useLoadedMessage";
import { useThreadDrafts, type QuoteDraft } from "../hooks/useThreadDrafts";
import { messageComposeBlock } from "../utils/messageAccess";
import { isSendShortcut, messageErrorMessage, refreshAfterMessageWrite } from "../utils/messageForm";
import { refreshQueriesAfterMutation } from "../utils/queryRefresh";
import { PROBLEM_TYPES, isProblem } from "../utils/problems";
import { blankQuote, quoteDraftExcerpt, reconcileQuote } from "../utils/quoteDraft";
import { isAccessRefusal } from "../utils/roomAccess";
import { roomMembersPath } from "../utils/roomLinks";
import { MAX_MESSAGE_BODY_LENGTH, messageBodyError, normalizeBody } from "../utils/threadForm";
import ComposerQuote from "./ComposerQuote";
import FormErrorAlert from "./FormErrorAlert";
import MentionTextarea from "./MentionTextarea";
import classes from "./MessageComposer.module.css";

// Posts in flight, by room and thread, OUTSIDE any component: a composer that is unmounted and mounted again (another thread and back, a tab and
// back) while its post is still travelling would otherwise start from the kept draft with nothing in flight and post it a second time. The value
// is what was sent: the text and the quote (the pick itself, so a message picked AGAIN while the post travels is a different pick), if any. `sentOk` remembers, per thread, what a post that succeeded since the last
// one began carried, so a composer that was mounted after the post started can still clear its box (and its quote) when it finishes.
type Sent = { text: string; quote: QuoteDraft | null };
const inFlight = new Map<string, Sent>();
const sentOk = new Map<string, Sent>();
const listeners = new Set<() => void>();

function subscribePending(listener: () => void) {
  listeners.add(listener);
  return () => void listeners.delete(listener);
}

function setPending(key: string, sent: Sent | null) {
  if (sent === null) inFlight.delete(key);
  else inFlight.set(key, sent);
  listeners.forEach((listener) => listener());
}

/**
 * A "Quote" the pane hands to the reply box (1.3): the message picked, the thread it was picked in and a number that grows with every pick (a
 * second pick of the same message is a new request). A composer applies a request it has not seen yet and takes the focus; one that mounts with a
 * request already made (another thread and back) takes the quote from the kept draft instead.
 */
export type QuoteRequest = { threadNumber: number; quote: QuoteDraft; seq: number };

type ComposerProps = { room: RoomResponse; thread: ThreadResponse; onPosted: (messageId: number) => void; quoteRequest?: QuoteRequest | null };

/**
 * After "Quote" the menu closes and gives the focus back to its button, but only if the focus is still where it was when it closed (Mantine's
 * `useFocusReturn` waits 10 ms): moving the focus later than that leaves it where the reader is writing.
 */
const QUOTE_FOCUS_DELAY_MS = 40;

/**
 * The reply box. The text is kept per thread above the routes (`useThreadDrafts`) as it changes, so leaving the thread for another one, a
 * tab or the list does not lose a half-written reply; coming back restores it, and a post clears it. A reload warns while it holds something
 * (the in-app half of the guard is the kept draft: there is no Cancel here to confirm). Ctrl/Cmd+Enter or Send posts; Enter is a newline (it picks, while the list of people to mention is open: `MentionTextarea`). The kept
 * draft is the canonical text, tokens and all; the box shows the names.
 * A failure keeps the text; a refusal (403, 404, 409) re-reads the room and the thread, so the notice that explains it replaces the box.
 *
 * The quote (1.3): "Quote" on a message arrives as a `quoteRequest` from the pane, and the box takes the focus. The picked message is kept per thread
 * beside the text (`getQuote`/`setQuote`), shown as a line above the box (`ComposerQuote`: "Quoting {author}", an excerpt and "Remove the quote",
 * after which the focus is in the box) and told to assistive technology by a polite status line that is mounted from the first render and holds the
 * words and the excerpt (so every new pick changes it; the visible line's text is `aria-hidden`, so it is read once, not twice). Send
 * carries its id; a post clears the text and the quote together; a failure keeps both, and the typed `quote-unavailable` 400 says the message is gone
 * and the quote must be removed (`messageErrorMessage`) and blanks the line's excerpt (1.3 A7, `utils/quoteDraft.ts`: so does the loaded conversation showing the message deleted;
 * showing it edited refreshes the excerpt). The box's own description is Mantine's (it replaces any `aria-describedby` given to it),
 * so the status line, not a description, carries the quote to a screen reader.
 */
function ComposerForm({ room, thread, onPosted, quoteRequest = null }: ComposerProps) {
  const { t } = useTranslation();
  const hintId = useId();
  const queryClient = useQueryClient();
  const drafts = useThreadDrafts();
  const [text, setText] = useState(() => drafts.getReply(room.id, thread.number));
  const [quote, setQuote] = useState(() => drafts.getQuote(room.id, thread.number));
  const key = `${room.id}/${thread.number}`;
  // What this thread's post in flight carries, if any — from THIS composer or an earlier one for the same thread.
  const pending = useSyncExternalStore(subscribePending, () => inFlight.get(key));
  const posting = pending !== undefined;
  // What the conversation already loaded says about the picked message (no request of its own): deleted blanks the quote, edited refreshes it. Not while a
  // post travels: the quote it carries is compared by identity when it finishes.
  const loaded = useLoadedMessage(room.id, thread.number, quote?.id ?? null);
  const reconciled = quote !== null && !posting ? reconcileQuote(quote, loaded) : quote;
  if (reconciled !== quote) setQuote(reconciled);
  const [error, setError] = useState<string | null>(null);
  const form = useRef<HTMLFormElement>(null);
  const focusBox = useCallback(() => form.current?.querySelector("textarea")?.focus(), []);
  useEffect(() => {
    drafts.setReply(room.id, thread.number, text);
  }, [drafts, room.id, thread.number, text]);
  useEffect(() => {
    drafts.setQuote(room.id, thread.number, quote);
  }, [drafts, room.id, thread.number, quote]);
  // A "Quote" the pane asked for since this box was mounted: take it, and the focus once the menu it came from has let go of it. A request that
  // was already there at mount was applied (or dropped) by an earlier box of this thread: the kept draft is the truth.
  const [seenSeq, setSeenSeq] = useState(quoteRequest?.seq ?? null);
  const mountedSeq = useRef(seenSeq);
  if (quoteRequest !== null && quoteRequest.seq !== seenSeq) {
    setSeenSeq(quoteRequest.seq);
    // A copy per pick: a pick is told apart from the next one by identity (the sent quote clears only if it is still THIS pick), even for the same message.
    setQuote({ ...quoteRequest.quote });
  }
  useEffect(() => {
    if (seenSeq === mountedSeq.current) return;
    const timer = window.setTimeout(focusBox, QUOTE_FOCUS_DELAY_MS);
    return () => window.clearTimeout(timer);
  }, [seenSeq, focusBox]);
  useBeforeUnloadGuard(() => text.trim() !== "");
  // A post that finished while this box was mounted after it began (the earlier composer is gone): clear the box if it still shows what was sent.
  const wasPending = useRef(pending);
  useEffect(() => {
    const sent = wasPending.current;
    if (sent !== undefined && pending === undefined && sentOk.get(key) === sent) {
      sentOk.delete(key);
      setText((current) => (current === sent.text ? "" : current));
      setQuote((current) => (current === sent.quote ? null : current));
    }
    wasPending.current = pending;
  }, [pending, key]);

  async function send() {
    // Checked on the module-level map, not a state: a held Ctrl+Enter fires again before the first press has rendered, and a remounted box
    // must not post what is already travelling.
    if (inFlight.has(key)) return;
    const invalid = messageBodyError(text, t);
    setError(invalid);
    if (invalid) return;
    sentOk.delete(key);
    const sent: Sent = { text, quote };
    setPending(key, sent);
    try {
      const created = await postMessage(room.id, thread.number, normalizeBody(text), quote?.id);
      // Cleared in the store too: a post that finishes after the reader navigated away has no mounted box to clear it, and the sent text
      // (and the quote) would otherwise come back as a draft. A quote picked while the post travelled (even of the same message) is another pick, and stays.
      drafts.setReply(room.id, thread.number, "");
      if (drafts.getQuote(room.id, thread.number) === sent.quote) drafts.setQuote(room.id, thread.number, null);
      setText("");
      setQuote((current) => (current === sent.quote ? null : current));
      sentOk.set(key, sent);
      onPosted(created.id);
      await refreshAfterMessageWrite(queryClient, room.id, { newReply: true });
    } catch (err) {
      setError(messageErrorMessage(err, t));
      // The server says the message is gone: the line keeps the author and loses the text (a pick made since, another object, stays as it is).
      if (isProblem(err, PROBLEM_TYPES.quoteUnavailable)) setQuote((current) => (current === sent.quote && current !== null ? blankQuote(current) : current));
      if (isAccessRefusal(err)) await refreshQueriesAfterMutation(queryClient, ["rooms"]);
    } finally {
      setPending(key, null);
    }
  }

  return (
    <form
      ref={form}
      className={classes.composer}
      noValidate
      onSubmit={(event) => {
        event.preventDefault();
        void send();
      }}
    >
      <Stack gap={4} className={classes.surface}>
        {/* Mounted from the first render, so the words that appear in it when a quote is set are announced (the `@everyone` note is the precedent). */}
        <VisuallyHidden role="status">{quote ? t("threads.quote.announce", { author: quote.authorName, excerpt: quoteDraftExcerpt(quote, t) }) : ""}</VisuallyHidden>
        {quote && (
          <ComposerQuote
            quote={quote}
            onRemove={() => {
              // The typed 400 told the reader to remove the quote: with it gone, the words go too (the text is kept for the next try).
              setError(null);
              setQuote(null);
              focusBox();
            }}
          />
        )}
        <MentionTextarea
          roomId={room.id}
          label={t("threads.composer.label")}
          attributes={{ input: { "aria-describedby": `${hintId} ${hintId}-count` } }}
          classNames={{ input: classes.input }}
          counter="none"
          autosize
          minRows={1}
          maxRows={8}
          maxLength={MAX_MESSAGE_BODY_LENGTH}
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
        <Group justify="space-between" align="center" gap="sm" wrap="nowrap" className={classes.footer}>
          <div className={classes.footerNote}>
            <Text id={hintId} size="xs" c="dimmed" component="span" className={classes.hint}>
              {t("threads.composer.hint")}
            </Text>
            <Text id={`${hintId}-count`} size="xs" c="dimmed" component="span" className={classes.count}>
              {t("common.charCount", { current: text.length, max: MAX_MESSAGE_BODY_LENGTH })}
            </Text>
          </div>
          <Button type="submit" size="sm" loading={posting} leftSection={<IconSend size={15} aria-hidden="true" />} className={classes.send}>
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
export default function MessageComposer({ room, thread, onPosted, quoteRequest = null }: ComposerProps) {
  const { t } = useTranslation();
  const block = messageComposeBlock(room, thread);
  // Keyed by the thread, so the box starts from THAT thread's kept reply and never carries the previous one's text along.
  if (block === null) {
    // Only a request made in THIS thread is for this box.
    const own = quoteRequest?.threadNumber === thread.number ? quoteRequest : null;
    return <ComposerForm key={`${room.id}/${thread.number}`} room={room} thread={thread} onPosted={onPosted} quoteRequest={own} />;
  }
  return (
    <Alert className={classes.blocked} color="gray" variant="light" role="note">
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
