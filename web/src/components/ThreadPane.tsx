import { useCallback, useEffect, useId, useRef, useState } from "react";
import { useLocation, useNavigationType } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { Box, Button, Group, Stack, Text, Title } from "@mantine/core";
import { useQuery } from "@tanstack/react-query";
import { IconChevronDown, IconChevronRight, IconLayoutSidebarLeftCollapse, IconLayoutSidebarLeftExpand, IconMessageOff } from "@tabler/icons-react";
import { ApiError } from "../api/http";
import type { RoomResponse } from "../api/rooms";
import { getThread } from "../api/threads";
import { useLiveSearchParams } from "../hooks/useLiveSearchParams";
import { useRoomWorkflow } from "../hooks/useRoomWorkflow";
import { browserTimeZone, formatDateTime } from "../utils/formatDate";
import { parseThreadNumber, wantsThreadPaneFocus } from "../utils/roomLinks";
import { parseFocus, parseMessageAnchor, writeFocus } from "../utils/threadListParams";
import EmptyState from "./EmptyState";
import ErrorAlert from "./ErrorAlert";
import LoadingBlock from "./LoadingBlock";
import MessageComposer from "./MessageComposer";
import MessageList from "./MessageList";
import ThreadPinButton from "./ThreadPinButton";
import ThreadSidePanel from "./ThreadSidePanel";
import ThreadStatusPill from "./ThreadStatusPill";
import classes from "./ThreadPane.module.css";
import { roomKeys } from "../utils/roomKeys";

/**
 * The room view's conversation pane for `/rooms/:id/threads/:number`: the thread's header — `#42 Title` as the pane's heading, its
 * status, owner, message count and when it started and last moved (the browser's time zone) — then the conversation (`MessageList`, newest at
 * the bottom) and the reply box (`MessageComposer`, or the note that says why there is none). The side panel (`ThreadSidePanel`: Status, Owner, Details, History) is part of the pane, placed beside the conversation from a wide main area and behind a "Details" toggle below it (`ThreadPane.module.css`). A number that names no thread (not a plain positive number, or a 404) reads as one "does not exist". The section is
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
  // The caller's own just-posted message (and its thread, so a request never outlives a navigation): the list scrolls to the bottom once it shows it.
  const [scrollRequest, setScrollRequest] = useState<{ threadNumber: number; messageId: number } | null>(null);
  const clearScrollRequest = useCallback(() => setScrollRequest(null), [setScrollRequest]);
  // Below the three-column width the side panel folds behind a "Details" toggle (collapsed by default); at that width it is always shown.
  const [detailsOpen, setDetailsOpen] = useState(false);
  const { data: thread, isLoading, error } = useQuery({
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
    <Box component="section" ref={paneRef} tabIndex={-1} aria-labelledby={headingId} className={classes.pane}>
      <Stack gap="xs" className={classes.head}>
        {workflow.isError && <ErrorAlert error={workflow.error} title={t("threads.pane.workflowFailed")} />}
        <Group justify="space-between" align="flex-start" wrap="nowrap" gap="sm">
          <Title order={3} id={headingId} miw={0} style={{ overflowWrap: "anywhere" }}>
            {`#${thread.number} ${thread.title}`}
          </Title>
          <ThreadStatusPill statuses={workflow.data?.statuses} statusKey={thread.status} />
        </Group>
        <Group gap="md" wrap="wrap">
          <Text size="sm" c="dimmed">
            {t("threads.pane.owner", { name: thread.ownerName })}
          </Text>
          <Text size="sm" c="dimmed">
            {t("threads.row.messages", { count: thread.messageCount })}
          </Text>
          <Text size="sm" c="dimmed">
            {t("threads.pane.started", { name: thread.createdByName, when: formatDateTime(thread.createdAt, zone) })}
          </Text>
          <Text size="sm" c="dimmed">
            {t("threads.pane.lastActivity", { when: formatDateTime(thread.lastActivityAt, zone) })}
          </Text>
        </Group>
        <Group gap="xs" wrap="wrap">
          <Button
            variant={focusView ? "light" : "default"}
            size="xs"
            aria-pressed={focusView}
            leftSection={focusView ? <IconLayoutSidebarLeftExpand size={14} /> : <IconLayoutSidebarLeftCollapse size={14} />}
            onClick={() => setSearch((prev) => writeFocus(prev, !parseFocus(prev)), { replace: true })}
          >
            {t("threads.focus.on")}
          </Button>
          <ThreadPinButton room={room} threadNumber={thread.number} />
          <Button
            className={classes.toggle}
            variant="default"
            size="xs"
            aria-expanded={detailsOpen}
            aria-controls={sideId}
            leftSection={detailsOpen ? <IconChevronDown size={14} /> : <IconChevronRight size={14} />}
            onClick={() => setDetailsOpen((open) => !open)}
          >
            {t("threads.side.toggle")}
          </Button>
        </Group>
      </Stack>
      <div id={sideId} className={classes.side} data-open={detailsOpen}>
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
        />
        <MessageComposer room={room} thread={thread} onPosted={(messageId) => setScrollRequest({ threadNumber: thread.number, messageId })} />
      </Stack>
    </Box>
  );
}
