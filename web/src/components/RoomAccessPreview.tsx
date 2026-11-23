import { useState } from "react";
import { useNavigate } from "react-router-dom";
import { Alert, Badge, Button, Group, Paper, Stack, Text } from "@mantine/core";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { IconDoorEnter, IconSend } from "@tabler/icons-react";
import { useTranslation } from "react-i18next";
import {
  joinPublicRoom,
  requestRoomMembership,
  type RoomAccessPreview as RoomAccessPreviewData,
} from "../api/rooms";
import { FORM_MAX_WIDTH } from "../utils/layout";
import { refreshQueriesAfterMutation } from "../utils/queryRefresh";
import { roomPath, roomsPath } from "../utils/roomLinks";
import { roomKeys } from "../utils/roomKeys";
import { saveErrorMessage } from "../utils/saveError";
import { showSuccessToast } from "../utils/toast";
import FormErrorAlert from "./FormErrorAlert";
import PageHeader from "./PageHeader";
import RoomAccessBadge from "./RoomAccessBadge";

/** The one viable enrollment action and its current state, shared by the safe preview and an outside admin's content view. */
export function RoomEnrollmentAction({ room, compact = false }: { room: RoomAccessPreviewData; compact?: boolean }) {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [failure, setFailure] = useState<unknown>(null);
  const publicRoom = room.accessType === "PUBLIC";
  const enrollment = useMutation({
    mutationFn: () => (publicRoom ? joinPublicRoom(room.id) : requestRoomMembership(room.id)),
    onSuccess: async () => {
      setFailure(null);
      if (publicRoom) {
        showSuccessToast(t("rooms.access.joined"));
        queryClient.removeQueries({ queryKey: roomKeys.access(room.id) });
        navigate(roomPath(room.id), { replace: true });
        await refreshQueriesAfterMutation(queryClient, ["rooms"]);
        return;
      }
      showSuccessToast(t("rooms.access.requested"));
      await queryClient.invalidateQueries({ queryKey: roomKeys.access(room.id) });
    },
    onError: setFailure,
  });
  const pending = room.requestStatus === "PENDING";
  const actionLabel = publicRoom
    ? t("rooms.access.join")
    : room.requestStatus === "REJECTED"
      ? t("rooms.access.requestAgain")
      : t("rooms.access.request");
  const stateCopy = room.requestStatus
    ? t(pending ? "rooms.access.pending" : "rooms.access.rejected")
    : t(`rooms.access.${room.accessType}.description`);

  const error = failure !== null && (
    <FormErrorAlert title={t(publicRoom ? "rooms.access.joinFailed" : "rooms.access.requestFailed")}>
      {saveErrorMessage(failure, t, {
        retryAfter: true,
        forbidden: publicRoom ? "rooms.access.joinFailed" : "rooms.access.requestFailed",
        notFound: "rooms.notFound",
        conflict: "rooms.access.full",
        failedStatus: "common.error.actionFailedStatus",
        failed: "common.error.actionFailed",
      })}
    </FormErrorAlert>
  );
  const button = !pending && (
    <Button
      style={compact ? undefined : { alignSelf: "flex-start" }}
      size={compact ? "xs" : undefined}
      leftSection={publicRoom ? <IconDoorEnter size={16} aria-hidden="true" /> : <IconSend size={16} aria-hidden="true" />}
      loading={enrollment.isPending}
      onClick={() => enrollment.mutate()}
    >
      {actionLabel}
    </Button>
  );

  if (compact) {
    return (
      <Paper withBorder p="sm">
        <Stack gap="sm">
          <Group justify="space-between" align="center" wrap="wrap">
            <Text size="sm" c="dimmed">
              {stateCopy}
            </Text>
            {button}
          </Group>
          {error}
        </Stack>
      </Paper>
    );
  }

  return (
    <>
      {room.requestStatus && (
        <Alert color={pending ? "blue" : "gray"} variant="light">
          {stateCopy}
        </Alert>
      )}
      {error}
      {button}
    </>
  );
}

/** The safe room landing shown before membership. It exposes metadata and the one viable enrollment action, never room content. */
export default function RoomAccessPreview({ room }: { room: RoomAccessPreviewData }) {
  const { t } = useTranslation();

  return (
    <Stack gap="lg">
      <PageHeader
        title={room.name}
        backTo={{ to: roomsPath, label: t("rooms.access.previewBack") }}
        description={
          <Group gap="xs">
            <RoomAccessBadge accessType={room.accessType} />
            {room.archived && <Badge color="gray">{t("rooms.archivedBadge")}</Badge>}
          </Group>
        }
      />
      <Paper withBorder p="xl" maw={FORM_MAX_WIDTH}>
        <Stack gap="md">
          {room.description && <Text>{room.description}</Text>}
          <Text size="sm" c="dimmed">
            {t(`rooms.access.${room.accessType}.description`)}
          </Text>
          <RoomEnrollmentAction room={room} />
        </Stack>
      </Paper>
    </Stack>
  );
}
