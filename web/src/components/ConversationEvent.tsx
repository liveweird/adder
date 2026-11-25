import { Group, Stack, Text } from "@mantine/core";
import { IconHistory } from "@tabler/icons-react";
import { useTranslation } from "react-i18next";
import type { ThreadEvent } from "../api/threads";
import { formatDateTime } from "../utils/formatDate";
import { presentThreadEvent } from "./threadEventPresentation";
import classes from "./MessageList.module.css";

export default function ConversationEvent({ event, zone }: { event: ThreadEvent; zone: string }) {
  const { t } = useTranslation();
  const item = presentThreadEvent(event, t);
  return (
    <li className={classes.event}>
      <Group gap="xs" wrap="nowrap" align="flex-start">
        <IconHistory size={15} className={classes.eventIcon} aria-hidden />
        <Stack gap={2}>
          <Text size="xs" fw={600}>{item.title}</Text>
          {item.lines.map((line) => <Text key={line} size="xs">{line}</Text>)}
          <Text size="xs" c="dimmed">
            {item.who} · <time dateTime={new Date(item.when).toISOString()}>{formatDateTime(item.when, zone)}</time>
          </Text>
        </Stack>
      </Group>
    </li>
  );
}
