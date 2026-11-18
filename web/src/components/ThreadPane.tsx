import { useEffect, useId, useRef } from "react";
import { useLocation } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { Group, Stack, Text, Title } from "@mantine/core";
import { useQuery } from "@tanstack/react-query";
import { IconMessageOff } from "@tabler/icons-react";
import { ApiError } from "../api/http";
import type { RoomResponse } from "../api/rooms";
import { getThread } from "../api/threads";
import { useRoomWorkflow } from "../hooks/useRoomWorkflow";
import { browserTimeZone, formatDateTime } from "../utils/formatDate";
import { parseThreadNumber, wantsThreadPaneFocus } from "../utils/roomLinks";
import EmptyState from "./EmptyState";
import ErrorAlert from "./ErrorAlert";
import LoadingBlock from "./LoadingBlock";
import ThreadStatusPill from "./ThreadStatusPill";

/**
 * The room view's conversation pane for `/rooms/:id/threads/:number`: today the thread's header — `#42 Title` as the pane's heading, its
 * status, owner, message count and when it started and last moved (the browser's time zone). The messages and the side panel arrive
 * in the next steps. A number that names no thread (not a plain positive number, or a 404) reads as one "does not exist". The section is
 * programmatically focusable and takes focus once its thread has loaded after a navigation that asked for it (a list row, a started
 * thread — `wantsThreadPaneFocus`), the `RoomWorkflowForm` precedent: the clicked row is left behind in the list.
 */
export default function ThreadPane({ room, numberParam }: { room: RoomResponse; numberParam: string | undefined }) {
  const { t } = useTranslation();
  const headingId = useId();
  const location = useLocation();
  const paneRef = useRef<HTMLDivElement>(null);
  const number = parseThreadNumber(numberParam);
  const workflow = useRoomWorkflow(room.id);
  const { data: thread, isLoading, error } = useQuery({
    queryKey: ["rooms", "threads", room.id, "detail", number],
    queryFn: () => getThread(room.id, number!),
    enabled: number !== null,
  });
  // Keyed by the loaded thread's id (not the object), so a background refetch never pulls focus back.
  const loadedId = thread?.id ?? null;
  const focusRequested = wantsThreadPaneFocus(location.state);
  useEffect(() => {
    if (focusRequested && loadedId !== null) paneRef.current?.focus();
  }, [focusRequested, location.key, loadedId]);
  // A 404 wins over a copy cached from an earlier visit (the room lost the thread's reader since).
  const notFound = number === null || (error instanceof ApiError && error.status === 404);

  if (notFound) return <EmptyState icon={IconMessageOff} label={t("threads.pane.notFound")} />;
  if (!thread) return isLoading ? <LoadingBlock /> : <ErrorAlert error={error} title={t("threads.pane.loadFailed")} />;

  const zone = browserTimeZone();
  return (
    <Stack component="section" ref={paneRef} tabIndex={-1} aria-labelledby={headingId} gap="xs">
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
    </Stack>
  );
}
