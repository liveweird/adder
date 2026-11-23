import type { ReactNode } from "react";
import { useTranslation } from "react-i18next";
import { Badge, Box, Group, Stack, Text } from "@mantine/core";
import { useQuery } from "@tanstack/react-query";
import { ApiError } from "../api/http";
import { getRoom, getRoomAccess, type RoomResponse } from "../api/rooms";
import { useRoomSeen } from "../hooks/useRoomSeen";
import { parseRoomId, roomsPath } from "../utils/roomLinks";
import { loadErrorMessage } from "../utils/saveError";
import EditPageLoadState from "./EditPageLoadState";
import PageHeader from "./PageHeader";
import PinnedPanel from "./PinnedPanel";
import RoomAbout from "./RoomAbout";
import RoomAccessPreview, { RoomEnrollmentAction } from "./RoomAccessPreview";
import RoomTabs, { type RoomTabKey } from "./RoomTabs";
import classes from "./RoomPage.module.css";
import { roomKeys } from "../utils/roomKeys";

/**
 * Loads the room, renders its navigation and actions, and supplies the room to the active page. The Threads view uses compact
 * navigation and passes `fill` for an open conversation so the workspace can share the viewport with the room header.
 * The room detail query is shared. Viewing an accessible room marks it seen for members only.
 */
export default function RoomPage({ idParam, tab, fill = false, children }: { idParam: string | undefined; tab: RoomTabKey; fill?: boolean; children: (room: RoomResponse) => ReactNode }) {
  const { t } = useTranslation();
  // An id that is not a plain positive number names no room: the not-found state, without a request.
  const id = parseRoomId(idParam);
  const { data: room, isLoading, error } = useQuery({ queryKey: roomKeys.detail(id), queryFn: () => getRoom(id!), enabled: id !== null });
  const contentDenied = error instanceof ApiError && error.status === 404;
  const readableOutsideMembership = room?.role === null && room.accessType !== "RESTRICTED_HIDDEN";
  const access = useQuery({
    queryKey: roomKeys.access(id),
    queryFn: () => getRoomAccess(id!),
    enabled: id !== null && (contentDenied || readableOutsideMembership),
  });
  // Opening the room (any of its pages) is looking at it: a member's "room seen" goes out on arrival and while the rail says it is new again.
  useRoomSeen(room && !contentDenied ? room.id : null, room !== undefined && !contentDenied && room.role !== null);

  if (contentDenied) {
    const accessNotFound = access.error instanceof ApiError && access.error.status === 404;
    // TanStack Query retains the last good data when a refetch fails. The fresh error must win or a deleted preview leaks from the cache.
    if (!access.error && access.data) return <RoomAccessPreview room={access.data} />;
    return (
      <EditPageLoadState
        isLoading={access.isLoading}
        message={accessNotFound ? t("rooms.notFound") : loadErrorMessage(access.error, t)}
        backTo={roomsPath}
        backLabel={t("rooms.backToRooms")}
        title={t("rooms.title")}
      />
    );
  }

  if (!room || id === null) {
    return (
      <EditPageLoadState
        isLoading={isLoading}
        message={id === null ? t("rooms.notFound") : loadErrorMessage(error, t)}
        backTo={roomsPath}
        backLabel={t("rooms.backToRooms")}
        title={t("rooms.title")}
      />
    );
  }

  const threads = tab === "home";
  const navigation = <RoomTabs id={room.id} active={tab} />;

  return (
    <Box className={classes.frame}>
      <Stack gap={threads ? "sm" : "lg"} className={fill ? classes.fill : threads ? undefined : classes.subpage}>
        <Box className={threads ? classes.header : `${classes.header} ${classes.subpageHeader}`}>
          <PageHeader
            compact
            title={room.name}
            documentTitle={threads ? room.name : `${t(`rooms.tabs.${tab}`)} · ${room.name}`}
            description={
              <Group gap="xs" wrap="nowrap">
                <Text span size="sm" className={classes.memberCount}>
                  {t("rooms.memberCount", { count: room.memberCount })}
                </Text>
                {room.archived && <Badge color="gray">{t("rooms.archivedBadge")}</Badge>}
              </Group>
            }
            backTo={{ to: roomsPath, label: t("rooms.backToRooms") }}
            toolbar={threads ? navigation : undefined}
            actions={
              <>
                <PinnedPanel room={room} className={classes.pinButton} />
                <RoomAbout description={room.description} />
              </>
            }
          />
          {!threads && navigation}
        </Box>
        {readableOutsideMembership && access.data && <RoomEnrollmentAction room={access.data} compact />}
        {children(room)}
      </Stack>
    </Box>
  );
}
