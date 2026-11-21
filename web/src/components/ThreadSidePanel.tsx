import type { ReactNode } from "react";
import { useId } from "react";
import { useTranslation } from "react-i18next";
import { Stack, Text, Title } from "@mantine/core";
import { useAdmin } from "../auth";
import { getUserId } from "../api/session";
import type { RoomResponse } from "../api/rooms";
import type { ThreadResponse } from "../api/threads";
import { useRoomWorkflow } from "../hooks/useRoomWorkflow";
import { isThreadSteward } from "../utils/threadAccess";
import LoadingBlock from "./LoadingBlock";
import ThreadDetails from "./ThreadDetails";
import ThreadHashtags from "./ThreadHashtags";
import ThreadHistory from "./ThreadHistory";
import ThreadOwnerSelect from "./ThreadOwnerSelect";
import ThreadStatusControl from "./ThreadStatusControl";
import ThreadTagsPanel from "./ThreadTagsPanel";

/** One panel section: its h4 (the pane's title is the h3; web-internals.md "The room view") and its body. */
function Section({ title, anchor, children }: { title: string; anchor?: string; children: ReactNode }) {
  const id = useId();
  return (
    <Stack gap="xs" role="group" aria-labelledby={id} data-section={anchor}>
      <Title order={4} size="h5" id={id}>
        {title}
      </Title>
      {children}
    </Stack>
  );
}

/**
 * The thread's side panel: Status, Owner, Details, Tags (the curated labels, with their editor, 1.2 D1), Hashtags (only when the thread has any: filter links, 1.2 A8) and History, each an h4 under the pane's h3. Status and Owner are acted on by whoever D1
 * allows (`isThreadSteward`, UX only), Details is the room's fields with an editor, History the server's event log. The sections that need the
 * room's workflow (the status options, the fields) wait for it under the shared `["rooms", "workflow", roomId]` read; the pane above already
 * says when that read failed. The panel is what folds into the "Details" toggle in a narrow main area (`ThreadPane.module.css`).
 */
export default function ThreadSidePanel({ room, thread }: { room: RoomResponse; thread: ThreadResponse }) {
  const { t } = useTranslation();
  const admin = useAdmin();
  const workflow = useRoomWorkflow(room.id);
  const steward = isThreadSteward({ room, thread, userId: getUserId(), admin });
  const waiting = workflow.data ? null : workflow.isError ? (
    <Text size="sm" c="dimmed">
      {t("threads.side.unavailable")}
    </Text>
  ) : (
    <LoadingBlock py="sm" />
  );

  return (
    <Stack gap="lg">
      <Section title={t("threads.side.status")}>
        {workflow.data ? <ThreadStatusControl room={room} thread={thread} statuses={workflow.data.statuses} steward={steward} /> : waiting}
      </Section>
      <Section title={t("threads.side.owner")}>
        <ThreadOwnerSelect room={room} thread={thread} steward={steward} />
      </Section>
      <Section title={t("threads.side.details")}>
        {workflow.data ? <ThreadDetails room={room} thread={thread} workflow={workflow.data} /> : waiting}
      </Section>
      <Section title={t("threads.side.tags")} anchor="tags">
        <ThreadTagsPanel room={room} thread={thread} steward={steward} />
      </Section>
      {thread.hashtags.length > 0 && (
        <Section title={t("threads.side.hashtags")} anchor="hashtags">
          <ThreadHashtags roomId={room.id} number={thread.number} hashtags={thread.hashtags} labelled={false} />
        </Section>
      )}
      <Section title={t("threads.side.history")}>
        <ThreadHistory roomId={room.id} number={thread.number} />
      </Section>
    </Stack>
  );
}
