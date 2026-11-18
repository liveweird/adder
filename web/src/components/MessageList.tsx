import { useCallback, useEffect, useLayoutEffect, useRef } from "react";
import { useTranslation } from "react-i18next";
import { Button, Center, Loader, Stack } from "@mantine/core";
import { useInfiniteQuery } from "@tanstack/react-query";
import { listMessages } from "../api/messages";
import type { RoomResponse } from "../api/rooms";
import type { ThreadResponse } from "../api/threads";
import { useAdmin } from "../auth";
import { getUserId } from "../api/session";
import { browserTimeZone } from "../utils/formatDate";
import { messageActions } from "../utils/messageAccess";
import ErrorAlert from "./ErrorAlert";
import LoadingBlock from "./LoadingBlock";
import MessageItem from "./MessageItem";
import classes from "./MessageList.module.css";

/** A load reads 50 messages (the server's cap is 100). */
const MESSAGE_PAGE_SIZE = 50;

/** A reader within this many pixels of the end of the conversation counts as being at the bottom. */
const AT_BOTTOM_TOLERANCE_PX = 48;

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
 */
export default function MessageList({
  room,
  thread,
  scrollToId,
  onScrolled,
}: {
  room: RoomResponse;
  thread: ThreadResponse;
  scrollToId: number | null;
  onScrolled: () => void;
}) {
  const { t } = useTranslation();
  const admin = useAdmin();
  const userId = getUserId();
  const zone = browserTimeZone();
  const { data, isLoading, isError, error, fetchNextPage, hasNextPage, isFetchingNextPage, refetch } = useInfiniteQuery({
    queryKey: ["rooms", "threads", room.id, "messages", thread.number],
    queryFn: ({ pageParam }) => listMessages(room.id, thread.number, { idLt: pageParam, pageSize: MESSAGE_PAGE_SIZE }),
    initialPageParam: undefined as number | undefined,
    getNextPageParam: (last) => (last.total > last.items.length ? last.items.at(-1)?.id : undefined),
  });
  // The pages read newest first; the conversation reads oldest first.
  const messages = (data?.pages.flatMap((page) => page.items) ?? []).reverse();
  const newestId = messages.at(-1)?.id;
  const newestEditedAt = messages.at(-1)?.editedAt;

  const scroller = useRef<HTMLDivElement>(null);
  const opened = useRef(false);
  // Whether the reader was within reach of the newest message the last time they (or an anchor) moved the scroller.
  const atBottom = useRef(true);
  const trackBottom = useCallback(() => {
    const el = scroller.current;
    if (el) atBottom.current = el.scrollHeight - el.scrollTop - el.clientHeight <= AT_BOTTOM_TOLERANCE_PX;
  }, []);
  // Where the reader was before older messages were prepended, so they are not thrown to the top of a longer list.
  const anchor = useRef<{ top: number; height: number } | null>(null);
  useLayoutEffect(() => {
    const el = scroller.current;
    if (!el || messages.length === 0) return;
    if (!opened.current) {
      opened.current = true;
      el.scrollTop = el.scrollHeight;
      atBottom.current = true;
    } else if (anchor.current !== null) {
      el.scrollTop = anchor.current.top + (el.scrollHeight - anchor.current.height);
      anchor.current = null;
      trackBottom();
    } else if (atBottom.current) {
      // The list grew under a reader at the bottom (a message from someone else): keep them there.
      el.scrollTop = el.scrollHeight;
    }
  }, [messages.length, trackBottom]);
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
    atBottom.current = true;
    onScrolled();
  }, [scrollToId, newestId, onScrolled]);

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

  if (isLoading) return <LoadingBlock />;
  return (
    <div ref={scroller} className={classes.scroller} role="region" aria-label={t("threads.messages.aria")} tabIndex={0} onScroll={trackBottom}>
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
          />
        ))}
      </ul>
    </div>
  );
}
