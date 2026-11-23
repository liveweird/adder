import { Anchor, Badge, Group, Text, Title, VisuallyHidden } from "@mantine/core";
import { IconArrowRight, IconEye, IconHome, IconMessageReply, IconMessages, IconUserStar } from "@tabler/icons-react";
import { useTranslation } from "react-i18next";
import { Link as RouterLink } from "react-router-dom";
import type { HomeQuote, HomeResponse, HomeRoom, HomeThread } from "../api/home";
import { browserTimeZone, formatDateTime } from "../utils/formatDate";
import { quoteExcerpt } from "../utils/messageExcerpt";
import { roomMembersPath, roomPath, searchPath, threadMessagePath, threadPath } from "../utils/roomLinks";
import { watchedThreadsPath } from "../utils/searchParams";
import { unreadBadge } from "../utils/unread";
import { statusHue } from "../utils/workflowColours";
import RoomAccessBadge from "./RoomAccessBadge";
import StatusPill from "./StatusPill";
import UnreadCue from "./UnreadCue";
import classes from "./HomeDashboard.module.css";

const REQUESTS_ANCHOR = "room-join-requests-title";

function Section({ id, title, action, children }: { id: string; title: string; action?: React.ReactNode; children: React.ReactNode }) {
  return (
    <section aria-labelledby={id} className={classes.section}>
      <header className={classes.sectionHeader}>
        <Title order={3} size="h4" id={id}>{title}</Title>
        {action}
      </header>
      {children}
    </section>
  );
}

function Empty({ icon: Icon, children }: { icon: typeof IconHome; children: React.ReactNode }) {
  return (
    <div className={classes.empty} role="status">
      <Icon size={20} stroke={1.5} aria-hidden="true" />
      <Text size="sm" c="dimmed">{children}</Text>
    </div>
  );
}

function Time({ value, children }: { value: number; children: React.ReactNode }) {
  return (
    <Text component="time" dateTime={new Date(value).toISOString()} size="xs" c="dimmed" className={classes.time}>
      {children}
    </Text>
  );
}

function RowArrow() {
  return <IconArrowRight size={15} aria-hidden="true" className={classes.rowArrow} />;
}

function ArchivedBadge({ archived }: { archived: boolean }) {
  const { t } = useTranslation();
  return archived ? <Badge size="xs" variant="light" color="gray">{t("rooms.archivedBadge")}</Badge> : null;
}

function Quotes({ items, zone }: { items: HomeQuote[]; zone: string }) {
  const { t } = useTranslation();
  if (items.length === 0) return <Empty icon={IconMessageReply}>{t("home.quotes.empty")}</Empty>;
  return (
    <ul className={classes.list}>
      {items.map((quote) => (
        <li key={quote.messageId}>
          <RouterLink to={threadMessagePath(quote.roomId, quote.threadNumber, quote.messageId)} className={classes.row}>
            <div className={classes.rowBody}>
              <Group gap="xs" wrap="nowrap" className={classes.contextLine}>
                {quote.unread && <UnreadCue size="xs" className={classes.quoteUnread}>{t("home.new")}</UnreadCue>}
                <Text component="span" size="xs" c="dimmed" lineClamp={1}>
                  {t("home.quotes.context", { author: quote.authorName, room: quote.roomName })}
                </Text>
              </Group>
              <Text component="span" fw={600} size="sm" lineClamp={1} className={classes.title}>
                {t("home.threadNumber", { number: quote.threadNumber })} {quote.threadTitle}
              </Text>
              <Text component="span" size="sm" c="dimmed" lineClamp={2} className={classes.excerpt}>
                {quoteExcerpt(quote)}
              </Text>
              <ArchivedBadge archived={quote.roomArchived} />
            </div>
            <div className={classes.rowMeta}>
              <Time value={quote.createdAt}>{t("home.quotes.at", { when: formatDateTime(quote.createdAt, zone) })}</Time>
              {quote.unread && <VisuallyHidden>{t("home.unreadAccessible")}</VisuallyHidden>}
              <RowArrow />
            </div>
          </RouterLink>
        </li>
      ))}
    </ul>
  );
}

