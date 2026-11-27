import { useCallback, useEffect, useId, useLayoutEffect, useRef, useState } from "react";
import { useLocation, useNavigate, useNavigationType } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { Box, Button, Group, Stack, Text, Title } from "@mantine/core";
import { useQuery } from "@tanstack/react-query";
import {
  IconLayoutSidebarLeftCollapse,
  IconLayoutSidebarLeftExpand,
  IconLayoutSidebarRightCollapse,
  IconLayoutSidebarRightExpand,
  IconMessageOff,
  IconSearch,
} from "@tabler/icons-react";
import { ApiError } from "../api/http";
import type { MessageResponse } from "../api/messages";
import type { RoomResponse } from "../api/rooms";
import { getThread } from "../api/threads";
import { useLiveSearchParams } from "../hooks/useLiveSearchParams";
import { isBoolean, useStoredState } from "../hooks/useStoredState";
import { useRoomPins } from "../hooks/useRoomPins";
import { useRoomWorkflow } from "../hooks/useRoomWorkflow";
import { fillSlots, slot } from "../utils/slots";
import { listRouteSearch, messageJumpPath, parseThreadNumber, quoteReturnLocation, quoteReturnMessageId, roomPath, threadPath, wantsThreadPaneFocus, withListParams } from "../utils/roomLinks";
import { parseFocus, parseMessageAnchor, writeFocus } from "../utils/threadListParams";
import EmptyState from "./EmptyState";
import ErrorAlert from "./ErrorAlert";
import FormErrorAlert from "./FormErrorAlert";
import HeaderIconButton from "./HeaderIconButton";
import LoadingBlock from "./LoadingBlock";
import MessageComposer, { type QuoteRequest } from "./MessageComposer";
import MessageList from "./MessageList";
import QuoteDestinationModal from "./QuoteDestinationModal";
import PersonLink from "./PersonLink";
import PinMark from "./PinMark";
import TagPills from "./TagPills";
import ThreadHashtags from "./ThreadHashtags";
import ThreadMessageSearch from "./ThreadMessageSearch";
import ThreadPinButton from "./ThreadPinButton";
import ThreadPinStrip from "./ThreadPinStrip";
import ThreadSidePanel from "./ThreadSidePanel";
import ThreadStatusControl from "./ThreadStatusControl";
import ThreadWatchButton from "./ThreadWatchButton";
import ThreadMuteButton from "./ThreadMuteButton";
import ThreadLifecycleActions from "./ThreadLifecycleActions";
import MergedThreadBanner from "./MergedThreadBanner";
import MergedThreadSources from "./MergedThreadSources";
import classes from "./ThreadPane.module.css";
import { roomKeys } from "../utils/roomKeys";

/** The header's third row shows at most this many hashtags, on ONE line (`ThreadHashtags.module.css`); the rest are one "+N" that opens the details (whose "Hashtags" section holds them all). */
const HEADER_HASHTAGS = 4;

/** The header's tag row shows at most this many curated tags, on ONE line (`TagPills.module.css`); the rest are one "+N" that opens the details (whose "Tags" section holds them all). */
const HEADER_TAGS = 3;

/**
 * The room view's conversation pane for `/rooms/:id/threads/:number`: the thread's header — `#42 Title` as the pane's heading, its
 * status, owner and message count — then the conversation and reply box (or a note saying why replies are unavailable). The header keeps
 * workflow actions, thread actions, curated tags and hashtag links in reach. The side panel holds ownership, fields, timestamps and history. Details
 * opens it beside the conversation on wide screens and above it on narrow screens. A number that names no thread reads as "does not exist". The section is
 * programmatically focusable and takes focus once its thread has loaded after a navigation that asked for it (a list row, a started
 * thread — `wantsThreadPaneFocus`), the `RoomWorkflowForm` precedent: the clicked row is left behind in the list. A `replace` (a filter
 * change, the focus toggle) never asks for it, and neither does a jump to a message (the conversation focuses that message).
 *
 * Two keys of the URL belong to the pane (`utils/threadListParams.ts`): `message=<id>` is the message the conversation jumps to (a search hit's
 * link) and `focus=1` is the focus view, which `RoomHome` reads to hide the thread list; the header's "Focus on this thread" toggle
 * (`aria-pressed`) writes it by `replace`, keeping the list's keys. The URL is the state, so a reload keeps it; nothing remembers it. A thread
 * that cannot be shown (missing, unreadable, failed) says so with its own "Show the thread list" button while the view is on, so it is never a dead end.
 *
 * Quotes (1.3): "Quote" on a message (`quoteMessage`) is a numbered request the pane hands to the reply box, which keeps the draft per thread and takes
 * the focus; a quote block's link is the 0.5 jump, and `requestJump` (the pin strip's too) repeats it when the URL already names the message.
 */
