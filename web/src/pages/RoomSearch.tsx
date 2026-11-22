import { useCallback } from "react";
import { useParams } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { Stack } from "@mantine/core";
import { useQueryClient } from "@tanstack/react-query";
import { searchRoom } from "../api/search";
import type { RoomResponse } from "../api/rooms";
import RoomPage from "../components/RoomPage";
import RoomTabHeader from "../components/RoomTabHeader";
import SearchBody from "../components/SearchBody";
import { useRoomWorkflow } from "../hooks/useRoomWorkflow";
import { roomKeys } from "../utils/roomKeys";

/** The page body of one loaded room: the search box and the hits in this room, each status drawn with the room's workflow, as the thread list does. */
function RoomSearchBody({ room }: { room: RoomResponse }) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const workflow = useRoomWorkflow(room.id);
  // The search answering 404 means the room is gone or the caller no longer reads it: re-read the room, which then shows not-found.
  const onGone = useCallback(() => void queryClient.invalidateQueries({ queryKey: roomKeys.detail(room.id) }), [queryClient, room.id]);
  return (
    <Stack gap="md">
      <RoomTabHeader title={t("search.room.title")} description={t("search.room.description")} />
      {/* Keyed by the room: moving to another room (its record already cached, so this body is not unmounted) must not show the previous room's hits
          as the placeholder of the new search. */}
      <SearchBody
        key={room.id}
        label={t("search.room.label")}
        initialLabel={t("search.room.start")}
        baseKey={roomKeys.search(room.id)}
        fetchPage={(query) => searchRoom(room.id, query)}
        statuses={workflow.data?.statuses}
        showRoom={false}
        onGone={onGone}
      />
    </Stack>
  );
}

/** `/rooms/:id/search`: search one room's thread titles and messages. */
export default function RoomSearch() {
  const { id } = useParams();
  return (
    <RoomPage idParam={id} tab="search">
      {(room) => <RoomSearchBody room={room} />}
    </RoomPage>
  );
}
