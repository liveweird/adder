import { Anchor, Avatar, Box, Divider, NavLink, Switch, Text, ThemeIcon, Tooltip } from "@mantine/core";
import { IconAlertCircle, IconHash } from "@tabler/icons-react";
import { Link as RouterLink } from "react-router-dom";
import { useTranslation } from "react-i18next";
import type { RoomListItem } from "../api/rooms";
import { RAIL_PAGE_SIZE, type useRailRooms } from "../hooks/useRailRooms";
import { roomPath, roomsPath } from "../utils/roomLinks";
import { loadErrorMessage } from "../utils/saveError";
import { initialsOf } from "../utils/text";
import classes from "../theme.module.css";
import FormErrorAlert from "./FormErrorAlert";

/**
 * The caller's rooms under the sidebar's "All rooms" leaf: one link per room (its name; `aria-current` while open), the archived
 * ones dimmed under a divider once the "Show archived" toggle is on, a compact empty/error state and a "more rooms" link when a
 * list outgrows the rail. In the icon rail each room is an initials avatar whose accessible name is the room's name, inside a
 * right-hand tooltip (the nav leaves' rule). The shell owns the queries and the active-room resolution; this only draws them.
 */
export default function RoomsRail({
  rooms,
  showArchived,
  onShowArchivedChange,
  collapsed,
  activeTo,
  onNavigate,
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
}) {
  const { t } = useTranslation();
  const { active, archived } = rooms;

  // Archived rooms are dimmed through the theme's dimmed ink (AA-tested), never through opacity, which would break the contrast.
  const renderRoom = (room: RoomListItem, dimmed = false) => {
    const to = roomPath(room.id);
    const current = to === activeTo;
    if (collapsed) {
      return (
        <Tooltip key={room.id} label={room.name} position="right" withinPortal>
          <NavLink
            component={RouterLink}
            to={to}
            active={current}
            aria-current={current ? "page" : undefined}
            aria-label={room.name}
            leftSection={
              <Avatar size={24} radius="xl" variant="light" color="gray">
                {initialsOf(room.name)}
              </Avatar>
            }
            className={classes.railLink}
            c={dimmed ? "dimmed" : undefined}
            onClick={onNavigate}
          />
        </Tooltip>
      );
    }
    return (
      <NavLink
        key={room.id}
        component={RouterLink}
        to={to}
        active={current}
        aria-current={current ? "page" : undefined}
        label={room.name}
        leftSection={<IconHash size={16} stroke={1.5} />}
        c={dimmed ? "dimmed" : undefined}
        onClick={onNavigate}
      />
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