export default function ThreadPane({ room, numberParam }: { room: RoomResponse; numberParam: string | undefined }) {
  const { t } = useTranslation();
  const headingId = useId();
  const sideId = useId();
  const findId = useId();
  const location = useLocation();
  const navigate = useNavigate();
  const [search, setSearch, liveUrl] = useLiveSearchParams();
  const focusView = parseFocus(search);
  const anchorId = parseMessageAnchor(search);
  const returnMessageId = anchorId === null ? null : quoteReturnMessageId(location.state);
  const paneRef = useRef<HTMLDivElement>(null);
  const findTriggerRef = useRef<HTMLSpanElement>(null);
  const number = parseThreadNumber(numberParam);
  const mountedThreadRef = useRef<string | null>(null);
  useLayoutEffect(() => {
    const key = number === null ? null : `${room.id}/${number}`;
    mountedThreadRef.current = key;
    return () => {
      if (mountedThreadRef.current === key) mountedThreadRef.current = null;
    };
  }, [room.id, number]);
  const workflow = useRoomWorkflow(room.id, { refetchOnMount: false });
  // Whether THIS thread is pinned (the room's one pins query, shared with the pin button, the strip and the list's marks): a failed read marks nothing.
  const { threadNumbers: pinnedThreads } = useRoomPins(room.id);
  // The pin strip's expanded state is kept per person per browser.
  const [pinsOpen, setPinsOpen] = useStoredState("threadPane.pinsOpen", false, isBoolean);
  // The strip's "Show in thread" for the message the URL ALREADY names asks the conversation to jump again (it has landed there once); any other
  // message is a URL change, and the change is the jump.
  const [jumpSeq, setJumpSeq] = useState(0);
  const requestJump = useCallback((messageId: number) => (messageId === anchorId ? setJumpSeq((seq) => seq + 1) : undefined), [anchorId]);
  const [findState, setFindState] = useState<{ threadNumber: number; open: boolean } | null>(null);
  const focusPane = useCallback(() => paneRef.current?.focus(), []);
  // "Quote" on a message (1.3): the pane hands the picked message to the reply box (which keeps the draft per thread, like the text) and numbers the
  // request, so picking the same message twice, or after removing the quote, is a new request that moves the focus to the box again.
  const [quoteRequest, setQuoteRequest] = useState<QuoteRequest | null>(null);
  const [quoteDestination, setQuoteDestination] = useState<MessageResponse | null>(null);
  const quoteMessage = useCallback(
    (message: MessageResponse) => {
      if (number === null) return;
      const { id, authorId, authorName, body, mentions, groupMentions } = message;
      setQuoteRequest((prev) => ({
        threadNumber: number,
        quote: { id, authorId, authorName, body, mentions, groupMentions, roomId: room.id, threadNumber: message.threadNumber },
        seq: (prev?.seq ?? 0) + 1,
      }));
    },
    [number, room.id],
  );
  // The caller's own just-posted message (and its thread, so a request never outlives a navigation): the list scrolls to the bottom once it shows it.
  const [scrollRequest, setScrollRequest] = useState<{ threadNumber: number; messageId: number } | null>(null);
  // A pin or watch failure, kept with the thread it belongs to and drawn in a full-width row of the header (outside the icon group).
  const [actionError, setActionError] = useState<{ number: number; message: string } | null>(null);
  const reportActionError = useCallback((message: string | null) => setActionError(message === null || number === null ? null : { number, message }), [number]);
  const clearScrollRequest = useCallback(() => setScrollRequest(null), [setScrollRequest]);
  // The side panel takes room only when asked for: collapsed by default at every width, one switch per person per browser (1.2 D4).
  const [detailsOpen, setDetailsOpen] = useStoredState("threadPane.detailsOpen", false, isBoolean);
  // The header's "+N": open the details (or leave them open) and bring the Hashtags section into view once the panel is displayed.
  const showSection = useCallback(
    (section: "hashtags" | "tags") => {
      setDetailsOpen(true);
      window.setTimeout(() => document.getElementById(sideId)?.querySelector(`[data-section=${section}]`)?.scrollIntoView?.({ block: "nearest" }), 0);
    },
    [setDetailsOpen, sideId],
  );
  const showAllHashtags = useCallback(() => showSection("hashtags"), [showSection]);
  const showAllTags = useCallback(() => showSection("tags"), [showSection]);
  const { data: thread, isLoading, isFetching, error } = useQuery({
    queryKey: roomKeys.threadDetail(room.id, number),
    queryFn: () => getThread(room.id, number!),
    enabled: number !== null,
  });
  // Keyed by the loaded thread's id (not the object), so a background refetch never pulls focus back.
  const loadedId = thread?.id ?? null;
  const navigationType = useNavigationType();
  const focusRequested = wantsThreadPaneFocus(location.state) && navigationType !== "REPLACE" && anchorId === null;
  useEffect(() => {
    if (focusRequested && loadedId !== null) paneRef.current?.focus();
  }, [focusRequested, location.key, loadedId]);
  // A 404 wins over a copy cached from an earlier visit (the room lost the thread's reader since).
  const notFound = number === null || (error instanceof ApiError && error.status === 404);
  const returnToLatest = useCallback(() => {
    if (!liveUrl.params().has("message")) return;
    const state = liveUrl.state();
    setSearch((previous) => {
      const next = new URLSearchParams(previous);
      next.delete("message");
      return next;
    }, { replace: true, state: quoteReturnMessageId(state) === null ? state : null });
  }, [liveUrl, setSearch]);
  const returnToReply = useCallback(() => {
    const target = quoteReturnLocation(liveUrl.state());
    if (target === null) return;
    if (target.roomId !== room.id || target.threadNumber !== number) {
      void navigate(
        messageJumpPath(target.roomId, target.threadNumber, target.messageId, listRouteSearch(location.pathname, location.search), focusView),
        { replace: true, state: null },
      );
      return;
    }
    setSearch((previous) => {
      const next = new URLSearchParams(previous);
      next.set("message", String(target.messageId));
      return next;
    }, { replace: true, state: null });
  }, [focusView, liveUrl, location.pathname, location.search, navigate, number, room.id, setSearch]);
  // In the focus view the list is hidden: a pane with no thread to show carries its own way back.
  const showList = focusView ? (
    <Button variant="default" size="xs" style={{ alignSelf: "flex-start" }} onClick={() => setSearch((prev) => writeFocus(prev, false), { replace: true })}>
      {t("threads.focus.off")}
    </Button>
  ) : null;

  if (notFound) {
    return (
      <Stack gap="sm">
        <EmptyState icon={IconMessageOff} label={t("threads.pane.notFound")} />
        {showList}
      </Stack>
    );
  }
  if (!thread) {
    return isLoading ? (
      <LoadingBlock />
    ) : (
      <Stack gap="sm">
        <ErrorAlert error={error} title={t("threads.pane.loadFailed")} />
        {showList}
      </Stack>
    );
  }

  const findOpen = findState?.threadNumber === thread.number && findState.open;
  const mergedInto = thread.mergedIntoThreadNumber ?? null;
  return (
    <Box
      component="section"
      ref={paneRef}
      tabIndex={-1}
      aria-labelledby={headingId}
      className={classes.pane}
      data-side={detailsOpen ? "open" : "closed"}
      data-find-open={findOpen || undefined}
    >
      <Stack gap={5} className={classes.head}>
        {workflow.isError && <ErrorAlert error={workflow.error} title={t("threads.pane.workflowFailed")} />}
        <div className={classes.summary}>
          <Title order={3} id={headingId} className={classes.title}>
            <span className={classes.number}>{`#${thread.number}`}</span>{" "}{thread.title}
          </Title>
          <Group className={classes.actions} gap={4} align="center" wrap="nowrap" role="group" aria-label={t("threads.pane.actionsAria")}>
            <span className={classes.focusAction}>
              <HeaderIconButton
                label={t("threads.focus.on")}
                pressed={focusView}
                icon={focusView ? <IconLayoutSidebarLeftExpand size={17} /> : <IconLayoutSidebarLeftCollapse size={17} />}
                onClick={() => setSearch((prev) => writeFocus(prev, !parseFocus(prev)), { replace: true })}
              />
            </span>
            <ThreadPinButton room={room} threadNumber={thread.number} onError={reportActionError} />
            {mergedInto === null && <ThreadWatchButton room={room} thread={thread} onError={reportActionError} />}
            {mergedInto === null && room.role !== null && <ThreadMuteButton roomId={room.id} number={thread.number} muted={thread.muted ?? false} onError={reportActionError} />}
            <ThreadLifecycleActions
              key={thread.id}
              room={room}
              thread={thread}
              onDeleted={() => {
                if (mountedThreadRef.current !== `${room.id}/${thread.number}`) return;
                void navigate(withListParams(roomPath(room.id), liveUrl.params()), { replace: true });
              }}
              onMerged={(target) => {
                if (mountedThreadRef.current !== `${room.id}/${thread.number}`) return;
                void navigate(withListParams(threadPath(room.id, target), liveUrl.params()), { replace: true });
              }}
            />
            <span ref={findTriggerRef}>
              <HeaderIconButton
                label={t("threads.find.open")}
                expanded={findOpen}
                controls={findId}
                icon={<IconSearch size={17} />}
                onClick={() => setFindState((current) => ({
                  threadNumber: thread.number,
                  open: current?.threadNumber !== thread.number || !current.open,
                }))}
              />
            </span>
            <HeaderIconButton
              label={t("threads.side.toggle")}
              expanded={detailsOpen}
              controls={sideId}
              icon={detailsOpen ? <IconLayoutSidebarRightCollapse size={16} /> : <IconLayoutSidebarRightExpand size={16} />}
              onClick={() => setDetailsOpen(!detailsOpen)}
            />
          </Group>
          <Group className={classes.meta} gap="0.25rem 0.625rem">
            {pinnedThreads.has(thread.number) && <PinMark size="sm" />}
            <Text size="xs" c="dimmed">
              {fillSlots(t("threads.pane.owner", { name: slot("name") }), { name: <PersonLink id={thread.ownerId} name={thread.ownerName} inherit /> })}
            </Text>
            <Text size="xs" c="dimmed">
              {t("threads.row.messages", { count: thread.messageCount })}
            </Text>
          </Group>
        </div>
        <ThreadStatusControl key={`${room.id}/${thread.number}`} room={room} thread={thread} statuses={workflow.data?.statuses} />
        {mergedInto !== null && <MergedThreadBanner roomId={room.id} targetNumber={mergedInto} />}
        <MergedThreadSources key={thread.id} roomId={room.id} targetNumber={thread.number} count={thread.mergedSourceCount ?? 0} />
        {actionError?.number === thread.number && <FormErrorAlert>{actionError.message}</FormErrorAlert>}
        {(thread.tags.length > 0 || thread.hashtags.length > 0) && (
          <div className={classes.labels}>
            {thread.tags.length > 0 && (
              <div role="group" aria-label={t("threads.tags.aria")} className={classes.tagRow}>
                <TagPills tags={thread.tags} limit={HEADER_TAGS} onShowAll={showAllTags} />
              </div>
            )}
            {thread.hashtags.length > 0 && (
              <div className={classes.hashtagRow}>
                <ThreadHashtags roomId={room.id} number={thread.number} hashtags={thread.hashtags} limit={HEADER_HASHTAGS} onShowAll={showAllHashtags} />
              </div>
            )}
          </div>
        )}
        <ThreadPinStrip room={room} threadNumber={thread.number} open={pinsOpen} onOpenChange={setPinsOpen} onShow={requestJump} focusPane={focusPane} />
        <div id={findId} className={classes.find}>
          <ThreadMessageSearch
            key={`${room.id}/${thread.number}`}
            roomId={room.id}
            threadNumber={thread.number}
            open={findOpen}
            triggerRef={findTriggerRef}
            locationSearch={location.search}
            focusView={focusView}
            onClose={() => setFindState({ threadNumber: thread.number, open: false })}
            onSelect={requestJump}
            statuses={workflow.data?.statuses}
          />
        </div>
      </Stack>
      <div id={sideId} className={classes.side} data-open={detailsOpen} role="region" aria-label={t("threads.side.aria")} tabIndex={0}>
        <ThreadSidePanel key={`${room.id}/${thread.number}`} room={room} thread={thread} showHistory={detailsOpen} />
      </div>
      <Stack gap="xs" className={classes.conversation}>
        <MessageList
          key={`${room.id}/${thread.number}`}
          room={room}
          thread={thread}
          scrollToId={scrollRequest?.threadNumber === thread.number ? scrollRequest.messageId : null}
          onScrolled={clearScrollRequest}
          anchorId={anchorId}
          layoutKey={`${detailsOpen}/${pinsOpen}/${focusView}`}
          jumpSeq={jumpSeq}
          threadSettled={!isFetching}
          readingPaused={findOpen}
          onQuote={quoteMessage}
          onQuoteElsewhere={setQuoteDestination}
          onShowQuoted={requestJump}
          returnMessageId={returnMessageId}
          onReturnToReply={returnToReply}
          onReturnToLatest={returnToLatest}
        />
        {mergedInto === null && <MessageComposer
          room={room}
          thread={thread}
          onPosted={(messageId) => {
            if (mountedThreadRef.current !== `${room.id}/${thread.number}`) return;
            returnToLatest();
            setScrollRequest({ threadNumber: thread.number, messageId });
          }}
          quoteRequest={quoteRequest}
        />}
      </Stack>
      {quoteDestination && (
        <QuoteDestinationModal key={quoteDestination.id} source={quoteDestination} currentRoom={room} onClose={() => setQuoteDestination(null)} />
      )}
    </Box>
  );
}
