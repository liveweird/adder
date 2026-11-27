import { type ReactNode } from "react";
import { useTranslation } from "react-i18next";
import { Center, Group, Paper, Stack, Title } from "@mantine/core";
import { useDocumentTitle } from "../hooks/useDocumentTitle";
import BrandLogo from "./BrandLogo";
import VersionStamp from "./VersionStamp";
import classes from "./AuthCard.module.css";

interface Props {
  /** The current task (e.g. "Sign in"). */
  title: string;
  children: ReactNode;
}

/**
 * The shared scaffold of the unauthenticated pages: a quiet brand-tinted canvas and one
 * responsive task card. The compact lockup identifies the product without competing with
 * the page title, while the version stays outside the form surface.
 */
export default function AuthCard({ title, children }: Props) {
  const { t } = useTranslation();
  useDocumentTitle(title);

  return (
    <Center component="main" className={classes.canvas}>
      <Stack gap="sm" className={classes.shell}>
        <Paper className={classes.card} withBorder>
          <Stack gap="lg">
            <Group gap="sm" className={classes.brand}>
              <BrandLogo size={38} />
              <Title order={2} size="h3" fw={650}>{t("appShell.brand")}</Title>
            </Group>
            <Title order={3} className={classes.title}>
              {title}
            </Title>
            {children}
          </Stack>
        </Paper>
        <Group justify="center">
          <VersionStamp ta="center" />
        </Group>
      </Stack>
    </Center>
  );
}
