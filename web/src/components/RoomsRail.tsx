import { useId } from "react";
import { Anchor, Avatar, Box, Divider, Group, NavLink, Switch, Text, ThemeIcon, Tooltip, VisuallyHidden } from "@mantine/core";
import { IconAlertCircle, IconArchive, IconAt, IconFolder, IconHash } from "@tabler/icons-react";
import { Link as RouterLink } from "react-router-dom";
import { useTranslation } from "react-i18next";
import type { RoomOrganization } from "../api/roomOrganization";
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

function personalSections(organization: RoomOrganization, items: RoomListItem[]) {
  const placements = new Map(organization.rooms.map((room, index) => [room.roomId, { groupId: room.groupId, index }]));
  const buckets = new Map<string | null, RoomListItem[]>(organization.groups.map((group) => [group.id, []]));
  const ungrouped: RoomListItem[] = [];
  for (const room of items) {
    const groupId = placements.get(room.id)?.groupId ?? null;
    (buckets.get(groupId) ?? ungrouped).push(room);
  }
  // The rail's active and archived pages arrive separately. Merge their order before rendering each group.
  const rank = (id: number) => placements.get(id)?.index ?? Number.MAX_SAFE_INTEGER;
  const inOrder = (rooms: RoomListItem[]) => rooms.sort((a, b) => rank(a.id) - rank(b.id) || b.lastActivityAt - a.lastActivityAt || a.id - b.id);
  return [
    ...organization.groups.map((group) => ({ ...group, items: inOrder(buckets.get(group.id) ?? []) })),
    { id: null, name: null, items: inOrder(ungrouped) },
  ].filter((section) => section.items.length > 0);
}

/**
 * The caller's rooms in personal order, with group labels whenever there are saved groups. The archived toggle reveals dimmed
 * links in their saved positions. The icon rail uses an initials avatar with the room name and the same accessible description.
 *
 * Unread (0.6.0): a room the server says is new (`unread`) and that is not the open one carries a dot, and a room with unseen notices
 * (`mentions`: the count per room from the bell's query) an `@` count. Neither changes the link's NAME, which stays the room's name (tests, the
 * palette and e2e find it by name): what is new is the link's DESCRIPTION (`aria-describedby` on a visually hidden text), so the marks are
 * never colour or shape alone. The icon rail shows one dot for either and puts the words in the tooltip.
 */
export default function RoomsRail({
  rooms,
  organization,
  showArchived,
  onShowArchivedChange,
  collapsed,
  activeTo,
  onNavigate,
  mentions = NO_MENTIONS,
}: {
  rooms: ReturnType<typeof useRailRooms>;
  organization?: RoomOrganization;
  showArchived: boolean;
  onShowArchivedChange: (next: boolean) => void;
  /** The desktop icon rail (labels hidden). */
  collapsed: boolean;
  /** The nav's active path (the shell resolves it across the leaves and these rooms). */
  activeTo: string | null;
  /** A link click closes the mobile drawer. */
  onNavigate?: (to: string) => void;
  /** The caller's unseen notices per room id (`utils/unread.ts` `unseenByRoom`). */
  mentions?: ReadonlyMap<number, number>;
}) {
  const { t } = useTranslation();
  const noteBase = useId();
  const { active, archived } = rooms;

  // Archived rooms are dimmed through the theme's dimmed ink (AA-tested), never through opacity, which would break the contrast.
  const renderRoom = (room: RoomListItem) => {
    const to = roomPath(room.id);
    const current = to === activeTo;
    const isNew = room.unread && !current;
    const notices = mentions.get(room.id) ?? 0;
    const hasActivity = isNew || notices > 0;
    const note = [room.archived && t("rooms.archivedBadge"), isNew && t("rooms.rail.unread"), notices > 0 && t("rooms.rail.notices", { count: notices })]
      .filter(Boolean).join(". ");
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
                <UnreadAnchor show={hasActivity} ring={false}>
                  <Avatar size={24} radius="xl" variant="light" color="gray">
                    {initialsOf(room.name)}
                  </Avatar>
                </UnreadAnchor>
              }
              className={classes.railLink}
              c={room.archived ? "dimmed" : undefined}
              onClick={onNavigate ? () => onNavigate(to) : undefined}
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
          title={room.name}
          className={classes.roomRailLink}
          leftSection={room.archived ? <IconArchive size={16} stroke={1.5} aria-hidden="true" /> : <IconHash size={16} stroke={1.5} aria-hidden="true" />}
          rightSection={
            hasActivity ? (
              <Group gap={6} wrap="nowrap" aria-hidden="true">
                {isNew && <UnreadCue />}
                {notices > 0 && (
                  <UnreadCue size="xs" icon={<IconAt size={10} />}>{unreadBadge(notices)}</UnreadCue>
                )}
              </Group>
            ) : undefined
          }
          c={room.archived ? "dimmed" : undefined}
          onClick={onNavigate ? () => onNavigate(to) : undefined}
        />
        {noteText}
      </Box>
    );
  };

  const error = active.error ?? (showArchived ? archived.error : null);
  const activeItems = active.data?.items ?? [];
  const archivedItems = showArchived ? (archived.data?.items ?? []) : [];
  const more = (active.data?.total ?? 0) > RAIL_PAGE_SIZE || (showArchived && (archived.data?.total ?? 0) > RAIL_PAGE_SIZE);
  const hasLayout = organization && Array.isArray(organization.groups) && Array.isArray(organization.rooms);
  const hasGroups = hasLayout && organization.groups.length > 0;
  const sections = hasLayout && (hasGroups || organization.rooms.length > 0)
    ? personalSections(organization, [...activeItems, ...archivedItems])
    : null;

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
      {!error && !collapsed && active.data && activeItems.length === 0 && archivedItems.length === 0 && (
        <Text size="xs" c="dimmed" px={12} py={4}>
          {t("rooms.rail.empty")}
        </Text>
      )}
      {sections === null ? (
        <>
          {activeItems.map(renderRoom)}
          {archivedItems.length > 0 && (
            <Box role="group" aria-label={t("rooms.rail.archivedGroup")}>
              <Divider my={4} mx={8} />
              {archivedItems.map(renderRoom)}
            </Box>
          )}
        </>
      ) : sections.map((section) => {
        const label = section.name ?? t("rooms.organization.ungrouped");
        const showHeading = !collapsed && hasGroups;
        return (
          <Box key={section.id ?? "ungrouped"} role="group" aria-label={label}>
            {showHeading && (
              <Group gap={6} px={12} pt={8} pb={2} wrap="nowrap">
                <IconFolder size={14} aria-hidden="true" />
                <Text size="xs" fw={650} c="dimmed" truncate title={label}>{label}</Text>
              </Group>
            )}
            {section.items.map(renderRoom)}
          </Box>
        );
      })}
      {more && !collapsed && (
        <Anchor component={RouterLink} to={roomsPath} size="xs" px={12} py={4} display="block" onClick={onNavigate ? () => onNavigate(roomsPath) : undefined}>
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
