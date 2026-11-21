import { useState } from "react";
import { Link as RouterLink, useLocation } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { Anchor, Button, Drawer, Group, Stack, Text } from "@mantine/core";
import { useQueryClient } from "@tanstack/react-query";
import { IconPin, IconPinnedOff } from "@tabler/icons-react";
import { setMessagePin, setThreadPin, type RoomPin } from "../api/pins";
import type { RoomResponse, RoomWorkflow } from "../api/rooms";
import { useRoomPins } from "../hooks/useRoomPins";
import { useRoomWorkflow } from "../hooks/useRoomWorkflow";
import { browserTimeZone, formatDateTime } from "../utils/formatDate";
import { canPin, pinErrorMessage, refreshAfterPinFailure, refreshAfterPinWrite } from "../utils/pins";
import { focusThreadPaneState, listRouteSearch, tagFilterPath, threadMessagePath, threadPath, withListParams } from "../utils/roomLinks";
import EmptyState from "./EmptyState";
import ErrorAlert from "./ErrorAlert";
import FormErrorAlert from "./FormErrorAlert";
import LoadingBlock from "./LoadingBlock";
import MarkdownView from "./MarkdownView";
import ThreadStatusPill from "./ThreadStatusPill";

/**
 * The room's pins: a "Pinned (n)" button in the room header that opens a drawer. The drawer lists the thread pins (`#number title` with the
 * status pill, a link to the thread) and the message pins (the thread they belong to, the message as Markdown, "Open in thread" with `message=`),
 * newest first, each with "Unpin" for a member of a live room (any member, 0.6 D4). The list mounts when the drawer opens, so it re-reads the
 * pins (a pinned thread's title or status may have moved); `room.pin_changed` keeps an open drawer current. The pinned text is Markdown or text,
 * never HTML. Following a link closes the drawer WITHOUT returning focus to the button: the destination (the thread's pane, the message) takes
 * focus itself, and the drawer's return would pull it back over it. The thread link keeps the list's URL keys only from the Threads tab
 * (`listRouteSearch`): the Search tab's `scope` and `page` are not the list's.
 */
export default function PinnedPanel({ room }: { room: RoomResponse }) {
  const { t } = useTranslation();
  const [opened, setOpened] = useState(false);
  const [returnFocus, setReturnFocus] = useState(true);
  const pins = useRoomPins(room.id);
  const label = pins.data ? t("rooms.pins.button", { count: pins.data.items.length }) : t("rooms.pins.buttonNoCount");
  return (
    <>
      <Button variant="default" size="xs" leftSection={<IconPin size={14} />} aria-haspopup="dialog" onClick={() => {
          setReturnFocus(true);
          setOpened(true);
        }}
      >
        {label}
      </Button>
      <Drawer
        opened={opened}
        onClose={() => setOpened(false)}
        returnFocus={returnFocus}
        position="right"
        size="lg"
        title={t("rooms.pins.title")}
        closeButtonProps={{ "aria-label": t("common.action.close") }}
      >
        <PinnedList
          room={room}
          onNavigate={() => {
            setReturnFocus(false);
            setOpened(false);
          }}
        />
      </Drawer>
    </>
  );
}

function PinnedList({ room, onNavigate }: { room: RoomResponse; onNavigate: () => void }) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const { data, isLoading, error } = useRoomPins(room.id);
  const workflow = useRoomWorkflow(room.id);
  const [failure, setFailure] = useState<string | null>(null);
  const [busyId, setBusyId] = useState<number | null>(null);
  const mayUnpin = canPin(room);

  async function unpin(pin: RoomPin) {
    // A message pin without its message cannot be addressed; it must never fall through to unpinning the THREAD.
    if (pin.kind === "MESSAGE" && !pin.message) return;
    setFailure(null);
    setBusyId(pin.id);
    try {
      if (pin.kind === "MESSAGE" && pin.message) await setMessagePin(room.id, pin.message.id, false);
      else await setThreadPin(room.id, pin.threadNumber, false);
      await refreshAfterPinWrite(queryClient, room.id);
    } catch (err) {
      setFailure(pinErrorMessage(err, t));
      await refreshAfterPinFailure(queryClient, room.id, err);
    } finally {
      setBusyId(null);
    }
  }

  if (isLoading) return <LoadingBlock />;
  if (!data) return <ErrorAlert error={error} title={t("rooms.pins.loadFailed")} />;
  return (
    <Stack gap="sm">
      {failure && <FormErrorAlert>{failure}</FormErrorAlert>}
      {data.items.length === 0 ? (
        <EmptyState icon={IconPinnedOff} label={t("rooms.pins.empty")} hint={t("rooms.pins.emptyHint")} />
      ) : (
        <Stack component="ul" gap="md" m={0} p={0} style={{ listStyle: "none" }}>
          {data.items.map((pin) => (
            <PinnedItem
              key={pin.id}
              room={room}
              pin={pin}
              statuses={workflow.data?.statuses}
              onUnpin={mayUnpin ? () => void unpin(pin) : undefined}
              busy={busyId === pin.id}
              onNavigate={onNavigate}
            />
          ))}
        </Stack>
      )}
    </Stack>
  );
}

function PinnedItem({
  room,
  pin,
  statuses,
  onUnpin,
  busy,
  onNavigate,
}: {
  room: RoomResponse;
  pin: RoomPin;
  statuses: RoomWorkflow["statuses"] | undefined;
  /** Absent when the caller may not change pins (not a member, or the room is archived). */
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
  const unpinName = message ? t("threads.message.name", { author: message.authorName, when }) : threadName;
  return (
    <Stack component="li" gap={6}>
      <Group justify="space-between" align="flex-start" wrap="nowrap" gap="xs">
        <Text size="sm" fw={600} miw={0} style={{ overflowWrap: "anywhere" }}>
          {message ? (
            <>
              {t("rooms.pins.messageBy", { author: message.authorName })}
              <Text span c="dimmed" inherit>{` · ${when}`}</Text>
            </>
          ) : (
            <Anchor component={RouterLink} to={withListParams(threadPath(room.id, pin.threadNumber), search)} onClick={onNavigate} state={focusThreadPaneState} inherit>
              {threadName}
            </Anchor>
          )}
        </Text>
        <ThreadStatusPill statuses={statuses} statusKey={pin.threadStatus} size="sm" />
      </Group>
      {message && (
        <>
          <Text size="xs" c="dimmed" style={{ overflowWrap: "anywhere" }}>
            {t("rooms.pins.inThread", { thread: threadName })}
          </Text>
          <MarkdownView variant="message" tagHref={(tag) => tagFilterPath(room.id, pin.threadNumber, tag)} mentions={message.mentions} groupMentions={message.groupMentions}>
            {message.body}
          </MarkdownView>
        </>
      )}
      <Text size="xs" c="dimmed">
        {t("rooms.pins.pinnedBy", { name: pin.pinnedByName, when: formatDateTime(pin.pinnedAt, zone) })}
      </Text>
      <Group gap="xs">
        {message && (
          <Button
            component={RouterLink}
            to={threadMessagePath(room.id, pin.threadNumber, message.id, search)}
            onClick={onNavigate}
            variant="default"
            size="compact-xs"
            aria-label={t("rooms.pins.openAria", { name: threadName })}
          >
            {t("rooms.pins.open")}
          </Button>
        )}
        {onUnpin && (
          <Button variant="default" size="compact-xs" loading={busy} aria-label={t("rooms.pins.unpinAria", { name: unpinName })} onClick={onUnpin}>
            {t("rooms.pins.unpin")}
          </Button>
        )}
      </Group>
    </Stack>
  );
}
