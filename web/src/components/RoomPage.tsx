import type { ReactNode } from "react";
import { useTranslation } from "react-i18next";
import { Badge, Group, Stack, Text } from "@mantine/core";
import { useQuery } from "@tanstack/react-query";
import { ApiError } from "../api/http";
import { getRoom, type RoomResponse } from "../api/rooms";
import { parseRoomId, roomsPath } from "../utils/roomLinks";
import { loadErrorMessage } from "../utils/saveError";
import EditPageLoadState from "./EditPageLoadState";
import PageHeader from "./PageHeader";
import PinnedPanel from "./PinnedPanel";
import RoomTabs, { type RoomTabKey } from "./RoomTabs";
import { roomKeys } from "../utils/roomKeys";

/**
 * The frame every page of one room shares: the load triage (a missing room and one the caller may not read are the same 404),
 * the header (name, description, member count, an Archived badge, the "Pinned (n)" button that opens the pinned panel) and the tabs. `children` renders the open page's body once
 * the room has loaded. The room reads under `["rooms", "detail", id]`, so any `["rooms"]` refresh updates it.
 */
export default function RoomPage({ idParam, tab, children }: { idParam: string | undefined; tab: RoomTabKey; children: (room: RoomResponse) => ReactNode }) {
  const { t } = useTranslation();
  // An id that is not a plain positive number names no room: the not-found state, without a request.
  const id = parseRoomId(idParam);
  const { data: room, isLoading, error } = useQuery({ queryKey: roomKeys.detail(id), queryFn: () => getRoom(id!), enabled: id !== null });
  // A 404 wins over data cached from an earlier visit (the room was deleted, or the caller left it, since).
  const notFound = id === null || (error instanceof ApiError && error.status === 404);

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
    <Stack gap="md">
      <PageHeader
        title={room.name}
        description={
          <>
            {room.description && (
              <Text span display="block">
                {room.description}
              </Text>
            )}
            <Text span display="block">
              {t("rooms.memberCount", { count: room.memberCount })}
            </Text>
          </>
        }
        backTo={{ to: roomsPath, label: t("rooms.backToRooms") }}
        actions={
          <Group gap="xs">
            {room.archived && <Badge color="gray">{t("rooms.archivedBadge")}</Badge>}
            <PinnedPanel room={room} />
          </Group>
        }
      />
      <RoomTabs id={room.id} active={tab} />
      {children(room)}
    </Stack>
  );
}
