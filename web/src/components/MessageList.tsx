import { useCallback, useEffect, useLayoutEffect, useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import { Alert, Button, Center, Loader, Stack, VisuallyHidden } from "@mantine/core";
import { IconArrowDown, IconArrowUp } from "@tabler/icons-react";
import { useInfiniteQuery } from "@tanstack/react-query";
import { listMessages } from "../api/messages";
import type { RoomResponse } from "../api/rooms";
import type { ThreadResponse } from "../api/threads";
import { useAdmin } from "../auth";
import { getUserId } from "../api/session";
import { useArrivals, useFirstUnreadOnOpen } from "../hooks/useConversationUnread";
import { useRoomPins } from "../hooks/useRoomPins";
import { useThreadReadMarker } from "../hooks/useThreadReadMarker";
import { browserTimeZone } from "../utils/formatDate";
import { messageActions } from "../utils/messageAccess";
import ErrorAlert from "./ErrorAlert";
import LoadingBlock from "./LoadingBlock";
import MessageItem from "./MessageItem";
import classes from "./MessageList.module.css";
import { roomKeys } from "../utils/roomKeys";

/** A load reads 50 messages (the server's cap is 100). */
const MESSAGE_PAGE_SIZE = 50;

/** A reader within this many pixels of the end of the conversation counts as being at the bottom. */
const AT_BOTTOM_TOLERANCE_PX = 48;

/** A jump to a found message loads at most this many older pages (of [MESSAGE_PAGE_SIZE] messages each, counted from the moment the anchor arrives) before it gives up and says so. */
const MAX_JUMP_LOADS = 10;

/** How long the message a jump landed on stays highlighted. */
const SPOTLIGHT_MS = 5000;

/** Scrolls `scroller` (and only it, never the page) so that `item` sits in the middle of its visible area. */
function centreInScroller(scroller: HTMLElement, item: HTMLElement) {
  const offset = item.getBoundingClientRect().top - scroller.getBoundingClientRect().top - (scroller.clientHeight - item.offsetHeight) / 2;
  scroller.scrollTop = Math.max(0, scroller.scrollTop + offset);
}

/**
 * A thread's conversation: the newest message at the bottom, scrolled to on open, and "Load older messages" at the top (Lettuce's
 * `useInfiniteQuery` timeline, turned upside down). The pages are keyset pages: the server lists newest first and `id[lt]` keeps the ones
 * older than a cursor, so the next cursor is the oldest id loaded, offered while a page's `total` exceeds what it returned (`total` counts after
 * the filter). A refetch re-reads every loaded page, each cursor taken from the page just read, so a new message shifts nothing out of view.
 * It re-reads on focus, on navigation, after the caller's own writes and on a live hint. A reader at the bottom (within 48 px, tracked on scroll)
 * stays there when the list grows, so others' messages arrive in view; a reader who scrolled up is never moved.
 *
 * `scrollToId` is the id of the caller's own just-posted message: once the list shows it (or something newer), the scroller goes to the
 * bottom and `onScrolled` clears the request. Loading older messages keeps the reader where they were.
 *
 * `anchorId` is a message to jump to (a search hit's `?message=`): the list does NOT open at the bottom. It loads older pages (never
 * cancelling a fetch another reader or a live hint started: a load waits for the one in flight) until the id is listed, at most ten
 * loads, then scrolls to it, focuses it (`tabIndex={-1}` on the item), announces it and highlights it for a few seconds. When the
 * thread has no such message, or it is further back than ten loads reach (counted from the moment the anchor arrives), a gray note above the
 * list says so and the list goes to the bottom, as on any open. A deleted message is listed as its tombstone, so the jump lands on that.
 * The jump happens once per anchor, however often the list is re-read afterwards (and again when the reader asks for it: `jumpSeq`, the pin strip's "Show in thread").
 *
 * Unread (0.6.0): the first message that was unread when the thread opened (`thread.firstUnreadMessageId`, taken once, once the thread read has
 * settled: `threadSettled`) gets a "New" separator above it; while it is off screen (or not loaded yet) "Jump to the first new message"
 * takes the reader there through the same jump as `anchorId`, without touching the URL. Messages from others that arrive under a reader who
 * is not at the end are counted in a button below the list that goes to the end (and a polite status line says so); reaching the end clears it.
 * A MEMBER at the end of the list, in a visible tab, moves their read marker to the newest message (`useThreadReadMarker`).
 */
export default function MessageList({
  room,
  thread,
  scrollToId,
  onScrolled,
  anchorId: urlAnchorId = null,
  jumpSeq = 0,
  threadSettled = true,
}: {
  room: RoomResponse;
  thread: ThreadResponse;
  scrollToId: number | null;
  onScrolled: () => void;
  anchorId?: number | null;
  /** Bumped by the pane when the reader asks to be taken to a message again (the pin strip's "Show in thread"): the jump runs once per (anchor, request). */
  jumpSeq?: number;
  /** Whether the thread read that holds `firstUnreadMessageId` has settled (no fetch in flight): the unread boundary is taken from the first settled read. */
  threadSettled?: boolean;
}) {
  const { t } = useTranslation();
  const admin = useAdmin();
  const userId = getUserId();
  const zone = browserTimeZone();
  // Who pinned each pinned message (the room's one pins query): the message wears a "Pinned by" mark; a failed read marks nothing.
  const { pinnedByMessage } = useRoomPins(room.id);
  // "Jump to the first new message" asks the anchor machinery for a message of its own (it wins over the URL's only until the URL's changes).
  const [localJump, setLocalJump] = useState<{ url: number | null; id: number } | null>(null);
  const anchorId = localJump !== null && localJump.url === urlAnchorId ? localJump.id : urlAnchorId;
  const { data, isLoading, isError, isFetching, error, fetchNextPage, hasNextPage, isFetchingNextPage, refetch } = useInfiniteQuery({
    queryKey: roomKeys.messages(room.id, thread.number),
    queryFn: ({ pageParam }) => listMessages(room.id, thread.number, { idLt: pageParam, pageSize: MESSAGE_PAGE_SIZE }),
    initialPageParam: undefined as number | undefined,
    getNextPageParam: (last) => (last.total > last.items.length ? last.items.at(-1)?.id : undefined),
  });
  // The pages read newest first; the conversation reads oldest first.
  const messages = (data?.pages.flatMap((page) => page.items) ?? []).reverse();
  const newestId = messages.at(-1)?.id;
  const newestEditedAt = messages.at(-1)?.editedAt;
  const found = anchorId !== null && messages.some((message) => message.id === anchorId);
  // The loads of older messages since the anchor arrived (a reader's own earlier "Load older" presses do not spend the budget): the pages
  // then loaded are the baseline, and at least the first read is not a load.
  const pageCount = data?.pages.length ?? 0;
  const [baseline, setBaseline] = useState({ anchor: anchorId, pages: pageCount });
  if (baseline.anchor !== anchorId) setBaseline({ anchor: anchorId, pages: pageCount });
  const olderLoads = Math.max(pageCount - Math.max(baseline.pages, 1), 0);

  const scroller = useRef<HTMLDivElement>(null);
  const opened = useRef(false);
  // Whether the list opened on a jump: then it starts where the jump will take it, never at the bottom.
  const openedOnAnchor = useRef(urlAnchorId !== null);
  // Whether the reader was within reach of the newest message the last time they (or an anchor) moved the scroller: a ref for the layout
  // effects, and the same value as state for what is drawn and sent (the arrivals button, the read marker). A list opened on a jump starts away from the end.
  const atBottom = useRef(urlAnchorId === null);
  const [atEnd, setAtEnd] = useState(urlAnchorId === null);
  const setBottom = useCallback((value: boolean) => {
    atBottom.current = value;
    setAtEnd(value);
  }, []);
  const trackBottom = useCallback(() => {
    const el = scroller.current;
    if (el) setBottom(el.scrollHeight - el.scrollTop - el.clientHeight <= AT_BOTTOM_TOLERANCE_PX);
  }, [setBottom]);
  // Where the reader was before older messages were prepended, so they are not thrown to the top of a longer list.
  const anchor = useRef<{ top: number; height: number } | null>(null);
  useLayoutEffect(() => {
    const el = scroller.current;
    if (!el || messages.length === 0) return;
    if (!opened.current) {
      opened.current = true;
      if (!openedOnAnchor.current) el.scrollTop = el.scrollHeight;
      setBottom(!openedOnAnchor.current);
    } else if (anchor.current !== null) {
      el.scrollTop = anchor.current.top + (el.scrollHeight - anchor.current.height);
      anchor.current = null;
      trackBottom();
    } else if (atBottom.current) {
      // The list grew under a reader at the bottom (a message from someone else): keep them there.
      el.scrollTop = el.scrollHeight;
    }
  }, [messages.length, trackBottom, setBottom]);
  // The newest message was replaced or edited (it may be taller now) under a reader at the bottom: keep them there. Only a change of the
  // newest message fires this, so loading older messages (which anchors the reader) never does.
  useLayoutEffect(() => {
    const el = scroller.current;
    if (el && opened.current && anchor.current === null && atBottom.current) el.scrollTop = el.scrollHeight;
  }, [newestId, newestEditedAt]);
  useLayoutEffect(() => {
    const el = scroller.current;
    if (!el || scrollToId === null || newestId === undefined || newestId < scrollToId) return;
    el.scrollTop = el.scrollHeight;
    setBottom(true);
    onScrolled();
  }, [scrollToId, newestId, onScrolled, setBottom]);


  // The jump: once per anchor, when its message is listed. The message is centred in the scroller (the page does not move), takes the focus
  // (without a second scroll) and is highlighted for a while; `trackBottom` re-reads where that leaves the reader, so a list that grows later does not pull them away.
  const [spotlight, setSpotlight] = useState<number | null>(null);
  const jumpedTo = useRef<{ id: number; seq: number } | null>(null);
  useLayoutEffect(() => {
    if (anchorId === null) {
      jumpedTo.current = null;
      return;
    }
    if (!found || (jumpedTo.current?.id === anchorId && jumpedTo.current.seq === jumpSeq)) return;
    const item = scroller.current?.querySelector<HTMLElement>(`[data-message-id="${anchorId}"]`);
    if (!item) return;
    jumpedTo.current = { id: anchorId, seq: jumpSeq };
    if (scroller.current) centreInScroller(scroller.current, item);
    item.focus({ preventScroll: true });
    trackBottom();
    setSpotlight(anchorId);
  }, [anchorId, found, jumpSeq, trackBottom]);
  // A jump to the first new message is used up once it has landed (the spotlight is on it): the URL's anchor, if any, takes over again.
  if (localJump !== null && spotlight === localJump.id) setLocalJump(null);
  useEffect(() => {
    if (spotlight === null) return;
    const timer = setTimeout(() => setSpotlight(null), SPOTLIGHT_MS);
    return () => clearTimeout(timer);
  }, [spotlight]);
  // Look for the anchor: one older page at a time, never while a fetch is in flight (a live refetch is waited for, not cancelled), and
  // never after a failed load (the alert and the button take over) or past the bound.
  useEffect(() => {
    if (anchorId === null || found || isFetching || isError || !hasNextPage || olderLoads >= MAX_JUMP_LOADS) return;
    void fetchNextPage({ cancelRefetch: false });
  }, [anchorId, found, isFetching, isError, hasNextPage, olderLoads, fetchNextPage]);
  // Settled without it: every page is loaded (the thread has no such message) or the bound was reached.
  const anchorNote = anchorId !== null && !found && !isFetching && !isError && data !== undefined ? (hasNextPage ? (olderLoads >= MAX_JUMP_LOADS ? "tooFar" : null) : "gone") : null;
  // The search ended without the message: show the end of the conversation (the note stays in view above the list).
  useLayoutEffect(() => {
    const el = scroller.current;
    if (anchorNote === null || !el) return;
    el.scrollTop = el.scrollHeight;
    setBottom(true);
  }, [anchorNote, setBottom]);
  const spotlightAuthor = messages.find((message) => message.id === spotlight)?.authorName;

  // Unread. The boundary is taken once; the jump button shows while that message is not on screen (not loaded counts as not on screen).
  const firstUnread = useFirstUnreadOnOpen(thread.firstUnreadMessageId, threadSettled);
  const [firstOnScreen, setFirstOnScreen] = useState(false);
  const measureFirstUnread = useCallback(() => {
    const el = scroller.current;
    const item = firstUnread === null ? null : el?.querySelector<HTMLElement>(`[data-message-id="${firstUnread}"]`);
    if (!el || !item) {
      setFirstOnScreen(false);
      return;
    }
    const box = el.getBoundingClientRect();
    const rect = item.getBoundingClientRect();
    setFirstOnScreen(rect.bottom >= box.top && rect.top <= box.bottom);
  }, [firstUnread]);
  useLayoutEffect(measureFirstUnread, [measureFirstUnread, messages.length]);
  const arrivals = useArrivals(messages, atEnd, userId);
  // Only a settled conversation is read: the newest id of a stale cached copy (an earlier visit's) was never seen, and a re-read is about to replace it.
  useThreadReadMarker({ roomId: room.id, threadNumber: thread.number, newestId, atEnd, enabled: room.role !== null && threadSettled && !isFetching });

  function jumpToFirstUnread() {
    if (firstUnread === null) return;
    const el = scroller.current;
    const item = el?.querySelector<HTMLElement>(`[data-message-id="${firstUnread}"]`);
    if (!el || !item) {
      // Not loaded yet: the anchor machinery loads older pages until it is listed, then lands on it. The reader is away from the end meanwhile,
      // as when a list opens on the URL's anchor (older pages prepended must neither pull them down nor count as having seen the newest).
      setBottom(false);
      setLocalJump({ url: urlAnchorId, id: firstUnread });
      return;
    }
    centreInScroller(el, item);
    item.focus({ preventScroll: true });
    trackBottom();
    measureFirstUnread();
    setSpotlight(firstUnread);
  }
  function jumpToEnd() {
    const el = scroller.current;
    if (!el) return;
    el.scrollTop = el.scrollHeight;
    setBottom(true);
    el.focus({ preventScroll: true });
  }

  // Set when "Load older" was pressed: if that load was the last one the button goes away with the focus on it, and focus would fall to the page.
  const pressedLoad = useRef(false);
  useEffect(() => {
    if (hasNextPage || !pressedLoad.current) return;
    pressedLoad.current = false;
    // Only when focus really was lost with the button (it is on the body, or nowhere): never pull it from where the reader went meanwhile.
    const active = document.activeElement;
    if (active !== null && active !== document.body) return;
    // The oldest loaded message is exactly where the button was; the scroll position is already anchored, so do not scroll.
    (scroller.current?.querySelector<HTMLElement>("ul > li") ?? scroller.current)?.focus({ preventScroll: true });
  }, [hasNextPage]);

  function loadOlder() {
    if (isFetchingNextPage) return;
    const el = scroller.current;
    if (el) anchor.current = { top: el.scrollTop, height: el.scrollHeight };
    pressedLoad.current = true;
    // A failed load leaves the list as it was, so the anchor must not outlive it and shift the next change.
    void fetchNextPage().then((result) => {
      if (result.isError) anchor.current = null;
      if (result.isError || result.hasNextPage) pressedLoad.current = false;
    });
  }

  // Mounted from the first render (also while loading), so the line that appears in it is announced.
  const announced =
    spotlightAuthor !== undefined ? t("threads.anchor.found", { author: spotlightAuthor }) : arrivals > 0 ? t("threads.arrivals.announce", { count: arrivals }) : "";
  const announcement = <VisuallyHidden role="status">{announced}</VisuallyHidden>;
  if (isLoading) {
    return (
      <>
        {announcement}
        <LoadingBlock />
      </>
    );
  }
  return (
    <>
      {announcement}
      {anchorNote !== null && (
        <Alert color="gray" variant="light" role="note">
          {t(anchorNote === "gone" ? "threads.anchor.gone" : "threads.anchor.tooFar")}
        </Alert>
      )}
      {firstUnread !== null && !firstOnScreen && (
        <Button variant="light" size="compact-sm" style={{ alignSelf: "flex-start" }} leftSection={<IconArrowUp size={14} />} onClick={jumpToFirstUnread}>
          {t("threads.unread.jump")}
        </Button>
      )}
      <div
        ref={scroller}
        className={classes.scroller}
        role="region"
        aria-label={t("threads.messages.aria")}
        tabIndex={0}
        onScroll={() => {
          trackBottom();
          measureFirstUnread();
        }}
      >
        {isError && (
          <Stack gap="xs" align="flex-start" pb="xs">
            <ErrorAlert error={error} title={t("threads.messages.loadFailed")} />
            {/* A failed first read has no "Load older" to try again with; a failed load of older ones keeps its button. */}
            {!data && (
              <Button variant="default" size="compact-sm" onClick={() => void refetch()}>
                {t("common.action.retry")}
              </Button>
            )}
          </Stack>
        )}
        {hasNextPage && (
          <Center py="xs">
            {/* aria-busy, not disabled or loading: a disabled button would drop the focus the reader pressed it with. */}
            <Button
              variant="default"
              size="compact-sm"
              onClick={loadOlder}
              aria-busy={isFetchingNextPage}
              leftSection={isFetchingNextPage ? <Loader size={14} /> : undefined}
            >
              {t("threads.messages.loadOlder")}
            </Button>
          </Center>
        )}
        <ul className={classes.list}>
          {messages.map((message) => (
            <MessageItem
              key={message.id}
              roomId={room.id}
              message={message}
              actions={messageActions({ room, thread, message, userId, admin })}
              zone={zone}
              anchored={message.id === spotlight}
              firstNew={message.id === firstUnread}
              pinnedBy={pinnedByMessage.get(message.id)}
            />
          ))}
        </ul>
      </div>
      {arrivals > 0 && (
        <Button variant="light" size="compact-sm" style={{ alignSelf: "flex-start" }} leftSection={<IconArrowDown size={14} />} onClick={jumpToEnd}>
          {t("threads.arrivals.button", { count: arrivals })}
        </Button>
      )}
    </>
  );
}
