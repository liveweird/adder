import type { ReactNode } from "react";
import { useTranslation } from "react-i18next";
import { Badge, Box, Group, Stack, Text } from "@mantine/core";
import { useQuery } from "@tanstack/react-query";
import { ApiError } from "../api/http";
import { getRoom, type RoomResponse } from "../api/rooms";
import { useRoomSeen } from "../hooks/useRoomSeen";
import { parseRoomId, roomsPath } from "../utils/roomLinks";
import { loadErrorMessage } from "../utils/saveError";
import EditPageLoadState from "./EditPageLoadState";
import PageHeader from "./PageHeader";
import PinnedPanel from "./PinnedPanel";
import RoomAbout from "./RoomAbout";
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
  // A 404 wins over data cached from an earlier visit (the room was deleted, or the caller left it, since).
  const notFound = id === null || (error instanceof ApiError && error.status === 404);
  // Opening the room (any of its pages) is looking at it: a member's "room seen" goes out on arrival and while the rail says it is new again.
  useRoomSeen(room && !notFound ? room.id : null, room !== undefined && room.role !== null);

  if (!room || notFound) {
    return (
      <EditPageLoadState
        isLoading={isLoading}
        message={notFound ? t("rooms.notFound") : loadErrorMessage(error, t)}
        backTo={roomsPath}
        backLabel={t("rooms.backToRooms")}
        title={t("rooms.title")}
      />
    );
  }

  return (
    <Box className={classes.frame}>
      <Stack gap="sm" className={fill ? classes.fill : undefined}>
        <Box className={tab === "home" ? classes.header : undefined}>
          <PageHeader
            compact
            title={room.name}
            documentTitle={tab === "home" ? room.name : `${t(`rooms.tabs.${tab}`)} · ${room.name}`}
            description={
              <Group gap="xs" wrap="nowrap">
                <Text span size="sm" className={tab === "home" ? classes.memberCount : undefined}>
                  {t("rooms.memberCount", { count: room.memberCount })}
                </Text>
                {room.archived && <Badge color="gray">{t("rooms.archivedBadge")}</Badge>}
              </Group>
            }
            backTo={{ to: roomsPath, label: t("rooms.backToRooms") }}
            toolbar={<RoomTabs id={room.id} active={tab} />}
            actions={
              <>
                <PinnedPanel room={room} className={tab === "home" ? classes.pinButton : undefined} />
                <RoomAbout description={room.description} />
              </>
            }
          />
        </Box>
        {children(room)}
      </Stack>
    </Box>
  );
}
