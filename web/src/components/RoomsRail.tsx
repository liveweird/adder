import { useId } from "react";
import { Anchor, Avatar, Box, Divider, Group, NavLink, Switch, Text, ThemeIcon, Tooltip, VisuallyHidden } from "@mantine/core";
import { IconAlertCircle, IconAt, IconHash } from "@tabler/icons-react";
import { Link as RouterLink } from "react-router-dom";
import { useTranslation } from "react-i18next";
import type { RoomListItem } from "../api/rooms";
import { RAIL_PAGE_SIZE, type useRailRooms } from "../hooks/useRailRooms";
import { roomPath, roomsPath } from "../utils/roomLinks";
import { loadErrorMessage } from "../utils/saveError";
import { initialsOf } from "../utils/text";
import { unreadBadge } from "../utils/unread";
import classes from "../theme.module.css";
import FormErrorAlert from "./FormErrorAlert";
import UnreadCue, { UnreadAnchor } from "./UnreadCue";

const NO_MENTIONS: ReadonlyMap<number, number> = new Map();

/**
 * The caller's rooms under the sidebar's "All rooms" leaf: one link per room (its name; `aria-current` while open), the archived
 * ones dimmed under a divider once the "Show archived" toggle is on, a compact empty/error state and a "more rooms" link when a
 * list outgrows the rail. In the icon rail each room is an initials avatar whose accessible name is the room's name, inside a
 * right-hand tooltip (the nav leaves' rule). The shell owns the queries and the active-room resolution; this only draws them.
 *
 * Unread (0.6.0): a room the server says is new (`unread`) and that is not the open one carries a dot, and a room with unseen notices
 * (`mentions`: the count per room from the bell's query) an `@` count. Neither changes the link's NAME, which stays the room's name (tests, the
 * palette and e2e find it by name): what is new is the link's DESCRIPTION (`aria-describedby` on a visually hidden text), so the marks are
 * never colour or shape alone. The icon rail shows one dot for either and puts the words in the tooltip.
 */
export default function RoomsRail({
  rooms,
  showArchived,
  onShowArchivedChange,
  collapsed,
  activeTo,
  onNavigate,
  mentions = NO_MENTIONS,
}: {
  rooms: ReturnType<typeof useRailRooms>;
  showArchived: boolean;
  onShowArchivedChange: (next: boolean) => void;
  /** The desktop icon rail (labels hidden). */
  collapsed: boolean;
  /** The nav's active path (the shell resolves it across the leaves and these rooms). */
  activeTo: string | null;
  /** A link click closes the mobile drawer. */
  onNavigate: () => void;
  /** The caller's unseen notices per room id (`utils/unread.ts` `unseenByRoom`). */
  mentions?: ReadonlyMap<number, number>;
}) {
  const { t } = useTranslation();
  const noteBase = useId();
  const { active, archived } = rooms;

  // Archived rooms are dimmed through the theme's dimmed ink (AA-tested), never through opacity, which would break the contrast.
  const renderRoom = (room: RoomListItem, dimmed = false) => {
    const to = roomPath(room.id);
    const current = to === activeTo;
    const isNew = room.unread && !current;
    const notices = mentions.get(room.id) ?? 0;
    const note = [isNew && t("rooms.rail.unread"), notices > 0 && t("rooms.rail.notices", { count: notices })].filter(Boolean).join(". ");
    const noteId = note === "" ? undefined : `${noteBase}-${room.id}`;
    const noteText = noteId !== undefined && <VisuallyHidden id={noteId}>{note}</VisuallyHidden>;
    if (collapsed) {
      return (
        <Box key={room.id}>
          <Tooltip label={note === "" ? room.name : `${room.name}: ${note}`} position="right" withinPortal>
            <NavLink
              component={RouterLink}
              to={to}
              active={current}
              aria-current={current ? "page" : undefined}
              aria-label={room.name}
              aria-describedby={noteId}
              leftSection={
                <UnreadAnchor show={note !== ""} ring={false}>
                  <Avatar size={24} radius="xl" variant="light" color="gray">
                    {initialsOf(room.name)}
                  </Avatar>
                </UnreadAnchor>
              }
              className={classes.railLink}
              c={dimmed ? "dimmed" : undefined}
              onClick={onNavigate}
            />
          </Tooltip>
          {noteText}
        </Box>
      );
    }
    return (
      <Box key={room.id}>
        <NavLink
          component={RouterLink}
          to={to}
          active={current}
          aria-current={current ? "page" : undefined}
          aria-describedby={noteId}
          label={room.name}
          leftSection={<IconHash size={16} stroke={1.5} />}
          rightSection={
            note === "" ? undefined : (
              <Group gap={6} wrap="nowrap" aria-hidden="true">
                {isNew && <UnreadCue />}
                {notices > 0 && (
                  <UnreadCue size="xs" icon={<IconAt size={10} />}>{unreadBadge(notices)}</UnreadCue>
                )}
              </Group>
            )
          }
          c={dimmed ? "dimmed" : undefined}
          onClick={onNavigate}
        />
        {noteText}
      </Box>
    );
  };

  const error = active.error ?? (showArchived ? archived.error : null);
  const activeItems = active.data?.items ?? [];
  const archivedItems = showArchived ? (archived.data?.items ?? []) : [];
  const more = (active.data?.total ?? 0) > RAIL_PAGE_SIZE || (showArchived && (archived.data?.total ?? 0) > RAIL_PAGE_SIZE);

  return (
    <Box>
      {error && collapsed && (
        <Tooltip label={loadErrorMessage(error, t)} position="right" withinPortal>
          <ThemeIcon variant="light" color="red" size="lg" mx="auto" role="img" aria-label={t("rooms.rail.loadFailed")}>
            <IconAlertCircle size={18} />
          </ThemeIcon>
        </Tooltip>
      )}
      {error && !collapsed && (
        <FormErrorAlert p="xs" mx={4}>
          <Text size="xs">{t("rooms.rail.loadFailed")}</Text>
        </FormErrorAlert>
      )}
      {!error && !collapsed && active.isLoading && (
        <Text size="xs" c="dimmed" px={12} py={4}>
          {t("common.loading")}
        </Text>
      )}
      {!error && !collapsed && active.data && activeItems.length === 0 && (
        <Text size="xs" c="dimmed" px={12} py={4}>
          {t("rooms.rail.empty")}
        </Text>
      )}
      {activeItems.map((room) => renderRoom(room))}
      {archivedItems.length > 0 && (
        <Box role="group" aria-label={t("rooms.rail.archivedGroup")}>
          <Divider my={4} mx={8} />
          {archivedItems.map((room) => renderRoom(room, true))}
        </Box>
      )}
      {more && !collapsed && (
        <Anchor component={RouterLink} to={roomsPath} size="xs" px={12} py={4} display="block" onClick={onNavigate}>
          {t("rooms.rail.more")}
        </Anchor>
      )}
      {!collapsed && (
        <Switch
          size="xs"
          px={12}
          py={4}
          label={t("rooms.rail.showArchived")}
          checked={showArchived}
          onChange={(event) => onShowArchivedChange(event.currentTarget.checked)}
        />
      )}
    </Box>
  );
}
