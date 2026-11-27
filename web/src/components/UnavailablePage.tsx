import { Button, Stack, ThemeIcon } from "@mantine/core";
import { IconArrowLeft, IconMapOff } from "@tabler/icons-react";
import { useTranslation } from "react-i18next";
import { Link } from "react-router-dom";
import PageHeader from "./PageHeader";
import classes from "./UnavailablePage.module.css";

export default function UnavailablePage({ title, description }: { title: string; description: string }) {
  const { t } = useTranslation();
  return (
    <Stack gap="md" align="flex-start" className={classes.panel}>
      <ThemeIcon variant="light" size="xl" radius="md" aria-hidden="true">
        <IconMapOff size={23} />
      </ThemeIcon>
      <PageHeader title={title} description={description} />
      <Button component={Link} to="/" variant="default" leftSection={<IconArrowLeft size={16} aria-hidden="true" />}>
        {t("common.notFound.backHome")}
      </Button>
    </Stack>
  );
}
