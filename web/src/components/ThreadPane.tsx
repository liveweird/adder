import { useCallback, useEffect, useId, useRef, useState } from "react";
import { useLocation, useNavigationType } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { Box, Button, Group, Stack, Text, Title } from "@mantine/core";
import { useQuery } from "@tanstack/react-query";
import {
  IconLayoutSidebarLeftCollapse,
  IconLayoutSidebarLeftExpand,
  IconLayoutSidebarRightCollapse,
  IconLayoutSidebarRightExpand,
  IconMessageOff,
} from "@tabler/icons-react";
import { ApiError } from "../api/http";
import type { RoomResponse } from "../api/rooms";
import { getThread } from "../api/threads";
import { useLiveSearchParams } from "../hooks/useLiveSearchParams";
import { isBoolean, useStoredState } from "../hooks/useStoredState";
import { useRoomPins } from "../hooks/useRoomPins";
import { useRoomWorkflow } from "../hooks/useRoomWorkflow";
import { browserTimeZone, formatDateTime } from "../utils/formatDate";
import { messagePinsOf } from "../utils/pins";
import { fillSlots, slot } from "../utils/slots";
import { parseThreadNumber, wantsThreadPaneFocus } from "../utils/roomLinks";
import { parseFocus, parseMessageAnchor, writeFocus } from "../utils/threadListParams";
import EmptyState from "./EmptyState";
import ErrorAlert from "./ErrorAlert";
import FormErrorAlert from "./FormErrorAlert";
import HeaderIconButton from "./HeaderIconButton";
import LoadingBlock from "./LoadingBlock";
import MessageComposer from "./MessageComposer";
import MessageList from "./MessageList";
import PersonLink from "./PersonLink";
import PinMark from "./PinMark";
import ThreadHashtags from "./ThreadHashtags";
import ThreadPinButton from "./ThreadPinButton";
import ThreadPinStrip from "./ThreadPinStrip";
import ThreadSidePanel from "./ThreadSidePanel";
import ThreadStatusPill from "./ThreadStatusPill";
import ThreadWatchButton from "./ThreadWatchButton";
import classes from "./ThreadPane.module.css";
import { roomKeys } from "../utils/roomKeys";

/** The header's third row shows at most this many hashtags, on ONE line (`ThreadHashtags.module.css`); the rest are one "+N" that opens the details (whose "Hashtags" section holds them all). */
const HEADER_HASHTAGS = 4;

/**
 * The room view's conversation pane for `/rooms/:id/threads/:number`: the thread's header — `#42 Title` as the pane's heading, its
 * status, owner, message count and when it started and last moved (the browser's time zone) — then the conversation (`MessageList`, newest at
 * the bottom) and the reply box (`MessageComposer`, or the note that says why there is none). The header is two rows (the heading with the status pill and the icon actions — focus, pin, watch, details — then one dimmed line, and a third row of the thread's hashtags as filter links (1.2 A8) only when it has any). The side panel (`ThreadSidePanel`: Status, Owner, Details, History) is part of the pane and collapsed at every width until the header's "Details" button opens it (remembered per person per browser, 1.2 D4); open, it is placed beside the conversation from a wide main area and above it below that (`ThreadPane.module.css`). The owner and the starter in the dimmed line are links to their profiles (1.2 D6, `PersonLink`, in the sentence: `fillSlots`). A number that names no thread (not a plain positive number, or a 404) reads as one "does not exist". The section is
 * programmatically focusable and takes focus once its thread has loaded after a navigation that asked for it (a list row, a started
 * thread — `wantsThreadPaneFocus`), the `RoomWorkflowForm` precedent: the clicked row is left behind in the list. A `replace` (a filter
 * change, the focus toggle) never asks for it, and neither does a jump to a message (the conversation focuses that message).
 *
 * Two keys of the URL belong to the pane (`utils/threadListParams.ts`): `message=<id>` is the message the conversation jumps to (a search hit's
 * link) and `focus=1` is the focus view, which `RoomHome` reads to hide the thread list; the header's "Focus on this thread" toggle
 * (`aria-pressed`) writes it by `replace`, keeping the list's keys. The URL is the state, so a reload keeps it; nothing remembers it. A thread
 * that cannot be shown (missing, unreadable, failed) says so with its own "Show the thread list" button while the view is on, so it is never a dead end.
 */
