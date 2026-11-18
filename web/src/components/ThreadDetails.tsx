import { useEffect, useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import { Button, Stack, Text } from "@mantine/core";
import type { RoomResponse, RoomWorkflow } from "../api/rooms";
import type { ThreadResponse } from "../api/threads";
import { threadEditBlock } from "../utils/threadAccess";
import ThreadDetailsForm from "./ThreadDetailsForm";
import ThreadFieldValue from "./ThreadFieldValue";

/**
 * The Details section: the room's active fields with the thread's values, read-only (`ThreadFieldValue`), and an "Edit details" button that swaps
 * in the editor (`ThreadDetailsForm`: the title and the same fields). A closed thread and an archived room refuse the write, so there the
 * button is replaced by the reason; an editor already open when that happens stays, disabled, with its draft. When the editor ends, focus returns to
 * the button that opened it.
 */
export default function ThreadDetails({ room, thread, workflow }: { room: RoomResponse; thread: ThreadResponse; workflow: RoomWorkflow }) {
  const { t } = useTranslation();
  const [editing, setEditing] = useState(false);
  const editRef = useRef<HTMLButtonElement>(null);
  const wasEditing = useRef(false);
  useEffect(() => {
    if (wasEditing.current && !editing) editRef.current?.focus();
    wasEditing.current = editing;
  }, [editing]);
  const block = threadEditBlock(room, thread);

  if (editing) {
    return <ThreadDetailsForm room={room} thread={thread} fields={workflow.fields} workflowRevision={workflow.revision} onDone={() => setEditing(false)} />;
  }
  return (
    <Stack gap="sm">
      {workflow.fields.length === 0 ? (
        <Text size="sm" c="dimmed">
          {t("threads.details.noFields")}
        </Text>
      ) : (
        <Stack component="dl" gap="xs" m={0}>
          {workflow.fields.map((field) => (
            <div key={field.key}>
              <Text component="dt" size="xs" c="dimmed">
                {field.label}
              </Text>
              <Text component="dd" size="sm" m={0} style={{ overflowWrap: "anywhere" }}>
                <ThreadFieldValue field={field} value={thread.fields[field.key]} userNames={thread.fieldUserNames} />
              </Text>
            </div>
          ))}
        </Stack>
      )}
      {block === null ? (
        <Button ref={editRef} variant="default" size="xs" onClick={() => setEditing(true)} style={{ alignSelf: "flex-start" }}>
          {t("threads.details.edit")}
        </Button>
      ) : (
        <Text size="xs" c="dimmed">
          {t(block === "archived" ? "threads.details.archivedNote" : "threads.details.closedNote")}
        </Text>
      )}
    </Stack>
  );
}
