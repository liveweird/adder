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
 * The frame every page of one room shares: the load triage (a missing room and one the caller may not read are the same 404),
 * the compact header — ONE wrapping row (`PageHeader compact`): a back icon, the name, the member count and an Archived badge, the tabs, and the "Pinned (n)" button that opens the pinned panel beside the "About this room" popover (the description, only when there is one) — so the conversation gets the height. `fill` (an open thread on the Threads tab) bounds the page to the viewport below the shell header in a wide main area (`RoomPage.module.css`; below 62rem the page scrolls), so the page's own parts share what the room header leaves instead of a measured constant. `children` renders the open page's body once
 * the room has loaded. The room reads under `["rooms", "detail", id]`, so any `["rooms"]` refresh updates it. A member who has the room open
 * marks it seen (`useRoomSeen`); an ADMIN who is not a member has no read state and sends nothing.
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
        <PageHeader
          compact
          title={room.name}
          documentTitle={tab === "home" ? room.name : `${t(`rooms.tabs.${tab}`)} · ${room.name}`}
          description={
            <Group gap="xs" wrap="nowrap">
              <Text span size="sm">
                {t("rooms.memberCount", { count: room.memberCount })}
              </Text>
              {room.archived && <Badge color="gray">{t("rooms.archivedBadge")}</Badge>}
            </Group>
          }
          backTo={{ to: roomsPath, label: t("rooms.backToRooms") }}
          toolbar={<RoomTabs id={room.id} active={tab} />}
          actions={
            <>
              <PinnedPanel room={room} />
              <RoomAbout description={room.description} />
            </>
          }
        />
        {children(room)}
      </Stack>
    </Box>
  );
}