export default function ThreadPane({ room, numberParam }: { room: RoomResponse; numberParam: string | undefined }) {
  const { t } = useTranslation();
  const headingId = useId();
  const sideId = useId();
  const location = useLocation();
  const [search, setSearch] = useLiveSearchParams();
  const focusView = parseFocus(search);
  const anchorId = parseMessageAnchor(search);
  const paneRef = useRef<HTMLDivElement>(null);
  const number = parseThreadNumber(numberParam);
  const workflow = useRoomWorkflow(room.id);
  // Whether THIS thread is pinned (the room's one pins query, shared with the pin button, the strip and the list's marks): a failed read marks nothing.
  const { threadNumbers: pinnedThreads, data: pins } = useRoomPins(room.id);
  // The pin strip's expanded state: one switch per person per browser, held HERE because the pane's layout answers to it (`data-strip`).
  const [pinsOpen, setPinsOpen] = useStoredState("threadPane.pinsOpen", false, isBoolean);
  // The strip's "Show in thread" for the message the URL ALREADY names asks the conversation to jump again (it has landed there once); any other
  // message is a URL change, and the change is the jump.
  const [jumpSeq, setJumpSeq] = useState(0);
  const requestJump = useCallback((messageId: number) => (messageId === anchorId ? setJumpSeq((seq) => seq + 1) : undefined), [anchorId]);
  const focusPane = useCallback(() => paneRef.current?.focus(), []);
  // The caller's own just-posted message (and its thread, so a request never outlives a navigation): the list scrolls to the bottom once it shows it.
  const [scrollRequest, setScrollRequest] = useState<{ threadNumber: number; messageId: number } | null>(null);
  // A pin or watch failure, kept with the thread it belongs to and drawn in a full-width row of the header (outside the icon group).
  const [actionError, setActionError] = useState<{ number: number; message: string } | null>(null);
  const reportActionError = useCallback((message: string | null) => setActionError(message === null || number === null ? null : { number, message }), [number]);
  const clearScrollRequest = useCallback(() => setScrollRequest(null), [setScrollRequest]);
  // The side panel takes room only when asked for: collapsed by default at every width, one switch per person per browser (1.2 D4).
  const [detailsOpen, setDetailsOpen] = useStoredState("threadPane.detailsOpen", false, isBoolean);
  // The header's "+N": open the details (or leave them open) and bring the Hashtags section into view once the panel is displayed.
  const showAllHashtags = useCallback(() => {
    setDetailsOpen(true);
    window.setTimeout(() => document.getElementById(sideId)?.querySelector("[data-section=hashtags]")?.scrollIntoView?.({ block: "nearest" }), 0);
  }, [setDetailsOpen, sideId]);
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

  const zone = browserTimeZone();
  return (
    <Box component="section" ref={paneRef} tabIndex={-1} aria-labelledby={headingId} className={classes.pane} data-side={detailsOpen ? "open" : "closed"} data-tags={thread.hashtags.length > 0 ? "true" : undefined} data-strip={messagePinsOf(pins?.items, thread.number).length > 0 ? (pinsOpen ? "open" : "closed") : undefined}>
      <Stack gap={4} className={classes.head}>
        {workflow.isError && <ErrorAlert error={workflow.error} title={t("threads.pane.workflowFailed")} />}
        <Group align="center" wrap="wrap" gap="xs">
          {/* Up to two lines, then an ellipsis; the heading's text (and so the region's name) stays the whole title. */}
          <Title order={3} id={headingId} className={classes.title}>
            {`#${thread.number} ${thread.title}`}
          </Title>
          <ThreadStatusPill statuses={workflow.data?.statuses} statusKey={thread.status} />
          <Group gap="xs" align="center" wrap="wrap" role="group" aria-label={t("threads.pane.actionsAria")}>
            <HeaderIconButton
              label={t("threads.focus.on")}
              pressed={focusView}
              icon={focusView ? <IconLayoutSidebarLeftExpand size={16} /> : <IconLayoutSidebarLeftCollapse size={16} />}
              onClick={() => setSearch((prev) => writeFocus(prev, !parseFocus(prev)), { replace: true })}
            />
            <ThreadPinButton room={room} threadNumber={thread.number} onError={reportActionError} />
            <ThreadWatchButton room={room} thread={thread} onError={reportActionError} />
            <HeaderIconButton
              label={t("threads.side.toggle")}
              expanded={detailsOpen}
              controls={sideId}
              icon={detailsOpen ? <IconLayoutSidebarRightCollapse size={16} /> : <IconLayoutSidebarRightExpand size={16} />}
              onClick={() => setDetailsOpen(!detailsOpen)}
            />
          </Group>
        </Group>
        {actionError?.number === thread.number && <FormErrorAlert>{actionError.message}</FormErrorAlert>}
        <Group gap="0 var(--mantine-spacing-md)" wrap="wrap" align="center">
          {/* In the dimmed line, not beside the status pill: in the first row the badge would wrap the icons onto a second row in a pane beside the open side panel (1280x720), and the composer's Send would leave the screen. */}
          {pinnedThreads.has(thread.number) && <PinMark size="sm" />}
          <Text size="xs" c="dimmed">
            {fillSlots(t("threads.pane.owner", { name: slot("name") }), { name: <PersonLink id={thread.ownerId} name={thread.ownerName} inherit /> })}
          </Text>
          <Text size="xs" c="dimmed">
            {t("threads.row.messages", { count: thread.messageCount })}
          </Text>
          <Text size="xs" c="dimmed">
            {fillSlots(t("threads.pane.started", { name: slot("name"), when: formatDateTime(thread.createdAt, zone) }), {
              name: <PersonLink id={thread.createdById} name={thread.createdByName} inherit />,
            })}
          </Text>
          <Text size="xs" c="dimmed">
            {t("threads.pane.lastActivity", { when: formatDateTime(thread.lastActivityAt, zone) })}
          </Text>
        </Group>
        {thread.hashtags.length > 0 && (
          <ThreadHashtags roomId={room.id} number={thread.number} hashtags={thread.hashtags} limit={HEADER_HASHTAGS} onShowAll={showAllHashtags} />
        )}
        <ThreadPinStrip room={room} threadNumber={thread.number} open={pinsOpen} onOpenChange={setPinsOpen} onShow={requestJump} focusPane={focusPane} />
      </Stack>
      <div id={sideId} className={classes.side} data-open={detailsOpen} role="region" aria-label={t("threads.side.aria")} tabIndex={0}>
        <ThreadSidePanel key={`${room.id}/${thread.number}`} room={room} thread={thread} />
      </div>
      <Stack gap="xs" className={classes.conversation}>
        <MessageList
          key={`${room.id}/${thread.number}`}
          room={room}
          thread={thread}
          scrollToId={scrollRequest?.threadNumber === thread.number ? scrollRequest.messageId : null}
          onScrolled={clearScrollRequest}
          anchorId={anchorId}
          jumpSeq={jumpSeq}
          threadSettled={!isFetching}
        />
        <MessageComposer room={room} thread={thread} onPosted={(messageId) => setScrollRequest({ threadNumber: thread.number, messageId })} />
      </Stack>
    </Box>
  );
}
