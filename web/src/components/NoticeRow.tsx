import type { MouseEvent } from "react";
import { Link as RouterLink } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { Anchor, Box, Group, Stack, Text } from "@mantine/core";
import type { TFunction } from "i18next";
import type { NotificationFeedItem } from "../api/notifications";
import { formatDateTime } from "../utils/formatDate";
import { noticeLinkOf } from "../utils/noticeLinks";
import { focusThreadPaneState } from "../utils/roomLinks";
import { fillSlots, slot } from "../utils/slots";
import PersonLink from "./PersonLink";
import RoomLink from "./RoomLink";
import UnreadCue from "./UnreadCue";
import classes from "./NoticeRow.module.css";

/** The kinds this build words; a newer server's kind shows its raw name (the contract: a client treats an unknown value as plain text). */
const KNOWN_KINDS: readonly string[] = [
  "MENTIONED",
  "MESSAGE_QUOTED",
  "TEAM_MENTIONED",
  "WATCHERS_MENTIONED",
  "EVERYONE_MENTIONED",
  "THREAD_REPLIED",
  "THREAD_CREATED",
  "THREAD_ASSIGNED",
  "THREAD_OWNER_CHANGED",
  "THREAD_STATUS_CHANGED",
  "THREAD_CLOSED",
  "THREAD_REOPENED",
  "ROOM_ADDED",
] satisfies NotificationFeedItem["type"][];

/** One sentence per notice from ids' current names and the thread's title, text only (`escapeValue` is off because React escapes); `actor` is what stands for the actor's name. */
function describe(n: NotificationFeedItem, t: TFunction, actor: string): string {
  if (n.type === "THREAD_REPLIED" && n.replyCount != null && n.replyCount > 1) {
    return t("notifications.replyGroup", { count: n.replyCount, number: n.threadNumber, title: n.threadTitle });
  }
  if (!KNOWN_KINDS.includes(n.type)) return n.type;
  return t(`notifications.event.${n.type}`, { actor, number: n.threadNumber, title: n.threadTitle });
}

/**
 * A notice of the drawer, a `li`: the sentence (the actor a `PersonLink`), then the room (a `RoomLink`) and the time, bold while unseen (a seen row is dimmed, and the links in it inherit that). The whole row opens the notice through a STRETCHED link (`NoticeRow.module.css`): the rest of the sentence after the actor's name is its text
 * (so it is a link a pointer, a locator and a magnifier can find, with a box of its own), its `::after` covers the row, and its accessible name is "Unseen"
 * (while unseen) and the whole sentence, so a locator by the sentence still finds it; the room and the time follow as the room link and the row's text. The actor and room links sit
 * above it, beside it in the DOM and never inside it. The row's text is still exposed (it holds links), so a screen reader reads the sentence once
 * as text and once as the notice's link. The actor is cut out of the sentence at its marker (`slot`): whatever a language puts before the name stays
 * text, whatever follows is the notice link's text.
 */
export default function NoticeRow({ n, zone, onOpen }: { n: NotificationFeedItem; zone: string; onOpen: (n: NotificationFeedItem, event: MouseEvent<HTMLAnchorElement>) => void }) {
  const { t } = useTranslation();
  const when = formatDateTime(n.createdAt, zone);
  const roomNotice = n.type === "ROOM_ADDED";
  // The notice link is named for the unseen word and the sentence; the room and the time follow as the room link and the row's text.
  const name = [n.seen ? null : t("notifications.unseenRow"), describe(n, t, n.actorName)].filter(Boolean).join(" ");
  const openName = roomNotice ? `${name}. ${t("notifications.openRoom")}` : name;
  const marker = slot("actor");
  const sentence = describe(n, t, marker);
  const at = sentence.indexOf(marker);
  // A kind this build does not know shows its raw name: no actor in it, the whole text is the notice link's.
  const before = at < 0 ? "" : sentence.slice(0, at);
  const after = at < 0 ? sentence : sentence.slice(at + marker.length);
  return (
    <Box component="li" py="sm" px={4} className={classes.row}>
      <Group align="flex-start" wrap="nowrap" gap="sm">
        {/* The dot is a shape for sighted users; the link's name carries "unseen" for the rest. */}
        <Box w={8} pt={6} style={{ flex: "none" }}>
          {!n.seen && <UnreadCue />}
        </Box>
        <Stack gap={2} style={{ minWidth: 0, flex: 1 }}>
          <Text size="sm" fw={n.seen ? 400 : 600} c={n.seen ? "dimmed" : undefined} style={{ overflowWrap: "anywhere" }}>
            {before}
            {at >= 0 && <PersonLink id={n.actorId} name={n.actorName} inherit />}
            <Anchor
              component={RouterLink}
              to={noticeLinkOf(n)}
              // A thread-level notice lands on the thread's pane, which then takes focus (as a list row and a pin do); a message jump focuses the message itself.
              state={n.threadNumber !== null && n.messageId === null ? focusThreadPaneState : undefined}
              onClick={(event: MouseEvent<HTMLAnchorElement>) => onOpen(n, event)}
              aria-label={openName}
              className={classes.open}
              underline="never"
              inherit
              c="inherit"
            >
              {after}
              {roomNotice && <> · <span className={classes.roomAction}>{t("notifications.openRoom")}</span></>}
            </Anchor>
          </Text>
          <Text size="xs" c="dimmed" style={{ overflowWrap: "anywhere" }}>
            {fillSlots(t("notifications.inRoom", { room: slot("room"), when }), {
              room: roomNotice ? <span>{n.roomName}</span> : <RoomLink id={n.roomId} name={n.roomName} inherit />,
            })}
          </Text>
        </Stack>
      </Group>
    </Box>
  );
}
