import { useEffect, useId, useMemo } from "react";
import { Link as RouterLink, useLocation } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { Button, Group, SegmentedControl, Select, Stack, Text, VisuallyHidden } from "@mantine/core";
import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { IconAt, IconEyeFilled, IconMessages, IconPinFilled, IconPlus } from "@tabler/icons-react";
import { listThreads } from "../api/threads";
import type { RoomResponse } from "../api/rooms";
import { useRoomPins } from "../hooks/useRoomPins";
import { useRoomWorkflow } from "../hooks/useRoomWorkflow";
import { useAddressedNotifications } from "../hooks/useUnseenNotifications";
import { useThreadListParams } from "../hooks/useThreadListParams";
import { browserTimeZone, formatDateTime } from "../utils/formatDate";
import { focusThreadPaneState, newThreadPath, threadPath, withListParams } from "../utils/roomLinks";
import { threadStartBlock } from "../utils/threadAccess";
import { UNREAD_COUNT_CAP, unreadBadge, unseenThreadNumbers } from "../utils/unread";
import classes from "./ThreadList.module.css";
import EmptyState from "./EmptyState";
import ErrorAlert from "./ErrorAlert";
import LoadingBlock from "./LoadingBlock";
import PaginationBar from "./PaginationBar";
import ThreadStatusPill from "./ThreadStatusPill";
import UnreadCue from "./UnreadCue";
import { roomKeys } from "../utils/roomKeys";
import { activeFilterCount, ORDERS, pruneFieldEntries, SCOPES, toApiQuery, type Order, type Scope } from "../utils/threadListParams";
import { getUserId } from "../api/session";
import ThreadFilters from "./ThreadFilters";

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
  const { params, update, clear } = useThreadListParams(room.id);
  const { scope, order, page } = params;
  const { search } = useLocation();
  const workflow = useRoomWorkflow(room.id);
  // A pinned thread's row carries a mark; "is it pinned" is the room's pins query (shared with the header and the pane), a failed read marks nothing.
  const pins = useRoomPins(room.id);
  // A row says in words what its marks mean (a pin, a watch, unread messages, an unseen notice) through one hidden description.
  const noteId = useId();
  // The unseen notices ADDRESSED to the caller: a thread that holds one gets an `@` mark (a mention of any kind or a hand-over; a reply or a
  // new thread never does, 0.6 D1).
  const addressed = useAddressedNotifications();
  const noticeThreads = useMemo(() => unseenThreadNumbers(addressed.items, room.id), [addressed.items, room.id]);
  const zone = browserTimeZone();

  // The request: owner=me resolved to the session's id, a stale field entry dropped; null while field filters wait for the workflow.
  const me = getUserId();
  const filters = toApiQuery(params, workflow.data, me);
  // Once the workflow is known, field entries it cannot judge (a retired field, a value of the wrong shape) leave the URL and the remembered
  // query too, by replace and on the same page: the request already ignores them, so the badge, Clear and the 20-entry cap must not count them.
  const prunedFields = useMemo(() => (workflow.data ? pruneFieldEntries(params.field, workflow.data) : null), [params.field, workflow.data]);
  useEffect(() => {
    if (prunedFields !== null && prunedFields.length !== params.field.length) update({ field: prunedFields, page: params.page });
  }, [prunedFields, params.field.length, params.page, update]);
  const waiting = filters === null;
  const filterCount = filters === null ? 0 : activeFilterCount(filters);
  // Under the ["rooms"] prefix: a thread written anywhere in the room (and the rail's refresh) re-reads the page.
  const { data, isLoading, isError, error, isPlaceholderData } = useQuery({
    queryKey: [...roomKeys.threadList(room.id), page, THREAD_PAGE_SIZE, filters],
    queryFn: () => listThreads(room.id, { page, pageSize: THREAD_PAGE_SIZE, ...filters }),
    enabled: filters !== null,
    placeholderData: keepPreviousData,
  });
  // The page was emptied under the reader: step back to the first.
  const emptied = !!data && !isPlaceholderData && data.items.length === 0 && data.total > 0 && page > 1;
  useEffect(() => {
    if (emptied) update({ page: 1 });
  }, [emptied, update]);
  const canStart = threadStartBlock(room) === null;
  // Starting a thread is no way to fill the Closed list: it says how threads get there instead.
  const emptyHint = filterCount > 0 ? undefined : scope === "closed" ? t("threads.empty.hintClosed") : canStart ? t("threads.empty.hint") : undefined;

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
      <ThreadFilters roomId={room.id} me={me} workflow={workflow.data} params={params} update={update} clear={clear} activeCount={filterCount} />
      {isError && <ErrorAlert error={error} title={t("threads.loadFailed")} />}
      {/* Field filters cannot be judged before the workflow is read: a failed read says so instead of spinning for ever. */}
      {waiting && workflow.isError && <ErrorAlert error={workflow.error} title={t("threads.loadFailed")} />}
      {(isLoading || (waiting && !workflow.isError) || (isPlaceholderData && data?.items.length === 0)) && <LoadingBlock />}
      {/* The previous filter's rows stay (dimmed) while the new one loads, and an empty state is only ever the answer to the CURRENT filter. */}
      {data && data.items.length === 0 && !isError && !isPlaceholderData && (
        <EmptyState icon={IconMessages} label={t(filterCount > 0 ? "threads.empty.filtered" : `threads.empty.${scope}`)} hint={emptyHint} />
      )}
      {data && data.items.length > 0 && (
        <nav aria-label={t("threads.listAria")} aria-busy={isPlaceholderData} className={isPlaceholderData ? classes.stale : undefined}>
          <ul className={classes.list}>
            {data.items.map((thread) => {
              const unread = thread.unreadCount > 0;
              const notice = noticeThreads.has(thread.number);
              const pinned = pins.threadNumbers.has(thread.number);
              const watching = thread.watching;
              const note = [
                unread && t(thread.unreadCount >= UNREAD_COUNT_CAP ? "threads.unread.rowCap" : "threads.unread.row", { count: thread.unreadCount }),
                notice && t("threads.unread.notice"),
                pinned && t("threads.pin.mark"),
                watching && t("threads.watch.mark"),
              ]
                .filter(Boolean)
                .join(". ");
              const describedBy = note === "" ? undefined : `${noteId}-${thread.number}`;
              return (
                <li key={thread.id}>
                  <RouterLink
                    to={withListParams(threadPath(room.id, thread.number), search)}
                    state={focusThreadPaneState}
                    className={classes.row}
                    aria-current={thread.number === activeNumber ? "page" : undefined}
                    aria-describedby={describedBy}
                  >
                    <Stack gap={4}>
                      <Group justify="space-between" align="flex-start" wrap="nowrap" gap="xs">
                        <Text size="sm" fw={unread ? 700 : 500} lh={1.3} miw={0}>
                          <Text span c="dimmed" inherit>{`#${thread.number}`}</Text> {thread.title}
                          {pinned && (
                            <>
                              {" "}
                              <IconPinFilled size={12} aria-hidden="true" />
                            </>
                          )}
                          {watching && (
                            <>
                              {" "}
                              <IconEyeFilled size={12} aria-hidden="true" />
                            </>
                          )}
                        </Text>
                        {(unread || notice) && (
                          <Group gap={4} wrap="nowrap" aria-hidden="true">
                            {notice && <IconAt size={14} color="var(--adder-unread-fill)" />}
                            {unread && <UnreadCue>{unreadBadge(thread.unreadCount)}</UnreadCue>}
                          </Group>
                        )}
                      </Group>
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
                  {describedBy !== undefined && <VisuallyHidden id={describedBy}>{note}</VisuallyHidden>}
                </li>
              );
            })}
          </ul>
        </nav>
      )}
      {data && data.total > THREAD_PAGE_SIZE && <PaginationBar total={data.total} page={page} pageSize={THREAD_PAGE_SIZE} onPageChange={(next) => update({ page: next })} />}
    </Stack>
  );
}
