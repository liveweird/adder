import { useParams } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { Group, Paper, Stack, Text, Title } from "@mantine/core";
import { useQuery } from "@tanstack/react-query";
import { IconMessages } from "@tabler/icons-react";
import { getRoomWorkflow, type RoomWorkflow } from "../api/rooms";
import EmptyState from "../components/EmptyState";
import ErrorAlert from "../components/ErrorAlert";
import LoadingBlock from "../components/LoadingBlock";
import RoomPage from "../components/RoomPage";
import StatusPill from "../components/StatusPill";
import { statusHue } from "../utils/workflowColours";

/** One row of the summary: the statuses of a category, as pills (the dot and the label carry the meaning, never the colour alone). */
function StatusRow({ label, statuses }: { label: string; statuses: RoomWorkflow["statuses"] }) {
  if (statuses.length === 0) return null;
  return (
    <Group gap="xs" role="group" aria-label={label}>
      <Text size="sm" c="dimmed" w={64}>
        {label}
      </Text>
      {statuses.map((status) => (
        <StatusPill key={status.key} color={statusHue(status.colour)} dot>
          {status.label}
        </StatusPill>
      ))}
    </Group>
  );
}

function WorkflowSummary({ roomId }: { roomId: number }) {
  const { t } = useTranslation();
  const { data, isLoading, error } = useQuery({ queryKey: ["rooms", "workflow", roomId], queryFn: () => getRoomWorkflow(roomId) });
  if (isLoading) return <LoadingBlock />;
  if (!data) return <ErrorAlert error={error} title={t("rooms.home.workflowLoadFailed")} />;
  return (
    <Stack gap="xs">
      <StatusRow label={t("rooms.home.open")} statuses={data.statuses.filter((status) => status.category === "OPEN")} />
      <StatusRow label={t("rooms.home.closed")} statuses={data.statuses.filter((status) => status.category === "CLOSED")} />
      <Text size="sm" c="dimmed">
        {data.fields.length === 0
          ? t("rooms.home.noFields")
          : t("rooms.home.fields", { fields: data.fields.map((field) => field.label).join(", ") })}
      </Text>
    </Stack>
  );
}

/**
 * `/rooms/:id`: the room's header and tabs, and — until threads arrive — a placeholder with the workflow the room copied from its
 * template, as pills. (The three-pane thread view replaces the body.)
 */
export default function RoomHome() {
  const { t } = useTranslation();
  const { id } = useParams();
  return (
    <RoomPage idParam={id} tab="home">
      {(room) => (
        <>
          <EmptyState icon={IconMessages} label={t("rooms.home.threadsLabel")} hint={t("rooms.home.threadsHint")} />
          <Paper withBorder p="md" maw={720}>
            <Stack gap="sm">
              <Title order={3} size="h4">
                {t("rooms.home.workflowTitle")}
              </Title>
              <WorkflowSummary roomId={room.id} />
            </Stack>
          </Paper>
        </>
      )}
    </RoomPage>
  );
}