function Threads({ items, empty, zone }: { items: HomeThread[]; empty: string; zone: string }) {
  const { t } = useTranslation();
  if (items.length === 0) return <Empty icon={IconMessages}>{empty}</Empty>;
  return (
    <ul className={classes.list}>
      {items.map((thread) => (
        <li key={`${thread.roomId}-${thread.number}`}>
          <RouterLink to={threadPath(thread.roomId, thread.number)} className={classes.row}>
            <div className={classes.rowBody}>
              <Text component="span" size="xs" c="dimmed" lineClamp={1}>
                {thread.roomName} · {t("home.threadNumber", { number: thread.number })}
              </Text>
              <Text component="span" fw={thread.unreadCount > 0 ? 700 : 600} size="sm" lineClamp={2} className={classes.title}>
                {thread.title}
              </Text>
              <Group gap="xs" wrap="wrap">
                <StatusPill color={statusHue(thread.statusColour)} dot size="sm">{thread.statusLabel}</StatusPill>
                {thread.unreadCount > 0 && <UnreadCue size="xs">{t("home.unread", { count: unreadBadge(thread.unreadCount) })}</UnreadCue>}
                <ArchivedBadge archived={thread.roomArchived} />
              </Group>
            </div>
            <div className={classes.rowMeta}>
              <Time value={thread.lastActivityAt}>{t("home.activeAt", { when: formatDateTime(thread.lastActivityAt, zone) })}</Time>
              {thread.unreadCount > 0 && <VisuallyHidden>{t("home.unreadAccessible")}</VisuallyHidden>}
              <RowArrow />
            </div>
          </RouterLink>
        </li>
      ))}
    </ul>
  );
}

function Rooms({ items, zone }: { items: HomeRoom[]; zone: string }) {
  const { t } = useTranslation();
  if (items.length === 0) return <Empty icon={IconUserStar}>{t("home.rooms.empty")}</Empty>;
  return (
    <ul className={classes.list}>
      {items.map((room) => {
        const requests = room.pendingJoinRequests;
        const target = requests > 0 ? `${roomMembersPath(room.id)}#${REQUESTS_ANCHOR}` : roomPath(room.id);
        return (
          <li key={room.id}>
            <RouterLink to={target} className={classes.row}>
              <div className={classes.rowBody}>
                <Text component="span" fw={room.unread ? 700 : 600} size="sm" lineClamp={2} className={classes.title}>{room.name}</Text>
                <Group gap="xs" wrap="wrap">
                  <RoomAccessBadge accessType={room.accessType} size="xs" />
                  {requests > 0 && <Badge size="xs" color="orange" variant="light">{t("home.rooms.requests", { count: requests })}</Badge>}
                  {room.unread && <UnreadCue size="xs">{t("home.new")}</UnreadCue>}
                  <ArchivedBadge archived={room.archived} />
                </Group>
              </div>
              <div className={classes.rowMeta}>
                <Time value={room.lastActivityAt}>{t("home.activeAt", { when: formatDateTime(room.lastActivityAt, zone) })}</Time>
                {room.unread && <VisuallyHidden>{t("home.unreadAccessible")}</VisuallyHidden>}
                <RowArrow />
              </div>
            </RouterLink>
          </li>
        );
      })}
    </ul>
  );
}

/** Four deliberately small queues: urgent conversations first, broader spaces second. */
export default function HomeDashboard({ data }: { data: HomeResponse }) {
  const { t } = useTranslation();
  const zone = browserTimeZone();
  return (
    <div className={classes.grid}>
      <Section id="home-quotes" title={t("home.quotes.title")}>
        <Quotes items={data.quotes} zone={zone} />
      </Section>
      <Section id="home-owned-threads" title={t("home.ownedThreads.title")}>
        <Threads items={data.ownedThreads} empty={t("home.ownedThreads.empty")} zone={zone} />
      </Section>
      <Section id="home-watched-threads" title={t("home.watchedThreads.title")} action={
        <Anchor component={RouterLink} to={watchedThreadsPath(searchPath)} size="sm" className={classes.sectionAction}>
          {t("home.watchedThreads.all")}<IconEye size={15} aria-hidden="true" />
        </Anchor>
      }>
        <Threads items={data.watchedThreads} empty={t("home.watchedThreads.empty")} zone={zone} />
      </Section>
      <Section id="home-owned-rooms" title={t("home.rooms.title")}>
        <Rooms items={data.ownedRooms} zone={zone} />
      </Section>
    </div>
  );
}
