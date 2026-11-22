import { useState } from "react";
import { useTranslation } from "react-i18next";
import { Button, Drawer, Stack } from "@mantine/core";
import { IconPin, IconPinnedOff } from "@tabler/icons-react";
import type { RoomResponse } from "../api/rooms";
import { useCloseOnPageChange } from "../hooks/useCloseOnPageChange";
import { useRoomPins } from "../hooks/useRoomPins";
import { useRoomWorkflow } from "../hooks/useRoomWorkflow";
import { useUnpin } from "../hooks/useUnpin";
import { canPin } from "../utils/pins";
import EmptyState from "./EmptyState";
import ErrorAlert from "./ErrorAlert";
import FormErrorAlert from "./FormErrorAlert";
import LoadingBlock from "./LoadingBlock";
import PinCard from "./PinCard";

/**
 * The room's pins: a "Pinned (n)" button in the room header that opens a drawer. The drawer lists the thread pins (`#number title` with the
 * status pill, a link to the thread) and the message pins (the thread they belong to, the message as Markdown, "Open in thread" with `message=`),
 * newest first, each with "Unpin" for a member of a live room (any member, 0.6 D4). The list mounts when the drawer opens, so it re-reads the
 * pins (a pinned thread's title or status may have moved); `room.pin_changed` keeps an open drawer current. The pinned text is Markdown or text,
 * never HTML; each pin is a `PinCard` (the pin icon, "Pinned by" and a gray bar, 1.2 D5: the thread's own strip draws the same cards). Following a link closes the drawer WITHOUT returning focus to the button: the destination (the thread's pane, the message) takes
 * focus itself, and the drawer's return would pull it back over it. A drawer is a modal over the page, so ANY change of page while it is open closes it without
 * returning focus to the button and moves focus to the main region (a person's or a team's link inside a pinned body, a pin's author: 1.2 D6;
 * `useCloseOnPageChange`), never leaving it covering the profile it led to. The thread link keeps the list's URL keys only from the Threads tab
 * (`listRouteSearch`): the Search tab's `scope` and `page` are not the list's.
 */
export default function PinnedPanel({ room, className }: { room: RoomResponse; className?: string }) {
  const { t } = useTranslation();
  const [opened, setOpened] = useState(false);
  const [returnFocus, setReturnFocus] = useState(true);
  // A link inside the drawer that leaves the page (a person's, a team's) closes it and the main region takes the focus (`useCloseOnPageChange`).
  const noteOpened = useCloseOnPageChange(opened, () => {
    setReturnFocus(false);
    setOpened(false);
  });
  const pins = useRoomPins(room.id);
  const label = pins.data ? t("rooms.pins.button", { count: pins.data.items.length }) : t("rooms.pins.buttonNoCount");
  return (
    <>
      <Button className={className} variant="default" size="xs" leftSection={<IconPin size={16} />} aria-label={label} title={label} aria-haspopup="dialog" onClick={() => {
          setReturnFocus(true);
          noteOpened();
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
  const { data, isLoading, error } = useRoomPins(room.id);
  const workflow = useRoomWorkflow(room.id);
  const { unpin, busyId, failure } = useUnpin(room.id);
  const mayUnpin = canPin(room);

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
            <PinCard
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
