import type { ReactNode } from "react";
import { useId } from "react";
import { useTranslation } from "react-i18next";
import { Stack, Text, Title } from "@mantine/core";
import { useAdmin } from "../auth";
import { getUserId } from "../api/session";
import type { RoomResponse } from "../api/rooms";
import type { ThreadResponse } from "../api/threads";
import { useRoomWorkflow } from "../hooks/useRoomWorkflow";
import { browserTimeZone, formatDateTime } from "../utils/formatDate";
import { fillSlots, slot } from "../utils/slots";
import { isThreadSteward } from "../utils/threadAccess";
import LoadingBlock from "./LoadingBlock";
import PersonLink from "./PersonLink";
import ThreadDetails from "./ThreadDetails";
import ThreadHashtags from "./ThreadHashtags";
import ThreadHistory from "./ThreadHistory";
import ThreadOwnerSelect from "./ThreadOwnerSelect";
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
 * The thread's side panel holds ownership, custom fields, tags, hashtags and history. Its fields share the header's workflow
 * query. The panel folds into the "Details" toggle in a narrow main area (`ThreadPane.module.css`).
 */
export default function ThreadSidePanel({ room, thread, showHistory = true }: { room: RoomResponse; thread: ThreadResponse; showHistory?: boolean }) {
  const { t } = useTranslation();
  const admin = useAdmin();
  const workflow = useRoomWorkflow(room.id);
  const steward = isThreadSteward({ room, thread, userId: getUserId(), admin });
  const zone = browserTimeZone();
  const waiting = workflow.data ? null : workflow.isError ? (
    <Text size="sm" c="dimmed">
      {t("threads.side.unavailable")}
    </Text>
  ) : (
    <LoadingBlock py="sm" />
  );

  return (
    <Stack gap="md">
      <Section title={t("threads.side.owner")}>
        <ThreadOwnerSelect room={room} thread={thread} steward={steward} />
      </Section>
      <Section title={t("threads.side.details")}>
        {workflow.data ? <ThreadDetails room={room} thread={thread} workflow={workflow.data} /> : waiting}
        <Stack gap={2}>
          <Text size="xs" c="dimmed">
            {fillSlots(t("threads.pane.started", { name: slot("name"), when: formatDateTime(thread.createdAt, zone) }), {
              name: <PersonLink id={thread.createdById} name={thread.createdByName} inherit />,
            })}
          </Text>
          <Text size="xs" c="dimmed">
            {t("threads.pane.lastActivity", { when: formatDateTime(thread.lastActivityAt, zone) })}
          </Text>
        </Stack>
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
        {showHistory && <ThreadHistory roomId={room.id} number={thread.number} />}
      </Section>
    </Stack>
  );
}
