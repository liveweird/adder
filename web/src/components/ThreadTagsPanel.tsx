import { useEffect, useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import { Button, Stack, Text } from "@mantine/core";
import { IconTags } from "@tabler/icons-react";
import type { RoomResponse } from "../api/rooms";
import type { ThreadResponse } from "../api/threads";
import { threadTagsBlock } from "../utils/threadAccess";
import { retiredTagNames } from "../utils/threadTags";
import TagPills from "./TagPills";
import ThreadTagsEditor from "./ThreadTagsEditor";

/**
 * The Tags section of the side panel: the thread's pills (a retired tag struck through and said in words), and - unless the room is archived or
 * the thread is closed and the caller is no steward (`threadTagsBlock`, UX only: the server decides) - the "Edit tags" button that swaps them for
 * the grouped picker. When the editor ends, focus returns to the button that opened it.
 */
export default function ThreadTagsPanel({ room, thread, steward }: { room: RoomResponse; thread: ThreadResponse; steward: boolean }) {
  const { t } = useTranslation();
  const [editing, setEditing] = useState(false);
  const editRef = useRef<HTMLButtonElement>(null);
  const wasEditing = useRef(false);
  useEffect(() => {
    if (wasEditing.current && !editing) editRef.current?.focus();
    wasEditing.current = editing;
  }, [editing]);
  const block = threadTagsBlock(room, thread, steward);
  if (editing && block !== "merged") return <ThreadTagsEditor room={room} thread={thread} steward={steward} onClose={() => setEditing(false)} />;
  return (
    <Stack gap="xs">
      <TagPills tags={thread.tags} none={t("threads.tags.none")} />
      {retiredTagNames(thread.tags).length > 0 && (
        <Text size="xs" c="dimmed">
          {t("threads.tags.retiredNote")}
        </Text>
      )}
      {block === null ? (
        <Button ref={editRef} variant="default" size="xs" style={{ alignSelf: "flex-start" }} leftSection={<IconTags size={14} />} onClick={() => setEditing(true)}>
          {t("threads.tags.edit")}
        </Button>
      ) : block !== "merged" && (
        <Text size="xs" c="dimmed">
          {t(block === "archived" ? "threads.tags.archivedNote" : "threads.tags.closedNote")}
        </Text>
      )}
    </Stack>
  );
}
