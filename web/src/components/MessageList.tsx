import { useCallback, useEffect, useLayoutEffect, useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import { Alert, Button, Center, Group, Loader, Stack, VisuallyHidden } from "@mantine/core";
import { IconArrowDown, IconArrowUp } from "@tabler/icons-react";
import { useInfiniteQuery, useQuery } from "@tanstack/react-query";
import { getMessageContext, listMessages, type MessageContext, type MessageResponse } from "../api/messages";
import { ApiError } from "../api/http";
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

/** How long the message a jump landed on stays highlighted. */
const SPOTLIGHT_MS = 5000;

/** Scrolls `scroller` (and only it, never the page) so that `item` sits in the middle of its visible area. */
function centreInScroller(scroller: HTMLElement, item: HTMLElement) {
  const offset = item.getBoundingClientRect().top - scroller.getBoundingClientRect().top - (scroller.clientHeight - item.offsetHeight) / 2;
  scroller.scrollTop = Math.max(0, scroller.scrollTop + offset);
}

function ContextControls({ context, onSelect, onLatest }: {
  context: MessageContext | undefined;
  onSelect: (messageId: number) => void;
  onLatest: () => void;
}) {
  const { t } = useTranslation();
  return (
    <Group role="group" aria-label={t("threads.anchor.navigation")} className={classes.contextControls} gap="xs">
      {context?.older && (
        <Button variant="default" size="compact-sm" onClick={() => onSelect(context.items[0]!.id)}>{t("threads.anchor.earlier")}</Button>
      )}
      {context?.newer && (
        <Button variant="default" size="compact-sm" onClick={() => onSelect(context.items.at(-1)!.id)}>{t("threads.anchor.later")}</Button>
      )}
      <Button variant="light" size="compact-sm" className={classes.backToLatest} onClick={onLatest}>{t("threads.anchor.latest")}</Button>
    </Group>
  );
}

/**
 * Latest messages use the chronological timeline and older-page cursor. A message jump opens a bounded historical
 * window instead. Browsing that window never acknowledges unread activity or scrolls to newly posted messages.
 */
export default function MessageList({
  room,
  thread,
  scrollToId,
  onScrolled,
  anchorId: urlAnchorId = null,
  layoutKey,
  jumpSeq = 0,
  threadSettled = true,
  readingPaused = false,
  onQuote,
  onShowQuoted,
  onReturnToLatest,
}: {
  room: RoomResponse;
  thread: ThreadResponse;
  scrollToId: number | null;
  onScrolled: () => void;
  anchorId?: number | null;
  /** Changes when a thread control resizes the conversation. */
  layoutKey?: string;
  /** Bumped by the pane when the reader asks to be taken to a message again (the pin strip's "Show in thread"): the jump runs once per (anchor, request). */
  jumpSeq?: number;
  /** Whether the thread read that holds `firstUnreadMessageId` has settled (no fetch in flight): the unread boundary is taken from the first settled read. */
  threadSettled?: boolean;
  /** Search can take over the timeline on narrow screens. Pause read markers while it is open. */
  readingPaused?: boolean;
  /** The reader chose "Quote" on a message (1.3): the pane puts it in the reply box. Without it no message offers a quote. */
  onQuote?: (message: MessageResponse) => void;
  /** The reader followed a quote block (1.3): the quoted message's id; the pane runs the jump again when the URL already names it (the same `jumpSeq`). */
  onShowQuoted?: (messageId: number) => void;
  onReturnToLatest?: () => void;
}) {
  const { t } = useTranslation();
  const admin = useAdmin();
  const userId = getUserId();
  const zone = browserTimeZone();
  // Who pinned each pinned message (the room's one pins query): the message wears a "Pinned by" mark; a failed read marks nothing.
  const { pinnedByMessage } = useRoomPins(room.id);
  // An explicit jump or history navigation supersedes the window, until a new URL or repeat jump takes over.
  const jumpKey = `${urlAnchorId ?? ""}/${jumpSeq}`;
  const [selection, setSelection] = useState<{ key: string; id: number | null } | null>(null);
  // Posting returns to the latest timeline even if the historical window was opened without a URL anchor.
  const [seenScrollToId, setSeenScrollToId] = useState<number | null>(null);
  if (scrollToId !== seenScrollToId) {
    setSeenScrollToId(scrollToId);
    if (scrollToId !== null) setSelection({ key: jumpKey, id: null });
  }
  const windowId = selection?.key === jumpKey ? selection.id : urlAnchorId;
  const inContext = windowId !== null;
  const latest = useInfiniteQuery({
    queryKey: roomKeys.messages(room.id, thread.number),
    queryFn: ({ pageParam }) => listMessages(room.id, thread.number, { idLt: pageParam, pageSize: MESSAGE_PAGE_SIZE }),
    initialPageParam: undefined as number | undefined,
    getNextPageParam: (last) => (last.total > last.items.length ? last.items.at(-1)?.id : undefined),
    enabled: !inContext,
  });
  const context = useQuery({
    queryKey: roomKeys.messageContext(room.id, thread.number, windowId),
    queryFn: ({ signal }) => getMessageContext(room.id, thread.number, windowId!, signal),
    enabled: inContext,
  });
  const read = inContext ? context : latest;
  const missing = inContext && context.error instanceof ApiError && context.error.status === 404;
  const messageWindow = missing ? undefined : context.data;
  const messages = inContext ? messageWindow?.items ?? [] : (latest.data?.pages.flatMap((page) => page.items) ?? []).reverse();
  const newestId = messages.at(-1)?.id;
  const newestEditedAt = messages.at(-1)?.editedAt;
  const found = inContext && messages.some((message) => message.id === windowId);

  const scroller = useRef<HTMLDivElement>(null);
  const opened = useRef(false);
  const focusLatest = useRef(false);
  const atBottom = useRef(!inContext);
  const [position, setPosition] = useState({ inContext, atEnd: !inContext });
  if (position.inContext !== inContext) setPosition({ inContext, atEnd: !inContext });
  const setBottom = useCallback((value: boolean) => {
    atBottom.current = value;
    setPosition((current) => current.atEnd === value ? current : { ...current, atEnd: value });
  }, []);
  const trackBottom = useCallback(() => {
    const el = scroller.current;
    if (el) setBottom(el.scrollHeight - el.scrollTop - el.clientHeight <= AT_BOTTOM_TOLERANCE_PX);
  }, [setBottom]);
  // Where the reader was before older messages were prepended.
  const anchor = useRef<{ top: number; height: number } | null>(null);
  useLayoutEffect(() => {
    opened.current = false;
    anchor.current = null;
    atBottom.current = !inContext;
  }, [inContext]);
  useLayoutEffect(() => {
    const el = scroller.current;
    if (!el || inContext || messages.length === 0) return;
    if (!opened.current) {
      opened.current = true;
      el.scrollTop = el.scrollHeight;
    } else if (anchor.current !== null) {
      el.scrollTop = anchor.current.top + (el.scrollHeight - anchor.current.height);
      anchor.current = null;
      trackBottom();
    } else if (atBottom.current) {
      el.scrollTop = el.scrollHeight;
    }
  }, [inContext, messages.length, trackBottom]);
  useLayoutEffect(() => {
    const el = scroller.current;
    if (!inContext && el && opened.current && anchor.current === null && atBottom.current) el.scrollTop = el.scrollHeight;
  }, [inContext, newestId, newestEditedAt, layoutKey]);

  const selectContext = (id: number) => setSelection({ key: jumpKey, id });
  const returnToLatest = useCallback(() => {
    setSelection({ key: jumpKey, id: null });
    onReturnToLatest?.();
  }, [jumpKey, onReturnToLatest]);
  useLayoutEffect(() => {
    if (!inContext && !latest.isFetching && focusLatest.current && scroller.current) {
      focusLatest.current = false;
      scroller.current.focus({ preventScroll: true });
    }
  }, [inContext, latest.isFetching, latest.isLoading]);
  useLayoutEffect(() => {
    if (inContext || scrollToId === null) return;
    const el = scroller.current;
    if (!el || newestId === undefined || newestId < scrollToId) return;
    el.scrollTop = el.scrollHeight;
    setBottom(true);
    onScrolled();
  }, [inContext, scrollToId, newestId, onScrolled, setBottom]);

  const [spotlight, setSpotlight] = useState<number | null>(null);
  const jumpedTo = useRef<{ id: number; key: string } | null>(null);
  useLayoutEffect(() => {
    if (windowId === null) {
      jumpedTo.current = null;
      return;
    }
    if (!found || (jumpedTo.current?.id === windowId && jumpedTo.current.key === jumpKey)) return;
    const el = scroller.current;
    const item = el?.querySelector<HTMLElement>(`[data-message-id="${windowId}"]`);
    if (!el || !item) return;
    jumpedTo.current = { id: windowId, key: jumpKey };
    centreInScroller(el, item);
    item.focus({ preventScroll: true });
    setSpotlight(windowId);
  }, [windowId, found, jumpKey]);
  useEffect(() => {
    if (spotlight === null) return;
    const timer = setTimeout(() => setSpotlight(null), SPOTLIGHT_MS);
    return () => clearTimeout(timer);
  }, [spotlight, jumpKey]);
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
  useEffect(() => {
    const el = scroller.current;
    if (read.isLoading || !el) return;
    // Opening Details or resizing the workspace changes the visible height without adding messages.
    const observer = new ResizeObserver(() => {
      if (!inContext && opened.current && atBottom.current && anchor.current === null) el.scrollTop = el.scrollHeight;
      measureFirstUnread();
    });
    observer.observe(el);
    return () => observer.disconnect();
  }, [inContext, read.isLoading, measureFirstUnread]);
  const readingLatest = !inContext && position.atEnd;
  const arrivals = useArrivals(inContext ? [] : messages, readingLatest, userId);
  // Only a settled conversation is read: the newest id of a stale cached copy (an earlier visit's) was never seen, and a re-read is about to replace it.
  useThreadReadMarker({
    roomId: room.id, threadNumber: thread.number, newestId, atEnd: readingLatest && !readingPaused,
    enabled: !inContext && room.role !== null && threadSettled && !latest.isFetching,
  });

  function jumpToFirstUnread() {
    if (firstUnread === null) return;
    const el = scroller.current;
    const item = el?.querySelector<HTMLElement>(`[data-message-id="${firstUnread}"]`);
    if (!el || !item) {
      selectContext(firstUnread);
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
    if (inContext || latest.hasNextPage || !pressedLoad.current) return;
    pressedLoad.current = false;
    // Only when focus really was lost with the button (it is on the body, or nowhere): never pull it from where the reader went meanwhile.
    const active = document.activeElement;
    if (active !== null && active !== document.body) return;
    // The oldest loaded message is exactly where the button was; the scroll position is already anchored, so do not scroll.
    (scroller.current?.querySelector<HTMLElement>("ul > li") ?? scroller.current)?.focus({ preventScroll: true });
  }, [inContext, latest.hasNextPage]);

  function loadOlder() {
    if (latest.isFetchingNextPage) return;
    const el = scroller.current;
    if (el) anchor.current = { top: el.scrollTop, height: el.scrollHeight };
    pressedLoad.current = true;
    // A failed load leaves the list as it was, so the anchor must not outlive it and shift the next change.
    void latest.fetchNextPage().then((result) => {
      if (result.isError) anchor.current = null;
      if (result.isError || result.hasNextPage) pressedLoad.current = false;
    });
  }

  // Mounted from the first render (also while loading), so the line that appears in it is announced.
  const announced =
    spotlightAuthor !== undefined ? t("threads.anchor.found", { author: spotlightAuthor }) : arrivals > 0 ? t("threads.arrivals.announce", { count: arrivals }) : "";
  const announcement = <VisuallyHidden role="status">{announced}</VisuallyHidden>;
  if (read.isLoading && !inContext) {
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
      {inContext && (
        <ContextControls
          context={messageWindow}
          onSelect={selectContext}
          onLatest={() => {
            focusLatest.current = true;
            returnToLatest();
          }}
        />
      )}
      {missing && <Alert className={classes.notice} color="gray" variant="light" role="note">{t("threads.anchor.gone")}</Alert>}
      {firstUnread !== null && !firstOnScreen && (
        <Button className={classes.jump} variant="light" size="compact-sm" style={{ alignSelf: "flex-start" }} leftSection={<IconArrowUp size={14} />} onClick={jumpToFirstUnread}>
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
          if (!inContext) trackBottom();
          measureFirstUnread();
        }}
      >
        {read.isLoading && <LoadingBlock />}
        {read.isError && !missing && (
          <Stack gap="xs" align="flex-start" pb="xs">
            <ErrorAlert error={read.error} title={t("threads.messages.loadFailed")} />
            {/* A failed first read has no "Load older" to try again with; a failed load of older ones keeps its button. */}
            {(!read.data || inContext) && (
              <Button variant="default" size="compact-sm" onClick={() => void read.refetch()}>
                {t("common.action.retry")}
              </Button>
            )}
          </Stack>
        )}
        {!inContext && latest.hasNextPage && (
          <Center py="xs">
            {/* aria-busy, not disabled or loading: a disabled button would drop the focus the reader pressed it with. */}
            <Button
              variant="default"
              size="compact-sm"
              onClick={loadOlder}
              aria-busy={latest.isFetchingNextPage}
              leftSection={latest.isFetchingNextPage ? <Loader size={14} /> : undefined}
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
              onQuote={onQuote}
              onShowQuoted={onShowQuoted}
            />
          ))}
        </ul>
      </div>
      {arrivals > 0 && (
        <Button className={classes.jump} variant="light" size="compact-sm" style={{ alignSelf: "flex-start" }} leftSection={<IconArrowDown size={14} />} onClick={jumpToEnd}>
          {t("threads.arrivals.button", { count: arrivals })}
        </Button>
      )}
    </>
  );
}
