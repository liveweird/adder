import { useState, useSyncExternalStore, type MouseEvent } from "react";
import { Link as RouterLink, useLocation } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { ActionIcon, Anchor, Box, Button, Drawer, Group, Indicator, Stack, Text, VisuallyHidden } from "@mantine/core";
import { IconBell, IconBellOff, IconChecks } from "@tabler/icons-react";
import { keepPreviousData, useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import type { TFunction } from "i18next";
import { listNotifications, markAllNotificationsSeen, markNotificationSeen, NOTIFICATION_TOTAL_CAP, type NotificationItem } from "../api/notifications";
import { getToken } from "../api/session";
import { useUnseenNotifications } from "../hooks/useUnseenNotifications";
import { badgeText, notificationKeys } from "../utils/notificationKeys";
import { browserTimeZone, formatDateTime } from "../utils/formatDate";
import { notificationsKey } from "../utils/roomKeys";
import { focusThreadPaneState, threadMessagePath, threadPath } from "../utils/roomLinks";
import { subscribeLogicalSession } from "../utils/sessionQueryCache";
import EmptyState from "./EmptyState";
import ErrorAlert from "./ErrorAlert";
import FormErrorAlert from "./FormErrorAlert";
import LoadingBlock from "./LoadingBlock";
import Pager from "./Pager";

const PAGE_SIZE = 20;

/** The kinds this build words; a newer server's kind shows its raw name (the contract: a client treats an unknown value as plain text). */
const KNOWN_KINDS: readonly string[] = ["MENTIONED", "THREAD_ASSIGNED"] satisfies NotificationItem["type"][];

/** One sentence per notice from ids' current names and the thread's title, text only (`escapeValue` is off because React escapes). */
function describe(n: NotificationItem, t: TFunction): string {
  if (!KNOWN_KINDS.includes(n.type)) return n.type;
  return t(`notifications.event.${n.type}`, { actor: n.actorName, number: n.threadNumber, title: n.threadTitle });
}

/** A notice opens the message that mentions the person (the 0.5.0 jump), or the thread for a hand-over, which has no message. */
const linkOf = (n: NotificationItem) => (n.messageId === null ? threadPath(n.roomId, n.threadNumber) : threadMessagePath(n.roomId, n.threadNumber, n.messageId));

function NoticeRow({ n, zone, onOpen }: { n: NotificationItem; zone: string; onOpen: (n: NotificationItem, event: MouseEvent<HTMLAnchorElement>) => void }) {
  const { t } = useTranslation();
  return (
    <Box component="li" py="sm" px={4} style={{ borderTop: "1px solid var(--mantine-color-default-border)" }}>
      <Anchor
        component={RouterLink}
        to={linkOf(n)}
        // A hand-over lands on the thread's pane, which then takes focus (as a list row and a pin do); a message jump focuses the message itself.
        state={n.messageId === null ? focusThreadPaneState : undefined}
        onClick={(event: MouseEvent<HTMLAnchorElement>) => onOpen(n, event)}
        display="block"
        underline="hover"
        c="inherit"
      >
        <Group align="flex-start" wrap="nowrap" gap="sm">
          {/* The dot is a shape for sighted users; the hidden word carries "unseen" for the rest. */}
          <Indicator size={9} offset={3} disabled={n.seen} position="middle-start" inline>
            <Box w={12} />
          </Indicator>
          <Stack gap={2} style={{ minWidth: 0, flex: 1 }}>
            {!n.seen && <VisuallyHidden>{t("notifications.unseenRow")}</VisuallyHidden>}
            <Text size="sm" fw={n.seen ? 400 : 600} c={n.seen ? "dimmed" : undefined} style={{ overflowWrap: "anywhere" }}>
              {describe(n, t)}
            </Text>
            <Text size="xs" c="dimmed" style={{ overflowWrap: "anywhere" }}>
              {t("notifications.inRoom", { room: n.roomName, when: formatDateTime(n.createdAt, zone) })}
            </Text>
          </Stack>
        </Group>
      </Anchor>
    </Box>
  );
}

/**
 * The header bell (Covenant's compact NotificationsButton, ported): a labelled button with the unseen count and a right-hand drawer listing the
 * caller's notices newest first, 20 a page, with "Mark all as seen". Opening a notice marks it seen and goes to its message or thread. No polling
 * (`notification.created` re-reads `["notifications"]`), no delete, no "unseen" toggle, no toast. The drawer's list is read only while it is open.
 */
export default function NotificationsButton() {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const location = useLocation();
  const [opened, setOpened] = useState(false);
  const [returnFocus, setReturnFocus] = useState(true);
  const [page, setPage] = useState(1);
  // The bell outlives a sign-out until the route guard unmounts the shell: meanwhile it keeps no drawer open and reads nothing without a token.
  const signedIn = useSyncExternalStore(subscribeLogicalSession, () => getToken() !== null, () => false);
  const drawerOpen = opened && signedIn;
  const unseen = useUnseenNotifications();

  const listQuery = useQuery({
    queryKey: notificationKeys.list(page),
    queryFn: () => listNotifications({ page, pageSize: PAGE_SIZE }),
    enabled: drawerOpen,
    placeholderData: keepPreviousData,
  });
  const items = listQuery.data?.items ?? [];
  const total = listQuery.data?.total ?? 0;
  // A re-read that emptied the last page (everything on it went) steps back, adjusted during render: to the page before, or to the new last page
  // when more than one page went. Never on a kept-previous placeholder, whose rows belong to another page.
  if (listQuery.data && !listQuery.isPlaceholderData && items.length === 0 && page > 1) setPage(Math.min(page - 1, Math.ceil(total / PAGE_SIZE) || 1));

  const refresh = () => queryClient.invalidateQueries({ queryKey: notificationsKey });
  // Settled, not only succeeded: a 404 (the notice is gone) or a 500 (unknown) re-reads, so the rows show what the server holds.
  const markSeen = useMutation({ mutationFn: (id: number) => markNotificationSeen(id), onSettled: refresh });
  const markAll = useMutation({ mutationFn: () => markAllNotificationsSeen(), onSuccess: refresh });

  function open(n: NotificationItem, event: MouseEvent<HTMLAnchorElement>) {
    // Acting on it is "seen"; the navigation does not wait for the write, and a failed write re-reads what the server holds.
    if (!n.seen) markSeen.mutate(n.id);
    // Ctrl/Cmd/Shift/Alt-click opens a new tab or window (the router leaves it to the browser): the drawer and the focus stay where they are.
    if (event.ctrlKey || event.metaKey || event.shiftKey || event.altKey) return;
    // The destination (the message, the thread's pane) takes focus itself; returning it to the bell would pull it back. A link to where the
    // person already is moves nothing and focuses nothing, so there the bell gets the focus back.
    setReturnFocus(linkOf(n) === location.pathname + location.search);
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
      <Indicator inline size={18} offset={4} color="red" label={<span aria-hidden="true">{badgeText(unseen.total)}</span>} disabled={unseen.total === 0}>
        <ActionIcon
          variant="subtle"
          color="gray"
          size="lg"
          aria-haspopup="dialog"
          aria-expanded={drawerOpen}
          aria-label={`${t("notifications.title")} (${countWords})`}
          onClick={() => {
            setReturnFocus(true);
            setPage(1);
            markAll.reset();
            setOpened(true);
          }}
        >
          <IconBell size={18} />
        </ActionIcon>
      </Indicator>
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
          {anyUnseen && (
            <Group justify="flex-end">
              <Button size="xs" variant="light" leftSection={<IconChecks size={14} />} onClick={() => markAll.mutate()} loading={markAll.isPending}>
                {t("notifications.markAllSeen")}
              </Button>
            </Group>
          )}
          {markAll.isError && <FormErrorAlert>{t("notifications.markAllFailed")}</FormErrorAlert>}
          {listQuery.isLoading ? (
            <LoadingBlock />
          ) : listQuery.isError ? (
            <ErrorAlert error={listQuery.error} title={t("notifications.loadFailed")} />
          ) : items.length === 0 ? (
            <EmptyState icon={IconBellOff} label={t("notifications.empty")} />
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
