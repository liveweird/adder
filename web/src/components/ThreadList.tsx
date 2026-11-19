import { useEffect } from "react";
import { Link as RouterLink, useLocation } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { Button, Group, SegmentedControl, Select, Stack, Text } from "@mantine/core";
import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { IconMessages, IconPlus } from "@tabler/icons-react";
import { listThreads } from "../api/threads";
import type { RoomResponse } from "../api/rooms";
import { useRoomWorkflow } from "../hooks/useRoomWorkflow";
import { useThreadListParams } from "../hooks/useThreadListParams";
import { browserTimeZone, formatDateTime } from "../utils/formatDate";
import { focusThreadPaneState, newThreadPath, threadPath, withListParams } from "../utils/roomLinks";
import { threadStartBlock } from "../utils/threadAccess";
import classes from "./ThreadList.module.css";
import EmptyState from "./EmptyState";
import ErrorAlert from "./ErrorAlert";
import LoadingBlock from "./LoadingBlock";
import PaginationBar from "./PaginationBar";
import ThreadStatusPill from "./ThreadStatusPill";
import { roomKeys } from "../utils/roomKeys";
import { ORDER_SORT, ORDERS, SCOPE_FILTER, SCOPES, type Order, type Scope } from "../utils/threadListParams";

/** The rail shows 50 threads a page (the server's cap is 100); the page-size picker is left out, the rail is too narrow for it. */
const THREAD_PAGE_SIZE = 50;

/**
 * The room view's left pane: the room's threads as a labelled `nav` of whole-row links (number, title, status, owner, message count,
 * last activity), Open by default and most recently active first, with the Open/Closed/All filter, the order, a pager and, for a
 * member of a live room, "New thread". The open thread is marked with `aria-current`. The state (scope, order, page) lives in the URL
 * (`useThreadListParams`), so a change is one write that also resets the page: a changed filter reads page 1 from its very first request.
 * Every link out of the list keeps the list's keys, so opening a thread never loses the reader's place.
 */
export default function ThreadList({ room, activeNumber }: { room: RoomResponse; activeNumber: number | null }) {
  const { t } = useTranslation();
  const { params, update } = useThreadListParams(room.id);
  const { scope, order, page } = params;
  const { search } = useLocation();
  const workflow = useRoomWorkflow(room.id);
  const zone = browserTimeZone();

  // Under the ["rooms"] prefix: a thread written anywhere in the room (and the rail's refresh) re-reads the page.
  const { data, isLoading, isError, error, isPlaceholderData } = useQuery({
    queryKey: [...roomKeys.threadList(room.id), page, THREAD_PAGE_SIZE, ORDER_SORT[order], scope],
    queryFn: () => listThreads(room.id, { page, pageSize: THREAD_PAGE_SIZE, sort: ORDER_SORT[order], closed: SCOPE_FILTER[scope] }),
    placeholderData: keepPreviousData,
  });
  // The page was emptied under the reader: step back to the first.
  const emptied = !!data && !isPlaceholderData && data.items.length === 0 && data.total > 0 && page > 1;
  useEffect(() => {
    if (emptied) update({ page: 1 });
  }, [emptied, update]);
  const canStart = threadStartBlock(room) === null;
  // Starting a thread is no way to fill the Closed list: it says how threads get there instead.
  const emptyHint = scope === "closed" ? t("threads.empty.hintClosed") : canStart ? t("threads.empty.hint") : undefined;

  return (
    <Stack gap="sm">
      <Group justify="space-between" gap="xs" wrap="wrap">
        <SegmentedControl
          size="xs"
          aria-label={t("threads.scope.aria")}
          value={scope}
          onChange={(value) => update({ scope: value as Scope })}
          data={SCOPES.map((value) => ({ value, label: t(`threads.scope.${value}`) }))}
        />
        {canStart && (
          <Button component={RouterLink} to={withListParams(newThreadPath(room.id), search)} size="compact-sm" leftSection={<IconPlus size={14} />}>
            {t("threads.newThread")}
          </Button>
        )}
      </Group>
      <Select
        size="xs"
        aria-label={t("threads.order.aria")}
        data={ORDERS.map((value) => ({ value, label: t(`threads.order.${value}`) }))}
        value={order}
        onChange={(value) => value && update({ order: value as Order })}
        allowDeselect={false}
      />
      {isError && <ErrorAlert error={error} title={t("threads.loadFailed")} />}
      {(isLoading || (isPlaceholderData && data?.items.length === 0)) && <LoadingBlock />}
      {/* The previous filter's rows stay (dimmed) while the new one loads, and an empty state is only ever the answer to the CURRENT filter. */}
      {data && data.items.length === 0 && !isError && !isPlaceholderData && (
        <EmptyState icon={IconMessages} label={t(`threads.empty.${scope}`)} hint={emptyHint} />
      )}
      {data && data.items.length > 0 && (
        <nav aria-label={t("threads.listAria")} aria-busy={isPlaceholderData} className={isPlaceholderData ? classes.stale : undefined}>
          <ul className={classes.list}>
            {data.items.map((thread) => (
              <li key={thread.id}>
                <RouterLink
                  to={withListParams(threadPath(room.id, thread.number), search)}
                  state={focusThreadPaneState}
                  className={classes.row}
                  aria-current={thread.number === activeNumber ? "page" : undefined}
                >
                  <Stack gap={4}>
                    <Text size="sm" fw={500} lh={1.3}>
                      <Text span c="dimmed" inherit>{`#${thread.number}`}</Text> {thread.title}
                    </Text>
                    <Group gap="xs">
                      <ThreadStatusPill statuses={workflow.data?.statuses} statusKey={thread.status} size="sm" />
                      <Text size="xs" c="dimmed">
                        {thread.ownerName}
                      </Text>
                    </Group>
                    <Text size="xs" c="dimmed">
                      {t("threads.row.messages", { count: thread.messageCount })} · {formatDateTime(thread.lastActivityAt, zone)}
                    </Text>
                  </Stack>
                </RouterLink>
              </li>
            ))}
          </ul>
        </nav>
      )}
      {data && data.total > THREAD_PAGE_SIZE && <PaginationBar total={data.total} page={page} pageSize={THREAD_PAGE_SIZE} onPageChange={(next) => update({ page: next })} />}
    </Stack>
  );
}
