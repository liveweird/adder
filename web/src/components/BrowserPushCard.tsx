import { useState } from "react";
import { useTranslation } from "react-i18next";
import { useMutation, useQuery } from "@tanstack/react-query";
import { Button, Group, Paper, Stack, Text, ThemeIcon, Title } from "@mantine/core";
import { IconBell, IconBellOff } from "@tabler/icons-react";
import { browserPushSupported, currentBrowserPushId, disableBrowserPush, enableBrowserPush, getBrowserPushConfig } from "../api/browserPush";
import { CONTENT_MAX_WIDTH } from "../utils/layout";
import FormErrorAlert from "./FormErrorAlert";
import classes from "./BrowserPushCard.module.css";

export default function BrowserPushCard() {
  const { t } = useTranslation();
  const supported = browserPushSupported();
  const [denied, setDenied] = useState(() => supported && Notification.permission === "denied");
  const config = useQuery({ queryKey: ["browser-push-config"], queryFn: getBrowserPushConfig, enabled: supported });
  const enable = useMutation({
    mutationFn: enableBrowserPush,
    onSuccess: async (registered) => {
      if (registered === null) setDenied(Notification.permission === "denied");
      await config.refetch();
    },
  });
  const disable = useMutation({ mutationFn: disableBrowserPush, onSuccess: () => config.refetch() });
  const id = config.data ? config.data.enabledId : currentBrowserPushId();
  const key = config.data?.publicKey;
  const unavailable = supported && config.isSuccess && !key;
  const error = config.isError || enable.isError || disable.isError;

  function requestPush(publicKey: string) {
    // Start the permission request inside the browser's click gesture, before the mutation runs.
    const permission = Notification.permission === "default"
      ? Notification.requestPermission()
      : Promise.resolve(Notification.permission);
    enable.mutate({ publicKey, permission });
  }

  return (
    <Paper withBorder maw={CONTENT_MAX_WIDTH} className={classes.card}>
      <Stack gap="md">
        <Group justify="space-between" align="flex-start" gap="md" className={classes.header}>
          <Group align="flex-start" gap="sm" wrap="nowrap" className={classes.identity}>
            <ThemeIcon variant="light" color="adder" size="lg" radius="md" className={classes.icon}>
              <IconBell size={19} aria-hidden="true" />
            </ThemeIcon>
            <Stack gap={4}>
              <Title order={3} size="h4">{t("notifications.browserPush.title")}</Title>
              <Text size="sm" c="dimmed">{t("notifications.browserPush.description")}</Text>
            </Stack>
          </Group>
          {id !== null && <Button variant="default" leftSection={<IconBellOff size={16} />} loading={disable.isPending}
            onClick={() => disable.mutate(id)}>{t("notifications.browserPush.disable")}</Button>}
          {id === null && key && !denied && <Button leftSection={<IconBell size={16} />} loading={enable.isPending}
            onClick={() => requestPush(key)}>{t("notifications.browserPush.enable")}</Button>}
        </Group>
        {!supported && <Text size="sm">{t("notifications.browserPush.unsupported")}</Text>}
        {unavailable && <Text size="sm">{t("notifications.browserPush.unavailable")}</Text>}
        {denied && key && <Text size="sm">{t("notifications.browserPush.denied")}</Text>}
        {error && <FormErrorAlert>{t("notifications.browserPush.failed")}</FormErrorAlert>}
      </Stack>
    </Paper>
  );
}
