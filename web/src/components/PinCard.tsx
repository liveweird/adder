import { Link as RouterLink, useLocation } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { Anchor, Button, Group, Stack, Text } from "@mantine/core";
import { IconPinFilled } from "@tabler/icons-react";
import type { RoomPin } from "../api/pins";
import type { RoomResponse, RoomWorkflow } from "../api/rooms";
import { useThreadRefHref } from "../hooks/useThreadRefHref";
import { browserTimeZone, formatDateTime } from "../utils/formatDate";
import { messageExcerpt } from "../utils/messageExcerpt";
import { focusThreadPaneState, listRouteSearch, messageJumpPath, tagFilterPath, threadPath, withListParams } from "../utils/roomLinks";
import { fillSlots, slot } from "../utils/slots";
import { parseFocus } from "../utils/threadListParams";
import MarkdownView from "./MarkdownView";
import PersonLink from "./PersonLink";
import classes from "./PinCard.module.css";
import ThreadStatusPill from "./ThreadStatusPill";

/**
 * One pin of the room (a `li`), drawn by the room's Pinned drawer (`PinnedPanel`, whole body) and by the open thread's strip
 * (`ThreadPinStrip`, `compact`: author, time, a two-line plain excerpt, "Show in thread" and "Unpin", for a message pin). Both say who pinned and
 * when, with the filled pin and a gray bar (1.2 D5). `onUnpin` is absent when the caller may not change pins (not a member, or the room is
 * archived). `onNavigate` runs when a THREAD link is followed (the drawer closes; the strip asks the conversation to jump even to a message it
 * already landed on). The message's author and the pinner are links to their profiles (1.2 D6; the drawer closes on any change of page, see
 * `PinnedPanel`). The thread link keeps the list's URL keys only from the Threads tab (`listRouteSearch`). Titles and bodies are text or
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
  const threadHref = useThreadRefHref(room.id);
  const zone = browserTimeZone();
  const message = pin.kind === "MESSAGE" ? pin.message : null;
  const threadName = `#${pin.threadNumber} ${pin.threadTitle}`;
  const when = message ? formatDateTime(message.createdAt, zone) : "";
  // The strip sits in the pane: a jump from the focus view stays in it (the drawer's link goes to the plain thread, `focus` is not a list key).
  const stayFocused = compact && parseFocus(new URLSearchParams(currentSearch));
  const author = message ? <PersonLink id={message.authorId} name={message.authorName} inherit /> : null;
  const targetName = message ? t("threads.message.name", { author: message.authorName, when }) : threadName;
  return (
    <Stack component="li" gap={6} className={classes.card}>
      <Group justify="space-between" align="flex-start" wrap="nowrap" gap="xs">
        <Text size="sm" fw={600} miw={0} style={{ overflowWrap: "anywhere" }}>
          {message ? (
            <>
              {compact ? author : fillSlots(t("rooms.pins.messageBy", { author: slot("author") }), { author })}
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
          <MarkdownView variant="message" tagHref={(tag) => tagFilterPath(room.id, pin.threadNumber, tag)} threadHref={threadHref} mentions={message.mentions} groupMentions={message.groupMentions}>
            {message.body}
          </MarkdownView>
        </>
      )}
      {message && compact && (
        <Text size="sm" lineClamp={2} className={classes.excerpt}>
          {messageExcerpt(message)}
        </Text>
      )}
      <Group gap={4} wrap="nowrap" align="flex-start">
        <IconPinFilled size={12} aria-hidden="true" style={{ flex: "none", marginTop: 3 }} />
        <Text size="xs" c="dimmed">
          {fillSlots(t("rooms.pins.pinnedBy", { name: slot("name"), when: formatDateTime(pin.pinnedAt, zone) }), {
            name: <PersonLink id={pin.pinnedById} name={pin.pinnedByName} inherit />,
          })}
        </Text>
      </Group>
      <Group gap="xs">
        {message && (
          <Button
            component={RouterLink}
            to={messageJumpPath(room.id, pin.threadNumber, message.id, search, stayFocused)}
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
