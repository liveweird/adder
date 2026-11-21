import { Link as RouterLink, useLocation } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { Anchor, Button, Group, Stack, Text } from "@mantine/core";
import { IconPinFilled } from "@tabler/icons-react";
import type { RoomPin } from "../api/pins";
import type { RoomResponse, RoomWorkflow } from "../api/rooms";
import { browserTimeZone, formatDateTime } from "../utils/formatDate";
import { pinExcerpt } from "../utils/pinExcerpt";
import { focusThreadPaneState, listRouteSearch, tagFilterPath, threadMessagePath, threadPath, withListParams } from "../utils/roomLinks";
import { parseFocus, writeFocus } from "../utils/threadListParams";
import MarkdownView from "./MarkdownView";
import classes from "./PinCard.module.css";
import ThreadStatusPill from "./ThreadStatusPill";

/**
 * One pin of the room (a `li`), drawn by the room's Pinned drawer (`PinnedPanel`, whole body) and by the open thread's strip
 * (`ThreadPinStrip`, `compact`: author, time, a two-line plain excerpt, "Show in thread" and "Unpin", for a message pin). Both say who pinned and
 * when, with the filled pin and a gray bar (1.2 D5). `onUnpin` is absent when the caller may not change pins (not a member, or the room is
 * archived). `onNavigate` runs when a link is followed (the drawer closes; the strip asks the conversation to jump even to a message it
 * already landed on). The thread link keeps the list's URL keys only from the Threads tab (`listRouteSearch`). Titles and bodies are text or
 * Markdown, never HTML.
 */
export default function PinCard({
  room,
  pin,
  statuses,
  compact = false,
  onUnpin,
  busy,
  onNavigate,
}: {
  room: RoomResponse;
  pin: RoomPin;
  statuses?: RoomWorkflow["statuses"];
  compact?: boolean;
  onUnpin: (() => void) | undefined;
  busy: boolean;
  onNavigate: () => void;
}) {
  const { t } = useTranslation();
  const { pathname, search: currentSearch } = useLocation();
  const search = listRouteSearch(pathname, currentSearch);
  const zone = browserTimeZone();
  const message = pin.kind === "MESSAGE" ? pin.message : null;
  const threadName = `#${pin.threadNumber} ${pin.threadTitle}`;
  const when = message ? formatDateTime(message.createdAt, zone) : "";
  // The strip sits in the pane: a jump from the focus view stays in it (the drawer's link goes to the plain thread, `focus` is not a list key).
  const stayFocused = compact && parseFocus(new URLSearchParams(currentSearch));
  const jumpTo = (messageId: number) => {
    const [path, query = ""] = threadMessagePath(room.id, pin.threadNumber, messageId, search).split("?");
    return `${path}?${stayFocused ? writeFocus(new URLSearchParams(query), true) : query}`;
  };
  const targetName = message ? t("threads.message.name", { author: message.authorName, when }) : threadName;
  return (
    <Stack component="li" gap={6} className={classes.card}>
      <Group justify="space-between" align="flex-start" wrap="nowrap" gap="xs">
        <Text size="sm" fw={600} miw={0} style={{ overflowWrap: "anywhere" }}>
          {message ? (
            <>
              {compact ? message.authorName : t("rooms.pins.messageBy", { author: message.authorName })}
              <Text span c="dimmed" inherit>{` · ${when}`}</Text>
            </>
          ) : (
            <Anchor component={RouterLink} to={withListParams(threadPath(room.id, pin.threadNumber), search)} onClick={onNavigate} state={focusThreadPaneState} inherit>
              {threadName}
            </Anchor>
          )}
        </Text>
        {!compact && <ThreadStatusPill statuses={statuses} statusKey={pin.threadStatus} size="sm" />}
      </Group>
      {message && !compact && (
        <>
          <Text size="xs" c="dimmed" style={{ overflowWrap: "anywhere" }}>
            {t("rooms.pins.inThread", { thread: threadName })}
          </Text>
          <MarkdownView variant="message" tagHref={(tag) => tagFilterPath(room.id, pin.threadNumber, tag)} mentions={message.mentions} groupMentions={message.groupMentions}>
            {message.body}
          </MarkdownView>
        </>
      )}
      {message && compact && (
        <Text size="sm" lineClamp={2} className={classes.excerpt}>
          {pinExcerpt(message)}
        </Text>
      )}
      <Group gap={4} wrap="nowrap" align="flex-start">
        <IconPinFilled size={12} aria-hidden="true" style={{ flex: "none", marginTop: 3 }} />
        <Text size="xs" c="dimmed">
          {t("rooms.pins.pinnedBy", { name: pin.pinnedByName, when: formatDateTime(pin.pinnedAt, zone) })}
        </Text>
      </Group>
      <Group gap="xs">
        {message && (
          <Button
            component={RouterLink}
            to={jumpTo(message.id)}
            onClick={onNavigate}
            variant="default"
            size="compact-xs"
            aria-label={compact ? t("threads.pinStrip.showAria", { name: targetName }) : t("rooms.pins.openAria", { name: threadName })}
          >
            {compact ? t("threads.pinStrip.show") : t("rooms.pins.open")}
          </Button>
        )}
        {onUnpin && (
          <Button variant="default" size="compact-xs" loading={busy} aria-label={t("rooms.pins.unpinAria", { name: targetName })} onClick={onUnpin}>
            {t("rooms.pins.unpin")}
          </Button>
        )}
      </Group>
    </Stack>
  );
}
