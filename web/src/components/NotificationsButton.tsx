import { useState, useSyncExternalStore, type MouseEvent } from "react";
import { useLocation } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { ActionIcon, Box, Button, Drawer, Group, SegmentedControl, Stack } from "@mantine/core";
import { IconBell, IconBellOff, IconChecks } from "@tabler/icons-react";
import { keepPreviousData, useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ADDRESSED_TYPES, listNotificationFeed, listNotifications, markAllNotificationsSeen, markNotificationSeen, markReplyGroupSeen, NOTIFICATION_TOTAL_CAP, type NotificationFeedItem, type NotificationFeedPage } from "../api/notifications";
import { getToken } from "../api/session";
import { useCloseOnPageChange } from "../hooks/useCloseOnPageChange";
import { useUnseenNotifications } from "../hooks/useUnseenNotifications";
import { useNotificationRetryAfter } from "../hooks/useNotificationRetryAfter";
import { announceSeen } from "../utils/seenSync";
import { badgeText, notificationKeys, type NoticeView } from "../utils/notificationKeys";
import { browserTimeZone } from "../utils/formatDate";
import { noticeLinkOf } from "../utils/noticeLinks";
import { notificationsKey } from "../utils/roomKeys";
import { subscribeLogicalSession } from "../utils/sessionQueryCache";
import EmptyState from "./EmptyState";
import ErrorAlert from "./ErrorAlert";
import FormErrorAlert from "./FormErrorAlert";
import LoadingBlock from "./LoadingBlock";
import NoticeRow from "./NoticeRow";
import Pager from "./Pager";
import { UnreadAnchor } from "./UnreadCue";

const PAGE_SIZE = 20;

async function listAddressedFeed(page: number): Promise<NotificationFeedPage> {
  const result = await listNotifications({ page, pageSize: PAGE_SIZE, type: ADDRESSED_TYPES });
  return {
    ...result,
    items: result.items.map((item) => ({ ...item, replyCount: null, replyFloorId: null, replyCeilingId: null })),
  };
}

/**
 * The header bell (Covenant's compact NotificationsButton, ported): a labelled button with the unseen count and a right-hand drawer listing the
 * caller's notices newest first, 20 a page, with "Mark all as seen" and an "All / Mentions" switch (Mentions: the kinds addressed to the reader).
 * Opening a notice marks it seen and goes to its message or thread; a row's actor and room are links of their own (`NoticeRow`: the row is the card-with-several-links pattern). No polling (`notification.created` re-reads `["notifications"]`), no delete,
 * no "unseen" toggle, no toast. The badge counts ALL unseen notices; the drawer's list is read only while it is open.
 */
