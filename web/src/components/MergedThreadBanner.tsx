import { Alert, Anchor, Group, Text } from "@mantine/core";
import { IconGitMerge } from "@tabler/icons-react";
import { useTranslation } from "react-i18next";
import { Link, useLocation } from "react-router-dom";
import { threadPath, withListParams } from "../utils/roomLinks";

/** Read-only source notice, with a durable path to the canonical conversation. */
export default function MergedThreadBanner({ roomId, targetNumber }: { roomId: number; targetNumber: number }) {
  const { t } = useTranslation();
  const location = useLocation();
  return (
    <Alert color="gray" icon={<IconGitMerge size={18} aria-hidden />}>
      <Group gap="xs" justify="space-between">
        <Text size="sm">{t("threadLifecycle.merged.notice", { number: targetNumber })}</Text>
        <Anchor component={Link} to={withListParams(threadPath(roomId, targetNumber), location.search)} size="sm" fw={600}>
          {t("threadLifecycle.merged.openTarget", { number: targetNumber })}
        </Anchor>
      </Group>
    </Alert>
  );
}
