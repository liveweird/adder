import { Stack } from "@mantine/core";
import { IconMessages } from "@tabler/icons-react";
import { useTranslation } from "react-i18next";
import EmptyState from "../components/EmptyState";
import PageHeader from "../components/PageHeader";

/**
 * The landing page: a placeholder pointing at the rooms rail. The threads and messages live in a room (0.3.0); this
 * page stays an empty state until a later version gives it content (e.g. an unread overview).
 */
export default function Home() {
  const { t } = useTranslation();
  return (
    <Stack gap="md">
      <PageHeader title={t("home.title")} />
      <EmptyState icon={IconMessages} label={t("home.empty.label")} hint={t("home.empty.hint")} />
    </Stack>
  );
}