export default function NotificationsButton() {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const location = useLocation();
  const [opened, setOpened] = useState(false);
  const [returnFocus, setReturnFocus] = useState(true);
  const [page, setPage] = useState(1);
  const [view, setView] = useState<NoticeView>("all");
  // The bell outlives a sign-out until the route guard unmounts the shell: meanwhile it keeps no drawer open and reads nothing without a token.
  const signedIn = useSyncExternalStore(subscribeLogicalSession, () => getToken() !== null, () => false);
  // The drawer is a modal over the page: a change of page under it (the actor's or the room's link in a row, 1.2 D6) closes it and the main region
  // takes the focus (`useCloseOnPageChange`); a notice's own link closes it in `open`, with the "already there" exception.
  const noteOpened = useCloseOnPageChange(opened, () => {
    setReturnFocus(false);
    setOpened(false);
  });
  const drawerOpen = opened && signedIn;
  const unseen = useUnseenNotifications();

  const listQuery = useQuery({
    queryKey: notificationKeys.list(page, view),
    queryFn: () =>
      view === "mentions"
        ? listAddressedFeed(page)
        : listNotificationFeed({ page, pageSize: PAGE_SIZE, groupReplies: true }),
    enabled: drawerOpen,
    placeholderData: keepPreviousData,
  });
  useNotificationRetryAfter(listQuery.error);
  const items = listQuery.data?.items ?? [];
  const total = listQuery.data?.total ?? 0;
  // A re-read that emptied the last page (everything on it went) steps back, adjusted during render: to the page before, or to the new last page
  // when more than one page went. Never on a kept-previous placeholder, whose rows belong to another page.
  if (listQuery.data && !listQuery.isPlaceholderData && items.length === 0 && page > 1) setPage(Math.min(page - 1, Math.ceil(total / PAGE_SIZE) || 1));

  const refresh = () => queryClient.invalidateQueries({ queryKey: notificationsKey });
  // Settled, not only succeeded: a 404 (the notice is gone) or a 500 (unknown) re-reads, so the rows show what the server holds.
  // The person's other tabs hear of a write that landed (1.2 D9), so a hidden tab's title count follows.
  const markSeen = useMutation({
    mutationFn: (notice: NotificationFeedItem) =>
      notice.replyCount != null && notice.replyFloorId != null && notice.replyCeilingId != null && notice.threadNumber !== null
        ? markReplyGroupSeen(notice.roomId, notice.threadNumber, notice.replyFloorId, notice.replyCeilingId)
        : markNotificationSeen(notice.id),
    onSuccess: announceSeen,
    onSettled: refresh,
  });
  const markAll = useMutation({
    mutationFn: () => markAllNotificationsSeen(),
    onSuccess: () => {
      announceSeen();
      return refresh();
    },
  });

  function open(n: NotificationFeedItem, event: MouseEvent<HTMLAnchorElement>) {
    // Acting on it is "seen"; the navigation does not wait for the write, and a failed write re-reads what the server holds.
    if (!n.seen) markSeen.mutate(n);
    // Ctrl/Cmd/Shift/Alt-click opens a new tab or window (the router leaves it to the browser): the drawer and the focus stay where they are.
    if (event.ctrlKey || event.metaKey || event.shiftKey || event.altKey) return;
    // The destination (the message, the thread's pane) takes focus itself; returning it to the bell would pull it back. A link to where the
    // person already is moves nothing and focuses nothing, so there the bell gets the focus back.
    setReturnFocus(noticeLinkOf(n) === location.pathname + location.search);
    setOpened(false);
  }

  const zone = browserTimeZone();
  // The badge and the label agree: both say "999+" exactly when the server's total reached its cap (which means "at least 1000").
  const capped = unseen.total >= NOTIFICATION_TOTAL_CAP;
  const countWords = capped ? t("notifications.unseenMany") : t("notifications.unseen", { count: unseen.total });
  // The drawer's own rows count too: the badge query may have failed while the list loaded.
  const anyUnseen = unseen.total > 0 || items.some((n) => !n.seen);
  return (
    <>
      <UnreadAnchor show={unseen.total > 0} count={badgeText(unseen.total)}>
        <ActionIcon
          variant="subtle"
          color="gray"
          size="lg"
          aria-haspopup="dialog"
          aria-expanded={drawerOpen}
          aria-label={`${t("notifications.title")} (${countWords})`}
          onClick={() => {
            setReturnFocus(true);
            noteOpened();
            setPage(1);
            markAll.reset();
            setOpened(true);
          }}
        >
          <IconBell size={18} />
        </ActionIcon>
      </UnreadAnchor>
      <Drawer
        opened={drawerOpen}
        onClose={() => setOpened(false)}
        returnFocus={returnFocus}
        position="right"
        size="md"
        title={t("notifications.title")}
        closeButtonProps={{ "aria-label": t("common.action.close") }}
      >
        <Stack gap="sm">
          <Group justify="space-between" wrap="wrap" gap="xs">
            <SegmentedControl
              size="xs"
              aria-label={t("notifications.view.label")}
              value={view}
              onChange={(next) => {
                setView(next as NoticeView);
                setPage(1);
              }}
              data={[
                { value: "all", label: t("notifications.view.all") },
                { value: "mentions", label: t("notifications.view.mentions") },
              ]}
            />
            {anyUnseen && (
              <Button size="xs" variant="light" leftSection={<IconChecks size={14} />} onClick={() => markAll.mutate()} loading={markAll.isPending}>
                {t("notifications.markAllSeen")}
              </Button>
            )}
          </Group>
          {markAll.isError && <FormErrorAlert>{t("notifications.markAllFailed")}</FormErrorAlert>}
          {listQuery.isLoading ? (
            <LoadingBlock />
          ) : listQuery.isError && !listQuery.data ? (
            <ErrorAlert error={listQuery.error} title={t("notifications.loadFailed")} />
          ) : items.length === 0 ? (
            <EmptyState icon={IconBellOff} label={view === "mentions" ? t("notifications.emptyMentions") : t("notifications.empty")} />
          ) : (
            <Box component="ul" m={0} p={0} style={{ listStyle: "none" }}>
              {items.map((n) => (
                <NoticeRow key={n.id} n={n} zone={zone} onOpen={open} />
              ))}
            </Box>
          )}
          {total > PAGE_SIZE && (
            <Group justify="center">
              <Pager size="sm" page={page} pages={Math.ceil(total / PAGE_SIZE)} onChange={setPage} />
            </Group>
          )}
        </Stack>
      </Drawer>
    </>
  );
}
