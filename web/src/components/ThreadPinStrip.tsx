import { useId, useMemo, useRef, type KeyboardEvent } from "react";
import { useTranslation } from "react-i18next";
import { Group, Stack, Text, UnstyledButton } from "@mantine/core";
import { IconChevronDown, IconChevronUp, IconPinFilled } from "@tabler/icons-react";
import type { RoomResponse } from "../api/rooms";
import { useRoomPins } from "../hooks/useRoomPins";
import { useUnpin } from "../hooks/useUnpin";
import type { RoomPin } from "../api/pins";
import { canPin, messagePinsOf } from "../utils/pins";
import { messageExcerpt } from "../utils/messageExcerpt";
import FormErrorAlert from "./FormErrorAlert";
import PinCard from "./PinCard";
import classes from "./ThreadPinStrip.module.css";

/**
 * The pins of the OPEN thread, always at hand (1.2 D5): a strip under the thread header that exists only while the thread has pinned messages.
 * Collapsed it is one line, "Pinned: <the newest pin's first line> (+n)", a button (`aria-expanded`); expanded it lists the thread's message
 * pins IN PLACE, in the flow under the header, newest first (`PinCard compact`: author, time, a two-line excerpt, who pinned, "Show in thread" and
 * "Unpin"), capped and scrolling inside; the pane gives the height back out of the conversation (`ThreadPane.module.css`). Expanded or not is one
 * switch per person per browser (`adder.viewSettings.threadPane.pinsOpen`), held by the pane (`open`, `onOpenChange`) because the pane's layout
 * answers to it. The pins are the room's ONE pins query, so a pin from another browser appears or goes with `room.pin_changed` and no reload; the
 * thread's own pin is not here (its badge and the pin button say it), and neither are the pins of other threads (the room header's "Pinned (n)"
 * drawer lists the whole room). "Show in thread" is the 0.5.0 jump (`?message=`): `onShow` hands the pane the message's id, which re-jumps only
 * when the URL already names it. Escape folds the list and keeps the focus on the button. After an Unpin the focus is never lost: it goes to the
 * button while pins remain and to the pane (`focusPane`) when the strip goes with the last one.
 */
export default function ThreadPinStrip({
  room,
  threadNumber,
  open,
  onOpenChange,
  onShow,
  focusPane,
}: {
  room: RoomResponse;
  threadNumber: number;
  open: boolean;
  onOpenChange: (open: boolean) => void;
  onShow: (messageId: number) => void;
  focusPane: () => void;
}) {
  const { t } = useTranslation();
  const listId = useId();
  const { data } = useRoomPins(room.id);
  const { unpin, busyId, failure } = useUnpin(room.id);
  const pins = useMemo(() => messagePinsOf(data?.items, threadNumber), [data, threadNumber]);
  const toggleRef = useRef<HTMLButtonElement>(null);
  const newest = pins[0]?.message;
  if (!newest) return null;

  const more = pins.length - 1;
  const mayUnpin = canPin(room);
  const excerpt = messageExcerpt(newest, true);
  const collapsed = t("threads.pinStrip.collapsed", { excerpt });
  const moreText = more > 0 ? t("threads.pinStrip.more", { count: more }) : "";
  const label = open ? t("threads.pinStrip.expanded", { count: pins.length }) : t(more > 0 ? "threads.pinStrip.collapsedMore" : "threads.pinStrip.collapsed", { excerpt, count: more });
  // Escape folds the list away and keeps the focus on the strip's own button.
  function foldOnEscape(event: KeyboardEvent) {
    if (event.key !== "Escape" || !open) return;
    event.stopPropagation();
    onOpenChange(false);
    toggleRef.current?.focus();
  }
  async function removePin(pin: RoomPin) {
    const removed = await unpin(pin);
    if (!removed) return;
    // The row the focus was on is gone: the button while pins remain, the pane when the strip itself goes with the last pin.
    if (pins.length > 1) toggleRef.current?.focus();
    else focusPane();
  }
  return (
    <div role="region" aria-label={t("threads.pinStrip.aria")} className={classes.strip} data-pin-strip={open ? "open" : "closed"} onKeyDown={foldOnEscape}>
      <UnstyledButton ref={toggleRef} className={classes.toggle} aria-expanded={open} aria-controls={listId} aria-label={label} onClick={() => onOpenChange(!open)}>
        <Group gap={6} wrap="nowrap">
          <IconPinFilled size={14} aria-hidden="true" style={{ flex: "none" }} />
          {open ? (
            <Text size="xs" fw={600} style={{ flex: 1 }}>
              {label}
            </Text>
          ) : (
            <>
              <Text size="xs" truncate="end" miw={0} style={{ flex: "0 1 auto" }}>
                {collapsed}
              </Text>
              {moreText !== "" && (
                <Text size="xs" c="dimmed" style={{ flex: "none" }}>
                  {moreText}
                </Text>
              )}
              <span style={{ flex: 1 }} />
            </>
          )}
          {open ? <IconChevronUp size={14} aria-hidden="true" style={{ flex: "none" }} /> : <IconChevronDown size={14} aria-hidden="true" style={{ flex: "none" }} />}
        </Group>
      </UnstyledButton>
      {open && (
        <div id={listId} className={classes.list}>
          <Stack gap="xs">
            {failure && <FormErrorAlert>{failure}</FormErrorAlert>}
            <Stack component="ul" gap="sm" m={0} p={0} style={{ listStyle: "none" }}>
              {pins.map((pin) => (
                <PinCard key={pin.id} room={room} pin={pin} compact onUnpin={mayUnpin ? () => void removePin(pin) : undefined} busy={busyId === pin.id} onNavigate={() => onShow(pin.message?.id ?? 0)} />
              ))}
            </Stack>
          </Stack>
        </div>
      )}
    </div>
  );
}
