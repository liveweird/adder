import { useState } from "react";
import { Link as RouterLink } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { Button, Group, SegmentedControl, Select, Stack, Text } from "@mantine/core";
import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { IconMessages, IconPlus } from "@tabler/icons-react";
import { listThreads } from "../api/threads";
import type { RoomResponse } from "../api/rooms";
import { useRoomWorkflow } from "../hooks/useRoomWorkflow";
import { isOneOf, useStoredState } from "../hooks/useStoredState";
import { browserTimeZone, formatDateTime } from "../utils/formatDate";
import { focusThreadPaneState, newThreadPath, threadPath } from "../utils/roomLinks";
import { threadStartBlock } from "../utils/threadAccess";
import classes from "./ThreadList.module.css";
import EmptyState from "./EmptyState";
import ErrorAlert from "./ErrorAlert";
import LoadingBlock from "./LoadingBlock";
import PaginationBar from "./PaginationBar";
import ThreadStatusPill from "./ThreadStatusPill";

/** The rail shows 50 threads a page (the server's cap is 100); the page-size picker is left out, the rail is too narrow for it. */
const THREAD_PAGE_SIZE = 50;

const SCOPES = ["open", "closed", "all"] as const;
type Scope = (typeof SCOPES)[number];
/** `closed` is the list's only filter: open threads, closed ones, or both (omitted). */
const SCOPE_FILTER: Record<Scope, boolean | undefined> = { open: false, closed: true, all: undefined };

const ORDERS = ["activity", "newest", "number"] as const;
type Order = (typeof ORDERS)[number];
/** Recent activity and newest read from the top; a number counts up from #1. All three are on the server's sort whitelist. */
const ORDER_SORT: Record<Order, string> = { activity: "-lastActivityAt", newest: "-createdAt", number: "number" };

/** The view-settings namespace; each room has its own (`roomThreads.<id>`), so one room's scope or order never rearranges another's. */
const SETTINGS_KEY = "roomThreads";

/**
 * The room view's left pane: the room's threads as a labelled `nav` of whole-row links (number, title, status, owner, message count,
 * last activity), Open by default and most recently active first, with the Open/Closed/All filter, the order, a pager and, for a
 * member of a live room, "New thread". The open thread is marked with `aria-current`. The two choices persist per room like the other views'.
 * The page belongs to the (room, scope, order) it was chosen under: a changed filter reads page 1 from its very first request.
 */
export default function ThreadList({ room, activeNumber }: { room: RoomResponse; activeNumber: number | null }) {
  const { t } = useTranslation();
  const [scope, setScope] = useStoredState<Scope>(`${SETTINGS_KEY}.${room.id}.scope`, "open", isOneOf(SCOPES));
  const [order, setOrder] = useStoredState<Order>(`${SETTINGS_KEY}.${room.id}.order`, "activity", isOneOf(ORDERS));
  // Derived, not reset in an effect: an effect would run after the first request for the new filter had already gone out with the old page.
  const filterKey = `${room.id}|${scope}|${order}`;
  const [paging, setPaging] = useState({ filterKey, page: 1 });
  const page = paging.filterKey === filterKey ? paging.page : 1;
  const setPage = (next: number) => setPaging({ filterKey, page: next });
  const workflow = useRoomWorkflow(room.id);
  const zone = browserTimeZone();

  // Under the ["rooms"] prefix: a thread written anywhere in the room (and the rail's refresh) re-reads the page.
  const { data, isLoading, isError, error, isPlaceholderData } = useQuery({
    queryKey: ["rooms", "threads", room.id, "list", page, THREAD_PAGE_SIZE, ORDER_SORT[order], scope],
    queryFn: () => listThreads(room.id, { page, pageSize: THREAD_PAGE_SIZE, sort: ORDER_SORT[order], closed: SCOPE_FILTER[scope] }),
    placeholderData: keepPreviousData,
  });
  // The page was emptied under the reader: step back to the first.
  if (data && data.items.length === 0 && data.total > 0 && page > 1) setPage(1);
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
          onChange={(value) => setScope(value as Scope)}
          data={SCOPES.map((value) => ({ value, label: t(`threads.scope.${value}`) }))}
        />
        {canStart && (
          <Button component={RouterLink} to={newThreadPath(room.id)} size="compact-sm" leftSection={<IconPlus size={14} />}>
            {t("threads.newThread")}
          </Button>
        )}
      </Group>
      <Select
        size="xs"
        aria-label={t("threads.order.aria")}
        data={ORDERS.map((value) => ({ value, label: t(`threads.order.${value}`) }))}
        value={order}
        onChange={(value) => value && setOrder(value as Order)}
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
                  to={threadPath(room.id, thread.number)}
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
      {data && data.total > THREAD_PAGE_SIZE && <PaginationBar total={data.total} page={page} pageSize={THREAD_PAGE_SIZE} onPageChange={setPage} />}
    </Stack>
  );
}
